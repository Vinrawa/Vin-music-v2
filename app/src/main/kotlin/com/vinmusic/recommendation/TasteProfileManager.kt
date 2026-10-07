package com.vinmusic.recommendation

import com.vinmusic.data.db.InteractionSignal
import com.vinmusic.data.db.InteractionSignalDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * Spotify-feature-space taste profile (energy/valence/danceability/acousticness/tempo).
 * Used by MusicDnaScreen to display the user's audio feature preferences.
 *
 * NOTE: This is DIFFERENT from RecommendationManager.TasteProfile which contains
 * topArtists/topGenres/topMoods (the genre/mood/artist preference profile).
 * This class deals with raw audio features; that one deals with categorical preferences.
 */
data class AudioFeatureProfile(
    val energy: Int,
    val valence: Int,
    val danceability: Int,
    val acousticness: Int,
    val tempo: Int
)

@Singleton
class TasteProfileManager @Inject constructor(
    private val signalDao: InteractionSignalDao,
    private val spotifyDao: SpotifyTrackDao,
    private val featureCacheDao: com.vinmusic.data.db.SongFeatureCacheDao
) {

    /**
     * Calculates the user's overall TasteDNA profile based on their listening history.
     * Weights: Likes (+3), Completions (+1), Repeats (+1.2 each), Skips (-2 if < 20s),
     * all decayed by recency (~21-day time constant) so the DNA tracks current taste.
     */
    suspend fun calculateTasteProfile(): AudioFeatureProfile = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        val signals = signalDao.getAll()

        var totalWeight = 0.0
        var wEnergy = 0.0
        var wValence = 0.0
        var wDance = 0.0
        var wAcoustic = 0.0
        var wTempo = 0.0

        val updatedSignals = mutableListOf<InteractionSignal>()

        for (sig in signals) {
            // Skip signals with blank metadata — can't infer anything useful
            if (sig.title.isNullOrBlank() || sig.author.isNullOrBlank()) continue

            try {
                val songKey = RecommendationManager.generateSongKey(sig.author, sig.title)
                val realCache = try { featureCacheDao.get(songKey) } catch (_: Exception) { null }

                var needsUpdate = false

                // 1. Fetch accurate ground-truth features from Spotify database if available
                val spotifyTrack = try { RecommendationManager.findSpotifyTrackFuzzy(spotifyDao, sig.title, sig.author) } catch (_: Exception) { null }
                if (spotifyTrack != null) {
                    sig.energy = spotifyTrack.energy.coerceIn(10, 98)
                    sig.valence = spotifyTrack.valence.coerceIn(10, 98)
                    sig.danceability = spotifyTrack.dance.coerceIn(10, 98)
                    sig.acousticness = spotifyTrack.acoustic.coerceIn(5, 95)
                    sig.tempo = spotifyTrack.tempo.coerceIn(40, 220)
                    needsUpdate = true
                } else {
                    // 2. High-precision inference from title, artist, genre, mood
                    val fakeItem = com.vinmusic.innertube.VideoItem(sig.videoId, sig.title, sig.author, sig.durationText)
                    val inferred = RecommendationManager.inferMetadata(fakeItem)

                    sig.energy = (inferred.energy * 100).toInt().coerceIn(15, 95)
                    sig.valence = when (inferred.mood) {
                        "Sad" -> 25
                        "Chill/Relaxed" -> 45
                        "Romantic" -> 58
                        "Happy" -> 82
                        "Energetic" -> 75
                        "Dark" -> 28
                        else -> 52
                    }
                    sig.danceability = when (inferred.genre) {
                        "Rap/Hip-Hop" -> 82
                        "Punjabi Folk" -> 86
                        "Pop" -> 78
                        "Lofi" -> 35
                        "Sad", "Indie" -> 42
                        "Bollywood" -> if (inferred.mood == "Energetic") 78 else 55
                        else -> 58
                    }
                    sig.acousticness = when (inferred.genre) {
                        "Lofi" -> 72
                        "Indie" -> 66
                        "Sad" -> 58
                        "Bollywood" -> if (inferred.mood == "Romantic") 52 else 32
                        "Rap/Hip-Hop", "Rock" -> 14
                        else -> 32
                    }
                    sig.tempo = inferred.tempo.coerceIn(40, 220)
                    needsUpdate = true
                }

                // 3. If real analyzed audio cache exists, overlay exact measured energy & BPM
                if (realCache != null) {
                    if (realCache.energyReal > 0f) {
                        sig.energy = (realCache.energyReal * 100).toInt().coerceIn(15, 95)
                    }
                    if (realCache.bpmReal in 40f..250f) {
                        sig.tempo = realCache.bpmReal.toInt().coerceIn(40, 220)
                    }
                    needsUpdate = true
                }

                val features = sig
                if (needsUpdate) {
                    updatedSignals.add(sig)
                }

                if (features.energy <= 0) continue

                // Recency decay — recent listens represent current taste; a track
                // untouched for weeks barely moves the needle.
                val ageDays = if (features.lastPlayedAt > 0) {
                    ((now - features.lastPlayedAt) / 86_400_000.0).coerceAtLeast(0.0)
                } else 30.0
                val recency = kotlin.math.exp(-ageDays / 21.0)

                // Calculate weight based on interactions
                var weight = (features.playCount * 0.5) + features.completeCount +
                    features.repeatCount * 1.2
                if (features.isLiked) weight += 3.0
                if (features.isDownloaded) weight += 2.0
                weight -= (features.skip20sCount * 2.0)
                weight *= (0.25 + 0.75 * recency)

                if (weight > 0) {
                    totalWeight += weight
                    wEnergy += features.energy * weight
                    wValence += features.valence * weight
                    wDance += features.danceability * weight
                    wAcoustic += features.acousticness * weight
                    wTempo += features.tempo * weight
                }
            } catch (e: Exception) {
                android.util.Log.e("TasteDNA", "Signal processing error for ${sig.title}: ${e.message}", e)
                continue
            }
        }
        
        // Batch write all updated signals
        if (updatedSignals.isNotEmpty()) {
            try {
                for (sig in updatedSignals) {
                    signalDao.insert(sig)
                }
            } catch (_: Exception) { /* non-critical */ }
        }

        if (totalWeight <= 0) {
            // Fallback default profile if new user
            return@withContext AudioFeatureProfile(60, 50, 60, 20, 120)
        }

        return@withContext AudioFeatureProfile(
            energy = (wEnergy / totalWeight).toInt().coerceIn(0, 100),
            valence = (wValence / totalWeight).toInt().coerceIn(0, 100),
            danceability = (wDance / totalWeight).toInt().coerceIn(0, 100),
            acousticness = (wAcoustic / totalWeight).toInt().coerceIn(0, 100),
            tempo = (wTempo / totalWeight).toInt().coerceIn(0, 255)
        )
    }
}
