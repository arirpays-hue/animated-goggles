@file:kotlin.OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@file:androidx.annotation.OptIn(UnstableApi::class)

package com.sonify.app

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.net.Uri
import android.widget.FrameLayout
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Spotify-style circular download progress ring:
 * - Idle: subtle circular outline with down arrow
 * - Downloading: smooth animated circular green arc filling from 0% to 100%
 * - Downloaded: solid SpotifyGreen circle with dark arrow-down icon
 */
@Composable
fun SpotifyDownloadRingButton(
    isOffline: Boolean,
    isDownloading: Boolean,
    progressPct: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 26.dp,
    idleTint: Color = SpotifySubtle
) {
    val targetFrac = when {
        isOffline -> 1f
        isDownloading -> ((progressPct ?: 5).coerceIn(4, 100)) / 100f
        else -> 0f
    }
    val animatedSweep by animateFloatAsState(
        targetValue = targetFrac * 360f,
        animationSpec = tween(durationMillis = 260, easing = FastOutSlowInEasing),
        label = "dl_ring_sweep"
    )

    Box(
        modifier = modifier
            .size(size + 10.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        when {
            isOffline -> {
                Box(
                    modifier = Modifier.size(size).clip(CircleShape).background(SpotifyGreen),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = "Downloaded",
                        tint = Color.Black,
                        modifier = Modifier.size(size * 0.62f)
                    )
                }
            }
            isDownloading -> {
                Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokePx = 2.4.dp.toPx()
                        val inset = strokePx / 2f
                        val arcSize = Size(this.size.width - strokePx, this.size.height - strokePx)
                        drawArc(
                            color = SpotifyWhite.copy(alpha = 0.22f),
                            startAngle = 0f,
                            sweepAngle = 360f,
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = strokePx)
                        )
                        drawArc(
                            color = SpotifyGreen,
                            startAngle = -90f,
                            sweepAngle = animatedSweep.coerceAtLeast(18f),
                            useCenter = false,
                            topLeft = Offset(inset, inset),
                            size = arcSize,
                            style = Stroke(width = strokePx, cap = StrokeCap.Round)
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = "Downloading",
                        tint = SpotifyGreen,
                        modifier = Modifier.size(size * 0.48f)
                    )
                }
            }
            else -> {
                Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val strokePx = 1.6.dp.toPx()
                        drawCircle(
                            color = idleTint.copy(alpha = 0.72f),
                            radius = (this.size.minDimension - strokePx) / 2f,
                            style = Stroke(width = strokePx)
                        )
                    }
                    Icon(
                        imageVector = Icons.Rounded.ArrowDownward,
                        contentDescription = "Download",
                        tint = idleTint,
                        modifier = Modifier.size(size * 0.54f)
                    )
                }
            }
        }
    }
}

@Composable
fun FloatingMiniPlayer(state: UiState, vm: AppViewModel, modifier: Modifier = Modifier) {
    val track = state.currentTrack ?: return
    val isLiked = state.likedTracks.any { it.id == track.id }
    val progress = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs.toFloat()).coerceIn(0f, 1f) else 0f
    val accent by animateColorAsState(Color(state.dynamicAccentColor), animationSpec = tween(480), label = "mini_accent")
    val devices = remember(state.strings.isEn, state.showDevicesSheet) { vm.audioEngine.getOutputDevices(state.strings.isEn) }
    val activeExternal = devices.firstOrNull { it.isActive && !it.typeLabelEn.contains("Built-in", true) }
    var dragAccum by remember { mutableFloatStateOf(0f) }

    val likePop by animateFloatAsState(
        targetValue = if (isLiked) 1.08f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "mini_like_pop"
    )

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
                    Text(
                        track.title,
                        color = SpotifyWhite,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.SansSerif,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (activeExternal != null || state.jamState.active) {
                            SpotifyConnectIcon(true, SpotifyGreen, size = 12.dp)
                            Spacer(Modifier.width(4.dp))
                        }
                        Text(
                            text = when {
                                state.jamState.active -> "Jam • ${state.jamState.code}"
                                activeExternal != null -> activeExternal.name
                                state.isVideoMode -> "${track.artist} • Video"
                                else -> track.artist
                            },
                            color = if (activeExternal != null || state.jamState.active) SpotifyGreen else SpotifyWhite.copy(alpha = 0.78f),
                            fontSize = 11.sp,
                            fontWeight = if (activeExternal != null || state.jamState.active) FontWeight.SemiBold else FontWeight.Normal,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
            IconButton(onClick = { vm.setShowDevicesSheet(true) }, modifier = Modifier.size(36.dp)) {
                SpotifyConnectIcon(
                    isConnectedExternal = activeExternal != null || state.jamState.active,
                    tint = if (activeExternal != null || state.jamState.active) SpotifyGreen else SpotifyWhite.copy(alpha = 0.85f),
                    size = 20.dp
                )
            }
            IconButton(onClick = { vm.toggleLike(track) }, modifier = Modifier.size(36.dp).graphicsLayer { scaleX = likePop; scaleY = likePop }) {
                Icon(if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, "Save", tint = if (isLiked) SpotifyGreen else SpotifyWhite, modifier = Modifier.size(22.dp))
            }
            IconButton(onClick = { vm.togglePlayPause() }, modifier = Modifier.size(38.dp)) {
                if (state.isBusyLoading) CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
                else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = SpotifyWhite, modifier = Modifier.size(28.dp))
            }
        }
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().height(2.5.dp), color = SpotifyWhite, trackColor = SpotifyWhite.copy(alpha = 0.24f))
        }
    }
}

@Composable
fun SpotifySeekBar(progress: Float, onScrubChange: (Float) -> Unit, onScrubFinished: (Float) -> Unit, modifier: Modifier = Modifier) {
    var widthPx by remember { mutableFloatStateOf(1f) }
    var currentVal by remember(progress) { mutableFloatStateOf(progress.coerceIn(0f, 1f)) }
    var dragging by remember { mutableStateOf(false) }
    val thumbScale by animateFloatAsState(if (dragging) 1.35f else 1f, spring(stiffness = Spring.StiffnessMedium), label = "thumb_scale")

    Box(
        modifier = modifier.fillMaxWidth().height(28.dp)
            .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                detectTapGestures { offset -> val frac = (offset.x / widthPx).coerceIn(0f, 1f); currentVal = frac; onScrubFinished(frac) }
            }
            .pointerInput(Unit) {
                detectHorizontalDragGestures(
                    onDragStart = { dragging = true },
                    onHorizontalDrag = { change, _ -> change.consume(); val frac = (change.position.x / widthPx).coerceIn(0f, 1f); currentVal = frac; onScrubChange(frac) },
                    onDragEnd = { dragging = false; onScrubFinished(currentVal) },
                    onDragCancel = { dragging = false }
                )
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(SpotifyWhite.copy(alpha = 0.25f)))
        Box(modifier = Modifier.fillMaxWidth(currentVal.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(50)).background(if (dragging) SpotifyGreen else SpotifyWhite))
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val thumbSize = 13.dp
            Box(
                modifier = Modifier
                    .offset(x = (maxWidth - thumbSize) * currentVal.coerceIn(0f, 1f))
                    .size(thumbSize)
                    .graphicsLayer { scaleX = thumbScale; scaleY = thumbScale }
                    .clip(CircleShape)
                    .background(SpotifyWhite)
            )
        }
    }
}

private sealed class LyricDisplayRow {
    abstract val timeMs: Long
    abstract val keyId: String

    data class TextLine(
        override val timeMs: Long,
        val line: LyricLine,
        val originalIndex: Int
    ) : LyricDisplayRow() {
        override val keyId: String get() = "txt_${originalIndex}_$timeMs"
    }

    data class MelodyRow(
        override val timeMs: Long,
        val breakId: Int
    ) : LyricDisplayRow() {
        override val keyId: String get() = "mel_${breakId}_$timeMs"
    }
}

private fun isInstrumentalMarkerText(text: String): Boolean {
    val t = text.trim().lowercase()
    return t == "♪" || t == "♫" || t == "🎵" || t == "🎶" || t == "..." ||
        t == "[instrumental]" || t == "(instrumental)" || t == "instrumental" ||
        t == "[music]" || t == "(music)" || t == "[מוזיקה]" || t == "מנגינה"
}

private fun buildLyricDisplayRows(lyrics: List<LyricLine>): List<LyricDisplayRow> {
    if (lyrics.isEmpty()) return emptyList()
    val out = mutableListOf<LyricDisplayRow>()
    if (lyrics.first().timeMs >= 3500L) {
        out.add(LyricDisplayRow.MelodyRow(timeMs = 0L, breakId = 0))
    }
    lyrics.forEachIndexed { idx, line ->
        if (isInstrumentalMarkerText(line.text)) {
            out.add(LyricDisplayRow.MelodyRow(timeMs = line.timeMs, breakId = idx + 1))
        } else {
            out.add(LyricDisplayRow.TextLine(timeMs = line.timeMs, line = line, originalIndex = idx))
            val next = lyrics.getOrNull(idx + 1)
            if (next != null && !isInstrumentalMarkerText(next.text) && (next.timeMs - line.timeMs) >= 7500L) {
                out.add(LyricDisplayRow.MelodyRow(timeMs = line.timeMs + 3800L, breakId = idx + 1000))
            }
        }
    }
    return out
}

private suspend fun smoothGlideToLyric(listState: LazyListState, targetIndex: Int, topOffsetPx: Int) {
    val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == targetIndex }
    if (visible != null) {
        val delta = (visible.offset - topOffsetPx).toFloat()
        if (kotlin.math.abs(delta) > 4f) {
            listState.animateScrollBy(
                value = delta,
                animationSpec = tween(durationMillis = 520, easing = FastOutSlowInEasing)
            )
        }
    } else {
        listState.animateScrollToItem(index = targetIndex.coerceAtLeast(0), scrollOffset = -topOffsetPx)
    }
}

@Composable
private fun SpotifyMelodyRowItem(
    isCurrent: Boolean,
    isPast: Boolean,
    isPlaying: Boolean,
    isFullScreen: Boolean,
    onClick: () -> Unit
) {
    val tint by animateColorAsState(
        targetValue = when {
            isCurrent -> Color.White
            isPast -> Color.White.copy(alpha = 0.56f)
            else -> Color.Black.copy(alpha = 0.45f)
        },
        animationSpec = tween(320, easing = FastOutSlowInEasing),
        label = "mel_row_tint"
    )
    val scale by animateFloatAsState(
        targetValue = if (isCurrent) 1.06f else 0.94f,
        animationSpec = spring(dampingRatio = 0.78f, stiffness = 250f),
        label = "mel_row_scale"
    )
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                transformOrigin = TransformOrigin(if (isRtl) 1f else 0f, 0.5f)
                scaleX = scale
                scaleY = scale
            }
            .clickable(onClick = onClick)
            .padding(vertical = if (isFullScreen) 13.dp else 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Icon(
            imageVector = Icons.Rounded.MusicNote,
            contentDescription = "Instrumental",
            tint = tint,
            modifier = Modifier.size(if (isFullScreen) 24.dp else 20.dp)
        )
        SpotifyMelodyDots(
            isPlaying = isCurrent && isPlaying,
            tint = tint,
            dotSize = if (isFullScreen) 11.dp else 9.dp
        )
    }
}

@Composable
private fun SpotifyAnimatedLyricLine(
    line: LyricLine,
    isCurrent: Boolean,
    isPast: Boolean,
    showTranslation: Boolean,
    fontScale: Float,
    baseFontSp: Float,
    activeFontSp: Float,
    translationFontSp: Float,
    onClick: () -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current

    val lineColor by animateColorAsState(
        targetValue = when {
            isCurrent -> Color.White
            isPast -> Color.White.copy(alpha = 0.62f)
            else -> Color.Black.copy(alpha = 0.52f)
        },
        animationSpec = tween(durationMillis = 360, easing = FastOutSlowInEasing),
        label = "sp_lyric_color"
    )
    val lineScale by animateFloatAsState(
        targetValue = if (isCurrent) 1.045f else 0.985f,
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 260f),
        label = "sp_lyric_scale"
    )
    val nudgeY by animateFloatAsState(
        targetValue = when {
            isPast -> with(density) { (-3).dp.toPx() }
            isCurrent -> 0f
            else -> with(density) { 4.dp.toPx() }
        },
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 240f),
        label = "sp_lyric_nudge"
    )
    val lineAlpha by animateFloatAsState(
        targetValue = when {
            isCurrent -> 1f
            isPast -> 0.88f
            else -> 0.78f
        },
        animationSpec = tween(durationMillis = 340, easing = FastOutSlowInEasing),
        label = "sp_lyric_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                transformOrigin = TransformOrigin(if (isRtl) 1f else 0f, 0.5f)
                scaleX = lineScale
                scaleY = lineScale
                translationY = nudgeY
                alpha = lineAlpha
            }
            .clickable(onClick = onClick)
            .padding(vertical = 7.dp)
    ) {
        Text(
            text = line.text,
            color = lineColor,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = (-0.35).sp,
            lineHeight = ((if (isCurrent) activeFontSp else baseFontSp) * 1.30f * fontScale).sp,
            fontSize = ((if (isCurrent) activeFontSp else baseFontSp) * fontScale).sp
        )
        AnimatedVisibility(
            visible = showTranslation && !line.translatedText.isNullOrBlank(),
            enter = fadeIn(tween(260)) + expandVertically(tween(260)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(180))
        ) {
            Text(
                text = line.translatedText.orEmpty(),
                color = if (isCurrent) Color.White.copy(alpha = 0.82f) else lineColor.copy(alpha = 0.72f),
                fontWeight = FontWeight.Medium,
                fontFamily = FontFamily.SansSerif,
                fontSize = (translationFontSp * fontScale).sp,
                lineHeight = ((translationFontSp * 1.34f) * fontScale).sp,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
    }
}

@Composable
private fun LyricsTranslateErrorBanner(errorMsg: String, str: AppStrings, onOpenSettings: () -> Unit, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
            .clip(RoundedCornerShape(10.dp)).background(Color(0xCC2B1212))
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Rounded.WarningAmber, null, tint = Color(0xFFFFB74D), modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(errorMsg, color = SpotifyWhite, fontSize = 11.5.sp, modifier = Modifier.weight(1f))
        if (errorMsg.contains("API", true) || errorMsg.contains("מפתח")) {
            Text(
                str.t("הגדרות", "Settings"),
                color = SpotifyGreen,
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                modifier = Modifier.clickable(onClick = onOpenSettings).padding(horizontal = 6.dp)
            )
        }
        Icon(Icons.Rounded.Close, "Close", tint = SpotifySubtle, modifier = Modifier.size(16.dp).clickable(onClick = onDismiss))
    }
}

@Composable
private fun ExploreMiniCard(
    title: String,
    artworkUrl: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .height(148.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(SpotifyCard)
            .spotifyPressClickable(pressedScale = 0.96f, onClick = onClick)
    ) {
        ArtworkImage(artworkUrl, title, Modifier.fillMaxSize())
        Box(
            modifier = Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    0.45f to Color.Black.copy(alpha = 0.25f),
                    1f to Color.Black.copy(alpha = 0.88f)
                )
            )
        )
        Text(
            text = title,
            color = SpotifyWhite,
            fontSize = 12.sp,
            fontWeight = FontWeight.ExtraBold,
            lineHeight = 15.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(9.dp)
        )
    }
}

@Composable
fun NowPlayingScreen(state: UiState, vm: AppViewModel) {
    val track = state.currentTrack ?: return
    val str = state.strings
    val context = LocalContext.current
    val activity = context as? Activity
    val extras = state.nowPlayingExtras?.takeIf { it.trackId == track.id }
    val isLiked = state.likedTracks.any { it.id == track.id }
    val isFollowedArtist = state.followedArtists.contains(track.artist)
    val isLocalTrack = track.source == StreamSource.LOCAL
    val isOffline = state.offlineTrackIds.contains(track.id)
    val isDownloading = state.downloadingIds.contains(track.id)
    val dlPct = state.downloadProgress[track.id]
    val accent by animateColorAsState(Color(state.dynamicAccentColor), animationSpec = tween(550), label = "now_accent")

    val devices = remember(str.isEn, state.showDevicesSheet) { vm.audioEngine.getOutputDevices(str.isEn) }
    val activeExternal = devices.firstOrNull { it.isActive && !it.typeLabelEn.contains("Built-in", true) }

    val canvasPulse by rememberInfiniteTransition(label = "canvas_loop").animateFloat(
        initialValue = 1.0f, targetValue = 1.055f,
        animationSpec = infiniteRepeatable(tween(4800), AnimRepeatMode.Reverse), label = "pulse"
    )
    val useCanvasMotion = state.settings.canvasEnabled && !state.settings.dataSaver && state.isPlaying && !state.isVideoMode

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
    var showAllCreditsSheet by remember { mutableStateOf(false) }

    LaunchedEffect(state.isVideoMode) {
        if (state.isVideoMode) showFullLyrics = false
    }

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
    // Strictly blocks video clip button in Offline Mode!
    val canSwitchVideo = track.source == StreamSource.YOUTUBE && !state.settings.offlineMode

    if (isFullscreenVideo && state.isVideoMode && !state.settings.offlineMode) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (state.isUsingWebEngine) {
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
                            if (state.isBusyLoading || state.isFindingVideoClip) CircularProgressIndicator(color = SpotifyGreen)
                            else if (!state.isPlaying) Icon(Icons.Rounded.PlayArrow, null, tint = SpotifyWhite, modifier = Modifier.size(64.dp))
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

    val lyricDisplayRows = remember(state.lyrics) { buildLyricDisplayRows(state.lyrics) }
    val realArtistPhoto = extras?.artistPhotoUrl?.takeIf { it.isNotBlank() } ?: track.artworkUrl
    val creditsList = extras?.credits?.takeIf { it.isNotEmpty() }
        ?: listOf(TrackCreditEntry(track.artist, str.t("האמן/ית הראשי/ת", "Main Artist"), isMainArtist = true))

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(accent, Color(0xFF121212), SpotifyBlack)))
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 22.dp, vertical = 8.dp)) {
            // Top Bar
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { vm.setShowNowPlaying(false) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", tint = SpotifyWhite, modifier = Modifier.size(30.dp)) }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        if (state.isAiDjActive) "AI DJ • ${state.activeDjStyleLabel.ifBlank { str.nowPlaying }}" else str.nowPlaying.uppercase(),
                        color = SpotifyWhite.copy(alpha = 0.72f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        when {
                            state.settings.offlineMode -> str.t("אופליין • אודיו מקומי", "OFFLINE • Local Audio")
                            state.isWeakNetworkActive -> "$qualityLabel • ${str.t("הגנת רשת חלשה", "Weak-Net Shield")}"
                            else -> qualityLabel
                        },
                        color = if (state.settings.offlineMode || state.resolvedStreams?.isLossless == true || state.resolvedStreams?.codecLabel == "INSTANT CACHE") SpotifyGreen else SpotifyWhite,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
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

            // Cover Art / Official Video Box
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(if (state.isVideoMode) 16f / 9f else 1f)
                    .clip(RoundedCornerShape(12.dp)).background(Color.Black),
                contentAlignment = Alignment.Center
            ) {
                if (state.isVideoMode && !state.settings.offlineMode) {
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
                            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.82f))
                            .spotifyPressClickable(enabled = !state.isFindingVideoClip) { vm.toggleVideoClipMode() }
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
                            color = SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold
                        )
                    }
                }

                if (state.isVideoMode && !state.settings.offlineMode) {
                    IconButton(
                        onClick = { isFullscreenVideo = true },
                        modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).size(36.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.78f))
                    ) { Icon(Icons.Rounded.Fullscreen, str.fullScreen, tint = SpotifyWhite, modifier = Modifier.size(20.dp)) }
                }
            }

            if (state.isAiDjActive) {
                Spacer(Modifier.height(10.dp))
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(50)).background(SpotifyGreen.copy(alpha = 0.2f))
                            .clickable { vm.switchAiDjVibe() }.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = SpotifyGreen, modifier = Modifier.size(17.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(str.djVibeSwitch, color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, maxLines = 1)
                    }
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(SpotifyCard)
                            .clickable { vm.setShowDjStudioSheet(true) }
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                    ) { Icon(Icons.Rounded.Tune, "DJ Studio", tint = SpotifyWhite, modifier = Modifier.size(18.dp)) }
                }
            }

            Spacer(Modifier.height(20.dp))

            // Track Title, Artist, Spotify Filling Download Ring & Like Button
            val likeScale by animateFloatAsState(if (isLiked) 1.12f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "like_btn")
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                    Text(
                        track.title,
                        color = SpotifyWhite,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        fontFamily = FontFamily.SansSerif,
                        letterSpacing = (-0.4).sp,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee()
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.clickable { vm.openArtistPage(track.artist, realArtistPhoto) }
                    ) {
                        if (track.isExplicit) {
                            Box(
                                modifier = Modifier.padding(end = 6.dp).clip(RoundedCornerShape(3.dp))
                                    .background(SpotifyWhite.copy(alpha = 0.72f)).padding(horizontal = 4.dp, vertical = 1.dp)
                            ) { Text("E", color = Color.Black, fontSize = 9.sp, fontWeight = FontWeight.Black) }
                        }
                        Text(
                            text = track.artist,
                            color = SpotifyWhite.copy(alpha = 0.76f),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                if (!isLocalTrack) {
                    SpotifyDownloadRingButton(
                        isOffline = isOffline,
                        isDownloading = isDownloading,
                        progressPct = dlPct,
                        onClick = { vm.downloadOffline(track) },
                        size = 25.dp,
                        idleTint = SpotifyWhite.copy(alpha = 0.85f)
                    )
                }
                IconButton(onClick = { vm.toggleLike(track) }, modifier = Modifier.graphicsLayer { scaleX = likeScale; scaleY = likeScale }) {
                    Icon(if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, "Save", tint = if (isLiked) SpotifyGreen else SpotifyWhite, modifier = Modifier.size(28.dp))
                }
            }

            Spacer(Modifier.height(14.dp))

            // Seek Bar & Transport Controls
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val duration = state.durationMs.coerceAtLeast(1L)
                val progressFrac = if (isScrubbing) scrubFraction else (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val shownPos = (progressFrac * duration).toLong()

                SpotifySeekBar(progressFrac, onScrubChange = { isScrubbing = true; scrubFraction = it }, onScrubFinished = { isScrubbing = false; vm.seekTo((it * duration).toLong()) })
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(shownPos), color = SpotifyWhite.copy(alpha = 0.72f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(formatTime(state.durationMs), color = SpotifyWhite.copy(alpha = 0.72f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(10.dp))
                val playBtnSize = if (state.settings.carModeOptimized) 78.dp else 66.dp
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { vm.cycleShuffleMode() }) {
                            Icon(if (state.smartShuffleEnabled) Icons.Rounded.AutoAwesome else Icons.Rounded.Shuffle, str.shuffle, tint = if (state.shuffleEnabled) SpotifyGreen else SpotifyWhite.copy(alpha = 0.75f), modifier = Modifier.size(24.dp))
                        }
                        if (state.shuffleEnabled) Box(Modifier.size(4.dp).clip(CircleShape).background(SpotifyGreen))
                    }
                    Box(modifier = Modifier.size(46.dp).spotifyPressClickable { vm.skipPrevious() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.SkipPrevious, "Previous", tint = SpotifyWhite, modifier = Modifier.size(38.dp))
                    }
                    Box(
                        modifier = Modifier.size(playBtnSize).clip(CircleShape).background(SpotifyWhite)
                            .spotifyPressClickable(pressedScale = 0.90f) { vm.togglePlayPause() },
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.isBusyLoading) CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                        else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Color.Black, modifier = Modifier.size(38.dp))
                    }
                    Box(modifier = Modifier.size(46.dp).spotifyPressClickable { vm.skipNext() }, contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.SkipNext, "Next", tint = SpotifyWhite, modifier = Modifier.size(38.dp))
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { vm.cycleRepeatMode() }) {
                            Icon(if (state.repeatMode == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat, "Repeat", tint = if (state.repeatMode != RepeatMode.OFF) SpotifyGreen else SpotifyWhite.copy(alpha = 0.75f), modifier = Modifier.size(24.dp))
                        }
                        if (state.repeatMode != RepeatMode.OFF) Box(Modifier.size(4.dp).clip(CircleShape).background(SpotifyGreen))
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // Devices, QR, Real Clickable Share Link & Drag Queue Row
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Row(
                    modifier = Modifier.clip(RoundedCornerShape(50))
                        .background(if (activeExternal != null || state.jamState.active) SpotifyGreen.copy(alpha = 0.14f) else Color.Transparent)
                        .spotifyPressClickable { vm.setShowDevicesSheet(true) }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SpotifyConnectIcon(
                        isConnectedExternal = activeExternal != null || state.jamState.active,
                        tint = if (activeExternal != null || state.jamState.active) SpotifyGreen else SpotifyWhite.copy(alpha = 0.85f),
                        size = 21.dp
                    )
                    if (activeExternal != null || state.jamState.active) {
                        Spacer(Modifier.width(7.dp))
                        Text(
                            text = if (state.jamState.active) "Jam • ${state.jamState.code}" else activeExternal?.name.orEmpty(),
                            color = SpotifyGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 150.dp)
                        )
                    }
                }
                Row {
                    IconButton(onClick = { vm.setShowCodeFor(track.title to SonifyShareLinks.trackUrl(track, state.positionMs)) }) {
                        Icon(Icons.Rounded.QrCode2, "Code", tint = SpotifyWhite.copy(alpha = 0.85f))
                    }
                    IconButton(onClick = {
                        val shareLink = SonifyShareLinks.trackUrl(track, state.positionMs)
                        val shareText = "${track.title} - ${track.artist}\n$shareLink"
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText)
                        }, str.t("שתף שיר", "Share")))
                    }) { Icon(Icons.Rounded.Share, "Share", tint = SpotifyWhite.copy(alpha = 0.85f)) }
                    IconButton(onClick = { showQueueSheet = true }) { Icon(Icons.Rounded.QueueMusic, str.queue, tint = SpotifyWhite.copy(alpha = 0.85f)) }
                }
            }

            // 1. Synced Lyrics Card
            if (!state.isVideoMode) {
                Spacer(Modifier.height(18.dp))
                val lyricsCardBg by animateColorAsState(
                    targetValue = Color(
                        red = ((accent.red * 1.38f).coerceAtMost(0.82f)),
                        green = ((accent.green * 1.38f).coerceAtMost(0.82f)),
                        blue = ((accent.blue * 1.38f).coerceAtMost(0.82f)),
                        alpha = 1f
                    ),
                    animationSpec = tween(550),
                    label = "lyrics_card_bg"
                )
                val activeRowIdx = remember(lyricDisplayRows, state.positionMs) {
                    lyricDisplayRows.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
                }
                val inlineLyricsState = rememberLazyListState()
                val density = LocalDensity.current
                val inlineTopOffsetPx = remember(density) { with(density) { 46.dp.roundToPx() } }

                LaunchedEffect(track.id) { runCatching { inlineLyricsState.scrollToItem(0) } }
                LaunchedEffect(activeRowIdx, lyricDisplayRows.size) {
                    if (lyricDisplayRows.isNotEmpty() && activeRowIdx in lyricDisplayRows.indices) {
                        runCatching { smoothGlideToLyric(inlineLyricsState, activeRowIdx, inlineTopOffsetPx) }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = lyricsCardBg)) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(str.syncedLyrics, color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, fontFamily = FontFamily.SansSerif)
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                if (state.lyrics.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier.clip(RoundedCornerShape(50))
                                            .background(if (state.showTranslatedLyrics) SpotifyWhite else Color.Black.copy(alpha = 0.32f))
                                            .clickable { vm.toggleTranslateLyrics() }
                                            .padding(horizontal = 10.dp, vertical = 5.dp)
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            if (state.isTranslatingLyrics) {
                                                CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 1.8.dp, modifier = Modifier.size(12.dp))
                                                Spacer(Modifier.width(5.dp))
                                            } else {
                                                Icon(Icons.Rounded.Translate, null, tint = if (state.showTranslatedLyrics) Color.Black else SpotifyWhite, modifier = Modifier.size(14.dp))
                                                Spacer(Modifier.width(4.dp))
                                            }
                                            Text(
                                                text = when {
                                                    state.isTranslatingLyrics -> str.translating
                                                    state.showTranslatedLyrics -> str.showOriginal
                                                    else -> str.translateLyrics
                                                },
                                                color = if (state.showTranslatedLyrics) Color.Black else SpotifyWhite,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                    Box(
                                        modifier = Modifier.size(30.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.30f))
                                            .clickable { showFullLyrics = true },
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Rounded.OpenInFull, str.fullScreen, tint = SpotifyWhite, modifier = Modifier.size(15.dp)) }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        state.lyricsTranslateError?.let { err ->
                            LyricsTranslateErrorBanner(
                                errorMsg = err,
                                str = str,
                                onOpenSettings = { vm.openAiApiSettings() },
                                onDismiss = { vm.clearLyricsTranslateError() }
                            )
                        }

                        if (state.isLoadingLyrics) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 16.dp)) {
                                SpotifyMelodyDots(isPlaying = true, tint = SpotifyWhite, dotSize = 9.dp)
                                Spacer(Modifier.width(12.dp))
                                Text(str.t("מחפש מילים מ-6 מקורות...", "Searching lyrics across 6 sources..."), color = SpotifyWhite.copy(alpha = 0.85f), fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                            }
                        } else if (lyricDisplayRows.isEmpty()) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                Text(
                                    if (state.settings.offlineMode) str.t("אין מילים שמורות באופליין לשיר זה.", "No cached offline lyrics for this track.")
                                    else str.t("לא נמצאו מילים אוטומטית.", "Lyrics not found automatically."),
                                    color = SpotifyWhite.copy(alpha = 0.85f), fontSize = 14.sp, fontWeight = FontWeight.Medium
                                )
                                if (!state.settings.offlineMode) {
                                    TextButton(onClick = { showFullLyrics = true }) { Text(str.t("חיפוש ידני", "Manual Search"), color = SpotifyWhite, fontWeight = FontWeight.Bold) }
                                }
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxWidth().height(242.dp)) {
                                LazyColumn(
                                    state = inlineLyricsState,
                                    userScrollEnabled = false,
                                    contentPadding = PaddingValues(top = 10.dp, bottom = 115.dp),
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    itemsIndexed(lyricDisplayRows, key = { _, row -> "in_${row.keyId}" }) { idx, row ->
                                        val isCurrent = idx == activeRowIdx && state.positionMs >= row.timeMs
                                        val isPast = idx < activeRowIdx
                                        when (row) {
                                            is LyricDisplayRow.MelodyRow -> SpotifyMelodyRowItem(isCurrent, isPast, state.isPlaying, false) { vm.seekTo(row.timeMs) }
                                            is LyricDisplayRow.TextLine -> SpotifyAnimatedLyricLine(row.line, isCurrent, isPast, state.showTranslatedLyrics, state.lyricsFontScale, 18.5f, 20.5f, 12.5f) { vm.seekTo(row.timeMs) }
                                        }
                                    }
                                }
                                Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(20.dp).background(Brush.verticalGradient(listOf(lyricsCardBg, Color.Transparent))))
                                Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(34.dp).background(Brush.verticalGradient(listOf(Color.Transparent, lyricsCardBg))))
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // 2. Spotify-Style "מידע נוסף על [אמן]" (3 Visual Exploration Cards)
            if (!state.settings.offlineMode && !isLocalTrack) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = str.t("מידע נוסף על ${track.artist}", "Explore ${track.artist}"),
                            color = SpotifyWhite,
                            fontWeight = FontWeight.ExtraBold,
                            fontSize = 16.sp
                        )
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            ExploreMiniCard(
                                title = str.t("שירים של ${track.artist}", "Songs by ${track.artist}"),
                                artworkUrl = extras?.artistTopTrack?.artworkUrl?.takeIf { it.isNotBlank() } ?: realArtistPhoto,
                                onClick = {
                                    val top = extras?.artistTopTrack
                                    if (top != null) vm.playTrack(top, listOf(top, track))
                                    else vm.openArtistPage(track.artist, realArtistPhoto)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ExploreMiniCard(
                                title = str.t("דומה ל-${track.artist}", "Similar to ${track.artist}"),
                                artworkUrl = extras?.similarArtistTrack?.artworkUrl?.takeIf { it.isNotBlank() } ?: track.artworkUrl,
                                onClick = {
                                    val simA = extras?.similarArtistTrack
                                    if (simA != null) vm.playTrack(simA, listOf(simA, track))
                                    else vm.startSongOrArtistRadio(track)
                                },
                                modifier = Modifier.weight(1f)
                            )
                            ExploreMiniCard(
                                title = str.t("דומה ל-${track.title}", "Similar to ${track.title}"),
                                artworkUrl = extras?.similarSongTrack?.artworkUrl?.takeIf { it.isNotBlank() } ?: track.artworkUrl,
                                onClick = {
                                    val simS = extras?.similarSongTrack
                                    if (simS != null) vm.playTrack(simS, listOf(simS, track))
                                    else vm.startSongOrArtistRadio(track)
                                },
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(14.dp))
            }

            // 3. Spotify-Style "אודות האמן" Card with REAL Artist Photo!
            Card(
                modifier = Modifier.fillMaxWidth().spotifyPressClickable { vm.openArtistPage(track.artist, realArtistPhoto) },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)
            ) {
                Column {
                    Box(modifier = Modifier.fillMaxWidth().height(205.dp)) {
                        ArtworkImage(realArtistPhoto, track.artist, Modifier.fillMaxSize())
                        Box(
                            modifier = Modifier.fillMaxSize().background(
                                Brush.verticalGradient(
                                    0f to Color.Black.copy(alpha = 0.55f),
                                    0.45f to Color.Transparent,
                                    1f to SpotifyDarkSurface
                                )
                            )
                        )
                        Text(
                            text = str.t("אודות האמן", "About the artist"),
                            color = SpotifyWhite,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                        )
                    }
                    Column(modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                                Text(track.artist, color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 20.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.width(6.dp))
                                SpotifyVerifiedBadge(size = 18.dp)
                            }
                            OutlinedButton(
                                onClick = { vm.toggleFollowArtist(track.artist) },
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, if (isFollowedArtist) SpotifyGreen else SpotifyWhite.copy(alpha = 0.55f)),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(if (isFollowedArtist) str.following else str.follow, color = if (isFollowedArtist) SpotifyGreen else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        if (!extras?.artistSubscribers.isNullOrBlank()) {
                            Spacer(Modifier.height(3.dp))
                            Text(extras!!.artistSubscribers, color = SpotifySubtle, fontSize = 12.5.sp, fontWeight = FontWeight.Medium)
                        }
                        Spacer(Modifier.height(6.dp))
                        val playsCount = state.stats.artistPlayCounts[track.artist] ?: 1
                        val bioText = extras?.artistBio?.takeIf { it.isNotBlank() }
                            ?: str.t("לחץ לצפייה באלבומים, שירים אחרונים ודיסקוגרפיה מלאה • $playsCount השמעות אצלך", "Tap for full albums, latest releases & discography • $playsCount plays by you")
                        Text(bioText, color = SpotifySubtle, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("${track.artist} concerts tickets")}"))) } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50)) {
                                Icon(Icons.Rounded.Event, null, tint = SpotifyGreen, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text(str.concerts, color = SpotifyWhite, fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("${track.artist} official merch store")}"))) } }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(50)) {
                                Icon(Icons.Rounded.ShoppingBag, null, tint = SpotifyGreen, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(5.dp)); Text(str.merch, color = SpotifyWhite, fontSize = 12.sp)
                            }
                        }
                        if (!state.settings.offlineMode) {
                            Spacer(Modifier.height(8.dp))
                            Button(onClick = { vm.startSongOrArtistRadio(track) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyCard, contentColor = SpotifyWhite), shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Rounded.Radio, null, tint = SpotifyGreen, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("${str.startRadio} • ${track.artist}", fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))

            // 4. Spotify-Style "קרדיטים" Card (Main Artist + Follow + Composers/Writers/Producers + Show All!)
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(str.t("קרדיטים", "Credits"), color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Text(
                            text = str.t("הצגת כל האפשרויות", "Show all"),
                            color = SpotifyGreen,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { showAllCreditsSheet = true }
                        )
                    }
                    creditsList.take(3).forEach { entry ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { if (entry.isMainArtist) vm.openArtistPage(entry.name, realArtistPhoto) },
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                Text(entry.name, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(entry.roles, color = SpotifySubtle, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (entry.isMainArtist) {
                                val followedThis = state.followedArtists.contains(entry.name) || isFollowedArtist
                                OutlinedButton(
                                    onClick = { vm.toggleFollowArtist(entry.name) },
                                    shape = RoundedCornerShape(50),
                                    border = BorderStroke(1.dp, if (followedThis) SpotifyGreen else SpotifyWhite.copy(alpha = 0.55f)),
                                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                    modifier = Modifier.height(32.dp)
                                ) {
                                    Text(if (followedThis) str.following else str.t("מעקב", "Follow"), color = if (followedThis) SpotifyGreen else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                    HorizontalDivider(color = SpotifyCard)
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        val sizeInfo = if (isOffline) " • ${String.format("%.1f", vm.repo.getOfflineFileSizeMb(track.id))} MB" else ""
                        Text("${str.t("מנוע שמע", "Audio")}: $qualityLabel$sizeInfo", color = SpotifyGreen, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Text("${state.settings.playbackSpeed}x", color = SpotifySubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 5. Spotify-Style "עוד מידע על [אמן]" (Related Interviews & Podcast Episodes)
            if (!extras?.artistEpisodes.isNullOrEmpty() && !state.settings.offlineMode) {
                Spacer(Modifier.height(14.dp))
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(str.t("עוד מידע על ${track.artist}", "More about ${track.artist}"), color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        extras!!.artistEpisodes.forEach { ep ->
                            val epLiked = state.likedTracks.any { it.id == ep.id }
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable { vm.playTrack(ep, extras.artistEpisodes) }.padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkImage(ep.artworkUrl, ep.title, Modifier.size(54.dp))
                                Spacer(Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(ep.title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text("${str.t("פרק", "Episode")} • ${ep.artist}", color = SpotifySubtle, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                }
                                IconButton(onClick = { vm.toggleLike(ep) }, modifier = Modifier.size(34.dp)) {
                                    Icon(
                                        if (epLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                                        "Save Episode",
                                        tint = if (epLiked) SpotifyGreen else SpotifySubtle,
                                        modifier = Modifier.size(22.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(42.dp))
        }
    }

    // Full Credits & Specs Modal Sheet
    if (showAllCreditsSheet) {
        ModalBottomSheet(onDismissRequest = { showAllCreditsSheet = false }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
                Text(str.t("קרדיטים מלאים", "Full Song Credits"), fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(4.dp))
                Text(track.title, color = SpotifyGreen, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                if (track.album.isNotBlank()) Text("${str.t("אלבום", "Album")}: ${track.album}", color = SpotifySubtle, fontSize = 12.sp)
                HorizontalDivider(color = SpotifyCard, modifier = Modifier.padding(vertical = 12.dp))
                creditsList.forEach { c ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(c.name, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(c.roles, color = SpotifySubtle, fontSize = 12.5.sp)
                        }
                        if (c.isMainArtist) {
                            TextButton(onClick = { showAllCreditsSheet = false; vm.openArtistPage(c.name, realArtistPhoto) }) {
                                Text(str.t("לפרופיל", "Profile"), color = SpotifyGreen, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
                HorizontalDivider(color = SpotifyCard, modifier = Modifier.padding(vertical = 10.dp))
                Text("${str.t("מקור", "Source")}: ${track.source.label(str.isEn)}", color = SpotifySubtle, fontSize = 12.sp)
                Text("${str.t("איכות פעילה", "Stream")}: $qualityLabel • ${state.settings.playbackSpeed}x", color = SpotifySubtle, fontSize = 12.sp)
                if (isOffline) {
                    Text("${str.t("גודל קובץ אופליין", "Offline File Size")}: ${String.format("%.2f", vm.repo.getOfflineFileSizeMb(track.id))} MB", color = SpotifyGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(20.dp))
            }
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

    // Spotify-Style Drag-Handle Reorderable Queue Bottom Sheet!
    if (showQueueSheet) {
        val density = LocalDensity.current
        val rowHeightPx = remember(density) { with(density) { 60.dp.toPx() } }
        var draggedTrackId by remember { mutableStateOf<String?>(null) }
        var dragOffsetY by remember { mutableFloatStateOf(0f) }
        val latestQueue by rememberUpdatedState(state.queue)

        ModalBottomSheet(onDismissRequest = { showQueueSheet = false }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${str.queue} (${state.queue.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(str.t("החזק ≡ וגרור לסידור", "Drag ≡ to reorder"), color = SpotifySubtle, fontSize = 11.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                    itemsIndexed(state.queue, key = { idx, item -> "q_${item.id}_$idx" }) { idx, item ->
                        val isBeingDragged = draggedTrackId == item.id
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .zIndex(if (isBeingDragged) 10f else 0f)
                                .graphicsLayer {
                                    translationY = if (isBeingDragged) dragOffsetY else 0f
                                    scaleX = if (isBeingDragged) 1.02f else 1f
                                    scaleY = if (isBeingDragged) 1.02f else 1f
                                }
                                .clip(RoundedCornerShape(10.dp))
                                .background(if (isBeingDragged) SpotifyCard else Color.Transparent)
                                .clickable { vm.playTrack(item, state.queue) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Drag Handle on the side (just like Spotify!)
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .pointerInput(item.id) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggedTrackId = item.id
                                                dragOffsetY = 0f
                                            },
                                            onDragEnd = {
                                                draggedTrackId = null
                                                dragOffsetY = 0f
                                            },
                                            onDragCancel = {
                                                draggedTrackId = null
                                                dragOffsetY = 0f
                                            },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetY += dragAmount.y
                                                val steps = (dragOffsetY / rowHeightPx).roundToInt()
                                                if (steps != 0) {
                                                    val curIdx = latestQueue.indexOfFirst { it.id == item.id }
                                                    if (curIdx >= 0) {
                                                        val targetIdx = (curIdx + steps).coerceIn(0, latestQueue.lastIndex)
                                                        if (targetIdx != curIdx) {
                                                            vm.moveQueueItem(curIdx, targetIdx)
                                                            dragOffsetY -= (targetIdx - curIdx) * rowHeightPx
                                                        }
                                                    }
                                                }
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Rounded.DragHandle,
                                    contentDescription = "Drag to reorder",
                                    tint = if (isBeingDragged) SpotifyGreen else SpotifySubtle,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(Modifier.width(4.dp))
                            Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) {
                                if (idx == state.currentIndex) SpotifyPlayingBars(isPlaying = state.isPlaying, size = 14.dp)
                                else Text("${idx + 1}", color = SpotifySubtle, fontSize = 12.sp)
                            }
                            Spacer(Modifier.width(6.dp))
                            ArtworkImage(item.artworkUrl, item.title, Modifier.size(40.dp))
                            Spacer(Modifier.width(10.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(item.title, color = if (idx == state.currentIndex) SpotifyGreen else SpotifyWhite, fontWeight = if (idx == state.currentIndex) FontWeight.Bold else FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis, fontSize = 14.sp)
                                Text(if (item.isSmartInjected) "${item.artist} • Smart Shuffle" else item.artist, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1)
                            }
                            if (idx != state.currentIndex) {
                                IconButton(onClick = { vm.removeFromQueue(idx) }, modifier = Modifier.size(34.dp)) {
                                    Icon(Icons.Rounded.Close, "Remove", tint = SpotifySubtle, modifier = Modifier.size(18.dp))
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

    if (showFullLyrics && !state.isVideoMode) {
        var manualQuery by remember(track.id) { mutableStateOf("${track.artist} ${track.title}") }
        val activeRowIdx = remember(lyricDisplayRows, state.positionMs) {
            lyricDisplayRows.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
        }
        val lyricsListState = rememberLazyListState()
        val density = LocalDensity.current
        val fullTopOffsetPx = remember(density) { with(density) { 120.dp.roundToPx() } }

        val fullBg = Color(
            red = (accent.red * 1.4f).coerceAtMost(0.84f),
            green = (accent.green * 1.4f).coerceAtMost(0.84f),
            blue = (accent.blue * 1.4f).coerceAtMost(0.84f),
            alpha = 1f
        )

        LaunchedEffect(activeRowIdx, lyricDisplayRows.size) {
            if (lyricDisplayRows.isNotEmpty() && activeRowIdx in lyricDisplayRows.indices) {
                runCatching { smoothGlideToLyric(lyricsListState, activeRowIdx, fullTopOffsetPx) }
            }
        }

        ModalBottomSheet(onDismissRequest = { showFullLyrics = false }, containerColor = fullBg, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).imePadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = SpotifyWhite.copy(alpha = 0.78f), fontSize = 12.sp, maxLines = 1)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.setLyricsFontScale(state.lyricsFontScale - 0.1f) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.TextDecrease, "Smaller", tint = SpotifyWhite) }
                        IconButton(onClick = { vm.setLyricsFontScale(state.lyricsFontScale + 0.1f) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.TextIncrease, "Larger", tint = SpotifyWhite) }
                    }
                }

                if (state.lyrics.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        item {
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (state.showTranslatedLyrics) SpotifyWhite else Color.Black.copy(alpha = 0.35f))
                                    .clickable { vm.toggleTranslateLyrics() }
                                    .padding(horizontal = 12.dp, vertical = 6.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (state.isTranslatingLyrics) {
                                        CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 1.8.dp, modifier = Modifier.size(12.dp))
                                        Spacer(Modifier.width(5.dp))
                                    }
                                    Text(
                                        if (state.isTranslatingLyrics) str.translating else if (state.showTranslatedLyrics) str.showOriginal else str.translateLyrics,
                                        color = if (state.showTranslatedLyrics) Color.Black else SpotifyWhite,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                        }
                        items(LyricsLanguage.entries) { lang ->
                            val sel = state.showTranslatedLyrics && state.settings.lyricsTargetLanguage == lang
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (sel) SpotifyGreen else Color.Black.copy(alpha = 0.25f))
                                    .clickable { vm.toggleTranslateLyrics(forceTargetLang = lang) }
                                    .padding(horizontal = 11.dp, vertical = 6.dp)
                            ) { Text(lang.label(str.isEn), color = if (sel) Color.Black else SpotifyWhite, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                state.lyricsTranslateError?.let { err ->
                    LyricsTranslateErrorBanner(
                        errorMsg = err,
                        str = str,
                        onOpenSettings = { showFullLyrics = false; vm.openAiApiSettings() },
                        onDismiss = { vm.clearLyricsTranslateError() }
                    )
                }

                if (state.isLoadingLyrics) {
                    Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                        SpotifyMelodyDots(isPlaying = true, tint = SpotifyWhite, dotSize = 13.dp)
                    }
                } else if (lyricDisplayRows.isEmpty()) {
                    OutlinedTextField(value = manualQuery, onValueChange = { manualQuery = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { vm.retryLyrics(manualQuery) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                        Text(str.t("חפש מילים", "Search Lyrics"), fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.height(24.dp))
                } else {
                    Box(modifier = Modifier.fillMaxWidth().heightIn(max = 540.dp)) {
                        LazyColumn(state = lyricsListState, modifier = Modifier.fillMaxWidth(), contentPadding = PaddingValues(top = 18.dp, bottom = 180.dp)) {
                            itemsIndexed(lyricDisplayRows, key = { _, row -> "fl_${row.keyId}" }) { idx, row ->
                                val isCurrent = idx == activeRowIdx && state.positionMs >= row.timeMs
                                val isPast = idx < activeRowIdx
                                when (row) {
                                    is LyricDisplayRow.MelodyRow -> SpotifyMelodyRowItem(isCurrent, isPast, state.isPlaying, true) { vm.seekTo(row.timeMs) }
                                    is LyricDisplayRow.TextLine -> SpotifyAnimatedLyricLine(row.line, isCurrent, isPast, state.showTranslatedLyrics, state.lyricsFontScale, 22f, 25.5f, 14.5f) { vm.seekTo(row.timeMs) }
                                }
                            }
                        }
                        Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(22.dp).background(Brush.verticalGradient(listOf(fullBg, Color.Transparent))))
                        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(42.dp).background(Brush.verticalGradient(listOf(Color.Transparent, fullBg))))
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
            Row(verticalAlignment = Alignment.CenterVertically) {
                SpotifyConnectIcon(isConnectedExternal = true, tint = SpotifyGreen, size = 24.dp)
                Spacer(Modifier.width(10.dp))
                Text(str.devicesConnect, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(14.dp))

            devices.forEach { d ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (d.isActive) Color(0xFF1A3A2E) else SpotifyCard)
                        .spotifyPressClickable { vm.audioEngine.openSystemAudioSettings() }
                        .padding(horizontal = 14.dp, vertical = 13.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier.size(42.dp).clip(CircleShape)
                            .background(if (d.isActive) SpotifyGreen.copy(alpha = 0.16f) else SpotifyDarkSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        ConnectedDeviceVectorIcon(device = d, tint = if (d.isActive) SpotifyGreen else SpotifySubtle, size = 26.dp)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(d.name, color = if (d.isActive) SpotifyGreen else SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        Text(d.typeLabel(str.isEn), color = SpotifySubtle, fontSize = 12.sp)
                    }
                    if (d.isActive) SpotifyPlayingBars(isPlaying = state.isPlaying, color = SpotifyGreen, size = 16.dp)
                }
                Spacer(Modifier.height(8.dp))
            }

            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.VolumeDown, null, tint = SpotifySubtle, modifier = Modifier.size(20.dp))
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                    Slider(
                        value = volumeFrac,
                        onValueChange = { volumeFrac = it; vm.setVolumeFromDevicesSheet(it) },
                        modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                        colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen, inactiveTrackColor = SpotifyCard)
                    )
                }
                Icon(Icons.Rounded.VolumeUp, null, tint = SpotifyWhite, modifier = Modifier.size(20.dp))
            }

            OutlinedButton(onClick = { vm.audioEngine.openSystemAudioSettings() }, shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
                Text(str.t("החלף אוזניות / Bluetooth בהגדרות", "Switch Bluetooth / Audio Output"), color = SpotifyWhite)
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 16.dp), color = SpotifyCard)

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                Text("Jam • ${str.t("האזנה משותפת חיה", "Live Shared Listening")}", color = SpotifyGreen, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                if (state.jamState.active) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(if (state.jamState.liveConnected) SpotifyGreen else Color(0xFFFFB74D)))
                        Spacer(Modifier.width(5.dp))
                        Text(state.jamState.syncStatus.ifBlank { str.liveJamConnected }, color = SpotifySubtle, fontSize = 11.sp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))

            if (state.jamState.active) {
                val smartLink = SonifyShareLinks.jamUrl(state.jamState.code, state.currentTrack, state.positionMs)
                Card(colors = CardDefaults.cardColors(containerColor = SpotifyCard), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Column {
                                Text("${str.t("קוד חדר Jam", "Jam Room Code")}: ${state.jamState.code}", color = SpotifyGreen, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                                Text("${state.jamState.participants.size} ${str.t("משתתפים", "participants")}: ${state.jamState.participants.joinToString(", ")}", color = SpotifySubtle, fontSize = 12.sp)
                            }
                            IconButton(onClick = { vm.setShowDevicesSheet(false); vm.setShowCodeFor("Jam • ${state.jamState.code}" to smartLink) }) {
                                Icon(Icons.Rounded.QrCode2, "QR", tint = SpotifyWhite)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { clipboard.setText(AnnotatedString(smartLink)) }, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.ContentCopy, null, tint = SpotifyWhite, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(str.t("העתק קישור", "Copy Link"), color = SpotifyWhite, fontSize = 12.sp)
                            }
                            OutlinedButton(
                                onClick = {
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "הצטרף לחדר ה-Jam החי שלי ב-Sonify! קוד: ${state.jamState.code}\n$smartLink")
                                    }, "Share Jam"))
                                },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Rounded.Share, null, tint = SpotifyGreen, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(4.dp)); Text(str.t("שתף הזמנה", "Share Invite"), color = SpotifyWhite, fontSize = 12.sp)
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(str.t("אורחים יכולים לערוך ניגון ותור", "Guests can control playback & queue"), color = SpotifyWhite, fontSize = 13.sp)
                            Switch(checked = state.jamState.guestsCanControlQueue, onCheckedChange = { vm.toggleJamPermission("queue") }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = SpotifyGreen))
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(str.t("אורחים יכולים לשלוט בווליום", "Guests can control volume"), color = SpotifyWhite, fontSize = 13.sp)
                            Switch(checked = state.jamState.guestsCanControlVolume, onCheckedChange = { vm.toggleJamPermission("volume") }, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = SpotifyGreen))
                        }
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { vm.endJamSession() }, colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B6B), contentColor = Color.Black), shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
                            Text(str.endJam, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            } else {
                Text(str.t("מאזינים יחד בזמן אמת בלי צורך בחשבון ענן — פתח חדר ושתף קוד או QR.", "Listen together in real time with zero cloud accounts — start a room and share the code or QR."), color = SpotifySubtle, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Button(onClick = { vm.startJamSession() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
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
