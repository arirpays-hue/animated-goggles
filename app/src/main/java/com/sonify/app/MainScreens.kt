@file:kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.sonify.app

import android.content.Intent
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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

// FIXED HD ArtworkImage with automatic maxresdefault -> sddefault -> hqdefault fallback!
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
    val isEn = state.settings.language == AppLanguage.ENGLISH
    LazyColumn(modifier = Modifier.fillMaxSize().background(SpotifyBlack), contentPadding = PaddingValues(bottom = 160.dp)) {
        item {
            Column(modifier = Modifier.fillMaxWidth().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.setShowSettings(true) }, modifier = Modifier.size(36.dp).clip(CircleShape).background(SpotifyCard)) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings", tint = SpotifyWhite, modifier = Modifier.size(20.dp))
                        }
                        if (state.settings.privateSession) {
                            Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF1E3264)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(if (isEn) "Private" else "סשן פרטי", color = SpotifyWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (state.settings.offlineMode) {
                            Box(Modifier.clip(RoundedCornerShape(50)).background(Color(0xFF006450)).padding(horizontal = 8.dp, vertical = 4.dp)) {
                                Text(if (isEn) "Offline" else "אופליין", color = SpotifyWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.setShowHistorySheet(true) }) { Icon(Icons.Rounded.History, "History", tint = SpotifyWhite, modifier = Modifier.size(22.dp)) }
                        IconButton(onClick = { vm.setShowStatsSheet(true) }) { Icon(Icons.Rounded.BarChart, "Stats", tint = SpotifyGreen, modifier = Modifier.size(22.dp)) }
                        IconButton(onClick = { vm.setShowMessagesSheet(true) }) { Icon(Icons.Rounded.Chat, "Messages", tint = SpotifyWhite, modifier = Modifier.size(21.dp)) }
                        if (state.isLoadingHome) {
                            CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            IconButton(onClick = { vm.refreshHomeContent() }) { Icon(Icons.Rounded.Refresh, "Refresh", tint = SpotifySubtle, modifier = Modifier.size(20.dp)) }
                        }
                    }
                }

                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(HomeFilter.entries) { f ->
                        val selected = state.homeFilter == f
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(50)).background(if (selected) SpotifyGreen else SpotifyCard)
                                .clickable { vm.setHomeFilter(f) }.padding(horizontal = 16.dp, vertical = 8.dp)
                        ) { Text(f.label(isEn), color = if (selected) Color.Black else SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
                    }
                }
            }
        }

        val gridItems = state.recentTracks.take(8)
        if (gridItems.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    gridItems.chunked(2).forEach { pair ->
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            pair.forEach { track ->
                                Row(
                                    modifier = Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(6.dp))
                                        .background(SpotifyCard).clickable { vm.playTrack(track, state.recentTracks) },
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
                            Text(dayPl.name, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(if (isEn) "${dayPl.tracks.size} songs • Tap to open" else "${dayPl.tracks.size} שירים • לחץ לפתיחת הרשימה", color = SpotifySubtle, fontSize = 12.sp)
                        }
                        IconButton(onClick = { if (dayPl.tracks.isNotEmpty()) vm.playTrack(dayPl.tracks.first(), dayPl.tracks) }) {
                            Icon(Icons.Rounded.PlayArrow, "Play", tint = SpotifyGreen, modifier = Modifier.size(30.dp))
                        }
                    }
                }
            }
        }

        if (state.homeMixPlaylists.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(top = 14.dp)) {
                    Text(if (isEn) "Made For You" else "נוצר עבורך • מיקסים ופלייליסטים", color = SpotifyWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(state.homeMixPlaylists, key = { it.id }) { pl ->
                            Column(modifier = Modifier.width(150.dp).clickable { vm.openPlaylist(pl) }) {
                                Box {
                                    PlaylistCoverBox(pl, Modifier.size(150.dp))
                                    Box(
                                        modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp).size(36.dp).clip(CircleShape)
                                            .background(SpotifyGreen).clickable { if (pl.tracks.isNotEmpty()) vm.playTrack(pl.tracks.first(), pl.tracks) },
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Rounded.PlayArrow, "Play", tint = Color.Black, modifier = Modifier.size(20.dp)) }
                                }
                                Spacer(Modifier.height(6.dp))
                                Text(pl.name, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${pl.tracks.size} ${if (isEn) "songs" else "שירים"} • ${pl.creator}", color = SpotifySubtle, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }

        if (state.trendingSongs.isNotEmpty()) {
            item {
                Column(modifier = Modifier.padding(top = 20.dp)) {
                    Text(if (isEn) "Recommended Songs" else "שירים מומלצים להאזנה מיידית", color = SpotifyWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
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

@Composable
fun SearchScreen(state: UiState, vm: AppViewModel) {
    val isEn = state.settings.language == AppLanguage.ENGLISH
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
            Text(if (isEn) "Search" else "חיפוש", color = SpotifyWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                text = state.settings.streamSource.label.substringBefore(" ("),
                color = SpotifyGreen, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(SpotifyCard)
                    .clickable { vm.setShowSettings(true) }.padding(horizontal = 10.dp, vertical = 4.dp)
            )
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
                        Text(if (isEn) "Song, artist, playlist or lyrics..." else "שיר, אמן, פלייליסט או מילים...", color = SpotifyMuted, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
            ) { Text(if (isEn) "Go" else "חפש", color = Color.Black, fontWeight = FontWeight.Bold, fontSize = 14.sp) }
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
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(if (isEn) "No playlists found" else "לא נמצאו פלייליסטים", color = SpotifySubtle) }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
                                    Text("פלייליסט • ${rPl.subtitle}", color = SpotifySubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                Icon(Icons.Rounded.LibraryMusic, null, tint = SpotifyGreen)
                            }
                        }
                    }
                }
            } else {
                if (state.searchResults.isEmpty()) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { Text(if (isEn) "No results found" else "לא נמצאו תוצאות", color = SpotifySubtle) }
                } else {
                    LazyColumn(contentPadding = PaddingValues(bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(state.searchResults, key = { it.id }) { track ->
                            SwipeableTrackRow(track = track, state = state, vm = vm, onClick = { vm.playTrack(track, state.searchResults) })
                        }
                    }
                }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
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
    val isEn = state.settings.language == AppLanguage.ENGLISH
    var showSortMenu by remember { mutableStateOf(false) }

    val allSavedTracks = remember(state.likedTracks, state.recentTracks) { (state.likedTracks + state.recentTracks).distinctBy { it.id } }
    val displayedTracks = remember(allSavedTracks, state.likedTracks, state.offlineTrackIds, state.libraryFilter, state.librarySort) {
        val base = when (state.libraryFilter) {
            LibraryFilter.OFFLINE -> allSavedTracks.filter { it.id in state.offlineTrackIds || it.source == StreamSource.LOCAL }
            LibraryFilter.PODCASTS -> allSavedTracks.filter { it.isPodcast }
            LibraryFilter.PLAYLISTS, LibraryFilter.FOLDERS -> emptyList()
            else -> state.likedTracks
        }
        when (state.librarySort) {
            LibrarySort.ALPHA -> base.sortedBy { it.title }
            LibrarySort.CREATOR -> base.sortedBy { it.artist }
            LibrarySort.DATE_ADDED -> base.sortedByDescending { it.addedAt }
            else -> base
        }
    }

    val displayedPlaylists = remember(state.playlists, state.libraryFilter, state.librarySort, state.selectedFolderId) {
        val base = when {
            state.selectedFolderId != null -> state.playlists.filter { it.folderId == state.selectedFolderId }
            state.libraryFilter == LibraryFilter.ALL || state.libraryFilter == LibraryFilter.PLAYLISTS -> state.playlists
            else -> emptyList()
        }
        val sorted = when (state.librarySort) {
            LibrarySort.ALPHA -> base.sortedBy { it.name }
            LibrarySort.CREATOR -> base.sortedBy { it.creator }
            LibrarySort.DATE_ADDED -> base.sortedByDescending { it.createdAt }
            else -> base
        }
        sorted.sortedByDescending { it.isPinned }
    }

    val artistsGrouped = remember(allSavedTracks) { allSavedTracks.groupBy { it.artist }.toList().sortedByDescending { it.second.size } }
    val albumsGrouped = remember(allSavedTracks) { allSavedTracks.groupBy { it.album.ifBlank { it.artist } }.toList().sortedByDescending { it.second.size } }

    Column(modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(if (isEn) "Your Library" else "הספרייה שלך", color = SpotifyWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = { vm.setShowImportSheet(true) }) { Icon(Icons.Rounded.FileDownload, "Import", tint = SpotifyWhite) }
                IconButton(onClick = { vm.setShowCreateSheet(true) }) { Icon(Icons.Rounded.Add, "Create", tint = SpotifyWhite) }
                IconButton(onClick = { vm.setShowSettings(true) }) { Icon(Icons.Rounded.Settings, "Settings", tint = SpotifyWhite) }
            }
        }

        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(LibraryFilter.entries) { filter ->
                val selected = state.libraryFilter == filter
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (selected) SpotifyGreen else SpotifyCard).clickable { vm.setLibraryFilter(filter) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                    Text(filter.label(isEn), color = if (selected) Color.Black else SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
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
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (state.folders.isEmpty()) item { Text(if (isEn) "No folders yet — tap + to create one" else "אין עדיין תיקיות — צור תיקייה דרך +", color = SpotifySubtle, modifier = Modifier.padding(16.dp)) }
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
                            Text("$count ${if (isEn) "playlists" else "פלייליסטים"}", color = SpotifySubtle, fontSize = 12.sp)
                        }
                    }
                }
            }
            return
        }

        if (state.selectedFolderId != null) {
            val fName = state.folders.firstOrNull { it.id == state.selectedFolderId }?.name ?: "תיקייה"
            Row(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp).clickable { vm.selectFolder(null) }, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.ArrowForward, null, tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("$fName (${if (isEn) "Back" else "חזרה"})", color = SpotifyGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            }
        }

        if (state.libraryFilter == LibraryFilter.ARTISTS) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(artistsGrouped) { (artist, tracks) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyDarkSurface)
                            .clickable { vm.openPlaylist(Playlist("artist_$artist", artist, "אמן", coverUrl = tracks.first().artworkUrl, tracks = tracks, isRemote = true)) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(tracks.first().artworkUrl, artist, Modifier.size(50.dp).clip(CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column { Text(artist, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text("${tracks.size} שירים", color = SpotifySubtle, fontSize = 12.sp) }
                    }
                }
            }
            return
        }

        if (state.libraryFilter == LibraryFilter.ALBUMS) {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(albumsGrouped) { (album, tracks) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyDarkSurface)
                            .clickable { vm.openPlaylist(Playlist("album_$album", album, "אלבום", coverUrl = tracks.first().artworkUrl, tracks = tracks, isRemote = true)) }.padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(tracks.first().artworkUrl, album, Modifier.size(50.dp))
                        Spacer(Modifier.width(12.dp))
                        Column { Text(album, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text("${tracks.size} שירים", color = SpotifySubtle, fontSize = 12.sp) }
                    }
                }
            }
            return
        }

        if (displayedPlaylists.isEmpty() && displayedTracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                Text(if (isEn) "Your library is empty — create a playlist or like songs" else "הספרייה ריקה כרגע — צור פלייליסט, ייבא או אהוב שירים", color = SpotifySubtle)
            }
        } else if (state.libraryGridView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
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
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                            Text("${if (pl.isPublic) "ציבורי" else "פרטי"}${if (pl.isCollaborative) " • שיתופי" else ""} • ${pl.tracks.size} שירים", color = SpotifySubtle, fontSize = 12.sp, maxLines = 1)
                        }
                        IconButton(onClick = { vm.togglePinPlaylist(pl.id) }) {
                            Icon(Icons.Rounded.PushPin, "Pin", tint = if (pl.isPinned) SpotifyGreen else SpotifyMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
                items(displayedTracks, key = { it.id }) { track ->
                    SwipeableTrackRow(track = track, state = state, vm = vm, onClick = { vm.playTrack(track, displayedTracks) })
                }
            }
        }
    }
}

@Composable
fun PlaylistDetailScreen(playlist: Playlist, state: UiState, vm: AppViewModel) {
    var showFolderPicker by remember { mutableStateOf(false) }

    Column(modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.openPlaylist(null) }) { Icon(Icons.Rounded.ArrowForward, "Back", tint = SpotifyWhite) }
            PlaylistCoverBox(playlist, Modifier.size(44.dp))
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(playlist.name, color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${playlist.creator} • ${playlist.tracks.size} שירים", color = SpotifySubtle, fontSize = 12.sp)
            }
            IconButton(onClick = { vm.setShowCodeFor(playlist.name to "sonify:playlist:${playlist.id.take(10)}") }) { Icon(Icons.Rounded.QrCode2, "Code", tint = SpotifyWhite) }
            if (playlist.isRemote) {
                IconButton(onClick = { vm.saveRemotePlaylistToLibrary(playlist) }, enabled = playlist.tracks.isNotEmpty()) { Icon(Icons.Rounded.BookmarkAdd, "Save", tint = SpotifyGreen) }
            } else {
                IconButton(onClick = { vm.deletePlaylist(playlist.id) }) { Icon(Icons.Rounded.Delete, "Delete", tint = Color(0xFFFF6B6B)) }
            }
        }

        if (!playlist.isRemote) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item { PlaylistActionPill(if (playlist.isPinned) "נעוץ" else "נעץ", Icons.Rounded.PushPin, playlist.isPinned) { vm.togglePinPlaylist(playlist.id) } }
                item { PlaylistActionPill("עצב עטיפה", Icons.Rounded.Palette, playlist.customCoverBgHex != null) { vm.setEditingCoverPlaylist(playlist.id) } }
                item { PlaylistActionPill(if (playlist.isMixedMode) "Mix פעיל" else "מצב Mix", Icons.Rounded.GraphicEq, playlist.isMixedMode) { vm.togglePlaylistFlag(playlist.id, "mix") } }
                item { PlaylistActionPill(if (playlist.isCollaborative) "שיתופי" else "הפוך לשיתופי", Icons.Rounded.GroupAdd, playlist.isCollaborative) { vm.togglePlaylistFlag(playlist.id, "collab") } }
                item { PlaylistActionPill(if (playlist.isPublic) "ציבורי" else "פרטי", if (playlist.isPublic) Icons.Rounded.Public else Icons.Rounded.Lock, !playlist.isPublic) { vm.togglePlaylistFlag(playlist.id, "public") } }
                item { PlaylistActionPill(if (playlist.excludeFromTaste) "מוחרג מטעם" else "החרג מטעם", Icons.Rounded.Block, playlist.excludeFromTaste) { vm.togglePlaylistFlag(playlist.id, "taste") } }
                if (state.folders.isNotEmpty()) {
                    item { PlaylistActionPill("תיקייה", Icons.Rounded.Folder, playlist.folderId != null) { showFolderPicker = true } }
                }
            }
        }

        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = {
                    if (playlist.tracks.isNotEmpty()) {
                        if (playlist.isMixedMode && state.settings.crossfadeSeconds == 0) vm.updateSettings(state.settings.copy(automixEnabled = true))
                        vm.playTrack(playlist.tracks.first(), playlist.tracks)
                    }
                },
                enabled = playlist.tracks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                modifier = Modifier.weight(1f)
            ) { Icon(Icons.Rounded.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text("נגן הכל", fontWeight = FontWeight.Bold) }
            OutlinedButton(
                onClick = { if (playlist.tracks.isNotEmpty()) { val s = playlist.tracks.shuffled(); vm.playTrack(s.first(), s) } },
                enabled = playlist.tracks.isNotEmpty(), modifier = Modifier.weight(1f)
            ) { Icon(Icons.Rounded.Shuffle, null, tint = SpotifyWhite); Spacer(Modifier.width(6.dp)); Text("ערבב", color = SpotifyWhite) }
        }

        if (state.isLoadingPlaylist) {
            Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SpotifyGreen) }
        } else if (playlist.tracks.isEmpty()) {
            Column(modifier = Modifier.fillMaxWidth().padding(32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text("הפלייליסט ריק כרגע", color = SpotifyWhite, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.selectTab(MainTab.SEARCH) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)) { Text("חפש שירים להוספה") }
            }
        } else {
            LazyColumn(contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(playlist.tracks, key = { it.id }) { track ->
                    val isHidden = track.id in state.hiddenTrackIds
                    val isUnplayableOffline = state.settings.offlineMode && track.id !in state.offlineTrackIds && track.source != StreamSource.LOCAL
                    if (!state.settings.showUnplayableSongs && (isHidden || isUnplayableOffline)) return@items

                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.alpha(if (isHidden || isUnplayableOffline) 0.42f else 1f)) {
                        Box(modifier = Modifier.weight(1f)) {
                            SwipeableTrackRow(track = track, state = state, vm = vm, onClick = { if (!isHidden) vm.playTrack(track, playlist.tracks) })
                        }
                        if (!playlist.isRemote) {
                            IconButton(onClick = { vm.removeTrackFromPlaylist(playlist.id, track.id) }) { Icon(Icons.Rounded.Close, "Remove", tint = SpotifyMuted, modifier = Modifier.size(18.dp)) }
                        }
                    }
                }
            }
        }
    }

    if (showFolderPicker) {
        ModalBottomSheet(onDismissRequest = { showFolderPicker = false }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("בחר תיקייה לפלייליסט", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(Modifier.height(10.dp))
                Text("ללא תיקייה", color = SpotifySubtle, modifier = Modifier.fillMaxWidth().clickable { vm.assignPlaylistToFolder(playlist.id, null); showFolderPicker = false }.padding(vertical = 10.dp))
                state.folders.forEach { f ->
                    Text(f.name, color = SpotifyWhite, modifier = Modifier.fillMaxWidth().clickable { vm.assignPlaylistToFolder(playlist.id, f.id); showFolderPicker = false }.padding(vertical = 10.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun PlaylistActionPill(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, active: Boolean, onClick: () -> Unit) {
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
                    Text(if (isQueue) "הוסף לתור" else "שמור בספרייה", color = if (isQueue) SpotifyWhite else Color.Black, fontWeight = FontWeight.Bold, fontSize = 13.sp)
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
                if (downloadPct != null && downloadPct > 1) {
                    Text("$downloadPct%", color = SpotifyGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                } else {
                    CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                }
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
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
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
                Text("הוספה / הסרה ממספר פלייליסטים", color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                if (state.playlists.isEmpty()) {
                    Text("אין עדיין פלייליסטים — צור אחד חדש בתפריט יצירה (+)", color = SpotifySubtle, fontSize = 13.sp)
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
                    Button(
                        onClick = { vm.syncTrackPlaylists(selectedPlIds.toSet(), track) },
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("שמור שינויים (${selectedPlIds.size})", fontWeight = FontWeight.Bold) }
                }
                TextButton(onClick = { pickMultiPlaylists = false }) { Text("חזרה", color = SpotifySubtle) }
            } else {
                OptionActionRow(if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, if (isLiked) "הסר מהשירים שאהבת" else "הוסף לשירים שאהבת") { vm.toggleLike(track); vm.openTrackOptions(null) }
                OptionActionRow(Icons.Rounded.PlaylistAdd, "הוסף למספר פלייליסטים") { pickMultiPlaylists = true }
                OptionActionRow(Icons.Rounded.QueueMusic, "הוסף לתור ההשמעה") { vm.addToQueue(track); vm.openTrackOptions(null) }
                OptionActionRow(Icons.Rounded.Radio, "עבור לרדיו שיר / אמן") { vm.startSongOrArtistRadio(track) }
                if (track.source == StreamSource.YOUTUBE) {
                    OptionActionRow(Icons.Rounded.OndemandVideo, "נגן מיד כקליפ וידאו") {
                        vm.openTrackOptions(null); vm.playTrack(track, listOf(track), openVideoImmediately = true); vm.setShowNowPlaying(true)
                    }
                }
                OptionActionRow(if (isOffline) Icons.Rounded.DownloadDone else Icons.Rounded.Download, if (isOffline) "הסר מהורדות אופליין" else "הורד להאזנה אופליין") { vm.downloadOffline(track); vm.openTrackOptions(null) }
                OptionActionRow(if (isHidden) Icons.Rounded.Visibility else Icons.Rounded.VisibilityOff, if (isHidden) "בטל הסתרת שיר" else "הסתר שיר זה (דלג תמיד)") { vm.toggleHideTrack(track) }
                OptionActionRow(Icons.Rounded.Chat, "שלח לחבר בהודעות Sonify") { vm.sendMessage("בדוק את השיר הזה!", sharedTrack = track); vm.openTrackOptions(null); vm.setShowMessagesSheet(true) }
                OptionActionRow(Icons.Rounded.Share, "שתף שיר") {
                    val sec = if (state.currentTrack?.id == track.id) (state.positionMs / 1000).toInt() else 0
                    val tsNote = if (sec > 5) " (מתחיל ב-${formatDurationShort(sec * 1000L)})" else ""
                    val url = if (track.source == StreamSource.YOUTUBE) "\nhttps://youtu.be/${track.id}${if (sec > 5) "?t=$sec" else ""}" else ""
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${track.title} - ${track.artist}$tsNote$url")
                    }, "שתף שיר"))
                    vm.openTrackOptions(null)
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun OptionActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = SpotifyWhite, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = SpotifyWhite, fontSize = 15.sp)
    }
}

@Composable
fun CreateBottomSheet(state: UiState, vm: AppViewModel) {
    var mode by remember { mutableStateOf("menu") }
    var textInput by remember { mutableStateOf("") }
    var collabChecked by remember { mutableStateOf(false) }
    val aiSuggestions = listOf("אימון כוח ואנרגיה", "צ'יל ללילה", "היפ הופ ישראלי", "רוק לנסיעה", "אנימה וגיימינג")

    ModalBottomSheet(onDismissRequest = { vm.setShowCreateSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            when (mode) {
                "menu" -> {
                    Text("יצירה", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SpotifyWhite)
                    Spacer(Modifier.height(12.dp))
                    CreateOptionRow(Icons.Rounded.LibraryMusic, "פלייליסט חדש / שיתופי", "אסוף שירים לבד או יחד עם חברים") { textInput = ""; collabChecked = false; mode = "playlist" }
                    CreateOptionRow(Icons.Rounded.AutoAwesome, "AI Playlist", "בונה פלייליסט שלם מתיאור טקסטואלי") { textInput = ""; mode = "ai" }
                    CreateOptionRow(Icons.Rounded.GraphicEq, "AI DJ", "סט רציף מותאם לטעם האישי שלך") { vm.startAiDj() }
                    CreateOptionRow(Icons.Rounded.GroupAdd, "Blend", "פלייליסט משותף עם ציון אחוזי התאמה") { textInput = ""; mode = "blend" }
                    CreateOptionRow(Icons.Rounded.Devices, if (state.jamState.active) "סיום Jam (${state.jamState.code})" else "Jam", "סשן האזנה משותף בזמן אמת") { vm.toggleJamSession() }
                    CreateOptionRow(Icons.Rounded.CreateNewFolder, "תיקיית פלייליסטים", "ארגן פלייליסטים בתיקיות מסודרות") { textInput = ""; mode = "folder" }
                    CreateOptionRow(Icons.Rounded.FileDownload, "ייבוא פלייליסט", "הדבק קישור או רשימת שירים משירות אחר") { vm.setShowCreateSheet(false); vm.setShowImportSheet(true) }
                }
                else -> {
                    val title = when (mode) {
                        "playlist" -> "שם הפלייליסט החדש"
                        "folder" -> "שם התיקייה החדשה"
                        "blend" -> "שם החבר ל-Blend"
                        else -> "AI Playlist • תאר איזו מוזיקה תרצה"
                    }
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SpotifyWhite)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = textInput, onValueChange = { textInput = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    if (mode == "playlist") {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
                            Checkbox(checked = collabChecked, onCheckedChange = { collabChecked = it })
                            Text("פלייליסט שיתופי (Collaborative)", color = SpotifyWhite, fontSize = 13.sp)
                        }
                    }
                    if (mode == "ai") {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(aiSuggestions) { chip ->
                                Box(Modifier.clip(RoundedCornerShape(50)).background(SpotifyCard).clickable { textInput = chip }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                                    Text(chip, color = SpotifyWhite, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedButton(onClick = { mode = "menu" }, modifier = Modifier.weight(1f)) { Text("חזרה") }
                        Button(
                            onClick = {
                                when (mode) {
                                    "playlist" -> vm.createPlaylist(textInput, state.likedTracks.take(5), collabChecked)
                                    "folder" -> vm.createFolder(textInput)
                                    "blend" -> vm.createBlend(textInput)
                                    "ai" -> vm.createAiPlaylist(textInput)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                            modifier = Modifier.weight(1f)
                        ) { Text(if (mode == "ai") "בנה עם AI" else "צור", fontWeight = FontWeight.Bold) }
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun CreateOptionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(modifier = Modifier.size(42.dp).clip(CircleShape).background(SpotifyCard), contentAlignment = Alignment.Center) { Icon(icon, null, tint = SpotifyGreen) }
        Spacer(Modifier.width(14.dp))
        Column { Text(title, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp); Text(subtitle, color = SpotifySubtle, fontSize = 12.sp) }
    }
}
