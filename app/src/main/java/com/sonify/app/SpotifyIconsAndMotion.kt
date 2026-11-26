package com.sonify.app

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode as AnimRepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spotify signature tactile press spring animation (shrinks smoothly on press, springs back).
 */
fun Modifier.spotifyPressClickable(
    enabled: Boolean = true,
    pressedScale: Float = 0.94f,
    onClick: () -> Unit
): Modifier = composed {
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "sp_press_scale"
    )
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(
            interactionSource = interactionSource,
            indication = null,
            enabled = enabled,
            onClick = onClick
        )
}

/**
 * Custom Spotify-style Home Icon (filled solid when active, crisp outline when inactive).
 */
@Composable
fun SpotifyNavHomeIcon(selected: Boolean, tint: Color, size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.085f
        val roof = Path().apply {
            moveTo(w * 0.14f, h * 0.44f)
            lineTo(w * 0.50f, h * 0.14f)
            lineTo(w * 0.86f, h * 0.44f)
            lineTo(w * 0.86f, h * 0.86f)
            lineTo(w * 0.62f, h * 0.86f)
            lineTo(w * 0.62f, h * 0.58f)
            lineTo(w * 0.38f, h * 0.58f)
            lineTo(w * 0.38f, h * 0.86f)
            lineTo(w * 0.14f, h * 0.86f)
            close()
        }
        if (selected) {
            drawPath(roof, color = tint)
        } else {
            drawPath(roof, color = tint, style = Stroke(stroke, join = StrokeJoin.Round, cap = StrokeCap.Round))
        }
    }
}

/**
 * Custom Spotify-style Search Icon (bold ring when active, sleek ring when inactive).
 */
@Composable
fun SpotifyNavSearchIcon(selected: Boolean, tint: Color, size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = if (selected) w * 0.125f else w * 0.085f
        val radius = w * 0.30f
        val center = Offset(w * 0.44f, h * 0.44f)
        drawCircle(color = tint, radius = radius, center = center, style = Stroke(stroke))
        drawLine(
            color = tint,
            start = Offset(w * 0.66f, h * 0.66f),
            end = Offset(w * 0.88f, h * 0.88f),
            strokeWidth = stroke * 1.1f,
            cap = StrokeCap.Round
        )
    }
}

/**
 * Custom Spotify-style Your Library Icon (two upright books + one slanted book).
 */
@Composable
fun SpotifyNavLibraryIcon(selected: Boolean, tint: Color, size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = if (selected) w * 0.11f else w * 0.08f
        drawLine(tint, Offset(w * 0.18f, h * 0.16f), Offset(w * 0.18f, h * 0.84f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.40f, h * 0.16f), Offset(w * 0.40f, h * 0.84f), stroke, StrokeCap.Round)
        if (selected) {
            val tilted = Path().apply {
                moveTo(w * 0.56f, h * 0.22f)
                lineTo(w * 0.72f, h * 0.18f)
                lineTo(w * 0.88f, h * 0.82f)
                lineTo(w * 0.72f, h * 0.86f)
                close()
            }
            drawPath(tilted, color = tint)
        } else {
            drawLine(tint, Offset(w * 0.60f, h * 0.20f), Offset(w * 0.82f, h * 0.84f), stroke, StrokeCap.Round)
        }
    }
}

/**
 * Custom Spotify-style Create (+) Icon.
 */
@Composable
fun SpotifyNavCreateIcon(tint: Color, size: Dp = 24.dp) {
    Canvas(modifier = Modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.095f
        drawLine(tint, Offset(w * 0.5f, h * 0.18f), Offset(w * 0.5f, h * 0.82f), stroke, StrokeCap.Round)
        drawLine(tint, Offset(w * 0.18f, h * 0.5f), Offset(w * 0.82f, h * 0.5f), stroke, StrokeCap.Round)
    }
}

/**
 * Spotify Blue Verified Artist Checkmark Badge.
 */
@Composable
fun SpotifyVerifiedBadge(size: Dp = 18.dp, modifier: Modifier = Modifier) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val badgeColor = Color(0xFF3D91F4)
        drawCircle(color = badgeColor, radius = w * 0.48f, center = Offset(w * 0.5f, h * 0.5f))
        val check = Path().apply {
            moveTo(w * 0.29f, h * 0.52f)
            lineTo(w * 0.44f, h * 0.67f)
            lineTo(w * 0.73f, h * 0.36f)
        }
        drawPath(
            path = check,
            color = Color.White,
            style = Stroke(width = w * 0.11f, cap = StrokeCap.Round, join = StrokeJoin.Round)
        )
    }
}

/**
 * Spotify Animated Green 3-Bar Equalizer Waveform (shown next to currently playing track!).
 */
@Composable
fun SpotifyPlayingBars(isPlaying: Boolean, color: Color = SpotifyGreen, size: Dp = 16.dp, modifier: Modifier = Modifier) {
    val inf = rememberInfiniteTransition(label = "eq_bars")
    val b1 by inf.animateFloat(0.25f, 1.0f, infiniteRepeatable(tween(420, easing = FastOutSlowInEasing), AnimRepeatMode.Reverse), label = "b1")
    val b2 by inf.animateFloat(0.95f, 0.22f, infiniteRepeatable(tween(340, easing = FastOutSlowInEasing), AnimRepeatMode.Reverse), label = "b2")
    val b3 by inf.animateFloat(0.35f, 0.90f, infiniteRepeatable(tween(510, easing = FastOutSlowInEasing), AnimRepeatMode.Reverse), label = "b3")

    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val barW = w * 0.22f
        val gap = w * 0.14f
        val heights = if (isPlaying) listOf(b1, b2, b3) else listOf(0.28f, 0.48f, 0.28f)
        heights.forEachIndexed { idx, frac ->
            val bh = h * frac.coerceIn(0.18f, 1f)
            drawRoundRect(
                color = color,
                topLeft = Offset(idx * (barW + gap), h - bh),
                size = Size(barW, bh),
                cornerRadius = CornerRadius(barW * 0.4f, barW * 0.4f)
            )
        }
    }
}

/**
 * Spotify Signature Instrumental Melody Dots (• • •) pulsing rhythmically during musical breaks!
 */
@Composable
fun SpotifyMelodyDots(
    isPlaying: Boolean,
    tint: Color = Color.White,
    dotSize: Dp = 11.dp,
    modifier: Modifier = Modifier
) {
    val inf = rememberInfiniteTransition(label = "melody_dots")
    val phase by inf.animateFloat(
        initialValue = 0f,
        targetValue = 3f,
        animationSpec = infiniteRepeatable(tween(1500, easing = LinearEasing), AnimRepeatMode.Restart),
        label = "melody_phase"
    )

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        for (i in 0 until 3) {
            val activeLevel = if (!isPlaying) {
                0.55f
            } else {
                val dist = kotlin.math.abs(phase - (i + 0.5f))
                (1f - (dist / 0.85f)).coerceIn(0.25f, 1f)
            }
            val scale = 0.78f + (activeLevel * 0.34f)
            Canvas(
                modifier = Modifier
                    .size(dotSize)
                    .graphicsLayer { scaleX = scale; scaleY = scale }
            ) {
                drawCircle(color = tint.copy(alpha = 0.35f + activeLevel * 0.65f))
            }
        }
    }
}

/**
 * Custom Spotify Connect Icon (Speaker + Phone + Live Signal Arcs when connected to external device or Jam).
 */
@Composable
fun SpotifyConnectIcon(
    isConnectedExternal: Boolean,
    tint: Color,
    size: Dp = 22.dp,
    modifier: Modifier = Modifier
) {
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.082f

        // Speaker cabinet (left/back)
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.10f, h * 0.14f),
            size = Size(w * 0.50f, h * 0.72f),
            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
            style = Stroke(stroke)
        )
        // Woofer & tweeter dots
        drawCircle(tint, w * 0.11f, Offset(w * 0.35f, h * 0.60f), style = Stroke(stroke * 0.9f))
        drawCircle(tint, w * 0.045f, Offset(w * 0.35f, h * 0.31f))

        // Connected device / phone foreground (right)
        drawRoundRect(
            color = SpotifyBlack,
            topLeft = Offset(w * 0.50f, h * 0.42f),
            size = Size(w * 0.42f, h * 0.50f),
            cornerRadius = CornerRadius(w * 0.09f, w * 0.09f)
        )
        drawRoundRect(
            color = tint,
            topLeft = Offset(w * 0.52f, h * 0.44f),
            size = Size(w * 0.38f, h * 0.46f),
            cornerRadius = CornerRadius(w * 0.08f, w * 0.08f),
            style = Stroke(stroke)
        )

        // Glowing wireless arcs when external audio or Jam is active
        if (isConnectedExternal) {
            drawArc(
                color = tint,
                startAngle = -65f,
                sweepAngle = 55f,
                useCenter = false,
                topLeft = Offset(w * 0.58f, h * 0.06f),
                size = Size(w * 0.36f, h * 0.36f),
                style = Stroke(stroke * 0.85f, cap = StrokeCap.Round)
            )
            drawCircle(tint, w * 0.055f, Offset(w * 0.71f, h * 0.67f))
        }
    }
}

/**
 * Custom Redesigned Connected Audio Device Icons (Phone, Bluetooth Headphones, Wired Jack, USB-C DAC).
 */
@Composable
fun ConnectedDeviceVectorIcon(
    device: AudioOutputItem,
    tint: Color,
    size: Dp = 28.dp,
    modifier: Modifier = Modifier
) {
    val labelLower = device.typeLabelEn.lowercase()
    Canvas(modifier = modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val stroke = w * 0.078f

        when {
            labelLower.contains("usb") -> {
                // Sleek USB-C DAC Adapter + Hi-Res Sound Wave
                drawRoundRect(tint, Offset(w * 0.24f, h * 0.14f), Size(w * 0.52f, h * 0.26f), CornerRadius(w * 0.10f, w * 0.10f), style = Stroke(stroke))
                drawRoundRect(tint, Offset(w * 0.34f, h * 0.40f), Size(w * 0.32f, h * 0.22f), CornerRadius(w * 0.05f, w * 0.05f), style = Stroke(stroke))
                drawLine(tint, Offset(w * 0.50f, h * 0.62f), Offset(w * 0.50f, h * 0.88f), stroke, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.36f, h * 0.27f), Offset(w * 0.64f, h * 0.27f), stroke * 0.8f, StrokeCap.Round)
                drawArc(tint.copy(alpha = 0.8f), -40f, 80f, false, Offset(w * 0.68f, h * 0.54f), Size(w * 0.24f, h * 0.24f), style = Stroke(stroke * 0.8f, cap = StrokeCap.Round))
            }
            labelLower.contains("wired") -> {
                // Studio Wired Headphones + Plug
                drawArc(tint, 180f, 180f, false, Offset(w * 0.18f, h * 0.16f), Size(w * 0.64f, h * 0.64f), style = Stroke(stroke, cap = StrokeCap.Round))
                drawRoundRect(tint, Offset(w * 0.14f, h * 0.46f), Size(w * 0.18f, h * 0.30f), CornerRadius(w * 0.08f, w * 0.08f))
                drawRoundRect(tint, Offset(w * 0.68f, h * 0.46f), Size(w * 0.18f, h * 0.30f), CornerRadius(w * 0.08f, w * 0.08f))
                drawLine(tint, Offset(w * 0.23f, h * 0.76f), Offset(w * 0.23f, h * 0.90f), stroke * 0.85f, StrokeCap.Round)
                drawLine(tint, Offset(w * 0.23f, h * 0.90f), Offset(w * 0.48f, h * 0.90f), stroke * 0.85f, StrokeCap.Round)
            }
            labelLower.contains("bluetooth") -> {
                // Modern Wireless Over-Ear Headphones + Active Signal Wave
                drawArc(tint, 180f, 180f, false, Offset(w * 0.18f, h * 0.22f), Size(w * 0.64f, h * 0.62f), style = Stroke(stroke, cap = StrokeCap.Round))
                drawRoundRect(tint, Offset(w * 0.13f, h * 0.50f), Size(w * 0.19f, h * 0.32f), CornerRadius(w * 0.09f, w * 0.09f))
                drawRoundRect(tint, Offset(w * 0.68f, h * 0.50f), Size(w * 0.19f, h * 0.32f), CornerRadius(w * 0.09f, w * 0.09f))
                // Top wireless arc
                drawArc(tint.copy(alpha = 0.9f), 225f, 90f, false, Offset(w * 0.34f, h * 0.06f), Size(w * 0.32f, h * 0.22f), style = Stroke(stroke * 0.8f, cap = StrokeCap.Round))
            }
            else -> {
                // Sleek Smartphone with Speaker Waves
                drawRoundRect(tint, Offset(w * 0.26f, h * 0.12f), Size(w * 0.48f, h * 0.76f), CornerRadius(w * 0.10f, w * 0.10f), style = Stroke(stroke))
                drawLine(tint, Offset(w * 0.42f, h * 0.78f), Offset(w * 0.58f, h * 0.78f), stroke * 0.85f, StrokeCap.Round)
                drawCircle(tint.copy(alpha = 0.85f), w * 0.08f, Offset(w * 0.50f, h * 0.46f), style = Stroke(stroke * 0.75f))
                drawCircle(tint, w * 0.03f, Offset(w * 0.50f, h * 0.46f))
            }
        }
    }
}
