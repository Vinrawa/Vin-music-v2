package com.vinmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.util.UnstableApi
import com.vinmusic.data.db.*
import com.vinmusic.innertube.InnerTube
import com.vinmusic.innertube.VideoItem
import com.vinmusic.innertube.ArtistItem
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.components.SongListItem
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.CookieManager
import android.widget.Toast

@OptIn(UnstableApi::class, ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    vm: PlayerViewModel,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit,
    onSongMore: (VideoItem) -> Unit,
    onPlaylistMore: (PlaylistEntity) -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onArtistClick: (ArtistItem) -> Unit,
    onDnaClick: () -> Unit
) {
    val ctx   = androidx.compose.ui.platform.LocalContext.current
    val db    = VinDatabase.getInstance(ctx)
    val scope = rememberCoroutineScope()

    var liked     by remember { mutableStateOf<List<LikedSong>>(emptyList()) }
    var history   by remember { mutableStateOf<List<HistoryEntry>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlaylistEntity>>(emptyList()) }
    var artists   by remember { mutableStateOf<List<FollowedArtist>>(emptyList()) }

    // Playlist sub-tab toggling
    var playlistSubTab by rememberSaveable { mutableStateOf("Local") }
    var ytPlaylists by remember { mutableStateOf<List<com.vinmusic.innertube.AlbumItem>>(emptyList()) }
    var isLoadingYtPlaylists by remember { mutableStateOf(false) }
    val ytMusicConnected = remember { com.vinmusic.innertube.YTMusicSession.hasCookie(ctx) }

    var selectedYtPlaylist by remember { mutableStateOf<com.vinmusic.innertube.AlbumItem?>(null) }
    var ytPlaylistSongs by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoadingYtPlaylistSongs by remember { mutableStateOf(false) }

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
        launch(Dispatchers.IO) { db.followedArtistDao().getAllFlow().collect { artists = it } }
    }

    LaunchedEffect(ytMusicConnected) {
        if (ytMusicConnected) {
            isLoadingYtPlaylists = true
            launch(Dispatchers.IO) {
                try {
                    val pl = vm.recommendationRepository.getLibraryPlaylists()
                    withContext(Dispatchers.Main) {
                        ytPlaylists = pl
                        isLoadingYtPlaylists = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoadingYtPlaylists = false
                    }
                }
            }
        }
    }

    LaunchedEffect(selectedYtPlaylist) {
        val ytPl = selectedYtPlaylist
        if (ytPl != null) {
            ytPlaylistSongs = emptyList()
            isLoadingYtPlaylistSongs = true
            try {
                val (_, playlistSongs) = InnerTube.getPlaylistSongs(ytPl.playlistId)
                withContext(Dispatchers.Main) {
                    ytPlaylistSongs = playlistSongs
                    isLoadingYtPlaylistSongs = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    isLoadingYtPlaylistSongs = false
                }
            }
        } else {
            ytPlaylistSongs = emptyList()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, top = 24.dp, bottom = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Library", fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, color = VinColors.Primary)
            
            Box(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(VinColors.Accent.copy(alpha = 0.15f))
                    .clickable { onDnaClick() }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(Icons.Default.Analytics, contentDescription = "TasteDNA", tint = VinColors.Accent, modifier = Modifier.size(16.dp))
                    Text("TasteDNA", color = VinColors.Accent, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // Tabs with equal weight & premium micro-animations
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(VinColors.White10)
                .padding(4.dp)
        ) {
            listOf("Liked", "Playlists", "Artists", "History").forEach { t ->
                val active = vm.libraryTab == t
                
                val scale by animateFloatAsState(
                    targetValue = if (active) 1.05f else 1.0f,
                    animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
                    label = "tab_scale"
                )
                
                val activeBgColor by animateColorAsState(
                    targetValue = if (active) VinColors.Accent else Color.Transparent,
                    animationSpec = tween(300),
                    label = "tab_bg"
                )
                
                val activeTextColor by animateColorAsState(
                    targetValue = if (active) Color.White else VinColors.Secondary,
                    animationSpec = tween(300),
                    label = "tab_text"
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .scale(scale)
                        .clip(RoundedCornerShape(20.dp))
                        .background(activeBgColor)
                        .clickable { vm.libraryTab = t }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = t,
                        color = activeTextColor,
                        fontSize = 13.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        when (vm.libraryTab) {
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
                        items(songs, key = { it.videoId }) { song ->
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
                    val subTabs = remember(ytMusicConnected) {
                        val list = mutableListOf("Local")
                        if (ytMusicConnected) list.add("YouTube Music")
                        list
                    }

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        subTabs.forEach { subTab ->
                            val active = playlistSubTab == subTab
                            val activeBg = if (active) {
                                VinColors.Accent.copy(alpha = 0.25f)
                            } else VinColors.White10
                            
                            val activeText = if (active) {
                                VinColors.AccentLight
                            } else VinColors.Secondary
                            
                            val activeBorder = if (active) {
                                BorderStroke(1.dp, VinColors.Accent.copy(alpha = 0.4f))
                            } else BorderStroke(1.dp, VinColors.GlassBorder)
                            
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(14.dp))
                                    .background(activeBg)
                                    .border(activeBorder, RoundedCornerShape(14.dp))
                                    .clickable { playlistSubTab = subTab }
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = subTab,
                                    color = activeText,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    if (playlistSubTab == "Local") {
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
                                items(playlists, key = { it.id }) { pl ->
                                    PlaylistItem(
                                        playlist = pl,
                                        db = db,
                                        onClick  = { onPlaylistClick(pl.id) },
                                        onMore = {
                                            onPlaylistMore(pl)
                                        }
                                    )
                                }
                            }
                        }
                    } else if (playlistSubTab == "YouTube Music") {
                        // YouTube Music Playlists
                        if (isLoadingYtPlaylists) {
                            Box(modifier = Modifier.fillMaxSize().padding(bottom = 100.dp), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = VinColors.Accent)
                            }
                        } else if (ytPlaylists.isEmpty()) {
                            EmptyState(Icons.Default.CloudQueue, "No online playlists", "Log in or check your YouTube Music account connection")
                        } else {
                            LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
                                items(ytPlaylists, key = { it.playlistId }) { pl ->
                                    YtPlaylistItem(
                                        playlist = pl,
                                        onClick = { selectedYtPlaylist = pl }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ── Artists ───────────────────────────────────────────────────────
            "Artists" -> {
                if (artists.isEmpty()) {
                    EmptyState(Icons.Default.Person, "No followed artists", "Follow your favorite artists to see them here")
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
                        items(artists, key = { it.channelId }) { artist ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onArtistClick(ArtistItem(artist.channelId, artist.name, artist.thumbnail, artist.subscriberCount)) }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(54.dp)
                                        .clip(CircleShape)
                                        .border(1.dp, VinColors.White10, CircleShape)
                                ) {
                                    coil3.compose.AsyncImage(
                                        model = artist.thumbnail,
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize().clip(CircleShape),
                                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                    )
                                }
                                
                                Spacer(Modifier.width(16.dp))
                                
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = artist.name,
                                        color = Color.White,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                    )
                                    if (artist.subscriberCount.isNotEmpty()) {
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = artist.subscriberCount,
                                            color = VinColors.Secondary,
                                            fontSize = 13.sp,
                                            maxLines = 1
                                        )
                                    }
                                }
                                
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = VinColors.Secondary,
                                    modifier = Modifier.size(20.dp)
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
                        items(songs, key = { it.videoId }) { song ->
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

    // ── YouTube Music Playlist Details Sheet ──────────────────────────────────
    if (selectedYtPlaylist != null) {
        val recommendedPl = selectedYtPlaylist!!
        ModalBottomSheet(
            onDismissRequest = { selectedYtPlaylist = null },
            sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
            containerColor = VinColors.Surface2,
            dragHandle = { BottomSheetDefaults.DragHandle(color = VinColors.GlassBorder) }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Playlist Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(VinColors.White10)
                    ) {
                        coil3.compose.AsyncImage(
                            model = recommendedPl.thumbnail,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                        )
                    }
                    
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = recommendedPl.title,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = if (recommendedPl.author.isNotBlank()) "Created by ${recommendedPl.author}" else "YouTube Playlist",
                            fontSize = 13.sp,
                            color = VinColors.Secondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "${ytPlaylistSongs.size} tracks total",
                            fontSize = 12.sp,
                            color = VinColors.AccentLight,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                
                Spacer(Modifier.height(16.dp))
                
                // Frosted Gradient Import Button
                Button(
                    onClick = {
                        if (ytPlaylistSongs.isNotEmpty()) {
                            scope.launch(Dispatchers.IO) {
                                val playlistDbId = db.playlistDao().insertPlaylist(PlaylistEntity(name = recommendedPl.title))
                                ytPlaylistSongs.forEachIndexed { index, song ->
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
                                withContext(Dispatchers.Main) {
                                    android.widget.Toast.makeText(ctx, "Imported '${recommendedPl.title}' successfully!", android.widget.Toast.LENGTH_LONG).show()
                                    selectedYtPlaylist = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                    enabled = !isLoadingYtPlaylistSongs && ytPlaylistSongs.isNotEmpty(),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Default.CloudDownload, null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Import to Offline Library", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
                
                Spacer(Modifier.height(16.dp))
                
                HorizontalDivider(color = VinColors.GlassBorder.copy(alpha = 0.3f))
                
                Spacer(Modifier.height(12.dp))
                
                // Scrollable preview tracks
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 350.dp)
                ) {
                    if (isLoadingYtPlaylistSongs) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = VinColors.Accent)
                        }
                    } else if (ytPlaylistSongs.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("No tracks found in this playlist", color = VinColors.Secondary)
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            itemsIndexed(ytPlaylistSongs, key = { index, song -> song.videoId + "_" + index }) { index, song ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable { onSongClick(song, ytPlaylistSongs) }
                                        .padding(horizontal = 8.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text(
                                        text = (index + 1).toString(),
                                        color = VinColors.Secondary,
                                        fontSize = 13.sp,
                                        modifier = Modifier.width(24.dp)
                                    )
                                    Box(
                                        modifier = Modifier
                                            .size(42.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(VinColors.White10)
                                    ) {
                                        coil3.compose.AsyncImage(
                                            model = song.thumbnail,
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = androidx.compose.ui.layout.ContentScale.Crop
                                        )
                                    }
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            color = Color.White,
                                            fontSize = 14.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.author,
                                            color = VinColors.Secondary,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                    if (song.durationText.isNotEmpty()) {
                                        Text(
                                            text = song.durationText,
                                            color = VinColors.Secondary,
                                            fontSize = 12.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        }
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
private fun PlaylistItem(playlist: PlaylistEntity, db: VinDatabase, onClick: () -> Unit, onMore: () -> Unit) {
    var songs by remember { mutableStateOf<List<PlaylistSongEntity>>(emptyList()) }
    LaunchedEffect(playlist.id) {
        db.playlistDao().getSongsFlow(playlist.id).collect {
            songs = it.sortedBy { s -> s.position }
        }
    }

    Row(modifier = Modifier.fillMaxWidth()
        .clickable { onClick() }
        .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        
        PlaylistCover(
            songs = songs,
            modifier = Modifier.size(60.dp),
            cornerRadius = 12.dp
        )

        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = playlist.name,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = VinColors.Primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (playlist.isPinned) {
                    Icon(
                        imageVector = Icons.Default.PushPin,
                        contentDescription = "Pinned",
                        tint = VinColors.AccentLight,
                        modifier = Modifier.size(14.dp)
                    )
                }
            }
            Text("Playlist • ${songs.size} tracks", fontSize = 13.sp, color = VinColors.Secondary)
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

@Composable
private fun YtPlaylistItem(
    playlist: com.vinmusic.innertube.AlbumItem,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(60.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(VinColors.White10)
        ) {
            coil3.compose.AsyncImage(
                model = playlist.thumbnail,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = androidx.compose.ui.layout.ContentScale.Crop
            )
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = playlist.title,
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold,
                color = VinColors.Primary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = playlist.songCount.ifBlank { "Playlist • YouTube Music" },
                fontSize = 13.sp,
                color = VinColors.Secondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = VinColors.Secondary,
            modifier = Modifier.size(20.dp)
        )
    }
}
