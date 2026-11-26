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
        .readTimeout(15, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val pipedInstances = listOf(
        "https://pipedapi.kavin.rocks",
        "https://pipedapi.adminforge.de",
        "https://api.piped.private.coffee",
        "https://pipedapi.leptons.xyz"
    )

    private val offlineDir: File
        get() = File(context.filesDir, "offline_tracks").apply { if (!exists()) mkdirs() }

    fun getOfflineFile(trackId: String): File {
        val safe = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(offlineDir, "$safe.media")
    }

    fun isTrackOffline(trackId: String): Boolean {
        val f = getOfflineFile(trackId)
        return f.exists() && f.length() > 1024
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
        podcastsOnly: Boolean = false
    ): List<Track> = withContext(Dispatchers.IO) {
        val cleanQuery = if (podcastsOnly && !query.contains("podcast", true) && !query.contains("פודקאסט")) {
            "$query podcast"
        } else query

        when (settings.streamSource) {
            StreamSource.YOUTUBE -> searchYouTube(cleanQuery, podcastsOnly)
            StreamSource.OPENSUBSONIC -> searchSubsonic(cleanQuery, settings)
            StreamSource.LOCAL -> searchLocalAudio(cleanQuery)
        }
    }

    private fun searchYouTube(query: String, isPodcast: Boolean): List<Track> {
        // 1) Direct YouTube InnerTube Web Search (fast & no API key needed)
        val innerTubeResults = runCatching { searchInnerTube(query, isPodcast) }.getOrDefault(emptyList())
        if (innerTubeResults.isNotEmpty()) return innerTubeResults

        // 2) Fallback to Piped instances
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
                            if (id.isBlank()) continue
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
            out.distinctBy { it.id }.take(20)
        }
    }

    private fun extractVideoRenderers(node: Any?, out: MutableList<Track>, isPodcast: Boolean) {
        if (out.size >= 25 || node == null) return
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

    suspend fun resolveStreams(track: Track, settings: AppSettings): ResolvedStreams? = withContext(Dispatchers.IO) {
        // 1) Offline downloaded file first
        val offlineFile = getOfflineFile(track.id)
        if (offlineFile.exists() && offlineFile.length() > 1024) {
            return@withContext ResolvedStreams(
                audioUrl = offlineFile.toURI().toString(),
                videoUrl = null,
                isLossless = track.source != StreamSource.YOUTUBE,
                isOffline = true,
                codecLabel = "OFFLINE"
            )
        }

        // 2) Local device file
        if (track.source == StreamSource.LOCAL && !track.localUri.isNullOrBlank()) {
            val isFlac = track.title.endsWith(".flac", true) || track.album.contains("FLAC", true)
            return@withContext ResolvedStreams(
                audioUrl = track.localUri,
                videoUrl = null,
                isLossless = isFlac,
                codecLabel = if (isFlac) "FLAC Lossless" else "LOCAL"
            )
        }

        // 3) OpenSubsonic / Navidrome server (True Lossless FLAC)
        if (track.source == StreamSource.OPENSUBSONIC && settings.subsonicUrl.isNotBlank()) {
            val auth = buildSubsonicAuth(settings.subsonicUser, settings.subsonicPass)
            val formatParam = if (settings.quality == AudioQuality.LOSSLESS) "format=raw" else "maxBitRate=${settings.quality.kbps}"
            val base = settings.subsonicUrl.trimEnd('/')
            val url = "$base/rest/stream.view?id=${URLEncoder.encode(track.id, "UTF-8")}&$formatParam&$auth"
            return@withContext ResolvedStreams(
                audioUrl = url,
                videoUrl = null,
                isLossless = settings.quality == AudioQuality.LOSSLESS,
                codecLabel = if (settings.quality == AudioQuality.LOSSLESS) "FLAC Lossless" else "${settings.quality.kbps}kbps"
            )
        }

        // 4) YouTube Audio + Video Clip resolution
        resolveYouTubeStreams(track.id, settings.quality)
    }

    private fun resolveYouTubeStreams(videoId: String, quality: AudioQuality): ResolvedStreams? {
        // Try InnerTube ANDROID_VR & IOS clients (direct non-deciphered URLs)
        val clients = listOf(
            Triple("ANDROID_VR", "1.60.19", "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"),
            Triple("IOS", "20.03.02", "com.google.ios.youtube/20.03.02 (iPhone16,2; U; CPU iOS 18_2 like Mac OS X)")
        )
        for ((clientName, clientVersion, ua) in clients) {
            val resolved = runCatching { fetchInnerTubePlayer(videoId, clientName, clientVersion, ua, quality) }.getOrNull()
            if (resolved != null) return resolved
        }

        // Fallback: Piped API streams
        for (base in pipedInstances) {
            val resolved = runCatching {
                val req = Request.Builder()
                    .url("$base/streams/$videoId")
                    .header("User-Agent", "Mozilla/5.0")
                    .build()
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

                    var chosenVideo: String? = hls
                    if (videoArr != null) {
                        for (i in 0 until videoArr.length()) {
                            val v = videoArr.optJSONObject(i) ?: continue
                            val videoOnly = v.optBoolean("videoOnly", true)
                            val u = v.optString("url")
                            if (!videoOnly && u.startsWith("http")) {
                                chosenVideo = u
                                break
                            }
                        }
                    }
                    ResolvedStreams(
                        audioUrl = chosenAudio,
                        videoUrl = chosenVideo,
                        isLossless = false,
                        codecLabel = "${quality.kbps.coerceAtMost(256)}kbps"
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
            .build()

        return client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val root = JSONObject(resp.body?.string() ?: return null)
            val sd = root.optJSONObject("streamingData") ?: return null
            val hls = sd.optString("hlsManifestUrl").takeIf { it.startsWith("http") }

            val audioCandidates = mutableListOf<Pair<Int, String>>()
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
                    if (mime.startsWith("audio/") && u.startsWith("http")) {
                        val br = f.optInt("bitrate", 128000) / 1000
                        audioCandidates.add(br to u)
                    }
                }
            }

            val bestAudio = pickAudioByQuality(audioCandidates, quality) ?: hls ?: return null
            val bestVideo = muxedVideoUrl ?: hls
            ResolvedStreams(
                audioUrl = bestAudio,
                videoUrl = bestVideo,
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

    private fun searchSubsonic(query: String, settings: AppSettings): List<Track> {
        if (settings.subsonicUrl.isBlank()) return emptyList()
        return runCatching {
            val base = settings.subsonicUrl.trimEnd('/')
            val auth = buildSubsonicAuth(settings.subsonicUser, settings.subsonicPass)
            val q = URLEncoder.encode(query.ifBlank { "a" }, "UTF-8")
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

    suspend fun fetchSyncedLyrics(artist: String, title: String): List<LyricLine> = withContext(Dispatchers.IO) {
        val cleanTitle = title
            .replace(Regex("\\(.*?\\)|\\[.*?]"), "")
            .replace("Official Video", "", true)
            .replace("Official Audio", "", true)
            .trim()
        val cleanArtist = artist.replace("- Topic", "", true).trim()

        runCatching {
            val url = "https://lrclib.net/api/get?artist_name=${URLEncoder.encode(cleanArtist, "UTF-8")}&track_name=${URLEncoder.encode(cleanTitle, "UTF-8")}"
            val req = Request.Builder().url(url).header("User-Agent", "Sonify/1.0").build()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@use emptyList()
                val json = JSONObject(resp.body?.string() ?: return@use emptyList())
                val synced = json.optString("syncedLyrics")
                if (synced.isNotBlank()) return@use parseLrc(synced)
                val plain = json.optString("plainLyrics")
                if (plain.isNotBlank()) {
                    return@use plain.lines().filter { it.isNotBlank() }.mapIndexed { idx, line ->
                        LyricLine(idx * 3500L, line.trim())
                    }
                }
                emptyList()
            }
        }.getOrDefault(emptyList())
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
                .header("User-Agent", "Mozilla/5.0")
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
