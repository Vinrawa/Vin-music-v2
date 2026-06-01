package com.vinmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.vinmusic.data.db.PlaylistEntity
import com.vinmusic.data.db.PlaylistSongEntity
import com.vinmusic.data.db.VinDatabase
import com.vinmusic.innertube.VideoItem
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onSongMore: (VideoItem) -> Unit
) {
    val ctx = LocalContext.current
    val db = VinDatabase.getInstance(ctx)
    val scope = rememberCoroutineScope()

    var playlist by remember { mutableStateOf<PlaylistEntity?>(null) }
    var songs by remember { mutableStateOf<List<PlaylistSongEntity>>(emptyList()) }

    var isSearchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredSongs = remember(songs, searchQuery) {
        if (searchQuery.isBlank()) songs
        else songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
            it.author.contains(searchQuery, ignoreCase = true)
        }
    }

    var recommendedPlaylists by remember { mutableStateOf<List<com.vinmusic.innertube.AlbumItem>>(emptyList()) }
    var isLoadingRecommendations by remember { mutableStateOf(false) }
    
    var selectedRecommendedPlaylist by remember { mutableStateOf<com.vinmusic.innertube.AlbumItem?>(null) }
    var recommendedPlaylistSongs by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoadingPlaylistSongs by remember { mutableStateOf(false) }

    var showRenameDialog by remember { mutableStateOf(false) }
    var newNameText by remember { mutableStateOf("") }

    LaunchedEffect(playlistId) {
        playlist = withContext(Dispatchers.IO) { db.playlistDao().getPlaylist(playlistId) }
        db.playlistDao().getSongsFlow(playlistId).collect {
            songs = it.sortedBy { s -> s.position }
        }
    }

    LaunchedEffect(playlist, songs) {
        val pl = playlist ?: return@LaunchedEffect
        if (songs.isEmpty() && pl.name.lowercase() in listOf("playlist", "new playlist", "imported playlist", "favorites", "liked")) {
            isLoadingRecommendations = false
            return@LaunchedEffect
        }
        isLoadingRecommendations = true
        withContext(Dispatchers.IO) {
            val queries = mutableListOf<String>()
            val plName = pl.name.trim()
            val genericNames = listOf("playlist", "new playlist", "imported playlist", "favorites", "liked", "custom playlist")
            if (plName.isNotEmpty() && !genericNames.any { plName.lowercase() == it }) {
                queries.add("$plName playlist")
            }
            
            // Extract top artist
            val topArtist = songs.groupBy { it.author }
                .filter { it.key.isNotBlank() }
                .maxByOrNull { it.value.size }?.key?.trim()
            if (!topArtist.isNullOrEmpty()) {
                queries.add("$topArtist playlist")
            }
            
            val allResults = mutableListOf<com.vinmusic.innertube.AlbumItem>()
            for (query in queries.take(2)) {
                try {
                    val searchResult = com.vinmusic.innertube.InnerTube.searchAll(query)
                    allResults.addAll(searchResult.albums)
                } catch (e: Exception) {
                    android.util.Log.e("VIN_STREAM", "Failed recommendation search for $query", e)
                }
            }
            
            val uniquePlaylists = allResults
                .distinctBy { it.playlistId }
                .filter { it.playlistId.startsWith("PL") || it.playlistId.startsWith("VL") }
                .take(8)
            
            withContext(Dispatchers.Main) {
                recommendedPlaylists = uniquePlaylists
                isLoadingRecommendations = false
            }
        }
    }

    LaunchedEffect(selectedRecommendedPlaylist) {
        val recommendedPl = selectedRecommendedPlaylist
        if (recommendedPl != null) {
            isLoadingPlaylistSongs = true
            recommendedPlaylistSongs = emptyList()
            withContext(Dispatchers.IO) {
                try {
                    val (_, playlistSongs) = com.vinmusic.innertube.InnerTube.getPlaylistSongs(recommendedPl.playlistId)
                    withContext(Dispatchers.Main) {
                        recommendedPlaylistSongs = playlistSongs
                        isLoadingPlaylistSongs = false
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        isLoadingPlaylistSongs = false
                    }
                }
            }
        } else {
            recommendedPlaylistSongs = emptyList()
            isLoadingPlaylistSongs = false
        }
    }

    // Rename playlist dialog
    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = VinColors.Surface2,
            shape = RoundedCornerShape(24.dp),
            title = { Text("Rename Playlist", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                OutlinedTextField(
                    value = newNameText,
                    onValueChange = { newNameText = it },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = VinColors.Accent,
                        unfocusedBorderColor = VinColors.GlassBorder,
                        focusedContainerColor = VinColors.White10,
                        unfocusedContainerColor = VinColors.White10
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val name = newNameText.trim()
                        if (name.isNotEmpty()) {
                            scope.launch(Dispatchers.IO) {
                                db.playlistDao().insertPlaylist(PlaylistEntity(id = playlistId, name = name, createdAt = playlist?.createdAt ?: System.currentTimeMillis()))
                                playlist = db.playlistDao().getPlaylist(playlistId)
                            }
                            showRenameDialog = false
                        }
                    }
                ) {
                    Text("Rename", color = VinColors.Accent)
                }
            },
            dismissButton = {
                TextButton(onClick = { showRenameDialog = false }) {
                    Text("Cancel", color = VinColors.Secondary)
                }
            }
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VinColors.BgColor)
    ) {
        // Blurs of the first song artwork or custom gradient as background
        val coverArtUrl = songs.firstOrNull()?.let { "https://i.ytimg.com/vi/${it.videoId}/hqdefault.jpg" }
        
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .graphicsLayer(alpha = 0.35f)
        ) {
            if (coverArtUrl != null) {
                AsyncImage(
                    model = coverArtUrl,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .blur(50.dp),
                    contentScale = ContentScale.Crop
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        Brush.verticalGradient(
                            listOf(
                                VinColors.GradTop.copy(alpha = 0.7f),
                                VinColors.BgColor.copy(alpha = 0.3f),
                                VinColors.BgColor
                            )
                        )
                    )
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // Header bar with vertical three-dot menu
            if (isSearchActive) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        placeholder = { Text("Search songs or artists...", color = VinColors.Secondary, fontSize = 14.sp) },
                        singleLine = true,
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Search,
                                contentDescription = "Search",
                                tint = VinColors.Secondary,
                                modifier = Modifier.size(20.dp)
                            )
                        },
                        trailingIcon = {
                            IconButton(onClick = {
                                if (searchQuery.isNotEmpty()) {
                                    searchQuery = ""
                                } else {
                                    isSearchActive = false
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Close Search",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White,
                            focusedBorderColor = VinColors.Accent,
                            unfocusedBorderColor = VinColors.GlassBorder,
                            focusedContainerColor = VinColors.White10,
                            unfocusedContainerColor = VinColors.White10
                        ),
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 8.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onBack) {
                        Box(
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Playlist Detail",
                        fontSize = 17.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f)
                    )
                    
                    var showMenu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(Color.Black.copy(0.4f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Menu",
                                    tint = Color.White,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                            modifier = Modifier.background(VinColors.Surface2)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Search in playlist", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Search, null, tint = Color.White) },
                                onClick = {
                                    showMenu = false
                                    isSearchActive = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Sort by", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Sort, null, tint = Color.White) },
                                onClick = { showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Rename playlist", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Edit, null, tint = Color.White) },
                                onClick = {
                                    showMenu = false
                                    newNameText = playlist?.name ?: ""
                                    showRenameDialog = true
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("Edit cover", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Image, null, tint = Color.White) },
                                onClick = { showMenu = false }
                            )
                            DropdownMenuItem(
                                text = { Text("Share playlist", color = Color.White) },
                                leadingIcon = { Icon(Icons.Default.Share, null, tint = Color.White) },
                                onClick = {
                                    showMenu = false
                                    val shareText = "Check out my playlist '${playlist?.name}' on VinMusic:\n" + songs.joinToString("\n") { "${it.title} - ${it.author}" }
                                    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                        type = "text/plain"
                                        putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                                    }
                                    ctx.startActivity(android.content.Intent.createChooser(intent, "Share Playlist"))
                                }
                            )
                        }
                    }
                }
            }

            if (songs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Text("This playlist is empty.", color = VinColors.Secondary, fontSize = 14.sp)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    // Header Card matching Album Detail
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                        ) {
                             // Centered Collage Playlist Artwork
                             Box(
                                 modifier = Modifier.fillMaxWidth(),
                                 contentAlignment = Alignment.Center
                             ) {
                                 PlaylistCover(
                                     songs = songs,
                                     modifier = Modifier.size(260.dp),
                                     cornerRadius = 16.dp
                                 )
                             }
 
                             Spacer(Modifier.height(16.dp))
 
                             // Centered Playlist Name
                             Box(
                                 modifier = Modifier.fillMaxWidth(),
                                 contentAlignment = Alignment.Center
                             ) {
                                 Text(
                                     text = playlist?.name ?: "",
                                     fontSize = 24.sp,
                                     fontWeight = FontWeight.ExtraBold,
                                     color = Color.White,
                                     textAlign = TextAlign.Center,
                                     maxLines = 2,
                                     overflow = TextOverflow.Ellipsis
                                 )
                             }
 
                             Spacer(Modifier.height(8.dp))
 
                             // Centered Track Count in muted gray text
                             Box(
                                 modifier = Modifier.fillMaxWidth(),
                                 contentAlignment = Alignment.Center
                             ) {
                                 Text(
                                     text = "Playlist • ${songs.size} tracks",
                                     fontSize = 13.sp,
                                     color = VinColors.Secondary,
                                     textAlign = TextAlign.Center
                                 )
                             }

                             Spacer(Modifier.height(16.dp))

                             // Controls row: Shuffle + Delete on the left, Red Play on the right
                             Row(
                                 modifier = Modifier.fillMaxWidth(),
                                 horizontalArrangement = Arrangement.SpaceBetween,
                                 verticalAlignment = Alignment.CenterVertically
                             ) {
                                 Row(
                                     horizontalArrangement = Arrangement.spacedBy(12.dp),
                                     verticalAlignment = Alignment.CenterVertically
                                 ) {
                                     // Shuffle
                                     IconButton(
                                         onClick = {
                                             if (filteredSongs.isNotEmpty()) {
                                                 val videoItems = filteredSongs.map { VideoItem(it.videoId, it.title, it.author, it.durationText) }.shuffled()
                                                 vm.setQueue(videoItems, 0)
                                             }
                                         },
                                         modifier = Modifier
                                             .size(38.dp)
                                             .clip(CircleShape)
                                             .background(Color.White.copy(0.06f))
                                     ) {
                                         Icon(
                                             imageVector = Icons.Default.Shuffle,
                                             contentDescription = "Shuffle",
                                             tint = Color.White,
                                             modifier = Modifier.size(18.dp)
                                         )
                                     }
 
                                     // Add/Import styled plus button that performs Delete/Clear playlist action
                                     IconButton(
                                         onClick = {
                                             scope.launch(Dispatchers.IO) {
                                                 db.playlistDao().deletePlaylist(playlistId)
                                                 withContext(Dispatchers.Main) {
                                                     onBack()
                                                 }
                                             }
                                         },
                                         modifier = Modifier
                                             .size(38.dp)
                                             .clip(CircleShape)
                                             .background(Color.White.copy(0.06f))
                                     ) {
                                         Icon(
                                             imageVector = Icons.Default.Add,
                                             contentDescription = "Clear Playlist",
                                             tint = Color.White,
                                             modifier = Modifier.size(18.dp)
                                         )
                                     }
                                 }
 
                                 // Solid Red circular play button on the right
                                 Box(
                                     modifier = Modifier
                                         .size(56.dp)
                                         .clip(CircleShape)
                                         .background(VinColors.Accent)
                                         .clickable {
                                             if (filteredSongs.isNotEmpty()) {
                                                 val videoItems = filteredSongs.map { VideoItem(it.videoId, it.title, it.author, it.durationText) }
                                                 vm.setQueue(videoItems, 0)
                                             }
                                         },
                                     contentAlignment = Alignment.Center
                                 ) {
                                     Icon(
                                         imageVector = Icons.Default.PlayArrow,
                                         contentDescription = "Play All",
                                         tint = Color.White,
                                         modifier = Modifier.size(30.dp)
                                     )
                                 }
                             }
                        }
 
                        HorizontalDivider(
                            color = VinColors.GlassBorder.copy(alpha = 0.3f),
                            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                        )
                    }

                    if (filteredSongs.isEmpty() && songs.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "No tracks match \"$searchQuery\"",
                                    color = VinColors.Secondary,
                                    fontSize = 14.sp
                                )
                            }
                        }
                    } else {
                        // Songs list items
                        itemsIndexed(filteredSongs, key = { _, s -> "pl_song_${s.videoId}" }) { index, s ->
                            val isPlaying = vm.currentSong?.videoId == s.videoId
                            
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp, vertical = 6.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(if (isPlaying) VinColors.Accent.copy(alpha = 0.12f) else VinColors.White10)
                                    .border(1.dp, if (isPlaying) VinColors.Accent.copy(alpha = 0.4f) else VinColors.GlassBorder, RoundedCornerShape(16.dp))
                                    .clickable {
                                        val videoItems = filteredSongs.map { VideoItem(it.videoId, it.title, it.author, it.durationText) }
                                        vm.setQueue(videoItems, index)
                                    }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(10.dp))) {
                                    AsyncImage(
                                        model = "https://i.ytimg.com/vi/${s.videoId}/hqdefault.jpg",
                                        contentDescription = null,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop
                                    )
                                    if (isPlaying) {
                                        Box(Modifier.fillMaxSize().background(Color(0x60000000)),
                                            contentAlignment = Alignment.Center) {
                                            Icon(Icons.Default.VolumeUp, null,
                                                tint = VinColors.AccentLight, modifier = Modifier.size(16.dp))
                                        }
                                    }
                                }
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        s.title,
                                        fontSize = 14.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isPlaying) VinColors.AccentLight else VinColors.Primary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        "${s.author} • ${s.durationText}",
                                        fontSize = 12.sp,
                                        color = VinColors.Secondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }

                                // Vertical three-dot menu icon
                                IconButton(
                                    onClick = {
                                        val videoItem = VideoItem(s.videoId, s.title, s.author, s.durationText)
                                        onSongMore(videoItem)
                                    },
                                    modifier = Modifier.size(32.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "More Options",
                                        tint = VinColors.Secondary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                            }
                        }
                    }

                    // Similar Playlist Recommendations Shelf
                    if (playlist != null) {
                        item {
                            Spacer(Modifier.height(28.dp))
                            Text(
                                text = "Recommended playlists",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color.White,
                                modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp)
                            )
                        }
                        
                        item {
                            when {
                                isLoadingRecommendations -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(180.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(32.dp))
                                    }
                                }
                                recommendedPlaylists.isNotEmpty() -> {
                                    androidx.compose.foundation.lazy.LazyRow(
                                        contentPadding = PaddingValues(horizontal = 24.dp),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 8.dp)
                                    ) {
                                        items(recommendedPlaylists) { pl ->
                                            RecommendedPlaylistCard(
                                                playlist = pl,
                                                onClick = { selectedRecommendedPlaylist = pl }
                                            )
                                        }
                                    }
                                }
                                else -> {
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(120.dp)
                                            .padding(horizontal = 24.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "No recommended playlists available for this playlist right now.",
                                            color = VinColors.Secondary,
                                            fontSize = 13.sp,
                                            textAlign = TextAlign.Center
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ModalBottomSheet for recommended playlist details & import
    if (selectedRecommendedPlaylist != null) {
        val recommendedPl = selectedRecommendedPlaylist!!
        ModalBottomSheet(
            onDismissRequest = { selectedRecommendedPlaylist = null },
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
                        AsyncImage(
                            model = recommendedPl.thumbnail,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
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
                            text = "${recommendedPlaylistSongs.size} tracks total",
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
                        if (recommendedPlaylistSongs.isNotEmpty()) {
                            scope.launch(Dispatchers.IO) {
                                val playlistDbId = db.playlistDao().insertPlaylist(PlaylistEntity(name = recommendedPl.title))
                                recommendedPlaylistSongs.forEachIndexed { index, song ->
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
                                    selectedRecommendedPlaylist = null
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                    enabled = !isLoadingPlaylistSongs && recommendedPlaylistSongs.isNotEmpty(),
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
                        .heightIn(max = 280.dp)
                ) {
                    if (isLoadingPlaylistSongs) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(32.dp))
                        }
                    } else if (recommendedPlaylistSongs.isEmpty()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(150.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("No tracks found or loading failed.", color = VinColors.Secondary, fontSize = 13.sp)
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            itemsIndexed(recommendedPlaylistSongs) { index, song ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .background(VinColors.White10)
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.size(36.dp).clip(RoundedCornerShape(6.dp))) {
                                        AsyncImage(
                                            model = "https://i.ytimg.com/vi/${song.videoId}/hqdefault.jpg",
                                            contentDescription = null,
                                            modifier = Modifier.fillMaxSize(),
                                            contentScale = ContentScale.Crop
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = song.title,
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                        Text(
                                            text = song.author,
                                            fontSize = 11.sp,
                                            color = VinColors.Secondary,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
fun RecommendedPlaylistCard(
    playlist: com.vinmusic.innertube.AlbumItem,
    onClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
    ) {
        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(VinColors.White10)
        ) {
            AsyncImage(
                model = playlist.thumbnail.ifBlank { "https://images.unsplash.com/photo-1614613535308-eb5fbd3d2c17" },
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            
            // Video Count Tag Overlay on the bottom right
            if (playlist.songCount.isNotBlank()) {
                Row(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Black.copy(alpha = 0.65f))
                        .padding(horizontal = 6.dp, vertical = 3.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.List,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(12.dp)
                    )
                    Text(
                        text = playlist.songCount.filter { it.isDigit() },
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
        
        Spacer(Modifier.height(8.dp))
        
        Text(
            text = playlist.title,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        
        Text(
            text = if (playlist.author.isNotBlank()) "${playlist.author} • Playlist" else "YouTube Playlist",
            fontSize = 12.sp,
            color = VinColors.Secondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
fun PlaylistCover(
    songs: List<PlaylistSongEntity>,
    modifier: Modifier = Modifier,
    cornerRadius: androidx.compose.ui.unit.Dp = 16.dp
) {
    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(cornerRadius))
            .background(VinColors.Surface)
    ) {
        val covers = songs.take(4).map { "https://i.ytimg.com/vi/${it.videoId}/hqdefault.jpg" }
        if (covers.size >= 4) {
            // 2x2 Grid Collage
            Column(modifier = Modifier.fillMaxSize()) {
                Row(modifier = Modifier.weight(1f)) {
                    AsyncImage(
                        model = covers[0],
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .graphicsLayer(scaleX = 1.35f, scaleY = 1.35f),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.3f)))
                    AsyncImage(
                        model = covers[1],
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .graphicsLayer(scaleX = 1.35f, scaleY = 1.35f),
                        contentScale = ContentScale.Crop
                    )
                }
                Spacer(modifier = Modifier.height(1.dp).fillMaxWidth().background(Color.White.copy(alpha = 0.3f)))
                Row(modifier = Modifier.weight(1f)) {
                    AsyncImage(
                        model = covers[2],
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .graphicsLayer(scaleX = 1.35f, scaleY = 1.35f),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(modifier = Modifier.width(1.dp).fillMaxHeight().background(Color.White.copy(alpha = 0.3f)))
                    AsyncImage(
                        model = covers[3],
                        contentDescription = null,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clipToBounds()
                            .graphicsLayer(scaleX = 1.35f, scaleY = 1.35f),
                        contentScale = ContentScale.Crop
                    )
                }
            }
        } else if (covers.isNotEmpty()) {
            // Fallback to first song artwork
            AsyncImage(
                model = covers[0],
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds()
                    .graphicsLayer(scaleX = 1.35f, scaleY = 1.35f),
                contentScale = ContentScale.Crop
            )
        } else {
            // Default Playlist Music Icon
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.LibraryMusic,
                    null,
                    tint = VinColors.AccentLight,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}
