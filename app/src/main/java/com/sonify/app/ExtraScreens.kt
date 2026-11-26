@file:kotlin.OptIn(ExperimentalMaterial3Api::class)

package com.sonify.app

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

// 1. Listening Stats & Wrapped Sheet
@Composable
fun StatsWrappedSheet(state: UiState, vm: AppViewModel) {
    val minutes = (state.stats.totalListeningMs / 60_000L).coerceAtLeast(0L)
    val topArtists = state.stats.artistPlayCounts.entries.sortedByDescending { it.value }.take(5)
    val allKnown = (state.recentTracks + state.likedTracks).distinctBy { it.id }.associateBy { it.id }
    val topTracks = state.stats.trackPlayCounts.entries.sortedByDescending { it.value }.mapNotNull { (id, cnt) -> allKnown[id]?.let { it to cnt } }.take(5)

    ModalBottomSheet(onDismissRequest = { vm.setShowStatsSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        LazyColumn(modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp), contentPadding = PaddingValues(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Card(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(containerColor = Color.Transparent)) {
                    Column(
                        modifier = Modifier.fillMaxWidth()
                            .background(Brush.linearGradient(listOf(Color(0xFF1DB954), Color(0xFF1E3264))))
                            .padding(18.dp)
                    ) {
                        Text("Sonify Wrapped • סטטיסטיקות האזנה", color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("$minutes דקות האזנה מצטברות", color = SpotifyWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("${state.stats.trackPlayCounts.values.sum()} השמעות • ${state.likedTracks.size} שירים שאהבת", color = SpotifyWhite.copy(alpha = 0.85f), fontSize = 13.sp)
                    }
                }
            }
            item { Text("האמנים המובילים שלך", color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            if (topArtists.isEmpty()) item { Text("האזן לכמה שירים כדי לראות את האמנים המובילים שלך.", color = SpotifySubtle, fontSize = 13.sp) }
            items(topArtists) { (artist, plays) ->
                Row(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyCard).padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(artist, color = SpotifyWhite, fontWeight = FontWeight.SemiBold)
                    Text("$plays השמעות", color = SpotifyGreen, fontSize = 12.sp)
                }
            }
            item { Text("השירים הכי מושמעים", color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            items(topTracks) { (track, plays) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { vm.playTrack(track); vm.setShowStatsSheet(false) }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ArtworkImage(track.artworkUrl, null, Modifier.size(44.dp))
                    Spacer(Modifier.width(10.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(track.title, color = SpotifyWhite, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(track.artist, color = SpotifySubtle, fontSize = 12.sp)
                    }
                    Text("$plays×", color = SpotifyGreen, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

// 2. Listening History (Recents) Sheet
@Composable
fun ListeningHistorySheet(state: UiState, vm: AppViewModel) {
    ModalBottomSheet(onDismissRequest = { vm.setShowHistorySheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("היסטוריית האזנה (${state.recentTracks.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { vm.clearRecentHistory() }) { Text("נקה", color = Color(0xFFFF6B6B)) }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.recentTracks, key = { it.id }) { track ->
                    TrackRowItem(
                        track = track, isOffline = track.id in state.offlineTrackIds, isDownloading = track.id in state.downloadingIds,
                        onClick = { vm.playTrack(track, state.recentTracks); vm.setShowHistorySheet(false) },
                        onDownloadClick = { vm.downloadOffline(track) }, onMoreClick = { vm.openTrackOptions(track) }
                    )
                }
            }
        }
    }
}

// 3. Messages & Friends Social Sheet
@Composable
fun MessagesAndFriendsSheet(state: UiState, vm: AppViewModel) {
    var msgText by remember { mutableStateOf("") }
    var friendInput by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = { vm.setShowMessagesSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text("הודעות וחברים", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text("חברים שאתה עוקב אחריהם:", color = SpotifySubtle, fontSize = 12.sp)
            LazyRow(modifier = Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(state.followedFriends) { f ->
                    Box(Modifier.clip(RoundedCornerShape(50)).background(SpotifyCard).padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(f, color = SpotifyWhite, fontSize = 12.sp)
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = friendInput, onValueChange = { friendInput = it }, placeholder = { Text("הוסף חבר למעקב...") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.addFriend(friendInput); friendInput = "" }, modifier = Modifier.clip(CircleShape).background(SpotifyGreen)) {
                    Icon(Icons.Rounded.PersonAdd, contentDescription = "הוסף", tint = Color.Black)
                }
            }
            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = SpotifyCard)
            LazyColumn(modifier = Modifier.heightIn(min = 140.dp, max = 260.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (state.messages.isEmpty()) item { Text("שלח הודעה, הזמנה ל-Jam או שתף שיר כאן.", color = SpotifySubtle, fontSize = 13.sp) }
                items(state.messages, key = { it.id }) { m ->
                    Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SpotifyCard).padding(10.dp)) {
                        Text(m.sender, color = SpotifyGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (m.text.isNotBlank()) Text(m.text, color = SpotifyWhite, fontSize = 14.sp)
                        m.sharedTrack?.let { trk ->
                            Spacer(Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(SpotifyDarkSurface)
                                    .clickable { vm.playTrack(trk, startAtMs = (m.trackTimestampSec ?: 0) * 1000L); vm.setShowMessagesSheet(false) }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                ArtworkImage(trk.artworkUrl, null, Modifier.size(38.dp))
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(trk.title, color = SpotifyWhite, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(trk.artist, color = SpotifySubtle, fontSize = 11.sp)
                                }
                                Icon(Icons.Rounded.PlayArrow, null, tint = SpotifyGreen)
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = msgText, onValueChange = { msgText = it }, placeholder = { Text("כתוב הודעה...") }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.sendMessage(msgText); msgText = "" }, modifier = Modifier.clip(CircleShape).background(SpotifyGreen)) {
                    Icon(Icons.Rounded.Send, contentDescription = "שלח", tint = Color.Black)
                }
            }
            if (state.currentTrack != null) {
                TextButton(onClick = { vm.sendMessage("תקשיב לזה!", state.currentTrack, (state.positionMs / 1000).toInt()) }) {
                    Text("שתף את השיר המתנגן עכשיו עם חותמת זמן", color = SpotifyGreen, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

// 4. Visual Sonify Code (Spotify-Style Soundwave Barcode)
@Composable
fun VisualCodeSheet(title: String, codeData: String, onDismiss: () -> Unit) {
    val bars = remember(codeData) {
        val hash = codeData.hashCode()
        List(23) { i ->
            val v = abs((hash * (i + 7) * 31 + i * 17) % 100)
            0.22f + (v / 100f) * 0.78f
        }
    }
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("קוד Sonify לסריקה מהירה", color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(16.dp))
            Box(
                modifier = Modifier.fillMaxWidth().height(92.dp).clip(RoundedCornerShape(14.dp)).background(SpotifyWhite).padding(horizontal = 20.dp, vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val gap = size.width / (bars.size * 1.65f)
                    val barW = gap * 0.8f
                    var x = gap
                    bars.forEach { frac ->
                        val h = size.height * frac
                        val top = (size.height - h) / 2f
                        drawRoundRect(Color.Black, Offset(x, top), Size(barW, h), CornerRadius(barW / 2f, barW / 2f))
                        x += gap * 1.6f
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Text(codeData, color = SpotifySubtle, fontSize = 12.sp)
            Spacer(Modifier.height(20.dp))
        }
    }
}

// 5. Playlist Cover Art Creator
@Composable
fun CoverArtCreatorSheet(playlist: Playlist, vm: AppViewModel) {
    val colors = listOf(0xFF1DB954, 0xFFE8115B, 0xFF1E3264, 0xFFBC5900, 0xFF9C27B0, 0xFF0D73EC, 0xFFE91429, 0xFF006450)
    val stickers = listOf("", "🔥", "🎧", "⚡", "🌙", "🎸", "💎", "🚀")
    var selectedColor by remember { mutableLongStateOf(playlist.customCoverBgHex ?: colors.first()) }
    var coverText by remember { mutableStateOf(playlist.customCoverText.ifBlank { playlist.name }) }
    var selectedSticker by remember { mutableStateOf(playlist.customCoverSticker) }

    ModalBottomSheet(onDismissRequest = { vm.setEditingCoverPlaylist(null) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("מעצב עטיפת פלייליסט", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            PlaylistCoverBox(
                playlist.copy(customCoverBgHex = selectedColor, customCoverText = coverText, customCoverSticker = selectedSticker),
                Modifier.size(160.dp)
            )
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(value = coverText, onValueChange = { coverText = it }, label = { Text("טקסט על העטיפה") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(colors) { c ->
                    Box(Modifier.size(34.dp).clip(CircleShape).background(Color(c)).clickable { selectedColor = c }) {
                        if (selectedColor == c) Icon(Icons.Rounded.Check, null, tint = Color.Black, modifier = Modifier.align(Alignment.Center).size(18.dp))
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(stickers) { s ->
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(if (selectedSticker == s) SpotifyGreen else SpotifyCard)
                            .clickable { selectedSticker = s }.padding(horizontal = 12.dp, vertical = 6.dp)
                    ) { Text(if (s.isEmpty()) "ללא" else s, color = if (selectedSticker == s) Color.Black else SpotifyWhite) }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.updatePlaylistCover(playlist.id, selectedColor, coverText, selectedSticker) },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) { Text("שמור עטיפה", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(18.dp))
        }
    }
}

// 6. Import Playlist Sheet (URL or Track List)
@Composable
fun ImportPlaylistSheet(vm: AppViewModel) {
    var name by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = { vm.setShowImportSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text("ייבוא פלייליסט", fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text("הדבק קישור לפלייליסט YouTube או רשימת שמות שירים (שיר אחד בכל שורה).", color = SpotifySubtle, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("שם הפלייליסט") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = raw, onValueChange = { raw = it }, label = { Text("קישור או רשימת שירים") }, minLines = 4, maxLines = 6, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Button(
                onClick = { vm.importPlaylist(name, raw) },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                modifier = Modifier.fillMaxWidth()
            ) { Text("ייבא עכשיו", fontWeight = FontWeight.Bold) }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// 7. Recover Deleted Playlists Sheet
@Composable
fun RecoverPlaylistsSheet(state: UiState, vm: AppViewModel) {
    ModalBottomSheet(onDismissRequest = { vm.setShowRecoverSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text("שחזור פלייליסטים שנמחקו", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (state.deletedPlaylists.isEmpty()) {
                Text("אין פלייליסטים שנמחקו לאחרונה.", color = SpotifySubtle)
            } else {
                state.deletedPlaylists.forEach { rec ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyCard).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rec.playlist.name, color = SpotifyWhite, fontWeight = FontWeight.SemiBold)
                            Text("${rec.playlist.tracks.size} שירים", color = SpotifySubtle, fontSize = 12.sp)
                        }
                        Button(onClick = { vm.recoverDeletedPlaylist(rec) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)) {
                            Text("שחזר", fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
