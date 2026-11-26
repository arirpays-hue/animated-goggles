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
    val title: String,
    val subtitle: String,
    val keywords: String
)

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val s = state.settings
    val str = state.strings
    val isEn = str.isEn
    val section = state.settingsSection
    var settingsSearch by remember { mutableStateOf("") }

    val qualitySummary = when (s.streamSource) {
        StreamSource.LOCAL -> str.t("איכות קובץ מקורית (FLAC/MP3)", "Native File Quality (FLAC/MP3)")
        else -> "Wi-Fi: ${s.streamSource.clampQuality(s.wifiQuality).shortLabel(isEn, s.streamSource)}"
    }

    val menuEntries = remember(s, state.offlineTrackIds, state.hiddenTrackIds, isEn) {
        listOf(
            SettingsMenuEntry(
                SettingsSection.STREAM_QUALITY, Icons.Rounded.HighQuality,
                str.t("מקור הזרמה ואיכות שמע", "Streaming Source & Quality"),
                "${s.streamSource.label(isEn).substringBefore(" (")} • $qualitySummary",
                "youtube lossless flac opensubsonic local wifi cellular quality מקור הזרמה איכות"
            ),
            SettingsMenuEntry(
                SettingsSection.PLAYBACK_AUDIO, Icons.Rounded.Tune,
                str.t("ניגון ועיבוד שמע", "Playback & Audio"),
                "Crossfade ${s.crossfadeSeconds}s • ${if (s.monoAudio) str.t("מונו", "Mono") else str.t("סטריאו", "Stereo")} • ${s.playbackSpeed}x",
                "crossfade gapless automix mono balance normalize autoplay canvas speed ניגון שמע מונו איזון"
            ),
            SettingsMenuEntry(
                SettingsSection.EQUALIZER, Icons.Rounded.GraphicEq,
                str.t("אקולייזר 10 ערוצים ולימיטר", "10-Band Equalizer & Limiter"),
                if (s.eqEnabled) str.t("פעיל • 10 ערוצים + לימיטר דינמי", "Active • 10 Bands + Limiter") else str.t("כבוי", "Off"),
                "eq equalizer bass treble limiter אקולייזר באס טרבל"
            ),
            SettingsMenuEntry(
                SettingsSection.STORAGE_OFFLINE, Icons.Rounded.DownloadForOffline,
                str.t("אחסון, הורדות ומצב אופליין", "Storage, Downloads & Offline"),
                "${state.offlineTrackIds.size} ${str.t("שירים באופליין", "offline tracks")} • ${if (s.offlineMode) str.t("אופליין פעיל", "Offline ON") else str.t("מקוון", "Online")}",
                "offline cache download data saver recover import אופליין מטמון הורדות שחזור ייבוא"
            ),
            SettingsMenuEntry(
                SettingsSection.PRIVACY_TASTE, Icons.Rounded.Security,
                str.t("פרטיות ופרופיל טעם", "Privacy & Taste Profile"),
                "${if (s.privateSession) str.t("סשן פרטי פעיל", "Private Session ON") else str.t("רגיל", "Standard")} • ${state.hiddenTrackIds.size} ${str.t("מוסתרים", "hidden")}",
                "private explicit taste hidden friends activity פרטיות בוטה טעם מוסתרים"
            ),
            SettingsMenuEntry(
                SettingsSection.UI_LANGUAGE, Icons.Rounded.Palette,
                str.t("ממשק, שפה ומצב רכב", "Appearance, Language & Car"),
                "${s.language.label} • ${if (s.carModeOptimized) str.t("מצב רכב פעיל", "Car Mode ON") else str.t("תצוגה רגילה", "Standard")}",
                "language hebrew english car create button unplayable שפה עברית אנגלית רכב ממשק"
            ),
            SettingsMenuEntry(
                SettingsSection.AI_NOTIFICATIONS, Icons.Rounded.AutoAwesome,
                str.t("בינה מלאכותית והתראות", "AI & Notifications"),
                if (s.geminiApiKey.isNotBlank()) str.t("Gemini API מחובר", "Gemini API Connected") else str.t("AI מובנה + קול DJ", "Built-in AI + DJ Voice"),
                "ai gemini playlist dj voice notifications concerts releases התראות בינה מלאכותית"
            )
        )
    }

    val headerTitle = when (section) {
        SettingsSection.ROOT -> str.settings
        SettingsSection.STREAM_QUALITY -> str.t("מקור הזרמה ואיכות", "Streaming & Quality")
        SettingsSection.PLAYBACK_AUDIO -> str.t("ניגון ועיבוד שמע", "Playback & Audio")
        SettingsSection.EQUALIZER -> str.t("אקולייזר 10 ערוצים", "10-Band Equalizer")
        SettingsSection.STORAGE_OFFLINE -> str.t("אחסון ואופליין", "Storage & Offline")
        SettingsSection.PRIVACY_TASTE -> str.t("פרטיות ופרופיל טעם", "Privacy & Taste")
        SettingsSection.UI_LANGUAGE -> str.t("ממשק ושפה", "Appearance & Language")
        SettingsSection.AI_NOTIFICATIONS -> str.t("AI והתראות", "AI & Notifications")
    }

    Column(modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (section == SettingsSection.ROOT) vm.setShowSettings(false)
                else vm.setSettingsSection(SettingsSection.ROOT)
            }) {
                Icon(if (isEn) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward, contentDescription = str.back, tint = SpotifyWhite)
            }
            Text(headerTitle, color = SpotifyWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp).padding(bottom = 80.dp)
        ) {
            when (section) {
                SettingsSection.ROOT -> {
                    Row(
                        modifier = Modifier.fillMaxWidth().height(44.dp).clip(RoundedCornerShape(8.dp))
                            .background(SpotifyCard).padding(horizontal = 12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Search, null, tint = SpotifySubtle, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                            if (settingsSearch.isEmpty()) {
                                Text(str.t("חפש הגדרה (איכות, אקולייזר, אופליין...)", "Search settings (quality, EQ, offline...)"), color = SpotifyMuted, fontSize = 13.sp)
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

                    Card(colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface), shape = RoundedCornerShape(12.dp)) {
                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
                            ToggleSettingRow(str.offlineMode, str.t("השמעת שירים שהורדו בלבד", "Play downloaded tracks only"), s.offlineMode) { vm.updateSettings(s.copy(offlineMode = it)) }
                            HorizontalDivider(color = SpotifyCard)
                            ToggleSettingRow(str.privateSession, str.t("האזנה בלי להשפיע על המלצות", "Listen without affecting recommendations"), s.privateSession) { vm.updateSettings(s.copy(privateSession = it)) }
                            HorizontalDivider(color = SpotifyCard)
                            ToggleSettingRow(str.dataSaver, str.t("איכות חסכונית וכיבוי Canvas", "Lower quality & disable Canvas"), s.dataSaver) { vm.updateSettings(s.copy(dataSaver = it)) }
                        }
                    }

                    Spacer(Modifier.height(16.dp))

                    menuEntries.filter {
                        settingsSearch.isBlank() || it.title.contains(settingsSearch, true) || it.keywords.contains(settingsSearch, true)
                    }.forEach { item ->
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
                                Text(item.title, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
                                Text(item.subtitle, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1)
                            }
                            Icon(if (isEn) Icons.Rounded.ChevronRight else Icons.Rounded.ChevronLeft, null, tint = SpotifyMuted)
                        }
                    }

                    Spacer(Modifier.height(16.dp))
                    Text("Sonify v8.0.0 • Pro Audio, QR & HD Engine", color = SpotifyMuted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                SettingsSection.STREAM_QUALITY -> {
                    SectionCard(str.t("מקור ההזרמה הראשי", "Primary Streaming Source")) {
                        StreamSource.entries.forEach { src ->
                            val selected = s.streamSource == src
                            val selectSource = {
                                vm.updateSettings(
                                    s.copy(
                                        streamSource = src,
                                        wifiQuality = src.clampQuality(s.wifiQuality),
                                        cellularQuality = src.clampQuality(s.cellularQuality),
                                        downloadQuality = src.clampQuality(s.downloadQuality)
                                    )
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (selected) Color(0xFF1F3B33) else SpotifyCard)
                                    .clickable { selectSource() }.padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = selected, onClick = { selectSource() }, colors = RadioButtonDefaults.colors(selectedColor = SpotifyGreen))
                                Spacer(Modifier.width(8.dp))
                                Column {
                                    Text(src.label(isEn), color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(src.sub(isEn), color = SpotifySubtle, fontSize = 11.sp)
                                }
                            }
                        }
                    }

                    if (s.streamSource == StreamSource.YOUTUBE) {
                        Spacer(Modifier.height(12.dp))
                        SectionCard(str.t("מנוע ניגון YouTube", "YouTube Playback Engine")) {
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
                                        Text(mode.label(isEn), color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                                        Text(mode.sub(isEn), color = SpotifySubtle, fontSize = 11.sp)
                                    }
                                }
                            }
                        }
                    }

                    if (s.streamSource == StreamSource.OPENSUBSONIC) {
                        Spacer(Modifier.height(12.dp))
                        SectionCard(str.t("חיבור שרת OpenSubsonic / Navidrome (FLAC)", "OpenSubsonic / Navidrome Server (FLAC)")) {
                            OutlinedTextField(value = s.subsonicUrl, onValueChange = { vm.updateSettings(s.copy(subsonicUrl = it)) }, label = { Text(str.t("כתובת שרת (https://...)", "Server URL (https://...)")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = s.subsonicUser, onValueChange = { vm.updateSettings(s.copy(subsonicUser = it)) }, label = { Text(str.t("שם משתמש", "Username")) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(value = s.subsonicPass, onValueChange = { vm.updateSettings(s.copy(subsonicPass = it)) }, label = { Text(str.t("סיסמה", "Password")) }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { vm.testSubsonicServer() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                                Text(str.t("בדוק חיבור לשרת", "Test Server Connection"), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (s.streamSource == StreamSource.LOCAL) {
                        Spacer(Modifier.height(12.dp))
                        SectionCard(str.t("איכות שמע מקומית", "Local Audio Quality")) {
                            Text(
                                str.t("קובצי המכשיר מתנגנים תמיד באיכות המקורית המלאה (FLAC / WAV / MP3) ללא דחיסה וללא תלות ברשת.", "Local files always play at full native bitrate (FLAC / WAV / MP3) with zero compression."),
                                color = SpotifySubtle, fontSize = 12.sp
                            )
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { vm.scanLocalFiles() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                                Text(str.t("סרוק קובצי FLAC / MP3 במכשיר", "Scan Local FLAC / MP3 Files"), fontWeight = FontWeight.Bold)
                            }
                        }
                    } else {
                        val supportedQualities = s.streamSource.supportedQualities()
                        Spacer(Modifier.height(12.dp))
                        SectionCard(
                            if (s.streamSource == StreamSource.YOUTUBE)
                                str.t("איכות שמע (מותאם ל-YouTube)", "Audio Quality (YouTube Supported)")
                            else
                                str.t("איכות שמע (כולל Lossless FLAC)", "Audio Quality (Includes Lossless FLAC)")
                        ) {
                            QualitySelectorRow(str.t("איכות ב-Wi-Fi", "Wi-Fi Streaming"), s.streamSource.clampQuality(s.wifiQuality), supportedQualities, s.streamSource, isEn) {
                                vm.updateSettings(s.copy(wifiQuality = it))
                            }
                            Spacer(Modifier.height(8.dp))
                            QualitySelectorRow(str.t("איכות בסלולר", "Cellular Streaming"), s.streamSource.clampQuality(s.cellularQuality), supportedQualities, s.streamSource, isEn) {
                                vm.updateSettings(s.copy(cellularQuality = it))
                            }
                            Spacer(Modifier.height(8.dp))
                            QualitySelectorRow(str.t("איכות הורדות אופליין", "Download Quality"), s.streamSource.clampQuality(s.downloadQuality), supportedQualities, s.streamSource, isEn) {
                                vm.updateSettings(s.copy(downloadQuality = it))
                            }
                            if (s.streamSource == StreamSource.YOUTUBE) {
                                Spacer(Modifier.height(8.dp))
                                Text(
                                    str.t("שרתי YouTube מזרימים עד ~256kbps Opus/AAC. ל-Lossless FLAC אמיתי בחר שרת OpenSubsonic או קבצים מקומיים.", "YouTube streams up to ~256kbps Opus/AAC. For true Lossless FLAC, use OpenSubsonic or Local Files."),
                                    color = SpotifyMuted, fontSize = 11.sp
                                )
                            }
                            HorizontalDivider(color = SpotifyCard, modifier = Modifier.padding(vertical = 8.dp))
                            ToggleSettingRow(str.t("התאמת איכות אוטומטית", "Auto Adjust Quality"), str.t("מוריד איכות כשהרשת חלשה למניעת קטיעות", "Lowers quality on weak connections"), s.autoAdjustQuality) { vm.updateSettings(s.copy(autoAdjustQuality = it)) }
                        }
                    }
                }

                SettingsSection.PLAYBACK_AUDIO -> {
                    SectionCard(str.t("עיבוד שמע ומעברים", "Audio Processing & Transitions")) {
                        ToggleSettingRow(str.t("נרמול עוצמת שמע", "Normalize Volume"), str.t("השוואת ווליום אחידה בין שירים", "Set the same volume level for all tracks"), s.normalizeVolume) { vm.updateSettings(s.copy(normalizeVolume = it)) }
                        if (s.normalizeVolume) {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 8.dp)) {
                                NormalizationLevel.entries.forEach { lvl ->
                                    val sel = s.normalizationLevel == lvl
                                    Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { vm.updateSettings(s.copy(normalizationLevel = lvl)) }.padding(horizontal = 14.dp, vertical = 6.dp)) {
                                        Text(lvl.label(isEn), color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                    }
                                }
                            }
                        }
                        HorizontalDivider(color = SpotifyCard)
                        ToggleSettingRow("Gapless Playback", str.t("מעבר ללא שבריר שנייה של שקט בין רצועות", "Seamless transitions between album tracks"), s.gaplessPlayback) { vm.updateSettings(s.copy(gaplessPlayback = it)) }
                        ToggleSettingRow(str.t("מיקס אוטומטי (Automix)", "Automix"), str.t("מעברים חלקים בסגנון DJ", "Smooth DJ-style transitions"), s.automixEnabled) { vm.updateSettings(s.copy(automixEnabled = it)) }
                        ToggleSettingRow(str.t("ניגון אוטומטי (Autoplay)", "Autoplay"), str.t("ממשיך לשירים דומים כשהתור נגמר", "Keep playing similar songs when queue ends"), s.autoplayEnabled) { vm.updateSettings(s.copy(autoplayEnabled = it)) }
                        ToggleSettingRow(str.t("אודיו מונו (Mono Audio)", "Mono Audio"), str.t("איחוד ערוצי שמאל וימין לאוזנייה אחת", "Combine left and right channels into one"), s.monoAudio) { vm.updateSettings(s.copy(monoAudio = it)) }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard(str.t("Crossfade ואיזון ערוצים", "Crossfade & Audio Balance")) {
                        Text("Crossfade: ${s.crossfadeSeconds}s", color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Slider(value = s.crossfadeSeconds.toFloat(), onValueChange = { vm.updateSettings(s.copy(crossfadeSeconds = it.toInt())) }, valueRange = 0f..12f, steps = 11, colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen, inactiveTrackColor = SpotifyCard))
                        }
                        Spacer(Modifier.height(6.dp))
                        val balLabel = when {
                            s.audioBalance == 0f -> str.t("מרכז", "Center")
                            s.audioBalance < 0f -> "${str.t("שמאל", "Left")} ${(-s.audioBalance * 100).roundToInt()}%"
                            else -> "${str.t("ימין", "Right")} ${(s.audioBalance * 100).roundToInt()}%"
                        }
                        Text("${str.t("איזון שמע (L / R)", "Audio Balance (L / R)")}: $balLabel", color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Slider(value = s.audioBalance, onValueChange = { vm.updateSettings(s.copy(audioBalance = (it * 10).roundToInt() / 10f)) }, valueRange = -1f..1f, colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen, inactiveTrackColor = SpotifyCard))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                Text("L", color = SpotifySubtle, fontSize = 11.sp); Text("C", color = SpotifyGreen, fontSize = 11.sp); Text("R", color = SpotifySubtle, fontSize = 11.sp)
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard(str.t("וידאו, Canvas ופודקאסטים", "Video, Canvas & Podcasts")) {
                        ToggleSettingRow("Canvas", str.t("אפקט תנועה עדין במסך הנגן", "Subtle looping motion on Now Playing"), s.canvasEnabled) { vm.updateSettings(s.copy(canvasEnabled = it)) }
                        ToggleSettingRow(str.t("אודיו בלבד בפודקאסטים", "Audio-Only Podcasts"), str.t("חוסך סוללה ודאטה בווידאו-פודקאסטים", "Save battery and data on video podcasts"), s.audioOnlyPodcasts) { vm.updateSettings(s.copy(audioOnlyPodcasts = it)) }
                        ToggleSettingRow(str.t("פתיחת קליפ אוטומטית", "Auto-Switch to Music Video"), str.t("עובר אוטומטית לקליפ הרשמי כשזמין", "Automatically open official music video"), s.autoVideoOnWifi) { vm.updateSettings(s.copy(autoVideoOnWifi = it)) }
                    }
                }

                SettingsSection.EQUALIZER -> {
                    val bandLabels = listOf("31Hz", "62Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")
                    val eqPresets = listOf(
                        str.t("רגיל", "Flat") to List(10) { 0 },
                        str.t("בס חזק", "Bass Boost") to listOf(8, 7, 5, 2, 0, 0, 1, 2, 2, 1),
                        str.t("פופ", "Pop") to listOf(-1, 1, 3, 4, 3, 1, 0, 2, 3, 2),
                        str.t("רוק", "Rock") to listOf(5, 4, 2, -1, -2, 0, 2, 4, 5, 6),
                        str.t("אלקטרוני", "Electronic") to listOf(7, 6, 3, 0, -2, 1, 3, 4, 5, 6),
                        str.t("ווקאלי", "Vocal") to listOf(-3, -2, 0, 3, 5, 6, 4, 2, 0, -1),
                        str.t("קלאסי", "Classical") to listOf(4, 3, 2, 1, 0, 0, 1, 2, 3, 4)
                    )
                    SectionCard(str.t("אקולייזר פרמטרי (10 ערוצים) + לימיטר", "10-Band Parametric EQ + Limiter")) {
                        ToggleSettingRow(str.t("הפעל אקולייזר", "Enable Equalizer"), str.t("פועל על זרמי ExoPlayer, FLAC והורדות אופליין", "Active on ExoPlayer, FLAC & offline files"), s.eqEnabled) { vm.updateSettings(s.copy(eqEnabled = it)) }
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

                    SectionCard(str.t("אופליין ונתונים", "Offline & Data")) {
                        ToggleSettingRow(str.offlineMode, str.t("מנתק חיפוש ומנגן רק קבצים שהורדו", "Play downloaded tracks only"), s.offlineMode) { vm.updateSettings(s.copy(offlineMode = it)) }
                        ToggleSettingRow(str.dataSaver, str.t("מוריד איכות שמע ומכבה Canvas", "Lower quality & disable Canvas"), s.dataSaver) { vm.updateSettings(s.copy(dataSaver = it)) }
                        ToggleSettingRow(str.t("הורדה דרך רשת סלולרית", "Download Using Cellular"), str.t("מאפשר הורדות גם ללא Wi-Fi", "Allow downloads without Wi-Fi"), s.downloadUsingCellular) { vm.updateSettings(s.copy(downloadUsingCellular = it)) }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard(str.t("ניהול אחסון", "Storage Management")) {
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(str.t("מטמון זמני (Cache):", "Cache:"), color = SpotifySubtle, fontSize = 13.sp)
                            Text("$cacheMb MB", color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${str.offlineDownloads} (${state.offlineTrackIds.size}):", color = SpotifySubtle, fontSize = 13.sp)
                            Text("$offlineMb MB", color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(10.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { vm.clearAppCache() }, modifier = Modifier.weight(1f)) { Text(str.clearCache, color = SpotifyWhite, fontSize = 12.sp) }
                            OutlinedButton(onClick = { vm.removeAllDownloads() }, modifier = Modifier.weight(1f)) { Text(str.deleteDownloads, color = Color(0xFFFF6B6B), fontSize = 12.sp) }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard(str.t("פלייליסטים — שחזור וייבוא", "Playlists — Recover & Import")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            Button(onClick = { vm.setShowRecoverSheet(true) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyCard, contentColor = SpotifyWhite), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Restore, null, tint = SpotifyGreen, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(str.t("שחזור", "Recover"), fontSize = 13.sp)
                            }
                            Button(onClick = { vm.setShowImportSheet(true) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.FileDownload, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(str.t("ייבוא", "Import"), fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }

                SettingsSection.PRIVACY_TASTE -> {
                    var tasteArtistInput by remember { mutableStateOf("") }
                    SectionCard(str.t("פרטיות ותוכן", "Privacy & Content")) {
                        ToggleSettingRow(str.privateSession, str.t("לא שומר היסטוריה ולא משפיע על המלצות", "Pause listening history and taste updates"), s.privateSession) { vm.updateSettings(s.copy(privateSession = it)) }
                        ToggleSettingRow(str.t("אפשר תוכן בוטה (Explicit)", "Allow Explicit Content"), str.t("השמעה או חסימה של שירים מסומנים E", "Play or filter tracks marked E"), s.allowExplicit) { vm.updateSettings(s.copy(allowExplicit = it)) }
                        ToggleSettingRow(str.t("הצג פעילות האזנה לחברים", "Share Listening Activity"), str.t("שיתוף השיר המתנגן כעת", "Let friends see what you play"), s.shareListeningActivity) { vm.updateSettings(s.copy(shareListeningActivity = it)) }
                        ToggleSettingRow(str.t("הצג אמנים אחרונים בפרופיל", "Recently Played Artists"), str.t("חשיפת אמנים מושמעים לאחרונה", "Show top recent artists on profile"), s.showRecentlyPlayedArtists) { vm.updateSettings(s.copy(showRecentlyPlayedArtists = it)) }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard(str.t("פרופיל טעם ושירים מוסתרים", "Taste Profile & Hidden Tracks")) {
                        Text(str.t("החרגת אמן או ז'אנר מהמלצות:", "Exclude artist or genre from recommendations:"), color = SpotifySubtle, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedTextField(value = tasteArtistInput, onValueChange = { tasteArtistInput = it }, placeholder = { Text(str.t("שם אמן...", "Artist name...")) }, singleLine = true, modifier = Modifier.weight(1f))
                            Button(
                                onClick = { if (tasteArtistInput.isNotBlank()) { vm.toggleExcludeArtistFromTaste(tasteArtistInput.trim()); tasteArtistInput = "" } },
                                colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black)
                            ) { Text(str.t("החרג", "Exclude"), fontWeight = FontWeight.Bold) }
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
                                Text(str.t("שחזר ${state.hiddenTrackIds.size} שירים מוסתרים", "Restore ${state.hiddenTrackIds.size} hidden tracks"), color = SpotifyWhite)
                            }
                        }
                    }
                }

                SettingsSection.UI_LANGUAGE -> {
                    SectionCard(str.t("שפת האפליקציה", "App Language")) {
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
                    SectionCard(str.t("תצוגה וניווט", "Display & Navigation")) {
                        ToggleSettingRow(str.t("הצג כפתור יצירה (+) בסרגל התחתון", "Show Create (+) Tab"), str.t("הסתרה או הצגה של לשונית היצירה", "Show or hide Create in bottom bar"), s.showCreateNavButton) { vm.updateSettings(s.copy(showCreateNavButton = it)) }
                        ToggleSettingRow(str.t("הצג רצועות לא זמינות באפור", "Show Unplayable Songs"), str.t("הצגת שירים מוסתרים או לא זמינים באופליין", "Grey out hidden or offline-unavailable tracks"), s.showUnplayableSongs) { vm.updateSettings(s.copy(showUnplayableSongs = it)) }
                        ToggleSettingRow(str.t("מצב רכב (כפתורי ניגון מוגדלים)", "Car Mode (Large Controls)"), str.t("כפתורי שליטה גדולים ונוחים לנסיעה", "Larger playback buttons while driving"), s.carModeOptimized) { vm.updateSettings(s.copy(carModeOptimized = it)) }
                    }
                }

                SettingsSection.AI_NOTIFICATIONS -> {
                    SectionCard(str.t("AI Playlist ו-AI DJ", "AI Playlist & AI DJ")) {
                        ToggleSettingRow(str.t("קריינות קולית ב-AI DJ", "AI DJ Voice Host (TTS)"), str.t("הדי ג'יי מציג שירים והחלפות אווירה בקול", "Speaks track intros and vibe switches"), s.djVoiceEnabled) { vm.updateSettings(s.copy(djVoiceEnabled = it)) }
                        HorizontalDivider(color = SpotifyCard, modifier = Modifier.padding(vertical = 8.dp))
                        Text(str.t("עובד אוטומטית גם בלי מפתח. הזנת Gemini API Key משדרגת את בחירת השירים.", "Works out of the box. Optional Gemini API key enhances AI curation."), color = SpotifySubtle, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = s.geminiApiKey, onValueChange = { vm.updateSettings(s.copy(geminiApiKey = it.trim())) },
                            label = { Text("Gemini API Key (${str.t("אופציונלי", "Optional")})") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                    SectionCard(str.t("התראות", "Notifications")) {
                        ToggleSettingRow(str.t("שחרורים חדשים", "New Releases"), str.t("עדכונים מאמנים אהובים", "Updates from followed artists"), s.notifyNewReleases) { vm.updateSettings(s.copy(notifyNewReleases = it)) }
                        ToggleSettingRow(str.t("הופעות קרובות", "Live Concerts"), str.t("התראות על הופעות ומכירה מוקדמת", "Concert alerts & presales"), s.notifyConcerts) { vm.updateSettings(s.copy(notifyConcerts = it)) }
                        ToggleSettingRow(str.t("פרקי פודקאסטים חדשים", "New Podcast Episodes"), str.t("התראות על פרקים חדשים", "Alerts for new podcast drops"), s.notifyPodcasts) { vm.updateSettings(s.copy(notifyPodcasts = it)) }
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
private fun QualitySelectorRow(
    title: String,
    selected: AudioQuality,
    options: List<AudioQuality>,
    source: StreamSource,
    isEn: Boolean,
    onSelect: (AudioQuality) -> Unit
) {
    Column {
        Text(title, color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options) { q ->
                val isSel = q == selected
                Box(Modifier.clip(RoundedCornerShape(50)).background(if (isSel) SpotifyGreen else SpotifyCard).clickable { onSelect(q) }.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    Text(q.shortLabel(isEn, source), color = if (isSel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
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
