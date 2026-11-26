package com.sonify.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

object WidgetActionBridge {
    var onPrev: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null

    fun updateWidget(context: Context, title: String, artist: String, isPlaying: Boolean) {
        runCatching {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, SonifyWidget::class.java))
            if (ids.isEmpty()) return
            val views = buildViews(context, title, artist, isPlaying)
            ids.forEach { mgr.updateAppWidget(it, views) }
        }
    }

    fun buildViews(context: Context, title: String, artist: String, isPlaying: Boolean): RemoteViews {
        val views = RemoteViews(context.packageName, R.layout.widget_sonify)
        views.setTextViewText(R.id.widget_title, title.ifBlank { "Sonify" })
        views.setTextViewText(R.id.widget_artist, artist.ifBlank { "לחץ לפתיחת הנגן" })
        views.setImageViewResource(
            R.id.widget_play_pause,
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )

        val openIntent = Intent(context, MainActivity::class.java)
        val openPending = PendingIntent.getActivity(
            context, 0, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_root, openPending)

        views.setOnClickPendingIntent(R.id.widget_prev, actionPendingIntent(context, SonifyWidget.ACTION_PREV, 1))
        views.setOnClickPendingIntent(R.id.widget_play_pause, actionPendingIntent(context, SonifyWidget.ACTION_PLAY_PAUSE, 2))
        views.setOnClickPendingIntent(R.id.widget_next, actionPendingIntent(context, SonifyWidget.ACTION_NEXT, 3))
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
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        val views = WidgetActionBridge.buildViews(context, "Sonify", "מוזיקה ללא הגבלה", false)
        appWidgetIds.forEach { appWidgetManager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            ACTION_PREV -> WidgetActionBridge.onPrev?.invoke()
            ACTION_PLAY_PAUSE -> WidgetActionBridge.onPlayPause?.invoke()
            ACTION_NEXT -> WidgetActionBridge.onNext?.invoke()
        }
    }
}
