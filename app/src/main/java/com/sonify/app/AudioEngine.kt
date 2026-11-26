@file:OptIn(UnstableApi::class)

package com.sonify.app

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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
    var onProgressUpdate: ((Long, Long, Boolean) -> Unit)? = null

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
        })

        // Start foreground MediaSessionService for background playback
        runCatching {
            context.startService(Intent(context, PlaybackService::class.java))
        }

        // Progress + Crossfade ticker
        scope.launch {
            while (isActive) {
                val pos = player.currentPosition.coerceAtLeast(0L)
                val dur = player.duration.coerceAtLeast(0L)
                onProgressUpdate?.invoke(pos, dur, player.isPlaying)

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
                } else {
                    player.volume = 1f
                }
                delay(350)
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
                    val norm = settings.eqBands[i] // -10..+10
                    val lvl = ((norm / 10f) * (maxLvl - minLvl) / 2f).toInt().coerceIn(minLvl, maxLvl)
                    eq.setBandLevel(i.toShort(), lvl.toShort())
                }
            }
        }
    }

    fun playMedia(url: String, track: Track, startPositionMs: Long = 0L, playImmediately: Boolean = true) {
        val metadata = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .build()
        val item = MediaItem.Builder()
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
        player.setMediaItem(item, startPositionMs)
        player.prepare()
        player.playWhenReady = playImmediately
        if (player.audioSessionId > 0 && equalizer == null) {
            attachAudioEffects(player.audioSessionId)
        }
    }

    fun seamlessSwitchStream(newUrl: String, track: Track) {
        val currentPos = player.currentPosition.coerceAtLeast(0L)
        val wasPlaying = player.isPlaying
        playMedia(newUrl, track, startPositionMs = currentPos, playImmediately = wasPlaying)
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun startSleepTimer(minutes: Int, onRemainingChange: (Int?) -> Unit) {
        sleepJob?.cancel()
        sleepEndOfTrack = false
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
        sleepEndOfTrack = enabled
    }

    fun getOutputDevices(): List<AudioOutputItem> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return devices.mapNotNull { d ->
            val typeLabel = when (d.type) {
                AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "רמקול הטלפון"
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "אוזניות Bluetooth"
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> "אוזניות חוטיות (Lossless)"
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> "USB-C DAC / אוזניות (Lossless)"
                else -> return@mapNotNull null
            }
            AudioOutputItem(
                id = d.id,
                name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeLabel,
                typeLabel = typeLabel,
                isActive = d.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    d.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                    d.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                    d.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER
            )
        }.distinctBy { it.typeLabel }
    }
}
