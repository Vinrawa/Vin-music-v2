package com.vinmusic.recommendation

import com.vinmusic.data.db.HistoryEntry
import com.vinmusic.data.db.InteractionSignal
import com.vinmusic.innertube.VideoItem
import kotlin.math.abs

/**
 * A transparent comparison between a listener's long-term taste and their
 * recent listening.  This deliberately uses only on-device interaction data:
 * it is useful without an AI service and gives an AI layer factual context
 * rather than invented personality claims.
 */
data class MusicDnaEra(
    val profile: AudioFeatureProfile,
    val trackCount: Int,
    val interactionCount: Int
)

data class MusicDnaComparison(
    val core: MusicDnaEra,
    val currentEra: MusicDnaEra?,
    val confidence: Confidence
) {
    enum class Confidence(val label: String) {
        LEARNING("Learning"),
        EMERGING("Emerging"),
        CONFIDENT("Confident")
    }

    fun strongestShift(): TasteShift? {
        val recent = currentEra ?: return null
        val shifts = listOf(
            TasteShift("energy", recent.profile.energy - core.profile.energy),
            TasteShift("danceability", recent.profile.danceability - core.profile.danceability),
            TasteShift("acoustic sound", recent.profile.acousticness - core.profile.acousticness),
            TasteShift("tempo", recent.profile.tempo - core.profile.tempo)
        )
        return shifts.maxByOrNull { abs(it.amount) }?.takeIf { abs(it.amount) >= 3 }
    }
}

data class TasteShift(val dimension: String, val amount: Int)

object MusicDnaInsights {
    private const val RECENT_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L
    private const val SHORTER_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L

    fun enrichSignalIfNeeded(signal: InteractionSignal): InteractionSignal {
        if (hasUsableFeatures(signal)) return signal
        if (signal.title.isBlank() || signal.author.isBlank()) return signal

        val fakeItem = VideoItem(signal.videoId, signal.title, signal.author, signal.durationText)
        val inferred = RecommendationManager.inferMetadata(fakeItem)

        signal.energy = (inferred.energy * 100).toInt().coerceIn(15, 95)
        signal.valence = when (inferred.mood) {
            "Sad" -> 25
            "Chill/Relaxed" -> 45
            "Romantic" -> 58
            "Happy" -> 82
            "Energetic" -> 75
            "Dark" -> 28
            else -> 52
        }
        signal.danceability = when (inferred.genre) {
            "Rap/Hip-Hop" -> 82
            "Punjabi Folk" -> 86
            "Pop" -> 78
            "Lofi" -> 35
            "Sad", "Indie" -> 42
            "Bollywood" -> if (inferred.mood == "Energetic") 78 else 55
            else -> 58
        }
        signal.acousticness = when (inferred.genre) {
            "Lofi" -> 72
            "Indie" -> 66
            "Sad" -> 58
            "Bollywood" -> if (inferred.mood == "Romantic") 52 else 32
            "Rap/Hip-Hop", "Rock" -> 14
            else -> 32
        }
        signal.tempo = inferred.tempo.coerceIn(40, 220)
        return signal
    }

    fun build(
        signals: List<InteractionSignal>,
        nowMs: Long = System.currentTimeMillis(),
        recentHistory: List<HistoryEntry> = emptyList()
    ): MusicDnaComparison? {
        val usableSignals = signals.filter { it.title.isNotBlank() && it.author.isNotBlank() }
        if (usableSignals.isEmpty()) return null

        val enriched = usableSignals.map { enrichSignalIfNeeded(it) }.filter(::hasUsableFeatures)
        if (enriched.isEmpty()) return null

        val core = makeEra(enriched) ?: return null

        // Current Era: focus on recent listening.
        // If recent playback history exists, weight recent tracks by their actual playback frequency
        // in the 14-day window. This allows the current era to dynamically shift as the user loops new genres.
        val recent14dCutoff = nowMs - RECENT_WINDOW_MS
        val recentHistoryWindow = recentHistory.filter { it.playedAt >= recent14dCutoff }
        val recentHistoryCounts = recentHistoryWindow
            .filter { it.videoId.isNotBlank() }
            .groupingBy { it.videoId }
            .eachCount()

        val recentPool: List<InteractionSignal>
        val recentWeightsMap: Map<String, Int>

        if (recentHistoryCounts.isNotEmpty()) {
            val signalMap = enriched.associateBy { it.videoId }
            val signalsInRecentHistory = recentHistoryCounts.keys.mapNotNull { signalMap[it] }
            if (signalsInRecentHistory.isNotEmpty()) {
                recentPool = signalsInRecentHistory
                recentWeightsMap = recentHistoryCounts
            } else {
                val recent14d = enriched.filter { it.lastPlayedAt >= recent14dCutoff }
                recentPool = if (recent14d.size == enriched.size && enriched.size >= 3) {
                    val weekPool = enriched.filter { it.lastPlayedAt >= nowMs - SHORTER_WINDOW_MS }
                    if (weekPool.isNotEmpty() && weekPool.size < enriched.size) weekPool
                    else enriched.sortedByDescending { it.lastPlayedAt }.take(maxOf(2, (enriched.size * 0.6).toInt()))
                } else {
                    recent14d
                }
                recentWeightsMap = emptyMap()
            }
        } else {
            val recent14d = enriched.filter { it.lastPlayedAt >= recent14dCutoff }
            recentPool = if (recent14d.size == enriched.size && enriched.size >= 3) {
                val weekPool = enriched.filter { it.lastPlayedAt >= nowMs - SHORTER_WINDOW_MS }
                if (weekPool.isNotEmpty() && weekPool.size < enriched.size) weekPool
                else enriched.sortedByDescending { it.lastPlayedAt }.take(maxOf(2, (enriched.size * 0.6).toInt()))
            } else {
                recent14d
            }
            recentWeightsMap = emptyMap()
        }

        val current = makeEra(recentPool, recentWeightsMap)
        val confidence = when {
            core.interactionCount >= 30 -> MusicDnaComparison.Confidence.CONFIDENT
            core.interactionCount >= 10 -> MusicDnaComparison.Confidence.EMERGING
            else -> MusicDnaComparison.Confidence.LEARNING
        }
        return MusicDnaComparison(core, current, confidence)
    }

    private fun hasUsableFeatures(signal: InteractionSignal): Boolean =
        signal.energy in 0..100 &&
            signal.valence in 0..100 &&
            signal.danceability in 0..100 &&
            signal.acousticness in 0..100 &&
            signal.tempo in 40..220

    private fun makeEra(
        signals: List<InteractionSignal>,
        recentWeights: Map<String, Int> = emptyMap()
    ): MusicDnaEra? {
        if (signals.isEmpty()) return null

        var totalWeight = 0.0
        var energy = 0.0
        var valence = 0.0
        var danceability = 0.0
        var acousticness = 0.0
        var tempo = 0.0
        var interactions = 0

        signals.forEach { signal ->
            val recentCount = recentWeights[signal.videoId]
            val weight = engagementWeight(signal, recentCount)
            totalWeight += weight
            energy += signal.energy * weight
            valence += signal.valence * weight
            danceability += signal.danceability * weight
            acousticness += signal.acousticness * weight
            tempo += signal.tempo * weight
            interactions += recentCount ?: interactionCount(signal)
        }

        if (totalWeight <= 0.0) return null
        return MusicDnaEra(
            profile = AudioFeatureProfile(
                energy = (energy / totalWeight).toInt().coerceIn(0, 100),
                valence = (valence / totalWeight).toInt().coerceIn(0, 100),
                danceability = (danceability / totalWeight).toInt().coerceIn(0, 100),
                acousticness = (acousticness / totalWeight).toInt().coerceIn(0, 100),
                tempo = (tempo / totalWeight).toInt().coerceIn(40, 220)
            ),
            trackCount = signals.size,
            interactionCount = interactions
        )
    }

    private fun interactionCount(signal: InteractionSignal): Int =
        signal.playCount + signal.completeCount + signal.repeatCount + signal.skipCount + signal.skip20sCount

    /** Caps repeat-heavy tracks so one obsession cannot define an entire era. */
    private fun engagementWeight(
        signal: InteractionSignal,
        recentCount: Int? = null
    ): Double {
        if (recentCount != null && recentCount > 0) {
            var weight = 1.0 + recentCount.coerceAtMost(15) * 1.5
            if (signal.isLiked) weight += 3.0
            return weight.coerceAtLeast(0.5)
        }
        var weight = 1.0 + signal.playCount.coerceAtMost(10) * 0.5
        weight += signal.completeCount.coerceAtMost(10) * 1.2
        weight += signal.repeatCount.coerceAtMost(8) * 1.6
        weight += signal.searchClickCount.coerceAtMost(6) * 0.8
        if (signal.isLiked) weight += 3.0
        if (signal.isDownloaded) weight += 0.5
        weight -= signal.skip20sCount.coerceAtMost(8) * 1.5
        weight -= signal.skipCount.coerceAtMost(8) * 0.5
        return weight.coerceAtLeast(0.25)
    }
}
