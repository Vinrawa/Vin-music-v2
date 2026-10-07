# Implementation Plan: Pro Genre Enhancements

## Overview

This implementation plan breaks down the Pro Genre Enhancements feature into incremental, file-by-file tasks. Each task is production-safe and ensures the app remains compilable after completion.

**Implementation Strategy**: Modular helpers → Repository integration → UI enhancements → Testing

**Language**: Kotlin (Android)

## Tasks

- [x] 1. Create Core Data Models Module
  - [x] 1.1 Create GenreModels.kt with data classes
    - Create new file `app/src/main/kotlin/com/vinmusic/recommendation/genre/GenreModels.kt`
    - Add `GenreConfig`, `VisualizerType`, `ScoredContent`, `GenreCacheEntry`, `GenreTasteProfile`, `GenreFilterConfig`, `GenreQueryTemplate` data classes
    - Add `GenreConstants` object with RAP, KPOP, NINETIES, INDIE configurations
    - Add `CacheKeys` object with cache key generation functions
    - Verification: Run `./gradlew assembleDebug` - app should compile successfully
    - Expected: New file created in `recommendation/genre/` folder
    - _Requirements: 12.1, 12.2, 17.1_

- [ ] 2. Checkpoint - Verify data models compile
  - Ensure all tests pass, ask the user if questions arise.

- [x] 3. Implement Pure Query Builder Module
  - [x] 3.1 Create GenreQueryBuilder.kt
    - Create new file `app/src/main/kotlin/com/vinmusic/recommendation/genre/GenreQueryBuilder.kt`
    - Implement `buildRapQueries(subGenre: String?): List<String>` with Trap, Old School, Desi Hip-Hop, UK Drill queries
    - Implement `buildRapArtistQueries(): List<String>` for artist spotlight
    - Implement `buildKPopQueries(contentType: String?): List<String>` for dance practice, live stage, groups, soloists
    - Implement `build90sQueries(year: Int?): List<String>` for year-specific queries
    - Implement `buildIndieUndiscoveredQueries(): List<String>` for hidden gems
    - Implement `buildIndieAcousticQueries(): List<String>` for acoustic content
    - Verification: Add unit test `GenreQueryBuilderTest.kt` and run `./gradlew test`
    - Expected: All query functions return non-empty lists for valid inputs
    - _Requirements: 1.5, 3.3, 6.3, 6.6, 8.5, 10.2, 11.2_

- [x] 4. Implement Pure Content Filter Module
  - [x] 4.1 Create GenreContentFilter.kt
    - Create new file `app/src/main/kotlin/com/vinmusic/recommendation/genre/GenreContentFilter.kt`
    - Implement `filterContent(candidates, config): List<VideoItem>` master filter pipeline
    - Implement `isCompilation(item): Boolean` using existing RecommendationManager function
    - Implement `isNonMusic(item): Boolean` using existing RecommendationManager function
    - Implement `isValidDuration(item, minSeconds, maxSeconds): Boolean` with HH:MM:SS and MM:SS parsing
    - Implement `hasExcludedKeywords(item, keywords): Boolean` for keyword filtering
    - Implement `Presets` object with RAP, KPOP, NINETIES, INDIE_UNDISCOVERED, INDIE_ACOUSTIC configs
    - Verification: Add unit test `GenreContentFilterTest.kt` with sample VideoItem lists
    - Expected: Filters remove compilations, non-music, invalid durations, excluded keywords
    - _Requirements: 1.6, 1.7, 6.4, 8.6, 8.7, 10.3, 10.4, 10.5, 19.1, 19.3, 20.1, 20.2_
  
  - [x] 4.2 Add artist diversity and deduplication functions
    - In `GenreContentFilter.kt`, add `applyArtistDiversity(items, maxPerArtist): List<VideoItem>`
    - Add `deduplicateSimilar(items): List<VideoItem>` using RecommendationManager.isTooSimilar()
    - Verification: Unit test with multiple songs from same artist, verify max constraint respected
    - Expected: Artist diversity limits 1-3 songs per artist based on config
    - _Requirements: 1.9, 4.3, 8.11, 10.8, 19.4, 20.8_

- [ ] 5. Checkpoint - Verify pure modules compile and test
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 6. Implement TasteDNA Personalization Module
  - [ ] 6.1 Create GenreTasteDNA.kt with profile building
    - Create new file `app/src/main/kotlin/com/vinmusic/recommendation/genre/GenreTasteDNA.kt`
    - Add constructor accepting `VinDatabase` dependency
    - Implement `suspend fun buildProfile(genre: String?): GenreTasteProfile`
    - Read from `interactionSignalDao().getAll()` and `historyDao().getAllHistory()`
    - Calculate genre/mood/language scores from interaction signals
    - Build preferred/skipped/recent artists sets
    - Build excluded video IDs set (played in last 14-30 days)
    - Verification: Mock VinDatabase, verify profile extraction logic with test signals
    - Expected: Profile contains topGenres, preferredArtists, skippedArtists, excludedVideoIds
    - _Requirements: 17.5, 18.1, 18.2, 18.3, 18.4, 18.5_
  
  - [ ] 6.2 Add scoring and reranking functions
    - In `GenreTasteDNA.kt`, implement `scoreItem(item, profile): Double`
    - Apply weighted scoring: 30% genre, 20% mood, 25% artist, 10% language, 15% official bonus
    - Apply penalties: -0.25 for skipped artists, -0.50 for excluded video IDs
    - Implement `rerankByTaste(items, profile): List<ScoredContent>` sorting by score descending
    - Implement `filterExcluded(items, profile): List<VideoItem>` removing excluded IDs and skipped artists
    - Implement `isColdStart(profile): Boolean` returning true if profile is empty
    - Verification: Unit test with mock profile, verify scores in 0.0-1.0 range
    - Expected: Scoring respects weighted factors, reranking produces sorted results
    - _Requirements: 17.6, 18.4, 18.5, 18.6, 18.8_

- [ ] 7. Implement Cache Manager Module
  - [ ] 7.1 Create GenreCacheManager.kt with save/load
    - Create new file `app/src/main/kotlin/com/vinmusic/recommendation/genre/GenreCacheManager.kt`
    - Add constructor accepting `Context` for SharedPreferences access
    - Use SharedPreferences key: `"genre_cache_v1"`
    - Implement `save(cacheKey, content, ttlMillis)` serializing GenreCacheEntry to JSON via Gson
    - Implement `load(cacheKey): List<VideoItem>?` returning null if expired or missing
    - Implement `loadStale(cacheKey): Pair<List<VideoItem>, Boolean>?` for fallback scenarios
    - Implement `isFresh(cacheKey): Boolean` checking expiry
    - Implement `staleness(cacheKey): Float` returning 0.0-1.0 staleness factor
    - Verification: Unit test with test Context, verify save/load cycle with TTL expiry
    - Expected: Cache persists across save/load, respects TTL, returns null after expiry
    - _Requirements: 15.6, 15.7, 17.8, 17.9_
  
  - [ ] 7.2 Add LRU eviction and statistics
    - In `GenreCacheManager.kt`, implement `invalidate(cacheKey)` removing entry
    - Implement `clearAll()` clearing all caches
    - Implement `getStats(): CacheStats` returning totalEntries, freshCount, staleCount, hitRate
    - Implement private `evictIfNeeded()` with LRU logic when size > MAX_CACHE_ENTRIES (500)
    - Store access timestamps as `"${cacheKey}_access"` in SharedPreferences
    - Verification: Unit test with 501+ entries, verify oldest accessed entries evicted
    - Expected: LRU eviction maintains cache size <= 500, preserves most recently accessed
    - _Requirements: 15.8, 15.10_

- [ ] 8. Checkpoint - Verify all helper modules compile
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 9. Integrate Genre Methods into RecommendationRepository
  - [ ] 9.1 Add getGenreContent method to RecommendationRepository
    - Open `app/src/main/kotlin/com/vinmusic/recommendation/RecommendationRepository.kt`
    - Add `suspend fun getGenreContent(genre: String, subGenre: String?, contentType: String?): List<VideoItem>`
    - Implement hybrid pipeline: check cache → build queries → fetch API → filter → rerank → cache → return
    - Use GenreQueryBuilder to build queries based on genre/subGenre/contentType
    - Use GenreContentFilter.filterContent() with genre-specific preset configs
    - Use GenreTasteDNA to build profile and rerank results
    - Use GenreCacheManager for caching with genre-specific TTL (6h-48h based on genre)
    - Verification: Add integration test calling getGenreContent("rap", "trap", null), verify non-empty results
    - Expected: Method returns filtered, reranked, cached results; cache hit on second call
    - _Requirements: 17.1, 17.2, 17.3, 17.4, 17.5, 17.8, 17.10_
  
  - [ ] 9.2 Add helper methods for filter config and TTL selection
    - In `RecommendationRepository.kt`, add private `getFilterConfig(genre, subGenre): GenreFilterConfig`
    - Return GenreContentFilter.Presets.RAP for rap, NINETIES for 90s, etc.
    - Add private `getTTL(genre, subGenre): Long` returning TTL milliseconds
    - Rap sub-genres: 12h (43200000ms), 90s years: 48h (172800000ms), Indie undiscovered: 6h (21600000ms)
    - Add private `buildCacheKey(genre, subGenre, contentType): String` using CacheKeys functions
    - Verification: Unit test filter config selection, verify correct presets returned per genre
    - Expected: Correct filter config and TTL selected based on genre/subGenre combination
    - _Requirements: 1.10, 8.12, 10.9, 19.9, 20.9_

- [ ] 10. Checkpoint - Verify repository integration compiles
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 11. Add UI Components for Genre Selection
  - [ ] 11.1 Add genre tab selection to DiscoverScreen
    - Open `app/src/main/kotlin/com/vinmusic/ui/screens/DiscoverScreen.kt`
    - Add `var selectedGenre by remember { mutableStateOf<GenreConfig?>(null) }` state
    - Add horizontal LazyRow with genre tabs (Rap, K-Pop, 90s Hits, Indie) using GenreConstants.ALL_GENRES
    - Apply genre-specific colors from GenreConfig.primaryColor to selected tab
    - On tab click, set selectedGenre and call `RecommendationRepository.getGenreContent()`
    - Display loading indicator (CircularProgressIndicator) while fetching
    - Verification: Run app, tap genre tabs, verify color changes and content loads
    - Expected: Genre tabs visible, clicking tab loads genre-specific content
    - _Requirements: 12.1, 12.2, 12.4_
  
  - [ ] 11.2 Add sub-genre chips for Rap genre
    - In `DiscoverScreen.kt`, add `AnimatedVisibility` for sub-genre chips when selectedGenre == RAP
    - Display chips for "Trap", "Old School", "Desi Hip-Hop", "UK Drill" in horizontal Row
    - Add fade + slide animation with 300ms duration
    - On chip click, call `getGenreContent("rap", subGenre, null)`
    - Use FilterChip from Material 3 with rounded corners and accent colors
    - Verification: Select Rap genre, verify chips appear with animation, click chip loads filtered content
    - Expected: Sub-genre chips animate in, clicking loads sub-genre specific content
    - _Requirements: 1.1, 1.2, 1.3, 1.4_
  
  - [ ] 11.3 Add year timeline slider for 90s Hits genre
    - In `DiscoverScreen.kt`, add `AnimatedVisibility` for year timeline when selectedGenre == NINETIES
    - Display horizontal scrollable Row with year buttons 1990-1999
    - Highlight selected year with accent color and larger font (16sp vs 14sp)
    - Support tap and drag selection
    - On year selection, call `getGenreContent("90s", year.toString(), null)`
    - Verification: Select 90s genre, verify timeline appears, click year loads year-specific content
    - Expected: Year timeline displays, selection highlights year, loads year-filtered content
    - _Requirements: 8.1, 8.2, 8.3, 8.4_

- [ ] 12. Add Visualizer Selection to FullPlayerScreen
  - [ ] 12.1 Implement conditional visualizer rendering
    - Open `app/src/main/kotlin/com/vinmusic/ui/screens/FullPlayerScreen.kt`
    - Get current song genre: `val meta = RecommendationManager.inferMetadata(currentSong)`
    - Get visualizer type from GenreConstants: `val visualizerType = GenreConstants.ALL_GENRES.find { it.displayName.contains(meta.genre) }?.visualizerType ?: VisualizerType.VINYL`
    - Add when expression: `when (visualizerType) { BASS -> BassVisualizer(...), CASSETTE -> CassetteVisualizer(...), VINYL -> VinylVisualizer(...) }`
    - Add 300ms fade transition using AnimatedContent when visualizer changes
    - Verification: Play rap song, verify bass visualizer renders; play 90s song, verify cassette renders
    - Expected: Correct visualizer displays based on song genre with smooth transitions
    - _Requirements: 13.1, 13.2, 13.3, 13.4, 13.5_
  
  - [ ] 12.2 Create BassVisualizer composable
    - Create new file `app/src/main/kotlin/com/vinmusic/ui/components/BassVisualizer.kt`
    - Add `@Composable fun BassVisualizer(audioData: ByteArray?, isPlaying: Boolean)`
    - Use ExoPlayer audio session ID to extract FFT data for frequencies <200Hz
    - Draw sharp, angular geometric patterns using Canvas with animation at 30+ FPS
    - Use dark red/black color scheme for rap aesthetic
    - Transition to static idle state within 500ms when paused
    - Verification: Play rap song, verify aggressive bass-reactive visualization
    - Expected: Visualizer reacts to bass frequencies, uses sharp geometric patterns
    - _Requirements: 2.1, 2.2, 2.3, 2.4, 2.5_
  
  - [ ] 12.3 Create CassetteVisualizer composable
    - Create new file `app/src/main/kotlin/com/vinmusic/ui/components/CassetteVisualizer.kt`
    - Add `@Composable fun CassetteVisualizer(isPlaying: Boolean)`
    - Draw cassette tape housing with reels using Canvas
    - Animate reels rotating clockwise when playing, speed proportional to playback speed
    - Use retro color scheme: tan (#D2B48C), brown (#8B4513), orange (#FF8C00)
    - Verification: Play 90s song, verify cassette animation with rotating reels
    - Expected: Cassette tape visualization with era-appropriate colors and reel animation
    - _Requirements: 7.1, 7.2, 7.3, 7.4, 7.5_

- [ ] 13. Checkpoint - Verify UI integration compiles and renders
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 14. Add Dynamic Gradient Backgrounds for K-Pop
  - [ ] 14.1 Implement animated gradient background
    - In `DiscoverScreen.kt`, add gradient background when selectedGenre == KPOP
    - Use `rememberInfiniteTransition()` for color animation
    - Define 3 color palettes: Pink/Purple, Blue/Mint, Purple/Blue
    - Transition between palettes with 2000ms duration using FastOutSlowInEasing
    - Use `Brush.verticalGradient()` with animated colors
    - Ensure text contrast ratio >= 4.5:1 by using white text with 90% opacity
    - Verification: Select K-Pop genre, verify smooth gradient transitions with readable text
    - Expected: Vibrant pastel gradients animate smoothly, text remains readable
    - _Requirements: 4.1, 4.2, 4.3, 4.4, 4.5_

- [ ] 15. Add Retro Visual Styling for 90s Hits
  - [ ] 15.1 Apply retro color schemes and fonts
    - In `DiscoverScreen.kt`, apply retro styling when selectedGenre == NINETIES
    - Use color palette: Teal (#008080), Purple (#800080), Orange (#FF8C00), Pink (#FF69B4)
    - Apply bold, geometric font family (use existing system bold font)
    - Maintain 4.5:1 contrast ratio for readability
    - Add subtle grain texture overlay (optional) using Canvas
    - Verification: Select 90s genre, verify retro color scheme and bold fonts applied
    - Expected: 90s aesthetic with teal/purple/orange colors, bold fonts, readable text
    - _Requirements: 9.1, 9.2, 9.3, 9.4, 9.5_

- [ ] 16. Add K-Pop Content Shelves
  - [ ] 16.1 Add Trending Groups and Top Soloists shelves
    - In `DiscoverScreen.kt`, when selectedGenre == KPOP, display two LazyRow shelves
    - Shelf 1: "Trending Groups" with BTS, Blackpink, NewJeans, Stray Kids, Twice, Seventeen, Aespa, ITZY
    - Shelf 2: "Top Soloists" with Jungkook, IU, Taeyeon, Lisa, Jennie, Rosé, Jimin, V
    - Use circular thumbnail images with 80dp size, minimal text overlay (artist name only)
    - On tap, call `getGenreContent("kpop", null, "groups")` or `"soloists"` and begin playback
    - Verification: Select K-Pop genre, verify two shelves render, tap artist begins playback
    - Expected: Two distinct shelves with K-Pop artists, tapping loads and plays artist mix
    - _Requirements: 5.1, 5.2, 5.3, 5.4, 5.5_
  
  - [ ] 16.2 Add Dance Practices and Live Stages shelves
    - In `DiscoverScreen.kt`, when selectedGenre == KPOP, add two more LazyRow shelves
    - Shelf 3: "Dance Practices" calling `getGenreContent("kpop", null, "dance practice")`
    - Shelf 4: "Live Stages" calling `getGenreContent("kpop", null, "live stage")`
    - Display video thumbnails with play icon overlay
    - On tap, begin playback of performance video
    - Verification: Select K-Pop genre, scroll down to see practice/stage shelves, tap video plays
    - Expected: Dance practice and live stage shelves with video content, playback works
    - _Requirements: 6.1, 6.2, 6.5, 6.7_

- [ ] 17. Add Indie Content Shelves
  - [ ] 17.1 Add Undiscovered Gems shelf
    - In `DiscoverScreen.kt`, when selectedGenre == INDIE, display "Undiscovered Gems" LazyRow
    - Call `getGenreContent("indie", "Undiscovered", null)`
    - Display 10+ songs from artists with <1M views
    - Use compact card design with artist thumbnail, song title, artist name
    - On tap, begin playback
    - Verification: Select Indie genre, verify "Undiscovered Gems" shelf with low-view content
    - Expected: Shelf displays hidden indie gems, tapping plays song
    - _Requirements: 10.1, 10.10_
  
  - [ ] 17.2 Add Acoustic / Unplugged shelf
    - In `DiscoverScreen.kt`, when selectedGenre == INDIE, display "Acoustic / Unplugged" LazyRow
    - Call `getGenreContent("indie", "Acoustic", null)`
    - Display 12+ acoustic/unplugged performance songs
    - Use same compact card design
    - On tap, begin playback
    - Verification: Select Indie genre, verify "Acoustic / Unplugged" shelf with acoustic content
    - Expected: Shelf displays acoustic performances, tapping plays song
    - _Requirements: 11.1, 11.9, 11.10_

- [ ] 18. Add Rap Artist Spotlight
  - [ ] 18.1 Create artist spotlight section for Rap genre
    - In `DiscoverScreen.kt`, when selectedGenre == RAP, display "Artist Spotlight" horizontal Row
    - Fetch trending rap artists using `GenreQueryBuilder.buildRapArtistQueries()`
    - Display circular avatar thumbnails (64dp) with artist name below (12sp)
    - Include minimum: Kendrick Lamar, Divine, Kr$na, Eminem, plus 5 additional from API
    - Arrange with 8dp spacing in scrollable Row
    - On tap, call `InnerTube.search("[artist name] popular songs")` and begin playback
    - Verification: Select Rap genre, verify artist spotlight renders with avatars, tap plays artist mix
    - Expected: Artist spotlight with circular avatars, tapping artist loads and plays their mix
    - _Requirements: 3.1, 3.2, 3.7, 3.9, 3.10_

- [ ] 19. Checkpoint - Verify all UI enhancements compile and render
  - Ensure all tests pass, ask the user if questions arise.

- [ ] 20. Add Property-Based Tests
  - [ ] 20.1 Set up Kotest PropertyTesting dependency
    - Open `app/build.gradle.kts`
    - Add testImplementation: `"io.kotest:kotest-property:5.8.0"`
    - Sync Gradle
    - Verification: Run `./gradlew test` to verify Kotest dependency resolves
    - Expected: Dependency added, Gradle sync successful
  
  - [ ] 20.2 Create property tests for GenreQueryBuilder
    - Create `app/src/test/kotlin/com/vinmusic/recommendation/genre/GenreQueryBuilderPropertyTest.kt`
    - Add Property 1: Query Generation Determinism - verify same input produces same queries (100 iterations)
    - Use Arb.string() for subGenre inputs
    - Verification: Run `./gradlew test --tests GenreQueryBuilderPropertyTest`
    - Expected: All 100 iterations pass, queries are deterministic
    - _Property 1 validates Requirements: 1.5, 6.3, 8.5, 10.2, 11.2_
  
  - [ ] 20.3 Create property tests for GenreContentFilter
    - Create `app/src/test/kotlin/com/vinmusic/recommendation/genre/GenreContentFilterPropertyTest.kt`
    - Add Property 2: Filter Idempotence - verify applying filter twice = applying once (100 iterations)
    - Add Property 3: Artist Diversity Constraint - verify max songs per artist respected (100 iterations)
    - Add Property 7: Deduplication Completeness - verify no duplicates or similar titles (100 iterations)
    - Add Property 9: Content Duration Validation - verify duration bounds respected (100 iterations)
    - Use custom Arb.videoItem() generator for test data
    - Verification: Run `./gradlew test --tests GenreContentFilterPropertyTest`
    - Expected: All properties pass 100 iterations each
    - _Properties 2,3,7,9 validate Requirements: 1.6-1.9, 4.3, 8.11, 10.3-10.8, 19.1-19.4, 20.1_
  
  - [ ] 20.4 Create property tests for GenreTasteDNA
    - Create `app/src/test/kotlin/com/vinmusic/recommendation/genre/GenreTasteDNAPropertyTest.kt`
    - Add Property 4: TasteDNA Scoring Range - verify scores always 0.0-1.0 (100 iterations)
    - Add Property 6: Excluded Content Filtering - verify excluded IDs removed (100 iterations)
    - Use custom Arb.tasteProfile() generator for test profiles
    - Use mock VinDatabase for testing
    - Verification: Run `./gradlew test --tests GenreTasteDNAPropertyTest`
    - Expected: All properties pass 100 iterations, scores in valid range
    - _Properties 4,6 validate Requirements: 17.6, 18.2-18.6_
  
  - [ ] 20.5 Create property tests for GenreCacheManager
    - Create `app/src/test/kotlin/com/vinmusic/recommendation/genre/GenreCacheManagerPropertyTest.kt`
    - Add Property 5: Cache Freshness Invariant - verify TTL expiry behavior (100 iterations)
    - Add Property 8: Cache LRU Eviction Order - verify oldest accessed evicted first (100 iterations)
    - Use test Context with in-memory SharedPreferences
    - Verification: Run `./gradlew test --tests GenreCacheManagerPropertyTest`
    - Expected: All properties pass 100 iterations, cache behavior correct
    - _Properties 5,8 validate Requirements: 15.6, 15.7, 15.8, 17.8_

- [ ] 21. Add Integration Tests
  - [ ] 21.1 Create end-to-end integration test
    - Create `app/src/androidTest/kotlin/com/vinmusic/recommendation/GenreIntegrationTest.kt`
    - Test full pipeline: getGenreContent("rap", "trap", null) → verify non-empty results
    - Test cache hit: second call returns cached results faster
    - Test API failure: mock InnerTube failure, verify stale cache fallback
    - Test TasteDNA personalization: mock user profile, verify reranking applied
    - Verification: Run `./gradlew connectedAndroidTest`
    - Expected: All integration tests pass, pipeline works end-to-end
    - _Validates Requirements: 17.1-17.10_

- [ ] 22. Add Performance Monitoring
  - [ ] 22.1 Add cache statistics logging
    - In `RecommendationRepository.kt`, add logging for cache hit/miss in getGenreContent
    - Log "Cache HIT for $cacheKey" or "Cache MISS for $cacheKey"
    - Call `cacheManager.getStats()` periodically and log hit rate
    - Verification: Run app, check Logcat for cache statistics, verify >70% hit rate after 10 queries
    - Expected: Cache hit rate logged, target >70% achieved
    - _Requirements: 15.10_
  
  - [ ] 22.2 Add performance profiling tags
    - In `RecommendationRepository.kt`, add Trace markers around getGenreContent
    - Use `Trace.beginSection("GenreContent_$genre")` and `Trace.endSection()`
    - Add trace markers for query, filter, rerank, cache stages
    - Verification: Use Android Studio Profiler, verify API response <2s, cache response <100ms
    - Expected: Performance metrics visible in profiler, targets met
    - _Requirements: 15.1, 15.3_

- [ ] 23. Optional: Add Analytics Tracking
  - [ ] 23.1 Track genre engagement events
    - In `DiscoverScreen.kt`, add analytics logging when genre selected
    - Log "genre_selected" event with genre name parameter
    - Log "subgenre_selected" for sub-genre/year selections
    - Log "artist_tapped" when artist spotlight avatar tapped
    - Use existing AnalyticsHelper.kt for event tracking
    - Verification: Trigger events, check Firebase Analytics console for events
    - Expected: Events logged to analytics with correct parameters
    - _Requirements: 14.1_

- [ ] 24. Optional: Add Content Persistence
  - [ ] 24.1 Persist genre selection to VinDatabase
    - Open `app/src/main/kotlin/com/vinmusic/data/db/VinDatabase.kt`
    - Add Room @Entity: `GenrePreferenceEntity(id: Int = 1, selectedGenre: String?, selectedSubGenre: String?, timestamp: Long)`
    - Add DAO with `getPreference(): GenrePreferenceEntity?` and `savePreference(pref)`
    - In `DiscoverScreen.kt`, save selectedGenre on change, restore on launch
    - Verification: Select genre, close app, reopen, verify genre selection restored
    - Expected: Genre selection persists across app restarts
    - _Requirements: 16.1, 16.2, 16.3, 16.4, 16.5_

## Notes

- All tasks reference specific requirement IDs for traceability
- Tasks are ordered by dependency (data models → helpers → integration → UI → tests)
- Each task includes file location, verification steps, and expected outcomes
- App compiles after each task completion (incremental implementation)
- Property-based tests use Kotest with 100 iterations per property
- Optional tasks (23, 24) can be skipped for MVP

## Task Dependency Graph

```json
{
  "waves": [
    { "id": 0, "tasks": ["1.1"] },
    { "id": 1, "tasks": ["3.1"] },
    { "id": 2, "tasks": ["4.1", "4.2"] },
    { "id": 3, "tasks": ["6.1", "6.2"] },
    { "id": 4, "tasks": ["7.1", "7.2"] },
    { "id": 5, "tasks": ["9.1", "9.2"] },
    { "id": 6, "tasks": ["11.1", "11.2", "11.3", "12.1"] },
    { "id": 7, "tasks": ["12.2", "12.3", "14.1", "15.1"] },
    { "id": 8, "tasks": ["16.1", "16.2", "17.1", "17.2", "18.1"] },
    { "id": 9, "tasks": ["20.1"] },
    { "id": 10, "tasks": ["20.2", "20.3", "20.4", "20.5"] },
    { "id": 11, "tasks": ["21.1"] },
    { "id": 12, "tasks": ["22.1", "22.2", "23.1", "24.1"] }
  ]
}
```
