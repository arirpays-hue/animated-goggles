package com.sonify.app

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

data class UiState(
    val selectedTab: MainTab = MainTab.HOME,
    val showSettings: Boolean = false,
    val showNowPlaying: Boolean = false,
    val showCreateSheet: Boolean = false,
    val homeFilter: HomeFilter = HomeFilter.ALL,
    val libraryFilter: LibraryFilter = LibraryFilter.ALL,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val libraryGridView: Boolean = false,
    val settings: AppSettings = AppSettings(),

    // Content
    val recentTracks: List<Track> = emptyList(),
    val dailyMixTracks: List<Track> = emptyList(),
    val discoverTracks: List<Track> = emptyList(),
    val releaseRadarTracks: List<Track> = emptyList(),
    val daylistTitle: String = "Daylist",
    val daylistTracks: List<Track> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<Track> = emptyList(),
    val isSearching: Boolean = false,
    val likedTracks: List<Track> = emptyList(),
    val playlists: List<Playlist> = emptyList(),
    val offlineTrackIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),

    // Playback
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val currentTrack: Track? = null,
    val resolvedStreams: ResolvedStreams? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val isVideoMode: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val smartShuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val lyrics: List<LyricLine> = emptyList(),
    val isLoadingLyrics: Boolean = false,
    val sleepMinutesLeft: Int? = null,
    val sleepEndOfTrack: Boolean = false,
    val jamState: JamState = JamState(),
    val statusMessage: String? = null
)

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("sonify_prefs", Context.MODE_PRIVATE)
    val repo = StreamRepository(application)
    val audioEngine = AudioEngine(application)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    val genres = listOf(
        GenreCard("פופ ישראלי", "פופ ישראלי להיטים", 0xFFE8115B),
        GenreCard("היפ-הופ וראפ", "hip hop hits 2026", 0xFFBC5900),
        GenreCard("ים תיכוני", "מוזיקה מזרחית חדשה", 0xFF1E3264),
        GenreCard("אימון ואנרגיה", "workout gym music high energy", 0xFF148A08),
        GenreCard("צ'יל ופוקוס", "lofi chill beats study", 0xFF503750),
        GenreCard("רוק ואלטרנטיבי", "rock classics hits", 0xFFE91429),
        GenreCard("אלקטרוני ודאנס", "electronic dance club", 0xFF0D73EC),
        GenreCard("פודקאסטים", "פודקאסט ישראלי", 0xFF006450)
    )

    init {
        loadSavedState()
        audioEngine.onProgressUpdate = { pos, dur, playing ->
            _uiState.update {
                it.copy(
                    positionMs = pos,
                    durationMs = if (dur > 0) dur else it.currentTrack?.durationMs ?: 0L,
                    isPlaying = playing
                )
            }
        }
        audioEngine.onTrackEnded = { handleTrackEnded() }
        refreshHomeContent()
    }

    private fun loadSavedState() {
        val source = runCatching {
            StreamSource.valueOf(prefs.getString("stream_source", StreamSource.YOUTUBE.name)!!)
        }.getOrDefault(StreamSource.YOUTUBE)
        val quality = runCatching {
            AudioQuality.valueOf(prefs.getString("audio_quality", AudioQuality.HIGH.name)!!)
        }.getOrDefault(AudioQuality.HIGH)
        val eqStr = prefs.getString("eq_bands", "0,0,0,0,0") ?: "0,0,0,0,0"
        val eqBands = eqStr.split(",").mapNotNull { it.toIntOrNull() }.ifEmpty { listOf(0, 0, 0, 0, 0) }

        val settings = AppSettings(
            streamSource = source,
            quality = quality,
            subsonicUrl = prefs.getString("subsonic_url", "") ?: "",
            subsonicUser = prefs.getString("subsonic_user", "") ?: "",
            subsonicPass = prefs.getString("subsonic_pass", "") ?: "",
            normalizeVolume = prefs.getBoolean("norm_vol", true),
            gaplessPlayback = prefs.getBoolean("gapless", true),
            crossfadeSeconds = prefs.getInt("crossfade", 0),
            eqEnabled = prefs.getBoolean("eq_enabled", false),
            eqPresetIndex = prefs.getInt("eq_preset", 0),
            eqBands = eqBands,
            canvasEnabled = prefs.getBoolean("canvas", true),
            autoVideoOnWifi = prefs.getBoolean("auto_video", false)
        )
        audioEngine.applySettings(settings)

        val liked = decodeTracks(prefs.getString("liked_tracks", "[]") ?: "[]")
        val recent = decodeTracks(prefs.getString("recent_tracks", "[]") ?: "[]")
        val playlists = decodePlaylists(prefs.getString("playlists", "[]") ?: "[]")

        _uiState.update {
            it.copy(
                settings = settings,
                likedTracks = liked,
                recentTracks = recent,
                playlists = playlists,
                offlineTrackIds = repo.getOfflineTrackIds(),
                daylistTitle = computeDaylistTitle()
            )
        }
    }

    private fun computeDaylistTitle(): String {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Daylist • אנרגיית בוקר רעננה"
            in 12..16 -> "Daylist • פוקוס וקצב צהריים"
            in 17..21 -> "Daylist • אווירת ערב ואימון"
            else -> "Daylist • צ'יל לילי מאוחר"
        }
    }

    fun refreshHomeContent() {
        viewModelScope.launch {
            val s = _uiState.value.settings
            val isPod = _uiState.value.homeFilter == HomeFilter.PODCASTS
            val dayQuery = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> "morning upbeat hits"
                in 12..16 -> "top hits 2026"
                in 17..21 -> "workout energy music"
                else -> "night chill vibes"
            }
            val daily = repo.searchTracks(if (isPod) "פודקאסטים מובילים" else "להיטים מובילים ישראל", s, isPod)
            val discover = repo.searchTracks(if (isPod) "tech podcast" else "discover new music hits", s, isPod)
            val radar = repo.searchTracks(if (isPod) "history podcast" else "new music release 2026", s, isPod)
            val daylist = repo.searchTracks(dayQuery, s, isPod)

            _uiState.update { state ->
                state.copy(
                    daylistTitle = computeDaylistTitle(),
                    dailyMixTracks = daily.take(10),
                    discoverTracks = discover.take(10),
                    releaseRadarTracks = radar.take(10),
                    daylistTracks = daylist.take(10),
                    recentTracks = if (state.recentTracks.isEmpty()) daily.take(8) else state.recentTracks
                )
            }
        }
    }

    fun selectTab(tab: MainTab) = _uiState.update { it.copy(selectedTab = tab, showSettings = false) }
    fun setShowSettings(show: Boolean) = _uiState.update { it.copy(showSettings = show) }
    fun setShowNowPlaying(show: Boolean) = _uiState.update { it.copy(showNowPlaying = show) }
    fun setShowCreateSheet(show: Boolean) = _uiState.update { it.copy(showCreateSheet = show) }
    fun clearStatus() = _uiState.update { it.copy(statusMessage = null) }

    fun setHomeFilter(filter: HomeFilter) {
        _uiState.update { it.copy(homeFilter = filter) }
        refreshHomeContent()
    }

    fun setLibraryFilter(filter: LibraryFilter) = _uiState.update { it.copy(libraryFilter = filter) }
    fun setLibrarySort(sort: LibrarySort) = _uiState.update { it.copy(librarySort = sort) }
    fun toggleLibraryGrid() = _uiState.update { it.copy(libraryGridView = !it.libraryGridView) }

    fun updateSettings(newSettings: AppSettings) {
        val sourceChanged = newSettings.streamSource != _uiState.value.settings.streamSource
        _uiState.update { it.copy(settings = newSettings) }
        audioEngine.applySettings(newSettings)
        prefs.edit()
            .putString("stream_source", newSettings.streamSource.name)
            .putString("audio_quality", newSettings.quality.name)
            .putString("subsonic_url", newSettings.subsonicUrl)
            .putString("subsonic_user", newSettings.subsonicUser)
            .putString("subsonic_pass", newSettings.subsonicPass)
            .putBoolean("norm_vol", newSettings.normalizeVolume)
            .putBoolean("gapless", newSettings.gaplessPlayback)
            .putInt("crossfade", newSettings.crossfadeSeconds)
            .putBoolean("eq_enabled", newSettings.eqEnabled)
            .putInt("eq_preset", newSettings.eqPresetIndex)
            .putString("eq_bands", newSettings.eqBands.joinToString(","))
            .putBoolean("canvas", newSettings.canvasEnabled)
            .putBoolean("auto_video", newSettings.autoVideoOnWifi)
            .apply()
        if (sourceChanged) refreshHomeContent()
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query) }
    }

    fun performSearch(query: String = _uiState.value.searchQuery) {
        if (query.isBlank()) return
        _uiState.update { it.copy(searchQuery = query, isSearching = true) }
        viewModelScope.launch {
            val res = repo.searchTracks(query, _uiState.value.settings)
            _uiState.update { it.copy(searchResults = res, isSearching = false) }
        }
    }

    fun playTrack(track: Track, list: List<Track> = listOf(track)) {
        val finalQueue = if (list.any { it.id == track.id }) list else listOf(track) + list
        val idx = finalQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)
        val updatedRecent = (listOf(track) + _uiState.value.recentTracks.filterNot { it.id == track.id }).take(12)
        prefs.edit().putString("recent_tracks", encodeTracks(updatedRecent)).apply()

        _uiState.update {
            it.copy(
                queue = finalQueue,
                currentIndex = idx,
                currentTrack = track,
                recentTracks = updatedRecent,
                isBuffering = true,
                isVideoMode = false,
                lyrics = emptyList(),
                isLoadingLyrics = true
            )
        }

        viewModelScope.launch {
            val resolved = repo.resolveStreams(track, _uiState.value.settings)
            if (resolved == null) {
                _uiState.update {
                    it.copy(isBuffering = false, statusMessage = "לא נמצא זרם זמין לשיר זה — נסה מקור אחר בהגדרות")
                }
                return@launch
            }
            val startInVideo = _uiState.value.settings.autoVideoOnWifi && !resolved.videoUrl.isNullOrBlank()
            val startUrl = if (startInVideo) resolved.videoUrl!! else resolved.audioUrl
            audioEngine.playMedia(startUrl, track)
            _uiState.update {
                it.copy(
                    resolvedStreams = resolved,
                    isBuffering = false,
                    isVideoMode = startInVideo
                )
            }
            val lyrics = repo.fetchSyncedLyrics(track.artist, track.title)
            _uiState.update { it.copy(lyrics = lyrics, isLoadingLyrics = false) }
        }
    }

    fun toggleVideoClipMode() {
        val state = _uiState.value
        val track = state.currentTrack ?: return
        val resolved = state.resolvedStreams ?: return
        if (!state.isVideoMode) {
            val vUrl = resolved.videoUrl
            if (vUrl.isNullOrBlank()) {
                _uiState.update { it.copy(statusMessage = "אין קליפ וידאו זמין למקור השמע הנוכחי") }
                return
            }
            audioEngine.seamlessSwitchStream(vUrl, track)
            _uiState.update { it.copy(isVideoMode = true) }
        } else {
            audioEngine.seamlessSwitchStream(resolved.audioUrl, track)
            _uiState.update { it.copy(isVideoMode = false) }
        }
    }

    fun togglePlayPause() = audioEngine.togglePlayPause()
    fun seekTo(ms: Long) = audioEngine.seekTo(ms)

    fun skipNext() {
        val state = _uiState.value
        if (state.queue.isEmpty()) return
        val nextIdx = when {
            state.shuffleEnabled -> (state.queue.indices).random()
            state.currentIndex + 1 < state.queue.size -> state.currentIndex + 1
            state.repeatMode == RepeatMode.ALL -> 0
            else -> return
        }
        playTrack(state.queue[nextIdx], state.queue)
    }

    fun skipPrevious() {
        val state = _uiState.value
        if (state.positionMs > 3000L) {
            seekTo(0L)
            return
        }
        if (state.queue.isEmpty()) return
        val prevIdx = (state.currentIndex - 1).coerceAtLeast(0)
        playTrack(state.queue[prevIdx], state.queue)
    }

    private fun handleTrackEnded() {
        val state = _uiState.value
        if (state.repeatMode == RepeatMode.ONE) {
            seekTo(0L)
            audioEngine.player.play()
        } else {
            skipNext()
        }
    }

    fun cycleShuffleMode() {
        val state = _uiState.value
        when {
            !state.shuffleEnabled -> _uiState.update { it.copy(shuffleEnabled = true, smartShuffleEnabled = false) }
            !state.smartShuffleEnabled -> {
                _uiState.update { it.copy(shuffleEnabled = true, smartShuffleEnabled = true, statusMessage = "Smart Shuffle הופעל — מוזרקים שירים מומלצים לתור") }
                injectSmartShuffleTracks()
            }
            else -> _uiState.update { it.copy(shuffleEnabled = false, smartShuffleEnabled = false) }
        }
    }

    private fun injectSmartShuffleTracks() {
        val current = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            val similar = repo.searchTracks("${current.artist} similar songs", _uiState.value.settings)
                .filterNot { cand -> _uiState.value.queue.any { it.id == cand.id } }
                .take(3)
                .map { it.copy(isSmartInjected = true) }
            if (similar.isNotEmpty()) {
                _uiState.update { it.copy(queue = it.queue + similar) }
            }
        }
    }

    fun cycleRepeatMode() {
        _uiState.update {
            val next = when (it.repeatMode) {
                RepeatMode.OFF -> RepeatMode.ALL
                RepeatMode.ALL -> RepeatMode.ONE
                RepeatMode.ONE -> RepeatMode.OFF
            }
            it.copy(repeatMode = next)
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        val list = _uiState.value.queue.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        val item = list.removeAt(from)
        list.add(to, item)
        val currentId = _uiState.value.currentTrack?.id
        val newCurrentIdx = list.indexOfFirst { it.id == currentId }
        _uiState.update { it.copy(queue = list, currentIndex = newCurrentIdx) }
    }

    fun removeFromQueue(index: Int) {
        val list = _uiState.value.queue.toMutableList()
        if (index !in list.indices || index == _uiState.value.currentIndex) return
        list.removeAt(index)
        val newIdx = if (index < _uiState.value.currentIndex) _uiState.value.currentIndex - 1 else _uiState.value.currentIndex
        _uiState.update { it.copy(queue = list, currentIndex = newIdx) }
    }

    fun addToQueue(track: Track) {
        _uiState.update { it.copy(queue = it.queue + track, statusMessage = "נוסף לתור ההשמעה") }
    }

    fun toggleLike(track: Track) {
        val current = _uiState.value.likedTracks
        val exists = current.any { it.id == track.id }
        val updated = if (exists) current.filterNot { it.id == track.id } else listOf(track) + current
        prefs.edit().putString("liked_tracks", encodeTracks(updated)).apply()
        _uiState.update {
            it.copy(
                likedTracks = updated,
                statusMessage = if (exists) "הוסר מהשירים שאהבת" else "נוסף לשירים שאהבת"
            )
        }
    }

    fun downloadOffline(track: Track) {
        if (_uiState.value.offlineTrackIds.contains(track.id)) {
            repo.deleteOfflineTrack(track.id)
            _uiState.update {
                it.copy(
                    offlineTrackIds = repo.getOfflineTrackIds(),
                    statusMessage = "הקובץ הוסר מהורדות האופליין"
                )
            }
            return
        }
        _uiState.update { it.copy(downloadingIds = it.downloadingIds + track.id) }
        viewModelScope.launch {
            val ok = repo.downloadTrackOffline(track, _uiState.value.settings)
            _uiState.update {
                it.copy(
                    downloadingIds = it.downloadingIds - track.id,
                    offlineTrackIds = repo.getOfflineTrackIds(),
                    statusMessage = if (ok) "ירד להאזנה אופליין" else "שגיאה בהורדת הקובץ"
                )
            }
        }
    }

    fun createPlaylist(name: String, initialTracks: List<Track> = emptyList()) {
        if (name.isBlank()) return
        val pl = Playlist(name = name.trim(), tracks = initialTracks)
        val updated = listOf(pl) + _uiState.value.playlists
        prefs.edit().putString("playlists", encodePlaylists(updated)).apply()
        _uiState.update { it.copy(playlists = updated, showCreateSheet = false, statusMessage = "הפלייליסט \"$name\" נוצר") }
    }

    fun createBlend(friendName: String) {
        val name = if (friendName.isBlank()) "Blend משותף" else "Blend • אתה + $friendName"
        val match = (74..98).random()
        val combined = (_uiState.value.likedTracks.take(4) + _uiState.value.discoverTracks.take(4)).distinctBy { it.id }
        val pl = Playlist(
            name = name,
            creator = "Blend",
            description = "התאמה מוזיקלית של $match%",
            tracks = combined,
            isBlend = true,
            blendMatchPercent = match
        )
        val updated = listOf(pl) + _uiState.value.playlists
        prefs.edit().putString("playlists", encodePlaylists(updated)).apply()
        _uiState.update { it.copy(playlists = updated, showCreateSheet = false, statusMessage = "נוצר Blend עם $match% התאמה") }
    }

    fun createAiPlaylist(prompt: String) {
        if (prompt.isBlank()) return
        _uiState.update { it.copy(showCreateSheet = false, statusMessage = "AI Playlist בונה עבורך רשימה...") }
        viewModelScope.launch {
            val results = repo.searchTracks(prompt, _uiState.value.settings).take(10)
            if (results.isNotEmpty()) {
                val pl = Playlist(name = "AI • $prompt", creator = "AI Playlist", tracks = results)
                val updated = listOf(pl) + _uiState.value.playlists
                prefs.edit().putString("playlists", encodePlaylists(updated)).apply()
                _uiState.update { it.copy(playlists = updated, statusMessage = "הפלייליסט מוכן (${results.size} שירים)") }
                playTrack(results.first(), results)
            }
        }
    }

    fun startAiDj() {
        val mix = (_uiState.value.recentTracks.take(3) + _uiState.value.discoverTracks.take(4) + _uiState.value.daylistTracks.take(4))
            .distinctBy { it.id }
            .shuffled()
        if (mix.isNotEmpty()) {
            _uiState.update { it.copy(showCreateSheet = false, statusMessage = "AI DJ: מרכיב לך סט מותאם אישית") }
            playTrack(mix.first(), mix)
        }
    }

    fun toggleJamSession() {
        val cur = _uiState.value.jamState
        if (cur.active) {
            _uiState.update { it.copy(jamState = JamState(), statusMessage = "סשן ה-Jam הסתיים") }
        } else {
            val code = UUID.randomUUID().toString().take(6).uppercase()
            _uiState.update {
                it.copy(
                    jamState = JamState(active = true, code = code, participants = listOf("אתה (מארח)")),
                    showCreateSheet = false,
                    statusMessage = "Jam פעיל • קוד הצטרפות: $code"
                )
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        audioEngine.startSleepTimer(minutes) { left ->
            _uiState.update { it.copy(sleepMinutesLeft = left, sleepEndOfTrack = false) }
        }
        _uiState.update {
            it.copy(statusMessage = if (minutes > 0) "טיימר שינה הופעל ל-$minutes דקות" else "טיימר השינה בוטל")
        }
    }

    fun setSleepEndOfTrack() {
        audioEngine.setSleepEndOfTrack(true)
        _uiState.update { it.copy(sleepMinutesLeft = null, sleepEndOfTrack = true, statusMessage = "הניגון ייעצר בסיום השיר הנוכחי") }
    }

    // Simple JSON serialization
    private fun encodeTracks(tracks: List<Track>): String {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id)
                put("title", t.title)
                put("artist", t.artist)
                put("album", t.album)
                put("art", t.artworkUrl)
                put("dur", t.durationMs)
                put("src", t.source.name)
                put("local", t.localUri ?: "")
                put("pod", t.isPodcast)
            })
        }
        return arr.toString()
    }

    private fun decodeTracks(raw: String): List<Track> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(
                    Track(
                        id = o.optString("id"),
                        title = o.optString("title"),
                        artist = o.optString("artist"),
                        album = o.optString("album"),
                        artworkUrl = o.optString("art"),
                        durationMs = o.optLong("dur"),
                        source = runCatching { StreamSource.valueOf(o.optString("src")) }.getOrDefault(StreamSource.YOUTUBE),
                        localUri = o.optString("local").takeIf { it.isNotBlank() },
                        isPodcast = o.optBoolean("pod")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun encodePlaylists(list: List<Playlist>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id)
                put("name", p.name)
                put("creator", p.creator)
                put("desc", p.description)
                put("blend", p.isBlend)
                put("match", p.blendMatchPercent ?: -1)
                put("tracks", encodeTracks(p.tracks))
            })
        }
        return arr.toString()
    }

    private fun decodePlaylists(raw: String): List<Playlist> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val m = o.optInt("match", -1)
                add(
                    Playlist(
                        id = o.optString("id"),
                        name = o.optString("name"),
                        creator = o.optString("creator", "שלך"),
                        description = o.optString("desc"),
                        isBlend = o.optBoolean("blend"),
                        blendMatchPercent = if (m >= 0) m else null,
                        tracks = decodeTracks(o.optString("tracks", "[]"))
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
