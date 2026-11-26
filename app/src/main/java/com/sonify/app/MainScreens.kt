@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.sonify.app

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
        contentPadding = PaddingValues(bottom = 148.dp)
    ) {
        // Top Header + Filter Chips
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
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(SpotifyCard)
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

        // Daylist Banner Card
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

        // Horizontal Carousels
        if (state.dailyMixTracks.isNotEmpty()) {
            item {
                TrackCarousel(
                    title = "המיקסים היומיים שלך",
                    tracks = state.dailyMixTracks,
                    onTrackClick = { vm.playTrack(it, state.dailyMixTracks) }
                )
            }
        }
        if (state.discoverTracks.isNotEmpty()) {
            item {
                TrackCarousel(
                    title = "Discover Weekly • גילוי שבועי",
                    tracks = state.discoverTracks,
                    onTrackClick = { vm.playTrack(it, state.discoverTracks) }
                )
            }
        }
        if (state.releaseRadarTracks.isNotEmpty()) {
            item {
                TrackCarousel(
                    title = "Release Radar • שחרורים חדשים",
                    tracks = state.releaseRadarTracks,
                    onTrackClick = { vm.playTrack(it, state.releaseRadarTracks) }
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
private fun TrackCarousel(title: String, tracks: List<Track>, onTrackClick: (Track) -> Unit) {
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
                        .width(140.dp)
                        .clickable { onTrackClick(track) }
                ) {
                    AsyncImage(
                        model = track.artworkUrl,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(SpotifyCard)
                    )
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

        OutlinedTextField(
            value = state.searchQuery,
            onValueChange = { vm.onSearchQueryChange(it) },
            placeholder = { Text("שיר, אמן, אלבום או שורת מילים...", color = SpotifyMuted, fontSize = 14.sp) },
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
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(14.dp))

        if (state.isSearching) {
            Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = SpotifyGreen)
            }
        } else if (state.searchQuery.isNotBlank() && state.searchResults.isNotEmpty()) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 148.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(state.searchResults, key = { it.id }) { track ->
                    TrackRowItem(
                        track = track,
                        isLiked = state.likedTracks.any { it.id == track.id },
                        isOffline = state.offlineTrackIds.contains(track.id),
                        onClick = { vm.playTrack(track, state.searchResults) },
                        onQueueClick = { vm.addToQueue(track) },
                        onDownloadClick = { vm.downloadOffline(track) }
                    )
                }
            }
        } else {
            Text("עיון לפי ז'אנרים ומצבי רוח", color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(bottom = 148.dp),
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

    val displayedTracks = remember(state.likedTracks, state.recentTracks, state.offlineTrackIds, state.libraryFilter, state.librarySort) {
        val base = when (state.libraryFilter) {
            LibraryFilter.OFFLINE -> (state.likedTracks + state.recentTracks)
                .distinctBy { it.id }
                .filter { state.offlineTrackIds.contains(it.id) }
            LibraryFilter.PODCASTS -> (state.likedTracks + state.recentTracks).filter { it.isPodcast }
            else -> state.likedTracks
        }
        when (state.librarySort) {
            LibrarySort.ALPHA -> base.sortedBy { it.title }
            LibrarySort.CREATOR -> base.sortedBy { it.artist }
            LibrarySort.RECENT -> base
        }
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

        // Filter Chips
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

        // Sort + Grid/List Toggle
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
                            onClick = {
                                vm.setLibrarySort(s)
                                showSortMenu = false
                            }
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

        if (state.libraryGridView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 148.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(state.playlists, key = { it.id }) { pl ->
                    Column(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(SpotifyDarkSurface)
                            .clickable { if (pl.tracks.isNotEmpty()) vm.playTrack(pl.tracks.first(), pl.tracks) }
                            .padding(10.dp)
                    ) {
                        Box(
                            modifier = Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(6.dp)).background(SpotifyCard),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.LibraryMusic, contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(40.dp))
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
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 148.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(state.playlists, key = { it.id }) { pl ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .background(SpotifyDarkSurface)
                            .clickable { if (pl.tracks.isNotEmpty()) vm.playTrack(pl.tracks.first(), pl.tracks) }
                            .padding(10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(52.dp).clip(RoundedCornerShape(6.dp))
                                .background(if (pl.isBlend) Color(0xFF9C27B0) else SpotifyCard),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (pl.isBlend) Icons.Rounded.GroupAdd else Icons.Rounded.LibraryMusic,
                                contentDescription = null,
                                tint = SpotifyWhite
                            )
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(pl.name, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                            Text(
                                pl.description.ifBlank { "פלייליסט • ${pl.tracks.size} שירים" },
                                color = SpotifySubtle,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
                items(displayedTracks, key = { it.id }) { track ->
                    TrackRowItem(
                        track = track,
                        isLiked = state.likedTracks.any { it.id == track.id },
                        isOffline = state.offlineTrackIds.contains(track.id),
                        onClick = { vm.playTrack(track, displayedTracks) },
                        onQueueClick = { vm.addToQueue(track) },
                        onDownloadClick = { vm.downloadOffline(track) }
                    )
                }
            }
        }
    }
}

@Composable
fun TrackRowItem(
    track: Track,
    isLiked: Boolean,
    isOffline: Boolean,
    onClick: () -> Unit,
    onQueueClick: () -> Unit,
    onDownloadClick: () -> Unit
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
            Icon(
                if (isOffline) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                contentDescription = "אופליין",
                tint = if (isOffline) SpotifyGreen else SpotifySubtle,
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onQueueClick) {
            Icon(Icons.Rounded.QueueMusic, contentDescription = "הוסף לתור", tint = SpotifySubtle, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
fun CreateBottomSheet(state: UiState, vm: AppViewModel) {
    var mode by remember { mutableStateOf("menu") }
    var textInput by remember { mutableStateOf("") }

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
                    CreateOptionRow(Icons.Rounded.GroupAdd, "Blend", "פלייליסט משותף עם ציון אחוזי התאמה") {
                        textInput = ""; mode = "blend"
                    }
                    CreateOptionRow(Icons.Rounded.Devices, if (state.jamState.active) "סיום Jam (${state.jamState.code})" else "Jam", "סשן האזנה משותף בזמן אמת") {
                        vm.toggleJamSession()
                    }
                    CreateOptionRow(Icons.Rounded.AutoAwesome, "AI Playlist", "בנה פלייליסט מתיאור טקסט חופשי") {
                        textInput = ""; mode = "ai"
                    }
                    CreateOptionRow(Icons.Rounded.GraphicEq, "AI DJ", "סט מוזיקלי רציף מותאם אישית") {
                        vm.startAiDj()
                    }
                }
                else -> {
                    val title = when (mode) {
                        "playlist" -> "שם הפלייליסט החדש"
                        "blend" -> "שם החבר ל-Blend"
                        else -> "תאר את האווירה ל-AI Playlist"
                    }
                    Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = SpotifyWhite)
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = textInput,
                        onValueChange = { textInput = it },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
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
                        ) { Text("צור") }
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
