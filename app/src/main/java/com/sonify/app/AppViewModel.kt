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
import kotlinx.coroutines.async
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
    val openedPlaylist: Playlist? = null,
    val isLoadingPlaylist: Boolean = false,
    val trackForOptions: Track? = null,
    val homeFilter: HomeFilter = HomeFilter.ALL,
    val isLoadingHome: Boolean = false,
    val isBuildingAiPlaylist: Boolean = false,
    val searchMode: SearchMode = SearchMode.SONGS,
    val libraryFilter: LibraryFilter = LibraryFilter.ALL,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val libraryGridView: Boolean = false,
    val settings: AppSettings = AppSettings(),

    // Home & Search Content
    val recentTracks: List<Track> = emptyList(),
    val homeMixPlaylists: List<Playlist> = emptyList(),
    val daylistPlaylist: Playlist? = null,
    val trendingSongs: List<Track> = emptyList(),
    val searchQuery: String = "",
    val searchResults: List<Track> = emptyList(),
    val searchPlaylists: List<RemotePlaylist> = emptyList(),
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
    val isResolvingStream: Boolean = false,
    val isPlayerBuffering: Boolean = false,
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
) {
    val isBusyLoading: Boolean get() = isResolvingStream || isPlayerBuffering
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("sonify_prefs", Context.MODE_PRIVATE)
    val repo = StreamRepository(application)
    val audioEngine = AudioEngine(application)

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var retryAttemptForCurrent = 0

    val genres = listOf(
        GenreCard("פופ ישראלי", "פופ ישראלי", 0xFFE8115B),
        GenreCard("היפ-הופ וראפ", "hip hop rap", 0xFFBC5900),
        GenreCard("ים תיכוני", "עומר אדם עדן חסון", 0xFF1E3264),
        GenreCard("אימון ואנרגיה", "workout gym rap", 0xFF148A08),
        GenreCard("צ'יל ופוקוס", "chill lofi beats", 0xFF503750),
        GenreCard("רוק ואלטרנטיבי", "rock classics", 0xFFE91429),
        GenreCard("אלקטרוני ודאנס", "electronic dance", 0xFF0D73EC),
        GenreCard("פודקאסטים", "פודקאסט ישראלי", 0xFF006450)
    )

    init {
        loadSavedState()
        audioEngine.onProgressUpdate = { pos, dur, playing, buffering ->
            _uiState.update {
                it.copy(
                    positionMs = pos,
                    durationMs = if (dur > 0) dur else it.currentTrack?.durationMs ?: 0L,
                    isPlaying = playing,
                    isPlayerBuffering = buffering
                )
            }
        }
        audioEngine.onTrackEnded = { handleTrackEnded() }
        audioEngine.onPlaybackError = { code -> handlePlaybackError(code) }
        refreshHomeContent()
    }

    private fun loadSavedState() {
        val schemaVer = prefs.getInt("schema_version", 1)
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

        var liked = decodeTracks(prefs.getString("liked_tracks", "[]") ?: "[]")
        var recent = decodeTracks(prefs.getString("recent_tracks", "[]") ?: "[]")
        val playlists = decodePlaylists(prefs.getString("playlists", "[]") ?: "[]")

        // v3 migration: purge old 1-2 hour YouTube compilation videos mistakenly cached earlier
        if (schemaVer < 3) {
            recent = recent.filter { it.isPodcast || it.durationMs <= 600_000L }
            liked = liked.filter { it.isPodcast || it.durationMs <= 600_000L }
            prefs.edit()
                .putInt("schema_version", 3)
                .putString("recent_tracks", encodeTracks(recent))
                .putString("liked_tracks", encodeTracks(liked))
                .apply()
        }

        _uiState.update {
            it.copy(
                settings = settings,
                likedTracks = liked,
                recentTracks = recent,
                playlists = playlists,
                offlineTrackIds = repo.getOfflineTrackIds()
            )
        }
    }

    private fun computeDaylistMeta(): Pair<String, String> {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> "Daylist • אנרגיית בוקר" to "upbeat pop morning"
            in 12..16 -> "Daylist • קצב צהריים" to "top hits 2025"
            in 17..21 -> "Daylist • אווירת ערב ואימון" to "workout energy hip hop"
            else -> "Daylist • צ'יל לילי מאוחר" to "late night chill r&b"
        }
    }

    fun refreshHomeContent() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingHome = true) }
            val s = _uiState.value.settings
            val isPod = _uiState.value.homeFilter == HomeFilter.PODCASTS
            val (dayTitle, dayQuery) = computeDaylistMeta()

            val dMix1 = async { repo.searchTracks(if (isPod) "פודקאסט ישראלי" else "להיטים ישראליים", s, isPod) }
            val dMix2 = async { repo.searchTracks(if (isPod) "פודקאסט מדע וטכנולוגיה" else "The Weeknd Dua Lipa", s, isPod) }
            val dDiscover = async { repo.searchTracks(if (isPod) "history podcast" else "indie pop alternative", s, isPod) }
            val dRadar = async { repo.searchTracks(if (isPod) "tech podcast" else "new pop releases", s, isPod) }
            val dDaylist = async { repo.searchTracks(if (isPod) "פודקאסט סיפורים" else dayQuery, s, isPod) }

            val mix1Tracks = dMix1.await().take(12)
            val mix2Tracks = dMix2.await().take(12)
            val discoverTracks = dDiscover.await().take(12)
            val radarTracks = dRadar.await().take(12)
            val dayTracks = dDaylist.await().take(12)

            val mixes = buildList {
                if (mix1Tracks.isNotEmpty()) add(
                    Playlist(
                        id = "home_daily_1", name = "Daily Mix 1 • ישראלי", creator = "Sonify",
                        description = "הלהיטים המובילים בעברית — ${mix1Tracks.size} שירים",
                        coverUrl = mix1Tracks.first().artworkUrl, tracks = mix1Tracks, isRemote = true
                    )
                )
                if (mix2Tracks.isNotEmpty()) add(
                    Playlist(
                        id = "home_daily_2", name = "Daily Mix 2 • בינלאומי", creator = "Sonify",
                        description = "פופ ומוזיקה בינלאומית — ${mix2Tracks.size} שירים",
                        coverUrl = mix2Tracks.first().artworkUrl, tracks = mix2Tracks, isRemote = true
                    )
                )
                if (discoverTracks.isNotEmpty()) add(
                    Playlist(
                        id = "home_discover", name = "Discover Weekly", creator = "Sonify",
                        description = "גילוי שבועי של שירים חדשים עבורך",
                        coverUrl = discoverTracks.first().artworkUrl, tracks = discoverTracks, isRemote = true
                    )
                )
                if (radarTracks.isNotEmpty()) add(
                    Playlist(
                        id = "home_radar", name = "Release Radar", creator = "Sonify",
                        description = "שחרורים חדשים ורעננים",
                        coverUrl = radarTracks.first().artworkUrl, tracks = radarTracks, isRemote = true
                    )
                )
            }

            val dayPlaylist = if (dayTracks.isNotEmpty()) {
                Playlist(
                    id = "home_daylist", name = dayTitle, creator = "Sonify",
                    description = "פלייליסט דינמי שמתעדכן לפי השעה ביום",
                    coverUrl = dayTracks.first().artworkUrl, tracks = dayTracks, isRemote = true
                )
            } else null

            _uiState.update { state ->
                state.copy(
                    isLoadingHome = false,
                    homeMixPlaylists = mixes,
                    daylistPlaylist = dayPlaylist,
                    trendingSongs = (mix1Tracks.take(5) + mix2Tracks.take(5)).distinctBy { it.id },
                    recentTracks = if (state.recentTracks.isEmpty()) mix1Tracks.take(8) else state.recentTracks
                )
            }
        }
    }

    fun selectTab(tab: MainTab) = _uiState.update {
        it.copy(selectedTab = tab, showSettings = false, openedPlaylist = null)
    }
    fun setShowSettings(show: Boolean) = _uiState.update { it.copy(showSettings = show) }
    fun setShowNowPlaying(show: Boolean) = _uiState.update { it.copy(showNowPlaying = show) }
    fun setShowCreateSheet(show: Boolean) = _uiState.update { it.copy(showCreateSheet = show) }
    fun setShowDevicesSheet(show: Boolean) = _uiState.update { it.copy(showDevicesSheet = show) }
    fun openPlaylist(playlist: Playlist?) = _uiState.update { it.copy(openedPlaylist = playlist, showSettings = false) }
    fun openTrackOptions(track: Track?) = _uiState.update { it.copy(trackForOptions = track) }
    fun clearStatus() = _uiState.update { it.copy(statusMessage = null) }

    fun openRemotePlaylist(remote: RemotePlaylist) {
        _uiState.update {
            it.copy(
                isLoadingPlaylist = true,
                openedPlaylist = Playlist(
                    id = remote.id,
                    name = remote.title,
                    creator = remote.subtitle,
                    coverUrl = remote.artworkUrl,
                    tracks = emptyList(),
                    isRemote = true
                )
            )
        }
        viewModelScope.launch {
            val tracks = repo.fetchPlaylistTracks(remote.id)
            _uiState.update { state ->
                val cur = state.openedPlaylist
                if (cur != null && cur.id == remote.id) {
                    state.copy(
                        isLoadingPlaylist = false,
                        openedPlaylist = cur.copy(tracks = tracks),
                        statusMessage = if (tracks.isEmpty()) "לא ניתן לטעון שירים מפלייליסט זה" else null
                    )
                } else state.copy(isLoadingPlaylist = false)
            }
        }
    }

    fun saveRemotePlaylistToLibrary(pl: Playlist) {
        val saved = pl.copy(id = UUID.randomUUID().toString(), isRemote = false)
        savePlaylists(listOf(saved) + _uiState.value.playlists)
        _uiState.update { it.copy(openedPlaylist = saved, statusMessage = "הפלייליסט נשמר בספרייה שלך!") }
    }

    fun setHomeFilter(filter: HomeFilter) {
        _uiState.update { it.copy(homeFilter = filter) }
        refreshHomeContent()
    }

    fun setSearchMode(mode: SearchMode) {
        _uiState.update { it.copy(searchMode = mode) }
        if (_uiState.value.searchQuery.isNotBlank()) performSearch()
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

    fun clearRecentHistory() {
        prefs.edit().putString("recent_tracks", "[]").apply()
        _uiState.update { it.copy(recentTracks = emptyList(), statusMessage = "היסטוריית הניגון נוקתה") }
        refreshHomeContent()
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
                    statusMessage = if (found.isEmpty()) "לא נמצאו קובצי אודיו במכשיר" else "נמצאו ${found.size} קובצי אודיו במכשיר"
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
            when (_uiState.value.searchMode) {
                SearchMode.PLAYLISTS -> {
                    val pls = repo.searchPlaylists(clean)
                    _uiState.update { it.copy(searchPlaylists = pls, isSearching = false) }
                }
                SearchMode.PODCASTS -> {
                    val res = repo.searchTracks(clean, _uiState.value.settings, podcastsOnly = true)
                    _uiState.update { it.copy(searchResults = res, isSearching = false) }
                }
                SearchMode.SONGS -> {
                    val res = repo.searchTracks(clean, _uiState.value.settings, podcastsOnly = false)
                    _uiState.update { it.copy(searchResults = res, isSearching = false) }
                }
            }
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
                isResolvingStream = true,
                isPlayerBuffering = false,
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
                    it.copy(
                        isResolvingStream = false,
                        isPlayerBuffering = false,
                        statusMessage = "לא הצלחנו לפתוח זרם פעיל לשיר — נסה שיר אחר"
                    )
                }
                return@launch
            }
            val startInVideo = (forceVideo || _uiState.value.settings.autoVideoOnWifi) && resolved.hasVideoClip
            audioEngine.playResolved(resolved, track, videoMode = startInVideo)
            _uiState.update {
                it.copy(
                    resolvedStreams = resolved,
                    isResolvingStream = false,
                    isVideoMode = startInVideo
                )
            }
        }
    }

    private fun handlePlaybackError(code: String) {
        val track = _uiState.value.currentTrack ?: return
        if (retryAttemptForCurrent < 2 && track.source == StreamSource.YOUTUBE) {
            retryAttemptForCurrent++
            _uiState.update { it.copy(isResolvingStream = true, statusMessage = "מנסה שרת חלופי...") }
            resolveAndStartTrack(track, attempt = retryAttemptForCurrent)
        } else {
            _uiState.update {
                it.copy(
                    isResolvingStream = false,
                    isPlayerBuffering = false,
                    statusMessage = "שגיאת ניגון ($code) — לחץ Play לניסיון חוזר"
                )
            }
        }
    }

    private fun extractDynamicColor(artworkUrl: String) {
        if (artworkUrl.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val request = ImageRequest.Builder(app).data(artworkUrl).allowHardware(false).size(128).build()
                val result = app.imageLoader.execute(request)
                val bmp = ((result as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@launch
                val palette = Palette.from(bmp).generate()
                val picked = palette.getDarkVibrantColor(
                    palette.getDarkMutedColor(palette.getDominantColor(0xFF1F3B33.toInt()))
                )
                _uiState.update { it.copy(dynamicAccentColor = (0xFF000000L or (picked.toLong() and 0x00FFFFFFL))) }
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
                _uiState.update { it.copy(isResolvingStream = true) }
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
            val current = state.currentTrack ?: return
            _uiState.update { it.copy(statusMessage = "טוען שיר המשך...") }
            viewModelScope.launch {
                val related = repo.searchTracks(current.artist, _uiState.value.settings)
                    .filterNot { cand -> _uiState.value.queue.any { it.id == cand.id } }
                    .take(5)
                if (related.isNotEmpty()) {
                    playTrack(related.first(), _uiState.value.queue + related)
                }
            }
        }
    }

    fun skipPrevious() {
        val state = _uiState.value
        if (state.positionMs > 3000L) { seekTo(0L); return }
        if (state.queue.isEmpty()) return
        val prevIdx = if (state.currentIndex > 0) state.currentIndex - 1 else 0
        playTrack(state.queue[prevIdx], state.queue)
    }

    private fun handleTrackEnded() {
        val state = _uiState.value
        if (state.repeatMode == RepeatMode.ONE) {
            seekTo(0L)
            audioEngine.player.play()
        } else skipNext()
    }

    fun cycleShuffleMode() {
        val state = _uiState.value
        when {
            !state.shuffleEnabled -> _uiState.update { it.copy(shuffleEnabled = true, smartShuffleEnabled = false, statusMessage = "ערבוב הופעל") }
            !state.smartShuffleEnabled -> {
                _uiState.update { it.copy(shuffleEnabled = true, smartShuffleEnabled = true, statusMessage = "Smart Shuffle הופעל") }
                injectSmartShuffleTracks()
            }
            else -> _uiState.update { it.copy(shuffleEnabled = false, smartShuffleEnabled = false, statusMessage = "ערבוב כובה") }
        }
    }

    private fun injectSmartShuffleTracks() {
        val current = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            val similar = repo.searchTracks(current.artist, _uiState.value.settings)
                .filterNot { cand -> _uiState.value.queue.any { it.id == cand.id } }
                .take(4)
                .map { it.copy(isSmartInjected = true) }
            if (similar.isNotEmpty()) _uiState.update { it.copy(queue = it.queue + similar) }
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
        list.add(to, list.removeAt(from))
        val newCurrentIdx = list.indexOfFirst { it.id == _uiState.value.currentTrack?.id }
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
            it.copy(likedTracks = updated, statusMessage = if (exists) "הוסר מהשירים שאהבת" else "נוסף לשירים שאהבת")
        }
    }

    fun downloadOffline(track: Track) {
        if (_uiState.value.offlineTrackIds.contains(track.id)) {
            repo.deleteOfflineTrack(track.id)
            _uiState.update { it.copy(offlineTrackIds = repo.getOfflineTrackIds(), statusMessage = "הוסר מהורדות האופליין") }
            return
        }
        _uiState.update { it.copy(downloadingIds = it.downloadingIds + track.id, statusMessage = "מוריד לאופליין...") }
        viewModelScope.launch {
            val ok = repo.downloadTrackOffline(track, _uiState.value.settings)
            _uiState.update {
                it.copy(
                    downloadingIds = it.downloadingIds - track.id,
                    offlineTrackIds = repo.getOfflineTrackIds(),
                    statusMessage = if (ok) "ירד להאזנה אופליין" else "שגיאה בהורדה"
                )
            }
        }
    }

    // ---------- Playlists ----------
    private fun savePlaylists(updated: List<Playlist>) {
        prefs.edit().putString("playlists", encodePlaylists(updated)).apply()
        _uiState.update { state ->
            val refreshedOpen = state.openedPlaylist?.let { open ->
                updated.firstOrNull { it.id == open.id } ?: open
            }
            state.copy(playlists = updated, openedPlaylist = refreshedOpen)
        }
    }

    fun createPlaylist(name: String, initialTracks: List<Track> = emptyList()) {
        val clean = name.trim()
        if (clean.isBlank()) {
            _uiState.update { it.copy(statusMessage = "יש להזין שם לפלייליסט") }
            return
        }
        val pl = Playlist(name = clean, coverUrl = initialTracks.firstOrNull()?.artworkUrl ?: "", tracks = initialTracks)
        savePlaylists(listOf(pl) + _uiState.value.playlists)
        _uiState.update { it.copy(showCreateSheet = false, openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = "הפלייליסט \"$clean\" נוצר") }
    }

    fun addTrackToPlaylist(playlistId: String, track: Track) {
        val updated = _uiState.value.playlists.map { pl ->
            if (pl.id == playlistId && pl.tracks.none { it.id == track.id }) {
                pl.copy(tracks = pl.tracks + track, coverUrl = pl.coverUrl.ifBlank { track.artworkUrl })
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
        savePlaylists(_uiState.value.playlists.filterNot { it.id == playlistId })
        _uiState.update { it.copy(openedPlaylist = null, statusMessage = "הפלייליסט נמחק") }
    }

    fun createBlend(friendName: String) {
        val cleanFriend = friendName.trim().ifBlank { "חבר" }
        _uiState.update { it.copy(showCreateSheet = false, statusMessage = "מרכיב Blend עם $cleanFriend...") }
        viewModelScope.launch {
            val baseTracks = (_uiState.value.likedTracks.take(5) + _uiState.value.trendingSongs.take(5)).distinctBy { it.id }
            val tracks = if (baseTracks.size >= 4) baseTracks else repo.searchTracks("top pop hits", _uiState.value.settings).take(10)
            val match = (76..98).random()
            val pl = Playlist(
                name = "Blend • אתה + $cleanFriend", creator = "Blend",
                description = "התאמה מוזיקלית של $match%",
                coverUrl = tracks.firstOrNull()?.artworkUrl ?: "",
                tracks = tracks, isBlend = true, blendMatchPercent = match
            )
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update { it.copy(openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = "נוצר Blend עם $match% התאמה") }
        }
    }

    fun createAiPlaylist(prompt: String) {
        val clean = prompt.trim()
        if (clean.isBlank()) {
            _uiState.update { it.copy(statusMessage = "תאר איזה פלייליסט תרצה שה-AI יבנה") }
            return
        }
        _uiState.update { it.copy(showCreateSheet = false, isBuildingAiPlaylist = true, statusMessage = "AI Playlist בוחר שירים בודדים...") }
        viewModelScope.launch {
            val (title, tracks) = repo.buildAiPlaylist(clean, _uiState.value.settings)
            if (tracks.isEmpty()) {
                _uiState.update { it.copy(isBuildingAiPlaylist = false, statusMessage = "לא נמצאו שירים — נסה תיאור אחר") }
                return@launch
            }
            val pl = Playlist(
                name = title, creator = "AI Playlist", description = "נבנה לפי: $clean",
                coverUrl = tracks.first().artworkUrl, tracks = tracks
            )
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update {
                it.copy(
                    isBuildingAiPlaylist = false,
                    openedPlaylist = pl,
                    selectedTab = MainTab.LIBRARY,
                    statusMessage = "נבנה פלייליסט עם ${tracks.size} שירים!"
                )
            }
            playTrack(tracks.first(), tracks)
        }
    }

    fun startAiDj() {
        _uiState.update { it.copy(showCreateSheet = false, statusMessage = "AI DJ מכין לך סט אישי...") }
        viewModelScope.launch {
            val existing = (_uiState.value.recentTracks.take(4) + _uiState.value.trendingSongs.take(8)).distinctBy { it.id }.shuffled()
            val mix = if (existing.size >= 4) existing else repo.searchTracks("להיטים מובילים", _uiState.value.settings).take(12)
            if (mix.isNotEmpty()) {
                playTrack(mix.first(), mix)
                _uiState.update { it.copy(showNowPlaying = true, statusMessage = "AI DJ מנגן סט של ${mix.size} שירים") }
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
                it.copy(jamState = JamState(active = true, code = code, participants = listOf("אתה (מארח)")), showCreateSheet = false, statusMessage = "Jam פעיל • קוד: $code")
            }
        }
    }

    fun setSleepTimer(minutes: Int) {
        audioEngine.startSleepTimer(minutes) { left -> _uiState.update { it.copy(sleepMinutesLeft = left, sleepEndOfTrack = false) } }
        _uiState.update { it.copy(sleepEndOfTrack = false, statusMessage = if (minutes > 0) "טיימר שינה ל-$minutes דקות" else "טיימר השינה בוטל") }
    }

    fun setSleepEndOfTrack() {
        audioEngine.setSleepEndOfTrack(true)
        _uiState.update { it.copy(sleepMinutesLeft = null, sleepEndOfTrack = true, statusMessage = "ייעצר בסיום השיר הנוכחי") }
    }

    fun retryLyrics(customQuery: String? = null) {
        val track = _uiState.value.currentTrack ?: return
        _uiState.update { it.copy(isLoadingLyrics = true) }
        viewModelScope.launch {
            val lyrics = if (!customQuery.isNullOrBlank()) repo.fetchSyncedLyrics("", customQuery) else repo.fetchSyncedLyrics(track.artist, track.title)
            _uiState.update { it.copy(lyrics = lyrics, isLoadingLyrics = false) }
        }
    }

    private fun encodeTracks(tracks: List<Track>): String {
        val arr = JSONArray()
        tracks.forEach { t ->
            arr.put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("artist", t.artist); put("album", t.album)
                put("art", t.artworkUrl); put("dur", t.durationMs); put("src", t.source.name)
                put("local", t.localUri ?: ""); put("pod", t.isPodcast)
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
                        id = o.optString("id"), title = o.optString("title"), artist = o.optString("artist"),
                        album = o.optString("album"), artworkUrl = o.optString("art"), durationMs = o.optLong("dur"),
                        source = runCatching { StreamSource.valueOf(o.optString("src")) }.getOrDefault(StreamSource.YOUTUBE),
                        localUri = o.optString("local").takeIf { it.isNotBlank() }, isPodcast = o.optBoolean("pod")
                    )
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun encodePlaylists(list: List<Playlist>): String {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("creator", p.creator); put("desc", p.description)
                put("cover", p.coverUrl); put("blend", p.isBlend); put("match", p.blendMatchPercent ?: -1)
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
                val trks = decodeTracks(o.optString("tracks", "[]"))
                add(
                    Playlist(
                        id = o.optString("id"), name = o.optString("name"), creator = o.optString("creator", "שלך"),
                        description = o.optString("desc"), coverUrl = o.optString("cover").ifBlank { trks.firstOrNull()?.artworkUrl ?: "" },
                        isBlend = o.optBoolean("blend"), blendMatchPercent = if (m >= 0) m else null, tracks = trks
                    )
                )
            }
        }
    }.getOrDefault(emptyList())
}
