package com.sonify.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

private enum class ArtMotif {
    VINYL_WAVES, BOLT_ENERGY, MOON_CHILL, SOUND_BARS, PRISM_ROCK,
    MIC_PODCAST, BOOK_PAGES, CROWN_HITS, COMPASS_DRIVE, GAME_GRID,
    HEART_ROMANCE, ANIME_BURST, SPOTLIGHT_LIVE
}

private data class GeneratedArtTheme(
    val primary: Color,
    val secondary: Color,
    val accent: Color,
    val motif: ArtMotif,
    val badge: String,
    val patternVariant: Int
)

private fun deriveArtTheme(seedText: String, styleTag: String = "", baseColorOverride: Long? = null): GeneratedArtTheme {
    val combined = "$seedText $styleTag".lowercase()
    val hash = abs((seedText + "|" + styleTag).hashCode())

    val (c1, c2, acc, motif, badge) = when {
        combined.contains("אימון") || combined.contains("כושר") || combined.contains("workout") || combined.contains("gym") || combined.contains("אנרג") ->
            QuintArt(Color(0xFFFF4D00), Color(0xFF1A0A00), Color(0xFFFFD600), ArtMotif.BOLT_ENERGY, "ENERGY")
        combined.contains("לילה") || combined.contains("שינה") || combined.contains("צ'יל") || combined.contains("רגוע") || combined.contains("chill") || combined.contains("sleep") || combined.contains("wellness") ->
            QuintArt(Color(0xFF1B2B65), Color(0xFF0A0F24), Color(0xFF64D2FF), ArtMotif.MOON_CHILL, "CHILL")
        combined.contains("פודקאסט") || combined.contains("podcast") ->
            QuintArt(Color(0xFF006B54), Color(0xFF06211A), Color(0xFF1ED760), ArtMotif.MIC_PODCAST, "PODCAST")
        combined.contains("ספר") || combined.contains("audiobook") || combined.contains("book") ->
            QuintArt(Color(0xFF283E7A), Color(0xFF0F1830), Color(0xFFFFB74D), ArtMotif.BOOK_PAGES, "AUDIOBOOK")
        combined.contains("רוק") || combined.contains("מטאל") || combined.contains("rock") || combined.contains("metal") || combined.contains("indie") ->
            QuintArt(Color(0xFFB71C1C), Color(0xFF1B090C), Color(0xFFFF5252), ArtMotif.PRISM_ROCK, "ROCK")
        combined.contains("ראפ") || combined.contains("היפ") || combined.contains("rap") || combined.contains("hip") || combined.contains("trap") ->
            QuintArt(Color(0xFFC75B00), Color(0xFF1C1006), Color(0xFFFFAB40), ArtMotif.SOUND_BARS, "HIP-HOP")
        combined.contains("מסיבה") || combined.contains("דאנס") || combined.contains("אלקטרוני") || combined.contains("party") || combined.contains("dance") || combined.contains("edm") ->
            QuintArt(Color(0xFF7B1FA2), Color(0xFF160726), Color(0xFFE040FB), ArtMotif.SPOTLIGHT_LIVE, "CLUB")
        combined.contains("נסיעה") || combined.contains("commute") || combined.contains("drive") || combined.contains("road") ->
            QuintArt(Color(0xFF1565C0), Color(0xFF091B36), Color(0xFF40C4FF), ArtMotif.COMPASS_DRIVE, "DRIVE")
        combined.contains("גיימינג") || combined.contains("gaming") || combined.contains("phonk") ->
            QuintArt(Color(0xFFD81B60), Color(0xFF1A0714), Color(0xFF00E5FF), ArtMotif.GAME_GRID, "GAMING")
        combined.contains("אנימה") || combined.contains("anime") || combined.contains("k-pop") || combined.contains("kpop") ->
            QuintArt(Color(0xFFE53935), Color(0xFF1E0820), Color(0xFFFF80AB), ArtMotif.ANIME_BURST, "OST")
        combined.contains("אהבה") || combined.contains("רומנט") || combined.contains("עצוב") || combined.contains("בלד") || combined.contains("romance") || combined.contains("soul") || combined.contains("r&b") ->
            QuintArt(Color(0xFFAD1457), Color(0xFF210713), Color(0xFFFF8A80), ArtMotif.HEART_ROMANCE, "FEELS")
        combined.contains("ישראלי") || combined.contains("מזרחית") || combined.contains("israeli") ->
            QuintArt(Color(0xFF0D73EC), Color(0xFF071B36), Color(0xFF1DB954), ArtMotif.CROWN_HITS, "IL MIX")
        combined.contains("הופע") || combined.contains("live") || combined.contains("concert") ->
            QuintArt(Color(0xFF6A1B9A), Color(0xFF150826), Color(0xFFFFD54F), ArtMotif.SPOTLIGHT_LIVE, "LIVE")
        combined.contains("התמקדות") || combined.contains("לימוד") || combined.contains("focus") || combined.contains("jazz") || combined.contains("classical") ->
            QuintArt(Color(0xFF4A325C), Color(0xFF140D1C), Color(0xFFB388FF), ArtMotif.VINYL_WAVES, "FOCUS")
        else -> {
            val palettes = listOf(
                Triple(Color(0xFF1DB954), Color(0xFF0A2416), Color(0xFF69F0AE)),
                Triple(Color(0xFFE8115B), Color(0xFF240614), Color(0xFFFF80AB)),
                Triple(Color(0xFF0D73EC), Color(0xFF081B33), Color(0xFF82B1FF)),
                Triple(Color(0xFFBC5900), Color(0xFF241103), Color(0xFFFFD180)),
                Triple(Color(0xFF8D67AB), Color(0xFF1C1026), Color(0xFFEA80FC)),
                Triple(Color(0xFF00897B), Color(0xFF06211F), Color(0xFF64FFDA))
            )
            val p = palettes[hash % palettes.size]
            val motifs = ArtMotif.entries
            QuintArt(p.first, p.second, p.third, motifs[hash % motifs.size], "AI MIX")
        }
    }

    val finalPrimary = if (baseColorOverride != null) Color(baseColorOverride) else c1
    return GeneratedArtTheme(finalPrimary, c2, acc, motif, badge, hash % 4)
}

private data class QuintArt(val c1: Color, val c2: Color, val acc: Color, val motif: ArtMotif, val badge: String)

private fun DrawScope.drawMotifArtwork(motif: ArtMotif, accent: Color, variant: Int) {
    val w = size.width
    val h = size.height
    val cx = w * 0.5f
    val cy = h * 0.5f
    val strokeW = (w * 0.045f).coerceAtLeast(2.5f)

    // Subtle geometric aura background
    drawCircle(
        color = accent.copy(alpha = 0.16f),
        radius = w * 0.44f,
        center = Offset(if (variant % 2 == 0) w * 0.8f else w * 0.2f, h * 0.25f)
    )
    drawCircle(
        color = Color.White.copy(alpha = 0.07f),
        radius = w * 0.34f,
        center = Offset(if (variant % 2 == 0) w * 0.2f else w * 0.85f, h * 0.82f)
    )

    when (motif) {
        ArtMotif.VINYL_WAVES -> {
            drawCircle(Color.Black.copy(alpha = 0.38f), w * 0.36f, Offset(cx, cy))
            listOf(0.32f, 0.24f, 0.16f).forEach { r ->
                drawCircle(accent.copy(alpha = 0.65f), w * r, Offset(cx, cy), style = Stroke(strokeW * 0.7f))
            }
            drawCircle(accent, w * 0.07f, Offset(cx, cy))
        }
        ArtMotif.BOLT_ENERGY -> {
            val path = Path().apply {
                moveTo(w * 0.58f, h * 0.14f)
                lineTo(w * 0.28f, h * 0.54f)
                lineTo(w * 0.49f, h * 0.54f)
                lineTo(w * 0.40f, h * 0.86f)
                lineTo(w * 0.74f, h * 0.44f)
                lineTo(w * 0.53f, h * 0.44f)
                close()
            }
            drawPath(path, color = accent.copy(alpha = 0.9f))
        }
        ArtMotif.MOON_CHILL -> {
            drawCircle(accent.copy(alpha = 0.88f), w * 0.26f, Offset(cx, cy))
            drawCircle(Color(0xFF10193B), w * 0.22f, Offset(cx + w * 0.11f, cy - h * 0.07f))
            drawCircle(Color.White.copy(alpha = 0.8f), w * 0.03f, Offset(w * 0.24f, h * 0.26f))
            drawCircle(Color.White.copy(alpha = 0.65f), w * 0.022f, Offset(w * 0.78f, h * 0.72f))
        }
        ArtMotif.SOUND_BARS -> {
            val heights = listOf(0.28f, 0.56f, 0.74f, 0.44f, 0.66f, 0.34f)
            val barW = w * 0.085f
            val gap = w * 0.042f
            val startX = (w - (heights.size * barW + (heights.size - 1) * gap)) / 2f
            heights.forEachIndexed { idx, frac ->
                val bh = h * frac
                drawRoundRect(
                    color = if (idx % 2 == 0) accent else Color.White.copy(alpha = 0.85f),
                    topLeft = Offset(startX + idx * (barW + gap), (h - bh) / 2f),
                    size = Size(barW, bh),
                    cornerRadius = CornerRadius(barW / 2f, barW / 2f)
                )
            }
        }
        ArtMotif.PRISM_ROCK -> {
            val p1 = Path().apply {
                moveTo(cx, h * 0.16f); lineTo(w * 0.82f, h * 0.78f); lineTo(w * 0.18f, h * 0.78f); close()
            }
            drawPath(p1, accent.copy(alpha = 0.85f), style = Stroke(strokeW * 1.15f))
            val p2 = Path().apply {
                moveTo(cx, h * 0.34f); lineTo(w * 0.68f, h * 0.70f); lineTo(w * 0.32f, h * 0.70f); close()
            }
            drawPath(p2, Color.White.copy(alpha = 0.28f))
        }
        ArtMotif.MIC_PODCAST -> {
            drawRoundRect(accent, Offset(w * 0.39f, h * 0.18f), Size(w * 0.22f, h * 0.38f), CornerRadius(w * 0.11f, w * 0.11f))
            drawArc(Color.White.copy(alpha = 0.85f), 0f, 180f, false, Offset(w * 0.28f, h * 0.26f), Size(w * 0.44f, h * 0.40f), style = Stroke(strokeW, cap = StrokeCap.Round))
            drawLine(Color.White.copy(alpha = 0.85f), Offset(cx, h * 0.66f), Offset(cx, h * 0.82f), strokeW, cap = StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.85f), Offset(w * 0.36f, h * 0.82f), Offset(w * 0.64f, h * 0.82f), strokeW, cap = StrokeCap.Round)
        }
        ArtMotif.BOOK_PAGES -> {
            drawRoundRect(accent.copy(alpha = 0.88f), Offset(w * 0.20f, h * 0.24f), Size(w * 0.27f, h * 0.52f), CornerRadius(8f, 8f))
            drawRoundRect(Color.White.copy(alpha = 0.85f), Offset(w * 0.53f, h * 0.24f), Size(w * 0.27f, h * 0.52f), CornerRadius(8f, 8f))
            drawLine(Color.Black.copy(alpha = 0.35f), Offset(cx, h * 0.24f), Offset(cx, h * 0.76f), strokeW)
        }
        ArtMotif.CROWN_HITS -> {
            val crown = Path().apply {
                moveTo(w * 0.18f, h * 0.72f)
                lineTo(w * 0.22f, h * 0.34f)
                lineTo(w * 0.39f, h * 0.52f)
                lineTo(cx, h * 0.24f)
                lineTo(w * 0.61f, h * 0.52f)
                lineTo(w * 0.78f, h * 0.34f)
                lineTo(w * 0.82f, h * 0.72f)
                close()
            }
            drawPath(crown, accent.copy(alpha = 0.9f))
        }
        ArtMotif.COMPASS_DRIVE -> {
            drawCircle(Color.White.copy(alpha = 0.75f), w * 0.32f, Offset(cx, cy), style = Stroke(strokeW))
            val needle = Path().apply {
                moveTo(cx, h * 0.22f); lineTo(w * 0.59f, cy); lineTo(cx, h * 0.78f); lineTo(w * 0.41f, cy); close()
            }
            drawPath(needle, accent.copy(alpha = 0.92f))
        }
        ArtMotif.GAME_GRID -> {
            drawRoundRect(accent.copy(alpha = 0.88f), Offset(w * 0.16f, h * 0.30f), Size(w * 0.68f, h * 0.40f), CornerRadius(w * 0.20f, w * 0.20f))
            drawLine(Color.Black.copy(alpha = 0.75f), Offset(w * 0.32f, h * 0.42f), Offset(w * 0.32f, h * 0.58f), strokeW * 1.1f, StrokeCap.Round)
            drawLine(Color.Black.copy(alpha = 0.75f), Offset(w * 0.24f, cy), Offset(w * 0.40f, cy), strokeW * 1.1f, StrokeCap.Round)
            drawCircle(Color.White, w * 0.045f, Offset(w * 0.64f, h * 0.45f))
            drawCircle(Color.White, w * 0.045f, Offset(w * 0.72f, h * 0.55f))
        }
        ArtMotif.HEART_ROMANCE -> {
            val heart = Path().apply {
                moveTo(cx, h * 0.78f)
                cubicTo(w * 0.14f, h * 0.54f, w * 0.16f, h * 0.22f, cx, h * 0.38f)
                cubicTo(w * 0.84f, h * 0.22f, w * 0.86f, h * 0.54f, cx, h * 0.78f)
                close()
            }
            drawPath(heart, accent.copy(alpha = 0.9f))
        }
        ArtMotif.ANIME_BURST -> {
            val rays = 8
            for (i in 0 until rays) {
                val angle = (i * 2.0 * PI / rays).toFloat()
                val sx = cx + cos(angle) * w * 0.12f
                val sy = cy + sin(angle) * h * 0.12f
                val ex = cx + cos(angle) * w * 0.38f
                val ey = cy + sin(angle) * h * 0.38f
                drawLine(if (i % 2 == 0) accent else Color.White.copy(alpha = 0.8f), Offset(sx, sy), Offset(ex, ey), strokeW, StrokeCap.Round)
            }
            drawCircle(accent, w * 0.10f, Offset(cx, cy))
        }
        ArtMotif.SPOTLIGHT_LIVE -> {
            val beam1 = Path().apply { moveTo(w * 0.2f, 0f); lineTo(w * 0.65f, h); lineTo(w * 0.35f, h); close() }
            val beam2 = Path().apply { moveTo(w * 0.8f, 0f); lineTo(w * 0.65f, h); lineTo(w * 0.35f, h); close() }
            drawPath(beam1, accent.copy(alpha = 0.38f))
            drawPath(beam2, Color.White.copy(alpha = 0.24f))
            drawCircle(accent, w * 0.14f, Offset(cx, h * 0.62f))
        }
    }
}

/**
 * Generates a unique, Spotify-grade AI playlist cover tailored to the artist, genre, or mood.
 * Blends custom procedural vector artwork + gradient palette + lead artist's HD portrait cleanly.
 */
@Composable
fun AiPlaylistCoverArt(playlist: Playlist, useAiCovers: Boolean, modifier: Modifier = Modifier) {
    val leadArt = playlist.coverUrl.ifBlank { playlist.tracks.firstOrNull()?.artworkUrl ?: "" }
    val leadArtist = playlist.tracks.firstOrNull()?.artist.orEmpty()
    val seed = "${playlist.name}|${playlist.aiSeedPrompt}|$leadArtist"
    val theme = remember(seed, playlist.aiStyleTag, playlist.customCoverBgHex) {
        deriveArtTheme(seed, playlist.aiStyleTag, playlist.customCoverBgHex)
    }

    val isManualCover = playlist.customCoverBgHex != null && (playlist.customCoverText.isNotBlank() || playlist.customCoverSticker.isNotBlank())
    val shouldRenderAiCover = useAiCovers && (
        playlist.aiStyleTag.isNotBlank() ||
        playlist.aiSeedPrompt.isNotBlank() ||
        playlist.creator.contains("AI", true) ||
        playlist.creator.equals("Sonify", true) ||
        playlist.isBlend ||
        leadArt.isBlank()
    )

    if (!shouldRenderAiCover && !isManualCover && leadArt.isNotBlank()) {
        ArtworkImage(leadArt, playlist.name, modifier)
        return
    }

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(10.dp))
            .background(Brush.linearGradient(listOf(theme.primary, theme.secondary)))
    ) {
        // Procedural vector artwork layer
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawMotifArtwork(theme.motif, theme.accent, theme.patternVariant)
        }

        // Artist HD Portrait Inset (Spotify "Daily Mix" / "This Is..." aesthetic)
        if (leadArt.isNotBlank() && !isManualCover) {
            ArtworkImage(
                url = leadArt,
                contentDescription = playlist.name,
                modifier = Modifier
                    .align(Alignment.Center)
                    .fillMaxSize(0.58f)
                    .rotate(if (theme.patternVariant % 2 == 0) -5f else 5f)
                    .shadow(8.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp))
            )
        }

        // Bottom readable scrim + clean typographic badge
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Transparent,
                        0.55f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.78f)
                    )
                )
        )

        // Top Accent Pill
        Box(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(7.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color.Black.copy(alpha = 0.52f))
                .padding(horizontal = 6.dp, vertical = 2.dp)
        ) {
            Text(
                text = playlist.aiStyleTag.ifBlank { theme.badge },
                color = theme.accent,
                fontSize = 9.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1
            )
        }

        // Bottom Title & Color Accent Bar
        Column(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 7.dp)
        ) {
            val labelText = playlist.customCoverText.ifBlank { playlist.name }
            Text(
                text = labelText,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.ExtraBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.45f)
                    .height(3.dp)
                    .clip(CircleShape)
                    .background(theme.accent)
            )
        }
    }
}

/**
 * Spotify-style Browse / Mood / Genre Card with rich gradient and tilted framed vector artwork.
 */
@Composable
fun SpotifyMoodBrowseCard(
    category: BrowseCategory,
    isEn: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val baseColor = Color(category.colorHex)
    val theme = remember(category.titleEn, category.colorHex) {
        deriveArtTheme("${category.title} ${category.titleEn} ${category.query}", category.groupEn, category.colorHex)
    }

    Box(
        modifier = modifier
            .height(96.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(
                Brush.linearGradient(
                    listOf(
                        baseColor,
                        baseColor.copy(alpha = 0.78f),
                        Color.Black.copy(alpha = 0.45f)
                    )
                )
            )
            .clickable(onClick = onClick)
    ) {
        // Subtle background radial highlight
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawCircle(
                color = Color.White.copy(alpha = 0.10f),
                radius = size.height * 0.85f,
                center = Offset(size.width * 0.15f, size.height * 0.15f)
            )
        }

        // Tilted Framed Vector Card in the Bottom-End corner (Spotify signature layout!)
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = 12.dp, y = 10.dp)
                .size(66.dp)
                .rotate(22f)
                .shadow(10.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(theme.secondary, baseColor)))
        ) {
            Canvas(modifier = Modifier.fillMaxSize().padding(6.dp)) {
                drawMotifArtwork(theme.motif, theme.accent, theme.patternVariant)
            }
        }

        // Crisp Category Title
        Text(
            text = category.displayTitle(isEn),
            color = Color.White,
            fontWeight = FontWeight.ExtraBold,
            fontSize = 15.sp,
            lineHeight = 18.sp,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth(0.68f)
                .padding(start = 12.dp, top = 12.dp, end = 6.dp)
        )
    }
}
