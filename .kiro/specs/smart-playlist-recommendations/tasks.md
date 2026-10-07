# Implementation Plan: Smart Playlist Recommendations

## Overview

This implementation plan breaks down the smart playlist recommendation system into discrete, actionable tasks. The system analyzes user listening behavior, local playlists, and interaction signals to build a comprehensive taste profile, then generates diverse, high-quality music recommendations through Genre Mixes, Quick Picks, Smart Radio, and Related Songs.

The design uses **Kotlin** with existing Android/Compose patterns. All tasks build incrementally on existing `RecommendationManager.kt` and `RecommendationRepository.kt` files with integration into `DiscoverScreen.kt`.

## Tasks

- [ ] 1. Implement Genre Mix generation system
  - [ ] 1.1 Add getGenreMixes() function to RecommendationRepository
    - Create `suspend fun getGenreMixes(): List<SpotifyMix>` in RecommendationRepository.kt
    - Implement 15-minute disk cache using SharedPreferences with key "genre_mixes_v2"
    - Return cached SpotifyMix list if valid (not expired)
    - Build TasteProfile using `RecommendationManager.buildTasteProfile(db)`
    - _Requirements: 6.1, 6.2, 10.1, 10.2_
  
  - [ ] 1.2 Implement parallel genre query fetching with coroutines
    - Iterate through all 7 genre configs (Lofi, Rap/Hip-Hop, Bollywood, Punjabi Folk, Pop, Indie, Rock)
    - For each genre, launch async coroutine to fetch candidates using `InnerTube.search()` with genre-specific queries
    - Use `coroutineScope { async { ... }.awaitAll() }` pattern for parallel execution
    - Flatten and deduplicate results by videoId across all genres
    - _Requirements: 6.2, 10.4_
  
  - [ ] 1.3 Apply content quality filters to all genre mix candidates
    - Filter out compilation tracks using `RecommendationManager.isCompilationTrack()`
    - Filter out non-music videos using `RecommendationManager.isNonMusicVideo()`
    - Filter out unofficial content using `RecommendationManager.isUnofficialContent()`
    - Remove songs from skipped tracks and skipped artists (check TasteProfile)
    - _Requirements: 6.3, 9.1, 9.2, 9.3, 9.4, 9.5_
  
  - [ ] 1.4 Score candidates using taste similarity with target mood filtering
    - For each candidate, infer metadata using `RecommendationManager.inferMetadata()`
    - Filter by target mood: only include songs where `meta.mood == GENRE_CONFIGS[genre].targetMood`
    - Calculate taste similarity score using `RecommendationManager.calculateTasteSimilarity(meta, tasteDNA)`
    - Apply official content bonus (+0.15 if `meta.isOfficial`)
    - Store as Pair<VideoItem, Double> for sorting
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5, 5.6, 5.7, 6.3_
  
  - [ ] 1.5 Apply artist diversity filter to genre mixes
    - Sort scored candidates by similarity score descending
    - Implement artist diversity loop: max 2 songs per artist per genre mix
    - Track artist counts using HashMap<String, Int> with normalized artist names
    - Use `RecommendationManager.normalizeArtistName()` for consistent artist matching
    - Take top 12 songs per genre after diversity filtering
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5, 6.5_
  
  - [ ] 1.6 Create SpotifyMix objects with gradient colors and save to cache
    - Map each genre to SpotifyMix with id, title, description from GENRE_CONFIGS
    - Wrap selected songs as RecommendedSong with score, source="genre_mix", reason
    - Apply gradient colors from GENRE_CONFIGS (gradientStartHex, gradientEndHex)
    - Save complete List<SpotifyMix> to SharedPreferences cache with timestamp
    - Return list of genre mixes (up to 7 genres)
    - _Requirements: 6.4, 10.1, 10.6_

- [ ] 2. Checkpoint - Verify Genre Mix implementation
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 3. Integrate Genre Mixes into DiscoverScreen UI
  - [ ] 3.1 Add Genre Mix display section to DiscoverScreen
    - Add "Your Genre Mixes" section header below screen title
    - Create horizontal LazyRow for genre mix cards
    - Each card displays genre title, description, gradient background
    - Use `RoundedCornerShape(16.dp)` with gradient from mix.gradientStartHex/gradientEndHex
    - Show first 4 song thumbnails in grid overlay (2x2)
    - _Requirements: 6.1, 6.4_
  
  - [ ] 3.2 Handle Genre Mix card click navigation
    - On mix card click, navigate to new GenreMixDetailScreen composable
    - Pass SpotifyMix object as navigation parameter
    - Display full 12-song list with play controls
    - Add "Play All" button to play entire mix as queue
    - Add individual song click handlers to play song with mix as queue
    - _Requirements: 6.1_
  
  - [ ] 3.3 Load Genre Mixes on DiscoverScreen launch
    - Call `RecommendationRepository.getGenreMixes()` in LaunchedEffect on screen load
    - Store result in `var genreMixes by remember { mutableStateOf<List<SpotifyMix>>(emptyList()) }`
    - Show loading indicator while fetching (CircularProgressIndicator)
    - Handle empty state gracefully (show "No genre mixes available" message)
    - _Requirements: 6.6, 10.1, 10.2_

- [ ] 4. Checkpoint - Verify UI integration
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 5. Add analytical logging for recommendation quality tracking
  - [ ] 5.1 Log Genre Mix generation metrics
    - Log total candidates fetched per genre before filtering
    - Log candidates remaining after content filters
    - Log final mix size after artist diversity filter
    - Use `Log.d("VIN_GENRE_MIX", "...")` with structured messages
    - _Requirements: 10.4_
  
  - [ ] 5.2 Log cache hit/miss events for performance monitoring
    - Log cache hits with age: "Loaded genre_mixes from cache (age: X min)"
    - Log cache misses: "Cache expired/empty, generating fresh genre mixes"
    - Log cache save operations: "Saved N genre mixes to disk cache"
    - _Requirements: 10.1, 10.2, 10.3_

- [ ] 6. Optional: Add performance optimizations
  - [ ] 6.1 Implement parallel query batching for faster fetches
    - Group genre queries into batches of 3 concurrent requests
    - Add configurable batch size based on network conditions
    - Monitor and log total fetch time per batch
    - _Requirements: 10.4_
  
  - [ ] 6.2 Add smart prefetching when cache is near expiration
    - Check cache age on app resume/screen navigation
    - If cache age > 12 minutes (80% of expiry), prefetch in background
    - Update cache silently without blocking UI
    - _Requirements: 10.1, 10.2_

- [ ] 7. Optional: Implement analytics integration
  - [ ] 7.1 Track Genre Mix engagement events
    - Log "genre_mix_viewed" event when user opens mix detail screen
    - Log "genre_mix_played" event when user plays song from mix
    - Include genre name, mix position, and song position in event metadata
    - Use existing AnalyticsHelper.kt for event tracking
    - _Requirements: 6.1_
  
  - [ ] 7.2 Track recommendation quality metrics
    - Log "recommendation_accepted" when user likes/plays recommended song
    - Log "recommendation_skipped" when user skips recommended song
    - Include source ("genre_mix", "quick_picks", etc.) and score in metadata
    - Track conversion rate per recommendation source
    - _Requirements: 5.7, 6.1_

## Notes

- Tasks marked with `*` are optional and can be skipped for faster MVP
- Each task references specific requirements for traceability
- Checkpoints ensure incremental validation
- The design already includes comprehensive filtering logic in RecommendationManager.kt
- All genre configs (GENRE_CONFIGS map) already exist in RecommendationManager.kt
- The caching strategy uses SharedPreferences with 15-minute expiry (CACHE_EXPIRY_MS)
- Artist diversity filtering uses existing `normalizeArtistName()` and artist count tracking
- The UI integrations follow existing Compose patterns from DiscoverScreen.kt
- Content quality filters reuse existing functions: `isCompilationTrack()`, `isNonMusicVideo()`, `isUnofficialContent()`
- Taste similarity scoring uses existing `calculateTasteSimilarity()` function (already implemented in RecommendationManager)
- All database access uses existing Room DAO patterns (InteractionSignalDao, PlaylistDao, HistoryDao)

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["1.2"] },
    { "id": 2, "tasks": ["1.3", "1.4"] },
    { "id": 3, "tasks": ["1.5"] },
    { "id": 4, "tasks": ["1.6"] },
    { "id": 5, "tasks": ["3.1"] },
    { "id": 6, "tasks": ["3.2", "3.3"] },
    { "id": 7, "tasks": ["5.1", "5.2"] },
    { "id": 8, "tasks": ["6.1", "6.2", "7.1", "7.2"] }
  ]
}
```
