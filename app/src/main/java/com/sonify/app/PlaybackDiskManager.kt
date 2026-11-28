@file:androidx.annotation.OptIn(UnstableApi::class)

package com.sonify.app

import android.content.Context
import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

data class PlaybackDiskStats(
    val usedMb: Float,
    val limitMb: Int,
    val cachedItemsCount: Int
)

/**
 * Dedicated Playback Disk Manager for instant track-to-track transitions.
 * Manages both ExoPlayer's SimpleCache and instant-start pre-cached audio files
 * under a single user-configurable MB storage quota (with automatic LRU eviction).
 * Completely separate from permanent Offline Downloads!
 */
object PlaybackDiskManager {

    private const val PREFS_NAME = "sonify_prefs"
    private const val KEY_LIMIT_MB = "playback_disk_limit_mb"
    const val DEFAULT_LIMIT_MB = 500
    const val MIN_LIMIT_MB = 100
    const val MAX_LIMIT_MB = 4096

    @Volatile private var cacheInstance: SimpleCache? = null
    @Volatile private var configuredLimitMb: Int = DEFAULT_LIMIT_MB
    private val cacheLock = Any()
    private val activePrewarm = AtomicBoolean(false)

    fun cacheKeyFor(trackId: String): String =
        "sonify_track_${trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")}"

    fun getLimitMb(context: Context): Int {
        val saved = context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_LIMIT_MB, DEFAULT_LIMIT_MB)
            .coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)
        configuredLimitMb = saved
        return saved
    }

    fun setLimitMb(context: Context, limitMb: Int) {
        val clamped = limitMb.coerceIn(MIN_LIMIT_MB, MAX_LIMIT_MB)
        configuredLimitMb = clamped
        context.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_LIMIT_MB, clamped)
            .apply()
        enforceQuotaNow(context, clamped)
    }

    fun exoCacheDir(context: Context): File =
        File(context.applicationContext.cacheDir, "exo_stream_cache").apply { if (!exists()) mkdirs() }

    fun instantDiskDir(context: Context): File =
        File(context.applicationContext.cacheDir, "stream_precache").apply { if (!exists()) mkdirs() }

    fun getInstantAudioFile(context: Context, trackId: String): File {
        val safe = trackId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        return File(instantDiskDir(context), "$safe.cache")
    }

    fun getSimpleCache(context: Context): SimpleCache {
        return cacheInstance ?: synchronized(cacheLock) {
            cacheInstance ?: run {
                val limitMb = getLimitMb(context)
                val maxBytes = limitMb.toLong() * 1024L * 1024L
                val dir = exoCacheDir(context)
                val db = StandaloneDatabaseProvider(context.applicationContext)
                SimpleCache(dir, LeastRecentlyUsedCacheEvictor(maxBytes), db).also { cacheInstance = it }
            }
        }
    }

    fun isTrackWarmedOnDisk(context: Context, trackId: String): Boolean {
        val instant = getInstantAudioFile(context, trackId)
        if (instant.exists() && instant.length() > 48_000L) return true
        return runCatching {
            val cache = getSimpleCache(context)
            val key = cacheKeyFor(trackId)
            cache.getCachedBytes(key, 0L, 512L * 1024L) >= 131_072L
        }.getOrDefault(false)
    }

    fun buildCacheDataSourceFactory(
        context: Context,
        userAgent: String,
        cookie: String? = null
    ): CacheDataSource.Factory {
        val headers = mutableMapOf<String, String>()
        if (!cookie.isNullOrBlank()) headers["Cookie"] = cookie
        val httpFactory = DefaultHttpDataSource.Factory()
            .setUserAgent(userAgent)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(8_000)
            .setReadTimeoutMs(12_000)
            .setDefaultRequestProperties(headers)
        val upstream = DefaultDataSource.Factory(context.applicationContext, httpFactory)
        return CacheDataSource.Factory()
            .setCache(getSimpleCache(context))
            .setUpstreamDataSourceFactory(upstream)
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
    }

    /**
     * Streams the first ~2.2MB of an upcoming track into ExoPlayer's SimpleCache under its stable Track ID key.
     * Serialized via `activePrewarm` so background pre-warming NEVER competes with active playback!
     */
    fun prewarmTrackHeadOnDisk(
        context: Context,
        trackId: String,
        audioUrl: String,
        userAgent: String,
        cookie: String? = null,
        maxBytes: Long = 2_200_000L
    ): Boolean {
        if (audioUrl.startsWith("file:") || audioUrl.startsWith("content:") || audioUrl.contains(".m3u8", true)) return false
        if (isTrackWarmedOnDisk(context, trackId)) return true
        if (!activePrewarm.compareAndSet(false, true)) return false
        return try {
            val cache = getSimpleCache(context)
            val key = cacheKeyFor(trackId)
            val dataSource = buildCacheDataSourceFactory(context, userAgent, cookie).createDataSource()
            val dataSpec = DataSpec.Builder()
                .setUri(Uri.parse(audioUrl))
                .setKey(key)
                .setPosition(0L)
                .setLength(maxBytes)
                .build()
            val writer = CacheWriter(dataSource, dataSpec, ByteArray(32 * 1024)) { _, _, _ -> }
            writer.cache()
            enforceQuotaNow(context, getLimitMb(context))
            cache.getCachedBytes(key, 0L, maxBytes) > 96_000L
        } catch (_: Throwable) {
            false
        } finally {
            activePrewarm.set(false)
        }
    }

    /**
     * Enforces the user-selected Playback Disk limit immediately using LRU eviction
     * without interrupting active playback.
     */
    fun enforceQuotaNow(context: Context, limitMb: Int = getLimitMb(context)) {
        runCatching {
            val maxBytes = limitMb.toLong().coerceIn(MIN_LIMIT_MB.toLong(), MAX_LIMIT_MB.toLong()) * 1024L * 1024L
            var total = dirSizeBytes(instantDiskDir(context)) + dirSizeBytes(exoCacheDir(context))
            if (total <= maxBytes) return

            // 1. Evict oldest instant-precache files first
            val instantFiles = instantDiskDir(context)
                .listFiles()
                ?.filter { it.isFile }
                ?.sortedBy { it.lastModified() }
                .orEmpty()
            for (f in instantFiles) {
                if (total <= maxBytes) break
                val len = f.length()
                if (f.delete()) total -= len
            }

            // 2. If still over quota, evict SimpleCache entries until within target budget
            if (total > maxBytes) {
                val cache = cacheInstance ?: return
                val keys = cache.keys.toList()
                for (k in keys) {
                    if (total <= (maxBytes * 9L) / 10L) break
                    val spansBytes = runCatching { cache.getCachedSpans(k).sumOf { it.length } }.getOrDefault(0L)
                    runCatching { cache.removeResource(k) }
                    total = (total - spansBytes).coerceAtLeast(0L)
                }
            }
        }
    }

    fun getDiskStats(context: Context): PlaybackDiskStats {
        val limitMb = getLimitMb(context)
        val instantBytes = dirSizeBytes(instantDiskDir(context))
        val exoBytes = dirSizeBytes(exoCacheDir(context))
        val usedMb = (instantBytes + exoBytes) / (1024f * 1024f)

        val instantCount = instantDiskDir(context).listFiles()?.count { it.isFile && it.extension == "cache" && it.length() > 16_384 } ?: 0
        val exoKeysCount = runCatching { cacheInstance?.keys?.size ?: 0 }.getOrDefault(0)
        return PlaybackDiskStats(
            usedMb = usedMb,
            limitMb = limitMb,
            cachedItemsCount = maxOf(instantCount, exoKeysCount)
        )
    }

    fun clearPlaybackDisk(context: Context) {
        runCatching {
            instantDiskDir(context).listFiles()?.forEach { it.delete() }
        }
        runCatching {
            val c = cacheInstance ?: getSimpleCache(context)
            c.keys.toList().forEach { key -> runCatching { c.removeResource(key) } }
        }
    }

    private fun dirSizeBytes(f: File): Long {
        if (!f.exists()) return 0L
        return if (f.isDirectory) f.listFiles()?.sumOf { dirSizeBytes(it) } ?: 0L else f.length()
    }
}

/**
 * Compatibility bridge for any legacy calls to AudioCacheHolder.
 */
object AudioCacheHolder {
    fun cacheKeyFor(trackId: String): String = PlaybackDiskManager.cacheKeyFor(trackId)
    fun isTrackWarmed(context: Context, trackId: String): Boolean = PlaybackDiskManager.isTrackWarmedOnDisk(context, trackId)
    fun clearAllEntries() {
        // Delegated via PlaybackDiskManager.clearPlaybackDisk(context)
    }
}
