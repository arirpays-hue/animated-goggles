@file:kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.sonify.app

import android.content.Intent

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage

@Composable
fun FloatingMiniPlayer(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    val track = state.currentTrack ?: return
    val isLiked = state.likedTracks.any { it.id == track.id }
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    var dragAccum by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color(0xFF23322D))
            .pointerInput(track.id) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        if (dragAccum > 80f) vm.skipPrevious()
                        else if (dragAccum < -80f) vm.skipNext()
                        dragAccum = 0f
                    },
                    onHorizontalDrag = { _, dragAmount -> dragAccum += dragAmount }
                )
            }
            .clickable { vm.setShowNowPlaying(true) }
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = track.artworkUrl,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.size(42.dp).clip(RoundedCornerShape(6.dp)).background(SpotifyCard)
            )
            Spacer(Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = track.title,
                    color = SpotifyWhite,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee()
                )
                Text(
                    text = if (state.isVideoMode) "${track.artist} • קליפ" else track.artist,
                    color = SpotifySubtle,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = { vm.setShowNowPlaying(true) }, modifier = Modifier.size(34.dp)) {
                Icon(Icons.Rounded.Devices, contentDescription = "התקן שמע", tint = SpotifySubtle, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { vm.toggleLike(track) }, modifier = Modifier.size(34.dp)) {
                Icon(
                    if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                    contentDescription = "ספרייה",
                    tint = if (isLiked) SpotifyGreen else SpotifyWhite,
                    modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(36.dp)) {
                if (state.isBuffering) {
                    CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "נגן/השהה",
                        tint = SpotifyWhite,
                        modifier = Modifier.size(26.dp)
                    )
                }
            }
        }
        LinearProgressIndicator(
            progress = { progress },
            modifier = Modifier.fillMaxWidth().height(2.5.dp),
            color = SpotifyWhite,
            trackColor = SpotifyWhite.copy(alpha = 0.2f)
        )
    }
}

@Composable
fun NowPlayingScreen(state: UiState, vm: AppViewModel) {
    val track = state.currentTrack ?: return
    val context = LocalContext.current
    val isLiked = state.likedTracks.any { it.id == track.id }
    val isOffline = state.offlineTrackIds.contains(track.id)

    var showQueueSheet by remember { mutableStateOf(false) }
    var showDevicesSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showFullLyrics by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFF1F3B33), SpotifyBlack, SpotifyBlack)
                )
            )
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            // Top Bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { vm.setShowNowPlaying(false) }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "מזער", tint = SpotifyWhite, modifier = Modifier.size(30.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("מנגן כעת", color = SpotifySubtle, fontSize = 11.sp)
                    Text(
                        text = state.resolvedStreams?.codecLabel ?: state.settings.streamSource.label.substringBefore(" "),
                        color = if (state.resolvedStreams?.isLossless == true) SpotifyGreen else SpotifyWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(onClick = { showSleepSheet = true }) {
                    Icon(
                        Icons.Rounded.Bedtime,
                        contentDescription = "טיימר שינה",
                        tint = if (state.sleepMinutesLeft != null || state.sleepEndOfTrack) SpotifyGreen else SpotifyWhite
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Central Visual Area: Album Art / Canvas OR Live Video Clip!
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(if (state.isVideoMode) 16f / 10f else 1f)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                AnimatedContent(targetState = state.isVideoMode, label = "audio_video_switch") { videoActive ->
                    if (videoActive) {
                        DisposableEffect(Unit) {
                            onDispose { }
                        }
                        AndroidView(
                            factory = { ctx ->
                                PlayerView(ctx).apply {
                                    player = vm.audioEngine.player
                                    useController = false
                                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                                }
                            },
                            update = { view -> view.player = vm.audioEngine.player },
                            onRelease = { view -> view.player = null },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AsyncImage(
                            model = track.artworkUrl,
                            contentDescription = track.title,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Spotify-style floating "Switch to Video" / "Switch to Audio" pill button
                if (track.source == StreamSource.YOUTUBE && !state.resolvedStreams?.videoUrl.isNullOrBlank()) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.78f))
                            .clickable { vm.toggleVideoClipMode() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (state.isVideoMode) Icons.Rounded.MusicNote else Icons.Rounded.OndemandVideo,
                            contentDescription = null,
                            tint = SpotifyGreen,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (state.isVideoMode) "מעבר לאודיו" else "מעבר לקליפ",
                            color = SpotifyWhite,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            // Song Title + Artist + Add to Library / Offline
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title,
                        color = SpotifyWhite,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Text(
                        text = track.artist,
                        color = SpotifySubtle,
                        fontSize = 15.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { vm.downloadOffline(track) }) {
                    Icon(
                        if (isOffline) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                        contentDescription = "הורדה לאופליין",
                        tint = if (isOffline) SpotifyGreen else SpotifySubtle
                    )
                }
                IconButton(onClick = { vm.toggleLike(track) }) {
                    Icon(
                        if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                        contentDescription = "הוספה לספרייה",
                        tint = if (isLiked) SpotifyGreen else SpotifyWhite,
                        modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(10.dp))

            // Interactive Seekbar
            val duration = state.durationMs.coerceAtLeast(1L)
            Slider(
                value = state.positionMs.toFloat().coerceIn(0f, duration.toFloat()),
                onValueChange = { vm.seekTo(it.toLong()) },
                valueRange = 0f..duration.toFloat(),
                colors = SliderDefaults.colors(
                    thumbColor = SpotifyWhite,
                    activeTrackColor = SpotifyWhite,
                    inactiveTrackColor = SpotifyWhite.copy(alpha = 0.25f)
                )
            )
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(formatTime(state.positionMs), color = SpotifySubtle, fontSize = 11.sp)
                Text(formatTime(state.durationMs), color = SpotifySubtle, fontSize = 11.sp)
            }

            Spacer(Modifier.height(8.dp))

            // Main Playback Controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { vm.cycleShuffleMode() }) {
                    Icon(
                        if (state.smartShuffleEnabled) Icons.Rounded.AutoAwesome else Icons.Rounded.Shuffle,
                        contentDescription = "ערבוב",
                        tint = if (state.shuffleEnabled) SpotifyGreen else SpotifySubtle,
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = { vm.skipPrevious() }) {
                    Icon(Icons.Rounded.SkipPrevious, contentDescription = "קודם", tint = SpotifyWhite, modifier = Modifier.size(38.dp))
                }
                Box(
                    modifier = Modifier
                        .size(66.dp)
                        .clip(CircleShape)
                        .background(SpotifyWhite)
                        .clickable { vm.togglePlayPause() },
                    contentAlignment = Alignment.Center
                ) {
                    if (state.isBuffering) {
                        CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            contentDescription = "נגן/השהה",
                            tint = Color.Black,
                            modifier = Modifier.size(38.dp)
                        )
                    }
                }
                IconButton(onClick = { vm.skipNext() }) {
                    Icon(Icons.Rounded.SkipNext, contentDescription = "הבא", tint = SpotifyWhite, modifier = Modifier.size(38.dp))
                }
                IconButton(onClick = { vm.cycleRepeatMode() }) {
                    Icon(
                        if (state.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        contentDescription = "חזרה",
                        tint = if (state.repeatMode != RepeatMode.OFF) SpotifyGreen else SpotifySubtle,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            // Secondary Row: Connect/Devices, Share, Queue
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { showDevicesSheet = true }) {
                    Icon(Icons.Rounded.Devices, contentDescription = "בחירת התקן", tint = SpotifySubtle)
                }
                Row {
                    IconButton(onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "מאזין עכשיו ל-${track.title} - ${track.artist}")
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "שתף שיר"))
                    }) {
                        Icon(Icons.Rounded.Share, contentDescription = "שיתוף", tint = SpotifySubtle)
                    }
                    IconButton(onClick = { showQueueSheet = true }) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "תור השמעה", tint = SpotifySubtle)
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            // Synchronized Lyrics Card (Scroll Down)
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF2A4D43))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("מילים מסונכרנות", color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        TextButton(onClick = { showFullLyrics = true }) {
                            Text("מסך מלא", color = SpotifyWhite, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (state.isLoadingLyrics) {
                        Text("טוען מילים...", color = SpotifyWhite.copy(alpha = 0.7f))
                    } else if (state.lyrics.isEmpty()) {
                        Text("לא נמצאו מילים מסונכרנות לשיר זה.", color = SpotifyWhite.copy(alpha = 0.75f), fontSize = 14.sp)
                    } else {
                        val activeIdx = state.lyrics.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
                        val slice = state.lyrics.subList(
                            (activeIdx - 1).coerceAtLeast(0),
                            (activeIdx + 5).coerceAtMost(state.lyrics.size)
                        )
                        slice.forEach { line ->
                            val isActive = state.lyrics.getOrNull(activeIdx) == line
                            Text(
                                text = line.text,
                                color = if (isActive) SpotifyWhite else SpotifyWhite.copy(alpha = 0.5f),
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                fontSize = if (isActive) 18.sp else 15.sp,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { vm.seekTo(line.timeMs) }
                                    .padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // Artist Info & Production Credits Card
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("מידע וקרדיטים", color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("אמן ראשי: ${track.artist}", color = SpotifySubtle, fontSize = 14.sp)
                    Text("מקור הזרמה: ${track.source.label}", color = SpotifySubtle, fontSize = 13.sp)
                    Text("איכות פעילה: ${state.resolvedStreams?.codecLabel ?: "אוטומטית"}", color = SpotifyGreen, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(32.dp))
        }
    }

    // Queue Sheet (Reorderable)
    if (showQueueSheet) {
        ModalBottomSheet(
            onDismissRequest = { showQueueSheet = false },
            containerColor = SpotifyDarkSurface,
            contentColor = SpotifyWhite
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("תור ההשמעה (${state.queue.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(state.queue) { idx, item ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { vm.playTrack(item, state.queue) }
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = if (idx == state.currentIndex) "▶" else "${idx + 1}",
                                color = if (idx == state.currentIndex) SpotifyGreen else SpotifySubtle,
                                modifier = Modifier.width(24.dp)
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    item.title,
                                    color = if (idx == state.currentIndex) SpotifyGreen else SpotifyWhite,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    if (item.isSmartInjected) "${item.artist} • Smart Shuffle" else item.artist,
                                    color = SpotifySubtle,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                            IconButton(onClick = { vm.moveQueueItem(idx, idx - 1) }, enabled = idx > 0) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "הזז למעלה", tint = SpotifySubtle, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { vm.moveQueueItem(idx, idx + 1) }, enabled = idx < state.queue.lastIndex) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "הזז למטה", tint = SpotifySubtle, modifier = Modifier.size(18.dp))
                            }
                            if (idx != state.currentIndex) {
                                IconButton(onClick = { vm.removeFromQueue(idx) }) {
                                    Icon(Icons.Rounded.Delete, contentDescription = "הסר", tint = SpotifySubtle, modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }

    // Audio Output / Connect Sheet
    if (showDevicesSheet) {
        val devices = remember { vm.audioEngine.getOutputDevices() }
        ModalBottomSheet(
            onDismissRequest = { showDevicesSheet = false },
            containerColor = SpotifyDarkSurface,
            contentColor = SpotifyWhite
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("בחירת התקן שמע ו-Connect", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                devices.forEach { d ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Headphones, contentDescription = null, tint = if (d.isActive) SpotifyGreen else SpotifySubtle)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(d.name, color = if (d.isActive) SpotifyGreen else SpotifyWhite, fontWeight = FontWeight.SemiBold)
                            Text(d.typeLabel, color = SpotifySubtle, fontSize = 12.sp)
                        }
                    }
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = SpotifyCard)
                Button(
                    onClick = { vm.toggleJamSession(); showDevicesSheet = false },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.jamState.active) "Jam פעיל (${state.jamState.code}) — לחץ לסיום" else "התחל סשן Jam משותף")
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Sleep Timer Sheet
    if (showSleepSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSleepSheet = false },
            containerColor = SpotifyDarkSurface,
            contentColor = SpotifyWhite
        ) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("טיימר שינה (עם Fade-out הדרגתי)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                listOf(5, 10, 15, 30, 45, 60).forEach { min ->
                    Text(
                        "$min דקות",
                        color = SpotifyWhite,
                        fontSize = 16.sp,
                        modifier = Modifier.fillMaxWidth().clickable { vm.setSleepTimer(min); showSleepSheet = false }.padding(vertical = 10.dp)
                    )
                }
                Text(
                    "בסיום השיר הנוכחי",
                    color = SpotifyGreen,
                    fontSize = 16.sp,
                    modifier = Modifier.fillMaxWidth().clickable { vm.setSleepEndOfTrack(); showSleepSheet = false }.padding(vertical = 10.dp)
                )
                if (state.sleepMinutesLeft != null || state.sleepEndOfTrack) {
                    Text(
                        "בטל טיימר שינה",
                        color = Color(0xFFFF6B6B),
                        fontSize = 15.sp,
                        modifier = Modifier.fillMaxWidth().clickable { vm.setSleepTimer(0); showSleepSheet = false }.padding(vertical = 10.dp)
                    )
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Fullscreen Synced Lyrics Sheet
    if (showFullLyrics) {
        ModalBottomSheet(
            onDismissRequest = { showFullLyrics = false },
            containerColor = Color(0xFF1F3B33),
            contentColor = SpotifyWhite
        ) {
            val activeIdx = state.lyrics.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
            LazyColumn(
                modifier = Modifier.fillMaxWidth().heightIn(max = 560.dp).padding(horizontal = 20.dp),
                contentPadding = PaddingValues(bottom = 32.dp)
            ) {
                itemsIndexed(state.lyrics) { idx, line ->
                    Text(
                        text = line.text,
                        color = if (idx == activeIdx) SpotifyWhite else SpotifyWhite.copy(alpha = 0.45f),
                        fontSize = if (idx == activeIdx) 22.sp else 18.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.seekTo(line.timeMs) }
                            .padding(vertical = 8.dp)
                    )
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
