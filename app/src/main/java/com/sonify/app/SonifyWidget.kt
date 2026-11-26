package com.sonify.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.widget.RemoteViews

object WidgetActionBridge {
    var onPrev: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onLike: (() -> Unit)? = null

    @Volatile private var cachedArtUrl: String = ""
    @Volatile private var cachedBitmap: Bitmap? = null

    fun setArtworkBitmap(url: String, rawBitmap: Bitmap?) {
        cachedArtUrl = url
        cachedBitmap = rawBitmap?.let { makeRounded(it, 18f) }
    }

    private fun makeRounded(src: Bitmap, radiusPx: Float): Bitmap {
        val size = 144
        val scaled = Bitmap.createScaledBitmap(src, size, size, true)
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val rect = Rect(0, 0, size, size)
        canvas.drawRoundRect(RectF(rect), radiusPx, radiusPx, paint)
        paint.xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_IN)
        canvas.drawBitmap(scaled, rect, rect, paint)
        return out
    }

    fun updateWidget(
        context: Context,
        title: String,
        artist: String,
        artworkUrl: String,
        isPlaying: Boolean,
        isLiked: Boolean,
        progressPermille: Int
    ) {
        runCatching {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, SonifyWidget::class.java))
            if (ids.isEmpty()) return
            val bmp = if (artworkUrl == cachedArtUrl) cachedBitmap else null
            val views = buildViews(context, title, artist, bmp, isPlaying, isLiked, progressPermille)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }
    }

    fun buildViews(
        context: Context,
        title: String,
        artist: String,
        artwork: Bitmap?,
        isPlaying: Boolean,
        isLiked: Boolean,
        progressPermille: Int
    ): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_sonify)
        views.setTextViewText(R.id.widget_title, title.ifBlank { "Sonify" })
        views.setTextViewText(R.id.widget_artist, artist.ifBlank { "לחץ לפתיחת הנגן" })
        if (artwork != null) {
            views.setImageViewBitmap(R.id.widget_artwork, artwork)
        } else {
            views.setImageViewResource(R.id.widget_artwork, R.drawable.ic_launcher)
        }
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
        views.setImageViewResource(
            R.id.widget_like,
            if (isLiked) android.R.drawable.btn_star_big_on else android.R.drawable.btn_star_big_off
        )
        views.setProgressBar(R.id.widget_progress, 1000, progressPermille.coerceIn(0, 1000), false)

        val openPending = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPending)
        views.setOnClickPendingIntent(R.id.widget_prev, actionPendingIntent(context, SonifyWidget.ACTION_PREV, 1))
        views.setOnClickPendingIntent(R.id.widget_play_pause, actionPendingIntent(context, SonifyWidget.ACTION_PLAY_PAUSE, 2))
        views.setOnClickPendingIntent(R.id.widget_next, actionPendingIntent(context, SonifyWidget.ACTION_NEXT, 3))
        views.setOnClickPendingIntent(R.id.widget_like, actionPendingIntent(context, SonifyWidget.ACTION_LIKE, 4))
        return views
    }

    private fun actionPendingIntent(context: Context, action: String, reqCode: Int): PendingIntent {
        val intent = Intent(context, SonifyWidget::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

class SonifyWidget : AppWidgetProvider() {
    companion object {
        const val ACTION_PREV = "com.sonify.app.WIDGET_PREV"
        const val ACTION_PLAY_PAUSE = "com.sonify.app.WIDGET_PLAY_PAUSE"
        const val ACTION_NEXT = "com.sonify.app.WIDGET_NEXT"
        const val ACTION_LIKE = "com.sonify.app.WIDGET_LIKE"
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = WidgetActionBridge.buildViews(context, "Sonify", "Music & Podcasts", null, false, false, 0)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PREV -> WidgetActionBridge.onPrev?.invoke()
            ACTION_PLAY_PAUSE -> WidgetActionBridge.onPlayPause?.invoke()
            ACTION_NEXT -> WidgetActionBridge.onNext?.invoke()
            ACTION_LIKE -> WidgetActionBridge.onLike?.invoke()
        }
    }
}
