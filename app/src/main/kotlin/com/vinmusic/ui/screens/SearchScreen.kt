package com.vinmusic.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.foundation.shape.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.vinmusic.innertube.*
import com.vinmusic.player.PlayerViewModel
import com.vinmusic.ui.components.SongListItem
import com.vinmusic.ui.theme.VinColors
import kotlinx.coroutines.*
import android.content.Context
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.activity.compose.BackHandler

private fun getSearchHistory(context: Context): List<String> {
    val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    val historyString = prefs.getString("history", "") ?: ""
    if (historyString.isEmpty()) return emptyList()
    return historyString.split("\n").filter { it.isNotEmpty() }
}

private fun saveSearchHistory(context: Context, query: String, onUpdate: (List<String>) -> Unit) {
    val trimmed = query.trim()
    if (trimmed.isEmpty()) return
    val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    val currentHistory = getSearchHistory(context).toMutableList()
    currentHistory.remove(trimmed)
    currentHistory.add(0, trimmed)
    val limitedHistory = currentHistory.take(15)
    prefs.edit().putString("history", limitedHistory.joinToString("\n")).apply()
    onUpdate(limitedHistory)
}

private fun deleteSearchHistoryItem(context: Context, query: String, onUpdate: (List<String>) -> Unit) {
    val prefs = context.getSharedPreferences("search_history", Context.MODE_PRIVATE)
    val currentHistory = getSearchHistory(context).toMutableList()
    currentHistory.remove(query)
    prefs.edit().putString("history", currentHistory.joinToString("\n")).apply()
    onUpdate(currentHistory)
}

private fun isDirectArtistMatch(query: String, artistName: String): Boolean {
    val q = query.trim().lowercase()
    val name = artistName.trim().lowercase()
        .replace("- topic", "").replace("vevo", "").trim()
    // Query must be a substantial match to the artist name
    // e.g. "j cole" matches "J. Cole" but "baby doll" doesn't match "Baby Doll Series"
    val qWords = q.split(" ").filter { it.length > 1 }
    val nameWords = name.split(" ").filter { it.length > 1 }
    // All query words must appear in artist name, or all name words in query
    val queryMatchesName = qWords.all { qw -> nameWords.any { nw -> nw.startsWith(qw) || qw.startsWith(nw) } }
    val nameMatchesQuery = nameWords.all { nw -> qWords.any { qw -> qw.startsWith(nw) || nw.startsWith(qw) } }
    // At least one direction must match AND name shouldn't be much longer than query (prevents "Baby Doll Series" matching "baby doll")
    return (queryMatchesName || nameMatchesQuery) && nameWords.size <= qWords.size + 1
}

private enum class SearchTab { ALL, SONGS, VIDEOS, ARTISTS, ALBUMS }

@OptIn(UnstableApi::class)
@Composable
fun SearchScreen(
    vm: PlayerViewModel,
    onSongClick: (VideoItem, List<VideoItem>) -> Unit,
    onSongMore: (VideoItem) -> Unit,
    onAlbumClick: (AlbumItem) -> Unit,
    isPlayerOpen: Boolean = false
) {
    var query          by remember { mutableStateOf("") }
    var activeTab      by remember { mutableStateOf(SearchTab.ALL) }
    var allResults     by remember { mutableStateOf(AllSearchResults()) }
    var suggestions    by remember { mutableStateOf<List<String>>(emptyList()) }
    var isLoading      by remember { mutableStateOf(false) }
    var showSugg       by remember { mutableStateOf(false) }
    var selectedArtist by remember { mutableStateOf<ArtistItem?>(null) }
    // Extra songs from "Load More"
    var moreSongs      by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var isLoadingMore  by remember { mutableStateOf(false) }
    // Artist-specific albums (fetched when artist found)
    var artistAlbums   by remember { mutableStateOf<List<AlbumItem>>(emptyList()) }
    // Smart recs when no songs found
    var searchRecs     by remember { mutableStateOf<List<VideoItem>>(emptyList()) }
    var recsLabel      by remember { mutableStateOf("") }

    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var isFocused by remember { mutableStateOf(false) }
    var searchHistory by remember { mutableStateOf(getSearchHistory(context)) }

    val scope      = rememberCoroutineScope()
    var searchJob by remember { mutableStateOf<Job?>(null) }
    var suggJob by remember { mutableStateOf<Job?>(null) }

    fun loadMoreSongs(q: String) {
        if (isLoadingMore) return
        isLoadingMore = true
        scope.launch(Dispatchers.IO) {
            val existing = allResults.songs.map { it.videoId }.toSet() + moreSongs.map { it.videoId }.toSet()
            try {
                val extras = listOf(
                    "$q official songs",
                    "$q popular songs audio",
                    "$q rare unreleased songs audio"
                ).map { query ->
                    async { runCatching { InnerTube.search(query) }.getOrDefault(emptyList()) }
                }.awaitAll().flatten()
                    .filterNot { com.vinmusic.recommendation.RecommendationManager.isNonMusicVideo(it.title, it.author) }
                    .filterNot { com.vinmusic.recommendation.RecommendationManager.isCompilationTrack(it.title, it.durationText) }
                val nextMoreSongs = (moreSongs + extras).distinctBy { it.videoId.ifBlank { "${it.title}|${it.author}" } }
                    .filter { it.videoId !in existing }
                withContext(Dispatchers.Main) {
                    moreSongs = nextMoreSongs
                }
            } finally {
                withContext(Dispatchers.Main) {
                    isLoadingMore = false
                }
            }
        }
    }

    fun doSearch(q: String) {
        if (q.isBlank()) return
        saveSearchHistory(context, q) { searchHistory = it }
        focusManager.clearFocus()
        showSugg = false; isLoading = true; selectedArtist = null
        moreSongs = emptyList(); artistAlbums = emptyList()
        searchRecs = emptyList(); recsLabel = ""
        searchJob?.cancel()
        searchJob = scope.launch(Dispatchers.IO) {
            val fastSongs = runCatching {
                withTimeout(5_500L) { InnerTube.search(q) }
            }.getOrDefault(emptyList())
            if (fastSongs.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    allResults = AllSearchResults(songs = fastSongs)
                    isLoading = false
                }
            }

            val r = runCatching {
                withTimeout(9_000L) { InnerTube.searchAll(q) }
            }.getOrElse {
                AllSearchResults(songs = fastSongs)
            }
            val filteredArtists = r.artists.filter { isDirectArtistMatch(q, it.name) }
            val merged = r.copy(
                songs = (fastSongs + r.songs).distinctBy { it.videoId },
                artists = filteredArtists
            )
            withContext(Dispatchers.Main) {
                allResults = merged
                isLoading = false
            }

            if (merged.artists.isNotEmpty()) {
                val artist = merged.artists[0]
                // When the query resolves directly to an artist, enrich the
                // Videos tab with that artist's own channel feed. The channel
                // loader already ranks entries by YouTube view count, so these
                // appear as the Popular videos first instead of generic search
                // matches from unrelated uploaders.
                scope.launch(Dispatchers.IO) {
                    val channelVideos = runCatching {
                        InnerTube.getArtistChannelVideos(artist.channelId, artist.name)
                    }.getOrDefault(emptyList())
                    if (channelVideos.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            if (query.trim().equals(q.trim(), ignoreCase = true)) {
                                allResults = allResults.copy(
                                    videos = (channelVideos + allResults.videos)
                                        .distinctBy { it.videoId }
                                )
                            }
                        }
                    }
                }
                scope.launch(Dispatchers.IO) {
                    var mergedReleases = emptyList<AlbumItem>()
                    try {
                        if (artist.channelId.isNotEmpty()) {
                            val (ytmAlbums, ytmSingles) = InnerTube.getArtistAlbumsAndSingles(artist.channelId, artist.name)
                            mergedReleases = ytmAlbums + ytmSingles
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("VIN_SEARCH", "Error fetching artist browse albums: ${e.message}")
                    }

                    if (mergedReleases.isEmpty()) {
                        try {
                            val ytmAlbums = InnerTube.searchArtistAlbums(artist.name)
                            val ytmSingles = InnerTube.searchArtistSingles(artist.name)
                            mergedReleases = ytmAlbums + ytmSingles
                        } catch (e: Exception) {
                            android.util.Log.e("VIN_SEARCH", "Error fetching artist fallback albums: ${e.message}")
                        }
                    }

                    if (mergedReleases.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            artistAlbums = mergedReleases.distinctBy { it.playlistId }
                        }
                    }
                }
            }

            if (merged.songs.isEmpty()) {
                val words = q.trim().split(" ").filter { it.length > 2 }
                val recQuery = when {
                    merged.artists.isNotEmpty() -> "${merged.artists[0].name} top songs audio"
                    words.isNotEmpty()     -> "${words.take(3).joinToString(" ")} song audio"
                    else                   -> "top hindi songs ${java.time.LocalDate.now().year} audio"
                }
                val recs = withContext(Dispatchers.IO) {
                    runCatching { withTimeout(5_500L) { InnerTube.search(recQuery) } }.getOrDefault(emptyList())
                }
                withContext(Dispatchers.Main) {
                    recsLabel = if (merged.artists.isNotEmpty()) "Songs by ${merged.artists[0].name}" else "You might like"
                    searchRecs = recs
                }
            }
        }
    }

    // Navigate to artist profile
    if (selectedArtist != null) {
        val artist = selectedArtist ?: return
        ArtistProfileScreen(
            artist      = artist,
            vm          = vm,
            onBack      = { selectedArtist = null },
            onSongClick = onSongClick,
            onAlbumClick = onAlbumClick
        )
        return
    }

    if (isFocused && !isPlayerOpen) {
        BackHandler {
            focusManager.clearFocus()
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Transparent)) {
        // ── Search bar ────────────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = query, onValueChange = { q ->
                    query = q
                    showSugg = q.isNotEmpty()
                    suggestions = emptyList()
                    suggJob?.cancel()
                    if (q.isNotBlank()) {
                        suggJob = scope.launch {
                            delay(300)
                            val suggs = withContext(Dispatchers.IO) {
                                runCatching { InnerTube.getSuggestions(q) }.getOrDefault(emptyList())
                            }
                            suggestions = suggs
                        }
                    }
                },
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { isFocused = it.isFocused },
                placeholder = { Text("Songs, artists, albums...", color = VinColors.Secondary) },
                leadingIcon  = { Icon(Icons.Default.Search, null, tint = VinColors.Secondary) },
                trailingIcon = {
                    if (query.isNotEmpty()) IconButton(onClick = {
                        query = ""; allResults = AllSearchResults(); suggestions = emptyList()
                        showSugg = false; moreSongs = emptyList(); artistAlbums = emptyList()
                        searchRecs = emptyList()
                    }) { Icon(Icons.Default.Close, null, tint = VinColors.Secondary) }
                },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VinColors.Accent, unfocusedBorderColor = VinColors.GlassBorder,
                    focusedTextColor = VinColors.Primary, unfocusedTextColor = VinColors.Primary,
                    cursorColor = VinColors.Accent,
                    focusedContainerColor = VinColors.White10, unfocusedContainerColor = VinColors.White10
                ),
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSearch = { doSearch(query) })
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = { doSearch(query) },
                colors = ButtonDefaults.buttonColors(containerColor = VinColors.Accent),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
                Text("Go", fontWeight = FontWeight.Bold)
            }
        }

        Box(Modifier.fillMaxWidth().weight(1f)) {
            // ── Main Content ──────────────────────────────────────────────────
            Column(Modifier.fillMaxSize()) {
                // ── Tabs ──────────────────────────────────────────────────────────────
                val allSongs   = allResults.songs + moreSongs
                val hasResults = allResults.songs.isNotEmpty() || allResults.videos.isNotEmpty() || allResults.artists.isNotEmpty() || allResults.albums.isNotEmpty()
                if (hasResults || isLoading) {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(SearchTab.entries) { tab ->
                            val active = activeTab == tab
                            val label = when (tab) {
                                SearchTab.ALL     -> "All"
                                SearchTab.SONGS   -> if (allSongs.isNotEmpty()) "Songs (${allSongs.size}+)" else "Songs"
                                SearchTab.VIDEOS  -> if (allResults.videos.isNotEmpty()) "Videos (${allResults.videos.size}+)" else "Videos"
                                SearchTab.ARTISTS -> "Artists"
                                SearchTab.ALBUMS  -> "Albums"
                            }
                            Box(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(20.dp))
                                    .background(if (active) VinColors.Accent else VinColors.White10)
                                    .border(1.dp, if (active) Color.Transparent else VinColors.GlassBorder, RoundedCornerShape(20.dp))
                                    .clickable { activeTab = tab }
                                    .padding(horizontal = 18.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = label,
                                    fontSize = 13.sp,
                                    color = if (active) Color.White else VinColors.Primary,
                                    fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                // ── Content ───────────────────────────────────────────────────────────
                if (isLoading) {
                    Box(Modifier.fillMaxSize(), Alignment.Center) {
                        CircularProgressIndicator(color = VinColors.Accent)
                    }
                } else if (!hasResults && query.isNotEmpty()) {
                    // No results — smart recommendations
                    LazyColumn(contentPadding = PaddingValues(bottom = 220.dp)) {
                        item {
                            Column(Modifier.fillMaxWidth().padding(16.dp, 20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.MusicOff, null, tint = VinColors.Secondary, modifier = Modifier.size(44.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("No results for \"$query\"", color = VinColors.Secondary, fontSize = 14.sp)
                                if (searchRecs.isNotEmpty()) {
                                    Spacer(Modifier.height(4.dp))
                                    Text("Here's what we think you'll like:", color = VinColors.Secondary.copy(alpha = 0.6f), fontSize = 12.sp)
                                }
                            }
                        }
                        if (searchRecs.isNotEmpty()) {
                            item {
                                Text(recsLabel.ifEmpty { "You might like" },
                                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = VinColors.Primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                            }
                            items(searchRecs, key = { it.videoId }) { song ->
                                SongListItem(song = song, isPlaying = vm.currentSong?.videoId == song.videoId,
                                    onClick = { onSongClick(song, searchRecs) },
                                    onMore = { onSongMore(song) })
                            }
                        }
                    }
                } else if (!hasResults) {
                    SearchEmptyState()
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 220.dp)) {
                        when (activeTab) {
                            // ── ALL tab: Artist → Albums → Songs ─────────────────────
                            SearchTab.ALL -> {
                                // 1. Top artist (big card)
                                if (allResults.artists.isNotEmpty()) {
                                    item {
                                        SearchSectionHeader("Artist")
                                        val a = allResults.artists[0]
                                        Row(Modifier.fillMaxWidth().clickable { selectedArtist = a }
                                            .padding(horizontal = 16.dp, vertical = 10.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                            Box(Modifier.size(70.dp).clip(CircleShape)
                                                .border(2.dp, VinColors.Accent, CircleShape)) {
                                                if (a.thumbnail.isNotEmpty())
                                                    AsyncImage(model = a.thumbnail, contentDescription = null,
                                                        modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                                                else Box(Modifier.fillMaxSize().background(VinColors.White10), Alignment.Center) {
                                                    Icon(Icons.Default.Person, null, tint = VinColors.Secondary, modifier = Modifier.size(36.dp))
                                                }
                                            }
                                            Column(Modifier.weight(1f)) {
                                                Text(a.name, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, color = VinColors.Primary)
                                                if (a.subscriberCount.isNotEmpty())
                                                    Text(searchMonthlyListenersText(a.subscriberCount), fontSize = 13.sp, color = VinColors.Secondary)
                                                Text("Tap to view profile →", fontSize = 12.sp, color = VinColors.AccentLight)
                                            }
                                        }
                                        Spacer(Modifier.height(8.dp))
                                    }
                                }
                                // 2. Albums by this artist (horizontal scroll)
                                val displayAlbums = artistAlbums.ifEmpty { allResults.albums }
                                if (displayAlbums.isNotEmpty()) {
                                    item {
                                        SearchSectionHeader("Albums")
                                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp),
                                            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                            items(displayAlbums.take(10), key = { it.playlistId }) { album -> AlbumCard(album) { onAlbumClick(album) } }
                                        }
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }
                                // 3. All songs (no limit)
                                if (allResults.songs.isNotEmpty()) {
                                    item { SearchSectionHeader("Songs") }
                                    items(allResults.songs, key = { it.videoId }) { song ->
                                        SongListItem(song = song, isPlaying = vm.currentSong?.videoId == song.videoId,
                                            onClick = { onSongClick(song, allResults.songs) },
                                            onMore = { onSongMore(song) })
                                    }
                                }
                                // 4. Videos (user uploads, unreleased, leaked content)
                                if (allResults.videos.isNotEmpty()) {
                                    item { SearchSectionHeader("Videos") }
                                    items(allResults.videos.take(5), key = { it.videoId }) { video ->
                                        SongListItem(song = video, isPlaying = vm.currentSong?.videoId == video.videoId,
                                            onClick = { onSongClick(video, allResults.videos) },
                                            onMore = { onSongMore(video) })
                                    }
                                    if (allResults.videos.size > 5) {
                                        item {
                                            TextButton(
                                                onClick = { activeTab = SearchTab.VIDEOS },
                                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                                            ) {
                                                Text("See all ${allResults.videos.size} videos →", color = VinColors.Accent, fontSize = 13.sp)
                                            }
                                        }
                                    }
                                }
                            }

                            // ── SONGS tab: all songs + load more ─────────────────────
                            SearchTab.SONGS -> {
                                if (allSongs.isEmpty()) {
                                    item { SearchEmptyState("No songs found") }
                                } else {
                                    items(allSongs, key = { it.videoId }) { song ->
                                        SongListItem(song = song, isPlaying = vm.currentSong?.videoId == song.videoId,
                                            onClick = { onSongClick(song, allSongs) },
                                            onMore = { onSongMore(song) })
                                    }
                                    item {
                                        Spacer(Modifier.height(8.dp))
                                        Box(Modifier.fillMaxWidth(), Alignment.Center) {
                                            if (isLoadingMore) {
                                                CircularProgressIndicator(color = VinColors.Accent, modifier = Modifier.size(28.dp), strokeWidth = 2.dp)
                                            } else {
                                                OutlinedButton(
                                                    onClick = { loadMoreSongs(query) },
                                                    border = BorderStroke(1.dp, VinColors.Accent),
                                                    shape = RoundedCornerShape(20.dp)
                                                ) {
                                                    Icon(Icons.Default.Add, null, tint = VinColors.Accent, modifier = Modifier.size(18.dp))
                                                    Spacer(Modifier.width(6.dp))
                                                    Text("Load more songs", color = VinColors.Accent)
                                                }
                                            }
                                        }
                                        Spacer(Modifier.height(16.dp))
                                    }
                                }
                            }

                            // ── ARTISTS tab ───────────────────────────────────────────
                            SearchTab.ARTISTS -> {
                                if (allResults.artists.isEmpty()) {
                                    item { SearchEmptyState("No artists found") }
                                } else {
                                    items(allResults.artists, key = { it.channelId }) { artist ->
                                        ArtistListItem(artist) { selectedArtist = artist }
                                    }
                                }
                            }

                            // ── VIDEOS tab: user uploads, unreleased, leaked ────────────
                            SearchTab.VIDEOS -> {
                                if (allResults.videos.isEmpty()) {
                                    item { SearchEmptyState("No videos found") }
                                } else {
                                    item {
                                        Text("${allResults.videos.size} videos found",
                                            fontSize = 13.sp, color = VinColors.Secondary,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                    }
                                    items(allResults.videos, key = { it.videoId }) { video ->
                                        SongListItem(song = video, isPlaying = vm.currentSong?.videoId == video.videoId,
                                            onClick = { onSongClick(video, allResults.videos) },
                                            onMore = { onSongMore(video) })
                                    }
                                }
                            }

                            // ── ALBUMS tab: artist discography ────────────────────────
                            SearchTab.ALBUMS -> {
                                val albums = artistAlbums.ifEmpty { allResults.albums }
                                if (albums.isEmpty()) {
                                    item { SearchEmptyState("No albums found") }
                                } else {
                                    item {
                                        Text("${albums.size} albums found",
                                            fontSize = 13.sp, color = VinColors.Secondary,
                                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                                    }
                                    items(albums, key = { it.playlistId }) { album -> AlbumListItem(album) { onAlbumClick(album) } }
                                }
                            }
                        }
                    }
                }
            }

            // ── Suggestions / Autocomplete overlay ───────────────────────────
            if (isFocused && (query.isNotEmpty() || searchHistory.isNotEmpty())) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(VinColors.BgColor)
                        .clickable(enabled = false) {}
                ) {
                    val filteredHistory = if (query.isEmpty()) {
                        searchHistory
                    } else {
                        searchHistory.filter { it.contains(query, ignoreCase = true) }
                    }

                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 220.dp)
                    ) {
                        items(filteredHistory) { histQuery ->
                            SuggestionRow(
                                text = histQuery,
                                isHistory = true,
                                onSelect = {
                                    query = histQuery
                                    doSearch(histQuery)
                                },
                                onInsert = {
                                    query = histQuery
                                },
                                onDeleteHistory = {
                                    deleteSearchHistoryItem(context, histQuery) { searchHistory = it }
                                }
                            )
                        }

                        if (query.isNotEmpty()) {
                            val filteredSuggestions = suggestions.filter { s ->
                                !filteredHistory.any { it.equals(s, ignoreCase = true) }
                            }

                            items(filteredSuggestions) { suggQuery ->
                                SuggestionRow(
                                    text = suggQuery,
                                    isHistory = false,
                                    onSelect = {
                                        query = suggQuery
                                        doSearch(suggQuery)
                                    },
                                    onInsert = {
                                        query = suggQuery
                                    },
                                    onDeleteHistory = null
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
private fun ArtistCard(artist: ArtistItem, onClick: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.width(80.dp).clickable { onClick() }) {
        Box(Modifier.size(72.dp).clip(CircleShape).border(2.dp, VinColors.GlassBorder, CircleShape)) {
            if (artist.thumbnail.isNotEmpty())
                AsyncImage(model = artist.thumbnail, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(VinColors.White10), Alignment.Center) {
                Icon(Icons.Default.Person, null, tint = VinColors.Secondary, modifier = Modifier.size(36.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(artist.name, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis,
            color = VinColors.Primary, fontWeight = FontWeight.Medium,
            modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        if (artist.subscriberCount.isNotEmpty())
            Text(searchMonthlyListenersText(artist.subscriberCount), fontSize = 10.sp, color = VinColors.Secondary, maxLines = 1)
    }
}

@Composable
private fun ArtistListItem(artist: ArtistItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }
        .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(52.dp).clip(CircleShape)) {
            if (artist.thumbnail.isNotEmpty())
                AsyncImage(model = artist.thumbnail, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(VinColors.White10), Alignment.Center) {
                Icon(Icons.Default.Person, null, tint = VinColors.Secondary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(artist.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                color = VinColors.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (artist.subscriberCount.isNotEmpty())
                Text(searchMonthlyListenersText(artist.subscriberCount), fontSize = 12.sp, color = VinColors.Secondary)
        }
        Icon(Icons.Default.ChevronRight, null, tint = VinColors.Secondary)
    }
}

private fun searchMonthlyListenersText(source: String): String {
    val compact = source
        .replace(Regex("""@\S+"""), "")
        .replace("subscribers", "", ignoreCase = true)
        .replace("subscriber", "", ignoreCase = true)
        .replace(Regex("""\bartist\b""", RegexOption.IGNORE_CASE), "")
        .replace(Regex("""[•|·]+"""), " ")
        .replace(Regex("""\s+"""), " ")
        .trim()
    return if (compact.isBlank()) "" else "$compact Subscribers"
}

@Composable
private fun AlbumCard(album: AlbumItem, onClick: () -> Unit) {
    Column(Modifier.width(120.dp).clickable { onClick() }) {
        Box(Modifier.size(120.dp).clip(RoundedCornerShape(10.dp))) {
            if (album.thumbnail.isNotEmpty())
                AsyncImage(model = album.thumbnail, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(VinColors.White10), Alignment.Center) {
                Icon(Icons.Default.Album, null, tint = VinColors.Secondary, modifier = Modifier.size(40.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(album.title, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            color = VinColors.Primary, fontWeight = FontWeight.Medium)
        Text(album.author, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = VinColors.Secondary)
    }
}

@Composable
private fun AlbumListItem(album: AlbumItem, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onClick() }.padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))) {
            if (album.thumbnail.isNotEmpty())
                AsyncImage(model = album.thumbnail, contentDescription = null,
                    modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            else Box(Modifier.fillMaxSize().background(VinColors.White10), Alignment.Center) {
                Icon(Icons.Default.Album, null, tint = VinColors.Secondary)
            }
        }
        Column(Modifier.weight(1f)) {
            Text(album.title, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                color = VinColors.Primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(album.author, fontSize = 12.sp, color = VinColors.Secondary)
            album.metadataSummary().takeIf { it.isNotEmpty() }?.let { metadata ->
                Text(metadata, fontSize = 11.sp, color = VinColors.Secondary)
            }
        }
        Icon(Icons.Default.PlayCircle, null, tint = VinColors.Accent, modifier = Modifier.size(28.dp))
    }
}

@Composable
private fun SearchSectionHeader(title: String) {
    Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = VinColors.Primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
}

@Composable
private fun SearchEmptyState(msg: String = "Search for your favourite music") {
    Box(Modifier.fillMaxWidth().height(300.dp), Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Default.Search, null, tint = VinColors.White20, modifier = Modifier.size(64.dp))
            Spacer(Modifier.height(16.dp))
            Text(msg, color = VinColors.Secondary, fontSize = 16.sp)
        }
    }
}

@Composable
private fun SuggestionRow(
    text: String,
    isHistory: Boolean,
    onSelect: () -> Unit,
    onInsert: () -> Unit,
    onDeleteHistory: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onSelect() }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (isHistory) Icons.Default.History else Icons.Default.Search,
            contentDescription = null,
            tint = VinColors.Secondary,
            modifier = Modifier.size(20.dp)
        )
        
        Spacer(modifier = Modifier.width(16.dp))
        
        Text(
            text = text,
            color = VinColors.Primary,
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isHistory && onDeleteHistory != null) {
                IconButton(
                    onClick = onDeleteHistory,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Clear,
                        contentDescription = "Delete from history",
                        tint = VinColors.Secondary.copy(alpha = 0.6f),
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(4.dp))
            }
            
            IconButton(
                onClick = onInsert,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.ArrowUpward,
                    contentDescription = "Insert query",
                    tint = VinColors.Secondary,
                    modifier = Modifier
                        .size(20.dp)
                        .rotate(-45f)
                )
            }
        }
    }
}
