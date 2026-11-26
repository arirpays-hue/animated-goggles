@file:kotlin.OptIn(ExperimentalMaterial3Api::class)

package com.sonify.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val s = state.settings
    val bandLabels = listOf("31Hz", "62Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")
    val eqPresets = listOf(
        "רגיל" to List(10) { 0 },
        "בס חזק" to listOf(8, 7, 5, 2, 0, 0, 1, 2, 2, 1),
        "פופ" to listOf(-1, 1, 3, 4, 3, 1, 0, 2, 3, 2),
        "רוק" to listOf(5, 4, 2, -1, -2, 0, 2, 4, 5, 6),
        "אלקטרוני" to listOf(7, 6, 3, 0, -2, 1, 3, 4, 5, 6),
        "ווקאלי" to listOf(-3, -2, 0, 3, 5, 6, 4, 2, 0, -1),
        "קלאסי" to listOf(4, 3, 2, 1, 0, 0, 1, 2, 3, 4)
    )
    var tasteArtistInput by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding()
            .verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 10.dp).padding(bottom = 80.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setShowSettings(false) }) { Icon(Icons.Rounded.ArrowForward, contentDescription = "חזרה", tint = SpotifyWhite) }
            Text("הגדרות Sonify", color = SpotifyWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        // 1. UI & Language
        Spacer(Modifier.height(12.dp))
        SectionTitle("ממשק ושפה")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
            AppLanguage.entries.forEach { lang ->
                val sel = s.language == lang
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard)
                        .clickable { vm.updateSettings(s.copy(language = lang)) }.padding(horizontal = 16.dp, vertical = 8.dp)
                ) { Text(lang.label, color = if (sel) Color.Black else SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.sp) }
            }
        }
        ToggleSettingRow("הצג כפתור יצירה (+) בסרגל התחתון", "הסתרה או הצגה של לשונית היצירה", s.showCreateNavButton) { vm.updateSettings(s.copy(showCreateNavButton = it)) }
        ToggleSettingRow("הצג רצועות לא זמינות באפור", "שירים מוסתרים או לא זמינים באופליין", s.showUnplayableSongs) { vm.updateSettings(s.copy(showUnplayableSongs = it)) }
        ToggleSettingRow("מצב רכב (כפתורי ניגון מוגדלים)", "נוחות שליטה מירבית בנסיעה", s.carModeOptimized) { vm.updateSettings(s.copy(carModeOptimized = it)) }

        // 2. Streaming Source & Engine
        Spacer(Modifier.height(16.dp))
        SectionTitle("מקור ההזרמה ומנוע הניגון")
        StreamSource.entries.forEach { src ->
            val selected = s.streamSource == src
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Color(0xFF1F3B33) else SpotifyDarkSurface)
                    .clickable { vm.updateSettings(s.copy(streamSource = src)) }.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = { vm.updateSettings(s.copy(streamSource = src)) }, colors = RadioButtonDefaults.colors(selectedColor = SpotifyGreen))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(src.label, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(src.subtitle, color = SpotifySubtle, fontSize = 11.sp)
                }
            }
        }

        if (s.streamSource == StreamSource.YOUTUBE) {
            Spacer(Modifier.height(8.dp))
            YouTubeEngineMode.entries.forEach { mode ->
                val selected = s.ytEngineMode == mode
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp).clip(RoundedCornerShape(8.dp))
                        .background(if (selected) Color(0xFF1F3B33) else SpotifyDarkSurface)
                        .clickable { vm.updateSettings(s.copy(ytEngineMode = mode)) }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = { vm.updateSettings(s.copy(ytEngineMode = mode)) }, colors = RadioButtonDefaults.colors(selectedColor = SpotifyGreen))
                    Column {
                        Text(mode.label, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Text(mode.subtitle, color = SpotifySubtle, fontSize = 11.sp)
                    }
                }
            }
        }

        if (s.streamSource == StreamSource.OPENSUBSONIC) {
            Spacer(Modifier.height(8.dp))
            Card(colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = s.subsonicUrl, onValueChange = { vm.updateSettings(s.copy(subsonicUrl = it)) }, label = { Text("כתובת שרת OpenSubsonic") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = s.subsonicUser, onValueChange = { vm.updateSettings(s.copy(subsonicUser = it)) }, label = { Text("שם משתמש") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = s.subsonicPass, onValueChange = { vm.updateSettings(s.copy(subsonicPass = it)) }, label = { Text("סיסמה") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.testSubsonicServer() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                        Text("בדוק חיבור לשרת Lossless", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (s.streamSource == StreamSource.LOCAL) {
            Spacer(Modifier.height(8.dp))
            Button(onClick = { vm.scanLocalFiles() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                Text("סרוק קובצי FLAC / MP3 במכשיר", fontWeight = FontWeight.Bold)
            }
        }

        // 3. 5-Level Quality Separated by Connection
        Spacer(Modifier.height(16.dp))
        SectionTitle("איכות שמע (5 רמות לפי חיבור)")
        QualitySelectorRow("איכות ב-Wi-Fi", s.wifiQuality) { vm.updateSettings(s.copy(wifiQuality = it)) }
        QualitySelectorRow("איכות בסלולר", s.cellularQuality) { vm.updateSettings(s.copy(cellularQuality = it)) }
        QualitySelectorRow("איכות הורדות אופליין", s.downloadQuality) { vm.updateSettings(s.copy(downloadQuality = it)) }
        ToggleSettingRow("התאמת איכות אוטומטית", "מוריד איכות כשהרשת חלשה למניעת קטיעות", s.autoAdjustQuality) { vm.updateSettings(s.copy(autoAdjustQuality = it)) }

        // 4. Playback & Advanced Audio Processing
        Spacer(Modifier.height(16.dp))
        SectionTitle("ניגון ועיבוד שמע מתקדם")
        ToggleSettingRow("נרמול עוצמת שמע (Volume Normalization)", "השוואת ווליום בין שירים", s.normalizeVolume) { vm.updateSettings(s.copy(normalizeVolume = it)) }
        if (s.normalizeVolume) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                NormalizationLevel.entries.forEach { lvl ->
                    val sel = s.normalizationLevel == lvl
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { vm.updateSettings(s.copy(normalizationLevel = lvl)) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                        Text(lvl.label, color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        ToggleSettingRow("Gapless Playback", "מעבר רציף ללא הפסקת שקט בין רצועות", s.gaplessPlayback) { vm.updateSettings(s.copy(gaplessPlayback = it)) }
        ToggleSettingRow("Automix (מיקס אוטומטי)", "מעברים חלקים בסגנון DJ בין שירים", s.automixEnabled) { vm.updateSettings(s.copy(automixEnabled = it)) }
        ToggleSettingRow("ניגון אוטומטי (Autoplay)", "המשך ניגון שירים דומים בסיום התור", s.autoplayEnabled) { vm.updateSettings(s.copy(autoplayEnabled = it)) }
        ToggleSettingRow("אודיו מונו (Mono Audio)", "איחוד ערוצי שמאל וימין לאוזנייה אחת", s.monoAudio) { vm.updateSettings(s.copy(monoAudio = it)) }
        ToggleSettingRow("Canvas (תנועת רקע חיה)", "אפקט רקע חי במסך הנגן", s.canvasEnabled) { vm.updateSettings(s.copy(canvasEnabled = it)) }
        ToggleSettingRow("אודיו בלבד בפודקאסטים", "חוסך סוללה ודאטה בווידאו-פודקאסטים", s.audioOnlyPodcasts) { vm.updateSettings(s.copy(audioOnlyPodcasts = it)) }
        ToggleSettingRow("פתיחת קליפ אוטומטית ב-YouTube", "עובר מיד לקליפ השיר כשזמין", s.autoVideoOnWifi) { vm.updateSettings(s.copy(autoVideoOnWifi = it)) }

        Spacer(Modifier.height(6.dp))
        Text("Crossfade (עמעום בין שירים): ${s.crossfadeSeconds} שניות", color = SpotifyWhite, fontSize = 13.sp)
        Slider(value = s.crossfadeSeconds.toFloat(), onValueChange = { vm.updateSettings(s.copy(crossfadeSeconds = it.toInt())) }, valueRange = 0f..12f, steps = 11, colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen))

        Text("איזון שמע (שמאל / ימין): ${if (s.audioBalance == 0f) "מרכז" else "${(s.audioBalance * 100).roundToInt()}%"}", color = SpotifyWhite, fontSize = 13.sp)
        Slider(value = s.audioBalance, onValueChange = { vm.updateSettings(s.copy(audioBalance = (it * 10).roundToInt() / 10f)) }, valueRange = -1f..1f, colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen))

        // 5. 10-Band Parametric Equalizer + Dynamic Limiter
        Spacer(Modifier.height(14.dp))
        SectionTitle("אקולייזר 10 ערוצים + לימיטר דינמי")
        ToggleSettingRow("הפעל אקולייזר", "שליטה ב-10 תדרים עם הגנת עיוותים", s.eqEnabled) { vm.updateSettings(s.copy(eqEnabled = it)) }
        if (s.eqEnabled) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                itemsIndexed(eqPresets) { idx, (name, bands) ->
                    val sel = s.eqPresetIndex == idx
                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { vm.updateSettings(s.copy(eqPresetIndex = idx, eqBands = bands)) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                        Text(name, color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    s.eqBands.forEachIndexed { idx, value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(bandLabels.getOrElse(idx) { "B$idx" }, color = SpotifySubtle, fontSize = 11.sp, modifier = Modifier.width(46.dp))
                            Slider(
                                value = value.toFloat(),
                                onValueChange = { v ->
                                    val updated = s.eqBands.toMutableList()
                                    updated[idx] = v.toInt()
                                    vm.updateSettings(s.copy(eqBands = updated, eqPresetIndex = -1))
                                },
                                valueRange = -12f..12f, modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen)
                            )
                            Text("${if (value > 0) "+" else ""}$value", color = SpotifyWhite, fontSize = 11.sp, modifier = Modifier.width(28.dp))
                        }
                    }
                }
            }
        }

        // 6. Privacy, Data, Offline & Storage
        Spacer(Modifier.height(16.dp))
        SectionTitle("פרטיות, נתונים ואחסון")
        ToggleSettingRow("מצב אופליין ידני (Offline Mode)", "מציג ומנגן רק קבצים שהורדו למכשיר", s.offlineMode) { vm.updateSettings(s.copy(offlineMode = it)) }
        ToggleSettingRow("מצב חסכוני בנתונים (Data Saver)", "מוריד איכות שמע ומכבה Canvas", s.dataSaver) { vm.updateSettings(s.copy(dataSaver = it)) }
        ToggleSettingRow("הורדה דרך רשת סלולרית", "מאפשר הורדות גם ללא Wi-Fi", s.downloadUsingCellular) { vm.updateSettings(s.copy(downloadUsingCellular = it)) }
        ToggleSettingRow("אפשר תוכן בוטה (Explicit)", "השמעה או חסימה של שירים מסומנים E", s.allowExplicit) { vm.updateSettings(s.copy(allowExplicit = it)) }
        ToggleSettingRow("סשן פרטי (Private Session)", "האזנה שלא משפיעה על המלצות וסטטיסטיקות", s.privateSession) { vm.updateSettings(s.copy(privateSession = it)) }
        ToggleSettingRow("הצג פעילות האזנה לחברים", "שיתוף השיר המתנגן כעת", s.shareListeningActivity) { vm.updateSettings(s.copy(shareListeningActivity = it)) }
        ToggleSettingRow("הצג אמנים אחרונים בפרופיל", "חשיפת אמנים מושמעים לאחרונה", s.showRecentlyPlayedArtists) { vm.updateSettings(s.copy(showRecentlyPlayedArtists = it)) }

        // Taste Profile Editor
        Spacer(Modifier.height(10.dp))
        Text("עריכת פרופיל טעם (החרגת אמנים/ז'אנרים מהמלצות):", color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            OutlinedTextField(value = tasteArtistInput, onValueChange = { tasteArtistInput = it }, placeholder = { Text("שם אמן להחרגה...") }, singleLine = true, modifier = Modifier.weight(1f))
            Button(onClick = { if (tasteArtistInput.isNotBlank()) { vm.toggleExcludeArtistFromTaste(tasteArtistInput.trim()); tasteArtistInput = "" } }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyCard)) { Text("החרג") }
        }
        if (state.excludedArtistsFromTaste.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
                items(state.excludedArtistsFromTaste.toList()) { a ->
                    InputChip(selected = true, onClick = { vm.toggleExcludeArtistFromTaste(a) }, label = { Text("$a ✕") })
                }
            }
        }

        // Notifications
        Spacer(Modifier.height(14.dp))
        SectionTitle("התראות")
        ToggleSettingRow("שחרורים חדשים", "עדכונים מאמנים אהובים", s.notifyNewReleases) { vm.updateSettings(s.copy(notifyNewReleases = it)) }
        ToggleSettingRow("הופעות קרובות", "התראות על הופעות ומכירה מוקדמת", s.notifyConcerts) { vm.updateSettings(s.copy(notifyConcerts = it)) }
        ToggleSettingRow("פרקי פודקאסטים חדשים", "התראות על פרקים חדשים", s.notifyPodcasts) { vm.updateSettings(s.copy(notifyPodcasts = it)) }

        // Storage, Recovery & AI Key
        Spacer(Modifier.height(16.dp))
        SectionTitle("אחסון, שחזור וייבוא")
        val cacheMb = remember(state.statusMessage) { "%.1f".format(vm.repo.getCacheSizeMb()) }
        val offlineMb = remember(state.offlineTrackIds) { "%.1f".format(vm.repo.getOfflineSizeMb()) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.clearAppCache() }, modifier = Modifier.weight(1f)) { Text("נקה מטמון ($cacheMb MB)", color = SpotifyWhite, fontSize = 12.sp) }
            OutlinedButton(onClick = { vm.removeAllDownloads() }, modifier = Modifier.weight(1f)) { Text("מחק הורדות ($offlineMb MB)", color = Color(0xFFFF6B6B), fontSize = 12.sp) }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { vm.setShowRecoverSheet(true) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.Restore, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("שחזור פלייליסטים", color = SpotifyWhite, fontSize = 12.sp)
            }
            OutlinedButton(onClick = { vm.setShowImportSheet(true) }, modifier = Modifier.weight(1f)) {
                Icon(Icons.Rounded.FileDownload, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp)); Text("ייבוא פלייליסט", color = SpotifyWhite, fontSize = 12.sp)
            }
        }

        Spacer(Modifier.height(16.dp))
        SectionTitle("AI Playlist מתקדם (אופציונלי)")
        OutlinedTextField(
            value = s.geminiApiKey,
            onValueChange = { vm.updateSettings(s.copy(geminiApiKey = it.trim())) },
            label = { Text("Gemini API Key (אופציונלי)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun QualitySelectorRow(title: String, selected: AudioQuality, onSelect: (AudioQuality) -> Unit) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(title, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AudioQuality.entries.size) { idx ->
                val q = AudioQuality.entries[idx]
                val isSel = q == selected
                Box(
                    Modifier.clip(RoundedCornerShape(50)).background(if (isSel) SpotifyGreen else SpotifyCard)
                        .clickable { onSelect(q) }.padding(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(q.label.substringBefore(" ("), color = if (isSel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun ToggleSettingRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SpotifyWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = SpotifySubtle, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = SpotifyGreen))
    }
}
