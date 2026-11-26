@file:kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.sonify.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.FrameLayout
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay

@Composable
fun FloatingMiniPlayer(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    val track = state.currentTrack ?: return
    val isLiked = state.likedTracks.any { it.id == track.id }
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val accent by animateColorAsState(Color(state.dynamicAccentColor), animationSpec = tween(450), label = "mini_accent")
    var dragAccum by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(10.dp)).background(accent)
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier.weight(1f).clickable { vm.setShowNowPlaying(true) }
                    .pointerInput(track.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragAccum > 70f) vm.skipPrevious()
                                else if (dragAccum < -70f) vm.skipNext()
                                dragAccum = 0f
                            },
                            onHorizontalDrag = { _, d -> dragAccum += d }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                ArtworkImage(track.artworkUrl, null, Modifier.size(42.dp))
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(track.title, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = Modifier.basicMarquee())
                    Text(
                        text = buildString {
                            append(track.artist)
                            if (state.isVideoMode) append(" • Video")
                            if (state.jamState.active) append(" • Jam")
                        },
                        color = SpotifyWhite.copy(alpha = 0.78f), fontSize = 11.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
            }
            IconButton(onClick = { vm.setShowDevicesSheet(true) }, modifier = Modifier.size(36.dp)) {
                Icon(Icons.Rounded.Devices, "Devices", tint = if (state.jamState.active) SpotifyGreen else SpotifyWhite.copy(alpha = 0.85f), modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = { vm.toggleLike(track) }, modifier = Modifier.size(36.dp)) {
                Icon(if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, "Save", tint = if (isLiked) SpotifyGreen else SpotifyWhite, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(38.dp)) {
                if (state.isBusyLoading) CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = SpotifyWhite, modifier = Modifier.size(28.dp))
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.5.dp), color = SpotifyWhite, trackColor = SpotifyWhite.copy(alpha = 0.25f))
        }
    }
}

@Composable
fun SpotifySeekBar(progress: Float, onScrubChange: (Float) -> Unit, onScrubFinished: (Float) -> Unit, modifier: Modifier = Modifier) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var currentVal by remember(progress) { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }

    Box(
        modifier = modifier.fillMaxWidth().height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset -> val frac = (offset.x / widthPx).coerceIn(0f, 1f); currentVal = frac; onScrubFinished(frac) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onHorizontalDrag = { change, _ -> change.consume(); val frac = (change.position.x / widthPx).coerceIn(0f, 1f); currentVal = frac; onScrubChange(frac) },
                    onDragEnd = { onScrubFinished(currentVal) }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(SpotifyWhite.copy(alpha = 0.28f)))
        Box(modifier = Modifier.fillMaxWidth(currentVal.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(50)).background(SpotifyWhite))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val thumbSize = 13.dp
            Box(modifier = Modifier.offset(x = (maxWidth - thumbSize) * currentVal.coerceIn(0f, 1f)).size(thumbSize).clip(CircleShape).background(SpotifyWhite))
        }
    }
}

@Composable
fun NowPlayingScreen(state: UiState, vm: AppViewModel) {
    val track = state.currentTrack ?: return
    val str = state.strings
    val context = LocalContext.current
    val activity = context as? Activity
    val isLiked = state.likedTracks.any { it.id == track.id }
    val isOffline = state.offlineTrackIds.contains(track.id)
    val dlPct = state.downloadProgress[track.id]
    val accent by animateColorAsState(Color(state.dynamicAccentColor), animationSpec = tween(550), label = "now_accent")

    val canvasPulse by rememberInfiniteTransition(label = "canvas_loop").animateFloat(
        initialValue = 1.0f, targetValue = 1.06f,
        animationSpec = infiniteRepeatable(tween(4500), AnimRepeatMode.Reverse), label = "pulse"
    )
    val useCanvasMotion = state.settings.canvasEnabled && !state.settings.dataSaver && state.isPlaying && !state.isVideoMode

    // Smooth curtain hides YouTube's loading/buffering/pause overlays completely!
    var revealWebVideo by remember(track.id, state.activeVideoClipId, state.isVideoMode) { mutableStateOf(false) }
    LaunchedEffect(state.isVideoMode, state.isUsingWebEngine, state.isPlaying, state.isBusyLoading, state.isFindingVideoClip) {
        if (state.isVideoMode && state.isUsingWebEngine && state.isPlaying && !state.isBusyLoading && !state.isFindingVideoClip) {
            delay(450)
            revealWebVideo = true
        } else {
            revealWebVideo = false
        }
    }
    val curtainAlpha by animateFloatAsState(
        targetValue = if (state.isVideoMode && (!state.isUsingWebEngine || revealWebVideo)) 0f else 1f,
        animationSpec = tween(280),
        label = "video_curtain"
    )

    var isFullscreenVideo by remember { mutableStateOf(false) }
    var showQueueSheet by remember { mutableStateOf(false) }
    var showSleepSheet by remember { mutableStateOf(false) }
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showFullLyrics by remember { mutableStateOf(false) }

    var isScrubbing by remember(track.id) { mutableStateOf(false) }
    var scrubFraction by remember(track.id) { mutableFloatStateOf(0f) }

    DisposableEffect(isFullscreenVideo) {
        activity?.requestedOrientation = if (isFullscreenVideo) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED }
    }

    val scrollState = rememberScrollState()
    val qualityLabel = when {
        state.isUsingWebEngine -> "YouTube Stream"
        state.resolvedStreams != null -> state.resolvedStreams.codecLabel
        else -> state.settings.streamSource.label(str.isEn).substringBefore(" (")
    }
    val canSwitchVideo = track.source == StreamSource.YOUTUBE

    if (isFullscreenVideo && state.isVideoMode) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (state.isUsingWebEngine) {
                // Keep exact 16:9 viewport in landscape so 150vh overscan hides YouTube chrome with zero video zoom
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f).clip(RoundedCornerShape(0.dp))) {
                    AndroidView(
                        factory = { ctx -> FrameLayout(ctx).also { vm.audioEngine.attachWebView(it) } },
                        update = { vm.audioEngine.attachWebView(it) },
                        modifier = Modifier.fillMaxSize()
                    )
                    if (curtainAlpha > 0.01f) {
                        Box(modifier = Modifier.fillMaxSize().alpha(curtainAlpha).background(Color.Black), contentAlignment = Alignment.Center) {
                            ArtworkImage(track.artworkUrl, track.title, Modifier.fillMaxSize())
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
                            if (state.isBusyLoading || state.isFindingVideoClip) {
                                CircularProgressIndicator(color = SpotifyGreen)
                            } else if (!state.isPlaying) {
                                Icon(Icons.Rounded.PlayArrow, null, tint = SpotifyWhite, modifier = Modifier.size(64.dp))
                            }
                        }
                    }
                    Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.togglePlayPause() })
                }
            } else {
                AndroidView(
                    factory = { ctx -> PlayerView(ctx).apply { player = vm.audioEngine.player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } },
                    update = { it.player = vm.audioEngine.player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize()
                )
            }
            IconButton(
                onClick = { isFullscreenVideo = false },
                modifier = Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(12.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.65f))
            ) { Icon(Icons.Rounded.FullscreenExit, "Exit Fullscreen", tint = SpotifyWhite) }
        }
        return
    }

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent, SpotifyBlack, SpotifyBlack)))
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 8.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { vm.setShowNowPlaying(false) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", tint = SpotifyWhite, modifier = Modifier.size(30.dp)) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(if (state.isAiDjActive) "AI DJ • ${str.nowPlaying}" else str.nowPlaying, color = SpotifyWhite.copy(alpha = 0.72f), fontSize = 11.sp)
                    Text(qualityLabel, color = if (state.resolvedStreams?.isLossless == true) SpotifyGreen else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Row {
                    IconButton(onClick = { showSpeedSheet = true }) {
                        Text("${state.settings.playbackSpeed}x", color = if (state.settings.playbackSpeed != 1f) SpotifyGreen else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                    IconButton(onClick = { showSleepSheet = true }) {
                        Icon(Icons.Rounded.Bedtime, str.sleepTimer, tint = if (state.sleepMinutesLeft != null || state.sleepEndOfTrack) SpotifyGreen else SpotifyWhite)
                    }
                    IconButton(onClick = { vm.openTrackOptions(track) }) { Icon(Icons.Rounded.MoreVert, "More", tint = SpotifyWhite) }
                }
            }

            Spacer(Modifier.height(10.dp))

            // 16:9 Official Music Video (with HD curtain hiding all YT loading/pause chrome) OR 1:1 HD Album Art
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(if (state.isVideoMode) 16f / 9f else 1f)
                    .clip(RoundedCornerShape(14.dp)).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (state.isVideoMode) {
                    if (state.isUsingWebEngine) {
                        AndroidView(
                            factory = { ctx -> FrameLayout(ctx).also { vm.audioEngine.attachWebView(it) } },
                            update = { vm.audioEngine.attachWebView(it) },
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        AndroidView(
                            factory = { ctx -> PlayerView(ctx).apply { player = vm.audioEngine.player; useController = false; resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT } },
                            update = { it.player = vm.audioEngine.player }, onRelease = { it.player = null }, modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                // Album Art Layer (always visible in Audio Mode, and acts as smooth curtain over WebView while buffering/paused!)
                if (!state.isVideoMode || curtainAlpha > 0.01f) {
                    Box(modifier = Modifier.fillMaxSize().alpha(if (state.isVideoMode) curtainAlpha else 1f)) {
                        ArtworkImage(
                            track.artworkUrl, track.title,
                            Modifier.fillMaxSize().graphicsLayer { if (useCanvasMotion) { scaleX = canvasPulse; scaleY = canvasPulse } }
                        )
                        if (state.isVideoMode) {
                            Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)), contentAlignment = Alignment.Center) {
                                if (state.isBusyLoading || state.isFindingVideoClip) {
                                    CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 3.dp)
                                } else if (!state.isPlaying) {
                                    Box(modifier = Modifier.size(56.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
                                        Icon(Icons.Rounded.PlayArrow, null, tint = SpotifyWhite, modifier = Modifier.size(34.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                if (state.isVideoMode && state.isUsingWebEngine) {
                    Box(modifier = Modifier.fillMaxSize().clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { vm.togglePlayPause() })
                }

                if (canSwitchVideo) {
                    Row(
                        modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.84f))
                            .clickable(enabled = !state.isFindingVideoClip) { vm.toggleVideoClipMode() }
                            .padding(horizontal = 14.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.isFindingVideoClip) {
                            CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                        } else {
                            Icon(if (state.isVideoMode) Icons.Rounded.MusicNote else Icons.Rounded.OndemandVideo, null, tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                state.isFindingVideoClip -> str.findingVideo
                                state.isVideoMode -> str.switchToAudio
                                else -> str.switchToVideo
                            },
                            color = SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                if (state.isVideoMode) {
                    IconButton(
                        onClick = { isFullscreenVideo = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.78f))
                    ) { Icon(Icons.Rounded.Fullscreen, str.fullScreen, tint = SpotifyWhite, modifier = Modifier.size(20.dp)) }
                }
            }

            if (state.isAiDjActive) {
                Spacer(Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(50)).background(SpotifyGreen.copy(alpha = 0.2f))
                        .clickable { vm.switchAiDjVibe() }.padding(horizontal = 14.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
                ) {
                    Icon(Icons.Rounded.GraphicEq, null, tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(str.djVibeSwitch, color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }

            Spacer(Modifier.height(18.dp))

            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
                    Text(track.title, color = SpotifyWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, modifier = Modifier.basicMarquee())
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = track.artist, color = SpotifyWhite.copy(alpha = 0.78f), fontSize = 15.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable { vm.openArtistPage(track.artist, track.artworkUrl) }
                    )
                }
                IconButton(onClick = { vm.downloadOffline(track) }) {
                    if (state.downloadingIds.contains(track.id)) {
                        if (dlPct != null && dlPct > 1) Text("$dlPct%", color = SpotifyGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        else CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                    } else {
                        Icon(if (isOffline) Icons.Rounded.DownloadDone else Icons.Rounded.Download, "Offline", tint = if (isOffline) SpotifyGreen else SpotifyWhite.copy(alpha = 0.82f))
                    }
                }
                IconButton(onClick = { vm.toggleLike(track) }) {
                    Icon(if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, "Save", tint = if (isLiked) SpotifyGreen else SpotifyWhite, modifier = Modifier.size(28.dp))
                }
            }

            Spacer(Modifier.height(14.dp))

            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val duration = state.durationMs.coerceAtLeast(1L)
                val progressFrac = if (isScrubbing) scrubFraction else (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val shownPos = (progressFrac * duration).toLong()

                SpotifySeekBar(progressFrac, onScrubChange = { isScrubbing = true; scrubFraction = it }, onScrubFinished = { isScrubbing = false; vm.seekTo((it * duration).toLong()) })
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(shownPos), color = SpotifyWhite.copy(alpha = 0.75f), fontSize = 11.sp)
                    Text(formatTime(state.durationMs), color = SpotifyWhite.copy(alpha = 0.75f), fontSize = 11.sp)
                }

                Spacer(Modifier.height(10.dp))
                val playBtnSize = if (state.settings.carModeOptimized) 78.dp else 66.dp
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    IconButton(onClick = { vm.cycleShuffleMode() }) {
                        Icon(if (state.smartShuffleEnabled) Icons.Rounded.AutoAwesome else Icons.Rounded.Shuffle, str.shuffle, tint = if (state.shuffleEnabled) SpotifyGreen else SpotifyWhite.copy(alpha = 0.75f), modifier = Modifier.size(24.dp))
                    }
                    IconButton(onClick = { vm.skipPrevious() }) { Icon(Icons.Rounded.SkipPrevious, "Previous", tint = SpotifyWhite, modifier = Modifier.size(38.dp)) }
                    Box(modifier = Modifier.size(playBtnSize).clip(CircleShape).background(SpotifyWhite).clickable { vm.togglePlayPause() }, contentAlignment = Alignment.Center) {
                        if (state.isBusyLoading) CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Color.Black, modifier = Modifier.size(38.dp))
                    }
                    IconButton(onClick = { vm.skipNext() }) { Icon(Icons.Rounded.SkipNext, "Next", tint = SpotifyWhite, modifier = Modifier.size(38.dp)) }
                    IconButton(onClick = { vm.cycleRepeatMode() }) {
                        Icon(if (state.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (state.repeatMode != RepeatMode.OFF) SpotifyGreen else SpotifyWhite.copy(alpha = 0.75f), modifier = Modifier.size(24.dp))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { vm.setShowDevicesSheet(true) }) {
                    Icon(Icons.Rounded.Devices, str.devicesConnect, tint = if (state.jamState.active) SpotifyGreen else SpotifyWhite.copy(alpha = 0.85f))
                }
                Row {
                    IconButton(onClick = { vm.setShowCodeFor(track.title to "sonify:track:${track.id}") }) { Icon(Icons.Rounded.QrCode2, "Code", tint = SpotifyWhite.copy(alpha = 0.85f)) }
                    IconButton(onClick = {
                        val sec = (state.positionMs / 1000).toInt()
                        val tsText = if (sec > 5) " (${formatTime(state.positionMs)})" else ""
                        val link = if (track.source == StreamSource.YOUTUBE) "\nhttps://youtu.be/${track.id}${if (sec > 5) "?t=$sec" else ""}" else ""
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${track.title} - ${track.artist}$tsText$link")
                        }, str.t("שתף שיר", "Share")))
                    }) { Icon(Icons.Rounded.Share, "Share", tint = SpotifyWhite.copy(alpha = 0.85f)) }
                    IconButton(onClick = { showQueueSheet = true }) { Icon(Icons.Rounded.QueueMusic, str.queue, tint = SpotifyWhite.copy(alpha = 0.85f)) }
                }
            }

            Spacer(Modifier.height(18.dp))

            // 4-Layer Synced Lyrics Card
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = accent.copy(alpha = 0.9f))) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(str.syncedLyrics, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (state.lyrics.isNotEmpty()) {
                                IconButton(onClick = {
                                    val activeIdx = state.lyrics.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
                                    val quote = state.lyrics.subList(activeIdx, (activeIdx + 3).coerceAtMost(state.lyrics.size)).joinToString("\n") { it.text }
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "\"$quote\"\n— ${track.title}, ${track.artist}")
                                    }, str.t("שתף מילים", "Share Lyrics")))
                                }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.Share, "Share Lyrics", tint = SpotifyWhite, modifier = Modifier.size(18.dp)) }
                            }
                            TextButton(onClick = { showFullLyrics = true }) { Text(str.fullScreen, color = SpotifyWhite, fontSize = 12.sp) }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    if (state.isLoadingLyrics) {
                        Text(str.t("מחפש מילים ב-4 מקורות...", "Searching lyrics across 4 sources..."), color = SpotifyWhite.copy(alpha = 0.75f))
                    } else if (state.lyrics.isEmpty()) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                            Text(str.t("לא נמצאו מילים אוטומטית.", "Lyrics not found automatically."), color = SpotifyWhite.copy(alpha = 0.8f), fontSize = 13.sp)
                            TextButton(onClick = { showFullLyrics = true }) { Text(str.t("חיפוש ידני", "Manual Search"), color = SpotifyGreen) }
                        }
                    } else {
                        val activeIdx = state.lyrics.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
                        state.lyrics.subList((activeIdx - 1).coerceAtLeast(0), (activeIdx + 5).coerceAtMost(state.lyrics.size)).forEach { line ->
                            val isActive = state.lyrics.getOrNull(activeIdx) == line
                            Text(
                                text = line.text, color = if (isActive) SpotifyWhite else SpotifyWhite.copy(alpha = 0.55f),
                                fontWeight = if (isActive) FontWeight.Bold else FontWeight.Medium,
                                fontSize = ((if (isActive) 18f else 15f) * state.lyricsFontScale).sp,
                                modifier = Modifier.fillMaxWidth().clickable { vm.seekTo(line.timeMs) }.padding(vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // SongDNA Card
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(str.t("SongDNA • קרדיטים ומידע טכני", "SongDNA • Credits & Info"), color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text("${str.t("אמן מבצע", "Artist")}: ${track.artist}", color = SpotifySubtle, fontSize = 13.sp)
                    if (track.album.isNotBlank()) Text("${str.t("אלבום", "Album")}: ${track.album}", color = SpotifySubtle, fontSize = 13.sp)
                    Text("${str.t("מקור", "Source")}: ${track.source.label(str.isEn)}", color = SpotifySubtle, fontSize = 13.sp)
                    Text("${str.t("פורמט", "Engine")}: $qualityLabel • ${state.settings.playbackSpeed}x", color = SpotifyGreen, fontSize = 13.sp)
                    Text("${str.t("השמעות אצלך", "Your plays")}: ${state.stats.trackPlayCounts[track.id] ?: 1}", color = SpotifySubtle, fontSize = 12.sp)
                }
            }

            Spacer(Modifier.height(14.dp))

            // About Artist + Live Events + Merch + Open Artist Page
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(str.aboutArtist, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        TextButton(onClick = { vm.openArtistPage(track.artist, track.artworkUrl) }) { Text(str.t("פרופיל אמן", "View Artist"), color = SpotifyGreen, fontSize = 12.sp) }
                    }
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("${track.artist} concerts tickets")}"))) } }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.Event, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(str.concerts, color = SpotifyWhite, fontSize = 12.sp)
                        }
                        OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("${track.artist} official merch store")}"))) } }, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Rounded.ShoppingBag, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(str.merch, color = SpotifyWhite, fontSize = 12.sp)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { vm.startSongOrArtistRadio(track) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyCard, contentColor = SpotifyWhite), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Radio, null, tint = SpotifyGreen, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("${str.startRadio} • ${track.artist}")
                    }
                }
            }
            Spacer(Modifier.height(42.dp))
        }
    }

    if (showSpeedSheet) {
        val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 2.5f, 3.0f, 3.5f)
        ModalBottomSheet(onDismissRequest = { showSpeedSheet = false }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
                Text(str.speedTitle, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                speeds.chunked(5).forEach { row ->
                    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { spd ->
                            val sel = state.settings.playbackSpeed == spd
                            Box(Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { vm.updateSettings(state.settings.copy(playbackSpeed = spd)); showSpeedSheet = false }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                                Text("${spd}x", color = if (sel) Color.Black else SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(18.dp))
            }
        }
    }

    if (showQueueSheet) {
        ModalBottomSheet(onDismissRequest = { showQueueSheet = false }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Text("${str.queue} (${state.queue.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                    itemsIndexed(state.queue) { idx, item ->
                        Row(modifier = Modifier.fillMaxWidth().clickable { vm.playTrack(item, state.queue) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(if (idx == state.currentIndex) "▶" else "${idx + 1}", color = if (idx == state.currentIndex) SpotifyGreen else SpotifySubtle, modifier = Modifier.width(24.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = if (idx == state.currentIndex) SpotifyGreen else SpotifyWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (item.isSmartInjected) "${item.artist} • Smart Shuffle" else item.artist, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1)
                            }
                            IconButton(onClick = { vm.moveQueueItem(idx, idx - 1) }, enabled = idx > 0) { Icon(Icons.Rounded.ArrowUpward, "Up", tint = SpotifySubtle, modifier = Modifier.size(18.dp)) }
                            IconButton(onClick = { vm.moveQueueItem(idx, idx + 1) }, enabled = idx < state.queue.lastIndex) { Icon(Icons.Rounded.ArrowDownward, "Down", tint = SpotifySubtle, modifier = Modifier.size(18.dp)) }
                            if (idx != state.currentIndex) { IconButton(onClick = { vm.removeFromQueue(idx) }) { Icon(Icons.Rounded.Delete, "Remove", tint = SpotifySubtle, modifier = Modifier.size(18.dp)) } }
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
                Text("${str.sleepTimer} (Fade-out)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(12.dp))
                listOf(5, 10, 15, 30, 45, 60).forEach { min ->
                    Text("$min ${str.t("דקות", "minutes")}", color = SpotifyWhite, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { vm.setSleepTimer(min); showSleepSheet = false }.padding(vertical = 10.dp))
                }
                Text(str.t("בסיום השיר הנוכחי", "End of current track"), color = SpotifyGreen, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { vm.setSleepEndOfTrack(); showSleepSheet = false }.padding(vertical = 10.dp))
                if (state.sleepMinutesLeft != null || state.sleepEndOfTrack) {
                    Text(str.t("בטל טיימר שינה", "Turn off timer"), color = Color(0xFFFF6B6B), fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { vm.setSleepTimer(0); showSleepSheet = false }.padding(vertical = 10.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    if (showFullLyrics) {
        var manualQuery by remember(track.id) { mutableStateOf("${track.artist} ${track.title}") }
        val activeIdx = state.lyrics.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
        val lyricsListState = rememberLazyListState()

        LaunchedEffect(activeIdx, state.lyrics.size) {
            if (state.lyrics.isNotEmpty() && activeIdx in state.lyrics.indices) {
                runCatching { lyricsListState.animateScrollToItem((activeIdx - 2).coerceAtLeast(0)) }
            }
        }

        ModalBottomSheet(onDismissRequest = { showFullLyrics = false }, containerColor = accent, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp).imePadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(str.syncedLyrics, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.setLyricsFontScale(state.lyricsFontScale - 0.1f) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.TextDecrease, "Smaller", tint = SpotifyWhite) }
                        IconButton(onClick = { vm.setLyricsFontScale(state.lyricsFontScale + 0.1f) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.TextIncrease, "Larger", tint = SpotifyWhite) }
                    }
                }
                Spacer(Modifier.height(10.dp))
                if (state.isLoadingLyrics) {
                    Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = SpotifyWhite) }
                } else if (state.lyrics.isEmpty()) {
                    OutlinedTextField(value = manualQuery, onValueChange = { manualQuery = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { vm.retryLyrics(manualQuery) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                        Text(str.t("חפש מילים", "Search Lyrics"), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(24.dp))
                } else {
                    LazyColumn(state = lyricsListState, modifier = Modifier.fillMaxWidth().heightIn(max = 520.dp), contentPadding = PaddingValues(bottom = 32.dp)) {
                        itemsIndexed(state.lyrics) { idx, line ->
                            Text(
                                text = line.text, color = if (idx == activeIdx) SpotifyWhite else SpotifyWhite.copy(alpha = 0.45f),
                                fontSize = ((if (idx == activeIdx) 22f else 18f) * state.lyricsFontScale).sp, fontWeight = FontWeight.Bold,
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
    val str = state.strings
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val devices = remember(str.isEn) { vm.audioEngine.getOutputDevices(str.isEn) }
    var volumeFrac by remember { mutableFloatStateOf(vm.audioEngine.getSystemVolumeFraction()) }
    var joinCodeInput by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = { vm.setShowDevicesSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).imePadding()) {
            Text(str.devicesConnect, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))

            devices.forEach { d ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                        .background(if (d.isActive) Color(0xFF1F3B33) else SpotifyCard)
                        .clickable { vm.audioEngine.openSystemAudioSettings() }
                        .padding(horizontal = 12.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(if (d.isLosslessCapable) Icons.Rounded.Usb else Icons.Rounded.Headphones, null, tint = if (d.isActive) SpotifyGreen else SpotifySubtle)
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(d.name, color = if (d.isActive) SpotifyGreen else SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(d.typeLabel(str.isEn), color = SpotifySubtle, fontSize = 12.sp)
                    }
                    if (d.isActive) Icon(Icons.Rounded.Check, null, tint = SpotifyGreen)
                }
                Spacer(Modifier.height(6.dp))
            }

            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VolumeDown, null, tint = SpotifySubtle, modifier = Modifier.size(20.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Slider(
                        value = volumeFrac,
                        onValueChange = { volumeFrac = it; vm.audioEngine.setSystemVolumeFraction(it) },
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen, inactiveTrackColor = SpotifyCard)
                    )
                }
                Icon(Icons.Rounded.VolumeUp, null, tint = SpotifyWhite, modifier = Modifier.size(20.dp))
            }

            OutlinedButton(onClick = { vm.audioEngine.openSystemAudioSettings() }, modifier = Modifier.fillMaxWidth()) {
                Text(str.t("החלף אוזניות / Bluetooth בהגדרות", "Switch Bluetooth / Audio Output"), color = SpotifyWhite)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = SpotifyCard)

            Text("Jam • ${str.t("האזנה משותפת", "Shared Listening")}", color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(8.dp))

            if (state.jamState.active) {
                Card(colors = CardDefaults.cardColors(containerColor = SpotifyCard), shape = RoundedCornerShape(12.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("${str.t("קוד Jam", "Jam Code")}: ${state.jamState.code}", color = SpotifyGreen, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                Text("${state.jamState.participants.size} ${str.t("משתתפים מחוברים", "participants")}: ${state.jamState.participants.joinToString(", ")}", color = SpotifySubtle, fontSize = 12.sp)
                            }
                            IconButton(onClick = { vm.setShowDevicesSheet(false); vm.setShowCodeFor("Jam • ${state.jamState.code}" to state.jamState.joinUrl) }) {
                                Icon(Icons.Rounded.QrCode2, "QR", tint = SpotifyWhite)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(state.jamState.code)) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.ContentCopy, null, tint = SpotifyWhite, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(str.t("העתק קוד", "Copy Code"), color = SpotifyWhite, fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "Join my Sonify Jam! Code: ${state.jamState.code} (${state.jamState.joinUrl})")
                                }, "Share Jam"))
                            }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Share, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text(str.t("שתף הזמנה", "Share Invite"), color = SpotifyWhite, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(str.t("אורחים יכולים לערוך תור", "Guests can edit queue"), color = SpotifyWhite, fontSize = 13.sp)
                            Switch(checked = state.jamState.guestsCanControlQueue, onCheckedChange = { vm.toggleJamPermission("queue") }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = SpotifyGreen))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(str.t("אורחים יכולים לשלוט בווליום", "Guests can control volume"), color = SpotifyWhite, fontSize = 13.sp)
                            Switch(checked = state.jamState.guestsCanControlVolume, onCheckedChange = { vm.toggleJamPermission("volume") }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = SpotifyGreen))
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.endJamSession() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B), contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                            Text(str.endJam, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Button(onClick = { vm.startJamSession() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                    Text(str.startJam, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = joinCodeInput, onValueChange = { joinCodeInput = it }, placeholder = { Text(str.joinJam) }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.joinJamByCode(joinCodeInput); joinCodeInput = "" }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyCard)) {
                        Text(str.t("הצטרף", "Join"), color = SpotifyWhite)
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}
