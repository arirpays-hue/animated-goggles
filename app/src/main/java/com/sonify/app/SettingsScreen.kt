package com.sonify.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SpotifyBlack)
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .padding(bottom = 120.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.setShowSettings(false) }) {
                Icon(Icons.Rounded.Close, contentDescription = "סגור", tint = SpotifyWhite)
            }
            Text("הגדרות הזרמה וסאונד", color = SpotifyWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        }

        Spacer(Modifier.height(14.dp))
        SectionTitle("מקור ההזרמה")
        StreamSource.entries.forEach { src ->
            val selected = s.streamSource == src
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (selected) Color(0xFF1F3B33) else SpotifyDarkSurface)
                    .clickable { vm.updateSettings(s.copy(streamSource = src)) }
                    .padding(14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = selected,
                    onClick = { vm.updateSettings(s.copy(streamSource = src)) },
                    colors = RadioButtonDefaults.colors(selectedColor = SpotifyGreen)
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(src.label, color = SpotifyWhite, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                    Text(src.subtitle, color = SpotifySubtle, fontSize = 12.sp)
                }
            }
        }

        // OpenSubsonic / Navidrome Credentials
        if (s.streamSource == StreamSource.OPENSUBSONIC) {
            Spacer(Modifier.height(10.dp))
            Card(colors = CardDefaults.cardColors(containerColor = SpotifyDarkSurface), shape = RoundedCornerShape(10.dp)) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("הגדרות שרת Navidrome / OpenSubsonic", color = SpotifyGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    OutlinedTextField(
                        value = s.subsonicUrl,
                        onValueChange = { vm.updateSettings(s.copy(subsonicUrl = it)) },
                        label = { Text("כתובת שרת (למשל https://music.myserver.com)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = s.subsonicUser,
                        onValueChange = { vm.updateSettings(s.copy(subsonicUser = it)) },
                        label = { Text("שם משתמש") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = s.subsonicPass,
                        onValueChange = { vm.updateSettings(s.copy(subsonicPass = it)) },
                        label = { Text("סיסמה") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("איכות סטרימינג")
        AudioQuality.entries.forEach { q ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.updateSettings(s.copy(quality = q)) }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = s.quality == q,
                    onClick = { vm.updateSettings(s.copy(quality = q)) },
                    colors = RadioButtonDefaults.colors(selectedColor = SpotifyGreen)
                )
                Text(q.label, color = SpotifyWhite, fontSize = 14.sp)
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionTitle("עיבוד שמע ווידאו")
        ToggleSettingRow("נרמול עוצמת שמע (Volume Normalization)", "מונע קפיצות ווליום בין שירים", s.normalizeVolume) {
            vm.updateSettings(s.copy(normalizeVolume = it))
        }
        ToggleSettingRow("Gapless Playback", "מעבר ללא הפסקה בין רצועות באלבום", s.gaplessPlayback) {
            vm.updateSettings(s.copy(gaplessPlayback = it))
        }
        ToggleSettingRow("פתיחת קליפ אוטומטית ב-YouTube", "עובר ישירות לקליפ השיר כשזמין", s.autoVideoOnWifi) {
            vm.updateSettings(s.copy(autoVideoOnWifi = it))
        }

        Spacer(Modifier.height(10.dp))
        Text("Crossfade (עמעום בין שירים): ${s.crossfadeSeconds} שניות", color = SpotifyWhite, fontSize = 14.sp)
        Slider(
            value = s.crossfadeSeconds.toFloat(),
            onValueChange = { vm.updateSettings(s.copy(crossfadeSeconds = it.toInt())) },
            valueRange = 0f..12f,
            steps = 11,
            colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen)
        )

        Spacer(Modifier.height(18.dp))
        SectionTitle("אקולייזר מובנה")
        ToggleSettingRow("הפעל אקולייזר", "שליטה ב-5 תדרים בזמן אמת", s.eqEnabled) {
            vm.updateSettings(s.copy(eqEnabled = it))
        }
        if (s.eqEnabled) {
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
                                    vm.updateSettings(s.copy(eqBands = updated))
                                },
                                valueRange = -10f..10f,
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(thumbColor = SpotifyGreen, activeTrackColor = SpotifyGreen)
                            )
                            Text("${if (value > 0) "+" else ""}$value", color = SpotifyWhite, fontSize = 12.sp, modifier = Modifier.width(30.dp))
                        }
                    }
                }
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
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = SpotifyWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(subtitle, color = SpotifySubtle, fontSize = 12.sp)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Color.Black, checkedTrackColor = SpotifyGreen)
        )
    }
}
