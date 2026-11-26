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

    val qualitySummary = when {
        s.streamSource == StreamSource.LOCAL -> str.t("איכות קובץ מקורית (FLAC/MP3)", "Native File Quality (FLAC/MP3)")
        s.streamSource == StreamSource.YOUTUBE && s.ytEngineMode == YouTubeEngineMode.WEB_DIRECT ->
            str.t("הזרמה אוטומטית • הורדות: ${s.streamSource.clampQuality(s.downloadQuality).shortLabel(isEn, s.streamSource)}", "Auto Stream • DL: ${s.streamSource.clampQuality(s.downloadQuality).shortLabel(isEn, s.streamSource)}")
        else -> "Wi-Fi: ${s.streamSource.clampQuality(s.wifiQuality).shortLabel(isEn, s.streamSource)}"
    }

    val menuEntries = remember(s, state.offlineTrackIds, state.hiddenTrackIds, isEn) {
        listOf(
            SettingsMenuEntry(
                SettingsSection.STREAM_QUALITY, Icons.Rounded.HighQuality,
                str.t("מקור הזרמה, איכות ויציבות שמע", "Streaming, Quality & Anti-Stutter"),
                "${s.streamSource.label(isEn).substringBefore(" (")} • $qualitySummary",
                "youtube lossless flac opensubsonic local wifi cellular quality buffer wakelock מקור הזרמה איכות קטיעות"
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
                str.t("אחסון, מהירות הורדה ואופליין", "Storage, Turbo Downloads & Offline"),
                "${s.downloadSpeedMode.label(isEn)} • ${state.offlineTrackIds.size} ${str.t("באופליין", "offline")}",
                "offline cache download speed turbo parallel data saver recover import אופליין מטמון הורדות מהירות"
            ),
            SettingsMenuEntry(
                SettingsSection.JAM_SOCIAL, Icons.Rounded.Groups,
                str.t("Jam חי וסנכרון בין מכשירים", "Live Jam Sync (No Cloud Account)"),
                if (s.jamLiveSyncEnabled) str.t("סנכרון חי פעיל • חדר לפי קוד", "Live room sync active") else str.t("מצב קישור מקומי", "Local link mode"),
                "jam sync live room server friends share האזנה משותפת סנכרון חברים"
            ),
            SettingsMenuEntry(
                SettingsSection.AI_NOTIFICATIONS, Icons.Rounded.AutoAwesome,
                str.t("AI, בית אינסופי ותרגום מילים", "AI, Infinite Home & Lyrics Translation"),
                if (s.geminiApiKey.isNotBlank()) str.t("Gemini API מחובר • תרגום ל${s.lyricsTargetLanguage.labelHe}", "Gemini Connected • Translate to ${s.lyricsTargetLanguage.labelEn}") else str.t("AI מובנה + פיד אינסופי", "Built-in AI + Infinite Feed"),
                "ai gemini translate lyrics infinite home covers dj voice notifications תרגום מילים בית אינסופי עטיפות"
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
            )
        )
    }

    val headerTitle = when (section) {
        SettingsSection.ROOT -> str.settings
        SettingsSection.STREAM_QUALITY -> str.t("מקור הזרמה ויציבות", "Streaming & Stability")
        SettingsSection.PLAYBACK_AUDIO -> str.t("ניגון ועיבוד שמע", "Playback & Audio")
        SettingsSection.EQUALIZER -> str.t("אקולייזר 10 ערוצים", "10-Band Equalizer")
        SettingsSection.STORAGE_OFFLINE -> str.t("אחסון ומהירות הורדה", "Storage & Downloads")
        SettingsSection.JAM_SOCIAL -> str.t("Jam חי וסנכרון", "Live Jam & Sync")
        SettingsSection.PRIVACY_TASTE -> str.t("פרטיות ופרופיל טעם", "Privacy & Taste")
        SettingsSection.UI_LANGUAGE -> str.t("ממשק ושפה", "Appearance & Language")
        SettingsSection.AI_NOTIFICATIONS -> str.t("AI ותרגום מילים", "AI & Lyrics Translation")
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
                                Text(str.t("חפש הגדרה (Jam, תרגום, מהירות הורדה...)", "Search settings (Jam, translate, speed...)"), color = SpotifyMuted, fontSize = 13.sp)
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
                                .background(SpotifyDarkSurface).spotifyPressClickable { vm.setSettingsSection(item.section) }
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
                    Text("Sonify v10.0.0 • Spotify Edition + Live Jam + AI Studio", color = SpotifyMuted, fontSize = 11.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
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

                    Spacer(Modifier.height(12.dp))
                    SectionCard(str.t("יציבות הזרמה, ניגון במסך כבוי ומניעת קטיעות", "Anti-Stutter & Screen-Off Playback")) {
                        Text(str.t("פרופיל באפר אודיו:", "Audio Buffer Profile:"), color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            AudioBufferProfile.entries.forEach { prof ->
                                val sel = s.audioBufferProfile == prof
                                Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { vm.updateSettings(s.copy(audioBufferProfile = prof)) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                                    Text(prof.label(isEn), color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        ToggleSettingRow(
                            str.t("נעילת מעבד ו-Wi-Fi בזמן ניגון (WakeLock)", "Keep CPU & Wi-Fi Awake While Playing"),
                            str.t("מונע מאנדרואיד לחנוק את הזרמת השיר כשהמסך נכבה", "Prevents Android from throttling audio on lock screen"),
                            s.keepWakeLockAudio
                        ) { vm.updateSettings(s.copy(keepWakeLockAudio = it)) }
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

                    when (s.streamSource) {
                        StreamSource.LOCAL -> {
                            Spacer(Modifier.height(12.dp))
                            SectionCard(str.t("איכות שמע מקומית", "Local Audio Quality")) {
                                Text(str.t("קובצי המכשיר מתנגנים תמיד באיכות המקורית המלאה (FLAC / WAV / MP3).", "Local files always play at full native bitrate (FLAC / WAV / MP3)."), color = SpotifySubtle, fontSize = 12.sp)
                                Spacer(Modifier.height(10.dp))
                                Button(onClick = { vm.scanLocalFiles() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                                    Text(str.t("סרוק קובצי FLAC / MP3 במכשיר", "Scan Local FLAC / MP3 Files"), fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        StreamSource.YOUTUBE -> {
                            val ytQualities = listOf(AudioQuality.LOW, AudioQuality.NORMAL, AudioQuality.HIGH, AudioQuality.VERY_HIGH)
                            Spacer(Modifier.height(12.dp))
                            SectionCard(str.t("איכות שמע (נתמכת ב-YouTube בלבד)", "Audio Quality (YouTube Supported Only)")) {
                                if (s.ytEngineMode == YouTubeEngineMode.WEB_DIRECT) {
                                    Text(str.t("במצב נגן YouTube מובנה ההזרמה מותאמת אוטומטית. בחר איכות להורדות אופליין:", "In Built-in YouTube mode, stream bitrate is automatic. Choose offline download quality:"), color = SpotifySubtle, fontSize = 12.sp)
                                    Spacer(Modifier.height(10.dp))
                                    QualitySelectorRow(str.t("איכות הורדות אופליין", "Offline Download Quality"), s.streamSource.clampQuality(s.downloadQuality), ytQualities, StreamSource.YOUTUBE, isEn) {
                                        vm.updateSettings(s.copy(downloadQuality = it))
                                    }
                                } else {
                                    QualitySelectorRow(str.t("איכות ב-Wi-Fi", "Wi-Fi Streaming"), s.streamSource.clampQuality(s.wifiQuality), ytQualities, StreamSource.YOUTUBE, isEn) { vm.updateSettings(s.copy(wifiQuality = it)) }
                                    Spacer(Modifier.height(8.dp))
                                    QualitySelectorRow(str.t("איכות בסלולר", "Cellular Streaming"), s.streamSource.clampQuality(s.cellularQuality), ytQualities, StreamSource.YOUTUBE, isEn) { vm.updateSettings(s.copy(cellularQuality = it)) }
                                    Spacer(Modifier.height(8.dp))
                                    QualitySelectorRow(str.t("איכות הורדות אופליין", "Offline Download Quality"), s.streamSource.clampQuality(s.downloadQuality), ytQualities, StreamSource.YOUTUBE, isEn) { vm.updateSettings(s.copy(downloadQuality = it)) }
                                    HorizontalDivider(color = SpotifyCard, modifier = Modifier.padding(vertical = 8.dp))
                                    ToggleSettingRow(str.t("התאמת איכות אוטומטית", "Auto Adjust Quality"), str.t("מוריד איכות כשהרשת חלשה למניעת קטיעות", "Lowers quality on weak connections"), s.autoAdjustQuality) { vm.updateSettings(s.copy(autoAdjustQuality = it)) }
                                }
                                Spacer(Modifier.height(8.dp))
                                Text(str.t("YouTube תומך עד ~256kbps Opus/AAC (ללא Lossless).", "YouTube supports up to ~256kbps Opus/AAC (no Lossless)."), color = SpotifyMuted, fontSize = 11.sp)
                            }
                        }
                        StreamSource.OPENSUBSONIC -> {
                            val subQualities = AudioQuality.entries
                            Spacer(Modifier.height(12.dp))
                            SectionCard(str.t("איכות שמע (שרת OpenSubsonic + Lossless FLAC)", "Audio Quality (OpenSubsonic + Lossless FLAC)")) {
                                QualitySelectorRow(str.t("איכות ב-Wi-Fi", "Wi-Fi Streaming"), s.wifiQuality, subQualities, StreamSource.OPENSUBSONIC, isEn) { vm.updateSettings(s.copy(wifiQuality = it)) }
                                Spacer(Modifier.height(8.dp))
                                QualitySelectorRow(str.t("איכות בסלולר", "Cellular Streaming"), s.cellularQuality, subQualities, StreamSource.OPENSUBSONIC, isEn) { vm.updateSettings(s.copy(cellularQuality = it)) }
                                Spacer(Modifier.height(8.dp))
                                QualitySelectorRow(str.t("איכות הורדות אופליין", "Offline Download Quality"), s.downloadQuality, subQualities, StreamSource.OPENSUBSONIC, isEn) { vm.updateSettings(s.copy(downloadQuality = it)) }
                            }
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

                    SectionCard(str.t("מאיץ מהירות הורדה מקבילית", "Parallel Turbo Download Speed")) {
                        Text(str.t("בחר כמה חיבורים ושירים ירוצו במקביל בעת הורדה:", "Choose parallel chunks & concurrent songs during downloads:"), color = SpotifySubtle, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DownloadSpeedMode.entries) { mode ->
                                val sel = s.downloadSpeedMode == mode
                                Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { vm.updateSettings(s.copy(downloadSpeedMode = mode)) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                                    Text(mode.label(isEn), color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(12.dp))
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

                SettingsSection.JAM_SOCIAL -> {
                    SectionCard(str.t("סנכרון Jam חי (בלי חשבון ענן)", "Live Jam Sync (No Cloud Accounts)")) {
                        ToggleSettingRow(
                            str.t("הפעל סנכרון חדר חי בין מכשירים", "Enable Live Room Sync"),
                            str.t("מסנכרן שיר, ניגון/השהיה וזמן בזמן אמת לפי קוד החדר בלבד", "Syncs track, play/pause & position live using only the room code"),
                            s.jamLiveSyncEnabled
                        ) { vm.updateSettings(s.copy(jamLiveSyncEnabled = it)) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = s.jamUserDisplayName,
                            onValueChange = { vm.updateSettings(s.copy(jamUserDisplayName = it)) },
                            label = { Text(str.t("כינוי תצוגה בחדר Jam", "Jam Display Name")) },
                            placeholder = { Text(str.t("למשל: איתי", "e.g. Alex")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = s.jamSyncServerUrl,
                            onValueChange = { vm.updateSettings(s.copy(jamSyncServerUrl = it.trim())) },
                            label = { Text(str.t("שרת סנכרון (ברירת מחדל: https://ntfy.sh)", "Sync Relay URL (default: https://ntfy.sh)")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(10.dp))
                        Text("${str.t("סבילות השהיית סנכרון (מונע קפיצות):", "Sync Drift Tolerance (prevents jumps):")} ${s.jamSyncToleranceSec}s", color = SpotifyWhite, fontSize = 13.sp)
                        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
                            Slider(
                                value = s.jamSyncToleranceSec.toFloat(),
                                onValueChange = { vm.updateSettings(s.copy(jamSyncToleranceSec = it.toInt().coerceIn(1, 8))) },
                                valueRange = 1f..8f, steps = 6,
                                colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen, inactiveTrackColor = SpotifyCard)
                            )
                        }
                    }
                }

                SettingsSection.AI_NOTIFICATIONS -> {
                    SectionCard(str.t("מפתח Gemini API (AI חכם + תרגום מילים)", "Gemini API Key (Smart AI + Lyrics Translation)")) {
                        Text(str.t("הזנת מפתח Gemini API מפעילה תרגום מילות שירים מובנה ושדרוג מלא לבית האינסופי ול-AI Studio.", "Adding a Gemini API key enables instant song lyrics translation and deeper AI curation."), color = SpotifySubtle, fontSize = 12.sp)
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = s.geminiApiKey, onValueChange = { vm.updateSettings(s.copy(geminiApiKey = it.trim())) },
                            label = { Text("Gemini API Key") }, singleLine = true,
                            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(12.dp))
                        Text(str.t("שפת יעד לתרגום מילות שיר:", "Target Language for Lyrics Translation:"), color = SpotifyWhite, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(LyricsLanguage.entries) { lang ->
                                val sel = s.lyricsTargetLanguage == lang
                                Box(Modifier.clip(RoundedCornerShape(50)).background(if (sel) SpotifyGreen else SpotifyCard).clickable { vm.updateSettings(s.copy(lyricsTargetLanguage = lang)) }.padding(horizontal = 14.dp, vertical = 7.dp)) {
                                    Text(lang.label(isEn), color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        ToggleSettingRow(str.t("תרגום מילים אוטומטי בכל שיר", "Auto-Translate Lyrics on Play"), str.t("מתרגם אוטומטית כשמפתח API מוגדר", "Automatically translates when API key is set"), s.autoTranslateLyrics) { vm.updateSettings(s.copy(autoTranslateLyrics = it)) }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard(str.t("מסך בית אינסופי, פלייליסטים ועטיפות AI", "Infinite AI Home, YouTube Playlists & AI Art")) {
                        ToggleSettingRow(str.t("מסך בית אינסופי (AI)", "Infinite AI Home Feed"), str.t("טוען בלי הפסקה פלייליסטים ושירים לפי מה שאהבת", "Continuously curates new shelves from your liked songs"), s.infiniteAiHome) { vm.updateSettings(s.copy(infiniteAiHome = it)) }
                        ToggleSettingRow(str.t("פלייליסטים מוכנים מיוטיוב בבית", "Ready YouTube Playlists on Home"), str.t("מציג ומזרים פלייליסטים רשמיים מוכנים מ-YouTube Music", "Streams curated official YouTube Music playlists"), s.showYoutubePlaylistsHome) { vm.updateSettings(s.copy(showYoutubePlaylistsHome = it)) }
                        ToggleSettingRow(str.t("עטיפות AI ייחודיות לפלייליסטים", "Unique AI-Generated Playlist Covers"), str.t("מעצב עטיפה ייחודית לפי אמן וסגנון לכל פלייליסט AI", "Generates distinct artist/genre cover artwork for AI mixes"), s.aiUniqueCovers) { vm.updateSettings(s.copy(aiUniqueCovers = it)) }
                        ToggleSettingRow(str.t("קריינות קולית ב-AI DJ", "AI DJ Voice Host (TTS)"), str.t("הדי ג'יי מציג שירים והחלפות אווירה בקול", "Speaks track intros and vibe switches"), s.djVoiceEnabled) { vm.updateSettings(s.copy(djVoiceEnabled = it)) }
                    }

                    Spacer(Modifier.height(12.dp))
                    SectionCard(str.t("התראות", "Notifications")) {
                        ToggleSettingRow(str.t("שחרורים חדשים", "New Releases"), str.t("עדכונים מאמנים אהובים", "Updates from followed artists"), s.notifyNewReleases) { vm.updateSettings(s.copy(notifyNewReleases = it)) }
                        ToggleSettingRow(str.t("הופעות קרובות", "Live Concerts"), str.t("התראות על הופעות ומכירה מוקדמת", "Concert alerts & presales"), s.notifyConcerts) { vm.updateSettings(s.
