# Requirements Document: Pro Genre Enhancements

## Introduction

The Pro Genre Enhancements feature transforms the existing genre system in VinMusic Android app by adding premium UI/UX elements that give each genre (Rap/Hip-Hop, K-Pop, 90s Hits, Indie) its own unique visual personality and custom treatments. This enhancement elevates the user experience by providing differentiated, memorable interactions for each music category while maintaining the app's existing architecture using Kotlin, Jetpack Compose, and YouTube Music API via InnerTube.

## Glossary

- **Genre_System**: The existing genre classification and browsing mechanism in DiscoverScreen that categorizes music into Rap, K-Pop, 90s Hits, and Indie
- **Sub_Genre_Chips**: Interactive UI chips that appear when a parent genre is selected, displaying refined subcategories
- **Bass_Visualizer**: A specialized audio visualization component that reacts primarily to low-frequency audio ranges
- **Artist_Spotlight**: A UI section featuring circular avatar representations of trending artists within a genre
- **Dynamic_Gradient**: Animated background color transitions using gradient patterns
- **Content_Shelf**: A horizontal scrollable row displaying related music content
- **Vinyl_Visualizer**: The existing rotating disc animation in FullPlayerScreen
- **Cassette_Animation**: A retro-styled tape deck visualization for 90s era music
- **Timeline_Slider**: A year-selection UI component allowing users to browse content by specific years
- **Undiscovered_Shelf**: A specialized content section featuring artists with view counts below 1 million
- **InnerTube_API**: The YouTube Music backend API used for content retrieval
- **FullPlayerScreen**: The full-screen music player interface with album art and controls
- **DiscoverScreen**: The main music discovery interface with genre navigation
- **Hybrid_Filtering**: A two-stage content discovery approach that fetches candidates from InnerTube_API and applies local filtering based on user listening history and preferences
- **TasteDNA_Reranking**: Algorithm that reorders API results based on user's listening patterns, preferred genres, moods, recently played artists, and skip history
- **Local_Content_Cache**: Device-stored query results with configurable TTL (Time To Live) to minimize redundant API calls
- **View_Count_Filter**: Local filter that excludes content exceeding specified view count thresholds for undiscovered content discovery
- **Listening_History**: User's complete playback history including play counts, skip counts, completed plays, and temporal patterns

## Requirements

### Requirement 1: Rap/Hip-Hop Sub-Genre Navigation with Hybrid Filtering

**User Story:** As a Hip-Hop fan, I want to browse specific rap sub-genres, so that I can discover music that matches my precise taste within the broader rap category.

#### Acceptance Criteria

1. WHEN THE User taps the Rap genre in Genre_System, THE Sub_Genre_Chips SHALL appear displaying "Trap", "Old School", "Desi Hip-Hop", and "UK Drill"
2. WHEN THE User taps any Sub_Genre_Chip, THE Genre_System SHALL filter content to show only music matching that sub-genre
3. THE Sub_Genre_Chips SHALL use chip-style UI components with rounded corners and accent colors
4. THE Sub_Genre_Chips SHALL animate into view with a fade and slide animation within 300ms
5. WHEN THE User taps a Sub_Genre_Chip, THE Genre_System SHALL query InnerTube_API with specialized search terms: "[sub-genre] hits", "[sub-genre] popular", "[sub-genre] 2024 2025", and "[sub-genre] new releases"
6. THE Genre_System SHALL apply local filters to exclude non-rap content by checking for genre-specific keywords in title/artist metadata
7. THE Genre_System SHALL apply content quality filters to remove compilations, reaction videos, and lyric videos
8. THE Genre_System SHALL apply TasteDNA_Reranking to prioritize results matching user's rap sub-genre preferences from Listening_History
9. THE Genre_System SHALL exclude songs user has already played within last 14 days to surface fresh content
10. THE Genre_System SHALL cache sub-genre results with 12-hour TTL per sub-genre in Local_Content_Cache

### Requirement 2: Heavy Bass Visualizer for Rap

**User Story:** As a rap music listener, I want an aggressive bass-reactive visualizer, so that I can experience the heavy low-end frequencies characteristic of rap music.

#### Acceptance Criteria

1. WHEN THE FullPlayerScreen displays a song tagged as Rap or Hip-Hop, THE Bass_Visualizer SHALL replace the standard Vinyl_Visualizer
2. THE Bass_Visualizer SHALL react more prominently to audio frequencies below 200Hz than to mid or high frequencies
3. THE Bass_Visualizer SHALL use aggressive visual animations with sharp, angular geometric patterns
4. THE Bass_Visualizer SHALL update visual elements at least 30 times per second for smooth animation
5. WHEN THE audio playback is paused, THE Bass_Visualizer SHALL transition to a static idle state within 500ms

### Requirement 3: Rap Artist Spotlight Section with Intelligent Selection

**User Story:** As a rap enthusiast, I want quick access to trending rap artists, so that I can instantly play mixes of my favorite rappers.

#### Acceptance Criteria

1. WHEN THE User selects the Rap genre, THE Artist_Spotlight SHALL display circular avatar thumbnails for trending rap artists
2. THE Artist_Spotlight SHALL use Hybrid_Filtering to select artists: fetch trending rappers from InnerTube_API, then rerank based on user's Listening_History
3. THE Artist_Spotlight SHALL include at minimum artists: Kendrick Lamar, Divine, Kr$na, Eminem, and at least 5 additional trending artists selected via queries like "trending rap artists 2024", "popular hip hop rappers"
4. THE Artist_Spotlight SHALL apply TasteDNA_Reranking to prioritize artists user has played before or similar artists based on genre/mood match
5. THE Artist_Spotlight SHALL boost recently popular artists (trending searches) while maintaining personalization balance
6. THE Artist_Spotlight SHALL cache artist list with 24-hour TTL as trending artists change slowly
7. WHEN THE User taps an artist avatar in Artist_Spotlight, THE Genre_System SHALL immediately begin playback of that artist's mix fetched via "[artist name] popular songs" or "[artist name] greatest hits"
8. THE Genre_System SHALL construct artist mix with at least 15 songs avoiding compilations and low-quality uploads
9. THE Artist_Spotlight SHALL arrange avatars in a horizontal scrollable row with 8dp spacing between items
10. THE Artist_Spotlight SHALL display artist names below avatars with 12sp font size for easy identification

### Requirement 4: K-Pop Dynamic Gradient Backgrounds

**User Story:** As a K-Pop fan, I want vibrant visual aesthetics, so that the app reflects the colorful and energetic nature of K-Pop culture.

#### Acceptance Criteria

1. WHEN THE User selects the K-Pop genre, THE Dynamic_Gradient SHALL apply vibrant pastel gradient backgrounds to the K-Pop genre tab
2. THE Dynamic_Gradient SHALL transition between at least 3 distinct color combinations with smooth animation
3. THE Dynamic_Gradient SHALL complete each color transition within 2000ms using easing functions
4. THE Dynamic_Gradient SHALL use color palettes containing pink, purple, blue, and mint tones characteristic of K-Pop aesthetics
5. THE Dynamic_Gradient SHALL maintain text readability by ensuring minimum contrast ratio of 4.5:1 with foreground text

### Requirement 5: K-Pop Content Organization

**User Story:** As a K-Pop listener, I want organized access to groups and soloists, so that I can easily navigate between different K-Pop content types.

#### Acceptance Criteria

1. WHEN THE User selects K-Pop genre, THE Content_Shelf SHALL display two distinct sections: "Trending Groups" and "Top Soloists"
2. THE "Trending Groups" Content_Shelf SHALL include BTS, Blackpink, NewJeans, Stray Kids, and at least 4 additional popular groups
3. THE "Top Soloists" Content_Shelf SHALL include Jungkook, IU, Taeyeon, and at least 4 additional popular solo artists
4. THE Content_Shelf SHALL use thumbnail images as the primary visual element with minimal text overlay
5. WHEN THE User taps any item in Content_Shelf, THE Genre_System SHALL begin playback of a mix for that artist or group

### Requirement 6: K-Pop Performance Content with Hybrid Filtering

**User Story:** As a K-Pop fan, I want access to dance practices and live stages, so that I can experience the performance-focused aspect of K-Pop.

#### Acceptance Criteria

1. WHEN THE User selects K-Pop genre, THE Content_Shelf SHALL include a section titled "Dance Practices"
2. THE Content_Shelf SHALL include a section titled "Live Stages"
3. THE "Dance Practices" Content_Shelf SHALL query InnerTube_API using specialized search terms: "[artist] dance practice", "[artist] choreography", "[artist] studio choom", and "[artist] performance video"
4. THE "Dance Practices" Content_Shelf SHALL apply local filters to exclude random dance covers by filtering for official channels or verified choreography channels
5. THE "Dance Practices" Content_Shelf SHALL prioritize videos with "dance practice" or "choreography" in the title over generic performance videos
6. THE "Live Stages" Content_Shelf SHALL query InnerTube_API using search terms combining artist names with "live performance", "music show", and "comeback stage"
7. THE "Live Stages" Content_Shelf SHALL filter results to prioritize official content from verified music show channels (Mnet, SBS, KBS, MBC)
8. THE Content_Shelf SHALL apply TasteDNA_Reranking based on user's K-Pop artist preferences from Listening_History
9. THE Content_Shelf SHALL cache results with 12-hour TTL in Local_Content_Cache per artist
10. THE Content_Shelf SHALL exclude previously played performance videos to surface fresh content

### Requirement 7: 90s Cassette Tape Visualizer

**User Story:** As a 90s music nostalgic listener, I want retro-styled visuals, so that I can experience the era-appropriate aesthetic while listening to 90s hits.

#### Acceptance Criteria

1. WHEN THE FullPlayerScreen displays a song tagged as 90s era, THE Cassette_Animation SHALL replace the default Vinyl_Visualizer
2. THE Cassette_Animation SHALL display visual elements resembling a cassette tape including reels and tape housing
3. WHEN THE audio is playing, THE Cassette_Animation SHALL animate the tape reels rotating clockwise
4. THE Cassette_Animation SHALL rotate reels at a speed proportional to audio playback speed
5. THE Cassette_Animation SHALL use retro color schemes including tan, brown, and orange tones

### Requirement 8: 90s Timeline Year Navigation with Hybrid Content Discovery

**User Story:** As a nostalgic music fan, I want to browse music by specific years in the 90s, so that I can relive precise periods of musical history.

#### Acceptance Criteria

1. WHEN THE User selects 90s Hits genre, THE Timeline_Slider SHALL display year markers from 1990 to 1999
2. WHEN THE User selects a specific year on Timeline_Slider, THE Genre_System SHALL filter content to show only songs released in that year
3. THE Timeline_Slider SHALL highlight the currently selected year with accent color and larger font size
4. THE Timeline_Slider SHALL allow users to tap or drag to select years
5. THE Timeline_Slider SHALL query InnerTube_API using specialized search terms: "[year] hits", "top songs [year]", "popular music [year]", and "best of [year]"
6. THE Genre_System SHALL apply local filters to remove content uploaded after 2005 to avoid modern remasters and covers (check upload date metadata where available)
7. THE Genre_System SHALL apply content quality filters to exclude: compilations ("90s hits compilation"), reaction videos, lyric videos, and modern remixes
8. THE Genre_System SHALL prioritize official artist channels and verified music distributors (VEVO, official labels)
9. THE Genre_System SHALL apply TasteDNA_Reranking based on user's 90s music preferences from Listening_History (rock, pop, R&B, hip-hop from that era)
10. THE Genre_System SHALL exclude songs user has played within last 21 days to encourage nostalgic rediscovery
11. THE Genre_System SHALL apply artist diversity filter limiting maximum 2 songs per artist per year
12. THE Genre_System SHALL cache year-specific results with 24-hour TTL per year in Local_Content_Cache

### Requirement 9: 90s Retro Visual Styling

**User Story:** As a 90s music enthusiast, I want era-appropriate visual design, so that the browsing experience matches the nostalgic theme.

#### Acceptance Criteria

1. WHEN THE User selects 90s Hits genre, THE Genre_System SHALL apply retro color schemes to the genre interface
2. THE Genre_System SHALL use color palettes containing teal, purple, orange, and pink characteristic of 90s design
3. THE Genre_System SHALL use fonts with retro styling such as bold, geometric, or rounded letter forms
4. THE Genre_System SHALL maintain visual consistency across all 90s genre interface elements
5. THE Genre_System SHALL preserve readability while applying retro styling with minimum contrast ratio of 4.5:1

### Requirement 10: Indie Undiscovered Content with Hybrid Filtering

**User Story:** As an indie music explorer, I want to find hidden gems from underground artists, so that I can discover music before it becomes mainstream.

#### Acceptance Criteria

1. WHEN THE User selects Indie genre, THE Undiscovered_Shelf SHALL display a section titled "Undiscovered Gems"
2. THE Undiscovered_Shelf SHALL use Hybrid_Filtering with specialized InnerTube_API queries including "indie unsigned artist", "underrated indie songs", "new indie music", and "hidden indie gems"
3. THE Undiscovered_Shelf SHALL apply View_Count_Filter to exclude videos with more than 1 million views where view count metadata is available
4. THE Undiscovered_Shelf SHALL apply content quality filters to remove remixes, covers, live recordings, and unofficial content unless from verified indie channels
5. THE Undiscovered_Shelf SHALL filter out songs with duration less than 90 seconds or greater than 8 minutes to ensure proper song format
6. THE Undiscovered_Shelf SHALL apply TasteDNA_Reranking to prioritize results matching user's indie music preferences from Listening_History
7. THE Undiscovered_Shelf SHALL exclude songs the user has already played to surface fresh discoveries
8. THE Undiscovered_Shelf SHALL limit same-artist repetition to maximum 1 song per artist in results
9. THE Undiscovered_Shelf SHALL cache query results with 6-hour TTL in Local_Content_Cache
10. THE Undiscovered_Shelf SHALL display at least 10 songs from qualifying artists after all filtering stages

### Requirement 11: Indie Acoustic Content with Intelligent Filtering

**User Story:** As an indie music fan, I want dedicated access to acoustic and unplugged performances, so that I can enjoy the raw, intimate nature of indie music.

#### Acceptance Criteria

1. WHEN THE User selects Indie genre, THE Content_Shelf SHALL display a section titled "Acoustic / Unplugged"
2. THE Content_Shelf SHALL query InnerTube_API using specialized search terms: "[artist] acoustic", "[artist] unplugged", "[artist] live acoustic", and "[artist] stripped"
3. THE Content_Shelf SHALL apply local filters to prioritize live acoustic performance videos over studio recordings by checking for keywords "live", "session", "unplugged" in title
4. THE Content_Shelf SHALL apply TasteDNA_Reranking based on user's indie and acoustic music preferences from Listening_History
5. THE Content_Shelf SHALL exclude heavily produced or full-band versions by filtering out tracks with keywords "full band", "electric", "remix"
6. THE Content_Shelf SHALL apply artist diversity filter to limit maximum 2 songs per artist
7. THE Content_Shelf SHALL exclude songs user has already played to surface fresh acoustic content
8. THE Content_Shelf SHALL cache results with 12-hour TTL per artist in Local_Content_Cache
9. THE Content_Shelf SHALL display at least 12 songs in the acoustic section after all filtering stages
10. WHEN THE User taps any item in the acoustic Content_Shelf, THE Genre_System SHALL begin playback immediately

### Requirement 12: Genre-Specific Visual Personality

**User Story:** As a music listener, I want each genre to feel unique, so that I have a memorable and differentiated experience based on my music choice.

#### Acceptance Criteria

1. THE Genre_System SHALL apply distinct visual themes to each of the four genres (Rap, K-Pop, 90s Hits, Indie)
2. THE Genre_System SHALL use genre-specific color palettes that do not overlap between genres
3. THE Genre_System SHALL apply consistent visual treatments throughout each genre's interface
4. THE Genre_System SHALL transition between genre visual themes within 500ms when switching genres
5. THE Genre_System SHALL maintain visual differentiation across all UI elements including backgrounds, text, buttons, and content cards

### Requirement 13: Conditional Visualizer Rendering

**User Story:** As a developer, I want visualizers to render based on genre context, so that the appropriate visual experience displays for each music category.

#### Acceptance Criteria

1. THE FullPlayerScreen SHALL determine the active visualizer based on song genre metadata
2. WHEN THE song genre is Rap or Hip-Hop, THE FullPlayerScreen SHALL render Bass_Visualizer
3. WHEN THE song genre is 90s Hits, THE FullPlayerScreen SHALL render Cassette_Animation
4. WHEN THE song genre is K-Pop or Indie, THE FullPlayerScreen SHALL render the default Vinyl_Visualizer
5. THE FullPlayerScreen SHALL transition between visualizer types within 300ms when song genre changes

### Requirement 14: Genre Mix Instant Playback

**User Story:** As a user exploring genres, I want instant mix playback, so that I can quickly audition content without navigating through multiple screens.

#### Acceptance Criteria

1. WHEN THE User taps any artist avatar, group thumbnail, or content item in Genre_System, THE Genre_System SHALL begin audio playback within 2000ms
2. THE Genre_System SHALL construct a playlist of at least 10 songs related to the tapped item
3. THE Genre_System SHALL query InnerTube_API to retrieve the playlist content
4. THE Genre_System SHALL display a loading indicator if playback takes longer than 500ms
5. THE Genre_System SHALL handle InnerTube_API failures gracefully by displaying an error message and retaining the previous screen state

### Requirement 15: Performance Optimization with Intelligent Caching

**User Story:** As a user, I want smooth performance and fast content loading, so that genre enhancements do not negatively impact app responsiveness.

#### Acceptance Criteria

1. THE Genre_System SHALL maintain UI frame rate of at least 50 FPS during all animations
2. THE Genre_System SHALL cache genre-specific visual assets to reduce loading time
3. THE Genre_System SHALL load thumbnails and images asynchronously to prevent UI blocking
4. THE Bass_Visualizer SHALL process audio frequency data without impacting audio playback quality
5. THE Dynamic_Gradient SHALL use hardware acceleration for color transitions
6. THE Local_Content_Cache SHALL store InnerTube_API query results with configurable TTL: 6-24 hours for general queries, 1-3 hours for user-personalized results
7. THE Genre_System SHALL serve cached content immediately while optionally refreshing in background if cache age exceeds 80% of TTL
8. THE Local_Content_Cache SHALL implement LRU (Least Recently Used) eviction when cache size exceeds 500 entries
9. THE Genre_System SHALL compress cached data using lightweight serialization to minimize storage footprint
10. THE Genre_System SHALL measure and log cache hit rate to monitor effectiveness with target >70% hit rate

### Requirement 16: Genre Content Persistence

**User Story:** As a user, I want my genre selections remembered, so that I can return to my preferred genre view without renavigation.

#### Acceptance Criteria

1. THE Genre_System SHALL persist the last selected genre to local device storage
2. WHEN THE User reopens DiscoverScreen, THE Genre_System SHALL restore the previously selected genre view
3. THE Genre_System SHALL persist sub-genre selections within each main genre
4. THE Genre_System SHALL persist Timeline_Slider year selections for 90s Hits genre
5. THE Genre_System SHALL store genre preferences using the existing VinDatabase Room database instance


### Requirement 17: Hybrid Content Discovery Architecture

**User Story:** As a developer, I want a robust content discovery system, so that API queries produce high-quality, personalized results that match user preferences.

#### Acceptance Criteria

1. THE Genre_System SHALL implement a hybrid content discovery pipeline with stages: API Query → Normalization → Local Filtering → TasteDNA Reranking → Caching
2. THE Genre_System SHALL fetch raw candidates from InnerTube_API using specialized search queries optimized for each content type
3. THE Genre_System SHALL normalize API results by extracting videoId, title, author, thumbnail, duration, and view count (where available)
4. THE Genre_System SHALL apply local content quality filters including: view count thresholds, duration ranges, official/remix/live/cover detection, and already-played exclusion
5. THE Genre_System SHALL implement TasteDNA_Reranking that scores candidates based on: genre match, mood match, artist similarity to Listening_History, recently played artists, and negative signals from skip history
6. THE TasteDNA_Reranking SHALL calculate similarity scores (0.0-1.0) with weighted factors: user's preferred genres (30%), preferred moods (20%), recently played similar artists (25%), skip history penalty (-15%), recency bonus (15%)
7. THE Genre_System SHALL apply artist diversity constraints limiting consecutive same-artist results and capping per-artist repetition based on content type
8. THE Genre_System SHALL cache final ranked results with TTL based on personalization level: 6-24 hours for generic queries, 1-3 hours for user-personalized results
9. THE Genre_System SHALL log pipeline metrics including: candidates fetched, candidates after filtering, final result count, cache hit/miss, and pipeline execution time
10. THE Genre_System SHALL handle InnerTube_API failures by falling back to cached results with staleness indicator if cache exists

### Requirement 18: Listening History Integration for Personalization

**User Story:** As a user, I want genre content tailored to my listening habits, so that recommendations feel personalized rather than generic.

#### Acceptance Criteria

1. THE Genre_System SHALL integrate with existing Listening_History from VinDatabase including play counts, skip counts, complete plays, and skip-within-20-seconds signals
2. THE TasteDNA_Reranking SHALL extract user's genre preferences from Listening_History by aggregating play counts per inferred genre with minimum 5 plays required
3. THE TasteDNA_Reranking SHALL extract user's mood preferences from Listening_History by aggregating play counts per inferred mood with minimum 3 plays required
4. THE TasteDNA_Reranking SHALL identify recently played artists (within last 7 days) and boost similar artists in recommendations by +0.15 similarity score
5. THE TasteDNA_Reranking SHALL identify heavily skipped artists (skip rate >50%) and apply -0.20 penalty to similarity score
6. THE Genre_System SHALL exclude songs user has already played if Listening_History contains play record within last 30 days
7. THE Genre_System SHALL refresh TasteDNA profile when user's play count increases by 20+ plays or when last refresh was >24 hours ago
8. THE Genre_System SHALL handle cold-start scenario (user with <10 total plays) by using genre-specific defaults without personalization penalties
9. THE Genre_System SHALL log personalization effectiveness metrics including: personalized result click-through rate vs generic result CTR
10. THE Genre_System SHALL allow manual personalization reset via developer debug menu for testing purposes


### Requirement 19: Rap Content Quality and Diversity

**User Story:** As a rap music listener, I want high-quality, diverse rap content without repetitive songs or low-quality uploads, so that my listening experience remains engaging.

#### Acceptance Criteria

1. THE Genre_System SHALL apply content quality filters for all Rap genre queries to exclude: lyric videos, instrumental versions, reaction videos, "type beat" producer uploads, and fan-made remixes
2. THE Genre_System SHALL prioritize official artist uploads, verified VEVO channels, and major label distributors (Def Jam, Top Dawg, Young Money, etc.)
3. THE Genre_System SHALL filter out songs with duration <90 seconds or >8 minutes to ensure proper track format (excludes interludes and extended freestyles)
4. THE Genre_System SHALL apply artist diversity constraints: maximum 3 songs per artist in general Rap browsing, maximum 2 songs per artist in sub-genre browsing
5. THE Genre_System SHALL exclude songs with explicit blacklist keywords: "slowed", "reverb", "8D audio", "nightcore", "1 hour", "10 hours"
6. THE Genre_System SHALL apply TasteDNA_Reranking to boost songs from user's frequently played rap sub-genres (e.g., if user plays mostly Trap, boost Trap results)
7. THE Genre_System SHALL penalize artists user has skipped 3+ times (skip rate >60%) by reducing their similarity score by -0.25
8. THE Genre_System SHALL exclude songs user has played in the last 7 days within same sub-genre to encourage variety
9. THE Genre_System SHALL cache filtered results per sub-genre with 6-hour TTL for personalized results, 18-hour TTL for generic queries
10. THE Genre_System SHALL log filtering effectiveness metrics: candidates before/after filtering, user engagement rate with filtered vs unfiltered results

### Requirement 20: 90s Era Authenticity and Content Verification

**User Story:** As a 90s music nostalgia enthusiast, I want authentic era-appropriate content without modern covers or remasters, so that I experience genuine 90s music.

#### Acceptance Criteria

1. THE Genre_System SHALL apply content authenticity filters for all 90s Hits queries to exclude: modern covers, "remastered", "remix", "tribute", and "karaoke" versions
2. THE Genre_System SHALL prioritize videos uploaded between 2005-2015 which typically contain authentic CD rips and original releases (check upload date metadata where available)
3. THE Genre_System SHALL filter out channels with names containing "covers", "tribute", "karaoke", "acoustic version" unless verified as original artist
4. THE Genre_System SHALL use specialized era-specific queries combining year with genre: "[year] rock hits", "[year] pop songs", "[year] R&B", "[year] hip hop"
5. THE Genre_System SHALL apply regional personalization: if user's Listening_History shows preference for specific regional 90s music (e.g., US hip-hop, UK rock, Euro pop), boost those results
6. THE Genre_System SHALL implement decade-appropriate artist boosting: artists with peak popularity in selected year get +0.20 similarity bonus
7. THE Genre_System SHALL exclude songs user has played within last 30 days to maximize nostalgic rediscovery experience
8. THE Genre_System SHALL apply strict artist diversity: maximum 1 song per artist per year selection to showcase era breadth
9. THE Genre_System SHALL cache year-specific filtered results with 48-hour TTL since 90s content is static
10. THE Genre_System SHALL display era-appropriate metadata in UI: show original release year, original album name, and artist peak era indicator
