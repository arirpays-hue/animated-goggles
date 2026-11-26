package com.sonify.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val SpotifyBlack = Color(0xFF121212)
val SpotifyDarkSurface = Color(0xFF181818)
val SpotifyCard = Color(0xFF282828)
val SpotifyElevated = Color(0xFF333333)
val SpotifyGreen = Color(0xFF1DB954)
val SpotifyWhite = Color(0xFFFFFFFF)
val SpotifySubtle = Color(0xFFB3B3B3)
val SpotifyMuted = Color(0xFF727272)

private val SonifyColorScheme = darkColorScheme(
    primary = SpotifyGreen,
    onPrimary = Color.Black,
    background = SpotifyBlack,
    onBackground = SpotifyWhite,
    surface = SpotifyDarkSurface,
    onSurface = SpotifyWhite,
    surfaceVariant = SpotifyCard,
    onSurfaceVariant = SpotifySubtle
)

@Composable
fun SonifyTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = SonifyColorScheme,
        content = content
    )
}
