@file:kotlin.OptIn(ExperimentalMaterial3Api::class)

package com.sonify.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

private data class SettingsMenuEntry(
    val section: SettingsSection,
    val icon: ImageVector,
    val titleHe: String,
    val titleEn: String,
    val subHe: String,
    val subEn: String,
    val keywords: String
)

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val s = state.settings
    val isEn = s.language == AppLanguage.ENGLISH
    val section = state.settingsSection
    var settingsSearch by remember { mutableStateOf("") }

    val menuEntries = remember(s, state.offlineTrackIds, state.hiddenTrackIds) {
        listOf(
            SettingsMenuEntry(
                SettingsSection.STREAM_QUALITY, Icons.Rounded.HighQuality,
                "מקור הזרמה ואיכות שמע", "Streaming Source & Quality",
                "${s.streamSource.label.substringBefore(" (")} • Wi-Fi: ${s.wifiQuality.shortLabel}",
                "${s.streamSource.label.substringBefore(" (")} • Wi-Fi: ${s.wifiQuality.shortLabel}",
                "youtube lossless flac openSubsonic local wifi cellular quality מקור הזרמה איכות"
            ),
            SettingsMenuEntry(
                SettingsSection.PLAYBACK_AUDIO, Icons.Rounded.Tune,
                "ניגון ועיבוד שמע", "Playback & Audio",
                "Crossfade ${s.crossfadeSeconds}s • ${if (s.monoAudio) "מונו" else "סטריאו"} • ${s.playbackSpeed}x",
                "Crossfade ${s.crossfadeSeconds}s • ${if (s.monoAudio) "Mono" else "Stereo"} • ${s.playbackSpeed}x",
                "crossfade gapless automix mono balance normalize autoplay canvas speed ניגון שמע מונו איזון"
            ),
            SettingsMenuEntry(
                SettingsSection.EQUALIZER, Icons.Rounded.GraphicEq,
                "אקולייזר 10 ערוצים ולימיטר", "10-Band Equalizer & Limiter",
                if (s.eqEnabled) "פעיל • 10 ערוצים + לימיטר דינמי" else "כבוי",
                if (s.eqEnabled) "Enabled • 10 Bands + Limiter" else "Off",
                "eq equalizer bass treble limiter אקולייזר באס טרבל"
            ),
            SettingsMenuEntry(
                SettingsSection.STORAGE_OFFLINE, Icons.Rounded.DownloadForOffline,
                "אחסון, הורדות ומצב אופליין", "Storage, Downloads & Offline",
                "${state.offlineTrackIds.size} שירים באופליין • ${if (s.offlineMode) "מצב אופליין פעיל" else "מקוון"}",
                "${state.offlineTrackIds.size} offline songs • ${if (s.offlineMode) "Offline Mode ON" else "Online"}",
                "offline cache download data saver recover import אופליין מטמון הורדות שחזור ייבוא"
            ),
            SettingsMenuEntry(
                SettingsSection.PRIVACY_TASTE, Icons.Rounded.Security,
                "פרטיות ופרופיל טעם", "Privacy & Taste Profile",
                "${if (s.privateSession) "סשן פרטי פעיל" else "רגיל"} • ${state.hiddenTrackIds.size} שירים מוסתרים",
                "${if (s.privateSession) "Private Session ON" else "Normal"} • ${state.hiddenTrackIds.size} hidden",
                "private explicit taste hidden friends activity פרטיות בוטה טעם מוסתרים"
            ),
            SettingsMenuEntry(
                SettingsSection.UI_LANGUAGE, Icons.Rounded.Palette,
                "ממשק, שפה ומצב רכב", "Appearance, Language & Car",
                "${s.language.label} • ${if (s.carModeOptimized) "מצב רכב פעיל" else "תצוגה רגילה"}",
                "${s.language.label} • ${if (s.carModeOptimized) "Car Mode ON" else "Standard"}",
                "language hebrew english car create button unplayable שפה עברית אנגלית רכב ממשק"
            ),
            SettingsMenuEntry(
                SettingsSection.AI_NOTIFICATIONS, Icons.Rounded.AutoAwesome,
                "בינה מלאכותית והתראות", "AI & Notifications",
                if (s.geminiApiKey.isNotBlank()) "Gemini API מחובר" else "AI מובנה פעיל",
                if (s.geminiApiKey.isNotBlank()) "Gemini API Connected" else "Built-in AI Active",
                "ai gemini playlist notifications concerts releases התראות בינה מלאכותית"
            )
        )
    }

    val headerTitle = when (section) {
        SettingsSection.ROOT -> if (isEn) "Settings" else "הגדרות"
        SettingsSection.STREAM_QUALITY -> if (isEn) "Streaming & Quality" else "מקור הזרמה ואיכות"
        SettingsSection.PLAYBACK_AUDIO -> if (isEn) "Playback & Audio" else "ניגון ועיבוד שמע"
        SettingsSection.EQUALIZER -> if (isEn) "10-Band Equalizer" else "אקולייזר 10 ערוצים"
        SettingsSection.STORAGE_OFFLINE -> if (isEn) "Storage & Offline" else "אחסון ואופליין"
        SettingsSection.PRIVACY_TASTE -> if (isEn) "Privacy & Taste" else "פרטיות ופרופיל טעם"
        SettingsSection.UI_LANGUAGE -> if (isEn) "Appearance & Language" else "ממשק ושפה"
        SettingsSection.AI_NOTIFICATIONS -> if (isEn) "AI & Notifications" else "AI והתראות"
    }

    Column(
        modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding()
    ) {
        // Clean Spotify Top App Bar
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (section == SettingsSection.ROOT) vm.setShowSettings(false)
                else vm.setSettingsSection(SettingsSection.ROOT)
            }) {
                Icon(
                    if (isEn) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward,
                    contentDescription = "חזרה", tint = SpotifyWhite
                )
            }
            Text(headerTitle, color = SpotifyWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 80.dp)
        ) {
            when (section) {
                SettingsSection.ROOT -> {
                    // Search Settings Bar
                    Row(
                        modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp))
                            .background(SpotifyCard).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = SpotifySubtle, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (settingsSearch.isEmpty()) {
                                Text(if (isEn) "Search settings..." else "חפש הגדרה (איכות, אקולייזר, אופליין...)", color = SpotifyMuted, fontSize = 13.sp)
                            }
                            BasicTextField(
                                value = settingsSearch, onValueChange = { settingsSearch = it }, singleLine = true,
                                textStyle = TextStyle(color = SpotifyWhite, fontSize = 14.sp),
                                cursorBrush = SolidColor(SpotifyGreen), modifier = Modifier.fillMaxWidth()
                            )
                        }
                        if (settingsSearch.isNotEmpty()) {
                            IconButton(onClick = { settingsSearch = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Rounded.Close, null, tint = SpotifySubtle, modifier = Modifier.size(16.dp))
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))

                    // Quick Toggles Card (Most Used)
                    Card(colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            ToggleSettingRow(if (isEn) "Offline Mode" else "מצב אופליין ידני", if (isEn) "Play downloaded tracks only" else "השמעת שירים שהורדו בלבד", s.offlineMode) {
                                vm.updateSettings(s.copy(offlineMode = it))
                            }
                            HorizontalDivider(color = SpotifyCard)
                            ToggleSettingRow(if (isEn) "Private Session" else "סשן פרטי", if (isEn) "Listen anonymously" else "האזנה בלי להשפיע על המלצות", s.privateSession) {
                                vm.updateSettings(s.copy(privateSession = it))
                            }
                            HorizontalDivider(color = SpotifyCard)
                            ToggleSettingRow(if (isEn) "Data Saver" else "חסכוני בנתונים", if (isEn) "Lower quality & disable Canvas" else "איכות חסכונית וכיבוי Canvas", s.dataSaver) {
                                vm.updateSettings(s.copy(dataSaver = it))
                            }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    val filtered = menuEntries.filter {
                        settingsSearch.isBlank() ||
                            it.titleHe.contains(settingsSearch, true) ||
                            it.titleEn.contains(settingsSearch, true) ||
                            it.keywords.contains(settingsSearch, true)
                    }

                    filtered.forEach { item ->
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(12.dp))
                                .background(SpotifyDarkSurface).clickable { vm.setSettingsSection(item.section) }
                                .padding(horizontal = 14.dp, vertical = 14.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(SpotifyCard), contentAlignment = Alignment.Center) {
                                Icon(item.icon, null, tint = SpotifyGreen, modifier = Modifier.size(22.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(if (isEn) item.titleEn else item.titleHe, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(if (isEn) item.subEn else item.subHe, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1)
                            }
                            Icon(if (isEn) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft, null, tint = SpotifyMuted)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Sonify v6.0.0 • Media3 + HD Engine", color = SpotifyMuted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                SettingsSection.STREAM_QUALITY -> {
                    SectionCard("מקור ההזרמה הראשי") {
                        StreamSource.entries.forEach { src ->
                            val selected = s.streamSource == src
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) Color(0xFF1F3B33) else SpotifyCard)
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
                    }

                    if (s.streamSource == StreamSource.YOUTUBE) {
                        Spacer(Modifier.height(12.dp))
                        SectionCard("מנוע ניגון YouTube") {
                            YouTubeEngineMode.entries.forEach { mode ->
                                val selected = s.ytEngineMode == mode
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(8.dp))
                                        .background(if (selected) Color(0xFF1F3B33) else SpotifyCard)
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
                    }

                    if (s.streamSource == StreamSource.OPENSUBSONIC) {
                        Spacer(Modifier.height(12.dp))
                        SectionCard("חיבור שרת OpenSubsonic / Navidrome (FLAC)") {
                            OutlinedTextField(value = s.subsonicUrl, onValueChange = { vm.updateSettings(s.copy(subsonicUrl = it)) }, label = { Text("כתובת שרת (https://...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = s.subsonicUser, onValueChange = { vm.updateSettings(s.copy(subsonicUser = it)) }, label = { Text("שם משתמש") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = s.subsonicPass, onValueChange = { vm.updateSettings(s.copy(subsonicPass = it)) }, label = { Text("סיסמה") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { vm.testSubsonicServer() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                                Text("בדוק חיבור לשרת", fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (s.streamSource == StreamSource.LOCAL) {
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { vm.scanLocalFiles() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                            Text("סרוק קובצי FLAC / MP3 במכשיר", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard("איכות שמע לפי סוג חיבור (5 רמות)") {
                        QualitySelectorRow("איכות ב-Wi-Fi", s.wifiQuality) { vm.updateSettings(s.copy(wifiQuality = it)) }
                        Spacer(Modifier.height(8.dp))
                        QualitySelectorRow("איכות בסלולר", s.cellularQuality) { vm.updateSettings(s.copy(cellularQuality = it)) }
                        Spacer(Modifier.height(8.dp))
                        QualitySelectorRow("איכות הורדות אופליין", s.downloadQuality) { vm.updateSettings(s.copy(downloadQuality = it)) }
                        HorizontalDivider(color = SpotifyCard, modifier = Modifier.padding(vertical = 8.dp))
                        ToggleSettingRow("התאמת איכות אוטומטית", "מוריד איכות כשהאינטרנט חלש למניעת קטיעות", s.autoAdjustQuality) { vm.updateSettings(s.copy(autoAdjustQuality = it)) }
                    }
                }

                SettingsSection.PLAYBACK_AUDIO -> {
                    SectionCard("עיבוד שמע ומעברים") {
                        ToggleSettingRow("נרמול עוצמת שמע (Volume Normalization)", "השוואת ווליום אחידה בין שירים", s.normalizeVolume) { vm.updateSettings(s.copy(normalizeVolume = it)) }
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
                        HorizontalDivider(color = SpotifyCard)
                        ToggleSettingRow("Gapless Playback", "מעבר ללא שבריר שנייה של שקט בין רצועות", s.gaplessPlayback) { vm.updateSettings(s.copy(gaplessPlayback = it)) }
                        ToggleSettingRow("Automix (מיקס אוטומטי)", "מעברים חלקים בסגנון DJ", s.automixEnabled) { vm.updateSettings(s.copy(automixEnabled = it)) }
                        ToggleSettingRow("ניגון אוטומטי (Autoplay)", "ממשיך לשירים דומים כשהתור נגמר", s.autoplayEnabled) { vm.updateSettings(s.copy(autoplayEnabled = it)) }
                        ToggleSettingRow("אודיו מונו (Mono Audio)", "איחוד ערוצי שמאל וימין לאוזנייה אחת", s.monoAudio) { vm.updateSettings(s.copy(monoAudio = it)) }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard("Crossfade ואיזון ערוצים") {
                        Text("Crossfade: ${s.crossfadeSeconds} שניות", color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Slider(value = s.crossfadeSeconds.toFloat(), onValueChange = { vm.updateSettings(s.copy(crossfadeSeconds = it.toInt())) }, valueRange = 0f..12f, steps = 11, colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen, inactiveTrackColor = SpotifyCard))
                        }
                        Spacer(Modifier.height(6.dp))
                        val balLabel = when {
                            s.audioBalance == 0f -> "מרכז"
                            s.audioBalance < 0f -> "שמאל ${(-s.audioBalance * 100).roundToInt()}%"
                            else -> "ימין ${(s.audioBalance * 100).roundToInt()}%"
                        }
                        Text("איזון שמע (L / R): $balLabel", color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Slider(value = s.audioBalance, onValueChange = { vm.updateSettings(s.copy(audioBalance = (it * 10).roundToInt() / 10f)) }, valueRange = -1f..1f, colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen, inactiveTrackColor = SpotifyCard))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("L", color = SpotifySubtle, fontSize = 11.sp); Text("C", color = SpotifyGreen, fontSize = 11.sp); Text("R", color = SpotifySubtle, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard("וידאו, Canvas ופודקאסטים") {
                        ToggleSettingRow("Canvas (תנועת רקע חיה)", "אפקט תנועה עדין במסך הנגן", s.canvasEnabled) { vm.updateSettings(s.copy(canvasEnabled = it)) }
                        ToggleSettingRow("אודיו בלבד בפודקאסטים", "חוסך סוללה ודאטה בווידאו-פודקאסטים", s.audioOnlyPodcasts) { vm.updateSettings(s.copy(audioOnlyPodcasts = it)) }
                        ToggleSettingRow("פתיחת קליפ אוטומטית ב-YouTube", "עובר ישירות לקליפ השיר כשזמין", s.autoVideoOnWifi) { vm.updateSettings(s.copy(autoVideoOnWifi = it)) }
                    }
                }

                SettingsSection.EQUALIZER -> {
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
                    SectionCard("אקולייזר פרמטרי (10 ערוצים) + לימיטר") {
                        ToggleSettingRow("הפעל אקולייזר", "פועל על זרמי ExoPlayer, קובצי FLAC והורדות אופליין", s.eqEnabled) { vm.updateSettings(s.copy(eqEnabled = it)) }
                        if (s.eqEnabled) {
                            Spacer(Modifier.height(6.dp))
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                itemsIndexed(eqPresets) { idx, (name, bands) ->
                                    val sel = s.eqPresetIndex == idx
                                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { vm.updateSettings(s.copy(eqPresetIndex = idx, eqBands = bands)) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                                        Text(name, color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                            Spacer(Modifier.height(10.dp))
                            CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                                s.eqBands.forEachIndexed { idx, value ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(bandLabels.getOrElse(idx) { "B$idx" }, color = SpotifySubtle, fontSize = 11.sp, modifier = Modifier.width(44.dp))
                                        Slider(
                                            value = value.toFloat(),
                                            onValueChange = { v ->
                                                val updated = s.eqBands.toMutableList(); updated[idx] = v.toInt()
                                                vm.updateSettings(s.copy(eqBands = updated, eqPresetIndex = -1))
                                            },
                                            valueRange = -12f..12f, modifier = Modifier.weight(1f),
                                            colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen, inactiveTrackColor = SpotifyCard)
                                        )
                                        Text("${if (value > 0) "+" else ""}$value", color = SpotifyWhite, fontSize = 11.sp, modifier = Modifier.width(30.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                SettingsSection.STORAGE_OFFLINE -> {
                    val cacheMb = remember(state.storageRefreshKey) { "%.1f".format(vm.repo.getCacheSizeMb()) }
                    val offlineMb = remember(state.storageRefreshKey, state.offlineTrackIds) { "%.1f".format(vm.repo.getOfflineSizeMb()) }

                    SectionCard("אופליין ונתונים") {
                        ToggleSettingRow("מצב אופליין ידני", "מנתק חיפוש ומנגן רק קבצים שהורדו", s.offlineMode) { vm.updateSettings(s.copy(offlineMode = it)) }
                        ToggleSettingRow("מצב חסכוני בנתונים (Data Saver)", "מוריד איכות שמע ומכבה Canvas", s.dataSaver) { vm.updateSettings(s.copy(dataSaver = it)) }
                        ToggleSettingRow("הורדה דרך רשת סלולרית", "מאפשר הורדות גם ללא Wi-Fi", s.downloadUsingCellular) { vm.updateSettings(s.copy(downloadUsingCellular = it)) }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard("ניהול אחסון") {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("מטמון זמני (Cache):", color = SpotifySubtle, fontSize = 13.sp)
                            Text("$cacheMb MB", color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("הורדות אופליין (${state.offlineTrackIds.size} שירים):", color = SpotifySubtle, fontSize = 13.sp)
                            Text("$offlineMb MB", color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { vm.clearAppCache() }, modifier = Modifier.weight(1f)) { Text("נקה מטמון", color = SpotifyWhite, fontSize = 12.sp) }
                            OutlinedButton(onClick = { vm.removeAllDownloads() }, modifier = Modifier.weight(1f)) { Text("מחק הורדות", color = Color(0xFFFF6B6B), fontSize = 12.sp) }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard("פלייליסטים — שחזור וייבוא") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { vm.setShowRecoverSheet(true) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyCard, contentColor = SpotifyWhite), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Restore, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("שחזור", fontSize = 13.sp)
                            }
                            Button(onClick = { vm.setShowImportSheet(true) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.FileDownload, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text("ייבוא", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }

                SettingsSection.PRIVACY_TASTE -> {
                    var tasteArtistInput by remember { mutableStateOf("") }
                    SectionCard("פרטיות ותוכן") {
                        ToggleSettingRow("סשן פרטי (Private Session)", "לא שומר היסטוריה ולא משפיע על המלצות", s.privateSession) { vm.updateSettings(s.copy(privateSession = it)) }
                        ToggleSettingRow("אפשר תוכן בוטה (Explicit)", "השמעה או חסימה של שירים מסומנים E", s.allowExplicit) { vm.updateSettings(s.copy(allowExplicit = it)) }
                        ToggleSettingRow("הצג פעילות האזנה לחברים", "שיתוף השיר המתנגן כעת", s.shareListeningActivity) { vm.updateSettings(s.copy(shareListeningActivity = it)) }
                        ToggleSettingRow("הצג אמנים אחרונים בפרופיל", "חשיפת אמנים מושמעים לאחרונה", s.showRecentlyPlayedArtists) { vm.updateSettings(s.copy(showRecentlyPlayedArtists = it)) }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard("פרופיל טעם ושירים מוסתרים") {
                        Text("החרגת אמן או ז'אנר מהמלצות:", color = SpotifySubtle, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(
                                value = tasteArtistInput, onValueChange = { tasteArtistInput = it },
                                placeholder = { Text("שם אמן...") }, singleLine = true, modifier = Modifier.weight(1f)
                            )
                            Button(
                                onClick = { if (tasteArtistInput.isNotBlank()) { vm.toggleExcludeArtistFromTaste(tasteArtistInput.trim()); tasteArtistInput = "" } },
                                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)
                            ) { Text("החרג", fontWeight = FontWeight.Bold) }
                        }
                        if (state.excludedArtistsFromTaste.isNotEmpty()) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                                items(state.excludedArtistsFromTaste.toList()) { a ->
                                    InputChip(selected = true, onClick = { vm.toggleExcludeArtistFromTaste(a) }, label = { Text("$a ✕") })
                                }
                            }
                        }
                        if (state.hiddenTrackIds.isNotEmpty()) {
                            Spacer(Modifier.height(12.dp))
                            OutlinedButton(onClick = { vm.clearAllHiddenTracks() }, modifier = Modifier.fillMaxWidth()) {
                                Text("שחזר ${state.hiddenTrackIds.size} שירים מוסתרים", color = SpotifyWhite)
                            }
                        }
                    }
                }

                SettingsSection.UI_LANGUAGE -> {
                    SectionCard("שפת האפליקציה") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AppLanguage.entries.forEach { lang ->
                                val sel = s.language == lang
                                Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { vm.updateSettings(s.copy(language = lang)) }.padding(horizontal = 18.dp, vertical = 9.dp)) {
                                    Text(lang.label, color = if (sel) Color.Black else SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    SectionCard("תצוגה וניווט") {
                        ToggleSettingRow("הצג כפתור יצירה (+) בסרגל התחתון", "הסתרה או הצגה של לשונית היצירה", s.showCreateNavButton) { vm.updateSettings(s.copy(showCreateNavButton = it)) }
                        ToggleSettingRow("הצג רצועות לא זמינות באפור", "הצגת שירים מוסתרים או לא זמינים באופליין", s.showUnplayableSongs) { vm.updateSettings(s.copy(showUnplayableSongs = it)) }
                        ToggleSettingRow("מצב רכב (כפתורי ניגון מוגדלים)", "כפתורי שליטה גדולים ונוחים לנסיעה", s.carModeOptimized) { vm.updateSettings(s.copy(carModeOptimized = it)) }
                    }
                }

                SettingsSection.AI_NOTIFICATIONS -> {
                    SectionCard("AI Playlist מתקדם") {
                        Text("בניית פלייליסטים עם AI עובדת אוטומטית גם בלי מפתח. הזנת Gemini API Key מאפשרת הרכבת שירים מותאמת עוד יותר.", color = SpotifySubtle, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = s.geminiApiKey, onValueChange = { vm.updateSettings(s.copy(geminiApiKey = it.trim())) },
                            label = { Text("Gemini API Key (אופציונלי)") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SectionCard("התראות") {
                        ToggleSettingRow("שחרורים חדשים", "עדכונים מאמנים אהובים", s.notifyNewReleases) { vm.updateSettings(s.copy(notifyNewReleases = it)) }
                        ToggleSettingRow("הופעות קרובות", "התראות על הופעות ומכירה מוקדמת", s.notifyConcerts) { vm.updateSettings(s.copy(notifyConcerts = it)) }
                        ToggleSettingRow("פרקי פודקאסטים חדשים", "התראות על פרקים חדשים", s.notifyPodcasts) { vm.updateSettings(s.copy(notifyPodcasts = it)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface), shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 14.sp, modifier = Modifier.padding(bottom = 10.dp))
            content()
        }
    }
}

@Composable
private fun QualitySelectorRow(title: String, selected: AudioQuality, onSelect: (AudioQuality) -> Unit) {
    Column {
        Text(title, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(AudioQuality.entries) { q ->
                val isSel = q == selected
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (isSel) SpotifyGreen else SpotifyCard).clickable { onSelect(q) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(q.shortLabel, color = if (isSel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun ToggleSettingRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = SpotifyWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = SpotifySubtle, fontSize = 11.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = SpotifyGreen))
    }
}
