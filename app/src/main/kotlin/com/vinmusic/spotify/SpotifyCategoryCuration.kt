package com.vinmusic.spotify

object SpotifyCategoryCuration {

    data class SpotifyShelfQuery(
        val shelfTitle: String,
        val searchQuery: String
    )

    fun getCuratedShelves(category: String): List<SpotifyShelfQuery> {
        return when (category.trim()) {
            "Happy" -> listOf(
                SpotifyShelfQuery("Happy Hits & Mood Booster", "Spotify Happy Hits official playlist"),
                SpotifyShelfQuery("Feelin' Good & Sunshine", "Spotify Feelin Good playlist"),
                SpotifyShelfQuery("Desi Happy Upbeat", "Spotify Bollywood Happy Upbeat songs playlist")
            )
            "Sad" -> listOf(
                SpotifyShelfQuery("Life Sucks & Heartache", "Spotify Life Sucks official playlist"),
                SpotifyShelfQuery("Sad Songs & Melancholy", "Spotify Sad Songs playlist"),
                SpotifyShelfQuery("Broken Hearted Desi", "Spotify Sad Hindi Songs playlist")
            )
            "Energize" -> listOf(
                SpotifyShelfQuery("Beast Mode & Adrenaline", "Spotify Beast Mode official playlist"),
                SpotifyShelfQuery("Hype & High Energy", "Spotify Hype playlist"),
                SpotifyShelfQuery("Power Workout Tracks", "Spotify Workout Beats playlist")
            )
            "Workout" -> listOf(
                SpotifyShelfQuery("Beast Mode Gym", "Spotify Beast Mode playlist"),
                SpotifyShelfQuery("Workout Hits & Cardio", "Spotify Workout Hits official playlist"),
                SpotifyShelfQuery("Desi Gym Motivation", "Spotify Punjabi Workout Gym songs playlist")
            )
            "Party" -> listOf(
                SpotifyShelfQuery("Club Bangers & Dance", "Spotify Dance Party official playlist"),
                SpotifyShelfQuery("Party Hits & Bass", "Spotify Party Hits playlist"),
                SpotifyShelfQuery("Desi Club Night", "Spotify Bollywood Club Party playlist")
            )
            "Focus" -> listOf(
                SpotifyShelfQuery("Deep Focus & Flow", "Spotify Deep Focus official playlist"),
                SpotifyShelfQuery("Brain Food & Ambient", "Spotify Brain Food playlist"),
                SpotifyShelfQuery("Peaceful Piano Focus", "Spotify Peaceful Piano playlist")
            )
            "Sleep" -> listOf(
                SpotifyShelfQuery("Deep Sleep & Night Rain", "Spotify Deep Sleep official playlist"),
                SpotifyShelfQuery("Night Rain & Soothing", "Spotify Night Rain playlist"),
                SpotifyShelfQuery("Calming Ambient Waves", "Spotify Ambient Chill Sleep playlist")
            )
            "90s Hits" -> listOf(
                SpotifyShelfQuery("90s Bollywood Evergreen", "Spotify 90s Bollywood Evergreen official playlist"),
                SpotifyShelfQuery("All Out 90s Retro", "Spotify All Out 90s official playlist"),
                SpotifyShelfQuery("90s Romantic Melodies", "Spotify 90s Hindi Romantic playlist")
            )
            "Bollywood" -> listOf(
                SpotifyShelfQuery("Bollywood Butter", "Spotify Bollywood Butter official playlist"),
                SpotifyShelfQuery("Mega Bollywood Hits", "Spotify Mega Bollywood Hits playlist"),
                SpotifyShelfQuery("Top Hindi Tracks", "Spotify Top Tracks India Hindi playlist")
            )
            "Lo-fi" -> listOf(
                SpotifyShelfQuery("Lofi Cafe & Chill Beats", "Spotify Lofi Cafe official playlist"),
                SpotifyShelfQuery("Chill Instrumental Lofi", "Spotify Lofi Beats playlist"),
                SpotifyShelfQuery("Bollywood Lofi Vibes", "Spotify Bollywood Lofi Chill playlist")
            )
            "Indie" -> listOf(
                SpotifyShelfQuery("Indie India & Discovery", "Spotify Indie India official playlist"),
                SpotifyShelfQuery("Ultimate Indie", "Spotify Ultimate Indie official playlist"),
                SpotifyShelfQuery("Acoustic Indie Cafe", "Spotify Acoustic Indie playlist")
            )
            "Rap" -> listOf(
                SpotifyShelfQuery("RapCaviar & Hip-Hop", "Spotify RapCaviar official playlist"),
                SpotifyShelfQuery("Desi Hip-Hop Wave", "Spotify Desi Hip Hop playlist"),
                SpotifyShelfQuery("Underground Rap", "Spotify Underground Hip Hop playlist")
            )
            "K-Pop" -> listOf(
                SpotifyShelfQuery("K-Pop ON! Mega Hits", "Spotify K-Pop ON official playlist"),
                SpotifyShelfQuery("K-Pop Fresh Drops", "Spotify K-Pop playlist")
            )
            else -> listOf(
                SpotifyShelfQuery("$category Curated", "Spotify $category top official playlist")
            )
        }
    }
}
