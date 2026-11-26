package com.sonify.app

import android.content.ContentUris
import android.content.Context
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

class StreamRepository(private val context: Context) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(14, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    @Volatile
    private var cachedVisitorData: String? = null

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

    private val offlineDir: File
        get() = File(context.filesDir, "offline_tracks").apply { if (!exists()) mkdirs() }

    fun getOfflineFile(trackId: String): File {
        val safe = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(offlineDir, "$safe.media")
    }

    fun getOfflineTrackIds(): Set<String> {
        return offlineDir.listFiles()
            ?.filter { it.isFile && it.length() > 1024 }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
    }

    private fun getOrFetchVisitorData(): String {
        cachedVisitorData?.let { return it }
        val fetched = runCatching {
            val body = JSONObject().apply {
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", "WEB")
                        put("clientVersion", "2.20250219.01.00")
                        put("hl", "he")
                        put("gl", "IL")
                    })
                })
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/visitor_id?prettyPrint=false")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use ""
                JSONObject(resp.body?.string() ?: "").optJSONObject("responseContext")?.optString("visitorData").orEmpty()
            }
        }.getOrDefault("")
        if (fetched.isNotBlank()) cachedVisitorData = fetched
        return fetched
    }

    // ---------- 1. Search Songs (YouTube Music Songs-Only First!) ----------
    suspend fun searchTracks(
        query: String,
        settings: AppSettings,
        podcastsOnly: Boolean = false
    ): List<Track> = withContext(Dispatchers.IO) {
        val cleanQuery = if (podcastsOnly && !query.contains("podcast", true) && !query.contains("פודקאסט")) {
            "$query podcast"
        } else query

        when (settings.streamSource) {
            StreamSource.YOUTUBE -> searchYouTubeSongsStrict(cleanQuery, podcastsOnly)
            StreamSource.OPENSUBSONIC -> searchSubsonic(cleanQuery, settings)
            StreamSource.LOCAL -> searchLocalAudio(cleanQuery)
        }
    }

    private fun searchYouTubeSongsStrict(query: String, isPodcast: Boolean): List<Track> {
        val raw = if (!isPodcast) {
            runCatching { searchYouTubeMusicSongs(query) }.getOrDefault(emptyList())
                .ifEmpty { runCatching { searchYouTubeWeb(query, false) }.getOrDefault(emptyList()) }
                .ifEmpty { searchPiped(query, false) }
                .ifEmpty { searchInvidious(query, false) }
        } else {
            runCatching { searchYouTubeWeb(query, true) }.getOrDefault(emptyList())
                .ifEmpty { searchPiped(query, true) }
        }

        if (isPodcast) return raw.distinctBy { it.id }.take(20)
        return raw.filter { isLikelySingleSong(it) }.distinctBy { it.id }.take(20)
    }

    private fun isLikelySingleSong(t: Track): Boolean {
        if (t.durationMs > 570_000L || (t.durationMs in 1L..35_000L)) return false
        val lower = t.title.lowercase()
        val bannedKeywords = listOf(
            "1 hour", "2 hour", "3 hour", "hour playlist", "hour mix",
            "full album", "nonstop", "non-stop", "compilation", "megamix",
            "best songs of", "top 50", "top 100", "playlist 202", "music 2024",
            "music 2025", "music 2026", "פלייליסט", "מיקס", "מחרוזת", "שעה של", "שעתיים"
        )
        return bannedKeywords.none { lower.contains(it) }
    }

    private fun searchYouTubeMusicSongs(query: String): List<Track> {
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
            val root = JSONObject(resp.body?.string() ?: return emptyList())
            val out = mutableListOf<Track>()
            extractYtMusicSongs(root, out)
            out.distinctBy { it.id }
        }
    }

    // ---------- 2. Search REAL Playlists & Browse Individual Songs ----------
    suspend fun searchPlaylists(query: String): List<RemotePlaylist> = withContext(Dispatchers.IO) {
        val bodyJson = JSONObject().apply {
            put("query", query)
            put("params", "EgeKAQQoAEABagoQAxAEEAkQChAF")
            put("context", ytMusicContext())
        }
        val req = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()

        val ytMusicRes = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val root = JSONObject(resp.body?.string() ?: return@use emptyList())
                val out = mutableListOf<RemotePlaylist>()
                extractYtMusicPlaylists(root, out)
                out.distinctBy { it.id }
            }
        }.getOrDefault(emptyList())

        if (ytMusicRes.isNotEmpty()) return@withContext ytMusicRes

        val encoded = URLEncoder.encode(query, "UTF-8")
        for (base in pipedInstances) {
            val res = runCatching {
                val pReq = Request.Builder().url("$base/search?q=$encoded&filter=music_playlists").build()
                client.newCall(pReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val items = JSONObject(resp.body?.string() ?: return@use emptyList()).optJSONArray("items") ?: return@use emptyList()
                    buildList {
                        for (i in 0 until items.length()) {
                            val o = items.optJSONObject(i) ?: continue
                            val id = o.optString("url").substringAfter("list=", "")
                            if (id.isBlank()) continue
                            add(
                                RemotePlaylist(
                                    id = if (id.startsWith("VL")) id else "VL$id",
                                    title = o.optString("name", "פלייליסט"),
                                    subtitle = o.optString("uploaderName", "YouTube Music"),
                                    artworkUrl = upgradeArtworkUrl(o.optString("thumbnail"))
                                )
                            )
                        }
                    }
                }
            }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return@withContext res
        }
        emptyList()
    }

    suspend fun fetchPlaylistTracks(playlistId: String): List<Track> = withContext(Dispatchers.IO) {
        val browseId = if (playlistId.startsWith("VL") || playlistId.startsWith("MPRE")) playlistId else "VL$playlistId"
        val rawId = playlistId.removePrefix("VL")

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

        val ytMusicTracks = runCatching {
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val root = JSONObject(resp.body?.string() ?: return@use emptyList())
                val out = mutableListOf<Track>()
                extractYtMusicSongs(root, out)
                out.filter { isLikelySingleSong(it) }.distinctBy { it.id }
            }
        }.getOrDefault(emptyList())

        if (ytMusicTracks.isNotEmpty()) return@withContext ytMusicTracks

        for (base in pipedInstances) {
            val res = runCatching {
                val pReq = Request.Builder().url("$base/playlists/$rawId").build()
                client.newCall(pReq).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val arr = JSONObject(resp.body?.string() ?: return@use emptyList()).optJSONArray("relatedStreams") ?: return@use emptyList()
                    buildList {
                        for (i in 0 until arr.length()) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString("url").substringAfter("v=", "").substringBefore("&")
                            if (id.length != 11) continue
                            add(
                                Track(
                                    id = id,
                                    title = o.optString("title", "ללא שם"),
                                    artist = o.optString("uploaderName", "אמן"),
                                    artworkUrl = upgradeArtworkUrl(o.optString("thumbnail").ifBlank { "https://i.ytimg.com/vi/$id/sddefault.jpg" }),
                                    durationMs = o.optLong("duration", 0L) * 1000L,
                                    source = StreamSource.YOUTUBE
                                )
                            )
                        }
                    }.filter { isLikelySingleSong(it) }
                }
            }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return@withContext res
        }
        emptyList()
    }

    private fun ytMusicContext(): JSONObject = JSONObject().apply {
        put("client", JSONObject().apply {
            put("clientName", "WEB_REMIX")
            put("clientVersion", "1.20250219.01.00")
            put("hl", "he")
            put("gl", "IL")
        })
    }

    private fun extractYtMusicSongs(node: Any?, out: MutableList<Track>) {
        if (out.size >= 35 || node == null) return
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
                        val title = flexCols?.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.let { extractRunsText(it) }.orEmpty()

                        val metaRuns = flexCols?.optJSONObject(1)
                            ?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                            ?.optJSONObject("text")
                            ?.optJSONArray("runs")

                        val parts = mutableListOf<String>()
                        if (metaRuns != null) {
                            for (i in 0 until metaRuns.length()) {
                                val t = metaRuns.optJSONObject(i)?.optString("text")?.trim().orEmpty()
                                if (t.isNotBlank() && t != "•" && t != "·" && t != "שיר" && !t.equals("Song", true) && !t.equals("Video", true)) {
                                    parts.add(t)
                                }
                            }
                        }

                        var durationMs = 0L
                        val fixedDur = item.optJSONArray("fixedColumns")?.optJSONObject(0)
                            ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                            ?.optJSONObject("text")
                            ?.let { extractRunsText(it) }.orEmpty()
                        if (fixedDur.contains(":")) durationMs = parseDurationText(fixedDur)
                        if (durationMs == 0L) {
                            val durPart = parts.lastOrNull { it.matches(Regex("\\d{1,2}:\\d{2}(:\\d{2})?")) }
                            if (durPart != null) {
                                durationMs = parseDurationText(durPart)
                                parts.remove(durPart)
                            }
                        }

                        val artist = parts.firstOrNull { !it.contains("צפיות") && !it.contains("plays", true) && !it.contains("views", true) } ?: "אמן"
                        val album = parts.getOrNull(1)?.takeIf { !it.contains("צפיות") && !it.contains("plays", true) } ?: ""

                        val thumbs = item.optJSONObject("thumbnail")
                            ?.optJSONObject("musicThumbnailRenderer")
                            ?.optJSONObject("thumbnail")
                            ?.optJSONArray("thumbnails")
                        val rawThumb = if (thumbs != null && thumbs.length() > 0) {
                            thumbs.optJSONObject(thumbs.length() - 1)?.optString("url").orEmpty()
                        } else ""
                        val art = upgradeArtworkUrl(rawThumb.ifBlank { "https://i.ytimg.com/vi/$videoId/sddefault.jpg" })

                        if (title.isNotBlank()) {
                            out.add(Track(id = videoId, title = title, artist = artist, album = album, artworkUrl = art, durationMs = durationMs, source = StreamSource.YOUTUBE))
                        }
                    }
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) extractYtMusicSongs(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) extractYtMusicSongs(node.opt(i), out)
            }
        }
    }

    private fun extractYtMusicPlaylists(node: Any?, out: MutableList<RemotePlaylist>) {
        if (out.size >= 20 || node == null) return
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
                        if (title.isNotBlank()) {
                            out.add(RemotePlaylist(id = browseId, title = title, subtitle = subtitle.ifBlank { "פלייליסט" }, artworkUrl = upgradeArtworkUrl(rawThumb)))
                        }
                    }
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) extractYtMusicPlaylists(node.opt(keys.next()), out)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) extractYtMusicPlaylists(node.opt(i), out)
            }
        }
    }

    private fun upgradeArtworkUrl(url: String): String {
        if (url.isBlank()) return ""
        if (url.contains("lh3.googleusercontent.com")) return url.replace(Regex("=w\\d+-h\\d+.*"), "=w544-h544-l90-rj")
        return url
    }

    private fun searchYouTubeWeb(query: String, isPodcast: Boolean): List<Track> {
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
            extractVideoRenderers(JSONObject(resp.body?.string() ?: return emptyList()), out, isPodcast)
            out.distinctBy { it.id }
        }
    }

    private fun searchPiped(query: String, isPodcast: Boolean): List<Track> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (base in pipedInstances) {
            val res = runCatching {
                val req = Request.Builder().url("$base/search?q=$encoded&filter=music_songs").header("User-Agent", "Mozilla/5.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val items = JSONObject(resp.body?.string() ?: return@use emptyList()).optJSONArray("items") ?: return@use emptyList()
                    buildList {
                        for (i in 0 until items.length()) {
                            val o = items.optJSONObject(i) ?: continue
                            val id = o.optString("url").substringAfter("v=", "").substringBefore("&")
                            if (id.length != 11) continue
                            add(
                                Track(
                                    id = id, title = o.optString("title", "ללא שם"), artist = o.optString("uploaderName", "אמן"),
                                    artworkUrl = upgradeArtworkUrl(o.optString("thumbnail").ifBlank { "https://i.ytimg.com/vi/$id/sddefault.jpg" }),
                                    durationMs = o.optLong("duration", 0L) * 1000L, source = StreamSource.YOUTUBE, isPodcast = isPodcast
                                )
                            )
                        }
                    }
                }
            }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    private fun searchInvidious(query: String, isPodcast: Boolean): List<Track> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (base in invidiousInstances) {
            val res = runCatching {
                val req = Request.Builder().url("$base/api/v1/search?q=$encoded&type=video").header("User-Agent", "Mozilla/5.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val arr = JSONArray(resp.body?.string() ?: return@use emptyList())
                    buildList {
                        for (i in 0 until arr.length().coerceAtMost(20)) {
                            val o = arr.optJSONObject(i) ?: continue
                            val id = o.optString("videoId")
                            if (id.length != 11) continue
                            add(
                                Track(
                                    id = id, title = o.optString("title", "ללא שם"), artist = o.optString("author", "YouTube"),
                                    artworkUrl = "https://i.ytimg.com/vi/$id/sddefault.jpg",
                                    durationMs = o.optLong("lengthSeconds", 0L) * 1000L, source = StreamSource.YOUTUBE, isPodcast = isPodcast
                                )
                            )
                        }
                    }
                }
            }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    private fun extractVideoRenderers(node: Any?, out: MutableList<Track>, isPodcast: Boolean) {
        if (out.size >= 30 || node == null) return
        when (node) {
            is JSONObject -> {
                if (node.has("videoRenderer")) {
                    val v = node.optJSONObject("videoRenderer")
                    val id = v?.optString("videoId").orEmpty()
                    if (id.length == 11) {
                        val title = extractRunsText(v?.optJSONObject("title"))
                        val artist = extractRunsText(v?.optJSONObject("ownerText")).ifBlank { extractRunsText(v?.optJSONObject("shortBylineText")) }.ifBlank { "YouTube" }
                        val durationText = v?.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
                        if (title.isNotBlank()) {
                            out.add(Track(id = id, title = title, artist = artist, artworkUrl = "https://i.ytimg.com/vi/$id/sddefault.jpg", durationMs = parseDurationText(durationText), source = StreamSource.YOUTUBE, isPodcast = isPodcast))
                        }
                    }
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) extractVideoRenderers(node.opt(keys.next()), out, isPodcast)
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) extractVideoRenderers(node.opt(i), out, isPodcast)
            }
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

    // ---------- 3. Strict Non-Wrapping Verified Stream Resolution ----------
    suspend fun resolveStreams(track: Track, settings: AppSettings, attemptIndex: Int = 0): ResolvedStreams? = withContext(Dispatchers.IO) {
        val offlineFile = getOfflineFile(track.id)
        if (offlineFile.exists() && offlineFile.length() > 1024) {
            return@withContext ResolvedStreams(audioUrl = offlineFile.toURI().toString(), isLossless = track.source != StreamSource.YOUTUBE, isOffline = true, codecLabel = "OFFLINE")
        }

        if (track.source == StreamSource.LOCAL && !track.localUri.isNullOrBlank()) {
            val isFlac = track.title.endsWith(".flac", true) || track.album.contains("FLAC", true)
            return@withContext ResolvedStreams(audioUrl = track.localUri, isLossless = isFlac, codecLabel = if (isFlac) "FLAC Lossless" else "LOCAL")
        }

        if (track.source == StreamSource.OPENSUBSONIC && settings.subsonicUrl.isNotBlank()) {
            val auth = buildSubsonicAuth(settings.subsonicUser, settings.subsonicPass)
            val formatParam = if (settings.quality == AudioQuality.LOSSLESS) "format=raw" else "maxBitRate=${settings.quality.kbps}"
            val base = settings.subsonicUrl.trimEnd('/')
            val url = "$base/rest/stream.view?id=${URLEncoder.encode(track.id, "UTF-8")}&$formatParam&$auth"
            return@withContext ResolvedStreams(audioUrl = url, isLossless = settings.quality == AudioQuality.LOSSLESS, codecLabel = if (settings.quality == AudioQuality.LOSSLESS) "FLAC Lossless" else "${settings.quality.kbps}kbps")
        }

        resolveYouTubeNonWrapping(track.id, settings.quality, attemptIndex)
    }

    private fun verifyMediaStreamDeep(url: String, userAgent: String): Boolean {
        return runCatching {
            if (url.contains(".m3u8", true) || url.contains("manifest/hls", true)) {
                // Verify HLS manifest AND at least one inner media segment!
                val req = Request.Builder().url(url).header("User-Agent", userAgent).build()
                val text = probeClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return false
                    resp.body?.string().orEmpty()
                }
                val firstSubUrl = text.lines().firstOrNull { it.startsWith("http") } ?: return false
                return verifyMediaStreamDeep(firstSubUrl, userAgent)
            }

            // Direct media stream check: request 64KB chunk and verify non-HTML binary bytes
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", userAgent)
                .header("Range", "bytes=0-65535")
                .build()
            probeClient.newCall(req).execute().use { resp ->
                if (resp.code !in listOf(200, 206)) return false
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.contains("text/html") || ct.contains("application/json")) return false
                val buf = ByteArray(2048)
                val read = resp.body?.byteStream()?.read(buf) ?: 0
                read > 256
            }
        }.getOrDefault(false)
    }

    private fun resolveYouTubeNonWrapping(videoId: String, quality: AudioQuality, startAttempt: Int): ResolvedStreams? {
        val visitorData = getOrFetchVisitorData()
        val strategies: List<() -> ResolvedStreams?> = listOf(
            { fetchInnerTubeClient(videoId, "ANDROID_VR", quality, visitorData, preferMuxed = false) },
            { fetchInnerTubeClient(videoId, "ANDROID_VR", quality, visitorData, preferMuxed = true) },
            { fetchInnerTubeClient(videoId, "IOS", quality, visitorData, preferMuxed = false) },
            { fetchInnerTubeClient(videoId, "TV_EMBEDDED", quality, visitorData, preferMuxed = false) },
            { fetchPipedStream(videoId, quality) },
            { fetchInvidiousProxiedStream(videoId) }
        )

        // NEVER wrap around to already-failed strategies!
        for (i in startAttempt until strategies.size) {
            val res = runCatching { strategies[i]() }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun fetchInnerTubeClient(
        videoId: String,
        clientType: String,
        quality: AudioQuality,
        visitorData: String,
        preferMuxed: Boolean
    ): ResolvedStreams? {
        val (clientObj, ua, idHeader, verHeader) = when (clientType) {
            "ANDROID_VR" -> Quad(
                JSONObject().apply {
                    put("clientName", "ANDROID_VR"); put("clientVersion", "1.60.19")
                    put("androidSdkVersion", 32); put("osName", "Android"); put("osVersion", "12L")
                    put("deviceMake", "Oculus"); put("deviceModel", "Quest 3")
                    put("hl", "he"); put("gl", "IL")
                    if (visitorData.isNotBlank()) put("visitorData", visitorData)
                },
                "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip",
                "28", "1.60.19"
            )
            "IOS" -> Quad(
                JSONObject().apply {
                    put("clientName", "IOS"); put("clientVersion", "20.10.4")
                    put("deviceMake", "Apple"); put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone"); put("osVersion", "18.3.2.22D82")
                    put("hl", "he"); put("gl", "IL")
                    if (visitorData.isNotBlank()) put("visitorData", visitorData)
                },
                "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)",
                "5", "20.10.4"
            )
            else -> Quad(
                JSONObject().apply {
                    put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER"); put("clientVersion", "2.0")
                    put("hl", "he"); put("gl", "IL")
                    if (visitorData.isNotBlank()) put("visitorData", visitorData)
                },
                "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
                "85", "2.0"
            )
        }

        val contextObj = JSONObject().apply {
            put("client", clientObj)
            if (clientType == "TV_EMBEDDED") put("thirdParty", JSONObject().apply { put("embedUrl", "https://www.youtube.com") })
        }
        val bodyJson = JSONObject().apply {
            put("videoId", videoId); put("contentCheckOk", true); put("racyCheckOk", true); put("context", contextObj)
        }
        val reqBuilder = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", ua)
            .header("X-YouTube-Client-Name", idHeader)
            .header("X-YouTube-Client-Version", verHeader)
        if (visitorData.isNotBlank()) reqBuilder.header("X-Goog-Visitor-Id", visitorData)

        return client.newCall(reqBuilder.build()).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string() ?: return null)
            if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
            val sd = root.optJSONObject("streamingData") ?: return null
            val hls = sd.optString("hlsManifestUrl").takeIf { it.startsWith("http") }

            val audioCandidates = mutableListOf<Pair<Int, String>>()
            val videoCandidates = mutableListOf<Pair<Int, String>>()
            var muxedVideoUrl: String? = null

            val formats = sd.optJSONArray("formats")
            if (formats != null) {
                for (i in 0 until formats.length()) {
                    val f = formats.optJSONObject(i) ?: continue
                    val u = f.optString("url")
                    if (u.startsWith("http")) {
                        muxedVideoUrl = u
                        audioCandidates.add((f.optInt("bitrate", 128000) / 1000) to u)
                    }
                }
            }

            val adaptive = sd.optJSONArray("adaptiveFormats")
            if (adaptive != null) {
                for (i in 0 until adaptive.length()) {
                    val f = adaptive.optJSONObject(i) ?: continue
                    val mime = f.optString("mimeType")
                    val u = f.optString("url")
                    if (!u.startsWith("http")) continue
                    if (mime.startsWith("audio/")) {
                        audioCandidates.add((f.optInt("bitrate", 128000) / 1000) to u)
                    } else if (mime.startsWith("video/mp4")) {
                        val h = f.optInt("height", 360)
                        if (h in 240..720) videoCandidates.add(h to u)
                    }
                }
            }

            val preferredAudio = pickAudioByQuality(audioCandidates, quality)
            val candidateList = if (preferMuxed) {
                listOfNotNull(muxedVideoUrl, preferredAudio, hls)
            } else {
                listOfNotNull(preferredAudio, muxedVideoUrl, hls)
            }.distinct()

            val verifiedAudio = candidateList.firstOrNull { verifyMediaStreamDeep(it, ua) } ?: return null
            val bestVideoOnly = videoCandidates.sortedBy { it.first }.lastOrNull { it.first <= 720 }?.second
            val bestVideoOrHls = muxedVideoUrl ?: hls

            ResolvedStreams(
                audioUrl = verifiedAudio,
                videoUrl = bestVideoOrHls,
                videoOnlyUrl = bestVideoOnly,
                userAgent = ua,
                codecLabel = when (quality) {
                    AudioQuality.LOW -> "64kbps"
                    AudioQuality.NORMAL -> "128kbps"
                    else -> "256kbps Opus/AAC"
                }
            )
        }
    }

    private fun fetchPipedStream(videoId: String, quality: AudioQuality): ResolvedStreams? {
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        for (base in pipedInstances) {
            val res = runCatching {
                val req = Request.Builder().url("$base/streams/$videoId").header("User-Agent", ua).build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val json = JSONObject(resp.body?.string() ?: return@use null)
                    val audioArr = json.optJSONArray("audioStreams")
                    val videoArr = json.optJSONArray("videoStreams")
                    val hls = json.optString("hls").takeIf { it.startsWith("http") }

                    val audioStreams = mutableListOf<Pair<Int, String>>()
                    if (audioArr != null) {
                        for (i in 0 until audioArr.length()) {
                            val a = audioArr.optJSONObject(i) ?: continue
                            val u = a.optString("url")
                            if (u.startsWith("http")) audioStreams.add((a.optInt("bitrate", 128000) / 1000) to u)
                        }
                    }
                    val picked = pickAudioByQuality(audioStreams, quality)
                    val validAudio = listOfNotNull(picked, hls).firstOrNull { verifyMediaStreamDeep(it, ua) } ?: return@use null
                    var muxedVideo: String? = hls
                    var videoOnly: String? = null
                    if (videoArr != null) {
                        for (i in 0 until videoArr.length()) {
                            val v = videoArr.optJSONObject(i) ?: continue
                            val u = v.optString("url")
                            if (!u.startsWith("http")) continue
                            if (!v.optBoolean("videoOnly", true)) { muxedVideo = u; break }
                            else if (videoOnly == null) videoOnly = u
                        }
                    }
                    ResolvedStreams(audioUrl = validAudio, videoUrl = muxedVideo, videoOnlyUrl = videoOnly, userAgent = ua, codecLabel = "Piped Proxy 256kbps")
                }
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun fetchInvidiousProxiedStream(videoId: String): ResolvedStreams? {
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        for (base in invidiousInstances) {
            // &local=true proxies the audio through Invidious server, bypassing cellular CGNAT 403!
            val audioProxy = "$base/latest_version?id=$videoId&itag=140&local=true"
            val videoProxy = "$base/latest_version?id=$videoId&itag=18&local=true"
            if (verifyMediaStreamDeep(audioProxy, ua)) {
                return ResolvedStreams(audioUrl = audioProxy, videoUrl = videoProxy, userAgent = ua, codecLabel = "Proxy AAC")
            }
            if (verifyMediaStreamDeep(videoProxy, ua)) {
                return ResolvedStreams(audioUrl = videoProxy, videoUrl = videoProxy, userAgent = ua, codecLabel = "Proxy MP4")
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
            AudioQuality.NORMAL -> sorted.minByOrNull { kotlin.math.abs(it.first - 128) }?.second
            AudioQuality.HIGH, AudioQuality.LOSSLESS -> sorted.last().second
        }
    }

    // ---------- 4. Smart AI Playlist Builder ----------
    suspend fun buildAiPlaylist(prompt: String, settings: AppSettings): Pair<String, List<Track>> = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()

        if (settings.geminiApiKey.isNotBlank()) {
            val geminiPlan = runCatching { requestGeminiSongList(cleanPrompt, settings.geminiApiKey) }.getOrNull()
            if (geminiPlan != null && geminiPlan.second.isNotEmpty()) {
                val curated = mutableListOf<Track>()
                for (songQuery in geminiPlan.second.take(12)) {
                    val found = searchTracks(songQuery, settings, podcastsOnly = false)
                    found.firstOrNull { cand -> curated.none { it.id == cand.id } }?.let { curated.add(it) }
                }
                if (curated.isNotEmpty()) return@withContext geminiPlan.first to curated
            }
        }

        val (playlistTitle, queries) = expandPromptIntoSmartQueries(cleanPrompt)
        val curated = mutableListOf<Track>()
        val seenTitles = mutableSetOf<String>()
        for (q in queries) {
            val results = searchTracks(q, settings, podcastsOnly = false)
            for (t in results) {
                val normTitle = t.title.lowercase().replace(Regex("[^a-z0-9א-ת]"), "").take(18)
                if (curated.none { it.id == t.id } && seenTitles.add(normTitle)) {
                    curated.add(t)
                    if (curated.size >= 15) break
                }
            }
            if (curated.size >= 15) break
        }
        playlistTitle to curated.shuffled()
    }

    private fun expandPromptIntoSmartQueries(prompt: String): Pair<String, List<String>> {
        val lower = prompt.lowercase()
        val isHebrewPrompt = prompt.any { it in '\u0590'..'\u05FF' }
        val wantsEnglish = lower.contains("אנגלית") || lower.contains("english") || lower.contains("לועזי")
        val wantsHebrew = lower.contains("עברית") || lower.contains("ישראלי") || lower.contains("hebrew") || (isHebrewPrompt && !wantsEnglish)

        val queries = mutableListOf<String>()
        when {
            lower.contains("אימון") || lower.contains("כושר") || lower.contains("ריצה") || lower.contains("workout") || lower.contains("gym") -> {
                if (wantsHebrew) queries += listOf("נועה קירל", "טונה", "סטטיק", "אודיה")
                queries += listOf("Eminem Lose Yourself", "Kanye West Stronger", "Travis Scott FEIN", "The Weeknd Blinding Lights", "Imagine Dragons Believer")
            }
            lower.contains("לילה") || lower.contains("רגוע") || lower.contains("שינה") || lower.contains("צ'יל") || lower.contains("chill") -> {
                if (wantsHebrew) queries += listOf("עידן רייכל", "ישי ריבו", "נתן גושן")
                queries += listOf("Billie Eilish Birds of a Feather", "Frank Ocean Pink White", "The Weeknd Die For You", "Lana Del Rey Cinnamon Girl")
            }
            lower.contains("מסיבה") || lower.contains("קלאב") || lower.contains("ריקוד") || lower.contains("party") || lower.contains("dance") -> {
                if (wantsHebrew) queries += listOf("עומר אדם", "עדן בן זקן", "אנה זק")
                queries += listOf("Dua Lipa Houdini", "David Guetta Titanium", "Calvin Harris Summer", "Pitbull Give Me Everything")
            }
            lower.contains("עצוב") || lower.contains("שבור") || lower.contains("בלד") || lower.contains("sad") -> {
                if (wantsHebrew) queries += listOf("אביב גפן", "חנן בן ארי", "עדן חסון")
                queries += listOf("Adele Someone Like You", "Lewis Capaldi Someone You Loved", "Olivia Rodrigo Drivers License")
            }
            lower.contains("רוק") || lower.contains("rock") || lower.contains("מטאל") -> {
                if (wantsHebrew) queries += listOf("היהודים", "משינה", "מרסדס בנד")
                queries += listOf("Linkin Park Numb", "Arctic Monkeys Do I Wanna Know", "Queen Bohemian Rhapsody", "Nirvana Smells Like Teen Spirit")
            }
            lower.contains("ראפ") || lower.contains("היפ") || lower.contains("rap") || lower.contains("hip hop") -> {
                if (wantsHebrew) queries += listOf("רביד פלוטניק", "טונה", "ג'ימבו ג'יי", "שחר סאול")
                queries += listOf("Kendrick Lamar Not Like Us", "Drake God's Plan", "Travis Scott Sicko Mode", "21 Savage Redrum")
            }
            lower.contains("מזרחית") || lower.contains("ים תיכוני") -> {
                queries += listOf("עומר אדם", "עדן חסון", "פאר טסי", "אושר כהן", "אייל גולן")
            }
            else -> queries += listOf(prompt, "$prompt hits", "$prompt top songs")
        }
        return "AI • ${prompt.take(26)}" to queries.distinct()
    }

    private fun requestGeminiSongList(prompt: String, apiKey: String): Pair<String, List<String>>? {
        val sysPrompt = """
            Build a 12-song music playlist for this user request: "$prompt".
            Reply ONLY with valid JSON: {"title": "Short catchy playlist title in the user's language", "songs": ["Artist - Song Title", ...]}
        """.trimIndent()
        val payload = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysPrompt)))))
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
            val title = parsed.optString("title", "AI • ${prompt.take(24)}")
            val songsArr = parsed.optJSONArray("songs") ?: return null
            val songs = buildList { for (i in 0 until songsArr.length()) songsArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) } }
            title to songs
        }
    }

    // ---------- OpenSubsonic / Local / Lyrics / Offline ----------
    suspend fun testSubsonicConnection(settings: AppSettings): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (settings.subsonicUrl.isBlank()) return@withContext false to "יש להזין כתובת שרת"
        runCatching {
            val base = settings.subsonicUrl.trimEnd('/')
            val auth = buildSubsonicAuth(settings.subsonicUser, settings.subsonicPass)
            client.newCall(Request.Builder().url("$base/rest/ping.view?$auth").build()).execute().use { resp ->
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
            client.newCall(Request.Builder().url("$base/rest/search3.view?query=$q&songCount=30&$auth").build()).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val songs = JSONObject(resp.body?.string() ?: return emptyList()).optJSONObject("subsonic-response")?.optJSONObject("searchResult3")?.optJSONArray("song") ?: return emptyList()
                buildList {
                    for (i in 0 until songs.length()) {
                        val s = songs.optJSONObject(i) ?: continue
                        val coverId = s.optString("coverArt")
                        add(
                            Track(
                                id = s.optString("id"), title = s.optString("title", "ללא שם"), artist = s.optString("artist", "אמן"),
                                album = s.optString("album", ""), artworkUrl = if (coverId.isNotBlank()) "$base/rest/getCoverArt.view?id=$coverId&$auth" else "",
                                durationMs = s.optLong("duration", 0L) * 1000L, source = StreamSource.OPENSUBSONIC
                            )
                        )
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
            val projection = arrayOf(
                MediaStore.Audio.Media._ID, MediaStore.Audio.Media.TITLE, MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM, MediaStore.Audio.Media.DURATION, MediaStore.Audio.Media.ALBUM_ID
            )
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, projection,
                "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC"
            )?.use { c ->
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
                    list.add(
                        Track(
                            id = "local_$id", title = title, artist = artist, album = album,
                            artworkUrl = ContentUris.withAppendedId(android.net.Uri.parse("content://media/external/audio/albumart"), c.getLong(albIdCol)).toString(),
                            durationMs = c.getLong(durCol), source = StreamSource.LOCAL,
                            localUri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                        )
                    )
                }
            }
        }
        return list
    }

    suspend fun fetchSyncedLyrics(artist: String, title: String): List<LyricLine> = withContext(Dispatchers.IO) {
        val cleanTitle = title.replace(Regex("\\(.*?\\)|\\[.*?]"), "").replace("Official Video", "", true).replace("Official Audio", "", true).trim()
        val cleanArtist = artist.replace("- Topic", "", true).replace("VEVO", "", true).trim()

        val exact = runCatching {
            val url = "https://lrclib.net/api/get?artist_name=${URLEncoder.encode(cleanArtist, "UTF-8")}&track_name=${URLEncoder.encode(cleanTitle, "UTF-8")}"
            client.newCall(Request.Builder().url(url).header("User-Agent", "Sonify/4.0").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                parseLyricsJson(JSONObject(resp.body?.string() ?: return@use emptyList()))
            }
        }.getOrDefault(emptyList())
        if (exact.isNotEmpty()) return@withContext exact

        runCatching {
            val q = URLEncoder.encode("$cleanArtist $cleanTitle".trim(), "UTF-8")
            client.newCall(Request.Builder().url("https://lrclib.net/api/search?q=$q").header("User-Agent", "Sonify/4.0").build()).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val arr = JSONArray(resp.body?.string() ?: return@use emptyList())
                for (i in 0 until arr.length().coerceAtMost(5)) {
                    val parsed = parseLyricsJson(arr.optJSONObject(i) ?: continue)
                    if (parsed.isNotEmpty()) return@use parsed
                }
                emptyList()
            }
        }.getOrDefault(emptyList())
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

    suspend fun downloadTrackOffline(track: Track, settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        val resolved = resolveStreams(track, settings) ?: return@withContext false
        if (resolved.isOffline) return@withContext true
        runCatching {
            val req = Request.Builder().url(resolved.audioUrl).header("User-Agent", resolved.userAgent).build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val target = getOfflineFile(track.id)
                resp.body?.byteStream()?.use { input -> target.outputStream().use { output -> input.copyTo(output) } }
                target.exists() && target.length() > 1024
            }
        }.getOrDefault(false)
    }

    fun deleteOfflineTrack(trackId: String) {
        val f = getOfflineFile(trackId)
        if (f.exists()) f.delete()
    }
}
