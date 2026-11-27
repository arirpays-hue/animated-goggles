package com.sonify.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

data class DownloadCoordinatorState(
    val downloadingIds: Set<String> = emptySet(),
    val downloadProgress: Map<String, Int> = emptyMap(),
    val activeBulkPlaylistId: String? = null,
    val bulkDone: Int = 0,
    val bulkTotal: Int = 0,
    val completedVersion: Int = 0,
    val lastMessage: String? = null
)

object OfflineDownloadCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val _state = MutableStateFlow(DownloadCoordinatorState())
    val state: StateFlow<DownloadCoordinatorState> = _state.asStateFlow()

    private val activeSingleJobs = ConcurrentHashMap<String, Job>()
    private var bulkJob: Job? = null
    private val songSemaphore = Semaphore(2)

    @Volatile private var repoRef: StreamRepository? = null
    @Volatile private var appCtxRef: Context? = null
    @Volatile private var serviceCallbacks: ((String, String, Int, Int, Boolean) -> Unit)? = null

    fun attachService(context: Context, onNotify: (title: String, sub: String, progress: Int, max: Int, finished: Boolean) -> Unit) {
        appCtxRef = context.applicationContext
        if (repoRef == null) repoRef = StreamRepository(context.applicationContext)
        serviceCallbacks = onNotify
    }

    fun detachService() {
        serviceCallbacks = null
    }

    fun clearLastMessage() {
        _state.update { it.copy(lastMessage = null) }
    }

    private fun getRepo(context: Context): StreamRepository {
        appCtxRef = context.applicationContext
        return repoRef ?: synchronized(this) {
            repoRef ?: StreamRepository(context.applicationContext).also { repoRef = it }
        }
    }

    private fun startForegroundServiceSafe(context: Context) {
        runCatching {
            val intent = Intent(context.applicationContext, DownloadService::class.java).apply {
                action = DownloadService.ACTION_START
            }
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }
    }

    private fun notifyOrStopIfIdle() {
        val st = _state.value
        val activeCount = st.downloadingIds.size
        if (activeCount == 0 && st.activeBulkPlaylistId == null) {
            serviceCallbacks?.invoke("Sonify", "", 0, 0, true)
            appCtxRef?.let { ctx ->
                runCatching {
                    ctx.startService(Intent(ctx, DownloadService::class.java).apply { action = DownloadService.ACTION_STOP })
                }
            }
        }
    }

    fun enqueueSingle(context: Context, track: Track, settings: AppSettings) {
        if (track.source == StreamSource.LOCAL) return
        if (activeSingleJobs.containsKey(track.id) || track.id in _state.value.downloadingIds) return

        val repo = getRepo(context)
        startForegroundServiceSafe(context)

        _state.update {
            it.copy(
                downloadingIds = it.downloadingIds + track.id,
                downloadProgress = it.downloadProgress + (track.id to 1),
                lastMessage = if (settings.language == AppLanguage.ENGLISH) "Downloading \"${track.title}\"..." else "מוריד \"${track.title}\"..."
            )
        }

        val job = scope.launch {
            try {
                songSemaphore.withPermit {
                    serviceCallbacks?.invoke(track.title, track.artist, 5, 100, false)
                    val (_, msg) = repo.downloadTrackOffline(
                        track = track,
                        settings = settings,
                        onProgress = { pct ->
                            _state.update { cur -> cur.copy(downloadProgress = cur.downloadProgress + (track.id to pct)) }
                            serviceCallbacks?.invoke(track.title, "${track.artist} • $pct%", pct, 100, false)
                        }
                    )
                    _state.update { cur ->
                        cur.copy(
                            completedVersion = cur.completedVersion + 1,
                            lastMessage = msg
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Throwable) {
                _state.update { cur -> cur.copy(lastMessage = "שגיאה בהורדה — נסה שוב") }
            } finally {
                activeSingleJobs.remove(track.id)
                _state.update { cur ->
                    cur.copy(
                        downloadingIds = cur.downloadingIds - track.id,
                        downloadProgress = cur.downloadProgress - track.id
                    )
                }
                notifyOrStopIfIdle()
            }
        }
        activeSingleJobs[track.id] = job
    }

    fun enqueuePlaylist(context: Context, playlistId: String, playlistName: String, tracks: List<Track>, settings: AppSettings) {
        val repo = getRepo(context)
        val offlineIds = repo.getOfflineTrackIds()
        val toDownload = tracks.distinctBy { it.id }.filter { it.id !in offlineIds && it.source != StreamSource.LOCAL }
        val isEn = settings.language == AppLanguage.ENGLISH

        if (toDownload.isEmpty()) {
            _state.update {
                it.copy(lastMessage = if (isEn) "All tracks already downloaded" else "כל הפריטים כבר הורדו לאופליין")
            }
            return
        }

        bulkJob?.cancel()
        startForegroundServiceSafe(context)

        val concurrency = settings.downloadSpeedMode.concurrentSongs.coerceIn(1, 2)
        val localSem = Semaphore(concurrency)
        val doneCounter = AtomicInteger(0)
        val okCounter = AtomicInteger(0)
        val total = toDownload.size

        _state.update {
            it.copy(
                activeBulkPlaylistId = playlistId,
                bulkDone = 0,
                bulkTotal = total,
                lastMessage = if (isEn) "Downloading $total items in background..." else "מוריד $total פריטים ברקע (פעיל גם ביציאה מהאפליקציה)..."
            )
        }
        serviceCallbacks?.invoke(playlistName, "0 / $total", 0, total, false)

        bulkJob = scope.launch {
            try {
                coroutineScope {
                    toDownload.map { trk ->
                        async(Dispatchers.IO) {
                            localSem.withPermit {
                                _state.update { cur ->
                                    cur.copy(
                                        downloadingIds = cur.downloadingIds + trk.id,
                                        downloadProgress = cur.downloadProgress + (trk.id to 2)
                                    )
                                }
                                try {
                                    val (ok, _) = repo.downloadTrackOffline(
                                        track = trk,
                                        settings = settings,
                                        onProgress = { pct ->
                                            _state.update { cur -> cur.copy(downloadProgress = cur.downloadProgress + (trk.id to pct)) }
                                        }
                                    )
                                    if (ok) okCounter.incrementAndGet()
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (_: Throwable) {
                                    // Continue with remaining tracks without crashing!
                                } finally {
                                    val done = doneCounter.incrementAndGet()
                                    _state.update { cur ->
                                        cur.copy(
                                            downloadingIds = cur.downloadingIds - trk.id,
                                            downloadProgress = cur.downloadProgress - trk.id,
                                            bulkDone = done,
                                            completedVersion = cur.completedVersion + 1
                                        )
                                    }
                                    serviceCallbacks?.invoke(playlistName, "$done / $total • ${trk.title}", done, total, false)
                                }
                            }
                        }
                    }.awaitAll()
                }
                val okCount = okCounter.get()
                _state.update { cur ->
                    cur.copy(
                        activeBulkPlaylistId = null,
                        bulkDone = 0,
                        bulkTotal = 0,
                        completedVersion = cur.completedVersion + 1,
                        lastMessage = if (isEn) "Downloaded $okCount / $total items!" else "ירדו $okCount מתוך $total לאופליין!"
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } finally {
                _state.update { cur ->
                    cur.copy(activeBulkPlaylistId = null, bulkDone = 0, bulkTotal = 0)
                }
                notifyOrStopIfIdle()
            }
        }
    }

    fun cancelAll(context: Context, isEn: Boolean = false) {
        bulkJob?.cancel()
        bulkJob = null
        activeSingleJobs.values.forEach { it.cancel() }
        activeSingleJobs.clear()
        _state.update {
            it.copy(
                downloadingIds = emptySet(),
                downloadProgress = emptyMap(),
                activeBulkPlaylistId = null,
                bulkDone = 0,
                bulkTotal = 0,
                lastMessage = if (isEn) "Downloads cancelled" else "ההורדות בוטלו"
            )
        }
        runCatching {
            context.applicationContext.startService(Intent(context.applicationContext, DownloadService::class.java).apply {
                action = DownloadService.ACTION_STOP
            })
        }
    }
}

class DownloadService : Service() {

    companion object {
        const val ACTION_START = "com.sonify.app.DL_START"
        const val ACTION_STOP = "com.sonify.app.DL_STOP"
        const val ACTION_CANCEL = "com.sonify.app.DL_CANCEL"
        private const val CHANNEL_ID = "sonify_downloads_ch"
        private const val NOTIF_ID = 2048
    }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var lastNotifUpdateMs = 0L

    override fun onCreate() {
        super.onCreate()
        createChannel()
        wakeLock = runCatching {
            (getSystemService(Context.POWER_SERVICE) as? PowerManager)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Sonify::OfflineDownloadLock")
                ?.apply { setReferenceCounted(false) }
        }.getOrNull()
        wifiLock = runCatching {
            val wm = applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) WifiManager.WIFI_MODE_FULL_LOW_LATENCY
            else @Suppress("DEPRECATION") WifiManager.WIFI_MODE_FULL_HIGH_PERF
            wm?.createWifiLock(mode, "Sonify::OfflineDownloadWifi")?.apply { setReferenceCounted(false) }
        }.getOrNull()

        OfflineDownloadCoordinator.attachService(this) { title, sub, prog, max, finished ->
            if (finished) {
                stopSelfClean()
            } else {
                val now = System.currentTimeMillis()
                if (now - lastNotifUpdateMs >= 450L || prog >= max) {
                    lastNotifUpdateMs = now
                    val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
                    nm?.notify(NOTIF_ID, buildNotification(title, sub, prog, max))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_CANCEL -> {
                OfflineDownloadCoordinator.cancelAll(this)
                stopSelfClean()
                return START_NOT_STICKY
            }
            ACTION_STOP -> {
                stopSelfClean()
                return START_NOT_STICKY
            }
            else -> {
                acquireLocks()
                val notif = buildNotification("Sonify", "מוריד לאופליין...", 0, 100)
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    } else {
                        startForeground(NOTIF_ID, notif)
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun acquireLocks() {
        runCatching {
            if (wakeLock?.isHeld == false) wakeLock?.acquire(45L * 60L * 1000L)
            if (wifiLock?.isHeld == false) wifiLock?.acquire()
        }
    }

    private fun releaseLocks() {
        runCatching {
            if (wakeLock?.isHeld == true) wakeLock?.release()
            if (wifiLock?.isHeld == true) wifiLock?.release()
        }
    }

    private fun stopSelfClean() {
        releaseLocks()
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
            else @Suppress("DEPRECATION") stopForeground(true)
        }
        stopSelf()
    }

    private fun createChannel() {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
        if (nm.getNotificationChannel(CHANNEL_ID) == null) {
            val ch = NotificationChannel(CHANNEL_ID, "Sonify Offline Downloads", NotificationManager.IMPORTANCE_LOW).apply {
                setShowBadge(false)
                setSound(null, null)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(title: String, sub: String, progress: Int, max: Int): Notification {
        val openPi = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val cancelPi = PendingIntent.getService(
            this, 1,
            Intent(this, DownloadService::class.java).apply { action = ACTION_CANCEL },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle(title)
            .setContentText(sub)
            .setContentIntent(openPi)
            .setOngoing(true)
            .setSilent(true)
            .setProgress(max.coerceAtLeast(1), progress.coerceIn(0, max.coerceAtLeast(1)), max <= 0)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "ביטול", cancelPi)
            .build()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        OfflineDownloadCoordinator.detachService()
        releaseLocks()
        super.onDestroy()
    }
}
