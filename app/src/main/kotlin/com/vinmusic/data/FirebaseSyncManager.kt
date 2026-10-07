package com.vinmusic.data

import android.content.Context
import android.util.Log
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.DocumentReference
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

    private suspend fun writeInBatches(writes: List<Pair<DocumentReference, Map<String, Any>>>) {
        // Firestore accepts at most 500 operations per batch. Leave room below
        // that ceiling so a large music library can still be backed up safely.
        writes.chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { (document, data) -> batch.set(document, data) }
            batch.commit().await()
        }
    }

    private suspend fun deleteInBatches(documents: List<DocumentReference>) {
        documents.chunked(400).forEach { chunk ->
            val batch = firestore.batch()
            chunk.forEach { batch.delete(it) }
            batch.commit().await()
        }
    }

    /**
     * Backup all local user data (Liked Songs, Followed Artists, and Playlists) to Cloud Firestore.
     */
    suspend fun backupLocalDataToCloud(): Result<Unit> = withContext(Dispatchers.IO) {
        val uid = getUserId() ?: return@withContext Result.failure(Exception("User not authenticated"))
        try {
            Log.d(TAG, "Starting full cloud backup for user=$uid")
            
            // 1. Fetch local liked songs
            val likedSongs = db.likedSongDao().getAll()
            val likedWrites = likedSongs.filter { it.videoId.isNotBlank() }.map { song ->
                userDocRef(uid).collection("likedSongs").document(song.videoId) to mapOf(
                    "videoId" to song.videoId,
                    "title" to song.title,
                    "author" to song.author,
                    "durationText" to song.durationText,
                    "likedAt" to song.likedAt
                )
            }
            
            // 2. Fetch local followed artists
            val followedArtists = db.followedArtistDao().getAll()
            val artistWrites = followedArtists.filter { it.channelId.isNotBlank() }.map { artist ->
                userDocRef(uid).collection("followedArtists").document(artist.channelId) to mapOf(
                    "channelId" to artist.channelId,
                    "name" to artist.name,
                    "thumbnail" to artist.thumbnail,
                    "subscriberCount" to artist.subscriberCount,
                    "followedAt" to artist.followedAt
                )
            }

            // 3. Fetch local playlists and their songs
            val localPlaylists = db.playlistDao().getAll()
            val allPlaylistSongs = db.playlistDao().getAllPlaylistSongs()
            
            val playlistWrites = mutableListOf<Pair<DocumentReference, Map<String, Any>>>()
            for (playlist in localPlaylists) {
                val songsInPlaylist = allPlaylistSongs
                    .filter { it.playlistId == playlist.id }
                val playlistRef = userDocRef(uid).collection("playlists")
                    .document("${playlist.createdAt}_${playlist.id}")
                val songWrites = songsInPlaylist.filter { it.videoId.isNotBlank() }.map { song ->
                    playlistRef.collection("songs").document(song.videoId) to mapOf(
                        "videoId" to song.videoId,
                        "title" to song.title,
                        "author" to song.author,
                        "durationText" to song.durationText,
                        "position" to song.position
                    )
                }
                writeInBatches(songWrites)
                val currentSongIds = songWrites.map { it.first.id }.toSet()
                val staleSongDocs = playlistRef.collection("songs").get().await().documents
                    .filter { it.id !in currentSongIds }
                    .map { it.reference }
                deleteInBatches(staleSongDocs)
                playlistWrites += playlistRef to mapOf(
                    "name" to playlist.name,
                    "createdAt" to playlist.createdAt
                )
            }

            // Keep the user document tiny; each growing data type lives in its
            // own collection instead of eventually exceeding Firestore's 1 MiB
            // document limit.
            val userDocRef = userDocRef(uid)
            userDocRef.set(mapOf("lastBackupAt" to System.currentTimeMillis(), "schemaVersion" to 2)).await()
            writeInBatches(likedWrites)
            writeInBatches(artistWrites)
            writeInBatches(playlistWrites)
            deleteInBatches(
                userDocRef.collection("likedSongs").get().await().documents
                    .filter { it.id !in likedWrites.map { write -> write.first.id }.toSet() }
                    .map { it.reference }
            )
            deleteInBatches(
                userDocRef.collection("followedArtists").get().await().documents
                    .filter { it.id !in artistWrites.map { write -> write.first.id }.toSet() }
                    .map { it.reference }
            )
            val currentPlaylistIds = playlistWrites.map { it.first.id }.toSet()
            for (stalePlaylist in userDocRef.collection("playlists").get().await().documents
                .filter { it.id !in currentPlaylistIds }) {
                deleteInBatches(stalePlaylist.reference.collection("songs").get().await().documents.map { it.reference })
                stalePlaylist.reference.delete().await()
            }
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
            
            val userDocRef = userDocRef(uid)
            val snapshot = userDocRef.get().await()
            
            if (!snapshot.exists()) {
                Log.d(TAG, "No backup data found on cloud for this user.")
                return@withContext Result.success(Unit)
            }

            // 1. Restore Liked Songs. Use the scalable subcollection format;
            // old single-document backups remain readable as a fallback.
            val storedLiked = userDocRef.collection("likedSongs").get().await()
                .documents.mapNotNull { it.data }
            val cloudLiked = storedLiked.ifEmpty {
                snapshot.get("likedSongs") as? List<Map<String, Any>> ?: emptyList()
            }
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
            val storedArtists = userDocRef.collection("followedArtists").get().await()
                .documents.mapNotNull { it.data }
            val cloudArtists = storedArtists.ifEmpty {
                snapshot.get("followedArtists") as? List<Map<String, Any>> ?: emptyList()
            }
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

            // 3. Restore Playlists and their separately stored songs.
            val storedPlaylists = mutableListOf<Map<String, Any>>()
            for (doc in userDocRef.collection("playlists").get().await().documents) {
                val playlist = HashMap<String, Any>(doc.data ?: emptyMap())
                playlist["songs"] = doc.reference.collection("songs").get().await()
                    .documents.mapNotNull { it.data }
                storedPlaylists += playlist
            }
            val cloudPlaylists = storedPlaylists.ifEmpty {
                snapshot.get("playlists") as? List<Map<String, Any>> ?: emptyList()
            }
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
                val resolvedPlaylistId = playlistId
                // Add songs to the playlist with deduplication
                val existingSongsInPlaylist = db.playlistDao().getAllPlaylistSongs().filter { it.playlistId == resolvedPlaylistId }
                val existingVideoIds = existingSongsInPlaylist.map { it.videoId }.toSet()
                var currentMaxPos = existingSongsInPlaylist.maxOfOrNull { it.position } ?: -1

                val songsToInsert = songs.mapNotNull { songMap ->
                    val videoId = songMap["videoId"] as? String ?: ""
                    if (videoId.isBlank() || existingVideoIds.contains(videoId)) return@mapNotNull null
                    val title = songMap["title"] as? String ?: ""
                    val author = songMap["author"] as? String ?: ""
                    val durationText = songMap["durationText"] as? String ?: ""
                    currentMaxPos++

                    PlaylistSongEntity(
                        playlistId = resolvedPlaylistId,
                        videoId = videoId,
                        title = title,
                        author = author,
                        durationText = durationText,
                        position = currentMaxPos
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

    private fun userDocRef(uid: String) = firestore.collection("users").document(uid)
}
