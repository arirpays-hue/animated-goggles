package com.sonify.app

import android.content.ContentUris
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.MediaStore
import android.text.Html
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
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

    val downloadEngine: OfflineDownloadEngine = OfflineDownloadCoordinator.getEngine(context)
    val aiClient = AiApiClient()

    private val artistPhotoCache = ConcurrentHashMap<String, String>()
    private val artistInfoCache = ConcurrentHashMap<String, SimilarArtist>()

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

    private val lyricsCacheDir: File
        get() = File(context.filesDir, "lyrics_cache").apply { if (!exists()) mkdirs() }

    // ---------- Delegated Offline & Network Utilities ----------
    suspend fun prewarmYtDlp() = downloadEngine.prewarmYtDlp()
    fun isOnWifi(): Boolean = downloadEngine.isOnWifi()

    fun isWeakNetwork(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return true
        val caps = cm.getNetworkCapabilities(net) ?: return true
        val downKbps = caps.linkDownstreamBandwidthKbps
        if (downKbps in 1..1500) return true
        return !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && downKbps in 1..2500
    }

    fun effectiveQuality(settings: AppSettings, forDownload: Boolean = false): AudioQuality {
        val raw = when {
            forDownload -> settings.downloadQuality
            settings.dataSaver -> AudioQuality.LOW
            isOnWifi() -> settings.wifiQuality
            else -> settings.cellularQuality
        }
        val clamped = settings.streamSource.clampQuality(raw)
        if (!forDownload && settings.autoAdjustQuality) {
            if (isWeakNetwork()) {
                return if (clamped == AudioQuality.LOW) AudioQuality.LOW else AudioQuality.NORMAL
            }
            if (!isOnWifi() && clamped == AudioQuality.LOSSLESS) {
                return AudioQuality.HIGH
            }
        }
        return clamped
    }

    fun getOfflineFile(trackId: String): File = downloadEngine.getOfflineFile(trackId)
    fun getPrecachedAudioFile(trackId: String): File = downloadEngine.getPrecachedAudioFile(trackId)
    fun getOfflineTrackIds(): Set<String> = downloadEngine.getOfflineTrackIds()
    fun getOfflineFileSizeMb(trackId: String): Float = downloadEngine.getOfflineFileSizeMb(trackId)
    fun getOfflineSizeMb(): Float = downloadEngine.getTotalOfflineSizeMb()
    fun deleteAllOfflineTracks() = downloadEngine.deleteAllOfflineTracks()
    fun deleteOfflineTrack(trackId: String) = downloadEngine.deleteOfflineTrack(trackId)

    suspend fun precacheTrackAudio(track: Track, settings: AppSettings): Boolean {
        if (settings.offlineMode || settings.dataSaver || track.source != StreamSource.YOUTUBE) return false
        return downloadEngine.precacheAudioTrack(track, effectiveQuality(settings, forDownload = false))
    }

    suspend fun downloadTrackOffline(
        track: Track,
        settings: AppSettings,
        capturedStreams: List<CapturedMediaStream> = emptyList(),
        onProgress: (Int) -> Unit = {}
    ): Pair<Boolean, String> {
        val nonYt: (suspend () -> ResolvedStreams?)? = if (track.source != StreamSource.YOUTUBE) {
            { resolveStreams(track, settings, forDownload = true) }
        } else null
        return downloadEngine.downloadAudioTrack(track, settings, nonYt, capturedStreams, onProgress)
    }

    fun getCacheSizeMb(): Float {
        fun dirSize(f: File): Long = if (f.isDirectory) f.listFiles()?.sumOf { dirSize(it) } ?: 0L else f.length()
        return dirSize(context.cacheDir) / (1024f * 1024f)
    }

    fun clearCache() {
        runCatching {
            AudioCacheHolder.clearAllEntries()
            context.cacheDir.listFiles()?.forEach { f ->
                if (f.name != "exo_stream_cache") f.deleteRecursively()
            }
        }
    }

    suspend fun testAiConnection(settings: AppSettings): Pair<Boolean, String> = aiClient.testConnection(settings)

    // ---------- 1. Search Songs, Artists, Podcasts & Audiobooks ----------
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

    suspend fun searchArtists(query: String, maxResults: Int = 8): List<SearchArtistResult> = withContext(Dispatchers.IO) {
        val clean = query.trim()
        if (clean.isBlank()) return@withContext emptyList()
        runCatching {
            val bodyJson = JSONObject().apply {
                put("query", clean)
                put("params", "EgWKAQIgAWoKEAkQBRAKEAMQBA==")
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
                if (!resp.isSuccessful) return@use emptyList()
                val out = mutableListOf<SimilarArtist>()
                extractYtMusicArtists(JSONObject(resp.body?.string() ?: return@use emptyList()), out)
                out.distinctBy { it.browseId.ifBlank { it.name.lowercase() } }
                    .take(maxResults)
                    .map { a ->
                        if (a.artworkUrl.isNotBlank()) artistPhotoCache[a.name.lowercase()] = a.artworkUrl
                        SearchArtistResult(a.name, a.browseId, a.artworkUrl, a.subscribers)
                    }
            }
        }.getOrDefault(emptyList())
    }

    suspend fun searchArtistTracks(artistName: String, settings: AppSettings): List<Track> = withContext(Dispatchers.IO) {
        val s1 = searchTracks(artistName, settings, maxResults = 25)
        val s2 = searchTracks("$artistName top hits", settings, maxResults = 20)
        (s1 + s2).distinctBy { it.id }.take(35)
    }

    // ---------- 1B. Real Artist Photo + Full Now Playing Extras (Spotify-Grade) ----------
    suspend fun fetchArtistPhotoUrl(artistName: String): String = withContext(Dispatchers.IO) {
        val clean = cleanArtistName(artistName).ifBlank { artistName.trim() }
        if (clean.isBlank() || clean.equals("YouTube", true) || clean.equals("אמן", true)) return@withContext ""
        val key = clean.lowercase()
        artistPhotoCache[key]?.takeIf { it.isNotBlank() }?.let { return@withContext it }

        val ch = findYtMusicArtistChannel(clean)
        var url = ch?.artworkUrl.orEmpty()
        if (ch != null && ch.browseId.startsWith("UC")) {
            val root = runCatching { fetchYtMusicBrowseJson(ch.browseId) }.getOrNull()
            val header = root?.optJSONObject("header")
            val immersive = header?.optJSONObject("musicImmersiveHeaderRenderer")
                ?: header?.optJSONObject("musicVisualHeaderRenderer")
            val thumbs = immersive?.optJSONObject("thumbnail")
                ?.optJSONObject("musicThumbnailRenderer")
                ?.optJSONObject("thumbnail")
                ?.optJSONArray("thumbnails")
                ?: immersive?.optJSONObject("foregroundThumbnail")
                    ?.optJSONObject("musicThumbnailRenderer")
                    ?.optJSONObject("thumbnail")
                    ?.optJSONArray("thumbnails")
            if (thumbs != null && thumbs.length() > 0) {
                val raw = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
                if (raw.isNotBlank()) url = upgradeWideBannerUrl(raw)
            }
        }
        if (url.isNotBlank()) artistPhotoCache[key] = url
        url
    }

    suspend fun fetchNowPlayingExtras(track: Track, settings: AppSettings): NowPlayingExtras = withContext(Dispatchers.IO) {
        val mainArtist = cleanArtistName(track.artist).ifBlank { track.artist.trim() }
        if (settings.offlineMode || track.source == StreamSource.LOCAL) {
            return@withContext NowPlayingExtras(
                trackId = track.id,
                artistPhotoUrl = artistPhotoCache[mainArtist.lowercase()].orEmpty(),
                credits = buildFallbackCredits(track, settings.language == AppLanguage.ENGLISH)
            )
        }

        val isEn = settings.language == AppLanguage.ENGLISH
        coroutineScope {
            val artistJob = async {
                runCatching {
                    val ch = artistInfoCache[mainArtist.lowercase()] ?: findYtMusicArtistChannel(mainArtist)?.also {
                        artistInfoCache[mainArtist.lowercase()] = it
                    }
                    var photo = ch?.artworkUrl.orEmpty()
                    var bio = ""
                    var subs = ch?.subscribers.orEmpty()
                    val topList = mutableListOf<Track>()
                    if (ch != null && ch.browseId.startsWith("UC")) {
                        val root = fetchYtMusicBrowseJson(ch.browseId)
                        if (root != null) {
                            val header = root.optJSONObject("header")
                            val imm = header?.optJSONObject("musicImmersiveHeaderRenderer")
                                ?: header?.optJSONObject("musicVisualHeaderRenderer")
                            if (imm != null) {
                                bio = extractRunsText(imm.optJSONObject("description"))
                                val subBtn = imm.optJSONObject("subscriptionButton")?.optJSONObject("subscribeButtonRenderer")
                                val subTxt = extractRunsText(subBtn?.optJSONObject("longSubscriberCountText"))
                                    .ifBlank { extractRunsText(subBtn?.optJSONObject("shortSubscriberCountText")) }
                                    .ifBlank { extractRunsText(subBtn?.optJSONObject("subscriberCountText")) }
                                if (subTxt.isNotBlank()) subs = subTxt
                                val thumbs = imm.optJSONObject("thumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                    ?: imm.optJSONObject("foregroundThumbnail")?.optJSONObject("musicThumbnailRenderer")?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                                if (thumbs != null && thumbs.length() > 0) {
                                    val raw = thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
                                    if (raw.isNotBlank()) photo = upgradeWideBannerUrl(raw)
                                }
                            }
                            extractYtMusicSongs(root, topList, 10, fallbackArtist = mainArtist)
                        }
                    }
                    if (photo.isNotBlank()) artistPhotoCache[mainArtist.lowercase()] = photo
                    Quad(photo, bio, subs, topList.firstOrNull { it.id != track.id })
                }.getOrDefault(Quad("", "", "", null))
            }

            val creditsJob = async {
                runCatching { fetchTrackCreditsFromYouTube(track, isEn) }
                    .getOrDefault(buildFallbackCredits(track, isEn))
            }

            val episodesJob = async {
                runCatching {
                    searchYouTubeWeb("$mainArtist interview podcast", isPodcast = true, isAudiobook = false, maxLimit = 5)
                        .filter { it.id != track.id }
                        .take(3)
                }.getOrDefault(emptyList())
            }

            val similarJob = async {
                runCatching {
                    val simA = searchTracks("$mainArtist similar artists", settings, maxResults = 6)
                        .firstOrNull { it.id != track.id && !it.artist.contains(mainArtist, true) }
                    val simS = searchTracks("${cleanSongTitle(track.title)} $mainArtist similar songs", settings, maxResults = 6)
                        .firstOrNull { it.id != track.id && it.id != simA?.id }
                    simA to simS
                }.getOrDefault(null to null)
            }

            val (photo, bio, subs, topTrack) = artistJob.await()
            val credits = creditsJob.await()
            val episodes = episodesJob.await()
            val (simArtistTrack, simSongTrack) = similarJob.await()

            NowPlayingExtras(
                trackId = track.id,
                artistPhotoUrl = photo,
                artistBio = bio,
                artistSubscribers = subs,
                credits = credits,
                artistEpisodes = episodes,
                artistTopTrack = topTrack,
                similarArtistTrack = simArtistTrack,
                similarSongTrack = simSongTrack
            )
        }
    }

    private fun buildFallbackCredits(track: Track, isEn: Boolean): List<TrackCreditEntry> {
        val rawArtists = track.artist.split(",", "&", "feat.", "ft.", " x ", " X ")
            .map { cleanArtistName(it) }
            .filter { it.length >= 2 }
            .distinct()
        if (rawArtists.isEmpty()) {
            return listOf(TrackCreditEntry(track.artist, if (isEn) "Main Artist" else "האמן/ית הראשי/ת", true))
        }
        return rawArtists.mapIndexed { idx, name ->
            if (idx == 0) TrackCreditEntry(name, if (isEn) "Main Artist" else "האמן/ית הראשי/ת", true)
            else TrackCreditEntry(name, if (isEn) "Featured Artist" else "אמן/ית אורח/ת", false)
        }
    }

    private fun fetchTrackCreditsFromYouTube(track: Track, isEn: Boolean): List<TrackCreditEntry> {
        val base = buildFallbackCredits(track, isEn).toMutableList()
        if (track.source != StreamSource.YOUTUBE || track.id.length != 11) return base

        val desc = runCatching {
            val bodyJson = JSONObject().apply {
                put("videoId", track.id)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB"); put("clientVersion", "2.20250219.01.00"); put("hl", "en"); put("gl", "US")
                    })
                })
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            fastProxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                JSONObject(resp.body?.string() ?: return@use "")
                    .optJSONObject("videoDetails")
                    ?.optString("shortDescription")
                    .orEmpty()
            }
        }.getOrDefault("")

        if (desc.isBlank()) return base

        val roleMap = LinkedHashMap<String, MutableSet<String>>()
        val roleRegex = Regex("^(Composer|Lyricist|Writer|Producer|Associated Performer|Vocals|Mixer|Mastering Engineer|Engineer|Arranger|Music Publisher|Composer, Lyricist|Written by|Produced by)\\s*:\\s*(.+)$", RegexOption.IGNORE_CASE)

        desc.lines().forEach { rawLine ->
            val m = roleRegex.find(rawLine.trim()) ?: return@forEach
            val rawRole = m.groupValues[1].trim()
            val names = m.groupValues[2].split(",", "&", ";").map { it.trim() }.filter { it.length in 2..42 && !it.startsWith("http") }
            val label = translateCreditRole(rawRole, isEn)
            names.forEach { person ->
                roleMap.getOrPut(person) { LinkedHashSet() }.add(label)
            }
        }

        roleMap.entries.take(8).forEach { (person, roles) ->
            val existingIdx = base.indexOfFirst { it.name.equals(person, true) }
            if (existingIdx >= 0) {
                val cur = base[existingIdx]
                val mergedRoles = (listOf(cur.roles) + roles).distinct().joinToString(" • ")
                base[existingIdx] = cur.copy(roles = mergedRoles)
            } else {
                base.add(TrackCreditEntry(person, roles.joinToString(" • "), false))
            }
        }
        return base.take(10)
    }

    private fun translateCreditRole(role: String, isEn: Boolean): String {
        val l = role.lowercase()
        if (isEn) return role.replaceFirstChar { it.uppercase() }
        return when {
            l.contains("composer") && l.contains("lyricist") -> "מלחין/ה • כותב/ת המילים"
            l.contains("composer") -> "מלחין/ה"
            l.contains("lyricist") || l.contains("written") || l.contains("writer") -> "כותב/ת המילים"
            l.contains("producer") || l.contains("produced") -> "מפיק/ה"
            l.contains("engineer") || l.contains("mixer") -> "טכנאי/ת הקלטות ומיקס"
            l.contains("vocal") || l.contains("performer") -> "מבצע/ת"
            l.contains("arranger") -> "מעבד/ת"
            l.contains("publisher") -> "הוצאה לאור"
            else -> role
        }
    }

    // ---------- 1C. Full Spotify-Style Artist Profile ----------
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
                bannerUrl = artistPhotoCache[cleanName.lowercase()].orEmpty().ifBlank { tracks.firstOrNull()?.artworkUrl ?: "" },
                avatarUrl = artistPhotoCache[cleanName.lowercase()].orEmpty().ifBlank { tracks.firstOrNull()?.artworkUrl ?: "" },
                topTracks = tracks
            )
        }

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
                            extractYtMusicSongs(shelf, topTracks, 35, fallbackArtist = cleanName)
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
        val finalBanner = bannerUrl.ifBlank { fallbackArt }
        if (finalBanner.isNotBlank()) artistPhotoCache[cleanName.lowercase()] = finalBanner

        ArtistProfileDetails(
            artistName = foundArtist?.name?.takeIf { it.isNotBlank() } ?: cleanName,
            browseId = foundArtist?.browseId.orEmpty(),
            bannerUrl = finalBanner,
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
        if (out.size >= 10 || node == null) return
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

    // ---------- Shared Track Metadata Fetcher ----------
    suspend fun fetchTrackMetadataById(videoId: String): Track = withContext(Dispatchers.IO) {
        val cleanId = videoId.trim().take(11)
        val defaultArt = "https://i.ytimg.com/vi/$cleanId/maxresdefault.jpg"

        val fromPlayer = runCatching {
            val bodyJson = JSONObject().apply {
                put("videoId", cleanId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB"); put("clientVersion", "2.20250219.01.00"); put("hl", "he"); put("gl", "IL")
                    })
                })
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            fastProxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val vd = JSONObject(resp.body?.string() ?: return@use null).optJSONObject("videoDetails") ?: return@use null
                val rawTitle = vd.optString("title").trim()
                val rawAuthor = cleanArtistName(vd.optString("author").trim())
                val durMs = vd.optString("lengthSeconds").toLongOrNull()?.times(1000L) ?: 0L
                val thumbs = vd.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                val art = if (thumbs != null && thumbs.length() > 0) {
                    upgradeArtworkUrlGlobal(thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()).ifBlank { defaultArt }
                } else defaultArt
                if (rawTitle.isBlank()) return@use null
                val (parsedArtist, parsedTitle) = splitArtistAndTitle(rawTitle, rawAuthor)
                Track(cleanId, parsedTitle, parsedArtist, artworkUrl = art, durationMs = durMs, source = StreamSource.YOUTUBE, isExplicit = detectExplicit(rawTitle))
            }
        }.getOrNull()
        if (fromPlayer != null) return@withContext fromPlayer

        val fromOembed = runCatching {
            val url = "https://www.youtube.com/oembed?url=https://www.youtube.com/watch?v=$cleanId&format=json"
            val req = Request.Builder().url(url).header("User-Agent", "Mozilla/5.0").build()
            fastProxyClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use null
                val o = JSONObject(resp.body?.string() ?: return@use null)
                val rawTitle = o.optString("title").trim()
                val rawAuthor = cleanArtistName(o.optString("author_name").trim())
                val art = upgradeArtworkUrlGlobal(o.optString("thumbnail_url")).ifBlank { defaultArt }
                if (rawTitle.isBlank()) return@use null
                val (parsedArtist, parsedTitle) = splitArtistAndTitle(rawTitle, rawAuthor)
                Track(cleanId, parsedTitle, parsedArtist, artworkUrl = art, source = StreamSource.YOUTUBE, isExplicit = detectExplicit(rawTitle))
            }
        }.getOrNull()
        if (fromOembed != null) return@withContext fromOembed

        Track(cleanId, "YouTube Track", "YouTube", artworkUrl = defaultArt)
    }

    private fun splitArtistAndTitle(rawTitle: String, rawAuthor: String): Pair<String, String> {
        val cleanedTitle = cleanSongTitle(rawTitle).ifBlank { rawTitle.trim() }
        val sep = listOf(" - ", " – ", " — ").firstOrNull { cleanedTitle.contains(it) }
        if (sep != null) {
            val left = cleanedTitle.substringBefore(sep).trim()
            val right = cleanedTitle.substringAfter(sep).trim()
            if (left.length in 2..40 && right.length >= 2) {
                return left to right
            }
        }
        return rawAuthor.ifBlank { "YouTube" } to cleanedTitle
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

    // ---------- 2. Playlists Search, Browse & Complete Multi-Source Import (YouTube + Spotify + Text) ----------
    suspend fun searchPlaylists(query: String): List<RemotePlaylist> = withContext(Dispatchers.IO) {
        val combined = mutableListOf<RemotePlaylist>()

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
            put("params", "EgIQAw==")
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
            queries.take(4).map { q -> async { runCatching { searchPlaylists(q).take(5) }.getOrDefault(emptyList()) } }.awaitAll().flatten()
        }
        all.distinctBy { it.id.removePrefix("VL") }.take(maxTotal)
    }

    suspend fun fetchPlaylistTracks(
        playlistId: String,
        maxTracks: Int = 100,
        fallbackCoverUrl: String = "",
        fallbackArtist: String = ""
    ): List<Track> = withContext(Dispatchers.IO) {
        val browseId = if (playlistId.startsWith("VL") || playlistId.startsWith("MPRE")) playlistId else "VL$playlistId"
        val rawId = playlistId.removePrefix("VL")

        val ytMusicTracks = runCatching {
            val root = fetchYtMusicBrowseJson(browseId) ?: return@runCatching emptyList()
            val headerObj = root.optJSONObject("header")
                ?.let { h -> h.optJSONObject("musicDetailHeaderRenderer") ?: h.optJSONObject("musicResponsiveHeaderRenderer") }
            val headerThumb = headerObj
                ?.optJSONObject("thumbnail")
                ?.let { t -> t.optJSONObject("croppedSquareThumbnailRenderer") ?: t.optJSONObject("musicThumbnailRenderer") }
                ?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
                ?.let { arr -> if (arr.length() > 0) arr.optJSONObject(arr.length() - 1)?.optString("url").orEmpty() else "" }
                .orEmpty()
            val headerArtist = extractRunsText(headerObj?.optJSONObject("straplineTextOne"))
                .ifBlank { extractRunsText(headerObj?.optJSONObject("subtitle")).substringAfter("•", "").substringBefore("•").trim() }
            val effectiveCover = upgradeArtworkUrlGlobal(fallbackCoverUrl.ifBlank { headerThumb })
            val effectiveArtist = fallbackArtist.ifBlank { headerArtist }

            val out = mutableListOf<Track>()
            extractYtMusicSongs(root, out, maxTracks, fallbackCover = effectiveCover, fallbackArtist = effectiveArtist)
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
                            val uploader = o.optString("uploaderName").ifBlank { fallbackArtist }.ifBlank { "אמן" }
                            add(Track(id, title, uploader, artworkUrl = upgradeArtworkUrlGlobal(thumb), durationMs = o.optLong("duration", 0L) * 1000L, source = StreamSource.YOUTUBE, isExplicit = detectExplicit(title)))
                        }
                    }.filter { isLikelySingleSong(it) }
                }
            }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return@withContext res
        }
        emptyList()
    }

    suspend fun importPlaylistFromTextOrUrl(rawInput: String, settings: AppSettings): Pair<String?, List<Track>> = withContext(Dispatchers.IO) {
        val trimmed = rawInput.trim()

        // 1. YouTube Playlist URL
        if (trimmed.contains("list=", ignoreCase = true)) {
            val listId = trimmed.substringAfter("list=").substringBefore("&").substringBefore("\n").substringBefore(" ").trim()
            if (listId.isNotBlank()) {
                val tracks = fetchPlaylistTracks(listId, maxTracks = 100)
                if (tracks.isNotEmpty()) return@withContext null to tracks
            }
        }

        // 2. Spotify Playlist / Album / Track Link
        val spotifyMatch = Regex("open\\.spotify\\.com/(playlist|album|track)/([a-zA-Z0-9]+)", RegexOption.IGNORE_CASE).find(trimmed)
        if (spotifyMatch != null) {
            val spType = spotifyMatch.groupValues[1].lowercase()
            val spId = spotifyMatch.groupValues[2]
            val (spName, spQueries) = fetchSpotifyEmbedQueries(spType, spId)
            if (spQueries.isNotEmpty()) {
                val tracks = resolveSongQueriesParallel(spQueries.take(50), settings)
                if (tracks.isNotEmpty()) return@withContext spName to tracks
            }
        }

        // 3. Single YouTube Video URL(s)
        val ytVidMatches = Regex("(?:youtu\\.be/|watch\\?v=)([a-zA-Z0-9_-]{11})").findAll(trimmed).map { it.groupValues[1] }.distinct().toList()
        if (ytVidMatches.isNotEmpty() && trimmed.lines().all { it.contains("http", true) || it.isBlank() }) {
            val tracks = coroutineScope {
                ytVidMatches.take(50).map { vid -> async { fetchTrackMetadataById(vid) } }.awaitAll()
            }
            if (tracks.isNotEmpty()) return@withContext null to tracks
        }

        // 4. Plain-Text Song List (parallel batches of 4)
        val lines = trimmed.lines()
            .map { it.replace(Regex("^\\d+[.)\\s-]+"), "").trim() }
            .filter { it.length >= 2 && !it.startsWith("http", true) }
            .take(60)
        if (lines.isEmpty()) return@withContext null to emptyList()
        null to resolveSongQueriesParallel(lines, settings)
    }

    private suspend fun resolveSongQueriesParallel(queries: List<String>, settings: AppSettings): List<Track> {
        val imported = mutableListOf<Track>()
        for (batch in queries.chunked(4)) {
            val foundBatch = coroutineScope {
                batch.map { q -> async { searchTracks(q, settings, maxResults = 3).firstOrNull() } }.awaitAll()
            }
            foundBatch.filterNotNull().forEach { cand ->
                if (imported.none { it.id == cand.id }) imported.add(cand)
            }
        }
        return imported
    }

    private fun fetchSpotifyEmbedQueries(type: String, id: String): Pair<String?, List<String>> {
        return runCatching {
            val url = "https://open.spotify.com/embed/$type/$id"
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .build()
            val html = client.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string().orEmpty() else "" }
            val nextData = Regex("<script id=\"__NEXT_DATA__\"[^>]*>(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
                .find(html)?.groupValues?.get(1) ?: return null to emptyList()
            val root = JSONObject(nextData)
            val entity = root.optJSONObject("props")
                ?.optJSONObject("pageProps")
                ?.optJSONObject("state")
                ?.optJSONObject("data")
                ?.optJSONObject("entity") ?: return null to emptyList()

            val title = entity.optString("name").ifBlank { entity.optString("title") }.takeIf { it.isNotBlank() }
            val list = mutableListOf<String>()
            val trackList = entity.optJSONArray("trackList")
            if (trackList != null && trackList.length() > 0) {
                for (i in 0 until trackList.length()) {
                    val item = trackList.optJSONObject(i) ?: continue
                    val t = item.optString("title")
                    val a = item.optString("subtitle")
                    if (t.isNotBlank()) list.add("$a $t".trim())
                }
            } else if (type == "track" && !title.isNullOrBlank()) {
                val artistsArr = entity.optJSONArray("artists")
                val artist = artistsArr?.optJSONObject(0)?.optString("name").orEmpty()
                list.add("$artist $title".trim())
            }
            title to list
        }.getOrDefault(null to emptyList())
    }

    private fun ytMusicContext(): JSONObject = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX"); put("clientVersion", "1.20260401.01.00"); put("hl", "he"); put("gl", "IL")
        })
    }

    private fun extractYtMusicSongs(
        node: Any?,
        out: MutableList<Track>,
        maxLimit: Int = 100,
        fallbackCover: String = "",
        fallbackArtist: String = ""
    ) {
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
                        val artist = parts.firstOrNull {
                            !it.contains("צפיות") && !it.contains("השמעות") && !it.contains("plays", true) && !it.contains("views", true)
                        }?.takeIf { it.isNotBlank() } ?: fallbackArtist.ifBlank { "אמן" }
                        val album = parts.getOrNull(1)?.takeIf {
                            !it.contains("צפיות") && !it.contains("השמעות") && !it.contains("plays", true)
                        } ?: ""
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
                    while (keys.hasNext()) extractYtMusicSongs(node.opt(keys.next()), out, maxLimit, fallbackCover, fallbackArtist)
                }
            }
            is JSONArray -> { for (i in 0 until node.length()) extractYtMusicSongs(node.opt(i), out, maxLimit, fallbackCover, fallbackArtist) }
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

    // ---------- 3. Verified Pure-Audio-First Stream Resolution ----------
    suspend fun resolveStreams(track: Track, settings: AppSettings, attemptIndex: Int = 0, forDownload: Boolean = false): ResolvedStreams? = withContext(Dispatchers.IO) {
        val offlineFile = getOfflineFile(track.id)
        if (!forDownload && offlineFile.exists() && offlineFile.length() > 4096) {
            return@withContext ResolvedStreams(audioUrl = offlineFile.toURI().toString(), isLossless = track.source != StreamSource.YOUTUBE, isOffline = true, codecLabel = "OFFLINE")
        }
        val precacheFile = getPrecachedAudioFile(track.id)
        if (!forDownload && attemptIndex == 0 && precacheFile.exists() && precacheFile.length() > 48_000) {
            precacheFile.setLastModified(System.currentTimeMillis())
            return@withContext ResolvedStreams(audioUrl = precacheFile.toURI().toString(), isOffline = true, codecLabel = "INSTANT CACHE")
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
            val encId = URLEncoder.encode(track.id, "UTF-8")
            val url = "$base/rest/stream.view?id=$encId&$formatParam&$auth"
            return@withContext ResolvedStreams(audioUrl = url, isLossless = quality == AudioQuality.LOSSLESS, codecLabel = if (quality == AudioQuality.LOSSLESS) "FLAC Lossless" else "${quality.kbps}kbps")
        }
        resolveYouTubeNonWrapping(track.id, quality, attemptIndex, strictAudioOnly = forDownload)
    }

    private fun verifyMediaStreamDeep(url: String, userAgent: String, cookie: String? = null, depth: Int = 0, strictAudioOnly: Boolean = false): Boolean {
        if (depth > 3) return false
        return runCatching {
            if (url.contains(".m3u8", true) || url.contains("manifest/hls", true)) {
                val rb = Request.Builder().url(url).header("User-Agent", userAgent)
                if (!cookie.isNullOrBlank()) rb.header("Cookie", cookie)
                val text = probeClient.newCall(rb.build()).execute().use { resp ->
                    if (!resp.isSuccessful) return false
                    resp.body?.string().orEmpty()
                }
                val firstSubUrl = extractFirstHlsTarget(text, strictAudioOnly) ?: return false
                return verifyMediaStreamDeep(firstSubUrl, userAgent, cookie, depth + 1, strictAudioOnly)
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
                if (strictAudioOnly && ct.startsWith("video/")) return false
                val buf = ByteArray(2048)
                (resp.body?.byteStream()?.read(buf) ?: 0) > 256
            }
        }.getOrDefault(false)
    }

    private fun extractFirstHlsTarget(manifest: String, strictAudioOnly: Boolean = false): String? {
        val mediaAudio = Regex("TYPE=AUDIO.*?URI=\"(https?://[^\"]+)\"").find(manifest)?.groupValues?.get(1)
        if (!mediaAudio.isNullOrBlank()) return mediaAudio
        if (strictAudioOnly && (manifest.contains("#EXT-X-STREAM-INF") || manifest.contains("#EXT-X-MEDIA"))) return null
        return manifest.lines().map { it.trim() }.firstOrNull { it.startsWith("http") }
    }

    private fun youtubeStrategies(videoId: String, quality: AudioQuality, strictAudioOnly: Boolean = false): List<() -> ResolvedStreams?> = listOf(
        { fetchInnerTubeClient(videoId, "ANDROID_VR", quality, preferHls = false, strictAudioOnly = strictAudioOnly) },
        { fetchInnerTubeClient(videoId, "IOS", quality, preferHls = true, strictAudioOnly = strictAudioOnly) },
        { fetchInnerTubeClient(videoId, "ANDROID_VR_LEGACY", quality, preferHls = false, strictAudioOnly = strictAudioOnly) },
        { fetchCobaltAudio(videoId) },
        { fetchPipedStream(videoId, quality, strictAudioOnly = strictAudioOnly) },
        { fetchInvidiousProxiedStream(videoId, strictAudioOnly = strictAudioOnly) }
    )

    private fun resolveYouTubeNonWrapping(videoId: String, quality: AudioQuality, startAttempt: Int, strictAudioOnly: Boolean = false): ResolvedStreams? {
        val strategies = youtubeStrategies(videoId, quality, strictAudioOnly)
        for (i in startAttempt until strategies.size) {
            val res = runCatching { strategies[i]() }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun fetchInnerTubeClient(videoId: String, clientType: String, quality: AudioQuality, preferHls: Boolean, strictAudioOnly: Boolean = false): ResolvedStreams? {
        val (clientObj, ua, idHeader, verHeader) = when (clientType) {
            "ANDROID_VR" -> Quad(
                JSONObject().apply {
                    put("clientName", "ANDROID_VR"); put("clientVersion", "1.65.10")
                    put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L")
                    put("deviceMake", "Oculus"); put("deviceModel", "Quest 3"); put("hl", "he"); put("gl", "IL")
                },
                "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                "28", "1.65.10"
            )
            "IOS" -> Quad(
                JSONObject().apply {
                    put("clientName", "IOS"); put("clientVersion", "20.14.2")
                    put("deviceMake", "Apple"); put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone"); put("osVersion", "18.4.0.22E240"); put("hl", "he"); put("gl", "IL")
                },
                "com.google.ios.youtube/20.14.2 (iPhone16,2; U; CPU iOS 18_4_0 like Mac OS X)",
                "5", "20.14.2"
            )
            else -> Quad(
                JSONObject().apply {
                    put("clientName", "ANDROID_VR"); put("clientVersion", "1.62.27")
                    put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L")
                    put("deviceMake", "Oculus"); put("deviceModel", "Quest 3"); put("hl", "he"); put("gl", "IL")
                },
                "com.google.android.apps.youtube.vr.oculus/1.62.27 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                "28", "1.62.27"
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
                    if (u.startsWith("http")) muxedVideoUrl = u
                }
            }
            sd.optJSONArray("adaptiveFormats")?.let { adaptive ->
                for (i in 0 until adaptive.length()) {
                    val f = adaptive.optJSONObject(i) ?: continue
                    val mime = f.optString("mimeType").lowercase()
                    val u = f.optString("url")
                    if (!u.startsWith("http")) continue
                    if (mime.startsWith("audio/")) {
                        audioCandidates.add((f.optInt("bitrate", 128000) / 1000).coerceAtLeast(16) to u)
                    } else if (mime.startsWith("video/mp4")) {
                        val h = f.optInt("height", 360)
                        if (h in 240..720) videoCandidates.add(h to u)
                    }
                }
            }

            val preferredAudio = pickAudioByQuality(audioCandidates, quality)
            val altAudios = audioCandidates.sortedBy { it.first }.map { it.second }.filterNot { it == preferredAudio }.take(2)
            val candidateList = if (strictAudioOnly) {
                if (preferHls) (listOfNotNull(hls, preferredAudio) + altAudios).distinct()
                else (listOfNotNull(preferredAudio) + altAudios).distinct()
            } else {
                if (preferHls) (listOfNotNull(hls, preferredAudio) + altAudios.take(1) + listOfNotNull(muxedVideoUrl)).distinct()
                else (listOfNotNull(preferredAudio) + altAudios.take(1) + listOfNotNull(hls, muxedVideoUrl)).distinct()
            }

            val verifiedAudio = candidateList.firstOrNull { verifyMediaStreamDeep(it, ua, strictAudioOnly = strictAudioOnly) } ?: return null
            val bestVideoOnly = if (strictAudioOnly) null else videoCandidates.sortedBy { it.first }.lastOrNull { it.first <= 720 }?.second
            val chosenKbps = audioCandidates.firstOrNull { it.second == verifiedAudio }?.first ?: quality.kbps.coerceAtMost(160)
            ResolvedStreams(
                audioUrl = verifiedAudio,
                videoUrl = if (strictAudioOnly) null else (muxedVideoUrl ?: hls),
                videoOnlyUrl = bestVideoOnly,
                userAgent = ua,
                codecLabel = if (verifiedAudio == hls) "HLS Audio" else "${chosenKbps}kbps Audio"
            )
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
                    if (u.startsWith("http") && verifyMediaStreamDeep(u, ua, strictAudioOnly = true)) ResolvedStreams(u, userAgent = ua, codecLabel = "MP3 Audio") else null
                }
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun fetchPipedStream(videoId: String, quality: AudioQuality, strictAudioOnly: Boolean = false): ResolvedStreams? {
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
                            val mime = a.optString("mimeType").lowercase()
                            if (u.startsWith("http") && !mime.startsWith("video/")) {
                                audioStreams.add((a.optInt("bitrate", 128000) / 1000).coerceAtLeast(16) to u)
                            }
                        }
                    }
                    val hls = if (strictAudioOnly) null else json.optString("hls").takeIf { it.startsWith("http") }
                    val validAudio = listOfNotNull(pickAudioByQuality(audioStreams, quality), hls)
                        .firstOrNull { verifyMediaStreamDeep(it, ua, strictAudioOnly = strictAudioOnly) } ?: return@use null
                    var muxedVideo: String? = hls; var videoOnly: String? = null
                    if (!strictAudioOnly) {
                        json.optJSONArray("videoStreams")?.let { arr ->
                            for (i in 0 until arr.length()) {
                                val v = arr.optJSONObject(i) ?: continue
                                val u = v.optString("url")
                                if (!u.startsWith("http")) continue
                                if (!v.optBoolean("videoOnly", true)) { muxedVideo = u; break } else if (videoOnly == null) videoOnly = u
                            }
                        }
                    }
                    ResolvedStreams(validAudio, if (strictAudioOnly) null else muxedVideo, videoOnly, ua, codecLabel = "Piped Audio")
                }
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun fetchInvidiousProxiedStream(videoId: String, strictAudioOnly: Boolean = false): ResolvedStreams? {
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        for (base in invidiousInstances) {
            val audioProxy = "$base/latest_version?id=$videoId&itag=140&local=true"
            val videoProxy = "$base/latest_version?id=$videoId&itag=18&local=true"
            if (verifyMediaStreamDeep(audioProxy, ua, strictAudioOnly = strictAudioOnly)) {
                return ResolvedStreams(audioProxy, if (strictAudioOnly) null else videoProxy, userAgent = ua, codecLabel = "Proxy AAC")
            }
            if (!strictAudioOnly && verifyMediaStreamDeep(videoProxy, ua)) {
                return ResolvedStreams(videoProxy, videoProxy, userAgent = ua, codecLabel = "Proxy MP4")
            }
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
            AudioQuality.HIGH -> sorted.minByOrNull { abs(it.first - 140) }?.second
            AudioQuality.VERY_HIGH, AudioQuality.LOSSLESS -> sorted.last().second
        }
    }

    // ---------- 4. Upgraded Multi-Provider AI Engine (Deep Taste Profile + YouTube Mixes + Auto Count) ----------
    private fun computeWeightedTasteArtists(
        likedTracks: List<Track>,
        recentTracks: List<Track>,
        followedArtists: Set<String>,
        stats: ListeningStats,
        excludedArtists: Set<String>
    ): List<String> {
        val scores = LinkedHashMap<String, Int>()
        fun addScore(rawArtist: String, pts: Int) {
            val clean = cleanArtistName(rawArtist)
            if (clean.length < 2 || clean.equals("YouTube", true) || clean.equals("אמן", true)) return
            if (excludedArtists.any { ex -> clean.contains(ex, true) }) return
            scores[clean] = (scores[clean] ?: 0) + pts
        }
        followedArtists.forEach { addScore(it, 14) }
        likedTracks.take(40).forEachIndexed { idx, t -> addScore(t.artist, if (idx < 10) 10 else 6) }
        stats.artistPlayCounts.entries.sortedByDescending { it.value }.take(20).forEach { (a, count) ->
            addScore(a, (count * 3).coerceAtMost(30))
        }
        recentTracks.take(25).forEach { addScore(it.artist, 4) }
        return scores.entries.sortedByDescending { it.value }.map { it.key }
    }

    suspend fun buildSmartAiHomeBatch(
        pageIndex: Int,
        likedTracks: List<Track>,
        recentTracks: List<Track>,
        followedArtists: Set<String>,
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

        val tasteArtists = computeWeightedTasteArtists(likedTracks, recentTracks, followedArtists, stats, excludedArtists)

        if (!isPod && !isBook && settings.showYoutubePlaylistsHome && pageIndex % 2 == 1) {
            val idxA = (pageIndex / 2) % tasteArtists.size.coerceAtLeast(1)
            val a1 = tasteArtists.getOrNull(idxA)
            val a2 = tasteArtists.getOrNull((idxA + 1) % tasteArtists.size.coerceAtLeast(1))
            val ytQueries = if (a1 != null) {
                buildList {
                    add("$a1 mix playlist")
                    if (a2 != null && a2 != a1) add("$a1 $a2 playlist")
                    add("$a1 similar artists best playlist")
                }
            } else {
                listOf("top hits 2026 playlist", "workout energy hits playlist", "chill lofi beats playlist")
            }
            val remotePls = fetchReadyYoutubePlaylists(ytQueries, maxTotal = 12)
            if (remotePls.isNotEmpty()) {
                val secTitle = if (a1 != null) {
                    if (isEn) "YouTube Playlists • Made for your $a1 vibe" else "פלייליסטים מיוטיוב • לפי הטעם שלך ב-$a1"
                } else {
                    if (isEn) "Ready YouTube Playlists" else "פלייליסטים מוכנים מיוטיוב"
                }
                return@withContext HomeFeedSection(
                    id = "inf_yt_$pageIndex",
                    title = secTitle,
                    subtitle = if (isEn) "Matched to your taste from YouTube Music" else "נבחר מ-YouTube Music לפי היסטוריית ההאזנה שלך",
                    remotePlaylists = remotePls
                )
            }
        }

        val aiConcepts = if (!isPod && !isBook && aiClient.hasValidConfig(settings)) {
            runCatching { requestAiInfiniteBatch(tasteArtists.take(10), likedTracks.take(12), existingTitles, isEn, settings) }.getOrNull()
        } else null

        val (sectionTitle, sectionSub, cardsPlan) = aiConcepts ?: buildLocalInfinitePlan(pageIndex, tasteArtists, homeFilter, isEn)

        val playlists = coroutineScope {
            cardsPlan.mapIndexed { idx, (title, query, styleTag) ->
                async {
                    val found = runCatching { searchTracks(query, settings, isPod, isBook, maxResults = 18) }
                        .getOrDefault(emptyList())
                        .filterNot { t -> excludedArtists.any { ex -> t.artist.contains(ex, true) } }
                    if (found.isEmpty()) null
                    else {
                        val mainArt = found.firstOrNull()?.artist.orEmpty()
                        val artPhoto = if (!isPod && !isBook && mainArt.isNotBlank()) runCatching { fetchArtistPhotoUrl(mainArt) }.getOrDefault("") else ""
                        Playlist(
                            id = when {
                                isBook -> "book_inf_${pageIndex}_$idx"
                                isPod -> "pod_inf_${pageIndex}_$idx"
                                else -> "ai_inf_${pageIndex}_$idx"
                            },
                            name = title,
                            creator = when {
                                isBook -> "Audiobooks"
                                isPod -> "Podcasts"
                                else -> "Sonify AI"
                            },
                            description = query,
                            coverUrl = found.first().artworkUrl,
                            coverMode = if (artPhoto.isNotBlank() && (styleTag == "ARTIST" || styleTag == "RADIO")) "ARTIST_ONLY" else "AUTO",
                            artistCoverUrl = artPhoto,
                            tracks = found,
                            isRemote = true,
                            aiSeedPrompt = query,
                            aiStyleTag = styleTag
                        )
                    }
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
            tracks = if (pageIndex % 2 == 0) bonusTracks else emptyList()
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
                    Triple(if (isEn) "AI & Future Tech" else "בינה מלאכותית ועתיד", "AI technology podcast", "PODCAST"),
                    Triple(if (isEn) "Psychology & Mind" else "פסיכולוגיה ומוח", "psychology mind podcast", "PODCAST")
                )),
                Triple(if (isEn) "Stories & Culture" else "סיפורים ותרבות", if (isEn) "Endless Feed" else "פיד אינסופי", listOf(
                    Triple(if (isEn) "World History" else "היסטוריה עולמית", "history stories podcast", "PODCAST"),
                    Triple(if (isEn) "Sports & Fitness" else "ספורט וכושר", "fitness sports podcast", "PODCAST"),
                    Triple(if (isEn) "Israeli Talks" else "שיחות וראיונות בישראל", "פודקאסט ראיונות ישראלי", "PODCAST")
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

    private suspend fun requestAiInfiniteBatch(
        topArtists: List<String>,
        likedTracks: List<Track>,
        existingTitles: Set<String>,
        isEn: Boolean,
        settings: AppSettings
    ): Triple<String, String, List<Triple<String, String, String>>>? {
        val likedStr = likedTracks.take(10).joinToString(", ") { "${it.artist} - ${it.title}" }
        val topStr = topArtists.take(10).joinToString(", ")
        val avoidList = existingTitles.take(15).joinToString(", ")
        val langHint = if (isEn) "English" else "Hebrew"
        val prompt = """
            You are Sonify's personal music taste AI.
            User's top artists (weighted by plays & likes): [$topStr].
            Recent liked songs: [$likedStr].
            Avoid repeating titles: [$avoidList].
            Design ONE fresh, highly personalized shelf in $langHint with 3 distinct playlist cards that match their taste and introduce 1 closely related discovery.
            Return ONLY valid JSON:
            {"sectionTitle": "...", "subtitle": "...", "cards": [{"title": "...", "searchQuery": "precise youtube music query", "styleTag": "SHORT_EN_TAG"}]}
        """.trimIndent()

        val (text, _) = aiClient.generateText(prompt, settings, expectJson = true, maxTokens = 700)
        if (text.isNullOrBlank()) return null
        return runCatching {
            val jsonBody = text.substringAfter("{").substringBeforeLast("}")
            val parsed = JSONObject("{$jsonBody}")
            val secTitle = parsed.optString("sectionTitle").takeIf { it.isNotBlank() } ?: return null
            val secSub = parsed.optString("subtitle", "${settings.aiProvider.labelHe} AI")
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
        }.getOrNull()
    }

    suspend fun buildAiPlaylist(
        config: AiPlaylistConfig,
        settings: AppSettings,
        likedContext: List<Track> = emptyList()
    ): Pair<String, List<Track>> = withContext(Dispatchers.IO) {
        val targetCount = if (config.autoCountByAi) inferAutoTrackCount(config.prompt) else config.targetCount.coerceIn(10, 50)

        if (aiClient.hasValidConfig(settings)) {
            val aiPlan = runCatching { requestAiSongList(config, likedContext, settings) }.getOrNull()
            if (aiPlan != null && aiPlan.second.isNotEmpty()) {
                val limit = if (config.autoCountByAi) aiPlan.second.size.coerceIn(12, 45) else targetCount
                val curated = resolveSongQueriesParallel(aiPlan.second.take(limit), settings)
                if (curated.size >= 5) return@withContext aiPlan.first to curated
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

    private fun inferAutoTrackCount(prompt: String): Int {
        val l = prompt.lowercase()
        val explicitNum = Regex("\\b([1-4]\\d|50|[6-9])\\b").find(l)?.value?.toIntOrNull()
        if (explicitNum != null) return explicitNum.coerceIn(8, 50)
        return when {
            l.contains("נסיעה") || l.contains("road") || l.contains("מסיבה") || l.contains("party") || l.contains("לימודים") || l.contains("study") -> 32
            l.contains("אימון") || l.contains("workout") || l.contains("gym") || l.contains("ריצה") -> 24
            l.contains("שינה") || l.contains("sleep") || l.contains("שבת") || l.contains("רגוע") -> 20
            else -> 22
        }
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
        likedContext.map { cleanArtistName(it.artist) }.distinct().take(2).forEach { fav ->
            queries.add("$fav$energySuffix")
        }
        return "AI • ${prompt.take(26)}" to queries.distinct()
    }

    private suspend fun requestAiSongList(config: AiPlaylistConfig, likedContext: List<Track>, settings: AppSettings): Pair<String, List<String>>? {
        val favs = likedContext.take(10).joinToString(", ") { "${it.artist} - ${it.title}" }
        val countInstruction = if (config.autoCountByAi) {
            "Choose the ideal number of songs yourself (between 14 and 35 songs) that best fits this vibe or activity."
        } else {
            "Build a ${config.targetCount}-song playlist."
        }
        val sysPrompt = """
            $countInstruction
            Request: "${config.prompt}".
            User's known favorite songs for taste calibration: [$favs].
            Preferences: language=${config.languagePref}, energy=${config.energyPref}, era=${config.eraPref}.
            Reply ONLY with valid JSON: {"title": "Short catchy title", "songs": ["Artist - Song", ...]}
        """.trimIndent()
        val (text, _) = aiClient.generateText(sysPrompt, settings, expectJson = true, maxTokens = 2048)
        if (text.isNullOrBlank()) return null
        return runCatching {
            val jsonBody = text.substringAfter("{").substringBeforeLast("}")
            val parsed = JSONObject("{$jsonBody}")
            val title = parsed.optString("title", "AI • ${config.prompt.take(24)}")
            val songsArr = parsed.optJSONArray("songs") ?: return null
            title to buildList { for (i in 0 until songsArr.length()) songsArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
        }.getOrNull()
    }

    // ---------- 5. Bulletproof Multi-Provider AI Lyrics Translation ----------
    suspend fun translateLyrics(
        lines: List<LyricLine>,
        targetLanguage: LyricsLanguage,
        settings: AppSettings
    ): Pair<List<LyricLine>?, String?> = withContext(Dispatchers.IO) {
        if (!aiClient.hasValidConfig(settings)) {
            return@withContext null to "הזן מפתח API בהגדרות ← בינה מלאכותית"
        }
        if (lines.isEmpty()) return@withContext null to "אין מילים לתרגום"

        val result = lines.toMutableList()
        val batchSize = 35
        var offset = 0
        var translatedCount = 0

        while (offset < lines.size) {
            val end = (offset + batchSize).coerceAtMost(lines.size)
            val slice = lines.subList(offset, end)
            val numberedInput = slice.mapIndexed { idx, l -> "$idx|${l.text.replace("\n", " ")}" }.joinToString("\n")
            val prompt = """
                Translate each numbered song lyric line naturally into ${targetLanguage.apiName}.
                Keep the exact line index and pipe format: INDEX|TRANSLATED_LINE
                Do not add explanations, markdown, or extra lines.
                
                $numberedInput
            """.trimIndent()

            val (rawOutput, err) = aiClient.generateText(prompt, settings, expectJson = false, maxTokens = 2200)
            if (rawOutput.isNullOrBlank()) {
                return@withContext null to (err ?: "שגיאה בתרגום המילים")
            }

            val cleanedLines = rawOutput
                .replace("```", "")
                .lines()
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val parsedMap = mutableMapOf<Int, String>()
            val numRegex = Regex("^(\\d+)\\s*[|:\\-]\\s*(.+)$")
            cleanedLines.forEach { l ->
                val m = numRegex.find(l)
                if (m != null) {
                    val idx = m.groupValues[1].toIntOrNull()
                    val txt = m.groupValues[2].trim()
                    if (idx != null && idx in slice.indices && txt.isNotBlank()) {
                        parsedMap[idx] = txt
                    }
                }
            }

            for (i in slice.indices) {
                val tr = parsedMap[i] ?: cleanedLines.getOrNull(i)?.substringAfter("|", "")?.trim()?.takeIf { it.isNotBlank() }
                if (!tr.isNullOrBlank()) {
                    result[offset + i] = result[offset + i].copy(translatedText = tr)
                    translatedCount++
                }
            }
            offset = end
        }

        if (translatedCount == 0) null to "לא הצלחנו לפענח את התרגום — נסה שוב"
        else result.toList() to null
    }

    // ---------- 6. 7-Source Parallel Synced Lyrics Engine + Offline Lyrics Cache ----------
    private fun JSONObject.safeStr(key: String): String {
        if (isNull(key)) return ""
        val v = optString(key, "").trim()
        return if (v.equals("null", true)) "" else v
    }

    private fun lyricsCacheFile(trackId: String): File {
        val safe = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(lyricsCacheDir, "$safe.json")
    }

    fun clearLyricsCache() {
        runCatching { lyricsCacheDir.listFiles()?.forEach { it.delete() } }
    }

    fun getCachedLyrics(trackId: String): List<LyricLine> {
        val f = lyricsCacheFile(trackId)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.optJSONObject(i)
                LyricLine(o.optLong("t", 0L), o.safeStr("x"))
            }.filter { it.text.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun saveLyricsToCache(trackId: String, lines: List<LyricLine>) {
        if (lines.isEmpty()) return
        runCatching {
            val arr = JSONArray()
            lines.forEach { l -> arr.put(JSONObject().apply { put("t", l.timeMs); put("x", l.text) }) }
            lyricsCacheFile(trackId).writeText(arr.toString())
        }
    }

    private fun cleanSongTitle(title: String): String {
        return title
            .replace(Regex("\\([^)]*(official|video|audio|lyric|prod|remaster|feat|ft\\.|קליפ|מילים|רשמי|from the series)[^)]*\\)", RegexOption.IGNORE_CASE), "")
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
            .replace(Regex("\\s*(?:feat\\.|ft\\.).*", RegexOption.IGNORE_CASE), "")
            .substringBefore(",")
            .substringBefore("&")
            .trim()
    }

    suspend fun fetchSyncedLyrics(
        track: Track,
        manualOverrideQuery: String? = null,
        offlineOnly: Boolean = false
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        if (manualOverrideQuery.isNullOrBlank()) {
            val cached = getCachedLyrics(track.id)
            if (cached.isNotEmpty()) return@withContext cached
        }
        if (offlineOnly) return@withContext emptyList()

        val cleanTitle = if (!manualOverrideQuery.isNullOrBlank()) manualOverrideQuery.trim() else cleanSongTitle(track.title)
        val titleNoParens = cleanTitle.replace(Regex("\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim()
        val cleanArtist = if (!manualOverrideQuery.isNullOrBlank()) "" else cleanArtistName(track.artist)
        val encArtist = URLEncoder.encode(cleanArtist, "UTF-8")
        val encTitle = URLEncoder.encode(cleanTitle, "UTF-8")
        val encTitleNoP = URLEncoder.encode(titleNoParens, "UTF-8")
        val lrcUa = "Sonify v11.1.0 ([https://sonify.link](https://sonify.link))"

        val searchQueries = listOfNotNull(
            "$cleanArtist $cleanTitle".trim().takeIf { it.isNotBlank() },
            "$cleanArtist $titleNoParens".trim().takeIf { it.isNotBlank() },
            titleNoParens.takeIf { it.length >= 2 && cleanArtist.isNotBlank() },
            cleanTitle.takeIf { it.length >= 2 && cleanArtist.isNotBlank() }
        ).distinct()

        // Wave 1: Race the 3 strongest synced-lyrics sources in parallel
        val (simpRes, lrcExactRes, lrcSearchRes) = coroutineScope {
            val simpJob = async {
                if (track.source == StreamSource.YOUTUBE && manualOverrideQuery.isNullOrBlank()) {
                    runCatching { fetchSimpMusicLyrics(track.id) }.getOrDefault(emptyList())
                } else emptyList()
            }
            val exactJob = async {
                if (cleanArtist.isBlank()) emptyList()
                else {
                    var best: List<LyricLine> = emptyList()
                    for (tEnc in listOf(encTitle, encTitleNoP).distinct()) {
                        val found = runCatching {
                            val url = "[https://lrclib.net/api/get?artist_name=$encArtist&track_name=$tEnc](https://lrclib.net/api/get?artist_name=$encArtist&track_name=$tEnc)"
                            client.newCall(Request.Builder().url(url).header("User-Agent", lrcUa).build()).execute().use { resp ->
                                if (!resp.isSuccessful) return@use emptyList()
                                parseLyricsJson(JSONObject(resp.body?.string() ?: return@use emptyList()))
                            }
                        }.getOrDefault(emptyList())
                        if (found.isNotEmpty()) { best = found; break }
                    }
                    best
                }
            }
            val searchJob = async {
                var best: List<LyricLine> = emptyList()
                for (sq in searchQueries.take(3)) {
                    val found = runCatching {
                        val encQ = URLEncoder.encode(sq, "UTF-8")
                        client.newCall(Request.Builder().url("[https://lrclib.net/api/search?q=$encQ](https://lrclib.net/api/search?q=$encQ)").header("User-Agent", lrcUa).build()).execute().use { resp ->
                            if (!resp.isSuccessful) return@use emptyList()
                            val arr = JSONArray(resp.body?.string() ?: return@use emptyList())
                            for (i in 0 until arr.length().coerceAtMost(8)) {
                                val obj = arr.optJSONObject(i) ?: continue
                                if (obj.safeStr("syncedLyrics").isNotBlank()) {
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
                    if (found.isNotEmpty()) { best = found; break }
                }
                best
            }
            Triple(simpJob.await(), exactJob.await(), searchJob.await())
        }

        val fastWinner = listOf(lrcExactRes, simpRes, lrcSearchRes).firstOrNull { it.isNotEmpty() }
        if (fastWinner != null) {
            saveLyricsToCache(track.id, fastWinner)
            return@withContext fastWinner
        }

        // Wave 2: YouTube Music Native Lyrics + NetEase + YouTube Captions in parallel
        val (ytmRes, netEaseRes, ytCapRes) = coroutineScope {
            val ytmJob = async {
                if (track.source == StreamSource.YOUTUBE && manualOverrideQuery.isNullOrBlank()) {
                    runCatching { fetchYouTubeMusicNativeLyrics(track.id) }.getOrDefault(emptyList())
                } else emptyList()
            }
            val neJob = async {
                val q = searchQueries.firstOrNull().orEmpty()
                if (q.isNotBlank()) runCatching { fetchNetEaseSyncedLyrics(q) }.getOrDefault(emptyList()) else emptyList()
            }
            val capJob = async {
                if (track.source == StreamSource.YOUTUBE && manualOverrideQuery.isNullOrBlank()) {
                    runCatching { fetchYouTubeCaptions(track.id) }.getOrDefault(emptyList())
                } else emptyList()
            }
            Triple(ytmJob.await(), neJob.await(), capJob.await())
        }

        val secondWinner = listOf(netEaseRes, ytmRes, ytCapRes).firstOrNull { it.isNotEmpty() }
        if (secondWinner != null) {
            saveLyricsToCache(track.id, secondWinner)
            return@withContext secondWinner
        }

        // Wave 3: Lyrics.ovh Fallback
        if (cleanArtist.isNotBlank() && titleNoParens.isNotBlank()) {
            val ovh = runCatching {
                val url = "[https://api.lyrics.ovh/v1/$encArtist/$encTitleNoP](https://api.lyrics.ovh/v1/$encArtist/$encTitleNoP)"
                probeClient.newCall(Request.Builder().url(url).header("User-Agent", lrcUa).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val plain = JSONObject(resp.body?.string() ?: return@use emptyList()).safeStr("lyrics")
                    plain.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("Paroles de", true) }
                        .mapIndexed { idx, line -> LyricLine(idx * 3200L, line) }
                }
            }.getOrDefault(emptyList())
            if (ovh.isNotEmpty()) {
                saveLyricsToCache(track.id, ovh)
                return@withContext ovh
            }
        }

        emptyList()
    }

    private fun fetchSimpMusicLyrics(videoId: String): List<LyricLine> {
        val req = Request.Builder()
            .url("[https://api-lyrics.simpmusic.org/v1/$videoId](https://api-lyrics.simpmusic.org/v1/$videoId)")
            .header("User-Agent", "Sonify/11.1")
            .header("Accept", "application/json")
            .build()
        return fastProxyClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val dataArr = root.optJSONArray("data")
            if (dataArr != null && dataArr.length() > 0) {
                for (i in 0 until dataArr.length()) {
                    val item = dataArr.optJSONObject(i) ?: continue
                    val synced = item.safeStr("syncedLyrics")
                    if (synced.isNotBlank()) {
                        val parsed = parseLrc(synced)
                        if (parsed.isNotEmpty()) return parsed
                    }
                    val plain = item.safeStr("plainLyrics")
                    if (plain.isNotBlank()) {
                        return plain.lines().map { it.trim() }.filter { it.isNotBlank() }
                            .mapIndexed { idx, l -> LyricLine(idx * 3400L, l) }
                    }
                }
            }
            emptyList()
        }
    }

    private fun fetchNetEaseSyncedLyrics(query: String): List<LyricLine> {
        val encQ = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "[https://music.163.com/api/search/get/web?s=$encQ&type=1&limit=4](https://music.163.com/api/search/get/web?s=$encQ&type=1&limit=4)"
        val sReq = Request.Builder()
            .url(searchUrl)
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Referer", "[https://music.163.com/](https://music.163.com/)")
            .build()
        val songId = fastProxyClient.newCall(sReq).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            JSONObject(resp.body?.string() ?: return emptyList())
                .optJSONObject("result")
                ?.optJSONArray("songs")
                ?.optJSONObject(0)
                ?.optLong("id", 0L) ?: 0L
        }
        if (songId <= 0L) return emptyList()

        val lyrReq = Request.Builder()
            .url("[https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1](https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1)")
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Referer", "[https://music.163.com/](https://music.163.com/)")
            .build()
        return fastProxyClient.newCall(lyrReq).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val lrc = JSONObject(resp.body?.string() ?: return emptyList())
                .optJSONObject("lrc")
                ?.safeStr("lyric")
                .orEmpty()
            if (lrc.isBlank()) return emptyList()
            val parsed = parseLrc(lrc)
            if (parsed.size >= 4) parsed else emptyList()
        }
    }

    private fun fetchYouTubeMusicNativeLyrics(videoId: String): List<LyricLine> {
        val nextBody = JSONObject().apply {
            put("videoId", videoId)
            put("context", ytMusicContext())
        }
        val nextReq = Request.Builder()
            .url("[https://music.youtube.com/youtubei/v1/next?prettyPrint=false](https://music.youtube.com/youtubei/v1/next?prettyPrint=false)")
            .post(nextBody.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
            .header("Origin", "[https://music.youtube.com](https://music.youtube.com)")
            .header("Referer", "[https://music.youtube.com/](https://music.youtube.com/)")
            .build()

        val lyricsBrowseId = client.newCall(nextReq).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val text = resp.body?.string().orEmpty()
            Regex("\"(MPLYt[^\"]+)\"").find(text)?.groupValues?.get(1)
        } ?: return emptyList()

        val browseRoot = fetchYtMusicBrowseJson(lyricsBrowseId) ?: return emptyList()
        val rawLyricsText = findFirstMusicDescriptionText(browseRoot)
        if (rawLyricsText.isBlank()) return emptyList()
        val lines = rawLyricsText.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("Source:", true) }
        if (lines.size < 3) return emptyList()
        return lines.mapIndexed { idx, line -> LyricLine(idx * 3200L, line) }
    }

    private fun findFirstMusicDescriptionText(node: Any?): String {
        if (node == null) return ""
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicDescriptionShelfRenderer")?.let { shelf ->
                    val txt = extractRunsText(shelf.optJSONObject("description"))
                    if (txt.length > 30) return txt
                }
                val keys = node.keys()
                while (keys.hasNext()) {
                    val found = findFirstMusicDescriptionText(node.opt(keys.next()))
                    if (found.isNotBlank()) return found
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    val found = findFirstMusicDescriptionText(node.opt(i))
                    if (found.isNotBlank()) return found
                }
            }
        }
        return ""
    }

    private fun fetchYouTubeCaptions(videoId: String): List<LyricLine> {
        val body = JSONObject().apply {
            put("videoId", videoId)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "ANDROID_VR"); put("clientVersion", "1.65.10")
                    put("androidSdkVersion", 32); put("hl", "he"); put("gl", "IL")
                })
            })
        }
        val req = Request.Builder()
            .url("[https://www.youtube.com/youtubei/v1/player?prettyPrint=false](https://www.youtube.com/youtubei/v1/player?prettyPrint=false)")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L) gzip")
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
                val base = ct.safeStr("baseUrl")
                val lang = ct.safeStr("languageCode")
                val kind = ct.safeStr("kind")
                if (base.startsWith("http")) {
                    if (kind.isBlank() && (lang.startsWith("he") || lang.startsWith("iw") || lang.startsWith("en"))) {
                        bestUrl = base; break
                    }
                    if (bestUrl == null) bestUrl = base
                }
            }
            bestUrl
        } ?: return emptyList()

        val finalCapUrl = if (captionUrl.contains("fmt=")) captionUrl else "$captionUrl&fmt=srv1"
        val xml = client.newCall(Request.Builder().url(finalCapUrl).header("User-Agent", "Mozilla/5.0").build()).execute().use { r ->
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
        val synced = json.safeStr("syncedLyrics")
        if (synced.isNotBlank()) {
            val parsed = parseLrc(synced)
            if (parsed.isNotEmpty()) return parsed
        }
        val plain = json.safeStr("plainLyrics")
        if (plain.isNotBlank()) {
            return plain.lines().filter { it.isNotBlank() }.mapIndexed { idx, line -> LyricLine(idx * 3500L, line.trim()) }
        }
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

    // ---------- 7. OpenSubsonic & Local Audio ----------
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
        val encUser = URLEncoder.encode(user, "UTF-8")
        return "u=$encUser&t=$digest&s=$salt&v=1.16.1&c=Sonify&f=json"
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
