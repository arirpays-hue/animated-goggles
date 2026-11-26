package com.sonify.app

import android.content.ContentUris
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.MediaStore
import android.text.Html
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlin.math.abs

data class ArtistRelease(
    val id: String,
    val title: String,
    val subtitle: String,
    val artworkUrl: String,
    val isSingle: Boolean = false,
    val year: Int = 0
)

data class SimilarArtist(
    val name: String,
    val browseId: String = "",
    val artworkUrl: String = "",
    val subscribers: String = ""
)

data class ArtistProfileDetails(
    val artistName: String,
    val browseId: String = "",
    val bannerUrl: String = "",
    val avatarUrl: String = "",
    val subscribersLabel: String = "",
    val bio: String = "",
    val topTracks: List<Track> = emptyList(),
    val latestRelease: ArtistRelease? = null,
    val albums: List<ArtistRelease> = emptyList(),
    val singles: List<ArtistRelease> = emptyList(),
    val featuredPlaylists: List<RemotePlaylist> = emptyList(),
    val similarArtists: List<SimilarArtist> = emptyList()
)

class StreamRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .callTimeout(22, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(10, 5, TimeUnit.MINUTES))
        .followRedirects(true)
        .build()

    private val fastProxyClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // High-concurrency dispatcher removes OkHttp's default 5-per-host bottleneck for 8x-10x downloads!
    private val downloadClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        })
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(18, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.leptons.xyz"
    )

    private val invidiousInstances = listOf(
        "https://inv.nadeko.net",
        "https://invidious.nerdvpn.de",
        "https://yewtu.be"
    )

    private val cobaltInstances = listOf(
        "https://api.cobalt.tools",
        "https://cobalt-api.kwiatekmiki.com"
    )

    private val ytDlpInitLock = Any()
    @Volatile private var ytDlpInitialized = false
    @Volatile private var ytDlpUpdateRunning = false

    private val offlineDir: File
        get() = File(context.filesDir, "offline_tracks").apply { if (!exists()) mkdirs() }

    suspend fun prewarmYtDlp() = withContext(Dispatchers.IO) {
        if (!ensureYtDlpInitOnly()) return@withContext
        val dlPrefs = context.getSharedPreferences("sonify_ytdlp", Context.MODE_PRIVATE)
        val lastUpdate = dlPrefs.getLong("last_update_ms", 0L)
        val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
        if (!ytDlpUpdateRunning && (System.currentTimeMillis() - lastUpdate > sevenDaysMs)) {
            ytDlpUpdateRunning = true
            runCatching {
                withTimeoutOrNull(25_000L) {
                    YoutubeDL.getInstance().updateYoutubeDL(
                        context.applicationContext,
                        YoutubeDL.UpdateChannel.NIGHTLY
                    )
                    dlPrefs.edit().putLong("last_update_ms", System.currentTimeMillis()).apply()
                }
            }
            ytDlpUpdateRunning = false
        }
    }

    private fun ensureYtDlpInitOnly(): Boolean {
        if (ytDlpInitialized) return true
        synchronized(ytDlpInitLock) {
            if (!ytDlpInitialized) {
                ytDlpInitialized = runCatching {
                    YoutubeDL.getInstance().init(context.applicationContext)
                    true
                }.getOrDefault(false)
            }
        }
        return ytDlpInitialized
    }

    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun effectiveQuality(settings: AppSettings, forDownload: Boolean = false): AudioQuality {
        val raw = when {
            forDownload -> settings.downloadQuality
            settings.dataSaver -> AudioQuality.LOW
            isOnWifi() -> settings.wifiQuality
            else -> settings.cellularQuality
        }
        val clamped = settings.streamSource.clampQuality(raw)
        return if (!forDownload && settings.autoAdjustQuality && !isOnWifi() && clamped == AudioQuality.LOSSLESS) {
            AudioQuality.HIGH
        } else clamped
    }

    fun getOfflineFile(trackId: String): File {
        val safe = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(offlineDir, "$safe.media")
    }

    fun getOfflineTrackIds(): Set<String> {
        return offlineDir.listFiles()
            ?.filter { it.isFile && it.length() > 4096 }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
    }

    fun getOfflineSizeMb(): Float = (offlineDir.listFiles()?.sumOf { it.length() } ?: 0L) / (1024f * 1024f)

    fun getCacheSizeMb(): Float {
        fun dirSize(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf { dirSize(it) } ?: 0L else f.length()
        return dirSize(context.cacheDir) / (1024f * 1024f)
    }

    fun clearCache() { runCatching { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } } }
    fun deleteAllOfflineTracks() { runCatching { offlineDir.listFiles()?.forEach { it.delete() } } }
    fun deleteOfflineTrack(trackId: String) { val f = getOfflineFile(trackId); if (f.exists()) f.delete() }

    // ---------- 1. Search Songs / Podcasts / Audiobooks / Artist Tracks ----------
    suspend fun searchTracks(
        query: String,
        settings: AppSettings,
        podcastsOnly: Boolean = false,
        audiobooksOnly: Boolean = false,
        maxResults: Int = 30
    ): List<Track> = withContext(Dispatchers.IO) {
        if (settings.offlineMode && settings.streamSource != StreamSource.LOCAL) return@withContext emptyList()

        val cleanQuery = when {
            audiobooksOnly && !query.contains("audiobook", true) && !query.contains("ספר שמע") -> "$query full audiobook"
            podcastsOnly && !query.contains("podcast", true) && !query.contains("פודקאסט") -> "$query podcast"
            else -> query
        }

        val list = when (settings.streamSource) {
            StreamSource.YOUTUBE -> searchYouTubeStrict(cleanQuery, podcastsOnly, audiobooksOnly, maxResults)
            StreamSource.OPENSUBSONIC -> searchSubsonic(cleanQuery, settings)
            StreamSource.LOCAL -> searchLocalAudio(cleanQuery)
        }
        if (!settings.allowExplicit) list.filterNot { it.isExplicit } else list
    }

    suspend fun searchArtistTracks(artistName: String, settings: AppSettings): List<Track> = withContext(Dispatchers.IO) {
        val s1 = searchTracks(artistName, settings, maxResults = 25)
        val s2 = searchTracks("$artistName top hits", settings, maxResults = 20)
        (s1 + s2).distinctBy { it.id }.take(35)
    }

    // ---------- 1B. Full Spotify-Style Artist Profile (Real Artist Banner, Albums, Latest Release, Singles, Similar Artists!) ----------
    suspend fun fetchFullArtistProfile(
        artistName: String,
        settings: AppSettings,
        knownBrowseId: String = ""
    ): ArtistProfileDetails = withContext(Dispatchers.IO) {
        val cleanName = cleanArtistName(artistName).ifBlank { artistName.trim() }
        if (settings.offlineMode || settings.streamSource != StreamSource.YOUTUBE) {
            val tracks = searchArtistTracks(cleanName, settings)
            return@withContext ArtistProfileDetails(
                artistName = cleanName,
                bannerUrl = tracks.firstOrNull()?.artworkUrl ?: "",
                avatarUrl = tracks.firstOrNull()?.artworkUrl ?: "",
                topTracks = tracks
            )
        }

        // Step 1: Locate Artist's UC channel browseId & photo
        val foundArtist = if (knownBrowseId.startsWith("UC")) {
            SimilarArtist(cleanName, knownBrowseId, "", "")
        } else {
            findYtMusicArtistChannel(cleanName)
        }

        var bannerUrl = ""
        var avatarUrl = foundArtist?.artworkUrl.orEmpty()
        var subscribersLabel = foundArtist?.subscribers.orEmpty()
        var bio = ""
        val topTracks = mutableListOf<Track>()
        val albums = mutableListOf<ArtistRelease>()
        val singles = mutableListOf<ArtistRelease>()
        val featuredPlaylists = mutableListOf<RemotePlaylist>()
        val similarArtists = mutableListOf<SimilarArtist>()

        // Step 2: Browse full YouTube Music Artist Page
        if (foundArtist != null && foundArtist.browseId.startsWith("UC")) {
            val root = runCatching { fetchYtMusicBrowseJson(foundArtist.browseId) }.getOrNull()
            if (root != null) {
                val header = root.optJSONObject("header")
                val immersive = header?.optJSONObject("musicImmersiveHeaderRenderer")
                    ?: header?.optJSONObject("musicVisualHeaderRenderer")
                if (immersive != null) {
                    val desc = extractRunsText(immersive.optJSONObject("description"))
                    if (desc.isNotBlank()) bio = desc

                    val subBtn = immersive.optJSONObject("subscriptionButton")?.optJSONObject("subscribeButtonRenderer")
                    val subTxt = extractRunsText(subBtn?.optJSONObject("longSubscriberCountText"))
                        .ifBlank { extractRunsText(subBtn?.optJSONObject("shortSubscriberCountText")) }
                        .ifBlank { extractRunsText(subBtn?.optJSONObject("subscriberCountText")) }
                    if (subTxt.isNotBlank()) subscribersLabel = subTxt

                    val thumbs = immersive.optJSONObject("thumbnail")
                        ?.optJSONObject("musicThumbnailRenderer")
                        ?.optJSONObject("thumbnail")
                        ?.optJSONArray("thumbnails")
                        ?: immersive.optJSONObject("foregroundThumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                    if (thumbs != null && thumbs.length() > 0) {
                        val raw = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
                        if (raw.isNotBlank()) {
                            bannerUrl = upgradeWideBannerUrl(raw)
                            if (avatarUrl.isBlank()) avatarUrl = upgradeArtworkUrlGlobal(raw)
                        }
                    }
                }

                // Parse Sections: Top Songs, Albums, Singles, Featured On, Fans Also Like
                val sectionContents = root.optJSONObject("contents")
                    ?.optJSONObject("singleColumnBrowseResultsRenderer")
                    ?.optJSONArray("tabs")?.optJSONObject(0)
                    ?.optJSONObject("tabRenderer")
                    ?.optJSONObject("content")
                    ?.optJSONObject("sectionListRenderer")
                    ?.optJSONArray("contents")

                if (sectionContents != null) {
                    for (i in 0 until sectionContents.length()) {
                        val sec = sectionContents.optJSONObject(i) ?: continue
                        sec.optJSONObject("musicShelfRenderer")?.let { shelf ->
                            extractYtMusicSongs(shelf, topTracks, 35)
                        }
                        sec.optJSONObject("musicCarouselShelfRenderer")?.let { carousel ->
                            val headerTitle = extractRunsText(
                                carousel.optJSONObject("header")
                                    ?.optJSONObject("musicCarouselShelfBasicHeaderRenderer")
                                    ?.optJSONObject("title")
                            ).lowercase()
                            val items = carousel.optJSONArray("contents") ?: return@let
                            for (j in 0 until items.length()) {
                                val twoRow = items.optJSONObject(j)?.optJSONObject("musicTwoRowItemRenderer") ?: continue
                                val title = extractRunsText(twoRow.optJSONObject("title"))
                                val subtitle = extractRunsText(twoRow.optJSONObject("subtitle"))
                                val bId = twoRow.optJSONObject("navigationEndpoint")
                                    ?.optJSONObject("browseEndpoint")
                                    ?.optString("browseId").orEmpty()
                                val thumbs = twoRow.optJSONObject("thumbnailRenderer")
                                    ?.optJSONObject("musicThumbnailRenderer")
                                    ?.optJSONObject("thumbnail")
                                    ?.optJSONArray("thumbnails")
                                val rawArt = if (thumbs != null && thumbs.length() > 0) {
                                    thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
                                } else ""
                                val art = upgradeArtworkUrlGlobal(rawArt)
                                if (title.isBlank() || bId.isBlank()) continue

                                val yearMatch = Regex("(19|20)\\d{2}").find(subtitle)?.value?.toIntOrNull() ?: 0
                                val subLower = subtitle.lowercase()

                                when {
                                    bId.startsWith("UC") -> {
                                        if (similarArtists.none { it.name.equals(title, true) }) {
                                            similarArtists.add(SimilarArtist(title, bId, art, subtitle))
                                        }
                                    }
                                    bId.startsWith("MPRE") || bId.startsWith("OLAK") -> {
                                        val isSingle = headerTitle.contains("single") || headerTitle.contains("סינגל") ||
                                            subLower.contains("single") || subLower.contains("ep") || subLower.contains("סינגל")
                                        val rel = ArtistRelease(bId, title, subtitle.ifBlank { if (isSingle) "Single" else "Album" }, art, isSingle, yearMatch)
                                        if (isSingle) {
                                            if (singles.none { it.id == bId }) singles.add(rel)
                                        } else {
                                            if (albums.none { it.id == bId }) albums.add(rel)
                                        }
                                    }
                                    bId.startsWith("VL") || bId.startsWith("PL") || bId.startsWith("RDCLAK") -> {
                                        if (featuredPlaylists.none { it.id == bId }) {
                                            featuredPlaylists.add(RemotePlaylist(bId, title, subtitle.ifBlank { "Playlist" }, art))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Step 3: Supplement missing sections so Artist Page is ALWAYS Spotify-rich
        coroutineScope {
            val extraTracksJob = if (topTracks.size < 12) async { searchArtistTracks(cleanName, settings) } else null
            val extraAlbumsJob = if (albums.isEmpty() && singles.isEmpty()) async { searchYtMusicAlbums(cleanName) } else null
            val extraPlaylistsJob = if (featuredPlaylists.isEmpty()) async { searchPlaylists(cleanName).take(8) } else null

            extraTracksJob?.await()?.forEach { t -> if (topTracks.none { it.id == t.id }) topTracks.add(t) }
            extraAlbumsJob?.await()?.forEach { rel ->
                if (rel.isSingle) { if (singles.none { it.id == rel.id }) singles.add(rel) }
                else { if (albums.none { it.id == rel.id }) albums.add(rel) }
            }
            extraPlaylistsJob?.await()?.forEach { pl -> if (featuredPlaylists.none { it.id == pl.id }) featuredPlaylists.add(pl) }
        }

        val allReleases = (singles + albums).distinctBy { it.id }
        val latestRelease = allReleases.sortedWith(
            compareByDescending<ArtistRelease> { it.year }.thenByDescending { it.isSingle }
        ).firstOrNull()

        val fallbackArt = avatarUrl.ifBlank { topTracks.firstOrNull()?.artworkUrl.orEmpty() }
        ArtistProfileDetails(
            artistName = foundArtist?.name?.takeIf { it.isNotBlank() } ?: cleanName,
            browseId = foundArtist?.browseId.orEmpty(),
            bannerUrl = bannerUrl.ifBlank { fallbackArt },
            avatarUrl = fallbackArt,
            subscribersLabel = subscribersLabel,
            bio = bio,
            topTracks = topTracks.distinctBy { it.id }.take(35),
            latestRelease = latestRelease,
            albums = albums.sortedByDescending { it.year },
            singles = singles.sortedByDescending { it.year },
            featuredPlaylists = featuredPlaylists.take(10),
            similarArtists = similarArtists.take(12)
        )
    }

    private fun upgradeWideBannerUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.contains("googleusercontent.com") || url.contains("ggpht.com")) {
            return when {
                Regex("=w\\d+-h\\d+").containsMatchIn(url) ->
                    url.replace(Regex("=w\\d+-h\\d+.*"), "=w1440-h900-p-l95-rj")
                Regex("=s\\d+").containsMatchIn(url) ->
                    url.replace(Regex("=s\\d+.*"), "=w1440-h900-p-l95-rj")
                else -> url
            }
        }
        return upgradeArtworkUrlGlobal(url)
    }

    private fun findYtMusicArtistChannel(artistName: String): SimilarArtist? {
        val paramOptions = listOf("EgWKAQIgAWoKEAkQBRAKEAMQBA==", null)
        for (param in paramOptions) {
            val found = runCatching {
                val bodyJson = JSONObject().apply {
                    put("query", artistName)
                    if (param != null) put("params", param)
                    put("context", ytMusicContext())
                }
                val req = Request.Builder()
                    .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
                    .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                    .header("Origin", "https://music.youtube.com")
                    .header("Referer", "https://music.youtube.com/")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val out = mutableListOf<SimilarArtist>()
                    extractYtMusicArtists(JSONObject(resp.body?.string() ?: return@use null), out)
                    out.firstOrNull { it.name.equals(artistName, true) } ?: out.firstOrNull()
                }
            }.getOrNull()
            if (found != null) return found
        }
        return null
    }

    private fun extractYtMusicArtists(node: Any?, out: MutableList<SimilarArtist>) {
        if (out.size >= 8 || node == null) return
        when (node) {
            is JSONObject -> {
                if (node.has("musicCardShelfRenderer")) {
                    val card = node.optJSONObject("musicCardShelfRenderer")
                    val bId = card?.optJSONObject("title")?.optJSONArray("runs")?.optJSONObject(0)
                        ?.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                    if (bId.startsWith("UC")) {
                        val name = extractRunsText(card?.optJSONObject("title"))
                        val sub = extractRunsText(card?.optJSONObject("subtitle"))
                        val thumbs = card?.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        val raw = if (thumbs != null && thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() else ""
                        if (name.isNotBlank()) out.add(SimilarArtist(name, bId, upgradeArtworkUrlGlobal(raw), sub))
                    }
                }
                if (node.has("musicResponsiveListItemRenderer")) {
                    val item = node.optJSONObject("musicResponsiveListItemRenderer") ?: return
                    val bId = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                    if (bId.startsWith("UC")) {
                        val cols = item.optJSONArray("flexColumns")
                        val name = cols?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.let { extractRunsText(it) }.orEmpty()
                        val sub = cols?.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.let { extractRunsText(it) }.orEmpty()
                        val thumbs = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        val raw = if (thumbs != null && thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() else ""
                        if (name.isNotBlank()) out.add(SimilarArtist(name, bId, upgradeArtworkUrlGlobal(raw), sub))
                    }
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) extractYtMusicArtists(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> { for (i in 0 until node.length()) extractYtMusicArtists(node.opt(i), out) }
        }
    }

    private fun fetchYtMusicBrowseJson(browseId: String): JSONObject? {
        val bodyJson = JSONObject().apply {
            put("browseId", browseId)
            put("context", ytMusicContext())
        }
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) null else JSONObject(resp.body?.string() ?: return null)
        }
    }

    private fun searchYtMusicAlbums(artistName: String): List<ArtistRelease> {
        val bodyJson = JSONObject().apply {
            put("query", artistName)
            put("params", "EgWKAQIYAWoKEAkQBRAKEAMQBA==")
            put("context", ytMusicContext())
        }
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val out = mutableListOf<ArtistRelease>()
                extractYtMusicAlbums(JSONObject(resp.body?.string() ?: return@use emptyList()), out)
                out.distinctBy { it.id }
            }
        }.getOrDefault(emptyList())
    }

    private fun extractYtMusicAlbums(node: Any?, out: MutableList<ArtistRelease>) {
        if (out.size >= 16 || node == null) return
        when (node) {
            is JSONObject -> {
                if (node.has("musicResponsiveListItemRenderer")) {
                    val item = node.optJSONObject("musicResponsiveListItemRenderer") ?: return
                    val bId = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                    if (bId.startsWith("MPRE") || bId.startsWith("OLAK")) {
                        val cols = item.optJSONArray("flexColumns")
                        val title = cols?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.let { extractRunsText(it) }.orEmpty()
                        val sub = cols?.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.let { extractRunsText(it) }.orEmpty()
                        val thumbs = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        val raw = if (thumbs != null && thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() else ""
                        val yr = Regex("(19|20)\\d{2}").find(sub)?.value?.toIntOrNull() ?: 0
                        val isSingle = sub.contains("Single", true) || sub.contains("EP", true) || sub.contains("סינגל")
                        if (title.isNotBlank()) out.add(ArtistRelease(bId, title, sub, upgradeArtworkUrlGlobal(raw), isSingle, yr))
                    }
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) extractYtMusicAlbums(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> { for (i in 0 until node.length()) extractYtMusicAlbums(node.opt(i), out) }
        }
    }

    // ---------- Official Music Video Finder ----------
    suspend fun findOfficialMusicVideoId(track: Track): String? = withContext(Dispatchers.IO) {
        val cleanTitle = cleanSongTitle(track.title)
        val cleanArtist = cleanArtistName(track.artist)
        val hasHebrew = cleanTitle.any { it in '\u0590'..'\u05FF' } || cleanArtist.any { it in '\u0590'..'\u05FF' }

        val queries = buildList {
            add("$cleanArtist $cleanTitle official music video")
            if (hasHebrew) add("$cleanArtist $cleanTitle קליפ רשמי")
            add("$cleanArtist $cleanTitle official video")
        }

        for (q in queries) {
            val candidates = runCatching { searchYouTubeWeb(q, false, false, 10) }.getOrDefault(emptyList())
            val scored = candidates
                .filter { cand ->
                    cand.id != track.id &&
                        !cand.artist.endsWith("- Topic", true) &&
                        (cand.durationMs == 0L || cand.durationMs in 70_000L..540_000L)
                }
                .map { cand ->
                    var score = 0
                    val lt = cand.title.lowercase()
                    if (lt.contains("official music video") || lt.contains("קליפ רשמי")) score += 60
                    else if (lt.contains("official video") || lt.contains("music video") || lt.contains("קליפ")) score += 45
                    if (lt.contains("official audio") || lt.contains("lyric") || lt.contains("מילים") || lt.contains("audio only")) score -= 40
                    if (lt.contains("live") || lt.contains("cover") || lt.contains("remix") || lt.contains("karaoke")) score -= 25
                    if (track.durationMs > 0 && cand.durationMs > 0) {
                        val diffSec = abs(track.durationMs - cand.durationMs) / 1000
                        if (diffSec < 45) score += 20 else if (diffSec > 120) score -= 25
                    }
                    cand to score
                }
                .sortedByDescending { it.second }

            val best = scored.firstOrNull { it.second >= 10 }?.first
            if (best != null) return@withContext best.id
        }
        null
    }

    private fun searchYouTubeStrict(query: String, isPodcast: Boolean, isAudiobook: Boolean, maxResults: Int): List<Track> {
        val longForm = isPodcast || isAudiobook
        val raw = if (!longForm) {
            runCatching { searchYouTubeMusicSongs(query, maxResults) }.getOrDefault(emptyList())
                .ifEmpty { runCatching { searchYouTubeWeb(query, false, false, maxResults) }.getOrDefault(emptyList()) }
                .ifEmpty { searchPiped(query, false, false) }
                .ifEmpty { searchInvidious(query, false, false) }
        } else {
            runCatching { searchYouTubeWeb(query, isPodcast, isAudiobook, maxResults) }.getOrDefault(emptyList())
                .ifEmpty { searchPiped(query, isPodcast, isAudiobook) }
        }
        if (longForm) return raw.distinctBy { it.id }.take(maxResults)
        return raw.filter { isLikelySingleSong(it) }.distinctBy { it.id }.take(maxResults)
    }

    private fun isLikelySingleSong(t: Track): Boolean {
        if (t.durationMs > 570_000L || (t.durationMs in 1L..35_000L)) return false
        val lower = t.title.lowercase()
        val banned = listOf(
            "1 hour", "2 hour", "3 hour", "hour playlist", "hour mix",
            "full album", "nonstop", "non-stop", "compilation", "megamix",
            "best songs of", "top 50", "top 100", "playlist 202", "music 2024",
            "music 2025", "music 2026", "פלייליסט", "מיקס", "מחרוזת", "שעה של", "שעתיים"
        )
        return banned.none { lower.contains(it) }
    }

    private fun detectExplicit(title: String): Boolean {
        val l = title.lowercase()
        return l.contains("explicit") || l.contains("(e)") || l.contains("uncensored") || l.contains("fuck") || l.contains("bitch")
    }

    private fun searchYouTubeMusicSongs(query: String, limit: Int): List<Track> {
        val bodyJson = JSONObject().apply {
            put("query", query)
            put("params", "EgWKAQIIAWoKEAkQBRAKEAMQBA==")
            put("context", ytMusicContext())
        }
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val out = mutableListOf<Track>()
            extractYtMusicSongs(JSONObject(resp.body?.string() ?: return emptyList()), out, limit)
            out.distinctBy { it.id }
        }
    }

    // ---------- 2. Enhanced Ready YouTube Playlists Search, Browse & Import ----------
    suspend fun searchPlaylists(query: String): List<RemotePlaylist> = withContext(Dispatchers.IO) {
        val combined = mutableListOf<RemotePlaylist>()

        // Run Featured + Community YouTube Music playlist searches concurrently!
        val (featured, community) = coroutineScope {
            val d1 = async { searchYtMusicPlaylistsByParam(query, "EgeKAQQoADgBagwQDhAKEAMQBRAJEAQ=") }
            val d2 = async { searchYtMusicPlaylistsByParam(query, "EgeKAQQoAEABagoQAxAEEAkQChAF") }
            d1.await() to d2.await()
        }
        combined.addAll(featured)
        combined.addAll(community)

        if (combined.size < 6) {
            combined.addAll(runCatching { searchYouTubeWebPlaylists(query) }.getOrDefault(emptyList()))
        }

        if (combined.isEmpty()) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            for (base in pipedInstances) {
                val res = runCatching {
                    fastProxyClient.newCall(Request.Builder().url("$base/search?q=$encoded&filter=music_playlists").build()).execute().use { resp ->
                        if (!resp.isSuccessful) return@use emptyList()
                        val items = JSONObject(resp.body?.string() ?: return@use emptyList()).optJSONArray("items") ?: return@use emptyList()
                        buildList {
                            for (i in 0 until items.length()) {
                                val o = items.optJSONObject(i) ?: continue
                                val id = o.optString("url").substringAfter("list=", "")
                                if (id.isBlank()) continue
                                add(RemotePlaylist(if (id.startsWith("VL")) id else "VL$id", o.optString("name", "פלייליסט"), o.optString("uploaderName", "YouTube Music"), upgradeArtworkUrlGlobal(o.optString("thumbnail"))))
                            }
                        }
                    }
                }.getOrDefault(emptyList())
                if (res.isNotEmpty()) { combined.addAll(res); break }
            }
        }
        combined.distinctBy { it.id.removePrefix("VL") }.take(30)
    }

    private fun searchYtMusicPlaylistsByParam(query: String, param: String): List<RemotePlaylist> {
        val bodyJson = JSONObject().apply {
            put("query", query)
            put("params", param)
            put("context", ytMusicContext())
        }
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val out = mutableListOf<RemotePlaylist>()
                extractYtMusicPlaylists(JSONObject(resp.body?.string() ?: return@use emptyList()), out)
                out
            }
        }.getOrDefault(emptyList())
    }

    private fun searchYouTubeWebPlaylists(query: String): List<RemotePlaylist> {
        val bodyJson = JSONObject().apply {
            put("query", query)
            put("params", "EgIQAw==") // Playlists filter on YouTube Web
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB"); put("clientVersion", "2.20250219.01.00"); put("hl", "he"); put("gl", "IL")
                })
            })
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val out = mutableListOf<RemotePlaylist>()
            extractWebPlaylistRenderers(JSONObject(resp.body?.string() ?: return emptyList()), out)
            out
        }
    }

    private fun extractWebPlaylistRenderers(node: Any?, out: MutableList<RemotePlaylist>) {
        if (out.size >= 20 || node == null) return
        when (node) {
            is JSONObject -> {
                if (node.has("playlistRenderer")) {
                    val p = node.optJSONObject("playlistRenderer")
                    val id = p?.optString("playlistId").orEmpty()
                    if (id.isNotBlank()) {
                        val title = extractRunsText(p?.optJSONObject("title"))
                        val count = p?.optString("videoCount").orEmpty()
                        val by = extractRunsText(p?.optJSONObject("shortBylineText")).ifBlank { "YouTube" }
                        val thumbs = p?.optJSONArray("thumbnails")?.optJSONObject(0)?.optJSONArray("thumbnails")
                        val raw = if (thumbs != null && thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() else ""
                        if (title.isNotBlank()) {
                            val sub = if (count.isNotBlank()) "$by • $count שירים" else by
                            out.add(RemotePlaylist("VL$id", title, sub, upgradeArtworkUrlGlobal(raw)))
                        }
                    }
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) extractWebPlaylistRenderers(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> { for (i in 0 until node.length()) extractWebPlaylistRenderers(node.opt(i), out) }
        }
    }

    suspend fun fetchReadyYoutubePlaylists(queries: List<String>, maxTotal: Int = 12): List<RemotePlaylist> = withContext(Dispatchers.IO) {
        val all = coroutineScope {
            queries.take(3).map { q -> async { runCatching { searchPlaylists(q).take(5) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        }
        all.distinctBy { it.id.removePrefix("VL") }.take(maxTotal)
    }

    suspend fun fetchPlaylistTracks(playlistId: String, maxTracks: Int = 100, fallbackCoverUrl: String = ""): List<Track> = withContext(Dispatchers.IO) {
        val browseId = if (playlistId.startsWith("VL") || playlistId.startsWith("MPRE")) playlistId else "VL$playlistId"
        val rawId = playlistId.removePrefix("VL")

        val ytMusicTracks = runCatching {
            val root = fetchYtMusicBrowseJson(browseId) ?: return@runCatching emptyList()
            val headerThumb = root.optJSONObject("header")
                ?.let { h -> h.optJSONObject("musicDetailHeaderRenderer") ?: h.optJSONObject("musicResponsiveHeaderRenderer") }
                ?.optJSONObject("thumbnail")
                ?.let { t -> t.optJSONObject("croppedSquareThumbnailRenderer") ?: t.optJSONObject("musicThumbnailRenderer") }
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty() else "" }
                .orEmpty()
            val effectiveCover = upgradeArtworkUrlGlobal(fallbackCoverUrl.ifBlank { headerThumb })

            val out = mutableListOf<Track>()
            extractYtMusicSongs(root, out, maxTracks, fallbackCover = effectiveCover)
            out.filter { isLikelySingleSong(it) }.distinctBy { it.id }
        }.getOrDefault(emptyList())
        if (ytMusicTracks.isNotEmpty()) return@withContext ytMusicTracks

        for (base in pipedInstances) {
            val res = runCatching {
                fastProxyClient.newCall(Request.Builder().url("$base/playlists/$rawId").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val arr = JSONObject(resp.body?.string() ?: return@use emptyList()).optJSONArray("relatedStreams") ?: return@use emptyList()
                    buildList {
                        for (i in 0 until arr.length().coerceAtMost(maxTracks)) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString("url").substringAfter("v=", "").substringBefore("&")
                            if (id.length != 11) continue
                            val title = o.optString("title", "ללא שם")
                            val thumb = o.optString("thumbnail").ifBlank { fallbackCoverUrl }.ifBlank { "https://i.ytimg.com/vi/$id/maxresdefault.jpg" }
                            add(Track(id, title, o.optString("uploaderName", "אמן"), artworkUrl = upgradeArtworkUrlGlobal(thumb), durationMs = o.optLong("duration", 0L) * 1000L, source = StreamSource.YOUTUBE, isExplicit = detectExplicit(title)))
                        }
                    }.filter { isLikelySingleSong(it) }
                }
            }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return@withContext res
        }
        emptyList()
    }

    suspend fun importPlaylistFromTextOrUrl(rawInput: String, settings: AppSettings): List<Track> = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()
        if (trimmed.contains("list=", ignoreCase = true)) {
            val listId = trimmed.substringAfter("list=").substringBefore("&").substringBefore("\n").trim()
            if (listId.isNotBlank()) {
                val tracks = fetchPlaylistTracks(listId, maxTracks = 100)
                if (tracks.isNotEmpty()) return@withContext tracks
            }
        }
        val lines = trimmed.lines().map { it.trim() }.filter { it.length >= 2 }.take(60)
        val imported = mutableListOf<Track>()
        for (line in lines) {
            val cleaned = line.replace(Regex("^\\d+[.)\\s-]+"), "").trim()
            val found = searchTracks(cleaned, settings, maxResults = 5)
            found.firstOrNull { cand -> imported.none { it.id == cand.id } }?.let { imported.add(it) }
        }
        imported
    }

    private fun ytMusicContext(): JSONObject = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX"); put("clientVersion", "1.20250219.01.00"); put("hl", "he"); put("gl", "IL")
        })
    }

    private fun extractYtMusicSongs(node: Any?, out: MutableList<Track>, maxLimit: Int = 100, fallbackCover: String = "") {
        if (out.size >= maxLimit || node == null) return
        when (node) {
            is JSONObject -> {
                if (node.has("musicResponsiveListItemRenderer")) {
                    val item = node.optJSONObject("musicResponsiveListItemRenderer") ?: return
                    val videoId = item.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
                        .ifBlank {
                            item.optJSONObject("overlay")
                                ?.optJSONObject("musicItemThumbnailOverlayRenderer")
                                ?.optJSONObject("content")
                                ?.optJSONObject("musicPlayButtonRenderer")
                                ?.optJSONObject("playNavigationEndpoint")
                                ?.optJSONObject("watchEndpoint")
                                ?.optString("videoId").orEmpty()
                        }
                    if (videoId.length == 11) {
                        val flexCols = item.optJSONArray("flexColumns")
                        val title = flexCols?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.let { extractRunsText(it) }.orEmpty()
                        val metaRuns = flexCols?.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.optJSONArray("runs")
                        val parts = mutableListOf<String>()
                        if (metaRuns != null) {
                            for (i in 0 until metaRuns.length()) {
                                val t = metaRuns.optJSONObject(i)?.optString("text")?.trim().orEmpty()
                                if (t.isNotBlank() && t != "•" && t != "·" && t != "שיר" && !t.equals("Song", true) && !t.equals("Video", true)) parts.add(t)
                            }
                        }
                        var durationMs = 0L
                        val fixedDur = item.optJSONArray("fixedColumns")?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")?.optJSONObject("text")?.let { extractRunsText(it) }.orEmpty()
                        if (fixedDur.contains(":")) durationMs = parseDurationText(fixedDur)
                        if (durationMs == 0L) {
                            val durPart = parts.lastOrNull { it.matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?")) }
                            if (durPart != null) { durationMs = parseDurationText(durPart); parts.remove(durPart) }
                        }
                        val artist = parts.firstOrNull { !it.contains("צפיות") && !it.contains("plays", true) && !it.contains("views", true) } ?: "אמן"
                        val album = parts.getOrNull(1)?.takeIf { !it.contains("צפיות") && !it.contains("plays", true) } ?: ""
                        val thumbs = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        val rawThumb = if (thumbs != null && thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() else ""
                        val art = upgradeArtworkUrlGlobal(rawThumb.ifBlank { fallbackCover }.ifBlank { "https://i.ytimg.com/vi/$videoId/maxresdefault.jpg" })
                        val hasExplicitBadge = item.optJSONArray("badges")?.toString()?.contains("MUSIC_EXPLICIT_BADGE", true) == true
                        if (title.isNotBlank()) {
                            out.add(Track(videoId, title, artist, album, art, durationMs, StreamSource.YOUTUBE, isExplicit = hasExplicitBadge || detectExplicit(title)))
                        }
                    }
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) extractYtMusicSongs(node.opt(keys.next()), out, maxLimit, fallbackCover)
                }
            }
            is JSONArray -> { for (i in 0 until node.length()) extractYtMusicSongs(node.opt(i), out, maxLimit, fallbackCover) }
        }
    }

    private fun extractYtMusicPlaylists(node: Any?, out: MutableList<RemotePlaylist>) {
        if (out.size >= 30 || node == null) return
        when (node) {
            is JSONObject -> {
                if (node.has("musicResponsiveListItemRenderer")) {
                    val item = node.optJSONObject("musicResponsiveListItemRenderer") ?: return
                    val browseId = item.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")?.optString("browseId").orEmpty()
                    if (browseId.startsWith("VL") || browseId.startsWith("PL") || browseId.startsWith("RDCLAK")) {
                        val flexCols = item.optJSONArray("flexColumns")
                        val title = flexCols?.optJSONObject(0)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.let { extractRunsText(it) }.orEmpty()
                        val subtitle = flexCols?.optJSONObject(1)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")?.optJSONObject("text")?.let { extractRunsText(it) }.orEmpty()
                        val thumbs = item.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                        val rawThumb = if (thumbs != null && thumbs.length() > 0) thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty() else ""
                        if (title.isNotBlank()) out.add(RemotePlaylist(browseId, title, subtitle.ifBlank { "פלייליסט" }, upgradeArtworkUrlGlobal(rawThumb)))
                    }
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) extractYtMusicPlaylists(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> { for (i in 0 until node.length()) extractYtMusicPlaylists(node.opt(i), out) }
        }
    }

    private fun searchYouTubeWeb(query: String, isPodcast: Boolean, isAudiobook: Boolean, maxLimit: Int = 30): List<Track> {
        val bodyJson = JSONObject().apply {
            put("query", query)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB"); put("clientVersion", "2.20250201.00.00"); put("hl", "he"); put("gl", "IL")
                })
            })
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val out = mutableListOf<Track>()
            extractVideoRenderers(JSONObject(resp.body?.string() ?: return emptyList()), out, isPodcast, isAudiobook, maxLimit)
            out.distinctBy { it.id }
        }
    }

    private fun searchPiped(query: String, isPodcast: Boolean, isAudiobook: Boolean): List<Track> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (base in pipedInstances) {
            val res = runCatching {
                fastProxyClient.newCall(Request.Builder().url("$base/search?q=$encoded&filter=music_songs").header("User-Agent", "Mozilla/5.0").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val items = JSONObject(resp.body?.string() ?: return@use emptyList()).optJSONArray("items") ?: return@use emptyList()
                    buildList {
                        for (i in 0 until items.length()) {
                            val o = items.optJSONObject(i) ?: continue
                            val id = o.optString("url").substringAfter("v=", "").substringBefore("&")
                            if (id.length != 11) continue
                            val title = o.optString("title", "ללא שם")
                            add(Track(id, title, o.optString("uploaderName", "אמן"), artworkUrl = upgradeArtworkUrlGlobal(o.optString("thumbnail").ifBlank { "https://i.ytimg.com/vi/$id/maxresdefault.jpg" }), durationMs = o.optLong("duration", 0L) * 1000L, source = StreamSource.YOUTUBE, isPodcast = isPodcast, isAudiobook = isAudiobook, isExplicit = detectExplicit(title)))
                        }
                    }
                }
            }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    private fun searchInvidious(query: String, isPodcast: Boolean, isAudiobook: Boolean): List<Track> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (base in invidiousInstances) {
            val res = runCatching {
                fastProxyClient.newCall(Request.Builder().url("$base/api/v1/search?q=$encoded&type=video").header("User-Agent", "Mozilla/5.0").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val arr = JSONArray(resp.body?.string() ?: return@use emptyList())
                    buildList {
                        for (i in 0 until arr.length().coerceAtMost(25)) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString("videoId")
                            if (id.length != 11) continue
                            val title = o.optString("title", "ללא שם")
                            add(Track(id, title, o.optString("author", "YouTube"), artworkUrl = "https://i.ytimg.com/vi/$id/maxresdefault.jpg", durationMs = o.optLong("lengthSeconds", 0L) * 1000L, source = StreamSource.YOUTUBE, isPodcast = isPodcast, isAudiobook = isAudiobook, isExplicit = detectExplicit(title)))
                        }
                    }
                }
            }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    private fun extractVideoRenderers(node: Any?, out: MutableList<Track>, isPodcast: Boolean, isAudiobook: Boolean, maxLimit: Int) {
        if (out.size >= maxLimit || node == null) return
        when (node) {
            is JSONObject -> {
                if (node.has("videoRenderer")) {
                    val v = node.optJSONObject("videoRenderer")
                    val id = v?.optString("videoId").orEmpty()
                    if (id.length == 11) {
                        val title = extractRunsText(v?.optJSONObject("title"))
                        val artist = extractRunsText(v?.optJSONObject("ownerText")).ifBlank { extractRunsText(v?.optJSONObject("shortBylineText")) }.ifBlank { "YouTube" }
                        val durText = v?.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
                        if (title.isNotBlank()) {
                            out.add(Track(id, title, artist, artworkUrl = "https://i.ytimg.com/vi/$id/maxresdefault.jpg", durationMs = parseDurationText(durText), source = StreamSource.YOUTUBE, isPodcast = isPodcast, isAudiobook = isAudiobook, isExplicit = detectExplicit(title)))
                        }
                    }
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) extractVideoRenderers(node.opt(keys.next()), out, isPodcast, isAudiobook, maxLimit)
                }
            }
            is JSONArray -> { for (i in 0 until node.length()) extractVideoRenderers(node.opt(i), out, isPodcast, isAudiobook, maxLimit) }
        }
    }

    private fun extractRunsText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        return buildString { for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty()) }
    }

    private fun parseDurationText(text: String): Long {
        val parts = text.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            else -> 0L
        }
    }

    // ---------- 3. Verified Stream Resolution ----------
    suspend fun resolveStreams(track: Track, settings: AppSettings, attemptIndex: Int = 0, forDownload: Boolean = false): ResolvedStreams? = withContext(Dispatchers.IO) {
        val offlineFile = getOfflineFile(track.id)
        if (!forDownload && offlineFile.exists() && offlineFile.length() > 4096) {
            return@withContext ResolvedStreams(audioUrl = offlineFile.toURI().toString(), isLossless = track.source != StreamSource.YOUTUBE, isOffline = true, codecLabel = "OFFLINE")
        }
        if (track.source == StreamSource.LOCAL && !track.localUri.isNullOrBlank()) {
            val isFlac = track.title.endsWith(".flac", true) || track.album.contains("FLAC", true)
            return@withContext ResolvedStreams(audioUrl = track.localUri, isLossless = isFlac, codecLabel = if (isFlac) "FLAC Lossless" else "LOCAL")
        }
        if (settings.offlineMode) return@withContext null

        val quality = effectiveQuality(settings, forDownload)
        if (track.source == StreamSource.OPENSUBSONIC && settings.subsonicUrl.isNotBlank()) {
            val auth = buildSubsonicAuth(settings.subsonicUser, settings.subsonicPass)
            val formatParam = if (quality == AudioQuality.LOSSLESS) "format=raw" else "maxBitRate=${quality.kbps}"
            val base = settings.subsonicUrl.trimEnd('/')
            val url = "$base/rest/stream.view?id=${URLEncoder.encode(track.id, "UTF-8")}&$formatParam&$auth"
            return@withContext ResolvedStreams(audioUrl = url, isLossless = quality == AudioQuality.LOSSLESS, codecLabel = if (quality == AudioQuality.LOSSLESS) "FLAC Lossless" else "${quality.kbps}kbps")
        }
        resolveYouTubeNonWrapping(track.id, quality, attemptIndex)
    }

    private fun verifyMediaStreamDeep(url: String, userAgent: String, cookie: String? = null, depth: Int = 0): Boolean {
        if (depth > 3) return false
        return runCatching {
            if (url.contains(".m3u8", true) || url.contains("manifest/hls", true)) {
                val rb = Request.Builder().url(url).header("User-Agent", userAgent)
                if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
                val text = probeClient.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return false
                    resp.body?.string().orEmpty()
                }
                val firstSubUrl = extractFirstHlsTarget(text) ?: return false
                return verifyMediaStreamDeep(firstSubUrl, userAgent, cookie, depth + 1)
            }
            val probeUrl = if (url.contains("googlevideo.com") && !url.contains("range=")) {
                val sep = if (url.contains("?")) "&" else "?"
                "${url}${sep}range=0-65535"
            } else url
            val rb = Request.Builder().url(probeUrl).header("User-Agent", userAgent)
            if (!probeUrl.contains("range=")) rb.header("Range", "bytes=0-65535")
            if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
            probeClient.newCall(rb.build()).execute().use { resp ->
                if (resp.code !in listOf(200, 206)) return false
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("text/html") || ct.contains("application/json") || ct.contains("yt-ump")) return false
                val buf = ByteArray(2048)
                (resp.body?.byteStream()?.read(buf) ?: 0) > 256
            }
        }.getOrDefault(false)
    }

    private fun extractFirstHlsTarget(manifest: String): String? {
        val mediaAudio = Regex("TYPE=AUDIO.*?URI=\"(https?://[^\"]+)\"").find(manifest)?.groupValues?.get(1)
        if (!mediaAudio.isNullOrBlank()) return mediaAudio
        return manifest.lines().map { it.trim() }.firstOrNull { it.startsWith("http") }
    }

    private fun youtubeStrategies(videoId: String, quality: AudioQuality): List<() -> ResolvedStreams?> = listOf(
        { fetchInnerTubeClient(videoId, "ANDROID_VR", quality, preferHls = false) },
        { fetchInnerTubeClient(videoId, "IOS", quality, preferHls = true) },
        { fetchInnerTubeClient(videoId, "ANDROID_VR_LEGACY", quality, preferHls = false) },
        { fetchCobaltAudio(videoId) },
        { fetchPipedStream(videoId, quality) },
        { fetchInvidiousProxiedStream(videoId) }
    )

    private fun resolveYouTubeNonWrapping(videoId: String, quality: AudioQuality, startAttempt: Int): ResolvedStreams? {
        val strategies = youtubeStrategies(videoId, quality)
        for (i in startAttempt until strategies.size) {
            val res = runCatching { strategies[i]() }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun fetchInnerTubeClient(videoId: String, clientType: String, quality: AudioQuality, preferHls: Boolean): ResolvedStreams? {
        val (clientObj, ua, idHeader, verHeader) = when (clientType) {
            "ANDROID_VR" -> Quad(
                JSONObject().apply {
                    put("clientName", "ANDROID_VR"); put("clientVersion", "1.62.27")
                    put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L")
                    put("deviceMake", "Oculus"); put("deviceModel", "Quest 3"); put("hl", "he"); put("gl", "IL")
                },
                "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                "28", "1.62.27"
            )
            "IOS" -> Quad(
                JSONObject().apply {
                    put("clientName", "IOS"); put("clientVersion", "20.11.6")
                    put("deviceMake", "Apple"); put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone"); put("osVersion", "18.3.2.22D82"); put("hl", "he"); put("gl", "IL")
                },
                "com.google.ios.youtube/20.11.6 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)",
                "5", "20.11.6"
            )
            else -> Quad(
                JSONObject().apply {
                    put("clientName", "ANDROID_VR"); put("clientVersion", "1.56.21")
                    put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L")
                    put("deviceMake", "Oculus"); put("deviceModel", "Quest 3"); put("hl", "he"); put("gl", "IL")
                },
                "com.google.android.apps.youtube.vr.oculus/1.56.21 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                "28", "1.56.21"
            )
        }
        val bodyJson = JSONObject().apply {
            put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true)
            put("context", JSONObject().apply { put("client", clientObj) })
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", ua)
            .header("X-YouTube-Client-Name", idHeader)
            .header("X-YouTube-Client-Version", verHeader)
            .build()

        return fastProxyClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string() ?: return null)
            if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
            val sd = root.optJSONObject("streamingData") ?: return null
            val hls = sd.optString("hlsManifestUrl").takeIf { it.startsWith("http") }

            val audioCandidates = mutableListOf<Pair<Int, String>>()
            val videoCandidates = mutableListOf<Pair<Int, String>>()
            var muxedVideoUrl: String? = null

            sd.optJSONArray("formats")?.let { formats ->
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val u = f.optString("url")
                    if (u.startsWith("http")) { muxedVideoUrl = u; audioCandidates.add((f.optInt("bitrate", 128000) / 1000) to u) }
                }
            }
            sd.optJSONArray("adaptiveFormats")?.let { adaptive ->
                for (i in 0 until adaptive.length()) {
                    val f = adaptive.optJSONObject(i) ?: continue
                    val mime = f.optString("mimeType"); val u = f.optString("url")
                    if (!u.startsWith("http")) continue
                    if (mime.startsWith("audio/")) audioCandidates.add((f.optInt("bitrate", 128000) / 1000) to u)
                    else if (mime.startsWith("video/mp4")) {
                        val h = f.optInt("height", 360)
                        if (h in 240..720) videoCandidates.add(h to u)
                    }
                }
            }

            val preferredAudio = pickAudioByQuality(audioCandidates, quality)
            val candidateList = (if (preferHls) listOfNotNull(hls, preferredAudio, muxedVideoUrl) else listOfNotNull(preferredAudio, muxedVideoUrl, hls)).distinct()
            val verifiedAudio = candidateList.firstOrNull { verifyMediaStreamDeep(it, ua) } ?: return null
            val bestVideoOnly = videoCandidates.sortedBy { it.first }.lastOrNull { it.first <= 720 }?.second
            ResolvedStreams(verifiedAudio, muxedVideoUrl ?: hls, bestVideoOnly, ua, codecLabel = if (verifiedAudio == hls) "HLS AAC" else "${quality.kbps.coerceAtMost(256)}kbps Opus/AAC")
        }
    }

    private fun fetchCobaltAudio(videoId: String): ResolvedStreams? {
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        val body = JSONObject().apply {
            put("url", "https://www.youtube.com/watch?v=$videoId")
            put("downloadMode", "audio"); put("audioFormat", "mp3")
        }.toString()
        for (base in cobaltInstances) {
            val res = runCatching {
                val req = Request.Builder().url("$base/").post(body.toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json").header("User-Agent", ua).build()
                fastProxyClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val u = JSONObject(resp.body?.string() ?: return@use null).optString("url")
                    if (u.startsWith("http") && verifyMediaStreamDeep(u, ua)) ResolvedStreams(u, userAgent = ua, codecLabel = "MP3 256kbps") else null
                }
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun fetchPipedStream(videoId: String, quality: AudioQuality): ResolvedStreams? {
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        for (base in pipedInstances) {
            val res = runCatching {
                fastProxyClient.newCall(Request.Builder().url("$base/streams/$videoId").header("User-Agent", ua).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val json = JSONObject(resp.body?.string() ?: return@use null)
                    val audioStreams = mutableListOf<Pair<Int, String>>()
                    json.optJSONArray("audioStreams")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val a = arr.optJSONObject(i) ?: continue
                            val u = a.optString("url")
                            if (u.startsWith("http")) audioStreams.add((a.optInt("bitrate", 128000) / 1000) to u)
                        }
                    }
                    val hls = json.optString("hls").takeIf { it.startsWith("http") }
                    val validAudio = listOfNotNull(pickAudioByQuality(audioStreams, quality), hls).firstOrNull { verifyMediaStreamDeep(it, ua) } ?: return@use null
                    var muxedVideo: String? = hls; var videoOnly: String? = null
                    json.optJSONArray("videoStreams")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val v = arr.optJSONObject(i) ?: continue
                            val u = v.optString("url")
                            if (!u.startsWith("http")) continue
                            if (!v.optBoolean("videoOnly", true)) { muxedVideo = u; break } else if (videoOnly == null) videoOnly = u
                        }
                    }
                    ResolvedStreams(validAudio, muxedVideo, videoOnly, ua, codecLabel = "Piped 256kbps")
                }
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun fetchInvidiousProxiedStream(videoId: String): ResolvedStreams? {
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        for (base in invidiousInstances) {
            val audioProxy = "$base/latest_version?id=$videoId&itag=140&local=true"
            val videoProxy = "$base/latest_version?id=$videoId&itag=18&local=true"
            if (verifyMediaStreamDeep(audioProxy, ua)) return ResolvedStreams(audioProxy, videoProxy, userAgent = ua, codecLabel = "Proxy AAC")
            if (verifyMediaStreamDeep(videoProxy, ua)) return ResolvedStreams(videoProxy, videoProxy, userAgent = ua, codecLabel = "Proxy MP4")
        }
        return null
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    private fun pickAudioByQuality(list: List<Pair<Int, String>>, quality: AudioQuality): String? {
        if (list.isEmpty()) return null
        val sorted = list.sortedBy { it.first }
        return when (quality) {
            AudioQuality.LOW -> sorted.first().second
            AudioQuality.NORMAL -> sorted.minByOrNull { abs(it.first - 96) }?.second
            AudioQuality.HIGH -> sorted.minByOrNull { abs(it.first - 160) }?.second
            AudioQuality.VERY_HIGH, AudioQuality.LOSSLESS -> sorted.last().second
        }
    }

    // ---------- 4. Turbo Parallel Offline Downloader (8x-10x Parallel OkHttp + Accelerated yt-dlp!) ----------
    private suspend fun downloadViaYtDlp(
        videoId: String,
        quality: AudioQuality,
        parallelCount: Int,
        target: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        if (!ensureYtDlpInitOnly()) return false
        onProgress(10)

        val qualityFormat = when (quality) {
            AudioQuality.LOW -> "worstaudio[ext=m4a]/worstaudio[ext=webm]/worstaudio/18"
            AudioQuality.NORMAL -> "bestaudio[abr<=100][ext=m4a]/bestaudio[abr<=100]/bestaudio[ext=m4a]/18"
            AudioQuality.HIGH -> "bestaudio[abr<=160][ext=m4a]/bestaudio[abr<=160]/bestaudio[ext=m4a]/18"
            AudioQuality.VERY_HIGH, AudioQuality.LOSSLESS -> "bestaudio[ext=m4a]/bestaudio[ext=webm]/bestaudio/18"
        }

        val attempts = listOf(
            qualityFormat to "youtube:player_client=android_vr,ios,tv_downgraded",
            "bestaudio/18" to "youtube:player_client=tv_downgraded,mweb,android_vr",
            "18/best[ext=mp4]/best" to null
        )

        val tmpPrefix = "${target.nameWithoutExtension}_ytdlp"
        val tmpFile = File(target.parentFile, "$tmpPrefix.tmp")

        for ((fmt, extractorArgs) in attempts) {
            target.parentFile?.listFiles()?.filter { it.name.startsWith(tmpPrefix) }?.forEach { it.delete() }

            val request = YoutubeDLRequest("https://www.youtube.com/watch?v=$videoId").apply {
                addOption("--no-playlist")
                addOption("--no-part")
                addOption("--no-mtime")
                addOption("--force-overwrites")
                addOption("--no-cache-dir")
                addOption("--retries", "2")
                addOption("--socket-timeout", "10")
                addOption("--http-chunk-size", "524288")
                addOption("--concurrent-fragments", parallelCount.coerceIn(4, 10).toString())
                addOption("-f", fmt)
                if (!extractorArgs.isNullOrBlank()) {
                    addOption("--extractor-args", extractorArgs)
                }
                addOption("-o", tmpFile.absolutePath)
            }

            val ok = runCatching {
                YoutubeDL.getInstance().execute(request, "sonify_dl_$videoId") { progress: Float, _: Long, _: String ->
                    if (progress >= 0f) {
                        val mapped = (12 + (progress * 0.86f).toInt()).coerceIn(12, 98)
                        onProgress(mapped)
                    }
                }
                val produced = if (tmpFile.exists() && tmpFile.length() > 16_384) {
                    tmpFile
                } else {
                    target.parentFile?.listFiles()?.firstOrNull { it.name.startsWith(tmpPrefix) && it.length() > 16_384 }
                }
                if (produced != null) {
                    if (target.exists()) target.delete()
                    produced.renameTo(target)
                    onProgress(100)
                    true
                } else false
            }.getOrDefault(false)

            if (ok) return true
        }

        target.parentFile?.listFiles()?.filter { it.name.startsWith(tmpPrefix) }?.forEach { it.delete() }
        return false
    }

    suspend fun downloadTrackOffline(
        track: Track,
        settings: AppSettings,
        capturedStreams: List<CapturedMediaStream> = emptyList(),
        webViewDownloadFn: (suspend (File, (Int) -> Unit) -> Boolean)? = null,
        onProgress: (Int) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (settings.offlineMode) return@withContext false to "מצב אופליין פעיל — כבה אותו כדי להוריד"
        if (!settings.downloadUsingCellular && !isOnWifi()) {
            return@withContext false to "הורדה דרך סלולר חסומה בהגדרות"
        }

        val target = getOfflineFile(track.id)
        val parallel = settings.downloadSpeedMode.parallelChunks

        val result = withTimeoutOrNull(85_000L) {
            onProgress(3)

            if (track.source != StreamSource.YOUTUBE) {
                val res = resolveStreams(track, settings, forDownload = true)
                if (res != null && downloadSmart(res.audioUrl, res.userAgent, res.cookie, emptyMap(), target, false, parallel, onProgress)) {
                    return@withTimeoutOrNull true to "השיר ירד להאזנה אופליין!"
                }
                return@withTimeoutOrNull false to "שגיאה בהורדה מהמקור שנבחר"
            }

            val quality = effectiveQuality(settings, forDownload = true)

            // Priority 1: Turbo 8x-10x Parallel OkHttp via ANDROID_VR direct stream (~1-2 seconds!)
            val fastDirect = runCatching { fetchInnerTubeClient(track.id, "ANDROID_VR", quality, preferHls = false) }.getOrNull()
            if (fastDirect != null && downloadSmart(fastDirect.audioUrl, fastDirect.userAgent, fastDirect.cookie, emptyMap(), target, false, parallel, onProgress)) {
                return@withTimeoutOrNull true to "השיר ירד להאזנה אופליין!"
            }

            // Priority 2: Turbo Parallel OkHttp on captured stream
            for (cap in capturedStreams.sortedByDescending { it.isAudioOnly }.take(2)) {
                val cleanBase = sanitizeYouTubePlaybackUrl(cap.url)
                if (downloadSmart(cleanBase, cap.userAgent, cap.cookie, cap.headers, target, true, parallel, onProgress)) {
                    return@withTimeoutOrNull true to "השיר ירד להאזנה אופליין!"
                }
            }

            // Priority 3: Accelerated prewarmed yt-dlp engine
            if (downloadViaYtDlp(track.id, quality, parallel, target, onProgress)) {
                return@withTimeoutOrNull true to "השיר ירד להאזנה אופליין!"
            }

            // Priority 4: Direct WebView JS fetch fallback
            if (webViewDownloadFn != null && capturedStreams.isNotEmpty()) {
                val wvOk = runCatching { webViewDownloadFn(target, onProgress) }.getOrDefault(false)
                if (wvOk) return@withTimeoutOrNull true to "השיר ירד להאזנה אופליין!"
            }

            // Priority 5: Fast IOS HLS / Cobalt / Piped / Invidious fallback
            for (strategy in youtubeStrategies(track.id, quality).drop(1)) {
                val resolved = runCatching { strategy() }.getOrNull() ?: continue
                if (downloadSmart(resolved.audioUrl, resolved.userAgent, resolved.cookie, emptyMap(), target, false, parallel, onProgress)) {
                    return@withTimeoutOrNull true to "השיר ירד להאזנה אופליין!"
                }
            }

            false to "לא ניתן להוריד שיר זה כרגע — נסה שוב בעוד רגע"
        }

        result ?: (false to "ההורדה לקחה יותר מדי זמן — נסה שוב")
    }

    private fun sanitizeYouTubePlaybackUrl(url: String): String {
        val sparamsRaw = Regex("[?&]sparams=([^&]+)").find(url)?.groupValues?.get(1).orEmpty()
        val signedParams = runCatching { URLDecoder.decode(sparamsRaw, "UTF-8") }
            .getOrDefault(sparamsRaw)
            .split(",")
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .toSet()

        val removable = listOf("range", "rn", "rbuf", "alr", "ump", "srfvp", "sabr")
            .filterNot { it in signedParams }
        if (removable.isEmpty()) return url

        val pattern = Regex("([?&])(${removable.joinToString("|")})=[^&]*", RegexOption.IGNORE_CASE)
        return url
            .replace(pattern, "$1")
            .replace("?&", "?")
            .replace(Regex("&{2,}"), "&")
            .trimEnd('&', '?')
    }

    private suspend fun downloadSmart(
        url: String, userAgent: String, cookie: String?, extraHeaders: Map<String, String>,
        target: File, isWebOrigin: Boolean, parallel: Int, onProgress: (Int) -> Unit
    ): Boolean {
        if (url.contains(".m3u8", true) || url.contains("manifest/hls", true)) {
            return downloadHlsAudioParallel(url, userAgent, cookie, target, parallel, onProgress)
        }
        val clean = sanitizeYouTubePlaybackUrl(url)
        if (clean.contains("googlevideo.com") && clean.contains("clen=")) {
            if (downloadChunkedParallel(clean, userAgent, cookie, extraHeaders, target, isWebOrigin, parallel, onProgress)) return true
        }
        return downloadUrlToFile(clean, userAgent, cookie, extraHeaders, target, isWebOrigin, onProgress)
    }

    private fun buildMediaRequest(url: String, userAgent: String, cookie: String?, extraHeaders: Map<String, String>, isWebOrigin: Boolean): Request {
        val rb = Request.Builder().url(url).header("User-Agent", userAgent)
        extraHeaders.forEach { (k, v) ->
            if (!k.equals("Range", true) && !k.equals("Host", true) && !k.equals("Content-Length", true)) {
                rb.header(k, v)
            }
        }
        if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
        if (isWebOrigin && url.contains("googlevideo.com")) {
            rb.header("Origin", "https://m.youtube.com")
            rb.header("Referer", "https://m.youtube.com/")
        }
        return rb.build()
    }

    private suspend fun downloadHlsAudioParallel(
        manifestUrl: String, userAgent: String, cookie: String?, target: File, parallel: Int, onProgress: (Int) -> Unit
    ): Boolean {
        return runCatching {
            fun fetchText(u: String): String? {
                val req = buildMediaRequest(u, userAgent, cookie, emptyMap(), false)
                return downloadClient.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }
            var playlistText = fetchText(manifestUrl) ?: return false
            if (playlistText.contains("#EXT-X-STREAM-INF") || playlistText.contains("#EXT-X-MEDIA")) {
                val audioUri = Regex("TYPE=AUDIO.*?URI=\"(https?://[^\"]+)\"").findAll(playlistText).lastOrNull()?.groupValues?.get(1)
                val nextUrl = audioUri ?: playlistText.lines().map { it.trim() }.firstOrNull { it.startsWith("http") } ?: return false
                playlistText = fetchText(nextUrl) ?: return false
            }
            val segments = playlistText.lines().map { it.trim() }.filter { it.startsWith("http") }
            if (segments.isEmpty()) return false

            val tmp = File(target.parentFile, "${target.name}.tmp")
            var completed = 0
            FileOutputStream(tmp).use { out ->
                for (batch in segments.chunked(parallel.coerceIn(4, 10))) {
                    val fetched = coroutineScope {
                        batch.map { segUrl ->
                            async(Dispatchers.IO) {
                                runCatching {
                                    downloadClient.newCall(buildMediaRequest(segUrl, userAgent, cookie, emptyMap(), false)).execute().use { r ->
                                        if (r.isSuccessful) r.body?.bytes() else null
                                    }
                                }.getOrNull()
                            }
                        }.awaitAll()
                    }
                    for (bytes in fetched) {
                        if (bytes == null || bytes.isEmpty()) { tmp.delete(); return false }
                        out.write(bytes)
                        completed++
                    }
                    onProgress((completed * 96 / segments.size).coerceIn(5, 98))
                }
            }
            if (tmp.exists() && tmp.length() > 16_384) {
                if (target.exists()) target.delete()
                tmp.renameTo(target)
                onProgress(100)
                true
            } else { tmp.delete(); false }
        }.getOrDefault(false)
    }

    private fun downloadUrlToFile(url: String, userAgent: String, cookie: String?, extraHeaders: Map<String, String>, target: File, isWebOrigin: Boolean, onProgress: (Int) -> Unit): Boolean {
        return runCatching {
            val req = buildMediaRequest(url, userAgent, cookie, extraHeaders, isWebOrigin)
            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("text/html") || ct.contains("application/json") || ct.contains("yt-ump")) return false
                val total = resp.body?.contentLength() ?: -1L
                val tmp = File(target.parentFile, "${target.name}.tmp")
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            copied += n
                            if (total > 0) onProgress(((copied * 98) / total).toInt().coerceIn(5, 98))
                        }
                    }
                }
                if (tmp.exists() && tmp.length() > 16_384) {
                    if (target.exists()) target.delete()
                    tmp.renameTo(target)
                    onProgress(100)
                    true
                } else { tmp.delete(); false }
            }
        }.getOrDefault(false)
    }

    // Turbo 8x-10x parallel range downloader with adaptive chunk size to saturate full mobile bandwidth
    private suspend fun downloadChunkedParallel(
        baseUrl: String, userAgent: String, cookie: String?, extraHeaders: Map<String, String>,
        target: File, isWebOrigin: Boolean, parallel: Int, onProgress: (Int) -> Unit
    ): Boolean {
        val clen = Regex("[?&]clen=(\\d+)").find(baseUrl)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        if (clen <= 0L || clen > 85_000_000L) return false

        return runCatching {
            val tmp = File(target.parentFile, "${target.name}.tmp")
            var activeBase = baseUrl
            val workers = parallel.coerceIn(4, 10)
            // Dynamic chunk sizing so even a 3MB song splits across all parallel workers simultaneously!
            val chunkSize = (clen / workers).coerceIn(384 * 1024L, 1024 * 1024L)

            fun fetchRange(urlBase: String, start: Long, end: Long): Pair<ByteArray?, String?> {
                val sep = if (urlBase.contains("?")) "&" else "?"
                val req = buildMediaRequest("$urlBase${sep}range=$start-$end", userAgent, cookie, extraHeaders, isWebOrigin)
                return runCatching {
                    downloadClient.newCall(req).execute().use { resp ->
                        if (!resp.isSuccessful) return@use null to null
                        val ct = resp.header("Content-Type").orEmpty().lowercase()
                        if (ct.contains("yt-ump") || ct.contains("text/html")) return@use null to null
                        val bytes = resp.body?.bytes() ?: return@use null to null
                        if (ct.contains("text/plain") && bytes.size < 2048) {
                            val redir = String(bytes).trim()
                            if (redir.startsWith("https://")) return@use null to sanitizeYouTubePlaybackUrl(redir)
                            return@use null to null
                        }
                        bytes to null
                    }
                }.getOrDefault(null to null)
            }

            val ranges = mutableListOf<Pair<Long, Long>>()
            var cursor = 0L
            while (cursor < clen) {
                val end = (cursor + chunkSize - 1).coerceAtMost(clen - 1)
                ranges.add(cursor to end)
                cursor = end + 1
            }

            var firstBytes: ByteArray? = null
            for (r in 0..1) {
                val (b, redir) = fetchRange(activeBase, ranges[0].first, ranges[0].second)
                if (redir != null) { activeBase = redir; continue }
                if (b != null && b.isNotEmpty()) { firstBytes = b; break }
            }
            if (firstBytes == null) return false

            var written = firstBytes.size.toLong()
            onProgress(((written * 98) / clen).toInt().coerceIn(5, 98))

            FileOutputStream(tmp).use { out ->
                out.write(firstBytes)
                val remainingRanges = ranges.drop(1)
                for (batch in remainingRanges.chunked(workers)) {
                    val results = coroutineScope {
                        batch.map { (start, end) ->
                            async(Dispatchers.IO) {
                                var got: ByteArray? = null
                                var localUrl = activeBase
                                for (retry in 0..2) {
                                    val (b, redir) = fetchRange(localUrl, start, end)
                                    if (redir != null) { localUrl = redir; continue }
                                    if (b != null && b.isNotEmpty()) { got = b; break }
                                }
                                got
                            }
                        }.awaitAll()
                    }
                    for (chunk in results) {
                        if (chunk == null) { tmp.delete(); return false }
                        out.write(chunk)
                        written += chunk.size
                    }
                    onProgress(((written * 98) / clen).toInt().coerceIn(5, 98))
                }
            }

            if (tmp.exists() && tmp.length() > 16_384) {
                if (target.exists()) target.delete()
                tmp.renameTo(target)
                onProgress(100)
                true
            } else { tmp.delete(); false }
        }.getOrDefault(false)
    }

    // ---------- 5. Smarter AI Engine: Infinite Taste-Aware Home Feed & Smart Playlist Studio ----------
    suspend fun buildSmartAiHomeBatch(
        pageIndex: Int,
        likedTracks: List<Track>,
        recentTracks: List<Track>,
        stats: ListeningStats,
        excludedArtists: Set<String>,
        homeFilter: HomeFilter,
        existingTitles: Set<String>,
        settings: AppSettings
    ): HomeFeedSection? = withContext(Dispatchers.IO) {
        if (settings.offlineMode) return@withContext null
        val isEn = settings.language == AppLanguage.ENGLISH
        val isPod = homeFilter == HomeFilter.PODCASTS
        val isBook = homeFilter == HomeFilter.AUDIOBOOKS

        // Build Taste Profile from Liked Tracks + Most Played Artists
        val tasteArtists = (
            likedTracks.map { cleanArtistName(it.artist) } +
            stats.artistPlayCounts.entries.sortedByDescending { it.value }.map { cleanArtistName(it.key) } +
            recentTracks.map { cleanArtistName(it.artist) }
        ).filter { a -> a.length >= 2 && excludedArtists.none { ex -> a.contains(ex, true) } }
            .distinct()

        // Every 3rd infinite page -> Ready-made YouTube Playlists curated by taste!
        if (!isPod && !isBook && settings.showYoutubePlaylistsHome && pageIndex % 3 == 2) {
            val seedArtist = tasteArtists.getOrNull((pageIndex / 3) % tasteArtists.size.coerceAtLeast(1))
            val ytQueries = if (seedArtist != null) {
                listOf("$seedArtist mix playlist", "$seedArtist similar artists playlist")
            } else {
                listOf("top hits 2026 playlist", "best workout energy playlist", "chill vibes playlist")
            }
            val remotePls = fetchReadyYoutubePlaylists(ytQueries, maxTotal = 10)
            if (remotePls.isNotEmpty()) {
                val secTitle = if (seedArtist != null) {
                    if (isEn) "YouTube Playlists • Inspired by $seedArtist" else "פלייליסטים מיוטיוב • בהשראת $seedArtist"
                } else {
                    if (isEn) "Ready YouTube Playlists" else "פלייליסטים מוכנים מיוטיוב"
                }
                return@withContext HomeFeedSection(
                    id = "inf_yt_$pageIndex",
                    title = secTitle,
                    subtitle = if (isEn) "Curated directly from YouTube Music" else "מוזרם ישירות מ-YouTube Music",
                    remotePlaylists = remotePls
                )
            }
        }

        // Try Gemini API for ultra-smart taste-aware section concepts when key is available
        val geminiConcepts = if (!isPod && !isBook && settings.geminiApiKey.isNotBlank()) {
            runCatching { requestGeminiInfiniteBatch(tasteArtists.take(8), likedTracks.take(10), existingTitles, isEn, settings.geminiApiKey) }.getOrNull()
        } else null

        val (sectionTitle, sectionSub, cardsPlan) = geminiConcepts ?: buildLocalInfinitePlan(pageIndex, tasteArtists, homeFilter, isEn)

        val playlists = coroutineScope {
            cardsPlan.mapIndexed { idx, (title, query, styleTag) ->
                async {
                    val found = runCatching { searchTracks(query, settings, isPod, isBook, maxResults = 18) }
                        .getOrDefault(emptyList())
                        .filterNot { t -> excludedArtists.any { ex -> t.artist.contains(ex, true) } }
                    if (found.isEmpty()) null
                    else Playlist(
                        id = "ai_inf_${pageIndex}_$idx",
                        name = title,
                        creator = "Sonify AI",
                        description = query,
                        coverUrl = found.first().artworkUrl,
                        tracks = found,
                        isRemote = true,
                        aiSeedPrompt = query,
                        aiStyleTag = styleTag
                    )
                }
            }.awaitAll().filterNotNull()
        }

        val bonusTracks = if (playlists.isNotEmpty()) {
            playlists.flatMap { it.tracks.take(4) }.distinctBy { it.id }.shuffled().take(12)
        } else emptyList()

        if (playlists.isEmpty() && bonusTracks.isEmpty()) return@withContext null

        HomeFeedSection(
            id = "ai_sec_$pageIndex",
            title = sectionTitle,
            subtitle = sectionSub,
            playlists = playlists,
            tracks = if (pageIndex % 2 == 1) bonusTracks else emptyList()
        )
    }

    private fun buildLocalInfinitePlan(
        pageIndex: Int,
        tasteArtists: List<String>,
        homeFilter: HomeFilter,
        isEn: Boolean
    ): Triple<String, String, List<Triple<String, String, String>>> {
        if (homeFilter == HomeFilter.PODCASTS) {
            val podThemes = listOf(
                Triple(if (isEn) "Deep Dive Podcasts" else "פודקאסטים להעמקה", if (isEn) "AI Picks" else "בחירת AI", listOf(
                    Triple(if (isEn) "True Crime" else "פשע אמיתי וחקירות", "true crime podcast", "PODCAST"),
                    Triple(if (isEn) "AI & Future Tech" else "בינה מלאכותית ועתיד", "AI technology podcast", "TECH"),
                    Triple(if (isEn) "Psychology & Mind" else "פסיכולוגיה ומוח", "psychology mind podcast", "FOCUS")
                )),
                Triple(if (isEn) "Stories & Culture" else "סיפורים ותרבות", if (isEn) "Endless Feed" else "פיד אינסופי", listOf(
                    Triple(if (isEn) "World History" else "היסטוריה עולמית", "history stories podcast", "PODCAST"),
                    Triple(if (isEn) "Sports & Fitness" else "ספורט וכושר", "fitness sports podcast", "ENERGY"),
                    Triple(if (isEn) "Israeli Talks" else "שיחות וראיונות בישראל", "פודקאסט ראיונות ישראלי", "IL MIX")
                ))
            )
            return podThemes[pageIndex % podThemes.size]
        }

        if (homeFilter == HomeFilter.AUDIOBOOKS) {
            val bookThemes = listOf(
                Triple(if (isEn) "More Audiobooks For You" else "עוד ספרי שמע בשבילך", "AI Picks", listOf(
                    Triple(if (isEn) "Productivity & Habits" else "הרגלים והצלחה", "atomic habits productivity audiobook", "AUDIOBOOK"),
                    Triple(if (isEn) "Classic Novels" else "רומנים קלאסיים", "classic novel full audiobook", "AUDIOBOOK"),
                    Triple(if (isEn) "Space & Sci-Fi" else "חלל ומדע בדיוני", "sci fi space full audiobook", "AUDIOBOOK")
                ))
            )
            return bookThemes[pageIndex % bookThemes.size]
        }

        // Personalized artist-based rotation when user has liked/played tracks!
        if (tasteArtists.isNotEmpty() && pageIndex % 2 == 0) {
            val anchor = tasteArtists[(pageIndex / 2) % tasteArtists.size]
            val second = tasteArtists[((pageIndex / 2) + 1) % tasteArtists.size]
            val title = if (isEn) "Because you like $anchor" else "כי אהבת את $anchor"
            val sub = if (isEn) "AI Curated for your taste" else "הותאם אישית על ידי ה-AI"
            val cards = listOf(
                Triple(if (isEn) "$anchor Radio" else "רדיו $anchor", "$anchor similar artists best songs", "RADIO"),
                Triple(if (isEn) "This Is $anchor" else "המיטב של $anchor", "$anchor top hits essential", "ARTIST"),
                Triple(if (isEn) "$anchor & $second Mix" else "מיקס $anchor ו-$second", "$anchor $second hits", "AI MIX"),
                Triple(if (isEn) "$anchor Late Night" else "$anchor ללילה", "$anchor chill acoustic slow", "CHILL")
            )
            return Triple(title, sub, cards)
        }

        // Rich diverse mood & genre rotation for infinite discovery
        val discoveryPool = listOf(
            Triple(if (isEn) "High Energy & Focus" else "אנרגיה, אימון וריכוז", "Sonify AI", listOf(
                Triple(if (isEn) "Beast Mode Gym" else "אימון כוח מקסימלי", "gym workout phonk rap energy", "ENERGY"),
                Triple(if (isEn) "Coding & Focus Flow" else "פוקוס, קוד ולימודים", "synthwave lofi coding focus", "FOCUS"),
                Triple(if (isEn) "Night Drive Bass" else "נסיעת לילה ובאס", "night drive electronic bass", "DRIVE")
            )),
            Triple(if (isEn) "Hidden Gems & Moods" else "גילויים חדשים ומצבי רוח", "Sonify AI", listOf(
                Triple(if (isEn) "Anime & Gaming OST" else "אנימה וגיימינג", "Arcane Cyberpunk anime soundtrack", "OST"),
                Triple(if (isEn) "Israeli Chill Sunset" else "שקיעה ישראלית רגועה", "עידן רייכל נתן גושן אקוסטי", "IL MIX"),
                Triple(if (isEn) "Modern Rock Anthems" else "המנוני רוק מודרני", "Imagine Dragons Linkin Park Arctic Monkeys", "ROCK")
            )),
            Triple(if (isEn) "Throwbacks & Global Beats" else "קלאסיקות ולהיטי עולם", "Sonify AI", listOf(
                Triple(if (isEn) "2010s Golden Hits" else "להיטי הזהב של שנות ה-2010", "2010s pop rap biggest hits", "HITS"),
                Triple(if (isEn) "Hip-Hop Heavyweights" else "היפ הופ וראפ כבד", "Kendrick Lamar Travis Scott Drake", "HIP-HOP"),
                Triple(if (isEn) "Midnight R&B" else "R&B לחצות הלילה", "The Weeknd Frank Ocean SZA", "FEELS")
            ))
        )
        return discoveryPool[pageIndex % discoveryPool.size]
    }

    private fun requestGeminiInfiniteBatch(
        topArtists: List<String>,
        likedTracks: List<Track>,
        existingTitles: Set<String>,
        isEn: Boolean,
        apiKey: String
    ): Triple<String, String, List<Triple<String, String, String>>>? {
        val tasteSummary = likedTracks.take(8).joinToString(", ") { "${it.artist} - ${it.title}" }
            .ifBlank { topArtists.joinToString(", ") }
        val langHint = if (isEn) "English" else "Hebrew"
        val prompt = """
            You are Sonify's music curator AI. User's liked songs/artists: [$tasteSummary].
            Avoid repeating titles: [${existingTitles.take(15).joinToString(", ")}].
            Create ONE fresh home feed shelf in $langHint with 3 distinct playlist cards.
            Return ONLY JSON:
            {"sectionTitle": "...", "subtitle": "...", "cards": [{"title": "...", "searchQuery": "artist/genre query for youtube music", "styleTag": "SHORT_EN_TAG"}]}
        """.trimIndent()

        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
            put("generationConfig", JSONObject().apply { put("responseMimeType", "application/json") })
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = JSONObject(resp.body?.string() ?: return null)
                .optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: return null
            val parsed = JSONObject("{${text.substringAfter("{").substringBeforeLast("}")}}")
            val secTitle = parsed.optString("sectionTitle").takeIf { it.isNotBlank() } ?: return null
            val secSub = parsed.optString("subtitle", "Gemini AI")
            val cardsArr = parsed.optJSONArray("cards") ?: return null
            val cards = buildList {
                for (i in 0 until cardsArr.length().coerceAtMost(4)) {
                    val c = cardsArr.optJSONObject(i) ?: continue
                    val t = c.optString("title")
                    val q = c.optString("searchQuery")
                    val tag = c.optString("styleTag", "AI MIX").take(10).uppercase()
                    if (t.isNotBlank() && q.isNotBlank()) add(Triple(t, q, tag))
                }
            }
            if (cards.isEmpty()) null else Triple(secTitle, secSub, cards)
        }
    }

    suspend fun buildAiPlaylist(
        config: AiPlaylistConfig,
        settings: AppSettings,
        likedContext: List<Track> = emptyList()
    ): Pair<String, List<Track>> = withContext(Dispatchers.IO) {
        val targetCount = config.targetCount.coerceIn(10, 50)

        if (settings.geminiApiKey.isNotBlank()) {
            val geminiPlan = runCatching { requestGeminiSongList(config, likedContext, settings.geminiApiKey) }.getOrNull()
            if (geminiPlan != null && geminiPlan.second.isNotEmpty()) {
                val curated = mutableListOf<Track>()
                // Search Gemini tracks in parallel batches of 4 for 4x faster AI playlist generation!
                for (batch in geminiPlan.second.take(targetCount).chunked(4)) {
                    val foundBatch = coroutineScope {
                        batch.map { q -> async { searchTracks(q, settings, maxResults = 3).firstOrNull() } }.awaitAll()
                    }
                    foundBatch.filterNotNull().forEach { cand ->
                        if (curated.none { it.id == cand.id }) curated.add(cand)
                    }
                }
                if (curated.size >= 5) return@withContext geminiPlan.first to curated
            }
        }

        val (playlistTitle, queries) = expandConfigIntoSmartQueries(config, likedContext)
        val curated = mutableListOf<Track>()
        val seenTitles = mutableSetOf<String>()
        for (q in queries) {
            val results = searchTracks(q, settings, maxResults = 15)
            for (t in results) {
                val normTitle = t.title.lowercase().replace(Regex("[^a-z0-9א-ת]"), "").take(18)
                if (curated.none { it.id == t.id } && seenTitles.add(normTitle)) {
                    curated.add(t)
                    if (curated.size >= targetCount) break
                }
            }
            if (curated.size >= targetCount) break
        }
        playlistTitle to curated.shuffled()
    }

    private fun expandConfigIntoSmartQueries(config: AiPlaylistConfig, likedContext: List<Track>): Pair<String, List<String>> {
        val prompt = config.prompt.trim()
        val lower = prompt.lowercase()
        val isHeb = config.languagePref == "עברית" || (config.languagePref == "משולב" && prompt.any { it in '\u0590'..'\u05FF' })
        val isEng = config.languagePref == "אנגלית" || config.languagePref == "משולב"
        val eraSuffix = when (config.eraPref) {
            "חדש (2024+)" -> " 2025 2026"
            "2010s" -> " 2010s hits"
            "קלאסיקות" -> " classic hits"
            else -> ""
        }
        val energySuffix = when (config.energyPref) {
            "אנרגטי" -> " upbeat energy"
            "רגוע" -> " chill acoustic"
            else -> ""
        }

        val queries = mutableListOf<String>()
        when {
            lower.contains("אימון") || lower.contains("כושר") || lower.contains("ריצה") || lower.contains("workout") || lower.contains("gym") -> {
                if (isHeb) queries += listOf("נועה קירל", "טונה", "סטטיק", "אודיה", "רביד פלוטניק", "עומר אדם קצבי")
                if (isEng) queries += listOf("Eminem workout", "Kanye West Stronger", "Travis Scott FEIN", "The Weeknd Blinding Lights", "Imagine Dragons Believer", "David Guetta workout", "Linkin Park workout")
            }
            lower.contains("לילה") || lower.contains("רגוע") || lower.contains("שינה") || lower.contains("צ'יל") || lower.contains("chill") -> {
                if (isHeb) queries += listOf("עידן רייכל", "ישי ריבו", "נתן גושן", "אביתר בנאי", "יסמין מועלם")
                if (isEng) queries += listOf("Billie Eilish chill", "Frank Ocean", "The Weeknd slow", "Lana Del Rey", "Cigarettes After Sex", "Radiohead calm")
            }
            lower.contains("מסיבה") || lower.contains("קלאב") || lower.contains("ריקוד") || lower.contains("party") || lower.contains("dance") -> {
                if (isHeb) queries += listOf("עומר אדם", "עדן בן זקן", "אנה זק", "אושר כהן", "פאר טסי")
                if (isEng) queries += listOf("Dua Lipa dance", "Calvin Harris hits", "David Guetta hits", "Pitbull party", "Rihanna club", "Lady Gaga dance")
            }
            lower.contains("עצוב") || lower.contains("שבור") || lower.contains("בלד") || lower.contains("sad") -> {
                if (isHeb) queries += listOf("אביב גפן", "חנן בן ארי", "עדן חסון", "אייל גולן בלדות", "שלמה ארצי")
                if (isEng) queries += listOf("Adele ballads", "Lewis Capaldi", "Olivia Rodrigo sad", "Coldplay Fix You", "Sam Smith sad")
            }
            lower.contains("רוק") || lower.contains("rock") || lower.contains("מטאל") -> {
                if (isHeb) queries += listOf("היהודים", "משינה", "מרסדס בנד", "מוניקה סקס", "ברי סחרוף")
                if (isEng) queries += listOf("Linkin Park", "Arctic Monkeys", "Queen greatest hits", "Nirvana", "Red Hot Chili Peppers", "Green Day", "Radiohead")
            }
            lower.contains("ראפ") || lower.contains("היפ") || lower.contains("rap") || lower.contains("hip hop") -> {
                if (isHeb) queries += listOf("רביד פלוטניק", "טונה", "ג'ימבו ג'יי", "שחר סאול", "נורוז", "פלד")
                if (isEng) queries += listOf("Kendrick Lamar", "Drake hits", "Travis Scott", "21 Savage", "Metro Boomin", "Future rap", "Eminem")
            }
            lower.contains("מזרחית") || lower.contains("ים תיכוני") -> {
                queries += listOf("עומר אדם", "עדן חסון", "פאר טסי", "אושר כהן", "אייל גולן", "איתי לוי", "שרית חדד", "משה פרץ")
            }
            lower.contains("אנימה") || lower.contains("anime") || lower.contains("גיימינג") || lower.contains("gaming") -> {
                queries += listOf("Arcane soundtrack", "YOASOBI anime", "LiSA anime", "Cyberpunk Edgerunners soundtrack", "Imagine Dragons Enemy", "phonk gaming")
            }
            else -> {
                queries += listOf("$prompt$eraSuffix$energySuffix", "$prompt best songs", "$prompt top hits", "$prompt similar artists", "$prompt essentials")
            }
        }
        // Blend in 1-2 user favorite artists matching the mood
        likedContext.map { cleanArtistName(it.artist) }.distinct().take(2).forEach { fav ->
            queries.add("$fav$energySuffix")
        }
        return "AI • ${prompt.take(26)}" to queries.distinct()
    }

    private fun requestGeminiSongList(config: AiPlaylistConfig, likedContext: List<Track>, apiKey: String): Pair<String, List<String>>? {
        val favs = likedContext.take(8).joinToString(", ") { "${it.artist} - ${it.title}" }
        val sysPrompt = """
            Build a ${config.targetCount}-song playlist for: "${config.prompt}".
            User's known favorite songs for taste calibration: [$favs].
            Preferences: language=${config.languagePref}, energy=${config.energyPref}, era=${config.eraPref}.
            Reply ONLY with valid JSON: {"title": "Short catchy title", "songs": ["Artist - Song", ...]}
        """.trimIndent()
        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysPrompt)))))
            put("generationConfig", JSONObject().apply { put("responseMimeType", "application/json") })
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = JSONObject(resp.body?.string() ?: return null)
                .optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: return null
            val parsed = JSONObject("{${text.substringAfter("{").substringBeforeLast("}")}}")
            val title = parsed.optString("title", "AI • ${config.prompt.take(24)}")
            val songsArr = parsed.optJSONArray("songs") ?: return null
            title to buildList { for (i in 0 until songsArr.length()) songsArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
        }
    }

    // ---------- 6. 4-Layer Synced Lyrics Engine + Gemini API Lyrics Translation ----------
    suspend fun translateLyricsWithGemini(
        lines: List<LyricLine>,
        targetLanguage: LyricsLanguage,
        apiKey: String
    ): Pair<List<LyricLine>?, String?> = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) return@withContext null to "NO_API_KEY"
        if (lines.isEmpty()) return@withContext null to "EMPTY"

        runCatching {
            val result = lines.toMutableList()
            val batchSize = 55
            var offset = 0
            while (offset < lines.size) {
                val end = (offset + batchSize).coerceAtMost(lines.size)
                val slice = lines.subList(offset, end)
                val inputArr = JSONArray().apply { slice.forEach { put(it.text) } }
                val prompt = """
                    Translate each song lyric line in this JSON array naturally into ${targetLanguage.apiName}.
                    Preserve meaning and poetic flow. Return ONLY a JSON array of strings with the EXACT SAME LENGTH (${slice.size}).
                    Input: $inputArr
                """.trimIndent()

                val payload = JSONObject().apply {
                    put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", prompt)))))
                    put("generationConfig", JSONObject().apply { put("responseMimeType", "application/json") })
                }
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
                    .post(payload.toString().toRequestBody("application/json".toMediaType()))
                    .build()

                val translatedBatch = client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null to "HTTP_${resp.code}"
                    val raw = JSONObject(resp.body?.string() ?: return@withContext null to "EMPTY_BODY")
                        .optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")
                        ?.optJSONArray("parts")?.optJSONObject(0)?.optString("text") ?: return@withContext null to "PARSE_ERR"
                    val arrStr = "[" + raw.substringAfter("[").substringBeforeLast("]") + "]"
                    val arr = JSONArray(arrStr)
                    List(arr.length()) { idx -> arr.optString(idx) }
                }

                for (i in slice.indices) {
                    val tr = translatedBatch.getOrNull(i)?.takeIf { it.isNotBlank() }
                    result[offset + i] = result[offset + i].copy(translatedText = tr)
                }
                offset = end
            }
            result.toList() to null
        }.getOrElse { null to (it.message ?: "ERROR") }
    }

    private fun cleanSongTitle(title: String): String {
        return title
            .replace(Regex("\\([^)]*(official|video|audio|lyric|prod|remaster|feat|ft\\.|קליפ|מילים|רשמי)[^)]*\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\[[^]]*]"), "")
            .replace(Regex("\\s*-\\s*(official|audio|video|קליפ).*", RegexOption.IGNORE_CASE), "")
            .replace("Official Music Video", "", true)
            .replace("Official Video", "", true)
            .replace("Official Audio", "", true)
            .replace("קליפ רשמי", "")
            .replace("|", " ")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    private fun cleanArtistName(artist: String): String {
        return artist
            .replace("- Topic", "", true)
            .replace("VEVO", "", true)
            .replace("Official", "", true)
            .substringBefore(",")
            .substringBefore("&")
            .trim()
    }

    suspend fun fetchSyncedLyrics(track: Track, manualOverrideQuery: String? = null): List<LyricLine> = withContext(Dispatchers.IO) {
        val cleanTitle = if (!manualOverrideQuery.isNullOrBlank()) manualOverrideQuery.trim() else cleanSongTitle(track.title)
        val cleanArtist = if (!manualOverrideQuery.isNullOrBlank()) "" else cleanArtistName(track.artist)

        if (cleanArtist.isNotBlank()) {
            val exact = runCatching {
                val url = "https://lrclib.net/api/get?artist_name=${URLEncoder.encode(cleanArtist, "UTF-8")}&track_name=${URLEncoder.encode(cleanTitle, "UTF-8")}"
                client.newCall(Request.Builder().url(url).header("User-Agent", "Sonify/10.0").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    parseLyricsJson(JSONObject(resp.body?.string() ?: return@use emptyList()))
                }
            }.getOrDefault(emptyList())
            if (exact.isNotEmpty()) return@withContext exact
        }

        val searchQueries = listOfNotNull(
            "$cleanArtist $cleanTitle".trim().takeIf { it.isNotBlank() },
            cleanTitle.takeIf { it.isNotBlank() && cleanArtist.isNotBlank() }
        ).distinct()

        for (sq in searchQueries) {
            val found = runCatching {
                val q = URLEncoder.encode(sq, "UTF-8")
                client.newCall(Request.Builder().url("https://lrclib.net/api/search?q=$q").header("User-Agent", "Sonify/10.0").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val arr = JSONArray(resp.body?.string() ?: return@use emptyList())
                    for (i in 0 until arr.length().coerceAtMost(8)) {
                        val obj = arr.optJSONObject(i) ?: continue
                        if (obj.optString("syncedLyrics").isNotBlank()) {
                            val parsed = parseLyricsJson(obj)
                            if (parsed.isNotEmpty()) return@use parsed
                        }
                    }
                    for (i in 0 until arr.length().coerceAtMost(8)) {
                        val parsed = parseLyricsJson(arr.optJSONObject(i) ?: continue)
                        if (parsed.isNotEmpty()) return@use parsed
                    }
                    emptyList()
                }
            }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return@withContext found
        }

        if (track.source == StreamSource.YOUTUBE && manualOverrideQuery.isNullOrBlank()) {
            val ytCaptions = runCatching { fetchYouTubeCaptions(track.id) }.getOrDefault(emptyList())
            if (ytCaptions.isNotEmpty()) return@withContext ytCaptions
        }

        if (cleanArtist.isNotBlank() && cleanTitle.isNotBlank()) {
            val ovh = runCatching {
                val url = "https://api.lyrics.ovh/v1/${URLEncoder.encode(cleanArtist, "UTF-8")}/${URLEncoder.encode(cleanTitle, "UTF-8")}"
                probeClient.newCall(Request.Builder().url(url).header("User-Agent", "Sonify/10.0").build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val plain = JSONObject(resp.body?.string() ?: return@use emptyList()).optString("lyrics")
                    plain.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("Paroles de", true) }
                        .mapIndexed { idx, line -> LyricLine(idx * 3200L, line) }
                }
            }.getOrDefault(emptyList())
            if (ovh.isNotEmpty()) return@withContext ovh
        }

        emptyList()
    }

    private fun fetchYouTubeCaptions(videoId: String): List<LyricLine> {
        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB"); put("clientVersion", "2.20250219.01.00"); put("hl", "he"); put("gl", "IL")
                })
            })
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .build()

        val captionUrl = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val root = JSONObject(resp.body?.string() ?: return emptyList())
            val tracks = root.optJSONObject("captions")
                ?.optJSONObject("playerCaptionsTracklistRenderer")
                ?.optJSONArray("captionTracks") ?: return emptyList()
            var bestUrl: String? = null
            for (i in 0 until tracks.length()) {
                val ct = tracks.optJSONObject(i) ?: continue
                val base = ct.optString("baseUrl")
                val lang = ct.optString("languageCode")
                val kind = ct.optString("kind")
                if (base.startsWith("http")) {
                    if (kind.isBlank() && (lang.startsWith("he") || lang.startsWith("iw") || lang.startsWith("en"))) {
                        bestUrl = base; break
                    }
                    if (bestUrl == null) bestUrl = base
                }
            }
            bestUrl
        } ?: return emptyList()

        val xml = client.newCall(Request.Builder().url(captionUrl).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
            if (!r.isSuccessful) return emptyList()
            r.body?.string().orEmpty()
        }
        val regex = Regex("<text[^>]*start=\"([0-9.]+)\"[^>]*>(.*?)</text>", RegexOption.DOT_MATCHES_ALL)
        return regex.findAll(xml).mapNotNull { m ->
            val startSec = m.groupValues[1].toDoubleOrNull() ?: return@mapNotNull null
            val rawText = Html.fromHtml(m.groupValues[2], Html.FROM_HTML_MODE_LEGACY).toString().replace("\n", " ").trim()
            if (rawText.isBlank() || rawText == "[Music]" || rawText == "[מוזיקה]") null
            else LyricLine((startSec * 1000).toLong(), rawText)
        }.toList().sortedBy { it.timeMs }
    }

    private fun parseLyricsJson(json: JSONObject): List<LyricLine> {
        val synced = json.optString("syncedLyrics")
        if (synced.isNotBlank()) return parseLrc(synced)
        val plain = json.optString("plainLyrics")
        if (plain.isNotBlank()) return plain.lines().filter { it.isNotBlank() }.mapIndexed { idx, line -> LyricLine(idx * 3500L, line.trim()) }
        return emptyList()
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val regex = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?](.*)")
        return lrc.lines().mapNotNull { line ->
            val m = regex.find(line.trim()) ?: return@mapNotNull null
            val min = m.groupValues[1].toLongOrNull() ?: 0L
            val sec = m.groupValues[2].toLongOrNull() ?: 0L
            val ms = m.groupValues[3].padEnd(3, '0').take(3).toLongOrNull() ?: 0L
            val text = m.groupValues[4].trim()
            if (text.isBlank()) null else LyricLine(min * 60_000L + sec * 1000L + ms, text)
        }.sortedBy { it.timeMs }
    }

    // ---------- OpenSubsonic & Local Audio ----------
    suspend fun testSubsonicConnection(settings: AppSettings): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (settings.subsonicUrl.isBlank()) return@withContext false to "יש להזין כתובת שרת"
        runCatching {
            val base = settings.subsonicUrl.trimEnd('/')
            val auth = buildSubsonicAuth(settings.subsonicUser, settings.subsonicPass)
            fastProxyClient.newCall(Request.Builder().url("$base/rest/ping.view?$auth").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use false to "שגיאת HTTP ${resp.code}"
                val ok = JSONObject(resp.body?.string() ?: "").optJSONObject("subsonic-response")?.optString("status") == "ok"
                if (ok) true to "חיבור לשרת Lossless הצליח!" else false to "שם משתמש או סיסמה שגויים"
            }
        }.getOrElse { false to "לא ניתן להתחבר לשרת: ${it.message}" }
    }

    private fun searchSubsonic(query: String, settings: AppSettings): List<Track> {
        if (settings.subsonicUrl.isBlank()) return emptyList()
        return runCatching {
            val base = settings.subsonicUrl.trimEnd('/')
            val auth = buildSubsonicAuth(settings.subsonicUser, settings.subsonicPass)
            val q = URLEncoder.encode(query.ifBlank { "" }, "UTF-8")
            client.newCall(Request.Builder().url("$base/rest/search3.view?query=$q&songCount=40&$auth").build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val songs = JSONObject(resp.body?.string() ?: return emptyList()).optJSONObject("subsonic-response")?.optJSONObject("searchResult3")?.optJSONArray("song") ?: return emptyList()
                buildList {
                    for (i in 0 until songs.length()) {
                        val s = songs.optJSONObject(i) ?: continue
                        val coverId = s.optString("coverArt")
                        add(Track(s.optString("id"), s.optString("title", "ללא שם"), s.optString("artist", "אמן"), s.optString("album", ""), if (coverId.isNotBlank()) "$base/rest/getCoverArt.view?id=$coverId&$auth" else "", s.optLong("duration", 0L) * 1000L, StreamSource.OPENSUBSONIC))
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun buildSubsonicAuth(user: String, pass: String): String {
        val salt = "sonify26"
        val digest = MessageDigest.getInstance("MD5").digest((pass + salt).toByteArray()).joinToString("") { "%02x".format(it) }
        return "u=${URLEncoder.encode(user, "UTF-8")}&t=$digest&s=$salt&v=1.16.1&c=Sonify&f=json"
    }

    fun searchLocalAudio(query: String): List<Track> {
        val list = mutableListOf<Track>()
        runCatching {
            val projection = arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST, MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID)
            context.contentResolver.query(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { c ->
                val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
                val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
                val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
                val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
                val durCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
                val albIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol)
                    val title = c.getString(titleCol) ?: "ללא שם"
                    val artist = c.getString(artistCol) ?: "אמן"
                    val album = c.getString(albumCol) ?: ""
                    if (query.isNotBlank() && !title.contains(query, true) && !artist.contains(query, true)) continue
                    list.add(Track("local_$id", title, artist, album, ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), c.getLong(albIdCol)).toString(), c.getLong(durCol), StreamSource.LOCAL, ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()))
                }
            }
        }
        return list
    }
}
