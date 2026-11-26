@file:kotlin.OptIn(ExperimentalMaterial3Api::class)

package com.sonify.app

import android.content.Intent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

@Composable
fun StatsWrappedSheet(state: UiState, vm: AppViewModel) {
    val str = state.strings
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
                        Text("Sonify Wrapped • ${str.statsWrapped}", color = SpotifyWhite, fontWeight = FontWeight.ExtraBold, fontSize = 18.sp)
                        Spacer(Modifier.height(6.dp))
                        Text("$minutes ${str.t("דקות האזנה מצטברות", "minutes listened")}", color = SpotifyWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        Text("${state.stats.trackPlayCounts.values.sum()} ${str.t("השמעות", "plays")} • ${state.likedTracks.size} ${str.likedSongs}", color = SpotifyWhite.copy(alpha = 0.85f), fontSize = 13.sp)
                    }
                }
            }
            item { Text(str.t("האמנים המובילים שלך", "Your Top Artists"), color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
            if (topArtists.isEmpty()) item { Text(str.t("האזן לכמה שירים כדי לראות את האמנים המובילים.", "Play some songs to see your top artists."), color = SpotifySubtle, fontSize = 13.sp) }
            items(topArtists) { (artist, plays) ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyCard)
                        .clickable { vm.setShowStatsSheet(false); vm.openArtistPage(artist) }.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(artist, color = SpotifyWhite, fontWeight = FontWeight.SemiBold)
                    Text("$plays ${str.t("השמעות", "plays")}", color = SpotifyGreen, fontSize = 12.sp)
                }
            }
            item { Text(str.t("השירים הכי מושמעים", "Your Top Tracks"), color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
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

@Composable
fun ListeningHistorySheet(state: UiState, vm: AppViewModel) {
    val str = state.strings
    ModalBottomSheet(onDismissRequest = { vm.setShowHistorySheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${str.listeningHistory} (${state.recentTracks.size})", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                TextButton(onClick = { vm.clearRecentHistory() }) { Text(str.t("נקה", "Clear"), color = Color(0xFFFF6B6B)) }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(modifier = Modifier.heightIn(max = 460.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                items(state.recentTracks, key = { it.id }) { track ->
                    TrackRowItem(
                        track = track, isOffline = track.id in state.offlineTrackIds, isDownloading = track.id in state.downloadingIds,
                        downloadPct = state.downloadProgress[track.id], containerColor = Color.Transparent,
                        onClick = { vm.playTrack(track, state.recentTracks); vm.setShowHistorySheet(false) },
                        onDownloadClick = { vm.downloadOffline(track) }, onMoreClick = { vm.openTrackOptions(track) }
                    )
                }
            }
        }
    }
}

// Upgraded Messages & Friends Sheet (Per-friend threads, Online status, Blend/Jam invites, proper keyboard padding!)
@Composable
fun MessagesAndFriendsSheet(state: UiState, vm: AppViewModel) {
    val str = state.strings
    var msgText by remember { mutableStateOf("") }
    var friendInput by remember { mutableStateOf("") }
    var showAddFriend by remember { mutableStateOf(false) }

    val activeThread = state.activeChatFriend
    val activeProfile = state.friends.firstOrNull { it.name == activeThread }
    val threadMessages = remember(state.messages, activeThread) {
        state.messages.filter { it.friendThread == activeThread || (activeThread == "כללי" && it.friendThread.isBlank()) }
    }

    ModalBottomSheet(onDismissRequest = { vm.setShowMessagesSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).navigationBarsPadding().imePadding()
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(str.messagesAndFriends, fontSize = 19.sp, fontWeight = FontWeight.Bold)
                IconButton(onClick = { showAddFriend = !showAddFriend }) {
                    Icon(Icons.Rounded.PersonAdd, "Add Friend", tint = SpotifyGreen)
                }
            }

            if (showAddFriend) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                    OutlinedTextField(value = friendInput, onValueChange = { friendInput = it }, placeholder = { Text(str.t("שם חבר חדש...", "New friend name...")) }, singleLine = true, modifier = Modifier.weight(1f))
                    Button(onClick = { vm.addFriend(friendInput); friendInput = ""; showAddFriend = false }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)) {
                        Text(str.t("הוסף", "Add"), fontWeight = FontWeight.Bold)
                    }
                }
            }

            // Friends / Threads Selector Row
            LazyRow(modifier = Modifier.padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    val sel = activeThread == "כללי"
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { vm.selectChatFriend("כללי") }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text(str.t("צ'אט כללי", "General"), color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
                items(state.friends, key = { it.name }) { friend ->
                    val sel = activeThread == friend.name
                    Row(
                        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard)
                            .clickable { vm.selectChatFriend(friend.name) }.padding(horizontal = 12.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(Modifier.size(7.dp).clip(CircleShape).background(if (friend.isOnline) (if (sel) Color.Black else SpotifyGreen) else SpotifyMuted))
                        Spacer(Modifier.width(6.dp))
                        Text(friend.name, color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }

            // Selected Friend Actions Card (Activity, Invite Jam, Create Blend, Remove)
            activeProfile?.let { fp ->
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SpotifyCard).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(fp.name, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        Text(fp.currentActivity.ifBlank { if (fp.isOnline) str.t("מחובר", "Online") else str.t("לא מחובר", "Offline") }, color = SpotifySubtle, fontSize = 11.sp)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { vm.inviteFriendToJam(fp.name) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Jam", color = SpotifyGreen, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        TextButton(onClick = { vm.setShowMessagesSheet(false); vm.createBlend(fp.name) }, contentPadding = PaddingValues(horizontal = 8.dp)) { Text("Blend", color = Color(0xFFCE93D8), fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                        IconButton(onClick = { vm.removeFriend(fp.name) }, modifier = Modifier.size(30.dp)) { Icon(Icons.Rounded.PersonRemove, "Remove", tint = Color(0xFFFF6B6B), modifier = Modifier.size(16.dp)) }
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp), color = SpotifyCard)

            // Messages Thread
            Column(modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (threadMessages.isEmpty()) {
                    Text(str.t("אין עדיין הודעות עם $activeThread — שלח שיר, קוד Jam או הודעה!", "No messages with $activeThread yet — share a track or Jam!"), color = SpotifySubtle, fontSize = 13.sp)
                } else {
                    threadMessages.takeLast(12).forEach { m ->
                        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(SpotifyCard).padding(10.dp)) {
                            Text(m.sender, color = SpotifyGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                            if (m.text.isNotBlank()) Text(m.text, color = SpotifyWhite, fontSize = 14.sp)
                            m.sharedJamCode?.let { code ->
                                Spacer(Modifier.height(6.dp))
                                Button(
                                    onClick = { vm.joinJamByCode(code); vm.setShowMessagesSheet(false) },
                                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)
                                ) { Text("${str.t("הצטרף ל-Jam", "Join Jam")} ($code)", fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                            }
                            m.sharedTrack?.let { trk ->
                                Spacer(Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp)).background(SpotifyDarkSurface)
                                        .clickable { vm.playTrack(trk, startAtMs = (m.trackTimestampSec ?: 0) * 1000L); vm.setShowMessagesSheet(false) }.padding(8.dp),
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
            }

            Spacer(Modifier.height(12.dp))
            if (state.currentTrack != null) {
                OutlinedButton(
                    onClick = { vm.sendMessage(str.t("תקשיב לשיר הזה!", "Listen to this!"), sharedTrack = state.currentTrack, timestampSec = (state.positionMs / 1000).toInt()) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Rounded.MusicNote, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(str.t("שתף את \"${state.currentTrack.title}\" בצ'אט", "Share \"${state.currentTrack.title}\" here"), color = SpotifyWhite, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(8.dp))
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = msgText, onValueChange = { msgText = it }, placeholder = { Text(str.t("כתוב הודעה...", "Write a message...")) }, singleLine = true, modifier = Modifier.weight(1f))
                IconButton(onClick = { vm.sendMessage(msgText); msgText = "" }, modifier = Modifier.clip(CircleShape).background(SpotifyGreen)) {
                    Icon(Icons.Rounded.Send, "Send", tint = Color.Black)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// Upgraded Visual Sonify Code (Copy + Share + Open/Paste Code to Play Immediately!)
@Composable
fun VisualCodeSheet(title: String, codeData: String, vm: AppViewModel, str: AppStrings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var openCodeInput by remember { mutableStateOf("") }
    val bars = remember(codeData) {
        val hash = codeData.ifBlank { "sonify" }.hashCode()
        List(23) { i -> 0.22f + (abs((hash * (i + 7) * 31 + i * 17) % 100) / 100f) * 0.78f }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(22.dp).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(str.t("קוד Sonify לסריקה ושיתוף", "Sonify Share Code"), color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 18.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)

            if (codeData.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Box(modifier = Modifier.fillMaxWidth().height(88.dp).clip(RoundedCornerShape(14.dp)).background(SpotifyWhite).padding(horizontal = 20.dp, vertical = 14.dp), contentAlignment = Alignment.Center) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        val gap = size.width / (bars.size * 1.65f)
                        val barW = gap * 0.8f
                        var x = gap
                        bars.forEach { frac ->
                            val h = size.height * frac
                            drawRoundRect(Color.Black, Offset(x, (size.height - h) / 2f), Size(barW, h), CornerRadius(barW / 2f, barW / 2f))
                            x += gap * 1.6f
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text(codeData, color = SpotifySubtle, fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(codeData)) }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ContentCopy, null, tint = SpotifyWhite, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(str.t("העתק קוד", "Copy Code"), color = SpotifyWhite, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$title\n$codeData")
                            }, "Share Code"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                        modifier = Modifier.weight(1f)
                    ) { Icon(Icons.Rounded.Share, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(str.t("שתף", "Share"), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(vertical = 14.dp), color = SpotifyCard)
            Text(str.t("פתח קוד Sonify או קישור שיר/פלייליסט/Jam:", "Open a Sonify Code, Jam or YouTube Link:"), color = SpotifySubtle, fontSize = 12.sp, modifier = Modifier.align(Alignment.Start))
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = openCodeInput, onValueChange = { openCodeInput = it }, placeholder = { Text("sonify:track:... / youtu.be/...") }, singleLine = true, modifier = Modifier.weight(1f))
                Button(onClick = { vm.openSonifyCodeOrLink(openCodeInput) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)) {
                    Text(str.t("פתח", "Open"), fontWeight = FontWeight.Bold)
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
fun CoverArtCreatorSheet(playlist: Playlist, vm: AppViewModel, str: AppStrings) {
    val colors = listOf(0xFF1DB954, 0xFFE8115B, 0xFF1E3264, 0xFFBC5900, 0xFF9C27B0, 0xFF0D73EC, 0xFFE91429, 0xFF006450)
    val stickers = listOf("", "🔥", "🎧", "⚡", "🌙", "🎸", "💎", "🚀")
    var selectedColor by remember { mutableLongStateOf(playlist.customCoverBgHex ?: colors.first()) }
    var coverText by remember { mutableStateOf(playlist.customCoverText.ifBlank { playlist.name }) }
    var selectedSticker by remember { mutableStateOf(playlist.customCoverSticker) }

    ModalBottomSheet(onDismissRequest = { vm.setEditingCoverPlaylist(null) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp).imePadding(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(str.t("מעצב עטיפת פלייליסט", "Playlist Cover Creator"), fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            PlaylistCoverBox(playlist.copy(customCoverBgHex = selectedColor, customCoverText = coverText, customCoverSticker = selectedSticker), Modifier.size(160.dp))
            Spacer(Modifier.height(14.dp))
            OutlinedTextField(value = coverText, onValueChange = { coverText = it }, label = { Text(str.t("טקסט על העטיפה", "Cover Title")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
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
                    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(if (selectedSticker == s) SpotifyGreen else SpotifyCard).clickable { selectedSticker = s }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                        Text(if (s.isEmpty()) str.t("ללא", "None") else s, color = if (selectedSticker == s) Color.Black else SpotifyWhite)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Button(onClick = { vm.updatePlaylistCover(playlist.id, selectedColor, coverText, selectedSticker) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                Text(str.save, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

@Composable
fun ImportPlaylistSheet(vm: AppViewModel, str: AppStrings) {
    var name by remember { mutableStateOf("") }
    var raw by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = { vm.setShowImportSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp).imePadding()) {
            Text(str.importPlaylist, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            Text(str.t("הדבק קישור לפלייליסט YouTube או רשימת שמות שירים (שיר בכל שורה, עד 60 שירים).", "Paste a YouTube playlist URL or song list (one per line, up to 60 tracks)."), color = SpotifySubtle, fontSize = 12.sp)
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text(str.t("שם הפלייליסט", "Playlist Name")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = raw, onValueChange = { raw = it }, label = { Text(str.t("קישור או רשימת שירים", "URL or Track List")) }, minLines = 4, maxLines = 6, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(14.dp))
            Button(onClick = { vm.importPlaylist(name, raw) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                Text(str.t("ייבא עכשיו", "Import Now"), fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

@Composable
fun RecoverPlaylistsSheet(state: UiState, vm: AppViewModel) {
    val str = state.strings
    ModalBottomSheet(onDismissRequest = { vm.setShowRecoverSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(str.recoverPlaylists, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(10.dp))
            if (state.deletedPlaylists.isEmpty()) {
                Text(str.t("אין פלייליסטים שנמחקו לאחרונה.", "No recently deleted playlists."), color = SpotifySubtle)
            } else {
                state.deletedPlaylists.forEach { rec ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(SpotifyCard).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rec.playlist.name, color = SpotifyWhite, fontWeight = FontWeight.SemiBold)
                            Text("${rec.playlist.tracks.size} ${str.songs}", color = SpotifySubtle, fontSize = 12.sp)
                        }
                        Button(onClick = { vm.recoverDeletedPlaylist(rec) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)) {
                            Text(str.t("שחזר", "Restore"), fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}
