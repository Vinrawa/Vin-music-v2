package com.vinmusic.recommendation

import com.vinmusic.data.db.InteractionSignal
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
        return shifts.maxByOrNull { abs(it.amount) }?.takeIf { abs(it.amount) >= 6 }
    }
}

data class TasteShift(val dimension: String, val amount: Int)

object MusicDnaInsights {
    private const val RECENT_WINDOW_MS = 14L * 24L * 60L * 60L * 1000L

    fun build(
        signals: List<InteractionSignal>,
        nowMs: Long = System.currentTimeMillis()
    ): MusicDnaComparison? {
        val enriched = signals.filter(::hasUsableFeatures)
        val core = makeEra(enriched) ?: return null
        val current = makeEra(enriched.filter { it.lastPlayedAt >= nowMs - RECENT_WINDOW_MS })
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

    private fun makeEra(signals: List<InteractionSignal>): MusicDnaEra? {
        if (signals.isEmpty()) return null

        var totalWeight = 0.0
        var energy = 0.0
        var valence = 0.0
        var danceability = 0.0
        var acousticness = 0.0
        var tempo = 0.0
        var interactions = 0

        signals.forEach { signal ->
            val weight = engagementWeight(signal)
            totalWeight += weight
            energy += signal.energy * weight
            valence += signal.valence * weight
            danceability += signal.danceability * weight
            acousticness += signal.acousticness * weight
            tempo += signal.tempo * weight
            interactions += interactionCount(signal)
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
    private fun engagementWeight(signal: InteractionSignal): Double {
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
