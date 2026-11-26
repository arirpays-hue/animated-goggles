package com.sonify.app

import android.app.Application
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import coil.imageLoader
import coil.request.CachePolicy
import coil.request.ImageRequest
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
    val settingsSection: SettingsSection = SettingsSection.ROOT,
    val showNowPlaying: Boolean = false,
    val showCreateSheet: Boolean = false,
    val showDevicesSheet: Boolean = false,
    val showStatsSheet: Boolean = false,
    val showMessagesSheet: Boolean = false,
    val showHistorySheet: Boolean = false,
    val showImportSheet: Boolean = false,
    val showRecoverSheet: Boolean = false,
    val showCodeSheetFor: Pair<String, String>? = null,
    val editingCoverPlaylistId: String? = null,
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
    val selectedFolderId: String? = null,
    val settings: AppSettings = AppSettings(),
    val storageRefreshKey: Int = 0,

    // Content & Discovery
    val recentTracks: List<Track> = emptyList(),
    val homeMixPlaylists: List<Playlist> = emptyList(),
    val daylistPlaylist: Playlist? = null,
    val trendingSongs: List<Track> = emptyList(),
    val searchQuery: String = "",
    val searchHistory: List<String> = emptyList(),
    val searchResults: List<Track> = emptyList(),
    val searchPlaylists: List<RemotePlaylist> = emptyList(),
    val hasSearched: Boolean = false,
    val isSearching: Boolean = false,

    // Library, Privacy & Social
    val likedTracks: List<Track> = emptyList(),
    val hiddenTrackIds: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val folders: List<PlaylistFolder> = emptyList(),
    val deletedPlaylists: List<DeletedPlaylistRecord> = emptyList(),
    val excludedArtistsFromTaste: Set<String> = emptySet(),
    val followedFriends: List<String> = listOf("עומר", "נועה", "דניאל"),
    val messages: List<ChatMessage> = emptyList(),
    val stats: ListeningStats = ListeningStats(),
    val offlineTrackIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val downloadProgress: Map<String, Int> = emptyMap(),

    // Playback
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val currentTrack: Track? = null,
    val resolvedStreams: ResolvedStreams? = null,
    val isUsingWebEngine: Boolean = false,
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
    private var lastWidgetTitle = ""
    private var lastWidgetPlaying = false
    private var lastStatsTickMs = 0L
    private var searchJob: Job? = null
    private var homeJob: Job? = null

    val browseCategories = listOf(
        // Content & Discovery
        BrowseCategory("מוזיקה", "Music", "להיטים מובילים", 0xFFE8115B, "תוכן וגילוי", "Content & Discovery"),
        BrowseCategory("פודקאסטים", "Podcasts", "פודקאסט ישראלי", 0xFF006450, "תוכן וגילוי", "Content & Discovery", SearchMode.PODCASTS),
        BrowseCategory("ספרי שמע", "Audiobooks", "audiobook english", 0xFF1E3264, "תוכן וגילוי", "Content & Discovery", SearchMode.AUDIOBOOKS),
        BrowseCategory("הופעות חיות", "Live Events", "live concert performance", 0xFF8D67AB, "תוכן וגילוי", "Content & Discovery"),
        BrowseCategory("נוצר עבורך", "Made For You", "top personalized hits", 0xFF1DB954, "תוכן וגילוי", "Content & Discovery"),
        BrowseCategory("שחרורים חדשים", "New Releases", "new music releases 2026", 0xFFE91429, "תוכן וגילוי", "Content & Discovery"),
        BrowseCategory("מצעדים", "Charts", "top charts global hits", 0xFF8C67AC, "תוכן וגילוי", "Content & Discovery"),
        BrowseCategory("מוזיקה ישראלית", "Israeli", "מוזיקה ישראלית חדשה", 0xFF0D73EC, "תוכן וגילוי", "Content & Discovery"),
        // Genres
        BrowseCategory("פופ", "Pop", "pop hits", 0xFF148A08, "ז'אנרים", "Genres"),
        BrowseCategory("היפ הופ", "Hip-Hop", "hip hop rap", 0xFFBC5900, "ז'אנרים", "Genres"),
        BrowseCategory("רוק", "Rock", "rock classics", 0xFFE91429, "ז'אנרים", "Genres"),
        BrowseCategory("לטיני", "Latin", "latin reggaeton hits", 0xFFE1118C, "ז'אנרים", "Genres"),
        BrowseCategory("דאנס / אלקטרוני", "Dance / Electronic", "electronic dance edm", 0xFF0D73EC, "ז'אנרים", "Genres"),
        BrowseCategory("R&B", "R&B", "r&b hits", 0xFFDC148C, "ז'אנרים", "Genres"),
        BrowseCategory("אינדי", "Indie", "indie alternative", 0xFF608108, "ז'אנרים", "Genres"),
        BrowseCategory("קאנטרי", "Country", "country hits", 0xFFD84000, "ז'אנרים", "Genres"),
        BrowseCategory("מטאל", "Metal", "heavy metal rock", 0xFF477D95, "ז'אנרים", "Genres"),
        BrowseCategory("ג'אז", "Jazz", "jazz classics", 0xFF477D95, "ז'אנרים", "Genres"),
        BrowseCategory("קלאסי", "Classical", "classical orchestra", 0xFF7D4B32, "ז'אנרים", "Genres"),
        BrowseCategory("פולק ואקוסטי", "Folk & Acoustic", "acoustic folk", 0xFFBC5900, "ז'אנרים", "Genres"),
        BrowseCategory("נשמה (Soul)", "Soul", "soul music classics", 0xFFDC148C, "ז'אנרים", "Genres"),
        BrowseCategory("בלוז", "Blues", "blues guitar", 0xFF1E3264, "ז'אנרים", "Genres"),
        BrowseCategory("רגאיי", "Reggae", "reggae bob marley", 0xFF006450, "ז'אנרים", "Genres"),
        BrowseCategory("K-Pop", "K-Pop", "kpop hits", 0xFFE8115B, "ז'אנרים", "Genres"),
        BrowseCategory("אפרוביטס", "Afrobeats", "afrobeats hits", 0xFFB02897, "ז'אנרים", "Genres"),
        // Mood & Activity
        BrowseCategory("אימון", "Workout", "gym workout high energy", 0xFF777777, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("התמקדות ולימודים", "Focus", "focus study beats", 0xFF503750, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("שינה", "Sleep", "sleep ambient calm", 0xFF1E3264, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("צ'יל", "Chill", "chill vibes lofi", 0xFFD84000, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("מסיבה", "Party", "party club hits", 0xFFAF2896, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("נסיעה", "Commute", "driving road trip songs", 0xFF2D46B9, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("גיימינג", "Gaming", "gaming soundtrack phonk", 0xFFE8115B, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("בישול ואוכל", "Cooking", "cooking dinner jazz pop", 0xFFBA5D07, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("בריאות ורוגע", "Wellness", "wellness meditation calm", 0xFF006450, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("אהבה ורומנטיקה", "Romance", "romantic love songs", 0xFF8C1932, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("ילדים ומשפחה", "Kids & Family", "kids family songs", 0xFF8D67AB, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("טלוויזיה וסרטים", "TV & Movies", "movie soundtrack theme", 0xFFAF2896, "מצב רוח ופעילות", "Mood & Activity"),
        BrowseCategory("אנימה", "Anime", "anime opening ost", 0xFFE91429, "מצב רוח ופעילות", "Mood & Activity")
    )

    init {
        loadSavedState()

        WidgetActionBridge.onPrev = { skipPrevious() }
        WidgetActionBridge.onPlayPause = { togglePlayPause() }
        WidgetActionBridge.onNext = { skipNext() }

        audioEngine.onProgressUpdate = { pos, dur, playing, buffering ->
            _uiState.update { state ->
                val track = state.currentTrack
                if (track != null && (track.title != lastWidgetTitle || playing != lastWidgetPlaying)) {
                    lastWidgetTitle = track.title
                    lastWidgetPlaying = playing
                    WidgetActionBridge.updateWidget(getApplication(), track.title, track.artist, playing)
                }
                val now = System.currentTimeMillis()
                val updatedStats = if (playing && !state.settings.privateSession && now - lastStatsTickMs >= 5000L) {
                    lastStatsTickMs = now
                    state.stats.copy(totalListeningMs = state.stats.totalListeningMs + 5000L).also { saveStats(it) }
                } else state.stats

                state.copy(
                    positionMs = pos,
                    durationMs = if (dur > 0) dur else track?.durationMs ?: 0L,
                    isPlaying = playing,
                    isPlayerBuffering = buffering,
                    stats = updatedStats
                )
            }
        }
        audioEngine.onTrackEnded = { handleTrackEnded() }
        audioEngine.onPlaybackError = { code -> handlePlaybackError(code) }
        refreshHomeContent()
    }

    private fun loadSavedState() {
        val schemaVer = prefs.getInt("schema_version", 1)
        val lang = runCatching { AppLanguage.valueOf(prefs.getString("lang", AppLanguage.HEBREW.name)!!) }.getOrDefault(AppLanguage.HEBREW)
        val source = runCatching { StreamSource.valueOf(prefs.getString("stream_source", StreamSource.YOUTUBE.name)!!) }.getOrDefault(StreamSource.YOUTUBE)
        val ytMode = runCatching { YouTubeEngineMode.valueOf(prefs.getString("yt_engine_mode", YouTubeEngineMode.AUTO.name)!!) }.getOrDefault(YouTubeEngineMode.AUTO)
        val wifiQ = runCatching { AudioQuality.valueOf(prefs.getString("wifi_q", AudioQuality.VERY_HIGH.name)!!) }.getOrDefault(AudioQuality.VERY_HIGH)
        val cellQ = runCatching { AudioQuality.valueOf(prefs.getString("cell_q", AudioQuality.HIGH.name)!!) }.getOrDefault(AudioQuality.HIGH)
        val dlQ = runCatching { AudioQuality.valueOf(prefs.getString("dl_q", AudioQuality.VERY_HIGH.name)!!) }.getOrDefault(AudioQuality.VERY_HIGH)
        val normLvl = runCatching { NormalizationLevel.valueOf(prefs.getString("norm_lvl", NormalizationLevel.NORMAL.name)!!) }.getOrDefault(NormalizationLevel.NORMAL)

        val eqStr = prefs.getString("eq_bands_10", "0,0,0,0,0,0,0,0,0,0") ?: "0,0,0,0,0,0,0,0,0,0"
        val eqBands = eqStr.split(",").mapNotNull { it.toIntOrNull() }.let { if (it.size == 10) it else List(10) { 0 } }

        val settings = AppSettings(
            language = lang,
            showCreateNavButton = prefs.getBoolean("show_create_nav", true),
            showUnplayableSongs = prefs.getBoolean("show_unplayable", true),
            streamSource = source,
            ytEngineMode = ytMode,
            wifiQuality = wifiQ,
            cellularQuality = cellQ,
            downloadQuality = dlQ,
            autoAdjustQuality = prefs.getBoolean("auto_adj_q", true),
            subsonicUrl = prefs.getString("subsonic_url", "") ?: "",
            subsonicUser = prefs.getString("subsonic_user", "") ?: "",
            subsonicPass = prefs.getString("subsonic_pass", "") ?: "",
            geminiApiKey = prefs.getString("gemini_key", "") ?: "",
            normalizeVolume = prefs.getBoolean("norm_vol", true),
            normalizationLevel = normLvl,
            gaplessPlayback = prefs.getBoolean("gapless", true),
            automixEnabled = prefs.getBoolean("automix", false),
            crossfadeSeconds = prefs.getInt("crossfade", 0),
            monoAudio = prefs.getBoolean("mono_audio", false),
            audioBalance = prefs.getFloat("audio_balance", 0f),
            autoplayEnabled = prefs.getBoolean("autoplay", true),
            playbackSpeed = prefs.getFloat("playback_speed", 1.0f),
            audioOnlyPodcasts = prefs.getBoolean("audio_only_pod", true),
            eqEnabled = prefs.getBoolean("eq_enabled", false),
            eqPresetIndex = prefs.getInt("eq_preset", 0),
            eqBands = eqBands,
            canvasEnabled = prefs.getBoolean("canvas", true),
            autoVideoOnWifi = prefs.getBoolean("auto_video", false),
            dataSaver = prefs.getBoolean("data_saver", false),
            downloadUsingCellular = prefs.getBoolean("dl_cellular", true),
            offlineMode = prefs.getBoolean("offline_mode", false),
            allowExplicit = prefs.getBoolean("allow_explicit", true),
            privateSession = prefs.getBoolean("private_session", false),
            shareListeningActivity = prefs.getBoolean("share_activity", true),
            showRecentlyPlayedArtists = prefs.getBoolean("show_recent_artists", true),
            notifyNewReleases = prefs.getBoolean("notify_rel", true),
            notifyConcerts = prefs.getBoolean("notify_conc", true),
            notifyPodcasts = prefs.getBoolean("notify_pod", true),
            carModeOptimized = prefs.getBoolean("car_mode", false)
        )
        audioEngine.applySettings(settings)

        // Upgrade previously saved artwork URLs to crisp 1080p HD on load
        var liked = decodeTracks(prefs.getString("liked_tracks", "[]") ?: "[]").map { it.copy(artworkUrl = upgradeArtworkUrlGlobal(it.artworkUrl)) }
        var recent = decodeTracks(prefs.getString("recent_tracks", "[]") ?: "[]").map { it.copy(artworkUrl = upgradeArtworkUrlGlobal(it.artworkUrl)) }
        val playlists = decodePlaylists(prefs.getString("playlists", "[]") ?: "[]").map { pl ->
            pl.copy(
                coverUrl = upgradeArtworkUrlGlobal(pl.coverUrl),
                tracks = pl.tracks.map { it.copy(artworkUrl = upgradeArtworkUrlGlobal(it.artworkUrl)) }
            )
        }
        val deleted = decodeDeletedPlaylists(prefs.getString("deleted_playlists", "[]") ?: "[]")
        val folders = decodeFolders(prefs.getString("folders", "[]") ?: "[]")
        val hidden = prefs.getStringSet("hidden_tracks", emptySet()) ?: emptySet()
        val excluded = prefs.getStringSet("excluded_artists", emptySet()) ?: emptySet()
        val searchHist = decodeStringList(prefs.getString("search_history", "[]") ?: "[]")
        val friends = decodeStringList(prefs.getString("friends", "[\"עומר\",\"נועה\",\"דניאל\"]") ?: "[]")
        val msgs = decodeMessages(prefs.getString("messages", "[]") ?: "[]")
        val stats = decodeStats(prefs.getString("stats", "{}") ?: "{}")

        if (schemaVer < 6) {
            recent = recent.filter { it.isPodcast || it.durationMs <= 600_000L }
            liked = liked.filter { it.isPodcast || it.durationMs <= 600_000L }
            prefs.edit()
                .putInt("schema_version", 6)
                .putString("recent_tracks", encodeTracks(recent))
                .putString("liked_tracks", encodeTracks(liked))
                .putString("playlists", encodePlaylists(playlists))
                .apply()
        }

        _uiState.update {
            it.copy(
                settings = settings, likedTracks = liked, recentTracks = recent, playlists = playlists,
                deletedPlaylists = deleted, folders = folders, hiddenTrackIds = hidden,
                excludedArtistsFromTaste = excluded, searchHistory = searchHist,
                followedFriends = friends, messages = msgs, stats = stats,
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
        homeJob?.cancel()
        homeJob = viewModelScope.launch {
            val s = _uiState.value.settings
            if (s.offlineMode) {
                buildOfflineBackupAndSpecialMixes()
                return@launch
            }
            _uiState.update { it.copy(isLoadingHome = true) }
            val isPod = _uiState.value.homeFilter == HomeFilter.PODCASTS
            val isBook = _uiState.value.homeFilter == HomeFilter.AUDIOBOOKS
            val (dayTitle, dayQuery) = computeDaylistMeta()

            val dMix1 = async { runCatching { repo.searchTracks(if (isBook) "best audiobook" else if (isPod) "פודקאסט ישראלי" else "להיטים ישראליים", s, isPod, isBook) }.getOrDefault(emptyList()) }
            val dMix2 = async { runCatching { repo.searchTracks(if (isBook) "science fiction audiobook" else if (isPod) "פודקאסט מדע וטכנולוגיה" else "The Weeknd Dua Lipa", s, isPod, isBook) }.getOrDefault(emptyList()) }
            val dMix3 = async { runCatching { repo.searchTracks(if (isBook) "self help audiobook" else if (isPod) "tech podcast" else "rap hip hop hits", s, isPod, isBook) }.getOrDefault(emptyList()) }
            val dDiscover = async { runCatching { repo.searchTracks(if (isBook) "history audiobook" else if (isPod) "history podcast" else "indie pop alternative", s, isPod, isBook) }.getOrDefault(emptyList()) }
            val dRadar = async { runCatching { repo.searchTracks(if (isBook) "mystery audiobook" else if (isPod) "comedy podcast" else "new pop releases", s, isPod, isBook) }.getOrDefault(emptyList()) }
            val dDaylist = async { runCatching { repo.searchTracks(if (isBook) "classic audiobook" else if (isPod) "פודקאסט סיפורים" else dayQuery, s, isPod, isBook) }.getOrDefault(emptyList()) }

            val mix1 = filterByTaste(dMix1.await()).take(12)
            val mix2 = filterByTaste(dMix2.await()).take(12)
            val mix3 = filterByTaste(dMix3.await()).take(12)
            val disc = filterByTaste(dDiscover.await()).take(12)
            val radar = filterByTaste(dRadar.await()).take(12)
            val dayT = filterByTaste(dDaylist.await()).take(12)

            val mixes = buildList {
                buildOfflineBackupPlaylist()?.let { add(it) }
                buildOnRepeatPlaylist()?.let { add(it) }
                buildRepeatRewindPlaylist()?.let { add(it) }
                if (mix1.isNotEmpty()) add(Playlist("home_daily_1", "Daily Mix 1 • ישראלי", "Sonify", "הלהיטים המובילים בעברית", mix1.first().artworkUrl, tracks = mix1, isRemote = true))
                if (mix2.isNotEmpty()) add(Playlist("home_daily_2", "Daily Mix 2 • פופ עולמי", "Sonify", "מוזיקה בינלאומית מובילה", mix2.first().artworkUrl, tracks = mix2, isRemote = true))
                if (mix3.isNotEmpty()) add(Playlist("home_daily_3", "Daily Mix 3 • היפ-הופ", "Sonify", "ראפ ואנרגיה גבוהה", mix3.first().artworkUrl, tracks = mix3, isRemote = true))
                if (disc.isNotEmpty()) add(Playlist("home_discover", "Discover Weekly", "Sonify", "גילוי שבועי מותאם אישית", disc.first().artworkUrl, tracks = disc, isRemote = true))
                if (radar.isNotEmpty()) add(Playlist("home_radar", "Release Radar", "Sonify", "שחרורים חדשים", radar.first().artworkUrl, tracks = radar, isRemote = true))
            }

            val dayPlaylist = if (dayT.isNotEmpty()) Playlist("home_daylist", dayTitle, "Sonify", "פלייליסט דינמי שמתעדכן לפי השעה", dayT.first().artworkUrl, tracks = dayT, isRemote = true) else null

            _uiState.update { state ->
                state.copy(
                    isLoadingHome = false,
                    homeMixPlaylists = mixes,
                    daylistPlaylist = dayPlaylist,
                    trendingSongs = (mix1.take(5) + mix2.take(5)).distinctBy { it.id },
                    recentTracks = if (state.recentTracks.isEmpty()) mix1.take(8) else state.recentTracks
                )
            }
        }
    }

    private fun filterByTaste(tracks: List<Track>): List<Track> {
        val excl = _uiState.value.excludedArtistsFromTaste
        val hidden = _uiState.value.hiddenTrackIds
        return tracks.filterNot { it.id in hidden || excl.any { a -> it.artist.contains(a, true) } }
    }

    private fun buildOfflineBackupPlaylist(): Playlist? {
        val state = _uiState.value
        val offlineIds = state.offlineTrackIds
        val offlineTracks = (state.recentTracks + state.likedTracks + state.queue)
            .distinctBy { it.id }
            .filter { it.id in offlineIds || it.source == StreamSource.LOCAL }
        if (offlineTracks.isEmpty()) return null
        return Playlist("offline_backup", "Offline Backup • גיבוי אוטומטי", "Sonify", "זמין ללא אינטרנט (${offlineTracks.size} שירים)", offlineTracks.first().artworkUrl, customCoverBgHex = 0xFF006450, tracks = offlineTracks, isRemote = true)
    }

    private fun buildOnRepeatPlaylist(): Playlist? {
        val state = _uiState.value
        val pool = (state.recentTracks + state.likedTracks).distinctBy { it.id }
        val sorted = pool.sortedByDescending { state.stats.trackPlayCounts[it.id] ?: 0 }.take(12)
        if (sorted.size < 3) return null
        return Playlist("on_repeat", "On Repeat • חרשת עליהם", "Sonify", "השירים שניגנת הכי הרבה", sorted.first().artworkUrl, customCoverBgHex = 0xFFE8115B, tracks = sorted, isRemote = true)
    }

    private fun buildRepeatRewindPlaylist(): Playlist? {
        val state = _uiState.value
        val pool = state.likedTracks.reversed().take(12)
        if (pool.size < 3) return null
        return Playlist("repeat_rewind", "Repeat Rewind • נוסטלגיה", "Sonify", "שירים שאהבת בעבר", pool.first().artworkUrl, customCoverBgHex = 0xFF1E3264, tracks = pool, isRemote = true)
    }

    private fun buildOfflineBackupAndSpecialMixes() {
        _uiState.update { it.copy(isLoadingHome = false, homeMixPlaylists = listOfNotNull(buildOfflineBackupPlaylist(), buildOnRepeatPlaylist()), daylistPlaylist = null) }
    }

    // ---------- Navigation ----------
    fun selectTab(tab: MainTab) = _uiState.update { it.copy(selectedTab = tab, showSettings = false, settingsSection = SettingsSection.ROOT, openedPlaylist = null) }
    fun setShowSettings(show: Boolean) = _uiState.update { it.copy(showSettings = show, settingsSection = SettingsSection.ROOT) }
    fun setSettingsSection(section: SettingsSection) = _uiState.update { it.copy(settingsSection = section) }
    fun setShowNowPlaying(show: Boolean) = _uiState.update { it.copy(showNowPlaying = show) }
    fun setShowCreateSheet(show: Boolean) = _uiState.update { it.copy(showCreateSheet = show) }
    fun setShowDevicesSheet(show: Boolean) = _uiState.update { it.copy(showDevicesSheet = show) }
    fun setShowStatsSheet(show: Boolean) = _uiState.update { it.copy(showStatsSheet = show) }
    fun setShowMessagesSheet(show: Boolean) = _uiState.update { it.copy(showMessagesSheet = show) }
    fun setShowHistorySheet(show: Boolean) = _uiState.update { it.copy(showHistorySheet = show) }
    fun setShowImportSheet(show: Boolean) = _uiState.update { it.copy(showImportSheet = show) }
    fun setShowRecoverSheet(show: Boolean) = _uiState.update { it.copy(showRecoverSheet = show) }
    fun setShowCodeFor(item: Pair<String, String>?) = _uiState.update { it.copy(showCodeSheetFor = item) }
    fun setEditingCoverPlaylist(id: String?) = _uiState.update { it.copy(editingCoverPlaylistId = id) }
    fun openPlaylist(playlist: Playlist?) = _uiState.update { it.copy(openedPlaylist = playlist, showSettings = false) }
    fun openTrackOptions(track: Track?) = _uiState.update { it.copy(trackForOptions = track) }
    fun clearStatus() = _uiState.update { it.copy(statusMessage = null) }

    fun setHomeFilter(filter: HomeFilter) { _uiState.update { it.copy(homeFilter = filter) }; refreshHomeContent() }
    fun setSearchMode(mode: SearchMode, triggerSearch: Boolean = true) {
        _uiState.update { it.copy(searchMode = mode) }
        if (triggerSearch && _uiState.value.searchQuery.isNotBlank()) performSearch()
    }
    fun setLibraryFilter(filter: LibraryFilter) = _uiState.update { it.copy(libraryFilter = filter, selectedFolderId = null) }
    fun setLibrarySort(sort: LibrarySort) = _uiState.update { it.copy(librarySort = sort) }
    fun toggleLibraryGrid() = _uiState.update { it.copy(libraryGridView = !it.libraryGridView) }
    fun selectFolder(id: String?) = _uiState.update { it.copy(selectedFolderId = id) }

    fun updateSettings(newSettings: AppSettings) {
        val sourceChanged = newSettings.streamSource != _uiState.value.settings.streamSource || newSettings.offlineMode != _uiState.value.settings.offlineMode
        _uiState.update { it.copy(settings = newSettings) }
        audioEngine.applySettings(newSettings)
        prefs.edit()
            .putString("lang", newSettings.language.name)
            .putBoolean("show_create_nav", newSettings.showCreateNavButton)
            .putBoolean("show_unplayable", newSettings.showUnplayableSongs)
            .putString("stream_source", newSettings.streamSource.name)
            .putString("yt_engine_mode", newSettings.ytEngineMode.name)
            .putString("wifi_q", newSettings.wifiQuality.name)
            .putString("cell_q", newSettings.cellularQuality.name)
            .putString("dl_q", newSettings.downloadQuality.name)
            .putBoolean("auto_adj_q", newSettings.autoAdjustQuality)
            .putString("subsonic_url", newSettings.subsonicUrl)
            .putString("subsonic_user", newSettings.subsonicUser)
            .putString("subsonic_pass", newSettings.subsonicPass)
            .putString("gemini_key", newSettings.geminiApiKey)
            .putBoolean("norm_vol", newSettings.normalizeVolume)
            .putString("norm_lvl", newSettings.normalizationLevel.name)
            .putBoolean("gapless", newSettings.gaplessPlayback)
            .putBoolean("automix", newSettings.automixEnabled)
            .putInt("crossfade", newSettings.crossfadeSeconds)
            .putBoolean("mono_audio", newSettings.monoAudio)
            .putFloat("audio_balance", newSettings.audioBalance)
            .putBoolean("autoplay", newSettings.autoplayEnabled)
            .putFloat("playback_speed", newSettings.playbackSpeed)
            .putBoolean("audio_only_pod", newSettings.audioOnlyPodcasts)
            .putBoolean("eq_enabled", newSettings.eqEnabled)
            .putInt("eq_preset", newSettings.eqPresetIndex)
            .putString("eq_bands_10", newSettings.eqBands.joinToString(","))
            .putBoolean("canvas", newSettings.canvasEnabled)
            .putBoolean("auto_video", newSettings.autoVideoOnWifi)
            .putBoolean("data_saver", newSettings.dataSaver)
            .putBoolean("dl_cellular", newSettings.downloadUsingCellular)
            .putBoolean("offline_mode", newSettings.offlineMode)
            .putBoolean("allow_explicit", newSettings.allowExplicit)
            .putBoolean("private_session", newSettings.privateSession)
            .putBoolean("share_activity", newSettings.shareListeningActivity)
            .putBoolean("show_recent_artists", newSettings.showRecentlyPlayedArtists)
            .putBoolean("notify_rel", newSettings.notifyNewReleases)
            .putBoolean("notify_conc", newSettings.notifyConcerts)
            .putBoolean("notify_pod", newSettings.notifyPodcasts)
            .putBoolean("car_mode", newSettings.carModeOptimized)
            .apply()
        if (sourceChanged) refreshHomeContent()
    }

    // ---------- Storage, Privacy & Taste ----------
    fun clearAppCache() {
        repo.clearCache()
        _uiState.update { it.copy(storageRefreshKey = it.storageRefreshKey + 1, statusMessage = "המטמון נוקה (ההורדות נשמרו)") }
    }

    fun removeAllDownloads() {
        repo.deleteAllOfflineTracks()
        _uiState.update { it.copy(offlineTrackIds = emptySet(), storageRefreshKey = it.storageRefreshKey + 1, statusMessage = "כל ההורדות נמחקו מהמכשיר") }
    }

    fun clearRecentHistory() {
        prefs.edit().putString("recent_tracks", "[]").apply()
        _uiState.update { it.copy(recentTracks = emptyList(), statusMessage = "היסטוריית הניגון נוקתה") }
        refreshHomeContent()
    }

    fun toggleHideTrack(track: Track) {
        val cur = _uiState.value.hiddenTrackIds
        val updated = if (track.id in cur) cur - track.id else cur + track.id
        prefs.edit().putStringSet("hidden_tracks", updated).apply()
        _uiState.update { it.copy(hiddenTrackIds = updated, trackForOptions = null, statusMessage = if (track.id in updated) "השיר הוסתר — ידולג אוטומטית" else "השיר הוחזר להשמעה") }
    }

    fun clearAllHiddenTracks() {
        prefs.edit().putStringSet("hidden_tracks", emptySet()).apply()
        _uiState.update { it.copy(hiddenTrackIds = emptySet(), statusMessage = "כל השירים המוסתרים שוחזרו") }
    }

    fun toggleExcludeArtistFromTaste(artist: String) {
        val cur = _uiState.value.excludedArtistsFromTaste
        val updated = if (artist in cur) cur - artist else cur + artist
        prefs.edit().putStringSet("excluded_artists", updated).apply()
        _uiState.update { it.copy(excludedArtistsFromTaste = updated, statusMessage = if (artist in updated) "$artist הוחרג מפרופיל הטעם" else "$artist הוחזר לפרופיל הטעם") }
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

    // ---------- Search (Race-Condition Free) ----------
    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query, hasSearched = if (query.isBlank()) false else it.hasSearched) }
    }

    fun removeSearchHistoryItem(q: String) {
        val updated = _uiState.value.searchHistory.filterNot { it == q }
        prefs.edit().putString("search_history", encodeStringList(updated)).apply()
        _uiState.update { it.copy(searchHistory = updated) }
    }

    fun performSearch(query: String = _uiState.value.searchQuery, modeOverride: SearchMode? = null) {
        val clean = query.trim()
        if (clean.isBlank()) return
        val activeMode = modeOverride ?: _uiState.value.searchMode
        val updatedHist = (listOf(clean) + _uiState.value.searchHistory.filterNot { it.equals(clean, true) }).take(10)
        prefs.edit().putString("search_history", encodeStringList(updatedHist)).apply()
        _uiState.update { it.copy(searchQuery = clean, searchMode = activeMode, searchHistory = updatedHist, isSearching = true, hasSearched = true) }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            when (activeMode) {
                SearchMode.PLAYLISTS -> {
                    val pls = repo.searchPlaylists(clean)
                    _uiState.update { it.copy(searchPlaylists = pls, isSearching = false) }
                }
                SearchMode.PODCASTS -> {
                    val res = repo.searchTracks(clean, _uiState.value.settings, podcastsOnly = true)
                    _uiState.update { it.copy(searchResults = res, isSearching = false) }
                }
                SearchMode.AUDIOBOOKS -> {
                    val res = repo.searchTracks(clean, _uiState.value.settings, audiobooksOnly = true)
                    _uiState.update { it.copy(searchResults = res, isSearching = false) }
                }
                SearchMode.SONGS -> {
                    val res = repo.searchTracks(clean, _uiState.value.settings)
                    _uiState.update { it.copy(searchResults = res, isSearching = false) }
                }
            }
        }
    }

    // ---------- Playback & Radio ----------
    fun startSongOrArtistRadio(track: Track) {
        _uiState.update { it.copy(trackForOptions = null, statusMessage = "מכין רדיו מבוסס על ${track.title}...") }
        viewModelScope.launch {
            val related = repo.searchTracks("${track.artist} ${track.title}", _uiState.value.settings)
                .filterNot { it.id == track.id || it.id in _uiState.value.hiddenTrackIds }
                .take(14)
            val radioList = listOf(track) + related
            playTrack(track, radioList)
            _uiState.update { it.copy(showNowPlaying = true, statusMessage = "רדיו ${track.artist} (${radioList.size} שירים)") }
        }
    }

    fun playTrack(track: Track, list: List<Track> = listOf(track), openVideoImmediately: Boolean = false, startAtMs: Long = 0L) {
        val state = _uiState.value
        val isOfflineReady = track.id in state.offlineTrackIds || track.source == StreamSource.LOCAL
        if (state.settings.offlineMode && !isOfflineReady) {
            _uiState.update { it.copy(statusMessage = "מצב אופליין פעיל — השיר הזה לא הורד למכשיר") }
            return
        }

        retryAttemptForCurrent = 0
        val cleanList = list.filterNot { it.id in state.hiddenTrackIds && it.id != track.id }
        val finalQueue = if (cleanList.any { it.id == track.id }) cleanList else listOf(track) + cleanList
        val idx = finalQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        val updatedRecent = if (!state.settings.privateSession) {
            (listOf(track) + state.recentTracks.filterNot { it.id == track.id }).take(25).also {
                prefs.edit().putString("recent_tracks", encodeTracks(it)).apply()
            }
        } else state.recentTracks

        val updatedStats = if (!state.settings.privateSession) {
            val tCounts = state.stats.trackPlayCounts.toMutableMap()
            tCounts[track.id] = (tCounts[track.id] ?: 0) + 1
            val aCounts = state.stats.artistPlayCounts.toMutableMap()
            aCounts[track.artist] = (aCounts[track.artist] ?: 0) + 1
            state.stats.copy(trackPlayCounts = tCounts, artistPlayCounts = aCounts).also { saveStats(it) }
        } else state.stats

        val settings = state.settings
        val useWebDirect = track.source == StreamSource.YOUTUBE &&
            settings.ytEngineMode == YouTubeEngineMode.WEB_DIRECT &&
            !isOfflineReady

        val autoVid = !settings.dataSaver && !(track.isPodcast && settings.audioOnlyPodcasts) && (openVideoImmediately || (useWebDirect && settings.autoVideoOnWifi))

        _uiState.update {
            it.copy(
                queue = finalQueue, currentIndex = idx, currentTrack = track, recentTracks = updatedRecent,
                stats = updatedStats, resolvedStreams = null, isUsingWebEngine = useWebDirect,
                isResolvingStream = !useWebDirect, isPlayerBuffering = useWebDirect, isVideoMode = autoVid,
                positionMs = startAtMs, durationMs = track.durationMs, lyrics = emptyList(), isLoadingLyrics = true
            )
        }

        WidgetActionBridge.updateWidget(getApplication(), track.title, track.artist, true)
        extractDynamicColor(track.artworkUrl)

        if (useWebDirect) {
            audioEngine.playViaWebEngine(track)
            if (startAtMs > 0L) audioEngine.seekTo(startAtMs)
        } else {
            resolveAndStartTrack(track, attempt = 0, forceVideo = openVideoImmediately, startAtMs = startAtMs)
        }

        viewModelScope.launch {
            val lyrics = repo.fetchSyncedLyrics(track.artist, track.title)
            _uiState.update { it.copy(lyrics = lyrics, isLoadingLyrics = false) }
        }
    }

    private fun activateWebFallback(track: Track, forceVideo: Boolean = false) {
        val s = _uiState.value.settings
        val allowAutoVid = !s.dataSaver && !(track.isPodcast && s.audioOnlyPodcasts) && (forceVideo || s.autoVideoOnWifi)
        _uiState.update {
            it.copy(isUsingWebEngine = true, isResolvingStream = false, isPlayerBuffering = true, isVideoMode = allowAutoVid || it.isVideoMode)
        }
        audioEngine.playViaWebEngine(track)
    }

    private fun resolveAndStartTrack(track: Track, attempt: Int, forceVideo: Boolean = false, startAtMs: Long = 0L) {
        viewModelScope.launch {
            val settings = _uiState.value.settings
            val resolved = repo.resolveStreams(track, settings, attemptIndex = attempt)
            if (resolved == null) {
                if (track.source == StreamSource.YOUTUBE && settings.ytEngineMode != YouTubeEngineMode.EXOPLAYER_ONLY && !settings.offlineMode) {
                    activateWebFallback(track, forceVideo)
                } else {
                    _uiState.update { it.copy(isResolvingStream = false, isPlayerBuffering = false, statusMessage = "לא נמצא זרם זמין") }
                }
                return@launch
            }
            val startInVideo = !settings.dataSaver && !(track.isPodcast && settings.audioOnlyPodcasts) && (forceVideo || settings.autoVideoOnWifi) && resolved.hasVideoClip
            audioEngine.playResolved(resolved, track, videoMode = startInVideo, startPositionMs = startAtMs)
            _uiState.update { it.copy(isUsingWebEngine = false, resolvedStreams = resolved, isResolvingStream = false, isVideoMode = startInVideo) }
        }
    }

    private fun handlePlaybackError(code: String) {
        val state = _uiState.value
        val track = state.currentTrack ?: return
        if (track.source == StreamSource.YOUTUBE && !state.settings.offlineMode) {
            if (retryAttemptForCurrent < 5) {
                retryAttemptForCurrent++
                _uiState.update { it.copy(isResolvingStream = true) }
                resolveAndStartTrack(track, attempt = retryAttemptForCurrent, forceVideo = state.isVideoMode)
            } else if (state.settings.ytEngineMode != YouTubeEngineMode.EXOPLAYER_ONLY) {
                activateWebFallback(track, forceVideo = state.isVideoMode)
            } else {
                _uiState.update { it.copy(isResolvingStream = false, isPlayerBuffering = false, statusMessage = "שגיאת ניגון ($code)") }
            }
        } else {
            _uiState.update { it.copy(isResolvingStream = false, isPlayerBuffering = false, statusMessage = "שגיאת ניגון ($code)") }
        }
    }

    // FIXED: CachePolicy.DISABLED prevents overwriting Coil's HD image cache with a tiny 128px thumbnail!
    private fun extractDynamicColor(artworkUrl: String) {
        if (artworkUrl.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val request = ImageRequest.Builder(app)
                    .data(upgradeArtworkUrlGlobal(artworkUrl))
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .allowHardware(false)
                    .size(96)
                    .build()
                val bmp = ((app.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@launch
                val palette = Palette.from(bmp).generate()
                val picked = palette.getDarkVibrantColor(palette.getDarkMutedColor(palette.getDominantColor(0xFF1F3B33.toInt())))
                _uiState.update { it.copy(dynamicAccentColor = (0xFF000000L or (picked.toLong() and 0x00FFFFFFL))) }
            }
        }
    }

    fun toggleVideoClipMode() {
        val state = _uiState.value
        val track = state.currentTrack ?: return
        if (state.isUsingWebEngine) { _uiState.update { it.copy(isVideoMode = !it.isVideoMode) }; return }
        val resolved = state.resolvedStreams ?: return
        if (!state.isVideoMode) {
            if (!resolved.hasVideoClip) { _uiState.update { it.copy(statusMessage = "אין קליפ זמין לשיר זה") }; return }
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
                if (current.source == StreamSource.YOUTUBE && _uiState.value.settings.ytEngineMode != YouTubeEngineMode.EXOPLAYER_ONLY) activateWebFallback(current)
                else { retryAttemptForCurrent = 0; _uiState.update { it.copy(isResolvingStream = true) }; resolveAndStartTrack(current, 0) }
            }
        })
    }

    fun seekTo(ms: Long) { _uiState.update { it.copy(positionMs = ms.coerceAtLeast(0L)) }; audioEngine.seekTo(ms) }

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
        } else if (state.settings.autoplayEnabled && !state.settings.offlineMode) {
            val current = state.currentTrack ?: return
            _uiState.update { it.copy(statusMessage = "Autoplay • טוען שיר דומה...") }
            viewModelScope.launch {
                val related = repo.searchTracks(current.artist, _uiState.value.settings)
                    .filterNot { cand -> _uiState.value.queue.any { it.id == cand.id } || cand.id in _uiState.value.hiddenTrackIds }
                    .take(5)
                if (related.isNotEmpty()) playTrack(related.first(), _uiState.value.queue + related)
            }
        }
    }

    fun skipPrevious() {
        val state = _uiState.value
        if (state.positionMs > 3000L) { seekTo(0L); return }
        if (state.queue.isEmpty()) return
        playTrack(state.queue[(state.currentIndex - 1).coerceAtLeast(0)], state.queue)
    }

    private fun handleTrackEnded() {
        val state = _uiState.value
        if (state.repeatMode == RepeatMode.ONE) {
            seekTo(0L)
            if (state.isUsingWebEngine) audioEngine.webEngine.play() else audioEngine.player.play()
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
                .take(4).map { it.copy(isSmartInjected = true) }
            if (similar.isNotEmpty()) _uiState.update { it.copy(queue = it.queue + similar) }
        }
    }

    fun cycleRepeatMode() {
        _uiState.update {
            val next = when (it.repeatMode) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF }
            val msg = when (next) { RepeatMode.OFF -> "חזרה כובתה"; RepeatMode.ALL -> "חזור על הכל"; RepeatMode.ONE -> "חזור על שיר אחד" }
            it.copy(repeatMode = next, statusMessage = msg)
        }
    }

    fun moveQueueItem(from: Int, to: Int) {
        val list = _uiState.value.queue.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        _uiState.update { it.copy(queue = list, currentIndex = list.indexOfFirst { t -> t.id == it.currentTrack?.id }) }
    }

    fun removeFromQueue(index: Int) {
        val list = _uiState.value.queue.toMutableList()
        if (index !in list.indices || index == _uiState.value.currentIndex) return
        list.removeAt(index)
        val newIdx = if (index < _uiState.value.currentIndex) _uiState.value.currentIndex - 1 else _uiState.value.currentIndex
        _uiState.update { it.copy(queue = list, currentIndex = newIdx) }
    }

    fun addToQueue(track: Track) { _uiState.update { it.copy(queue = it.queue + track, statusMessage = "נוסף לתור ההשמעה") } }

    fun toggleLike(track: Track) {
        val cur = _uiState.value.likedTracks
        val exists = cur.any { it.id == track.id }
        val updated = if (exists) cur.filterNot { it.id == track.id } else listOf(track) + cur
        prefs.edit().putString("liked_tracks", encodeTracks(updated)).apply()
        _uiState.update { it.copy(likedTracks = updated, statusMessage = if (exists) "הוסר מהספרייה" else "נשמר בספרייה") }
    }

    // ---------- Offline Download with Live Progress % ----------
    fun downloadOffline(track: Track) {
        if (_uiState.value.offlineTrackIds.contains(track.id)) {
            repo.deleteOfflineTrack(track.id)
            _uiState.update { it.copy(offlineTrackIds = repo.getOfflineTrackIds(), storageRefreshKey = it.storageRefreshKey + 1, statusMessage = "הוסר מהורדות האופליין") }
            return
        }
        if (track.id in _uiState.value.downloadingIds) return
        _uiState.update {
            it.copy(
                downloadingIds = it.downloadingIds + track.id,
                downloadProgress = it.downloadProgress + (track.id to 1),
                statusMessage = "מוריד \"${track.title}\"..."
            )
        }
        viewModelScope.launch {
            val captured = audioEngine.getCapturedStreams(track.id)
            val (_, msg) = repo.downloadTrackOffline(track, _uiState.value.settings, captured) { pct ->
                _uiState.update { st -> st.copy(downloadProgress = st.downloadProgress + (track.id to pct)) }
            }
            _uiState.update {
                it.copy(
                    downloadingIds = it.downloadingIds - track.id,
                    downloadProgress = it.downloadProgress - track.id,
                    offlineTrackIds = repo.getOfflineTrackIds(),
                    storageRefreshKey = it.storageRefreshKey + 1,
                    statusMessage = msg
                )
            }
        }
    }

    // ---------- Playlists, Pinning, Folders, Cover Art, Recovery & Import ----------
    private fun savePlaylists(updated: List<Playlist>) {
        prefs.edit().putString("playlists", encodePlaylists(updated)).apply()
        _uiState.update { state ->
            val refreshedOpen = state.openedPlaylist?.let { open -> updated.firstOrNull { it.id == open.id } ?: open }
            state.copy(playlists = updated, openedPlaylist = refreshedOpen)
        }
    }

    fun openRemotePlaylist(remote: RemotePlaylist) {
        _uiState.update { it.copy(isLoadingPlaylist = true, openedPlaylist = Playlist(remote.id, remote.title, remote.subtitle, coverUrl = remote.artworkUrl, isRemote = true)) }
        viewModelScope.launch {
            val tracks = repo.fetchPlaylistTracks(remote.id)
            _uiState.update { state ->
                val cur = state.openedPlaylist
                if (cur != null && cur.id == remote.id) state.copy(isLoadingPlaylist = false, openedPlaylist = cur.copy(tracks = tracks))
                else state.copy(isLoadingPlaylist = false)
            }
        }
    }

    fun saveRemotePlaylistToLibrary(pl: Playlist) {
        val saved = pl.copy(id = UUID.randomUUID().toString(), isRemote = false)
        savePlaylists(listOf(saved) + _uiState.value.playlists)
        _uiState.update { it.copy(openedPlaylist = saved, statusMessage = "הפלייליסט נשמר בספרייה!") }
    }

    fun createPlaylist(name: String, initialTracks: List<Track> = emptyList(), isCollaborative: Boolean = false) {
        val clean = name.trim()
        if (clean.isBlank()) { _uiState.update { it.copy(statusMessage = "יש להזין שם לפלייליסט") }; return }
        val pl = Playlist(name = clean, coverUrl = initialTracks.firstOrNull()?.artworkUrl ?: "", tracks = initialTracks, isCollaborative = isCollaborative, collaborators = if (isCollaborative) listOf("אתה") else emptyList())
        savePlaylists(listOf(pl) + _uiState.value.playlists)
        _uiState.update { it.copy(showCreateSheet = false, openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = "הפלייליסט \"$clean\" נוצר") }
    }

    fun createFolder(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) { _uiState.update { it.copy(statusMessage = "יש להזין שם תיקייה") }; return }
        val updated = listOf(PlaylistFolder(name = clean)) + _uiState.value.folders
        prefs.edit().putString("folders", encodeFolders(updated)).apply()
        _uiState.update { it.copy(folders = updated, showCreateSheet = false, statusMessage = "התיקייה \"$clean\" נוצרה") }
    }

    fun assignPlaylistToFolder(playlistId: String, folderId: String?) {
        savePlaylists(_uiState.value.playlists.map { if (it.id == playlistId) it.copy(folderId = folderId) else it })
        _uiState.update { it.copy(statusMessage = "התיקייה עודכנה") }
    }

    fun togglePinPlaylist(playlistId: String) {
        val cur = _uiState.value.playlists
        val target = cur.firstOrNull { it.id == playlistId } ?: return
        if (!target.isPinned && cur.count { it.isPinned } >= 4) {
            _uiState.update { it.copy(statusMessage = "ניתן לנעוץ עד 4 פלייליסטים לראש הספרייה") }
            return
        }
        savePlaylists(cur.map { if (it.id == playlistId) it.copy(isPinned = !it.isPinned) else it })
        _uiState.update { it.copy(statusMessage = if (!target.isPinned) "ננעץ לראש הספרייה" else "הוסרה הנעיצה") }
    }

    fun togglePlaylistFlag(playlistId: String, flag: String) {
        var msg = ""
        val updated = _uiState.value.playlists.map { pl ->
            if (pl.id != playlistId) pl else when (flag) {
                "public" -> { msg = if (!pl.isPublic) "הפלייליסט הפך לציבורי" else "הפלייליסט הפך לפרטי"; pl.copy(isPublic = !pl.isPublic) }
                "collab" -> { msg = if (!pl.isCollaborative) "מצב שיתופי הופעל" else "מצב שיתופי כובה"; pl.copy(isCollaborative = !pl.isCollaborative) }
                "taste" -> { msg = if (!pl.excludeFromTaste) "הוחרג מפרופיל הטעם" else "הוחזר לפרופיל הטעם"; pl.copy(excludeFromTaste = !pl.excludeFromTaste) }
                "mix" -> { msg = if (!pl.isMixedMode) "מעברי Mix הופעלו לפלייליסט" else "מעברי Mix כובו"; pl.copy(isMixedMode = !pl.isMixedMode) }
                else -> pl
            }
        }
        savePlaylists(updated)
        if (msg.isNotBlank()) _uiState.update { it.copy(statusMessage = msg) }
    }

    fun updatePlaylistCover(playlistId: String, bgHex: Long, titleText: String, sticker: String) {
        savePlaylists(_uiState.value.playlists.map { pl ->
            if (pl.id == playlistId) pl.copy(customCoverBgHex = bgHex, customCoverText = titleText, customCoverSticker = sticker) else pl
        })
        _uiState.update { it.copy(editingCoverPlaylistId = null, statusMessage = "עטיפת הפלייליסט עודכנה!") }
    }

    // Supports both adding to checked playlists and removing from unchecked playlists!
    fun syncTrackPlaylists(selectedPlaylistIds: Set<String>, track: Track) {
        val updated = _uiState.value.playlists.map { pl ->
            val shouldHave = pl.id in selectedPlaylistIds
            val hasNow = pl.tracks.any { it.id == track.id }
            when {
                shouldHave && !hasNow -> pl.copy(tracks = pl.tracks + track, coverUrl = pl.coverUrl.ifBlank { track.artworkUrl })
                !shouldHave && hasNow -> pl.copy(tracks = pl.tracks.filterNot { it.id == track.id })
                else -> pl
            }
        }
        savePlaylists(updated)
        _uiState.update { it.copy(trackForOptions = null, statusMessage = "הפלייליסטים עודכנו (${selectedPlaylistIds.size})") }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        savePlaylists(_uiState.value.playlists.map { pl -> if (pl.id == playlistId) pl.copy(tracks = pl.tracks.filterNot { it.id == trackId }) else pl })
        _uiState.update { it.copy(statusMessage = "השיר הוסר") }
    }

    fun deletePlaylist(playlistId: String) {
        val target = _uiState.value.playlists.firstOrNull { it.id == playlistId } ?: return
        val newDeleted = (listOf(DeletedPlaylistRecord(target)) + _uiState.value.deletedPlaylists).take(15)
        prefs.edit().putString("deleted_playlists", encodeDeletedPlaylists(newDeleted)).apply()
        savePlaylists(_uiState.value.playlists.filterNot { it.id == playlistId })
        _uiState.update { it.copy(deletedPlaylists = newDeleted, openedPlaylist = null, statusMessage = "הפלייליסט נמחק (ניתן לשחזור מההגדרות)") }
    }

    fun recoverDeletedPlaylist(record: DeletedPlaylistRecord) {
        val newDeleted = _uiState.value.deletedPlaylists.filterNot { it.playlist.id == record.playlist.id }
        prefs.edit().putString("deleted_playlists", encodeDeletedPlaylists(newDeleted)).apply()
        savePlaylists(listOf(record.playlist) + _uiState.value.playlists)
        _uiState.update { it.copy(deletedPlaylists = newDeleted, statusMessage = "\"${record.playlist.name}\" שוחזר לספרייה!") }
    }

    fun importPlaylist(name: String, rawInput: String) {
        if (rawInput.isBlank()) { _uiState.update { it.copy(statusMessage = "הדבק קישור פלייליסט או רשימת שירים") }; return }
        _uiState.update { it.copy(showImportSheet = false, isBuildingAiPlaylist = true, statusMessage = "מייבא פלייליסט...") }
        viewModelScope.launch {
            val tracks = repo.importPlaylistFromTextOrUrl(rawInput, _uiState.value.settings)
            if (tracks.isEmpty()) {
                _uiState.update { it.copy(isBuildingAiPlaylist = false, statusMessage = "לא הצלחנו לייבא שירים מהטקסט/קישור") }
                return@launch
            }
            val pl = Playlist(name = name.trim().ifBlank { "פלייליסט מיובא" }, creator = "ייבוא", coverUrl = tracks.first().artworkUrl, tracks = tracks)
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update { it.copy(isBuildingAiPlaylist = false, openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = "יובאו ${tracks.size} שירים בהצלחה!") }
        }
    }

    fun createBlend(friendName: String) {
        val cleanFriend = friendName.trim().ifBlank { "חבר" }
        _uiState.update { it.copy(showCreateSheet = false, statusMessage = "מרכיב Blend עם $cleanFriend...") }
        viewModelScope.launch {
            val baseTracks = (_uiState.value.likedTracks.take(5) + _uiState.value.trendingSongs.take(5)).distinctBy { it.id }
            val tracks = if (baseTracks.size >= 4) baseTracks else repo.searchTracks("top pop hits", _uiState.value.settings).take(10)
            val match = (76..98).random()
            val pl = Playlist(name = "Blend • אתה + $cleanFriend", creator = "Blend", description = "התאמה מוזיקלית של $match%", coverUrl = tracks.firstOrNull()?.artworkUrl ?: "", customCoverBgHex = 0xFF9C27B0, tracks = tracks, isBlend = true, blendMatchPercent = match)
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update { it.copy(openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = "נוצר Blend עם $match% התאמה") }
        }
    }

    fun createAiPlaylist(prompt: String) {
        val clean = prompt.trim()
        if (clean.isBlank()) { _uiState.update { it.copy(statusMessage = "תאר איזה פלייליסט תרצה") }; return }
        _uiState.update { it.copy(showCreateSheet = false, isBuildingAiPlaylist = true, statusMessage = "AI Playlist בוחר שירים בודדים...") }
        viewModelScope.launch {
            val (title, tracks) = repo.buildAiPlaylist(clean, _uiState.value.settings)
            if (tracks.isEmpty()) { _uiState.update { it.copy(isBuildingAiPlaylist = false, statusMessage = "לא נמצאו שירים — נסה תיאור אחר") }; return@launch }
            val pl = Playlist(name = title, creator = "AI Playlist", description = "נבנה לפי: $clean", coverUrl = tracks.first().artworkUrl, tracks = tracks)
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update { it.copy(isBuildingAiPlaylist = false, openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = "נבנה פלייליסט עם ${tracks.size} שירים!") }
            playTrack(tracks.first(), tracks)
        }
    }

    fun startAiDj() {
        _uiState.update { it.copy(showCreateSheet = false, statusMessage = "AI DJ: מרכיב לך סט אישי...") }
        viewModelScope.launch {
            val existing = (_uiState.value.recentTracks.take(4) + _uiState.value.trendingSongs.take(8)).distinctBy { it.id }.shuffled()
            val mix = if (existing.size >= 4) existing else repo.searchTracks("להיטים מובילים", _uiState.value.settings).take(12)
            if (mix.isNotEmpty()) {
                playTrack(mix.first(), mix)
                _uiState.update { it.copy(showNowPlaying = true, statusMessage = "AI DJ מנגן עכשיו (${mix.size} שירים)") }
            }
        }
    }

    fun toggleJamSession() {
        val cur = _uiState.value.jamState
        if (cur.active) _uiState.update { it.copy(jamState = JamState(), statusMessage = "סשן ה-Jam הסתיים") }
        else {
            val code = UUID.randomUUID().toString().take(6).uppercase()
            _uiState.update { it.copy(jamState = JamState(true, code, listOf("אתה (מארח)")), showCreateSheet = false, statusMessage = "Jam פעיל • קוד: $code") }
        }
    }

    fun sendMessage(text: String, sharedTrack: Track? = null, timestampSec: Int? = null) {
        if (text.isBlank() && sharedTrack == null) return
        val msg = ChatMessage(sender = "אתה", text = text.trim(), sharedTrack = sharedTrack, trackTimestampSec = timestampSec)
        val updated = (_uiState.value.messages + msg).takeLast(50)
        prefs.edit().putString("messages", encodeMessages(updated)).apply()
        _uiState.update { it.copy(messages = updated, statusMessage = if (sharedTrack != null) "השיר נשלח בהודעות" else null) }
    }

    fun addFriend(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val updated = (listOf(clean) + _uiState.value.followedFriends.filterNot { it == clean }).take(20)
        prefs.edit().putString("friends", encodeStringList(updated)).apply()
        _uiState.update { it.copy(followedFriends = updated, statusMessage = "עוקב עכשיו אחרי $clean") }
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

    // ---------- JSON Serialization ----------
    private fun saveStats(st: ListeningStats) {
        val obj = JSONObject().apply {
            put("totalMs", st.totalListeningMs); put("tracks", JSONObject(st.trackPlayCounts)); put("artists", JSONObject(st.artistPlayCounts))
        }
        prefs.edit().putString("stats", obj.toString()).apply()
    }

    private fun decodeStats(raw: String): ListeningStats = runCatching {
        val o = JSONObject(raw)
        val tMap = mutableMapOf<String, Int>(); o.optJSONObject("tracks")?.let { jt -> jt.keys().forEach { k -> tMap[k] = jt.optInt(k) } }
        val aMap = mutableMapOf<String, Int>(); o.optJSONObject("artists")?.let { ja -> ja.keys().forEach { k -> aMap[k] = ja.optInt(k) } }
        ListeningStats(o.optLong("totalMs", 0L), tMap, aMap)
    }.getOrDefault(ListeningStats())

    private fun encodeStringList(list: List<String>): String = JSONArray().apply { list.forEach { put(it) } }.toString()
    private fun decodeStringList(raw: String): List<String> = runCatching { val a = JSONArray(raw); List(a.length()) { a.optString(it) }.filter { it.isNotBlank() } }.getOrDefault(emptyList())

    private fun encodeFolders(list: List<PlaylistFolder>): String = JSONArray().apply { list.forEach { put(JSONObject().apply { put("id", it.id); put("name", it.name) }) } }.toString()
    private fun decodeFolders(raw: String): List<PlaylistFolder> = runCatching { val a = JSONArray(raw); List(a.length()) { i -> a.optJSONObject(i) }.mapNotNull { o -> o?.let { PlaylistFolder(it.optString("id"), it.optString("name")) } } }.getOrDefault(emptyList())

    private fun encodeTracks(tracks: List<Track>): String = JSONArray().apply {
        tracks.forEach { t ->
            put(JSONObject().apply {
                put("id", t.id); put("title", t.title); put("artist", t.artist); put("album", t.album)
                put("art", t.artworkUrl); put("dur", t.durationMs); put("src", t.source.name)
                put("local", t.localUri ?: ""); put("pod", t.isPodcast); put("book", t.isAudiobook)
                put("exp", t.isExplicit); put("added", t.addedAt)
            })
        }
    }.toString()

    private fun decodeTracks(raw: String): List<Track> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                add(Track(o.optString("id"), o.optString("title"), o.optString("artist"), o.optString("album"), o.optString("art"), o.optLong("dur"), runCatching { StreamSource.valueOf(o.optString("src")) }.getOrDefault(StreamSource.YOUTUBE), o.optString("local").takeIf { it.isNotBlank() }, o.optBoolean("pod"), o.optBoolean("book"), o.optBoolean("exp"), addedAt = o.optLong("added", System.currentTimeMillis())))
            }
        }
    }.getOrDefault(emptyList())

    private fun encodePlaylistObj(p: Playlist): JSONObject = JSONObject().apply {
        put("id", p.id); put("name", p.name); put("creator", p.creator); put("desc", p.description)
        put("cover", p.coverUrl); put("bgHex", p.customCoverBgHex ?: -1L); put("covTxt", p.customCoverText)
        put("covStk", p.customCoverSticker); put("blend", p.isBlend); put("match", p.blendMatchPercent ?: -1)
        put("pub", p.isPublic); put("col", p.isCollaborative); put("pin", p.isPinned)
        put("excl", p.excludeFromTaste); put("mix", p.isMixedMode); put("folder", p.folderId ?: "")
        put("created", p.createdAt); put("tracks", encodeTracks(p.tracks))
    }

    private fun decodePlaylistObj(o: JSONObject): Playlist {
        val m = o.optInt("match", -1); val bg = o.optLong("bgHex", -1L)
        val trks = decodeTracks(o.optString("tracks", "[]"))
        return Playlist(o.optString("id"), o.optString("name"), o.optString("creator", "שלך"), o.optString("desc"), o.optString("cover").ifBlank { trks.firstOrNull()?.artworkUrl ?: "" }, if (bg != -1L) bg else null, o.optString("covTxt"), o.optString("covStk"), trks, o.optBoolean("blend"), if (m >= 0) m else null, isPublic = o.optBoolean("pub", true), isCollaborative = o.optBoolean("col"), isPinned = o.optBoolean("pin"), excludeFromTaste = o.optBoolean("excl"), isMixedMode = o.optBoolean("mix"), folderId = o.optString("folder").takeIf { it.isNotBlank() }, createdAt = o.optLong("created", System.currentTimeMillis()))
    }

    private fun encodePlaylists(list: List<Playlist>): String = JSONArray().apply { list.forEach { put(encodePlaylistObj(it)) } }.toString()
    private fun decodePlaylists(raw: String): List<Playlist> = runCatching { val arr = JSONArray(raw); buildList { for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(decodePlaylistObj(it)) } } }.getOrDefault(emptyList())

    private fun encodeDeletedPlaylists(list: List<DeletedPlaylistRecord>): String = JSONArray().apply { list.forEach { put(JSONObject().apply { put("pl", encodePlaylistObj(it.playlist)); put("delAt", it.deletedAt) }) } }.toString()
    private fun decodeDeletedPlaylists(raw: String): List<DeletedPlaylistRecord> = runCatching {
        val arr = JSONArray(raw); buildList { for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; o.optJSONObject("pl")?.let { add(DeletedPlaylistRecord(decodePlaylistObj(it), o.optLong("delAt"))) } } }
    }.getOrDefault(emptyList())

    private fun encodeMessages(list: List<ChatMessage>): String = JSONArray().apply {
        list.forEach { m -> put(JSONObject().apply { put("id", m.id); put("sender", m.sender); put("text", m.text); put("ts", m.timestampMs); put("tSec", m.trackTimestampSec ?: -1); m.sharedTrack?.let { put("track", encodeTracks(listOf(it))) } }) }
    }.toString()

    private fun decodeMessages(raw: String): List<ChatMessage> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tSec = o.optInt("tSec", -1)
                val trk = o.optString("track").takeIf { it.isNotBlank() }?.let { decodeTracks(it).firstOrNull() }
                add(ChatMessage(o.optString("id"), o.optString("sender", "אתה"), o.optString("text"), trk, if (tSec >= 0) tSec else null, o.optLong("ts")))
            }
        }
    }.getOrDefault(emptyList())
}
