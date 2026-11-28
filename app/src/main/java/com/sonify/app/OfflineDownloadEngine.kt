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
import kotlinx.coroutines.delay
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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs

class OfflineDownloadEngine(private val context: Context) {

    companion object {
        private val ytDlpMutex = Mutex()
        private val ytDlpInitLock = Any()
        @Volatile private var ytDlpInitialized = false
        @Volatile private var ytDlpUpdateRunning = false

        private val pipedInstances = listOf(
            "https://pipedapi.kavin.rocks",
            "https://pipedapi.adminforge.de",
            "https://api.piped.private.coffee"
        )
        private val invidiousInstances = listOf(
            "https://inv.nadeko.net",
            "https://invidious.nerdvpn.de"
        )
        private val cobaltInstances = listOf(
            "https://api.cobalt.tools"
        )
    }

    private val fastClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(7, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val proxyClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .callTimeout(7, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    // High-concurrency download pool so 8–12 parallel HLS / Micro-Burst chunks fly at full line speed!
    private val downloadClient = OkHttpClient.Builder()
        .dispatcher(Dispatcher().apply {
            maxRequests = 64
            maxRequestsPerHost = 16
        })
        .connectionPool(ConnectionPool(16, 5, TimeUnit.MINUTES))
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(45, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
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
            ?.filter { it.isFile && it.extension == "media" && it.length() > 16_384 }
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
            val cutoff = System.currentTimeMillis() - 10L * 60_000L
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
        val threeDaysMs = 3L * 24L * 60L * 60L * 1000L
        if (!ytDlpUpdateRunning && (System.currentTimeMillis() - lastUpdate > threeDaysMs)) {
            ytDlpUpdateRunning = true
            runCatching {
                withTimeoutOrNull(30_000L) {
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
                if (idx >= 12 || totalBytes > 75L * 1024L * 1024L) f.delete()
            }
        }
    }

    /**
     * Fast background audio pre-cacher for upcoming queue tracks so track-to-track transitions are 0ms instant!
     */
    suspend fun precacheAudioTrack(track: Track, quality: AudioQuality): Boolean = withContext(Dispatchers.IO) {
        if (track.source != StreamSource.YOUTUBE) return@withContext false
        if (getOfflineFile(track.id).let { it.exists() && it.length() > 16_384 }) return@withContext true
        val target = getPrecachedAudioFile(track.id)
        if (target.exists() && target.length() > 48_000) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext true
        }
        // Try Micro-Burst on ANDROID_VR first, then IOS HLS Turbo!
        val vrAudio = runCatching { fetchPureInnerTubeAudio(track.id, "ANDROID_VR", quality, preferHls = false) }.getOrNull()
        if (vrAudio != null && !vrAudio.audioUrl.contains(".m3u8", true)) {
            val clen = Regex("[?&]clen=(\\d+)").find(vrAudio.audioUrl)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
            if (clen in 1L..(16L * 1024L * 1024L)) {
                val ok = downloadMicroBurstToDisk(vrAudio.audioUrl, vrAudio.userAgent, vrAudio.cookie, emptyMap(), target, false, 6, 256 * 1024L) {}
                if (ok) { trimStreamPrecache(); return@withContext true }
            }
        }
        val iosAudio = runCatching { fetchPureInnerTubeAudio(track.id, "IOS", quality, preferHls = true) }.getOrNull()
        if (iosAudio != null && iosAudio.audioUrl.contains(".m3u8", true)) {
            val ok = downloadHlsAudioOnlyParallel(iosAudio.audioUrl, iosAudio.userAgent, iosAudio.cookie, target, 6) {}
            if (ok) { trimStreamPrecache(); return@withContext true }
        }
        false
    }

    /**
     * Main pure-audio offline download entry point with user-selectable DownloadMethod!
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
        val highWaterPct = AtomicInteger(0)
        val reportRealProgress: (Int) -> Unit = { pct ->
            val clamped = pct.coerceIn(1, 100)
            while (true) {
                val cur = highWaterPct.get()
                if (clamped <= cur) break
                if (highWaterPct.compareAndSet(cur, clamped)) {
                    onProgress(clamped)
                    break
                }
            }
        }

        // Instant zero-network copy from rolling precache if already on disk!
        val cached = getPrecachedAudioFile(track.id)
        if (cached.exists() && cached.length() > 48_000) {
            val copied = runCatching {
                cached.copyTo(target, overwrite = true)
                reportRealProgress(100)
                true
            }.getOrDefault(false)
            if (copied) {
                val mb = String.format("%.1f", target.length() / (1024f * 1024f))
                return@withContext true to if (isEn) "Saved instantly ($mb MB)" else "נשמר מיד לאופליין ($mb MB)!"
            }
        }

        val parallel = settings.downloadSpeedMode.parallelChunks
        val quality = settings.streamSource.clampQuality(settings.downloadQuality)

        val result = withTimeoutOrNull(140_000L) {
            reportRealProgress(2)

            if (track.source != StreamSource.YOUTUBE) {
                val res = nonYouTubeResolver?.invoke()
                if (res != null && downloadUrlToFile(res.audioUrl, res.userAgent, res.cookie, emptyMap(), target, false, reportRealProgress)) {
                    val mb = String.format("%.1f", target.length() / (1024f * 1024f))
                    return@withTimeoutOrNull true to if (isEn) "Downloaded ($mb MB)" else "השיר ירד לאופליין ($mb MB)!"
                }
                return@withTimeoutOrNull false to if (isEn) "Download failed from source" else "שגיאה בהורדה מהמקור שנבחר"
            }

            // Build ordered execution plan prioritizing the user's chosen DownloadMethod!
            val plan: List<suspend () -> Boolean> = buildDownloadPlan(
                method = settings.downloadMethod,
                videoId = track.id,
                quality = quality,
                parallel = parallel,
                capturedStreams = capturedStreams,
                target = target,
                onProgress = reportRealProgress
            )

            for (step in plan) {
                val ok = runCatching { step() }.getOrDefault(false)
                if (ok && target.exists() && target.length() > 20_480) {
                    val mb = String.format("%.1f", target.length() / (1024f * 1024f))
                    return@withTimeoutOrNull true to if (isEn) "Downloaded audio ($mb MB)" else "השיר ירד לאופליין ($mb MB)!"
                }
            }

            false to if (isEn) "Download failed — try switching Download Method in Settings" else "ההורדה נכשלה — נסה להחליף שיטת הורדה בהגדרות"
        }

        result ?: (false to if (isEn) "Download timed out — please retry" else "ההורדה לקחה יותר מדי זמן — נסה שוב")
    }

    private fun buildDownloadPlan(
        method: DownloadMethod,
        videoId: String,
        quality: AudioQuality,
        parallel: Int,
        capturedStreams: List<CapturedMediaStream>,
        target: File,
        onProgress: (Int) -> Unit
    ): List<suspend () -> Boolean> {
        val hlsTurboStep: suspend () -> Boolean = {
            val ios = runCatching { fetchPureInnerTubeAudio(videoId, "IOS", quality, preferHls = true) }.getOrNull()
            if (ios != null && ios.audioUrl.contains(".m3u8", true)) {
                downloadHlsAudioOnlyParallel(ios.audioUrl, ios.userAgent, ios.cookie, target, parallel.coerceIn(6, 12), onProgress)
            } else false
        }

        val microBurstStep: suspend () -> Boolean = {
            val stream = runCatching { fetchPureInnerTubeAudio(videoId, "ANDROID_VR", quality, preferHls = false) }.getOrNull()
                ?: runCatching { fetchPureInnerTubeAudio(videoId, "TV_SIMPLY", quality, preferHls = false) }.getOrNull()
            if (stream != null && !stream.audioUrl.contains(".m3u8", true)) {
                val clean = sanitizeYouTubePlaybackUrl(stream.audioUrl)
                downloadMicroBurstToDisk(clean, stream.userAgent, stream.cookie, emptyMap(), target, false, parallel.coerceIn(6, 12), 256 * 1024L, onProgress)
            } else false
        }

        val directStreamStep: suspend () -> Boolean = {
            val stream = runCatching { fetchPureInnerTubeAudio(videoId, "ANDROID_VR", quality, preferHls = false) }.getOrNull()
                ?: runCatching { fetchPureInnerTubeAudio(videoId, "IOS", quality, preferHls = false) }.getOrNull()
            if (stream != null && !stream.audioUrl.contains(".m3u8", true)) {
                val clean = sanitizeYouTubePlaybackUrl(stream.audioUrl)
                downloadUrlToFile(clean, stream.userAgent, stream.cookie, emptyMap(), target, false, onProgress)
            } else false
        }

        val capturedStep: suspend () -> Boolean = {
            var done = false
            for (cap in capturedStreams.filter { it.isAudioOnly }.take(2)) {
                val clean = sanitizeYouTubePlaybackUrl(cap.url)
                if (downloadMicroBurstToDisk(clean, cap.userAgent, cap.cookie, cap.headers, target, true, parallel.coerceIn(4, 10), 256 * 1024L, onProgress) ||
                    downloadUrlToFile(clean, cap.userAgent, cap.cookie, cap.headers, target, true, onProgress)
                ) {
                    done = true; break
                }
            }
            done
        }

        val ytDlpStep: suspend () -> Boolean = {
            downloadViaYtDlpAudioOnly(videoId, quality, parallel, target, onProgress)
        }

        val proxyStep: suspend () -> Boolean = {
            var done = false
            val mirrors = listOf(
                { fetchCobaltPureAudio(videoId) },
                { fetchPipedPureAudio(videoId, quality) },
                { fetchInvidiousPureAudio(videoId) }
            )
            for (m in mirrors) {
                val res = runCatching { m() }.getOrNull() ?: continue
                if (downloadUrlToFile(res.audioUrl, res.userAgent, res.cookie, emptyMap(), target, false, onProgress)) {
                    done = true; break
                }
            }
            done
        }

        return when (method) {
            DownloadMethod.AUTO_SMART -> listOf(hlsTurboStep, microBurstStep, capturedStep, ytDlpStep, proxyStep)
            DownloadMethod.HLS_TURBO -> listOf(hlsTurboStep, microBurstStep, ytDlpStep)
            DownloadMethod.MICRO_BURST -> listOf(microBurstStep, hlsTurboStep, ytDlpStep)
            DownloadMethod.INNERTUBE_DIRECT -> listOf(directStreamStep, microBurstStep, hlsTurboStep, ytDlpStep)
            DownloadMethod.YTDLP_ENGINE -> listOf(ytDlpStep, hlsTurboStep, microBurstStep)
            DownloadMethod.PROXY_FAST -> listOf(proxyStep, hlsTurboStep, microBurstStep, ytDlpStep)
        }
    }

    private fun fetchPureInnerTubeAudio(videoId: String, clientType: String, quality: AudioQuality, preferHls: Boolean): ResolvedStreams? {
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
            "TV_SIMPLY" -> Quad(
                JSONObject().apply {
                    put("clientName", "TVHTML5_SIMPLY_EMBEDDED_PLAYER"); put("clientVersion", "2.0")
                    put("hl", "he"); put("gl", "IL")
                },
                "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version",
                "85", "2.0"
            )
            else -> Quad(
                JSONObject().apply {
                    put("clientName", "IOS"); put("clientVersion", "20.14.2")
                    put("deviceMake", "Apple"); put("deviceModel", "iPhone16,2")
                    put("osName", "iPhone"); put("osVersion", "18.4.0.22E240"); put("hl", "he"); put("gl", "IL")
                },
                "com.google.ios.youtube/20.14.2 (iPhone16,2; U; CPU iOS 18_4_0 like Mac OS X)",
                "5", "20.14.2"
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
            put("downloadMode", "audio"); put("audioFormat", "mp3"); put("audioBitrate", "128")
        }.toString()
        for (base in cobaltInstances) {
            val res = runCatching {
                val req = Request.Builder().url("$base/").post(body.toRequestBody("application/json".toMediaType()))
                    .header("Accept", "application/json").header("User-Agent", ua).build()
                proxyClient.newCall(req).execute().use { resp ->
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
                proxyClient.newCall(Request.Builder().url("$base/streams/$videoId").header("User-Agent", ua).build()).execute().use { resp ->
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
                return extractHlsAudioPlaylistUrl(manifest) != null
            }
            val probeUrl = if (url.contains("googlevideo.com") && !url.contains("range=")) {
                val sep = if (url.contains("?")) "&" else "?"
                "${url}${sep}range=0-16383"
            } else url
            val rb = Request.Builder().url(probeUrl).header("User-Agent", ua)
            if (!probeUrl.contains("range=")) rb.header("Range", "bytes=0-16383")
            fastClient.newCall(rb.build()).execute().use { resp ->
                if (resp.code !in listOf(200, 206)) return false
                val ct = resp.header("Content-Type").orEmpty().lowercase()
                if (ct.startsWith("video/") || ct.contains("text/html") || ct.contains("application/json") || ct.contains("yt-ump")) return false
                val buf = ByteArray(1024)
                (resp.body?.byteStream()?.read(buf) ?: 0) > 64
            }
        }.getOrDefault(false)
    }

    private fun extractHlsAudioPlaylistUrl(manifest: String): String? {
        val audioCandidates = mutableListOf<String>()
        manifest.lines().forEach { line ->
            if (line.contains("TYPE=AUDIO", true) && line.contains("URI=", true)) {
                val m = Regex("URI=[\"'](https?://[^\"']+)[\"']").find(line)
                if (m != null) audioCandidates.add(m.groupValues[1])
            }
        }
        return audioCandidates.lastOrNull()
    }

    private fun pickTargetBitrate(list: List<Pair<Int, String>>, quality: AudioQuality): String? {
        if (list.isEmpty()) return null
        val sorted = list.sortedBy { it.first }
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
            val qualityFormat = when (quality) {
                AudioQuality.LOW -> "worstaudio[vcodec=none][ext=m4a]/worstaudio[vcodec=none]/worstaudio"
                AudioQuality.NORMAL -> "bestaudio[vcodec=none][abr<=100][ext=m4a]/bestaudio[vcodec=none][abr<=100]/bestaudio[vcodec=none]"
                AudioQuality.HIGH -> "bestaudio[vcodec=none][abr<=145][ext=m4a]/bestaudio[vcodec=none][abr<=145]/bestaudio[vcodec=none]"
                AudioQuality.VERY_HIGH, AudioQuality.LOSSLESS -> "bestaudio[vcodec=none][ext=m4a]/bestaudio[vcodec=none][ext=webm]/bestaudio[vcodec=none]"
            }
            val attempts = listOf(
                qualityFormat to null,
                "bestaudio[vcodec=none]/bestaudio[abr<=192]" to "youtube:player_client=android_vr,ios,web"
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
                    addOption("--retries", "3")
                    addOption("--fragment-retries", "3")
                    addOption("--socket-timeout", "15")
                    addOption("--concurrent-fragments", parallelCount.coerceIn(4, 10).toString())
                    addOption("-f", fmt)
                    if (extractorArgs != null) addOption("--extractor-args", extractorArgs)
                    addOption("-o", tmpFile.absolutePath)
                }

                val ok = runCatching {
                    YoutubeDL.getInstance().execute(request, "sonify_dl_$videoId") { progress: Float, _: Long, _: String ->
                        if (progress > 0f && progress <= 100f) onProgress(progress.toInt().coerceIn(5, 98))
                    }
                    val produced = if (tmpFile.exists() && tmpFile.length() > 20_480) tmpFile
                    else target.parentFile?.listFiles()?.firstOrNull { it.name.startsWith(tmpPrefix) && it.length() > 20_480 }
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
                val audioUri = extractHlsAudioPlaylistUrl(playlistText) ?: return false
                playlistText = fetchText(audioUri) ?: return false
            }
            val segments = playlistText.lines().map { it.trim() }.filter { it.startsWith("http") }
            if (segments.isEmpty()) return false

            var completed = 0
            val workers = parallel.coerceIn(4, 12)
            FileOutputStream(tmp).use { out ->
                for (batch in segments.chunked(workers)) {
                    val fetched = coroutineScope {
                        batch.map { segUrl ->
                            async(Dispatchers.IO) {
                                var data: ByteArray? = null
                                for (attempt in 0..2) {
                                    data = runCatching {
                                        downloadClient.newCall(buildMediaRequest(segUrl, userAgent, cookie, emptyMap(), false)).execute().use { r ->
                                            if (r.isSuccessful) r.body?.bytes() else null
                                        }
                                    }.getOrNull()
                                    if (data != null && data.isNotEmpty()) break
                                    delay(200L * (attempt + 1))
                                }
                                data
                            }
                        }.awaitAll()
                    }
                    for (bytes in fetched) {
                        if (bytes == null || bytes.isEmpty()) { tmp.delete(); return false }
                        out.write(bytes)
                        completed++
                    }
                    onProgress((completed * 98 / segments.size).coerceIn(5, 98))
                }
            }
            if (tmp.exists() && tmp.length() > 20_480) {
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
                if (tmp.exists() && tmp.length() > 20_480) {
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

    /**
     * Micro-Burst Chunked Downloader: splits audio into small 256KB blocks across 6–12 parallel connections
     * so every single request rides YouTube's unthrottled initial burst window!
     */
    private suspend fun downloadMicroBurstToDisk(
        baseUrl: String, userAgent: String, cookie: String?, extraHeaders: Map<String, String>,
        target: File, isWebOrigin: Boolean, parallel: Int, blockSizeBytes: Long, onProgress: (Int) -> Unit
    ): Boolean {
        val clen = Regex("[?&]clen=(\\d+)").find(baseUrl)?.groupValues?.get(1)?.toLongOrNull() ?: return false
        if (clen <= 0L || clen > 75_000_000L) return false

        val tmp = File(target.parentFile, "${target.name}.${System.nanoTime()}.tmp")
        return try {
            val workers = parallel.coerceIn(4, 12)
            val chunkSize = blockSizeBytes.coerceIn(192 * 1024L, 512 * 1024L)
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

            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(clen)

                suspend fun streamRangeToDisk(start: Long, end: Long): Boolean {
                    var activeUrl = baseUrl
                    for (attempt in 0..2) {
                        if (aborted.get()) return false
                        val sep = if (activeUrl.contains("?")) "&" else "?"
                        val req = buildMediaRequest("$activeUrl${sep}range=$start-$end", userAgent, cookie, extraHeaders, isWebOrigin)
                        var writtenThisAttempt = 0L
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
                                        writtenThisAttempt += n
                                        val writtenNow = totalWritten.addAndGet(n.toLong())
                                        onProgress(((writtenNow * 98) / clen).toInt().coerceIn(5, 98))
                                    }
                                }
                                if (pos >= end) 1 else -1
                            }
                        }.getOrDefault(-1)
                        if (result == 1) return true
                        if (writtenThisAttempt > 0) totalWritten.addAndGet(-writtenThisAttempt)
                        if (aborted.get()) return false
                        delay(250L * (attempt + 1))
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

            if (tmp.exists() && tmp.length() > 20_480) {
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
