@file:kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.sonify.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest

@Composable
fun ArtworkImage(url: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val baseHdUrl = remember(url) { upgradeArtworkUrlGlobal(url) }
    var fallbackStage by remember(baseHdUrl) { mutableIntStateOf(0) }

    val effectiveUrl = remember(baseHdUrl, fallbackStage) {
        when {
            baseHdUrl.isBlank() -> ""
            fallbackStage == 1 && baseHdUrl.contains("maxresdefault.jpg") -> baseHdUrl.replace("maxresdefault.jpg", "sddefault.jpg")
            fallbackStage >= 2 && baseHdUrl.contains("i.ytimg.com") -> baseHdUrl.replace("maxresdefault.jpg", "hqdefault.jpg").replace("sddefault.jpg", "hqdefault.jpg")
            else -> baseHdUrl
        }
    }
    val needsCropZoom = effectiveUrl.contains("hqdefault.jpg") || effectiveUrl.contains("sddefault.jpg")

    Box(modifier = modifier.clip(RoundedCornerShape(6.dp)).background(SpotifyCard)) {
        if (effectiveUrl.isNotBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(context).data(effectiveUrl).crossfade(true).build(),
                contentDescription = contentDescription,
                contentScale = ContentScale.Crop,
                onError = { if (fallbackStage < 2 && baseHdUrl.contains("i.ytimg.com")) fallbackStage++ },
                modifier = Modifier.fillMaxSize().graphicsLayer {
                    if (needsCropZoom) { scaleX = 1.36f; scaleY = 1.36f }
                }
            )
        } else {
            Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = SpotifyMuted, modifier = Modifier.align(Alignment.Center))
        }
    }
}

@Composable
fun PlaylistCoverBox(pl: Playlist, modifier: Modifier = Modifier) {
    val bgHex = pl.customCoverBgHex
    val cover = pl.coverUrl.ifBlank { pl.tracks.firstOrNull()?.artworkUrl ?: "" }
    if (bgHex != null && (pl.customCoverText.isNotBlank() || pl.customCoverSticker.isNotBlank() || cover.isBlank())) {
        Box(
            modifier = modifier.clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(Color(bgHex), Color(bgHex).copy(alpha = 0.6f), SpotifyDarkSurface)))
                .padding(8.dp),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (pl.customCoverSticker.isNotBlank()) Text(pl.customCoverSticker, fontSize = 22.sp)
                Text(pl.customCoverText.ifBlank { pl.name }, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
            }
        }
    } else if (cover.isNotBlank()) {
        ArtworkImage(cover, pl.name, modifier)
    } else {
        Box(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(SpotifyCard), contentAlignment = Alignment.Center) {
            Icon(if (pl.isBlend) Icons.Rounded.GroupAdd else Icons.Rounded.LibraryMusic, null, tint = SpotifyGreen, modifier = Modifier.size(32.dp))
        }
    }
}

@Composable
fun HomeScreen(state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isEn = str.isEn
    val unitLabel = when (state.homeFilter) {
        HomeFilter.AUDIOBOOKS -> str.books
        HomeFilter.PODCASTS -> str.episodes
        else -> str.songs
    }
    val mixesHeading = when (state.homeFilter) {
        HomeFilter.AUDIOBOOKS -> str.t("אוספי ספרי שמע מומלצים", "Featured Audiobook Collections")
        HomeFilter.PODCASTS -> str.t("תוכניות ופודקאסטים מומלצים", "Featured Podcast Shows")
        else -> str.madeForYou
    }
    val trendingHeading = when (state.homeFilter) {
        HomeFilter.AUDIOBOOKS -> str.topAudiobooks
        HomeFilter.PODCASTS -> str.topPodcasts
        else -> str.recommendedSongs
    }

    // Pinned opaque top bar (fixes status bar overlap when scrolling!)
    Column(modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.setShowSettings(true) }, modifier = Modifier.size(36.dp).clip(CircleShape).background(SpotifyCard)) {
                    Icon(Icons.Rounded.Settings, contentDescription = str.settings, tint = SpotifyWhite, modifier = Modifier.size(20.dp))
                }
                if (state.settings.privateSession) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1E3264)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(str.privateSession, color = SpotifyWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                if (state.settings.offlineMode) {
                    Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF006450)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                        Text(str.offlineMode, color = SpotifyWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.setShowHistorySheet(true) }) { Icon(Icons.Rounded.History, str.listeningHistory, tint = SpotifyWhite, modifier = Modifier.size(22.dp)) }
                IconButton(onClick = { vm.setShowStatsSheet(true) }) { Icon(Icons.Rounded.BarChart, str.statsWrapped, tint = SpotifyGreen, modifier = Modifier.size(22.dp)) }
                IconButton(onClick = { vm.setShowMessagesSheet(true) }) { Icon(Icons.Rounded.Chat, str.messagesAndFriends, tint = SpotifyWhite, modifier = Modifier.size(21.dp)) }
                if (state.isLoadingHome) {
                    CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    IconButton(onClick = { vm.refreshHomeContent() }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = SpotifySubtle, modifier = Modifier.size(20.dp)) }
                }
            }
        }

        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(HomeFilter.entries) { f ->
                val selected = state.homeFilter == f
                Box(
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) SpotifyGreen else SpotifyCard)
                        .clickable { vm.setHomeFilter(f) }.padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(f.label(isEn), color = if (selected) Color.Black else SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 165.dp)) {
            // 2x4 Recent grid shown for Music / All (hidden on Audiobooks/Podcasts so music recents don't clash!)
            val gridItems = when (state.homeFilter) {
                HomeFilter.AUDIOBOOKS -> state.recentTracks.filter { it.isAudiobook }.take(8)
                HomeFilter.PODCASTS -> state.recentTracks.filter { it.isPodcast }.take(8)
                else -> state.recentTracks.filterNot { it.isAudiobook }.take(8)
            }
            if (gridItems.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        gridItems.chunked(2).forEach { pair ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { track ->
                                    Row(
                                        modifier = Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(6.dp))
                                            .background(SpotifyCard).clickable { vm.playTrack(track, gridItems) },
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        ArtworkImage(track.artworkUrl, null, Modifier.size(56.dp))
                                        Text(track.title, color = SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 8.dp))
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                    }
                }
            }

            // Daylist / Highlight Banner
            state.daylistPlaylist?.let { dayPl ->
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable { vm.openPlaylist(dayPl) },
                        shape = RoundedCornerShape(12.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().background(Brush.horizontalGradient(listOf(Color(0xFF1E3A5F), Color(0xFF1DB954).copy(alpha = 0.35f)))).padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(dayPl.coverUrl, null, Modifier.size(56.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(dayPl.name, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${dayPl.tracks.size} $unitLabel • ${str.t("לחץ לפתיחה", "Tap to open")}", color = SpotifySubtle, fontSize = 12.sp)
                            }
                            IconButton(onClick = { if (dayPl.tracks.isNotEmpty()) vm.playTrack(dayPl.tracks.first(), dayPl.tracks) }) {
                                Icon(Icons.Rounded.PlayArrow, str.playAll, tint = SpotifyGreen, modifier = Modifier.size(30.dp))
                            }
                        }
                    }
                }
            }

            // Curated Collections Carousel
            if (state.homeMixPlaylists.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 12.dp)) {
                        Text(mixesHeading, color = SpotifyWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.homeMixPlaylists, key = { it.id }) { pl ->
                                Column(modifier = Modifier.width(150.dp).clickable { vm.openPlaylist(pl) }) {
                                    Box {
                                        PlaylistCoverBox(pl, Modifier.size(150.dp))
                                        Box(
                                            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp).clip(CircleShape)
                                                .background(SpotifyGreen).clickable { if (pl.tracks.isNotEmpty()) vm.playTrack(pl.tracks.first(), pl.tracks) },
                                            contentAlignment = Alignment.Center
                                        ) { Icon(Icons.Rounded.PlayArrow, str.playAll, tint = Color.Black, modifier = Modifier.size(20.dp)) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(pl.name, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${pl.tracks.size} $unitLabel • ${pl.creator}", color = SpotifySubtle, fontSize = 11.sp, maxLines = 1)
                                }
                            }
                        }
                    }
                }
            }

            // Individual Recommended Items
            if (state.trendingSongs.isNotEmpty()) {
                item {
                    Column(modifier = Modifier.padding(top = 18.dp)) {
                        Text(trendingHeading, color = SpotifyWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(state.trendingSongs, key = { it.id }) { track ->
                                Column(modifier = Modifier.width(140.dp).clickable { vm.playTrack(track, state.trendingSongs) }) {
                                    Box {
                                        ArtworkImage(track.artworkUrl, track.title, Modifier.size(140.dp))
                                        IconButton(
                                            onClick = { vm.openTrackOptions(track) },
                                            modifier = Modifier.align(Alignment.TopEnd).padding(4.dp).size(28.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.6f))
                                        ) { Icon(Icons.Rounded.MoreVert, "More", tint = SpotifyWhite, modifier = Modifier.size(16.dp)) }
                                    }
                                    Spacer(Modifier.height(6.dp))
                                    Text(track.title, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(track.artist, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SearchScreen(state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isEn = str.isEn
    val groupedCategories = remember(isEn) { vm.browseCategories.groupBy { it.displayGroup(isEn) } }
    val matchingHistory = remember(state.searchQuery, state.searchHistory) {
        if (state.searchQuery.isBlank()) state.searchHistory
        else state.searchHistory.filter { it.contains(state.searchQuery, true) && !it.equals(state.searchQuery, true) }
    }

    Column(modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding().padding(horizontal = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically
        ) {
            Text(str.search, color = SpotifyWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.setShowCodeFor(str.t("פתיחת קוד / קישור Sonify", "Open Sonify Code / Link") to "") }, modifier = Modifier.size(32.dp)) {
                    Icon(Icons.Rounded.QrCodeScanner, "Scan/Open Code", tint = SpotifyWhite, modifier = Modifier.size(20.dp))
                }
                Text(
                    text = state.settings.streamSource.label(isEn).substringBefore(" ("),
                    color = SpotifyGreen, fontSize = 12.sp,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(SpotifyCard)
                        .clickable { vm.setShowSettings(true) }.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(8.dp)).background(SpotifyWhite).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, tint = Color.Black, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (state.searchQuery.isEmpty()) {
                        Text(str.searchPlaceholder, color = SpotifyMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    BasicTextField(
                        value = state.searchQuery, onValueChange = { vm.onSearchQueryChange(it) }, singleLine = true,
                        textStyle = TextStyle(color = Color.Black, fontSize = 14.sp), cursorBrush = SolidColor(SpotifyGreen),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { vm.performSearch() }),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                if (state.searchQuery.isNotBlank()) {
                    IconButton(onClick = { vm.onSearchQueryChange("") }, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Rounded.Close, "Clear", tint = Color.Black, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Box(
                modifier = Modifier.height(48.dp).clip(RoundedCornerShape(8.dp)).background(SpotifyGreen)
                    .clickable { vm.performSearch() }.padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center
            ) { Text(str.go, color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
        }

        LazyRow(modifier = Modifier.padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(SearchMode.entries) { m ->
                val sel = state.searchMode == m
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { vm.setSearchMode(m) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                    Text(m.label(isEn), color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }

        if (matchingHistory.isNotEmpty() && !state.hasSearched) {
            LazyRow(modifier = Modifier.padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(matchingHistory) { hist ->
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(SpotifyDarkSurface)
                            .clickable { vm.performSearch(hist) }.padding(start = 12.dp, end = 6.dp, top = 5.dp, bottom = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.History, null, tint = SpotifySubtle, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(hist, color = SpotifyWhite, fontSize = 12.sp, maxLines = 1)
                        IconButton(onClick = { vm.removeSearchHistoryItem(hist) }, modifier = Modifier.size(20.dp)) {
                            Icon(Icons.Rounded.Close, "Remove", tint = SpotifyMuted, modifier = Modifier.size(13.dp))
                        }
                    }
                }
            }
        }

        if (state.isSearching) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SpotifyGreen) }
        } else if (state.hasSearched && state.searchQuery.isNotBlank()) {
            if (state.searchMode == SearchMode.PLAYLISTS) {
                if (state.searchPlaylists.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(str.t("לא נמצאו פלייליסטים", "No playlists found"), color = SpotifySubtle) }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 165.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.searchPlaylists, key = { it.id }) { rPl ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyDarkSurface)
                                    .clickable { vm.openRemotePlaylist(rPl) }.padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkImage(rPl.artworkUrl, rPl.title, Modifier.size(56.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(rPl.title, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${str.t("פלייליסט", "Playlist")} • ${rPl.subtitle}", color = SpotifySubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Rounded.LibraryMusic, null, tint = SpotifyGreen)
                            }
                        }
                    }
                }
            } else {
                if (state.searchResults.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(str.t("לא נמצאו תוצאות", "No results found"), color = SpotifySubtle) }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 165.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.searchResults, key = { it.id }) { track ->
                            SwipeableTrackRow(track = track, state = state, vm = vm, onClick = { vm.playTrack(track, state.searchResults) })
                        }
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 165.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                groupedCategories.forEach { (groupName, cats) ->
                    item(key = "header_$groupName") { Text(groupName, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 4.dp)) }
                    items(cats.chunked(2)) { pair ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            pair.forEach { cat ->
                                Box(
                                    modifier = Modifier.weight(1f).height(84.dp).clip(RoundedCornerShape(8.dp))
                                        .background(Color(cat.colorHex))
                                        .clickable { vm.performSearch(cat.query, modeOverride = cat.searchMode) }
                                        .padding(12.dp)
                                ) { Text(cat.displayTitle(isEn), color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isEn = str.isEn
    var showSortMenu by remember { mutableStateOf(false) }
    var showSearchBar by remember { mutableStateOf(false) }
    val libQuery = state.librarySearchQuery.trim()

    val allSavedTracks = remember(state.likedTracks, state.recentTracks, state.playlists) {
        (state.likedTracks + state.recentTracks + state.playlists.flatMap { it.tracks }).distinctBy { it.id }
    }
    val displayedTracks = remember(allSavedTracks, state.likedTracks, state.offlineTrackIds, state.libraryFilter, state.librarySort, libQuery) {
        val base = when (state.libraryFilter) {
            LibraryFilter.OFFLINE -> allSavedTracks.filter { it.id in state.offlineTrackIds || it.source == StreamSource.LOCAL }
            LibraryFilter.PODCASTS -> allSavedTracks.filter { it.isPodcast }
            LibraryFilter.PLAYLISTS, LibraryFilter.FOLDERS -> emptyList()
            else -> state.likedTracks
        }.filter { libQuery.isBlank() || it.title.contains(libQuery, true) || it.artist.contains(libQuery, true) }
        when (state.librarySort) {
            LibrarySort.ALPHA -> base.sortedBy { it.title }
            LibrarySort.CREATOR -> base.sortedBy { it.artist }
            LibrarySort.DATE_ADDED -> base.sortedByDescending { it.addedAt }
            else -> base
        }
    }

    val displayedPlaylists = remember(state.playlists, state.libraryFilter, state.librarySort, state.selectedFolderId, libQuery) {
        val base = when {
            state.selectedFolderId != null -> state.playlists.filter { it.folderId == state.selectedFolderId }
            state.libraryFilter == LibraryFilter.ALL || state.libraryFilter == LibraryFilter.PLAYLISTS -> state.playlists
            else -> emptyList()
        }.filter { libQuery.isBlank() || it.name.contains(libQuery, true) || it.creator.contains(libQuery, true) }
        val sorted = when (state.librarySort) {
            LibrarySort.ALPHA -> base.sortedBy { it.name }
            LibrarySort.CREATOR -> base.sortedBy { it.creator }
            LibrarySort.DATE_ADDED -> base.sortedByDescending { it.createdAt }
            else -> base
        }
        sorted.sortedByDescending { it.isPinned }
    }

    val artistsGrouped = remember(allSavedTracks, state.followedArtists) {
        val fromTracks = allSavedTracks.groupBy { it.artist }
        val allNames = (state.followedArtists + fromTracks.keys).distinct()
        allNames.map { name -> name to (fromTracks[name] ?: emptyList()) }.sortedByDescending { (it.first in state.followedArtists).compareTo(false) * 100 + it.second.size }
    }
    val albumsGrouped = remember(allSavedTracks) { allSavedTracks.groupBy { it.album.ifBlank { it.artist } }.toList().sortedByDescending { it.second.size } }

    Column(modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(str.yourLibrary, color = SpotifyWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = { showSearchBar = !showSearchBar; if (!showSearchBar) vm.onLibrarySearchChange("") }) {
                    Icon(Icons.Rounded.Search, str.search, tint = if (showSearchBar) SpotifyGreen else SpotifyWhite)
                }
                IconButton(onClick = { vm.setShowImportSheet(true) }) { Icon(Icons.Rounded.FileDownload, str.importPlaylist, tint = SpotifyWhite) }
                IconButton(onClick = { vm.setShowCreateSheet(true) }) { Icon(Icons.Rounded.Add, str.create, tint = SpotifyWhite) }
                IconButton(onClick = { vm.setShowSettings(true) }) { Icon(Icons.Rounded.Settings, str.settings, tint = SpotifyWhite) }
            }
        }

        if (showSearchBar) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(42.dp)
                    .clip(RoundedCornerShape(8.dp)).background(SpotifyCard).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(Icons.Rounded.Search, null, tint = SpotifySubtle, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (state.librarySearchQuery.isEmpty()) Text(str.searchInLibrary, color = SpotifyMuted, fontSize = 13.sp)
                    BasicTextField(
                        value = state.librarySearchQuery, onValueChange = { vm.onLibrarySearchChange(it) }, singleLine = true,
                        textStyle = TextStyle(color = SpotifyWhite, fontSize = 14.sp), cursorBrush = SolidColor(SpotifyGreen), modifier = Modifier.fillMaxWidth()
                    )
                }
                if (state.librarySearchQuery.isNotEmpty()) {
                    IconButton(onClick = { vm.onLibrarySearchChange("") }, modifier = Modifier.size(24.dp)) { Icon(Icons.Rounded.Close, null, tint = SpotifySubtle, modifier = Modifier.size(16.dp)) }
                }
            }
        }

        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryFilter.entries) { filter ->
                val selected = state.libraryFilter == filter
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) SpotifyGreen else SpotifyCard).clickable { vm.setLibraryFilter(filter) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(filter.label(isEn), color = if (selected) Color.Black else SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Box {
                Row(modifier = Modifier.clickable { showSortMenu = true }, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.Sort, null, tint = SpotifyWhite, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(state.librarySort.label(isEn), color = SpotifyWhite, fontSize = 13.sp)
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    LibrarySort.entries.forEach { s -> DropdownMenuItem(text = { Text(s.label(isEn)) }, onClick = { vm.setLibrarySort(s); showSortMenu = false }) }
                }
            }
            IconButton(onClick = { vm.toggleLibraryGrid() }) { Icon(if (state.libraryGridView) Icons.Rounded.ViewList else Icons.Rounded.GridView, "View", tint = SpotifyWhite) }
        }

        if (state.libraryFilter == LibraryFilter.FOLDERS && state.selectedFolderId == null) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 165.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.folders.isEmpty()) item { Text(str.t("אין עדיין תיקיות — צור תיקייה דרך +", "No folders yet — tap + to create one"), color = SpotifySubtle, modifier = Modifier.padding(16.dp)) }
                items(state.folders, key = { it.id }) { folder ->
                    val count = state.playlists.count { it.folderId == folder.id }
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyDarkSurface).clickable { vm.selectFolder(folder.id) }.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Folder, null, tint = SpotifyGreen, modifier = Modifier.size(36.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(folder.name, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("$count ${str.t("פלייליסטים", "playlists")}", color = SpotifySubtle, fontSize = 12.sp)
                        }
                    }
                }
            }
            return
        }

        if (state.selectedFolderId != null) {
            val fName = state.folders.firstOrNull { it.id == state.selectedFolderId }?.name ?: str.t("תיקייה", "Folder")
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { vm.selectFolder(null) }, verticalAlignment = Alignment.CenterVertically) {
                Icon(if (isEn) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward, null, tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("$fName (${str.back})", color = SpotifyGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (state.libraryFilter == LibraryFilter.ARTISTS) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 165.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(artistsGrouped) { (artist, tracks) ->
                    val isFollowed = artist in state.followedArtists
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyDarkSurface)
                            .clickable { vm.openArtistPage(artist, tracks.firstOrNull()?.artworkUrl ?: "") }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(tracks.firstOrNull()?.artworkUrl ?: "", artist, Modifier.size(50.dp).clip(CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(artist, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("${if (isFollowed) "${str.following} • " else ""}${tracks.size} ${str.songs}", color = SpotifySubtle, fontSize = 12.sp)
                        }
                        Icon(if (isEn) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft, null, tint = SpotifyMuted)
                    }
                }
            }
            return
        }

        if (state.libraryFilter == LibraryFilter.ALBUMS) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 165.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(albumsGrouped) { (album, tracks) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyDarkSurface)
                            .clickable { vm.openPlaylist(Playlist("album_$album", album, str.t("אלבום", "Album"), coverUrl = tracks.first().artworkUrl, tracks = tracks, isRemote = true)) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(tracks.first().artworkUrl, album, Modifier.size(50.dp))
                        Spacer(Modifier.width(12.dp))
                        Column { Text(album, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text("${tracks.size} ${str.songs}", color = SpotifySubtle, fontSize = 12.sp) }
                    }
                }
            }
            return
        }

        if (state.libraryGridView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 165.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (state.libraryFilter == LibraryFilter.ALL && libQuery.isBlank()) {
                    item { SmartLibraryGridCard(str.likedSongs, "${state.likedTracks.size} ${str.songs}", Icons.Rounded.Favorite, 0xFF503750) { vm.openLikedSongsPlaylist() } }
                    item { SmartLibraryGridCard(str.offlineDownloads, "${state.offlineTrackIds.size} ${str.songs}", Icons.Rounded.DownloadDone, 0xFF006450) { vm.openOfflineDownloadsPlaylist() } }
                }
                items(displayedPlaylists, key = { it.id }) { pl ->
                    Column(modifier = Modifier.clip(RoundedCornerShape(8.dp)).background(SpotifyDarkSurface).clickable { vm.openPlaylist(pl) }.padding(10.dp)) {
                        PlaylistCoverBox(pl, Modifier.fillMaxWidth().height(110.dp))
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (pl.isPinned) Icon(Icons.Rounded.PushPin, null, tint = SpotifyGreen, modifier = Modifier.size(13.dp))
                            Text(pl.name, color = SpotifyWhite, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${pl.tracks.size} • ${pl.creator}", color = SpotifySubtle, fontSize = 11.sp)
                    }
                }
                items(displayedTracks, key = { it.id }) { t ->
                    Column(modifier = Modifier.clickable { vm.playTrack(t, displayedTracks) }) {
                        ArtworkImage(t.artworkUrl, t.title, Modifier.fillMaxWidth().height(130.dp))
                        Spacer(Modifier.height(4.dp))
                        Text(t.title, color = SpotifyWhite, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, color = SpotifySubtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 165.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // Smart Pinned Library Cards (Spotify style - library never looks empty & broken!)
                if (state.libraryFilter == LibraryFilter.ALL && libQuery.isBlank()) {
                    item { SmartLibraryRowCard(str.likedSongs, "${state.likedTracks.size} ${str.songs}", Icons.Rounded.Favorite, 0xFF503750) { vm.openLikedSongsPlaylist() } }
                    item { SmartLibraryRowCard(str.offlineDownloads, "${state.offlineTrackIds.size} ${str.songs} • ${"%.1f".format(vm.repo.getOfflineSizeMb())} MB", Icons.Rounded.DownloadForOffline, 0xFF006450) { vm.openOfflineDownloadsPlaylist() } }
                    item { SmartLibraryRowCard(str.localFiles, "${state.localAudioTracks.size} ${str.t("קובצי מכשיר", "device files")}", Icons.Rounded.Folder, 0xFF1E3264) { vm.openLocalFilesPlaylist() } }
                }

                items(displayedPlaylists, key = { it.id }) { pl ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyDarkSurface).clickable { vm.openPlaylist(pl) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        PlaylistCoverBox(pl, Modifier.size(54.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                if (pl.isPinned) Icon(Icons.Rounded.PushPin, null, tint = SpotifyGreen, modifier = Modifier.size(14.dp))
                                Text(pl.name, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Text("${if (pl.isPublic) str.t("ציבורי", "Public") else str.t("פרטי", "Private")}${if (pl.isCollaborative) " • ${str.t("שיתופי", "Collab")}" else ""} • ${pl.tracks.size} ${str.songs}", color = SpotifySubtle, fontSize = 12.sp, maxLines = 1)
                        }
                        IconButton(onClick = { vm.togglePinPlaylist(pl.id) }) {
                            Icon(Icons.Rounded.PushPin, "Pin", tint = if (pl.isPinned) SpotifyGreen else SpotifyMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }

                if (state.libraryFilter != LibraryFilter.ALL || libQuery.isNotBlank()) {
                    items(displayedTracks, key = { it.id }) { track ->
                        SwipeableTrackRow(track = track, state = state, vm = vm, onClick = { vm.playTrack(track, displayedTracks) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SmartLibraryRowCard(title: String, subtitle: String, icon: ImageVector, colorHex: Long, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SpotifyDarkSurface).clickable(onClick = onClick).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(Color(colorHex), Color(colorHex).copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = SpotifyWhite, modifier = Modifier.size(26.dp)) }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                Icon(Icons.Rounded.PushPin, null, tint = SpotifyGreen, modifier = Modifier.size(13.dp))
                Text(title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Text(subtitle, color = SpotifySubtle, fontSize = 12.sp)
        }
    }
}

@Composable
private fun SmartLibraryGridCard(title: String, subtitle: String, icon: ImageVector, colorHex: Long, onClick: () -> Unit) {
    Column(modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(SpotifyDarkSurface).clickable(onClick = onClick).padding(10.dp)) {
        Box(
            modifier = Modifier.fillMaxWidth().height(100.dp).clip(RoundedCornerShape(8.dp))
                .background(Brush.linearGradient(listOf(Color(colorHex), Color(colorHex).copy(alpha = 0.55f)))),
            contentAlignment = Alignment.Center
        ) { Icon(icon, null, tint = SpotifyWhite, modifier = Modifier.size(38.dp)) }
        Spacer(Modifier.height(6.dp))
        Text(title, color = SpotifyWhite, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, color = SpotifySubtle, fontSize = 11.sp)
    }
}

@Composable
fun ArtistPageScreen(artist: ArtistPageState, state: UiState, vm: AppViewModel) {
    val str = state.strings
    val context = LocalContext.current
    val isFollowed = artist.artistName in state.followedArtists

    Column(modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.closeArtistPage() }) { Icon(if (str.isEn) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward, str.back, tint = SpotifyWhite) }
            Text(artist.artistName, color = SpotifyWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { vm.toggleFollowArtist(artist.artistName) }, contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp)) {
                Text(if (isFollowed) str.following else str.follow, color = if (isFollowed) SpotifyGreen else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        LazyColumn(contentPadding = PaddingValues(bottom = 165.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Box(modifier = Modifier.fillMaxWidth().height(190.dp).padding(horizontal = 16.dp).clip(RoundedCornerShape(14.dp))) {
                    ArtworkImage(artist.artworkUrl, artist.artistName, Modifier.fillMaxSize())
                    Box(modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f)))))
                    Column(modifier = Modifier.align(Alignment.BottomStart).padding(16.dp)) {
                        Text(artist.artistName, color = SpotifyWhite, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                        Text("${artist.tracks.size} ${str.songs}", color = SpotifySubtle, fontSize = 13.sp)
                    }
                }
            }
            item {
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (artist.tracks.isNotEmpty()) vm.playTrack(artist.tracks.first(), artist.tracks) },
                        enabled = artist.tracks.isNotEmpty(),
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(4.dp)); Text(str.playAll, fontWeight = FontWeight.Bold) }
                    OutlinedButton(
                        onClick = { if (artist.tracks.isNotEmpty()) { val s = artist.tracks.shuffled(); vm.playTrack(s.first(), s) } },
                        enabled = artist.tracks.isNotEmpty(), modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Rounded.Shuffle, null, tint = SpotifyWhite); Spacer(Modifier.width(4.dp)); Text(str.shuffle, color = SpotifyWhite) }
                }
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("${artist.artistName} concerts tickets")}"))) } }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.Event, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(str.concerts, color = SpotifyWhite, fontSize = 12.sp)
                    }
                    OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("${artist.artistName} merch store")}"))) } }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ShoppingBag, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(str.merch, color = SpotifyWhite, fontSize = 12.sp)
                    }
                }
            }
            if (artist.isLoading) {
                item { Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SpotifyGreen) } }
            } else {
                items(artist.tracks, key = { it.id }) { trk ->
                    Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                        SwipeableTrackRow(trk, state, vm) { vm.playTrack(trk, artist.tracks) }
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistDetailScreen(playlist: Playlist, state: UiState, vm: AppViewModel) {
    val str = state.strings
    var showFolderPicker by remember { mutableStateOf(false) }
    var showRenameDialog by remember { mutableStateOf(false) }
    var editOrderMode by remember { mutableStateOf(false) }
    var filterInPlaylist by remember { mutableStateOf("") }

    val filteredTracks = remember(playlist.tracks, filterInPlaylist) {
        if (filterInPlaylist.isBlank()) playlist.tracks
        else playlist.tracks.filter { it.title.contains(filterInPlaylist, true) || it.artist.contains(filterInPlaylist, true) }
    }

    Column(modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openPlaylist(null) }) { Icon(if (str.isEn) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward, str.back, tint = SpotifyWhite) }
            PlaylistCoverBox(playlist, Modifier.size(44.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(playlist.name, color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${playlist.creator} • ${playlist.tracks.size} ${str.songs}", color = SpotifySubtle, fontSize = 12.sp)
            }
            IconButton(onClick = { vm.setShowCodeFor(playlist.name to "sonify:playlist:${playlist.id.take(10)}") }) { Icon(Icons.Rounded.QrCode2, "Code", tint = SpotifyWhite) }
            if (playlist.isRemote && !playlist.id.startsWith("virtual_")) {
                IconButton(onClick = { vm.saveRemotePlaylistToLibrary(playlist) }, enabled = playlist.tracks.isNotEmpty()) { Icon(Icons.Rounded.BookmarkAdd, str.save, tint = SpotifyGreen) }
            } else if (!playlist.isRemote) {
                IconButton(onClick = { showRenameDialog = true }) { Icon(Icons.Rounded.Edit, "Rename", tint = SpotifyWhite, modifier = Modifier.size(20.dp)) }
                IconButton(onClick = { vm.deletePlaylist(playlist.id) }) { Icon(Icons.Rounded.Delete, "Delete", tint = Color(0xFFFF6B6B)) }
            }
        }

        // In-Playlist Search Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).height(40.dp)
                .clip(RoundedCornerShape(8.dp)).background(SpotifyCard).padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.Search, null, tint = SpotifySubtle, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                if (filterInPlaylist.isEmpty()) Text(str.searchInPlaylist, color = SpotifyMuted, fontSize = 13.sp)
                BasicTextField(
                    value = filterInPlaylist, onValueChange = { filterInPlaylist = it }, singleLine = true,
                    textStyle = TextStyle(color = SpotifyWhite, fontSize = 13.sp), cursorBrush = SolidColor(SpotifyGreen), modifier = Modifier.fillMaxWidth()
                )
            }
            if (filterInPlaylist.isNotEmpty()) {
                IconButton(onClick = { filterInPlaylist = "" }, modifier = Modifier.size(22.dp)) { Icon(Icons.Rounded.Close, null, tint = SpotifySubtle, modifier = Modifier.size(14.dp)) }
            }
        }

        Spacer(Modifier.height(6.dp))

        // Action Pills (Download All, Reorder, Pin, Cover, Mix, Collab, Public/Private, Taste, Folder)
        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            item { PlaylistActionPill(str.downloadAll, Icons.Rounded.DownloadForOffline, false) { vm.downloadEntirePlaylist(playlist) } }
            if (!playlist.isRemote) {
                item { PlaylistActionPill(str.t("סדר שירים", "Reorder"), Icons.Rounded.Sort, editOrderMode) { editOrderMode = !editOrderMode } }
                item { PlaylistActionPill(if (playlist.isPinned) str.t("נעוץ", "Pinned") else str.t("נעץ", "Pin"), Icons.Rounded.PushPin, playlist.isPinned) { vm.togglePinPlaylist(playlist.id) } }
                item { PlaylistActionPill(str.t("עצב עטיפה", "Cover"), Icons.Rounded.Palette, playlist.customCoverBgHex != null) { vm.setEditingCoverPlaylist(playlist.id) } }
                item { PlaylistActionPill(if (playlist.isMixedMode) "Mix ON" else "Mix", Icons.Rounded.GraphicEq, playlist.isMixedMode) { vm.togglePlaylistFlag(playlist.id, "mix") } }
                item { PlaylistActionPill(if (playlist.isCollaborative) str.t("שיתופי", "Collab") else str.t("הפוך לשיתופי", "Make Collab"), Icons.Rounded.GroupAdd, playlist.isCollaborative) { vm.togglePlaylistFlag(playlist.id, "collab") } }
                item { PlaylistActionPill(if (playlist.isPublic) str.t("ציבורי", "Public") else str.t("פרטי", "Private"), if (playlist.isPublic) Icons.Rounded.Public else Icons.Rounded.Lock, !playlist.isPublic) { vm.togglePlaylistFlag(playlist.id, "public") } }
                item { PlaylistActionPill(if (playlist.excludeFromTaste) str.t("מוחרג מטעם", "Excluded") else str.t("החרג מטעם", "Exclude Taste"), Icons.Rounded.Block, playlist.excludeFromTaste) { vm.togglePlaylistFlag(playlist.id, "taste") } }
                if (state.folders.isNotEmpty()) {
                    item { PlaylistActionPill(str.t("תיקייה", "Folder"), Icons.Rounded.Folder, playlist.folderId != null) { showFolderPicker = true } }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (filteredTracks.isNotEmpty()) {
                        if (playlist.isMixedMode && state.settings.crossfadeSeconds == 0) vm.updateSettings(state.settings.copy(automixEnabled = true))
                        vm.playTrack(filteredTracks.first(), filteredTracks)
                    }
                },
                enabled = filteredTracks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                modifier = Modifier.weight(1f)
            ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(str.playAll, fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { if (filteredTracks.isNotEmpty()) { val s = filteredTracks.shuffled(); vm.playTrack(s.first(), s) } },
                enabled = filteredTracks.isNotEmpty(), modifier = Modifier.weight(1f)
            ) { Icon(Icons.Rounded.Shuffle, null, tint = SpotifyWhite); Spacer(Modifier.width(6.dp)); Text(str.shuffle, color = SpotifyWhite) }
        }

        if (state.isLoadingPlaylist) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SpotifyGreen) }
        } else if (filteredTracks.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(str.t("אין שירים להצגה", "No tracks here yet"), color = SpotifyWhite, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.selectTab(MainTab.SEARCH) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)) { Text(str.t("חפש שירים להוספה", "Search Tracks")) }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 165.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                itemsIndexed(filteredTracks, key = { _, t -> t.id }) { idx, track ->
                    val isHidden = track.id in state.hiddenTrackIds
                    val isUnplayableOffline = state.settings.offlineMode && track.id !in state.offlineTrackIds && track.source != StreamSource.LOCAL
                    if (!state.settings.showUnplayableSongs && (isHidden || isUnplayableOffline)) return@itemsIndexed

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (isHidden || isUnplayableOffline) 0.42f else 1f)) {
                        Box(modifier = Modifier.weight(1f)) {
                            SwipeableTrackRow(track = track, state = state, vm = vm, onClick = { if (!isHidden) vm.playTrack(track, filteredTracks) })
                        }
                        if (!playlist.isRemote && editOrderMode && filterInPlaylist.isBlank()) {
                            IconButton(onClick = { vm.movePlaylistTrack(playlist.id, idx, idx - 1) }, enabled = idx > 0, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.ArrowUpward, null, tint = SpotifySubtle, modifier = Modifier.size(16.dp))
                            }
                            IconButton(onClick = { vm.movePlaylistTrack(playlist.id, idx, idx + 1) }, enabled = idx < playlist.tracks.lastIndex, modifier = Modifier.size(30.dp)) {
                                Icon(Icons.Rounded.ArrowDownward, null, tint = SpotifySubtle, modifier = Modifier.size(16.dp))
                            }
                        } else if (!playlist.isRemote) {
                            IconButton(onClick = { vm.removeTrackFromPlaylist(playlist.id, track.id) }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Rounded.Close, "Remove", tint = SpotifyMuted, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
                // Unlimited Expansion Button ("Load More Tracks")
                if (!playlist.id.startsWith("virtual_")) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { vm.loadMoreTracksForPlaylist(playlist) },
                            enabled = !state.isExpandingPlaylist,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.isExpandingPlaylist) CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            else Icon(Icons.Rounded.AddCircle, null, tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(str.loadMore, color = SpotifyWhite)
                        }
                    }
                }
            }
        }
    }

    if (showRenameDialog) {
        var newName by remember { mutableStateOf(playlist.name) }
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = SpotifyDarkSurface,
            titleContentColor = SpotifyWhite,
            title = { Text(str.t("שינוי שם פלייליסט", "Rename Playlist")) },
            text = { OutlinedTextField(value = newName, onValueChange = { newName = it }, singleLine = true) },
            confirmButton = { Button(onClick = { vm.renamePlaylist(playlist.id, newName); showRenameDialog = false }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)) { Text(str.save) } },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text(str.cancel, color = SpotifySubtle) } }
        )
    }

    if (showFolderPicker) {
        ModalBottomSheet(onDismissRequest = { showFolderPicker = false }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(str.t("בחר תיקייה לפלייליסט", "Move to Folder"), fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(10.dp))
                Text(str.t("ללא תיקייה", "No Folder"), color = SpotifySubtle, modifier = Modifier.fillMaxWidth().clickable { vm.assignPlaylistToFolder(playlist.id, null); showFolderPicker = false }.padding(vertical = 10.dp))
                state.folders.forEach { f ->
                    Text(f.name, color = SpotifyWhite, modifier = Modifier.fillMaxWidth().clickable { vm.assignPlaylistToFolder(playlist.id, f.id); showFolderPicker = false }.padding(vertical = 10.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PlaylistActionPill(label: String, icon: ImageVector, active: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier.clip(RoundedCornerShape(50))
            .background(if (active) SpotifyGreen else SpotifyCard)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = if (active) Color.Black else SpotifyWhite, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(5.dp))
        Text(label, color = if (active) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
fun SwipeableTrackRow(track: Track, state: UiState, vm: AppViewModel, onClick: () -> Unit) {
    val str = state.strings
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when (value) {
                SwipeToDismissBoxValue.StartToEnd -> vm.addToQueue(track)
                SwipeToDismissBoxValue.EndToStart -> vm.toggleLike(track)
                SwipeToDismissBoxValue.Settled -> {}
            }
            false
        }
    )

    SwipeToDismissBox(
        state = dismissState,
        backgroundContent = {
            val isQueue = dismissState.targetValue == SwipeToDismissBoxValue.StartToEnd
            val bg = when (dismissState.targetValue) {
                SwipeToDismissBoxValue.StartToEnd -> Color(0xFF1E3264)
                SwipeToDismissBoxValue.EndToStart -> SpotifyGreen
                SwipeToDismissBoxValue.Settled -> Color.Transparent
            }
            Row(
                modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(bg).padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = if (isQueue) Arrangement.Start else Arrangement.End
            ) {
                if (dismissState.targetValue != SwipeToDismissBoxValue.Settled) {
                    Icon(if (isQueue) Icons.Rounded.QueueMusic else Icons.Rounded.CheckCircle, null, tint = if (isQueue) SpotifyWhite else Color.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(if (isQueue) str.t("הוסף לתור", "Add to Queue") else str.t("שמור בספרייה", "Save"), color = if (isQueue) SpotifyWhite else Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
        }
    ) {
        TrackRowItem(
            track = track,
            isOffline = state.offlineTrackIds.contains(track.id),
            isDownloading = state.downloadingIds.contains(track.id),
            downloadPct = state.downloadProgress[track.id],
            onClick = onClick,
            onDownloadClick = { vm.downloadOffline(track) },
            onMoreClick = { vm.openTrackOptions(track) }
        )
    }
}

@Composable
fun TrackRowItem(
    track: Track,
    isOffline: Boolean,
    isDownloading: Boolean,
    downloadPct: Int? = null,
    containerColor: Color = SpotifyBlack,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().background(containerColor).clickable(onClick = onClick).padding(vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        ArtworkImage(track.artworkUrl, null, Modifier.size(50.dp))
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (track.isExplicit) {
                    Box(Modifier.clip(RoundedCornerShape(3.dp)).background(SpotifyMuted).padding(horizontal = 4.dp, vertical = 1.dp)) {
                        Text("E", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.ExtraBold)
                    }
                }
                Text(track.title, color = SpotifyWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isOffline) Icon(Icons.Rounded.DownloadDone, null, tint = SpotifyGreen, modifier = Modifier.size(14.dp))
                val durStr = if (track.durationMs > 0) " • ${formatDurationShort(track.durationMs)}" else ""
                Text("${track.artist}$durStr", color = SpotifySubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onDownloadClick) {
            if (isDownloading) {
                if (downloadPct != null && downloadPct > 1) Text("$downloadPct%", color = SpotifyGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                else CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(if (isOffline) Icons.Rounded.DownloadDone else Icons.Rounded.Download, "Offline", tint = if (isOffline) SpotifyGreen else SpotifySubtle, modifier = Modifier.size(20.dp))
            }
        }
        IconButton(onClick = onMoreClick) { Icon(Icons.Rounded.MoreVert, "More", tint = SpotifySubtle, modifier = Modifier.size(20.dp)) }
    }
}

private fun formatDurationShort(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(sec / 60, sec % 60)
}

@Composable
fun TrackOptionsBottomSheet(track: Track, state: UiState, vm: AppViewModel) {
    val str = state.strings
    val context = LocalContext.current
    val isLiked = state.likedTracks.any { it.id == track.id }
    val isOffline = state.offlineTrackIds.contains(track.id)
    val isHidden = track.id in state.hiddenTrackIds
    var pickMultiPlaylists by remember { mutableStateOf(false) }
    val selectedPlIds = remember(track.id, state.playlists) {
        mutableStateListOf<String>().apply {
            addAll(state.playlists.filter { pl -> pl.tracks.any { it.id == track.id } }.map { it.id })
        }
    }

    ModalBottomSheet(onDismissRequest = { vm.openTrackOptions(null) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(track.artworkUrl, null, Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(track.title, color = SpotifyWhite, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1)
                }
                IconButton(onClick = { vm.openTrackOptions(null); vm.setShowCodeFor(track.title to "sonify:track:${track.id}") }) {
                    Icon(Icons.Rounded.QrCode2, "Code", tint = SpotifyGreen)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SpotifyCard)

            if (pickMultiPlaylists) {
                Text(str.t("הוספה / הסרה ממספר פלייליסטים", "Add / Remove from Playlists"), color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                if (state.playlists.isEmpty()) {
                    Text(str.t("אין עדיין פלייליסטים — צור אחד חדש בתפריט +", "No playlists yet — create one via +"), color = SpotifySubtle, fontSize = 13.sp)
                } else {
                    state.playlists.forEach { pl ->
                        val checked = pl.id in selectedPlIds
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { if (checked) selectedPlIds.remove(pl.id) else selectedPlIds.add(pl.id) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(checked = checked, onCheckedChange = { if (it) selectedPlIds.add(pl.id) else selectedPlIds.remove(pl.id) })
                            Spacer(Modifier.width(8.dp))
                            Text("${pl.name} (${pl.tracks.size})", color = SpotifyWhite, fontSize = 15.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.syncTrackPlaylists(selectedPlIds.toSet(), track) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                        Text("${str.save} (${selectedPlIds.size})", fontWeight = FontWeight.Bold)
                    }
                }
                TextButton(onClick = { pickMultiPlaylists = false }) { Text(str.back, color = SpotifySubtle) }
            } else {
                OptionActionRow(if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, if (isLiked) str.t("הסר מהשירים שאהבת", "Remove from Liked Songs") else str.t("הוסף לשירים שאהבת", "Save to Liked Songs")) { vm.toggleLike(track); vm.openTrackOptions(null) }
                OptionActionRow(Icons.Rounded.PlaylistAdd, str.t("הוסף למספר פלייליסטים", "Add to Playlists")) { pickMultiPlaylists = true }
                OptionActionRow(Icons.Rounded.QueueMusic, str.t("הוסף לתור ההשמעה", "Add to Queue")) { vm.addToQueue(track); vm.openTrackOptions(null) }
                OptionActionRow(Icons.Rounded.Person, str.t("עבור לדף האמן", "Go to Artist")) { vm.openTrackOptions(null); vm.openArtistPage(track.artist, track.artworkUrl) }
                OptionActionRow(Icons.Rounded.Radio, str.t("עבור לרדיו שיר / אמן", "Go to Song Radio")) { vm.startSongOrArtistRadio(track) }
                if (track.source == StreamSource.YOUTUBE) {
                    OptionActionRow(Icons.Rounded.OndemandVideo, str.t("נגן מיד כקליפ רשמי", "Play Official Music Video")) {
                        vm.openTrackOptions(null); vm.playTrack(track, listOf(track), openVideoImmediately = true); vm.setShowNowPlaying(true)
                    }
                }
                OptionActionRow(if (isOffline) Icons.Rounded.DownloadDone else Icons.Rounded.Download, if (isOffline) str.t("הסר מהורדות אופליין", "Remove Download") else str.t("הורד להאזנה אופליין", "Download Offline")) { vm.downloadOffline(track); vm.openTrackOptions(null) }
                OptionActionRow(if (isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (isHidden) str.t("בטל הסתרת שיר", "Unhide Song") else str.t("הסתר שיר זה (דלג תמיד)", "Hide This Song")) { vm.toggleHideTrack(track) }
                OptionActionRow(Icons.Rounded.Chat, str.t("שלח לחבר בהודעות", "Send in Messages")) {
                    vm.sendMessage(str.t("בדוק את השיר הזה!", "Check out this song!"), sharedTrack = track)
                    vm.openTrackOptions(null); vm.setShowMessagesSheet(true)
                }
                OptionActionRow(Icons.Rounded.Share, str.t("שתף שיר", "Share Track")) {
                    val sec = if (state.currentTrack?.id == track.id) (state.positionMs / 1000).toInt() else 0
                    val tsNote = if (sec > 5) " (${formatDurationShort(sec * 1000L)})" else ""
                    val url = if (track.source == StreamSource.YOUTUBE) "\nhttps://youtu.be/${track.id}${if (sec > 5) "?t=$sec" else ""}" else ""
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${track.title} - ${track.artist}$tsNote$url")
                    }, str.t("שתף שיר", "Share")))
                    vm.openTrackOptions(null)
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun OptionActionRow(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = SpotifyWhite, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = SpotifyWhite, fontSize = 15.sp)
    }
}

@Composable
fun CreateBottomSheet(state: UiState, vm: AppViewModel) {
    val str = state.strings
    var mode by remember { mutableStateOf("menu") }
    var textInput by remember { mutableStateOf("") }
    var collabChecked by remember { mutableStateOf(false) }

    // AI Playlist Studio parameters
    var aiTargetCount by remember { mutableIntStateOf(25) }
    var aiLanguage by remember { mutableStateOf("משולב") }
    var aiEnergy by remember { mutableStateOf("מאוזן") }
    var aiEra by remember { mutableStateOf("הכל") }
    val aiSuggestions = listOf("אימון כוח ואנרגיה", "צ'יל ללילה", "היפ הופ ישראלי", "רוק לנסיעה", "אנימה וגיימינג")

    ModalBottomSheet(onDismissRequest = { vm.setShowCreateSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp).imePadding()) {
            when (mode) {
                "menu" -> {
                    Text(str.create, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SpotifyWhite)
                    Spacer(Modifier.height(12.dp))
                    CreateOptionRow(Icons.Rounded.LibraryMusic, str.t("פלייליסט חדש / שיתופי", "New / Collaborative Playlist"), str.t("אסוף שירים לבד או עם חברים", "Collect tracks solo or with friends")) { textInput = ""; collabChecked = false; mode = "playlist" }
                    CreateOptionRow(Icons.Rounded.AutoAwesome, str.aiStudioTitle, str.t("בונה 15–50 שירים לפי מצב רוח, אנרגיה ותקופה", "Generate 15–50 tracks by mood, energy & era")) { textInput = ""; mode = "ai" }
                    CreateOptionRow(Icons.Rounded.GraphicEq, "AI DJ (${str.t("עם קריינות קולית", "Voice Host")})", str.t("סט רציף שמציג שירים ומחליף אווירה", "Personalized live set with DJ voice & vibes")) { vm.startAiDj() }
                    CreateOptionRow(Icons.Rounded.GroupAdd, "Blend", str.t("פלייליסט משותף של 25 שירים + אחוזי התאמה", "25-track shared playlist with taste match %")) { textInput = ""; mode = "blend" }
                    CreateOptionRow(Icons.Rounded.Devices, if (state.jamState.active) "Jam (${state.jamState.code})" else "Jam", str.t("סשן האזנה משותף בזמן אמת", "Live shared listening session")) {
                        vm.setShowCreateSheet(false); vm.setShowDevicesSheet(true)
                    }
                    CreateOptionRow(Icons.Rounded.CreateNewFolder, str.t("תיקיית פלייליסטים", "Playlist Folder"), str.t("ארגן פלייליסטים בתיקיות מסודרות", "Organize playlists in folders")) { textInput = ""; mode = "folder" }
                    CreateOptionRow(Icons.Rounded.FileDownload, str.importPlaylist, str.t("הדבק קישור או רשימת שירים משירות אחר", "Import via link or track list")) { vm.setShowCreateSheet(false); vm.setShowImportSheet(true) }
                }
                "ai" -> {
                    Text(str.aiStudioTitle, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = SpotifyGreen)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = textInput, onValueChange = { textInput = it },
                        placeholder = { Text(str.t("למשל: אימון אינטנסיבי עם ראפ ישראלי ולועזי", "e.g. Late night synthwave & chill rap")) },
                        minLines = 2, maxLines = 3, modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(aiSuggestions) { chip ->
                            Box(Modifier.clip(RoundedCornerShape(50)).background(SpotifyCard).clickable { textInput = chip }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(chip, color = SpotifyWhite, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Text(str.t("כמות שירים בפלייליסט:", "Playlist Length:"), color = SpotifySubtle, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        listOf(15, 25, 40, 50).forEach { cnt ->
                            val sel = aiTargetCount == cnt
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { aiTargetCount = cnt }.padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
                                Text("$cnt", color = if (sel) Color.Black else SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                    Text(str.t("שפה:", "Language:"), color = SpotifySubtle, fontSize = 12.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        listOf("משולב", "עברית", "אנגלית").forEach { l ->
                            val sel = aiLanguage == l
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { aiLanguage = l }.padding(vertical = 7.dp), contentAlignment = Alignment.Center) {
                                Text(l, color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }
                    Text(str.t("אנרגיה ותקופה:", "Energy & Era:"), color = SpotifySubtle, fontSize = 12.sp)
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 6.dp)) {
                        items(listOf("מאוזן", "אנרגטי", "רגוע")) { e ->
                            val sel = aiEnergy == e
                            Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { aiEnergy = e }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(e, color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp)
                            }
                        }
                        items(listOf("הכל", "חדש (2024+)", "2010s", "קלאסיקות")) { er ->
                            val sel = aiEra == er
                            Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) Color(0xFF1E3264) else SpotifyCard).clickable { aiEra = er }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                Text(er, color = SpotifyWhite, fontSize = 12.sp)
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { mode = "menu" }, modifier = Modifier.weight(1f)) { Text(str.back) }
                        Button(
                            onClick = { vm.createAiPlaylist(AiPlaylistConfig(textInput, aiTargetCount, aiLanguage, aiEnergy, aiEra)) },
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                            modifier = Modifier.weight(1.4f)
                        ) { Text(str.t("בנה $aiTargetCount שירים", "Generate $aiTargetCount"), fontWeight = FontWeight.Bold) }
                    }
                }
                else -> {
                    val title = when (mode) {
                        "playlist" -> str.t("שם הפלייליסט החדש", "New Playlist Name")
                        "folder" -> str.t("שם התיקייה החדשה", "New Folder Name")
                        else -> str.t("שם החבר ל-Blend", "Friend's Name for Blend")
                    }
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SpotifyWhite)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = textInput, onValueChange = { textInput = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (mode == "playlist") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Checkbox(checked = collabChecked, onCheckedChange = { collabChecked = it })
                            Text(str.t("פלייליסט שיתופי (Collaborative)", "Collaborative Playlist"), color = SpotifyWhite, fontSize = 13.sp)
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { mode = "menu" }, modifier = Modifier.weight(1f)) { Text(str.back) }
                        Button(
                            onClick = {
                                when (mode) {
                                    "playlist" -> vm.createPlaylist(textInput, state.likedTracks.take(5), collabChecked)
                                    "folder" -> vm.createFolder(textInput)
                                    "blend" -> vm.createBlend(textInput)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                            modifier = Modifier.weight(1f)
                        ) { Text(str.create, fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CreateOptionRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(SpotifyCard), contentAlignment = Alignment.Center) { Icon(icon, null, tint = SpotifyGreen) }
        Spacer(Modifier.width(14.dp))
        Column { Text(title, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text(subtitle, color = SpotifySubtle, fontSize = 12.sp) }
    }
}
