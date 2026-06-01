package com.vinmusic.data

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.vinmusic.data.db.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FirebaseSyncManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: VinDatabase
) {
    private val TAG = "FirebaseSyncManager"
    private val firestore: FirebaseFirestore by lazy { FirebaseFirestore.getInstance() }
    private val auth: FirebaseAuth by lazy { FirebaseAuth.getInstance() }

    private fun getUserId(): String? = auth.currentUser?.uid

    /**
     * Backup all local user data (Liked Songs, Followed Artists, and Playlists) to Cloud Firestore.
     */
    suspend fun backupLocalDataToCloud(): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = getUserId() ?: return@withContext Result.failure(Exception("User not authenticated"))
        try {
            Log.d(TAG, "Starting full cloud backup for user=$uid")
            
            // 1. Fetch local liked songs
            val likedSongs = db.likedSongDao().getAll()
            val likedList = likedSongs.map {
                mapOf(
                    "videoId" to it.videoId,
                    "title" to it.title,
                    "author" to it.author,
                    "durationText" to it.durationText,
                    "likedAt" to it.likedAt
                )
            }
            
            // 2. Fetch local followed artists
            val followedArtists = db.followedArtistDao().getAll()
            val artistList = followedArtists.map {
                mapOf(
                    "channelId" to it.channelId,
                    "name" to it.name,
                    "thumbnail" to it.thumbnail,
                    "subscriberCount" to it.subscriberCount,
                    "followedAt" to it.followedAt
                )
            }

            // 3. Fetch local playlists and their songs
            val localPlaylists = db.playlistDao().getAll()
            val allPlaylistSongs = db.playlistDao().getAllPlaylistSongs()
            
            val playlistList = localPlaylists.map { playlist ->
                val songsInPlaylist = allPlaylistSongs
                    .filter { it.playlistId == playlist.id }
                    .map {
                        mapOf(
                            "videoId" to it.videoId,
                            "title" to it.title,
                            "author" to it.author,
                            "durationText" to it.durationText,
                            "position" to it.position
                        )
                    }
                mapOf(
                    "name" to playlist.name,
                    "createdAt" to playlist.createdAt,
                    "songs" to songsInPlaylist
                )
            }

            // 4. Batch write to Firestore
            val userDocRef = firestore.collection("users").document(uid)
            val backupData = mapOf(
                "lastBackupAt" to System.currentTimeMillis(),
                "likedSongs" to likedList,
                "followedArtists" to artistList,
                "playlists" to playlistList
            )

            userDocRef.set(backupData).await()
            Log.d(TAG, "Cloud backup completed successfully!")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud backup failed: ${e.message}", e)
            Result.failure(e)
        }
    }

    /**
     * Restore all user data from Cloud Firestore and merge/overwrite into local Room Database.
     */
    suspend fun restoreDataFromCloud(): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = getUserId() ?: return@withContext Result.failure(Exception("User not authenticated"))
        try {
            Log.d(TAG, "Starting cloud data restore for user=$uid")
            
            val userDocRef = firestore.collection("users").document(uid)
            val snapshot = userDocRef.get().await()
            
            if (!snapshot.exists()) {
                Log.d(TAG, "No backup data found on cloud for this user.")
                return@withContext Result.success(Unit)
            }

            // 1. Restore Liked Songs
            val cloudLiked = snapshot.get("likedSongs") as? List<Map<String, Any>>
            cloudLiked?.forEach { map ->
                val videoId = map["videoId"] as? String ?: return@forEach
                val title = map["title"] as? String ?: ""
                val author = map["author"] as? String ?: ""
                val durationText = map["durationText"] as? String ?: ""
                val likedAt = (map["likedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                
                db.likedSongDao().insert(LikedSong(videoId, title, author, durationText, likedAt))
                
                // Update local interaction signals as well
                val sig = db.interactionSignalDao().get(videoId)
                if (sig != null) {
                    if (!sig.isLiked) {
                        sig.isLiked = true
                        db.interactionSignalDao().insert(sig)
                    }
                } else {
                    db.interactionSignalDao().insert(
                        InteractionSignal(
                            videoId = videoId,
                            title = title,
                            author = author,
                            durationText = durationText,
                            isLiked = true
                        )
                    )
                }
            }

            // 2. Restore Followed Artists
            val cloudArtists = snapshot.get("followedArtists") as? List<Map<String, Any>>
            cloudArtists?.forEach { map ->
                val channelId = map["channelId"] as? String ?: return@forEach
                val name = map["name"] as? String ?: ""
                val thumbnail = map["thumbnail"] as? String ?: ""
                val subscriberCount = map["subscriberCount"] as? String ?: ""
                val followedAt = (map["followedAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                
                db.followedArtistDao().insert(
                    FollowedArtist(channelId, name, thumbnail, subscriberCount, followedAt)
                )
            }

            // 3. Restore Playlists
            val cloudPlaylists = snapshot.get("playlists") as? List<Map<String, Any>>
            val currentLocalPlaylists = db.playlistDao().getAll()
            
            cloudPlaylists?.forEach { playlistMap ->
                val name = playlistMap["name"] as? String ?: ""
                val createdAt = (playlistMap["createdAt"] as? Number)?.toLong() ?: System.currentTimeMillis()
                val songs = playlistMap["songs"] as? List<Map<String, Any>> ?: emptyList()
                
                // Check if a playlist with this name already exists
                var playlistId = currentLocalPlaylists.find { it.name.equals(name, ignoreCase = true) }?.id
                if (playlistId == null) {
                    playlistId = db.playlistDao().insertPlaylist(
                        PlaylistEntity(name = name, createdAt = createdAt)
                    )
                }
                
                // Add songs to the playlist
                val songsToInsert = songs.map { songMap ->
                    val videoId = songMap["videoId"] as? String ?: ""
                    val title = songMap["title"] as? String ?: ""
                    val author = songMap["author"] as? String ?: ""
                    val durationText = songMap["durationText"] as? String ?: ""
                    val position = (songMap["position"] as? Number)?.toInt() ?: 0
                    
                    PlaylistSongEntity(
                        playlistId = playlistId!!,
                        videoId = videoId,
                        title = title,
                        author = author,
                        durationText = durationText,
                        position = position
                    )
                }
                if (songsToInsert.isNotEmpty()) {
                    db.playlistDao().insertSongs(songsToInsert)
                }
            }

            Log.d(TAG, "Cloud data restore completed successfully!")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Cloud data restore failed: ${e.message}", e)
            Result.failure(e)
        }
    }
}
