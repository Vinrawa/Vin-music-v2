# Requirements Document

## Introduction

This document specifies the requirements for an intelligent playlist recommendation system for VinMusic Android app. The system will analyze user's local playlists and listening behavior to generate diverse, multi-artist recommendations that match their taste profile. Unlike the current system which suggests playlists from a single artist, the new system will create mixed playlists with multiple artists across similar genres and complementary styles.

## Glossary

- **Taste_Profile**: A data structure representing user's music preferences including top artists, genres, moods, languages, and acoustic DNA (energy, tempo).
- **Playlist_Analyzer**: Component that scans and analyzes all local playlists to extract music preference patterns.
- **Genre_Detector**: Component that identifies music genres from songs and artists using metadata inference and keyword matching.
- **Recommendation_Engine**: Core system that generates diverse playlist recommendations based on user's taste profile.
- **Artist_Diversity_Filter**: Component that prevents same-artist repetition by limiting consecutive songs from the same artist.
- **Mixed_Playlist**: A recommended playlist containing songs from multiple artists (minimum 3 different artists).
- **InnerTube_API**: YouTube Music API interface used for fetching music metadata and recommendations.
- **Local_Playlist**: User-created playlists stored in Room database (PlaylistEntity and PlaylistSongEntity).
- **Interaction_Signal**: User behavior data including play count, skip count, likes, downloads, and repeat count.

## Requirements

### Requirement 1: Analyze All Local Playlists

**User Story:** As a user, I want the system to analyze all my local playlists, so that recommendations reflect my complete music taste.

#### Acceptance Criteria

1. WHEN the Recommendation_Engine initializes, THE Playlist_Analyzer SHALL scan all Local_Playlists from the Room database
2. THE Playlist_Analyzer SHALL extract song metadata (title, artist, duration) from all PlaylistSongEntity records
3. THE Playlist_Analyzer SHALL build a comprehensive Taste_Profile by aggregating data from all Local_Playlists
4. THE Playlist_Analyzer SHALL include imported playlist songs with a weight of +3.0 in Taste_Profile calculation
5. IF no Local_Playlists exist, THEN THE Playlist_Analyzer SHALL build Taste_Profile from Interaction_Signals and history data only

### Requirement 2: Detect Genre and Music Categories

**User Story:** As a user, I want the system to identify music genres from my songs, so that recommendations match my preferred music styles.

#### Acceptance Criteria

1. THE Genre_Detector SHALL identify genres including Lofi, Rap/Hip-Hop, Bollywood, Punjabi Folk, Pop, Indie, and Rock
2. WHEN a song is analyzed, THE Genre_Detector SHALL infer genre using keyword matching on title and artist metadata
3. THE Genre_Detector SHALL detect language (English, Hindi, Punjabi, Tamil, Korean) using linguistic keyword patterns
4. THE Genre_Detector SHALL detect mood categories (Romantic, Sad, Energetic, Happy, Chill/Relaxed, Dark) from song metadata
5. THE Genre_Detector SHALL calculate acoustic properties (energy level 0.0-1.0, tempo in BPM) for each song
6. THE Genre_Detector SHALL store detected genres, moods, and acoustic properties in the Taste_Profile

### Requirement 3: Track Artist Listening Patterns

**User Story:** As a user, I want the system to track which artists I listen to most, so that recommendations include my favorite artists.

#### Acceptance Criteria

1. THE Recommendation_Engine SHALL calculate artist affinity scores based on play count, repeat count, likes, and downloads
2. THE Recommendation_Engine SHALL assign +5.0 points per complete playthrough for an artist's songs
3. THE Recommendation_Engine SHALL assign +6.0 points per repeat for an artist's songs
4. THE Recommendation_Engine SHALL assign +10.0 points when a song is liked
5. THE Recommendation_Engine SHALL assign +8.0 points when a song is downloaded
6. THE Recommendation_Engine SHALL subtract -6.0 points per skip within 20 seconds for an artist's songs
7. THE Recommendation_Engine SHALL maintain a top 8 artist list in Taste_Profile sorted by affinity score
8. THE Recommendation_Engine SHALL track skipped artists (skip count > play count) in a blacklist

### Requirement 4: Generate Mixed Artist Recommendations

**User Story:** As a user, I want recommendations with multiple different artists, so that my playlists are diverse and not repetitive.

#### Acceptance Criteria

1. THE Recommendation_Engine SHALL generate Mixed_Playlists containing songs from at least 3 different artists
2. THE Artist_Diversity_Filter SHALL limit consecutive songs from the same artist to maximum 1 occurrence
3. THE Artist_Diversity_Filter SHALL cap maximum 2 songs per artist in a single recommendation section (12 songs)
4. WHEN generating recommendations, THE Recommendation_Engine SHALL prioritize artist diversity over similarity score
5. THE Artist_Diversity_Filter SHALL apply artist normalization to handle "Artist - Topic" and "Artist VEVO" as the same artist
6. THE Recommendation_Engine SHALL exclude songs from artists in the skipped artists blacklist

### Requirement 5: Match User Taste Profile

**User Story:** As a user, I want recommendations that match my overall taste, so that I discover music I will enjoy.

#### Acceptance Criteria

1. THE Recommendation_Engine SHALL calculate taste similarity score (0.0-1.0) comparing candidate songs to Taste_Profile
2. THE Recommendation_Engine SHALL weight genre matching at 35% in similarity calculation
3. THE Recommendation_Engine SHALL weight mood matching at 20% in similarity calculation
4. THE Recommendation_Engine SHALL weight language matching at 15% in similarity calculation
5. THE Recommendation_Engine SHALL weight energy delta at 15% in similarity calculation (closer to target energy = higher score)
6. THE Recommendation_Engine SHALL weight tempo delta at 15% in similarity calculation (closer to target tempo = higher score)
7. THE Recommendation_Engine SHALL apply +15% bonus to official content (official artist channels, verified sources)
8. THE Recommendation_Engine SHALL filter out compilation tracks, non-music videos, and unofficial content

### Requirement 6: Create Smart Genre-Based Mixes

**User Story:** As a user, I want curated genre-based mixes, so that I can explore music moods that match my preferences.

#### Acceptance Criteria

1. THE Recommendation_Engine SHALL generate genre-based mixes for Lofi, Rap/Hip-Hop, Bollywood, Punjabi Folk, Pop, Indie, and Rock
2. WHEN generating a genre mix, THE Recommendation_Engine SHALL use genre-specific search queries from GENRE_CONFIGS
3. THE Recommendation_Engine SHALL filter mix candidates to match target mood specified in GENRE_CONFIGS
4. THE Recommendation_Engine SHALL assign gradient color schemes to each genre mix as defined in GENRE_CONFIGS
5. THE Recommendation_Engine SHALL apply Artist_Diversity_Filter to all genre mixes
6. THE Recommendation_Engine SHALL cache genre mixes for 15 minutes to minimize API calls

### Requirement 7: Provide Quick Picks Recommendations

**User Story:** As a user, I want quick personalized recommendations, so that I can instantly play music I'll enjoy.

#### Acceptance Criteria

1. THE Recommendation_Engine SHALL generate Quick Picks from cached related songs, forgotten favorites, and InnerTube_API related tracks
2. WHEN generating Quick Picks, THE Recommendation_Engine SHALL query related songs from the last 5 played tracks
3. THE Recommendation_Engine SHALL include forgotten favorites (songs played before but not in last 14 days) with play time > 30 seconds
4. THE Recommendation_Engine SHALL fetch YouTube Music related songs for the most recent history entry
5. IF Quick Picks count is less than 6, THEN THE Recommendation_Engine SHALL fallback to TasteDNA-based search queries
6. THE Recommendation_Engine SHALL limit Quick Picks to 20 songs maximum
7. THE Recommendation_Engine SHALL cache Quick Picks for 15 minutes

### Requirement 8: Generate Smart Radio from Seed Track

**User Story:** As a user, I want to start a radio station from a song, so that I discover similar music continuously.

#### Acceptance Criteria

1. WHEN a seed track is provided, THE Recommendation_Engine SHALL generate a radio queue of 20 similar songs
2. THE Recommendation_Engine SHALL fetch related songs using InnerTube_API related browse endpoint
3. THE Recommendation_Engine SHALL apply taste similarity scoring to rank candidate songs
4. THE Artist_Diversity_Filter SHALL prevent consecutive songs from the same artist in radio queue
5. THE Recommendation_Engine SHALL filter out title variants of the seed song using Levenshtein distance (similarity > 70%)
6. IF InnerTube_API returns no results, THEN THE Recommendation_Engine SHALL fallback to TasteDNA search queries using seed metadata
7. THE Recommendation_Engine SHALL cache radio queues per seed track for 15 minutes

### Requirement 9: Filter Non-Music and Low-Quality Content

**User Story:** As a user, I want only high-quality music recommendations, so that I don't get reaction videos, compilations, or unofficial content.

#### Acceptance Criteria

1. THE Recommendation_Engine SHALL filter out videos with blacklist keywords (explained, reaction, tutorial, interview, podcast, meme, comedy)
2. THE Recommendation_Engine SHALL filter out compilation tracks with keywords (top 10, mashup, jukebox, nonstop, full album)
3. THE Recommendation_Engine SHALL filter out tracks longer than 15 minutes duration
4. THE Recommendation_Engine SHALL filter out unofficial content (remix, slowed, reverb, cover, karaoke, nightcore) unless from official channels
5. THE Recommendation_Engine SHALL identify corporate/distributor channels (T-Series, Zee Music, Sony Music, etc.) as official
6. THE Recommendation_Engine SHALL identify "- Topic" and "VEVO" channels as official artist channels
7. THE Recommendation_Engine SHALL apply content filters to all recommendation endpoints (Quick Picks, Related Songs, Radio, Genre Mixes)

### Requirement 10: Cache and Optimize API Calls

**User Story:** As a user, I want fast recommendations without delays, so that my experience is smooth and responsive.

#### Acceptance Criteria

1. THE Recommendation_Engine SHALL cache all recommendation results for 15 minutes in SharedPreferences
2. WHEN cached data exists and is not expired, THE Recommendation_Engine SHALL return cached results immediately
3. THE Recommendation_Engine SHALL store cache timestamps to determine expiration
4. WHEN cache is expired or empty, THE Recommendation_Engine SHALL fetch fresh data from InnerTube_API
5. THE Recommendation_Engine SHALL invalidate all caches when user explicitly requests refresh
6. THE Recommendation_Engine SHALL cache related song maps in Room database for offline quick picks
7. THE Recommendation_Engine SHALL cache song play metadata for forgotten favorites calculation

### Requirement 11: Integrate with Existing Database Schema

**User Story:** As a developer, I want the recommendation system to work with existing database entities, so that no schema migration is required.

#### Acceptance Criteria

1. THE Playlist_Analyzer SHALL read playlist data from PlaylistEntity and PlaylistSongEntity tables
2. THE Recommendation_Engine SHALL read interaction signals from InteractionSignal table
3. THE Recommendation_Engine SHALL read play history from HistoryEntry table
4. THE Recommendation_Engine SHALL read liked songs from LikedSong table
5. THE Recommendation_Engine SHALL store related song maps in RelatedSongMap table
6. THE Recommendation_Engine SHALL store song play metadata in SongCacheMeta table
7. THE Recommendation_Engine SHALL utilize existing FollowedArtist table for artist preference weights

### Requirement 12: Support Cold Start Scenarios

**User Story:** As a new user, I want reasonable recommendations even without listening history, so that I can start discovering music immediately.

#### Acceptance Criteria

1. WHEN Taste_Profile has no interaction signals and no playlists, THE Recommendation_Engine SHALL generate default genre-based mixes
2. THE Recommendation_Engine SHALL use neutral acoustic targets (energy = 0.58, tempo = 105 BPM) for cold start users
3. THE Recommendation_Engine SHALL query trending music across multiple genres for cold start recommendations
4. WHEN user imports playlists, THE Recommendation_Engine SHALL immediately incorporate imported songs into Taste_Profile with +3.0 weight
5. THE Recommendation_Engine SHALL regenerate recommendations after first 5 play sessions to warm up Taste_Profile
