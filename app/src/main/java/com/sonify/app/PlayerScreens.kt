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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
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
 * Spotify-exact Vector Download Icon:
 * - Idle: clean circle ring with sharp downward arrow
 * - Downloading: smooth green filling circular progress arc with inner square indicator
 * - Downloaded: solid SpotifyGreen circle with crisp black downward arrow
 */
@Composable
fun SpotifyDownloadRingButton(
    isOffline: Boolean,
    isDownloading: Boolean,
    progressPct: Int?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    idleTint: Color = SpotifySubtle
) {
    val targetFrac = when {
        isOffline -> 1f
        isDownloading -> ((progressPct ?: 6).coerceIn(5, 100)) / 100f
        else -> 0f
    }
    val animatedSweep by animateFloatAsState(
        targetValue = targetFrac * 360f,
        animationSpec = tween(durationMillis = 240, easing = FastOutSlowInEasing),
        label = "dl_ring_sweep"
    )

    Box(
        modifier = modifier
            .size(size + 12.dp)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(size)) {
            val w = this.size.width
            val h = this.size.height
            val cx = w / 2f
            val cy = h / 2f
            val ringStroke = (w * 0.085f).coerceAtLeast(1.5.dp.toPx())

            fun drawSpotifyDownArrow(color: Color, strokeWidth: Float) {
                val topY = h * 0.27f
                val tipY = h * 0.72f
                val headW = w * 0.21f
                val headY = h * 0.51f
                drawLine(
                    color = color,
                    start = Offset(cx, topY),
                    end = Offset(cx, tipY - strokeWidth * 0.25f),
                    strokeWidth = strokeWidth,
                    cap = StrokeCap.Round
                )
                val chevron = Path().apply {
                    moveTo(cx - headW, headY)
                    lineTo(cx, tipY)
                    lineTo(cx + headW, headY)
                }
                drawPath(
                    path = chevron,
                    color = color,
                    style = Stroke(width = strokeWidth, cap = StrokeCap.Round, join = StrokeJoin.Round)
                )
            }

            when {
                isOffline -> {
                    drawCircle(color = SpotifyGreen, radius = w / 2f)
                    drawSpotifyDownArrow(Color.Black, strokeWidth = (w * 0.105f).coerceAtLeast(1.8.dp.toPx()))
                }
                isDownloading -> {
                    val inset = ringStroke / 2f
                    val arcSize = Size(w - ringStroke, h - ringStroke)
                    drawArc(
                        color = SpotifyWhite.copy(alpha = 0.22f),
                        startAngle = 0f,
                        sweepAngle = 360f,
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = ringStroke)
                    )
                    drawArc(
                        color = SpotifyGreen,
                        startAngle = -90f,
                        sweepAngle = animatedSweep.coerceAtLeast(22f),
                        useCenter = false,
                        topLeft = Offset(inset, inset),
                        size = arcSize,
                        style = Stroke(width = ringStroke, cap = StrokeCap.Round)
                    )
                    // Inner Spotify-style small stop square while downloading
                    val sq = w * 0.28f
                    drawRoundRect(
                        color = SpotifyGreen,
                        topLeft = Offset(cx - sq / 2f, cy - sq / 2f),
                        size = Size(sq, sq),
                        cornerRadius = CornerRadius(2.dp.toPx(), 2.dp.toPx())
                    )
                }
                else -> {
                    drawCircle(
                        color = idleTint.copy(alpha = 0.85f),
                        radius = (w - ringStroke) / 2f,
                        style = Stroke(width = ringStroke)
                    )
                    drawSpotifyDownArrow(idleTint, strokeWidth = (w * 0.088f).coerceAtLeast(1.5.dp.toPx()))
                }
            }
        }
    }
}

/**
 * Spotify-exact Podcast Skip ±15 Seconds Circular Button (100% custom Canvas so it matches Spotify's icon).
 */
@Composable
private fun PodcastSkip15Button(
    isForward: Boolean,
    onClick: () -> Unit,
    tint: Color = SpotifyWhite
) {
    Box(
        modifier = Modifier
            .size(46.dp)
            .clip(CircleShape)
            .spotifyPressClickable(pressedScale = 0.88f, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.size(32.dp)) {
            val w = size.width
            val h = size.height
            val stroke = 2.1.dp.toPx()
            val inset = stroke + 2.dp.toPx()
            val arcSize = Size(w - inset * 2, h - inset * 2)
            if (isForward) {
                drawArc(
                    color = tint,
                    startAngle = -65f,
                    sweepAngle = 295f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                val arrow = Path().apply {
                    moveTo(w * 0.62f, h * 0.06f)
                    lineTo(w * 0.77f, h * 0.21f)
                    lineTo(w * 0.57f, h * 0.26f)
                }
                drawPath(arrow, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            } else {
                drawArc(
                    color = tint,
                    startAngle = -115f,
                    sweepAngle = -295f,
                    useCenter = false,
                    topLeft = Offset(inset, inset),
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
                val arrow = Path().apply {
                    moveTo(w * 0.38f, h * 0.06f)
                    lineTo(w * 0.23f, h * 0.21f)
                    lineTo(w * 0.43f, h * 0.26f)
                }
                drawPath(arrow, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        Text(
            text = "15",
            color = tint,
            fontSize = 10.sp,
            fontWeight = FontWeight.ExtraBold,
            modifier = Modifier.padding(top = 2.dp)
        )
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

private fun buildLyricDisplayRows(lyrics: List<LyricLine>, isPodcast: Boolean): List<LyricDisplayRow> {
    if (lyrics.isEmpty()) return emptyList()
    if (isPodcast) {
        return lyrics.mapIndexed { idx, l -> LyricDisplayRow.TextLine(l.timeMs, l, idx) }
    }
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
                animationSpec = tween(durationMillis = 480, easing = FastOutSlowInEasing)
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
    palette: LyricsContrastPalette,
    onClick: () -> Unit
) {
    val tint by animateColorAsState(
        targetValue = when {
            isCurrent -> palette.activeLine
            isPast -> palette.pastLine
            else -> palette.upcomingLine
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
        Icon(Icons.Rounded.MusicNote, "Instrumental", tint = tint, modifier = Modifier.size(if (isFullScreen) 24.dp else 20.dp))
        SpotifyMelodyDots(isPlaying = isCurrent && isPlaying, tint = tint, dotSize = if (isFullScreen) 11.dp else 9.dp)
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
    palette: LyricsContrastPalette,
    isPodcastTranscript: Boolean = false,
    onClick: () -> Unit
) {
    val isRtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val density = LocalDensity.current

    val lineColor by animateColorAsState(
        targetValue = when {
            isCurrent -> palette.activeLine
            isPast -> palette.pastLine
            else -> palette.upcomingLine
        },
        animationSpec = tween(durationMillis = 320, easing = FastOutSlowInEasing),
        label = "sp_lyric_color"
    )
    val lineScale by animateFloatAsState(
        targetValue = if (isCurrent && !isPodcastTranscript) 1.04f else 0.99f,
        animationSpec = spring(dampingRatio = 0.80f, stiffness = 260f),
        label = "sp_lyric_scale"
    )
    val nudgeY by animateFloatAsState(
        targetValue = when {
            isPast -> with(density) { (-2).dp.toPx() }
            isCurrent -> 0f
            else -> with(density) { 3.dp.toPx() }
        },
        animationSpec = spring(dampingRatio = 0.82f, stiffness = 240f),
        label = "sp_lyric_nudge"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                transformOrigin = TransformOrigin(if (isRtl) 1f else 0f, 0.5f)
                scaleX = lineScale
                scaleY = lineScale
                translationY = nudgeY
            }
            .clickable(onClick = onClick)
            .padding(vertical = if (isPodcastTranscript) 8.dp else 7.dp)
    ) {
        if (isPodcastTranscript) {
            Text(
                text = formatTime(line.timeMs),
                color = palette.headerText.copy(alpha = 0.72f),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(2.dp))
        }
        Text(
            text = line.text,
            color = lineColor,
            fontWeight = FontWeight.ExtraBold,
            fontFamily = FontFamily.SansSerif,
            letterSpacing = (-0.3).sp,
            lineHeight = ((if (isCurrent) activeFontSp else baseFontSp) * 1.32f * fontScale).sp,
            fontSize = ((if (isCurrent) activeFontSp else baseFontSp) * fontScale).sp
        )
        AnimatedVisibility(
            visible = showTranslation && !line.translatedText.isNullOrBlank(),
            enter = fadeIn(tween(240)) + expandVertically(tween(240)),
            exit = fadeOut(tween(180)) + shrinkVertically(tween(180))
        ) {
            Text(
                text = line.translatedText.orEmpty(),
                color = if (isCurrent) palette.translationActive else palette.translationInactive,
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
    val isPodcast = track.isPodcastOrAudiobook
    val extras = state.nowPlayingExtras?.takeIf { it.trackId == track.id }
    val isLiked = state.likedTracks.any { it.id == track.id }
    val isFollowedArtist = state.followedArtists.contains(track.artist)
    val isLocalTrack = track.source == StreamSource.LOCAL
    val isOffline = state.offlineTrackIds.contains(track.id)
    val isDownloading = state.downloadingIds.contains(track.id)
    val dlPct = state.downloadProgress[track.id]
    val accent by animateColorAsState(Color(state.dynamicAccentColor), animationSpec = tween(550), label = "now_accent")

    // WCAG Contrast Palette for Lyrics & Podcast Transcripts!
    val contrastPalette = remember(state.dynamicAccentColor, isPodcast) {
        computeLyricsContrastPalette(state.dynamicAccentColor, isPodcast = isPodcast)
    }

    val devices = remember(str.isEn, state.showDevicesSheet) { vm.audioEngine.getOutputDevices(str.isEn) }
    val activeExternal = devices.firstOrNull { it.isActive && !it.typeLabelEn.contains("Built-in", true) }

    val canvasPulse by rememberInfiniteTransition(label = "canvas_loop").animateFloat(
        initialValue = 1.0f, targetValue = 1.055f,
        animationSpec = infiniteRepeatable(tween(4800), AnimRepeatMode.Reverse), label = "pulse"
    )
    val useCanvasMotion = !isPodcast && state.settings.canvasEnabled && !state.settings.dataSaver && state.isPlaying && !state.isVideoMode

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
    var showPodcastCommentsSheet by remember { mutableStateOf(false) }

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
    val canSwitchVideo = track.source == StreamSource.YOUTUBE && !state.settings.offlineMode

    if (isFullscreenVideo && state.isVideoMode && !state.settings.offlineMode) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            if (state.isUsingWebEngine) {
                Box(modifier = Modifier.fillMaxHeight().aspectRatio(16f / 9f)) {
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

    val lyricDisplayRows = remember(state.lyrics, isPodcast) { buildLyricDisplayRows(state.lyrics, isPodcast) }
    val realArtistPhoto = extras?.artistPhotoUrl?.takeIf { it.isNotBlank() } ?: track.artworkUrl
    val creditsList = extras?.credits?.takeIf { it.isNotEmpty() }
        ?: listOf(TrackCreditEntry(track.artist, str.t("האמן/ית הראשי/ת", "Main Artist"), isMainArtist = true))
    val epComments = state.podcastComments[track.id].orEmpty()

    Box(
        modifier = Modifier.fillMaxSize()
            .background(Brush.verticalGradient(listOf(if (isPodcast) Color(0xFF1A1412) else accent, Color(0xFF121212), SpotifyBlack)))
            .statusBarsPadding().navigationBarsPadding()
    ) {
        Column(modifier = Modifier.fillMaxSize().verticalScroll(scrollState).padding(horizontal = 20.dp, vertical = 8.dp)) {
            // Top Bar (Podcast vs Song Header)
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                IconButton(onClick = { vm.setShowNowPlaying(false) }) { Icon(Icons.Rounded.KeyboardArrowDown, "Minimize", tint = SpotifyWhite, modifier = Modifier.size(30.dp)) }
                Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f).padding(horizontal = 6.dp)) {
                    Text(
                        text = when {
                            isPodcast -> str.t("משמיע מפודקאסט", "PLAYING FROM PODCAST")
                            state.isAiDjActive -> "AI DJ • ${state.activeDjStyleLabel.ifBlank { str.nowPlaying }}"
                            else -> str.nowPlaying.uppercase()
                        },
                        color = SpotifyWhite.copy(alpha = 0.72f),
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.8.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = when {
                            isPodcast -> track.artist
                            state.settings.offlineMode -> str.t("אופליין • אודיו מקומי", "OFFLINE • Local Audio")
                            state.isWeakNetworkActive -> "$qualityLabel • ${str.t("הגנת רשת חלשה", "Weak-Net Shield")}"
                            else -> qualityLabel
                        },
                        color = if (!isPodcast && (state.settings.offlineMode || state.resolvedStreams?.isLossless == true || state.resolvedStreams?.codecLabel == "DISK CACHE")) SpotifyGreen else SpotifyWhite,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Row {
                    if (!isPodcast) {
                        IconButton(onClick = { showSpeedSheet = true }) {
                            Text("${state.settings.playbackSpeed}x", color = if (state.settings.playbackSpeed != 1f) SpotifyGreen else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = { showSleepSheet = true }) {
                            Icon(Icons.Rounded.Bedtime, str.sleepTimer, tint = if (state.sleepMinutesLeft != null || state.sleepEndOfTrack) SpotifyGreen else SpotifyWhite)
                        }
                    }
                    IconButton(onClick = { vm.openTrackOptions(track) }) { Icon(Icons.Rounded.MoreVert, "More", tint = SpotifyWhite) }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Cover Art / Video Frame (16:9 widescreen for podcasts & video mode, 1:1 square for songs!)
            Box(
                modifier = Modifier.fillMaxWidth().aspectRatio(if (state.isVideoMode || isPodcast) 16f / 9f else 1f)
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
                                if (state.isBusyLoading || state.isFindingVideoClip) CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 3.dp)
                                else if (!state.isPlaying) {
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
                            Icon(if (state.isVideoMode) Icons.Rounded.Headphones else Icons.Rounded.OndemandVideo, null, tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                        }
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = when {
                                state.isFindingVideoClip -> str.findingVideo
                                state.isVideoMode -> str.switchToAudio
                                isPodcast -> str.t("מעבר לווידאו", "Switch to Video")
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

            if (state.isAiDjActive && !isPodcast) {
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

            Spacer(Modifier.height(18.dp))

            // Title + Thumbnail (in Podcast Mode) + Spotify Download Ring + Like Button
            val likeScale by animateFloatAsState(if (isLiked) 1.12f else 1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy), label = "like_btn")
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (isPodcast) {
                    ArtworkImage(track.artworkUrl, track.artist, Modifier.size(46.dp))
                    Spacer(Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                    Text(
                        track.title,
                        color = SpotifyWhite,
                        fontSize = if (isPodcast) 18.sp else 22.sp,
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
                            fontSize = if (isPodcast) 13.5.sp else 15.sp,
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
                        size = 24.dp,
                        idleTint = SpotifyWhite.copy(alpha = 0.85f)
                    )
                }
                IconButton(onClick = { vm.toggleLike(track) }, modifier = Modifier.graphicsLayer { scaleX = likeScale; scaleY = likeScale }) {
                    Icon(if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, "Save", tint = if (isLiked) SpotifyGreen else SpotifyWhite, modifier = Modifier.size(28.dp))
                }
            }

            Spacer(Modifier.height(14.dp))

            // Seek Bar & Transport Controls (Distinct Podcast 15s± Layout vs Song Layout!)
            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                val duration = state.durationMs.coerceAtLeast(1L)
                val progressFrac = if (isScrubbing) scrubFraction else (state.positionMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
                val shownPos = (progressFrac * duration).toLong()

                SpotifySeekBar(progressFrac, onScrubChange = { isScrubbing = true; scrubFraction = it }, onScrubFinished = { isScrubbing = false; vm.seekTo((it * duration).toLong()) })
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatTime(shownPos), color = SpotifyWhite.copy(alpha = 0.72f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                    Text(formatTime(state.durationMs), color = SpotifyWhite.copy(alpha = 0.72f), fontSize = 11.sp, fontWeight = FontWeight.Medium)
                }

                Spacer(Modifier.height(12.dp))
                val playBtnSize = if (state.settings.carModeOptimized) 78.dp else 66.dp

                if (isPodcast) {
                    // SPOTIFY PODCAST TRANSPORT BAR: [Speed 1x]  [Replay 15s]  [Big Play/Pause]  [Forward 15s]  [Sleep Timer]
                    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        Box(
                            modifier = Modifier.size(44.dp).clip(CircleShape).clickable { showSpeedSheet = true },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                "${state.settings.playbackSpeed}x",
                                color = if (state.settings.playbackSpeed != 1f) SpotifyGreen else SpotifyWhite,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold
                            )
                        }
                        PodcastSkip15Button(isForward = false, onClick = { vm.skipByDelta(-15_000L) })
                        Box(
                            modifier = Modifier.size(playBtnSize).clip(CircleShape).background(SpotifyWhite)
                                .spotifyPressClickable(pressedScale = 0.90f) { vm.togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            if (state.isBusyLoading) CircularProgressIndicator(color = Color.Black, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                            else Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "Play/Pause", tint = Color.Black, modifier = Modifier.size(38.dp))
                        }
                        PodcastSkip15Button(isForward = true, onClick = { vm.skipByDelta(15_000L) })
                        IconButton(onClick = { showSleepSheet = true }) {
                            Icon(
                                Icons.Rounded.Timer,
                                str.sleepTimer,
                                tint = if (state.sleepMinutesLeft != null || state.sleepEndOfTrack) SpotifyGreen else SpotifyWhite,
                                modifier = Modifier.size(25.dp)
                            )
                        }
                    }
                } else {
                    // SPOTIFY MUSIC TRANSPORT BAR
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
            }

            Spacer(Modifier.height(14.dp))

            // Devices, QR, Real Clickable Share Link & Queue Row
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
                    if (isPodcast) {
                        IconButton(onClick = { vm.cycleShuffleMode() }) {
                            Icon(Icons.Rounded.Shuffle, str.shuffle, tint = if (state.shuffleEnabled) SpotifyGreen else SpotifyWhite.copy(alpha = 0.75f), modifier = Modifier.size(21.dp))
                        }
                    }
                    IconButton(onClick = { vm.setShowCodeFor(track.title to SonifyShareLinks.trackUrl(track, state.positionMs)) }) {
                        Icon(Icons.Rounded.QrCode2, "Code", tint = SpotifyWhite.copy(alpha = 0.85f))
                    }
                    IconButton(onClick = {
                        val shareLink = SonifyShareLinks.trackUrl(track, state.positionMs)
                        val shareText = "${track.title} - ${track.artist}\n$shareLink"
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"; putExtra(Intent.EXTRA_TEXT, shareText)
                        }, str.t("שתף", "Share")))
                    }) { Icon(Icons.Rounded.Share, "Share", tint = SpotifyWhite.copy(alpha = 0.85f)) }
                    IconButton(onClick = { showQueueSheet = true }) { Icon(Icons.Rounded.QueueMusic, str.queue, tint = SpotifyWhite.copy(alpha = 0.85f)) }
                }
            }

            // =========================================================================
            // PODCAST-ONLY SCROLLABLE EXPERIENCE (Comments, Transcript, Show Info, Episode Info, Related!)
            // =========================================================================
            if (isPodcast) {
                Spacer(Modifier.height(16.dp))

                // 1. Podcast Comments Card ("הערות")
                Card(
                    modifier = Modifier.fillMaxWidth().clickable { showPodcastCommentsSheet = true },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(str.t("הערות", "Comments"), color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Text(
                                str.t("הצגת הכול (${epComments.size})", "Show all (${epComments.size})"),
                                color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp
                            )
                        }
                        Spacer(Modifier.height(10.dp))
                        val latestComment = epComments.firstOrNull()
                        if (latestComment != null) {
                            Row(verticalAlignment = Alignment.Top) {
                                Box(Modifier.size(34.dp).clip(CircleShape).background(SpotifyCard), contentAlignment = Alignment.Center) {
                                    Text(latestComment.author.firstOrNull()?.uppercase() ?: "S", color = SpotifyGreen, fontWeight = FontWeight.Black)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(latestComment.author, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(latestComment.timeAgo, color = SpotifySubtle, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(latestComment.text, color = SpotifyWhite.copy(alpha = 0.9f), fontSize = 13.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (latestComment.timestampSec != null) {
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            formatTime(latestComment.timestampSec * 1000L),
                                            color = SpotifyGreen, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                                            modifier = Modifier.clickable { vm.seekTo(latestComment.timestampSec * 1000L) }
                                        )
                                    }
                                }
                            }
                        } else {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Rounded.ChatBubbleOutline, null, tint = SpotifySubtle, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(str.t("הוסף הערה או סימניית זמן לפרק הזה...", "Add a comment or timestamp note for this episode..."), color = SpotifySubtle, fontSize = 13.sp)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 2. Podcast Episode Transcript Card ("תמלול הפרק" with WCAG Contrast Engine!)
                val activeRowIdx = remember(lyricDisplayRows, state.positionMs) {
                    lyricDisplayRows.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
                }
                val inlinePodState = rememberLazyListState()
                val density = LocalDensity.current
                val podOffsetPx = remember(density) { with(density) { 42.dp.roundToPx() } }

                LaunchedEffect(track.id) { runCatching { inlinePodState.scrollToItem(0) } }
                LaunchedEffect(activeRowIdx, lyricDisplayRows.size) {
                    if (lyricDisplayRows.isNotEmpty() && activeRowIdx in lyricDisplayRows.indices) {
                        runCatching { smoothGlideToLyric(inlinePodState, activeRowIdx, podOffsetPx) }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = contrastPalette.cardBackground)) {
                    Column(modifier = Modifier.padding(18.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(str.t("תמלול הפרק", "Episode Transcript"), color = contrastPalette.headerText, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (lyricDisplayRows.isNotEmpty()) {
                                    Box(
                                        modifier = Modifier.size(32.dp).clip(CircleShape).background(contrastPalette.pillBackground)
                                            .clickable {
                                                val snippet = lyricDisplayRows.filterIsInstance<LyricDisplayRow.TextLine>()
                                                    .take(8).joinToString("\n") { "${formatTime(it.timeMs)} ${it.line.text}" }
                                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                                    type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${track.title}\n\n$snippet")
                                                }, "Transcript"))
                                            },
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Rounded.Share, "Share Transcript", tint = contrastPalette.pillText, modifier = Modifier.size(16.dp)) }
                                    Box(
                                        modifier = Modifier.size(32.dp).clip(CircleShape).background(contrastPalette.pillBackground)
                                            .clickable { showFullLyrics = true },
                                        contentAlignment = Alignment.Center
                                    ) { Icon(Icons.Rounded.OpenInFull, str.fullScreen, tint = contrastPalette.pillText, modifier = Modifier.size(16.dp)) }
                                }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        if (state.isLoadingLyrics) {
                            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 14.dp)) {
                                SpotifyMelodyDots(isPlaying = true, tint = contrastPalette.activeLine, dotSize = 9.dp)
                                Spacer(Modifier.width(10.dp))
                                Text(str.t("טוען תמלול מסונכרן לפרק...", "Loading synced episode transcript..."), color = contrastPalette.pastLine, fontSize = 13.5.sp)
                            }
                        } else if (lyricDisplayRows.isEmpty()) {
                            Text(str.t("אין תמלול אוטומטי זמין לפרק זה.", "No transcript available for this episode."), color = contrastPalette.pastLine, fontSize = 13.5.sp)
                        } else {
                            Box(modifier = Modifier.fillMaxWidth().height(235.dp)) {
                                LazyColumn(state = inlinePodState, userScrollEnabled = false, contentPadding = PaddingValues(top = 6.dp, bottom = 100.dp), modifier = Modifier.fillMaxSize()) {
                                    itemsIndexed(lyricDisplayRows, key = { _, r -> "pt_${r.keyId}" }) { idx, row ->
                                        if (row is LyricDisplayRow.TextLine) {
                                            SpotifyAnimatedLyricLine(
                                                line = row.line,
                                                isCurrent = idx == activeRowIdx && state.positionMs >= row.timeMs,
                                                isPast = idx < activeRowIdx,
                                                showTranslation = state.showTranslatedLyrics,
                                                fontScale = state.lyricsFontScale,
                                                baseFontSp = 16.5f,
                                                activeFontSp = 18f,
                                                translationFontSp = 12.5f,
                                                palette = contrastPalette,
                                                isPodcastTranscript = true
                                            ) { vm.seekTo(row.timeMs) }
                                        }
                                    }
                                }
                                Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(36.dp).background(Brush.verticalGradient(listOf(Color.Transparent, contrastPalette.cardBackground))))
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 3. About the Podcast Card ("מידע כללי על הפודקאסט")
                Card(
                    modifier = Modifier.fillMaxWidth().spotifyPressClickable { vm.openArtistPage(track.artist, realArtistPhoto) },
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(str.t("מידע כללי על הפודקאסט", "About the podcast"), color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            ArtworkImage(realArtistPhoto, track.artist, Modifier.size(56.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.artist, color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (!extras?.artistSubscribers.isNullOrBlank()) {
                                    Text(extras!!.artistSubscribers, color = SpotifySubtle, fontSize = 12.sp)
                                }
                            }
                            OutlinedButton(
                                onClick = { vm.toggleFollowArtist(track.artist) },
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, if (isFollowedArtist) SpotifyGreen else SpotifyWhite.copy(alpha = 0.55f)),
                                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(if (isFollowedArtist) str.following else str.t("מעקב", "Follow"), color = if (isFollowedArtist) SpotifyGreen else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                        val showBio = extras?.artistBio?.takeIf { it.isNotBlank() }
                        if (!showBio.isNullOrBlank()) {
                            Spacer(Modifier.height(10.dp))
                            Text(showBio, color = SpotifySubtle, fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 4. About the Episode Card ("מידע כללי על הפרק")
                Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(str.t("מידע כללי על הפרק", "About the episode"), color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                        Spacer(Modifier.height(10.dp))
                        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Column(modifier = Modifier.weight(1f).padding(end = 8.dp)) {
                                Text(track.title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                val metaLine = buildList {
                                    extras?.episodePublishDate?.takeIf { it.isNotBlank() }?.let { add(it) }
                                    if (track.durationMs > 0) add(formatTime(track.durationMs))
                                }.joinToString(" • ")
                                if (metaLine.isNotBlank()) {
                                    Spacer(Modifier.height(3.dp))
                                    Text(metaLine, color = SpotifySubtle, fontSize = 12.sp)
                                }
                            }
                            IconButton(onClick = { vm.toggleLike(track) }) {
                                Icon(if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline, "Save", tint = if (isLiked) SpotifyGreen else SpotifyWhite, modifier = Modifier.size(26.dp))
                            }
                        }
                        val epDesc = extras?.episodeDescription?.takeIf { it.isNotBlank() }
                        if (!epDesc.isNullOrBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(epDesc, color = SpotifySubtle, fontSize = 12.5.sp, lineHeight = 18.sp, maxLines = 5, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // 5. Explore Related Episodes ("עיון בפרקים שקשורים לנושא")
                if (!extras?.artistEpisodes.isNullOrEmpty() && !state.settings.offlineMode) {
                    Spacer(Modifier.height(14.dp))
                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(str.t("עיון בפרקים שקשורים לנושא", "Explore related episodes"), color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                            Spacer(Modifier.height(12.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                items(extras!!.artistEpisodes, key = { "relp_${it.id}" }) { ep ->
                                    ExploreMiniCard(
                                        title = ep.title,
                                        artworkUrl = ep.artworkUrl,
                                        onClick = { vm.playTrack(ep, extras.artistEpisodes) },
                                        modifier = Modifier.width(134.dp).height(176.dp)
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // =========================================================================
                // MUSIC NOW PLAYING SCROLLABLE EXPERIENCE (WCAG Lyrics, Samples Feed, Artist, Live Events, Credits)
                // =========================================================================

                // 1. Synced Lyrics Card with WCAG Contrast Engine
                if (!state.isVideoMode) {
                    Spacer(Modifier.height(18.dp))
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

                    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = contrastPalette.cardBackground)) {
                        Column(modifier = Modifier.padding(18.dp)) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text(str.syncedLyrics, color = contrastPalette.headerText, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, fontFamily = FontFamily.SansSerif)
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (state.lyrics.isNotEmpty()) {
                                        Box(
                                            modifier = Modifier.clip(RoundedCornerShape(50))
                                                .background(if (state.showTranslatedLyrics) contrastPalette.activeLine else contrastPalette.pillBackground)
                                                .clickable { vm.toggleTranslateLyrics() }
                                                .padding(horizontal = 10.dp, vertical = 5.dp)
                                        ) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                if (state.isTranslatingLyrics) {
                                                    CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 1.8.dp, modifier = Modifier.size(12.dp))
                                                    Spacer(Modifier.width(5.dp))
                                                } else {
                                                    Icon(Icons.Rounded.Translate, null, tint = if (state.showTranslatedLyrics) contrastPalette.cardBackground else contrastPalette.pillText, modifier = Modifier.size(14.dp))
                                                    Spacer(Modifier.width(4.dp))
                                                }
                                                Text(
                                                    text = when {
                                                        state.isTranslatingLyrics -> str.translating
                                                        state.showTranslatedLyrics -> str.showOriginal
                                                        else -> str.translateLyrics
                                                    },
                                                    color = if (state.showTranslatedLyrics) contrastPalette.cardBackground else contrastPalette.pillText,
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold
                                                )
                                            }
                                        }
                                        Box(
                                            modifier = Modifier.size(30.dp).clip(CircleShape).background(contrastPalette.pillBackground)
                                                .clickable { showFullLyrics = true },
                                            contentAlignment = Alignment.Center
                                        ) { Icon(Icons.Rounded.OpenInFull, str.fullScreen, tint = contrastPalette.pillText, modifier = Modifier.size(15.dp)) }
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
                                    SpotifyMelodyDots(isPlaying = true, tint = contrastPalette.activeLine, dotSize = 9.dp)
                                    Spacer(Modifier.width(12.dp))
                                    Text(str.t("מחפש מילים מ-8 מקורות במקביל...", "Searching lyrics across 8 sources..."), color = contrastPalette.pastLine, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                }
                            } else if (lyricDisplayRows.isEmpty()) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                                    Text(
                                        if (state.settings.offlineMode) str.t("אין מילים שמורות באופליין לשיר זה.", "No cached offline lyrics for this track.")
                                        else str.t("לא נמצאו מילים אוטומטית.", "Lyrics not found automatically."),
                                        color = contrastPalette.pastLine, fontSize = 14.sp, fontWeight = FontWeight.Medium
                                    )
                                    if (!state.settings.offlineMode) {
                                        TextButton(onClick = { showFullLyrics = true }) { Text(str.t("חיפוש ידני", "Manual Search"), color = contrastPalette.headerText, fontWeight = FontWeight.Bold) }
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
                                                is LyricDisplayRow.MelodyRow -> SpotifyMelodyRowItem(isCurrent, isPast, state.isPlaying, false, contrastPalette) { vm.seekTo(row.timeMs) }
                                                is LyricDisplayRow.TextLine -> SpotifyAnimatedLyricLine(row.line, isCurrent, isPast, state.showTranslatedLyrics, state.lyricsFontScale, 18.5f, 20.5f, 12.5f, contrastPalette) { vm.seekTo(row.timeMs) }
                                            }
                                        }
                                    }
                                    Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(20.dp).background(Brush.verticalGradient(listOf(contrastPalette.cardBackground, Color.Transparent))))
                                    Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(34.dp).background(Brush.verticalGradient(listOf(Color.Transparent, contrastPalette.cardBackground))))
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 2. "מידע נוסף על [אמן]" -> Tapping ANY card launches the Vertical Song Samples Discovery Screen!
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
                                    onClick = { vm.openSamplesFeed("ARTIST_SONGS", track) },
                                    modifier = Modifier.weight(1f)
                                )
                                ExploreMiniCard(
                                    title = str.t("דומה ל-${track.artist}", "Similar to ${track.artist}"),
                                    artworkUrl = extras?.similarArtistTrack?.artworkUrl?.takeIf { it.isNotBlank() } ?: track.artworkUrl,
                                    onClick = { vm.openSamplesFeed("SIMILAR_ARTIST", track) },
                                    modifier = Modifier.weight(1f)
                                )
                                ExploreMiniCard(
                                    title = str.t("דומה ל-${track.title}", "Similar to ${track.title}"),
                                    artworkUrl = extras?.similarSongTrack?.artworkUrl?.takeIf { it.isNotBlank() } ?: track.artworkUrl,
                                    onClick = { vm.openSamplesFeed("SIMILAR_SONG", track) },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // 3. "מידע על האמן או האמנית" Card with Real Artist Photo
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
                                text = str.t("מידע על האמן או האמנית", "About the artist"),
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
                            if (!state.settings.offlineMode) {
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = { vm.startSongOrArtistRadio(track) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyCard, contentColor = SpotifyWhite), shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
                                    Icon(Icons.Rounded.Radio, null, tint = SpotifyGreen, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("${str.startRadio} • ${track.artist}", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                // 4. Spotify-Style "אירועים חיים" (Live Events) Visual Card
                if (!state.settings.offlineMode) {
                    Spacer(Modifier.height(14.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth().spotifyPressClickable {
                            runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("${track.artist} concerts tour tickets")}"))) }
                        },
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)
                    ) {
                        Box(modifier = Modifier.fillMaxWidth().height(200.dp)) {
                            ArtworkImage(realArtistPhoto, track.artist, Modifier.fillMaxSize())
                            Box(
                                modifier = Modifier.fillMaxSize().background(
                                    Brush.verticalGradient(
                                        0f to Color.Black.copy(alpha = 0.55f),
                                        0.45f to Color.Black.copy(alpha = 0.18f),
                                        1f to Color.Black.copy(alpha = 0.90f)
                                    )
                                )
                            )
                            Text(
                                text = str.t("אירועים חיים", "Live events"),
                                color = SpotifyWhite,
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                modifier = Modifier.align(Alignment.TopStart).padding(16.dp)
                            )
                            Row(
                                modifier = Modifier.align(Alignment.BottomStart).fillMaxWidth().padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
                                    Text(track.artist, color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(str.t("הופעות וכרטיסים קרובים", "Upcoming concerts & tour dates"), color = SpotifyWhite.copy(alpha = 0.78f), fontSize = 12.sp)
                                }
                                OutlinedButton(
                                    onClick = {
                                        runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${Uri.encode("${track.artist} concerts tour tickets")}"))) }
                                    },
                                    shape = RoundedCornerShape(50),
                                    border = BorderStroke(1.dp, SpotifyWhite.copy(alpha = 0.8f)),
                                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                    modifier = Modifier.height(34.dp)
                                ) {
                                    Text(str.t("איתור כרטיסים", "Find tickets"), color = SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // 5. "קרדיטים" Card
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

                // 6. "עוד מידע על [אמן]" (Related Interviews & Podcast Episodes)
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
            }

            Spacer(Modifier.height(42.dp))
        }
    }

    // Podcast Comments Modal Sheet
    if (showPodcastCommentsSheet && isPodcast) {
        var commentInput by remember { mutableStateOf("") }
        var attachTimestamp by remember { mutableStateOf(true) }

        ModalBottomSheet(onDismissRequest = { showPodcastCommentsSheet = false }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp).imePadding()) {
                Text("${str.t("הערות לפרק", "Episode Comments")} (${epComments.size})", fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                Spacer(Modifier.height(10.dp))

                if (epComments.isEmpty()) {
                    Text(str.t("עדיין אין הערות. כתוב את ההערה הראשונה!", "No comments yet. Write the first note!"), color = SpotifySubtle, fontSize = 13.sp, modifier = Modifier.padding(vertical = 18.dp))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 320.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(epComments, key = { it.id }) { c ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(SpotifyCard).padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Box(Modifier.size(34.dp).clip(CircleShape).background(SpotifyDarkSurface), contentAlignment = Alignment.Center) {
                                    Text(c.author.firstOrNull()?.uppercase() ?: "S", color = SpotifyGreen, fontWeight = FontWeight.Black)
                                }
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        Text(c.author, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                        Text(c.timeAgo, color = SpotifySubtle, fontSize = 11.sp)
                                    }
                                    Spacer(Modifier.height(3.dp))
                                    Text(c.text, color = SpotifyWhite, fontSize = 13.5.sp)
                                    Spacer(Modifier.height(6.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        if (c.timestampSec != null) {
                                            Text(
                                                "⏱ ${formatTime(c.timestampSec * 1000L)}",
                                                color = SpotifyGreen, fontSize = 11.5.sp, fontWeight = FontWeight.Bold,
                                                modifier = Modifier.clickable { vm.seekTo(c.timestampSec * 1000L); showPodcastCommentsSheet = false }
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { vm.likePodcastComment(track.id, c.id) }
                                        ) {
                                            Icon(Icons.Rounded.ThumbUp, null, tint = SpotifySubtle, modifier = Modifier.size(14.dp))
                                            Spacer(Modifier.width(4.dp))
                                            Text("${c.likes}", color = SpotifySubtle, fontSize = 12.sp)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = attachTimestamp, onCheckedChange = { attachTimestamp = it }, colors = CheckboxDefaults.colors(checkedColor = SpotifyGreen))
                    Text("${str.t("צרף חותמת זמן", "Attach timestamp")} (${formatTime(state.positionMs)})", color = SpotifySubtle, fontSize = 12.sp)
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = commentInput,
                        onValueChange = { commentInput = it },
                        placeholder = { Text(str.t("כתוב הערה לפרק...", "Write a comment...")) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            if (commentInput.isNotBlank()) {
                                vm.addPodcastComment(track.id, commentInput, attachTimestamp)
                                commentInput = ""
                            }
                        },
                        modifier = Modifier.size(46.dp).clip(CircleShape).background(SpotifyGreen)
                    ) { Icon(Icons.Rounded.Send, "Post", tint = Color.Black) }
                }
                Spacer(Modifier.height(20.dp))
            }
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

    // Smooth Drag-Handle Reorderable Queue Sheet (stable keys + locked scroll while dragging)
    if (showQueueSheet) {
        val density = LocalDensity.current
        val rowHeightPx = remember(density) { with(density) { 60.dp.toPx() } }
        var draggedKey by remember { mutableStateOf<String?>(null) }
        var dragOffsetY by remember { mutableFloatStateOf(0f) }

        val keyedQueue = remember(state.queue) {
            val counts = HashMap<String, Int>()
            state.queue.map { trk ->
                val occ = counts.getOrDefault(trk.id, 0)
                counts[trk.id] = occ + 1
                "${trk.id}#$occ" to trk
            }
        }
        val latestKeyedQueue by rememberUpdatedState(keyedQueue)

        ModalBottomSheet(onDismissRequest = { draggedKey = null; showQueueSheet = false }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("${str.queue} (${state.queue.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    Text(str.t("החזק ≡ וגרור לסידור", "Drag ≡ to reorder"), color = SpotifySubtle, fontSize = 11.5.sp)
                }
                Spacer(Modifier.height(10.dp))
                LazyColumn(userScrollEnabled = draggedKey == null, modifier = Modifier.heightIn(max = 460.dp)) {
                    itemsIndexed(keyedQueue, key = { _, pair -> pair.first }) { idx, (rowKey, item) ->
                        val isBeingDragged = draggedKey == rowKey
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
                                .clickable { if (draggedKey == null) vm.playTrack(item, state.queue) }
                                .padding(vertical = 8.dp, horizontal = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .pointerInput(rowKey) {
                                        detectDragGestures(
                                            onDragStart = { draggedKey = rowKey; dragOffsetY = 0f },
                                            onDragEnd = { draggedKey = null; dragOffsetY = 0f },
                                            onDragCancel = { draggedKey = null; dragOffsetY = 0f },
                                            onDrag = { change, dragAmount ->
                                                change.consume()
                                                dragOffsetY += dragAmount.y
                                                val steps = (dragOffsetY / rowHeightPx).roundToInt()
                                                if (steps != 0) {
                                                    val curList = latestKeyedQueue
                                                    val curIdx = curList.indexOfFirst { it.first == rowKey }
                                                    if (curIdx >= 0) {
                                                        val targetIdx = (curIdx + steps).coerceIn(0, curList.lastIndex)
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
                                Icon(Icons.Rounded.DragHandle, "Drag", tint = if (isBeingDragged) SpotifyGreen else SpotifySubtle, modifier = Modifier.size(22.dp))
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
                Text(str.t("בסיום הפרק/השיר הנוכחי", "End of current item"), color = SpotifyGreen, fontSize = 16.sp, modifier = Modifier.fillMaxWidth().clickable { vm.setSleepEndOfTrack(); showSleepSheet = false }.padding(vertical = 10.dp))
                if (state.sleepMinutesLeft != null || state.sleepEndOfTrack) {
                    Text(str.t("בטל טיימר שינה", "Turn off timer"), color = Color(0xFFFF6B6B), fontSize = 15.sp, modifier = Modifier.fillMaxWidth().clickable { vm.setSleepTimer(0); showSleepSheet = false }.padding(vertical = 10.dp))
                }
                Spacer(Modifier.height(16.dp))
            }
        }
    }

    // Full-Screen Synced Lyrics / Podcast Transcript Sheet with WCAG Contrast Engine!
    if (showFullLyrics && !state.isVideoMode) {
        var manualQuery by remember(track.id) { mutableStateOf("${track.artist} ${track.title}") }
        val activeRowIdx = remember(lyricDisplayRows, state.positionMs) {
            lyricDisplayRows.indexOfLast { it.timeMs <= state.positionMs }.coerceAtLeast(0)
        }
        val lyricsListState = rememberLazyListState()
        val density = LocalDensity.current
        val fullTopOffsetPx = remember(density) { with(density) { 120.dp.roundToPx() } }

        LaunchedEffect(activeRowIdx, lyricDisplayRows.size) {
            if (lyricDisplayRows.isNotEmpty() && activeRowIdx in lyricDisplayRows.indices) {
                runCatching { smoothGlideToLyric(lyricsListState, activeRowIdx, fullTopOffsetPx) }
            }
        }

        ModalBottomSheet(onDismissRequest = { showFullLyrics = false }, containerColor = contrastPalette.cardBackground, contentColor = contrastPalette.headerText) {
            Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp).imePadding()) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, color = contrastPalette.headerText, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = contrastPalette.pastLine, fontSize = 12.sp, maxLines = 1)
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { vm.setLyricsFontScale(state.lyricsFontScale - 0.1f) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.TextDecrease, "Smaller", tint = contrastPalette.headerText) }
                        IconButton(onClick = { vm.setLyricsFontScale(state.lyricsFontScale + 0.1f) }, modifier = Modifier.size(32.dp)) { Icon(Icons.Rounded.TextIncrease, "Larger", tint = contrastPalette.headerText) }
                    }
                }

                if (state.lyrics.isNotEmpty()) {
                    Spacer(Modifier.height(10.dp))
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                        item {
                            Box(
                                modifier = Modifier.clip(RoundedCornerShape(50))
                                    .background(if (state.showTranslatedLyrics) contrastPalette.activeLine else contrastPalette.pillBackground)
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
                                        color = if (state.showTranslatedLyrics) contrastPalette.cardBackground else contrastPalette.pillText,
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
                                    .background(if (sel) SpotifyGreen else contrastPalette.pillBackground)
                                    .clickable { vm.toggleTranslateLyrics(forceTargetLang = lang) }
                                    .padding(horizontal = 11.dp, vertical = 6.dp)
                            ) { Text(lang.label(str.isEn), color = if (sel) Color.Black else contrastPalette.pillText, fontSize = 11.sp, fontWeight = FontWeight.SemiBold) }
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
                        SpotifyMelodyDots(isPlaying = true, tint = contrastPalette.activeLine, dotSize = 13.dp)
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
                                    is LyricDisplayRow.MelodyRow -> SpotifyMelodyRowItem(isCurrent, isPast, state.isPlaying, true, contrastPalette) { vm.seekTo(row.timeMs) }
                                    is LyricDisplayRow.TextLine -> SpotifyAnimatedLyricLine(row.line, isCurrent, isPast, state.showTranslatedLyrics, state.lyricsFontScale, if (isPodcast) 19f else 22f, if (isPodcast) 21f else 25.5f, 14.5f, contrastPalette, isPodcastTranscript = isPodcast) { vm.seekTo(row.timeMs) }
                                }
                            }
                        }
                        Box(modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().height(22.dp).background(Brush.verticalGradient(listOf(contrastPalette.cardBackground, Color.Transparent))))
                        Box(modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(42.dp).background(Brush.verticalGradient(listOf(Color.Transparent, contrastPalette.cardBackground))))
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
