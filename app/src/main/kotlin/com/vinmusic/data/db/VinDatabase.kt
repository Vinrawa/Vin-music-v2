package com.vinmusic.data.db

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

// ── Entities ──────────────────────────────────────────────────────────────────

@Entity(tableName = "liked_songs")
data class LikedSong(
    @PrimaryKey val videoId: String,
    val title:       String,
    val author:      String,
    val durationText: String,
    val likedAt:     Long = System.currentTimeMillis()
)

@Entity(tableName = "history", indices = [Index(value = ["playedAt"])])
data class HistoryEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: String,
    val title:   String,
    val author:  String,
    val genre: String? = null,
    val durationText: String,
    val playedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "downloads", indices = [Index(value = ["status"]), Index(value = ["downloadedAt"])])
data class DownloadEntity(
    @PrimaryKey val videoId:   String,
    val title:       String,
    val author:      String,
    val durationText: String,
    val filePath:    String,
    val sizeBytes:   Long   = 0L,
    val downloadedAt: Long  = System.currentTimeMillis(),
    val status:      String = "completed", // queued / downloading / completed / failed
    val progress:    Int    = 0,
    val thumbnailPath: String? = null,  // Local path to cached thumbnail
    val thumbnailUrl: String? = null    // Original YouTube thumbnail URL
)

@Entity(tableName = "playlists", indices = [Index(value = ["isPinned", "createdAt"])])
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name:      String,
    val createdAt: Long = System.currentTimeMillis(),
    val isPinned:  Boolean = false
)

@Entity(tableName = "playlist_songs",
    primaryKeys = ["playlistId", "videoId"],
    indices = [Index(value = ["playlistId"]), Index(value = ["position"])])
data class PlaylistSongEntity(
    val playlistId: Long,
    val videoId:    String,
    val title:      String,
    val author:     String,
    val durationText: String,
    val position:   Int = 0
)

@Entity(tableName = "queue")
data class QueueEntity(
    @PrimaryKey val position: Int,
    val videoId:      String,
    val title:        String,
    val author:       String,
    val durationText: String
)

@Entity(tableName = "interaction_signals")
data class InteractionSignal(
    @PrimaryKey val videoId: String,
    val title: String,
    val author: String,
    val durationText: String,
    var playCount: Int = 0,
    var skipCount: Int = 0,
    var completeCount: Int = 0,
    var repeatCount: Int = 0,
    var lastPlayedAt: Long = 0,
    var isLiked: Boolean = false,
    var isDownloaded: Boolean = false,
    var searchClickCount: Int = 0,
    var skip20sCount: Int = 0,
    // TasteDNA attributes
    var energy: Int = -1,
    var valence: Int = -1,
    var danceability: Int = -1,
    var acousticness: Int = -1,
    var tempo: Int = -1
)

@Entity(tableName = "cached_lyrics")
data class CachedLyricsEntity(
    @PrimaryKey val videoId: String,
    val lyricsType: String, // "synced", "plain", "not_found"
    val content: String, // JSON for synced, raw text for plain, empty for not_found
    val source: String = "", // which provider supplied this lyrics
    val fetchedAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false
)

@Entity(tableName = "user_accounts")
data class UserAccount(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val gender: String,
    val dob: String,
    val email: String,
    val phone: String
)

/** Metrolist-style related song cache (seed → related tracks from YouTube Music). */
@Entity(tableName = "related_song_map", primaryKeys = ["songId", "relatedVideoId"])
data class RelatedSongMap(
    val songId: String,
    val relatedVideoId: String,
    val title: String,
    val author: String,
    val durationText: String,
    val savedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "song_cache_meta")
data class SongCacheMeta(
    @PrimaryKey val videoId: String,
    val title: String,
    val author: String,
    val durationText: String,
    val lastPlayedAt: Long = System.currentTimeMillis(),
    val totalPlayTime: Long = 0L,
)

@Entity(tableName = "followed_artists")
data class FollowedArtist(
    @PrimaryKey val channelId: String,
    val name: String,
    val thumbnail: String,
    val subscriberCount: String = "",
    val followedAt: Long = System.currentTimeMillis()
)

@Entity(tableName = "song_feature_cache")
data class SongFeatureCache(
    @PrimaryKey val songKey: String,
    val bpmReal: Float,
    val energyReal: Float,
    val genreTags: String, // serialized json list
    val moodTags: String,  // serialized json list
    val title: String,
    val artist: String,
    val synced: Boolean = false,
    val likedByCount: Int = 0,
    val cachedAt: Long = System.currentTimeMillis()
)


// ── DAOs ──────────────────────────────────────────────────────────────────────

@Dao
interface LikedSongDao {
    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    fun getAllFlow(): Flow<List<LikedSong>>

    @Query("SELECT * FROM liked_songs ORDER BY likedAt DESC")
    suspend fun getAll(): List<LikedSong>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(song: LikedSong)

    @Query("DELETE FROM liked_songs WHERE videoId = :id")
    suspend fun delete(id: String)

    @Query("SELECT EXISTS(SELECT 1 FROM liked_songs WHERE videoId = :id)")
    suspend fun isLiked(id: String): Boolean
}

@Dao
interface HistoryDao {
    @Query("SELECT * FROM history ORDER BY playedAt DESC LIMIT 50")
    fun getRecentFlow(): Flow<List<HistoryEntry>>

    @Query("SELECT * FROM history ORDER BY playedAt DESC")
    suspend fun getAllHistory(): List<HistoryEntry>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: HistoryEntry)

    @Query("DELETE FROM history")
    suspend fun clearAll()
}

@Dao
interface DownloadDao {
    @Query("SELECT * FROM downloads ORDER BY downloadedAt DESC")
    fun getAllFlow(): Flow<List<DownloadEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(dl: DownloadEntity)

    @Query("DELETE FROM downloads WHERE videoId = :id")
    suspend fun delete(id: String)

    @Query("SELECT * FROM downloads WHERE videoId = :id LIMIT 1")
    suspend fun get(id: String): DownloadEntity?

    @Query("SELECT filePath FROM downloads WHERE videoId = :id")
    suspend fun getFilePath(id: String): String?

    @Query("SELECT * FROM downloads WHERE status = :status ORDER BY downloadedAt ASC")
    suspend fun getByStatus(status: String): List<DownloadEntity>

    @Query("UPDATE downloads SET progress = :progress, sizeBytes = :sizeBytes WHERE videoId = :id")
    suspend fun updateProgress(id: String, progress: Int, sizeBytes: Long)
}

@Dao
interface PlaylistDao {
    @Query("SELECT * FROM playlists ORDER BY isPinned DESC, createdAt DESC")
    fun getAllFlow(): Flow<List<PlaylistEntity>>

    @Query("SELECT * FROM playlists ORDER BY isPinned DESC, createdAt DESC")
    suspend fun getAll(): List<PlaylistEntity>

    @Query("SELECT * FROM playlists WHERE id = :id LIMIT 1")
    suspend fun getPlaylist(id: Long): PlaylistEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertPlaylist(p: PlaylistEntity): Long

    @Query("UPDATE playlists SET isPinned = NOT isPinned WHERE id = :id")
    suspend fun togglePin(id: Long)

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun deletePlaylist(id: Long)

    /** playlist_songs has no FK cascade — deleting only the playlist row orphaned its songs forever. */
    @Query("DELETE FROM playlist_songs WHERE playlistId = :id")
    suspend fun deleteSongsForPlaylist(id: Long)

    @Transaction
    suspend fun deletePlaylistWithSongs(id: Long) {
        deleteSongsForPlaylist(id)
        deletePlaylist(id)
    }

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :id ORDER BY position")
    fun getSongsFlow(id: Long): Flow<List<PlaylistSongEntity>>

    @Query("SELECT * FROM playlist_songs")
    suspend fun getAllPlaylistSongs(): List<PlaylistSongEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSong(s: PlaylistSongEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSongs(songs: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :pid AND videoId = :vid")
    suspend fun removeSong(pid: Long, vid: String)
}

@Dao
interface QueueDao {
    @Query("SELECT * FROM queue ORDER BY position")
    suspend fun getAll(): List<QueueEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<QueueEntity>)

    @Query("DELETE FROM queue")
    suspend fun clear()
}

@Dao
interface InteractionSignalDao {
    @Query("SELECT * FROM interaction_signals")
    suspend fun getAll(): List<InteractionSignal>

    @Query("SELECT * FROM interaction_signals ORDER BY playCount DESC LIMIT 20")
    fun getTopPlayedSongsFlow(): kotlinx.coroutines.flow.Flow<List<InteractionSignal>>

    @Query("SELECT * FROM interaction_signals WHERE videoId = :id LIMIT 1")
    suspend fun get(id: String): InteractionSignal?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(signal: InteractionSignal)

    @Query("UPDATE interaction_signals SET isLiked = :liked WHERE videoId = :id")
    suspend fun updateLiked(id: String, liked: Boolean)

    @Query("UPDATE interaction_signals SET isDownloaded = :downloaded WHERE videoId = :id")
    suspend fun updateDownloaded(id: String, downloaded: Boolean)
}

@Dao
interface CachedLyricsDao {
    @Query("SELECT * FROM cached_lyrics WHERE videoId = :id LIMIT 1")
    suspend fun get(id: String): CachedLyricsEntity?

    @Query("SELECT * FROM cached_lyrics")
    suspend fun getAll(): List<CachedLyricsEntity>

    @Query("SELECT * FROM cached_lyrics WHERE content != '' ORDER BY RANDOM() LIMIT 5")
    suspend fun getRandomLyrics(): List<CachedLyricsEntity>

    @Query("SELECT * FROM cached_lyrics WHERE pinned = 0 AND fetchedAt < :cutoffMs AND lyricsType != 'not_found'")
    suspend fun getStaleEntries(cutoffMs: Long): List<CachedLyricsEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(lyrics: CachedLyricsEntity)

    @Query("DELETE FROM cached_lyrics WHERE videoId = :id")
    suspend fun delete(id: String)
}

@Dao
interface RelatedSongDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(rows: List<RelatedSongMap>)

    @Query("DELETE FROM related_song_map WHERE songId = :songId")
    suspend fun deleteForSong(songId: String)

    @Query(
        """
        SELECT relatedVideoId AS videoId, title, author, durationText
        FROM related_song_map
        WHERE songId IN (SELECT videoId FROM history ORDER BY playedAt DESC LIMIT 5)
           OR songId IN (SELECT videoId FROM interaction_signals ORDER BY lastPlayedAt DESC LIMIT 5)
           OR songId IN (SELECT videoId FROM song_cache_meta ORDER BY lastPlayedAt DESC LIMIT 5)
        GROUP BY relatedVideoId
        ORDER BY MAX(savedAt) DESC
        LIMIT :limit
        """
    )
    suspend fun quickPickVideos(limit: Int = 20): List<QuickPickRow>

    @Query(
        """
        SELECT relatedVideoId AS videoId, title, author, durationText
        FROM related_song_map
        WHERE songId = :songId
        ORDER BY savedAt DESC
        LIMIT :limit
        """
    )
    suspend fun relatedForSong(songId: String, limit: Int = 20): List<QuickPickRow>

    @Query("SELECT COUNT(1) FROM related_song_map WHERE songId = :songId LIMIT 1")
    suspend fun hasRelated(songId: String): Boolean
}

data class QuickPickRow(
    val videoId: String,
    val title: String,
    val author: String,
    val durationText: String,
)

@Dao
interface SongCacheMetaDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(meta: SongCacheMeta)

    @Query("SELECT * FROM song_cache_meta ORDER BY totalPlayTime DESC LIMIT :limit")
    suspend fun topPlayed(limit: Int): List<SongCacheMeta>

    @Query("SELECT * FROM song_cache_meta WHERE videoId = :id LIMIT 1")
    suspend fun get(id: String): SongCacheMeta?

    @Query(
        """
        SELECT * FROM song_cache_meta
        WHERE lastPlayedAt < :before
        ORDER BY totalPlayTime DESC
        LIMIT :limit
        """
    )
    suspend fun forgottenFavorites(before: Long, limit: Int): List<SongCacheMeta>
}

@Dao
interface UserAccountDao {
    @Query("SELECT * FROM user_accounts WHERE email = :email LIMIT 1")
    suspend fun getByEmail(email: String): UserAccount?

    @Query("SELECT * FROM user_accounts WHERE phone = :phone LIMIT 1")
    suspend fun getByPhone(phone: String): UserAccount?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(account: UserAccount): Long
}

@Dao
interface FollowedArtistDao {
    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    fun getAllFlow(): Flow<List<FollowedArtist>>

    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    suspend fun getAll(): List<FollowedArtist>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(artist: FollowedArtist)

    @Query("DELETE FROM followed_artists WHERE channelId = :channelId")
    suspend fun delete(channelId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE channelId = :channelId)")
    suspend fun isFollowing(channelId: String): Boolean
}

@Dao
interface SongFeatureCacheDao {
    @Query("SELECT * FROM song_feature_cache WHERE songKey = :songKey LIMIT 1")
    suspend fun get(songKey: String): SongFeatureCache?

    @Query("SELECT * FROM song_feature_cache")
    suspend fun getAll(): List<SongFeatureCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(cache: SongFeatureCache)

    @Query("DELETE FROM song_feature_cache WHERE songKey = :songKey")
    suspend fun delete(songKey: String)

    @Query("SELECT * FROM song_feature_cache WHERE synced = 0")
    suspend fun getUnsynced(): List<SongFeatureCache>

    @Query("UPDATE song_feature_cache SET synced = 1 WHERE songKey = :songKey")
    suspend fun markSynced(songKey: String)
}

// ── Database ──────────────────────────────────────────────────────────────────

@Database(
    entities  = [LikedSong::class, HistoryEntry::class, DownloadEntity::class,
                 PlaylistEntity::class, PlaylistSongEntity::class, QueueEntity::class,
                 InteractionSignal::class, CachedLyricsEntity::class, UserAccount::class,
                 RelatedSongMap::class, SongCacheMeta::class, FollowedArtist::class,
                 SongFeatureCache::class],
    version   = 16,
    exportSchema = false
)
abstract class VinDatabase : RoomDatabase() {
    abstract fun likedSongDao(): LikedSongDao
    abstract fun historyDao():   HistoryDao
    abstract fun downloadDao():  DownloadDao
    abstract fun playlistDao():  PlaylistDao
    abstract fun queueDao():     QueueDao
    abstract fun interactionSignalDao(): InteractionSignalDao
    abstract fun cachedLyricsDao(): CachedLyricsDao
    abstract fun userAccountDao(): UserAccountDao
    abstract fun relatedSongDao(): RelatedSongDao
    abstract fun songCacheMetaDao(): SongCacheMetaDao
    abstract fun followedArtistDao(): FollowedArtistDao
    abstract fun songFeatureCacheDao(): SongFeatureCacheDao

    companion object {
        @Volatile private var INSTANCE: VinDatabase? = null

        private val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Create followed_artists table if it doesn't exist
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `followed_artists` (
                        `channelId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `thumbnail` TEXT NOT NULL,
                        `subscriberCount` TEXT NOT NULL DEFAULT '',
                        `followedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`channelId`)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE playlists ADD COLUMN isPinned INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_10_11 = object : Migration(10, 11) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `cached_lyrics` (
                        `videoId` TEXT NOT NULL,
                        `lyricsType` TEXT NOT NULL,
                        `content` TEXT NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `user_accounts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `gender` TEXT NOT NULL,
                        `dob` TEXT NOT NULL,
                        `email` TEXT NOT NULL,
                        `phone` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `related_song_map` (
                        `songId` TEXT NOT NULL,
                        `relatedVideoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `durationText` TEXT NOT NULL,
                        `savedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`songId`, `relatedVideoId`)
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `song_cache_meta` (
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `durationText` TEXT NOT NULL,
                        `lastPlayedAt` INTEGER NOT NULL,
                        `totalPlayTime` INTEGER NOT NULL,
                        PRIMARY KEY(`videoId`)
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `followed_artists` (
                        `channelId` TEXT NOT NULL,
                        `name` TEXT NOT NULL,
                        `thumbnail` TEXT NOT NULL,
                        `subscriberCount` TEXT NOT NULL DEFAULT '',
                        `followedAt` INTEGER NOT NULL DEFAULT 0,
                        PRIMARY KEY(`channelId`)
                    )
                """.trimIndent())
            }
        }
        
        private val MIGRATION_11_12 = object : Migration(11, 12) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE interaction_signals ADD COLUMN energy INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE interaction_signals ADD COLUMN valence INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE interaction_signals ADD COLUMN danceability INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE interaction_signals ADD COLUMN acousticness INTEGER NOT NULL DEFAULT -1")
                database.execSQL("ALTER TABLE interaction_signals ADD COLUMN tempo INTEGER NOT NULL DEFAULT -1")
            }
        }

        private val MIGRATION_12_13 = object : Migration(12, 13) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_history_playedAt` ON `history` (`playedAt`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_status` ON `downloads` (`status`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_downloads_downloadedAt` ON `downloads` (`downloadedAt`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_playlists_isPinned_createdAt` ON `playlists` (`isPinned`, `createdAt`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_songs_playlistId` ON `playlist_songs` (`playlistId`)")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_playlist_songs_position` ON `playlist_songs` (`position`)")
            }
        }

        private val MIGRATION_13_14 = object : Migration(13, 14) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `song_feature_cache` (
                        `songKey` TEXT NOT NULL,
                        `bpmReal` REAL NOT NULL,
                        `energyReal` REAL NOT NULL,
                        `genreTags` TEXT NOT NULL,
                        `moodTags` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `artist` TEXT NOT NULL,
                        `synced` INTEGER NOT NULL DEFAULT 0,
                        `likedByCount` INTEGER NOT NULL DEFAULT 0,
                        `cachedAt` INTEGER NOT NULL,
                        PRIMARY KEY(`songKey`)
                    )
                """.trimIndent())
            }
        }

        private val MIGRATION_14_15 = object : Migration(14, 15) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // These nullable thumbnail columns were added to DownloadEntity
                // but were missing from the 14 -> 15 migration. Existing users
                // therefore crashed during Room schema validation on launch.
                database.execSQL("ALTER TABLE `downloads` ADD COLUMN `thumbnailPath` TEXT")
                database.execSQL("ALTER TABLE `downloads` ADD COLUMN `thumbnailUrl` TEXT")
                database.execSQL("ALTER TABLE `cached_lyrics` ADD COLUMN `source` TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE `cached_lyrics` ADD COLUMN `fetchedAt` INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE `cached_lyrics` ADD COLUMN `pinned` INTEGER NOT NULL DEFAULT 0")
            }
        }

        private val MIGRATION_15_16 = object : Migration(15, 16) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // History used videoId as its primary key, so every repeat listen
                // overwrote the prior event. Rebuild it with an event id while
                // retaining every existing entry.
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `history_new` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `videoId` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `author` TEXT NOT NULL,
                        `genre` TEXT,
                        `durationText` TEXT NOT NULL,
                        `playedAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    INSERT INTO `history_new` (`videoId`, `title`, `author`, `genre`, `durationText`, `playedAt`)
                    SELECT `videoId`, `title`, `author`, `genre`, `durationText`, `playedAt` FROM `history`
                """.trimIndent())
                database.execSQL("DROP TABLE `history`")
                database.execSQL("ALTER TABLE `history_new` RENAME TO `history`")
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_history_playedAt` ON `history` (`playedAt`)")
            }
        }

        fun getInstance(ctx: Context): VinDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(ctx, VinDatabase::class.java, "vin_music.db")
                    .addMigrations(MIGRATION_8_9, MIGRATION_9_10, MIGRATION_10_11, MIGRATION_11_12, MIGRATION_12_13, MIGRATION_13_14, MIGRATION_14_15, MIGRATION_15_16)
                    .build().also { INSTANCE = it }
            }
    }
}

