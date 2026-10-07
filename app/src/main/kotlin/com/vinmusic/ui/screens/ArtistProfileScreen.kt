package com.vinmusic.ui.screens

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.*
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.*
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.*
import androidx.compose.ui.unit.*
import coil3.compose.AsyncImage
import com.vinmusic.data.db.FollowedArtist
import com.vinmusic.data.db.VinDatabase
import com.vinmusic.innertube.*
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistProfileScreen(
    artist: ArtistItem,
    vm: PlayerViewModel,
    onBack: () -> Unit,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    onArtistClick: ((ArtistItem) -> Unit)? = null
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    val cachedInitial = remember(artist.channelId, artist.name) {
        com.vinmusic.data.ArtistDataCache.get(artist.channelId) ?: com.vinmusic.data.ArtistDataCache.get(artist.name)
    }
    var topSongs     by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var rareSongs    by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var albums       by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var singles      by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    var bio          by remember { mutableStateOf(cachedInitial?.bio ?: "") }
    var banner       by remember { mutableStateOf(cachedInitial?.bannerUrl ?: "") }
    var subs         by remember { mutableStateOf(cachedInitial?.subscriberCount?.ifBlank { artist.subscriberCount } ?: artist.subscriberCount) }
    var bioExpanded  by remember { mutableStateOf(false) }
    var showAllSongs by remember { mutableStateOf(false) }
    var showAllRare  by remember { mutableStateOf(false) }

    var songsLoading   by remember { mutableStateOf(true) }
    var rareLoading    by remember { mutableStateOf(true) }
    var albumsLoading  by remember { mutableStateOf(true) }
    var singlesLoading by remember { mutableStateOf(true) }

    // ── Data fetching ─────────────────────────────────────────────────────────

    // Top Songs comes from the artist's real YouTube channel Videos tab and is
    // ranked by YouTube's view-count metadata. Search remains a fallback for
    // channels that do not expose a readable Videos tab. Rare/unreleased/demo/
    // leak tracks still go into the separate "More from Artist" section.
    LaunchedEffect(artist.name) {
        songsLoading = true
        rareLoading = true
        withContext(Dispatchers.IO) {
            try {
                // Terms that mark a song as unofficial — these are filtered OUT
                // of Top Songs and routed into "More from Artist" instead.
                val leakTerms = listOf(
                    "unreleased", "leak", "leaked", "demo", "rare", "loosie",
                    "snippet", "teaser", "preview", "unofficial", "vibe", "cdq leak"
                )
                fun isLeak(item: VideoItem): Boolean {
                    val t = item.title.lowercase()
                    return leakTerms.any { t.contains(it) }
                }

                val channelVideos = runCatching {
                    InnerTube.getArtistChannelVideos(artist.channelId, artist.name)
                }.getOrDefault(emptyList())
                val q1 = InnerTube.search("${artist.name} songs")
                val q2 = InnerTube.search("${artist.name} best hits")
                fun score(item: VideoItem): Int {
                    val title = item.title.lowercase()
                    val author = item.author.lowercase()
                    val artistName = artist.name.lowercase()
                    var rank = 0
                    if (author.contains(artistName)) rank += 40
                    if (title.contains(artistName)) rank += 30
                    if (author.contains("topic") || author.contains("vevo")) rank += 15
                    if (listOf("audio", "song", "track", "lyrics", "lyric").any { title.contains(it) }) rank += 8
                    return rank
                }
                // Split the search hits for the fallback/rare bucket. Channel
                // videos are preferred for Top Songs so unrelated search matches
                // cannot displace the artist's own uploads.
                val (officialSongs, leakedFromYtm) = (q1 + q2)
                    .distinctBy { it.videoId }
                    .partition { !isLeak(it) }

                val officialRanked = officialSongs
                    .sortedByDescending { score(it) }
                    .take(50)

                val nonMusicVideoTerms = listOf(
                    "interview", "reaction", "podcast", "behind the scenes",
                    "documentary", "trailer", "teaser", "vlog", "shorts"
                )
                val channelTopSongs = channelVideos.filter { item ->
                    !isLeak(item) && nonMusicVideoTerms.none { term ->
                        item.title.contains(term, ignoreCase = true)
                    }
                }
                val preferredTopSongs = channelTopSongs.ifEmpty { officialRanked }

                val rareFromUploads = runCatching { InnerTube.getArtistRareUploads(artist.name) }
                    .getOrDefault(emptyList())

                val rareBucket = (leakedFromYtm + rareFromUploads)
                    .distinctBy { it.videoId }
                    .filter { rareItem -> preferredTopSongs.none { it.videoId == rareItem.videoId } }
                    .sortedByDescending { score(it) }
                    .take(20)

                withContext(Dispatchers.Main) {
                    topSongs = preferredTopSongs
                    songsLoading = false
                    rareSongs = rareBucket
                    rareLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    songsLoading = false
                    rareLoading = false
                }
            }
        }
    }

    // Load albums and singles via YouTube Music API (Browse or Search Fallback)
    LaunchedEffect("releases_${artist.name}_${artist.channelId}") {
        albumsLoading = true
        singlesLoading = true
        withContext(Dispatchers.IO) {
            try {
                if (artist.channelId.isNotEmpty()) {
                    val (ytmAlbums, ytmSingles) = InnerTube.getArtistAlbumsAndSingles(artist.channelId, artist.name)
                    if (ytmAlbums.isNotEmpty() || ytmSingles.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            albums = ytmAlbums
                            singles = ytmSingles
                            albumsLoading = false
                            singlesLoading = false
                        }
                        return@withContext
                    }
                }
                
                // Fallback to query-based search if channelId is empty or browse returns nothing
                val ytmAlbums = InnerTube.searchArtistAlbums(artist.name)
                val ytmSingles = InnerTube.searchArtistSingles(artist.name)
                withContext(Dispatchers.Main) {
                    albums = ytmAlbums
                    singles = ytmSingles
                    albumsLoading = false
                    singlesLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    albumsLoading = false
                    singlesLoading = false
                }
            }
        }
    }

    // Similar artists (disabled)

    // Channel bio + banner + dynamic avatar
    var avatar by remember { mutableStateOf(artist.thumbnail) }
    LaunchedEffect("channel_${artist.channelId}_${artist.name}") {
        withContext(Dispatchers.IO) {
            try {
                val cd = InnerTube.fetchChannelData(artist.channelId, artist.name)
                withContext(Dispatchers.Main) {
                    if (cd.bannerUrl.isNotEmpty()) banner = cd.bannerUrl
                    if (cd.bio.isNotEmpty()) bio = cd.bio
                    if (cd.subscriberCount.isNotEmpty()) subs = cd.subscriberCount
                    if (cd.avatarUrl.isNotEmpty()) avatar = cd.avatarUrl
                }
            } catch (_: Exception) {}
        }
    }

    // Persistent follow state
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val db  = remember { VinDatabase.getInstance(ctx) }
    val followScope = rememberCoroutineScope()
    var isFollowed by remember { mutableStateOf(false) }
    LaunchedEffect(artist.channelId) {
        if (artist.channelId.isNotEmpty()) {
            isFollowed = withContext(Dispatchers.IO) {
                db.followedArtistDao().isFollowing(artist.channelId)
            }
        }
    }

    fun toggleFollowArtist() {
        val next = !isFollowed
        isFollowed = next
        followScope.launch(Dispatchers.IO) {
            if (next) {
                db.followedArtistDao().insert(
                    FollowedArtist(
                        channelId = artist.channelId,
                        name = artist.name,
                        thumbnail = avatar.ifEmpty { artist.thumbnail },
                        subscriberCount = subs
                    )
                )
            } else {
                db.followedArtistDao().delete(artist.channelId)
            }
        }
    }

    // ── UI ────────────────────────────────────────────────────────────────────
    LazyColumn(
        modifier = Modifier.fillMaxSize().background(VinColors.BgColor),
        contentPadding = PaddingValues(bottom = 220.dp)
    ) {

        // ── Premium Artist Profile Header Banner ──────────────────────────────
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .wrapContentHeight()
            ) {
                // Highly faded background banner image (translucent face in background)
                val bannerSrc = banner.ifEmpty {
                    avatar.ifEmpty { topSongs.firstOrNull()?.thumbnailHd ?: artist.thumbnail }
                }
                AsyncImage(
                    model = bannerSrc,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .alpha(0.35f),
                    contentScale = ContentScale.Crop
                )
                
                // Linear black gradient overlay to fade banner background into BgColor at the bottom
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(260.dp)
                        .background(
                            Brush.verticalGradient(
                                listOf(
                                    Color.Transparent,
                                    Color.Black.copy(0.4f),
                                    VinColors.BgColor
                                )
                            )
                        )
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .statusBarsPadding()
                        .padding(horizontal = 20.dp, vertical = 8.dp)
                ) {
                    // Top header row: Back button, Title, Follow/Following button
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 20.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = Color.White
                            )
                        }
                        
                        Text(
                            text = "Artist Profile",
                            fontSize = 17.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        
                        Button(
                            onClick = { toggleFollowArtist() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isFollowed) Color.White.copy(0.08f) else Color.White,
                                contentColor = if (isFollowed) Color.White else Color.Black
                            ),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
                            modifier = Modifier.height(34.dp)
                        ) {
                            Text(
                                text = if (isFollowed) "Following" else "Follow",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }

                    // Circle avatar of the artist (large, top left, with glowing amber-gold border)
                    Box(
                        modifier = Modifier
                            .size(96.dp)
                            .clip(CircleShape)
                            .border(2.dp, VinColors.Accent.copy(0.8f), CircleShape) // Light Brown border
                            .padding(2.dp)
                    ) {
                        AsyncImage(
                            model = avatar,
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize().clip(CircleShape),
                            contentScale = ContentScale.Crop
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    // Artist name with red verified check badge
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = artist.name,
                            fontSize = 28.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color.White,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        
                        // Red circle verified badge checkmark
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(VinColors.Accent),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Check,
                                contentDescription = "Verified",
                                tint = Color.White,
                                modifier = Modifier.size(12.dp)
                            )
                        }
                    }

                    Spacer(Modifier.height(18.dp))

                    // Stats & Socials Row
                    val cleanSubs = artistMonthlyListenersCount(subs)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Listener stats
                        if (cleanSubs.isNotEmpty()) {
                            Column {
                                Text(
                                    text = cleanSubs,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Text(
                                    text = "Subscribers",
                                    fontSize = 12.sp,
                                    color = Color.White.copy(0.4f),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        // Right side: Social media icons next to stats
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    val url = if (artist.channelId.isNotBlank()) {
                                        "https://www.youtube.com/channel/${artist.channelId}"
                                    } else {
                                        "https://www.youtube.com/results?search_query=${android.net.Uri.encode(artist.name)}"
                                    }
                                    runCatching {
                                        context.startActivity(
                                            android.content.Intent(
                                                android.content.Intent.ACTION_VIEW,
                                                android.net.Uri.parse(url)
                                            )
                                        )
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Public, contentDescription = "Open YouTube channel", tint = Color.White.copy(0.7f), modifier = Modifier.size(20.dp))
                            }
                            IconButton(onClick = { toggleFollowArtist() }, modifier = Modifier.size(32.dp)) {
                                Icon(
                                    imageVector = if (isFollowed) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                                    contentDescription = if (isFollowed) "Unfollow artist" else "Follow artist",
                                    tint = if (isFollowed) VinColors.AccentLight else Color.White.copy(0.7f),
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // ── Latest Release Card ───────────────────────────────────────────────
        item {
            val latestRelease = albums.firstOrNull() ?: singles.firstOrNull()
            if (latestRelease != null || topSongs.isNotEmpty()) {
                val latestTitle = latestRelease?.title ?: (topSongs.firstOrNull()?.title ?: "")
                val latestThumb = latestRelease?.thumbnail ?: (topSongs.firstOrNull()?.thumbnailHd ?: "")
                val latestMeta = if (latestRelease != null) {
                    val type = if (albums.contains(latestRelease)) "Album" else "Single"
                    // YT Music's subtitle carries its actual available release
                    // metadata (year, type, artist, and/or track count). Never
                    // invent a date when the source does not provide one.
                    val details = latestRelease.metadataSummary()
                        .replace(Regex("""(?i)\b(album|single|ep)\b\s*[•·]?\s*"""), "")
                        .trim(' ', '•', '·')
                    if (details.isBlank()) type else "$type · $details"
                } else {
                    "Popular single"
                }

                Card(
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF0C0C0E)),
                    border = BorderStroke(1.dp, Color.White.copy(0.04f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Lates Release gray tag
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color.White.copy(0.08f))
                                    .padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "Latest Release",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(0.7f)
                                )
                            }
                            
                            Spacer(Modifier.height(10.dp))
                            
                            Text(
                                text = latestTitle,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            
                            Text(
                                text = latestMeta,
                                fontSize = 12.sp,
                                color = Color.White.copy(0.4f),
                                modifier = Modifier.padding(top = 2.dp)
                            )
                            
                        }
                        
                        Spacer(Modifier.width(16.dp))
                        
                        // Image on the right
                        AsyncImage(
                            model = latestThumb,
                            contentDescription = null,
                            modifier = Modifier
                                .size(96.dp)
                                .clip(RoundedCornerShape(12.dp)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        }

        // ── Bio ───────────────────────────────────────────────────────────────
        if (bio.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Text(
                        if (bioExpanded || bio.length <= 180) bio else "${bio.take(180)}…",
                        fontSize = 13.sp, color = Color.White.copy(0.55f), lineHeight = 20.sp
                    )
                    if (bio.length > 180) {
                        Text(
                            if (bioExpanded) "Show less" else "Read more",
                            fontSize = 13.sp, color = VinColors.AccentLight,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.clickable { bioExpanded = !bioExpanded }.padding(top = 4.dp)
                        )
                    }
                }
                HorizontalDivider(
                    color = VinColors.GlassBorder.copy(alpha = 0.4f),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                )
            }
        }

        // ── Top Songs ─────────────────────────────────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 18.dp, bottom = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Top Songs", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                if (topSongs.size > 6) {
                    TextButton(onClick = { showAllSongs = !showAllSongs }) {
                        Text(
                            if (showAllSongs) "Show less" else "See all",
                            color = VinColors.AccentLight, fontSize = 13.sp
                        )
                    }
                }
            }
        }

        if (songsLoading) {
            item { ArtLoadingRow() }
        } else if (topSongs.isEmpty()) {
            item {
                Text(
                    "No songs found", color = VinColors.Secondary, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        } else {
            val shown = if (showAllSongs) topSongs else topSongs.take(6)
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shown, key = { "ts_${it.videoId}" }) { song ->
                        ArtistSongCard(song, vm.currentSong?.videoId == song.videoId) {
                            onSongClick(song, topSongs)
                        }
                    }
                }
            }
        }

        // ── More from Artist (rare / unreleased / unofficial) ────────────────
        // Surfaces leaked/unreleased/demo uploads (Kendrick "prayer", J Cole
        // "4 Your Eyez"-style unofficial uploads) that Top Songs filters out.
        // Only renders when we actually found some, so artists with no rare
        // uploads look identical to before.
        if (!rareLoading && rareSongs.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 28.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("More from Artist", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                        Text(
                            "${rareSongs.size} rare & unreleased",
                            fontSize = 12.sp, color = Color.White.copy(0.4f), fontWeight = FontWeight.Medium
                        )
                    }
                    if (rareSongs.size > 6) {
                        TextButton(onClick = { showAllRare = !showAllRare }) {
                            Text(
                                if (showAllRare) "Show less" else "See all",
                                color = VinColors.AccentLight, fontSize = 13.sp
                            )
                        }
                    }
                }
            }
            val shownRare = if (showAllRare) rareSongs else rareSongs.take(6)
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(shownRare, key = { "rare_${it.videoId}" }) { song ->
                        ArtistSongCard(song, vm.currentSong?.videoId == song.videoId) {
                            onSongClick(song, rareSongs)
                        }
                    }
                }
            }
        }

        // ── Albums ────────────────────────────────────────────────────────────
        item {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 24.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Albums", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
            }
        }
        if (albumsLoading) {
            item { ArtLoadingRow() }
        } else if (albums.isEmpty()) {
            item {
                Text(
                    "No albums found", color = VinColors.Secondary, fontSize = 13.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        } else {
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(albums, key = { "alb_${it.playlistId}" }) { album ->
                        ArtAlbumCard(album, isAlbum = true) {
                            onAlbumClick(album)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }

        // ── Singles & EPs ─────────────────────────────────────────────────────
        if (!singlesLoading && singles.isNotEmpty()) {
            item {
                Row(
                    Modifier.fillMaxWidth().padding(start = 16.dp, end = 12.dp, top = 24.dp, bottom = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Singles & EPs", fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, color = Color.White)
                }
            }
            item {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(singles, key = { "sin_${it.playlistId}" }) { single ->
                        ArtAlbumCard(single, isAlbum = false) {
                            onAlbumClick(single)
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
    }
}

private fun artistMonthlyListenersCount(source: String): String {
    return source
        .replace(Regex("""@\S+"""), "")
        .replace("subscribers", "", ignoreCase = true)
        .replace("subscriber", "", ignoreCase = true)
        .replace(Regex("""\bartist\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""[•|·]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
}

private suspend fun resolveAlbumSongs(album: AlbumItem, context: android.content.Context): List<VideoItem> {
    return if (album.playlistId.startsWith("album_") || album.playlistId.startsWith("single_")) {
        val trackId = album.playlistId.substringAfterLast("_")
        listOf(VideoItem(trackId, album.title, album.author))
    } else {
        try {
            if (album.playlistId.startsWith("MPRE")) {
                InnerTube.getAlbumSongs(album.playlistId)
            } else {
                InnerTube.getPlaylistSongs(album.playlistId).second
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

// ── Sub-components ────────────────────────────────────────────────────────────

@Composable
private fun ArtLoadingRow() {
    Box(Modifier.fillMaxWidth().height(90.dp), Alignment.Center) {
        CircularProgressIndicator(
            color = VinColors.Accent,
            modifier = Modifier.size(26.dp),
            strokeWidth = 2.dp
        )
    }
}

@Composable
private fun ArtistSongCard(song: VideoItem, isPlaying: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgColor by animateColorAsState(
        targetValue = when {
            isPlaying -> VinColors.Accent.copy(alpha = 0.16f)
            isPressed -> Color.White.copy(alpha = 0.08f)
            else -> VinColors.Surface.copy(alpha = 0.72f)
        },
        label = "artist_song_card_bg"
    )

    Column(
        modifier = Modifier
            .width(142.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .padding(8.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(9.dp))
        ) {
            AsyncImage(
                model = song.thumbnailHd.ifEmpty { song.thumbnail },
                contentDescription = song.title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            if (isPlaying) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.4f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = "Playing", tint = VinColors.AccentLight, modifier = Modifier.size(22.dp))
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            song.title,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            color = if (isPlaying) VinColors.AccentLight else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            song.author,
            fontSize = 11.sp,
            color = Color.White.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ArtSongRow(index: Int, song: VideoItem, isPlaying: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val bgColor by animateColorAsState(
        targetValue = when {
            isPlaying -> VinColors.Accent.copy(alpha = 0.12f)
            isPressed -> Color.White.copy(alpha = 0.05f)
            else -> Color.Transparent
        },
        label = "song_row_bg"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current) { onClick() }
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Index number or playing indicator
        Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) {
            if (isPlaying) {
                Icon(
                    Icons.AutoMirrored.Filled.VolumeUp, null,
                    tint = VinColors.AccentLight, modifier = Modifier.size(16.dp)
                )
            } else {
                Text(
                    "$index", fontSize = 14.sp,
                    color = Color.White.copy(0.4f),
                    textAlign = TextAlign.Center
                )
            }
        }
        // Thumbnail
        Box(Modifier.size(48.dp).clip(RoundedCornerShape(8.dp))) {
            AsyncImage(
                model = song.thumbnail, contentDescription = null,
                modifier = Modifier.fillMaxSize().scale(1.3f), contentScale = ContentScale.Crop
            )
            if (isPlaying) {
                Box(Modifier.fillMaxSize().background(Color.Black.copy(0.4f)))
            }
        }
        // Title + author + stats
        Column(Modifier.weight(1f)) {
            Text(
                song.title, fontSize = 14.sp, fontWeight = FontWeight.Bold,
                color = if (isPlaying) VinColors.AccentLight else Color.White,
                maxLines = 1, overflow = TextOverflow.Ellipsis
            )
            Text(
                song.author, fontSize = 12.sp,
                color = Color.White.copy(0.4f), maxLines = 1
            )
        }
        if (song.durationText.isNotEmpty()) {
            Text(song.durationText, fontSize = 12.sp, color = Color.White.copy(0.35f))
        }
        Icon(Icons.Default.MoreVert, null, tint = Color.White.copy(0.3f), modifier = Modifier.size(18.dp))
    }
}

@Composable
private fun ArtAlbumCard(album: AlbumItem, isAlbum: Boolean, onClick: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.95f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "album_scale"
    )

    Column(
        Modifier
            .width(140.dp)
            .graphicsLayer(scaleX = scale, scaleY = scale)
            .clickable(interactionSource = interactionSource, indication = null) { onClick() }
    ) {
        Box(Modifier.size(140.dp).clip(RoundedCornerShape(12.dp))) {
            if (album.thumbnail.isNotEmpty()) {
                AsyncImage(
                    model = album.thumbnail, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop
                )
            } else {
                Box(Modifier.fillMaxSize().background(VinColors.White10), Alignment.Center) {
                    Icon(Icons.Default.Album, null, tint = VinColors.Secondary, modifier = Modifier.size(40.dp))
                }
            }
            // Play button overlay
            Box(
                Modifier.align(Alignment.BottomEnd).padding(8.dp)
                    .size(32.dp).clip(CircleShape)
                    .background(VinColors.Accent.copy(0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.PlayArrow, null, tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(
            album.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val typeLabel = if (isAlbum) "Album" else "Single"
            Text(typeLabel, fontSize = 11.sp, color = VinColors.Accent.copy(0.8f), fontWeight = FontWeight.Medium)
            album.trackCountLabel()?.let { trackCount ->
                Text("·", fontSize = 11.sp, color = Color.White.copy(0.3f))
                Text(trackCount, fontSize = 11.sp, color = Color.White.copy(0.4f))
            }
        }
    }
}
