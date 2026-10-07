package com.vinmusic.ui.screens

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.vinmusic.data.db.DownloadEntity
import com.vinmusic.data.db.VinDatabase
import com.vinmusic.innertube.VideoItem
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import android.util.Log
import java.io.File
import android.content.Intent
import com.vinmusic.download.DownloadService
import com.vinmusic.player.PlayerSingleton

@OptIn(UnstableApi::class)
@Composable
fun DownloadsScreen(
    vm: PlayerViewModel,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit,
    onSongMore: (VideoItem) -> Unit,
    onCachedSongMore: (VideoItem) -> Unit = onSongMore,
    cacheRefreshToken: Int = 0
) {
    val ctx   = LocalContext.current
    val db    = VinDatabase.getInstance(ctx)
    val scope = rememberCoroutineScope()

    var downloads by remember { mutableStateOf<List<DownloadEntity>>(emptyList()) }
    var cachedSongs by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoadingCached by remember { mutableStateOf(true) }
    var selectedTab by remember { mutableStateOf("Downloads") }

    LaunchedEffect(Unit) {
        db.downloadDao().getAllFlow().collect { downloads = it }
    }

    // Load cached songs from player cache + interaction signals (stable, no jitter)
    LaunchedEffect(cacheRefreshToken, selectedTab) {
        if (cachedSongs.isEmpty()) isLoadingCached = true
        try {
            withContext(Dispatchers.IO) {
                val downloadedIds = downloads.map { it.videoId }.toSet()
                val cached = mutableListOf<VideoItem>()
                val seenIds = mutableSetOf<String>()

                // 1. Get songs from player cache (ExoPlayer SimpleCache)
                val playerCache = com.vinmusic.player.PlayerSingleton.getCache(ctx)
                if (playerCache != null) {
                    val cacheKeys = playerCache.keys
                    val cacheSpanIterator = cacheKeys.iterator()
                    while (cacheSpanIterator.hasNext()) {
                        val key = cacheSpanIterator.next()
                        if (key !in downloadedIds && key !in seenIds) {
                            // Try to find song info from multiple metadata sources
                            val signal = db.interactionSignalDao().get(key)
                            if (signal != null) {
                                cached.add(VideoItem(signal.videoId, signal.title, signal.author, signal.durationText))
                                seenIds.add(key)
                            } else {
                                // Fallback: check history
                                val hist = db.historyDao().getAllHistory().firstOrNull { it.videoId == key }
                                if (hist != null) {
                                    cached.add(VideoItem(hist.videoId, hist.title, hist.author, hist.durationText))
                                    seenIds.add(key)
                                } else {
                                    // Fallback: check song cache meta
                                    val meta = db.songCacheMetaDao().get(key)
                                    if (meta != null) {
                                        cached.add(VideoItem(meta.videoId, meta.title, meta.author, meta.durationText))
                                        seenIds.add(key)
                                    }
                                }
                            }
                        }
                    }
                }

                // 2. Resolve only signals that still have bytes in the player
                // cache. History alone must not make a song look cached; this
                // keeps the per-song Remove Cache action truthful.
                val signals = db.interactionSignalDao().getAll()
                for (signal in signals) {
                    val cachedBytes = playerCache?.getCachedBytes(signal.videoId, 0, -1) ?: 0L
                    if (cachedBytes > 0L && signal.videoId !in downloadedIds && signal.videoId !in seenIds) {
                        cached.add(VideoItem(signal.videoId, signal.title, signal.author, signal.durationText))
                        seenIds.add(signal.videoId)
                    }
                }

                withContext(Dispatchers.Main) { cachedSongs = cached }
            }
            isLoadingCached = false
        } catch (_: Exception) {
            isLoadingCached = false
        }
    }

    val totalCompletedBytes = remember(downloads) { downloads.filter { it.status == "completed" }.sumOf { it.sizeBytes } }
    val usedText = remember(totalCompletedBytes) {
        if (totalCompletedBytes >= 1_000_000_000) {
            "%.2f GB used".format(totalCompletedBytes / 1_000_000_000.0)
        } else {
            "%.1f MB used".format(totalCompletedBytes / 1_000_000.0)
        }
    }
    val storageProgressFraction = remember(totalCompletedBytes) { 
        ((totalCompletedBytes / 10_000_000_000.0).coerceIn(0.01..1.0)).toFloat() 
    }

    Column(modifier = Modifier.fillMaxSize().background(Color.Transparent)) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text("Downloads", fontSize = 26.sp, fontWeight = FontWeight.ExtraBold, color = VinColors.Primary)
                Text(
                    if (selectedTab == "Downloads") "${downloads.size} songs"
                    else "${cachedSongs.size} cached",
                    fontSize = 13.sp,
                    color = VinColors.Secondary
                )
            }
            val completedSongs = downloads.filter { it.status == "completed" }.map { VideoItem(it.videoId, it.title, it.author, it.durationText) }
            if (completedSongs.isNotEmpty()) {
                IconButton(onClick = { onSongClick(completedSongs[0], completedSongs) }) {
                    Icon(Icons.Default.PlayCircle, null, tint = VinColors.Accent, modifier = Modifier.size(36.dp))
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Tab switcher: Downloads | Cached
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(VinColors.White10)
                .padding(4.dp)
        ) {
            listOf("Downloads", "Cached").forEach { tab ->
                val isActive = selectedTab == tab
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (isActive) VinColors.Accent else Color.Transparent)
                        .clickable { selectedTab = tab }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = tab,
                        color = if (isActive) Color.White else VinColors.Secondary,
                        fontSize = 13.sp,
                        fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium
                    )
                }
            }
        }

        Spacer(Modifier.height(4.dp))

        // Offline storage and cleanup panel for Downloads (only show in Downloads tab)
        if (selectedTab == "Downloads") {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(VinColors.White10)
                    .border(1.dp, VinColors.GlassBorder, RoundedCornerShape(16.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Offline Downloads", fontSize = 12.sp, color = VinColors.Secondary, fontWeight = FontWeight.Medium)
                        Text(usedText, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = VinColors.Primary)
                    }

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val hasPending = downloads.any { it.status == "queued" || it.status == "failed" }
                        if (hasPending) {
                            OutlinedButton(
                                onClick = {
                                    scope.launch(Dispatchers.IO) {
                                        val pending = downloads.filter { it.status == "queued" || it.status == "failed" }
                                        for (dl in pending) {
                                            val intent = android.content.Intent(ctx, com.vinmusic.download.DownloadService::class.java).apply {
                                                action = com.vinmusic.download.DownloadService.ACTION_ENQUEUE
                                                putExtra(com.vinmusic.download.DownloadService.EXTRA_VIDEO_ID, dl.videoId)
                                                putExtra(com.vinmusic.download.DownloadService.EXTRA_TITLE, dl.title)
                                                putExtra(com.vinmusic.download.DownloadService.EXTRA_AUTHOR, dl.author)
                                                putExtra(com.vinmusic.download.DownloadService.EXTRA_DURATION, dl.durationText)
                                                putExtra(com.vinmusic.download.DownloadService.EXTRA_THUMBNAIL, dl.thumbnailUrl)
                                            }
                                            ctx.startService(intent)
                                        }
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(ctx, "Resuming ${pending.size} download(s)...", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = VinColors.Accent),
                                border = BorderStroke(1.dp, VinColors.Accent.copy(alpha = 0.6f)),
                                shape = RoundedCornerShape(12.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(Icons.Default.Refresh, null, modifier = Modifier.size(14.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Resume All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }

                        OutlinedButton(
                            onClick = {
                                scope.launch(Dispatchers.IO) {
                                    try {
                                        val downloadCache = com.vinmusic.player.PlayerSingleton.getDownloadCache(ctx)
                                        val allDownloads = db.downloadDao().getAllFlow().first()
                                        for (dl in allDownloads) {
                                            val intent = android.content.Intent(ctx, com.vinmusic.download.DownloadService::class.java).apply {
                                                action = com.vinmusic.download.DownloadService.ACTION_CANCEL
                                                putExtra(com.vinmusic.download.DownloadService.EXTRA_VIDEO_ID, dl.videoId)
                                            }
                                            ctx.startService(intent)
                                            downloadCache?.removeResource(dl.videoId)
                                            try {
                                                dl.thumbnailPath?.let { path ->
                                                    val file = java.io.File(path)
                                                    if (file.exists()) file.delete()
                                                }
                                            } catch (e: Exception) {}
                                            db.downloadDao().delete(dl.videoId)
                                            db.interactionSignalDao().updateDownloaded(dl.videoId, false)
                                        }
                                        withContext(Dispatchers.Main) {
                                            android.widget.Toast.makeText(ctx, "All downloads deleted!", android.widget.Toast.LENGTH_SHORT).show()
                                        }
                                    } catch (e: Exception) {
                                        Log.e("Downloads", "Error deleting all", e)
                                    }
                                }
                            },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = VinColors.AccentLight),
                            border = BorderStroke(1.dp, VinColors.GlassBorder),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Delete All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(CircleShape)
                        .background(VinColors.White10)
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(storageProgressFraction)
                            .clip(CircleShape)
                            .background(
                                androidx.compose.ui.graphics.Brush.horizontalGradient(
                                    colors = listOf(VinColors.Accent, VinColors.AccentLight)
                                )
                            )
                    )
                }
            }
        } // end storage panel

        Spacer(Modifier.height(8.dp))

        if (selectedTab == "Downloads" && downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DownloadForOffline, null,
                        tint = VinColors.White20, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("No downloads yet", color = VinColors.Secondary, fontSize = 16.sp)
                    Text("Download songs from the player menu", color = VinColors.Secondary, fontSize = 13.sp)
                }
            }
        } else if (selectedTab == "Downloads") {
            LazyColumn(contentPadding = PaddingValues(bottom = 220.dp)) {
                itemsIndexed(downloads, key = { index, dl -> "dl_song_${dl.videoId}_$index" }) { index, dl ->
                    val cleanTitle = remember(dl.title) { dl.title.replace(Regex("[\\r\\n]+"), " ").trim() }
                    val cleanAuthor = remember(dl.author) { dl.author.replace(Regex("[\\r\\n]+"), " ").trim() }
                    val song = remember(dl.videoId, cleanTitle, cleanAuthor, dl.durationText) {
                        VideoItem(dl.videoId, cleanTitle, cleanAuthor, dl.durationText)
                    }
                    val isCompleted = dl.status == "completed"
                    val isDownloading = dl.status == "downloading"
                    val isQueued = dl.status == "queued"
                    val isFailed = dl.status == "failed"
                    val isPlaying = vm.currentSong?.videoId == dl.videoId

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 3.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(if (isPlaying) VinColors.White10 else Color.Transparent)
                            .clickable(enabled = isCompleted) {
                                val completedSongs = downloads
                                    .filter { it.status == "completed" }
                                    .map {
                                        VideoItem(
                                            it.videoId,
                                            it.title.replace(Regex("[\\r\\n]+"), " ").trim(),
                                            it.author.replace(Regex("[\\r\\n]+"), " ").trim(),
                                            it.durationText
                                        )
                                    }
                                onSongClick(song, completedSongs)
                            }
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Serial Number on the left
                        Box(
                            modifier = Modifier.width(30.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isPlaying) {
                                Icon(
                                    imageVector = Icons.Default.VolumeUp,
                                    contentDescription = null,
                                    tint = VinColors.AccentLight,
                                    modifier = Modifier.size(16.dp)
                                )
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    color = VinColors.Secondary,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        // Artwork Box
                        Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) {
                            val thumbPath = dl.thumbnailPath
                            val thumbnailModel = if (thumbPath != null && File(thumbPath).exists()) {
                                File(thumbPath)
                            } else {
                                song.thumbnail
                            }
                            AsyncImage(
                                model = thumbnailModel,
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().scale(1.3f),
                                contentScale = ContentScale.Crop
                            )
                            if (isCompleted) {
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(VinColors.Success),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(10.dp))
                                }
                            } else if (isDownloading || isQueued) {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color(0x80000000)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    CircularProgressIndicator(
                                        color = VinColors.AccentLight,
                                        modifier = Modifier.size(24.dp),
                                        strokeWidth = 2.dp
                                    )
                                }
                            }
                        }

                        // Title & Subtitle column
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.Center
                        ) {
                            Text(
                                text = cleanTitle,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = VinColors.Primary
                            )
                            Spacer(Modifier.height(3.dp))
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = cleanAuthor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp,
                                    color = VinColors.Secondary,
                                    modifier = Modifier.weight(1f, fill = false)
                                )
                                if (isCompleted && dl.sizeBytes > 0) {
                                    Text("• ${formatBytes(dl.sizeBytes)}", fontSize = 12.sp, color = VinColors.Secondary, maxLines = 1)
                                } else if (isDownloading) {
                                    Text("• Caching ${dl.progress}%", fontSize = 12.sp, color = VinColors.AccentLight, fontWeight = FontWeight.SemiBold, maxLines = 1)
                                } else if (isQueued) {
                                    Text("• Queued...", fontSize = 12.sp, color = VinColors.Secondary, maxLines = 1)
                                } else if (isFailed) {
                                    Text("• Failed", fontSize = 12.sp, color = Color(0xFFFF5252), maxLines = 1)
                                }
                            }
                        }

                        if (isDownloading) {
                            IconButton(onClick = {
                                val intent = Intent(ctx, DownloadService::class.java).apply {
                                    action = DownloadService.ACTION_PAUSE
                                    putExtra(DownloadService.EXTRA_VIDEO_ID, dl.videoId)
                                }
                                ctx.startService(intent)
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Pause, null, tint = VinColors.AccentLight, modifier = Modifier.size(18.dp))
                            }
                        } else if (isFailed) {
                            IconButton(onClick = {
                                val intent = Intent(ctx, DownloadService::class.java).apply {
                                    action = DownloadService.ACTION_RESUME
                                    putExtra(DownloadService.EXTRA_VIDEO_ID, dl.videoId)
                                    putExtra(DownloadService.EXTRA_TITLE, dl.title)
                                    putExtra(DownloadService.EXTRA_AUTHOR, dl.author)
                                    putExtra(DownloadService.EXTRA_DURATION, dl.durationText)
                                }
                                ctx.startService(intent)
                            }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.Refresh, null, tint = VinColors.Secondary, modifier = Modifier.size(18.dp))
                            }
                        }

                        IconButton(
                            onClick = { onSongMore(song) },
                            modifier = Modifier.size(48.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreVert,
                                contentDescription = "Options",
                                tint = VinColors.Secondary,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }
                }
            }
        }

        // Cached tab
        if (selectedTab == "Cached") {
            if (isLoadingCached) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(200.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(36.dp))
                        Text("Loading cached songs...", color = VinColors.Secondary, fontSize = 13.sp)
                    }
                }
            } else if (cachedSongs.isEmpty()) {
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Cached, null,
                            tint = VinColors.White20, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("No cached songs yet", color = VinColors.Secondary, fontSize = 16.sp)
                        Text("Songs you play will be cached here", color = VinColors.Secondary, fontSize = 13.sp)
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(bottom = 220.dp)) {
                    itemsIndexed(cachedSongs, key = { index, song -> "cached_${song.videoId}_$index" }) { index, song ->
                        val cleanTitle = remember(song.title) { song.title.replace(Regex("[\\r\\n]+"), " ").trim() }
                        val cleanAuthor = remember(song.author) { song.author.replace(Regex("[\\r\\n]+"), " ").trim() }
                        val safeSong = remember(song.videoId, cleanTitle, cleanAuthor, song.durationText) {
                            VideoItem(song.videoId, cleanTitle, cleanAuthor, song.durationText)
                        }
                        val isPlaying = vm.currentSong?.videoId == song.videoId

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 3.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isPlaying) VinColors.White10 else Color.Transparent)
                                .clickable { onSongClick(safeSong, cachedSongs) }
                                .padding(horizontal = 8.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Serial Number
                            Box(
                                modifier = Modifier.width(30.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isPlaying) {
                                    Icon(
                                        imageVector = Icons.Default.VolumeUp,
                                        contentDescription = null,
                                        tint = VinColors.AccentLight,
                                        modifier = Modifier.size(16.dp)
                                    )
                                } else {
                                    Text(
                                        text = "${index + 1}",
                                        color = VinColors.Secondary,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }

                            // Thumbnail
                            Box(modifier = Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) {
                                AsyncImage(
                                    model = safeSong.thumbnail,
                                    contentDescription = null,
                                    modifier = Modifier.fillMaxSize().scale(1.3f),
                                    contentScale = ContentScale.Crop
                                )
                                // Cached indicator
                                Box(
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .size(18.dp)
                                        .clip(CircleShape)
                                        .background(VinColors.Accent),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(Icons.Default.Cached, null, tint = Color.White, modifier = Modifier.size(10.dp))
                                }
                            }

                            // Song info
                            Column(
                                modifier = Modifier.weight(1f),
                                verticalArrangement = Arrangement.Center
                            ) {
                                Text(
                                    text = cleanTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = VinColors.Primary
                                )
                                Spacer(Modifier.height(3.dp))
                                Text(
                                    text = cleanAuthor,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    fontSize = 12.sp,
                                    color = VinColors.Secondary
                                )
                            }

                            // More options
                            IconButton(
                                onClick = { onCachedSongMore(safeSong) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.MoreVert,
                                    contentDescription = "Options",
                                    tint = VinColors.Secondary,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
        bytes >= 1_000     -> "${bytes / 1_000} KB"
        else               -> "$bytes B"
    }
}
