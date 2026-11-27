package com.sonify.app

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val str = state.strings
    val isEn = str.isEn
    val s = state.settings

    Column(
        modifier = Modifier.fillMaxSize().background(SpotifyBlack).statusBarsPadding().navigationBarsPadding()
    ) {
        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = {
                if (state.settingsSection != SettingsSection.ROOT) vm.setSettingsSection(SettingsSection.ROOT)
                else vm.setShowSettings(false)
            }) {
                Icon(if (isEn) Icons.Rounded.ArrowBack else Icons.Rounded.ArrowForward, str.back, tint = SpotifyWhite)
            }
            Spacer(Modifier.width(6.dp))
            Text(
                text = when (state.settingsSection) {
                    SettingsSection.ROOT -> str.settings
                    SettingsSection.UI_LANGUAGE -> str.t("שפה וממשק", "Language & UI")
                    SettingsSection.STREAM_QUALITY -> str.t("מקור שמע ואיכות", "Audio Source & Quality")
                    SettingsSection.PLAYBACK_AUDIO -> str.t("ניגון והגנת רשת", "Playback & Weak-Network Shield")
                    SettingsSection.EQUALIZER -> str.t("אקולייזר 10 ערוצים", "10-Band Equalizer")
                    SettingsSection.PRIVACY_TASTE -> str.t("פרטיות ופרופיל טעם", "Privacy & Taste Profile")
                    SettingsSection.STORAGE_OFFLINE -> str.t("אחסון ומהירות הורדה", "Storage & Download Engine")
                    SettingsSection.AI_NOTIFICATIONS -> str.t("בינה מלאכותית, API והתראות", "AI API, Models & Notifications")
                    SettingsSection.JAM_SOCIAL -> str.t("Jam חי וצ'אט חברים", "Live Jam & Cloudless Chat")
                },
                color = SpotifyWhite, fontSize = 20.sp, fontWeight = FontWeight.ExtraBold
            )
        }

        Column(
            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 48.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            when (state.settingsSection) {
                SettingsSection.ROOT -> {
                    SettingsNavTile(Icons.Rounded.AutoAwesome, str.t("בינה מלאכותית, API ומודלים", "AI API, Multi-Provider & Models"), "${s.aiProvider.label(isEn)} • ${s.aiModelId.ifBlank { s.aiProvider.defaultModel }}") { vm.setSettingsSection(SettingsSection.AI_NOTIFICATIONS) }
                    SettingsNavTile(Icons.Rounded.HighQuality, str.t("מקור שמע ואיכות הזרמה", "Stream Source & Audio Quality"), "${s.streamSource.label(isEn).substringBefore(" (")} • ${s.wifiQuality.shortLabel(isEn, s.streamSource)}") { vm.setSettingsSection(SettingsSection.STREAM_QUALITY) }
                    SettingsNavTile(Icons.Rounded.Storage, str.t("אחסון, אופליין ומהירות הורדה", "Storage, Offline & Download Engine"), "${state.offlineTrackIds.size} ${str.songs} • ${String.format("%.1f", vm.repo.getOfflineSizeMb())} MB") { vm.setSettingsSection(SettingsSection.STORAGE_OFFLINE) }
                    SettingsNavTile(Icons.Rounded.PlayCircleOutline, str.t("ניגון, Crossfade והגנת רשת חלשה", "Playback, Crossfade & Buffer"), s.audioBufferProfile.label(isEn)) { vm.setSettingsSection(SettingsSection.PLAYBACK_AUDIO) }
                    SettingsNavTile(Icons.Rounded.Equalizer, str.t("אקולייזר 10 ערוצים", "10-Band Equalizer"), if (s.eqEnabled) str.t("פעיל", "ON") else str.t("כבוי", "OFF")) { vm.setSettingsSection(SettingsSection.EQUALIZER) }
                    SettingsNavTile(Icons.Rounded.Groups, str.t("Jam חי וצ'אט ללא ענן", "Live Jam & Cloudless Chat"), s.jamUserDisplayName.ifBlank { str.t("הגדר שם תצוגה ושרת סנכרון", "Set display name & relay") }) { vm.setSettingsSection(SettingsSection.JAM_SOCIAL) }
                    SettingsNavTile(Icons.Rounded.Translate, str.t("שפה, ממשק ופיד AI אינסופי", "Language, UI & Infinite AI Feed"), s.language.label) { vm.setSettingsSection(SettingsSection.UI_LANGUAGE) }
                    SettingsNavTile(Icons.Rounded.Lock, str.t("פרטיות ופרופיל טעם", "Privacy & Taste Profile"), "${state.hiddenTrackIds.size} ${str.t("מוסתרים", "hidden")}") { vm.setSettingsSection(SettingsSection.PRIVACY_TASTE) }
                    Spacer(Modifier.height(8.dp))
                    Text("Sonify v11.0.0 • Pure-Audio Engine & Multi-Model AI", color = SpotifyMuted, fontSize = 11.5.sp, modifier = Modifier.align(Alignment.CenterHorizontally))
                }

                SettingsSection.AI_NOTIFICATIONS -> {
                    val clipboard = LocalClipboardManager.current
                    var showKey by remember { mutableStateOf(false) }
                    var customModelInput by remember(s.aiProvider, s.aiModelId) { mutableStateOf(s.aiModelId) }
                    var endpointInput by remember(s.aiProvider, s.aiCustomEndpoint) { mutableStateOf(s.aiCustomEndpoint) }
                    var apiKeyInput by remember(s.geminiApiKey) { mutableStateOf(s.geminiApiKey) }

                    SettingsSectionCard(str.t("ספק AI ומודלים (לפלייליסטים, תרגום מילים ופיד חכם)", "AI Provider & Models (Playlists, Lyrics & Taste)")) {
                        Text(str.t("בחר ספק API:", "Select AI Provider:"), color = SpotifySubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(AiProvider.entries) { prov ->
                                val sel = s.aiProvider == prov
                                FilterChip(
                                    selected = sel,
                                    onClick = {
                                        customModelInput = prov.defaultModel
                                        endpointInput = prov.defaultEndpoint
                                        vm.updateSettings(s.copy(aiProvider = prov, aiModelId = prov.defaultModel, aiCustomEndpoint = if (prov == AiProvider.CUSTOM_COMPAT) s.aiCustomEndpoint else ""))
                                    },
                                    label = { Text(prov.label(isEn), fontSize = 12.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        Text(str.t("מודלים מומלצים ל-${s.aiProvider.labelHe}:", "Preset models for ${s.aiProvider.labelEn}:"), color = SpotifySubtle, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(s.aiProvider.presetModels) { mId ->
                                val active = s.aiModelId.ifBlank { s.aiProvider.defaultModel } == mId
                                FilterChip(
                                    selected = active,
                                    onClick = {
                                        customModelInput = mId
                                        vm.updateSettings(s.copy(aiModelId = mId))
                                    },
                                    label = { Text(mId, fontSize = 11.sp) }
                                )
                            }
                        }

                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = customModelInput,
                            onValueChange = { customModelInput = it; vm.updateSettings(s.copy(aiModelId = it.trim())) },
                            label = { Text(str.t("מזהה מודל (ניתן להקליד כל מודל חדש)", "Model ID (enter any custom model)")) },
                            placeholder = { Text(s.aiProvider.defaultModel) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )

                        if (s.aiProvider == AiProvider.CUSTOM_COMPAT || s.aiProvider == AiProvider.OPENROUTER) {
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = endpointInput,
                                onValueChange = { endpointInput = it; vm.updateSettings(s.copy(aiCustomEndpoint = it.trim())) },
                                label = { Text(str.t("כתובת Endpoint (תואם OpenAI)", "API Endpoint URL (OpenAI-compatible)")) },
                                placeholder = { Text(s.aiProvider.defaultEndpoint) },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }

                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            value = apiKeyInput,
                            onValueChange = { apiKeyInput = it; vm.updateSettings(s.copy(geminiApiKey = it.trim())) },
                            label = { Text("${str.t("מפתח API", "API Key")} (${s.aiProvider.keyHint})") },
                            singleLine = true,
                            visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = {
                                Row {
                                    IconButton(onClick = { clipboard.getText()?.text?.trim()?.let { apiKeyInput = it; vm.updateSettings(s.copy(geminiApiKey = it)) } }) {
                                        Icon(Icons.Rounded.ContentPaste, "Paste", tint = SpotifyGreen, modifier = Modifier.size(18.dp))
                                    }
                                    IconButton(onClick = { showKey = !showKey }) {
                                        Icon(if (showKey) Icons.Rounded.VisibilityOff else Icons.Rounded.Visibility, "Show", tint = SpotifySubtle, modifier = Modifier.size(18.dp))
                                    }
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        )

                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = { vm.testAiApiConnection() },
                            enabled = !state.isTestingAiApi,
                            colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black),
                            shape = RoundedCornerShape(50),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            if (state.isTestingAiApi) {
                                CircularProgressIndicator(color = Color.Black, strokeWidth = 2.dp, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(str.t("בודק חיבור למודל...", "Testing API connection..."), fontWeight = FontWeight.Bold)
                            } else {
                                Icon(Icons.Rounded.Bolt, null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(str.t("בדוק חיבור API עכשיו", "Test API Connection Now"), fontWeight = FontWeight.ExtraBold)
                            }
                        }

                        state.aiTestResult?.let { (ok, msg) ->
                            Spacer(Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (ok) Color(0xFF1A3A2E) else Color(0xFF3E1C1C))
                                    .padding(10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (ok) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (ok) SpotifyGreen else Color(0xFFFF8A80), modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text(msg, color = SpotifyWhite, fontSize = 12.sp)
                            }
                        }
                    }

                    SettingsSectionCard(str.t("תרגום מילים וקריינות AI DJ", "Lyrics Translation & AI DJ Voice")) {
                        Text(str.t("שפת יעד לתרגום מילות שירים:", "Lyrics translation target language:"), color = SpotifySubtle, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(LyricsLanguage.entries) { lang ->
                                FilterChip(selected = s.lyricsTargetLanguage == lang, onClick = { vm.updateSettings(s.copy(lyricsTargetLanguage = lang)) }, label = { Text(lang.label(isEn), fontSize = 12.sp) })
                            }
                        }
                        SettingsSwitchRow(str.t("תרגם מילים אוטומטית", "Auto-Translate Lyrics"), str.t("מתרגם אוטומטית בעזרת ה-API שהגדרת", "Uses your configured AI API automatically"), s.autoTranslateLyrics) { vm.updateSettings(s.copy(autoTranslateLyrics = it)) }
                        SettingsSwitchRow(str.t("קריינות AI בפתיחת DJ", "AI DJ Start Intro Voice"), "", s.djIntroVoice) { vm.updateSettings(s.copy(djIntroVoice = it, djVoiceEnabled = it || s.djBetweenTracksVoice)) }
                        SettingsSwitchRow(str.t("קריינות AI בין שירים", "AI DJ Voice Between Tracks"), "", s.djBetweenTracksVoice) { vm.updateSettings(s.copy(djBetweenTracksVoice = it, djVoiceEnabled = it || s.djIntroVoice)) }
                    }

                    SettingsSectionCard(str.t("התראות", "Notifications")) {
                        SettingsSwitchRow(str.t("שחרורים חדשים מאמנים נעקבים", "New Releases"), "", s.notifyNewReleases) { vm.updateSettings(s.copy(notifyNewReleases = it)) }
                        SettingsSwitchRow(str.t("הופעות קרובות", "Concerts"), "", s.notifyConcerts) { vm.updateSettings(s.copy(notifyConcerts = it)) }
                        SettingsSwitchRow(str.t("פרקי פודקאסט חדשים", "Podcast Episodes"), "", s.notifyPodcasts) { vm.updateSettings(s.copy(notifyPodcasts = it)) }
                    }
                }

                SettingsSection.STORAGE_OFFLINE -> {
                    SettingsSectionCard(str.t("מנוע הורדות אודיו בלבד (2–4MB לשיר)", "Pure-Audio Download Engine (~2–4MB/song)")) {
                        Text(str.t("מהירות וחיבורים מקבילים:", "Parallel download speed:"), color = SpotifySubtle, fontSize = 12.sp)
                        Spacer(Modifier.height(6.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(DownloadSpeedMode.entries) { mode ->
                                FilterChip(selected = s.downloadSpeedMode == mode, onClick = { vm.updateSettings(s.copy(downloadSpeedMode = mode)) }, label = { Text(mode.label(isEn), fontSize = 12.sp) })
                            }
                        }
                    }

                    SettingsSectionCard(str.t("אופליין ונתונים", "Offline & Data")) {
                        SettingsSwitchRow(str.t("מצב אופליין", "Offline Mode"), str.t("מנגן רק הורדות וקבצי מכשיר • חוסם קליפים ושימוש ברשת", "Plays downloaded & local files only • disables videos"), s.offlineMode) { vm.updateSettings(s.copy(offlineMode = it)) }
                        SettingsSwitchRow(str.t("חיסכון בנתונים", "Data Saver"), str.t("מוריד איכות שמע ומכבה אנימציות Canvas", "Lowers audio bitrate & disables Canvas"), s.dataSaver) { vm.updateSettings(s.copy(dataSaver = it)) }
                        SettingsSwitchRow(str.t("הורדה דרך רשת סלולרית", "Download over Cellular"), str.t("מאפשר הורדות גם ללא Wi-Fi", "Allows downloads without Wi-Fi"), s.downloadUsingCellular) { vm.updateSettings(s.copy(downloadUsingCellular = it)) }
                    }

                    SettingsSectionCard(str.t("ניהול אחסון", "Storage Management")) {
                        val cacheMb = remember(state.storageRefreshKey) { vm.repo.getCacheSizeMb() }
                        val offlineMb = remember(state.storageRefreshKey, state.offlineTrackIds.size) { vm.repo.getOfflineSizeMb() }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(str.t("מטמון זמני (Cache):", "Temporary Cache:"), color = SpotifySubtle, fontSize = 14.sp)
                            Text("${String.format("%.1f", cacheMb)} MB", color = SpotifyWhite, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${str.t("הורדות אופליין", "Offline Audio")} (${state.offlineTrackIds.size}):", color = SpotifySubtle, fontSize = 14.sp)
                            Text("${String.format("%.1f", offlineMb)} MB", color = SpotifyGreen, fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { vm.clearAppCache() }, shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) {
                                Text(str.t("נקה מטמון", "Clear Cache"), color = SpotifyWhite, fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { vm.removeAllDownloads() }, shape = RoundedCornerShape(50), border = BorderStroke(1.dp, Color(0xFFFF6B6B)), modifier = Modifier.weight(1f)) {
                                Text(str.t("מחק הורדות", "Delete Downloads"), color = Color(0xFFFF8A80), fontSize = 12.sp)
                            }
                        }
                    }

                    SettingsSectionCard(str.t("פלייליסטים — שחזור וייבוא", "Playlists — Recover & Import")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { vm.setShowRecoverSheet(true) }, shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.Restore, null, tint = SpotifyWhite, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(str.t("שחזור", "Recover"), color = SpotifyWhite)
                            }
                            Button(onClick = { vm.setShowImportSheet(true) }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) {
                                Icon(Icons.Rounded.FileDownload, null, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(5.dp)); Text(str.t("ייבוא", "Import"), fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }

                SettingsSection.STREAM_QUALITY -> {
                    SettingsSectionCard(str.t("מקור המוזיקה הראשי", "Primary Stream Source")) {
                        StreamSource.entries.forEach { src ->
                            Row(
                                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                    .background(if (s.streamSource == src) Color(0xFF1A3A2E) else SpotifyCard)
                                    .clickable { vm.updateSettings(s.copy(streamSource = src)) }
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(selected = s.streamSource == src, onClick = { vm.updateSettings(s.copy(streamSource = src)) }, colors = RadioButtonDefaults.colors(selectedColor = SpotifyGreen))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(src.label(isEn), color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                                    Text(src.sub(isEn), color = SpotifySubtle, fontSize = 11.5.sp)
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }

                    if (s.streamSource == StreamSource.YOUTUBE) {
                        SettingsSectionCard(str.t("מנוע הזרמה של YouTube", "YouTube Engine Mode")) {
                            YouTubeEngineMode.entries.forEach { mode ->
                                Row(
                                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                        .background(if (s.ytEngineMode == mode) Color(0xFF1A3A2E) else SpotifyCard)
                                        .clickable { vm.updateSettings(s.copy(ytEngineMode = mode)) }
                                        .padding(10.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    RadioButton(selected = s.ytEngineMode == mode, onClick = { vm.updateSettings(s.copy(ytEngineMode = mode)) }, colors = RadioButtonDefaults.colors(selectedColor = SpotifyGreen))
                                    Column(Modifier.weight(1f)) {
                                        Text(mode.label(isEn), color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 13.5.sp)
                                        Text(mode.sub(isEn), color = SpotifySubtle, fontSize = 11.sp)
                                    }
                                }
                                Spacer(Modifier.height(5.dp))
                            }
                        }
                    }

                    val quals = s.streamSource.supportedQualities()
                    if (quals.isNotEmpty()) {
                        SettingsSectionCard(str.t("איכות שמע והורדות", "Audio & Download Quality")) {
                            QualityRow(str.t("הזרמה ב-Wi-Fi", "Wi-Fi Streaming"), quals, s.wifiQuality, isEn, s.streamSource) { vm.updateSettings(s.copy(wifiQuality = it)) }
                            QualityRow(str.t("הזרמה בסלולר", "Cellular Streaming"), quals, s.cellularQuality, isEn, s.streamSource) { vm.updateSettings(s.copy(cellularQuality = it)) }
                            QualityRow(str.t("איכות הורדה לאופליין", "Offline Download Quality"), quals, s.downloadQuality, isEn, s.streamSource) { vm.updateSettings(s.copy(downloadQuality = it)) }
                            SettingsSwitchRow(str.t("התאמת איכות אוטומטית ברשת חלשה", "Auto-Adjust Quality on Weak Signal"), "", s.autoAdjustQuality) { vm.updateSettings(s.copy(autoAdjustQuality = it)) }
                        }
                    }

                    if (s.streamSource == StreamSource.OPENSUBSONIC) {
                        SettingsSectionCard("OpenSubsonic / Navidrome") {
                            OutlinedTextField(value = s.subsonicUrl, onValueChange = { vm.updateSettings(s.copy(subsonicUrl = it)) }, label = { Text("Server URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(value = s.subsonicUser, onValueChange = { vm.updateSettings(s.copy(subsonicUser = it)) }, label = { Text("Username") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(value = s.subsonicPass, onValueChange = { vm.updateSettings(s.copy(subsonicPass = it)) }, label = { Text("Password") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                            Spacer(Modifier.height(10.dp))
                            Button(onClick = { vm.testSubsonicServer() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
                                Text(str.t("בדוק חיבור לשרת", "Test Server Connection"), fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    if (s.streamSource == StreamSource.LOCAL) {
                        Button(onClick = { vm.scanLocalFiles() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), shape = RoundedCornerShape(50), modifier = Modifier.fillMaxWidth()) {
                            Text(str.t("סרוק קובצי שמע במכשיר", "Scan Device Audio Files"), fontWeight = FontWeight.Bold)
                        }
                    }
                }

                SettingsSection.PLAYBACK_AUDIO -> {
                    SettingsSectionCard(str.t("הגנת רשת חלשה ומטמון ניגון", "Weak-Network Shield & Buffer")) {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(AudioBufferProfile.entries) { p ->
                                FilterChip(selected = s.audioBufferProfile == p, onClick = { vm.updateSettings(s.copy(audioBufferProfile = p)) }, label = { Text(p.label(isEn), fontSize = 12.sp) })
                            }
                        }
                        SettingsSwitchRow(str.t("שמור WakeLock בניגון ברקע", "Keep WakeLock in Background"), str.t("מונע קטיעות בכיבוי מסך", "Prevents screen-off stutters"), s.keepWakeLockAudio) { vm.updateSettings(s.copy(keepWakeLockAudio = it)) }
                    }
                    SettingsSectionCard(str.t("עוצמה ומעברים", "Volume & Transitions")) {
                        SettingsSwitchRow(str.t("נרמול עוצמת קול", "Normalize Volume"), "", s.normalizeVolume) { vm.updateSettings(s.copy(normalizeVolume = it)) }
                        if (s.normalizeVolume) {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(NormalizationLevel.entries) { lvl ->
                                    FilterChip(selected = s.normalizationLevel == lvl, onClick = { vm.updateSettings(s.copy(normalizationLevel = lvl)) }, label = { Text(lvl.label(isEn), fontSize = 12.sp) })
                                }
                            }
                        }
                        SettingsSwitchRow(str.t("ניגון ללא הפסקות (Gapless)", "Gapless Playback"), "", s.gaplessPlayback) { vm.updateSettings(s.copy(gaplessPlayback = it)) }
                        SettingsSwitchRow("Automix", str.t("מעברים חלקים בין שירים", "Smooth cross-track blends"), s.automixEnabled) { vm.updateSettings(s.copy(automixEnabled = it)) }
                        Text("Crossfade: ${s.crossfadeSeconds}s", color = SpotifyWhite, fontSize = 13.sp, modifier = Modifier.padding(top = 6.dp))
                        Slider(value = s.crossfadeSeconds.toFloat(), onValueChange = { vm.updateSettings(s.copy(crossfadeSeconds = it.toInt())) }, valueRange = 0f..12f, colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen))
                        SettingsSwitchRow("Autoplay", str.t("ממשיך להשמיע שירים דומים בסיום התור", "Play similar songs when queue ends"), s.autoplayEnabled) { vm.updateSettings(s.copy(autoplayEnabled = it)) }
                        SettingsSwitchRow(str.t("שמע מונו (Mono)", "Mono Audio"), "", s.monoAudio) { vm.updateSettings(s.copy(monoAudio = it)) }
                        Text("${str.t("איזון שמאל / ימין", "L/R Balance")}: ${String.format("%.1f", s.audioBalance)}", color = SpotifyWhite, fontSize = 13.sp)
                        Slider(value = s.audioBalance, onValueChange = { vm.updateSettings(s.copy(audioBalance = it)) }, valueRange = -1f..1f, colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen))
                        SettingsSwitchRow("Canvas", str.t("תנועה עדינה לעטיפה בזמן ניגון", "Subtle artwork motion"), s.canvasEnabled) { vm.updateSettings(s.copy(canvasEnabled = it)) }
                        SettingsSwitchRow(str.t("פתח קליפ אוטומטית ב-Wi-Fi", "Auto-Video on Wi-Fi"), "", s.autoVideoOnWifi) { vm.updateSettings(s.copy(autoVideoOnWifi = it)) }
                        SettingsSwitchRow(str.t("אודיו בלבד בפודקאסטים", "Audio-only Podcasts"), "", s.audioOnlyPodcasts) { vm.updateSettings(s.copy(audioOnlyPodcasts = it)) }
                    }
                }

                SettingsSection.EQUALIZER -> {
                    val presets = listOf(
                        "Flat" to listOf(0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
                        "Bass Boost" to listOf(7, 6, 4, 2, 0, 0, 0, 1, 2, 2),
                        "Vocal" to listOf(-2, -1, 0, 2, 4, 5, 4, 2, 0, -1),
                        "Pop" to listOf(2, 3, 2, 0, -1, 1, 3, 3, 2, 1),
                        "Rock" to listOf(5, 4, 2, 0, -1, 1, 3, 4, 5, 4),
                        "Electronic" to listOf(6, 5, 2, 0, -2, 0, 2, 4, 5, 6),
                        "Late Night" to listOf(3, 2, 0, -1, 0, 1, 1, 0, -2, -3)
                    )
                    val freqs = listOf("31Hz", "62Hz", "125Hz", "250Hz", "500Hz", "1kHz", "2kHz", "4kHz", "8kHz", "16kHz")
                    SettingsSectionCard(str.t("אקולייזר 10 ערוצים", "10-Band Equalizer")) {
                        SettingsSwitchRow(str.t("הפעל אקולייזר", "Enable Equalizer"), "", s.eqEnabled) { vm.updateSettings(s.copy(eqEnabled = it)) }
                        Spacer(Modifier.height(8.dp))
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(presets.indices.toList()) { idx ->
                                val (pName, pBands) = presets[idx]
                                FilterChip(
                                    selected = s.eqPresetIndex == idx,
                                    onClick = { vm.updateSettings(s.copy(eqEnabled = true, eqPresetIndex = idx, eqBands = pBands)) },
                                    label = { Text(pName, fontSize = 12.sp) }
                                )
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        freqs.forEachIndexed { idx, label ->
                            val v = s.eqBands.getOrElse(idx) { 0 }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(label, color = SpotifySubtle, fontSize = 11.5.sp, modifier = Modifier.width(46.dp))
                                Slider(
                                    value = v.toFloat(),
                                    onValueChange = { nv ->
                                        val mutable = s.eqBands.toMutableList()
                                        while (mutable.size < 10) mutable.add(0)
                                        mutable[idx] = nv.toInt()
                                        vm.updateSettings(s.copy(eqEnabled = true, eqPresetIndex = -1, eqBands = mutable))
                                    },
                                    valueRange = -12f..12f,
                                    enabled = s.eqEnabled,
                                    colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen),
                                    modifier = Modifier.weight(1f)
                                )
                                Text("${if (v > 0) "+" else ""}${v}dB", color = SpotifyWhite, fontSize = 11.sp, modifier = Modifier.width(38.dp))
                            }
                        }
                    }
                }

                SettingsSection.UI_LANGUAGE -> {
                    SettingsSectionCard(str.t("שפה", "Language")) {
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            AppLanguage.entries.forEach { lang ->
                                FilterChip(selected = s.language == lang, onClick = { vm.updateSettings(s.copy(language = lang)) }, label = { Text(lang.label) })
                            }
                        }
                    }
                    SettingsSectionCard(str.t("ממשק וגילוי", "Interface & Discovery")) {
                        SettingsSwitchRow(str.t("הצג כפתור + בסרגל התחתון", "Show Create (+) Nav Button"), "", s.showCreateNavButton) { vm.updateSettings(s.copy(showCreateNavButton = it)) }
                        SettingsSwitchRow(str.t("פיד AI אינסופי במסך הבית", "Infinite AI Home Feed"), "", s.infiniteAiHome) { vm.updateSettings(s.copy(infiniteAiHome = it)) }
                        SettingsSwitchRow(str.t("הצג פלייליסטים מוכנים מיוטיוב בבית", "Show YouTube Playlists on Home"), "", s.showYoutubePlaylistsHome) { vm.updateSettings(s.copy(showYoutubePlaylistsHome = it)) }
                        SettingsSwitchRow(str.t("עטיפות AI מעוצבות לפלייליסטים", "Styled AI Playlist Covers"), "", s.aiUniqueCovers) { vm.updateSettings(s.copy(aiUniqueCovers = it)) }
                        SettingsSwitchRow(str.t("מצב נהיגה (כפתורים גדולים)", "Car Mode Large Controls"), "", s.carModeOptimized) { vm.updateSettings(s.copy(carModeOptimized = it)) }
                        SettingsSwitchRow(str.t("הצג שירים לא זמינים באפור", "Show Unplayable Songs"), "", s.showUnplayableSongs) { vm.updateSettings(s.copy(showUnplayableSongs = it)) }
                    }
                }

                SettingsSection.PRIVACY_TASTE -> {
                    SettingsSectionCard(str.t("פרטיות ותוכן", "Privacy & Content")) {
                        SettingsSwitchRow(str.t("אפשר תוכן בוטה (Explicit)", "Allow Explicit Content"), "", s.allowExplicit) { vm.updateSettings(s.copy(allowExplicit = it)) }
                        SettingsSwitchRow(str.t("סשן פרטי", "Private Session"), str.t("לא שומר היסטוריה וסטטיסטיקות", "Pauses history & stats"), s.privateSession) { vm.updateSettings(s.copy(privateSession = it)) }
                        SettingsSwitchRow(str.t("שתף פעילות האזנה", "Share Listening Activity"), "", s.shareListeningActivity) { vm.updateSettings(s.copy(shareListeningActivity = it)) }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = { vm.clearRecentHistory() }, shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) {
                                Text(str.t("נקה היסטוריה", "Clear History"), color = SpotifyWhite, fontSize = 12.sp)
                            }
                            OutlinedButton(onClick = { vm.clearAllHiddenTracks() }, shape = RoundedCornerShape(50), modifier = Modifier.weight(1f)) {
                                Text("${str.t("שחזר מוסתרים", "Unhide All")} (${state.hiddenTrackIds.size})", color = SpotifyWhite, fontSize = 12.sp)
                            }
                        }
                    }
                    if (state.excludedArtistsFromTaste.isNotEmpty()) {
                        SettingsSectionCard(str.t("אמנים מוחרגים מהמלצות", "Excluded Artists")) {
                            state.excludedArtistsFromTaste.forEach { art ->
                                Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                    Text(art, color = SpotifyWhite, fontSize = 14.sp)
                                    TextButton(onClick = { vm.toggleExcludeArtistFromTaste(art) }) { Text(str.t("החזר", "Restore"), color = SpotifyGreen) }
                                }
                            }
                        }
                    }
                }

                SettingsSection.JAM_SOCIAL -> {
                    SettingsSectionCard(str.t("פרופיל Jam וצ'אט ללא ענן", "Cloudless Jam & Chat Profile")) {
                        OutlinedTextField(
                            value = s.jamUserDisplayName,
                            onValueChange = { vm.updateSettings(s.copy(jamUserDisplayName = it)) },
                            label = { Text(str.t("שם תצוגה בחדרים וצ'אט", "Your Display Name")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        SettingsSwitchRow(str.t("סנכרון חי בחדרי Jam", "Live Real-Time Jam Sync"), str.t("ללא חשבון ענן — דרך שרת Relay", "Zero accounts — via lightweight relay"), s.jamLiveSyncEnabled) { vm.updateSettings(s.copy(jamLiveSyncEnabled = it)) }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = s.jamSyncServerUrl,
                            onValueChange = { vm.updateSettings(s.copy(jamSyncServerUrl = it.trim())) },
                            label = { Text(str.t("שרת Relay (ברירת מחדל: https://ntfy.sh)", "Relay Server URL")) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(Modifier.height(8.dp))
                        Text("${str.t("סבילות סנכרון זמן:", "Timeline drift tolerance:")} ${s.jamSyncToleranceSec}s", color = SpotifySubtle, fontSize = 12.sp)
                        Slider(value = s.jamSyncToleranceSec.toFloat(), onValueChange = { vm.updateSettings(s.copy(jamSyncToleranceSec = it.toInt())) }, valueRange = 1f..8f, colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen))
                    }
                }
            }
        }
    }
}

@Composable
private fun SettingsNavTile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(SpotifyDarkSurface)
            .clickable(onClick = onClick).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(40.dp).clip(CircleShape).background(SpotifyCard), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = SpotifyGreen, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SpotifyWhite, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            Text(subtitle, color = SpotifySubtle, fontSize = 12.sp, maxLines = 1)
        }
        Icon(Icons.Rounded.ChevronLeft, null, tint = SpotifyMuted)
    }
}

@Composable
private fun SettingsSectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp), colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface)) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(title, color = SpotifyGreen, fontWeight = FontWeight.ExtraBold, fontSize = 14.sp)
            Spacer(Modifier.height(10.dp))
            content()
        }
    }
}

@Composable
private fun SettingsSwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f).padding(end = 10.dp)) {
            Text(title, color = SpotifyWhite, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold)
            if (subtitle.isNotBlank()) Text(subtitle, color = SpotifySubtle, fontSize = 11.5.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = SpotifyGreen))
    }
}

@Composable
private fun QualityRow(title: String, options: List<AudioQuality>, current: AudioQuality, isEn: Boolean, source: StreamSource, onSelect: (AudioQuality) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp)) {
        Text(title, color = SpotifyWhite, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            items(options) { q ->
                FilterChip(selected = current == q, onClick = { onSelect(q) }, label = { Text(q.shortLabel(isEn, source), fontSize = 11.5.sp) })
            }
        }
    }
}
