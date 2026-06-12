package com.vinmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
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
import com.vinmusic.innertube.*
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    album: AlbumItem,
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit,
    onSongMore: (VideoItem) -> Unit
) {
    val context = LocalContext.current
    var songs by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(album.playlistId) {
        isLoading = true
        withContext(Dispatchers.IO) {
            try {
                val pid = album.playlistId
                android.util.Log.d("AlbumDetail", "Loading playlistId=$pid")
                val resolved = if (pid.startsWith("album_") || pid.startsWith("single_")) {
                    val trackId = pid.substringAfterLast("_")
                    listOf(VideoItem(trackId, album.title, album.author))
                } else if (pid.startsWith("MPRE")) {
                    InnerTube.getAlbumSongs(pid)
                } else {
                    // Handle community playlist IDs: VLPLxxx, PLxxx, RDCLAKxxx, VLRDxxx, etc.
                    val result = InnerTube.getPlaylistSongs(pid).second
                    if (result.isEmpty() && pid.startsWith("VL")) {
                        // If VL-prefixed browse failed, try stripping VL and using raw playlist ID
                        android.util.Log.d("AlbumDetail", "VL browse empty, retrying with stripped ID")
                        InnerTube.getPlaylistSongs(pid.removePrefix("VL")).second
                    } else {
                        result
                    }
                }
                
                android.util.Log.d("AlbumDetail", "Resolved ${resolved.size} songs for $pid")
                withContext(Dispatchers.Main) {
                    songs = resolved
                    isLoading = false
                }
            } catch (e: Exception) {
                android.util.Log.e("AlbumDetail", "Failed to load: ${e.message}")
                withContext(Dispatchers.Main) {
                    isLoading = false
                }
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(VinColors.BgColor)
    ) {
        // Static solid background gradient header area
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(
                            VinColors.Surface2,
                            VinColors.BgColor
                        )
                    )
                )
        )

        Column(modifier = Modifier.fillMaxSize()) {
            // Header bar
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
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Back",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = when {
                        album.playlistId.startsWith("single_") -> "Single Detail"
                        album.playlistId.startsWith("MPRE") || album.playlistId.startsWith("album_") -> "Album Detail"
                        else -> "Playlist"
                    },
                    fontSize = 17.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            if (isLoading) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = VinColors.Accent)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = 120.dp)
                ) {
                    // Album Cover & Info Header
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 16.dp)
                        ) {
                            // Centered Artwork
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.Center
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(200.dp)
                                        .background(
                                            Brush.linearGradient(
                                                listOf(
                                                    VinColors.Accent,
                                                    Color(0xFF7C3AED)
                                                )
                                            ),
                                            RoundedCornerShape(24.dp)
                                        )
                                        .padding(2.dp)
                                ) {
                                    AsyncImage(
                                        model = album.thumbnail,
                                        contentDescription = null,
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .clip(RoundedCornerShape(22.dp)),
                                        contentScale = ContentScale.Crop
                                    )
                                }
                            }

                            Spacer(Modifier.height(24.dp))

                            // Metadata & Overlapping Circular Play Button in a responsive split row
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Bottom
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = album.title,
                                        fontSize = 26.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = Color.White,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis
                                    )

                                    Spacer(Modifier.height(6.dp))

                                    Text(
                                        text = album.author,
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = VinColors.AccentLight
                                    )

                                    if (album.songCount.isNotEmpty() || songs.isNotEmpty()) {
                                        Spacer(Modifier.height(4.dp))
                                        val metaText = buildString {
                                            val typeLabel = if (album.playlistId.startsWith("single_") || album.songCount == "1") "Single" else "Album"
                                            append(typeLabel)
                                            if (songs.isNotEmpty()) {
                                                append("  •  ${songs.size} tracks")
                                            }
                                        }
                                        Text(
                                            text = metaText,
                                            fontSize = 13.sp,
                                            color = VinColors.Secondary
                                        )
                                    }

                                    Spacer(Modifier.height(18.dp))

                                    // Custom Action Row on the left (Like, Download, Shuffle)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        var isLiked by remember { mutableStateOf(false) }
                                        IconButton(
                                            onClick = { isLiked = !isLiked },
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(0.06f))
                                        ) {
                                            Icon(
                                                imageVector = if (isLiked) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                                contentDescription = "Like",
                                                tint = if (isLiked) VinColors.Pink else Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }

                                        IconButton(
                                            onClick = {
                                                if (songs.isNotEmpty()) {
                                                    val shuffled = songs.shuffled()
                                                    onSongClick(shuffled[0], shuffled)
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

                                        var isDownloaded by remember { mutableStateOf(false) }
                                        IconButton(
                                            onClick = { isDownloaded = !isDownloaded },
                                            modifier = Modifier
                                                .size(38.dp)
                                                .clip(CircleShape)
                                                .background(Color.White.copy(0.06f))
                                        ) {
                                            Icon(
                                                imageVector = if (isDownloaded) Icons.Default.DownloadDone else Icons.Default.Download,
                                                contentDescription = "Download",
                                                tint = if (isDownloaded) VinColors.AccentLight else Color.White,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }

                                Spacer(Modifier.width(16.dp))

                                // Massive overlapping circular play button on the right
                                Box(
                                    modifier = Modifier
                                        .size(56.dp)
                                        .clip(CircleShape)
                                        .background(
                                            Brush.radialGradient(
                                                listOf(
                                                    VinColors.AccentLight,
                                                    VinColors.Accent
                                                )
                                            )
                                        )
                                        .clickable {
                                            if (songs.isNotEmpty()) {
                                                onSongClick(songs[0], songs)
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

                    // Songs list
                    if (songs.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(40.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("No tracks found in this album.", color = VinColors.Secondary, fontSize = 14.sp)
                            }
                        }
                    } else {
                        itemsIndexed(songs, key = { index, s -> "alb_song_${s.videoId}_$index" }) { index, song ->
                            val isPlaying = vm.currentSong?.videoId == song.videoId
                            AlbumSongRow(
                                index = index + 1,
                                song = song,
                                isPlaying = isPlaying,
                                onMore = { onSongMore(song) },
                                onClick = { onSongClick(song, songs) }
                            )
                        }
                        
                        // Extra Album Release info at the end (as requested by user!)
                        item {
                            Spacer(Modifier.height(24.dp))
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "Released by ${album.author}",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.3f),
                                    textAlign = TextAlign.Center
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = "Official Release",
                                    fontSize = 11.sp,
                                    color = Color.White.copy(alpha = 0.25f),
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

@Composable
private fun AlbumSongRow(
    index: Int,
    song: VideoItem,
    isPlaying: Boolean,
    onMore: () -> Unit,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgColor by animateColorAsState(
        targetValue = when {
            isPlaying -> VinColors.Accent.copy(alpha = 0.12f)
            isPressed -> Color.White.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        label = "album_song_row_bg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current) { onClick() }
            .padding(horizontal = 24.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
            if (isPlaying) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                    contentDescription = null,
                    tint = VinColors.AccentLight,
                    modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    text = "$index",
                    fontSize = 13.sp,
                    color = Color.White.copy(0.35f),
                    textAlign = TextAlign.Center
                )
            }
        }

        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium,
                color = if (isPlaying) VinColors.AccentLight else Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = song.author,
                fontSize = 12.sp,
                color = Color.White.copy(0.4f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        if (song.durationText.isNotEmpty()) {
            Text(
                text = song.durationText,
                fontSize = 12.sp,
                color = Color.White.copy(0.35f)
            )
        }

        IconButton(
            onClick = onMore,
            modifier = Modifier.size(48.dp)
        ) {
            Icon(
                imageVector = Icons.Default.MoreVert,
                contentDescription = "Options",
                tint = Color.White.copy(0.3f),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}
