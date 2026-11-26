@file:androidx.annotation.OptIn(UnstableApi::class)

package com.sonify.app

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.provider.Settings
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

object PlayerHolder {
    @Volatile
    private var player: ExoPlayer? = null

    fun getOrCreate(context: Context): ExoPlayer {
        return player ?: synchronized(this) {
            player ?: ExoPlayer.Builder(context.applicationContext)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(C.USAGE_MEDIA)
                        .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    true
                )
                .setHandleAudioBecomingNoisy(true)
                .build()
                .also { player = it }
        }
    }
}

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val exoPlayer = PlayerHolder.getOrCreate(this)
        mediaSession = MediaSession.Builder(this, exoPlayer).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}

class AudioEngine(private val context: Context) {

    val player: ExoPlayer = PlayerHolder.getOrCreate(context)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var lastSettings: AppSettings = AppSettings()

    private var sleepJob: Job? = null
    var sleepEndOfTrack: Boolean = false
        private set

    var onTrackEnded: (() -> Unit)? = null
    var onPlaybackError: ((String) -> Unit)? = null
    var onProgressUpdate: ((Long, Long, Boolean, Boolean) -> Unit)? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                attachAudioEffects(audioSessionId)
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    if (sleepEndOfTrack) {
                        sleepEndOfTrack = false
                        player.pause()
                    } else {
                        onTrackEnded?.invoke()
                    }
                }
            }

            override fun onPlayerError(error: PlaybackException) {
                onPlaybackError?.invoke(error.errorCodeName)
            }
        })

        runCatching {
            context.startService(Intent(context, PlaybackService::class.java))
        }

        scope.launch {
            while (isActive) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration.coerceAtLeast(0L)
                val buffering = player.playbackState == Player.STATE_BUFFERING
                onProgressUpdate?.invoke(pos, dur, player.isPlaying, buffering)

                val cfMs = lastSettings.crossfadeSeconds * 1000L
                if (cfMs > 0 && dur > cfMs && player.isPlaying) {
                    val remaining = dur - pos
                    if (remaining in 1..cfMs) {
                        player.volume = (remaining.toFloat() / cfMs.toFloat()).coerceIn(0.05f, 1f)
                    } else if (pos < cfMs / 2) {
                        player.volume = (pos.toFloat() / (cfMs / 2f)).coerceIn(0.15f, 1f)
                    } else {
                        player.volume = 1f
                    }
                } else if (sleepJob == null || !sleepJob!!.isActive) {
                    player.volume = 1f
                }
                delay(300)
            }
        }
    }

    private fun attachAudioEffects(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return
        runCatching {
            equalizer?.release()
            equalizer = Equalizer(0, sessionId)
        }
        runCatching {
            loudnessEnhancer?.release()
            loudnessEnhancer = LoudnessEnhancer(sessionId)
        }
        applySettings(lastSettings)
    }

    fun applySettings(settings: AppSettings) {
        lastSettings = settings
        runCatching {
            loudnessEnhancer?.apply {
                enabled = settings.normalizeVolume
                setTargetGain(if (settings.normalizeVolume) 250 else 0)
            }
        }
        runCatching {
            equalizer?.let { eq ->
                eq.enabled = settings.eqEnabled
                val bands = eq.numberOfBands.toInt()
                val range = eq.bandLevelRange
                val minLvl = range[0].toInt()
                val maxLvl = range[1].toInt()
                for (i in 0 until bands.coerceAtMost(settings.eqBands.size)) {
                    val norm = settings.eqBands[i]
                    val lvl = ((norm / 10f) * (maxLvl - minLvl) / 2f).toInt().coerceIn(minLvl, maxLvl)
                    eq.setBandLevel(i.toShort(), lvl.toShort())
                }
            }
        }
    }

    private fun buildSingleSource(url: String, metadata: MediaMetadata, userAgent: String): MediaSource {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12_000)
            .setReadTimeoutMs(15_000)
        val dataSourceFactory = DefaultDataSource.Factory(context, httpFactory)
        val item = MediaItem.Builder().setUri(url).setMediaMetadata(metadata).build()
        return if (url.contains(".m3u8", ignoreCase = true) || url.contains("manifest/hls", ignoreCase = true)) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)
        }
    }

    fun playResolved(
        resolved: ResolvedStreams,
        track: Track,
        videoMode: Boolean,
        startPositionMs: Long = 0L,
        playImmediately: Boolean = true
    ) {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .build()

        val source: MediaSource = if (videoMode) {
            when {
                !resolved.videoUrl.isNullOrBlank() ->
                    buildSingleSource(resolved.videoUrl, metadata, resolved.userAgent)
                !resolved.videoOnlyUrl.isNullOrBlank() -> {
                    val vSrc = buildSingleSource(resolved.videoOnlyUrl, metadata, resolved.userAgent)
                    val aSrc = buildSingleSource(resolved.audioUrl, metadata, resolved.userAgent)
                    MergingMediaSource(vSrc, aSrc)
                }
                else -> buildSingleSource(resolved.audioUrl, metadata, resolved.userAgent)
            }
        } else {
            buildSingleSource(resolved.audioUrl, metadata, resolved.userAgent)
        }

        player.setMediaSource(source, startPositionMs)
        player.prepare()
        player.playWhenReady = playImmediately
        if (player.audioSessionId > 0 && equalizer == null) {
            attachAudioEffects(player.audioSessionId)
        }
    }

    fun seamlessSwitchVideoMode(resolved: ResolvedStreams, track: Track, toVideo: Boolean) {
        val currentPos = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.isPlaying || player.playWhenReady
        playResolved(resolved, track, videoMode = toVideo, startPositionMs = currentPos, playImmediately = wasPlaying)
    }

    fun togglePlayPause(onNeedRetry: () -> Unit) {
        when {
            player.playbackState == Player.STATE_IDLE -> onNeedRetry()
            player.playbackState == Player.STATE_ENDED -> {
                player.seekTo(0L)
                player.play()
            }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun startSleepTimer(minutes: Int, onRemainingChange: (Int?) -> Unit) {
        sleepJob?.cancel()
        sleepJob = null
        sleepEndOfTrack = false
        player.volume = 1f
        if (minutes <= 0) {
            onRemainingChange(null)
            return
        }
        sleepJob = scope.launch {
            var remainingSec = minutes * 60
            while (remainingSec > 0 && isActive) {
                onRemainingChange((remainingSec + 59) / 60)
                if (remainingSec <= 5) {
                    player.volume = (remainingSec / 5f).coerceIn(0.05f, 1f)
                }
                delay(1000)
                remainingSec--
            }
            player.pause()
            player.volume = 1f
            onRemainingChange(null)
        }
    }

    fun setSleepEndOfTrack(enabled: Boolean) {
        sleepJob?.cancel()
        sleepJob = null
        sleepEndOfTrack = enabled
    }

    fun openSystemAudioSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }.onFailure {
            runCatching {
                val intent = Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }
        }
    }

    fun getOutputDevices(): List<AudioOutputItem> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasExternal = devices.any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                it.type == AudioDeviceInfo.TYPE_USB_DEVICE
        }
        return devices.mapNotNull { d ->
            val typeLabel = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "רמקול הטלפון"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "אוזניות Bluetooth"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "אוזניות חוטיות (Lossless)"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C DAC / אוזניות (Lossless)"
                else -> return@mapNotNull null
            }
            val active = if (hasExternal) d.type != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER else d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            AudioOutputItem(
                id = d.id,
                name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeLabel,
                typeLabel = typeLabel,
                isActive = active
            )
        }.distinctBy { it.typeLabel }
    }
}
