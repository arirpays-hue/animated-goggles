package com.sonify.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.ConnectionPool
import okhttp3.Dispatcher
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

/**
 * Dedicated Pure-Audio Offline Download Engine for Sonify v11.
 * Guarantees audio-only streams (~2-4.5 MB per song like Spotify), rejects all video/muxed streams,
 * writes directly to disk via RandomAccessFile (near-zero RAM), escapes throttled CDNs,
 * and serializes yt-dlp fallback with a Mutex so background downloads never crash.
 */
class OfflineDownloadEngine(private val context: Context) {

    companion object {
        private val ytDlpMutex = Mutex()
        private val ytDlpInitLock = Any()
        @Volatile private var ytDlpInitialized = false
        @Volatile private var ytDlpUpdateRunning = false

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
    }

    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 48
            maxRequestsPerHost = 12
        })
        .connectionPool(ConnectionPool(12, 5, TimeUnit.MINUTES))
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(12, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    val offlineDir: File
        get() = File(context.filesDir, "offline_tracks").apply { if (!exists()) mkdirs() }

    val streamPrecacheDir: File
        get() = File(context.cacheDir, "stream_precache").apply { if (!exists()) mkdirs() }

    fun getOfflineFile(trackId: String): File {
        val safe = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(offlineDir, "$safe.media")
    }

    fun getPrecachedAudioFile(trackId: String): File {
        val safe = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(streamPrecacheDir, "$safe.cache")
    }

    fun getOfflineTrackIds(): Set<String> {
        return offlineDir.listFiles()
            ?.filter { it.isFile && it.length() > 4096 }
            ?.map { it.nameWithoutExtension }
            ?.toSet()
            ?: emptySet()
    }

    fun getOfflineFileSizeMb(trackId: String): Float {
        val f = getOfflineFile(trackId)
        return if (f.exists()) f.length() / (1024f * 1024f) else 0f
    }

    fun getTotalOfflineSizeMb(): Float =
        (offlineDir.listFiles()?.filter { it.isFile && it.extension == "media" }?.sumOf { it.length() } ?: 0L) / (1024f * 1024f)

    fun deleteOfflineTrack(trackId: String) {
        val f = getOfflineFile(trackId)
        if (f.exists()) f.delete()
    }

    fun deleteAllOfflineTracks() {
        runCatching { offlineDir.listFiles()?.forEach { it.delete() } }
    }

    fun cleanupStaleTempFiles() {
        runCatching {
            val cutoff = System.currentTimeMillis() - 15L * 60_000L
            offlineDir.listFiles()?.filter { (it.name.endsWith(".tmp") || it.name.endsWith(".wvtmp")) && it.lastModified() < cutoff }?.forEach { it.delete() }
            streamPrecacheDir.listFiles()?.filter { it.name.endsWith(".tmp") && it.lastModified() < cutoff }?.forEach { it.delete() }
        }
    }

    fun isOnWifi(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    suspend fun prewarmYtDlp() = withContext(Dispatchers.IO) {
        cleanupStaleTempFiles()
        if (!ensureYtDlpInitOnly()) return@withContext
        val dlPrefs = context.getSharedPreferences("sonify_ytdlp", Context.MODE_PRIVATE)
        val lastUpdate = dlPrefs.getLong("last_update_ms", 0L)
        val sevenDaysMs = 7L * 24L * 60L * 60L * 1000L
        if (!ytDlpUpdateRunning && (System.currentTimeMillis() - lastUpdate > sevenDaysMs)) {
            ytDlpUpdateRunning = true
            runCatching {
                withTimeoutOrNull(25_000L) {
                    ytDlpMutex.withLock {
                        YoutubeDL.getInstance().updateYoutubeDL(
                            context.applicationContext,
                            YoutubeDL.UpdateChannel.NIGHTLY
                        )
                        dlPrefs.edit().putLong("last_update_ms", System.currentTimeMillis()).apply()
                    }
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

    private fun trimStreamPrecache() {
        runCatching {
            val files = streamPrecacheDir.listFiles()?.filter { it.isFile && it.extension == "cache" }?.sortedByDescending { it.lastModified() } ?: return
            var totalBytes = 0L
            files.forEachIndexed { idx, f ->
                totalBytes += f.length()
                if (idx >= 8 || totalBytes > 50L * 1024L * 1024L) {
                    f.delete()
                }
            }
        }
    }

    // Quietly downloads pure audio for the next track in queue (instant 0ms switch + weak-network shield!)
    suspend fun precacheAudioTrack(track: Track, quality: AudioQuality): Boolean = withContext(Dispatchers.IO) {
        if (track.source != StreamSource.YOUTUBE) return@withContext false
        if (getOfflineFile(track.id).let { it.exists() && it.length() > 16_384 }) return@withContext true
        val target = getPrecachedAudioFile(track.id)
        if (target.exists() && target.length() > 48_000) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext true
        }
        val audioStream = resolvePureAudioOnly(track.id, quality, attempt = 0) ?: return@withContext false
        if (audioStream.audioUrl.contains(".m3u8", true)) return@withContext false
        val clen = Regex("[?&]clen=(\\d+)").find(audioStream.audioUrl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        if (clen > 14L * 1024L * 1024L) return@withContext false // Skip huge podcasts

        val ok = downloadAudioSmart(
            url = audioStream.audioUrl,
            userAgent = audioStream.userAgent,
            cookie = audioStream.cookie,
            extraHeaders = emptyMap(),
            target = target,
            isWebOrigin = false,
            parallel = 3,
            onProgress = {}
        )
        if (ok) trimStreamPrecache()
        ok
    }

    /**
     * Main pure-audio offline download entry point.
     * Rejects video streams at every layer so tracks stay ~2–5 MB just like Spotify.
     */
    suspend fun downloadAudioTrack(
        track: Track,
        settings: AppSettings,
        nonYouTubeResolver: (suspend () -> ResolvedStreams?)? = null,
        capturedStreams: List<CapturedMediaStream> = emptyList(),
        onProgress: (Int) -> Unit = {}
    ): Pair<Boolean, String> = withContext(Dispatchers.IO) {
        val isEn = settings.language == AppLanguage.ENGLISH
        if (settings.offlineMode) {
            return@withContext false to if (isEn) "Offline Mode is ON — turn it off to download" else "מצב אופליין פעיל — כבה אותו כדי להוריד"
        }
        if (!settings.downloadUsingCellular && !isOnWifi()) {
            return@withContext false to if (isEn) "Cellular downloads disabled in settings" else "הורדה דרך סלולר חסומה בהגדרות"
        }
        if (track.source == StreamSource.LOCAL) {
            return@withContext true to if (isEn) "Already stored on device" else "הקובץ כבר שמור במכשיר"
        }

        val target = getOfflineFile(track.id)

        // Instant zero-network copy from rolling precache if already cached on disk
        val cached = getPrecachedAudioFile(track.id)
        if (cached.exists() && cached.length() > 48_000) {
            val copied = runCatching {
                cached.copyTo(target, overwrite = true)
                onProgress(100)
                true
            }.getOrDefault(false)
            if (copied) {
                val mb = String.format("%.1f", target.length() / (1024f * 1024f))
                return@withContext true to if (isEn) "Saved offline ($mb MB)" else "נשמר לאופליין ($mb MB)!"
            }
        }

        val parallel = settings.downloadSpeedMode.parallelChunks
        val quality = settings.streamSource.clampQuality(settings.downloadQuality)

        val result = withTimeoutOrNull(75_000L) {
            onProgress(4)

            if (track.source != StreamSource.YOUTUBE) {
                val res = nonYouTubeResolver?.invoke()
                if (res != null && downloadAudioSmart(res.audioUrl, res.userAgent, res.cookie, emptyMap(), target, false, parallel, onProgress)) {
                    val mb = String.format("%.1f", target.length() / (1024f * 1024f))
                    return@withTimeoutOrNull true to if (isEn) "Downloaded ($mb MB)" else "השיר ירד לאופליין ($mb MB)!"
                }
                return@withTimeoutOrNull false to if (isEn) "Download failed from source" else "שגיאה בהורדה מהמקור שנבחר"
            }

            // Layer 1: Direct InnerTube ANDROID_VR pure audio (m4a / opus, ~2-5 MB, fast parallel chunks)
            val vrAudio = runCatching { fetchPureInnerTubeAudio(track.id, "ANDROID_VR", quality, preferHls = false) }.getOrNull()
            if (vrAudio != null && downloadAudioSmart(vrAudio.audioUrl, vrAudio.userAgent, vrAudio.cookie, emptyMap(), target, false, parallel, onProgress)) {
                val mb = String.format("%.1f", target.length() / (1024f * 1024f))
                return@withTimeoutOrNull true to if (isEn) "Downloaded audio ($mb MB)" else "השיר ירד לאופליין ($mb MB)!"
            }

            // Layer 2: InnerTube IOS HLS TYPE=AUDIO track only (immune to CDN throttling)
            val iosAudio = runCatching { fetchPureInnerTubeAudio(track.id, "IOS", quality, preferHls = true) }.getOrNull()
            if (iosAudio != null && downloadAudioSmart(iosAudio.audioUrl, iosAudio.userAgent, iosAudio.cookie, emptyMap(), target, false, parallel, onProgress)) {
                val mb = String.format("%.1f", target.length() / (1024f * 1024f))
                return@withTimeoutOrNull true to if (isEn) "Downloaded audio ($mb MB)" else "השיר ירד לאופליין ($mb MB)!"
            }

            // Layer 3: Audio-only captured streams from WebPlaybackEngine (never video!)
            for (cap in capturedStreams.filter { it.isAudioOnly }.take(2)) {
                val cleanBase = sanitizeYouTubePlaybackUrl(cap.url)
                if (downloadAudioSmart(cleanBase, cap.userAgent, cap.cookie, cap.headers, target, true, parallel, onProgress)) {
                    val mb = String.format("%.1f", target.length() / (1024f * 1024f))
                    return@withTimeoutOrNull true to if (isEn) "Downloaded audio ($mb MB)" else "השיר ירד לאופליין ($mb MB)!"
                }
            }

            // Layer 4: Cobalt / Piped / Invidious audio-only proxies (itag=140 / audioStreams only)
            val proxyStrategies = listOf(
                { fetchPureInnerTubeAudio(track.id, "ANDROID_VR_LEGACY", quality, preferHls = false) },
                { fetchCobaltPureAudio(track.id) },
                { fetchPipedPureAudio(track.id, quality) },
                { fetchInvidiousPureAudio(track.id) }
            )
            for (strategy in proxyStrategies) {
                val resolved = runCatching { strategy() }.getOrNull() ?: continue
                if (downloadAudioSmart(resolved.audioUrl, resolved.userAgent, resolved.cookie, emptyMap(), target, false, parallel, onProgress)) {
                    val mb = String.format("%.1f", target.length() / (1024f * 1024f))
                    return@withTimeoutOrNull true to if (isEn) "Downloaded audio ($mb MB)" else "השיר ירד לאופליין ($mb MB)!"
                }
            }

            // Layer 5: Mutex-serialized yt-dlp strictly audio-only (vcodec=none)
            if (downloadViaYtDlpAudioOnly(track.id, quality, parallel, target, onProgress)) {
                val mb = String.format("%.1f", target.length() / (1024f * 1024f))
                return@withTimeoutOrNull true to if (isEn) "Downloaded audio ($mb MB)" else "השיר ירד לאופליין ($mb MB)!"
            }

            false to if (isEn) "Could not download pure audio right now — try again" else "לא ניתן להוריד אודיו נקי לשיר זה כרגע — נסה שוב"
        }

        result ?: (false to if (isEn) "Download timed out — please retry" else "ההורדה לקחה יותר מדי זמן — נסה שוב")
    }

    private fun resolvePureAudioOnly(videoId: String, quality: AudioQuality, attempt: Int): ResolvedStreams? {
        val list = listOf(
            { fetchPureInnerTubeAudio(videoId, "ANDROID_VR", quality, preferHls = false) },
            { fetchPureInnerTubeAudio(videoId, "IOS", quality, preferHls = true) },
            { fetchCobaltPureAudio(videoId) },
            { fetchPipedPureAudio(videoId, quality) }
        )
        for (i in attempt until list.size) {
            val r = runCatching { list[i]() }.getOrNull()
            if (r != null) return r
        }
        return null
    }

    private fun fetchPureInnerTubeAudio(videoId: String, clientType: String, quality: AudioQuality, preferHls: Boolean): ResolvedStreams? {
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

        return fastClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string() ?: return null)
            if (root.optJSONObject("playabilityStatus")?.optString("status") != "OK") return null
            val sd = root.optJSONObject("streamingData") ?: return null
            val hls = sd.optString("hlsManifestUrl").takeIf { it.startsWith("http") }

            val audioOnly = mutableListOf<Pair<Int, String>>()
            sd.optJSONArray("adaptiveFormats")?.let { adaptive ->
                for (i in 0 until adaptive.length()) {
                    val f = adaptive.optJSONObject(i) ?: continue
                    val mime = f.optString("mimeType").lowercase()
                    val u = f.optString("url")
                    // Strictly audio/* adaptive tracks only!
                    if (u.startsWith("http") && mime.startsWith("audio/")) {
                        val kbps = (f.optInt("bitrate", 128000) / 1000).coerceAtLeast(16)
                        audioOnly.add(kbps to u)
                    }
                }
            }

            val preferred = pickTargetBitrate(audioOnly, quality)
            val ordered = if (preferHls) {
                (listOfNotNull(hls, preferred) + audioOnly.map { it.second }).distinct()
            } else {
                (listOfNotNull(preferred) + audioOnly.map { it.second }).distinct()
            }
            val chosen = ordered.firstOrNull { verifyPureAudioUrl(it, ua) } ?: return null
            val kbps = audioOnly.firstOrNull { it.second == chosen }?.first ?: 128
            ResolvedStreams(audioUrl = chosen, userAgent = ua, codecLabel = "${kbps}kbps Audio")
        }
    }

    private fun fetchCobaltPureAudio(videoId: String): ResolvedStreams? {
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        val body = JSONObject().apply {
            put("url", "https://www.youtube.com/watch?v=$videoId")
            put("downloadMode", "audio")
            put("audioFormat", "mp3")
            put("audioBitrate", "128")
        }.toString()
        for (base in cobaltInstances) {
            val res = runCatching {
                val req = Request.Builder().url("$base/").post(body.toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json").header("User-Agent", ua).build()
                fastClient.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val u = JSONObject(resp.body?.string() ?: return@use null).optString("url")
                    if (u.startsWith("http") && verifyPureAudioUrl(u, ua)) ResolvedStreams(u, userAgent = ua, codecLabel = "MP3 128kbps") else null
                }
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun fetchPipedPureAudio(videoId: String, quality: AudioQuality): ResolvedStreams? {
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        for (base in pipedInstances) {
            val res = runCatching {
                fastClient.newCall(Request.Builder().url("$base/streams/$videoId").header("User-Agent", ua).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use null
                    val json = JSONObject(resp.body?.string() ?: return@use null)
                    val list = mutableListOf<Pair<Int, String>>()
                    json.optJSONArray("audioStreams")?.let { arr ->
                        for (i in 0 until arr.length()) {
                            val a = arr.optJSONObject(i) ?: continue
                            val u = a.optString("url")
                            val mime = a.optString("mimeType").lowercase()
                            if (u.startsWith("http") && !mime.startsWith("video/")) {
                                list.add((a.optInt("bitrate", 128000) / 1000).coerceAtLeast(16) to u)
                            }
                        }
                    }
                    val chosen = pickTargetBitrate(list, quality) ?: return@use null
                    if (verifyPureAudioUrl(chosen, ua)) ResolvedStreams(chosen, userAgent = ua, codecLabel = "Audio") else null
                }
            }.getOrNull()
            if (res != null) return res
        }
        return null
    }

    private fun fetchInvidiousPureAudio(videoId: String): ResolvedStreams? {
        val ua = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36"
        for (base in invidiousInstances) {
            val audioProxy = "$base/latest_version?id=$videoId&itag=140&local=true"
            if (verifyPureAudioUrl(audioProxy, ua)) {
                return ResolvedStreams(audioProxy, userAgent = ua, codecLabel = "AAC 128kbps")
            }
        }
        return null
    }

    private fun verifyPureAudioUrl(url: String, ua: String): Boolean {
        return runCatching {
            if (url.contains(".m3u8", true) || url.contains("manifest/hls", true)) {
                val manifest = fastClient.newCall(Request.Builder().url(url).header("User-Agent", ua).build()).execute().use { r ->
                    if (!r.isSuccessful) return false
                    r.body?.string().orEmpty()
                }
                return Regex("TYPE=AUDIO.*?URI=\"(https?://[^\"]+)\"").containsMatchIn(manifest)
            }
            val probeUrl = if (url.contains("googlevideo.com") && !url.contains("range=")) {
                val sep = if (url.contains("?")) "&" else "?"
                "${url}${sep}range=0-32767"
            } else url
            val rb = Request.Builder().url(probeUrl).header("User-Agent", ua)
            if (!probeUrl.contains("range=")) rb.header("Range", "bytes=0-32767")
            fastClient.newCall(rb.build()).execute().use { resp ->
                if (resp.code !in listOf(200, 206)) return false
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.startsWith("video/") || ct.contains("text/html") || ct.contains("application/json") || ct.contains("yt-ump")) return false
                val buf = ByteArray(1024)
                (resp.body?.byteStream()?.read(buf) ?: 0) > 128
            }
        }.getOrDefault(false)
    }

    private fun pickTargetBitrate(list: List<Pair<Int, String>>, quality: AudioQuality): String? {
        if (list.isEmpty()) return null
        val sorted = list.sortedBy { it.first }
        // Keeps audio crisp & compact (Spotify-like sizes: ~1.5-5 MB for a 3.5 min track!)
        return when (quality) {
            AudioQuality.LOW -> sorted.first().second
            AudioQuality.NORMAL -> sorted.minByOrNull { abs(it.first - 96) }?.second
            AudioQuality.HIGH -> sorted.minByOrNull { abs(it.first - 130) }?.second
            AudioQuality.VERY_HIGH, AudioQuality.LOSSLESS -> sorted.lastOrNull { it.first <= 260 }?.second ?: sorted.last().second
        }
    }

    private suspend fun downloadViaYtDlpAudioOnly(
        videoId: String,
        quality: AudioQuality,
        parallelCount: Int,
        target: File,
        onProgress: (Int) -> Unit
    ): Boolean {
        if (!ensureYtDlpInitOnly()) return false
        return ytDlpMutex.withLock {
            onProgress(12)
            // Strictly vcodec=none (audio only, NEVER downloads video tracks)
            val qualityFormat = when (quality) {
                AudioQuality.LOW -> "worstaudio[vcodec=none][ext=m4a]/worstaudio[vcodec=none][ext=webm]/worstaudio[vcodec=none]"
                AudioQuality.NORMAL -> "bestaudio[vcodec=none][abr<=100][ext=m4a]/bestaudio[vcodec=none][abr<=100]/bestaudio[vcodec=none]"
                AudioQuality.HIGH -> "bestaudio[vcodec=none][abr<=145][ext=m4a]/bestaudio[vcodec=none][abr<=145]/bestaudio[vcodec=none]"
                AudioQuality.VERY_HIGH, AudioQuality.LOSSLESS -> "bestaudio[vcodec=none][ext=m4a]/bestaudio[vcodec=none][ext=webm]/bestaudio[vcodec=none]"
            }
            val attempts = listOf(
                qualityFormat to "youtube:player_client=android_vr,ios,tv_downgraded",
                "bestaudio[vcodec=none][abr<=160]/bestaudio[vcodec=none]" to "youtube:player_client=tv_downgraded,mweb,android_vr"
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
                    addOption("--http-chunk-size", "1048576")
                    addOption("--concurrent-fragments", parallelCount.coerceIn(4, 8).toString())
                    addOption("-f", fmt)
                    addOption("--extractor-args", extractorArgs)
                    addOption("-o", tmpFile.absolutePath)
                }

                val ok = runCatching {
                    YoutubeDL.getInstance().execute(request, "sonify_dl_$videoId") { progress: Float, _: Long, _: String ->
                        if (progress >= 0f) {
                            onProgress((12 + (progress * 0.86f).toInt()).coerceIn(12, 98))
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

                if (ok) return@withLock true
            }

            target.parentFile?.listFiles()?.filter { it.name.startsWith(tmpPrefix) }?.forEach { it.delete() }
            false
        }
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

    private suspend fun downloadAudioSmart(
        url: String, userAgent: String, cookie: String?, extraHeaders: Map<String, String>,
        target: File, isWebOrigin: Boolean, parallel: Int, onProgress: (Int) -> Unit
    ): Boolean {
        if (url.contains(".m3u8", true) || url.contains("manifest/hls", true)) {
            return downloadHlsAudioOnlyParallel(url, userAgent, cookie, target, parallel, onProgress)
        }
        val clean = sanitizeYouTubePlaybackUrl(url)
        if (clean.contains("googlevideo.com") && clean.contains("clen=")) {
            if (downloadChunkedDirectToDisk(clean, userAgent, cookie, extraHeaders, target, isWebOrigin, parallel, onProgress)) return true
        }
        return downloadUrlToFile(clean, userAgent, cookie, extraHeaders, target, isWebOrigin, onProgress)
    }

    private suspend fun downloadHlsAudioOnlyParallel(
        manifestUrl: String, userAgent: String, cookie: String?, target: File, parallel: Int, onProgress: (Int) -> Unit
    ): Boolean {
        val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        return try {
            fun fetchText(u: String): String? {
                val req = buildMediaRequest(u, userAgent, cookie, emptyMap(), false)
                return downloadClient.newCall(req).execute().use { r -> if (r.isSuccessful) r.body?.string() else null }
            }
            var playlistText = fetchText(manifestUrl) ?: return false
            if (playlistText.contains("#EXT-X-STREAM-INF") || playlistText.contains("#EXT-X-MEDIA")) {
                // Strictly require TYPE=AUDIO stream — never fall back to video playlist!
                val audioUri = Regex("TYPE=AUDIO.*?URI=\"(https?://[^\"]+)\"").findAll(playlistText).lastOrNull()?.groupValues?.get(1)
                    ?: return false
                playlistText = fetchText(audioUri) ?: return false
            }
            val segments = playlistText.lines().map { it.trim() }.filter { it.startsWith("http") }
            if (segments.isEmpty()) return false

            var completed = 0
            val workers = parallel.coerceIn(4, 8)
            FileOutputStream(tmp).use { out ->
                for (batch in segments.chunked(workers)) {
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
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (_: Throwable) {
            tmp.delete()
            false
        }
    }

    private fun downloadUrlToFile(
        url: String, userAgent: String, cookie: String?, extraHeaders: Map<String, String>,
        target: File, isWebOrigin: Boolean, onProgress: (Int) -> Unit
    ): Boolean {
        val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        return try {
            val req = buildMediaRequest(url, userAgent, cookie, extraHeaders, isWebOrigin)
            downloadClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.startsWith("video/") || ct.contains("text/html") || ct.contains("application/json") || ct.contains("yt-ump")) return false
                val total = resp.body?.contentLength() ?: -1L
                val startMs = System.currentTimeMillis()
                resp.body?.byteStream()?.use { input ->
                    FileOutputStream(tmp).use { output ->
                        val buf = ByteArray(32 * 1024)
                        var copied = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n <= 0) break
                            output.write(buf, 0, n)
                            copied += n
                            val elapsed = System.currentTimeMillis() - startMs
                            if (elapsed > 3000L && total > 600_000L && (copied * 1000L / elapsed) < 85_000L) {
                                tmp.delete()
                                return false
                            }
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
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (_: Throwable) {
            tmp.delete()
            false
        }
    }

    private suspend fun downloadChunkedDirectToDisk(
        baseUrl: String, userAgent: String, cookie: String?, extraHeaders: Map<String, String>,
        target: File, isWebOrigin: Boolean, parallel: Int, onProgress: (Int) -> Unit
    ): Boolean {
        val clen = Regex("[?&]clen=(\\d+)").find(baseUrl)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        if (clen <= 0L || clen > 75_000_000L) return false

        val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        return try {
            val workers = parallel.coerceIn(4, 6)
            val chunkSize = (clen / workers).coerceIn(256 * 1024L, 1_536 * 1024L)
            val ranges = mutableListOf<Pair<Long, Long>>()
            var cursor = 0L
            while (cursor < clen) {
                val end = (cursor + chunkSize - 1).coerceAtMost(clen - 1)
                ranges.add(cursor to end)
                cursor = end + 1
            }

            val writeLock = Any()
            val totalWritten = AtomicLong(0L)
            val aborted = AtomicBoolean(false)
            val startTimeMs = System.currentTimeMillis()

            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(clen)

                fun streamRangeToDisk(start: Long, end: Long): Boolean {
                    var activeUrl = baseUrl
                    for (attempt in 0..2) {
                        if (aborted.get()) return false
                        val sep = if (activeUrl.contains("?")) "&" else "?"
                        val req = buildMediaRequest("$activeUrl${sep}range=$start-$end", userAgent, cookie, extraHeaders, isWebOrigin)
                        val result = runCatching {
                            downloadClient.newCall(req).execute().use { resp ->
                                if (!resp.isSuccessful) return@use -1
                                val ct = resp.header("Content-Type").orEmpty().lowercase()
                                if (ct.startsWith("video/") || ct.contains("yt-ump") || ct.contains("text/html")) {
                                    aborted.set(true)
                                    return@use -1
                                }
                                val body = resp.body ?: return@use -1
                                if (ct.contains("text/plain")) {
                                    val txt = body.string().trim()
                                    if (txt.startsWith("https://")) {
                                        activeUrl = sanitizeYouTubePlaybackUrl(txt)
                                        return@use 2
                                    }
                                    return@use -1
                                }
                                val buf = ByteArray(32 * 1024)
                                var pos = start
                                body.byteStream().use { input ->
                                    while (true) {
                                        if (aborted.get()) return@use -1
                                        val n = input.read(buf)
                                        if (n <= 0) break
                                        synchronized(writeLock) {
                                            raf.seek(pos)
                                            raf.write(buf, 0, n)
                                        }
                                        pos += n
                                        val writtenNow = totalWritten.addAndGet(n.toLong())
                                        val elapsed = System.currentTimeMillis() - startTimeMs
                                        if (elapsed > 2600L && clen > 700_000L && (writtenNow * 1000L / elapsed) < 100_000L) {
                                            aborted.set(true)
                                            return@use -1
                                        }
                                        onProgress(((writtenNow * 98) / clen).toInt().coerceIn(5, 98))
                                    }
                                }
                                if (pos >= end) 1 else -1
                            }
                        }.getOrDefault(-1)
                        if (result == 1) return true
                        if (result != 2 && aborted.get()) return false
                    }
                    return false
                }

                for (batch in ranges.chunked(workers)) {
                    val allOk = coroutineScope {
                        batch.map { (s, e) -> async(Dispatchers.IO) { streamRangeToDisk(s, e) } }.awaitAll().all { it }
                    }
                    if (!allOk || aborted.get()) {
                        tmp.delete()
                        return false
                    }
                }
            }

            if (tmp.exists() && tmp.length() > 16_384) {
                if (target.exists()) target.delete()
                tmp.renameTo(target)
                onProgress(100)
                true
            } else { tmp.delete(); false }
        } catch (e: CancellationException) {
            tmp.delete()
            throw e
        } catch (_: Throwable) {
            tmp.delete()
            false
        }
    }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)
}
