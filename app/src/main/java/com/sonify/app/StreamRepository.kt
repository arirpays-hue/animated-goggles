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
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(16, TimeUnit.SECONDS)
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

    suspend fun searchTracks(
        query: String,
        settings: AppSettings,
        podcastsOnly: Boolean = false,
        filterLongCompilations: Boolean = !podcastsOnly
    ): List<Track> = withContext(Dispatchers.IO) {
        val cleanQuery = if (podcastsOnly && !query.contains("podcast", true) && !query.contains("פודקאסט")) {
            "$query podcast"
        } else query

        val allowLong = !filterLongCompilations ||
            query.contains("mix", true) ||
            query.contains("playlist", true) ||
            query.contains("מיקס") ||
            query.contains("פודקאסט") ||
            query.contains("סט")

        when (settings.streamSource) {
            StreamSource.YOUTUBE -> searchYouTube(cleanQuery, podcastsOnly, allowLong)
            StreamSource.OPENSUBSONIC -> searchSubsonic(cleanQuery, settings)
            StreamSource.LOCAL -> searchLocalAudio(cleanQuery)
        }
    }

    private fun searchYouTube(query: String, isPodcast: Boolean, allowLong: Boolean): List<Track> {
        val raw = runCatching { searchInnerTube(query, isPodcast) }.getOrDefault(emptyList())
            .ifEmpty { searchPiped(query, isPodcast) }
            .ifEmpty { searchInvidious(query, isPodcast) }

        if (allowLong) return raw.distinctBy { it.id }.take(20)

        val filtered = raw.filter { t ->
            val under12Min = t.durationMs == 0L || t.durationMs in 45_000L..720_000L
            val lower = t.title.lowercase()
            val notCompilation = !lower.contains("1 hour") &&
                !lower.contains("2 hour") &&
                !lower.contains("hour playlist") &&
                !lower.contains("full album") &&
                !lower.contains("nonstop") &&
                !lower.contains("compilation")
            under12Min && notCompilation
        }
        return (if (filtered.size >= 4) filtered else raw).distinctBy { it.id }.take(20)
    }

    private fun searchInnerTube(query: String, isPodcast: Boolean): List<Track> {
        val bodyJson = JSONObject().apply {
            put("query", query)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", "WEB")
                    put("clientVersion", "2.20250201.00.00")
                    put("hl", "he")
                    put("gl", "IL")
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
            val root = JSONObject(resp.body?.string() ?: return emptyList())
            val out = mutableListOf<Track>()
            extractVideoRenderers(root, out, isPodcast)
            out.distinctBy { it.id }
        }
    }

    private fun searchPiped(query: String, isPodcast: Boolean): List<Track> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        for (base in pipedInstances) {
            val res = runCatching {
                val req = Request.Builder()
                    .url("$base/search?q=$encoded&filter=music_songs")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use emptyList()
                    val json = JSONObject(resp.body?.string() ?: return@use emptyList())
                    val items = json.optJSONArray("items") ?: return@use emptyList()
                    buildList {
                        for (i in 0 until items.length()) {
                            val o = items.optJSONObject(i) ?: continue
                            val url = o.optString("url")
                            val id = url.substringAfter("v=", "").substringBefore("&")
                            if (id.length != 11) continue
                            add(
                                Track(
                                    id = id,
                                    title = o.optString("title", "ללא שם"),
                                    artist = o.optString("uploaderName", "אמן לא ידוע"),
                                    artworkUrl = o.optString("thumbnail").ifBlank { "https://i.ytimg.com/vi/$id/hqdefault.jpg" },
                                    durationMs = o.optLong("duration", 0L) * 1000L,
                                    source = StreamSource.YOUTUBE,
                                    isPodcast = isPodcast
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
                val req = Request.Builder()
                    .url("$base/api/v1/search?q=$encoded&type=video")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
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
                                    id = id,
                                    title = o.optString("title", "ללא שם"),
                                    artist = o.optString("author", "YouTube"),
                                    artworkUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                                    durationMs = o.optLong("lengthSeconds", 0L) * 1000L,
                                    source = StreamSource.YOUTUBE,
                                    isPodcast = isPodcast
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
                        val artist = extractRunsText(v?.optJSONObject("ownerText"))
                            .ifBlank { extractRunsText(v?.optJSONObject("shortBylineText")) }
                            .ifBlank { "YouTube" }
                        val durationText = v?.optJSONObject("lengthText")?.optString("simpleText").orEmpty()
                        if (title.isNotBlank()) {
                            out.add(
                                Track(
                                    id = id,
                                    title = title,
                                    artist = artist,
                                    artworkUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg",
                                    durationMs = parseDurationText(durationText),
                                    source = StreamSource.YOUTUBE,
                                    isPodcast = isPodcast
                                )
                            )
                        }
                    }
                } else {
                    val keys = node.keys()
                    while (keys.hasNext()) {
                        extractVideoRenderers(node.opt(keys.next()), out, isPodcast)
                    }
                }
            }
            is JSONArray -> {
                for (i in 0 until node.length()) {
                    extractVideoRenderers(node.opt(i), out, isPodcast)
                }
            }
        }
    }

    private fun extractRunsText(obj: JSONObject?): String {
        if (obj == null) return ""
        val simple = obj.optString("simpleText")
        if (simple.isNotBlank()) return simple
        val runs = obj.optJSONArray("runs") ?: return ""
        return buildString {
            for (i in 0 until runs.length()) {
                append(runs.optJSONObject(i)?.optString("text").orEmpty())
            }
        }
    }

    private fun parseDurationText(text: String): Long {
        val parts = text.split(":").mapNotNull { it.trim().toLongOrNull() }
        return when (parts.size) {
            2 -> (parts[0] * 60 + parts[1]) * 1000L
            3 -> (parts[0] * 3600 + parts[1] * 60 + parts[2]) * 1000L
            else -> 0L
        }
    }

    suspend fun resolveStreams(track: Track, settings: AppSettings, attemptIndex: Int = 0): ResolvedStreams? = withContext(Dispatchers.IO) {
        val offlineFile = getOfflineFile(track.id)
        if (offlineFile.exists() && offlineFile.length() > 1024) {
            return@withContext ResolvedStreams(
                audioUrl = offlineFile.toURI().toString(),
                isLossless = track.source != StreamSource.YOUTUBE,
                isOffline = true,
                codecLabel = "OFFLINE"
            )
        }

        if (track.source == StreamSource.LOCAL && !track.localUri.isNullOrBlank()) {
            val isFlac = track.title.endsWith(".flac", true) || track.album.contains("FLAC", true)
            return@withContext ResolvedStreams(
                audioUrl = track.localUri,
                isLossless = isFlac,
                codecLabel = if (isFlac) "FLAC Lossless" else "LOCAL"
            )
        }

        if (track.source == StreamSource.OPENSUBSONIC && settings.subsonicUrl.isNotBlank()) {
            val auth = buildSubsonicAuth(settings.subsonicUser, settings.subsonicPass)
            val formatParam = if (settings.quality == AudioQuality.LOSSLESS) "format=raw" else "maxBitRate=${settings.quality.kbps}"
            val base = settings.subsonicUrl.trimEnd('/')
            val url = "$base/rest/stream.view?id=${URLEncoder.encode(track.id, "UTF-8")}&$formatParam&$auth"
            return@withContext ResolvedStreams(
                audioUrl = url,
                isLossless = settings.quality == AudioQuality.LOSSLESS,
                codecLabel = if (settings.quality == AudioQuality.LOSSLESS) "FLAC Lossless" else "${settings.quality.kbps}kbps"
            )
        }

        resolveYouTubeStreams(track.id, settings.quality, attemptIndex)
    }

    private fun resolveYouTubeStreams(videoId: String, quality: AudioQuality, startIndex: Int): ResolvedStreams? {
        data class ClientSpec(val name: String, val version: String, val idHeader: String, val ua: String)
        val specs = listOf(
            ClientSpec("IOS", "20.10.4", "5", "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X)"),
            ClientSpec("ANDROID_VR", "1.61.48", "28", "com.google.android.apps.youtube.vr.oculus/1.61.48 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"),
            ClientSpec("ANDROID_TESTSUITE", "1.9", "30", "com.google.android.youtube/1.9 (Linux; U; Android 11) gzip")
        )

        val ordered = specs.drop(startIndex) + specs.take(startIndex.coerceAtMost(specs.size))
        for (spec in ordered) {
            val resolved = runCatching {
                fetchInnerTubePlayer(videoId, spec.name, spec.version, spec.idHeader, spec.ua, quality)
            }.getOrNull()
            if (resolved != null) return resolved
        }

        // Fallback 1: Piped API
        for (base in pipedInstances) {
            val resolved = runCatching {
                val req = Request.Builder().url("$base/streams/$videoId").header("User-Agent", "Mozilla/5.0").build()
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
                            val br = a.optInt("bitrate", 128000) / 1000
                            if (u.startsWith("http")) audioStreams.add(br to u)
                        }
                    }
                    val chosenAudio = pickAudioByQuality(audioStreams, quality) ?: hls ?: return@use null

                    var muxedVideo: String? = hls
                    var videoOnly: String? = null
                    if (videoArr != null) {
                        for (i in 0 until videoArr.length()) {
                            val v = videoArr.optJSONObject(i) ?: continue
                            val vo = v.optBoolean("videoOnly", true)
                            val u = v.optString("url")
                            val q = v.optString("quality")
                            if (!u.startsWith("http")) continue
                            if (!vo) {
                                muxedVideo = u
                                break
                            } else if (videoOnly == null || q.contains("720") || q.contains("480")) {
                                videoOnly = u
                            }
                        }
                    }
                    ResolvedStreams(
                        audioUrl = chosenAudio,
                        videoUrl = muxedVideo,
                        videoOnlyUrl = videoOnly,
                        userAgent = "Mozilla/5.0",
                        codecLabel = "${quality.kbps.coerceAtMost(256)}kbps"
                    )
                }
            }.getOrNull()
            if (resolved != null) return resolved
        }

        // Fallback 2: Invidious API
        for (base in invidiousInstances) {
            val resolved = runCatching {
                val req = Request.Builder().url("$base/api/v1/videos/$videoId").header("User-Agent", "Mozilla/5.0").build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val json = JSONObject(resp.body?.string() ?: return@use null)
                    val formats = json.optJSONArray("formatStreams")
                    val adaptive = json.optJSONArray("adaptiveFormats")
                    var muxed: String? = null
                    val audios = mutableListOf<Pair<Int, String>>()
                    var videoOnly: String? = null

                    if (formats != null) {
                        for (i in 0 until formats.length()) {
                            val f = formats.optJSONObject(i) ?: continue
                            val u = f.optString("url")
                            if (u.startsWith("http")) {
                                muxed = u
                                audios.add(128 to u)
                            }
                        }
                    }
                    if (adaptive != null) {
                        for (i in 0 until adaptive.length()) {
                            val f = adaptive.optJSONObject(i) ?: continue
                            val type = f.optString("type")
                            val u = f.optString("url")
                            if (!u.startsWith("http")) continue
                            if (type.startsWith("audio/")) {
                                val br = f.optString("bitrate").toIntOrNull()?.div(1000) ?: 128
                                audios.add(br to u)
                            } else if (type.startsWith("video/mp4") && videoOnly == null) {
                                videoOnly = u
                            }
                        }
                    }
                    val chosenAudio = pickAudioByQuality(audios, quality) ?: muxed ?: return@use null
                    ResolvedStreams(
                        audioUrl = chosenAudio,
                        videoUrl = muxed,
                        videoOnlyUrl = videoOnly,
                        userAgent = "Mozilla/5.0",
                        codecLabel = "256kbps"
                    )
                }
            }.getOrNull()
            if (resolved != null) return resolved
        }
        return null
    }

    private fun fetchInnerTubePlayer(
        videoId: String,
        clientName: String,
        clientVersion: String,
        clientIdHeader: String,
        userAgent: String,
        quality: AudioQuality
    ): ResolvedStreams? {
        val bodyJson = JSONObject().apply {
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
            put("context", JSONObject().apply {
                put("client", JSONObject().apply {
                    put("clientName", clientName)
                    put("clientVersion", clientVersion)
                    put("hl", "he")
                    put("gl", "IL")
                })
            })
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .post(bodyJson.toString().toRequestBody("application/json".toMediaType()))
            .header("User-Agent", userAgent)
            .header("X-YouTube-Client-Name", clientIdHeader)
            .header("X-YouTube-Client-Version", clientVersion)
            .header("Origin", "https://www.youtube.com")
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string() ?: return null)
            val status = root.optJSONObject("playabilityStatus")?.optString("status")
            if (status != null && status != "OK") return null
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
                        val br = f.optInt("bitrate", 128000) / 1000
                        audioCandidates.add(br to u)
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
                        val br = f.optInt("bitrate", 128000) / 1000
                        audioCandidates.add(br to u)
                    } else if (mime.startsWith("video/mp4")) {
                        val h = f.optInt("height", 360)
                        if (h in 240..720) videoCandidates.add(h to u)
                    }
                }
            }

            val bestAudio = pickAudioByQuality(audioCandidates, quality) ?: hls ?: return null
            val bestVideoOnly = videoCandidates.sortedBy { it.first }
                .lastOrNull { it.first <= 720 }?.second
            val bestMuxedOrHls = muxedVideoUrl ?: hls

            ResolvedStreams(
                audioUrl = bestAudio,
                videoUrl = bestMuxedOrHls,
                videoOnlyUrl = bestVideoOnly,
                userAgent = userAgent,
                isLossless = false,
                codecLabel = when (quality) {
                    AudioQuality.LOW -> "64kbps"
                    AudioQuality.NORMAL -> "128kbps"
                    else -> "256kbps Opus/AAC"
                }
            )
        }
    }

    private fun pickAudioByQuality(list: List<Pair<Int, String>>, quality: AudioQuality): String? {
        if (list.isEmpty()) return null
        val sorted = list.sortedBy { it.first }
        return when (quality) {
            AudioQuality.LOW -> sorted.first().second
            AudioQuality.NORMAL -> sorted.minByOrNull { kotlin.math.abs(it.first - 128) }?.second
            AudioQuality.HIGH, AudioQuality.LOSSLESS -> sorted.last().second
        }
    }

    // ---------- Smart AI Playlist Curating Engine ----------
    suspend fun buildAiPlaylist(prompt: String, settings: AppSettings): Pair<String, List<Track>> = withContext(Dispatchers.IO) {
        val cleanPrompt = prompt.trim()

        // Mode 1: If user provided a Gemini API key, ask Gemini to pick 12 exact songs
        if (settings.geminiApiKey.isNotBlank()) {
            val geminiPlan = runCatching { requestGeminiSongList(cleanPrompt, settings.geminiApiKey) }.getOrNull()
            if (geminiPlan != null && geminiPlan.second.isNotEmpty()) {
                val curated = mutableListOf<Track>()
                for (songQuery in geminiPlan.second.take(12)) {
                    val found = searchTracks("$songQuery official audio", settings, podcastsOnly = false, filterLongCompilations = true)
                    found.firstOrNull { cand -> curated.none { it.id == cand.id } }?.let { curated.add(it) }
                }
                if (curated.isNotEmpty()) {
                    return@withContext geminiPlan.first to curated
                }
            }
        }

        // Mode 2: Built-in Smart Multi-Query Curator (No API key needed, supports Hebrew + English)
        val (playlistTitle, queries) = expandPromptIntoSmartQueries(cleanPrompt)
        val curated = mutableListOf<Track>()
        val seenTitles = mutableSetOf<String>()

        for (q in queries) {
            val results = searchTracks(q, settings, podcastsOnly = false, filterLongCompilations = true)
            for (t in results) {
                val normTitle = t.title.lowercase().replace(Regex("[^a-z0-9א-ת]"), "").take(18)
                if (curated.none { it.id == t.id } && seenTitles.add(normTitle)) {
                    curated.add(t)
                    if (curated.size >= 14) break
                }
            }
            if (curated.size >= 14) break
        }

        // Last-resort fallback if prompt was super abstract
        if (curated.isEmpty()) {
            val fallback = searchTracks("$cleanPrompt song", settings, podcastsOnly = false, filterLongCompilations = false)
            curated.addAll(fallback.take(10))
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
                if (wantsHebrew) {
                    queries += listOf("שירי אימון ישראלי קצבי", "ראפ ישראלי אנרגטי שיר", "פופ ישראלי קצבי להיטים")
                }
                queries += listOf("gym workout rap songs official audio", "high energy phonk edm song", "running motivation hits song")
            }
            lower.contains("לילה") || lower.contains("רגוע") || lower.contains("שינה") || lower.contains("צ'יל") || lower.contains("chill") || lower.contains("sleep") -> {
                if (wantsHebrew) {
                    queries += listOf("שירים ישראליים שקטים ללילה", "בלדות ישראליות רגועות")
                }
                queries += listOf("chill late night r&b song", "acoustic indie chill song", "lofi chill song official audio")
            }
            lower.contains("מסיבה") || lower.contains("קלאב") || lower.contains("ריקוד") || lower.contains("party") || lower.contains("club") -> {
                if (wantsHebrew) queries += listOf("להיטי מסיבות ישראל 2025 2026", "מזרחית שמח להיטים")
                queries += listOf("party dance hits official audio", "club edm top songs")
            }
            lower.contains("עצוב") || lower.contains("שבור") || lower.contains("בלד") || lower.contains("sad") -> {
                if (wantsHebrew) queries += listOf("שירים עצובים בעברית", "בלדות ישראליות מרגשות")
                queries += listOf("emotional sad pop song official audio", "slow heartbreak ballad song")
            }
            lower.contains("למידה") || lower.contains("פוקוס") || lower.contains("ריכוז") || lower.contains("study") || lower.contains("focus") -> {
                queries += listOf("instrumental focus beats song", "ambient piano focus track", "chill electronic study track")
            }
            lower.contains("נסיעה") || lower.contains("אוטו") || lower.contains("כביש") || lower.contains("drive") -> {
                if (wantsHebrew) queries += listOf("שירים לנסיעה בעברית להיטים", "רוק ישראלי לנסיעה")
                queries += listOf("night drive synthwave song", "road trip pop rock hits song")
            }
            lower.contains("רוק") || lower.contains("rock") || lower.contains("מטאל") || lower.contains("metal") -> {
                if (wantsHebrew) queries += listOf("רוק ישראלי קלאסי שיר", "רוק ישראלי מודרני")
                queries += listOf("best rock songs official audio", "alternative rock hits song")
            }
            lower.contains("ראפ") || lower.contains("היפ") || lower.contains("rap") || lower.contains("hip hop") -> {
                if (wantsHebrew) queries += listOf("ראפ ישראלי להיטים שיר", "טונה רביד פלוטניק שירים")
                queries += listOf("hip hop rap hits official audio", "trap rap banger official audio")
            }
            lower.contains("מזרחית") || lower.contains("ים תיכוני") -> {
                queries += listOf("שירים מזרחיים חדשים 2026", "עומר אדם עדן חסון להיטים", "אייל גולן פאר טסי שירים")
            }
            else -> {
                queries += listOf(
                    "$prompt שיר",
                    "$prompt official audio",
                    "$prompt best songs",
                    "$prompt hits"
                )
            }
        }

        val cleanTitle = "AI • ${prompt.take(28)}"
        return cleanTitle to queries.distinct()
    }

    private fun requestGeminiSongList(prompt: String, apiKey: String): Pair<String, List<String>>? {
        val sysPrompt = """
            Build a 12-song music playlist for this user request: "$prompt".
            Reply ONLY with valid JSON: {"title": "Short catchy playlist title in the user's language", "songs": ["Artist - Song Title", ...]}
        """.trimIndent()
        val payload = JSONObject().apply {
            put("contents", JSONArray().put(
                JSONObject().put("parts", JSONArray().put(JSONObject().put("text", sysPrompt)))
            ))
        }
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash:generateContent?key=$apiKey")
            .post(payload.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string() ?: return null)
            val text = root.optJSONArray("candidates")
                ?.optJSONObject(0)
                ?.optJSONObject("content")
                ?.optJSONArray("parts")
                ?.optJSONObject(0)
                ?.optString("text") ?: return null
            val jsonSlice = text.substringAfter("{").substringBeforeLast("}")
            val parsed = JSONObject("{$jsonSlice}")
            val title = parsed.optString("title", "AI • ${prompt.take(24)}")
            val songsArr = parsed.optJSONArray("songs") ?: return null
            val songs = buildList {
                for (i in 0 until songsArr.length()) {
                    songsArr.optString(i).takeIf { it.isNotBlank() }?.let { add(it) }
                }
            }
            title to songs
        }
    }

    // ---------- OpenSubsonic / Navidrome ----------
    suspend fun testSubsonicConnection(settings: AppSettings): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        if (settings.subsonicUrl.isBlank()) return@withContext false to "יש להזין כתובת שרת"
        runCatching {
            val base = settings.subsonicUrl.trimEnd('/')
            val auth = buildSubsonicAuth(settings.subsonicUser, settings.subsonicPass)
            val req = Request.Builder().url("$base/rest/ping.view?$auth").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false to "שגיאת HTTP ${resp.code}"
                val json = JSONObject(resp.body?.string() ?: "")
                val ok = json.optJSONObject("subsonic-response")?.optString("status") == "ok"
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
            val req = Request.Builder()
                .url("$base/rest/search3.view?query=$q&songCount=30&$auth")
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val root = JSONObject(resp.body?.string() ?: return emptyList())
                val songs = root.optJSONObject("subsonic-response")
                    ?.optJSONObject("searchResult3")
                    ?.optJSONArray("song") ?: return emptyList()
                buildList {
                    for (i in 0 until songs.length()) {
                        val s = songs.optJSONObject(i) ?: continue
                        val id = s.optString("id")
                        val coverId = s.optString("coverArt")
                        val coverUrl = if (coverId.isNotBlank()) "$base/rest/getCoverArt.view?id=$coverId&$auth" else ""
                        add(
                            Track(
                                id = id,
                                title = s.optString("title", "ללא שם"),
                                artist = s.optString("artist", "אמן לא ידוע"),
                                album = s.optString("album", ""),
                                artworkUrl = coverUrl,
                                durationMs = s.optLong("duration", 0L) * 1000L,
                                source = StreamSource.OPENSUBSONIC
                            )
                        )
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun buildSubsonicAuth(user: String, pass: String): String {
        val salt = "sonify26"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest((pass + salt).toByteArray()).joinToString("") { "%02x".format(it) }
        return "u=${URLEncoder.encode(user, "UTF-8")}&t=$digest&s=$salt&v=1.16.1&c=Sonify&f=json"
    }

    // ---------- Local Device Audio ----------
    fun searchLocalAudio(query: String): List<Track> {
        val list = mutableListOf<Track>()
        runCatching {
            val projection = arrayOf(
                MediaStore.Audio.Media._ID,
                MediaStore.Audio.Media.TITLE,
                MediaStore.Audio.Media.ARTIST,
                MediaStore.Audio.Media.ALBUM,
                MediaStore.Audio.Media.DURATION,
                MediaStore.Audio.Media.ALBUM_ID
            )
            val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                null,
                "${MediaStore.Audio.Media.DATE_ADDED} DESC"
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
                    val artist = c.getString(artistCol) ?: "אמן לא ידוע"
                    val album = c.getString(albumCol) ?: ""
                    if (query.isNotBlank() &&
                        !title.contains(query, true) &&
                        !artist.contains(query, true) &&
                        !album.contains(query, true)
                    ) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id).toString()
                    val albumArt = ContentUris.withAppendedId(
                        android.net.Uri.parse("content://media/external/audio/albumart"),
                        c.getLong(albIdCol)
                    ).toString()
                    list.add(
                        Track(
                            id = "local_$id",
                            title = title,
                            artist = artist,
                            album = album,
                            artworkUrl = albumArt,
                            durationMs = c.getLong(durCol),
                            source = StreamSource.LOCAL,
                            localUri = uri
                        )
                    )
                }
            }
        }
        return list
    }

    // ---------- Synced Lyrics (LRCLIB Exact + Search Fallback) ----------
    suspend fun fetchSyncedLyrics(artist: String, title: String): List<LyricLine> = withContext(Dispatchers.IO) {
        val cleanTitle = title
            .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            .replace("Official Video", "", true)
            .replace("Official Audio", "", true)
            .replace("Official Music Video", "", true)
            .replace("Lyric Video", "", true)
            .replace("קליפ רשמי", "")
            .trim()
        val cleanArtist = artist.replace("- Topic", "", true).replace("VEVO", "", true).trim()

        // 1) Exact match
        val exact = runCatching {
            val url = "https://lrclib.net/api/get?artist_name=${URLEncoder.encode(cleanArtist, "UTF-8")}&track_name=${URLEncoder.encode(cleanTitle, "UTF-8")}"
            val req = Request.Builder().url(url).header("User-Agent", "Sonify/2.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                parseLyricsJson(JSONObject(resp.body?.string() ?: return@use emptyList()))
            }
        }.getOrDefault(emptyList())
        if (exact.isNotEmpty()) return@withContext exact

        // 2) Broad search fallback
        runCatching {
            val q = URLEncoder.encode("$cleanArtist $cleanTitle".trim(), "UTF-8")
            val req = Request.Builder().url("https://lrclib.net/api/search?q=$q").header("User-Agent", "Sonify/2.0").build()
            client.newCall(req).execute().use { resp ->
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
        if (plain.isNotBlank()) {
            return plain.lines().filter { it.isNotBlank() }.mapIndexed { idx, line ->
                LyricLine(idx * 3500L, line.trim())
            }
        }
        return emptyList()
    }

    private fun parseLrc(lrc: String): List<LyricLine> {
        val regex = Regex("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{1,3}))?](.*)")
        return lrc.lines().mapNotNull { line ->
            val m = regex.find(line.trim()) ?: return@mapNotNull null
            val min = m.groupValues[1].toLongOrNull() ?: 0L
            val sec = m.groupValues[2].toLongOrNull() ?: 0L
            val fracStr = m.groupValues[3].padEnd(3, '0').take(3)
            val ms = fracStr.toLongOrNull() ?: 0L
            val text = m.groupValues[4].trim()
            if (text.isBlank()) null else LyricLine(min * 60_000L + sec * 1000L + ms, text)
        }.sortedBy { it.timeMs }
    }

    suspend fun downloadTrackOffline(track: Track, settings: AppSettings): Boolean = withContext(Dispatchers.IO) {
        val resolved = resolveStreams(track, settings) ?: return@withContext false
        if (resolved.isOffline) return@withContext true
        runCatching {
            val req = Request.Builder()
                .url(resolved.audioUrl)
                .header("User-Agent", resolved.userAgent)
                .build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use false
                val target = getOfflineFile(track.id)
                resp.body?.byteStream()?.use { input ->
                    target.outputStream().use { output -> input.copyTo(output) }
                }
                target.exists() && target.length() > 1024
            }
        }.getOrDefault(false)
    }

    fun deleteOfflineTrack(trackId: String) {
        val f = getOfflineFile(trackId)
        if (f.exists()) f.delete()
    }
}
