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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun HomeScreen(state: UiState, vm: AppViewModel) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotifyBlack),
        contentPadding = PaddingValues(bottom = 156.dp)
    ) {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = { vm.setShowSettings(true) },
                    modifier = Modifier.size(36.dp).clip(CircleShape).background(SpotifyCard)
                ) {
                    Icon(Icons.Rounded.Settings, contentDescription = "הגדרות", tint = SpotifyWhite, modifier = Modifier.size(20.dp))
                }
                HomeFilter.entries.forEach { f ->
                    val selected = state.homeFilter == f
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(50))
                            .background(if (selected) SpotifyGreen else SpotifyCard)
                            .clickable { vm.setHomeFilter(f) }
                            .padding(horizontal = 16.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = f.label,
                            color = if (selected) Color.Black else SpotifyWhite,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 13.sp
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                if (state.isLoadingHome) {
                    CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    IconButton(onClick = { vm.refreshHomeContent() }, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Rounded.Refresh, contentDescription = "רענן", tint = SpotifySubtle, modifier = Modifier.size(20.dp))
                    }
                }
            }
        }

        // 2x4 Quick Access Grid (8 tiles)
        val gridItems = state.recentTracks.take(8)
        if (gridItems.isNotEmpty()) {
            item {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    gridItems.chunked(2).forEach { pair ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            pair.forEach { track ->
                                QuickAccessTile(
                                    track = track,
                                    modifier = Modifier.weight(1f),
                                    onClick = { vm.playTrack(track, state.recentTracks) }
                                )
                            }
                            if (pair.size == 1) Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }
        }

        // Daylist Banner
        if (state.daylistTracks.isNotEmpty()) {
            item {
                Spacer(Modifier.height(16.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clickable { vm.playTrack(state.daylistTracks.first(), state.daylistTracks) },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Brush.horizontalGradient(listOf(Color(0xFF1E3A5F), Color(0xFF1DB954).copy(alpha = 0.35f))))
                            .padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = state.daylistTracks.first().artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(56.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(state.daylistTitle, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text("מתעדכן לאורך היום לפי הסגנון שלך", color = SpotifySubtle, fontSize = 12.sp)
                        }
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(30.dp))
                    }
                }
            }
        }

        if (state.dailyMixTracks.isNotEmpty()) {
            item {
                TrackCarousel(
                    title = "המיקסים היומיים שלך",
                    tracks = state.dailyMixTracks,
                    onTrackClick = { vm.playTrack(it, state.dailyMixTracks) },
                    onMoreClick = { vm.openTrackOptions(it) }
                )
            }
        }
        if (state.discoverTracks.isNotEmpty()) {
            item {
                TrackCarousel(
                    title = "Discover Weekly • גילוי שבועי",
                    tracks = state.discoverTracks,
                    onTrackClick = { vm.playTrack(it, state.discoverTracks) },
                    onMoreClick = { vm.openTrackOptions(it) }
                )
            }
        }
        if (state.releaseRadarTracks.isNotEmpty()) {
            item {
                TrackCarousel(
                    title = "Release Radar • שחרורים חדשים",
                    tracks = state.releaseRadarTracks,
                    onTrackClick = { vm.playTrack(it, state.releaseRadarTracks) },
                    onMoreClick = { vm.openTrackOptions(it) }
                )
            }
        }
    }
}

@Composable
private fun QuickAccessTile(track: Track, modifier: Modifier, onClick: () -> Unit) {
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(SpotifyCard)
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(56.dp)
        )
        Text(
            text = track.title,
            color = SpotifyWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun TrackCarousel(
    title: String,
    tracks: List<Track>,
    onTrackClick: (Track) -> Unit,
    onMoreClick: (Track) -> Unit
) {
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Text(
            text = title,
            color = SpotifyWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(tracks, key = { it.id }) { track ->
                Column(
                    modifier = Modifier
                        .width(142.dp)
                        .clickable { onTrackClick(track) }
                ) {
                    Box {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(142.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(SpotifyCard)
                        )
                        IconButton(
                            onClick = { onMoreClick(track) },
                            modifier = Modifier
                                .align(Alignment.TopEnd)
                                .padding(4.dp)
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.55f))
                        ) {
                            Icon(Icons.Rounded.MoreVert, contentDescription = "אפשרויות", tint = SpotifyWhite, modifier = Modifier.size(16.dp))
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(track.title, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
fun SearchScreen(state: UiState, vm: AppViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotifyBlack)
            .statusBarsPadding()
            .padding(horizontal = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("חיפוש", color = SpotifyWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(
                text = state.settings.streamSource.label.substringBefore(" "),
                color = SpotifyGreen,
                fontSize = 12.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .background(SpotifyCard)
                    .clickable { vm.setShowSettings(true) }
                    .padding(horizontal = 10.dp, vertical = 4.dp)
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = { vm.onSearchQueryChange(it) },
                placeholder = { Text("שיר, אמן, אלבום או מילים...", color = SpotifyMuted, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Color.Black) },
                trailingIcon = {
                    if (state.searchQuery.isNotBlank()) {
                        IconButton(onClick = { vm.onSearchQueryChange("") }) {
                            Icon(Icons.Rounded.Close, contentDescription = "נקה", tint = Color.Black)
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.performSearch() }),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = SpotifyWhite,
                    unfocusedContainerColor = SpotifyWhite,
                    focusedTextColor = Color.Black,
                    unfocusedTextColor = Color.Black,
                    cursorColor = SpotifyGreen
                ),
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.weight(1f)
            )
            Button(
                onClick = { vm.performSearch() },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                shape = RoundedCornerShape(8.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 16.dp)
            ) {
                Text("חפש", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(14.dp))

        if (state.isSearching) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SpotifyGreen)
            }
        } else if (state.hasSearched && state.searchQuery.isNotBlank()) {
            if (state.searchResults.isEmpty()) {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text("לא נמצאו תוצאות עבור \"${state.searchQuery}\"", color = SpotifySubtle)
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(bottom = 156.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(state.searchResults, key = { it.id }) { track ->
                        TrackRowItem(
                            track = track,
                            isOffline = state.offlineTrackIds.contains(track.id),
                            isDownloading = state.downloadingIds.contains(track.id),
                            onClick = { vm.playTrack(track, state.searchResults) },
                            onDownloadClick = { vm.downloadOffline(track) },
                            onMoreClick = { vm.openTrackOptions(track) }
                        )
                    }
                }
            }
        } else {
            Text("עיון לפי ז'אנרים ומצבי רוח", color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 156.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(vm.genres) { genre ->
                    Box(
                        modifier = Modifier
                            .height(92.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(genre.colorHex))
                            .clickable { vm.performSearch(genre.query) }
                            .padding(12.dp)
                    ) {
                        Text(genre.title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(state: UiState, vm: AppViewModel) {
    var showSortMenu by remember { mutableStateOf(false) }

    val allSavedTracks = remember(state.likedTracks, state.recentTracks) {
        (state.likedTracks + state.recentTracks).distinctBy { it.id }
    }

    val displayedTracks = remember(allSavedTracks, state.likedTracks, state.offlineTrackIds, state.libraryFilter, state.librarySort) {
        val base = when (state.libraryFilter) {
            LibraryFilter.OFFLINE -> allSavedTracks.filter { state.offlineTrackIds.contains(it.id) }
            LibraryFilter.PODCASTS -> allSavedTracks.filter { it.isPodcast }
            LibraryFilter.PLAYLISTS -> emptyList()
            else -> state.likedTracks
        }
        when (state.librarySort) {
            LibrarySort.ALPHA -> base.sortedBy { it.title }
            LibrarySort.CREATOR -> base.sortedBy { it.artist }
            LibrarySort.RECENT -> base
        }
    }

    val displayedPlaylists = remember(state.playlists, state.libraryFilter, state.librarySort) {
        val base = when (state.libraryFilter) {
            LibraryFilter.ALL, LibraryFilter.PLAYLISTS -> state.playlists
            else -> emptyList()
        }
        when (state.librarySort) {
            LibrarySort.ALPHA -> base.sortedBy { it.name }
            LibrarySort.CREATOR -> base.sortedBy { it.creator }
            LibrarySort.RECENT -> base
        }
    }

    val artistsGrouped = remember(allSavedTracks) {
        allSavedTracks.groupBy { it.artist }.toList().sortedByDescending { it.second.size }
    }

    val albumsGrouped = remember(allSavedTracks) {
        allSavedTracks.groupBy { it.album.ifBlank { it.artist } }.toList().sortedByDescending { it.second.size }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotifyBlack)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("הספרייה שלך", color = SpotifyWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Row {
                IconButton(onClick = { vm.setShowCreateSheet(true) }) {
                    Icon(Icons.Rounded.Add, contentDescription = "יצירה", tint = SpotifyWhite)
                }
                IconButton(onClick = { vm.setShowSettings(true) }) {
                    Icon(Icons.Rounded.Settings, contentDescription = "הגדרות", tint = SpotifyWhite)
                }
            }
        }

        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(LibraryFilter.entries) { filter ->
                val selected = state.libraryFilter == filter
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(50))
                        .background(if (selected) SpotifyGreen else SpotifyCard)
                        .clickable { vm.setLibraryFilter(filter) }
                        .padding(horizontal = 14.dp, vertical = 7.dp)
                ) {
                    Text(filter.label, color = if (selected) Color.Black else SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Row(
                    modifier = Modifier.clickable { showSortMenu = true },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Sort, contentDescription = null, tint = SpotifyWhite, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(state.librarySort.label, color = SpotifyWhite, fontSize = 13.sp)
                }
                DropdownMenu(expanded = showSortMenu, onDismissRequest = { showSortMenu = false }) {
                    LibrarySort.entries.forEach { s ->
                        DropdownMenuItem(
                            text = { Text(s.label) },
                            onClick = { vm.setLibrarySort(s); showSortMenu = false }
                        )
                    }
                }
            }
            IconButton(onClick = { vm.toggleLibraryGrid() }) {
                Icon(
                    if (state.libraryGridView) Icons.Rounded.ViewList else Icons.Rounded.GridView,
                    contentDescription = "שינוי תצוגה",
                    tint = SpotifyWhite
                )
            }
        }

        // Special views for Artists / Albums filters
        if (state.libraryFilter == LibraryFilter.ARTISTS) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 156.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(artistsGrouped) { (artist, tracks) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SpotifyDarkSurface)
                            .clickable { vm.playTrack(tracks.first(), tracks) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = tracks.first().artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(50.dp).clip(CircleShape).background(SpotifyCard)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(artist, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("אמן • ${tracks.size} שירים", color = SpotifySubtle, fontSize = 12.sp)
                        }
                    }
                }
            }
            return
        }

        if (state.libraryFilter == LibraryFilter.ALBUMS) {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 156.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(albumsGrouped) { (album, tracks) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SpotifyDarkSurface)
                            .clickable { vm.playTrack(tracks.first(), tracks) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = tracks.first().artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(50.dp).clip(RoundedCornerShape(6.dp)).background(SpotifyCard)
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(album, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text("אלבום • ${tracks.size} שירים", color = SpotifySubtle, fontSize = 12.sp)
                        }
                    }
                }
            }
            return
        }

        if (displayedPlaylists.isEmpty() && displayedTracks.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.TopCenter) {
                Text("הספרייה ריקה כרגע — לחץ על + ליצירת פלייליסט או אהוב שירים", color = SpotifySubtle)
            }
        } else if (state.libraryGridView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 156.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(displayedPlaylists, key = { it.id }) { pl ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SpotifyDarkSurface)
                            .clickable { vm.openPlaylist(pl.id) }
                            .padding(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(6.dp)).background(SpotifyCard),
                            contentAlignment = Alignment.Center
                        ) {
                            val cover = pl.tracks.firstOrNull()?.artworkUrl
                            if (!cover.isNullOrBlank()) {
                                AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(40.dp))
                            }
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(pl.name, color = SpotifyWhite, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("${pl.tracks.size} שירים • ${pl.creator}", color = SpotifySubtle, fontSize = 11.sp)
                    }
                }
                items(displayedTracks, key = { it.id }) { t ->
                    Column(modifier = Modifier.clickable { vm.playTrack(t, displayedTracks) }) {
                        AsyncImage(
                            model = t.artworkUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(130.dp).clip(RoundedCornerShape(8.dp))
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(t.title, color = SpotifyWhite, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(t.artist, color = SpotifySubtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 156.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(displayedPlaylists, key = { it.id }) { pl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SpotifyDarkSurface)
                            .clickable { vm.openPlaylist(pl.id) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (pl.isBlend) Color(0xFF9C27B0) else SpotifyCard),
                            contentAlignment = Alignment.Center
                        ) {
                            val cover = pl.tracks.firstOrNull()?.artworkUrl
                            if (!cover.isNullOrBlank()) {
                                AsyncImage(model = cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            } else {
                                Icon(
                                    if (pl.isBlend) Icons.Rounded.GroupAdd else Icons.Rounded.LibraryMusic,
                                    contentDescription = null,
                                    tint = SpotifyWhite
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pl.name, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                pl.description.ifBlank { "פלייליסט • ${pl.tracks.size} שירים" },
                                color = SpotifySubtle,
                                fontSize = 12.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        IconButton(onClick = {
                            if (pl.tracks.isNotEmpty()) vm.playTrack(pl.tracks.first(), pl.tracks)
                            else vm.openPlaylist(pl.id)
                        }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "נגן", tint = SpotifyGreen)
                        }
                    }
                }
                items(displayedTracks, key = { it.id }) { track ->
                    TrackRowItem(
                        track = track,
                        isOffline = state.offlineTrackIds.contains(track.id),
                        isDownloading = state.downloadingIds.contains(track.id),
                        onClick = { vm.playTrack(track, displayedTracks) },
                        onDownloadClick = { vm.downloadOffline(track) },
                        onMoreClick = { vm.openTrackOptions(track) }
                    )
                }
            }
        }
    }
}

@Composable
fun PlaylistDetailScreen(playlist: Playlist, state: UiState, vm: AppViewModel) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotifyBlack)
            .statusBarsPadding()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.openPlaylist(null) }) {
                Icon(Icons.Rounded.ArrowForward, contentDescription = "חזרה", tint = SpotifyWhite)
            }
            Column(modifier = Modifier.weight(1f)) {
                Text(playlist.name, color = SpotifyWhite, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("${playlist.creator} • ${playlist.tracks.size} שירים", color = SpotifySubtle, fontSize = 12.sp)
            }
            IconButton(onClick = { vm.deletePlaylist(playlist.id) }) {
                Icon(Icons.Rounded.Delete, contentDescription = "מחק פלייליסט", tint = Color(0xFFFF6B6B))
            }
        }

        if (playlist.description.isNotBlank()) {
            Text(playlist.description, color = SpotifySubtle, fontSize = 13.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Button(
                onClick = { if (playlist.tracks.isNotEmpty()) vm.playTrack(playlist.tracks.first(), playlist.tracks) },
                enabled = playlist.tracks.isNotEmpty(),
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("נגן הכל", fontWeight = FontWeight.Bold)
            }
            OutlinedButton(
                onClick = {
                    if (playlist.tracks.isNotEmpty()) {
                        val shuffled = playlist.tracks.shuffled()
                        vm.playTrack(shuffled.first(), shuffled)
                    }
                },
                enabled = playlist.tracks.isNotEmpty(),
                modifier = Modifier.weight(1f)
            ) {
                Icon(Icons.Rounded.Shuffle, contentDescription = null, tint = SpotifyWhite)
                Spacer(Modifier.width(6.dp))
                Text("ערבב", color = SpotifyWhite)
            }
        }

        if (playlist.tracks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("הפלייליסט ריק כרגע", color = SpotifyWhite, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("חפש שירים ולחץ על ⋮ ליד כל שיר כדי להוסיף אותו לכאן", color = SpotifySubtle, fontSize = 13.sp)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { vm.selectTab(MainTab.SEARCH) },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)
                ) {
                    Text("עבור לחיפוש שירים")
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 156.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(playlist.tracks, key = { it.id }) { track ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(modifier = Modifier.weight(1f)) {
                            TrackRowItem(
                                track = track,
                                isOffline = state.offlineTrackIds.contains(track.id),
                                isDownloading = state.downloadingIds.contains(track.id),
                                onClick = { vm.playTrack(track, playlist.tracks) },
                                onDownloadClick = { vm.downloadOffline(track) },
                                onMoreClick = { vm.openTrackOptions(track) }
                            )
                        }
                        IconButton(onClick = { vm.removeTrackFromPlaylist(playlist.id, track.id) }) {
                            Icon(Icons.Rounded.Close, contentDescription = "הסר", tint = SpotifyMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TrackRowItem(
    track: Track,
    isOffline: Boolean,
    isDownloading: Boolean,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = track.artworkUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp)).background(SpotifyCard)
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(track.title, color = SpotifyWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (isOffline) {
                    Icon(Icons.Rounded.DownloadDone, contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(14.dp))
                }
                Text(track.artist, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        IconButton(onClick = onDownloadClick) {
            if (isDownloading) {
                CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                Icon(
                    if (isOffline) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                    contentDescription = "אופליין",
                    tint = if (isOffline) SpotifyGreen else SpotifySubtle,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        IconButton(onClick = onMoreClick) {
            Icon(Icons.Rounded.MoreVert, contentDescription = "אפשרויות", tint = SpotifySubtle, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun TrackOptionsBottomSheet(track: Track, state: UiState, vm: AppViewModel) {
    val context = LocalContext.current
    val isLiked = state.likedTracks.any { it.id == track.id }
    val isOffline = state.offlineTrackIds.contains(track.id)
    var pickPlaylist by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = { vm.openTrackOptions(null) },
        containerColor = SpotifyDarkSurface,
        contentColor = SpotifyWhite
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AsyncImage(
                    model = track.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(6.dp))
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(track.title, color = SpotifyWhite, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SpotifyCard)

            if (pickPlaylist) {
                Text("בחר פלייליסט להוספה", color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                Spacer(Modifier.height(8.dp))
                if (state.playlists.isEmpty()) {
                    Text("אין עדיין פלייליסטים — צור אחד חדש בתפריט יצירה (+)", color = SpotifySubtle, fontSize = 13.sp)
                } else {
                    state.playlists.forEach { pl ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { vm.addTrackToPlaylist(pl.id, track) }.padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = SpotifyGreen)
                            Spacer(Modifier.width(12.dp))
                            Text("${pl.name} (${pl.tracks.size})", color = SpotifyWhite, fontSize = 15.sp)
                        }
                    }
                }
                TextButton(onClick = { pickPlaylist = false }) { Text("חזרה", color = SpotifySubtle) }
            } else {
                OptionActionRow(if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, if (isLiked) "הסר מהשירים שאהבת" else "הוסף לשירים שאהבת") {
                    vm.toggleLike(track); vm.openTrackOptions(null)
                }
                OptionActionRow(Icons.Rounded.PlaylistAdd, "הוסף לפלייליסט") { pickPlaylist = true }
                OptionActionRow(Icons.Rounded.QueueMusic, "הוסף לתור ההשמעה") {
                    vm.addToQueue(track); vm.openTrackOptions(null)
                }
                if (track.source == StreamSource.YOUTUBE) {
                    OptionActionRow(Icons.Rounded.OndemandVideo, "נגן מיד כקליפ וידאו") {
                        vm.openTrackOptions(null)
                        vm.playTrack(track, listOf(track), openVideoImmediately = true)
                        vm.setShowNowPlaying(true)
                    }
                }
                OptionActionRow(if (isOffline) Icons.Rounded.DownloadDone else Icons.Rounded.Download, if (isOffline) "הסר מהורדות אופליין" else "הורד להאזנה אופליין") {
                    vm.downloadOffline(track); vm.openTrackOptions(null)
                }
                OptionActionRow(Icons.Rounded.Share, "שתף שיר") {
                    val sendIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "${track.title} - ${track.artist}")
                    }
                    context.startActivity(Intent.createChooser(sendIntent, "שתף שיר"))
                    vm.openTrackOptions(null)
                }
            }
            Spacer(Modifier.height(22.dp))
        }
    }
}

@Composable
private fun OptionActionRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = SpotifyWhite, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = SpotifyWhite, fontSize = 15.sp)
    }
}

@Composable
fun CreateBottomSheet(state: UiState, vm: AppViewModel) {
    var mode by remember { mutableStateOf("menu") }
    var textInput by remember { mutableStateOf("") }

    val aiSuggestions = listOf("אימון כוח ואנרגיה", "צ'יל ללילה", "היפ הופ ישראלי", "רוק לנסיעה", "מסיבה ודאנס")

    ModalBottomSheet(
        onDismissRequest = { vm.setShowCreateSheet(false) },
        containerColor = SpotifyDarkSurface,
        contentColor = SpotifyWhite
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp)) {
            when (mode) {
                "menu" -> {
                    Text("יצירה", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = SpotifyWhite)
                    Spacer(Modifier.height(14.dp))
                    CreateOptionRow(Icons.Rounded.LibraryMusic, "פלייליסט חדש", "אסוף שירים ואלבומים משלך") {
                        textInput = ""; mode = "playlist"
                    }
                    CreateOptionRow(Icons.Rounded.AutoAwesome, "AI Playlist", "בנה פלייליסט שלם מתיאור חופשי") {
                        textInput = ""; mode = "ai"
                    }
                    CreateOptionRow(Icons.Rounded.GraphicEq, "AI DJ", "סט מוזיקלי רציף מותאם אישית") {
                        vm.startAiDj()
                    }
                    CreateOptionRow(Icons.Rounded.GroupAdd, "Blend", "פלייליסט משותף עם ציון אחוזי התאמה") {
                        textInput = ""; mode = "blend"
                    }
                    CreateOptionRow(Icons.Rounded.Devices, if (state.jamState.active) "סיום Jam (${state.jamState.code})" else "Jam", "סשן האזנה משותף בזמן אמת") {
                        vm.toggleJamSession()
                    }
                }
                else -> {
                    val title = when (mode) {
                        "playlist" -> "שם הפלייליסט החדש"
                        "blend" -> "שם החבר ל-Blend"
                        else -> "AI Playlist • תאר איזו מוזיקה תרצה"
                    }
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SpotifyWhite)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        placeholder = {
                            Text(
                                when (mode) {
                                    "playlist" -> "למשל: שירי נסיעה"
                                    "blend" -> "למשל: עומר"
                                    else -> "למשל: שירים לאימון בחדר כושר"
                                }
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    if (mode == "ai") {
                        Spacer(Modifier.height(10.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(aiSuggestions) { chip ->
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(50))
                                        .background(SpotifyCard)
                                        .clickable { textInput = chip }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
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
                                    "playlist" -> vm.createPlaylist(textInput, state.likedTracks.take(5))
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
    Row(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.size(44.dp).clip(CircleShape).background(SpotifyCard),
            contentAlignment = Alignment.Center
        ) {
            Icon(icon, contentDescription = null, tint = SpotifyGreen)
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
            Text(subtitle, color = SpotifySubtle, fontSize = 12.sp)
        }
    }
}
