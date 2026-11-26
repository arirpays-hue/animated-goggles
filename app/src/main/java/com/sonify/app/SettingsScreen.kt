package com.sonify.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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

@Composable
fun SettingsScreen(state: UiState, vm: AppViewModel) {
    val s = state.settings
    val bandLabels = listOf("60Hz", "230Hz", "910Hz", "3.6kHz", "14kHz")
    val eqPresets = listOf(
        "רגיל" to listOf(0, 0, 0, 0, 0),
        "בס חזק" to listOf(8, 5, 0, 1, 2),
        "פופ" to listOf(-1, 3, 5, 3, -1),
        "רוק" to listOf(6, 2, -2, 4, 6),
        "אלקטרוני" to listOf(7, 4, -1, 3, 6),
        "ווקאלי" to listOf(-2, 1, 6, 5, 1)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotifyBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .padding(bottom = 100.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setShowSettings(false) }) {
                Icon(Icons.Rounded.ArrowForward, contentDescription = "חזרה", tint = SpotifyWhite)
            }
            Text("הגדרות הזרמה וסאונד", color = SpotifyWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("מקור ההזרמה")
        StreamSource.entries.forEach { src ->
            val selected = s.streamSource == src
            Row(
                modifier = Modifier
                    .fillMaxWidth().padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Color(0xFF1F3B33) else SpotifyDarkSurface)
                    .clickable { vm.updateSettings(s.copy(streamSource = src)) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(selected = selected, onClick = { vm.updateSettings(s.copy(streamSource = src)) }, colors = RadioButtonDefaults.colors(selectedColor = SpotifyGreen))
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(src.label, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(src.subtitle, color = SpotifySubtle, fontSize = 12.sp)
                }
            }
        }

        if (s.streamSource == StreamSource.YOUTUBE) {
            Spacer(Modifier.height(16.dp))
            SectionTitle("מנוע ניגון YouTube")
            YouTubeEngineMode.entries.forEach { mode ->
                val selected = s.ytEngineMode == mode
                Row(
                    modifier = Modifier
                        .fillMaxWidth().padding(vertical = 3.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (selected) Color(0xFF1F3B33) else SpotifyDarkSurface)
                        .clickable { vm.updateSettings(s.copy(ytEngineMode = mode)) }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(selected = selected, onClick = { vm.updateSettings(s.copy(ytEngineMode = mode)) }, colors = RadioButtonDefaults.colors(selectedColor = SpotifyGreen))
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(mode.label, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(mode.subtitle, color = SpotifySubtle, fontSize = 11.sp)
                    }
                }
            }
        }

        if (s.streamSource == StreamSource.OPENSUBSONIC) {
            Spacer(Modifier.height(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("הגדרות שרת Navidrome / OpenSubsonic", color = SpotifyGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(value = s.subsonicUrl, onValueChange = { vm.updateSettings(s.copy(subsonicUrl = it)) }, label = { Text("כתובת שרת (https://...)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = s.subsonicUser, onValueChange = { vm.updateSettings(s.copy(subsonicUser = it)) }, label = { Text("שם משתמש") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = s.subsonicPass, onValueChange = { vm.updateSettings(s.copy(subsonicPass = it)) }, label = { Text("סיסמה") }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                    Button(onClick = { vm.testSubsonicServer() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Wifi, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("בדוק חיבור לשרת", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (s.streamSource == StreamSource.LOCAL) {
            Spacer(Modifier.height(10.dp))
            Button(onClick = { vm.scanLocalFiles() }, colors = ButtonDefaults.buttonColors(containerColor = SpotifyGreen, contentColor = Color.Black), modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Rounded.Folder, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("סרוק קובצי FLAC/MP3 במכשיר", fontWeight = FontWeight.Bold)
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("איכות סטרימינג")
        AudioQuality.entries.forEach { q ->
            Row(modifier = Modifier.fillMaxWidth().clickable { vm.updateSettings(s.copy(quality = q)) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.quality == q, onClick = { vm.updateSettings(s.copy(quality = q)) }, colors = RadioButtonDefaults.colors(selectedColor = SpotifyGreen))
                Text(q.label, color = SpotifyWhite, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("עיבוד שמע ווידאו")
        ToggleSettingRow("נרמול עוצמת שמע (Volume Normalization)", "מונע קפיצות ווליום בין שירים", s.normalizeVolume) { vm.updateSettings(s.copy(normalizeVolume = it)) }
        ToggleSettingRow("Gapless Playback", "מעבר ללא הפסקה בין רצועות באלבום", s.gaplessPlayback) { vm.updateSettings(s.copy(gaplessPlayback = it)) }
        ToggleSettingRow("פתיחת קליפ אוטומטית ב-YouTube", "עובר ישירות לקליפ השיר כשזמין", s.autoVideoOnWifi) { vm.updateSettings(s.copy(autoVideoOnWifi = it)) }

        Spacer(Modifier.height(10.dp))
        Text("Crossfade (עמעום בין שירים): ${s.crossfadeSeconds} שניות", color = SpotifyWhite, fontSize = 14.sp)
        Slider(
            value = s.crossfadeSeconds.toFloat(),
            onValueChange = { vm.updateSettings(s.copy(crossfadeSeconds = it.toInt())) },
            valueRange = 0f..12f, steps = 11,
            colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen)
        )

        Spacer(Modifier.height(18.dp))
        SectionTitle("אקולייזר מובנה")
        ToggleSettingRow("הפעל אקולייזר", "שליטה ב-5 תדרים בזמן אמת + פריסטים", s.eqEnabled) { vm.updateSettings(s.copy(eqEnabled = it)) }
        if (s.eqEnabled) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
                itemsIndexed(eqPresets) { idx, (name, bands) ->
                    val sel = s.eqPresetIndex == idx
                    Box(
                        modifier = Modifier.clip(RoundedCornerShape(50))
                            .background(if (sel) SpotifyGreen else SpotifyCard)
                            .clickable { vm.updateSettings(s.copy(eqPresetIndex = idx, eqBands = bands)) }
                            .padding(horizontal = 14.dp, vertical = 7.dp)
                    ) { Text(name, color = if (sel) Color.Black else SpotifyWhite, fontSize = 12.sp, fontWeight = FontWeight.SemiBold) }
                }
            }
            Card(colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(12.dp)) {
                    s.eqBands.forEachIndexed { idx, value ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(bandLabels.getOrElse(idx) { "B$idx" }, color = SpotifySubtle, fontSize = 12.sp, modifier = Modifier.width(52.dp))
                            Slider(
                                value = value.toFloat(),
                                onValueChange = { v ->
                                    val updated = s.eqBands.toMutableList()
                                    updated[idx] = v.toInt()
                                    vm.updateSettings(s.copy(eqBands = updated, eqPresetIndex = -1))
                                },
                                valueRange = -10f..10f, modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen)
                            )
                            Text("${if (value > 0) "+" else ""}$value", color = SpotifyWhite, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                        }
                    }
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("AI Playlist מתקדם (אופציונלי)")
        Text("עובד אוטומטית גם בלי מפתח. הזנת מפתח Gemini API משדרגת את בחירת השירים.", color = SpotifySubtle, fontSize = 12.sp)
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = s.geminiApiKey,
            onValueChange = { vm.updateSettings(s.copy(geminiApiKey = it.trim())) },
            label = { Text("Gemini API Key (אופציונלי)") },
            singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.height(22.dp))
        SectionTitle("תחזוקה")
        OutlinedButton(onClick = { vm.clearRecentHistory() }, modifier = Modifier.fillMaxWidth()) {
            Icon(Icons.Rounded.CleaningServices, contentDescription = null, tint = SpotifyWhite, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text("נקה היסטוריית ניגון ורענן דף הבית", color = SpotifyWhite)
        }
    }
}

@Composable
private fun SectionTitle(title: String) {
    Text(title, color = SpotifyGreen, fontWeight = FontWeight.Bold, fontSize = 15.sp, modifier = Modifier.padding(bottom = 8.dp))
}

@Composable
private fun ToggleSettingRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SpotifyWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = SpotifySubtle, fontSize = 12.sp)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = SpotifyGreen))
    }
}
