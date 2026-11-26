package com.sonify.app

import android.app.Application
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID

data class UiState(
    val selectedTab: MainTab = MainTab.HOME,
    val showSettings: Boolean = false,
    val showNowPlaying: Boolean = false,
    val showCreateSheet: Boolean = false,
    val showDevicesSheet: Boolean = false,
    val openedPlaylistId: String? = null,
    val trackForOptions: Track? = null,
    val homeFilter: HomeFilter = HomeFilter.ALL,
    val isLoadingHome: Boolean = false,
    val isBuildingAiPlaylist: Boolean = false,
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
    val hasSearched: Boolean = false,
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
    val dynamicAccentColor: Long = 0xFF1F3B33,
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

    private var retryAttemptForCurrent = 0

    val genres = listOf(
        GenreCard("פופ ישראלי", "פופ ישראלי להיטים שיר", 0xFFE8115B),
        GenreCard("היפ-הופ וראפ", "hip hop rap hits official audio", 0xFFBC5900),
        GenreCard("ים תיכוני", "שירים מזרחיים חדשים", 0xFF1E3264),
        GenreCard("אימון ואנרגיה", "gym workout music official audio", 0xFF148A08),
        GenreCard("צ'יל ופוקוס", "lofi chill song official audio", 0xFF503750),
        GenreCard("רוק ואלטרנטיבי", "rock classics hits official audio", 0xFFE91429),
        GenreCard("אלקטרוני ודאנס", "electronic dance hits official audio", 0xFF0D73EC),
        GenreCard("פודקאסטים", "פודקאסט ישראלי פרק מלא", 0xFF006450)
    )

    init {
        loadSavedState()
        audioEngine.onProgressUpdate = { pos, dur, playing, buffering ->
            _uiState.update {
                it.copy(
                    positionMs = pos,
                    durationMs = if (dur > 0) dur else it.currentTrack?.durationMs ?: 0L,
                    isPlaying = playing,
                    isBuffering = buffering || (it.isBuffering && !playing && pos == 0L)
                )
            }
        }
        audioEngine.onTrackEnded = { handleTrackEnded() }
        audioEngine.onPlaybackError = { code -> handlePlaybackError(code) }
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
            geminiApiKey = prefs.getString("gemini_key", "") ?: "",
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
            _uiState.update { it.copy(isLoadingHome = true) }
            val s = _uiState.value.settings
            val isPod = _uiState.value.homeFilter == HomeFilter.PODCASTS
            val dayQuery = when (Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) {
                in 5..11 -> "morning upbeat pop song official audio"
                in 12..16 -> "top songs 2026 official audio"
                in 17..21 -> "workout energy song official audio"
                else -> "night chill song official audio"
            }
            val daily = repo.searchTracks(if (isPod) "פודקאסט ישראלי פרק" else "להיטים חדשים ישראל שיר", s, isPod)
            val discover = repo.searchTracks(if (isPod) "tech science podcast" else "new pop hits official audio", s, isPod)
            val radar = repo.searchTracks(if (isPod) "history podcast" else "new music release official audio", s, isPod)
            val daylist = repo.searchTracks(dayQuery, s, isPod)

            _uiState.update { state ->
                state.copy(
                    isLoadingHome = false,
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

    fun selectTab(tab: MainTab) = _uiState.update {
        it.copy(selectedTab = tab, showSettings = false, openedPlaylistId = null)
    }
    fun setShowSettings(show: Boolean) = _uiState.update { it.copy(showSettings = show) }
    fun setShowNowPlaying(show: Boolean) = _uiState.update { it.copy(showNowPlaying = show) }
    fun setShowCreateSheet(show: Boolean) = _uiState.update { it.copy(showCreateSheet = show) }
    fun setShowDevicesSheet(show: Boolean) = _uiState.update { it.copy(showDevicesSheet = show) }
    fun openPlaylist(playlistId: String?) = _uiState.update { it.copy(openedPlaylistId = playlistId, showSettings = false) }
    fun openTrackOptions(track: Track?) = _uiState.update { it.copy(trackForOptions = track) }
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
            .putString("gemini_key", newSettings.geminiApiKey)
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

    fun testSubsonicServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = "בודק חיבור לשרת...") }
            val (_, msg) = repo.testSubsonicConnection(_uiState.value.settings)
            _uiState.update { it.copy(statusMessage = msg) }
        }
    }

    fun scanLocalFiles() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { repo.searchLocalAudio("") }
            _uiState.update {
                it.copy(
                    recentTracks = if (found.isNotEmpty()) found.take(12) else it.recentTracks,
                    statusMessage = if (found.isEmpty()) "לא נמצאו קובצי אודיו במכשיר (בדוק הרשאות)" else "נמצאו ${found.size} קובצי אודיו במכשיר"
                )
            }
        }
    }

    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query, hasSearched = if (query.isBlank()) false else it.hasSearched) }
    }

    fun performSearch(query: String = _uiState.value.searchQuery) {
        val clean = query.trim()
        if (clean.isBlank()) return
        _uiState.update { it.copy(searchQuery = clean, isSearching = true, hasSearched = true) }
        viewModelScope.launch {
            val res = repo.searchTracks(clean, _uiState.value.settings)
            _uiState.update { it.copy(searchResults = res, isSearching = false) }
        }
    }

    fun playTrack(track: Track, list: List<Track> = listOf(track), openVideoImmediately: Boolean = false) {
        retryAttemptForCurrent = 0
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
                resolvedStreams = null,
                isBuffering = true,
                isVideoMode = false,
                positionMs = 0L,
                durationMs = track.durationMs,
                lyrics = emptyList(),
                isLoadingLyrics = true
            )
        }

        extractDynamicColor(track.artworkUrl)
        resolveAndStartTrack(track, attempt = 0, forceVideo = openVideoImmediately)

        viewModelScope.launch {
            val lyrics = repo.fetchSyncedLyrics(track.artist, track.title)
            _uiState.update { it.copy(lyrics = lyrics, isLoadingLyrics = false) }
        }
    }

    private fun resolveAndStartTrack(track: Track, attempt: Int, forceVideo: Boolean = false) {
        viewModelScope.launch {
            val resolved = repo.resolveStreams(track, _uiState.value.settings, attemptIndex = attempt)
            if (resolved == null) {
                _uiState.update {
                    it.copy(isBuffering = false, statusMessage = "לא הצלחנו לטעון זרם לשיר זה — נסה שיר אחר או בדוק אינטרנט")
                }
                return@launch
            }
            val startInVideo = (forceVideo || _uiState.value.settings.autoVideoOnWifi) && resolved.hasVideoClip
            audioEngine.playResolved(resolved, track, videoMode = startInVideo)
            _uiState.update {
                it.copy(
                    resolvedStreams = resolved,
                    isBuffering = false,
                    isVideoMode = startInVideo
                )
            }
        }
    }

    private fun handlePlaybackError(code: String) {
        val track = _uiState.value.currentTrack ?: return
        if (retryAttemptForCurrent < 2 && track.source == StreamSource.YOUTUBE) {
            retryAttemptForCurrent++
            _uiState.update { it.copy(isBuffering = true, statusMessage = "מחליף שרת הזרמה אוטומטית...") }
            resolveAndStartTrack(track, attempt = retryAttemptForCurrent)
        } else {
            _uiState.update { it.copy(isBuffering = false, statusMessage = "שגיאת ניגון ($code) — נסה להפעיל מחדש") }
        }
    }

    private fun extractDynamicColor(artworkUrl: String) {
        if (artworkUrl.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val request = ImageRequest.Builder(app)
                    .data(artworkUrl)
                    .allowHardware(false)
                    .size(128)
                    .build()
                val result = app.imageLoader.execute(request)
                val bmp = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@launch
                val palette = Palette.from(bmp).generate()
                val picked = palette.getDarkVibrantColor(
                    palette.getDarkMutedColor(palette.getDominantColor(0xFF1F3B33.toInt()))
                )
                val argb = (0xFF000000L or (picked.toLong() and 0x00FFFFFFL))
                _uiState.update { it.copy(dynamicAccentColor = argb) }
            }
        }
    }

    fun toggleVideoClipMode() {
        val state = _uiState.value
        val track = state.currentTrack ?: return
        val resolved = state.resolvedStreams ?: return
        if (!state.isVideoMode) {
            if (!resolved.hasVideoClip) {
                _uiState.update { it.copy(statusMessage = "אין קליפ וידאו זמין למקור השמע הנוכחי") }
                return
            }
            audioEngine.seamlessSwitchVideoMode(resolved, track, toVideo = true)
            _uiState.update { it.copy(isVideoMode = true) }
        } else {
            audioEngine.seamlessSwitchVideoMode(resolved, track, toVideo = false)
            _uiState.update { it.copy(isVideoMode = false) }
        }
    }

    fun togglePlayPause() {
        val current = _uiState.value.currentTrack
        audioEngine.togglePlayPause(onNeedRetry = {
            if (current != null) {
                retryAttemptForCurrent = 0
                _uiState.update { it.copy(isBuffering = true) }
                resolveAndStartTrack(current, attempt = 0)
            }
        })
    }

    fun seekTo(ms: Long) {
        _uiState.update { it.copy(positionMs = ms.coerceAtLeast(0L)) }
        audioEngine.seekTo(ms)
    }

    fun skipNext() {
        val state = _uiState.value
        if (state.queue.isEmpty()) return
        val nextIdx = when {
            state.shuffleEnabled && state.queue.size > 1 -> {
                var r = state.queue.indices.random()
                if (r == state.currentIndex) r = (state.currentIndex + 1) % state.queue.size
                r
            }
            state.currentIndex + 1 < state.queue.size -> state.currentIndex + 1
            state.repeatMode == RepeatMode.ALL -> 0
            else -> -1
        }
        if (nextIdx >= 0) {
            playTrack(state.queue[nextIdx], state.queue)
        } else {
            // Autoplay next related track so Next ALWAYS responds!
            val current = state.currentTrack ?: return
            _uiState.update { it.copy(statusMessage = "מחפש שיר המשך אוטומטי...") }
            viewModelScope.launch {
                val related = repo.searchTracks("${current.artist} song", _uiState.value.settings)
                    .filterNot { cand -> _uiState.value.queue.any { it.id == cand.id } }
                    .take(5)
                if (related.isNotEmpty()) {
                    val newQueue = _uiState.value.queue + related
                    playTrack(related.first(), newQueue)
                }
            }
        }
    }

    fun skipPrevious() {
        val state = _uiState.value
        if (state.positionMs > 3000L) {
            seekTo(0L)
            return
        }
        if (state.queue.isEmpty()) return
        val prevIdx = if (state.currentIndex > 0) state.currentIndex - 1 else 0
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
            !state.shuffleEnabled -> _uiState.update {
                it.copy(shuffleEnabled = true, smartShuffleEnabled = false, statusMessage = "ערבוב הופעל")
            }
            !state.smartShuffleEnabled -> {
                _uiState.update {
                    it.copy(shuffleEnabled = true, smartShuffleEnabled = true, statusMessage = "Smart Shuffle הופעל — מוזרקים שירים דומים")
                }
                injectSmartShuffleTracks()
            }
            else -> _uiState.update {
                it.copy(shuffleEnabled = false, smartShuffleEnabled = false, statusMessage = "ערבוב כובה")
            }
        }
    }

    private fun injectSmartShuffleTracks() {
        val current = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            val similar = repo.searchTracks("${current.artist} similar song", _uiState.value.settings)
                .filterNot { cand -> _uiState.value.queue.any { it.id == cand.id } }
                .take(4)
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
            val msg = when (next) {
                RepeatMode.OFF -> "חזרה כובתה"
                RepeatMode.ALL -> "חזור על כל התור"
                RepeatMode.ONE -> "חזור על השיר הנוכחי"
            }
            it.copy(repeatMode = next, statusMessage = msg)
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
        _uiState.update { it.copy(downloadingIds = it.downloadingIds + track.id, statusMessage = "מוריד לאופליין...") }
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

    // ---------- Playlists Management ----------
    private fun savePlaylists(updated: List<Playlist>) {
        prefs.edit().putString("playlists", encodePlaylists(updated)).apply()
        _uiState.update { it.copy(playlists = updated) }
    }

    fun createPlaylist(name: String, initialTracks: List<Track> = emptyList()) {
        val clean = name.trim()
        if (clean.isBlank()) {
            _uiState.update { it.copy(statusMessage = "יש להזין שם לפלייליסט") }
            return
        }
        val pl = Playlist(name = clean, tracks = initialTracks)
        savePlaylists(listOf(pl) + _uiState.value.playlists)
        _uiState.update {
            it.copy(
                showCreateSheet = false,
                openedPlaylistId = pl.id,
                selectedTab = MainTab.LIBRARY,
                statusMessage = "הפלייליסט \"$clean\" נוצר"
            )
        }
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        val updated = _uiState.value.playlists.map { pl ->
            if (pl.id == playlistId) {
                if (pl.tracks.any { it.id == track.id }) pl
                else pl.copy(tracks = pl.tracks + track)
            } else pl
        }
        savePlaylists(updated)
        _uiState.update { it.copy(trackForOptions = null, statusMessage = "השיר נוסף לפלייליסט") }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        val updated = _uiState.value.playlists.map { pl ->
            if (pl.id == playlistId) pl.copy(tracks = pl.tracks.filterNot { it.id == trackId }) else pl
        }
        savePlaylists(updated)
        _uiState.update { it.copy(statusMessage = "השיר הוסר מהפלייליסט") }
    }

    fun deletePlaylist(playlistId: String) {
        val updated = _uiState.value.playlists.filterNot { it.id == playlistId }
        savePlaylists(updated)
        _uiState.update { it.copy(openedPlaylistId = null, statusMessage = "הפלייליסט נמחק") }
    }

    fun createBlend(friendName: String) {
        val cleanFriend = friendName.trim().ifBlank { "חבר" }
        _uiState.update { it.copy(showCreateSheet = false, statusMessage = "מרכיב Blend עם $cleanFriend...") }
        viewModelScope.launch {
            val baseTracks = (_uiState.value.likedTracks.take(5) + _uiState.value.discoverTracks.take(5)).distinctBy { it.id }
            val tracks = if (baseTracks.size >= 4) baseTracks else {
                repo.searchTracks("top hits song official audio", _uiState.value.settings).take(10)
            }
            val match = (76..98).random()
            val pl = Playlist(
                name = "Blend • אתה + $cleanFriend",
                creator = "Blend",
                description = "התאמה מוזיקלית של $match%",
                tracks = tracks,
                isBlend = true,
                blendMatchPercent = match
            )
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update {
                it.copy(
                    openedPlaylistId = pl.id,
                    selectedTab = MainTab.LIBRARY,
                    statusMessage = "נוצר Blend עם $match% התאמה (${tracks.size} שירים)"
                )
            }
        }
    }

    fun createAiPlaylist(prompt: String) {
        val clean = prompt.trim()
        if (clean.isBlank()) {
            _uiState.update { it.copy(statusMessage = "תאר איזה פלייליסט תרצה שה-AI יבנה") }
            return
        }
        _uiState.update {
            it.copy(
                showCreateSheet = false,
                isBuildingAiPlaylist = true,
                statusMessage = "AI Playlist בונה עבורך רשימת שירים..."
            )
        }
        viewModelScope.launch {
            val (title, tracks) = repo.buildAiPlaylist(clean, _uiState.value.settings)
            if (tracks.isEmpty()) {
                _uiState.update {
                    it.copy(
                        isBuildingAiPlaylist = false,
                        statusMessage = "לא נמצאו שירים לתיאור הזה — נסה תיאור קצר יותר (למשל: היפ הופ לאימון)"
                    )
                }
                return@launch
            }
            val pl = Playlist(
                name = title,
                creator = "AI Playlist",
                description = "נבנה לפי: $clean",
                tracks = tracks
            )
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update {
                it.copy(
                    isBuildingAiPlaylist = false,
                    openedPlaylistId = pl.id,
                    selectedTab = MainTab.LIBRARY,
                    statusMessage = "הפלייליסט מוכן עם ${tracks.size} שירים!"
                )
            }
            playTrack(tracks.first(), tracks)
        }
    }

    fun startAiDj() {
        _uiState.update { it.copy(showCreateSheet = false, statusMessage = "AI DJ מכין לך סט אישי...") }
        viewModelScope.launch {
            val existing = (_uiState.value.recentTracks.take(4) + _uiState.value.discoverTracks.take(5) + _uiState.value.daylistTracks.take(5))
                .distinctBy { it.id }
                .shuffled()
            val mix = if (existing.size >= 3) existing else {
                repo.searchTracks("top hits 2026 official audio", _uiState.value.settings).take(12)
            }
            if (mix.isNotEmpty()) {
                playTrack(mix.first(), mix)
                _uiState.update { it.copy(showNowPlaying = true, statusMessage = "AI DJ מנגן עכשיו סט של ${mix.size} שירים") }
            } else {
                _uiState.update { it.copy(statusMessage = "לא הצלחנו לטעון שירים ל-AI DJ — בדוק חיבור אינטרנט") }
            }
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
            it.copy(
                sleepEndOfTrack = false,
                statusMessage = if (minutes > 0) "טיימר שינה הופעל ל-$minutes דקות" else "טיימר השינה בוטל"
            )
        }
    }

    fun setSleepEndOfTrack() {
        audioEngine.setSleepEndOfTrack(true)
        _uiState.update { it.copy(sleepMinutesLeft = null, sleepEndOfTrack = true, statusMessage = "הניגון ייעצר בסיום השיר הנוכחי") }
    }

    fun retryLyrics(customQuery: String? = null) {
        val track = _uiState.value.currentTrack ?: return
        _uiState.update { it.copy(isLoadingLyrics = true) }
        viewModelScope.launch {
            val lyrics = if (!customQuery.isNullOrBlank()) {
                repo.fetchSyncedLyrics("", customQuery)
            } else {
                repo.fetchSyncedLyrics(track.artist, track.title)
            }
            _uiState.update { it.copy(lyrics = lyrics, isLoadingLyrics = false) }
        }
    }

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
