@file:kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)

package com.sonify.app

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

private val liveSuggestClient = OkHttpClient.Builder()
    .connectTimeout(3, TimeUnit.SECONDS)
    .readTimeout(4, TimeUnit.SECONDS)
    .callTimeout(5, TimeUnit.SECONDS)
    .build()

private suspend fun fetchLiveYouTubeSuggestions(query: String): List<String> = withContext(Dispatchers.IO) {
    if (query.isBlank()) return@withContext emptyList()
    runCatching {
        val enc = URLEncoder.encode(query.trim(), "UTF-8")
        val url = "https://suggestqueries.google.com/complete/search?client=firefox&ds=yt&hl=he&q=$enc"
        val req = Request.Builder()
            .url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
            .build()
        liveSuggestClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return@use emptyList()
            val arr = JSONArray(resp.body?.string() ?: return@use emptyList())
            val suggestions = arr.optJSONArray(1) ?: return@use emptyList()
            buildList {
                for (i in 0 until suggestions.length().coerceAtMost(7)) {
                    val s = suggestions.optString(i).trim()
                    if (s.isNotBlank()) add(s)
                }
            }
        }
    }.getOrDefault(emptyList())
}

@Composable
fun ArtworkImage(url: String, contentDescription: String?, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val upgraded = remember(url) { upgradeArtworkUrlGlobal(url) }
    val fallbackUrl = remember(url) {
        if (upgraded.contains("/maxresdefault.jpg")) upgraded.replace("/maxresdefault.jpg", "/hqdefault.jpg") else ""
    }
    var useFallback by remember(url) { mutableStateOf(false) }
    val activeUrl = if (useFallback && fallbackUrl.isNotBlank()) fallbackUrl else upgraded

    if (activeUrl.isBlank()) {
        Box(modifier = modifier.clip(RoundedCornerShape(8.dp)).background(SpotifyCard), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, contentDescription, tint = SpotifySubtle, modifier = Modifier.size(26.dp))
        }
        return
    }

    SubcomposeAsyncImage(
        model = ImageRequest.Builder(context).data(activeUrl).crossfade(true).build(),
        contentDescription = contentDescription,
        contentScale = ContentScale.Crop,
        modifier = modifier.clip(RoundedCornerShape(8.dp)),
        loading = { Box(Modifier.fillMaxSize().background(SpotifyCard)) },
        error = {
            if (!useFallback && fallbackUrl.isNotBlank()) {
                LaunchedEffect(url) { useFallback = true }
                Box(Modifier.fillMaxSize().background(SpotifyCard))
            } else {
                Box(Modifier.fillMaxSize().background(SpotifyCard), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, tint = SpotifySubtle)
                }
            }
        }
    )
}

@Composable
fun PlaylistCoverBox(playlist: Playlist, modifier: Modifier = Modifier, useAiCovers: Boolean = true) {
    AiPlaylistCoverArt(playlist = playlist, useAiCovers = useAiCovers, modifier = modifier)
}

@Composable
fun TrackRowItem(
    track: Track,
    isOffline: Boolean,
    isDownloading: Boolean,
    downloadPct: Int?,
    containerColor: Color = Color.Transparent,
    isPlayingCurrent: Boolean = false,
    isAudioPlaying: Boolean = false,
    indexNumber: Int? = null,
    isUnavailable: Boolean = false,
    onKeepSmartClick: (() -> Unit)? = null,
    onClick: () -> Unit,
    onDownloadClick: () -> Unit,
    onMoreClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(containerColor)
            .alpha(if (isUnavailable) 0.42f else 1f)
            .spotifyPressClickable(pressedScale = 0.985f, onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (indexNumber != null) {
            Box(modifier = Modifier.width(26.dp), contentAlignment = Alignment.Center) {
                if (isPlayingCurrent) {
                    SpotifyPlayingBars(isPlaying = isAudioPlaying, color = SpotifyGreen, size = 14.dp)
                } else {
                    Text("$indexNumber", color = SpotifySubtle, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.width(6.dp))
        }

        Box(modifier = Modifier.size(50.dp)) {
            ArtworkImage(track.artworkUrl, track.title, Modifier.fillMaxSize())
            if (isPlayingCurrent && indexNumber == null) {
                Box(
                    modifier = Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.55f)),
                    contentAlignment = Alignment.Center
                ) {
                    SpotifyPlayingBars(isPlaying = isAudioPlaying, color = SpotifyGreen, size = 16.dp)
                }
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (track.isSmartInjected) {
                    Icon(Icons.Rounded.AutoAwesome, null, tint = SpotifyGreen, modifier = Modifier.size(13.dp).padding(end = 4.dp))
                }
                Text(
                    text = track.title,
                    color = if (isPlayingCurrent) SpotifyGreen else SpotifyWhite,
                    fontSize = 14.5.sp,
                    fontWeight = if (isPlayingCurrent) FontWeight.Bold else FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isOffline) {
                    Icon(Icons.Rounded.DownloadDone, null, tint = SpotifyGreen, modifier = Modifier.size(13.dp).padding(end = 4.dp))
                }
                if (track.isExplicit) {
                    Box(
                        modifier = Modifier.padding(end = 5.dp).clip(RoundedCornerShape(3.dp))
                            .background(SpotifySubtle.copy(alpha = 0.8f)).padding(horizontal = 3.5.dp, vertical = 0.5.dp)
                    ) { Text("E", color = Color.Black, fontSize = 8.5.sp, fontWeight = FontWeight.Black) }
                }
                val durStr = if (track.durationMs > 0) " • ${formatTrackDuration(track.durationMs)}" else ""
                Text(
                    text = "${track.artist}$durStr",
                    color = SpotifySubtle,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        if (track.isSmartInjected && onKeepSmartClick != null) {
            Box(
                modifier = Modifier.padding(end = 4.dp).clip(RoundedCornerShape(50))
                    .background(SpotifyGreen.copy(alpha = 0.18f))
                    .clickable { onKeepSmartClick() }
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text("+ שמור", color = SpotifyGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }

        IconButton(onClick = onDownloadClick, modifier = Modifier.size(34.dp)) {
            when {
                isDownloading -> {
                    if (downloadPct != null && downloadPct > 1) {
                        Text("$downloadPct%", color = SpotifyGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    } else {
                        CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                    }
                }
                isOffline -> Icon(Icons.Rounded.DownloadDone, "Downloaded", tint = SpotifyGreen, modifier = Modifier.size(19.dp))
                else -> Icon(Icons.Rounded.Download, "Download", tint = SpotifySubtle, modifier = Modifier.size(19.dp))
            }
        }

        IconButton(onClick = onMoreClick, modifier = Modifier.size(34.dp)) {
            Icon(Icons.Rounded.MoreVert, "More", tint = SpotifySubtle, modifier = Modifier.size(19.dp))
        }
    }
}

private fun formatTrackDuration(ms: Long): String {
    val sec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(sec / 60, sec % 60)
}

// =====================================================================
// 1. INFINITE AI HOME SCREEN (AI Covers + Ready YouTube Playlists + Infinite Feed)
// =====================================================================
@Composable
fun HomeScreen(state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isEn = str.isEn
    val listState = rememberLazyListState()

    val shouldLoadMore by remember {
        derivedStateOf {
            val total = listState.layoutInfo.totalItemsCount
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            total > 4 && lastVisible >= total - 3
        }
    }
    LaunchedEffect(shouldLoadMore, state.isLoadingHome, state.infiniteSections.size) {
        if (shouldLoadMore && state.settings.infiniteAiHome && !state.settings.offlineMode) {
            vm.loadMoreHomeContent()
        }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 165.dp)
    ) {
        item(key = "home_header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier.size(34.dp).clip(CircleShape).background(SpotifyGreen)
                        .spotifyPressClickable { vm.setShowStatsSheet(true) },
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        state.settings.jamUserDisplayName.firstOrNull()?.uppercase() ?: "S",
                        color = Color.Black, fontWeight = FontWeight.Black, fontSize = 15.sp
                    )
                }
                Spacer(Modifier.width(10.dp))
                LazyRow(modifier = Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(HomeFilter.entries) { filter ->
                        val sel = state.homeFilter == filter
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(if (sel) SpotifyGreen else SpotifyCard)
                                .spotifyPressClickable { vm.setHomeFilter(filter) }
                                .padding(horizontal = 14.dp, vertical = 7.dp)
                        ) {
                            Text(
                                filter.label(isEn),
                                color = if (sel) Color.Black else SpotifyWhite,
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
                IconButton(onClick = { vm.setShowHistorySheet(true) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.History, str.listeningHistory, tint = SpotifyWhite, modifier = Modifier.size(21.dp))
                }
                IconButton(onClick = { vm.setShowMessagesSheet(true) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.ChatBubbleOutline, str.messagesAndFriends, tint = SpotifyWhite, modifier = Modifier.size(20.dp))
                }
                IconButton(onClick = { vm.setShowSettings(true) }, modifier = Modifier.size(34.dp)) {
                    Icon(Icons.Rounded.Settings, str.settings, tint = SpotifyWhite, modifier = Modifier.size(21.dp))
                }
            }
        }

        item(key = "quick_grid") {
            val quickCards = buildList<Pair<String, () -> Unit>> {
                add(str.likedSongs to { vm.openLikedSongsPlaylist() })
                state.homeMixPlaylists.take(3).forEach { pl -> add(pl.name to { vm.openPlaylist(pl) }) }
                state.recentTracks.take(4).forEach { trk -> add(trk.title to { vm.playTrack(trk, state.recentTracks) }) }
            }.take(6)

            val quickArt = buildList<Pair<String, Playlist?>> {
                add((state.likedTracks.firstOrNull()?.artworkUrl ?: "") to Playlist("ql", str.likedSongs, "Sonify", customCoverBgHex = 0xFF503750, aiStyleTag = "LIKED"))
                state.homeMixPlaylists.take(3).forEach { pl -> add(pl.coverUrl to pl) }
                state.recentTracks.take(4).forEach { trk -> add(trk.artworkUrl to null) }
            }.take(6)

            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                quickCards.chunked(2).forEachIndexed { rowIdx, pair ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        pair.forEachIndexed { colIdx, (title, onTap) ->
                            val idx = rowIdx * 2 + colIdx
                            val (artUrl, plObj) = quickArt.getOrElse(idx) { "" to null }
                            Row(
                                modifier = Modifier.weight(1f).height(56.dp).clip(RoundedCornerShape(6.dp))
                                    .background(SpotifyCard).spotifyPressClickable(pressedScale = 0.96f, onClick = onTap),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (plObj != null) {
                                    AiPlaylistCoverArt(plObj, state.settings.aiUniqueCovers, Modifier.size(56.dp))
                                } else {
                                    ArtworkImage(artUrl, title, Modifier.size(56.dp))
                                }
                                Text(
                                    title, color = SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                    maxLines = 2, lineHeight = 14.sp, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(horizontal = 8.dp).weight(1f)
                                )
                            }
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }

        item(key = "daylist_dj") {
            Spacer(Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.daylistPlaylist?.let { dl ->
                    Card(
                        modifier = Modifier.weight(1.35f).height(92.dp).spotifyPressClickable { vm.openPlaylist(dl) },
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)
                    ) {
                        Row(modifier = Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                            AiPlaylistCoverArt(dl, state.settings.aiUniqueCovers, Modifier.size(92.dp))
                            Column(modifier = Modifier.padding(10.dp).weight(1f)) {
                                Text("DAYLIST", color = SpotifyGreen, fontSize = 10.sp, fontWeight = FontWeight.ExtraBold)
                                Text(dl.name, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Card(
                    modifier = Modifier.weight(1f).height(92.dp).spotifyPressClickable { vm.startAiDj() },
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Transparent)
                ) {
                    Box(
                        modifier = Modifier.fillMaxSize()
                            .background(Brush.linearGradient(listOf(Color(0xFF1DB954), Color(0xFF0D47A1))))
                            .padding(12.dp)
                    ) {
                        Column(modifier = Modifier.align(Alignment.BottomStart)) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = Color.White, modifier = Modifier.size(20.dp))
                            Spacer(Modifier.height(4.dp))
                            Text("AI DJ", color = Color.White, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Text(str.t("סט אישי חי", "Live Personal Set"), color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (state.isLoadingHome) {
            item(key = "home_loading") {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    SpotifyMelodyDots(isPlaying = true, tint = SpotifyGreen, dotSize = 11.dp)
                }
            }
        }

        if (state.homeMixPlaylists.isNotEmpty()) {
            item(key = "made_for_you") {
                Spacer(Modifier.height(20.dp))
                Text(str.madeForYou, color = SpotifyWhite, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.homeMixPlaylists, key = { it.id }) { pl ->
                        Column(modifier = Modifier.width(148.dp).spotifyPressClickable { vm.openPlaylist(pl) }) {
                            AiPlaylistCoverArt(pl, state.settings.aiUniqueCovers, Modifier.size(148.dp))
                            Spacer(Modifier.height(7.dp))
                            Text(pl.name, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pl.tracks.size} ${str.songs}", color = SpotifySubtle, fontSize = 11.sp)
                        }
                    }
                }
            }
        }

        if (state.readyYoutubePlaylists.isNotEmpty() && state.settings.showYoutubePlaylistsHome) {
            item(key = "yt_ready_playlists") {
                Spacer(Modifier.height(22.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(str.readyYtPlaylists, color = SpotifyWhite, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    Text("YouTube Music", color = SpotifyGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.readyYoutubePlaylists, key = { it.id }) { rp ->
                        Column(modifier = Modifier.width(146.dp).spotifyPressClickable { vm.openRemotePlaylist(rp) }) {
                            ArtworkImage(rp.artworkUrl, rp.title, Modifier.size(146.dp))
                            Spacer(Modifier.height(7.dp))
                            Text(rp.title, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(rp.subtitle, color = SpotifySubtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (state.trendingSongs.isNotEmpty()) {
            item(key = "rec_header") {
                Spacer(Modifier.height(22.dp))
                val recTitle = when (state.homeFilter) {
                    HomeFilter.AUDIOBOOKS -> str.topAudiobooks
                    HomeFilter.PODCASTS -> str.topPodcasts
                    else -> str.recommendedSongs
                }
                Text(recTitle, color = SpotifyWhite, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 16.dp))
                Spacer(Modifier.height(8.dp))
            }
            items(state.trendingSongs.take(8), key = { "tr_${it.id}" }) { track ->
                Box(modifier = Modifier.padding(horizontal = 10.dp)) {
                    TrackRowItem(
                        track = track,
                        isOffline = track.id in state.offlineTrackIds,
                        isDownloading = track.id in state.downloadingIds,
                        downloadPct = state.downloadProgress[track.id],
                        isPlayingCurrent = state.currentTrack?.id == track.id,
                        isAudioPlaying = state.isPlaying,
                        onClick = { vm.playTrack(track, state.trendingSongs) },
                        onDownloadClick = { vm.downloadOffline(track) },
                        onMoreClick = { vm.openTrackOptions(track) }
                    )
                }
            }
        }

        items(state.infiniteSections, key = { it.id }) { section ->
            Column(modifier = Modifier.fillMaxWidth().padding(top = 22.dp)) {
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(section.title, color = SpotifyWhite, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                    if (section.subtitle.isNotBlank()) {
                        Text(section.subtitle, color = SpotifySubtle, fontSize = 12.sp)
                    }
                }
                Spacer(Modifier.height(10.dp))

                if (section.playlists.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(section.playlists, key = { it.id }) { pl ->
                            Column(modifier = Modifier.width(148.dp).spotifyPressClickable { vm.openPlaylist(pl) }) {
                                AiPlaylistCoverArt(pl, state.settings.aiUniqueCovers, Modifier.size(148.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(pl.name, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text("${pl.tracks.size} ${str.songs}", color = SpotifySubtle, fontSize = 11.sp)
                            }
                        }
                    }
                }

                if (section.remotePlaylists.isNotEmpty()) {
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        items(section.remotePlaylists, key = { it.id }) { rp ->
                            Column(modifier = Modifier.width(146.dp).spotifyPressClickable { vm.openRemotePlaylist(rp) }) {
                                ArtworkImage(rp.artworkUrl, rp.title, Modifier.size(146.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(rp.title, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(rp.subtitle, color = SpotifySubtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }

                if (section.tracks.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    section.tracks.take(4).forEach { trk ->
                        Box(modifier = Modifier.padding(horizontal = 10.dp)) {
                            TrackRowItem(
                                track = trk,
                                isOffline = trk.id in state.offlineTrackIds,
                                isDownloading = trk.id in state.downloadingIds,
                                downloadPct = state.downloadProgress[trk.id],
                                isPlayingCurrent = state.currentTrack?.id == trk.id,
                                isAudioPlaying = state.isPlaying,
                                onClick = { vm.playTrack(trk, section.tracks) },
                                onDownloadClick = { vm.downloadOffline(trk) },
                                onMoreClick = { vm.openTrackOptions(trk) }
                            )
                        }
                    }
                }
            }
        }

        if (state.settings.infiniteAiHome && !state.settings.offlineMode) {
            item(key = "infinite_footer") {
                Box(modifier = Modifier.fillMaxWidth().padding(vertical = 22.dp, horizontal = 16.dp), contentAlignment = Alignment.Center) {
                    if (state.isLoadingMoreHome) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            SpotifyMelodyDots(isPlaying = true, tint = SpotifyGreen, dotSize = 9.dp)
                            Spacer(Modifier.height(8.dp))
                            Text(str.infiniteFeedLoading, color = SpotifySubtle, fontSize = 12.sp)
                        }
                    } else {
                        OutlinedButton(
                            onClick = { vm.loadMoreHomeContent() },
                            shape = RoundedCornerShape(50),
                            border = BorderStroke(1.dp, SpotifyCard)
                        ) {
                            Icon(Icons.Rounded.AutoAwesome, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(str.t("טען עוד המלצות AI", "Load more AI picks"), color = SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// =====================================================================
// 2. SPOTIFY-STYLE LIVE TYPE-AHEAD SEARCH SCREEN + MOOD CARDS
// =====================================================================
@Composable
fun SearchScreen(state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isEn = str.isEn
    val focusManager = LocalFocusManager.current

    var liveSuggestions by remember { mutableStateOf<List<String>>(emptyList()) }
    var instantTracks by remember { mutableStateOf<List<Track>>(emptyList()) }
    var isFetchingLive by remember { mutableStateOf(false) }

    LaunchedEffect(state.searchQuery, state.hasSearched, state.settings.offlineMode, state.searchMode) {
        val q = state.searchQuery.trim()
        if (q.isEmpty() || state.hasSearched || state.settings.offlineMode) {
            if (q.isEmpty()) {
                liveSuggestions = emptyList()
                instantTracks = emptyList()
            }
            isFetchingLive = false
            return@LaunchedEffect
        }
        isFetchingLive = true
        delay(170)
        val (sugs, trks) = coroutineScope {
            val sJob = async { fetchLiveYouTubeSuggestions(q) }
            val tJob = async {
                runCatching {
                    vm.repo.searchTracks(
                        query = q,
                        settings = state.settings,
                        podcastsOnly = state.searchMode == SearchMode.PODCASTS,
                        audiobooksOnly = state.searchMode == SearchMode.AUDIOBOOKS,
                        maxResults = 6
                    )
                }.getOrDefault(emptyList())
            }
            sJob.await() to tJob.await()
        }
        if (state.searchQuery.trim() == q && !state.hasSearched) {
            liveSuggestions = sugs
            instantTracks = trks
            isFetchingLive = false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 165.dp)
    ) {
        item(key = "search_top") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(str.search, color = SpotifyWhite, fontSize = 26.sp, fontWeight = FontWeight.ExtraBold)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    IconButton(onClick = { vm.setShowCodeFor(str.t("סריקת קוד Sonify", "Scan Sonify Code") to "") }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.QrCodeScanner, "Scan QR", tint = SpotifyWhite, modifier = Modifier.size(22.dp))
                    }
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(SpotifyCard)
                            .clickable { vm.setShowSettings(true); vm.setSettingsSection(SettingsSection.STREAM_QUALITY) }
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(state.settings.streamSource.label(isEn).substringBefore(" ("), color = SpotifyGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item(key = "search_bar") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.weight(1f).height(48.dp).clip(RoundedCornerShape(8.dp))
                        .background(Color(0xFFF2F2F2)).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = Color(0xFF181818), modifier = Modifier.size(22.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (state.searchQuery.isEmpty()) {
                            Text(str.searchPlaceholder, color = Color(0xFF5A5A5A), fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        BasicTextField(
                            value = state.searchQuery,
                            onValueChange = { vm.onSearchQueryChange(it) },
                            singleLine = true,
                            textStyle = TextStyle(color = Color.Black, fontSize = 15.sp, fontWeight = FontWeight.Medium),
                            cursorBrush = SolidColor(SpotifyGreen),
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus(); vm.performSearch() }),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (state.searchQuery.isNotEmpty()) {
                        IconButton(onClick = { vm.onSearchQueryChange(""); liveSuggestions = emptyList(); instantTracks = emptyList() }, modifier = Modifier.size(26.dp)) {
                            Icon(Icons.Rounded.Close, "Clear", tint = Color(0xFF333333), modifier = Modifier.size(18.dp))
                        }
                    }
                }
                Button(
                    onClick = { focusManager.clearFocus(); vm.performSearch() },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp),
                    modifier = Modifier.height(48.dp)
                ) {
                    Text(str.go, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        item(key = "search_modes") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(SearchMode.entries) { mode ->
                    val sel = state.searchMode == mode
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (sel) SpotifyGreen else SpotifyCard)
                            .spotifyPressClickable { vm.setSearchMode(mode, triggerSearch = state.hasSearched) }
                            .padding(horizontal = 15.dp, vertical = 7.dp)
                    ) {
                        Text(mode.label(isEn), color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.5.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (state.searchQuery.isNotBlank() && !state.hasSearched) {
            if (isFetchingLive && liveSuggestions.isEmpty() && instantTracks.isEmpty()) {
                item(key = "live_loading") {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(10.dp))
                        Text(str.t("מחפש הצעות ושירים...", "Finding live suggestions & songs..."), color = SpotifySubtle, fontSize = 13.sp)
                    }
                }
            }

            if (liveSuggestions.isNotEmpty()) {
                items(liveSuggestions, key = { "sug_$it" }) { suggestion ->
                    val qTrim = state.searchQuery.trim()
                    val annotated = remember(suggestion, qTrim) {
                        buildAnnotatedString {
                            val idx = suggestion.indexOf(qTrim, ignoreCase = true)
                            if (idx >= 0) {
                                append(suggestion.substring(0, idx))
                                withStyle(SpanStyle(color = SpotifyWhite, fontWeight = FontWeight.ExtraBold)) {
                                    append(suggestion.substring(idx, idx + qTrim.length))
                                }
                                withStyle(SpanStyle(color = SpotifySubtle, fontWeight = FontWeight.Medium)) {
                                    append(suggestion.substring(idx + qTrim.length))
                                }
                            } else {
                                withStyle(SpanStyle(color = SpotifyWhite, fontWeight = FontWeight.Medium)) {
                                    append(suggestion)
                                }
                            }
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { focusManager.clearFocus(); vm.performSearch(suggestion) }
                            .padding(horizontal = 16.dp, vertical = 11.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = SpotifySubtle, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(12.dp))
                        Text(annotated, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        IconButton(
                            onClick = { vm.onSearchQueryChange(suggestion) },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Rounded.NorthWest, "Fill", tint = SpotifyMuted, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            val topArtist = instantTracks.firstOrNull()?.artist?.takeIf { it.isNotBlank() && !it.equals("YouTube", true) }
            if (topArtist != null) {
                item(key = "live_artist_jump") {
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable { focusManager.clearFocus(); vm.openArtistPage(topArtist, instantTracks.first().artworkUrl) }
                            .padding(horizontal = 16.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(instantTracks.first().artworkUrl, topArtist, Modifier.size(48.dp).clip(CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(topArtist, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(5.dp))
                                SpotifyVerifiedBadge(size = 15.dp)
                            }
                            Text(str.t("אמן • לחץ לפרופיל מלא", "Artist • Tap for full page"), color = SpotifySubtle, fontSize = 12.sp)
                        }
                        Icon(if (isEn) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft, null, tint = SpotifyMuted)
                    }
                }
            }

            if (instantTracks.isNotEmpty()) {
                item(key = "live_songs_header") {
                    Text(
                        str.t("שירים תואמים עכשיו", "Instant Song Matches"),
                        color = SpotifyGreen,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                    )
                }
                items(instantTracks, key = { "live_${it.id}" }) { track ->
                    Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                        TrackRowItem(
                            track = track,
                            isOffline = track.id in state.offlineTrackIds,
                            isDownloading = track.id in state.downloadingIds,
                            downloadPct = state.downloadProgress[track.id],
                            isPlayingCurrent = state.currentTrack?.id == track.id,
                            isAudioPlaying = state.isPlaying,
                            onClick = { focusManager.clearFocus(); vm.playTrack(track, instantTracks) },
                            onDownloadClick = { vm.downloadOffline(track) },
                            onMoreClick = { vm.openTrackOptions(track) }
                        )
                    }
                }
            }

            item(key = "see_all_live") {
                TextButton(
                    onClick = { focusManager.clearFocus(); vm.performSearch() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                ) {
                    Text(
                        str.t("הצג את כל התוצאות והפלייליסטים עבור \"${state.searchQuery.trim()}\"", "See all results & playlists for \"${state.searchQuery.trim()}\""),
                        color = SpotifyGreen,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else if (state.hasSearched) {
            if (state.isSearching) {
                item(key = "searching_loader") {
                    Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                        SpotifyMelodyDots(isPlaying = true, tint = SpotifyGreen, dotSize = 11.dp)
                    }
                }
            } else if (state.searchMode == SearchMode.PLAYLISTS) {
                if (state.searchPlaylists.isEmpty()) {
                    item { Text(str.t("לא נמצאו פלייליסטים לחיפוש זה.", "No playlists found."), color = SpotifySubtle, modifier = Modifier.padding(20.dp)) }
                } else {
                    items(state.searchPlaylists, key = { "pl_${it.id}" }) { rp ->
                        Row(
                            modifier = Modifier.fillMaxWidth().spotifyPressClickable(pressedScale = 0.98f) { vm.openRemotePlaylist(rp) }
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(rp.artworkUrl, rp.title, Modifier.size(58.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(rp.title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(rp.subtitle, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Icon(if (isEn) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft, null, tint = SpotifyMuted)
                        }
                    }
                }
            } else {
                if (state.searchMode == SearchMode.SONGS && state.searchPlaylists.isNotEmpty()) {
                    item(key = "search_yt_pls_shelf") {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(str.t("פלייליסטים תואמים מיוטיוב", "Matching YouTube Playlists"), color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                str.t("הכל", "See all"), color = SpotifyGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.clickable { vm.setSearchMode(SearchMode.PLAYLISTS, triggerSearch = true) }
                            )
                        }
                        LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            items(state.searchPlaylists.take(8), key = { "srp_${it.id}" }) { rp ->
                                Column(modifier = Modifier.width(126.dp).spotifyPressClickable { vm.openRemotePlaylist(rp) }) {
                                    ArtworkImage(rp.artworkUrl, rp.title, Modifier.size(126.dp))
                                    Spacer(Modifier.height(5.dp))
                                    Text(rp.title, color = SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                    }
                }

                if (state.searchResults.isEmpty()) {
                    item { Text(str.t("לא נמצאו תוצאות.", "No results found."), color = SpotifySubtle, modifier = Modifier.padding(20.dp)) }
                } else {
                    items(state.searchResults, key = { "res_${it.id}" }) { track ->
                        Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                            TrackRowItem(
                                track = track,
                                isOffline = track.id in state.offlineTrackIds,
                                isDownloading = track.id in state.downloadingIds,
                                downloadPct = state.downloadProgress[track.id],
                                isPlayingCurrent = state.currentTrack?.id == track.id,
                                isAudioPlaying = state.isPlaying,
                                onClick = { vm.playTrack(track, state.searchResults) },
                                onDownloadClick = { vm.downloadOffline(track) },
                                onMoreClick = { vm.openTrackOptions(track) }
                            )
                        }
                    }
                }
            }
        } else {
            if (state.searchHistory.isNotEmpty()) {
                item(key = "recent_searches") {
                    Text(str.t("חיפושים אחרונים", "Recent Searches"), color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                    LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(state.searchHistory, key = { "hist_$it" }) { h ->
                            Row(
                                modifier = Modifier.clip(RoundedCornerShape(50)).background(SpotifyCard)
                                    .clickable { vm.performSearch(h) }.padding(horizontal = 12.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(h, color = SpotifyWhite, fontSize = 12.sp)
                                Spacer(Modifier.width(6.dp))
                                Icon(Icons.Rounded.Close, "Delete", tint = SpotifySubtle, modifier = Modifier.size(14.dp).clickable { vm.removeSearchHistoryItem(h) })
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }

            val grouped = vm.browseCategories.groupBy { it.displayGroup(isEn) }
            grouped.forEach { (groupTitle, categories) ->
                item(key = "grp_$groupTitle") {
                    Text(groupTitle, color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 17.sp, modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp))
                }
                items(categories.chunked(2), key = { pair -> "cat_${pair.first().titleEn}" }) { rowPair ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        rowPair.forEach { cat ->
                            SpotifyMoodBrowseCard(
                                category = cat,
                                isEn = isEn,
                                onClick = { vm.performSearch(cat.query, modeOverride = cat.searchMode) },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        if (rowPair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

// =====================================================================
// 3. SPOTIFY-STYLE FULL ARTIST PAGE
// =====================================================================
@Composable
fun ArtistPageScreen(artist: ArtistPageState, state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isEn = str.isEn
    val context = LocalContext.current
    val profile = state.openedArtistProfile
    val displayName = profile?.artistName?.takeIf { it.isNotBlank() } ?: artist.artistName
    val bannerUrl = profile?.bannerUrl?.takeIf { it.isNotBlank() } ?: artist.artworkUrl
    val tracks = profile?.topTracks?.takeIf { it.isNotEmpty() } ?: artist.tracks
    val isFollowed = state.followedArtists.contains(displayName) || state.followedArtists.contains(artist.artistName)
    var showAllPopular by remember(displayName) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SpotifyBlack),
        contentPadding = PaddingValues(bottom = 165.dp)
    ) {
        item(key = "artist_hero") {
            Box(modifier = Modifier.fillMaxWidth().height(300.dp)) {
                ArtworkImage(bannerUrl, displayName, Modifier.fillMaxSize().clip(RoundedCornerShape(0.dp)))
                Box(
                    modifier = Modifier.fillMaxSize().background(
                        Brush.verticalGradient(
                            0f to Color.Black.copy(alpha = 0.45f),
                            0.45f to Color.Transparent,
                            0.85f to SpotifyBlack.copy(alpha = 0.85f),
                            1f to SpotifyBlack
                        )
                    )
                )
                IconButton(
                    onClick = { vm.closeArtistPage() },
                    modifier = Modifier.align(Alignment.TopStart).statusBarsPadding().padding(10.dp)
                        .clip(CircleShape).background(Color.Black.copy(alpha = 0.55f))
                ) {
                    Icon(if (isEn) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward, str.back, tint = SpotifyWhite)
                }

                Column(modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 18.dp, vertical = 14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SpotifyVerifiedBadge(size = 18.dp)
                        Spacer(Modifier.width(6.dp))
                        Text(str.t("אמן מאומת", "Verified Artist"), color = SpotifyWhite.copy(alpha = 0.92f), fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        displayName,
                        color = SpotifyWhite,
                        fontSize = 34.sp,
                        fontWeight = FontWeight.Black,
                        lineHeight = 38.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (!profile?.subscribersLabel.isNullOrBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(profile!!.subscribersLabel, color = SpotifySubtle, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }

        item(key = "artist_actions") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { vm.toggleFollowArtist(displayName) },
                        shape = RoundedCornerShape(50),
                        border = BorderStroke(1.2.dp, if (isFollowed) SpotifyGreen else SpotifyWhite.copy(alpha = 0.65f)),
                        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp),
                        modifier = Modifier.height(36.dp)
                    ) {
                        Text(if (isFollowed) str.following else str.follow, color = if (isFollowed) SpotifyGreen else SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                    }
                    IconButton(
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("$displayName concerts tickets")}"))) } },
                        modifier = Modifier.size(36.dp)
                    ) { Icon(Icons.Rounded.Event, str.concerts, tint = SpotifySubtle) }
                    IconButton(
                        onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("$displayName official merch")}"))) } },
                        modifier = Modifier.size(36.dp)
                    ) { Icon(Icons.Rounded.ShoppingBag, str.merch, tint = SpotifySubtle) }
                }

                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    IconButton(onClick = { if (tracks.isNotEmpty()) vm.playTrack(tracks.random(), tracks.shuffled()) }) {
                        Icon(Icons.Rounded.Shuffle, str.shuffle, tint = if (state.shuffleEnabled) SpotifyGreen else SpotifySubtle, modifier = Modifier.size(26.dp))
                    }
                    Box(
                        modifier = Modifier.size(54.dp).clip(CircleShape).background(SpotifyGreen)
                            .spotifyPressClickable(pressedScale = 0.90f) { if (tracks.isNotEmpty()) vm.playTrack(tracks.first(), tracks) },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.PlayArrow, str.playAll, tint = Color.Black, modifier = Modifier.size(34.dp))
                    }
                }
            }
        }

        if (artist.isLoading && tracks.isEmpty()) {
            item(key = "artist_loading") {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    SpotifyMelodyDots(isPlaying = true, tint = SpotifyGreen, dotSize = 11.dp)
                }
            }
        }

        profile?.latestRelease?.let { latest ->
            item(key = "artist_latest") {
                Spacer(Modifier.height(10.dp))
                Text(str.t("יצא לאחרונה", "Latest Release"), color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp))
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp)
                        .clip(RoundedCornerShape(12.dp)).background(SpotifyDarkSurface)
                        .spotifyPressClickable { vm.openArtistRelease(latest, displayName) }
                        .padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkImage(latest.artworkUrl, latest.title, Modifier.size(72.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Box(Modifier.clip(RoundedCornerShape(4.dp)).background(SpotifyGreen).padding(horizontal = 6.dp, vertical = 2.dp)) {
                            Text(str.t("חדש", "NEW"), color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black)
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(latest.title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(latest.subtitle, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1)
                    }
                    Icon(if (isEn) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft, null, tint = SpotifySubtle)
                }
            }
        }

        if (tracks.isNotEmpty()) {
            item(key = "artist_pop_hdr") {
                Spacer(Modifier.height(20.dp))
                Text(str.t("פופולרי", "Popular"), color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp))
                Spacer(Modifier.height(6.dp))
            }
            val shownTracks = if (showAllPopular) tracks else tracks.take(5)
            itemsIndexed(shownTracks, key = { _, t -> "ap_${t.id}" }) { idx, track ->
                Box(modifier = Modifier.padding(horizontal = 10.dp)) {
                    TrackRowItem(
                        track = track,
                        isOffline = track.id in state.offlineTrackIds,
                        isDownloading = track.id in state.downloadingIds,
                        downloadPct = state.downloadProgress[track.id],
                        isPlayingCurrent = state.currentTrack?.id == track.id,
                        isAudioPlaying = state.isPlaying,
                        indexNumber = idx + 1,
                        onClick = { vm.playTrack(track, tracks) },
                        onDownloadClick = { vm.downloadOffline(track) },
                        onMoreClick = { vm.openTrackOptions(track) }
                    )
                }
            }
            if (tracks.size > 5) {
                item(key = "artist_pop_more") {
                    TextButton(onClick = { showAllPopular = !showAllPopular }, modifier = Modifier.padding(horizontal = 14.dp)) {
                        Text(if (showAllPopular) str.t("הצג פחות", "Show less") else str.t("הצג עוד (${tracks.size})", "See more (${tracks.size})"), color = SpotifySubtle, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (!profile?.albums.isNullOrEmpty()) {
            item(key = "artist_albums") {
                Spacer(Modifier.height(16.dp))
                Text(str.t("אלבומים", "Albums"), color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(profile!!.albums, key = { "alb_${it.id}" }) { alb ->
                        Column(modifier = Modifier.width(142.dp).spotifyPressClickable { vm.openArtistRelease(alb, displayName) }) {
                            ArtworkImage(alb.artworkUrl, alb.title, Modifier.size(142.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(alb.title, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(alb.subtitle, color = SpotifySubtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (!profile?.singles.isNullOrEmpty()) {
            item(key = "artist_singles") {
                Spacer(Modifier.height(20.dp))
                Text(str.t("סינגלים ו-EP", "Singles & EPs"), color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(profile!!.singles, key = { "sng_${it.id}" }) { sng ->
                        Column(modifier = Modifier.width(136.dp).spotifyPressClickable { vm.openArtistRelease(sng, displayName) }) {
                            ArtworkImage(sng.artworkUrl, sng.title, Modifier.size(136.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(sng.title, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(sng.subtitle, color = SpotifySubtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (!profile?.featuredPlaylists.isNullOrEmpty()) {
            item(key = "artist_feat_pls") {
                Spacer(Modifier.height(20.dp))
                Text(str.t("מופיע בפלייליסטים", "Featuring $displayName"), color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(profile!!.featuredPlaylists, key = { "afp_${it.id}" }) { rp ->
                        Column(modifier = Modifier.width(136.dp).spotifyPressClickable { vm.openRemotePlaylist(rp) }) {
                            ArtworkImage(rp.artworkUrl, rp.title, Modifier.size(136.dp))
                            Spacer(Modifier.height(6.dp))
                            Text(rp.title, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(rp.subtitle, color = SpotifySubtle, fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (!profile?.similarArtists.isNullOrEmpty()) {
            item(key = "artist_similar") {
                Spacer(Modifier.height(22.dp))
                Text(str.t("מעריצים אוהבים גם", "Fans also like"), color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp))
                Spacer(Modifier.height(10.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 18.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(profile!!.similarArtists, key = { "sim_${it.name}" }) { sa ->
                        Column(
                            modifier = Modifier.width(106.dp).spotifyPressClickable { vm.openArtistPage(sa.name, sa.artworkUrl, sa.browseId) },
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            ArtworkImage(sa.artworkUrl, sa.name, Modifier.size(100.dp).clip(CircleShape))
                            Spacer(Modifier.height(7.dp))
                            Text(sa.name, color = SpotifyWhite, fontSize = 12.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (!profile?.bio.isNullOrBlank()) {
            item(key = "artist_bio") {
                Spacer(Modifier.height(22.dp))
                Text(str.t("אודות", "About"), color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, modifier = Modifier.padding(horizontal = 18.dp))
                Spacer(Modifier.height(10.dp))
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)
                ) {
                    Text(
                        profile!!.bio,
                        color = SpotifySubtle,
                        fontSize = 13.sp,
                        lineHeight = 19.sp,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }
        }
    }
}

// =====================================================================
// 4. PLAYLIST / ALBUM DETAIL SCREEN (No "Load More" in Albums!)
// =====================================================================
@Composable
fun PlaylistDetailScreen(playlist: Playlist, state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isEn = str.isEn
    var searchInPl by remember(playlist.id) { mutableStateOf("") }
    var showRenameDialog by remember { mutableStateOf(false) }
    var renameInput by remember(playlist.name) { mutableStateOf(playlist.name) }
    var showPlOptions by remember { mutableStateOf(false) }

    val filteredTracks = remember(playlist.tracks, searchInPl) {
        if (searchInPl.isBlank()) playlist.tracks
        else playlist.tracks.filter { it.title.contains(searchInPl, true) || it.artist.contains(searchInPl, true) }
    }
    val isSmartOn = playlist.id in state.smartShuffledPlaylistIds
    val isAlbum = playlist.id.startsWith("MPRE") || playlist.id.startsWith("OLAK")
    val isBulkDownloadingThis = state.activeBulkDownloadPlaylistId == playlist.id
    val allDownloaded = playlist.tracks.isNotEmpty() && playlist.tracks.all { it.id in state.offlineTrackIds || it.source == StreamSource.LOCAL }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 165.dp)
    ) {
        item(key = "pl_top_bar") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { vm.openPlaylist(null) }) {
                    Icon(if (isEn) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward, str.back, tint = SpotifyWhite)
                }
                Row {
                    IconButton(onClick = { vm.setShowCodeFor(playlist.name to "sonify:playlist:${playlist.id}") }) {
                        Icon(Icons.Rounded.QrCode2, "QR", tint = SpotifyWhite)
                    }
                    if (playlist.isRemote && !playlist.id.startsWith("virtual_")) {
                        IconButton(onClick = { vm.saveRemotePlaylistToLibrary(playlist) }) {
                            Icon(Icons.Rounded.BookmarkAdd, "Save", tint = SpotifyGreen)
                        }
                    } else if (!playlist.isRemote) {
                        IconButton(onClick = { showPlOptions = true }) {
                            Icon(Icons.Rounded.MoreVert, "Options", tint = SpotifyWhite)
                        }
                    }
                }
            }
        }

        item(key = "pl_header_art") {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                AiPlaylistCoverArt(playlist, state.settings.aiUniqueCovers, Modifier.size(200.dp))
                Spacer(Modifier.height(14.dp))
                Text(playlist.name, color = SpotifyWhite, fontSize = 22.sp, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text("${playlist.creator} • ${playlist.tracks.size} ${str.songs}", color = SpotifySubtle, fontSize = 13.sp)
                if (playlist.description.isNotBlank()) {
                    Text(playlist.description, color = SpotifyMuted, fontSize = 12.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }

        if (playlist.tracks.isNotEmpty()) {
            item(key = "pl_search_box") {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(42.dp)
                        .clip(RoundedCornerShape(8.dp)).background(SpotifyCard).padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Search, null, tint = SpotifySubtle, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                        if (searchInPl.isEmpty()) Text(str.searchInPlaylist, color = SpotifyMuted, fontSize = 13.sp)
                        BasicTextField(
                            value = searchInPl, onValueChange = { searchInPl = it }, singleLine = true,
                            textStyle = TextStyle(color = SpotifyWhite, fontSize = 14.sp),
                            cursorBrush = SolidColor(SpotifyGreen), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (searchInPl.isNotEmpty()) {
                        IconButton(onClick = { searchInPl = "" }, modifier = Modifier.size(22.dp)) {
                            Icon(Icons.Rounded.Close, null, tint = SpotifySubtle, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            item(key = "pl_actions") {
                Spacer(Modifier.height(14.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(
                            modifier = Modifier.clip(RoundedCornerShape(50))
                                .background(
                                    when {
                                        isBulkDownloadingThis -> Color(0xFF422020)
                                        allDownloaded -> SpotifyGreen.copy(alpha = 0.2f)
                                        else -> SpotifyCard
                                    }
                                )
                                .spotifyPressClickable { vm.downloadEntirePlaylist(playlist) }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                when {
                                    isBulkDownloadingThis -> {
                                        CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                        Spacer(Modifier.width(6.dp))
                                        Text("${state.bulkDownloadDone}/${state.bulkDownloadTotal} • ${str.cancel}", color = Color(0xFFFF8A80), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    allDownloaded -> {
                                        Icon(Icons.Rounded.DownloadDone, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text(str.t("הכל באופליין", "Downloaded"), color = SpotifyGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                    else -> {
                                        Icon(Icons.Rounded.DownloadForOffline, null, tint = SpotifyWhite, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(5.dp))
                                        Text(str.downloadAll, color = SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }

                        if (!isAlbum) {
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (isSmartOn) SpotifyGreen else SpotifyCard)
                                    .spotifyPressClickable(enabled = !state.isLoadingSmartPlaylist) { vm.toggleSmartShuffleForPlaylist(playlist) }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (state.isLoadingSmartPlaylist) {
                                        CircularProgressIndicator(color = if (isSmartOn) Color.Black else SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                                    } else {
                                        Icon(Icons.Rounded.AutoAwesome, null, tint = if (isSmartOn) Color.Black else SpotifyGreen, modifier = Modifier.size(15.dp))
                                    }
                                    Spacer(Modifier.width(5.dp))
                                    Text(
                                        str.t("ערבוב חכם", "Smart Shuffle"),
                                        color = if (isSmartOn) Color.Black else SpotifyWhite,
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        IconButton(onClick = { vm.playTrack(playlist.tracks.random(), playlist.tracks.shuffled()) }) {
                            Icon(Icons.Rounded.Shuffle, str.shuffle, tint = SpotifySubtle, modifier = Modifier.size(24.dp))
                        }
                        Box(
                            modifier = Modifier.size(52.dp).clip(CircleShape).background(SpotifyGreen)
                                .spotifyPressClickable(pressedScale = 0.90f) { vm.playTrack(playlist.tracks.first(), playlist.tracks) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Rounded.PlayArrow, str.playAll, tint = Color.Black, modifier = Modifier.size(32.dp))
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
            }
        }

        if (state.isLoadingPlaylist) {
            item(key = "pl_loading") {
                Box(Modifier.fillMaxWidth().padding(36.dp), contentAlignment = Alignment.Center) {
                    SpotifyMelodyDots(isPlaying = true, tint = SpotifyGreen, dotSize = 11.dp)
                }
            }
        } else if (playlist.tracks.isEmpty()) {
            item(key = "pl_empty") {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(Icons.Rounded.QueueMusic, null, tint = SpotifyMuted, modifier = Modifier.size(52.dp))
                    Spacer(Modifier.height(10.dp))
                    Text(
                        if (isAlbum) str.t("לא נמצאו רצועות באלבום זה", "No tracks found in this album")
                        else str.t("הפלייליסט עדיין ריק", "This playlist is empty"),
                        color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp
                    )
                    if (!isAlbum) {
                        Spacer(Modifier.height(4.dp))
                        Text(str.t("חפש שירים להוסיף או תן ל-AI למלא אותו בשבילך.", "Search songs to add or let AI fill it for you."), color = SpotifySubtle, fontSize = 13.sp)
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Button(
                                onClick = { vm.openPlaylist(null); vm.selectTab(MainTab.SEARCH) },
                                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                                shape = RoundedCornerShape(50)
                            ) { Text(str.t("הוסף שירים", "Add Songs"), fontWeight = FontWeight.Bold) }
                            OutlinedButton(
                                onClick = { vm.loadMoreTracksForPlaylist(playlist) },
                                shape = RoundedCornerShape(50)
                            ) {
                                Icon(Icons.Rounded.AutoAwesome, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(5.dp))
                                Text(str.t("מלא עם AI", "Fill with AI"), color = SpotifyWhite, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            items(filteredTracks, key = { "plt_${it.id}_${it.isSmartInjected}" }) { track ->
                val isUnavailable = (state.settings.offlineMode && track.id !in state.offlineTrackIds && track.source != StreamSource.LOCAL) || track.id in state.hiddenTrackIds
                if (!isUnavailable || state.settings.showUnplayableSongs) {
                    Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                        TrackRowItem(
                            track = track,
                            isOffline = track.id in state.offlineTrackIds,
                            isDownloading = track.id in state.downloadingIds,
                            downloadPct = state.downloadProgress[track.id],
                            isPlayingCurrent = state.currentTrack?.id == track.id,
                            isAudioPlaying = state.isPlaying,
                            isUnavailable = isUnavailable,
                            onKeepSmartClick = if (track.isSmartInjected) { { vm.keepSmartTrackInPlaylist(playlist.id, track) } } else null,
                            onClick = { vm.playTrack(track, playlist.tracks) },
                            onDownloadClick = { vm.downloadOffline(track) },
                            onMoreClick = { vm.openTrackOptions(track) }
                        )
                    }
                }
            }

            if (!playlist.id.startsWith("virtual_") && !isAlbum) {
                item(key = "pl_load_more") {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        OutlinedButton(onClick = { vm.loadMoreTracksForPlaylist(playlist) }, enabled = !state.isExpandingPlaylist, shape = RoundedCornerShape(50)) {
                            if (state.isExpandingPlaylist) CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                            else Icon(Icons.Rounded.Add, null, tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(str.loadMore, color = SpotifyWhite, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }

    if (showPlOptions && !playlist.isRemote) {
        ModalBottomSheet(onDismissRequest = { showPlOptions = false }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(playlist.name, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(12.dp))
                OptionSheetRow(Icons.Rounded.Edit, str.t("שנה שם פלייליסט", "Rename Playlist")) { showPlOptions = false; showRenameDialog = true }
                OptionSheetRow(Icons.Rounded.Palette, str.t("ערוך עטיפת פלייליסט", "Edit Cover Art")) { showPlOptions = false; vm.setEditingCoverPlaylist(playlist.id) }
                OptionSheetRow(Icons.Rounded.PushPin, if (playlist.isPinned) str.t("הסר נעיצה", "Unpin") else str.t("נעץ לראש הספרייה", "Pin to Top")) { showPlOptions = false; vm.togglePinPlaylist(playlist.id) }
                OptionSheetRow(Icons.Rounded.Public, if (playlist.isPublic) str.t("הפוך לפרטי", "Make Private") else str.t("הפוך לציבורי", "Make Public")) { showPlOptions = false; vm.togglePlaylistFlag(playlist.id, "public") }
                OptionSheetRow(Icons.Rounded.Delete, str.t("מחק פלייליסט", "Delete Playlist"), tint = Color(0xFFFF6B6B)) { showPlOptions = false; vm.deletePlaylist(playlist.id) }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showRenameDialog) {
        AlertDialog(
            onDismissRequest = { showRenameDialog = false },
            containerColor = SpotifyDarkSurface,
            title = { Text(str.t("שינוי שם פלייליסט", "Rename Playlist"), color = SpotifyWhite) },
            text = { OutlinedTextField(value = renameInput, onValueChange = { renameInput = it }, singleLine = true) },
            confirmButton = {
                TextButton(onClick = { vm.renamePlaylist(playlist.id, renameInput); showRenameDialog = false }) { Text(str.save, color = SpotifyGreen) }
            },
            dismissButton = { TextButton(onClick = { showRenameDialog = false }) { Text(str.cancel, color = SpotifySubtle) } }
        )
    }
}

// =====================================================================
// 5. LIBRARY SCREEN, CREATE SHEET & TRACK OPTIONS SHEET
// =====================================================================
@Composable
fun LibraryScreen(state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isEn = str.isEn
    var showLibSearch by remember { mutableStateOf(false) }

    val sortedPlaylists = remember(state.playlists, state.librarySort, state.librarySearchQuery, state.selectedFolderId) {
        val base = state.playlists.filter { pl ->
            (state.selectedFolderId == null || pl.folderId == state.selectedFolderId) &&
                (state.librarySearchQuery.isBlank() || pl.name.contains(state.librarySearchQuery, true))
        }
        val sorted = when (state.librarySort) {
            LibrarySort.ALPHA -> base.sortedBy { it.name.lowercase() }
            LibrarySort.CREATOR -> base.sortedBy { it.creator.lowercase() }
            LibrarySort.DATE_ADDED -> base.sortedByDescending { it.createdAt }
            else -> base
        }
        sorted.sortedByDescending { it.isPinned }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding(),
        contentPadding = PaddingValues(bottom = 165.dp)
    ) {
        item(key = "lib_header") {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).clip(CircleShape).background(SpotifyGreen).clickable { vm.setShowStatsSheet(true) }, contentAlignment = Alignment.Center) {
                        Text(state.settings.jamUserDisplayName.firstOrNull()?.uppercase() ?: "S", color = Color.Black, fontWeight = FontWeight.Black)
                    }
                    Spacer(Modifier.width(10.dp))
                    Text(str.yourLibrary, color = SpotifyWhite, fontSize = 23.sp, fontWeight = FontWeight.ExtraBold)
                }
                Row {
                    IconButton(onClick = { showLibSearch = !showLibSearch }) { Icon(Icons.Rounded.Search, str.search, tint = SpotifyWhite) }
                    IconButton(onClick = { vm.toggleLibraryGrid() }) { Icon(if (state.libraryGridView) Icons.Rounded.ViewList else Icons.Rounded.GridView, "Layout", tint = SpotifyWhite) }
                    IconButton(onClick = { vm.setShowCreateSheet(true) }) { Icon(Icons.Rounded.Add, str.create, tint = SpotifyWhite) }
                }
            }
        }

        if (showLibSearch) {
            item(key = "lib_search") {
                OutlinedTextField(
                    value = state.librarySearchQuery, onValueChange = { vm.onLibrarySearchChange(it) },
                    placeholder = { Text(str.searchInLibrary) }, singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
                )
            }
        }

        item(key = "lib_filters") {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(LibraryFilter.entries) { f ->
                    val sel = state.libraryFilter == f
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).spotifyPressClickable { vm.setLibraryFilter(f) }.padding(horizontal = 13.dp, vertical = 7.dp)) {
                        Text(f.label(isEn), color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
        }

        if (state.libraryFilter == LibraryFilter.ALL || state.libraryFilter == LibraryFilter.PLAYLISTS || state.libraryFilter == LibraryFilter.OFFLINE) {
            item(key = "lib_virtual_cards") {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    LibrarySpecialRow(str.likedSongs, "${state.likedTracks.size} ${str.songs}", 0xFF503750, Icons.Rounded.Favorite) { vm.openLikedSongsPlaylist() }
                    LibrarySpecialRow(str.offlineDownloads, "${state.offlineTrackIds.size} ${str.songs}", 0xFF006450, Icons.Rounded.DownloadDone) { vm.openOfflineDownloadsPlaylist() }
                    LibrarySpecialRow(str.localFiles, "${state.localAudioTracks.size} ${str.songs}", 0xFF1E3264, Icons.Rounded.Folder) { vm.openLocalFilesPlaylist() }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if ((state.libraryFilter == LibraryFilter.ALL || state.libraryFilter == LibraryFilter.ARTISTS) && state.followedArtists.isNotEmpty()) {
            item(key = "lib_artists") {
                Text(str.t("אמנים שאתה עוקב אחריהם", "Followed Artists"), color = SpotifySubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(state.followedArtists.toList()) { artName ->
                        Column(modifier = Modifier.width(86.dp).spotifyPressClickable { vm.openArtistPage(artName) }, horizontalAlignment = Alignment.CenterHorizontally) {
                            Box(Modifier.size(72.dp).clip(CircleShape).background(SpotifyCard), contentAlignment = Alignment.Center) {
                                Text(artName.firstOrNull()?.uppercase() ?: "A", color = SpotifyGreen, fontSize = 24.sp, fontWeight = FontWeight.Black)
                            }
                            Spacer(Modifier.height(4.dp))
                            Text(artName, color = SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }
        }

        if (state.libraryGridView) {
            items(sortedPlaylists.chunked(2), key = { "grid_${it.first().id}" }) { row ->
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    row.forEach { pl ->
                        Column(modifier = Modifier.weight(1f).spotifyPressClickable { vm.openPlaylist(pl) }) {
                            AiPlaylistCoverArt(pl, state.settings.aiUniqueCovers, Modifier.fillMaxWidth().aspectRatio(1f))
                            Spacer(Modifier.height(6.dp))
                            Text(pl.name, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${pl.tracks.size} ${str.songs}", color = SpotifySubtle, fontSize = 11.sp)
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        } else {
            items(sortedPlaylists, key = { "libpl_${it.id}" }) { pl ->
                Row(
                    modifier = Modifier.fillMaxWidth().spotifyPressClickable(pressedScale = 0.98f) { vm.openPlaylist(pl) }
                        .padding(horizontal = 16.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AiPlaylistCoverArt(pl, state.settings.aiUniqueCovers, Modifier.size(58.dp))
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (pl.isPinned) Icon(Icons.Rounded.PushPin, null, tint = SpotifyGreen, modifier = Modifier.size(13.dp).padding(end = 4.dp))
                            Text(pl.name, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text("${pl.creator} • ${pl.tracks.size} ${str.songs}", color = SpotifySubtle, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun LibrarySpecialRow(title: String, subtitle: String, bgHex: Long, icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SpotifyDarkSurface)
            .spotifyPressClickable(pressedScale = 0.98f, onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(52.dp).clip(RoundedCornerShape(8.dp)).background(Color(bgHex)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = SpotifyWhite, modifier = Modifier.size(26.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(subtitle, color = SpotifySubtle, fontSize = 12.sp)
        }
    }
}

@Composable
fun CreateHubSheet(state: UiState, vm: AppViewModel) {
    val str = state.strings
    var tab by remember { mutableIntStateOf(0) }
    var plName by remember { mutableStateOf("") }
    var aiPrompt by remember { mutableStateOf("") }
    var aiCount by remember { mutableIntStateOf(25) }
    var aiLang by remember { mutableStateOf("משולב") }
    var aiEnergy by remember { mutableStateOf("מאוזן") }
    var aiEra by remember { mutableStateOf("הכל") }
    var blendFriend by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = { vm.setShowCreateSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).imePadding()) {
            when (tab) {
                0 -> {
                    Text(str.create, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                    Spacer(Modifier.height(14.dp))
                    OptionSheetRow(Icons.Rounded.QueueMusic, str.t("פלייליסט חדש", "New Playlist")) { tab = 1 }
                    OptionSheetRow(Icons.Rounded.AutoAwesome, "${str.aiStudioTitle} • ${str.t("בניית פלייליסט חכם", "Smart AI Builder")}", tint = SpotifyGreen) { tab = 2 }
                    OptionSheetRow(Icons.Rounded.GraphicEq, str.t("הפעל AI DJ אישי", "Launch Personal AI DJ"), tint = SpotifyGreen) { vm.startAiDj() }
                    OptionSheetRow(Icons.Rounded.Groups, str.t("התחל סשן Jam חי (ללא חשבון)", "Start Live Jam Room (No Account)"), tint = SpotifyGreen) { vm.startJamSession() }
                    OptionSheetRow(Icons.Rounded.Diversity2, str.t("צור Blend עם חבר", "Create Blend with Friend")) { tab = 3 }
                    OptionSheetRow(Icons.Rounded.FileDownload, str.importPlaylist) { vm.setShowCreateSheet(false); vm.setShowImportSheet(true) }
                }
                1 -> {
                    Text(str.t("פלייליסט חדש", "New Playlist"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = plName, onValueChange = { plName = it }, label = { Text(str.t("שם הפלייליסט", "Playlist Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.createPlaylist(plName) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                        Text(str.create, fontWeight = FontWeight.Bold)
                    }
                }
                2 -> {
                    Text(str.aiStudioTitle, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold, color = SpotifyGreen)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = aiPrompt, onValueChange = { aiPrompt = it }, label = { Text(str.t("תאר סגנון, אווירה, אמן או פעילות...", "Describe vibe, genre, artist or activity...")) }, minLines = 2, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Text("${str.t("מספר שירים:", "Track count:")} $aiCount", color = SpotifySubtle, fontSize = 12.sp)
                    Slider(value = aiCount.toFloat(), onValueChange = { aiCount = it.toInt() }, valueRange = 10f..50f, colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("משולב", "עברית", "אנגלית")) { l ->
                            FilterChip(selected = aiLang == l, onClick = { aiLang = l }, label = { Text(l) })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("מאוזן", "אנרגטי", "רגוע")) { e ->
                            FilterChip(selected = aiEnergy == e, onClick = { aiEnergy = e }, label = { Text(e) })
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(listOf("הכל", "חדש (2024+)", "2010s", "קלאסיקות")) { er ->
                            FilterChip(selected = aiEra == er, onClick = { aiEra = er }, label = { Text(er) })
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Button(
                        onClick = { vm.createAiPlaylist(AiPlaylistConfig(aiPrompt, aiCount, aiLang, aiEnergy, aiEra)) },
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(str.t("בנה פלייליסט עכשיו", "Generate Playlist"), fontWeight = FontWeight.ExtraBold) }
                }
                3 -> {
                    Text(str.t("צור Blend עם חבר", "Create Blend"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(value = blendFriend, onValueChange = { blendFriend = it }, label = { Text(str.t("שם החבר", "Friend Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { vm.createBlend(blendFriend) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                        Text(str.t("צור Blend", "Create Blend"), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun TrackOptionsBottomSheet(track: Track, state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isLiked = state.likedTracks.any { it.id == track.id }
    val isHidden = track.id in state.hiddenTrackIds
    var showPlPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = { vm.openTrackOptions(null) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ArtworkImage(track.artworkUrl, track.title, Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(track.title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = SpotifySubtle, fontSize = 13.sp, maxLines = 1)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SpotifyCard)

            if (showPlPicker) {
                var selectedIds by remember(track.id) { mutableStateOf(state.playlists.filter { pl -> pl.tracks.any { it.id == track.id } }.map { it.id }.toSet()) }
                state.playlists.forEach { pl ->
                    val checked = pl.id in selectedIds
                    Row(
                        modifier = Modifier.fillMaxWidth().clickable { selectedIds = if (checked) selectedIds - pl.id else selectedIds + pl.id }.padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(checked = checked, onCheckedChange = { selectedIds = if (it) selectedIds + pl.id else selectedIds - pl.id }, colors = CheckboxDefaults.colors(checkedColor = SpotifyGreen))
                        Text(pl.name, color = SpotifyWhite, fontSize = 14.sp)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = { vm.syncTrackPlaylists(selectedIds, track) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                    Text(str.save, fontWeight = FontWeight.Bold)
                }
            } else {
                OptionSheetRow(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (isLiked) str.t("הסר משירים שאהבת", "Remove from Liked") else str.t("שמור בשירים שאהבת", "Save to Liked"), tint = if (isLiked) SpotifyGreen else SpotifyWhite) { vm.toggleLike(track); vm.openTrackOptions(null) }
                OptionSheetRow(Icons.Rounded.PlaylistAdd, str.t("הוסף / הסר מפלייליסטים", "Add to Playlists")) { showPlPicker = true }
                OptionSheetRow(Icons.Rounded.QueueMusic, str.t("הוסף לתור ההשמעה", "Add to Queue")) { vm.addToQueue(track); vm.openTrackOptions(null) }
                OptionSheetRow(Icons.Rounded.Person, str.t("עבור לדף האמן", "Go to Artist")) { vm.openTrackOptions(null); vm.openArtistPage(track.artist, track.artworkUrl) }
                OptionSheetRow(Icons.Rounded.Radio, str.startRadio) { vm.startSongOrArtistRadio(track) }
                OptionSheetRow(Icons.Rounded.Download, str.t("הורד להאזנה אופליין", "Download Offline")) { vm.downloadOffline(track); vm.openTrackOptions(null) }
                OptionSheetRow(Icons.Rounded.QrCode2, str.t("קוד QR ושיתוף שיר", "QR Code & Share")) { vm.openTrackOptions(null); vm.setShowCodeFor(track.title to SonifyLinkCodec.buildSmartTrackLink(track, 0L)) }
                OptionSheetRow(Icons.Rounded.VisibilityOff, if (isHidden) str.t("הצג שיר מחדש", "Unhide Song") else str.t("הסתר שיר זה", "Hide Song"), tint = Color(0xFFFF8A80)) { vm.toggleHideTrack(track) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun OptionSheetRow(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, tint: Color = SpotifyWhite, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(vertical = 11.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, color = tint, fontSize = 15.sp, fontWeight = FontWeight.Medium)
    }
}
