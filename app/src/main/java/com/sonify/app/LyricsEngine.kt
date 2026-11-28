package com.sonify.app

import android.content.Context
import android.graphics.Color as AndroidColor
import android.text.Html
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.ConnectionPool
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLEncoder
import java.util.concurrent.TimeUnit
import kotlin.math.pow

data class LyricsContrastPalette(
    val cardBackground: Color,
    val headerText: Color,
    val activeLine: Color,
    val pastLine: Color,
    val upcomingLine: Color,
    val translationActive: Color,
    val translationInactive: Color,
    val pillBackground: Color,
    val pillText: Color
)

/**
 * WCAG 2.1 Perceptual Contrast Engine for Lyrics & Podcast Transcripts.
 * Guarantees crisp readability on dark, bright, saturated, or monochrome album covers
 * so lyrics NEVER vanish into the background!
 */
fun computeLyricsContrastPalette(accentColorLong: Long, isPodcast: Boolean = false): LyricsContrastPalette {
    val rawArgb = accentColorLong.toInt()
    val hsv = FloatArray(3)
    AndroidColor.colorToHSV(rawArgb, hsv)

    // Shape card surface into a rich, Spotify-style vibrant tone (never pitch black, never blinding white)
    val hue = if (isPodcast && hsv[1] < 0.18f) 6f else hsv[0]
    val sat = if (isPodcast) hsv[1].coerceIn(0.42f, 0.78f) else hsv[1].coerceIn(0.24f, 0.72f)
    val value = if (isPodcast) hsv[2].coerceIn(0.36f, 0.64f) else hsv[2].coerceIn(0.28f, 0.54f)

    val shapedArgb = AndroidColor.HSVToColor(floatArrayOf(hue, sat, value))
    val bgColor = Color(shapedArgb)
    val bgLum = relativeLuminance(shapedArgb)

    val whiteContrast = contrastRatio(1.0, bgLum)
    val darkContrast = contrastRatio(bgLum, 0.015)

    // Choose high-contrast foreground polarity automatically
    val preferLightText = whiteContrast >= darkContrast || whiteContrast >= 4.2

    return if (preferLightText) {
        LyricsContrastPalette(
            cardBackground = bgColor,
            headerText = Color.White,
            activeLine = Color.White,
            pastLine = Color.White.copy(alpha = 0.74f),
            upcomingLine = Color.White.copy(alpha = 0.46f),
            translationActive = Color(0xFFE8FFF0),
            translationInactive = Color.White.copy(alpha = 0.62f),
            pillBackground = Color.Black.copy(alpha = 0.34f),
            pillText = Color.White
        )
    } else {
        LyricsContrastPalette(
            cardBackground = bgColor,
            headerText = Color(0xFF101010),
            activeLine = Color(0xFF0A0A0A),
            pastLine = Color(0xFF181818).copy(alpha = 0.74f),
            upcomingLine = Color(0xFF181818).copy(alpha = 0.46f),
            translationActive = Color(0xFF0A2416),
            translationInactive = Color(0xFF181818).copy(alpha = 0.64f),
            pillBackground = Color.White.copy(alpha = 0.42f),
            pillText = Color(0xFF0A0A0A)
        )
    }
}

private fun relativeLuminance(argb: Int): Double {
    fun linearize(c: Int): Double {
        val s = c / 255.0
        return if (s <= 0.03928) s / 12.92 else ((s + 0.055) / 1.055).pow(2.4)
    }
    return 0.2126 * linearize(AndroidColor.red(argb)) +
        0.7152 * linearize(AndroidColor.green(argb)) +
        0.0722 * linearize(AndroidColor.blue(argb))
}

private fun contrastRatio(l1: Double, l2: Double): Double {
    val lighter = maxOf(l1, l2)
    val darker = minOf(l1, l2)
    return (lighter + 0.05) / (darker + 0.05)
}

/**
 * Dedicated 8-Source Synced Lyrics & Podcast Transcript Engine.
 * Uses HTTP/1.1 + browser-safe headers to avoid mobile carrier / Cloudflare rejections.
 */
class LyricsEngine(private val context: Context) {

    private val http = OkHttpClient.Builder()
        .protocols(listOf(Protocol.HTTP_1_1))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(14, TimeUnit.SECONDS)
        .connectionPool(ConnectionPool(8, 3, TimeUnit.MINUTES))
        .retryOnConnectionFailure(true)
        .followRedirects(true)
        .build()

    private val browserUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val lyricsCacheDir: File
        get() = File(context.filesDir, "lyrics_cache").apply { if (!exists()) mkdirs() }

    private fun JSONObject.safeStr(key: String): String {
        if (isNull(key)) return ""
        val v = optString(key, "").trim()
        return if (v.equals("null", true)) "" else v
    }

    private fun cacheFile(trackId: String, suffix: String = ""): File {
        val safe = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(lyricsCacheDir, "$safe$suffix.json")
    }

    fun clearCache() {
        runCatching { lyricsCacheDir.listFiles()?.forEach { it.delete() } }
    }

    fun getCached(trackId: String, suffix: String = ""): List<LyricLine> {
        val f = cacheFile(trackId, suffix)
        if (!f.exists()) return emptyList()
        return runCatching {
            val arr = JSONArray(f.readText())
            List(arr.length()) { i ->
                val o = arr.optJSONObject(i)
                LyricLine(o.optLong("t", 0L), o.safeStr("x"))
            }.filter { it.text.isNotBlank() }
        }.getOrDefault(emptyList())
    }

    private fun saveCache(trackId: String, lines: List<LyricLine>, suffix: String = "") {
        if (lines.isEmpty()) return
        runCatching {
            val arr = JSONArray()
            lines.forEach { l -> arr.put(JSONObject().apply { put("t", l.timeMs); put("x", l.text) }) }
            cacheFile(trackId, suffix).writeText(arr.toString())
        }
    }

    fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\([^)]*(official|video|audio|lyric|prod|remaster|feat|ft\\.|קליפ|מילים|רשמי|from the series|visualizer)[^)]*\\)", RegexOption.IGNORE_CASE), "")
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

    fun cleanArtist(artist: String): String {
        return artist
            .replace("- Topic", "", true)
            .replace("VEVO", "", true)
            .replace("Official", "", true)
            .replace(Regex("\\s*(?:feat\\.|ft\\.).*", RegexOption.IGNORE_CASE), "")
            .substringBefore(",")
            .substringBefore("&")
            .trim()
    }

    /**
     * Dedicated timestamped transcript fetcher for Podcasts (supports auto-generated Hebrew/English ASR captions!).
     */
    suspend fun fetchPodcastTranscript(track: Track, offlineOnly: Boolean = false): List<LyricLine> = withContext(Dispatchers.IO) {
        val cached = getCached(track.id, "_pod")
        if (cached.isNotEmpty()) return@withContext cached
        if (offlineOnly || track.source != StreamSource.YOUTUBE) return@withContext emptyList()

        val captions = runCatching { fetchYouTubeCaptionsMultiClient(track.id, allowAutoGenerated = true) }.getOrDefault(emptyList())
        if (captions.isNotEmpty()) {
            saveCache(track.id, captions, "_pod")
            return@withContext captions
        }
        emptyList()
    }

    /**
     * 8-Source Parallel Synced Lyrics Engine for Songs.
     */
    suspend fun fetchSongLyrics(
        track: Track,
        manualOverrideQuery: String? = null,
        offlineOnly: Boolean = false
    ): List<LyricLine> = withContext(Dispatchers.IO) {
        if (manualOverrideQuery.isNullOrBlank()) {
            val cached = getCached(track.id)
            if (cached.isNotEmpty()) return@withContext cached
        }
        if (offlineOnly) return@withContext emptyList()

        // If it's a podcast, route to dedicated transcript engine
        if (track.isPodcastOrAudiobook && manualOverrideQuery.isNullOrBlank()) {
            return@withContext fetchPodcastTranscript(track, offlineOnly = false)
        }

        val rawCleanTitle = if (!manualOverrideQuery.isNullOrBlank()) manualOverrideQuery.trim() else cleanTitle(track.title)
        val titleNoParens = rawCleanTitle.replace(Regex("\\([^)]*\\)"), "").replace(Regex("\\s+"), " ").trim()
        val rawCleanArtist = if (!manualOverrideQuery.isNullOrBlank()) "" else cleanArtist(track.artist)

        val encArtist = URLEncoder.encode(rawCleanArtist, "UTF-8")
        val encTitle = URLEncoder.encode(rawCleanTitle, "UTF-8")
        val encTitleNoP = URLEncoder.encode(titleNoParens, "UTF-8")

        val searchQueries = listOfNotNull(
            "$rawCleanArtist $rawCleanTitle".trim().takeIf { it.isNotBlank() },
            "$rawCleanArtist $titleNoParens".trim().takeIf { it.isNotBlank() },
            titleNoParens.takeIf { it.length >= 2 },
            rawCleanTitle.takeIf { it.length >= 2 }
        ).distinct()

        // WAVE 1: Race 4 fastest & highest-coverage synced sources in parallel!
        val (lrcExact, lrcStructured, simpRes, ytmNative) = coroutineScope {
            val j1 = async {
                if (rawCleanArtist.isBlank()) emptyList()
                else fetchLrclibExact(encArtist, listOf(encTitle, encTitleNoP).distinct())
            }
            val j2 = async {
                fetchLrclibSearch(rawCleanArtist, rawCleanTitle, titleNoParens, searchQueries)
            }
            val j3 = async {
                if (track.source == StreamSource.YOUTUBE && manualOverrideQuery.isNullOrBlank()) {
                    runCatching { fetchSimpMusicLyrics(track.id) }.getOrDefault(emptyList())
                } else emptyList()
            }
            val j4 = async {
                if (track.source == StreamSource.YOUTUBE && manualOverrideQuery.isNullOrBlank()) {
                    runCatching { fetchYouTubeMusicNativeLyrics(track.id) }.getOrDefault(emptyList())
                } else emptyList()
            }
            Quad(j1.await(), j2.await(), j3.await(), j4.await())
        }

        // Prefer true synced results over plain text
        val wave1Synced = listOf(lrcExact, lrcStructured, simpRes, ytmNative)
            .firstOrNull { it.size >= 3 && hasRealTimestamps(it) }
        if (wave1Synced != null) {
            saveCache(track.id, wave1Synced)
            return@withContext wave1Synced
        }
        val wave1Plain = listOf(ytmNative, lrcExact, lrcStructured, simpRes).firstOrNull { it.isNotEmpty() }
        if (wave1Plain != null) {
            saveCache(track.id, wave1Plain)
            return@withContext wave1Plain
        }

        // WAVE 2: Lyrist + NetEase Synced LRC + YouTube Captions + Lyrics.ovh in parallel!
        val (lyristRes, netEaseRes, ytCapRes, ovhRes) = coroutineScope {
            val j1 = async {
                if (rawCleanArtist.isNotBlank() && titleNoParens.isNotBlank()) {
                    runCatching { fetchLyristLyrics(encTitleNoP, encArtist) }.getOrDefault(emptyList())
                } else emptyList()
            }
            val j2 = async {
                val q = searchQueries.firstOrNull().orEmpty()
                if (q.isNotBlank()) runCatching { fetchNetEaseSyncedLyrics(q) }.getOrDefault(emptyList()) else emptyList()
            }
            val j3 = async {
                if (track.source == StreamSource.YOUTUBE && manualOverrideQuery.isNullOrBlank()) {
                    runCatching { fetchYouTubeCaptionsMultiClient(track.id, allowAutoGenerated = true) }.getOrDefault(emptyList())
                } else emptyList()
            }
            val j4 = async {
                if (rawCleanArtist.isNotBlank() && titleNoParens.isNotBlank()) {
                    runCatching { fetchLyricsOvh(encArtist, encTitleNoP) }.getOrDefault(emptyList())
                } else emptyList()
            }
            Quad(j1.await(), j2.await(), j3.await(), j4.await())
        }

        val wave2Winner = listOf(netEaseRes, ytCapRes, lyristRes, ovhRes).firstOrNull { it.isNotEmpty() }
        if (wave2Winner != null) {
            saveCache(track.id, wave2Winner)
            return@withContext wave2Winner
        }

        emptyList()
    }

    private fun hasRealTimestamps(lines: List<LyricLine>): Boolean {
        if (lines.size < 3) return false
        val d1 = lines[1].timeMs - lines[0].timeMs
        val d2 = lines[2].timeMs - lines[1].timeMs
        return d1 != 3500L || d2 != 3500L
    }

    private fun fetchLrclibExact(encArtist: String, encTitles: List<String>): List<LyricLine> {
        for (tEnc in encTitles) {
            val res = runCatching {
                val url = "https://lrclib.net/api/get?artist_name=$encArtist&track_name=$tEnc"
                val req = Request.Builder().url(url).header("User-Agent", browserUa).header("Accept", "application/json").build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    parseLrclibObject(JSONObject(resp.body?.string() ?: return@use emptyList()))
                }
            }.getOrDefault(emptyList())
            if (res.isNotEmpty()) return res
        }
        return emptyList()
    }

    private fun fetchLrclibSearch(artist: String, title: String, titleNoP: String, queries: List<String>): List<LyricLine> {
        val urls = buildList {
            if (artist.isNotBlank() && titleNoP.isNotBlank()) {
                val eA = URLEncoder.encode(artist, "UTF-8")
                val eT = URLEncoder.encode(titleNoP, "UTF-8")
                add("https://lrclib.net/api/search?track_name=$eT&artist_name=$eA")
            }
            queries.take(3).forEach { sq ->
                add("https://lrclib.net/api/search?q=${URLEncoder.encode(sq, "UTF-8")}")
            }
        }.distinct()

        for (url in urls) {
            val found = runCatching {
                val req = Request.Builder().url(url).header("User-Agent", browserUa).header("Accept", "application/json").build()
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val arr = JSONArray(resp.body?.string() ?: return@use emptyList())
                    for (i in 0 until arr.length().coerceAtMost(10)) {
                        val obj = arr.optJSONObject(i) ?: continue
                        if (obj.safeStr("syncedLyrics").isNotBlank()) {
                            val parsed = parseLrclibObject(obj)
                            if (parsed.isNotEmpty()) return@use parsed
                        }
                    }
                    for (i in 0 until arr.length().coerceAtMost(10)) {
                        val parsed = parseLrclibObject(arr.optJSONObject(i) ?: continue)
                        if (parsed.isNotEmpty()) return@use parsed
                    }
                    emptyList()
                }
            }.getOrDefault(emptyList())
            if (found.isNotEmpty()) return found
        }
        return emptyList()
    }

    private fun fetchSimpMusicLyrics(videoId: String): List<LyricLine> {
        val req = Request.Builder()
            .url("https://api-lyrics.simpmusic.org/v1/$videoId")
            .header("User-Agent", browserUa)
            .header("Accept", "application/json")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val body = resp.body?.string() ?: return emptyList()
            val root = JSONObject(body)
            val dataArr = root.optJSONArray("data") ?: return emptyList()
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
            emptyList()
        }
    }

    private fun fetchYouTubeMusicNativeLyrics(videoId: String): List<LyricLine> {
        val ytCtx = JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", "WEB_REMIX")
                put("clientVersion", "1.20260401.01.00")
                put("hl", "he")
                put("gl", "IL")
            })
        }
        val nextBody = JSONObject().apply {
            put("videoId", videoId)
            put("isAudioOnly", true)
            put("enablePersistentPlaylistPanel", true)
            put("context", ytCtx)
        }
        val nextReq = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/next?prettyPrint=false")
            .post(nextBody.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", browserUa)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()

        val lyricsBrowseId = http.newCall(nextReq).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val text = resp.body?.string().orEmpty()
            Regex("\"(MPLYt[^\"]+)\"").find(text)?.groupValues?.get(1)
        } ?: return emptyList()

        val browseBody = JSONObject().apply {
            put("browseId", lyricsBrowseId)
            put("context", ytCtx)
        }
        val browseReq = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false")
            .post(browseBody.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", browserUa)
            .header("Origin", "https://music.youtube.com")
            .header("Referer", "https://music.youtube.com/")
            .build()

        val browseRoot = http.newCall(browseReq).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            JSONObject(resp.body?.string() ?: return emptyList())
        }

        // 1. Try true synced timedLyricsRenderer first!
        val timed = mutableListOf<LyricLine>()
        extractTimedLyricsRenderer(browseRoot, timed)
        if (timed.size >= 3) return timed.sortedBy { it.timeMs }

        // 2. Fallback to musicDescriptionShelfRenderer plain lyrics
        val rawLyricsText = findFirstMusicDescriptionText(browseRoot)
        if (rawLyricsText.isBlank()) return emptyList()
        val lines = rawLyricsText.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("Source:", true) }
        if (lines.size < 3) return emptyList()
        return lines.mapIndexed { idx, line -> LyricLine(idx * 3200L, line) }
    }

    private fun extractTimedLyricsRenderer(node: Any?, out: MutableList<LyricLine>) {
        if (node == null) return
        when (node) {
            is JSONObject -> {
                node.optJSONArray("timedLyricsData")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val o = arr.optJSONObject(i) ?: continue
                        val txt = o.safeStr("lyricLine")
                        val ms = o.optJSONObject("cueRange")?.optString("startTimeMilliseconds")?.toLongOrNull() ?: continue
                        if (txt.isNotBlank()) out.add(LyricLine(ms, txt))
                    }
                    return
                }
                val keys = node.keys()
                while (keys.hasNext()) extractTimedLyricsRenderer(node.opt(keys.next()), out)
            }
            is JSONArray -> {
                for (i in 0 until node.length()) extractTimedLyricsRenderer(node.opt(i), out)
            }
        }
    }

    private fun findFirstMusicDescriptionText(node: Any?): String {
        if (node == null) return ""
        when (node) {
            is JSONObject -> {
                node.optJSONObject("musicDescriptionShelfRenderer")?.let { shelf ->
                    val descObj = shelf.optJSONObject("description")
                    val txt = extractRuns(descObj)
                    if (txt.length > 25) return txt
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

    private fun extractRuns(obj: JSONObject?): String {
        if (obj == null) return ""
        val s = obj.safeStr("simpleText")
        if (s.isNotBlank()) return s
        val runs = obj.optJSONArray("runs") ?: return ""
        return buildString { for (i in 0 until runs.length()) append(runs.optJSONObject(i)?.optString("text").orEmpty()) }
    }

    private fun fetchLyristLyrics(encTitle: String, encArtist: String): List<LyricLine> {
        val req = Request.Builder()
            .url("https://lyrist.vercel.app/api/$encTitle/$encArtist")
            .header("User-Agent", browserUa)
            .header("Accept", "application/json")
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val plain = JSONObject(resp.body?.string() ?: return emptyList()).safeStr("lyrics")
            if (plain.length < 30) return emptyList()
            plain.lines().map { it.trim() }.filter { it.isNotBlank() }
                .mapIndexed { idx, l -> LyricLine(idx * 3400L, l) }
        }
    }

    private fun fetchNetEaseSyncedLyrics(query: String): List<LyricLine> {
        val encQ = URLEncoder.encode(query, "UTF-8")
        val sReq = Request.Builder()
            .url("https://music.163.com/api/search/get/web?s=$encQ&type=1&limit=4")
            .header("User-Agent", browserUa)
            .header("Referer", "https://music.163.com/")
            .build()
        val songId = http.newCall(sReq).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            JSONObject(resp.body?.string() ?: return emptyList())
                .optJSONObject("result")
                ?.optJSONArray("songs")
                ?.optJSONObject(0)
                ?.optLong("id", 0L) ?: 0L
        }
        if (songId <= 0L) return emptyList()

        val lyrReq = Request.Builder()
            .url("https://music.163.com/api/song/lyric?id=$songId&lv=1&kv=1&tv=-1")
            .header("User-Agent", browserUa)
            .header("Referer", "https://music.163.com/")
            .build()
        return http.newCall(lyrReq).execute().use { resp ->
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

    private fun fetchYouTubeCaptionsMultiClient(videoId: String, allowAutoGenerated: Boolean): List<LyricLine> {
        val clientConfigs = listOf(
            Triple("ANDROID_VR", "1.65.10", "com.google.android.apps.youtube.vr.oculus/1.65.10 (Linux; U; Android 12L) gzip"),
            Triple("IOS", "20.14.2", "com.google.ios.youtube/20.14.2 (iPhone16,2; U; CPU iOS 18_4_0 like Mac OS X)"),
            Triple("WEB", "2.20260401.01.00", browserUa)
        )

        var captionUrl: String? = null
        for ((cName, cVer, ua) in clientConfigs) {
            val body = JSONObject().apply {
                put("videoId", videoId)
                put("context", JSONObject().apply {
                    put("client", JSONObject().apply {
                        put("clientName", cName)
                        put("clientVersion", cVer)
                        put("hl", "he")
                        put("gl", "IL")
                    })
                })
            }
            val req = Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .header("User-Agent", ua)
                .build()

            captionUrl = runCatching {
                http.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val root = JSONObject(resp.body?.string() ?: return@use null)
                    val tracks = root.optJSONObject("captions")
                        ?.optJSONObject("playerCaptionsTracklistRenderer")
                        ?.optJSONArray("captionTracks") ?: return@use null
                    var manualMatch: String? = null
                    var autoMatch: String? = null
                    var anyMatch: String? = null
                    for (i in 0 until tracks.length()) {
                        val ct = tracks.optJSONObject(i) ?: continue
                        val base = ct.safeStr("baseUrl")
                        if (!base.startsWith("http")) continue
                        val lang = ct.safeStr("languageCode").lowercase()
                        val isAsr = ct.safeStr("kind").equals("asr", true)
                        if (anyMatch == null) anyMatch = base
                        val goodLang = lang.startsWith("he") || lang.startsWith("iw") || lang.startsWith("en")
                        if (!isAsr && goodLang && manualMatch == null) manualMatch = base
                        if (isAsr && goodLang && autoMatch == null) autoMatch = base
                    }
                    manualMatch ?: (if (allowAutoGenerated) autoMatch ?: anyMatch else null)
                }
            }.getOrNull()
            if (!captionUrl.isNullOrBlank()) break
        }
        val baseCap = captionUrl ?: return emptyList()

        // Try clean JSON3 caption format first (most reliable for both songs and podcasts!)
        val json3Url = if (baseCap.contains("fmt=")) baseCap.replace(Regex("fmt=[^&]+"), "fmt=json3") else "$baseCap&fmt=json3"
        val fromJson3 = runCatching {
            http.newCall(Request.Builder().url(json3Url).header("User-Agent", browserUa).build()).execute().use { r ->
                if (!r.isSuccessful) return@use emptyList()
                val events = JSONObject(r.body?.string() ?: return@use emptyList()).optJSONArray("events") ?: return@use emptyList()
                buildList {
                    for (i in 0 until events.length()) {
                        val ev = events.optJSONObject(i) ?: continue
                        val startMs = ev.optLong("tStartMs", -1L)
                        if (startMs < 0L) continue
                        val segs = ev.optJSONArray("segs") ?: continue
                        val line = buildString {
                            for (j in 0 until segs.length()) append(segs.optJSONObject(j)?.optString("utf8").orEmpty())
                        }.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                        if (line.isNotBlank() && line != "[Music]" && line != "[מוזיקה]" && line != "♪") {
                            add(LyricLine(startMs, line))
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
        if (fromJson3.size >= 3) return fromJson3.sortedBy { it.timeMs }

        // Fallback: XML srv1 format
        val xmlUrl = if (baseCap.contains("fmt=")) baseCap else "$baseCap&fmt=srv1"
        val xml = http.newCall(Request.Builder().url(xmlUrl).header("User-Agent", browserUa).build()).execute().use { r ->
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

    private fun fetchLyricsOvh(encArtist: String, encTitle: String): List<LyricLine> {
        val req = Request.Builder()
            .url("https://api.lyrics.ovh/v1/$encArtist/$encTitle")
            .header("User-Agent", browserUa)
            .build()
        return http.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return emptyList()
            val plain = JSONObject(resp.body?.string() ?: return emptyList()).safeStr("lyrics")
            plain.lines().map { it.trim() }.filter { it.isNotBlank() && !it.startsWith("Paroles de", true) }
                .mapIndexed { idx, line -> LyricLine(idx * 3200L, line) }
        }
    }

    private fun parseLrclibObject(json: JSONObject): List<LyricLine> {
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

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
