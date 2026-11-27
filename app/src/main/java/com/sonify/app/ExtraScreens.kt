@file:kotlin.OptIn(ExperimentalMaterial3Api::class)

package com.sonify.app

import android.content.Intent
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

// =====================================================================
// 1. WRAPPED & LISTENING STATS SHEET
// =====================================================================
@Composable
fun StatsWrappedSheet(state: UiState, vm: AppViewModel) {
    val str = state.strings
    val context = LocalContext.current
    val totalMin = (state.stats.totalListeningMs / 60_000L).coerceAtLeast(0L)
    val topArtists = remember(state.stats.artistPlayCounts) {
        state.stats.artistPlayCounts.entries.sortedByDescending { it.value }.take(6)
    }
    val allKnown = remember(state.likedTracks, state.recentTracks, state.playlists) {
        (state.likedTracks + state.recentTracks + state.playlists.flatMap { it.tracks }).associateBy { it.id }
    }
    val topTracks = remember(state.stats.trackPlayCounts, allKnown) {
        state.stats.trackPlayCounts.entries.sortedByDescending { it.value }.mapNotNull { (id, cnt) ->
            allKnown[id]?.let { it to cnt }
        }.take(6)
    }

    ModalBottomSheet(onDismissRequest = { vm.setShowStatsSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp)) {
            Box(
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF1DB954), Color(0xFF1E3264))))
                    .padding(18.dp)
            ) {
                Column {
                    Text("SONIFY WRAPPED", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 11.sp, letterSpacing = 1.sp)
                    Spacer(Modifier.height(6.dp))
                    Text("$totalMin ${str.t("דקות האזנה", "minutes listened")}", color = Color.White, fontWeight = FontWeight.Black, fontSize = 26.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${state.likedTracks.size} ${str.t("אהובים", "liked")} • ${state.playlists.size} ${str.t("פלייליסטים", "playlists")} • ${state.offlineTrackIds.size} ${str.t("באופליין", "offline")}",
                        color = Color.White.copy(alpha = 0.88f), fontSize = 12.sp
                    )
                }
            }

            if (topArtists.isNotEmpty()) {
                Spacer(Modifier.height(16.dp))
                Text(str.t("האמנים המובילים שלך", "Your Top Artists"), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                topArtists.forEachIndexed { idx, (artist, plays) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { vm.setShowStatsSheet(false); vm.openArtistPage(artist) }
                            .padding(vertical = 8.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("${idx + 1}", color = SpotifyGreen, fontWeight = FontWeight.Black, modifier = Modifier.width(24.dp))
                        Text(artist, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 14.5.sp, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("$plays ${str.t("השמעות", "plays")}", color = SpotifySubtle, fontSize = 12.sp)
                    }
                }
            }

            if (topTracks.isNotEmpty()) {
                Spacer(Modifier.height(14.dp))
                Text(str.t("השירים שחרשת עליהם", "Your Top Songs"), fontWeight = FontWeight.ExtraBold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                topTracks.forEach { (trk, plays) ->
                    Row(
                        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                            .clickable { vm.setShowStatsSheet(false); vm.playTrack(trk, topTracks.map { it.first }) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        ArtworkImage(trk.artworkUrl, trk.title, Modifier.size(42.dp))
                        Spacer(Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(trk.title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 13.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(trk.artist, color = SpotifySubtle, fontSize = 11.5.sp, maxLines = 1)
                        }
                        Text("${plays}x", color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    val topArtTxt = topArtists.take(3).joinToString(", ") { it.first }
                    val summary = "Sonify Wrapped: $totalMin ${str.t("דקות האזנה", "min listened")}! ${if (topArtTxt.isNotBlank()) "Top: $topArtTxt" else ""}"
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"; putExtra(Intent.EXTRA_TEXT, summary)
                    }, "Wrapped"))
                },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.Share, null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(str.t("שתף סיכום האזנה", "Share Stats"), fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

// =====================================================================
// 2. LISTENING HISTORY SHEET
// =====================================================================
@Composable
fun ListeningHistorySheet(state: UiState, vm: AppViewModel) {
    val str = state.strings
    ModalBottomSheet(onDismissRequest = { vm.setShowHistorySheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(str.listeningHistory, fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
                if (state.recentTracks.isNotEmpty()) {
                    TextButton(onClick = { vm.clearRecentHistory() }) {
                        Text(str.t("נקה הכל", "Clear All"), color = Color(0xFFFF8A80), fontWeight = FontWeight.Bold)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            if (state.recentTracks.isEmpty()) {
                Text(str.t("עדיין אין היסטוריית השמעות.", "No listening history yet."), color = SpotifySubtle, modifier = Modifier.padding(vertical = 24.dp))
            } else {
                LazyColumn(modifier = Modifier.heightIn(max = 460.dp)) {
                    items(state.recentTracks, key = { "hist_${it.id}" }) { trk ->
                        TrackRowItem(
                            track = trk,
                            isOffline = trk.id in state.offlineTrackIds,
                            isDownloading = trk.id in state.downloadingIds,
                            downloadPct = state.downloadProgress[trk.id],
                            isPlayingCurrent = state.currentTrack?.id == trk.id,
                            isAudioPlaying = state.isPlaying,
                            onClick = { vm.setShowHistorySheet(false); vm.playTrack(trk, state.recentTracks) },
                            onDownloadClick = { vm.downloadOffline(trk) },
                            onMoreClick = { vm.openTrackOptions(trk) }
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }
}

// =====================================================================
// 3. COMPLETE CLOUDLESS LIVE CHAT & FRIENDS SHEET (Zero Accounts, Real-Time Rooms!)
// =====================================================================
@Composable
fun MessagesAndFriendsSheet(state: UiState, vm: AppViewModel) {
    val str = state.strings
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    var msgInput by remember { mutableStateOf("") }
    var showAddRoomPanel by remember { mutableStateOf(false) }
    var newFriendName by remember { mutableStateOf("") }
    var joinRoomCode by remember { mutableStateOf("") }
    var showSharePlaylistPicker by remember { mutableStateOf(false) }

    val activeFriendName = state.activeChatFriend
    val activeFriendObj = state.friends.firstOrNull { it.name == activeFriendName }
    val activeRoomCode = activeFriendObj?.roomCode.orEmpty()
    val threadMessages = remember(state.messages, activeFriendName) {
        state.messages.filter { it.friendThread == activeFriendName }
    }

    ModalBottomSheet(onDismissRequest = { vm.setShowMessagesSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).imePadding()) {
            // Header
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(str.t("צ'אט וחברים (ללא ענן)", "Live Chat & Friends (No Cloud)"), fontSize = 18.sp, fontWeight = FontWeight.ExtraBold)
                    Text(
                        if (activeRoomCode.isNotBlank()) {
                            if (state.activeChatLiveConnected) str.t("מחובר חי לחדר $activeRoomCode", "Live Connected • Room $activeRoomCode")
                            else str.t("מתחבר לחדר $activeRoomCode...", "Connecting to room $activeRoomCode...")
                        } else str.t("פתקים מקומיים • לחץ + ליצירת חדר חי עם חבר", "Local notes • Tap + to open a live room"),
                        color = if (state.activeChatLiveConnected) SpotifyGreen else SpotifySubtle,
                        fontSize = 11.5.sp
                    )
                }
                Row {
                    IconButton(onClick = { showAddRoomPanel = !showAddRoomPanel }) {
                        Icon(if (showAddRoomPanel) Icons.Rounded.Close else Icons.Rounded.PersonAdd, "New Room", tint = SpotifyGreen)
                    }
                    if (activeFriendName != "כללי") {
                        IconButton(onClick = { vm.removeFriend(activeFriendName) }) {
                            Icon(Icons.Rounded.DeleteOutline, "Remove", tint = Color(0xFFFF8A80))
                        }
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Friend / Room Chips Row
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    val sel = activeFriendName == "כללי"
                    FilterChip(
                        selected = sel,
                        onClick = { vm.selectChatFriend("כללי") },
                        label = { Text(str.t("פתקים / כללי", "Notes / General"), fontSize = 12.sp) }
                    )
                }
                items(state.friends, key = { "fr_${it.name}_${it.roomCode}" }) { fr ->
                    val sel = activeFriendName == fr.name
                    FilterChip(
                        selected = sel,
                        onClick = { vm.selectChatFriend(fr.name) },
                        label = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (fr.roomCode.isNotBlank()) {
                                    Box(Modifier.size(7.dp).clip(CircleShape).background(if (sel && state.activeChatLiveConnected) SpotifyGreen else Color(0xFF66BB6A)))
                                    Spacer(Modifier.width(5.dp))
                                }
                                Text(fr.name, fontSize = 12.sp)
                            }
                        }
                    )
                }
            }

            // Create / Join Cloudless Live Room Panel
            if (showAddRoomPanel) {
                Spacer(Modifier.height(10.dp))
                Card(colors = CardDefaults.cardColors(containerColor = SpotifyCard), shape = RoundedCornerShape(14.dp)) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(str.t("פתח חדר צ'אט חי חדש (ללא חשבון)", "Open New Live Chat Room (No Account)"), color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = newFriendName, onValueChange = { newFriendName = it },
                                placeholder = { Text(str.t("שם החבר או הקבוצה...", "Friend or group name...")) },
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    if (newFriendName.isNotBlank()) {
                                        vm.createLiveChatRoom(newFriendName)
                                        newFriendName = ""
                                        showAddRoomPanel = false
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)
                            ) { Text(str.t("פתח", "Open"), fontWeight = FontWeight.Bold) }
                        }
                        HorizontalDivider(color = SpotifyDarkSurface)
                        Text(str.t("או הצטרף לחדר קיים עם קוד / קישור:", "Or join an existing room by code:"), color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 12.5.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = joinRoomCode, onValueChange = { joinRoomCode = it },
                                placeholder = { Text(str.t("קוד חדר (6 תווים)...", "6-char room code...")) },
                                singleLine = true, modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = {
                                    val raw = joinRoomCode.trim()
                                    if (raw.contains("http", true) || raw.contains("sonify", true)) {
                                        vm.handleIncomingDeepLink(raw)
                                    } else if (raw.length >= 4) {
                                        vm.joinLiveChatRoom(raw, newFriendName)
                                    }
                                    joinRoomCode = ""
                                    showAddRoomPanel = false
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SpotifyDarkSurface)
                            ) { Text(str.t("הצטרף", "Join"), color = SpotifyWhite, fontWeight = FontWeight.Bold) }
                        }
                    }
                }
            }

            // Active Room Invite Bar (Share real clickable link + QR + Invite to Jam!)
            if (activeRoomCode.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                val inviteLink = SonifyShareLinks.chatRoomUrl(activeRoomCode, vm.effectiveJamName())
                Row(
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(Color(0xFF1A2E25))
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("${str.t("קוד חדר", "Room")}: $activeRoomCode", color = SpotifyGreen, fontWeight = FontWeight.ExtraBold, fontSize = 12.5.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(inviteLink)) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Rounded.ContentCopy, "Copy", tint = SpotifyWhite, modifier = Modifier.size(16.dp))
                        }
                        IconButton(
                            onClick = {
                                context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, "הצטרף לצ'אט המוזיקה שלי ב-Sonify (קוד: $activeRoomCode):\n$inviteLink")
                                }, "Chat Invite"))
                            },
                            modifier = Modifier.size(30.dp)
                        ) { Icon(Icons.Rounded.Share, "Share", tint = SpotifyGreen, modifier = Modifier.size(16.dp)) }
                        IconButton(onClick = { vm.setShowMessagesSheet(false); vm.setShowCodeFor("${str.t("צ'אט", "Chat")} • $activeFriendName" to inviteLink) }, modifier = Modifier.size(30.dp)) {
                            Icon(Icons.Rounded.QrCode2, "QR", tint = SpotifyWhite, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Messages Feed
            if (threadMessages.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().height(170.dp), contentAlignment = Alignment.Center) {
                    Text(
                        if (activeRoomCode.isNotBlank()) str.t("החדר פעיל! שלח הודעה, שיר מתנגן או פלייליסט — יופיע אצל החבר בזמן אמת.", "Room is live! Send a message, track, or playlist to sync instantly.")
                        else str.t("אין הודעות בשיחה זו עדיין.", "No messages in this thread yet."),
                        color = SpotifySubtle, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp)
                    )
                }
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 300.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(threadMessages, key = { it.id }) { m ->
                        val isMe = m.sender == vm.effectiveJamName() || m.sender == "אתה" || m.sender == "You"
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = if (isMe) Alignment.End else Alignment.Start
                        ) {
                            Card(
                                colors = CardDefaults.cardColors(containerColor = if (isMe) Color(0xFF1A3A2E) else SpotifyCard),
                                shape = RoundedCornerShape(14.dp),
                                modifier = Modifier.widthIn(max = 290.dp)
                            ) {
                                Column(modifier = Modifier.padding(11.dp)) {
                                    if (!isMe) {
                                        Text(m.sender, color = SpotifyGreen, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                                        Spacer(Modifier.height(2.dp))
                                    }
                                    if (m.text.isNotBlank()) {
                                        Text(m.text, color = SpotifyWhite, fontSize = 14.sp)
                                    }
                                    m.sharedTrack?.let { trk ->
                                        Spacer(Modifier.height(6.dp))
                                        Row(
                                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(Color.Black.copy(alpha = 0.35f))
                                                .clickable {
                                                    vm.setShowMessagesSheet(false)
                                                    vm.playTrack(trk, listOf(trk), startAtMs = (m.trackTimestampSec ?: 0) * 1000L)
                                                    vm.setShowNowPlaying(true)
                                                }.padding(7.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            ArtworkImage(trk.artworkUrl, trk.title, Modifier.size(40.dp))
                                            Spacer(Modifier.width(8.dp))
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(trk.title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 12.5.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(trk.artist, color = SpotifySubtle, fontSize = 11.sp, maxLines = 1)
                                            }
                                            Icon(Icons.Rounded.PlayArrow, null, tint = SpotifyGreen, modifier = Modifier.size(22.dp))
                                        }
                                    }
                                    if (!m.sharedPlaylistCode.isNullOrBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        OutlinedButton(
                                            onClick = { vm.setShowMessagesSheet(false); vm.openSonifyCodeOrLink(m.sharedPlaylistCode) },
                                            shape = RoundedCornerShape(50),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Icon(Icons.Rounded.QueueMusic, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp))
                                            Spacer(Modifier.width(6.dp))
                                            Text(m.sharedPlaylistName ?: str.t("פתח פלייליסט משותף", "Open Shared Playlist"), color = SpotifyWhite, fontSize = 12.sp)
                                        }
                                    }
                                    if (!m.sharedJamCode.isNullOrBlank()) {
                                        Spacer(Modifier.height(6.dp))
                                        Button(
                                            onClick = { vm.setShowMessagesSheet(false); vm.joinJamByCode(m.sharedJamCode) },
                                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                                            shape = RoundedCornerShape(50),
                                            modifier = Modifier.fillMaxWidth()
                                        ) { Text("${str.t("הצטרף ל-Jam", "Join Jam")} • ${m.sharedJamCode}", fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(10.dp))

            // Quick Share Chips inside Chat (Current Song / Playlist / Jam Invite)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                state.currentTrack?.let { cur ->
                    item {
                        AssistChip(
                            onClick = {
                                vm.sendMessage(
                                    text = str.t("שמע מה אני שומע עכשיו!", "Listen to what's playing!"),
                                    sharedTrack = cur,
                                    timestampSec = (state.positionMs / 1000L).toInt(),
                                    friendThread = activeFriendName
                                )
                            },
                            leadingIcon = { Icon(Icons.Rounded.MusicNote, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp)) },
                            label = { Text(str.t("שלח שיר נוכחי", "Send Now Playing"), fontSize = 11.5.sp) }
                        )
                    }
                }
                if (state.playlists.isNotEmpty()) {
                    item {
                        AssistChip(
                            onClick = { showSharePlaylistPicker = !showSharePlaylistPicker },
                            leadingIcon = { Icon(Icons.Rounded.QueueMusic, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp)) },
                            label = { Text(str.t("שלח פלייליסט", "Send Playlist"), fontSize = 11.5.sp) }
                        )
                    }
                }
                item {
                    AssistChip(
                        onClick = { vm.inviteFriendToJam(activeFriendName) },
                        leadingIcon = { Icon(Icons.Rounded.Groups, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp)) },
                        label = { Text(str.t("הזמן ל-Jam", "Invite to Jam"), fontSize = 11.5.sp) }
                    )
                }
                if (threadMessages.isNotEmpty()) {
                    item {
                        AssistChip(
                            onClick = { vm.clearChatThread(activeFriendName) },
                            label = { Text(str.t("נקה שיחה", "Clear"), fontSize = 11.5.sp, color = Color(0xFFFF8A80)) }
                        )
                    }
                }
            }

            if (showSharePlaylistPicker) {
                Spacer(Modifier.height(6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.playlists.take(10), key = { "shpl_${it.id}" }) { pl ->
                        FilterChip(
                            selected = false,
                            onClick = {
                                val code = vm.buildSharedPlaylistCode(pl)
                                vm.sendMessage(
                                    text = str.t("שיתפתי איתך את הפלייליסט \"${pl.name}\"!", "Shared playlist \"${pl.name}\"!"),
                                    friendThread = activeFriendName,
                                    sharedPlaylistName = pl.name,
                                    sharedPlaylistCode = code
                                )
                                showSharePlaylistPicker = false
                            },
                            label = { Text(pl.name.take(20), fontSize = 11.5.sp) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Input Row
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = msgInput,
                    onValueChange = { msgInput = it },
                    placeholder = { Text(str.t("כתוב הודעה...", "Write a message...")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = {
                        if (msgInput.isNotBlank()) {
                            vm.sendMessage(text = msgInput, friendThread = activeFriendName)
                            msgInput = ""
                        }
                    },
                    modifier = Modifier.size(46.dp).clip(CircleShape).background(SpotifyGreen)
                ) { Icon(Icons.Rounded.Send, "Send", tint = Color.Black) }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

// =====================================================================
// 4. COMPLETE PLAYLIST IMPORT SHEET (YouTube + Spotify + Sonify + Plain Text!)
// =====================================================================
@Composable
fun ImportPlaylistSheet(vm: AppViewModel, str: AppStrings) {
    val clipboard = LocalClipboardManager.current
    var nameInput by remember { mutableStateOf("") }
    var rawInput by remember { mutableStateOf("") }

    ModalBottomSheet(onDismissRequest = { vm.setShowImportSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).imePadding()) {
            Text(str.importPlaylist, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(6.dp))
            Text(
                str.t(
                    "תומך בקישורי פלייליסט YouTube / YouTube Music, קישורי Spotify (פלייליסט/אלבום/שיר), קישורי Sonify או רשימת שמות שירים (שיר בכל שורה, עד 60).",
                    "Supports YouTube / YT Music playlists, Spotify links (playlist/album/track), Sonify share links, or plain-text song lists (up to 60 lines)."
                ),
                color = SpotifySubtle, fontSize = 12.5.sp
            )
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                value = nameInput,
                onValueChange = { nameInput = it },
                label = { Text(str.t("שם הפלייליסט (אופציונלי — זוהה אוטומטית בקישורי Spotify)", "Playlist name (optional — auto-detected for Spotify)")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = rawInput,
                onValueChange = { rawInput = it },
                label = { Text(str.t("קישור או רשימת שירים", "Link or track list")) },
                minLines = 4,
                maxLines = 8,
                modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedButton(
                    onClick = { clipboard.getText()?.text?.let { rawInput = it } },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(Icons.Rounded.ContentPaste, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(str.t("הדבק מהלוח", "Paste"), color = SpotifyWhite, fontWeight = FontWeight.Bold)
                }
                Button(
                    onClick = { vm.importPlaylist(nameInput, rawInput) },
                    enabled = rawInput.isNotBlank(),
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.weight(1.3f)
                ) { Text(str.t("ייבא עכשיו", "Import Now"), fontWeight = FontWeight.ExtraBold) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

// =====================================================================
// 5. RECOVER DELETED PLAYLISTS SHEET
// =====================================================================
@Composable
fun RecoverPlaylistsSheet(state: UiState, vm: AppViewModel) {
    val str = state.strings
    ModalBottomSheet(onDismissRequest = { vm.setShowRecoverSheet(false) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(modifier = Modifier.fillMaxWidth().padding(20.dp)) {
            Text(str.t("שחזור פלייליסטים שנמחקו", "Recover Deleted Playlists"), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(10.dp))
            if (state.deletedPlaylists.isEmpty()) {
                Text(str.t("אין פלייליסטים שנמחקו.", "No deleted playlists to recover."), color = SpotifySubtle)
            } else {
                state.deletedPlaylists.forEach { rec ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        EnhancedPlaylistCoverArt(rec.playlist, state.settings.aiUniqueCovers, Modifier.size(48.dp))
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(rec.playlist.name, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(rec.playlist.countLabel(str), color = SpotifySubtle, fontSize = 12.sp)
                        }
                        Button(
                            onClick = { vm.recoverDeletedPlaylist(rec) },
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                            shape = RoundedCornerShape(50)
                        ) { Text(str.t("שחזר", "Restore"), fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                    }
                }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

// =====================================================================
// 6. REAL QR CODE GENERATOR & SCANNER SHEET
// =====================================================================
@Composable
fun VisualCodeSheet(title: String, codeData: String, vm: AppViewModel, str: AppStrings, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var manualInput by remember { mutableStateOf("") }

    val qrBitmap = remember(codeData) {
        if (codeData.isBlank()) null
        else runCatching {
            val size = 512
            val matrix = MultiFormatWriter().encode(codeData, BarcodeFormat.QR_CODE, size, size)
            Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888).apply {
                for (x in 0 until size) {
                    for (y in 0 until size) {
                        setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
                    }
                }
            }
        }.getOrNull()
    }

    val scanLauncher = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (!contents.isNullOrBlank()) {
            vm.openSonifyCodeOrLink(contents)
        }
    }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(14.dp))

            if (qrBitmap != null) {
                Box(modifier = Modifier.size(220.dp).clip(RoundedCornerShape(16.dp)).background(Color.White).padding(12.dp)) {
                    Image(bitmap = qrBitmap.asImageBitmap(), contentDescription = "QR Code", modifier = Modifier.fillMaxSize())
                }
                Spacer(Modifier.height(10.dp))
                Text(codeData, color = SpotifySubtle, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = { clipboard.setText(AnnotatedString(codeData)) }, shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) {
                        Icon(Icons.Rounded.ContentCopy, null, tint = SpotifyWhite, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(str.t("העתק קישור", "Copy Link"), color = SpotifyWhite, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"; putExtra(Intent.EXTRA_TEXT, "$title\n$codeData")
                            }, "Share"))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                        shape = RoundedCornerShape(50),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Share, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(5.dp))
                        Text(str.t("שתף קישור", "Share Link"), fontWeight = FontWeight.Bold, fontSize = 12.sp)
                    }
                }
                HorizontalDivider(color = SpotifyCard, modifier = Modifier.padding(vertical = 16.dp))
            }

            Button(
                onClick = {
                    val opts = ScanOptions().apply {
                        setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                        setPrompt(str.t("כוון את המצלמה לקוד QR של Sonify", "Point camera at a Sonify QR code"))
                        setBeepEnabled(false)
                        setOrientationLocked(false)
                    }
                    scanLauncher.launch(opts)
                },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyCard, contentColor = SpotifyWhite),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Rounded.QrCodeScanner, null, tint = SpotifyGreen, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Text(str.t("סרוק קוד QR במצלמה", "Scan QR with Camera"), fontWeight = FontWeight.Bold)
            }

            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = manualInput,
                    onValueChange = { manualInput = it },
                    placeholder = { Text(str.t("או הדבק קישור Sonify / YouTube / Spotify...", "Or paste Sonify / YouTube / Spotify link...")) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { if (manualInput.isNotBlank()) vm.handleIncomingDeepLink(manualInput) },
                    colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)
                ) { Text(str.t("פתח", "Open"), fontWeight = FontWeight.Bold) }
            }
            Spacer(Modifier.height(18.dp))
        }
    }
}

// =====================================================================
// 7. UPGRADED COVER ART STUDIO (Artist-Only Portrait, Collage, Minimal, Auto!)
// =====================================================================
@Composable
fun CoverArtCreatorSheet(playlist: Playlist, vm: AppViewModel, str: AppStrings) {
    var selectedMode by remember(playlist.id) { mutableStateOf(playlist.coverMode.ifBlank { "AUTO" }) }
    var bgHex by remember(playlist.id) { mutableLongStateOf(playlist.customCoverBgHex ?: 0xFF1DB954L) }
    var customTitle by remember(playlist.id) { mutableStateOf(playlist.customCoverText) }
    var sticker by remember(playlist.id) { mutableStateOf(playlist.customCoverSticker) }
    var artistQuery by remember(playlist.id) { mutableStateOf(playlist.tracks.firstOrNull()?.artist.orEmpty()) }

    val previewPlaylist = remember(playlist, selectedMode, bgHex, customTitle, sticker) {
        playlist.copy(
            coverMode = selectedMode,
            customCoverBgHex = bgHex,
            customCoverText = customTitle,
            customCoverSticker = sticker
        )
    }

    val palette = listOf(0xFF1DB954L, 0xFF503750L, 0xFF1E3264L, 0xFFE8115BL, 0xFFBC5900L, 0xFF006450L, 0xFF8D67ABL, 0xFF282828L)
    val stickers = listOf("", "🔥", "⚡", "🎧", "💜", "🌙", "🎸", "🏋️", "🚗", "✨")
    val modes = listOf(
        "ARTIST_ONLY" to str.t("תמונת אמן בלבד", "Artist Photo Only"),
        "COLLAGE" to str.t("קולאז' 4 שירים", "4-Song Collage"),
        "MINIMAL" to str.t("מינימליסטי מעוצב", "Minimalist"),
        "AUTO" to str.t("אוטומטי AI", "Auto AI")
    )

    ModalBottomSheet(onDismissRequest = { vm.setEditingCoverPlaylist(null) }, containerColor = SpotifyDarkSurface, contentColor = SpotifyWhite) {
        Column(
            modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp).imePadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(str.t("סטודיו עטיפות פלייליסט", "Playlist Cover Studio"), fontSize = 19.sp, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.height(14.dp))

            EnhancedPlaylistCoverArt(previewPlaylist, useAiCovers = true, modifier = Modifier.size(170.dp))

            Spacer(Modifier.height(14.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(modes) { (key, lbl) ->
                    FilterChip(selected = selectedMode == key, onClick = { selectedMode = key }, label = { Text(lbl, fontSize = 12.sp) })
                }
            }

            if (selectedMode == "ARTIST_ONLY") {
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = artistQuery,
                    onValueChange = { artistQuery = it },
                    label = { Text(str.t("שם האמן להצגת תמונת הפרופיל האמיתית שלו", "Artist name for real portrait cover")) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = customTitle,
                onValueChange = { customTitle = it },
                label = { Text(str.t("טקסט על העטיפה (אופציונלי)", "Cover overlay text (optional)")) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(12.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                items(palette) { hex ->
                    Box(
                        modifier = Modifier.size(34.dp).clip(CircleShape).background(Color(hex))
                            .then(if (bgHex == hex) Modifier.border(2.5.dp, SpotifyWhite, CircleShape) else Modifier)
                            .clickable { bgHex = hex }
                    )
                }
            }

            if (selectedMode == "MINIMAL" || selectedMode == "AUTO") {
                Spacer(Modifier.height(10.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(stickers) { st ->
                        val sel = sticker == st
                        Box(
                            modifier = Modifier.size(38.dp).clip(CircleShape).background(if (sel) SpotifyGreen else SpotifyCard)
                                .clickable { sticker = st },
                            contentAlignment = Alignment.Center
                        ) { Text(if (st.isEmpty()) "—" else st, color = if (sel) Color.Black else SpotifyWhite, fontSize = 17.sp) }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { vm.updatePlaylistCoverAdvanced(playlist.id, bgHex, customTitle, sticker, selectedMode, artistQuery) },
                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                shape = RoundedCornerShape(50),
                modifier = Modifier.fillMaxWidth().height(46.dp)
            ) { Text(str.save, fontWeight = FontWeight.ExtraBold) }
            Spacer(Modifier.height(18.dp))
        }
    }
}
