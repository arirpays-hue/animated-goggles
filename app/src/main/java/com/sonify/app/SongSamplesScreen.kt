@file:kotlin.OptIn(ExperimentalFoundationApi::class)

package com.sonify.app

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Spotify-style Vertical Song Samples Discovery Feed.
 * Triggered when tapping "דומה ל..." or "שירים של..." in Now Playing.
 * Swiping up/down instantly previews each song's chorus/hook, lets you follow the artist,
 * save the song with +, share a clickable link, or tap the bottom card to play the full track!
 */
@Composable
fun SongSamplesScreen(
    feed: SamplesFeedState,
    state: UiState,
    vm: AppViewModel
) {
    val str = state.strings
    val isEn = str.isEn
    val context = LocalContext.current
    val tracks = feed.tracks

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF141414))
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        if (feed.isLoading && tracks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                SpotifyMelodyDots(isPlaying = true, tint = SpotifyGreen, dotSize = 12.dp)
                Spacer(Modifier.height(14.dp))
                Text(
                    str.t("טוען דגימות שירים עבור ${feed.title}...", "Loading song samples for ${feed.title}..."),
                    color = SpotifyWhite,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
            }
        } else if (tracks.isEmpty()) {
            Column(
                modifier = Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(Icons.Rounded.MusicOff, null, tint = SpotifySubtle, modifier = Modifier.size(48.dp))
                Spacer(Modifier.height(10.dp))
                Text(str.t("לא נמצאו דגימות נוספות כרגע", "No samples found right now"), color = SpotifyWhite, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = { vm.closeSamplesFeed() },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                    shape = RoundedCornerShape(50)
                ) { Text(str.back, fontWeight = FontWeight.Bold) }
            }
        } else {
            val pagerState = rememberPagerState(pageCount = { tracks.size })
            var hasSwipedOnce by remember { mutableStateOf(false) }

            LaunchedEffect(pagerState.currentPage, tracks) {
                if (pagerState.currentPage > 0) hasSwipedOnce = true
                tracks.getOrNull(pagerState.currentPage)?.let { trk ->
                    vm.playSampleTrack(trk)
                }
            }

            VerticalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize()
            ) { page ->
                val sampleTrack = tracks[page]
                val isLiked = state.likedTracks.any { it.id == sampleTrack.id }
                val isFollowed = state.followedArtists.contains(sampleTrack.artist)
                val isCurrentlyPlayingSample = state.currentTrack?.id == sampleTrack.id && state.isPlaying
                val tags = remember(sampleTrack.id, feed.tags) {
                    if (feed.tags.isNotEmpty()) feed.tags
                    else buildSampleHashtags(sampleTrack, isEn)
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(
                            Brush.verticalGradient(
                                0f to Color(state.dynamicAccentColor).copy(alpha = 0.65f),
                                0.45f to Color(0xFF161616),
                                1f to Color(0xFF0C0C0C)
                            )
                        )
                ) {
                    // Ambient Artwork Centerpiece
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 20.dp)
                            .padding(top = 68.dp, bottom = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Spacer(Modifier.weight(0.6f))

                        // Large Square Cover Art with Tap-to-Pause/Resume Sample
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.76f)
                                .aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp))
                                .background(Color.Black)
                                .clickable { vm.togglePlayPause() },
                            contentAlignment = Alignment.Center
                        ) {
                            ArtworkImage(sampleTrack.artworkUrl, sampleTrack.title, Modifier.fillMaxSize())
                            if (state.isBusyLoading && state.currentTrack?.id == sampleTrack.id) {
                                Box(
                                    modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f)),
                                    contentAlignment = Alignment.Center
                                ) { CircularProgressIndicator(color = SpotifyGreen, strokeWidth = 3.dp) }
                            } else if (!isCurrentlyPlayingSample && state.currentTrack?.id == sampleTrack.id) {
                                Box(
                                    modifier = Modifier.size(58.dp).clip(CircleShape).background(Color.Black.copy(alpha = 0.65f)),
                                    contentAlignment = Alignment.Center
                                ) { Icon(Icons.Rounded.PlayArrow, null, tint = SpotifyWhite, modifier = Modifier.size(36.dp)) }
                            }

                            // Top badge showing sample progress index (e.g. 1 / 12)
                            Box(
                                modifier = Modifier.align(Alignment.TopEnd).padding(10.dp)
                                    .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.62f))
                                    .padding(horizontal = 9.dp, vertical = 4.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (isCurrentlyPlayingSample) {
                                        SpotifyPlayingBars(isPlaying = true, color = SpotifyGreen, size = 11.dp)
                                        Spacer(Modifier.width(5.dp))
                                    }
                                    Text("${page + 1}/${tracks.size}", color = SpotifyWhite, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }

                        Spacer(Modifier.weight(0.5f))

                        // Swipe Hint (matches Spotify screenshot!)
                        AnimatedVisibility(
                            visible = !hasSwipedOnce && page == 0 && tracks.size > 1,
                            enter = fadeIn(),
                            exit = fadeOut()
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(bottom = 14.dp)) {
                                Icon(Icons.Rounded.KeyboardArrowUp, null, tint = SpotifyWhite.copy(alpha = 0.85f), modifier = Modifier.size(22.dp))
                                Text(
                                    text = str.t("החליקו למעלה כדי לראות אפשרויות נוספות", "Swipe up to see more samples"),
                                    color = SpotifyWhite,
                                    fontWeight = FontWeight.ExtraBold,
                                    fontSize = 15.sp,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }

                        // Artist Row + Follow Button + Share & 3-Dots
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f).clickable {
                                    vm.closeSamplesFeed()
                                    vm.openArtistPage(sampleTrack.artist, sampleTrack.artworkUrl)
                                }
                            ) {
                                ArtworkImage(sampleTrack.artworkUrl, sampleTrack.artist, Modifier.size(42.dp).clip(CircleShape))
                                Spacer(Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(sampleTrack.artist, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(
                                        str.t("דגימת שיר • ${feed.subtitle.ifBlank { feed.title }}", "Sample • ${feed.subtitle.ifBlank { feed.title }}"),
                                        color = SpotifySubtle,
                                        fontSize = 11.5.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { vm.toggleFollowArtist(sampleTrack.artist) },
                                shape = RoundedCornerShape(50),
                                border = BorderStroke(1.dp, if (isFollowed) SpotifyGreen else SpotifyWhite.copy(alpha = 0.6f)),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                                modifier = Modifier.height(32.dp)
                            ) {
                                Text(
                                    if (isFollowed) str.following else str.t("מעקב", "Follow"),
                                    color = if (isFollowed) SpotifyGreen else SpotifyWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            IconButton(
                                onClick = {
                                    val link = SonifyShareLinks.trackUrl(sampleTrack, 0L)
                                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "${sampleTrack.title} - ${sampleTrack.artist}\n$link")
                                    }, str.t("שתף", "Share")))
                                },
                                modifier = Modifier.size(36.dp)
                            ) { Icon(Icons.Rounded.Share, "Share", tint = SpotifyWhite.copy(alpha = 0.85f), modifier = Modifier.size(20.dp)) }
                            IconButton(
                                onClick = { vm.openTrackOptions(sampleTrack) },
                                modifier = Modifier.size(34.dp)
                            ) { Icon(Icons.Rounded.MoreVert, "More", tint = SpotifyWhite.copy(alpha = 0.85f), modifier = Modifier.size(20.dp)) }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Hashtags Row (#צ'יל #heartache ...)
                        LazyRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            items(tags) { tag ->
                                Text(
                                    text = "#$tag",
                                    color = SpotifySubtle,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }

                        Spacer(Modifier.height(14.dp))

                        // Bottom Track Card (tap to play full track from 0:00, tap + to save!)
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color.White.copy(alpha = 0.09f))
                                .clickable { vm.playFullTrackFromSample(sampleTrack, tracks) }
                                .padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            ArtworkImage(sampleTrack.artworkUrl, sampleTrack.title, Modifier.size(46.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    sampleTrack.title,
                                    color = SpotifyWhite,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.5.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    sampleTrack.artist,
                                    color = SpotifySubtle,
                                    fontSize = 12.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            IconButton(onClick = { vm.toggleLike(sampleTrack) }, modifier = Modifier.size(38.dp)) {
                                Icon(
                                    imageVector = if (isLiked) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                                    contentDescription = "Save",
                                    tint = if (isLiked) SpotifyGreen else SpotifyWhite,
                                    modifier = Modifier.size(26.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Fixed Transparent Top Bar: Back Arrow + Feed Title + Mute Toggle
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            IconButton(onClick = { vm.closeSamplesFeed() }) {
                Icon(if (isEn) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward, str.back, tint = SpotifyWhite)
            }
            Text(
                text = feed.title,
                color = SpotifyWhite.copy(alpha = 0.92f),
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = { vm.toggleSamplesMute() }) {
                Icon(
                    imageVector = if (feed.isMuted) Icons.Rounded.VolumeOff else Icons.Rounded.VolumeUp,
                    contentDescription = "Mute",
                    tint = if (feed.isMuted) Color(0xFFFF8A80) else SpotifyWhite
                )
            }
        }
    }
}

private fun buildSampleHashtags(track: Track, isEn: Boolean): List<String> {
    val l = "${track.title} ${track.artist}".lowercase()
    return when {
        l.contains("sad") || l.contains("bleed") || l.contains("cry") || l.contains("heart") ->
            if (isEn) listOf("heartache", "chill", "late-night") else listOf("רגש", "צ'יל", "לילה")
        l.contains("workout") || l.contains("gym") || l.contains("phonk") || l.contains("enemy") || l.contains("darkside") ->
            if (isEn) listOf("energy", "hype", "workout") else listOf("אנרגיה", "אימון", "עוצמה")
        track.title.any { it in '\u0590'..'\u05FF' } ->
            listOf("ישראלי", "להיטים", "וייב")
        else ->
            if (isEn) listOf("discover", "vibes", "trending") else listOf("גילוי", "וייב", "מומלץ")
    }
}
