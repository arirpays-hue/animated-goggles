@file:androidx.annotation.OptIn(UnstableApi::class)

package com.sonify.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MergingMediaSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

class WebYouTubeEngine(private val context: Context) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val capturedMap = ConcurrentHashMap<String, MutableList<CapturedMediaStream>>()

    private val adBlockedHosts = listOf(
        "doubleclick.net",
        "googlesyndication.com",
        "googleadservices.com",
        "pagead2.",
        "/api/stats/ads",
        "/pagead/",
        "adservice.google"
    )

    @Volatile private var webView: WebView? = null
    @Volatile private var activeVideoId: String = ""
    @Volatile private var currentPosMs: Long = 0L
    @Volatile private var currentDurMs: Long = 0L
    @Volatile private var isPlayingNow: Boolean = false
    @Volatile private var isBufferingNow: Boolean = false
    @Volatile private var currentRate: Float = 1.0f
    @Volatile private var currentVolume: Float = 1.0f
    @Volatile private var activeMode: Boolean = false

    var onStateChanged: ((posMs: Long, durMs: Long, playing: Boolean, buffering: Boolean) -> Unit)? = null
    var onEnded: (() -> Unit)? = null

    fun isActive(): Boolean = activeMode
    fun isPlaying(): Boolean = activeMode && isPlayingNow
    fun currentPositionMs(): Long = currentPosMs

    fun getCapturedStreams(videoId: String): List<CapturedMediaStream> =
        capturedMap[videoId]?.toList().orEmpty()

    @SuppressLint("SetJavaScriptEnabled")
    private fun ensureWebView(): WebView {
        webView?.let { return it }
        val wv = WebView(context.applicationContext).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                mediaPlaybackRequiresUserGesture = false
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                cacheMode = WebSettings.LOAD_DEFAULT
                userAgentString = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            }
            addJavascriptInterface(JsBridge(), "SonifyBridge")
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString().orEmpty()
                    if (adBlockedHosts.any { url.contains(it, ignoreCase = true) }) {
                        return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                    }
                    if (url.contains("googlevideo.com/videoplayback", true) && activeVideoId.isNotBlank()) {
                        val isAudio = url.contains("mime=audio", true) || url.contains("mime%3Daudio", true)
                        if (isAudio) {
                            val ua = request?.requestHeaders?.get("User-Agent") ?: settings.userAgentString
                            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                            val list = capturedMap.getOrPut(activeVideoId) { mutableListOf() }
                            synchronized(list) {
                                if (list.none { it.url == url }) {
                                    list.add(0, CapturedMediaStream(activeVideoId, url, ua, cookie, request?.requestHeaders.orEmpty(), true))
                                    if (list.size > 4) list.removeAt(list.lastIndex)
                                }
                            }
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    injectPlayerControllerJs()
                }
            }
        }
        webView = wv
        return wv
    }

    fun attachTo(container: FrameLayout) {
        mainHandler.post {
            val wv = ensureWebView()
            if (wv.parent === container) return@post
            (wv.parent as? ViewGroup)?.removeView(wv)
            container.removeAllViews()
            container.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
    }

    fun loadVideo(videoId: String, startAtMs: Long, @Suppress("UNUSED_PARAMETER") title: String, @Suppress("UNUSED_PARAMETER") artist: String) {
        activeMode = true
        activeVideoId = videoId
        currentPosMs = startAtMs
        isBufferingNow = true
        val startSec = (startAtMs / 1000L).coerceAtLeast(0L)
        mainHandler.post {
            val wv = ensureWebView()
            val html = """
                <!DOCTYPE html>
                <html><head>
                <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no">
                <style>html,body,#player{margin:0;padding:0;width:100%;height:100%;background:#000;overflow:hidden;}</style>
                </head><body>
                <div id="player"></div>
                <script src="https://www.youtube.com/iframe_api"></script>
                <script>
                  var ytPlayer = null;
                  function onYouTubeIframeAPIReady() {
                    ytPlayer = new YT.Player('player', {
                      videoId: '$videoId',
                      playerVars: { autoplay: 1, controls: 0, rel: 0, modestbranding: 1, playsinline: 1, start: $startSec },
                      events: {
                        'onReady': function(e) {
                          try {
                            e.target.setPlaybackRate($currentRate);
                            e.target.setVolume(${(currentVolume * 100).toInt().coerceIn(0, 100)});
                            e.target.playVideo();
                          } catch(err){}
                        },
                        'onStateChange': function(e) {
                          if (e.data === 0) { try { SonifyBridge.onTrackEnded(); } catch(err){} }
                        }
                      }
                    });
                  }
                  setInterval(function() {
                    if (!ytPlayer || !ytPlayer.getCurrentTime) return;
                    try {
                      var st = ytPlayer.getPlayerState();
                      var pos = Math.floor((ytPlayer.getCurrentTime() || 0) * 1000);
                      var dur = Math.floor((ytPlayer.getDuration() || 0) * 1000);
                      SonifyBridge.onTick(pos, dur, st === 1, st === 3 || st === -1);
                    } catch(err){}
                  }, 400);
                </script>
                </body></html>
            """.trimIndent()
            wv.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "UTF-8", null)
        }
    }

    private fun injectPlayerControllerJs() {
        evalJs("if(ytPlayer){try{ytPlayer.setPlaybackRate($currentRate);ytPlayer.setVolume(${(currentVolume * 100).toInt()});}catch(e){}}")
    }

    fun play() {
        if (!activeMode) return
        evalJs("if(ytPlayer&&ytPlayer.playVideo)ytPlayer.playVideo();")
    }

    fun pause() {
        evalJs("if(ytPlayer&&ytPlayer.pauseVideo)ytPlayer.pauseVideo();")
    }

    fun stopAndDeactivate() {
        activeMode = false
        isPlayingNow = false
        isBufferingNow = false
        mainHandler.post {
            evalJs("if(ytPlayer&&ytPlayer.stopVideo)ytPlayer.stopVideo();")
            webView?.loadUrl("about:blank")
        }
    }

    fun seekTo(ms: Long) {
        currentPosMs = ms
        val sec = (ms / 1000.0)
        evalJs("if(ytPlayer&&ytPlayer.seekTo)ytPlayer.seekTo($sec,true);")
    }

    fun setPlaybackRate(rate: Float) {
        currentRate = rate
        evalJs("if(ytPlayer&&ytPlayer.setPlaybackRate)ytPlayer.setPlaybackRate($rate);")
    }

    fun setVolume(vol: Float) {
        currentVolume = vol.coerceIn(0f, 1f)
        val pct = (currentVolume * 100).toInt()
        evalJs("if(ytPlayer&&ytPlayer.setVolume)ytPlayer.setVolume($pct);")
    }

    private fun evalJs(script: String) {
        mainHandler.post { runCatching { webView?.evaluateJavascript(script, null) } }
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onTick(pos: Long, dur: Long, playing: Boolean, buffering: Boolean) {
            if (!activeMode) return
            currentPosMs = pos
            currentDurMs = dur
            isPlayingNow = playing
            isBufferingNow = buffering
            mainHandler.post { onStateChanged?.invoke(pos, dur, playing, buffering) }
        }

        @JavascriptInterface
        fun onTrackEnded() {
            if (!activeMode) return
            mainHandler.post { onEnded?.invoke() }
        }
    }

    fun release() {
        stopAndDeactivate()
        mainHandler.post {
            runCatching {
                (webView?.parent as? ViewGroup)?.removeView(webView)
                webView?.destroy()
                webView = null
            }
        }
    }
}

class AudioEngine(private val context: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val mainHandler = Handler(Looper.getMainLooper())
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val webEngine = WebYouTubeEngine(context)

    @Volatile private var currentSettings: AppSettings = AppSettings()
    @Volatile private var sleepEndOfTrack: Boolean = false
    @Volatile private var isMuted: Boolean = false
    @Volatile private var isDuckingForDj: Boolean = false
    @Volatile private var sleepFadeMultiplier: Float = 1f
    @Volatile private var crossfadeMultiplier: Float = 1f
    @Volatile private var crossfadeTriggeredForMediaId: String = ""

    private var equalizer: Equalizer? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var sleepTimerJob: Job? = null
    private var progressJob: Job? = null
    private var fadeInJob: Job? = null

    var onProgressUpdate: ((pos: Long, dur: Long, playing: Boolean, buffering: Boolean) -> Unit)? = null
    var onTrackEnded: (() -> Unit)? = null
    var onPlaybackError: ((code: String) -> Unit)? = null

    val player: ExoPlayer = buildExoPlayer(AudioBufferProfile.HIGH_STABILITY)

    init {
        initLocks()
        initTts()

        webEngine.onStateChanged = { pos, dur, playing, buffering ->
            if (webEngine.isActive()) {
                onProgressUpdate?.invoke(pos, dur, playing, buffering)
            }
        }
        webEngine.onEnded = {
            if (webEngine.isActive()) {
                if (sleepEndOfTrack) {
                    sleepEndOfTrack = false
                    webEngine.pause()
                } else {
                    onTrackEnded?.invoke()
                }
            }
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (webEngine.isActive()) return
                when (playbackState) {
                    Player.STATE_ENDED -> {
                        if (sleepEndOfTrack) {
                            sleepEndOfTrack = false
                            player.pause()
                        } else {
                            onTrackEnded?.invoke()
                        }
                    }
                    Player.STATE_READY -> attachAudioEffects()
                    else -> {}
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (isPlaying) acquireLocksIfAllowed() else releaseLocks()
            }

            override fun onPlayerError(error: PlaybackException) {
                if (webEngine.isActive()) return
                onPlaybackError?.invoke(error.errorCodeName)
            }
        })

        startProgressTicker()
    }

    private fun buildExoPlayer(profile: AudioBufferProfile): ExoPlayer {
        // Ultra-fast 250ms start-of-playback buffer paired with deep background stability shield
        val minBuf = profile.minMs.coerceIn(20_000, 60_000)
        val maxBuf = profile.maxMs.coerceIn(60_000, 180_000)
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                /* minBufferMs = */ minBuf,
                /* maxBufferMs = */ maxBuf,
                /* bufferForPlaybackMs = */ 250,
                /* bufferForPlaybackAfterRebufferMs = */ 850
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()
        val attrs = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(context.applicationContext)
            .setLoadControl(loadControl)
            .setAudioAttributes(attrs, true)
            .setHandleAudioBecomingNoisy(true)
            .build()
    }

    /**
     * Returns true once the current song has >= 6 seconds buffered ahead so background
     * disk pre-warming never steals bandwidth from active song startup.
     */
    fun isComfortablyBuffered(): Boolean {
        if (webEngine.isActive()) return true
        return player.isPlaying &&
            player.playbackState == Player.STATE_READY &&
            (player.bufferedPosition - player.currentPosition) >= 6_000L
    }

    private fun applyCompositeVolume() {
        val target = when {
            isMuted -> 0f
            isDuckingForDj -> (0.24f * sleepFadeMultiplier).coerceIn(0.05f, 1f)
            else -> (sleepFadeMultiplier * crossfadeMultiplier).coerceIn(0f, 1f)
        }
        player.volume = target
        webEngine.setVolume(target)
    }

    private fun startFadeInIfNeeded(fadeMs: Long) {
        fadeInJob?.cancel()
        if (fadeMs <= 0L) {
            crossfadeMultiplier = 1f
            applyCompositeVolume()
            return
        }
        fadeInJob = scope.launch {
            val steps = 16
            val stepDelay = (fadeMs / steps).coerceAtLeast(35L)
            for (i in 1..steps) {
                crossfadeMultiplier = (i.toFloat() / steps.toFloat()).coerceIn(0.08f, 1f)
                applyCompositeVolume()
                delay(stepDelay)
            }
            crossfadeMultiplier = 1f
            applyCompositeVolume()
        }
    }

    private fun effectiveCrossfadeSec(): Int {
        val s = currentSettings
        return when {
            s.crossfadeSeconds > 0 -> s.crossfadeSeconds.coerceIn(1, 12)
            s.automixEnabled -> 4
            else -> 0
        }
    }

    private fun startProgressTicker() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (!webEngine.isActive()) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: 0L
                    val playing = player.isPlaying
                    val buffering = player.playbackState == Player.STATE_BUFFERING

                    // Smooth Crossfade / Automix ramp-down near track end
                    val cfSec = effectiveCrossfadeSec()
                    val mediaId = player.currentMediaItem?.mediaId.orEmpty()
                    if (playing && cfSec > 0 && dur > 30_000L && !sleepEndOfTrack) {
                        val remainingMs = dur - pos
                        val cfMs = cfSec * 1000L
                        if (remainingMs in 1L..cfMs) {
                            crossfadeMultiplier = (remainingMs.toFloat() / cfMs.toFloat()).coerceIn(0.06f, 1f)
                            applyCompositeVolume()
                            if (remainingMs <= 450L && mediaId.isNotBlank() && crossfadeTriggeredForMediaId != mediaId) {
                                crossfadeTriggeredForMediaId = mediaId
                                onTrackEnded?.invoke()
                            }
                        } else if (fadeInJob?.isActive != true && crossfadeMultiplier < 0.99f) {
                            crossfadeMultiplier = 1f
                            applyCompositeVolume()
                        }
                    }

                    onProgressUpdate?.invoke(pos, dur, playing, buffering)
                }
                delay(300L)
            }
        }
    }

    private fun initLocks() {
        wakeLock = runCatching {
            (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sonify::AudioWakeLock")
                ?.apply { setReferenceCounted(false) }
        }.getOrNull()
        wifiLock = runCatching {
            val wm = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wm?.createWifiLock(mode, "Sonify::AudioWifiLock")?.apply { setReferenceCounted(false) }
        }.getOrNull()
    }

    private fun acquireLocksIfAllowed() {
        if (!currentSettings.keepWakeLockAudio) return
        runCatching {
            if (wakeLock?.isHeld == false) wakeLock?.acquire(180L * 60L * 1000L)
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        }
    }

    fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun initTts() {
        runCatching {
            tts = TextToSpeech(context.applicationContext) { status ->
                ttsReady = (status == TextToSpeech.SUCCESS)
                if (ttsReady) {
                    tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                        override fun onStart(utteranceId: String?) {
                            mainHandler.post {
                                isDuckingForDj = true
                                applyCompositeVolume()
                            }
                        }

                        override fun onDone(utteranceId: String?) {
                            mainHandler.post {
                                isDuckingForDj = false
                                applyCompositeVolume()
                            }
                        }

                        @Deprecated("Deprecated in Java")
                        override fun onError(utteranceId: String?) {
                            mainHandler.post {
                                isDuckingForDj = false
                                applyCompositeVolume()
                            }
                        }
                    })
                }
            }
        }
    }

    fun attachWebView(container: FrameLayout) = webEngine.attachTo(container)

    fun getCapturedStreams(videoId: String): List<CapturedMediaStream> = webEngine.getCapturedStreams(videoId)

    /**
     * Pre-warms the next track directly into PlaybackDiskManager's SimpleCache
     * under its stable `sonify_track_<id>` key for 0ms track switches.
     */
    fun prewarmNextTrackDiskCache(track: Track, resolved: ResolvedStreams) {
        if (resolved.isOffline || track.source != StreamSource.YOUTUBE) return
        scope.launch(Dispatchers.IO) {
            PlaybackDiskManager.prewarmTrackHeadOnDisk(
                context = context,
                trackId = track.id,
                audioUrl = resolved.audioUrl,
                userAgent = resolved.userAgent,
                cookie = resolved.cookie
            )
        }
    }

    fun playResolved(
        resolved: ResolvedStreams,
        track: Track,
        videoMode: Boolean = false,
        startPositionMs: Long = 0L
    ) {
        webEngine.stopAndDeactivate()
        crossfadeTriggeredForMediaId = ""
        val isLocalUri = resolved.audioUrl.startsWith("file:") || resolved.audioUrl.startsWith("content:")
        val cacheKey = PlaybackDiskManager.cacheKeyFor(track.id)

        // Direct local disk DataSource for offline/local/disk-cached tracks -> zero HTTP overhead
        val mediaSourceFactory = if (isLocalUri && !videoMode) {
            DefaultMediaSourceFactory(DefaultDataSource.Factory(context.applicationContext))
        } else {
            val dsFactory = PlaybackDiskManager.buildCacheDataSourceFactory(context, resolved.userAgent, resolved.cookie)
            DefaultMediaSourceFactory(dsFactory)
        }

        val meta = MediaMetadata.Builder()
            .setTitle(track.title)
            .setArtist(track.artist)
            .setAlbumTitle(track.album)
            .build()

        val audioItemBuilder = MediaItem.Builder()
            .setUri(resolved.audioUrl)
            .setMediaId(track.id)
            .setMediaMetadata(meta)
        if (!isLocalUri) audioItemBuilder.setCustomCacheKey(cacheKey)
        val audioItem = audioItemBuilder.build()

        val finalSource = if (videoMode && !resolved.videoOnlyUrl.isNullOrBlank()) {
            val videoItem = MediaItem.Builder()
                .setUri(resolved.videoOnlyUrl)
                .setCustomCacheKey("${cacheKey}_vid")
                .build()
            MergingMediaSource(
                mediaSourceFactory.createMediaSource(videoItem),
                mediaSourceFactory.createMediaSource(audioItem)
            )
        } else if (videoMode && !resolved.videoUrl.isNullOrBlank()) {
            val muxedItem = MediaItem.Builder()
                .setUri(resolved.videoUrl)
                .setMediaId(track.id)
                .setMediaMetadata(meta)
                .build()
            mediaSourceFactory.createMediaSource(muxedItem)
        } else {
            mediaSourceFactory.createMediaSource(audioItem)
        }

        player.setMediaSource(finalSource)
        player.playbackParameters = PlaybackParameters(currentSettings.playbackSpeed)
        if (startPositionMs > 0L) player.seekTo(startPositionMs)
        val cfSec = effectiveCrossfadeSec()
        if (cfSec > 0 && startPositionMs == 0L && !track.isPodcastOrAudiobook) {
            startFadeInIfNeeded((cfSec * 450L).coerceAtMost(2500L))
        } else {
            crossfadeMultiplier = 1f
            applyCompositeVolume()
        }
        player.prepare()
        player.play()
        acquireLocksIfAllowed()
    }

    fun seamlessSwitchVideoMode(resolved: ResolvedStreams, track: Track, toVideo: Boolean, startPositionMs: Long) {
        playResolved(resolved, track, videoMode = toVideo, startPositionMs = startPositionMs)
    }

    fun playViaWebEngine(videoId: String, startAtMs: Long, title: String, artist: String) {
        runCatching { player.stop(); player.clearMediaItems() }
        crossfadeTriggeredForMediaId = ""
        crossfadeMultiplier = 1f
        webEngine.setPlaybackRate(currentSettings.playbackSpeed)
        applyCompositeVolume()
        webEngine.loadVideo(videoId, startAtMs, title, artist)
        acquireLocksIfAllowed()
    }

    fun setMuted(muted: Boolean) {
        isMuted = muted
        applyCompositeVolume()
    }

    fun togglePlayPause(onNeedRetry: () -> Unit = {}) {
        if (webEngine.isActive()) {
            if (player.isPlaying) player.pause()
            if (webEngine.isPlaying()) webEngine.pause() else webEngine.play()
            return
        }
        when {
            player.isPlaying -> player.pause()
            player.playbackState == Player.STATE_IDLE || player.mediaItemCount == 0 -> onNeedRetry()
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0L); player.play() }
            else -> player.play()
        }
    }

    fun setPlaying(playing: Boolean) {
        if (webEngine.isActive()) {
            if (playing) webEngine.play() else webEngine.pause()
        } else {
            if (playing) player.play() else player.pause()
        }
    }

    fun seekTo(ms: Long) {
        val clamped = ms.coerceAtLeast(0L)
        if (webEngine.isActive()) webEngine.seekTo(clamped) else player.seekTo(clamped)
    }

    fun skipByDelta(deltaMs: Long) {
        val cur = if (webEngine.isActive()) webEngine.currentPositionMs() else player.currentPosition
        val dur = player.duration.takeIf { it != C.TIME_UNSET && it > 0L } ?: Long.MAX_VALUE
        val target = (cur + deltaMs).coerceIn(0L, dur)
        seekTo(target)
    }

    fun applySettings(settings: AppSettings) {
        currentSettings = settings
        player.playbackParameters = PlaybackParameters(settings.playbackSpeed)
        webEngine.setPlaybackRate(settings.playbackSpeed)
        PlaybackDiskManager.setLimitMb(context, settings.playbackDiskLimitMb)
        attachAudioEffects()
    }

    private fun attachAudioEffects() {
        val sessionId = player.audioSessionId
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return

        runCatching {
            if (loudnessEnhancer == null) loudnessEnhancer = LoudnessEnhancer(sessionId)
            loudnessEnhancer?.apply {
                enabled = currentSettings.normalizeVolume
                setTargetGain(if (currentSettings.normalizeVolume) currentSettings.normalizationLevel.gainMb else 0)
            }
        }

        runCatching {
            if (equalizer == null) equalizer = Equalizer(0, sessionId)
            equalizer?.apply {
                enabled = currentSettings.eqEnabled
                if (currentSettings.eqEnabled) {
                    val bands = numberOfBands.toInt()
                    val minLvl = bandLevelRange[0].toInt()
                    val maxLvl = bandLevelRange[1].toInt()
                    val userBands = currentSettings.eqBands
                    for (b in 0 until bands) {
                        val mappedIdx = ((b * userBands.size) / bands.coerceAtLeast(1)).coerceIn(0, userBands.lastIndex)
                        val db = userBands.getOrElse(mappedIdx) { 0 }
                        val targetMb = (db * 100).coerceIn(minLvl, maxLvl).toShort()
                        setBandLevel(b.toShort(), targetMb)
                    }
                }
            }
        }
    }

    fun speakDjAnnouncement(text: String, isEn: Boolean, forceAllow: Boolean = false) {
        if (!forceAllow && !currentSettings.djVoiceEnabled) return
        if (!ttsReady || text.isBlank()) return
        mainHandler.post {
            runCatching {
                tts?.language = if (isEn) Locale.US else Locale("he", "IL")
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "sonify_dj_${System.currentTimeMillis()}")
            }
        }
    }

    fun startSleepTimer(minutes: Int, onTick: (Int?) -> Unit) {
        sleepTimerJob?.cancel()
        sleepEndOfTrack = false
        sleepFadeMultiplier = 1f
        applyCompositeVolume()
        if (minutes <= 0) {
            onTick(null)
            return
        }
        sleepTimerJob = scope.launch {
            var remainingSec = minutes * 60
            while (remainingSec > 0 && isActive) {
                onTick((remainingSec + 59) / 60)
                if (remainingSec <= 6) {
                    sleepFadeMultiplier = (remainingSec / 6f).coerceIn(0.05f, 1f)
                    applyCompositeVolume()
                }
                delay(1000L)
                remainingSec--
            }
            setPlaying(false)
            sleepFadeMultiplier = 1f
            applyCompositeVolume()
            onTick(null)
        }
    }

    fun setSleepEndOfTrack(enabled: Boolean) {
        sleepTimerJob?.cancel()
        sleepEndOfTrack = enabled
        sleepFadeMultiplier = 1f
        applyCompositeVolume()
    }

    fun getSystemVolumeFraction(): Float {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val cur = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        return (cur.toFloat() / max.toFloat()).coerceIn(0f, 1f)
    }

    fun setSystemVolumeFraction(fraction: Float) {
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
        val target = (fraction.coerceIn(0f, 1f) * max).toInt()
        runCatching { audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0) }
    }

    fun getOutputDevices(isEn: Boolean): List<AudioOutputItem> {
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val list = mutableListOf<AudioOutputItem>()
        var hasExternal = false
        devices.forEach { d ->
            val type = d.type
            val (he, en, lossless) = when (type) {
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_BLE_HEADSET -> Triple("אוזניות Bluetooth", "Bluetooth Audio", false)
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET -> Triple("אוזניות חוטיות", "Wired Headphones", true)
                AudioDeviceInfo.TYPE_USB_DEVICE, AudioDeviceInfo.TYPE_USB_HEADSET -> Triple("USB DAC / אודיו", "USB Audio / DAC", true)
                else -> return@forEach
            }
            hasExternal = true
            val name = d.productName?.toString()?.takeIf { it.isNotBlank() } ?: if (isEn) en else he
            if (list.none { it.name == name }) {
                list.add(AudioOutputItem(d.id, name, he, en, lossless, true))
            }
        }
        list.add(
            AudioOutputItem(
                id = 0,
                name = if (isEn) "This Phone" else "מכשיר זה",
                typeLabelHe = "רמקול מובנה",
                typeLabelEn = "Built-in Speaker",
                isLosslessCapable = false,
                isActive = !hasExternal
            )
        )
        return list
    }

    fun openSystemAudioSettings() {
        runCatching {
            val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
            context.startActivity(intent)
        }
    }
}
