@file:androidx.annotation.OptIn(UnstableApi::class)

package com.sonify.app

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.audiofx.DynamicsProcessing
import android.media.audiofx.Equalizer
import android.media.audiofx.LoudnessEnhancer
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
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
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
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
import okhttp3.OkHttpClient
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

// ---------- Real-Time PCM Mono & Left/Right Balance Processor ----------
class ChannelBalanceMonoProcessor : BaseAudioProcessor() {
    @Volatile var monoEnabled: Boolean = false
    @Volatile var balance: Float = 0f // -1f (Left) .. 0f (Center) .. +1f (Right)

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT || inputAudioFormat.channelCount != 2) {
            return AudioProcessor.AudioFormat.NOT_SET
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val pos = inputBuffer.position()
        val limit = inputBuffer.limit()
        val size = limit - pos
        if (size <= 0) return

        val out = replaceOutputBuffer(size)
        val mono = monoEnabled
        val bal = balance.coerceIn(-1f, 1f)
        val leftGain = if (bal > 0f) 1f - bal else 1f
        val rightGain = if (bal < 0f) 1f + bal else 1f

        if (!mono && bal == 0f) {
            out.put(inputBuffer)
        } else {
            var i = pos
            while (i + 3 < limit) {
                val l = inputBuffer.getShort(i).toInt()
                val r = inputBuffer.getShort(i + 2).toInt()
                val baseL = if (mono) (l + r) / 2 else l
                val baseR = if (mono) (l + r) / 2 else r
                val outL = (baseL * leftGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                val outR = (baseR * rightGain).toInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
                out.putShort(outL)
                out.putShort(outR)
                i += 4
            }
            inputBuffer.position(limit)
        }
        out.flip()
    }
}

object PlayerHolder {
    @Volatile private var player: ExoPlayer? = null
    val balanceMonoProcessor = ChannelBalanceMonoProcessor()

    fun getOrCreate(context: Context): ExoPlayer {
        return player ?: synchronized(this) {
            player ?: run {
                val renderersFactory = object : DefaultRenderersFactory(context.applicationContext) {
                    override fun buildAudioSink(
                        ctx: Context,
                        enableFloatOutput: Boolean,
                        enableAudioTrackPlaybackParams: Boolean
                    ): AudioSink {
                        return DefaultAudioSink.Builder(ctx)
                            .setAudioProcessors(arrayOf(balanceMonoProcessor))
                            .build()
                    }
                }
                ExoPlayer.Builder(context.applicationContext, renderersFactory)
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
}

class PlaybackService : MediaSessionService() {
    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        mediaSession = MediaSession.Builder(this, PlayerHolder.getOrCreate(this)).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }
}

// ---------- Built-In YouTube Web Engine with Live Audio Stream Capture (Fixes Downloads!) ----------
class WebPlaybackEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var webView: WebView? = null
    private var isMobileWatchFallback = false
    private var currentVideoId: String = ""
    private var pendingVideoId: String? = null
    private var hasStartedPlayingCurrent = false
    private var watchdogRunnable: Runnable? = null
    private var currentSpeed: Float = 1.0f

    val capturedStreams = ConcurrentHashMap<String, CapturedMediaStream>()
    private val mobileUa = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    @Volatile var isActive: Boolean = false
        private set
    @Volatile var isPlaying: Boolean = false
        private set

    var onProgress: ((Long, Long, Boolean, Boolean) -> Unit)? = null
    var onEnded: (() -> Unit)? = null

    @SuppressLint("SetJavaScriptEnabled")
    private fun getOrCreateWebView(): WebView {
        webView?.let { return it }
        return WebView(context.applicationContext).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            val cm = CookieManager.getInstance()
            cm.setAcceptCookie(true)
            cm.setAcceptThirdPartyCookies(this, true)

            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            settings.userAgentString = mobileUa

            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    val host = request?.url?.host.orEmpty()
                    return !(host.contains("youtube.com") || host.contains("youtu.be") || host.contains("google.com"))
                }

                override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                    val url = request?.url?.toString().orEmpty()
                    val vid = currentVideoId
                    if (vid.isNotBlank() && url.contains("googlevideo.com/videoplayback")) {
                        val isAudio = url.contains("mime=audio", true) ||
                            url.contains("itag=140") || url.contains("itag=251") ||
                            url.contains("itag=250") || url.contains("itag=249") || url.contains("itag=18")
                        if (isAudio) {
                            val cookie = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                            capturedStreams[vid] = CapturedMediaStream(vid, url, mobileUa, cookie)
                        }
                    }
                    return super.shouldInterceptRequest(view, request)
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    if (isActive && isMobileWatchFallback) injectMobileWatchHook()
                }
            }
            addJavascriptInterface(JsBridge(), "SonifyBridge")
            webView = this
        }
    }

    fun attachTo(container: FrameLayout) {
        mainHandler.post {
            val wv = getOrCreateWebView()
            if (wv.parent !== container) {
                (wv.parent as? ViewGroup)?.removeView(wv)
                container.removeAllViews()
                container.addView(wv, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            }
            wv.onResume()
            wv.resumeTimers()
            val pending = pendingVideoId
            if (isActive && !pending.isNullOrBlank()) {
                pendingVideoId = null
                loadIframePlayer(wv, pending)
            }
        }
    }

    fun playVideo(videoId: String) {
        val safeId = videoId.replace(Regex("[^a-zA-Z0-9_-]"), "")
        currentVideoId = safeId
        isActive = true
        isPlaying = false
        isMobileWatchFallback = false
        hasStartedPlayingCurrent = false
        cancelWatchdog()

        mainHandler.post {
            val wv = getOrCreateWebView()
            if (wv.parent == null) pendingVideoId = safeId
            else { pendingVideoId = null; loadIframePlayer(wv, safeId) }
        }
    }

    private fun loadIframePlayer(wv: WebView, safeId: String) {
        wv.onResume()
        wv.resumeTimers()
        scheduleWatchdog(safeId)

        val html = """
            <!DOCTYPE html>
            <html>
            <head>
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0">
            <style>html, body, #player { margin:0; padding:0; width:100%; height:100%; background:#000; overflow:hidden; }</style>
            </head>
            <body>
            <div id="player"></div>
            <script src="https://www.youtube.com/iframe_api"></script>
            <script>
              var player = null;
              var userPaused = false;
              function onYouTubeIframeAPIReady() {
                player = new YT.Player('player', {
                  width: '100%', height: '100%', videoId: '$safeId',
                  host: 'https://www.youtube-nocookie.com',
                  playerVars: { 'autoplay': 1, 'playsinline': 1, 'controls': 0, 'rel': 0, 'fs': 0, 'modestbranding': 1, 'enablejsapi': 1, 'origin': 'https://www.youtube-nocookie.com' },
                  events: {
                    'onReady': function(e) {
                      try { e.target.unMute(); e.target.setVolume(100); e.target.setPlaybackRate($currentSpeed); e.target.playVideo(); } catch(err) {}
                    },
                    'onStateChange': function(e) { if (e.data === 0) SonifyBridge.onEnded(); },
                    'onError': function(e) { SonifyBridge.onEmbedError(e.data); }
                  }
                });
              }
              setInterval(function() {
                if (player && typeof player.getCurrentTime === 'function') {
                  var pos = Math.floor((player.getCurrentTime() || 0) * 1000);
                  var dur = Math.floor((player.getDuration() || 0) * 1000);
                  var st = player.getPlayerState ? player.getPlayerState() : -1;
                  if (st === -1 && !userPaused) { try { player.playVideo(); } catch(err) {} }
                  SonifyBridge.onProgress(pos, dur, st === 1, st === 3);
                }
              }, 350);
              window.sonifyPlay = function() { userPaused = false; if (player && player.playVideo) player.playVideo(); };
              window.sonifyPause = function() { userPaused = true; if (player && player.pauseVideo) player.pauseVideo(); };
              window.sonifyToggle = function() { if (!player) return; if (player.getPlayerState && player.getPlayerState() === 1) window.sonifyPause(); else window.sonifyPlay(); };
              window.sonifySeek = function(sec) { if (player && player.seekTo) player.seekTo(sec, true); };
              window.sonifyVolume = function(pct) { if (player && player.setVolume) player.setVolume(pct); };
              window.sonifySpeed = function(spd) { if (player && player.setPlaybackRate) player.setPlaybackRate(spd); };
            </script>
            </body>
            </html>
        """.trimIndent()
        wv.loadDataWithBaseURL("https://www.youtube-nocookie.com", html, "text/html", "UTF-8", null)
    }

    private fun scheduleWatchdog(expectedVideoId: String) {
        cancelWatchdog()
        val r = Runnable {
            if (isActive && currentVideoId == expectedVideoId && !hasStartedPlayingCurrent && !isMobileWatchFallback) {
                switchToMobileWatchFallback()
            }
        }
        watchdogRunnable = r
        mainHandler.postDelayed(r, 3200L)
    }

    private fun cancelWatchdog() {
        watchdogRunnable?.let { mainHandler.removeCallbacks(it) }
        watchdogRunnable = null
    }

    private fun switchToMobileWatchFallback() {
        if (currentVideoId.isBlank() || isMobileWatchFallback) return
        isMobileWatchFallback = true
        cancelWatchdog()
        mainHandler.post {
            val wv = getOrCreateWebView()
            wv.onResume()
            wv.resumeTimers()
            wv.loadUrl("https://m.youtube.com/watch?v=$currentVideoId")
        }
    }

    private fun injectMobileWatchHook() {
        val js = """
            (function() {
              window.__sonifyUserPaused = false;
              window.__sonifyEndedFired = false;
              if (window.__sonifyTimer) clearInterval(window.__sonifyTimer);
              window.__sonifyTimer = setInterval(function() {
                var skip = document.querySelector('.ytp-ad-skip-button, .ytp-skip-ad-button, .ytp-ad-skip-button-modern');
                if (skip) { try { skip.click(); } catch(e){} }
                var unmuteBtn = document.querySelector('.ytp-unmute');
                if (unmuteBtn && unmuteBtn.offsetParent !== null) { try { unmuteBtn.click(); } catch(e){} }
                var v = document.querySelector('video');
                if (!v) return;
                if (Math.abs(v.playbackRate - $currentSpeed) > 0.05) { try { v.playbackRate = $currentSpeed; } catch(e){} }
                if (!window.__sonifyUserPaused) {
                  if (v.muted) v.muted = false;
                  if (v.volume === 0) v.volume = 1.0;
                  if (v.paused && !v.ended) v.play().catch(function(){});
                }
                var pos = Math.floor((v.currentTime || 0) * 1000);
                var dur = Math.floor((isFinite(v.duration) && v.duration > 0 ? v.duration : 0) * 1000);
                var playing = !v.paused && !v.ended && v.readyState >= 2;
                var buffering = !window.__sonifyUserPaused && !playing && v.readyState < 3;
                SonifyBridge.onProgress(pos, dur, playing, buffering);
                if (v.ended && !window.__sonifyEndedFired) { window.__sonifyEndedFired = true; SonifyBridge.onEnded(); }
              }, 350);
              window.sonifyPlay = function() { window.__sonifyUserPaused = false; var v = document.querySelector('video'); if (v) { v.muted = false; v.play().catch(function(){}); } };
              window.sonifyPause = function() { window.__sonifyUserPaused = true; var v = document.querySelector('video'); if (v) v.pause(); };
              window.sonifyToggle = function() { var v = document.querySelector('video'); if (!v) return; if (v.paused) window.sonifyPlay(); else window.sonifyPause(); };
              window.sonifySeek = function(sec) { var v = document.querySelector('video'); if (v) v.currentTime = sec; };
              window.sonifyVolume = function(pct) { var v = document.querySelector('video'); if (v) v.volume = Math.max(0, Math.min(1, pct / 100)); };
              window.sonifySpeed = function(spd) { var v = document.querySelector('video'); if (v) v.playbackRate = spd; };
            })();
        """.trimIndent()
        mainHandler.post { webView?.evaluateJavascript(js, null) }
    }

    fun play() = mainHandler.post { webView?.evaluateJavascript("if(window.sonifyPlay) window.sonifyPlay();", null) }
    fun pause() = mainHandler.post { webView?.evaluateJavascript("if(window.sonifyPause) window.sonifyPause();", null) }
    fun seekTo(ms: Long) = mainHandler.post { webView?.evaluateJavascript("if(window.sonifySeek) window.sonifySeek(${(ms / 1000.0).coerceAtLeast(0.0)});", null) }
    fun setVolume(fraction: Float) = mainHandler.post { webView?.evaluateJavascript("if(window.sonifyVolume) window.sonifyVolume(${(fraction * 100).toInt().coerceIn(0, 100)});", null) }
    fun setSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.5f, 3.5f)
        mainHandler.post { webView?.evaluateJavascript("if(window.sonifySpeed) window.sonifySpeed($currentSpeed);", null) }
    }

    fun togglePlayPause() {
        mainHandler.post {
            if (!hasStartedPlayingCurrent && !isMobileWatchFallback && currentVideoId.isNotBlank()) switchToMobileWatchFallback()
            else webView?.evaluateJavascript("if(window.sonifyToggle) window.sonifyToggle();", null)
        }
    }

    fun stop() {
        isActive = false; isPlaying = false; pendingVideoId = null; hasStartedPlayingCurrent = false; cancelWatchdog()
        mainHandler.post {
            webView?.evaluateJavascript("if(window.sonifyPause) window.sonifyPause();", null)
            webView?.loadUrl("about:blank")
        }
    }

    private inner class JsBridge {
        @JavascriptInterface
        fun onProgress(posMs: Long, durMs: Long, playing: Boolean, buffering: Boolean) {
            if (!isActive) return
            if (playing && posMs > 200L) { hasStartedPlayingCurrent = true; cancelWatchdog() }
            isPlaying = playing
            mainHandler.post { onProgress?.invoke(posMs, durMs, playing, buffering) }
        }

        @JavascriptInterface fun onEnded() { if (isActive) mainHandler.post { onEnded?.invoke() } }
        @JavascriptInterface fun onEmbedError(code: Int) { if (isActive) switchToMobileWatchFallback() }
    }
}

class AudioEngine(private val context: Context) {

    val player: ExoPlayer = PlayerHolder.getOrCreate(context)
    val webEngine = WebPlaybackEngine(context)

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(12, TimeUnit.SECONDS)
        .readTimeout(16, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var equalizer: Equalizer? = null
    private var dynamicsProcessing: DynamicsProcessing? = null
    private var loudnessEnhancer: LoudnessEnhancer? = null
    private var lastSettings: AppSettings = AppSettings()

    private var sleepJob: Job? = null
    var sleepEndOfTrack: Boolean = false
        private set

    var onTrackEnded: (() -> Unit)? = null
    var onPlaybackError: ((String) -> Unit)? = null
    var onProgressUpdate: ((Long, Long, Boolean, Boolean) -> Unit)? = null

    private val eqCenterFreqs = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)

    init {
        webEngine.onProgress = { pos, dur, playing, buffering ->
            if (webEngine.isActive) onProgressUpdate?.invoke(pos, dur, playing, buffering)
        }
        webEngine.onEnded = {
            if (sleepEndOfTrack) { sleepEndOfTrack = false; webEngine.pause() } else onTrackEnded?.invoke()
        }

        player.addListener(object : Player.Listener {
            override fun onAudioSessionIdChanged(audioSessionId: Int) = attachAudioEffects(audioSessionId)
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED && !webEngine.isActive) {
                    if (sleepEndOfTrack) { sleepEndOfTrack = false; player.pause() } else onTrackEnded?.invoke()
                }
            }
            override fun onPlayerError(error: PlaybackException) {
                if (!webEngine.isActive) onPlaybackError?.invoke(error.errorCodeName)
            }
        })

        runCatching { context.startService(Intent(context, PlaybackService::class.java)) }

        scope.launch {
            while (isActive) {
                if (!webEngine.isActive) {
                    val pos = player.currentPosition.coerceAtLeast(0L)
                    val dur = player.duration.coerceAtLeast(0L)
                    val buffering = player.playbackState == Player.STATE_BUFFERING
                    onProgressUpdate?.invoke(pos, dur, player.isPlaying, buffering)

                    val effectiveFadeSec = if (lastSettings.automixEnabled && lastSettings.crossfadeSeconds == 0) 4 else lastSettings.crossfadeSeconds
                    val cfMs = effectiveFadeSec * 1000L
                    if (cfMs > 0 && dur > cfMs && player.isPlaying) {
                        val remaining = dur - pos
                        player.volume = when {
                            remaining in 1..cfMs -> (remaining.toFloat() / cfMs.toFloat()).coerceIn(0.05f, 1f)
                            pos < cfMs / 2 -> (pos.toFloat() / (cfMs / 2f)).coerceIn(0.15f, 1f)
                            else -> 1f
                        }
                    } else if (sleepJob == null || !sleepJob!!.isActive) {
                        player.volume = 1f
                    }
                }
                delay(300)
            }
        }
    }

    fun attachWebView(container: FrameLayout) = webEngine.attachTo(container)
    fun getCapturedStream(videoId: String): CapturedMediaStream? = webEngine.capturedStreams[videoId]

    private fun attachAudioEffects(sessionId: Int) {
        if (sessionId == C.AUDIO_SESSION_ID_UNSET || sessionId <= 0) return
        runCatching { loudnessEnhancer?.release(); loudnessEnhancer = LoudnessEnhancer(sessionId) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                dynamicsProcessing?.release()
                val cfg = DynamicsProcessing.Config.Builder(
                    DynamicsProcessing.VARIANT_FAVOR_FREQUENCY_RESOLUTION,
                    2,
                    true, 10,
                    false, 0,
                    false, 0,
                    true
                ).build()
                dynamicsProcessing = DynamicsProcessing(0, sessionId, cfg)
            }
        } else {
            runCatching { equalizer?.release(); equalizer = Equalizer(0, sessionId) }
        }
        applySettings(lastSettings)
    }

    fun applySettings(settings: AppSettings) {
        lastSettings = settings
        PlayerHolder.balanceMonoProcessor.monoEnabled = settings.monoAudio
        PlayerHolder.balanceMonoProcessor.balance = settings.audioBalance
        player.playbackParameters = PlaybackParameters(settings.playbackSpeed.coerceIn(0.5f, 3.5f))
        webEngine.setSpeed(settings.playbackSpeed)

        runCatching {
            loudnessEnhancer?.apply {
                enabled = settings.normalizeVolume
                setTargetGain(if (settings.normalizeVolume) settings.normalizationLevel.gainMb else 0)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            runCatching {
                dynamicsProcessing?.let { dp ->
                    dp.enabled = settings.eqEnabled
                    val eq = DynamicsProcessing.Eq(true, settings.eqEnabled, 10)
                    for (i in 0 until 10) {
                        val bandGainDb = settings.eqBands.getOrElse(i) { 0 }.toFloat().coerceIn(-12f, 12f)
                        eq.getBand(i).apply {
                            isEnabled = settings.eqEnabled
                            cutoffFrequency = eqCenterFreqs[i]
                            gain = bandGainDb
                        }
                    }
                    dp.setPreEqAllChannelsTo(eq)
                    val limiter = DynamicsProcessing.Limiter(true, true, 0, 2f, 60f, 4f, -1.5f, 0f)
                    dp.setLimiterAllChannelsTo(limiter)
                }
            }
        } else {
            runCatching {
                equalizer?.let { eq ->
                    eq.enabled = settings.eqEnabled
                    val bands = eq.numberOfBands.toInt()
                    val minLvl = eq.bandLevelRange[0].toInt()
                    val maxLvl = eq.bandLevelRange[1].toInt()
                    for (i in 0 until bands) {
                        val mapped = settings.eqBands.getOrElse((i * 2).coerceAtMost(9)) { 0 }
                        val lvl = ((mapped / 12f) * (maxLvl - minLvl) / 2f).toInt().coerceIn(minLvl, maxLvl)
                        eq.setBandLevel(i.toShort(), lvl.toShort())
                    }
                }
            }
        }
    }

    private fun buildSingleSource(url: String, metadata: MediaMetadata, userAgent: String, cookie: String? = null): MediaSource {
        val okHttpFactory = OkHttpDataSource.Factory(okHttpClient).setUserAgent(userAgent)
        if (!cookie.isNullOrBlank()) okHttpFactory.setDefaultRequestProperties(mapOf("Cookie" to cookie))
        val dataSourceFactory = DefaultDataSource.Factory(context, okHttpFactory)
        val item = MediaItem.Builder().setUri(url).setMediaMetadata(metadata).build()
        return if (url.contains(".m3u8", true) || url.contains("manifest/hls", true)) {
            HlsMediaSource.Factory(dataSourceFactory).createMediaSource(item)
        } else {
            ProgressiveMediaSource.Factory(dataSourceFactory).createMediaSource(item)
        }
    }

    fun playResolved(resolved: ResolvedStreams, track: Track, videoMode: Boolean, startPositionMs: Long = 0L, playImmediately: Boolean = true) {
        webEngine.stop()
        val metadata = MediaMetadata.Builder().setTitle(track.title).setArtist(track.artist).setAlbumTitle(track.album).build()
        val source: MediaSource = if (videoMode) {
            when {
                !resolved.videoUrl.isNullOrBlank() -> buildSingleSource(resolved.videoUrl, metadata, resolved.userAgent, resolved.cookie)
                !resolved.videoOnlyUrl.isNullOrBlank() -> MergingMediaSource(
                    buildSingleSource(resolved.videoOnlyUrl, metadata, resolved.userAgent, resolved.cookie),
                    buildSingleSource(resolved.audioUrl, metadata, resolved.userAgent, resolved.cookie)
                )
                else -> buildSingleSource(resolved.audioUrl, metadata, resolved.userAgent, resolved.cookie)
            }
        } else buildSingleSource(resolved.audioUrl, metadata, resolved.userAgent, resolved.cookie)

        player.setMediaSource(source, startPositionMs)
        player.prepare()
        player.playWhenReady = playImmediately
        if (player.audioSessionId > 0 && dynamicsProcessing == null && equalizer == null) attachAudioEffects(player.audioSessionId)
    }

    fun playViaWebEngine(track: Track) {
        player.stop()
        player.clearMediaItems()
        webEngine.playVideo(track.id)
    }

    fun seamlessSwitchVideoMode(resolved: ResolvedStreams, track: Track, toVideo: Boolean) {
        val pos = player.currentPosition.coerceAtLeast(0L)
        val playing = player.isPlaying || player.playWhenReady
        playResolved(resolved, track, videoMode = toVideo, startPositionMs = pos, playImmediately = playing)
    }

    fun togglePlayPause(onNeedRetry: () -> Unit) {
        if (webEngine.isActive) { webEngine.togglePlayPause(); return }
        when {
            player.playbackState == Player.STATE_IDLE -> onNeedRetry()
            player.playbackState == Player.STATE_ENDED -> { player.seekTo(0L); player.play() }
            player.isPlaying -> player.pause()
            else -> player.play()
        }
    }

    fun seekTo(positionMs: Long) {
        if (webEngine.isActive) webEngine.seekTo(positionMs) else player.seekTo(positionMs.coerceAtLeast(0L))
    }

    fun startSleepTimer(minutes: Int, onRemainingChange: (Int?) -> Unit) {
        sleepJob?.cancel(); sleepJob = null; sleepEndOfTrack = false
        player.volume = 1f; webEngine.setVolume(1f)
        if (minutes <= 0) { onRemainingChange(null); return }
        sleepJob = scope.launch {
            var remainingSec = minutes * 60
            while (remainingSec > 0 && isActive) {
                onRemainingChange((remainingSec + 59) / 60)
                if (remainingSec <= 5) {
                    val v = (remainingSec / 5f).coerceIn(0.05f, 1f)
                    player.volume = v
                    if (webEngine.isActive) webEngine.setVolume(v)
                }
                delay(1000)
                remainingSec--
            }
            if (webEngine.isActive) webEngine.pause() else player.pause()
            player.volume = 1f; webEngine.setVolume(1f)
            onRemainingChange(null)
        }
    }

    fun setSleepEndOfTrack(enabled: Boolean) { sleepJob?.cancel(); sleepJob = null; sleepEndOfTrack = enabled }

    fun adjustVolumeStep(up: Boolean) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        am.adjustStreamVolume(AudioManager.STREAM_MUSIC, if (up) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
    }

    fun openSystemAudioSettings() {
        runCatching { context.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_SOUND_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    fun getOutputDevices(): List<AudioOutputItem> {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return emptyList()
        val devices = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        val hasExternal = devices.any {
            it.type in listOf(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
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
            AudioOutputItem(d.id, d.productName?.toString()?.takeIf { it.isNotBlank() } ?: typeLabel, typeLabel, active)
        }.distinctBy { it.typeLabel }
    }
}
