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
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
    onSongMore: (VideoItem) -> Unit
) {
    val ctx   = LocalContext.current
    val db    = VinDatabase.getInstance(ctx)
    val scope = rememberCoroutineScope()
    
    var downloads by remember { mutableStateOf<List<DownloadEntity>>(emptyList()) }

    LaunchedEffect(Unit) {
        db.downloadDao().getAllFlow().collect { downloads = it }
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
                    "${downloads.size} songs",
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

        // Offline storage and cleanup panel for Downloads
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

                OutlinedButton(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            val failedOrQueued = downloads.filter { it.status == "failed" || it.status == "queued" }
                            var cleanedCount = 0
                            val cache = PlayerSingleton.getCache(ctx)
                            val downloadCache = PlayerSingleton.getDownloadCache(ctx)
                            failedOrQueued.forEach { dl ->
                                try {
                                    val intent = Intent(ctx, DownloadService::class.java).apply {
                                        action = DownloadService.ACTION_CANCEL
                                        putExtra(DownloadService.EXTRA_VIDEO_ID, dl.videoId)
                                    }
                                    ctx.startService(intent)
                                    cache?.removeResource(dl.videoId)
                                    downloadCache?.removeResource(dl.videoId)
                                    dl.thumbnailPath?.let { path ->
                                        val file = java.io.File(path)
                                        if (file.exists()) file.delete()
                                    }
                                } catch (_: Exception) {}
                                db.downloadDao().delete(dl.videoId)
                                cleanedCount++
                            }

                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                android.widget.Toast.makeText(
                                    ctx,
                                    if (cleanedCount > 0) "Cleaned $cleanedCount failed tasks!" else "Storage is fully optimized!",
                                    android.widget.Toast.LENGTH_SHORT
                                ).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = VinColors.AccentLight),
                    border = BorderStroke(1.dp, VinColors.GlassBorder),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.DeleteSweep, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Smart Cleanup", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.width(8.dp))
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
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.DeleteForever, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Delete All", fontSize = 11.sp, fontWeight = FontWeight.Bold)
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
                                colors = listOf(VinColors.Accent, Color(0xFFC084FC))
                            )
                        )
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (downloads.isEmpty()) {
            Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.DownloadForOffline, null,
                        tint = VinColors.White20, modifier = Modifier.size(64.dp))
                    Spacer(Modifier.height(16.dp))
                    Text("No downloads yet", color = VinColors.Secondary, fontSize = 16.sp)
                    Text("Download songs from the player menu", color = VinColors.Secondary, fontSize = 13.sp)
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 140.dp)) {
                items(downloads) { dl ->
                    val song = VideoItem(dl.videoId, dl.title, dl.author, dl.durationText)
                    val isCompleted = dl.status == "completed"
                    val isDownloading = dl.status == "downloading"
                    val isQueued = dl.status == "queued"
                    val isFailed = dl.status == "failed"

                    Row(modifier = Modifier.fillMaxWidth()
                        .background(if (vm.currentSong?.videoId == dl.videoId) VinColors.White10 else Color.Transparent)
                        .clickable(enabled = isCompleted) {
                            val completedSongs = downloads.filter { it.status == "completed" }.map { VideoItem(it.videoId, it.title, it.author, it.durationText) }
                            onSongClick(song, completedSongs)
                        }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {

                        Box(modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))) {
                            // Use local thumbnail if available, otherwise fall back to YouTube CDN
                            val thumbnailModel = if (dl.thumbnailPath != null && File(dl.thumbnailPath!!).exists()) {
                                File(dl.thumbnailPath!!)
                            } else {
                                song.thumbnail
                            }
                            AsyncImage(model = thumbnailModel, contentDescription = null,
                                modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                            if (isCompleted) {
                                Box(modifier = Modifier.align(Alignment.BottomEnd).size(18.dp)
                                    .clip(CircleShape).background(VinColors.Success),
                                    contentAlignment = Alignment.Center) {
                                    Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(10.dp))
                                }
                            } else if (isDownloading || isQueued) {
                                Box(modifier = Modifier.fillMaxSize().background(Color(0x80000000)),
                                    contentAlignment = Alignment.Center) {
                                    CircularProgressIndicator(color = VinColors.AccentLight, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                                }
                            }
                        }

                        Column(Modifier.weight(1f)) {
                            Text(dl.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                fontSize = 14.sp, fontWeight = FontWeight.Medium, color = VinColors.Primary)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(dl.author, maxLines = 1, fontSize = 12.sp, color = VinColors.Secondary)
                                if (isCompleted && dl.sizeBytes > 0) {
                                    Text("• ${formatBytes(dl.sizeBytes)}", fontSize = 12.sp, color = VinColors.Secondary)
                                } else if (isDownloading) {
                                    Text("• Caching ${dl.progress}%", fontSize = 12.sp, color = VinColors.AccentLight, fontWeight = FontWeight.SemiBold)
                                } else if (isQueued) {
                                    Text("• Queued...", fontSize = 12.sp, color = VinColors.Secondary)
                                } else if (isFailed) {
                                    Text("• Failed", fontSize = 12.sp, color = Color(0xFFFF5252))
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
    }
}

private fun formatBytes(bytes: Long): String {
    return when {
        bytes >= 1_000_000 -> "${"%.1f".format(bytes / 1_000_000.0)} MB"
        bytes >= 1_000     -> "${bytes / 1_000} KB"
        else               -> "$bytes B"
    }
}
