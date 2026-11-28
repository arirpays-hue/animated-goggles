package com.sonify.app

import android.app.Application
import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.net.Uri
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.Calendar
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlin.math.abs

val AppStrings.episodes: String get() = t("פרקים", "episodes")
val AppStrings.books: String get() = t("ספרים", "books")

/**
 * Cloudless real-time friend chat relay over public ntfy.sh rooms (zero accounts, zero servers to maintain).
 */
class CloudlessChatSync {
    val clientId: String = UUID.randomUUID().toString().take(8)

    private val sseClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val postClient = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var listenJob: Job? = null
    @Volatile private var activeRoom: String = ""
    @Volatile private var activeBaseUrl: String = "https://ntfy.sh"

    private fun topicFor(roomCode: String): String {
        val clean = roomCode.uppercase().replace(Regex("[^A-Z0-9]"), "")
        return "sonify_chat_v11_$clean"
    }

    fun connect(
        scope: kotlinx.coroutines.CoroutineScope,
        roomCode: String,
        serverBaseUrl: String,
        onConnectedChanged: (Boolean) -> Unit,
        onMessage: (ChatMessage) -> Unit
    ) {
        val cleanCode = roomCode.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (cleanCode.length < 4) {
            disconnect()
            onConnectedChanged(false)
            return
        }
        val base = serverBaseUrl.trim().trimEnd('/').ifBlank { "https://ntfy.sh" }
        if (activeRoom == cleanCode && activeBaseUrl == base && listenJob?.isActive == true) return

        disconnect()
        activeRoom = cleanCode
        activeBaseUrl = base

        listenJob = scope.launch(Dispatchers.IO) {
            val url = "$base/${topicFor(cleanCode)}/sse?since=10m"
            while (isActive && activeRoom == cleanCode) {
                try {
                    val req = Request.Builder().url(url).header("Accept", "text/event-stream").build()
                    sseClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) {
                            onConnectedChanged(false)
                            delay(2500)
                            return@use
                        }
                        onConnectedChanged(true)
                        val reader = BufferedReader(InputStreamReader(resp.body?.byteStream() ?: return@use))
                        var line: String? = null
                        while (isActive && reader.readLine().also { line = it } != null) {
                            val l = line ?: continue
                            if (!l.startsWith("data:")) continue
                            val raw = l.removePrefix("data:").trim()
                            if (raw.isBlank()) continue
                            runCatching {
                                val envelope = JSONObject(raw)
                                if (envelope.optString("event") != "message") return@runCatching
                                val payloadStr = envelope.optString("message")
                                if (payloadStr.isBlank()) return@runCatching
                                val o = JSONObject(payloadStr)
                                if (o.optString("cid") == clientId) return@runCatching
                                val trackObj = o.optJSONObject("trk")
                                val trk = trackObj?.let {
                                    Track(
                                        id = it.optString("id"),
                                        title = it.optString("t"),
                                        artist = it.optString("a"),
                                        artworkUrl = it.optString("art"),
                                        durationMs = it.optLong("d", 0L),
                                        source = StreamSource.YOUTUBE
                                    )
                                }
                                val ts = o.optInt("tSec", -1)
                                val msg = ChatMessage(
                                    id = o.optString("id", UUID.randomUUID().toString()),
                                    friendThread = "",
                                    sender = o.optString("sender", "חבר"),
                                    text = o.optString("text", ""),
                                    sharedTrack = trk,
                                    sharedPlaylistName = o.optString("plName").takeIf { it.isNotBlank() },
                                    sharedPlaylistCode = o.optString("plCode").takeIf { it.isNotBlank() },
                                    sharedJamCode = o.optString("jam").takeIf { it.isNotBlank() },
                                    trackTimestampSec = if (ts >= 0) ts else null,
                                    timestampMs = o.optLong("ts", System.currentTimeMillis())
                                )
                                onMessage(msg)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (_: Throwable) {
                    onConnectedChanged(false)
                    delay(2500)
                }
            }
            onConnectedChanged(false)
        }
    }

    fun send(scope: kotlinx.coroutines.CoroutineScope, roomCode: String, serverBaseUrl: String, msg: ChatMessage) {
        val cleanCode = roomCode.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (cleanCode.length < 4) return
        val base = serverBaseUrl.trim().trimEnd('/').ifBlank { "https://ntfy.sh" }
        scope.launch(Dispatchers.IO) {
            runCatching {
                val payload = JSONObject().apply {
                    put("cid", clientId)
                    put("id", msg.id)
                    put("sender", msg.sender)
                    put("text", msg.text)
                    put("ts", msg.timestampMs)
                    put("tSec", msg.trackTimestampSec ?: -1)
                    put("plName", msg.sharedPlaylistName ?: "")
                    put("plCode", msg.sharedPlaylistCode ?: "")
                    put("jam", msg.sharedJamCode ?: "")
                    msg.sharedTrack?.let { t ->
                        put("trk", JSONObject().apply {
                            put("id", t.id)
                            put("t", t.title)
                            put("a", t.artist)
                            put("art", t.artworkUrl)
                            put("d", t.durationMs)
                        })
                    }
                }
                val req = Request.Builder()
                    .url("$base/${topicFor(cleanCode)}")
                    .post(payload.toString().toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .build()
                postClient.newCall(req).execute().close()
            }
        }
    }

    fun disconnect() {
        activeRoom = ""
        listenJob?.cancel()
        listenJob = null
    }
}

data class UiState(
    val selectedTab: MainTab = MainTab.HOME,
    val showSettings: Boolean = false,
    val settingsSection: SettingsSection = SettingsSection.ROOT,
    val showNowPlaying: Boolean = false,
    val showCreateSheet: Boolean = false,
    val showDjStudioSheet: Boolean = false,
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
    val samplesFeed: SamplesFeedState? = null,
    val isLoadingPlaylist: Boolean = false,
    val isExpandingPlaylist: Boolean = false,
    val trackForOptions: Track? = null,
    val homeFilter: HomeFilter = HomeFilter.ALL,
    val isLoadingHome: Boolean = false,
    val isLoadingMoreHome: Boolean = false,
    val isBuildingAiPlaylist: Boolean = false,
    val isTestingAiApi: Boolean = false,
    val aiTestResult: Pair<Boolean, String>? = null,
    val liveAiModels: List<String> = emptyList(),
    val isFetchingAiModels: Boolean = false,
    val aiModelsError: String? = null,
    val isAiDjActive: Boolean = false,
    val activeDjStyleLabel: String = "",
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
    val searchArtists: List<SearchArtistResult> = emptyList(),
    val searchPlaylists: List<RemotePlaylist> = emptyList(),
    val hasSearched: Boolean = false,
    val isSearching: Boolean = false,

    // Library, Playlist Smart Shuffle, Background Downloads, Podcast Comments & Live Cloudless Chat
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
    val activeChatLiveConnected: Boolean = false,
    val messages: List<ChatMessage> = emptyList(),
    val podcastComments: Map<String, List<PodcastComment>> = emptyMap(),
    val stats: ListeningStats = ListeningStats(),
    val offlineTrackIds: Set<String> = emptySet(),
    val downloadingIds: Set<String> = emptySet(),
    val downloadProgress: Map<String, Int> = emptyMap(),
    val activeBulkDownloadPlaylistId: String? = null,
    val bulkDownloadDone: Int = 0,
    val bulkDownloadTotal: Int = 0,

    // Playback, Weak-Network Shield, Now Playing Extras & Lyrics
    val queue: List<Track> = emptyList(),
    val currentIndex: Int = -1,
    val currentTrack: Track? = null,
    val nowPlayingExtras: NowPlayingExtras? = null,
    val isLoadingNowPlayingExtras: Boolean = false,
    val resolvedStreams: ResolvedStreams? = null,
    val activeVideoClipId: String? = null,
    val isFindingVideoClip: Boolean = false,
    val isUsingWebEngine: Boolean = false,
    val isWeakNetworkActive: Boolean = false,
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
    val lyricsTranslateError: String? = null,
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
    private val chatSync = CloudlessChatSync()

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var retryAttemptForCurrent = 0
    private var lastWidgetPushMs = 0L
    private var lastWidgetTrackId = ""
    private var lastWidgetPlaying = false
    private var lastStatsTickMs = 0L
    private var lastCompletedDlVersion = -1
    private var searchJob: Job? = null
    private var homeJob: Job? = null
    private var infiniteHomeJob: Job? = null
    private var prefetchJob: Job? = null
    private var lyricsJob: Job? = null
    private var extrasJob: Job? = null
    private var samplesJob: Job? = null
    private var homeInfinitePage = 0
    @Volatile private var isApplyingRemoteJam = false

    private val officialVideoCache = ConcurrentHashMap<String, String>()
    private val resolvedStreamCache = ConcurrentHashMap<String, Pair<ResolvedStreams, Long>>()
    private val translatedLyricsCache = ConcurrentHashMap<String, List<LyricLine>>()
    private val nowPlayingExtrasCache = ConcurrentHashMap<String, NowPlayingExtras>()

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

        viewModelScope.launch {
            OfflineDownloadCoordinator.state.collect { dl ->
                val versionChanged = dl.completedVersion != lastCompletedDlVersion
                if (versionChanged) lastCompletedDlVersion = dl.completedVersion
                val msg = dl.lastMessage
                if (msg != null) OfflineDownloadCoordinator.clearLastMessage()

                _uiState.update { cur ->
                    cur.copy(
                        downloadingIds = dl.downloadingIds,
                        downloadProgress = dl.downloadProgress,
                        activeBulkDownloadPlaylistId = dl.activeBulkPlaylistId,
                        bulkDownloadDone = dl.bulkDone,
                        bulkDownloadTotal = dl.bulkTotal,
                        offlineTrackIds = if (versionChanged) repo.getOfflineTrackIds() else cur.offlineTrackIds,
                        storageRefreshKey = if (versionChanged) cur.storageRefreshKey + 1 else cur.storageRefreshKey,
                        statusMessage = msg ?: cur.statusMessage
                    )
                }
            }
        }

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
        pushWidgetQuickTiles()
    }

    override fun onCleared() {
        jamSync.disconnect(effectiveJamName(), sendLeave = true)
        chatSync.disconnect()
        audioEngine.releaseLocks()
        super.onCleared()
    }

    private fun pushWidgetAsync(title: String, artist: String, artworkUrl: String, isPlaying: Boolean, isLiked: Boolean, permille: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            WidgetActionBridge.updateWidget(getApplication(), title, artist, artworkUrl, isPlaying, isLiked, permille)
        }
    }

    private fun pushWidgetQuickTiles() {
        viewModelScope.launch(Dispatchers.IO) {
            val st = _uiState.value
            val tiles = buildList {
                st.playlists.take(2).forEach { pl ->
                    val art = pl.artistCoverUrl.ifBlank { pl.coverUrl }
                    add(WidgetQuickTile("pl_${pl.id}", pl.name, art, playlistId = pl.id))
                }
                st.recentTracks.take(4).forEach { trk ->
                    if (size < 4) add(WidgetQuickTile("tr_${trk.id}", trk.title, trk.artworkUrl, trackId = trk.id))
                }
            }.take(4)

            val app = getApplication<Application>()
            tiles.forEach { t ->
                if (t.artworkUrl.isNotBlank()) {
                    runCatching {
                        val req = ImageRequest.Builder(app)
                            .data(upgradeArtworkUrlGlobal(t.artworkUrl))
                            .allowHardware(false)
                            .size(144)
                            .build()
                        val bmp = ((app.imageLoader.execute(req) as? SuccessResult)?.drawable as? BitmapDrawable)?.bitmap
                        if (bmp != null) WidgetActionBridge.setTileBitmap(t.artworkUrl, bmp)
                    }
                }
            }
            WidgetActionBridge.updateQuickTiles(app, tiles)
        }
    }

    private fun loadSavedState() {
        val schemaVer = prefs.getInt("schema_version", 1)
        val lang = runCatching { AppLanguage.valueOf(prefs.getString("lang", AppLanguage.HEBREW.name)!!) }.getOrDefault(AppLanguage.HEBREW)
        val source = runCatching { StreamSource.valueOf(prefs.getString("stream_source", StreamSource.YOUTUBE.name)!!) }.getOrDefault(StreamSource.YOUTUBE)
        val ytMode = runCatching { YouTubeEngineMode.valueOf(prefs.getString("yt_engine_mode", YouTubeEngineMode.AUTO.name)!!) }.getOrDefault(YouTubeEngineMode.AUTO)
        val wifiQ = source.clampQuality(runCatching { AudioQuality.valueOf(prefs.getString("wifi_q", AudioQuality.VERY_HIGH.name)!!) }.getOrDefault(AudioQuality.VERY_HIGH))
        val cellQ = source.clampQuality(runCatching { AudioQuality.valueOf(prefs.getString("cell_q", AudioQuality.HIGH.name)!!) }.getOrDefault(AudioQuality.HIGH))
        val dlQ = source.clampQuality(runCatching { AudioQuality.valueOf(prefs.getString("dl_q", AudioQuality.HIGH.name)!!) }.getOrDefault(AudioQuality.HIGH))
        val dlMethod = runCatching { DownloadMethod.valueOf(prefs.getString("dl_method", DownloadMethod.AUTO_SMART.name)!!) }.getOrDefault(DownloadMethod.AUTO_SMART)
        val normLvl = runCatching { NormalizationLevel.valueOf(prefs.getString("norm_lvl", NormalizationLevel.NORMAL.name)!!) }.getOrDefault(NormalizationLevel.NORMAL)
        val dlSpeed = runCatching { DownloadSpeedMode.valueOf(prefs.getString("dl_speed", DownloadSpeedMode.TURBO.name)!!) }.getOrDefault(DownloadSpeedMode.TURBO)
        val bufProf = runCatching { AudioBufferProfile.valueOf(prefs.getString("buffer_profile", AudioBufferProfile.HIGH_STABILITY.name)!!) }.getOrDefault(AudioBufferProfile.HIGH_STABILITY)
        val lyricsLang = runCatching { LyricsLanguage.valueOf(prefs.getString("lyrics_lang", LyricsLanguage.HEBREW.name)!!) }.getOrDefault(LyricsLanguage.HEBREW)
        val aiProvider = runCatching { AiProvider.valueOf(prefs.getString("ai_provider", AiProvider.GEMINI.name)!!) }.getOrDefault(AiProvider.GEMINI)

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
            downloadMethod = dlMethod,
            autoAdjustQuality = prefs.getBoolean("auto_adj_q", true),
            subsonicUrl = prefs.getString("subsonic_url", "") ?: "",
            subsonicUser = prefs.getString("subsonic_user", "") ?: "",
            subsonicPass = prefs.getString("subsonic_pass", "") ?: "",
            aiProvider = aiProvider,
            aiModelId = prefs.getString("ai_model_id", "") ?: "",
            aiCustomEndpoint = prefs.getString("ai_custom_endpoint", "") ?: "",
            geminiApiKey = prefs.getString("gemini_key", "") ?: "",
            djVoiceEnabled = prefs.getBoolean("dj_voice", true),
            djIntroVoice = prefs.getBoolean("dj_intro_voice", true),
            djBetweenTracksVoice = prefs.getBoolean("dj_between_voice", true),
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
        val friends = decodeFriends(prefs.getString("friends_v11", prefs.getString("friends_v7", "") ?: "") ?: "")
        val msgs = decodeMessages(prefs.getString("messages", "[]") ?: "[]")
        val podComments = decodePodcastComments(prefs.getString("podcast_comments", "{}") ?: "{}")
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
                searchHistory = searchHist, friends = friends, messages = msgs,
                podcastComments = podComments, stats = stats,
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

            val stNow = _uiState.value
            val tasteSeeds = (
                stNow.followedArtists.toList() +
                stNow.stats.artistPlayCounts.entries.sortedByDescending { it.value }.map { it.key } +
                stNow.likedTracks.map { it.artist } +
                stNow.recentTracks.map { it.artist }
            ).filter { it.length >= 2 && stNow.excludedArtistsFromTaste.none { ex -> it.contains(ex, true) } }.distinct()

            val seed1 = tasteSeeds.getOrNull(0)
            val seed2 = tasteSeeds.getOrNull(1)
            val seed3 = tasteSeeds.getOrNull(2)

            val (q1, q2, q3, q4, q5) = when (filter) {
                HomeFilter.AUDIOBOOKS -> Quint("best self help audiobook", "sci fi fantasy audiobook", "history biography audiobook", "mystery thriller audiobook", "philosophy audiobook")
                HomeFilter.PODCASTS -> Quint("פודקאסט ישראלי מומלץ", "פודקאסט מדע וטכנולוגיה", "פודקאסט היסטוריה", "tech business podcast", "comedy talk podcast")
                else -> Quint(
                    seed1?.let { "$it top hits" } ?: "להיטים ישראליים",
                    seed2?.let { "$it similar artists hits" } ?: "The Weeknd Dua Lipa pop",
                    seed3?.let { "$it best songs" } ?: "hip hop rap hits",
                    seed1?.let { "$it discovery indie" } ?: "indie alternative rock",
                    "new music releases 2026"
                )
            }

            val d1 = async { runCatching { repo.searchTracks(q1, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val d2 = async { runCatching { repo.searchTracks(q2, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val d3 = async { runCatching { repo.searchTracks(q3, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val d4 = async { runCatching { repo.searchTracks(q4, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val d5 = async { runCatching { repo.searchTracks(q5, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val dDay = async { runCatching { repo.searchTracks(dayQuery, s, isPod, isBook, maxResults = 25) }.getOrDefault(emptyList()) }
            val dYtPls = async {
                if (!isPod && !isBook && s.showYoutubePlaylistsHome) {
                    val ytQs = buildList {
                        if (seed1 != null) add("$seed1 mix playlist")
                        if (seed2 != null) add("$seed2 similar playlist")
                        if (seed1 != null && seed2 != null) add("$seed1 $seed2 playlist")
                        add("top hits 2026 playlist")
                    }
                    runCatching { repo.fetchReadyYoutubePlaylists(ytQs, maxTotal = 12) }.getOrDefault(emptyList())
                } else {
                    emptyList<RemotePlaylist>()
                }
            }

            val list1 = filterByTaste(d1.await())
            val list2 = filterByTaste(d2.await())
            val list3 = filterByTaste(d3.await())
            val list4 = filterByTaste(d4.await())
            val list5 = filterByTaste(d5.await())
            val dayT = filterByTaste(dDay.await())
            val ytPls = dYtPls.await()

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
                    val mix1Title = seed1?.let { "Daily Mix 1 • $it" } ?: if (isEn) "Daily Mix 1 • Israeli" else "Daily Mix 1 • ישראלי"
                    val mix2Title = seed2?.let { "Daily Mix 2 • $it" } ?: if (isEn) "Daily Mix 2 • Global Pop" else "Daily Mix 2 • פופ עולמי"
                    val mix3Title = seed3?.let { "Daily Mix 3 • $it" } ?: if (isEn) "Daily Mix 3 • Hip-Hop" else "Daily Mix 3 • היפ-הופ"
                    if (list1.isNotEmpty()) add(Playlist("home_daily_1", mix1Title, "Sonify", "", list1.first().artworkUrl, tracks = list1, isRemote = true, aiSeedPrompt = q1, aiStyleTag = "DAILY 1"))
                    if (list2.isNotEmpty()) add(Playlist("home_daily_2", mix2Title, "Sonify", "", list2.first().artworkUrl, tracks = list2, isRemote = true, aiSeedPrompt = q2, aiStyleTag = "DAILY 2"))
                    if (list3.isNotEmpty()) add(Playlist("home_daily_3", mix3Title, "Sonify", "", list3.first().artworkUrl, tracks = list3, isRemote = true, aiSeedPrompt = q3, aiStyleTag = "DAILY 3"))
                    if (list4.isNotEmpty()) add(Playlist("home_discover", "Discover Weekly", "Sonify", "", list4.first().artworkUrl, tracks = list4, isRemote = true, aiSeedPrompt = q4, aiStyleTag = "DISCOVER"))
                    if (list5.isNotEmpty()) add(Playlist("home_radar", "Release Radar", "Sonify", "", list5.first().artworkUrl, tracks = list5, isRemote = true, aiSeedPrompt = q5, aiStyleTag = "NEW"))
                }
            }

            val dayTag = when (filter) {
                HomeFilter.AUDIOBOOKS -> "AUDIOBOOK"
                HomeFilter.PODCASTS -> "PODCAST"
                else -> "DAYLIST"
            }
            val dayPlaylist = if (dayT.isNotEmpty()) Playlist("home_daylist_${filter.name}", dayTitle, "Sonify", "", dayT.first().artworkUrl, tracks = dayT, isRemote = true, aiSeedPrompt = dayQuery, aiStyleTag = dayTag) else null

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
                followedArtists = st.followedArtists,
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
        val offlineTracks = (state.recentTracks + state.likedTracks + state.playlists.flatMap { it.tracks } + state.queue)
            .distinctBy { it.id }
            .filter { it.id in offlineIds && it.source != StreamSource.LOCAL }
        if (offlineTracks.isEmpty()) return null
        return Playlist("offline_backup", if (state.strings.isEn) "Offline Backup" else "Offline Backup • גיבוי אוטומטי", "Sonify", "", offlineTracks.first().artworkUrl, customCoverBgHex = 0xFF006450, tracks = offlineTracks, isRemote = true, aiStyleTag = "OFFLINE")
    }

    private fun buildOnRepeatPlaylist(): Playlist? {
        val state = _uiState.value
        val pool = (state.recentTracks + state.likedTracks).distinctBy { it.id }
            .let { list -> if (state.settings.offlineMode) list.filter { it.id in state.offlineTrackIds || it.source == StreamSource.LOCAL } else list }
        val sorted = pool.sortedByDescending { state.stats.trackPlayCounts[it.id] ?: 0 }.take(25)
        if (sorted.size < 2) return null
        return Playlist("on_repeat", if (state.strings.isEn) "On Repeat" else "On Repeat • חרשת עליהם", "Sonify", "", sorted.first().artworkUrl, customCoverBgHex = 0xFFE8115B, tracks = sorted, isRemote = true, aiStyleTag = "REPEAT")
    }

    private fun buildRepeatRewindPlaylist(): Playlist? {
        val state = _uiState.value
        val pool = state.likedTracks.reversed().take(25)
        if (pool.size < 3) return null
        return Playlist("repeat_rewind", if (state.strings.isEn) "Repeat Rewind" else "Repeat Rewind • נוסטלגיה", "Sonify", "", pool.first().artworkUrl, customCoverBgHex = 0xFF1E3264, tracks = pool, isRemote = true, aiStyleTag = "REWIND")
    }

    private fun buildOfflineBackupAndSpecialMixes() {
        val st = _uiState.value
        val offlineIds = st.offlineTrackIds
        val playableOffline = (st.likedTracks + st.recentTracks + st.playlists.flatMap { it.tracks } + st.localAudioTracks)
            .distinctBy { it.id }
            .filter { it.id in offlineIds || it.source == StreamSource.LOCAL }
        _uiState.update {
            it.copy(
                isLoadingHome = false,
                homeMixPlaylists = listOfNotNull(buildOfflineBackupPlaylist(), buildOnRepeatPlaylist()),
                readyYoutubePlaylists = emptyList(),
                infiniteSections = emptyList(),
                daylistPlaylist = null,
                trendingSongs = playableOffline.take(20)
            )
        }
    }

    // ---------- Vertical Song Samples Feed ("דומה ל..." / "שירים של...") ----------
    fun openSamplesFeed(mode: String, seedTrack: Track) {
        val st = _uiState.value
        val s = st.strings
        if (st.settings.offlineMode) {
            _uiState.update { it.copy(statusMessage = s.t("דגימות שירים דורשות חיבור לאינטרנט", "Song samples require internet")) }
            return
        }
        val title = when (mode) {
            "ARTIST_SONGS" -> seedTrack.artist
            "SIMILAR_ARTIST" -> s.t("דומה ל-${seedTrack.artist}", "Similar to ${seedTrack.artist}")
            else -> s.t("דומה ל-${seedTrack.title}", "Similar to ${seedTrack.title}")
        }
        val subtitle = when (mode) {
            "ARTIST_SONGS" -> s.t("שירים של ${seedTrack.artist}", "Songs by ${seedTrack.artist}")
            "SIMILAR_ARTIST" -> s.t("אמנים דומים", "Similar artists")
            else -> s.t("שירים דומים", "Similar songs")
        }

        _uiState.update {
            it.copy(
                samplesFeed = SamplesFeedState(
                    title = title,
                    subtitle = subtitle,
                    sourceArtist = seedTrack.artist,
                    tracks = emptyList(),
                    isLoading = true,
                    isMuted = false
                )
            )
        }

        samplesJob?.cancel()
        samplesJob = viewModelScope.launch {
            val found = repo.fetchSamplesForCard(mode, seedTrack, _uiState.value.settings)
            _uiState.update { cur ->
                val active = cur.samplesFeed ?: return@update cur
                cur.copy(samplesFeed = active.copy(tracks = found, isLoading = false))
            }
        }
    }

    fun playSampleTrack(track: Track) {
        val muted = _uiState.value.samplesFeed?.isMuted == true
        audioEngine.setMuted(muted)
        // Jump straight to the hook/chorus (~30% of track or 35 seconds) just like Spotify Samples!
        val hookStartMs = when {
            track.durationMs > 90_000L -> (track.durationMs * 0.28f).toLong().coerceIn(22_000L, 55_000L)
            track.durationMs > 45_000L -> 15_000L
            else -> 0L
        }
        playTrack(track, listOf(track), startAtMs = hookStartMs)
    }

    fun playFullTrackFromSample(track: Track, list: List<Track>) {
        audioEngine.setMuted(false)
        _uiState.update { it.copy(samplesFeed = null) }
        playTrack(track, list, startAtMs = 0L)
        setShowNowPlaying(true)
    }

    fun toggleSamplesMute() {
        val cur = _uiState.value.samplesFeed ?: return
        val newMuted = !cur.isMuted
        audioEngine.setMuted(newMuted)
        _uiState.update { it.copy(samplesFeed = cur.copy(isMuted = newMuted)) }
    }

    fun closeSamplesFeed() {
        samplesJob?.cancel()
        audioEngine.setMuted(false)
        _uiState.update { it.copy(samplesFeed = null) }
    }

    // ---------- Podcast Specific Controls & Comments ----------
    fun skipByDelta(deltaMs: Long) {
        val st = _uiState.value
        val maxDur = st.durationMs.takeIf { it > 0L } ?: Long.MAX_VALUE
        val target = (st.positionMs + deltaMs).coerceIn(0L, maxDur)
        seekTo(target)
    }

    fun addPodcastComment(trackId: String, text: String, includeTimestamp: Boolean) {
        val clean = text.trim()
        if (clean.isBlank()) return
        val s = _uiState.value.strings
        val tsSec = if (includeTimestamp) (_uiState.value.positionMs / 1000L).toInt() else null
        val comment = PodcastComment(
            author = effectiveJamName(),
            text = clean,
            timeAgo = s.t("עכשיו", "Just now"),
            likes = 1,
            timestampSec = tsSec
        )
        val curMap = _uiState.value.podcastComments.toMutableMap()
        val updatedList = (listOf(comment) + curMap[trackId].orEmpty()).take(40)
        curMap[trackId] = updatedList
        prefs.edit().putString("podcast_comments", encodePodcastComments(curMap)).apply()
        _uiState.update { it.copy(podcastComments = curMap, statusMessage = s.t("התגובה פורסמה!", "Comment posted!")) }
    }

    fun likePodcastComment(trackId: String, commentId: String) {
        val curMap = _uiState.value.podcastComments.toMutableMap()
        val list = curMap[trackId] ?: return
        curMap[trackId] = list.map { if (it.id == commentId) it.copy(likes = it.likes + 1) else it }
        prefs.edit().putString("podcast_comments", encodePodcastComments(curMap)).apply()
        _uiState.update { it.copy(podcastComments = curMap) }
    }

    // ---------- Navigation & UI State ----------
    fun selectTab(tab: MainTab) = _uiState.update { it.copy(selectedTab = tab, showSettings = false, settingsSection = SettingsSection.ROOT, openedPlaylist = null, openedArtist = null, openedArtistProfile = null) }
    fun setShowSettings(show: Boolean) = _uiState.update { it.copy(showSettings = show, settingsSection = SettingsSection.ROOT) }
    fun setSettingsSection(section: SettingsSection) = _uiState.update { it.copy(settingsSection = section) }
    fun openAiApiSettings() = _uiState.update { it.copy(showNowPlaying = false, showSettings = true, settingsSection = SettingsSection.AI_NOTIFICATIONS) }
    fun setShowNowPlaying(show: Boolean) = _uiState.update { it.copy(showNowPlaying = show) }
    fun setShowCreateSheet(show: Boolean) = _uiState.update { it.copy(showCreateSheet = show) }
    fun setShowDjStudioSheet(show: Boolean) = _uiState.update { it.copy(showDjStudioSheet = show, showCreateSheet = if (show) false else it.showCreateSheet) }
    fun setShowDevicesSheet(show: Boolean) = _uiState.update { it.copy(showDevicesSheet = show) }
    fun setShowStatsSheet(show: Boolean) = _uiState.update { it.copy(showStatsSheet = show) }
    fun setShowMessagesSheet(show: Boolean) {
        _uiState.update { it.copy(showMessagesSheet = show) }
        if (show) {
            connectActiveChatFriendIfRoom()
        } else {
            chatSync.disconnect()
            _uiState.update { it.copy(activeChatLiveConnected = false) }
        }
    }
    fun setShowHistorySheet(show: Boolean) = _uiState.update { it.copy(showHistorySheet = show) }
    fun setShowImportSheet(show: Boolean) = _uiState.update { it.copy(showImportSheet = show) }
    fun setShowRecoverSheet(show: Boolean) = _uiState.update { it.copy(showRecoverSheet = show) }
    fun setShowCodeFor(item: Pair<String, String>?) = _uiState.update { it.copy(showCodeSheetFor = item) }
    fun setEditingCoverPlaylist(id: String?) = _uiState.update { it.copy(editingCoverPlaylistId = id) }
    fun openPlaylist(playlist: Playlist?) = _uiState.update { it.copy(openedPlaylist = playlist, openedArtist = null, openedArtistProfile = null, showSettings = false) }
    fun closeArtistPage() = _uiState.update { it.copy(openedArtist = null, openedArtistProfile = null) }
    fun openTrackOptions(track: Track?) = _uiState.update { it.copy(trackForOptions = track) }
    fun clearStatus() = _uiState.update { it.copy(statusMessage = null) }
    fun clearLyricsTranslateError() = _uiState.update { it.copy(lyricsTranslateError = null) }
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

    // ---------- AI API Connection Test, Live Model Fetcher, Cache & Reset ----------
    fun testAiApiConnection() {
        if (_uiState.value.isTestingAiApi) return
        _uiState.update { it.copy(isTestingAiApi = true, aiTestResult = null) }
        viewModelScope.launch {
            val res = repo.testAiConnection(_uiState.value.settings)
            _uiState.update { it.copy(isTestingAiApi = false, aiTestResult = res, statusMessage = res.second) }
        }
    }

    fun fetchLiveAiModels() {
        if (_uiState.value.isFetchingAiModels) return
        _uiState.update { it.copy(isFetchingAiModels = true, aiModelsError = null) }
        viewModelScope.launch {
            val (models, err) = repo.aiClient.fetchAvailableModels(_uiState.value.settings)
            val s = _uiState.value.strings
            _uiState.update {
                it.copy(
                    isFetchingAiModels = false,
                    liveAiModels = models,
                    aiModelsError = err,
                    statusMessage = if (models.isNotEmpty()) s.t("נטענו ${models.size} מודלים חיים מה-API!", "Loaded ${models.size} live models!") else err
                )
            }
        }
    }

    fun clearLyricsCache() {
        repo.clearLyricsCache()
        translatedLyricsCache.clear()
        val s = _uiState.value.strings
        _uiState.update { it.copy(statusMessage = s.t("מטמון מילות השירים נוקה", "Lyrics cache cleared")) }
    }

    fun resetSettingsToDefaults() {
        val cur = _uiState.value.settings
        val fresh = AppSettings(
            language = cur.language,
            aiProvider = cur.aiProvider,
            aiModelId = cur.aiProvider.defaultModel,
            aiCustomEndpoint = cur.aiCustomEndpoint,
            geminiApiKey = cur.geminiApiKey,
            jamUserDisplayName = cur.jamUserDisplayName
        )
        updateSettings(fresh)
        _uiState.update { it.copy(statusMessage = it.strings.t("ההגדרות אופסו לברירת המחדל", "Settings reset to defaults")) }
    }

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
            val tracks = repo.fetchPlaylistTracks(
                playlistId = release.id,
                maxTracks = 60,
                fallbackCoverUrl = release.artworkUrl,
                fallbackArtist = artistName
            )
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

    // ---------- Complete Liked Songs & Smart Library Virtual Playlists ----------
    fun openLikedSongsPlaylist() {
        val st = _uiState.value; val s = st.strings
        openPlaylist(Playlist("virtual_liked", s.likedSongs, "Sonify", "${st.likedTracks.size} ${s.songs}", st.likedTracks.firstOrNull()?.artworkUrl ?: "", 0xFF503750, customCoverSticker = "♥", tracks = st.likedTracks, isRemote = true, aiStyleTag = "LIKED"))
    }

    fun exportLikedSongsToPlaylist(customName: String = "") {
        val st = _uiState.value
        val s = st.strings
        if (st.likedTracks.isEmpty()) {
            _uiState.update { it.copy(statusMessage = s.t("אין שירים שאהבת לייצוא", "No liked songs to export")) }
            return
        }
        val name = customName.trim().ifBlank { s.t("השירים שאהבתי • אוסף", "My Liked Collection") }
        val pl = Playlist(
            name = name,
            creator = effectiveJamName(),
            description = s.t("יוצא מתוך השירים שאהבת (${st.likedTracks.size} שירים)", "Exported from Liked Songs (${st.likedTracks.size} tracks)"),
            coverUrl = st.likedTracks.firstOrNull()?.artworkUrl ?: "",
            customCoverBgHex = 0xFF503750,
            tracks = st.likedTracks
        )
        savePlaylists(listOf(pl) + st.playlists)
        _uiState.update {
            it.copy(
                openedPlaylist = pl,
                selectedTab = MainTab.LIBRARY,
                statusMessage = s.t("נוצר פלייליסט \"$name\" עם ${pl.tracks.size} שירים!", "Exported \"$name\" (${pl.tracks.size} tracks)!")
            )
        }
    }

    fun sortLikedSongs(mode: String) {
        val cur = _uiState.value.likedTracks
        if (cur.size < 2) return
        val s = _uiState.value.strings
        val sorted = when (mode) {
            "title" -> cur.sortedBy { it.title.lowercase() }
            "artist" -> cur.sortedBy { it.artist.lowercase() }
            "recent" -> cur.sortedByDescending { it.addedAt }
            "oldest" -> cur.sortedBy { it.addedAt }
            "shuffle" -> cur.shuffled()
            else -> cur
        }
        prefs.edit().putString("liked_tracks", encodeTracks(sorted)).apply()
        _uiState.update { st ->
            val updatedOpen = if (st.openedPlaylist?.id == "virtual_liked") st.openedPlaylist.copy(tracks = sorted) else st.openedPlaylist
            st.copy(likedTracks = sorted, openedPlaylist = updatedOpen, statusMessage = s.t("השירים שאהבת מוינו", "Liked songs sorted"))
        }
    }

    fun removeDuplicatesFromLikedSongs() {
        val cur = _uiState.value.likedTracks
        val s = _uiState.value.strings
        val seenKeys = mutableSetOf<String>()
        val deduped = cur.filter { t ->
            val key = "${t.title.lowercase().replace(Regex("[^a-z0-9א-ת]"), "")}_${t.artist.lowercase().take(12)}"
            seenKeys.add(key)
        }
        val removed = cur.size - deduped.size
        if (removed <= 0) {
            _uiState.update { it.copy(statusMessage = s.t("לא נמצאו כפילויות בשירים שאהבת", "No duplicates found")) }
            return
        }
        prefs.edit().putString("liked_tracks", encodeTracks(deduped)).apply()
        _uiState.update { st ->
            val updatedOpen = if (st.openedPlaylist?.id == "virtual_liked") st.openedPlaylist.copy(tracks = deduped) else st.openedPlaylist
            st.copy(likedTracks = deduped, openedPlaylist = updatedOpen, statusMessage = s.t("הוסרו $removed כפילויות!", "Removed $removed duplicates!"))
        }
    }

    fun clearAllLikedSongs() {
        prefs.edit().putString("liked_tracks", "[]").apply()
        val s = _uiState.value.strings
        _uiState.update { st ->
            val updatedOpen = if (st.openedPlaylist?.id == "virtual_liked") st.openedPlaylist.copy(tracks = emptyList()) else st.openedPlaylist
            st.copy(likedTracks = emptyList(), openedPlaylist = updatedOpen, statusMessage = s.t("רשימת השירים שאהבת נוקתה", "Liked songs cleared"))
        }
    }

    fun openOfflineDownloadsPlaylist() {
        val st = _uiState.value; val s = st.strings
        val offTracks = (st.likedTracks + st.recentTracks + st.playlists.flatMap { it.tracks } + st.queue)
            .distinctBy { it.id }
            .filter { it.id in st.offlineTrackIds && it.source != StreamSource.LOCAL }
        openPlaylist(Playlist("virtual_offline", s.offlineDownloads, "Sonify", "${offTracks.size} ${s.songs}", offTracks.firstOrNull()?.artworkUrl ?: "", 0xFF006450, customCoverSticker = "⬇", tracks = offTracks, isRemote = true, aiStyleTag = "OFFLINE"))
    }

    fun openLocalFilesPlaylist() {
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { repo.searchLocalAudio("") }
            val s = _uiState.value.strings
            _uiState.update { it.copy(localAudioTracks = found) }
            openPlaylist(
                Playlist(
                    id = "virtual_local",
                    name = s.t("קבצי שמע במכשיר", "Device Audio Files"),
                    creator = s.t("אחסון מקומי • ללא אינטרנט", "Local Storage • Offline"),
                    description = "${found.size} ${s.songs}",
                    coverUrl = found.firstOrNull()?.artworkUrl ?: "",
                    customCoverBgHex = 0xFF1E3264,
                    customCoverSticker = "♫",
                    tracks = found,
                    isRemote = true,
                    aiStyleTag = "LOCAL"
                )
            )
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

        if (newSettings.offlineMode && _uiState.value.isVideoMode) {
            _uiState.update { it.copy(isVideoMode = false, isFindingVideoClip = false) }
        }

        val providerOrKeyChanged = newSettings.aiProvider != prev.aiProvider || newSettings.geminiApiKey != prev.geminiApiKey
        _uiState.update {
            it.copy(
                settings = newSettings,
                aiTestResult = if (providerOrKeyChanged || newSettings.aiModelId != prev.aiModelId) null else it.aiTestResult,
                liveAiModels = if (providerOrKeyChanged) emptyList() else it.liveAiModels,
                aiModelsError = if (providerOrKeyChanged) null else it.aiModelsError
            )
        }
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
            .putString("dl_method", newSettings.downloadMethod.name)
            .putBoolean("auto_adj_q", newSettings.autoAdjustQuality)
            .putString("subsonic_url", newSettings.subsonicUrl)
            .putString("subsonic_user", newSettings.subsonicUser)
            .putString("subsonic_pass", newSettings.subsonicPass)
            .putString("ai_provider", newSettings.aiProvider.name)
            .putString("ai_model_id", newSettings.aiModelId)
            .putString("ai_custom_endpoint", newSettings.aiCustomEndpoint)
            .putString("gemini_key", newSettings.geminiApiKey)
            .putBoolean("dj_voice", newSettings.djVoiceEnabled)
            .putBoolean("dj_intro_voice", newSettings.djIntroVoice)
            .putBoolean("dj_between_voice", newSettings.djBetweenTracksVoice)
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
                    statusMessage = if (found.isEmpty()) s.t("לא נמצאו קובצי אודיו במכשיר", "No local audio files found") else s.t("נמצאו ${found.size} קובצי שמע — זמינים בפלייליסט קבצי המכשיר", "Found ${found.size} audio files in Device Audio playlist")
                )
            }
        }
    }

    // ---------- Search (Includes Artists + Offline Local Search) ----------
    fun onSearchQueryChange(query: String) {
        _uiState.update { it.copy(searchQuery = query, hasSearched = false) }
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
            val st = _uiState.value
            if (st.settings.offlineMode) {
                val offlinePool = (st.likedTracks + st.recentTracks + st.playlists.flatMap { it.tracks } + st.localAudioTracks)
                    .distinctBy { it.id }
                    .filter { it.id in st.offlineTrackIds || it.source == StreamSource.LOCAL }
                    .filter { it.title.contains(clean, true) || it.artist.contains(clean, true) || it.album.contains(clean, true) }
                val artists = offlinePool.map { it.artist }.distinct().take(6).map { a ->
                    SearchArtistResult(a, "", offlinePool.firstOrNull { it.artist == a }?.artworkUrl ?: "", st.strings.t("אופליין", "Offline"))
                }
                _uiState.update { it.copy(searchResults = offlinePool, searchArtists = artists, searchPlaylists = emptyList(), isSearching = false) }
                return@launch
            }

            when (activeMode) {
                SearchMode.PLAYLISTS -> {
                    val pls = repo.searchPlaylists(clean)
                    _uiState.update { it.copy(searchPlaylists = pls, searchArtists = emptyList(), isSearching = false) }
                }
                SearchMode.PODCASTS -> {
                    val res = repo.searchTracks(clean, st.settings, podcastsOnly = true)
                    _uiState.update { it.copy(searchResults = res, searchArtists = emptyList(), isSearching = false) }
                }
                SearchMode.AUDIOBOOKS -> {
                    val res = repo.searchTracks(clean, st.settings, audiobooksOnly = true)
                    _uiState.update { it.copy(searchResults = res, searchArtists = emptyList(), isSearching = false) }
                }
                SearchMode.SONGS -> {
                    val (songs, artists, pls) = coroutineScope {
                        val sJob = async { repo.searchTracks(clean, st.settings) }
                        val aJob = async { runCatching { repo.searchArtists(clean, maxResults = 6) }.getOrDefault(emptyList()) }
                        val pJob = async { runCatching { repo.searchPlaylists(clean).take(8) }.getOrDefault(emptyList()) }
                        Triple(sJob.await(), aJob.await(), pJob.await())
                    }
                    _uiState.update { it.copy(searchResults = songs, searchArtists = artists, searchPlaylists = pls, isSearching = false) }
                }
            }
        }
    }

    // ---------- Real Clickable Deep Links & QR Code Handler ----------
    fun handleIncomingDeepLink(rawUriOrText: String) {
        val extracted = SonifyShareLinks.extractFirstLinkFromSharedText(rawUriOrText)
        if (extracted.isBlank()) return
        openSonifyCodeOrLink(extracted)
    }

    fun openSonifyCodeOrLink(raw: String) {
        val s = _uiState.value.strings
        val trimmed = raw.trim()

        if (trimmed.equals("sonify://liked", true)) {
            setShowCodeFor(null)
            openLikedSongsPlaylist()
            return
        }
        if (trimmed.equals("sonify://home", true)) {
            setShowCodeFor(null)
            selectTab(MainTab.HOME)
            return
        }

        val chatMatch = Regex("(?:sonify\\.link/chat/|sonify://chat/|sonify:chat:)([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(trimmed)
        if (chatMatch != null) {
            val roomCode = chatMatch.groupValues[1].uppercase()
            val friendName = runCatching { Uri.parse(trimmed).getQueryParameter("name") }.getOrNull()?.takeIf { it.isNotBlank() } ?: s.t("חדר $roomCode", "Room $roomCode")
            setShowCodeFor(null)
            joinLiveChatRoom(roomCode, friendName)
            setShowMessagesSheet(true)
            return
        }

        if (trimmed.contains("sonify:blend:", true) || trimmed.contains("/blend/", true)) {
            setShowCodeFor(null)
            createBlendFromFriendCode(trimmed)
            return
        }

        val sharedPl = BlendTasteCodec.decodeSharedPlaylist(trimmed)
        if (sharedPl != null) {
            setShowCodeFor(null)
            _uiState.update { it.copy(isBuildingAiPlaylist = true, statusMessage = s.t("מייבא פלייליסט משותף \"${sharedPl.name}\"...", "Importing shared playlist \"${sharedPl.name}\"...")) }
            viewModelScope.launch {
                val tracks = coroutineScope {
                    sharedPl.trackIds.map { id -> async(Dispatchers.IO) { repo.fetchTrackMetadataById(id) } }.awaitAll()
                }
                val pl = Playlist(
                    name = sharedPl.name,
                    creator = "${s.t("משותף מ-", "Shared by ")}${sharedPl.creator}",
                    coverUrl = tracks.firstOrNull()?.artworkUrl ?: "",
                    tracks = tracks,
                    isCollaborative = true,
                    collaborators = listOf(effectiveJamName(), sharedPl.creator)
                )
                savePlaylists(listOf(pl) + _uiState.value.playlists)
                _uiState.update { it.copy(isBuildingAiPlaylist = false, openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = s.t("הפלייליסט \"${pl.name}\" נשמר (${tracks.size} שירים)!", "Saved shared playlist (${tracks.size} tracks)!")) }
            }
            return
        }

        val jamMatch = Regex("(?:sonify\\.link/jam/|sonify://jam/)([A-Za-z0-9]+)", RegexOption.IGNORE_CASE).find(trimmed)
        if (jamMatch != null) {
            val code = jamMatch.groupValues[1].uppercase()
            val uri = runCatching { Uri.parse(trimmed) }.getOrNull()
            val vid = uri?.getQueryParameter("v")
            val sec = uri?.getQueryParameter("t")?.toIntOrNull() ?: 0
            setShowCodeFor(null)
            joinJamByCode(code, initialVideoId = vid, initialStartSec = sec)
            return
        }

        val trackMatch = Regex("(?:sonify\\.link/track/|sonify://track/|youtu\\.be/|watch\\?v=)([A-Za-z0-9_-]{11})", RegexOption.IGNORE_CASE).find(trimmed)
        if (trackMatch != null) {
            val vid = trackMatch.groupValues[1]
            val sec = runCatching { Uri.parse(trimmed).getQueryParameter("t")?.replace("s", "", true)?.toIntOrNull() }.getOrNull() ?: 0
            setShowCodeFor(null)
            _uiState.update { it.copy(statusMessage = s.t("טוען שיר משותף...", "Opening shared track...")) }
            viewModelScope.launch {
                val realTrack = repo.fetchTrackMetadataById(vid)
                playTrack(realTrack, listOf(realTrack), startAtMs = sec * 1000L)
                setShowNowPlaying(true)
            }
            return
        }

        val plMatch = Regex("(?:sonify\\.link/playlist/|sonify://playlist/)([^?&#\\s]+)", RegexOption.IGNORE_CASE).find(trimmed)
        if (plMatch != null) {
            val plId = plMatch.groupValues[1]
            val localPl = _uiState.value.playlists.firstOrNull { it.id == plId || it.id.startsWith(plId) }
            setShowCodeFor(null)
            if (localPl != null) openPlaylist(localPl)
            else openRemotePlaylist(RemotePlaylist(plId, s.t("פלייליסט משותף", "Shared Playlist"), "Sonify", ""))
            return
        }

        if (trimmed.contains("open.spotify.com/", true)) {
            setShowCodeFor(null)
            importPlaylist("", trimmed)
            return
        }

        when (val parsed = SonifyLinkCodec.parse(trimmed)) {
            is ParsedSonifyLink.JamLink -> {
                setShowCodeFor(null)
                joinJamByCode(parsed.code, initialVideoId = parsed.trackId, initialStartSec = parsed.startSec)
            }
            is ParsedSonifyLink.TrackLink -> {
                setShowCodeFor(null)
                _uiState.update { it.copy(statusMessage = s.t("טוען פרטי שיר...", "Loading track info...")) }
                viewModelScope.launch {
                    val realTrack = repo.fetchTrackMetadataById(parsed.videoId)
                    playTrack(realTrack, listOf(realTrack), startAtMs = parsed.startSec * 1000L)
                    setShowNowPlaying(true)
                }
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

    // ---------- Playback, Instant 250MB Disk Pre-Warm, Reliable 8-Source Lyrics & Extras ----------
    private suspend fun resolveFastWithCache(track: Track, settings: AppSettings, attempt: Int = 0): ResolvedStreams? {
        if (attempt == 0) {
            val offlineFile = repo.getOfflineFile(track.id)
            if (offlineFile.exists() && offlineFile.length() > 4096) {
                return ResolvedStreams(offlineFile.toURI().toString(), isLossless = track.source != StreamSource.YOUTUBE, isOffline = true, codecLabel = "OFFLINE")
            }
            val precacheFile = repo.getPrecachedAudioFile(track.id)
            if (precacheFile.exists() && precacheFile.length() > 48_000) {
                precacheFile.setLastModified(System.currentTimeMillis())
                return ResolvedStreams(precacheFile.toURI().toString(), isOffline = true, codecLabel = "DISK CACHE")
            }
        }
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

    /**
     * Proactively warms the upcoming track(s) directly into ExoPlayer's 250MB SimpleCache AND disk precache
     * under stable `sonify_track_<id>` keys so skipping to the next song starts immediately with zero buffering!
     */
    private fun prefetchNextTracksInQueue(queue: List<Track>, currentIndex: Int) {
        prefetchJob?.cancel()
        val settings = _uiState.value.settings
        if (settings.offlineMode || settings.ytEngineMode == YouTubeEngineMode.WEB_DIRECT) return

        val next1 = queue.getOrNull(currentIndex + 1)
        val next2 = queue.getOrNull(currentIndex + 2)
        if (next1 == null && next2 == null) return

        prefetchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(850)
            if (next1 != null && next1.source == StreamSource.YOUTUBE) {
                val res1 = resolveFastWithCache(next1, settings, attempt = 0)
                if (res1 != null && !res1.isOffline) {
                    // Warm directly into ExoPlayer's SimpleCache (first 3.5MB) under stable Track ID key!
                    audioEngine.prewarmNextTrackDiskCache(next1, res1)
                }
                if (!settings.dataSaver) {
                    runCatching { repo.precacheTrackAudio(next1, settings) }
                }
            }
            if (next2 != null && next2.source == StreamSource.YOUTUBE) {
                val res2 = resolveFastWithCache(next2, settings, attempt = 0)
                if (res2 != null && !res2.isOffline && repo.isOnWifi()) {
                    audioEngine.prewarmNextTrackDiskCache(next2, res2)
                }
            }
        }
    }

    fun startSongOrArtistRadio(track: Track) {
        val s = _uiState.value.strings
        if (_uiState.value.settings.offlineMode) {
            _uiState.update { it.copy(trackForOptions = null, statusMessage = s.t("רדיו שיר דורש חיבור לאינטרנט", "Song radio requires internet")) }
            return
        }
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
        val isDiskWarmed = AudioCacheHolder.isTrackWarmed(getApplication(), track.id)
        val isOfflineReady = track.id in state.offlineTrackIds ||
            track.source == StreamSource.LOCAL ||
            repo.getPrecachedAudioFile(track.id).let { it.exists() && it.length() > 48_000 }

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
            !isOfflineReady &&
            !isDiskWarmed &&
            !settings.offlineMode

        val updatedJam = if (state.jamState.active) {
            state.jamState.copy(joinUrl = SonifyShareLinks.jamUrl(state.jamState.code, track, startAtMs))
        } else state.jamState

        val weakNet = !isOfflineReady && !isDiskWarmed && !settings.offlineMode && repo.isWeakNetwork()
        val cachedExtras = nowPlayingExtrasCache[track.id]

        _uiState.update {
            it.copy(
                queue = finalQueue, currentIndex = idx, currentTrack = track, recentTracks = updatedRecent,
                stats = updatedStats, resolvedStreams = null, activeVideoClipId = null, isFindingVideoClip = false,
                nowPlayingExtras = cachedExtras, isLoadingNowPlayingExtras = cachedExtras == null && !settings.offlineMode,
                isAiDjActive = if (keepDjActive) it.isAiDjActive else false,
                activeDjStyleLabel = if (keepDjActive) it.activeDjStyleLabel else "",
                isUsingWebEngine = useWebDirect, isWeakNetworkActive = weakNet,
                isResolvingStream = !useWebDirect && !isOfflineReady && !isDiskWarmed,
                isPlayerBuffering = useWebDirect,
                isVideoMode = false, positionMs = startAtMs, durationMs = track.durationMs,
                lyrics = emptyList(), isLoadingLyrics = true, showTranslatedLyrics = false,
                isTranslatingLyrics = false, lyricsTranslateError = null,
                jamState = updatedJam
            )
        }

        val isLiked = state.likedTracks.any { it.id == track.id }
        pushWidgetAsync(track.title, track.artist, track.artworkUrl, true, isLiked, 0)
        extractDynamicColorAndWidgetArt(track)
        pushWidgetQuickTiles()

        if (useWebDirect) {
            audioEngine.playViaWebEngine(track.id, startAtMs, track.title, track.artist)
        } else {
            resolveAndStartTrack(track, attempt = 0, forceVideo = false, startAtMs = startAtMs)
        }

        if (!fromRemoteJam && state.jamState.active && (state.jamState.isHost || state.jamState.guestsCanControlQueue)) {
            publishJamState("TRACK", trackOverride = track, posOverride = startAtMs, playingOverride = true)
        }

        prefetchNextTracksInQueue(finalQueue, idx)

        if (!settings.offlineMode && (openVideoImmediately || (!settings.dataSaver && !(track.isPodcastOrAudiobook && settings.audioOnlyPodcasts) && settings.autoVideoOnWifi && track.source == StreamSource.YOUTUBE))) {
            viewModelScope.launch { toggleVideoClipMode() }
        }

        // Load 8-source synced lyrics (or podcast transcript automatically!)
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val lyrics = runCatching {
                if (track.isPodcastOrAudiobook) repo.fetchPodcastTranscript(track, offlineOnly = settings.offlineMode)
                else repo.fetchSyncedLyrics(track, offlineOnly = settings.offlineMode)
            }.getOrDefault(emptyList())
            if (_uiState.value.currentTrack?.id == track.id) {
                _uiState.update { it.copy(lyrics = lyrics, isLoadingLyrics = false) }
                if (lyrics.isNotEmpty() && !track.isPodcastOrAudiobook && !settings.offlineMode && settings.autoTranslateLyrics && repo.aiClient.hasValidConfig(settings)) {
                    toggleTranslateLyrics()
                }
            }
        }

        extrasJob?.cancel()
        extrasJob = viewModelScope.launch {
            val extras = cachedExtras ?: runCatching { repo.fetchNowPlayingExtras(track, settings) }.getOrNull()
            if (extras != null) nowPlayingExtrasCache[track.id] = extras
            if (_uiState.value.currentTrack?.id == track.id) {
                _uiState.update { it.copy(nowPlayingExtras = extras, isLoadingNowPlayingExtras = false) }
            }
        }
    }

    private fun activateWebFallback(track: Track, startAtMs: Long = 0L) {
        if (_uiState.value.settings.offlineMode) return
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
            val allowVideo = forceVideo && !settings.offlineMode && resolved.hasVideoClip
            audioEngine.playResolved(resolved, track, videoMode = allowVideo, startPositionMs = startAtMs)
            _uiState.update {
                it.copy(
                    isUsingWebEngine = false,
                    resolvedStreams = resolved,
                    isResolvingStream = false,
                    isVideoMode = allowVideo
                )
            }
        }
    }

    private fun handlePlaybackError(code: String) {
        val state = _uiState.value
        val track = state.currentTrack ?: return
        resolvedStreamCache.remove(track.id)
        if (track.source == StreamSource.YOUTUBE && !state.settings.offlineMode) {
            if (retryAttemptForCurrent < 5) {
                retryAttemptForCurrent++
                val resumePos = state.positionMs
                val backoffMs = if (code.contains("NETWORK", true) || code.contains("TIMEOUT", true)) 1200L else 250L
                _uiState.update { it.copy(isResolvingStream = true, isWeakNetworkActive = repo.isWeakNetwork()) }
                viewModelScope.launch {
                    delay(backoffMs)
                    if (_uiState.value.currentTrack?.id == track.id) {
                        resolveAndStartTrack(track, attempt = retryAttemptForCurrent, forceVideo = state.isVideoMode, startAtMs = resumePos)
                    }
                }
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
                    .size(180)
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

        if (state.settings.offlineMode || track.source != StreamSource.YOUTUBE) {
            _uiState.update { it.copy(statusMessage = s.t("קליפ וידאו אינו זמין במצב אופליין", "Music video is unavailable in Offline Mode")) }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isFindingVideoClip = true) }
            val officialId = if (track.isPodcastOrAudiobook) {
                track.id
            } else {
                officialVideoCache[track.id] ?: repo.findOfficialMusicVideoId(track)?.also { officialVideoCache[track.id] = it } ?: track.id
            }
            val latest = _uiState.value
            if (latest.currentTrack?.id != track.id || latest.settings.offlineMode) {
                _uiState.update { it.copy(isFindingVideoClip = false) }
                return@launch
            }

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
        if (state.isUsingWebEngine) {
            audioEngine.setPlaying(willPlay)
        } else {
            audioEngine.togglePlayPause(onNeedRetry = {
                if (current != null) {
                    if (current.source == StreamSource.YOUTUBE && !state.settings.offlineMode && state.settings.ytEngineMode != YouTubeEngineMode.EXOPLAYER_ONLY) {
                        activateWebFallback(current)
                    } else {
                        retryAttemptForCurrent = 0
                        _uiState.update { it.copy(isResolvingStream = true) }
                        resolveAndStartTrack(current, 0)
                    }
                }
            })
        }
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

    fun skipNext(announceDjIfEnabled: Boolean = false) {
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
            val nextTrack = state.queue[nextIdx]
            playTrack(nextTrack, state.queue, keepDjActive = state.isAiDjActive)
            if (announceDjIfEnabled && state.isAiDjActive && state.settings.djBetweenTracksVoice) {
                val isEn = state.strings.isEn
                val line = if (isEn) "Up next, ${nextTrack.title} by ${nextTrack.artist}."
                else "השיר הבא בסט: ${nextTrack.title} של ${nextTrack.artist}."
                audioEngine.speakDjAnnouncement(line, isEn, forceAllow = true)
            }
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
        } else {
            skipNext(announceDjIfEnabled = true)
        }
    }

    fun cycleShuffleMode() {
        val state = _uiState.value
        val s = state.strings
        when {
            !state.shuffleEnabled -> _uiState.update { it.copy(shuffleEnabled = true, smartShuffleEnabled = false, statusMessage = s.t("ערבוב הופעל", "Shuffle ON")) }
            !state.smartShuffleEnabled && !state.settings.offlineMode -> {
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

    fun toggleSmartShuffleForPlaylist(playlist: Playlist) {
        val st = _uiState.value
        val s = st.strings
        if (st.settings.offlineMode) {
            _uiState.update { it.copy(statusMessage = s.t("ערבוב חכם דורש חיבור לאינטרנט", "Smart Shuffle requires internet")) }
            return
        }
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

            val baseTracks = playlist.tracks.filterNot { it.isSmartInjected }
            val woven = mutableListOf<Track>()
            var smartIdx = 0
            baseTracks.forEachIndexed { idx, t ->
                woven.add(t)
                if ((idx + 1) % 3 == 0 && smartIdx < candidates.size) woven.add(candidates[smartIdx++])
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
        if (from !in list.indices || to !in list.indices || from == to) return
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
        val updated = if (exists) cur.filterNot { it.id == track.id } else listOf(track.copy(addedAt = System.currentTimeMillis())) + cur
        prefs.edit().putString("liked_tracks", encodeTracks(updated)).apply()
        val s = _uiState.value.strings
        _uiState.update { st ->
            val refreshedOpen = if (st.openedPlaylist?.id == "virtual_liked") st.openedPlaylist.copy(tracks = updated) else st.openedPlaylist
            st.copy(
                likedTracks = updated,
                openedPlaylist = refreshedOpen,
                statusMessage = if (exists) s.t("הוסר מהספרייה", "Removed from Liked Songs") else s.t("נשמר בשירים שאהבת", "Saved to Liked Songs")
            )
        }
        val st = _uiState.value
        if (st.currentTrack?.id == track.id) {
            val permille = if (st.durationMs > 0) ((st.positionMs * 1000L) / st.durationMs).toInt() else 0
            pushWidgetAsync(track.title, track.artist, track.artworkUrl, st.isPlaying, !exists, permille)
        }
    }

    // ---------- Dedicated Pure-Audio Offline Downloads ----------
    fun downloadOffline(track: Track) {
        if (track.source == StreamSource.LOCAL) return
        val st = _uiState.value
        val s = st.strings
        if (st.offlineTrackIds.contains(track.id)) {
            repo.deleteOfflineTrack(track.id)
            _uiState.update { it.copy(offlineTrackIds = repo.getOfflineTrackIds(), storageRefreshKey = it.storageRefreshKey + 1, statusMessage = s.t("הוסר מהורדות האופליין", "Removed from downloads")) }
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
        val captured = audioEngine.getCapturedStreams(track.id)
        OfflineDownloadCoordinator.enqueueSingle(getApplication(), track, st.settings, captured)
    }

    fun cancelPlaylistDownload() {
        OfflineDownloadCoordinator.cancelAll(getApplication(), _uiState.value.strings.isEn)
    }

    fun downloadEntirePlaylist(playlist: Playlist) {
        val st = _uiState.value
        val s = st.strings
        if (playlist.id == "virtual_local") return

        if (st.activeBulkDownloadPlaylistId == playlist.id) {
            cancelPlaylistDownload()
            return
        }
        if (playlist.tracks.isEmpty()) {
            _uiState.update { it.copy(statusMessage = s.t("אין פריטים בפלייליסט להורדה", "No items in playlist to download")) }
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
        OfflineDownloadCoordinator.enqueuePlaylist(getApplication(), playlist.id, playlist.name, playlist.tracks, st.settings)
    }

    // ---------- Multi-Provider AI Lyrics Translation ----------
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
            _uiState.update { it.copy(showTranslatedLyrics = false, lyricsTranslateError = null) }
            return
        }

        val cacheKey = "${track.id}_${targetLang.name}"
        translatedLyricsCache[cacheKey]?.let { cached ->
            _uiState.update { it.copy(lyrics = cached, showTranslatedLyrics = true, isTranslatingLyrics = false, lyricsTranslateError = null) }
            return
        }

        if (st.settings.offlineMode) {
            val msg = s.t("תרגום מילים דורש חיבור לאינטרנט", "Lyrics translation requires internet")
            _uiState.update { it.copy(lyricsTranslateError = msg, statusMessage = msg) }
            return
        }

        if (!repo.aiClient.hasValidConfig(st.settings)) {
            val msg = s.t("חסר מפתח API — הזן אותו בהגדרות ← בינה מלאכותית", "Missing API Key — add it in Settings -> AI")
            _uiState.update { it.copy(lyricsTranslateError = msg, statusMessage = msg) }
            return
        }

        if (st.isTranslatingLyrics) return
        _uiState.update { it.copy(isTranslatingLyrics = true, lyricsTranslateError = null) }

        viewModelScope.launch {
            val (translated, err) = repo.translateLyrics(st.lyrics, targetLang, st.settings)
            if (_uiState.value.currentTrack?.id != track.id) return@launch
            if (translated != null) {
                translatedLyricsCache[cacheKey] = translated
                _uiState.update { it.copy(lyrics = translated, showTranslatedLyrics = true, isTranslatingLyrics = false, lyricsTranslateError = null) }
            } else {
                val finalErr = err ?: s.t("תרגום המילים נכשל — נסה שוב", "Lyrics translation failed — try again")
                _uiState.update { it.copy(isTranslatingLyrics = false, lyricsTranslateError = finalErr, statusMessage = finalErr) }
            }
        }
    }

    // ---------- Playlists, Enhanced Cover Art Studio & Folders ----------
    private fun savePlaylists(updated: List<Playlist>) {
        prefs.edit().putString("playlists", encodePlaylists(updated)).apply()
        _uiState.update { state ->
            val refreshedOpen = state.openedPlaylist?.let { open -> updated.firstOrNull { it.id == open.id } ?: open }
            state.copy(playlists = updated, openedPlaylist = refreshedOpen)
        }
        pushWidgetQuickTiles()
    }

    fun buildSharedPlaylistCode(playlist: Playlist): String = BlendTasteCodec.encodeSharedPlaylist(playlist)

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
        if (pl.id.startsWith("MPRE") || pl.id.startsWith("OLAK") || pl.id == "virtual_local" || _uiState.value.settings.offlineMode) return
        if (_uiState.value.isExpandingPlaylist) return
        val s = _uiState.value.strings
        _uiState.update { it.copy(isExpandingPlaylist = true, statusMessage = s.t("מוסיף פריטים דומים...", "Adding more items...")) }
        viewModelScope.launch {
            val seedQuery = pl.aiSeedPrompt.ifBlank { pl.tracks.randomOrNull()?.artist ?: pl.name }
            val extra = repo.searchTracks(
                query = "$seedQuery similar hits",
                settings = _uiState.value.settings,
                podcastsOnly = pl.isPodcastPlaylist,
                audiobooksOnly = pl.isAudiobookPlaylist,
                maxResults = 25
            )
                .filterNot { cand -> pl.tracks.any { it.id == cand.id } || cand.id in _uiState.value.hiddenTrackIds }
                .take(15)
            if (extra.isEmpty()) {
                _uiState.update { it.copy(isExpandingPlaylist = false, statusMessage = s.t("לא נמצאו פריטים נוספים", "No more items found")) }
                return@launch
            }
            val updatedPl = pl.copy(tracks = pl.tracks + extra)
            updateOpenedAndSavedPlaylist(updatedPl)
            _uiState.update { it.copy(isExpandingPlaylist = false, statusMessage = s.t("נוספו ${extra.size} (${updatedPl.countLabel(s)})", "Added ${extra.size} (${updatedPl.countLabel(s)})")) }
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
        if (from !in list.indices || to !in list.indices || from == to) return
        list.add(to, list.removeAt(from))
        savePlaylists(_uiState.value.playlists.map { if (it.id == playlistId) it.copy(tracks = list) else it })
    }

    fun saveRemotePlaylistToLibrary(pl: Playlist) {
        val saved = pl.copy(id = UUID.randomUUID().toString(), isRemote = false)
        savePlaylists(listOf(saved) + _uiState.value.playlists)
        _uiState.update { it.copy(openedPlaylist = saved, statusMessage = it.strings.t("הפלייליסט נשמר בספרייה!", "Saved to your library!")) }
    }

    fun createPlaylist(
        name: String,
        initialTracks: List<Track> = emptyList(),
        isCollaborative: Boolean = false,
        description: String = "",
        isPublic: Boolean = true,
        coverBgHex: Long? = null,
        coverMode: String = "AUTO"
    ) {
        val clean = name.trim()
        val s = _uiState.value.strings
        if (clean.isBlank()) { _uiState.update { it.copy(statusMessage = s.t("יש להזין שם לפלייליסט", "Enter a playlist name")) }; return }
        val pl = Playlist(
            name = clean,
            creator = effectiveJamName(),
            description = description.trim(),
            coverUrl = initialTracks.firstOrNull()?.artworkUrl ?: "",
            customCoverBgHex = coverBgHex,
            coverMode = coverMode,
            tracks = initialTracks,
            isPublic = isPublic,
            isCollaborative = isCollaborative,
            collaborators = if (isCollaborative) listOf(effectiveJamName()) else emptyList()
        )
        savePlaylists(listOf(pl) + _uiState.value.playlists)
        _uiState.update { it.copy(showCreateSheet = false, openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = s.t("הפלייליסט \"$clean\" נוצר", "Created \"$clean\"")) }
        if (coverMode == "ARTIST_ONLY" && initialTracks.isNotEmpty()) {
            fetchArtistCoverForPlaylist(pl.id, initialTracks.first().artist)
        }
    }

    fun updatePlaylistCover(playlistId: String, bgHex: Long, titleText: String, sticker: String) {
        updatePlaylistCoverAdvanced(playlistId, bgHex, titleText, sticker, "AUTO", "")
    }

    fun updatePlaylistCoverAdvanced(
        playlistId: String,
        bgHex: Long,
        titleText: String,
        sticker: String,
        coverMode: String,
        artistNameQuery: String = ""
    ) {
        val pl = _uiState.value.playlists.firstOrNull { it.id == playlistId } ?: return
        savePlaylists(_uiState.value.playlists.map {
            if (it.id == playlistId) it.copy(
                customCoverBgHex = bgHex,
                customCoverText = titleText,
                customCoverSticker = sticker,
                coverMode = coverMode
            ) else it
        })
        _uiState.update { it.copy(editingCoverPlaylistId = null, statusMessage = it.strings.t("עטיפת הפלייליסט עודכנה!", "Cover art updated!")) }
        if (coverMode == "ARTIST_ONLY") {
            val targetArtist = artistNameQuery.trim().ifBlank { pl.tracks.firstOrNull()?.artist.orEmpty() }
            if (targetArtist.isNotBlank()) fetchArtistCoverForPlaylist(playlistId, targetArtist)
        }
    }

    fun fetchArtistCoverForPlaylist(playlistId: String, artistName: String) {
        if (artistName.isBlank() || _uiState.value.settings.offlineMode) return
        viewModelScope.launch {
            val photoUrl = repo.fetchArtistPhotoUrl(artistName)
            if (photoUrl.isNotBlank()) {
                savePlaylists(_uiState.value.playlists.map {
                    if (it.id == playlistId) it.copy(artistCoverUrl = photoUrl, coverMode = "ARTIST_ONLY") else it
                })
            }
        }
    }

    fun createFolder(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        val updated = listOf(PlaylistFolder(name = clean)) + _uiState.value.folders
        prefs.edit().putString("folders", encodeFolders(updated)).apply()
        _uiState.update { it.copy(folders = updated, showCreateSheet = false, statusMessage = it.strings.t("התיקייה \"$clean\" נוצרה", "Folder \"$clean\" created")) }
    }

    fun deleteFolder(folderId: String) {
        val updatedFolders = _uiState.value.folders.filterNot { it.id == folderId }
        prefs.edit().putString("folders", encodeFolders(updatedFolders)).apply()
        savePlaylists(_uiState.value.playlists.map { if (it.folderId == folderId) it.copy(folderId = null) else it })
        _uiState.update { it.copy(folders = updatedFolders, selectedFolderId = if (it.selectedFolderId == folderId) null else it.selectedFolderId, statusMessage = it.strings.t("התיקייה נמחקה", "Folder deleted")) }
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
        _uiState.update { it.copy(statusMessage = it.strings.t("השיר הוסר מהפלייליסט", "Track removed")) }
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
        val trimmed = rawInput.trim()
        if (trimmed.isBlank()) { _uiState.update { it.copy(statusMessage = s.t("הדבק קישור (YouTube / Spotify / Sonify) או רשימת שירים", "Paste a link or track list")) }; return }
        if (trimmed.contains("sonify:plshare:", true) || trimmed.contains("/plshare/", true) || trimmed.contains("sonify:blend:", true) || trimmed.contains("/blend/", true)) {
            _uiState.update { it.copy(showImportSheet = false) }
            openSonifyCodeOrLink(trimmed)
            return
        }
        if (_uiState.value.settings.offlineMode) {
            _uiState.update { it.copy(statusMessage = s.t("ייבוא פלייליסט דורש חיבור לאינטרנט", "Playlist import requires internet")) }
            return
        }
        _uiState.update { it.copy(showImportSheet = false, isBuildingAiPlaylist = true, statusMessage = s.t("מייבא פלייליסט...", "Importing playlist...")) }
        viewModelScope.launch {
            val (detectedName, tracks) = repo.importPlaylistFromTextOrUrl(trimmed, _uiState.value.settings)
            if (tracks.isEmpty()) {
                _uiState.update { it.copy(isBuildingAiPlaylist = false, statusMessage = s.t("לא הצלחנו למצוא שירים — בדוק שהקישור ציבורי", "Could not import tracks — make sure link is public")) }
                return@launch
            }
            val finalName = name.trim().ifBlank { detectedName ?: s.t("פלייליסט מיובא", "Imported Playlist") }
            val pl = Playlist(name = finalName, creator = s.t("ייבוא", "Import"), coverUrl = tracks.first().artworkUrl, tracks = tracks)
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update { it.copy(isBuildingAiPlaylist = false, openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = s.t("יובאו ${tracks.size} שירים ל-\"$finalName\"!", "Imported ${tracks.size} tracks!")) }
        }
    }

    // ---------- AI Playlist Studio (With Auto Count) & AI DJ ----------
    fun createAiPlaylist(config: AiPlaylistConfig) {
        val s = _uiState.value.strings
        if (config.prompt.isBlank()) { _uiState.update { it.copy(statusMessage = s.t("תאר איזה פלייליסט תרצה", "Describe your playlist")) }; return }
        if (_uiState.value.settings.offlineMode) {
            _uiState.update { it.copy(statusMessage = s.t("יצירת פלייליסט ב-AI דורשת חיבור לאינטרנט", "AI Playlist requires internet")) }
            return
        }
        val countHint = if (config.autoCountByAi) s.t("ה-AI בוחר את כמות השירים האידיאלית...", "AI choosing optimal track count...") else s.t("AI Studio בונה פלייליסט של ${config.targetCount} שירים...", "AI Studio building ${config.targetCount} tracks...")
        _uiState.update { it.copy(showCreateSheet = false, isBuildingAiPlaylist = true, statusMessage = countHint) }
        viewModelScope.launch {
            val (title, tracks) = repo.buildAiPlaylist(config, _uiState.value.settings, _uiState.value.likedTracks)
            if (tracks.isEmpty()) { _uiState.update { it.copy(isBuildingAiPlaylist = false, statusMessage = s.t("לא נמצאו שירים — נסה תיאור אחר", "No tracks found — try another prompt")) }; return@launch }
            val mainArtist = tracks.firstOrNull()?.artist.orEmpty()
            val artPhoto = if (mainArtist.isNotBlank()) runCatching { repo.fetchArtistPhotoUrl(mainArtist) }.getOrDefault("") else ""
            val pl = Playlist(
                name = title,
                creator = "AI Studio",
                description = "${config.prompt} • ${config.energyPref} • ${config.eraPref}",
                coverUrl = tracks.first().artworkUrl,
                artistCoverUrl = artPhoto,
                tracks = tracks,
                aiSeedPrompt = config.prompt,
                aiStyleTag = "AI STUDIO"
            )
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update { it.copy(isBuildingAiPlaylist = false, openedPlaylist = pl, selectedTab = MainTab.LIBRARY, statusMessage = s.t("נבנה פלייליסט עם ${tracks.size} שירים!", "Built ${tracks.size}-track playlist!")) }
            playTrack(tracks.first(), tracks)
        }
    }

    fun startAiDj() {
        _uiState.update { it.copy(showCreateSheet = false, showDjStudioSheet = true) }
    }

    fun launchAiDj(config: AiDjConfig) {
        val st = _uiState.value
        val s = st.strings
        val updatedSettings = st.settings.copy(
            djIntroVoice = config.speakAtStart,
            djBetweenTracksVoice = config.speakBetweenTracks,
            djVoiceEnabled = config.speakAtStart || config.speakBetweenTracks
        )
        updateSettings(updatedSettings)

        val chosenPrompt = config.customPrompt.trim().ifBlank { config.styleName.trim() }
        val styleLabel = config.styleName.trim().ifBlank { config.customPrompt.trim().take(24) }.ifBlank { s.t("סט אישי", "Personal Mix") }

        _uiState.update {
            it.copy(
                showDjStudioSheet = false,
                showCreateSheet = false,
                isAiDjActive = true,
                activeDjStyleLabel = styleLabel,
                statusMessage = "AI DJ • ${s.t("בונה סט: $styleLabel...", "Curating $styleLabel...")}"
            )
        }

        viewModelScope.launch {
            val mix = if (st.settings.offlineMode) {
                (st.likedTracks + st.recentTracks + st.localAudioTracks)
                    .distinctBy { it.id }
                    .filter { it.id in st.offlineTrackIds || it.source == StreamSource.LOCAL }
                    .shuffled()
            } else if (chosenPrompt.isNotBlank() && !chosenPrompt.equals("הטעם האישי שלי", true) && !chosenPrompt.equals("My Taste", true)) {
                val (_, built) = repo.buildAiPlaylist(AiPlaylistConfig(prompt = chosenPrompt, targetCount = 25), updatedSettings, st.likedTracks)
                built.ifEmpty { repo.searchTracks(chosenPrompt, updatedSettings, maxResults = 25) }
            } else {
                val topArtist = st.stats.artistPlayCounts.maxByOrNull { it.value }?.key ?: st.likedTracks.firstOrNull()?.artist
                val query = topArtist ?: "להיטים מובילים 2026"
                val fresh = repo.searchTracks(query, updatedSettings, maxResults = 25)
                (st.likedTracks.take(6) + st.recentTracks.take(5) + st.trendingSongs.take(8) + fresh).distinctBy { it.id }.shuffled().take(30)
            }

            if (mix.isEmpty()) {
                _uiState.update { it.copy(isAiDjActive = false, statusMessage = s.t("לא נמצאו שירים לסגנון שנבחר", "No tracks found for this style")) }
                return@launch
            }

            val first = mix.first()
            playTrack(first, mix, keepDjActive = true)
            _uiState.update { it.copy(showNowPlaying = true, isAiDjActive = true, activeDjStyleLabel = styleLabel) }

            if (config.speakAtStart) {
                val intro = if (s.isEn) "Hey! I'm your Sonify DJ with a $styleLabel set. Kicking off with ${first.title} by ${first.artist}."
                else "היי! כאן הדי ג'יי של סוניפיי עם סט $styleLabel. מתחילים עם ${first.title} של ${first.artist}."
                audioEngine.speakDjAnnouncement(intro, s.isEn, forceAllow = true)
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
        val label = if (s.isEn) enName else heName
        _uiState.update { it.copy(djVibeIndex = nextIdx, activeDjStyleLabel = label, statusMessage = "AI DJ • $label") }
        viewModelScope.launch {
            val tracks = repo.searchTracks(q, _uiState.value.settings, maxResults = 25).shuffled()
            if (tracks.isNotEmpty()) {
                playTrack(tracks.first(), tracks, keepDjActive = true)
                if (_uiState.value.settings.djBetweenTracksVoice) {
                    val line = if (s.isEn) "Switching the vibe to $enName. Up next, ${tracks.first().title}."
                    else "מחליף אווירה ל$heName. השיר הבא הוא ${tracks.first().title}."
                    audioEngine.speakDjAnnouncement(line, s.isEn, forceAllow = true)
                }
            }
        }
    }

    // ---------- Cloudless Friend Blend, Live Jam & Real Cloudless Live Chat Rooms ----------
    fun effectiveJamName(): String {
        val custom = _uiState.value.settings.jamUserDisplayName.trim()
        return if (custom.isNotBlank()) custom else _uiState.value.strings.t("אתה", "You")
    }

    fun buildMyBlendTasteCode(): String {
        val st = _uiState.value
        val myArtists = (
            st.likedTracks.map { it.artist } +
            st.stats.artistPlayCounts.entries.sortedByDescending { it.value }.map { it.key } +
            st.recentTracks.map { it.artist }
        ).map { it.trim() }.filter { it.length >= 2 && !it.equals("YouTube", true) }.distinct().take(8)
        val myTrackIds = (st.likedTracks + st.recentTracks)
            .filter { it.source == StreamSource.YOUTUBE && it.id.length == 11 }
            .map { it.id }
            .distinct()
            .take(6)
        return BlendTasteCodec.encodeTaste(effectiveJamName(), myArtists, myTrackIds)
    }

    fun createBlend(friendName: String) {
        createBlendFromFriendCode(friendCodeOrName = friendName, manualArtists = "")
    }

    fun createBlendFromFriendCode(friendCodeOrName: String, manualArtists: String = "") {
        val st = _uiState.value
        val s = st.strings
        val decoded = BlendTasteCodec.decodeTaste(friendCodeOrName)
        val friendName = decoded?.name ?: friendCodeOrName.trim().ifBlank { s.t("חבר", "Friend") }
        val friendArtists = decoded?.artists?.takeIf { it.isNotEmpty() }
            ?: manualArtists.split(",", "،", "\n").map { it.trim() }.filter { it.length >= 2 }
        val friendTrackIds = decoded?.trackIds.orEmpty()

        _uiState.update {
            it.copy(
                showCreateSheet = false,
                isBuildingAiPlaylist = true,
                statusMessage = s.t("ממזג טעם מוזיקלי עם $friendName...", "Blending music taste with $friendName...")
            )
        }

        viewModelScope.launch {
            val myArtists = (st.likedTracks.map { it.artist } + st.stats.artistPlayCounts.keys + st.recentTracks.map { it.artist })
                .map { it.lowercase().trim() }.toSet()

            val friendTracks = coroutineScope {
                val idJobs = friendTrackIds.take(6).map { id -> async(Dispatchers.IO) { runCatching { repo.fetchTrackMetadataById(id) }.getOrNull() } }
                val artJobs = friendArtists.take(4).map { a -> async(Dispatchers.IO) { runCatching { repo.searchTracks(a, st.settings, maxResults = 6) }.getOrDefault(emptyList()) } }
                (idJobs.awaitAll().filterNotNull() + artJobs.awaitAll().flatten())
            }

            val myPool = (st.likedTracks.take(12) + st.recentTracks.take(8) + st.trendingSongs.take(8)).distinctBy { it.id }
            val fallbackPool = if (friendTracks.isEmpty()) {
                runCatching { repo.searchTracks("top hits 2026", st.settings, maxResults = 16) }.getOrDefault(emptyList())
            } else emptyList()

            val friendUnique = (friendTracks + fallbackPool).distinctBy { it.id }
            val merged = mutableListOf<Track>()
            val maxLen = maxOf(myPool.size, friendUnique.size)
            for (i in 0 until maxLen) {
                myPool.getOrNull(i)?.let { t -> if (merged.none { it.id == t.id }) merged.add(t) }
                friendUnique.getOrNull(i)?.let { t -> if (merged.none { it.id == t.id }) merged.add(t) }
                if (merged.size >= 28) break
            }

            val overlapCount = friendArtists.count { fa -> myArtists.any { ma -> ma.contains(fa.lowercase()) || fa.lowercase().contains(ma) } }
            val match = if (friendArtists.isNotEmpty()) {
                (68 + (overlapCount * 30 / friendArtists.size.coerceAtLeast(1))).coerceIn(68, 99)
            } else (76..95).random()

            val desc = buildString {
                append("${s.t("התאמה מוזיקלית", "Taste Match")}: $match%")
                if (friendArtists.isNotEmpty()) append(" • ${friendArtists.take(3).joinToString(", ")}")
            }
            val pl = Playlist(
                name = "Blend • ${effectiveJamName()} + $friendName",
                creator = "Blend",
                description = desc,
                coverUrl = merged.firstOrNull()?.artworkUrl ?: "",
                customCoverBgHex = 0xFF9C27B0,
                tracks = merged,
                isBlend = true,
                blendMatchPercent = match,
                aiStyleTag = "BLEND $match%"
            )
            savePlaylists(listOf(pl) + _uiState.value.playlists)
            _uiState.update {
                it.copy(
                    isBuildingAiPlaylist = false,
                    openedPlaylist = pl,
                    selectedTab = MainTab.LIBRARY,
                    statusMessage = "Blend • $match% (${merged.size} ${s.songs})"
                )
            }
        }
    }

    fun startJamSession() {
        val code = UUID.randomUUID().toString().take(6).uppercase()
        val st = _uiState.value
        val s = st.strings
        val smartUrl = SonifyShareLinks.jamUrl(code, st.currentTrack, st.positionMs)
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
                showDevicesSheet = true,
                statusMessage = "Jam • $code"
            )
        }
        if (st.settings.jamLiveSyncEnabled) connectLiveJamRoom(code, isHost = true)
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
                    active = true, code = code, joinUrl = SonifyShareLinks.jamUrl(code), isHost = false,
                    guestsCanControlQueue = true,
                    participants = listOf(s.t("מארח ה-Jam", "Jam Host"), effectiveJamName()),
                    liveConnected = false,
                    syncStatus = if (it.settings.jamLiveSyncEnabled) s.t("מתחבר למארח...", "Syncing with host...") else s.t("קישור בלבד", "Link snapshot")
                ),
                showCreateSheet = false,
                statusMessage = s.t("הצטרפת ל-Jam ($code)!", "Joined Jam ($code)!")
            )
        }

        if (!vid.isNullOrBlank() && vid.length == 11) {
            viewModelScope.launch {
                val realTrack = repo.fetchTrackMetadataById(vid)
                playTrack(realTrack, listOf(realTrack), startAtMs = sec * 1000L, fromRemoteJam = true)
            }
        }

        if (st.settings.jamLiveSyncEnabled) connectLiveJamRoom(code, isHost = false)
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
                    if (ev.isPlaying != st.isPlaying) audioEngine.setPlaying(ev.isPlaying)
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

    // ---------- Cloudless Real-Time Friend Chat ----------
    fun selectChatFriend(name: String) {
        _uiState.update { it.copy(activeChatFriend = name) }
        connectActiveChatFriendIfRoom()
    }

    fun createLiveChatRoom(friendName: String): String {
        val cleanName = friendName.trim().ifBlank { _uiState.value.strings.t("חדר חברים", "Friends Room") }
        val existing = _uiState.value.friends.firstOrNull { it.name.equals(cleanName, true) && it.roomCode.isNotBlank() }
        val roomCode = existing?.roomCode ?: UUID.randomUUID().toString().take(6).uppercase()
        val updatedFriend = FriendProfile(cleanName, _uiState.value.strings.t("חדר צ'אט חי • $roomCode", "Live Chat Room • $roomCode"), true, roomCode)
        val updatedList = (listOf(updatedFriend) + _uiState.value.friends.filterNot { it.name.equals(cleanName, true) }).take(25)
        prefs.edit().putString("friends_v11", encodeFriends(updatedList)).apply()
        _uiState.update {
            it.copy(
                friends = updatedList,
                activeChatFriend = cleanName,
                statusMessage = it.strings.t("חדר הצ'אט \"$cleanName\" נפתח (קוד: $roomCode)", "Live chat room opened ($roomCode)")
            )
        }
        connectActiveChatFriendIfRoom()
        return roomCode
    }

    fun joinLiveChatRoom(rawCode: String, friendName: String = "") {
        val cleanCode = rawCode.trim().uppercase().replace(Regex("[^A-Z0-9]"), "").take(10)
        if (cleanCode.length < 4) return
        val existing = _uiState.value.friends.firstOrNull { it.roomCode.equals(cleanCode, true) }
        val name = existing?.name ?: friendName.trim().ifBlank { "חדר $cleanCode" }
        val updatedFriend = FriendProfile(name, _uiState.value.strings.t("מחובר לחדר חי • $cleanCode", "Live Room • $cleanCode"), true, cleanCode)
        val updatedList = (listOf(updatedFriend) + _uiState.value.friends.filterNot { it.name.equals(name, true) || it.roomCode.equals(cleanCode, true) }).take(25)
        prefs.edit().putString("friends_v11", encodeFriends(updatedList)).apply()
        _uiState.update {
            it.copy(
                friends = updatedList,
                activeChatFriend = name,
                statusMessage = it.strings.t("הצטרפת לחדר הצ'אט החי ($cleanCode)!", "Joined live chat room ($cleanCode)!")
            )
        }
        connectActiveChatFriendIfRoom()
    }

    private fun connectActiveChatFriendIfRoom() {
        val st = _uiState.value
        val activeName = st.activeChatFriend
        val friend = st.friends.firstOrNull { it.name == activeName }
        val roomCode = friend?.roomCode.orEmpty()
        if (roomCode.length < 4 || st.settings.offlineMode) {
            chatSync.disconnect()
            _uiState.update { it.copy(activeChatLiveConnected = false) }
            return
        }
        chatSync.connect(
            scope = viewModelScope,
            roomCode = roomCode,
            serverBaseUrl = st.settings.jamSyncServerUrl,
            onConnectedChanged = { ok -> _uiState.update { it.copy(activeChatLiveConnected = ok) } },
            onMessage = { incoming ->
                val assigned = incoming.copy(friendThread = activeName)
                _uiState.update { cur ->
                    if (cur.messages.any { it.id == assigned.id }) cur
                    else {
                        val updated = (cur.messages + assigned).takeLast(120)
                        prefs.edit().putString("messages", encodeMessages(updated)).apply()
                        cur.copy(messages = updated)
                    }
                }
            }
        )
    }

    fun sendMessage(
        text: String,
        sharedTrack: Track? = null,
        timestampSec: Int? = null,
        friendThread: String = _uiState.value.activeChatFriend,
        sharedPlaylistName: String? = null,
        sharedPlaylistCode: String? = null,
        sharedJamCode: String? = null
    ) {
        if (text.isBlank() && sharedTrack == null && sharedJamCode == null && sharedPlaylistCode == null) return
        val msg = ChatMessage(
            friendThread = friendThread,
            sender = effectiveJamName(),
            text = text.trim(),
            sharedTrack = sharedTrack,
            sharedPlaylistName = sharedPlaylistName,
            sharedPlaylistCode = sharedPlaylistCode,
            sharedJamCode = sharedJamCode,
            trackTimestampSec = timestampSec
        )
        val updated = (_uiState.value.messages + msg).takeLast(120)
        prefs.edit().putString("messages", encodeMessages(updated)).apply()

        val friendObj = _uiState.value.friends.firstOrNull { it.name == friendThread }
        if (friendObj != null && friendObj.roomCode.length >= 4 && !_uiState.value.settings.offlineMode) {
            chatSync.send(viewModelScope, friendObj.roomCode, _uiState.value.settings.jamSyncServerUrl, msg)
        }

        _uiState.update {
            it.copy(
                messages = updated,
                activeChatFriend = friendThread,
                statusMessage = if (sharedTrack != null || sharedJamCode != null || sharedPlaylistCode != null) it.strings.t("נשלח ל-$friendThread", "Sent to $friendThread") else null
            )
        }
    }

    fun clearChatThread(friendName: String) {
        val updated = _uiState.value.messages.filterNot { it.friendThread == friendName }
        prefs.edit().putString("messages", encodeMessages(updated)).apply()
        _uiState.update { it.copy(messages = updated, statusMessage = it.strings.t("השיחה נוקתה", "Chat cleared")) }
    }

    fun addFriend(name: String) {
        val clean = name.trim()
        if (clean.isBlank()) return
        createLiveChatRoom(clean)
    }

    fun removeFriend(name: String) {
        val updated = _uiState.value.friends.filterNot { it.name == name }
        prefs.edit().putString("friends_v11", encodeFriends(updated)).apply()
        _uiState.update { it.copy(friends = updated, activeChatFriend = if (it.activeChatFriend == name) "כללי" else it.activeChatFriend) }
        connectActiveChatFriendIfRoom()
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
        _uiState.update { it.copy(isLoadingLyrics = true, showTranslatedLyrics = false, lyricsTranslateError = null) }
        lyricsJob?.cancel()
        lyricsJob = viewModelScope.launch {
            val lyrics = if (track.isPodcastOrAudiobook && customQuery.isNullOrBlank()) {
                repo.fetchPodcastTranscript(track, offlineOnly = _uiState.value.settings.offlineMode)
            } else {
                repo.fetchSyncedLyrics(track, customQuery, offlineOnly = _uiState.value.settings.offlineMode)
            }
            if (_uiState.value.currentTrack?.id == track.id) {
                _uiState.update { it.copy(lyrics = lyrics, isLoadingLyrics = false) }
            }
        }
    }

    // ---------- JSON Serialization ----------
    private fun encodePodcastComments(map: Map<String, List<PodcastComment>>): String {
        val root = JSONObject()
        map.forEach { (trackId, list) ->
            val arr = JSONArray()
            list.forEach { c ->
                arr.put(JSONObject().apply {
                    put("id", c.id); put("au", c.author); put("tx", c.text)
                    put("tm", c.timeAgo); put("lk", c.likes); put("ts", c.timestampSec ?: -1)
                })
            }
            root.put(trackId, arr)
        }
        return root.toString()
    }

    private fun decodePodcastComments(raw: String): Map<String, List<PodcastComment>> = runCatching {
        val root = JSONObject(raw)
        val out = LinkedHashMap<String, List<PodcastComment>>()
        root.keys().forEach { trackId ->
            val arr = root.optJSONArray(trackId) ?: return@forEach
            out[trackId] = List(arr.length()) { i -> arr.optJSONObject(i) }.mapNotNull { o ->
                o?.let {
                    val ts = it.optInt("ts", -1)
                    PodcastComment(
                        id = it.optString("id", UUID.randomUUID().toString()),
                        author = it.optString("au", "מאזין"),
                        text = it.optString("tx", ""),
                        timeAgo = it.optString("tm", ""),
                        likes = it.optInt("lk", 0),
                        timestampSec = if (ts >= 0) ts else null
                    )
                }
            }.filter { it.text.isNotBlank() }
        }
        out
    }.getOrDefault(emptyMap())

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
        list.forEach {
            put(JSONObject().apply {
                put("n", it.name); put("a", it.currentActivity); put("o", it.isOnline); put("r", it.roomCode)
            })
        }
    }.toString()

    private fun decodeFriends(raw: String): List<FriendProfile> {
        if (raw.isBlank()) return emptyList()
        return runCatching {
            val a = JSONArray(raw)
            List(a.length()) { i -> a.optJSONObject(i) }.mapNotNull { o ->
                o?.let { FriendProfile(it.optString("n"), it.optString("a"), it.optBoolean("o", true), it.optString("r", "")) }
            }
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
        put("covStk", p.customCoverSticker); put("covMode", p.coverMode); put("artCov", p.artistCoverUrl)
        put("blend", p.isBlend); put("match", p.blendMatchPercent ?: -1)
        put("pub", p.isPublic); put("col", p.isCollaborative); put("pin", p.isPinned)
        put("excl", p.excludeFromTaste); put("mix", p.isMixedMode); put("folder", p.folderId ?: "")
        put("seed", p.aiSeedPrompt); put("tag", p.aiStyleTag); put("created", p.createdAt)
        put("tracks", encodeTracks(p.tracks.filterNot { it.isSmartInjected }))
    }

    private fun decodePlaylistObj(o: JSONObject): Playlist {
        val m = o.optInt("match", -1); val bg = o.optLong("bgHex", -1L)
        val trks = decodeTracks(o.optString("tracks", "[]"))
        return Playlist(
            id = o.optString("id"), name = o.optString("name"), creator = o.optString("creator", "שלך"),
            description = o.optString("desc"), coverUrl = o.optString("cover").ifBlank { trks.firstOrNull()?.artworkUrl ?: "" },
            customCoverBgHex = if (bg != -1L) bg else null, customCoverText = o.optString("covTxt"),
            customCoverSticker = o.optString("covStk"), coverMode = o.optString("covMode", "AUTO"),
            artistCoverUrl = o.optString("artCov", ""), tracks = trks, isBlend = o.optBoolean("blend"),
            blendMatchPercent = if (m >= 0) m else null, isPublic = o.optBoolean("pub", true),
            isCollaborative = o.optBoolean("col"), isPinned = o.optBoolean("pin"),
            excludeFromTaste = o.optBoolean("excl"), isMixedMode = o.optBoolean("mix"),
            folderId = o.optString("folder").takeIf { it.isNotBlank() }, aiSeedPrompt = o.optString("seed"),
            aiStyleTag = o.optString("tag"), createdAt = o.optLong("created", System.currentTimeMillis())
        )
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
                put("tSec", m.trackTimestampSec ?: -1); put("plName", m.sharedPlaylistName ?: "")
                put("plCode", m.sharedPlaylistCode ?: ""); put("jam", m.sharedJamCode ?: "")
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
                add(ChatMessage(
                    id = o.optString("id"), friendThread = o.optString("th", "כללי"),
                    sender = o.optString("sender", "אתה"), text = o.optString("text"),
                    sharedTrack = trk, sharedPlaylistName = o.optString("plName").takeIf { it.isNotBlank() },
                    sharedPlaylistCode = o.optString("plCode").takeIf { it.isNotBlank() },
                    sharedJamCode = o.optString("jam").takeIf { it.isNotBlank() },
                    trackTimestampSec = if (tSec >= 0) tSec else null, timestampMs = o.optLong("ts")
                ))
            }
        }
    }.getOrDefault(emptyList())
}
