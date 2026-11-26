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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs

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
    val openedArtist: ArtistPageState? = null,
    val openedArtistProfile: ArtistProfileDetails? = null,
    val isLoadingPlaylist: Boolean = false,
    val isExpandingPlaylist: Boolean = false,
    val trackForOptions: Track? = null,
    val homeFilter: HomeFilter = HomeFilter.ALL,
    val isLoadingHome: Boolean = false,
    val isLoadingMoreHome: Boolean = false,
    val isBuildingAiPlaylist: Boolean = false,
    val isAiDjActive: Boolean = false,
    val djVibeIndex: Int = 0,
    val searchMode: SearchMode = SearchMode.SONGS,
    val libraryFilter: LibraryFilter = LibraryFilter.ALL,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val librarySearchQuery: String = "",
    val libraryGridView: Boolean = false,
    val selectedFolderId: String? = null,
    val settings: AppSettings = AppSettings(),
    val storageRefreshKey: Int = 0,

    // Content & Infinite AI Discovery
    val recentTracks: List<Track> = emptyList(),
    val homeMixPlaylists: List<Playlist> = emptyList(),
    val readyYoutubePlaylists: List<RemotePlaylist> = emptyList(),
    val infiniteSections: List<HomeFeedSection> = emptyList(),
    val daylistPlaylist: Playlist? = null,
    val trendingSongs: List<Track> = emptyList(),
    val localAudioTracks: List<Track> = emptyList(),
    val searchQuery: String = "",
    val searchHistory: List<String> = emptyList(),
    val searchResults: List<Track> = emptyList(),
    val searchPlaylists: List<RemotePlaylist> = emptyList(),
    val hasSearched: Boolean = false,
    val isSearching: Boolean = false,

    // Library, Playlist Smart Shuffle, Bulk Download & Social
    val likedTracks: List<Track> = emptyList(),
    val hiddenTrackIds: Set<String> = emptySet(),
    val followedArtists: Set<String> = emptySet(),
    val playlists: List<Playlist> = emptyList(),
    val smartShuffledPlaylistIds: Set<String> = emptySet(),
    val isLoadingSmartPlaylist: Boolean = false,
    val folders: List<PlaylistFolder> = emptyList(),
    val deletedPlaylists: List<DeletedPlaylistRecord> = emptyList(),
    val excludedArtistsFromTaste: Set<String> = emptySet(),
    val friends: List<FriendProfile> = emptyList(),
    val activeChatFriend: String = "כללי",
    val messages: List<ChatMessage> = emptyList(),
    val stats: ListeningStats = ListeningStats(),
    val offlineTrackIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val downloadProgress: Map<String, Int> = emptyMap(),
    val activeBulkDownloadPlaylistId: String? = null,
    val bulkDownloadDone: Int = 0,
    val bulkDownloadTotal: Int = 0,

    // Playback & Lyrics Translation
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val currentTrack: Track? = null,
    val resolvedStreams: ResolvedStreams? = null,
    val activeVideoClipId: String? = null,
    val isFindingVideoClip: Boolean = false,
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
    val showTranslatedLyrics: Boolean = false,
    val isTranslatingLyrics: Boolean = false,
    val lyricsFontScale: Float = 1.0f,
    val sleepMinutesLeft: Int? = null,
    val sleepEndOfTrack: Boolean = false,
    val jamState: JamState = JamState(),
    val statusMessage: String? = null
) {
    val isBusyLoading: Boolean get() = isResolvingStream || isPlayerBuffering
    val strings: AppStrings get() = AppStrings(settings.language == AppLanguage.ENGLISH)
}

class AppViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("sonify_prefs", Context.MODE_PRIVATE)
    val repo = StreamRepository(application)
    val audioEngine = AudioEngine(application)
    val jamSync = JamSyncManager()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var retryAttemptForCurrent = 0
    private var lastWidgetPushMs = 0L
    private var lastWidgetTrackId = ""
    private var lastWidgetPlaying = false
    private var lastStatsTickMs = 0L
    private var searchJob: Job? = null
    private var homeJob: Job? = null
    private var infiniteHomeJob: Job? = null
    private var bulkDownloadJob: Job? = null
    private var prefetchJob: Job? = null
    private var homeInfinitePage = 0
    @Volatile private var isApplyingRemoteJam = false

    private val officialVideoCache = ConcurrentHashMap<String, String>()
    private val resolvedStreamCache = ConcurrentHashMap<String, Pair<ResolvedStreams, Long>>()
    private val translatedLyricsCache = ConcurrentHashMap<String, List<LyricLine>>()

    val browseCategories = listOf(
        BrowseCategory("מוזיקה", "Music", "להיטים מובילים", 0xFFE8115B, "תוכן וגילוי", "Content & Discovery"),
        BrowseCategory("פודקאסטים", "Podcasts", "פודקאסט ישראלי", 0xFF006450, "תוכן וגילוי", "Content & Discovery", SearchMode.PODCASTS),
        BrowseCategory("ספרי שמע", "Audiobooks", "audiobook english", 0xFF1E3264, "תוכן וגילוי", "Content & Discovery", SearchMode.AUDIOBOOKS),
        BrowseCategory("הופעות חיות", "Live Events", "live concert performance", 0xFF8D67AB, "תוכן וגילוי", "Content & Discovery"),
        BrowseCategory("נוצר עבורך", "Made For You", "top personalized hits", 0xFF1DB954, "תוכן וגילוי", "Content & Discovery"),
        BrowseCategory("שחרורים חדשים", "New Releases", "new music releases 2026", 0xFFE91429, "תוכן וגילוי", "Content & Discovery"),
        BrowseCategory("מצעדים", "Charts", "top charts global hits", 0xFF8C67AC, "תוכן וגילוי", "Content & Discovery"),
        BrowseCategory("מוזיקה ישראלית", "Israeli", "מוזיקה ישראלית חדשה", 0xFF0D73EC, "תוכן וגילוי", "Content & Discovery"),
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
        viewModelScope.launch(Dispatchers.IO) { repo.prewarmYtDlp() }

        WidgetActionBridge.onPrev = { skipPrevious() }
        WidgetActionBridge.onPlayPause = { togglePlayPause() }
        WidgetActionBridge.onNext = { skipNext() }
        WidgetActionBridge.onLike = { _uiState.value.currentTrack?.let { toggleLike(it) } }

        audioEngine.onProgressUpdate = { pos, dur, playing, buffering ->
            val prevState = _uiState.value
            val track = prevState.currentTrack
            val effectiveDur = if (dur > 0) dur else track?.durationMs ?: 0L
            val now = System.currentTimeMillis()

            if (track != null && (track.id != lastWidgetTrackId || playing != lastWidgetPlaying || now - lastWidgetPushMs >= 5000L)) {
                lastWidgetTrackId = track.id
                lastWidgetPlaying = playing
                lastWidgetPushMs = now
                val permille = if (effectiveDur > 0) ((pos * 1000L) / effectiveDur).toInt() else 0
                val isLiked = prevState.likedTracks.any { it.id == track.id }
                pushWidgetAsync(track.title, track.artist, track.artworkUrl, playing, isLiked, permille)
            }

            _uiState.update { state ->
                val updatedStats = if (playing && !state.settings.privateSession && now - lastStatsTickMs >= 5000L) {
                    lastStatsTickMs = now
                    state.stats.copy(totalListeningMs = state.stats.totalListeningMs + 5000L).also { saveStats(it) }
                } else state.stats

                state.copy(
                    positionMs = pos,
                    durationMs = effectiveDur,
                    isPlaying = playing,
                    isPlayerBuffering = buffering,
                    stats = updatedStats
                )
            }
        }
        audioEngine.onTrackEnded = { handleTrackEnded() }
        audioEngine.onPlaybackError = { code -> handlePlaybackError(code) }
        refreshHomeContent()
        scanLocalFilesSilently()
    }

    override fun onCleared() {
        jamSync.disconnect(effectiveJamName(), sendLeave = true)
        audioEngine.releaseLocks()
        super.onCleared()
    }

    private fun pushWidgetAsync(title: String, artist: String, artworkUrl: String, isPlaying: Boolean, isLiked: Boolean, permille: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            WidgetActionBridge.updateWidget(getApplication(), title, artist, artworkUrl, isPlaying, isLiked, permille)
        }
    }

    private fun loadSavedState() {
        val schemaVer = prefs.getInt("schema_version", 1)
        val lang = runCatching { AppLanguage.valueOf(prefs.getString("lang", AppLanguage.HEBREW.name)!!) }.getOrDefault(AppLanguage.HEBREW)
        val source = runCatching { StreamSource.valueOf(prefs.getString("stream_source", StreamSource.YOUTUBE.name)!!) }.getOrDefault(StreamSource.YOUTUBE)
        val ytMode = runCatching { YouTubeEngineMode.valueOf(prefs.getString("yt_engine_mode", YouTubeEngineMode.AUTO.name)!!) }.getOrDefault(YouTubeEngineMode.AUTO)
        val wifiQ = source.clampQuality(runCatching { AudioQuality.valueOf(prefs.getString("wifi_q", AudioQuality.VERY_HIGH.name)!!) }.getOrDefault(AudioQuality.VERY_HIGH))
        val cellQ = source.clampQuality(runCatching { AudioQuality.valueOf(prefs.getString("cell_q", AudioQuality.HIGH.name)!!) }.getOrDefault(AudioQuality.HIGH))
        val dlQ = source.clampQuality(runCatching { AudioQuality.valueOf(prefs.getString("dl_q", AudioQuality.VERY_HIGH.name)!!) }.getOrDefault(AudioQuality.VERY_HIGH))
        val normLvl = runCatching { NormalizationLevel.valueOf(prefs.getString("norm_lvl", NormalizationLevel.NORMAL.name)!!) }.getOrDefault(NormalizationLevel.NORMAL)
        val dlSpeed = runCatching { DownloadSpeedMode.valueOf(prefs.getString("dl_speed", DownloadSpeedMode.TURBO.name)!!) }.getOrDefault(DownloadSpeedMode.TURBO)
        val bufProf = runCatching { AudioBufferProfile.valueOf(prefs.getString("buffer_profile", AudioBufferProfile.HIGH_STABILITY.name)!!) }.getOrDefault(AudioBufferProfile.HIGH_STABILITY)
        val lyricsLang = runCatching { LyricsLanguage.valueOf(prefs.getString("lyrics_lang", LyricsLanguage.HEBREW.name)!!) }.getOrDefault(LyricsLanguage.HEBREW)

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
            djVoiceEnabled = prefs.getBoolean("dj_voice", true),
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
            carModeOptimized = prefs.getBoolean("car_mode", false),
            infiniteAiHome = prefs.getBoolean("infinite_ai_home", true),
            showYoutubePlaylistsHome = prefs.getBoolean("show_yt_playlists_home", true),
            aiUniqueCovers = prefs.getBoolean("ai_unique_covers", true),
            downloadSpeedMode = dlSpeed,
            audioBufferProfile = bufProf,
            keepWakeLockAudio = prefs.getBoolean("keep_wake_lock", true),
            jamLiveSyncEnabled = prefs.getBoolean("jam_live_sync", true),
            jamSyncServerUrl = prefs.getString("jam_server_url", "https://ntfy.sh") ?: "https://ntfy.sh",
            jamUserDisplayName = prefs.getString("jam_display_name", "") ?: "",
            jamSyncToleranceSec = prefs.getInt("jam_tolerance_sec", 3),
            lyricsTargetLanguage = lyricsLang,
            autoTranslateLyrics = prefs.getBoolean("auto_translate_lyrics", false)
        )
        audioEngine.applySettings(settings)

        var liked = decodeTracks(prefs.getString("liked_tracks", "[]") ?: "[]").map { it.copy(artworkUrl = upgradeArtworkUrlGlobal(it.artworkUrl)) }
        var recent = decodeTracks(prefs.getString("recent_tracks", "[]") ?: "[]").map { it.copy(artworkUrl = upgradeArtworkUrlGlobal(it.artworkUrl)) }
        val playlists = decodePlaylists(prefs.getString("playlists", "[]") ?: "[]").map { pl ->
            pl.copy(coverUrl = upgradeArtworkUrlGlobal(pl.coverUrl), tracks = pl.tracks.map { it.copy(artworkUrl = upgradeArtworkUrlGlobal(it.artworkUrl)) })
        }
        val deleted = decodeDeletedPlaylists(prefs.getString("deleted_playlists", "[]") ?: "[]")
        val folders = decodeFolders(prefs.getString("folders", "[]") ?: "[]")
        val hidden = prefs.getStringSet("hidden_tracks", emptySet()) ?: emptySet()
        val followedArtists = prefs.getStringSet("followed_artists", emptySet()) ?: emptySet()
        val excluded = prefs.getStringSet("excluded_artists", emptySet()) ?: emptySet()
        val searchHist = decodeStringList(prefs.getString("search_history", "[]") ?: "[]")
        val friends = decodeFriends(prefs.getString("friends_v7", "") ?: "")
        val msgs = decodeMessages(prefs.getString("messages", "[]") ?: "[]")
        val stats = decodeStats(prefs.getString("stats", "{}") ?: "{}")

        if (schemaVer < 7) {
            recent = recent.filter { it.isPodcast || it.isAudiobook || it.durationMs <= 600_000L }
            liked = liked.filter { it.isPodcast || it.isAudiobook || it.durationMs <= 600_000L }
            prefs.edit().putInt("schema_version", 7).putString("recent_tracks", encodeTracks(recent)).putString("liked_tracks", encodeTracks(liked)).putString("playlists", encodePlaylists(playlists)).apply()
        }

        _uiState.update {
            it.copy(
                settings = settings, likedTracks = liked, recentTracks = recent, playlists = playlists,
                deletedPlaylists = deleted, folders = folders, hiddenTrackIds = hidden,
                followedArtists = followedArtists, excludedArtistsFromTaste = excluded,
                searchHistory = searchHist, friends = friends, messages = msgs, stats = stats,
                offlineTrackIds = repo.getOfflineTrackIds()
            )
        }
    }

    private fun computeDaylistMeta(isEn: Boolean, filter: HomeFilter): Pair<String, String> {
        if (filter == HomeFilter.AUDIOBOOKS) return (if (isEn) "Audiobook Pick of the Hour" else "ספר השמע לשעה זו") to "classic literature full audiobook"
        if (filter == HomeFilter.PODCASTS) return (if (isEn) "Podcast Episodes For You" else "פרקי פודקאסט נבחרים להיום") to "פודקאסט ישראלי סיפורים"
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return when (hour) {
            in 5..11 -> (if (isEn) "Daylist • Morning Energy" else "Daylist • אנרגיית בוקר") to "upbeat pop morning"
            in 12..16 -> (if (isEn) "Daylist • Afternoon Flow" else "Daylist • קצב צהריים") to "top hits 2025"
            in 17..21 -> (if (isEn) "Daylist • Evening & Workout" else "Daylist • אווירת ערב ואימון") to "workout energy hip hop"
            else -> (if (isEn) "Daylist • Late Night Chill" else "Daylist • צ'יל לילי מאוחר") to "late night chill r&b"
        }
    }

    fun refreshHomeContent() {
        homeJob?.cancel()
        infiniteHomeJob?.cancel()
        homeInfinitePage = 0
        homeJob = viewModelScope.launch {
            val s = _uiState.value.settings
            val isEn = s.language == AppLanguage.ENGLISH
            if (s.offlineMode) {
                buildOfflineBackupAndSpecialMixes()
                return@launch
            }
            _uiState.update { it.copy(isLoadingHome = true, infiniteSections = emptyList(), isLoadingMoreHome = false) }
            val filter = _uiState.value.homeFilter
            val isPod = filter == HomeFilter.PODCASTS
            val isBook = filter == HomeFilter.AUDIOBOOKS
            val (dayTitle, dayQuery) = computeDaylistMeta(isEn, filter)

            val likedSeedArtist = _uiState.value.likedTracks.firstOrNull()?.artist?.takeIf { it.isNotBlank() }
            val (q1, q2, q3, q4, q5) = when (filter) {
                HomeFilter.AUDIOBOOKS -> Quint("best self help audiobook", "sci fi fantasy audiobook", "history biography audiobook", "mystery thriller audiobook", "philosophy audiobook")
                HomeFilter.PODCASTS -> Quint("פודקאסט ישראלי מומלץ", "פודקאסט מדע וטכנולוגיה", "פודקאסט היסטוריה", "tech business podcast", "comedy talk podcast")
                else -> Quint(
                    likedSeedArtist?.let { "$it top hits" } ?: "להיטים ישראליים",
                    "The Weeknd Dua Lipa pop",
                    "hip hop rap hits",
                    "indie alternative rock",
                    "new music releases 2026"
                )
            }

            val d1 = async { runCatching { repo.searchTracks(q1, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val d2 = async { runCatching { repo.searchTracks(q2, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val d3 = async { runCatching { repo.searchTracks(q3, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val d4 = async { runCatching { repo.searchTracks(q4, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val d5 = async { runCatching { repo.searchTracks(q5, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val dDay = async { runCatching { repo.searchTracks(dayQuery, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val dYtPls = if (!isPod && !isBook && s.showYoutubePlaylistsHome) {
                async {
                    val ytQs = buildList {
                        if (likedSeedArtist != null) add("$likedSeedArtist mix")
                        add("top hits 2026")
                        add("מוזיקה ישראלית להיטים")
                    }
                    runCatching { repo.fetchReadyYoutubePlaylists(ytQs, maxTotal = 10) }.getOrDefault(emptyList())
                }
            } else null

            val list1 = filterByTaste(d1.await())
            val list2 = filterByTaste(d2.await())
            val list3 = filterByTaste(d3.await())
            val list4 = filterByTaste(d4.await())
            val list5 = filterByTaste(d5.await())
            val dayT = filterByTaste(dDay.await())
            val ytPls = dYtPls?.await().orEmpty()

            val mixes = when (filter) {
                HomeFilter.AUDIOBOOKS -> buildList {
                    if (list1.isNotEmpty()) add(Playlist("book_1", if (isEn) "Self-Improvement Audiobooks" else "התפתחות אישית ומיינדסט", "Audiobooks", "", list1.first().artworkUrl, tracks = list1, isRemote = true, aiStyleTag = "AUDIOBOOK"))
                    if (list2.isNotEmpty()) add(Playlist("book_2", if (isEn) "Sci-Fi & Fantasy" else "מדע בדיוני ופנטזיה", "Audiobooks", "", list2.first().artworkUrl, tracks = list2, isRemote = true, aiStyleTag = "AUDIOBOOK"))
                    if (list3.isNotEmpty()) add(Playlist("book_3", if (isEn) "History & Biographies" else "היסטוריה וביוגרפיות", "Audiobooks", "", list3.first().artworkUrl, tracks = list3, isRemote = true, aiStyleTag = "AUDIOBOOK"))
                    if (list4.isNotEmpty()) add(Playlist("book_4", if (isEn) "Mystery & Thriller" else "מתח ומסתורין", "Audiobooks", "", list4.first().artworkUrl, tracks = list4, isRemote = true, aiStyleTag = "AUDIOBOOK"))
                    if (list5.isNotEmpty()) add(Playlist("book_5", if (isEn) "Philosophy & Classics" else "פילוסופיה וקלאסיקות", "Audiobooks", "", list5.first().artworkUrl, tracks = list5, isRemote = true, aiStyleTag = "AUDIOBOOK"))
                }
                HomeFilter.PODCASTS -> buildList {
                    if (list1.isNotEmpty()) add(Playlist("pod_1", if (isEn) "Top Israeli Podcasts" else "פודקאסטים מובילים בישראל", "Podcasts", "", list1.first().artworkUrl, tracks = list1, isRemote = true, aiStyleTag = "PODCAST"))
                    if (list2.isNotEmpty()) add(Playlist("pod_2", if (isEn) "Science & Tech" else "מדע, הייטק וטכנולוגיה", "Podcasts", "", list2.first().artworkUrl, tracks = list2, isRemote = true, aiStyleTag = "PODCAST"))
                    if (list3.isNotEmpty()) add(Playlist("pod_3", if (isEn) "History & Stories" else "היסטוריה וסיפורים", "Podcasts", "", list3.first().artworkUrl, tracks = list3, isRemote = true, aiStyleTag = "PODCAST"))
                    if (list4.isNotEmpty()) add(Playlist("pod_4", if (isEn) "Business & Ideas" else "עסקים ויזמות", "Podcasts", "", list4.first().artworkUrl, tracks = list4, isRemote = true, aiStyleTag = "PODCAST"))
                    if (list5.isNotEmpty()) add(Playlist("pod_5", if (isEn) "Comedy & Talks" else "קומדיה ושיחות", "Podcasts", "", list5.first().artworkUrl, tracks = list5, isRemote = true, aiStyleTag = "PODCAST"))
                }
                else -> buildList {
                    buildOfflineBackupPlaylist()?.let { add(it) }
                    buildOnRepeatPlaylist()?.let { add(it) }
                    buildRepeatRewindPlaylist()?.let { add(it) }
                    val mix1Title = if (likedSeedArtist != null) {
                        if (isEn) "Daily Mix 1 • $likedSeedArtist" else "Daily Mix 1 • $likedSeedArtist"
                    } else if (isEn) "Daily Mix 1 • Israeli" else "Daily Mix 1 • ישראלי"
                    if (list1.isNotEmpty()) add(Playlist("home_daily_1", mix1Title, "Sonify", "", list1.first().artworkUrl, tracks = list1, isRemote = true, aiSeedPrompt = q1, aiStyleTag = "DAILY 1"))
                    if (list2.isNotEmpty()) add(Playlist("home_daily_2", if (isEn) "Daily Mix 2 • Global Pop" else "Daily Mix 2 • פופ עולמי", "Sonify", "", list2.first().artworkUrl, tracks = list2, isRemote = true, aiSeedPrompt = q2, aiStyleTag = "DAILY 2"))
                    if (list3.isNotEmpty()) add(Playlist("home_daily_3", if (isEn) "Daily Mix 3 • Hip-Hop" else "Daily Mix 3 • היפ-הופ", "Sonify", "", list3.first().artworkUrl, tracks = list3, isRemote = true, aiSeedPrompt = q3, aiStyleTag = "HIP-HOP"))
                    if (list4.isNotEmpty()) add(Playlist("home_discover", "Discover Weekly", "Sonify", "", list4.first().artworkUrl, tracks = list4, isRemote = true, aiSeedPrompt = q4, aiStyleTag = "DISCOVER"))
                    if (list5.isNotEmpty()) add(Playlist("home_radar", "Release Radar", "Sonify", "", list5.first().artworkUrl, tracks = list5, isRemote = true, aiSeedPrompt = q5, aiStyleTag = "NEW"))
                }
            }

            val dayPlaylist = if (dayT.isNotEmpty()) Playlist("home_daylist_${filter.name}", dayTitle, "Sonify", "", dayT.first().artworkUrl, tracks = dayT, isRemote = true, aiSeedPrompt = dayQuery, aiStyleTag = "DAYLIST") else null

            _uiState.update { state ->
                state.copy(
                    isLoadingHome = false,
                    homeMixPlaylists = mixes,
                    readyYoutubePlaylists = ytPls,
                    daylistPlaylist = dayPlaylist,
                    trendingSongs = (list1.take(10) + list2.take(10)).distinctBy { it.id },
                    recentTracks = if (state.recentTracks.isEmpty() && !isPod && !isBook) list1.take(8) else state.recentTracks
                )
            }
        }
    }

    // Infinite AI Home Feed Loader (called automatically when scrolling near bottom!)
    fun loadMoreHomeContent() {
        val st = _uiState.value
        if (!st.settings.infiniteAiHome || st.settings.offlineMode || st.isLoadingHome || st.isLoadingMoreHome) return
        if (st.infiniteSections.size >= 40) return

        infiniteHomeJob = viewModelScope.launch {
            _uiState.update { it.copy(isLoadingMoreHome = true) }
            val page = homeInfinitePage++
            val existingTitles = (st.homeMixPlaylists.map { it.name } + st.infiniteSections.flatMap { sec -> sec.playlists.map { it.name } + sec.title }).toSet()
            val newSection = repo.buildSmartAiHomeBatch(
                pageIndex = page,
                likedTracks = st.likedTracks,
                recentTracks = st.recentTracks,
                stats = st.stats,
                excludedArtists = st.excludedArtistsFromTaste,
                homeFilter = st.homeFilter,
                existingTitles = existingTitles,
                settings = st.settings
            )
            _uiState.update { cur ->
                cur.copy(
                    isLoadingMoreHome = false,
                    infiniteSections = if (newSection != null) cur.infiniteSections + newSection else cur.infiniteSections
                )
            }
        }
    }

    private data class Quint<A, B, C, D, E>(val a: A, val b: B, val c: C, val d: D, val e: E)

    private fun filterByTaste(tracks: List<Track>): List<Track> {
        val excl = _uiState.value.excludedArtistsFromTaste
        val hidden = _uiState.value.hiddenTrackIds
        return tracks.filterNot { it.id in hidden || excl.any { a -> it.artist.contains(a, true) } }
    }

    private fun buildOfflineBackupPlaylist(): Playlist? {
        val state = _uiState.value
        val offlineIds = state.offlineTrackIds
        val offlineTracks = (state.recentTracks + state.likedTracks + state.queue + state.localAudioTracks)
            .distinctBy { it.id }
            .filter { it.id in offlineIds || it.source == StreamSource.LOCAL }
        if (offlineTracks.isEmpty()) return null
        return Playlist("offline_backup", if (state.strings.isEn) "Offline Backup" else "Offline Backup • גיבוי אוטומטי", "Sonify", "", offlineTracks.first().artworkUrl, customCoverBgHex = 0xFF006450, tracks = offlineTracks, isRemote = true, aiStyleTag = "OFFLINE")
    }

    private fun buildOnRepeatPlaylist(): Playlist? {
        val state = _uiState.value
        val pool = (state.recentTracks + state.likedTracks).distinctBy { it.id }
        val sorted = pool.sortedByDescending { state.stats.trackPlayCounts[it.id] ?: 0 }.take(25)
        if (sorted.size < 3) return null
        return Playlist("on_repeat", if (state.strings.isEn) "On Repeat" else "On Repeat • חרשת עליהם", "Sonify", "", sorted.first().artworkUrl, customCoverBgHex = 0xFFE8115B, tracks = sorted, isRemote = true, aiStyleTag = "REPEAT")
    }

    private fun buildRepeatRewindPlaylist(): Playlist? {
        val state = _uiState.value
        val pool = state.likedTracks.reversed().take(25)
        if (pool.size < 3) return null
        return Playlist("repeat_rewind", if (state.strings.isEn) "Repeat Rewind" else "Repeat Rewind • נוסטלגיה", "Sonify", "", pool.first().artworkUrl, customCoverBgHex = 0xFF1E3264, tracks = pool, isRemote = true, aiStyleTag = "REWIND")
    }

    private fun buildOfflineBackupAndSpecialMixes() {
        _uiState.update { it.copy(isLoadingHome = false, homeMixPlaylists = listOfNotNull(buildOfflineBackupPlaylist(), buildOnRepeatPlaylist()), readyYoutubePlaylists = emptyList(), infiniteSections = emptyList(), daylistPlaylist = null) }
    }

    // ---------- Navigation & UI State ----------
    fun selectTab(tab: MainTab) = _uiState.update { it.copy(selectedTab = tab, showSettings = false, settingsSection = SettingsSection.ROOT, openedPlaylist = null, openedArtist = null, openedArtistProfile = null) }
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
    fun openPlaylist(playlist: Playlist?) = _uiState.update { it.copy(openedPlaylist = playlist, openedArtist = null, openedArtistProfile = null, showSettings = false) }
    fun closeArtistPage() = _uiState.update { it.copy(openedArtist = null, openedArtistProfile = null) }
    fun openTrackOptions(track: Track?) = _uiState.update { it.copy(trackForOptions = track) }
    fun clearStatus() = _uiState.update { it.copy(statusMessage = null) }
    fun setLyricsFontScale(scale: Float) = _uiState.update { it.copy(lyricsFontScale = scale.coerceIn(0.8f, 1.5f)) }

    fun setHomeFilter(filter: HomeFilter) { _uiState.update { it.copy(homeFilter = filter) }; refreshHomeContent() }
    fun setSearchMode(mode: SearchMode, triggerSearch: Boolean = true) {
        _uiState.update { it.copy(searchMode = mode) }
        if (triggerSearch && _uiState.value.searchQuery.isNotBlank()) performSearch()
    }
    fun setLibraryFilter(filter: LibraryFilter) = _uiState.update { it.copy(libraryFilter = filter, selectedFolderId = null) }
    fun setLibrarySort(sort: LibrarySort) = _uiState.update { it.copy(librarySort = sort) }
    fun onLibrarySearchChange(q: String) = _uiState.update { it.copy(librarySearchQuery = q) }
    fun toggleLibraryGrid() = _uiState.update { it.copy(libraryGridView = !it.libraryGridView) }
    fun selectFolder(id: String?) = _uiState.update { it.copy(selectedFolderId = id) }

    // ---------- Spotify-Grade Full Artist Page & Album Opener ----------
    fun openArtistPage(artistName: String, artworkUrl: String = "", browseId: String = "") {
        val cleanName = artistName.trim().ifBlank { return }
        _uiState.update {
            it.copy(
                showNowPlaying = false,
                openedPlaylist = null,
                openedArtist = ArtistPageState(cleanName, artworkUrl, emptyList(), isLoading = true),
                openedArtistProfile = null
            )
        }
        viewModelScope.launch {
            val profile = repo.fetchFullArtistProfile(cleanName, _uiState.value.settings, browseId)
            _uiState.update { st ->
                val cur = st.openedArtist ?: return@update st
                st.copy(
                    openedArtist = cur.copy(
                        artistName = profile.artistName,
                        artworkUrl = profile.bannerUrl.ifBlank { cur.artworkUrl },
                        tracks = profile.topTracks,
                        isLoading = false
                    ),
                    openedArtistProfile = profile
                )
            }
        }
    }

    fun openArtistRelease(release: ArtistRelease, artistName: String) {
        _uiState.update {
            it.copy(
                isLoadingPlaylist = true,
                openedArtist = null,
                openedArtistProfile = null,
                openedPlaylist = Playlist(
                    id = release.id,
                    name = release.title,
                    creator = artistName,
                    description = release.subtitle,
                    coverUrl = release.artworkUrl,
                    isRemote = true
                )
            )
        }
        viewModelScope.launch {
            val tracks = repo.fetchPlaylistTracks(release.id, maxTracks = 60, fallbackCoverUrl = release.artworkUrl)
            _uiState.update { state ->
                val cur = state.openedPlaylist
                if (cur != null && cur.id == release.id) state.copy(isLoadingPlaylist = false, openedPlaylist = cur.copy(tracks = tracks))
                else state.copy(isLoadingPlaylist = false)
            }
        }
    }

    fun toggleFollowArtist(artistName: String) {
        val cur = _uiState.value.followedArtists
        val updated = if (artistName in cur) cur - artistName else cur + artistName
        prefs.edit().putStringSet("followed_artists", updated).apply()
        val s = _uiState.value.strings
        _uiState.update {
            it.copy(
                followedArtists = updated,
                statusMessage = if (artistName in updated) s.t("עוקב אחרי $artistName", "Following $artistName") else s.t("הפסקת לעקוב אחרי $artistName", "Unfollowed $artistName")
            )
        }
    }

    // ---------- Smart Library Virtual Playlists ----------
    fun openLikedSongsPlaylist() {
        val st = _uiState.value; val s = st.strings
        openPlaylist(Playlist("virtual_liked", s.likedSongs, "Sonify", "${st.likedTracks.size} ${s.songs}", st.likedTracks.firstOrNull()?.artworkUrl ?: "", 0xFF503750, customCoverSticker = "♥", tracks = st.likedTracks, isRemote = true, aiStyleTag = "LIKED"))
    }

    fun openOfflineDownloadsPlaylist() {
        val st = _uiState.value; val s = st.strings
        val offTracks = (st.likedTracks + st.recentTracks + st.playlists.flatMap { it.tracks } + st.queue).distinctBy { it.id }.filter { it.id in st.offlineTrackIds }
        openPlaylist(Playlist("virtual_offline", s.offlineDownloads, "Sonify", "${offTracks.size} ${s.songs}", offTracks.firstOrNull()?.artworkUrl ?: "", 0xFF006450, customCoverSticker = "⬇", tracks = offTracks, isRemote = true, aiStyleTag = "OFFLINE"))
    }

    fun openLocalFilesPlaylist() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { repo.searchLocalAudio("") }
            val s = _uiState.value.strings
            _uiState.update { it.copy(localAudioTracks = found) }
            openPlaylist(Playlist("virtual_local", s.localFiles, "Device", "${found.size} ${s.songs}", found.firstOrNull()?.artworkUrl ?: "", 0xFF1E3264, customCoverSticker = "📁", tracks = found, isRemote = true, aiStyleTag = "LOCAL"))
        }
    }

    fun updateSettings(rawNewSettings: AppSettings) {
        val src = rawNewSettings.streamSource
        val newSettings = rawNewSettings.copy(
            wifiQuality = src.clampQuality(rawNewSettings.wifiQuality),
            cellularQuality = src.clampQuality(rawNewSettings.cellularQuality),
            downloadQuality = src.clampQuality(rawNewSettings.downloadQuality)
        )
        val prev = _uiState.value.settings
        val sourceChanged = newSettings.streamSource != prev.streamSource ||
            newSettings.offlineMode != prev.offlineMode ||
            newSettings.language != prev.language ||
            newSettings.showYoutubePlaylistsHome != prev.showYoutubePlaylistsHome
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
            .putBoolean("dj_voice", newSettings.djVoiceEnabled)
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
            .putBoolean("infinite_ai_home", newSettings.infiniteAiHome)
            .putBoolean("show_yt_playlists_home", newSettings.showYoutubePlaylistsHome)
            .putBoolean("ai_unique_covers", newSettings.aiUniqueCovers)
            .putString("dl_speed", newSettings.downloadSpeedMode.name)
            .putString("buffer_profile", newSettings.audioBufferProfile.name)
            .putBoolean("keep_wake_lock", newSettings.keepWakeLockAudio)
            .putBoolean("jam_live_sync", newSettings.jamLiveSyncEnabled)
            .putString("jam_server_url", newSettings.jamSyncServerUrl)
            .putString("jam_display_name", newSettings.jamUserDisplayName)
            .putInt("jam_tolerance_sec", newSettings.jamSyncToleranceSec)
            .putString("lyrics_lang", newSettings.lyricsTargetLanguage.name)
            .putBoolean("auto_translate_lyrics", newSettings.autoTranslateLyrics)
            .apply()
        if (sourceChanged) refreshHomeContent()
    }

    // ---------- Storage, Privacy & Taste ----------
    fun clearAppCache() {
        repo.clearCache()
        resolvedStreamCache.clear()
        val s = _uiState.value.strings
        _uiState.update { it.copy(storageRefreshKey = it.storageRefreshKey + 1, statusMessage = s.t("המטמון נוקה (ההורדות נשמרו)", "Cache cleared (downloads kept)")) }
    }

    fun removeAllDownloads() {
        cancelPlaylistDownload()
        repo.deleteAllOfflineTracks()
        val s = _uiState.value.strings
        _uiState.update { it.copy(offlineTrackIds = emptySet(), storageRefreshKey = it.storageRefreshKey + 1, statusMessage = s.t("כל ההורדות נמחקו", "All downloads removed")) }
    }

    fun clearRecentHistory() {
        prefs.edit().putString("recent_tracks", "[]").apply()
        val s = _uiState.value.strings
        _uiState.update { it.copy(recentTracks = emptyList(), statusMessage = s.t("היסטוריית הניגון נוקתה", "Listening history cleared")) }
        refreshHomeContent()
    }

    fun toggleHideTrack(track: Track) {
        val cur = _uiState.value.hiddenTrackIds
        val updated = if (track.id in cur) cur - track.id else cur + track.id
        prefs.edit().putStringSet("hidden_tracks", updated).apply()
        val s = _uiState.value.strings
        _uiState.update { it.copy(hiddenTrackIds = updated, trackForOptions = null, statusMessage = if (track.id in updated) s.t("השיר הוסתר — ידולג אוטומטית", "Song hidden — will always skip") else s.t("השיר הוחזר להשמעה", "Song unhidden")) }
    }

    fun clearAllHiddenTracks() {
        prefs.edit().putStringSet("hidden_tracks", emptySet()).apply()
        val s = _uiState.value.strings
        _uiState.update { it.copy(hiddenTrackIds = emptySet(), statusMessage = s.t("כל השירים המוסתרים שוחזרו", "All hidden songs restored")) }
    }

    fun toggleExcludeArtistFromTaste(artist: String) {
        val cur = _uiState.value.excludedArtistsFromTaste
        val updated = if (artist in cur) cur - artist else cur + artist
        prefs.edit().putStringSet("excluded_artists", updated).apply()
        val s = _uiState.value.strings
        _uiState.update { it.copy(excludedArtistsFromTaste = updated, statusMessage = if (artist in updated) s.t("$artist הוחרג מפרופיל הטעם", "Excluded $artist from taste") else s.t("$artist הוחזר לפרופיל הטעם", "Restored $artist to taste")) }
    }

    fun testSubsonicServer() {
        viewModelScope.launch {
            _uiState.update { it.copy(statusMessage = it.strings.t("בודק חיבור לשרת...", "Testing server...")) }
            val (_, msg) = repo.testSubsonicConnection(_uiState.value.settings)
            _uiState.update { it.copy(statusMessage = msg) }
        }
    }

    private fun scanLocalFilesSilently() {
        viewModelScope.launch(Dispatchers.IO) {
            val found = repo.searchLocalAudio("")
            _uiState.update { it.copy(localAudioTracks = found) }
        }
    }

    fun scanLocalFiles() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { repo.searchLocalAudio("") }
            val s = _uiState.value.strings
            _uiState.update {
                it.copy(
                    localAudioTracks = found,
                    recentTracks = if (found.isNotEmpty() && it.settings.streamSource == StreamSource.LOCAL) found.take(12) else it.recentTracks,
                    statusMessage = if (found.isEmpty()) s.t("לא נמצאו קובצי אודיו במכשיר", "No local audio files found") else s.t("נמצאו ${found.size} קובצי אודיו", "Found ${found.size} local audio files")
                )
            }
        }
    }

    // ---------- Search (Songs + Ready YouTube Playlists!) ----------
    fun onSearchQueryChange(query: String) { _uiState.update { it.copy(searchQuery = query, hasSearched = if (query.isBlank()) false else it.hasSearched) } }

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
                    // Fetch both Songs AND matching Ready YouTube Playlists concurrently!
                    val (songs, pls) = coroutineScope {
                        val sJob = async { repo.searchTracks(clean, _uiState.value.settings) }
                        val pJob = async { runCatching { repo.searchPlaylists(clean).take(8) }.getOrDefault(emptyList()) }
                        sJob.await() to pJob.await()
                    }
                    _uiState.update { it.copy(searchResults = songs, searchPlaylists = pls, isSearching = false) }
                }
            }
        }
    }

    // ---------- Smart Sonify Code / QR / Deep Link Opener ----------
    fun openSonifyCodeOrLink(raw: String) {
        val s = _uiState.value.strings
        when (val parsed = SonifyLinkCodec.parse(raw)) {
            is ParsedSonifyLink.JamLink -> {
                setShowCodeFor(null)
                joinJamByCode(parsed.code, initialVideoId = parsed.trackId, initialStartSec = parsed.startSec)
            }
            is ParsedSonifyLink.TrackLink -> {
                setShowCodeFor(null)
                val track = Track(parsed.videoId, s.t("שיר משותף", "Shared Track"), "YouTube", artworkUrl = "https://i.ytimg.com/vi/${parsed.videoId}/maxresdefault.jpg")
                playTrack(track, listOf(track), startAtMs = parsed.startSec * 1000L)
                setShowNowPlaying(true)
            }
            is ParsedSonifyLink.PlaylistLink -> {
                val localPl = _uiState.value.playlists.firstOrNull { it.id.startsWith(parsed.playlistId) }
                setShowCodeFor(null)
                if (localPl != null) openPlaylist(localPl)
                else openRemotePlaylist(RemotePlaylist(parsed.playlistId, s.t("פלייליסט משותף", "Shared Playlist"), "Sonify", ""))
            }
            is ParsedSonifyLink.SearchQuery -> {
                if (parsed.query.isNotBlank()) {
                    setShowCodeFor(null)
                    selectTab(MainTab.SEARCH)
                    performSearch(parsed.query)
                }
            }
        }
    }

    // ---------- Fast Cached Playback, Next-Track Prefetch & Official Music Video ----------
    private suspend fun resolveFastWithCache(track: Track, settings: AppSettings, attempt: Int = 0): ResolvedStreams? {
        if (attempt == 0 && track.source == StreamSource.YOUTUBE && !settings.offlineMode) {
            val cached = resolvedStreamCache[track.id]
            if (cached != null && (System.currentTimeMillis() - cached.second) < 35L * 60_000L) {
                return cached.first
            }
        }
        val fresh = repo.resolveStreams(track, settings, attemptIndex = attempt)
        if (fresh != null && !fresh.isOffline && track.source == StreamSource.YOUTUBE) {
            resolvedStreamCache[track.id] = fresh to System.currentTimeMillis()
        }
        return fresh
    }

    private fun prefetchNextTrackInQueue(queue: List<Track>, currentIndex: Int) {
        prefetchJob?.cancel()
        val next = queue.getOrNull(currentIndex + 1) ?: return
        val settings = _uiState.value.settings
        if (settings.offlineMode || next.source != StreamSource.YOUTUBE || settings.ytEngineMode == YouTubeEngineMode.WEB_DIRECT) return
        if (next.id in _uiState.value.offlineTrackIds) return
        val existing = resolvedStreamCache[next.id]
        if (existing != null && (System.currentTimeMillis() - existing.second) < 30L * 60_000L) return

        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val res = repo.resolveStreams(next, settings, attemptIndex = 0)
                if (res != null && !res.isOffline) {
                    resolvedStreamCache[next.id] = res to System.currentTimeMillis()
                }
            }
        }
    }

    fun startSongOrArtistRadio(track: Track) {
        val s = _uiState.value.strings
        _uiState.update { it.copy(trackForOptions = null, statusMessage = s.t("מכין רדיו מבוסס על ${track.title}...", "Building radio for ${track.title}...")) }
        viewModelScope.launch {
            val related = repo.searchTracks("${track.artist} ${track.title}", _uiState.value.settings, maxResults = 25)
                .filterNot { it.id == track.id || it.id in _uiState.value.hiddenTrackIds }
                .take(24)
            val radioList = listOf(track) + related
            playTrack(track, radioList)
            _uiState.update { it.copy(showNowPlaying = true, statusMessage = "${s.startRadio} • ${track.artist} (${radioList.size})") }
        }
    }

    fun playTrack(
        track: Track,
        list: List<Track> = listOf(track),
        openVideoImmediately: Boolean = false,
        startAtMs: Long = 0L,
        keepDjActive: Boolean = false,
        fromRemoteJam: Boolean = false
    ) {
        val state = _uiState.value
        val s = state.strings
        val isOfflineReady = track.id in state.offlineTrackIds || track.source == StreamSource.LOCAL
        if (state.settings.offlineMode && !isOfflineReady) {
            _uiState.update { it.copy(statusMessage = s.t("מצב אופליין פעיל — השיר הזה לא הורד", "Offline Mode ON — track not downloaded")) }
            return
        }

        retryAttemptForCurrent = 0
        val cleanList = list.filterNot { it.id in state.hiddenTrackIds && it.id != track.id }
        val finalQueue = if (cleanList.any { it.id == track.id }) cleanList else listOf(track) + cleanList
        val idx = finalQueue.indexOfFirst { it.id == track.id }.coerceAtLeast(0)

        val updatedRecent = if (!state.settings.privateSession) {
            (listOf(track) + state.recentTracks.filterNot { it.id == track.id }).take(30).also {
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

        val updatedJam = if (state.jamState.active) {
            state.jamState.copy(joinUrl = SonifyLinkCodec.buildSmartJamLink(state.jamState.code, track, startAtMs))
        } else state.jamState

        _uiState.update {
            it.copy(
                queue = finalQueue, currentIndex = idx, currentTrack = track, recentTracks = updatedRecent,
                stats = updatedStats, resolvedStreams = null, activeVideoClipId = null, isFindingVideoClip = false,
                isAiDjActive = if (keepDjActive) it.isAiDjActive else false,
                isUsingWebEngine = useWebDirect, isResolvingStream = !useWebDirect, isPlayerBuffering = useWebDirect,
                isVideoMode = false, positionMs = startAtMs, durationMs = track.durationMs,
                lyrics = emptyList(), isLoadingLyrics = true, showTranslatedLyrics = false, isTranslatingLyrics = false,
                jamState = updatedJam
            )
        }

        val isLiked = state.likedTracks.any { it.id == track.id }
        pushWidgetAsync(track.title, track.artist, track.artworkUrl, true, isLiked, 0)
        extractDynamicColorAndWidgetArt(track)

        if (useWebDirect) {
            audioEngine.playViaWebEngine(track.id, startAtMs, track.title, track.artist)
        } else {
            resolveAndStartTrack(track, attempt = 0, forceVideo = false, startAtMs = startAtMs)
        }

        if (!fromRemoteJam && state.jamState.active && (state.jamState.isHost || state.jamState.guestsCanControlQueue)) {
            publishJamState("TRACK", trackOverride = track, posOverride = startAtMs, playingOverride = true)
        }

        prefetchNextTrackInQueue(finalQueue, idx)

        if (openVideoImmediately || (!settings.dataSaver && !(track.isPodcast && settings.audioOnlyPodcasts) && settings.autoVideoOnWifi && track.source == StreamSource.YOUTUBE)) {
            viewModelScope.launch { toggleVideoClipMode() }
        }

        viewModelScope.launch {
            val lyrics = repo.fetchSyncedLyrics(track)
            if (_uiState.value.currentTrack?.id == track.id) {
                _uiState.update { it.copy(lyrics = lyrics, isLoadingLyrics = false) }
                if (lyrics.isNotEmpty() && _uiState.value.settings.autoTranslateLyrics && _uiState.value.settings.geminiApiKey.isNotBlank()) {
                    toggleTranslateLyrics()
                }
            }
        }
    }

    private fun activateWebFallback(track: Track, startAtMs: Long = 0L) {
        _uiState.update { it.copy(isUsingWebEngine = true, isResolvingStream = false, isPlayerBuffering = true) }
        audioEngine.playViaWebEngine(track.id, startAtMs, track.title, track.artist)
    }

    private fun resolveAndStartTrack(track: Track, attempt: Int, forceVideo: Boolean = false, startAtMs: Long = 0L) {
        viewModelScope.launch {
            val settings = _uiState.value.settings
            val resolved = resolveFastWithCache(track, settings, attempt = attempt)
            if (_uiState.value.currentTrack?.id != track.id) return@launch
            if (resolved == null) {
                if (track.source == StreamSource.YOUTUBE && settings.ytEngineMode != YouTubeEngineMode.EXOPLAYER_ONLY && !settings.offlineMode) {
                    activateWebFallback(track, startAtMs)
                } else {
                    _uiState.update { it.copy(isResolvingStream = false, isPlayerBuffering = false, statusMessage = it.strings.t("לא נמצא זרם זמין", "No stream available")) }
                }
                return@launch
            }
            audioEngine.playResolved(resolved, track, videoMode = forceVideo && resolved.hasVideoClip, startPositionMs = startAtMs)
            _uiState.update { it.copy(isUsingWebEngine = false, resolvedStreams = resolved, isResolvingStream = false, isVideoMode = forceVideo && resolved.hasVideoClip) }
        }
    }

    private fun handlePlaybackError(code: String) {
        val state = _uiState.value
        val track = state.currentTrack ?: return
        resolvedStreamCache.remove(track.id)
        if (track.source == StreamSource.YOUTUBE && !state.settings.offlineMode) {
            if (retryAttemptForCurrent < 5) {
                retryAttemptForCurrent++
                _uiState.update { it.copy(isResolvingStream = true) }
                resolveAndStartTrack(track, attempt = retryAttemptForCurrent, forceVideo = state.isVideoMode, startAtMs = state.positionMs)
            } else if (state.settings.ytEngineMode != YouTubeEngineMode.EXOPLAYER_ONLY) {
                activateWebFallback(track, startAtMs = state.positionMs)
            } else {
                _uiState.update { it.copy(isResolvingStream = false, isPlayerBuffering = false, statusMessage = "Error ($code)") }
            }
        } else {
            _uiState.update { it.copy(isResolvingStream = false, isPlayerBuffering = false, statusMessage = "Error ($code)") }
        }
    }

    private fun extractDynamicColorAndWidgetArt(track: Track) {
        if (track.artworkUrl.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                val app = getApplication<Application>()
                val request = ImageRequest.Builder(app)
                    .data(upgradeArtworkUrlGlobal(track.artworkUrl))
                    .memoryCachePolicy(CachePolicy.DISABLED)
                    .diskCachePolicy(CachePolicy.DISABLED)
                    .allowHardware(false)
                    .size(160)
                    .build()
                val bmp = ((app.imageLoader.execute(request) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap ?: return@launch
                WidgetActionBridge.setArtworkBitmap(track.artworkUrl, bmp)
                val st = _uiState.value
                val isLiked = st.likedTracks.any { it.id == track.id }
                WidgetActionBridge.updateWidget(app, track.title, track.artist, track.artworkUrl, st.isPlaying, isLiked, 0)

                val palette = Palette.from(bmp).generate()
                val picked = palette.getDarkVibrantColor(palette.getDarkMutedColor(palette.getDominantColor(0xFF1F3B33.toInt())))
                _uiState.update { it.copy(dynamicAccentColor = (0xFF000000L or (picked.toLong() and 0x00FFFFFFL))) }
            }
        }
    }

    fun toggleVideoClipMode() {
        val state = _uiState.value
        val track = state.currentTrack ?: return
        val s = state.strings

        if (state.isVideoMode) {
            val pos = state.positionMs
            val usedDifferentClip = state.activeVideoClipId != null && state.activeVideoClipId != track.id
            if (usedDifferentClip) {
                if (state.isUsingWebEngine) audioEngine.playViaWebEngine(track.id, pos, track.title, track.artist)
                else state.resolvedStreams?.let { audioEngine.seamlessSwitchVideoMode(it, track, toVideo = false, startPositionMs = pos) }
            } else if (!state.isUsingWebEngine) {
                state.resolvedStreams?.let { audioEngine.seamlessSwitchVideoMode(it, track, toVideo = false, startPositionMs = pos) }
            }
            _uiState.update { it.copy(isVideoMode = false, isFindingVideoClip = false) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isFindingVideoClip = true) }
            val cached = officialVideoCache[track.id]
            val officialId = cached ?: repo.findOfficialMusicVideoId(track)?.also { officialVideoCache[track.id] = it } ?: track.id
            val latest = _uiState.value
            if (latest.currentTrack?.id != track.id) return@launch

            val pos = latest.positionMs
            if (officialId != track.id) {
                val clipTrack = track.copy(id = officialId)
                val clipResolved = if (!latest.isUsingWebEngine && latest.settings.ytEngineMode != YouTubeEngineMode.WEB_DIRECT) {
                    resolveFastWithCache(clipTrack, latest.settings)
                } else null

                if (clipResolved != null && clipResolved.hasVideoClip) {
                    audioEngine.playResolved(clipResolved, clipTrack, videoMode = true, startPositionMs = pos)
                    _uiState.update { it.copy(isVideoMode = true, isUsingWebEngine = false, activeVideoClipId = officialId, isFindingVideoClip = false, statusMessage = s.t("הוחלף לקליפ הרשמי", "Switched to official music video")) }
                } else {
                    audioEngine.playViaWebEngine(officialId, pos, track.title, track.artist)
                    _uiState.update { it.copy(isVideoMode = true, isUsingWebEngine = true, activeVideoClipId = officialId, isFindingVideoClip = false, statusMessage = s.t("הוחלף לקליפ הרשמי", "Switched to official music video")) }
                }
            } else {
                if (latest.isUsingWebEngine) {
                    _uiState.update { it.copy(isVideoMode = true, activeVideoClipId = track.id, isFindingVideoClip = false) }
                } else {
                    val res = latest.resolvedStreams
                    if (res != null && res.hasVideoClip) {
                        audioEngine.seamlessSwitchVideoMode(res, track, toVideo = true, startPositionMs = pos)
                        _uiState.update { it.copy(isVideoMode = true, activeVideoClipId = track.id, isFindingVideoClip = false) }
                    } else {
                        audioEngine.playViaWebEngine(track.id, pos, track.title, track.artist)
                        _uiState.update { it.copy(isVideoMode = true, isUsingWebEngine = true, activeVideoClipId = track.id, isFindingVideoClip = false) }
                    }
                }
            }
        }
    }

    fun togglePlayPause() {
        val state = _uiState.value
        val current = state.currentTrack
        val willPlay = !state.isPlaying
        audioEngine.togglePlayPause(onNeedRetry = {
            if (current != null) {
                if (current.source == StreamSource.YOUTUBE && state.settings.ytEngineMode != YouTubeEngineMode.EXOPLAYER_ONLY) activateWebFallback(current)
                else { retryAttemptForCurrent = 0; _uiState.update { it.copy(isResolvingStream = true) }; resolveAndStartTrack(current, 0) }
            }
        })
        if (!isApplyingRemoteJam && state.jamState.active && (state.jamState.isHost || state.jamState.guestsCanControlQueue)) {
            publishJamState(if (willPlay) "PLAY" else "PAUSE", playingOverride = willPlay)
        }
    }

    fun seekTo(ms: Long) {
        val clamped = ms.coerceAtLeast(0L)
        _uiState.update { it.copy(positionMs = clamped) }
        audioEngine.seekTo(clamped)
        val state = _uiState.value
        if (!isApplyingRemoteJam && state.jamState.active && (state.jamState.isHost || state.jamState.guestsCanControlQueue)) {
            publishJamState("SEEK", posOverride = clamped)
        }
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
            playTrack(state.queue[nextIdx], state.queue, keepDjActive = state.isAiDjActive)
        } else if (state.settings.autoplayEnabled && !state.settings.offlineMode) {
            val current = state.currentTrack ?: return
            _uiState.update { it.copy(statusMessage = it.strings.t("Autoplay • טוען שיר דומה...", "Autoplay • Loading similar track...")) }
            viewModelScope.launch {
                val related = repo.searchTracks(current.artist, _uiState.value.settings, maxResults = 15)
                    .filterNot { cand -> _uiState.value.queue.any { it.id == cand.id } || cand.id in _uiState.value.hiddenTrackIds }
                    .take(8)
                if (related.isNotEmpty()) playTrack(related.first(), _uiState.value.queue + related, keepDjActive = state.isAiDjActive)
            }
        }
    }

    fun skipPrevious() {
        val state = _uiState.value
        if (state.positionMs > 3000L) { seekTo(0L); return }
        if (state.queue.isEmpty()) return
        playTrack(state.queue[(state.currentIndex - 1).coerceAtLeast(0)], state.queue, keepDjActive = state.isAiDjActive)
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
        val s = state.strings
        when {
            !state.shuffleEnabled -> _uiState.update { it.copy(shuffleEnabled = true, smartShuffleEnabled = false, statusMessage = s.t("ערבוב הופעל", "Shuffle ON")) }
            !state.smartShuffleEnabled -> {
                _uiState.update { it.copy(shuffleEnabled = true, smartShuffleEnabled = true, statusMessage = s.t("Smart Shuffle הופעל", "Smart Shuffle ON")) }
                injectSmartShuffleTracks()
            }
            else -> _uiState.update { it.copy(shuffleEnabled = false, smartShuffleEnabled = false, statusMessage = s.t("ערבוב כובה", "Shuffle OFF")) }
        }
    }

    private fun injectSmartShuffleTracks() {
        val current = _uiState.value.currentTrack ?: return
        viewModelScope.launch {
            val similar = repo.searchTracks(current.artist, _uiState.value.settings, maxResults = 12)
                .filterNot { cand -> _uiState.value.queue.any { it.id == cand.id } }
                .take(5).map { it.copy(isSmartInjected = true) }
            if (similar.isNotEmpty()) _uiState.update { it.copy(queue = it.queue + similar) }
        }
    }

    // ---------- RESTORED: Smart Shuffle Inside Playlists! ----------
    fun toggleSmartShuffleForPlaylist(playlist: Playlist) {
        val st = _uiState.value
        val s = st.strings
        if (playlist.tracks.isEmpty()) {
            _uiState.update { it.copy(statusMessage = s.t("הפלייליסט ריק — הוסף שירים קודם", "Playlist is empty — add songs first")) }
            return
        }

        val isCurrentlyOn = playlist.id in st.smartShuffledPlaylistIds
        if (isCurrentlyOn) {
            val cleaned = playlist.copy(tracks = playlist.tracks.filterNot { it.isSmartInjected })
            updateOpenedAndSavedPlaylist(cleaned)
            _uiState.update {
                it.copy(
                    smartShuffledPlaylistIds = it.smartShuffledPlaylistIds - playlist.id,
                    statusMessage = s.t("ערבוב חכם כובה בפלייליסט", "Smart Shuffle turned off")
                )
            }
            return
        }

        if (st.isLoadingSmartPlaylist) return
        _uiState.update { it.copy(isLoadingSmartPlaylist = true, statusMessage = s.t("Smart Shuffle • מוסיף שירים תואמי-סגנון...", "Smart Shuffle • Blending matching songs...")) }

        viewModelScope.launch {
            val seedArtists = playlist.tracks.map { it.artist }.distinct().shuffled().take(3)
            val existingIds = playlist.tracks.map { it.id }.toSet()
            val candidates = coroutineScope {
                seedArtists.map { artist ->
                    async { runCatching { repo.searchTracks("$artist similar songs", _uiState.value.settings, maxResults = 8) }.getOrDefault(emptyList()) }
                }.awaitAll().flatten()
            }.filterNot { it.id in existingIds || it.id in _uiState.value.hiddenTrackIds }
                .distinctBy { it.id }
                .shuffled()
                .take(7)
                .map { it.copy(isSmartInjected = true) }

            if (candidates.isEmpty()) {
                _uiState.update { it.copy(isLoadingSmartPlaylist = false, statusMessage = s.t("לא נמצאו המלצות נוספות כרגע", "No extra matches found right now")) }
                return@launch
            }

            // Interleave 1 smart-injected recommendation every 2-3 original tracks
            val baseTracks = playlist.tracks.filterNot { it.isSmartInjected }
            val woven = mutableListOf<Track>()
            var smartIdx = 0
            baseTracks.forEachIndexed { idx, t ->
                woven.add(t)
                if ((idx + 1) % 3 == 0 && smartIdx < candidates.size) {
                    woven.add(candidates[smartIdx++])
                }
            }
            while (smartIdx < candidates.size) woven.add(candidates[smartIdx++])

            val updatedPl = playlist.copy(tracks = woven)
            updateOpenedAndSavedPlaylist(updatedPl)
            _uiState.update {
                it.copy(
                    isLoadingSmartPlaylist = false,
                    smartShuffledPlaylistIds = it.smartShuffledPlaylistIds + playlist.id,
                    shuffleEnabled = true,
                    smartShuffleEnabled = true,
                    statusMessage = s.t("Smart Shuffle פעיל • נוספו ${candidates.size} המלצות חכמות", "Smart Shuffle ON • Blended ${candidates.size} picks")
                )
            }
        }
    }

    fun keepSmartTrackInPlaylist(playlistId: String, track: Track) {
        val st = _uiState.value
        val target = st.openedPlaylist?.takeIf { it.id == playlistId } ?: st.playlists.firstOrNull { it.id == playlistId } ?: return
        val updated = target.copy(tracks = target.tracks.map { if (it.id == track.id) it.copy(isSmartInjected = false) else it })
        updateOpenedAndSavedPlaylist(updated)
        _uiState.update { it.copy(statusMessage = it.strings.t("\"${track.title}\" נשמר קבוע בפלייליסט", "Saved \"${track.title}\" to playlist")) }
    }

    private fun updateOpenedAndSavedPlaylist(updated: Playlist) {
        if (!updated.isRemote && _uiState.value.playlists.any { it.id == updated.id }) {
            savePlaylists(_uiState.value.playlists.map { if (it.id == updated.id) updated else it })
        }
        _uiState.update { st ->
            if (st.openedPlaylist?.id == updated.id) st.copy(openedPlaylist = updated) else st
        }
    }

    fun cycleRepeatMode() {
        _uiState.update {
            val s = it.strings
            val next = when (it.repeatMode) { RepeatMode.OFF -> RepeatMode.ALL; RepeatMode.ALL -> RepeatMode.ONE; RepeatMode.ONE -> RepeatMode.OFF }
            val msg = when (next) {
                RepeatMode.OFF -> s.t("חזרה כובתה", "Repeat OFF")
                RepeatMode.ALL -> s.t("חזור על הכל", "Repeat All")
                RepeatMode.ONE -> s.t("חזור על שיר אחד", "Repeat One")
            }
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

    fun addToQueue(track: Track) { _uiState.update { it.copy(queue = it.queue + track, statusMessage = it.strings.t("נוסף לתור ההשמעה", "Added to queue")) } }

    fun toggleLike(track: Track) {
        val cur = _uiState.value.likedTracks
        val exists = cur.any { it.id == track.id }
        val updated = if (exists) cur.filterNot { it.id == track.id } else listOf(track) + cur
        prefs.edit().putString("liked_tracks", encodeTracks(updated)).apply()
        val s = _uiState.value.strings
        _uiState.update { it.copy(likedTracks = updated, statusMessage = if (exists) s.t("הוסר מהספרייה", "Removed from Liked Songs") else s.t("נשמר בשירים שאהבת", "Saved to Liked Songs")) }
        val st = _uiState.value
        if (st.currentTrack?.id == track.id) {
            val permille = if (st.durationMs > 0) ((st.positionMs * 1000L) / st.durationMs).toInt() else 0
            pushWidgetAsync(track.title, track.artist, track.artworkUrl, st.isPlaying, !exists, permille)
        }
    }

    // ---------- Offline Download (Single + Parallel Bulk with Cancel & Empty Guard!) ----------
    fun downloadOffline(track: Track) {
        val s = _uiState.value.strings
        if (_uiState.value.offlineTrackIds.contains(track.id)) {
            repo.deleteOfflineTrack(track.id)
            _uiState.update { it.copy(offlineTrackIds = repo.getOfflineTrackIds(), storageRefreshKey = it.storageRefreshKey + 1, statusMessage = s.t("הוסר מהורדות האופליין", "Removed from downloads")) }
            return
        }
        if (track.id in _uiState.value.downloadingIds) return
        _uiState.update {
            it.copy(
                downloadingIds = it.downloadingIds + track.id,
                downloadProgress = it.downloadProgress + (track.id to 1),
                statusMessage = s.t("מוריד \"${track.title}\"...", "Downloading \"${track.title}\"...")
            )
        }
        viewModelScope.launch {
            val captured = audioEngine.getCapturedStreams(track.id)
            val (_, msg) = repo.downloadTrackOffline(
                track = track,
                settings = _uiState.value.settings,
                capturedStreams = captured,
                webViewDownloadFn = { targetFile, prog -> audioEngine.webEngine.downloadViaWebViewFetch(track.id, targetFile, prog) }
            ) { pct -> _uiState.update { st -> st.copy(downloadProgress = st.downloadProgress + (track.id to pct)) } }
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

    fun cancelPlaylistDownload() {
        bulkDownloadJob?.cancel()
        bulkDownloadJob = null
        _uiState.update {
            it.copy(
                activeBulkDownloadPlaylistId = null,
                bulkDownloadDone = 0,
                bulkDownloadTotal = 0,
                downloadingIds = emptySet(),
                downloadProgress = emptyMap(),
                statusMessage = it.strings.t("הורדת הפלייליסט בוטלה", "Playlist download cancelled")
            )
        }
    }

    fun downloadEntirePlaylist(playlist: Playlist) {
        val st = _uiState.value
        val s = st.strings

        // Toggle cancel if already downloading this playlist
        if (st.activeBulkDownloadPlaylistId == playlist.id) {
            cancelPlaylistDownload()
            return
        }
        if (playlist.tracks.isEmpty()) {
            _uiState.update { it.copy(statusMessage = s.t("אין שירים בפלייליסט להורדה", "No songs in playlist to download")) }
            return
        }
        if (st.settings.offlineMode) {
            _uiState.update { it.copy(statusMessage = s.t("מצב אופליין פעיל — כבה אותו כדי להוריד", "Turn off Offline Mode to download")) }
            return
        }
        if (!st.settings.downloadUsingCellular && !repo.isOnWifi()) {
            _uiState.update { it.copy(statusMessage = s.t("הורדה בסלולר חסומה בהגדרות", "Cellular downloads disabled in settings")) }
            return
        }

        val toDownload = playlist.tracks
            .distinctBy { it.id }
            .filter { it.id !in st.offlineTrackIds && it.source != StreamSource.LOCAL }

        if (toDownload.isEmpty()) {
            _uiState.update { it.copy(statusMessage = s.t("כל השירים בפלייליסט כבר באופליין", "All tracks already downloaded")) }
            return
        }

        bulkDownloadJob?.cancel()
        val concurrency = st.settings.downloadSpeedMode.concurrentSongs.coerceIn(2, 4)
        val sem = Semaphore(concurrency)
        val completedCount = AtomicInteger(0)
        val successCount = AtomicInteger(0)

        _uiState.update {
            it.copy(
                activeBulkDownloadPlaylistId = playlist.id,
                bulkDownloadDone = 0,
                bulkDownloadTotal = toDownload.size,
                statusMessage = s.t("מוריד ${toDownload.size} שירים במקביל (${concurrency}x)...", "Downloading ${toDownload.size} tracks (${concurrency}x)...")
            )
        }

        bulkDownloadJob = viewModelScope.launch {
            try {
                coroutineScope {
                    toDownload.map { trk ->
                        async(Dispatchers.IO) {
                            sem.withPermit {
                                _uiState.update { it.copy(downloadingIds = it.downloadingIds + trk.id, downloadProgress = it.downloadProgress + (trk.id to 2)) }
                                try {
                                    val captured = audioEngine.getCapturedStreams(trk.id)
                                    val (ok, _) = repo.downloadTrackOffline(
                                        trk, _uiState.value.settings, captured,
                                        webViewDownloadFn = { file, prog -> audioEngine.webEngine.downloadViaWebViewFetch(trk.id, file, prog) }
                                    ) { pct -> _uiState.update { cur -> cur.copy(downloadProgress = cur.downloadProgress + (trk.id to pct)) } }
                                    if (ok) successCount.incrementAndGet()
                                } finally {
                                    val doneNow = completedCount.incrementAndGet()
                                    _uiState.update { cur ->
                                        cur.copy(
                                            downloadingIds = cur.downloadingIds - trk.id,
                                            downloadProgress = cur.downloadProgress - trk.id,
                                            bulkDownloadDone = doneNow,
                                            offlineTrackIds = repo.getOfflineTrackIds(),
                                            storageRefreshKey = cur.storageRefreshKey + 1
                                        )
                                    }
                                }
                            }
                        }
                    }.awaitAll()
                }
                _uiState.update {
                    it.copy(
                        activeBulkDownloadPlaylistId = null,
                        statusMessage = s.t("ירדו ${successCount.get()} מתוך ${toDownload.size} שירים לאופליין!", "Downloaded ${successCount.get()} / ${toDownload.size} tracks!")
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _uiState.update {
                    it.copy(
                        activeBulkDownloadPlaylistId = null,
                        bulkDownloadDone = 0,
                        bulkDownloadTotal = 0,
                        offlineTrackIds = repo.getOfflineTrackIds()
                    )
                }
            }
        }
    }

    // ---------- Lyrics Translation via User's Gemini API Key ----------
    fun toggleTranslateLyrics(forceTargetLang: LyricsLanguage? = null) {
        val st = _uiState.value
        val s = st.strings
        val track = st.currentTrack ?: return
        if (st.lyrics.isEmpty()) return

        val targetLang = forceTargetLang ?: st.settings.lyricsTargetLanguage
        if (forceTargetLang != null && forceTargetLang != st.settings.lyricsTargetLanguage) {
            updateSettings(st.settings.copy(lyricsTargetLanguage = forceTargetLang))
        }

        if (st.showTranslatedLyrics && forceTargetLang == null) {
            _uiState.update { it.copy(showTranslatedLyrics = false) }
            return
        }

        val cacheKey = "${track.id}_${targetLang.name}"
        translatedLyricsCache[cacheKey]?.let { cached ->
            _uiState.update { it.copy(lyrics = cached, showTranslatedLyrics = true, isTranslatingLyrics = false) }
            return
        }

        val apiKey = st.settings.geminiApiKey.trim()
        if (apiKey.isBlank()) {
            _uiState.update {
                it.copy(statusMessage = s.t("לתרגום מילים יש להזין Gemini API Key בהגדרות ← בינה מלאכותית", "Add your Gemini API Key in Settings -> AI & Notifications to translate lyrics"))
            }
            return
        }

        if (st.isTranslatingLyrics) return
        _uiState.update { it.copy(isTranslatingLyrics = true) }

        viewModelScope.launch {
            val (translated, err) = repo.translateLyricsWithGemini(st.lyrics, targetLang, apiKey)
            if (_uiState.value.currentTrack?.id != track.id) return@launch
            if (translated != null) {
                translatedLyricsCache[cacheKey] = translated
                _uiState.update { it.copy(lyrics = translated, showTranslatedLyrics = true, isTranslatingLyrics = false) }
            } else {
                _uiState.update {
                    it.copy(
                        isTranslatingLyrics = false,
                        statusMessage = s.t("שגיאה בתרגום המילים ($err) — בדוק את מפתח ה-API", "Lyrics translation failed ($err) — check API key")
                    )
                }
            }
        }
    }

    // ---------- Playlists ----------
    private fun savePlaylists(updated: List<Playlist>) {
        prefs.edit().putString("playlists", encodePlaylists(updated)).apply()
        _uiState.update { state ->
            val refreshedOpen = state.openedPlaylist?.let { open -> updated.firstOrNull { it.id == open.id } ?: open }
            state.copy(playlists = updated, openedPlaylist = refreshedOpen)
        }
    }

    fun openRemotePlaylist(remote: RemotePlaylist) {
        _uiState.update {
            it.copy(
                isLoadingPlaylist = true,
                openedArtist = null,
                openedArtistProfile = null,
                openedPlaylist = Playlist(remote.id, remote.title, remote.subtitle, coverUrl = remote.artworkUrl, isRemote = true)
            )
        }
        viewModelScope.launch {
            val tracks = repo.fetchPlaylistTracks(remote.id, maxTracks = 100, fallbackCoverUrl = remote.artworkUrl)
            _uiState.update { state ->
                val cur = state.openedPlaylist
                if (cur != null && cur.id == remote.id) state.copy(isLoadingPlaylist = false, openedPlaylist = cur.copy(tracks = tracks))
                else state.copy(isLoadingPlaylist = false)
            }
        }
    }

    fun loadMoreTracksForPlaylist(pl: Playlist) {
        if (_uiState.value.isExpandingPlaylist) return
        val s = _uiState.value.strings
        _uiState.update { it.copy(isExpandingPlaylist = true, statusMessage = s.t("מוסיף שירים דומים לפלייליסט...", "Adding more tracks...")) }
        viewModelScope.launch {
            val seedQuery = pl.aiSeedPrompt.ifBlank { pl.tracks.randomOrNull()?.artist ?: pl.name }
            val extra = repo.searchTracks("$seedQuery similar hits", _uiState.value.settings, maxResults = 25)
                .filterNot { cand -> pl.tracks.any { it.id == cand.id } || cand.id in _uiState.value.hiddenTrackIds }
                .take(15)
            if (extra.isEmpty()) {
                _uiState.update { it.copy(isExpandingPlaylist = false, statusMessage = s.t("לא נמצאו שירים נוספים", "No more tracks found")) }
                return@launch
            }
            val updatedPl = pl.copy(tracks = pl.tracks + extra)
            updateOpenedAndSavedPlaylist(updatedPl)
            _uiState.update { it.copy(isExpandingPlaylist = false, statusMessage = s.t("נוספו ${extra.size} שירים (סה\"כ ${updatedPl.tracks.size})", "Added ${extra.size} tracks (${updatedPl.tracks.size} total)")) }
        }
    }

    fun renamePlaylist(playlistId: String, newName: String) {
        val clean = newName.trim()
        if (clean.isBlank()) return
        savePlaylists(_uiState.value.playlists.map { if (it.id == playlistId) it.copy(name = clean) else it })
        _uiState.update { it.copy(statusMessage = it.strings.t("שם הפלייליסט עודכן", "Playlist renamed")) }
    }

    fun movePlaylistTrack(playlistId: String, from: Int, to: Int) {
        val pl = _uiState.value.playlists.firstOrNull { it.id == playlistId } ?: return
        val list = pl.tracks.toMutableList()
        if (from !in list.indices || to !in list.indices) return
        list.add(to, list.removeAt(from))
        savePlaylists(_uiState.value.playlists.map { if (it.id == playlistId) it.copy(tracks = list) else it })
    }

    fun saveRemotePlaylistToLibrary(pl: Playlist) {
        val saved = pl.copy(id = UUID.randomUUID().toString(), isRemote = false)
        savePlaylists(listOf(saved) + _uiState.value.playlists)
        _uiState.update { it.copy(openedPlaylist = saved, statusMessage = it.strings.t("הפלייליסט נשמר בספרייה!", "Saved to your library!")) }
    }

    fun createPlaylist(name: String, initialTracks: List<Track> = emptyList(), isCollaborative: Boolean = false) {
        val clean = name.trim()
        val s = _uiState.value.strings
        if (clean.isBlank()) { _uiState.update { it.copy(statusMessage = s.t("יש להזין שם לפלייליסט", "Enter a playlist name")) }; return }
        val pl = Playlist(name = clean, coverUrl = initialTracks.firstOrNull()?.artworkUrl ?: "", tracks = initialTracks, isCollaborative = isCollaborative, collaborators = if (isCollaborative) listOf("אתה") else emptyList())
        savePlaylists(listOf(pl) + _uiState.value.playlists)
        _uiState.update { it.copy(showCreateSheet = false, openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = s.t("הפלייליסט \"$clean\" נוצר", "Created \"$clean\"")) }
    }

    fun createFolder(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val updated = listOf(PlaylistFolder(name = clean)) + _uiState.value.folders
        prefs.edit().putString("folders", encodeFolders(updated)).apply()
        _uiState.update { it.copy(folders = updated, showCreateSheet = false, statusMessage = it.strings.t("התיקייה \"$clean\" נוצרה", "Folder \"$clean\" created")) }
    }

    fun assignPlaylistToFolder(playlistId: String, folderId: String?) {
        savePlaylists(_uiState.value.playlists.map { if (it.id == playlistId) it.copy(folderId = folderId) else it })
        _uiState.update { it.copy(statusMessage = it.strings.t("התיקייה עודכנה", "Folder updated")) }
    }

    fun togglePinPlaylist(playlistId: String) {
        val cur = _uiState.value.playlists
        val target = cur.firstOrNull { it.id == playlistId } ?: return
        val s = _uiState.value.strings
        if (!target.isPinned && cur.count { it.isPinned } >= 4) {
            _uiState.update { it.copy(statusMessage = s.t("ניתן לנעוץ עד 4 פלייליסטים", "You can pin up to 4 playlists")) }
            return
        }
        savePlaylists(cur.map { if (it.id == playlistId) it.copy(isPinned = !it.isPinned) else it })
        _uiState.update { it.copy(statusMessage = if (!target.isPinned) s.t("ננעץ לראש הספרייה", "Pinned to top") else s.t("הוסרה הנעיצה", "Unpinned")) }
    }

    fun togglePlaylistFlag(playlistId: String, flag: String) {
        val s = _uiState.value.strings
        var msg = ""
        val updated = _uiState.value.playlists.map { pl ->
            if (pl.id != playlistId) pl else when (flag) {
                "public" -> { msg = if (!pl.isPublic) s.t("הפלייליסט הפך לציבורי", "Playlist is now Public") else s.t("הפלייליסט הפך לפרטי", "Playlist is now Private"); pl.copy(isPublic = !pl.isPublic) }
                "collab" -> { msg = if (!pl.isCollaborative) s.t("מצב שיתופי הופעל", "Collaborative mode ON") else s.t("מצב שיתופי כובה", "Collaborative mode OFF"); pl.copy(isCollaborative = !pl.isCollaborative) }
                "taste" -> { msg = if (!pl.excludeFromTaste) s.t("הוחרג מפרופיל הטעם", "Excluded from taste profile") else s.t("הוחזר לפרופיל הטעם", "Included in taste profile"); pl.copy(excludeFromTaste = !pl.excludeFromTaste) }
                "mix" -> { msg = if (!pl.isMixedMode) s.t("מעברי Mix הופעלו", "Mix transitions ON") else s.t("מעברי Mix כובו", "Mix transitions OFF"); pl.copy(isMixedMode = !pl.isMixedMode) }
                else -> pl
            }
        }
        savePlaylists(updated)
        if (msg.isNotBlank()) _uiState.update { it.copy(statusMessage = msg) }
    }

    fun updatePlaylistCover(playlistId: String, bgHex: Long, titleText: String, sticker: String) {
        savePlaylists(_uiState.value.playlists.map { pl -> if (pl.id == playlistId) pl.copy(customCoverBgHex = bgHex, customCoverText = titleText, customCoverSticker = sticker) else pl })
        _uiState.update { it.copy(editingCoverPlaylistId = null, statusMessage = it.strings.t("עטיפת הפלייליסט עודכנה!", "Cover art updated!")) }
    }

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
        _uiState.update { it.copy(trackForOptions = null, statusMessage = it.strings.t("הפלייליסטים עודכנו (${selectedPlaylistIds.size})", "Playlists updated (${selectedPlaylistIds.size})")) }
    }

    fun removeTrackFromPlaylist(playlistId: String, trackId: String) {
        savePlaylists(_uiState.value.playlists.map { pl -> if (pl.id == playlistId) pl.copy(tracks = pl.tracks.filterNot { it.id == trackId }) else pl })
        _uiState.update { it.copy(statusMessage = it.strings.t("השיר הוסר", "Track removed")) }
    }

    fun deletePlaylist(playlistId: String) {
        val target = _uiState.value.playlists.firstOrNull { it.id == playlistId } ?: return
        val newDeleted = (listOf(DeletedPlaylistRecord(target)) + _uiState.value.deletedPlaylists).take(15)
        prefs.edit().putString("deleted_playlists", encodeDeletedPlaylists(newDeleted)).apply()
        savePlaylists(_uiState.value.playlists.filterNot { it.id == playlistId })
        _uiState.update { it.copy(deletedPlaylists = newDeleted, openedPlaylist = null, statusMessage = it.strings.t("הפלייליסט נמחק (ניתן לשחזור)", "Playlist deleted (recoverable)")) }
    }

    fun recoverDeletedPlaylist(record: DeletedPlaylistRecord) {
        val newDeleted = _uiState.value.deletedPlaylists.filterNot { it.playlist.id == record.playlist.id }
        prefs.edit().putString("deleted_playlists", encodeDeletedPlaylists(newDeleted)).apply()
        savePlaylists(listOf(record.playlist) + _uiState.value.playlists)
        _uiState.update { it.copy(deletedPlaylists = newDeleted, statusMessage = it.strings.t("\"${record.playlist.name}\" שוחזר!", "Restored \"${record.playlist.name}\"!")) }
    }

    fun importPlaylist(name: String, rawInput: String) {
        val s = _uiState.value.strings
        if (rawInput.isBlank()) { _uiState.update { it.copy(statusMessage = s.t("הדבק קישור או רשימת שירים", "Paste a link or track list")) }; return }
        _uiState.update { it.copy(showImportSheet = false, isBuildingAiPlaylist = true, statusMessage = s.t("מייבא פלייליסט...", "Importing playlist...")) }
        viewModelScope.launch {
            val tracks = repo.importPlaylistFromTextOrUrl(rawInput, _uiState.value.settings)
            if (tracks.isEmpty()) { _uiState.update { it.copy(isBuildingAiPlaylist = false, statusMessage = s.t("לא הצלחנו לייבא שירים", "Could not import tracks")) }; return@launch }
            val pl = Playlist(name = name.trim().ifBlank { s.t("פלייליסט מיובא", "Imported Playlist") }, creator = s.t("ייבוא", "Import"), coverUrl = tracks.first().artworkUrl, tracks = tracks)
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update { it.copy(isBuildingAiPlaylist = false, openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = s.t("יובאו ${tracks.size} שירים בהצלחה!", "Imported ${tracks.size} tracks!")) }
        }
    }

    // ---------- Smarter AI Playlist Studio & Voice AI DJ ----------
    fun createAiPlaylist(config: AiPlaylistConfig) {
        val s = _uiState.value.strings
        if (config.prompt.isBlank()) { _uiState.update { it.copy(statusMessage = s.t("תאר איזה פלייליסט תרצה", "Describe your playlist")) }; return }
        _uiState.update { it.copy(showCreateSheet = false, isBuildingAiPlaylist = true, statusMessage = s.t("AI Studio בונה פלייליסט של ${config.targetCount} שירים...", "AI Studio building ${config.targetCount} tracks...")) }
        viewModelScope.launch {
            val (title, tracks) = repo.buildAiPlaylist(config, _uiState.value.settings, _uiState.value.likedTracks)
            if (tracks.isEmpty()) { _uiState.update { it.copy(isBuildingAiPlaylist = false, statusMessage = s.t("לא נמצאו שירים — נסה תיאור אחר", "No tracks found — try another prompt")) }; return@launch }
            val pl = Playlist(name = title, creator = "AI Studio", description = "${config.prompt} • ${config.energyPref} • ${config.eraPref}", coverUrl = tracks.first().artworkUrl, tracks = tracks, aiSeedPrompt = config.prompt, aiStyleTag = "AI STUDIO")
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update { it.copy(isBuildingAiPlaylist = false, openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = s.t("נבנה פלייליסט עם ${tracks.size} שירים!", "Built ${tracks.size}-track playlist!")) }
            playTrack(tracks.first(), tracks)
        }
    }

    fun startAiDj() {
        val st = _uiState.value; val s = st.strings
        _uiState.update { it.copy(showCreateSheet = false, isAiDjActive = true, statusMessage = s.t("AI DJ מכין לך סט אישי...", "AI DJ curating your set...")) }
        viewModelScope.launch {
            val topArtist = st.stats.artistPlayCounts.maxByOrNull { it.value }?.key ?: st.likedTracks.firstOrNull()?.artist
            val query = topArtist ?: "להיטים מובילים 2026"
            val fresh = repo.searchTracks(query, st.settings, maxResults = 25)
            val mix = (st.likedTracks.take(6) + st.recentTracks.take(5) + st.trendingSongs.take(8) + fresh).distinctBy { it.id }.shuffled().take(30)
            if (mix.isNotEmpty()) {
                val first = mix.first()
                playTrack(first, mix, keepDjActive = true)
                _uiState.update { it.copy(showNowPlaying = true, isAiDjActive = true) }
                val intro = if (s.isEn) "Hey! I'm your Sonify DJ. Starting things off with ${first.title} by ${first.artist}."
                else "היי! כאן הדי ג'יי של סוניפיי. מתחילים את הסט שלך עם ${first.title} של ${first.artist}."
                audioEngine.speakDjAnnouncement(intro, s.isEn)
            }
        }
    }

    fun switchAiDjVibe() {
        val vibes = listOf(
            Triple("אנרגיה ואימון", "High Energy & Workout", "gym workout high energy hits"),
            Triple("צ'יל ורוגע", "Chill & Relax", "chill lofi r&b vibes"),
            Triple("להיטים ישראליים", "Israeli Hits", "להיטים ישראליים חדשים"),
            Triple("היפ הופ וראפ", "Hip-Hop & Rap", "hip hop rap bangers"),
            Triple("רוק קלאסי ומודרני", "Rock Essentials", "best rock songs")
        )
        val nextIdx = (_uiState.value.djVibeIndex + 1) % vibes.size
        val (heName, enName, q) = vibes[nextIdx]
        val s = _uiState.value.strings
        _uiState.update { it.copy(djVibeIndex = nextIdx, statusMessage = "AI DJ • ${if (s.isEn) enName else heName}") }
        viewModelScope.launch {
            val tracks = repo.searchTracks(q, _uiState.value.settings, maxResults = 25).shuffled()
            if (tracks.isNotEmpty()) {
                playTrack(tracks.first(), tracks, keepDjActive = true)
                val line = if (s.isEn) "Switching the vibe to $enName. Up next, ${tracks.first().title}."
                else "מחליף אווירה ל$heName. השיר הבא הוא ${tracks.first().title}."
                audioEngine.speakDjAnnouncement(line, s.isEn)
            }
        }
    }

    // ---------- Blend, Live Room-Based Jam Sync (No Cloud Account!) & Friends Chat ----------
    private fun effectiveJamName(): String {
        val custom = _uiState.value.settings.jamUserDisplayName.trim()
        return if (custom.isNotBlank()) custom else _uiState.value.strings.t("אתה", "You")
    }

    fun createBlend(friendName: String) {
        val cleanFriend = friendName.trim().ifBlank { "חבר" }
        val s = _uiState.value.strings
        _uiState.update { it.copy(showCreateSheet = false, statusMessage = s.t("מרכיב Blend עם $cleanFriend...", "Building Blend with $cleanFriend...")) }
        viewModelScope.launch {
            val fresh = repo.searchTracks("top hits 2026", _uiState.value.settings, maxResults = 20)
            val tracks = (_uiState.value.likedTracks.take(10) + _uiState.value.trendingSongs.take(10) + fresh).distinctBy { it.id }.shuffled().take(25)
            val match = (76..98).random()
            val pl = Playlist(name = "Blend • $cleanFriend", creator = "Blend", description = "${s.t("התאמה מוזיקלית", "Taste Match")}: $match%", coverUrl = tracks.firstOrNull()?.artworkUrl ?: "", customCoverBgHex = 0xFF9C27B0, tracks = tracks, isBlend = true, blendMatchPercent = match, aiStyleTag = "BLEND")
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update { it.copy(openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = "Blend • $match% (${tracks.size})") }
        }
    }

    fun startJamSession() {
        val code = UUID.randomUUID().toString().take(6).uppercase()
        val st = _uiState.value
        val s = st.strings
        val smartUrl = SonifyLinkCodec.buildSmartJamLink(code, st.currentTrack, st.positionMs)
        val hostLabel = "${effectiveJamName()} (${s.t("מארח", "Host")})"

        _uiState.update {
            it.copy(
                jamState = JamState(
                    active = true, code = code, joinUrl = smartUrl, isHost = true,
                    guestsCanControlQueue = true, guestsCanControlVolume = false,
                    participants = listOf(hostLabel),
                    liveConnected = false,
                    syncStatus = if (it.settings.jamLiveSyncEnabled) s.t("מתחבר לחדר חי...", "Connecting live room...") else s.t("מצב קישור מקומי", "Local link mode")
                ),
                showCreateSheet = false,
                statusMessage = "Jam • $code"
            )
        }
        if (st.settings.jamLiveSyncEnabled) {
            connectLiveJamRoom(code, isHost = true)
        }
    }

    fun joinJamByCode(rawCode: String, initialVideoId: String? = null, initialStartSec: Int = 0) {
        val parsed = SonifyLinkCodec.parse(rawCode)
        val (code, vid, sec) = when (parsed) {
            is ParsedSonifyLink.JamLink -> Triple(parsed.code, initialVideoId ?: parsed.trackId, if (initialStartSec > 0) initialStartSec else parsed.startSec)
            else -> Triple(rawCode.trim().uppercase().removePrefix("SONIFY:JAM:").substringBefore("?"), initialVideoId, initialStartSec)
        }
        if (code.length < 4) return
        val st = _uiState.value
        val s = st.strings

        _uiState.update {
            it.copy(
                jamState = JamState(
                    active = true, code = code, joinUrl = "sonify:jam:$code", isHost = false,
                    guestsCanControlQueue = true,
                    participants = listOf(s.t("מארח ה-Jam", "Jam Host"), effectiveJamName()),
                    liveConnected = false,
                    syncStatus = if (it.settings.jamLiveSyncEnabled) s.t("מתחבר למארח...", "Syncing with host...") else s.t("קישור בלבד", "Link snapshot")
                ),
                statusMessage = s.t("הצטרפת ל-Jam ($code)!", "Joined Jam ($code)!")
            )
        }

        if (!vid.isNullOrBlank() && vid.length == 11) {
            val snapTrack = Track(vid, s.t("שיר מתוך Jam", "Jam Track"), "Jam • $code", artworkUrl = "https://i.ytimg.com/vi/$vid/maxresdefault.jpg")
            playTrack(snapTrack, listOf(snapTrack), startAtMs = sec * 1000L, fromRemoteJam = true)
        }

        if (st.settings.jamLiveSyncEnabled) {
            connectLiveJamRoom(code, isHost = false)
        }
    }

    private fun connectLiveJamRoom(code: String, isHost: Boolean) {
        val st = _uiState.value
        val myName = effectiveJamName()
        jamSync.connect(
            roomCode = code,
            serverBaseUrl = st.settings.jamSyncServerUrl,
            senderName = myName,
            onConnectionChanged = { connected ->
                viewModelScope.launch {
                    val s = _uiState.value.strings
                    _uiState.update { cur ->
                        if (!cur.jamState.active) cur
                        else cur.copy(
                            jamState = cur.jamState.copy(
                                liveConnected = connected,
                                syncStatus = if (connected) s.liveJamConnected else s.t("מתחבר מחדש...", "Reconnecting...")
                            )
                        )
                    }
                    if (connected) {
                        if (isHost) publishJamState("SYNC")
                        else jamSync.publish(JamRemoteEvent("REQ_SYNC", jamSync.clientId, myName, System.currentTimeMillis()))
                    }
                }
            },
            onEvent = { ev -> viewModelScope.launch { handleIncomingJamEvent(ev) } }
        )
    }

    private fun publishJamState(
        type: String,
        trackOverride: Track? = null,
        posOverride: Long? = null,
        playingOverride: Boolean? = null,
        volumeOverride: Float? = null
    ) {
        val st = _uiState.value
        if (!st.jamState.active || !st.settings.jamLiveSyncEnabled) return
        jamSync.publish(
            JamRemoteEvent(
                type = type,
                senderId = jamSync.clientId,
                senderName = effectiveJamName(),
                sentAtMs = System.currentTimeMillis(),
                track = trackOverride ?: st.currentTrack,
                positionMs = posOverride ?: st.positionMs,
                isPlaying = playingOverride ?: st.isPlaying,
                guestsCanControlQueue = st.jamState.guestsCanControlQueue,
                guestsCanControlVolume = st.jamState.guestsCanControlVolume,
                volumeFraction = volumeOverride
            )
        )
    }

    private fun handleIncomingJamEvent(ev: JamRemoteEvent) {
        val st = _uiState.value
        if (!st.jamState.active) return

        // Track participant list live
        if (ev.senderName.isNotBlank()) {
            _uiState.update { cur ->
                val list = when (ev.type) {
                    "LEAVE" -> cur.jamState.participants.filterNot { it == ev.senderName }
                    else -> (cur.jamState.participants + ev.senderName).distinct()
                }
                cur.copy(jamState = cur.jamState.copy(participants = list))
            }
        }

        if (ev.type == "JOIN" || ev.type == "REQ_SYNC") {
            if (st.jamState.isHost) publishJamState("SYNC")
            return
        }
        if (ev.type == "LEAVE") return

        // Update host permissions on guests
        if (!st.jamState.isHost) {
            _uiState.update { cur ->
                cur.copy(
                    jamState = cur.jamState.copy(
                        guestsCanControlQueue = ev.guestsCanControlQueue,
                        guestsCanControlVolume = ev.guestsCanControlVolume
                    )
                )
            }
        }

        isApplyingRemoteJam = true
        try {
            if (ev.type == "VOLUME" && ev.volumeFraction != null && (st.jamState.isHost || st.jamState.guestsCanControlVolume)) {
                audioEngine.setSystemVolumeFraction(ev.volumeFraction)
                return
            }

            val latencyMs = (System.currentTimeMillis() - ev.sentAtMs).coerceIn(0L, 4_000L)
            val targetPos = (ev.positionMs + if (ev.isPlaying) latencyMs else 0L).coerceAtLeast(0L)
            val toleranceMs = st.settings.jamSyncToleranceSec.coerceIn(1, 10) * 1000L

            val remoteTrack = ev.track
            if (remoteTrack != null && remoteTrack.id != st.currentTrack?.id) {
                playTrack(remoteTrack, listOf(remoteTrack), startAtMs = targetPos, fromRemoteJam = true)
            } else {
                val drift = abs(st.positionMs - targetPos)
                if (ev.type == "SEEK" || drift > toleranceMs) {
                    audioEngine.seekTo(targetPos)
                    _uiState.update { it.copy(positionMs = targetPos) }
                }
                if (ev.type == "PLAY" || ev.type == "PAUSE" || ev.type == "SYNC") {
                    if (ev.isPlaying != st.isPlaying) {
                        audioEngine.setPlaying(ev.isPlaying)
                    }
                }
            }
        } finally {
            isApplyingRemoteJam = false
        }
    }

    fun endJamSession() {
        jamSync.disconnect(effectiveJamName(), sendLeave = true)
        _uiState.update { it.copy(jamState = JamState(), statusMessage = it.strings.t("סשן ה-Jam הסתיים", "Jam session ended")) }
    }

    fun toggleJamPermission(perm: String) {
        _uiState.update { st ->
            val j = st.jamState
            st.copy(jamState = when (perm) {
                "queue" -> j.copy(guestsCanControlQueue = !j.guestsCanControlQueue)
                "volume" -> j.copy(guestsCanControlVolume = !j.guestsCanControlVolume)
                else -> j
            })
        }
        publishJamState("SYNC")
    }

    fun setVolumeFromDevicesSheet(fraction: Float) {
        audioEngine.setSystemVolumeFraction(fraction)
        val st = _uiState.value
        if (st.jamState.active && (st.jamState.isHost || st.jamState.guestsCanControlVolume)) {
            publishJamState("VOLUME", volumeOverride = fraction)
        }
    }

    fun inviteFriendToJam(friendName: String) {
        val jam = _uiState.value.jamState
        if (!jam.active) startJamSession()
        val activeJam = _uiState.value.jamState
        if (friendName !in activeJam.participants) {
            _uiState.update { it.copy(jamState = activeJam.copy(participants = activeJam.participants + friendName)) }
        }
        sendMessage(
            text = _uiState.value.strings.t("הזמנתי אותך ל-Jam חי! קוד: ${activeJam.code}", "Invited you to a Live Jam! Code: ${activeJam.code}"),
            friendThread = friendName,
            sharedJamCode = activeJam.code
        )
    }

    fun selectChatFriend(name: String) = _uiState.update { it.copy(activeChatFriend = name) }

    fun sendMessage(
        text: String,
        sharedTrack: Track? = null,
        timestampSec: Int? = null,
        friendThread: String = _uiState.value.activeChatFriend,
        sharedPlaylistName: String? = null,
        sharedJamCode: String? = null
    ) {
        if (text.isBlank() && sharedTrack == null && sharedJamCode == null) return
        val msg = ChatMessage(friendThread = friendThread, sender = _uiState.value.strings.t("אתה", "You"), text = text.trim(), sharedTrack = sharedTrack, sharedPlaylistName = sharedPlaylistName, sharedJamCode = sharedJamCode, trackTimestampSec = timestampSec)
        val updated = (_uiState.value.messages + msg).takeLast(80)
        prefs.edit().putString("messages", encodeMessages(updated)).apply()
        _uiState.update { it.copy(messages = updated, activeChatFriend = friendThread, statusMessage = if (sharedTrack != null || sharedJamCode != null) it.strings.t("נשלח ל-$friendThread", "Sent to $friendThread") else null) }
    }

    fun addFriend(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val updated = (listOf(FriendProfile(clean, _uiState.value.strings.t("מחובר עכשיו", "Online now"), true)) + _uiState.value.friends.filterNot { it.name.equals(clean, true) }).take(25)
        prefs.edit().putString("friends_v7", encodeFriends(updated)).apply()
        _uiState.update { it.copy(friends = updated, activeChatFriend = clean, statusMessage = it.strings.t("נוסף חבר: $clean", "Added friend: $clean")) }
    }

    fun removeFriend(name: String) {
        val updated = _uiState.value.friends.filterNot { it.name == name }
        prefs.edit().putString("friends_v7", encodeFriends(updated)).apply()
        _uiState.update { it.copy(friends = updated, activeChatFriend = if (it.activeChatFriend == name) "כללי" else it.activeChatFriend) }
    }

    fun setSleepTimer(minutes: Int) {
        audioEngine.startSleepTimer(minutes) { left -> _uiState.update { it.copy(sleepMinutesLeft = left, sleepEndOfTrack = false) } }
        val s = _uiState.value.strings
        _uiState.update { it.copy(sleepEndOfTrack = false, statusMessage = if (minutes > 0) s.t("טיימר שינה ל-$minutes דקות", "Sleep timer: $minutes min") else s.t("טיימר השינה בוטל", "Sleep timer off")) }
    }

    fun setSleepEndOfTrack() {
        audioEngine.setSleepEndOfTrack(true)
        _uiState.update { it.copy(sleepMinutesLeft = null, sleepEndOfTrack = true, statusMessage = it.strings.t("ייעצר בסיום השיר הנוכחי", "Stops at end of track")) }
    }

    fun retryLyrics(customQuery: String? = null) {
        val track = _uiState.value.currentTrack ?: return
        _uiState.update { it.copy(isLoadingLyrics = true, showTranslatedLyrics = false) }
        viewModelScope.launch {
            val lyrics = repo.fetchSyncedLyrics(track, customQuery)
            _uiState.update { it.copy(lyrics = lyrics, isLoadingLyrics = false) }
        }
    }

    // ---------- JSON Serialization ----------
    private fun saveStats(st: ListeningStats) {
        val obj = JSONObject().apply { put("totalMs", st.totalListeningMs); put("tracks", JSONObject(st.trackPlayCounts)); put("artists", JSONObject(st.artistPlayCounts)) }
        prefs.edit().putString("stats", obj.toString()).apply()
    }

    private fun decodeStats(raw: String): ListeningStats = runCatching {
        val o = JSONObject(raw)
        val tMap = mutableMapOf<String, Int>(); o.optJSONObject("tracks")?.let { jt -> jt.keys().forEach { k -> tMap[k] = jt.optInt(k) } }
        val aMap = mutableMapOf<String, Int>(); o.optJSONObject("artists")?.let { ja -> ja.keys().forEach { k -> aMap[k] = ja.optInt(k) } }
        ListeningStats(o.optLong("totalMs", 0L), tMap, aMap)
    }.getOrDefault(ListeningStats())

    private fun encodeFriends(list: List<FriendProfile>): String = JSONArray().apply {
        list.forEach { put(JSONObject().apply { put("n", it.name); put("a", it.currentActivity); put("o", it.isOnline) }) }
    }.toString()

    private fun decodeFriends(raw: String): List<FriendProfile> {
        if (raw.isBlank()) return listOf(
            FriendProfile("עומר", "מאזין לפופ ישראלי", true),
            FriendProfile("נועה", "מאזינה ל-Daylist", true),
            FriendProfile("דניאל", "לא מחובר", false)
        )
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { i -> a.optJSONObject(i) }.mapNotNull { o -> o?.let { FriendProfile(it.optString("n"), it.optString("a"), it.optBoolean("o", true)) } }
        }.getOrDefault(emptyList())
    }

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
        put("seed", p.aiSeedPrompt); put("tag", p.aiStyleTag); put("created", p.createdAt)
        put("tracks", encodeTracks(p.tracks.filterNot { it.isSmartInjected }))
    }

    private fun decodePlaylistObj(o: JSONObject): Playlist {
        val m = o.optInt("match", -1); val bg = o.optLong("bgHex", -1L)
        val trks = decodeTracks(o.optString("tracks", "[]"))
        return Playlist(o.optString("id"), o.optString("name"), o.optString("creator", "שלך"), o.optString("desc"), o.optString("cover").ifBlank { trks.firstOrNull()?.artworkUrl ?: "" }, if (bg != -1L) bg else null, o.optString("covTxt"), o.optString("covStk"), trks, o.optBoolean("blend"), if (m >= 0) m else null, isPublic = o.optBoolean("pub", true), isCollaborative = o.optBoolean("col"), isPinned = o.optBoolean("pin"), excludeFromTaste = o.optBoolean("excl"), isMixedMode = o.optBoolean("mix"), folderId = o.optString("folder").takeIf { it.isNotBlank() }, aiSeedPrompt = o.optString("seed"), aiStyleTag = o.optString("tag"), createdAt = o.optLong("created", System.currentTimeMillis()))
    }

    private fun encodePlaylists(list: List<Playlist>): String = JSONArray().apply { list.forEach { put(encodePlaylistObj(it)) } }.toString()
    private fun decodePlaylists(raw: String): List<Playlist> = runCatching { val arr = JSONArray(raw); buildList { for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { add(decodePlaylistObj(it)) } } }.getOrDefault(emptyList())

    private fun encodeDeletedPlaylists(list: List<DeletedPlaylistRecord>): String = JSONArray().apply { list.forEach { put(JSONObject().apply { put("pl", encodePlaylistObj(it.playlist)); put("delAt", it.deletedAt) }) } }.toString()
    private fun decodeDeletedPlaylists(raw: String): List<DeletedPlaylistRecord> = runCatching {
        val arr = JSONArray(raw); buildList { for (i in 0 until arr.length()) { val o = arr.optJSONObject(i) ?: continue; o.optJSONObject("pl")?.let { add(DeletedPlaylistRecord(decodePlaylistObj(it), o.optLong("delAt"))) } } }
    }.getOrDefault(emptyList())

    private fun encodeMessages(list: List<ChatMessage>): String = JSONArray().apply {
        list.forEach { m ->
            put(JSONObject().apply {
                put("id", m.id); put("th", m.friendThread); put("sender", m.sender); put("text", m.text); put("ts", m.timestampMs)
                put("tSec", m.trackTimestampSec ?: -1); put("plName", m.sharedPlaylistName ?: ""); put("jam", m.sharedJamCode ?: "")
                m.sharedTrack?.let { put("track", encodeTracks(listOf(it))) }
            })
        }
    }.toString()

    private fun decodeMessages(raw: String): List<ChatMessage> = runCatching {
        val arr = JSONArray(raw)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val tSec = o.optInt("tSec", -1)
                val trk = o.optString("track").takeIf { it.isNotBlank() }?.let { decodeTracks(it).firstOrNull() }
                add(ChatMessage(o.optString("id"), o.optString("th", "כללי"), o.optString("sender", "אתה"), o.optString("text"), trk, o.optString("plName").takeIf { it.isNotBlank() }, o.optString("jam").takeIf { it.isNotBlank() }, if (tSec >= 0) tSec else null, o.optLong("ts")))
            }
        }
    }.getOrDefault(emptyList())
}
