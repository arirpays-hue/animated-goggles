@file:kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.sonify.app

import android.content.Intent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@Composable
fun FloatingMiniPlayer(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    val track = state.currentTrack ?: return
    val isLiked = state.likedTracks.any { it.id == track.id }
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val accent by animateColorAsState(Color(state.dynamicAccentColor), animationSpec = tween(450), label = "mini_accent")
    var dragAccum by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(accent)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clickable { vm.setShowNowPlaying(true) }
                    .pointerInput(track.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragAccum > 70f) vm.skipPrevious()
                                else if (dragAccum < -70f) vm.skipNext()
                                dragAccum = 0f
                            },
                            onHorizontalDrag = { _, dragAmount -> dragAccum += dragAmount }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkImage(track.artworkUrl, null, Modifier.size(42.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = track.title, color = SpotifyWhite, fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.basicMarquee()
                    )
                    Text(
                        text = if (state.isVideoMode) "${track.artist} • קליפ" else track.artist,
                        color = SpotifyWhite.copy(alpha = 0.78f), fontSize = 11.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(onClick = { vm.setShowDevicesSheet(true) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Devices, contentDescription = "התקן שמע", tint = SpotifyWhite.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { vm.toggleLike(track) }, modifier = Modifier.size(36.dp)) {
                Icon(
                    if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                    contentDescription = "ספרייה", tint = if (isLiked) SpotifyGreen else SpotifyWhite, modifier = Modifier.size(22.dp)
                )
            }
            IconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(38.dp)) {
                if (state.isBusyLoading) {
                    CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "נגן/השהה", tint = SpotifyWhite, modifier = Modifier.size(28.dp)
                    )
                }
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth().height(2.5.dp),
                color = SpotifyWhite,
                trackColor = SpotifyWhite.copy(alpha = 0.25f)
            )
        }
    }
}

@Composable
fun SpotifySeekBar(
    progress: Float,
    onScrubChange: (Float) -> Unit,
    onScrubFinished: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var currentVal by remember(progress) { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset ->
                    val frac = (offset.x / widthPx).coerceIn(0f, 1f)
                    currentVal = frac
                    onScrubFinished(frac)
                }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        val frac = (change.position.x / widthPx).coerceIn(0f, 1f)
                        currentVal = frac
                        onScrubChange(frac)
                    },
                    onDragEnd = { onScrubFinished(currentVal) }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(SpotifyWhite.copy(alpha = 0.28f))
        )
        Box(
            modifier = Modifier
                .fillMaxWidth(currentVal.coerceIn(0f, 1f))
                .height(4.dp)
                .clip(RoundedCornerShape(50))
                .background(SpotifyWhite)
        )
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val thumbSize = 13.dp
            val offsetX = (maxWidth - thumbSize) * currentVal.coerceIn(0f, 1f)
            Box(
                modifier = Modifier
                    .offset(x = offsetX)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(SpotifyWhite)
            )
        }
    }
}

@Composable
fun NowPlayingScreen(state: UiState, vm: AppViewModel) {
    val track = state.currentTrack ?: return
    val context = LocalContext.current
    val isLiked = state.likedTracks.any { it.id == track.id }
    val isOffline = state.offlineTrackIds.contains(track.id)
    val accent by animateColorAsState(Color(state.dynamicAccentColor), animationSpec = tween(550), label = "now_accent")

    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showFullLyrics by remember { mutableStateOf(false) }

    var isScrubbing by remember(track.id) { mutableStateOf(false) }
    var scrubFraction by remember(track.id) { mutableFloatStateOf(0f) }

    val scrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent, SpotifyBlack, SpotifyBlack)))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(scrollState)
                .padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = { vm.setShowNowPlaying(false) }) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "מזער", tint = SpotifyWhite, modifier = Modifier.size(30.dp))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("מנגן כעת", color = SpotifyWhite.copy(alpha = 0.72f), fontSize = 11.sp)
                    Text(
                        text = state.resolvedStreams?.codecLabel ?: state.settings.streamSource.label.substringBefore(" ("),
                        color = if (state.resolvedStreams?.isLossless == true) SpotifyGreen else SpotifyWhite,
                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                    )
                }
                Row {
                    IconButton(onClick = { showSleepSheet = true }) {
                        Icon(
                            Icons.Rounded.Bedtime, contentDescription = "טיימר שינה",
                            tint = if (state.sleepMinutesLeft != null || state.sleepEndOfTrack) SpotifyGreen else SpotifyWhite
                        )
                    }
                    IconButton(onClick = { vm.openTrackOptions(track) }) {
                        Icon(Icons.Rounded.MoreVert, contentDescription = "אפשרויות", tint = SpotifyWhite)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

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
                        ArtworkImage(track.artworkUrl, track.title, Modifier.fillMaxSize())
                    }
                }

                if (track.source == StreamSource.YOUTUBE && state.resolvedStreams?.hasVideoClip == true) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(12.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color.Black.copy(alpha = 0.8f))
                            .clickable { vm.toggleVideoClipMode() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            if (state.isVideoMode) Icons.Rounded.MusicNote else Icons.Rounded.OndemandVideo,
                            contentDescription = null, tint = SpotifyGreen, modifier = Modifier.size(18.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = if (state.isVideoMode) "מעבר לאודיו" else "מעבר לקליפ",
                            color = SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }
            }

            Spacer(Modifier.height(22.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(
                        text = track.title, color = SpotifyWhite, fontSize = 20.sp,
                        fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = track.artist, color = SpotifyWhite.copy(alpha = 0.75f),
                        fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                IconButton(onClick = { vm.downloadOffline(track) }) {
                    if (state.downloadingIds.contains(track.id)) {
                        CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(
                            if (isOffline) Icons.Rounded.DownloadDone else Icons.Rounded.Download,
                            contentDescription = "אופליין",
                            tint = if (isOffline) SpotifyGreen else SpotifyWhite.copy(alpha = 0.82f)
                        )
                    }
                }
                IconButton(onClick = { vm.toggleLike(track) }) {
                    Icon(
                        if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                        contentDescription = "ספרייה",
                        tint = if (isLiked) SpotifyGreen else SpotifyWhite, modifier = Modifier.size(28.dp)
                    )
                }
            }

            Spacer(Modifier.height(14.dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val duration = state.durationMs.coerceAtLeast(1L)
                val progressFrac = if (isScrubbing) scrubFraction else (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val shownPos = (progressFrac * duration).toLong()

                SpotifySeekBar(
                    progress = progressFrac,
                    onScrubChange = { isScrubbing = true; scrubFraction = it },
                    onScrubFinished = { frac ->
                        isScrubbing = false
                        vm.seekTo((frac * duration).toLong())
                    }
                )
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(shownPos), color = SpotifyWhite.copy(alpha = 0.75f), fontSize = 11.sp)
                    Text(formatTime(state.durationMs), color = SpotifyWhite.copy(alpha = 0.75f), fontSize = 11.sp)
                }

                Spacer(Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { vm.cycleShuffleMode() }) {
                        Icon(
                            if (state.smartShuffleEnabled) Icons.Rounded.AutoAwesome else Icons.Rounded.Shuffle,
                            contentDescription = "ערבוב",
                            tint = if (state.shuffleEnabled) SpotifyGreen else SpotifyWhite.copy(alpha = 0.75f),
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
                        if (state.isBusyLoading) {
                            CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        } else {
                            Icon(
                                if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                contentDescription = "נגן/השהה", tint = Color.Black, modifier = Modifier.size(38.dp)
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
                            tint = if (state.repeatMode != RepeatMode.OFF) SpotifyGreen else SpotifyWhite.copy(alpha = 0.75f),
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = { vm.setShowDevicesSheet(true) }) {
                    Icon(Icons.Rounded.Devices, contentDescription = "התקן", tint = SpotifyWhite.copy(alpha = 0.85f))
                }
                Row {
                    IconButton(onClick = {
                        val sendIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, "מאזין עכשיו ל-${track.title} - ${track.artist}")
                        }
                        context.startActivity(Intent.createChooser(sendIntent, "שתף שיר"))
                    }) { Icon(Icons.Rounded.Share, contentDescription = "שיתוף", tint = SpotifyWhite.copy(alpha = 0.85f)) }
                    IconButton(onClick = { showQueueSheet = true }) {
                        Icon(Icons.Rounded.QueueMusic, contentDescription = "תור", tint = SpotifyWhite.copy(alpha = 0.85f))
                    }
                }
            }

            Spacer(Modifier.height(18.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.9f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("מילים מסונכרנות", color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        TextButton(onClick = { showFullLyrics = true }) { Text("מסך מלא", color = SpotifyWhite, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (state.isLoadingLyrics) {
                        Text("טוען מילים...", color = SpotifyWhite.copy(alpha = 0.75f))
                    } else if (state.lyrics.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text("לא נמצאו מילים אוטומטית לשיר זה.", color = SpotifyWhite.copy(alpha = 0.8f), fontSize = 13.sp)
                            TextButton(onClick = { vm.retryLyrics() }) { Text("נסה שוב", color = SpotifyGreen) }
                        }
                    } else {
                        val activeIdx = state.lyrics.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
                        val slice = state.lyrics.subList((activeIdx - 1).coerceAtLeast(0), (activeIdx + 5).coerceAtMost(state.lyrics.size))
                        slice.forEach { line ->
                            val isActive = state.lyrics.getOrNull(activeIdx) == line
                            Text(
                                text = line.text,
                                color = if (isActive) SpotifyWhite else SpotifyWhite.copy(alpha = 0.55f),
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                fontSize = if (isActive) 18.sp else 15.sp,
                                modifier = Modifier.fillMaxWidth().clickable { vm.seekTo(line.timeMs) }.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("מידע וקרדיטים", color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("אמן ראשי: ${track.artist}", color = SpotifySubtle, fontSize = 14.sp)
                    if (track.album.isNotBlank()) Text("אלבום: ${track.album}", color = SpotifySubtle, fontSize = 13.sp)
                    Text("מקור הזרמה: ${track.source.label}", color = SpotifySubtle, fontSize = 13.sp)
                    Text("איכות פעילה: ${state.resolvedStreams?.codecLabel ?: "אוטומטית"}", color = SpotifyGreen, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(40.dp))
        }
    }

    if (showQueueSheet) {
        ModalBottomSheet(onDismissRequest = { showQueueSheet = false }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("תור ההשמעה (${state.queue.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(state.queue) { idx, item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().clickable { vm.playTrack(item, state.queue) }.padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(if (idx == state.currentIndex) "▶" else "${idx + 1}", color = if (idx == state.currentIndex) SpotifyGreen else SpotifySubtle, modifier = Modifier.width(24.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = if (idx == state.currentIndex) SpotifyGreen else SpotifyWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (item.isSmartInjected) "${item.artist} • Smart Shuffle" else item.artist, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1)
                            }
                            IconButton(onClick = { vm.moveQueueItem(idx, idx - 1) }, enabled = idx > 0) {
                                Icon(Icons.Rounded.ArrowUpward, contentDescription = "למעלה", tint = SpotifySubtle, modifier = Modifier.size(18.dp))
                            }
                            IconButton(onClick = { vm.moveQueueItem(idx, idx + 1) }, enabled = idx < state.queue.lastIndex) {
                                Icon(Icons.Rounded.ArrowDownward, contentDescription = "למטה", tint = SpotifySubtle, modifier = Modifier.size(18.dp))
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

    if (showSleepSheet) {
        ModalBottomSheet(onDismissRequest = { showSleepSheet = false }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text("טיימר שינה (עם Fade-out הדרגתי)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                listOf(5, 10, 15, 30, 45, 60).forEach { min ->
                    Text("$min דקות", color = SpotifyWhite, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { vm.setSleepTimer(min); showSleepSheet = false }.padding(vertical = 10.dp))
                }
                Text("בסיום השיר הנוכחי", color = SpotifyGreen, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { vm.setSleepEndOfTrack(); showSleepSheet = false }.padding(vertical = 10.dp))
                if (state.sleepMinutesLeft != null || state.sleepEndOfTrack) {
                    Text("בטל טיימר שינה", color = Color(0xFFFF6B6B), fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { vm.setSleepTimer(0); showSleepSheet = false }.padding(vertical = 10.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showFullLyrics) {
        var manualQuery by remember { mutableStateOf("${track.artist} ${track.title}") }
        ModalBottomSheet(onDismissRequest = { showFullLyrics = false }, containerColor = accent, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                Text("מילים מסונכרנות", fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Spacer(Modifier.height(10.dp))
                if (state.lyrics.isEmpty()) {
                    Text("חפש מילים ידנית לפי שם השיר:", color = SpotifyWhite.copy(alpha = 0.85f), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(value = manualQuery, onValueChange = { manualQuery = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Button(
                        onClick = { vm.retryLyrics(manualQuery) },
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                        modifier = Modifier.fillMaxWidth()
                    ) { Text("חפש מילים", fontWeight = FontWeight.Bold) }
                    Spacer(Modifier.height(24.dp))
                } else {
                    val activeIdx = state.lyrics.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
                    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                        itemsIndexed(state.lyrics) { idx, line ->
                            Text(
                                text = line.text,
                                color = if (idx == activeIdx) SpotifyWhite else SpotifyWhite.copy(alpha = 0.45f),
                                fontSize = if (idx == activeIdx) 22.sp else 18.sp, fontWeight = FontWeight.Bold,
                                modifier = Modifier.fillMaxWidth().clickable { vm.seekTo(line.timeMs) }.padding(vertical = 8.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DevicesBottomSheet(state: UiState, vm: AppViewModel) {
    val devices = remember { vm.audioEngine.getOutputDevices() }
    ModalBottomSheet(onDismissRequest = { vm.setShowDevicesSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text("התקני שמע ו-Connect", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            devices.forEach { d ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                        .clickable { vm.audioEngine.openSystemAudioSettings(); vm.setShowDevicesSheet(false) }
                        .padding(vertical = 10.dp, horizontal = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.Headphones, contentDescription = null, tint = if (d.isActive) SpotifyGreen else SpotifySubtle)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(d.name, color = if (d.isActive) SpotifyGreen else SpotifyWhite, fontWeight = FontWeight.SemiBold)
                        Text(d.typeLabel, color = SpotifySubtle, fontSize = 12.sp)
                    }
                    if (d.isActive) Icon(Icons.Rounded.Check, contentDescription = null, tint = SpotifyGreen)
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(onClick = { vm.audioEngine.openSystemAudioSettings() }, modifier = Modifier.fillMaxWidth()) {
                Text("החלף אוזניות / Bluetooth בהגדרות", color = SpotifyWhite)
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), color = SpotifyCard)
            Button(
                onClick = { vm.toggleJamSession(); vm.setShowDevicesSheet(false) },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (state.jamState.active) "Jam פעיל (${state.jamState.code}) — לסיום" else "התחל סשן Jam משותף", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(16.dp))
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
