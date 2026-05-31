package com.vinmusic.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.media3.common.util.UnstableApi
import com.vinmusic.data.db.*
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.components.SongListItem
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(UnstableApi::class)
@Composable
fun LibraryScreen(
    vm: PlayerViewModel,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit,
    onSongMore: (VideoItem) -> Unit,
    onPlaylistMore: (PlaylistEntity) -> Unit,
    onPlaylistClick: (Long) -> Unit
) {
    val ctx   = androidx.compose.ui.platform.LocalContext.current
    val db    = VinDatabase.getInstance(ctx)
    val scope = rememberCoroutineScope()

    var liked     by remember { mutableStateOf<List<LikedSong>>(emptyList()) }
    var history   by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }
    var tab       by rememberSaveable { mutableStateOf("Liked") }

    // New playlist dialog
    var showNewPlaylist by remember { mutableStateOf(false) }
    var newPlaylistName by remember { mutableStateOf("") }

    // Playlist Import states
    var showImportPlaylist by remember { mutableStateOf(false) }
    var importUrl          by remember { mutableStateOf("") }
    var isImporting        by remember { mutableStateOf(false) }
    var importProgress     by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        launch(Dispatchers.IO) { db.likedSongDao().getAllFlow().collect   { liked = it } }
        launch(Dispatchers.IO) { db.historyDao().getRecentFlow().collect  { history = it } }
        launch(Dispatchers.IO) { db.playlistDao().getAllFlow().collect     { playlists = it } }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        Text("Library", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = VinColors.Primary,
            modifier = Modifier.padding(start = 20.dp, top = 24.dp, bottom = 12.dp))

        // Tabs
        Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(RoundedCornerShape(22.dp)).background(VinColors.White10).padding(4.dp)) {
            listOf("Liked", "Playlists", "History").forEach { t ->
                val active = tab == t
                Box(modifier = Modifier.clip(RoundedCornerShape(18.dp))
                    .background(if (active) VinColors.Accent else Color.Transparent)
                    .clickable { tab = t }
                    .padding(horizontal = 22.dp, vertical = 11.dp)) {
                    Text(t, color = if (active) Color.White else VinColors.Secondary,
                        fontSize = 14.sp, fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal)
                }
            }
        }

        when (tab) {
            // ── Liked ─────────────────────────────────────────────────────────
            "Liked" -> {
                if (liked.isEmpty()) {
                    EmptyState(Icons.Default.FavoriteBorder, "No liked songs yet", "Tap the like icon on any song to save it here")
                } else {
                    val songs = liked.map { VideoItem(it.videoId, it.title, it.author, it.durationText) }
                    LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
                        item {
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                shape = RoundedCornerShape(24.dp)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(
                                            Brush.linearGradient(
                                                colors = listOf(
                                                    Color(0xFFEC4899), // Pink
                                                    Color(0xFF8B5CF6), // Purple
                                                    Color(0xFF3B82F6)  // Blue
                                                )
                                            )
                                        )
                                        .clickable { onSongClick(songs[0], songs) }
                                        .padding(24.dp)
                                ) {
                                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .size(48.dp)
                                                    .clip(CircleShape)
                                                    .background(Color.White.copy(alpha = 0.2f)),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(
                                                    imageVector = Icons.Default.Favorite,
                                                    contentDescription = null,
                                                    tint = Color.White,
                                                    modifier = Modifier.size(24.dp)
                                                )
                                            }
                                            Column {
                                                Text(
                                                    text = "Liked Songs",
                                                    fontSize = 22.sp,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White
                                                )
                                                Text(
                                                    text = "${songs.size} tracks saved offline",
                                                    fontSize = 13.sp,
                                                    color = Color.White.copy(alpha = 0.8f)
                                                )
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier
                                                .clip(RoundedCornerShape(12.dp))
                                                .background(Color.White.copy(alpha = 0.25f))
                                                .clickable { onSongClick(songs[0], songs) }
                                                .padding(horizontal = 14.dp, vertical = 8.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.PlayArrow,
                                                contentDescription = "Play",
                                                tint = Color.White,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Text(
                                                text = "Play All",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        items(songs) { song ->
                            SongListItem(song = song, isPlaying = vm.currentSong?.videoId == song.videoId,
                                onClick = { onSongClick(song, songs) },
                                onMore = { onSongMore(song) })
                        }
                    }
                }
            }

            // ── Playlists ─────────────────────────────────────────────────────
            "Playlists" -> {
                Column {
                    // Create new playlist button
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${playlists.size} playlists", color = VinColors.Secondary, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            // Import Playlist Button
                            Button(onClick = { showImportPlaylist = true },
                                colors = ButtonDefaults.buttonColors(containerColor = VinColors.White10),
                                border = BorderStroke(1.dp, VinColors.GlassBorder),
                                shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Default.CloudDownload, null, tint = VinColors.Primary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Import", color = VinColors.Primary, fontSize = 13.sp)
                            }
                            
                            // New Playlist Button
                            Button(onClick = { showNewPlaylist = true },
                                colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                                shape = RoundedCornerShape(12.dp)) {
                                Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("New", fontSize = 13.sp)
                            }
                        }
                    }

                    if (playlists.isEmpty()) {
                        EmptyState(Icons.Default.LibraryMusic, "No playlists yet", "Create a playlist to organize your music")
                    } else {
                        LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
                            items(playlists) { pl ->
                                PlaylistItem(
                                    playlist = pl,
                                    onClick  = { onPlaylistClick(pl.id) },
                                    onMore = {
                                        onPlaylistMore(pl)
                                    }
                                )
                            }
                        }
                    }
                }
            }

            // ── History ───────────────────────────────────────────────────────
            "History" -> {
                if (history.isEmpty()) {
                    EmptyState(Icons.Default.History, "No history yet", "Play some songs to see them here")
                } else {
                    val songs = history.map { VideoItem(it.videoId, it.title, it.author, it.durationText) }
                    LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
                        item {
                            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.End) {
                                TextButton(onClick = { scope.launch(Dispatchers.IO) { db.historyDao().clearAll() } }) {
                                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(16.dp))
                                    Spacer(Modifier.width(4.dp))
                                    Text("Clear", fontSize = 13.sp)
                                }
                            }
                        }
                        items(songs) { song ->
                            SongListItem(song = song, isPlaying = vm.currentSong?.videoId == song.videoId,
                                onClick = { onSongClick(song, songs) })
                        }
                    }
                }
            }
        }
    }

    // ── New Playlist Dialog ────────────────────────────────────────────────────
    if (showNewPlaylist) {
        AlertDialog(
            onDismissRequest = { showNewPlaylist = false; newPlaylistName = "" },
            containerColor   = VinColors.Surface2,
            title = { Text("New Playlist", color = VinColors.Primary) },
            text = {
                OutlinedTextField(
                    value = newPlaylistName, onValueChange = { newPlaylistName = it },
                    placeholder = { Text("Playlist name", color = VinColors.Secondary) },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = VinColors.Accent, unfocusedBorderColor = VinColors.GlassBorder,
                        focusedTextColor = VinColors.Primary, unfocusedTextColor = VinColors.Primary,
                        focusedContainerColor = VinColors.White10, unfocusedContainerColor = VinColors.White10
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newPlaylistName.isNotBlank()) {
                        scope.launch(Dispatchers.IO) {
                            db.playlistDao().insertPlaylist(PlaylistEntity(name = newPlaylistName.trim()))
                        }
                        newPlaylistName = ""
                        showNewPlaylist = false
                    }
                }) { Text("Create", color = VinColors.Accent) }
            },
            dismissButton = {
                TextButton(onClick = { showNewPlaylist = false; newPlaylistName = "" }) {
                    Text("Cancel", color = VinColors.Secondary)
                }
            }
        )
    }

    // ── Import Playlist Dialog ──────────────────────────────────────────────────
    if (showImportPlaylist) {
        AlertDialog(
            onDismissRequest = { 
                if (!isImporting) {
                    showImportPlaylist = false
                    importUrl = ""
                    importProgress = ""
                }
            },
            containerColor = VinColors.Surface2,
            title = { Text("Import Playlist", color = VinColors.Primary) },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    if (isImporting) {
                        CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(48.dp))
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = importProgress,
                            color = VinColors.Primary,
                            fontSize = 14.sp,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    } else {
                        Text(
                            text = "Paste a Spotify playlist share link or YouTube playlist URL (with list=PL... ID).",
                            color = VinColors.Secondary,
                            fontSize = 13.sp
                        )
                        OutlinedTextField(
                            value = importUrl,
                            onValueChange = { importUrl = it },
                            placeholder = { Text("Playlist URL", color = VinColors.Secondary) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = VinColors.Accent,
                                unfocusedBorderColor = VinColors.GlassBorder,
                                focusedTextColor = VinColors.Primary,
                                unfocusedTextColor = VinColors.Primary,
                                focusedContainerColor = VinColors.White10,
                                unfocusedContainerColor = VinColors.White10
                            ),
                            shape = RoundedCornerShape(12.dp)
                        )
                    }
                }
            },
            confirmButton = {
                if (!isImporting) {
                    TextButton(onClick = {
                        val url = importUrl.trim()
                        if (url.isNotEmpty()) {
                            isImporting = true
                            importProgress = "Analyzing link..."
                            scope.launch(Dispatchers.IO) {
                                try {
                                    val isSpotify = url.contains("spotify.com")
                                    val isYouTube = url.contains("list=") || url.contains("youtube.com") || url.contains("youtu.be")
                                    
                                    if (isSpotify) {
                                        importProgress = "Fetching Spotify metadata..."
                                        val (playlistName, trackQueries) = InnerTube.importSpotifyPlaylist(url)
                                        if (trackQueries.isEmpty()) {
                                            launch(Dispatchers.Main) {
                                                android.widget.Toast.makeText(ctx, "Failed to parse Spotify tracks or playlist is empty", android.widget.Toast.LENGTH_LONG).show()
                                                isImporting = false
                                            }
                                            return@launch
                                        }
                                        
                                        // Insert Playlist record
                                        val playlistId = db.playlistDao().insertPlaylist(PlaylistEntity(name = playlistName))
                                        
                                        val resolvedSongs = mutableListOf<VideoItem>()
                                        trackQueries.forEachIndexed { index, query ->
                                            importProgress = "Playlist: $playlistName\nMatching track: ${index + 1} / ${trackQueries.size}..."
                                            val results = InnerTube.search(query)
                                            val bestSong = results.firstOrNull()
                                            if (bestSong != null) {
                                                resolvedSongs.add(bestSong)
                                                // Save to database playlist songs
                                                db.playlistDao().insertSong(
                                                    PlaylistSongEntity(
                                                        playlistId = playlistId,
                                                        videoId = bestSong.videoId,
                                                        title = bestSong.title,
                                                        author = bestSong.author,
                                                        durationText = bestSong.durationText,
                                                        position = resolvedSongs.size - 1
                                                    )
                                                )
                                            }
                                        }
                                        
                                        launch(Dispatchers.Main) {
                                            android.widget.Toast.makeText(ctx, "Imported '$playlistName' with ${resolvedSongs.size} tracks successfully!", android.widget.Toast.LENGTH_LONG).show()
                                            showImportPlaylist = false
                                            importUrl = ""
                                            importProgress = ""
                                            isImporting = false
                                        }
                                        
                                    } else if (isYouTube) {
                                        val playlistId = if (url.contains("list=")) {
                                            url.substringAfter("list=").substringBefore("&")
                                        } else {
                                            url
                                        }
                                        
                                        importProgress = "Fetching YouTube playlist..."
                                        val (playlistName, songs) = InnerTube.getPlaylistSongs(playlistId)
                                        if (songs.isEmpty()) {
                                            launch(Dispatchers.Main) {
                                                android.widget.Toast.makeText(ctx, "Failed to fetch YouTube songs or playlist is empty", android.widget.Toast.LENGTH_LONG).show()
                                                isImporting = false
                                            }
                                            return@launch
                                        }
                                        
                                        importProgress = "Playlist: $playlistName\nImporting ${songs.size} tracks..."
                                        val playlistDbId = db.playlistDao().insertPlaylist(PlaylistEntity(name = playlistName))
                                        songs.forEachIndexed { index, song ->
                                            db.playlistDao().insertSong(
                                                PlaylistSongEntity(
                                                    playlistId = playlistDbId,
                                                    videoId = song.videoId,
                                                    title = song.title,
                                                    author = song.author,
                                                    durationText = song.durationText,
                                                    position = index
                                                )
                                            )
                                        }
                                        
                                        launch(Dispatchers.Main) {
                                            android.widget.Toast.makeText(ctx, "Imported '$playlistName' with ${songs.size} tracks successfully!", android.widget.Toast.LENGTH_LONG).show()
                                            showImportPlaylist = false
                                            importUrl = ""
                                            importProgress = ""
                                            isImporting = false
                                        }
                                        
                                    } else {
                                        launch(Dispatchers.Main) {
                                            android.widget.Toast.makeText(ctx, "Invalid playlist URL. Please check the link.", android.widget.Toast.LENGTH_LONG).show()
                                            isImporting = false
                                        }
                                    }
                                } catch (e: Exception) {
                                    launch(Dispatchers.Main) {
                                        android.widget.Toast.makeText(ctx, "Import failed: ${e.message}", android.widget.Toast.LENGTH_LONG).show()
                                        isImporting = false
                                    }
                                }
                            }
                        }
                    }) { Text("Import", color = VinColors.Accent) }
                }
            },
            dismissButton = {
                if (!isImporting) {
                    TextButton(onClick = {
                        showImportPlaylist = false
                        importUrl = ""
                        importProgress = ""
                    }) { Text("Cancel", color = VinColors.Secondary) }
                }
            }
        )
    }
}

// ── Sub-components ─────────────────────────────────────────────────────────────

@Composable
private fun LibraryHeader(subtitle: String, onPlayAll: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(subtitle, color = VinColors.Secondary, fontSize = 13.sp)
        Button(onClick = onPlayAll, colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
            shape = RoundedCornerShape(12.dp)) {
            Icon(Icons.Default.PlayArrow, null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(4.dp))
            Text("Play All")
        }
    }
}

@Composable
private fun PlaylistItem(playlist: PlaylistEntity, onClick: () -> Unit, onMore: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()
        .clickable { onClick() }
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        // Playlist icon box
        Box(modifier = Modifier.size(60.dp).clip(RoundedCornerShape(12.dp))
            .background(Brush.linearGradient(listOf(VinColors.AccentGlow, VinColors.Surface2)))
            .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.LibraryMusic, null, tint = VinColors.AccentLight, modifier = Modifier.size(30.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(playlist.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = VinColors.Primary)
            Text("Playlist", fontSize = 13.sp, color = VinColors.Secondary)
        }
        IconButton(onClick = onMore, modifier = Modifier.size(36.dp)) {
            Icon(Icons.Default.MoreVert, null, tint = VinColors.Secondary, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String) {
    Box(Modifier.fillMaxSize(), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(icon, null, tint = VinColors.White20, modifier = Modifier.size(56.dp))
            Spacer(Modifier.height(12.dp))
            Text(title,    color = VinColors.Secondary, fontSize = 16.sp)
            Text(subtitle, color = VinColors.Secondary, fontSize = 13.sp)
        }
    }
}
