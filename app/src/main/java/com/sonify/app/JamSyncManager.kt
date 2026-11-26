package com.sonify.app

import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.UUID
import java.util.concurrent.TimeUnit

data class JamRemoteEvent(
    val type: String,
    val senderId: String,
    val senderName: String,
    val sentAtMs: Long,
    val track: Track? = null,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val guestsCanControlQueue: Boolean = true,
    val guestsCanControlVolume: Boolean = false,
    val volumeFraction: Float? = null
)

sealed class ParsedSonifyLink {
    data class JamLink(val code: String, val trackId: String? = null, val startSec: Int = 0) : ParsedSonifyLink()
    data class TrackLink(val videoId: String, val startSec: Int = 0) : ParsedSonifyLink()
    data class PlaylistLink(val playlistId: String) : ParsedSonifyLink()
    data class SearchQuery(val query: String) : ParsedSonifyLink()
}

object SonifyLinkCodec {

    fun buildSmartJamLink(code: String, track: Track?, positionMs: Long): String {
        val cleanCode = code.trim().uppercase()
        if (track == null || track.source != StreamSource.YOUTUBE || track.id.length != 11) {
            return "sonify:jam:$cleanCode"
        }
        val sec = (positionMs / 1000L).coerceAtLeast(0L)
        return "sonify:jam:$cleanCode?v=${track.id}&t=$sec"
    }

    fun buildSmartTrackLink(track: Track, positionMs: Long): String {
        val sec = (positionMs / 1000L).coerceAtLeast(0L)
        return if (sec > 3) "sonify:track:${track.id}?t=$sec" else "sonify:track:${track.id}"
    }

    fun parse(rawInput: String): ParsedSonifyLink {
        val input = rawInput.trim()
        if (input.isBlank()) return ParsedSonifyLink.SearchQuery("")

        if (input.startsWith("sonify:jam:", ignoreCase = true)) {
            val after = input.substringAfter("sonify:jam:")
            val code = after.substringBefore("?").trim().uppercase()
            val queryPart = after.substringAfter("?", "")
            val vId = Regex("(?:^|&)v=([a-zA-Z0-9_-]{11})").find(queryPart)?.groupValues?.get(1)
            val tSec = Regex("(?:^|&)t=(\\d+)").find(queryPart)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (code.length >= 4) return ParsedSonifyLink.JamLink(code, vId, tSec)
        }

        if (input.startsWith("sonify:track:", ignoreCase = true)) {
            val after = input.substringAfter("sonify:track:")
            val id = after.substringBefore("?").trim()
            val queryPart = after.substringAfter("?", "")
            val tSec = Regex("(?:^|&)t=(\\d+)").find(queryPart)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (id.isNotBlank()) return ParsedSonifyLink.TrackLink(id, tSec)
        }

        if (input.startsWith("sonify:playlist:", ignoreCase = true)) {
            val pid = input.substringAfter("sonify:playlist:").substringBefore("?").trim()
            if (pid.isNotBlank()) return ParsedSonifyLink.PlaylistLink(pid)
        }

        if (input.contains("list=", ignoreCase = true) && !input.contains("watch?v=", ignoreCase = true)) {
            val pid = input.substringAfter("list=").substringBefore("&").trim()
            if (pid.isNotBlank()) return ParsedSonifyLink.PlaylistLink(pid)
        }

        if (input.contains("youtu.be/", ignoreCase = true) || input.contains("youtube.com/watch", ignoreCase = true)) {
            val uri = runCatching { Uri.parse(input) }.getOrNull()
            val vid = when {
                input.contains("youtu.be/", ignoreCase = true) ->
                    input.substringAfter("youtu.be/").substringBefore("?").substringBefore("/").take(11)
                else -> uri?.getQueryParameter("v")?.take(11).orEmpty()
            }
            val rawT = uri?.getQueryParameter("t").orEmpty().replace("s", "", true)
            val tSec = rawT.toIntOrNull() ?: 0
            if (vid.length == 11) return ParsedSonifyLink.TrackLink(vid, tSec)
        }

        if (input.matches(Regex("^[A-Za-z0-9]{6}$"))) {
            return ParsedSonifyLink.JamLink(input.uppercase())
        }

        return ParsedSonifyLink.SearchQuery(input)
    }
}

class JamSyncManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val streamClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val postClient = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(10, TimeUnit.SECONDS)
        .build()

    val clientId: String = UUID.randomUUID().toString().take(8)
    private var listenJob: Job? = null
    private var activeCall: okhttp3.Call? = null

    @Volatile private var currentTopic: String = ""
    @Volatile private var currentBaseUrl: String = "https://ntfy.sh"
    @Volatile private var isConnectedFlag: Boolean = false

    fun connect(
        roomCode: String,
        serverBaseUrl: String,
        senderName: String,
        onConnectionChanged: (Boolean) -> Unit,
        onEvent: (JamRemoteEvent) -> Unit
    ) {
        disconnect(senderName, sendLeave = false)
        val cleanCode = roomCode.trim().uppercase().replace(Regex("[^A-Z0-9]"), "")
        if (cleanCode.length < 4) return

        currentBaseUrl = serverBaseUrl.trim().trimEnd('/').ifBlank { "https://ntfy.sh" }
        currentTopic = "sonify_jam_v10_$cleanCode"

        listenJob = scope.launch {
            var retryDelayMs = 1500L
            while (isActive && currentTopic.isNotBlank()) {
                val url = "$currentBaseUrl/$currentTopic/json?since=45s"
                val req = Request.Builder()
                    .url(url)
                    .header("User-Agent", "Sonify-Jam/10.0")
                    .build()

                try {
                    val call = streamClient.newCall(req)
                    activeCall = call
                    call.execute().use { resp ->
                        if (!resp.isSuccessful) {
                            if (isConnectedFlag) {
                                isConnectedFlag = false
                                onConnectionChanged(false)
                            }
                            delay(retryDelayMs)
                            retryDelayMs = (retryDelayMs * 2).coerceAtMost(10_000L)
                            return@use
                        }

                        retryDelayMs = 1500L
                        val body = resp.body ?: return@use
                        val reader = BufferedReader(InputStreamReader(body.byteStream(), Charsets.UTF_8))
                        while (isActive) {
                            val line = reader.readLine() ?: break
                            if (line.isBlank()) continue
                            val envelope = runCatching { JSONObject(line) }.getOrNull() ?: continue
                            when (envelope.optString("event")) {
                                "open" -> {
                                    if (!isConnectedFlag) {
                                        isConnectedFlag = true
                                        onConnectionChanged(true)
                                        publish(JamRemoteEvent("JOIN", clientId, senderName, System.currentTimeMillis()))
                                    }
                                }
                                "message" -> {
                                    val msgStr = envelope.optString("message")
                                    parsePayload(msgStr)?.let { ev ->
                                        if (ev.senderId != clientId) onEvent(ev)
                                    }
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    if (isConnectedFlag) {
                        isConnectedFlag = false
                        onConnectionChanged(false)
                    }
                    delay(retryDelayMs)
                    retryDelayMs = (retryDelayMs * 2).coerceAtMost(10_000L)
                }
            }
        }
    }

    fun publish(event: JamRemoteEvent) {
        val topic = currentTopic
        val base = currentBaseUrl
        if (topic.isBlank()) return

        val payload = JSONObject().apply {
            put("type", event.type)
            put("sid", event.senderId)
            put("sname", event.senderName.take(24))
            put("ts", event.sentAtMs)
            put("pos", event.positionMs)
            put("play", event.isPlaying)
            put("gq", event.guestsCanControlQueue)
            put("gv", event.guestsCanControlVolume)
            event.volumeFraction?.let { put("vol", it.toDouble()) }
            event.track?.let { t ->
                put("track", JSONObject().apply {
                    put("id", t.id)
                    put("title", t.title)
                    put("artist", t.artist)
                    put("art", t.artworkUrl)
                    put("dur", t.durationMs)
                    put("src", t.source.name)
                })
            }
        }.toString()

        scope.launch {
            runCatching {
                val req = Request.Builder()
                    .url("$base/$topic")
                    .post(payload.toRequestBody("text/plain; charset=utf-8".toMediaType()))
                    .header("User-Agent", "Sonify-Jam/10.0")
                    .build()
                postClient.newCall(req).execute().close()
            }
        }
    }

    fun disconnect(senderName: String = "", sendLeave: Boolean = true) {
        if (sendLeave && currentTopic.isNotBlank() && senderName.isNotBlank()) {
            publish(JamRemoteEvent("LEAVE", clientId, senderName, System.currentTimeMillis()))
        }
        currentTopic = ""
        isConnectedFlag = false
        runCatching { activeCall?.cancel() }
        activeCall = null
        listenJob?.cancel()
        listenJob = null
    }

    private fun parsePayload(raw: String): JamRemoteEvent? {
        if (!raw.trimStart().startsWith("{")) return null
        return runCatching {
            val o = JSONObject(raw)
            val type = o.optString("type")
            val sid = o.optString("sid")
            if (type.isBlank() || sid.isBlank()) return null

            val trkObj = o.optJSONObject("track")
            val trk = trkObj?.let { t ->
                val id = t.optString("id")
                if (id.isBlank()) null
                else Track(
                    id = id,
                    title = t.optString("title", "Shared Track"),
                    artist = t.optString("artist", "Artist"),
                    artworkUrl = upgradeArtworkUrlGlobal(t.optString("art")),
                    durationMs = t.optLong("dur", 0L),
                    source = runCatching { StreamSource.valueOf(t.optString("src")) }.getOrDefault(StreamSource.YOUTUBE)
                )
            }

            JamRemoteEvent(
                type = type,
                senderId = sid,
                senderName = o.optString("sname", "Guest"),
                sentAtMs = o.optLong("ts", System.currentTimeMillis()),
                track = trk,
                positionMs = o.optLong("pos", 0L),
                isPlaying = o.optBoolean("play", false),
                guestsCanControlQueue = o.optBoolean("gq", true),
                guestsCanControlVolume = o.optBoolean("gv", false),
                volumeFraction = if (o.has("vol")) o.optDouble("vol").toFloat() else null
            )
        }.getOrNull()
    }
}
