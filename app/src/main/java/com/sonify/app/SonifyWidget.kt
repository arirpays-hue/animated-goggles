package com.sonify.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.*
import android.os.Bundle
import android.util.LruCache
import android.view.View
import android.widget.RemoteViews
import androidx.palette.graphics.Palette

object WidgetActionBridge {
    var onPrev: (() -> Unit)? = null
    var onPlayPause: (() -> Unit)? = null
    var onNext: (() -> Unit)? = null
    var onLike: (() -> Unit)? = null

    private val artCache = LruCache<String, Bitmap>(12)
    @Volatile private var lastTitle: String = "Sonify"
    @Volatile private var lastArtist: String = "לחץ להפעלה"
    @Volatile private var lastArtworkUrl: String = ""
    @Volatile private var lastPlaying: Boolean = false
    @Volatile private var lastLiked: Boolean = false
    @Volatile private var lastAccentColor: Int = Color.parseColor("#6E655B")
    @Volatile private var quickTiles: List<WidgetQuickTile> = emptyList()

    fun setArtworkBitmap(url: String, bitmap: Bitmap) {
        if (url.isBlank()) return
        artCache.put(url, bitmap)
        runCatching {
            val palette = Palette.from(bitmap).generate()
            val rawColor = palette.getMutedColor(
                palette.getDarkMutedColor(
                    palette.getDarkVibrantColor(Color.parseColor("#5E564E"))
                )
            )
            // Soft warm Spotify-style widget surface tone
            lastAccentColor = blendColors(rawColor, Color.parseColor("#2A2622"), 0.24f)
        }
    }

    fun setTileBitmap(url: String, bitmap: Bitmap) {
        if (url.isNotBlank()) artCache.put(url, bitmap)
    }

    fun updateQuickTiles(context: Context, tiles: List<WidgetQuickTile>) {
        quickTiles = tiles.take(5)
        refreshAllWidgets(context)
    }

    fun updateWidget(
        context: Context,
        title: String,
        artist: String,
        artworkUrl: String,
        isPlaying: Boolean,
        isLiked: Boolean,
        @Suppress("UNUSED_PARAMETER") permille: Int
    ) {
        lastTitle = title.ifBlank { "Sonify" }
        lastArtist = artist.ifBlank { "Sonify" }
        lastArtworkUrl = artworkUrl
        lastPlaying = isPlaying
        lastLiked = isLiked
        refreshAllWidgets(context)
    }

    fun refreshAllWidgets(context: Context) {
        val manager = AppWidgetManager.getInstance(context)
        val ids = manager.getAppWidgetIds(ComponentName(context, SonifyWidget::class.java))
        if (ids.isEmpty()) return
        ids.forEach { id ->
            val opts = manager.getAppWidgetOptions(id)
            updateSingleWidget(context, manager, id, opts)
        }
    }

    internal fun updateSingleWidget(
        context: Context,
        manager: AppWidgetManager,
        widgetId: Int,
        options: Bundle
    ) {
        val minHeightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 72)
        val isExpanded = minHeightDp >= 122

        val views = RemoteViews(context.packageName, R.layout.sonify_widget)

        // 1. Dynamic Spotify-style rounded card background (with darker translucent bottom shelf when expanded)
        val bgBmp = renderWidgetCardBackground(
            baseColor = lastAccentColor,
            widthPx = 920,
            heightPx = if (isExpanded) 440 else 210,
            cornerPx = 56f,
            withBottomShelf = isExpanded
        )
        views.setImageViewBitmap(R.id.widget_bg_image, bgBmp)

        // 2. Text
        views.setTextViewText(R.id.widget_title, lastTitle)
        views.setTextViewText(R.id.widget_artist, lastArtist)

        // 3. Toggle Compact vs Expanded Controls & 5-Tile Shelf
        views.setViewVisibility(R.id.widget_compact_controls, if (isExpanded) View.GONE else View.VISIBLE)
        views.setViewVisibility(R.id.widget_expanded_controls, if (isExpanded) View.VISIBLE else View.GONE)
        views.setViewVisibility(R.id.widget_shelf, if (isExpanded) View.VISIBLE else View.GONE)

        // 4. Crisp Vector-Rendered Controls (Big white circular Play/Pause, Prev, Next, Logo)
        val logoBmp = renderWavesLogo(sizePx = 72, circleColor = Color.WHITE, waveColor = lastAccentColor)
        val playCircleBmp = renderPlayPauseCircle(sizePx = 128, isPlaying = lastPlaying, iconColor = lastAccentColor)
        val prevBmp = renderSkipIcon(sizePx = 96, isNext = false, tint = Color.parseColor("#E6FFFFFF"))
        val nextBmp = renderSkipIcon(sizePx = 96, isNext = true, tint = Color.WHITE)

        views.setImageViewBitmap(R.id.widget_logo_btn, logoBmp)
        views.setImageViewBitmap(R.id.widget_play_compact, playCircleBmp)
        views.setImageViewBitmap(R.id.widget_next_compact, nextBmp)
        views.setImageViewBitmap(R.id.widget_prev_expanded, prevBmp)
        views.setImageViewBitmap(R.id.widget_play_expanded, playCircleBmp)
        views.setImageViewBitmap(R.id.widget_next_expanded, nextBmp)

        // 5. Rounded Album Artwork
        val artRaw = artCache.get(lastArtworkUrl)
        val artBmp = if (artRaw != null) {
            renderRoundedSquareBitmap(artRaw, sizePx = 192, cornerPx = 28f)
        } else {
            renderPlaceholderTile(sizePx = 192, cornerPx = 28f, bgStart = Color.parseColor("#1DB954"), bgEnd = Color.parseColor("#0D5C2B"), isHeart = false)
        }
        views.setImageViewBitmap(R.id.widget_artwork, artBmp)

        // 6. Click Actions
        val openAppPi = PendingIntent.getActivity(
            context, 10,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        views.setOnClickPendingIntent(R.id.widget_text_box, openAppPi)
        views.setOnClickPendingIntent(R.id.widget_artwork, openAppPi)
        views.setOnClickPendingIntent(R.id.widget_logo_btn, openAppPi)

        val playPi = broadcastPi(context, 11, SonifyWidget.ACTION_PLAY_PAUSE)
        val nextPi = broadcastPi(context, 12, SonifyWidget.ACTION_NEXT)
        val prevPi = broadcastPi(context, 13, SonifyWidget.ACTION_PREV)

        views.setOnClickPendingIntent(R.id.widget_play_compact, playPi)
        views.setOnClickPendingIntent(R.id.widget_next_compact, nextPi)
        views.setOnClickPendingIntent(R.id.widget_play_expanded, playPi)
        views.setOnClickPendingIntent(R.id.widget_next_expanded, nextPi)
        views.setOnClickPendingIntent(R.id.widget_prev_expanded, prevPi)

        // 7. Bottom 5 Quick-Access Tiles (Expanded 4x2 mode)
        if (isExpanded) {
            val tileViewIds = listOf(R.id.widget_tile_0, R.id.widget_tile_1, R.id.widget_tile_2, R.id.widget_tile_3, R.id.widget_tile_4)
            val tiles = quickTiles
            for (i in 0 until 5) {
                val viewId = tileViewIds[i]
                // Rightmost tile (index 4) is always Liked Songs purple gradient with white heart!
                if (i == 4) {
                    val likedTileBmp = renderPlaceholderTile(
                        sizePx = 144,
                        cornerPx = 22f,
                        bgStart = Color.parseColor("#4F33F5"),
                        bgEnd = Color.parseColor("#9DA8F8"),
                        isHeart = true
                    )
                    views.setImageViewBitmap(viewId, likedTileBmp)
                    val likedIntent = PendingIntent.getActivity(
                        context, 200 + i,
                        Intent(context, MainActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = android.net.Uri.parse("sonify://liked")
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(viewId, likedIntent)
                } else {
                    val tile = tiles.getOrNull(i)
                    val tileRaw = tile?.artworkUrl?.takeIf { it.isNotBlank() }?.let { artCache.get(it) }
                    val tileBmp = when {
                        tileRaw != null -> renderRoundedSquareBitmap(tileRaw, 144, 22f)
                        i == 1 -> renderWavesTile(144, 22f)
                        else -> renderPlaceholderTile(
                            sizePx = 144,
                            cornerPx = 22f,
                            bgStart = Color.parseColor(listOf("#1E3264", "#1DB954", "#8D67AB", "#B02897")[i % 4]),
                            bgEnd = Color.parseColor("#181818"),
                            isHeart = false
                        )
                    }
                    views.setImageViewBitmap(viewId, tileBmp)

                    val uriStr = when {
                        tile?.playlistId != null -> "sonify://playlist/${tile.playlistId}"
                        tile?.trackId != null -> "sonify://track/${tile.trackId}"
                        else -> "sonify://home"
                    }
                    val tilePi = PendingIntent.getActivity(
                        context, 200 + i,
                        Intent(context, MainActivity::class.java).apply {
                            action = Intent.ACTION_VIEW
                            data = android.net.Uri.parse(uriStr)
                            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                        },
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                    views.setOnClickPendingIntent(viewId, tilePi)
                }
            }
        }

        manager.updateAppWidget(widgetId, views)
    }

    private fun broadcastPi(context: Context, reqCode: Int, action: String): PendingIntent {
        val intent = Intent(context, SonifyWidget::class.java).apply { this.action = action }
        return PendingIntent.getBroadcast(context, reqCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
        val inv = 1f - ratio
        val r = (Color.red(c1) * inv + Color.red(c2) * ratio).toInt().coerceIn(0, 255)
        val g = (Color.green(c1) * inv + Color.green(c2) * ratio).toInt().coerceIn(0, 255)
        val b = (Color.blue(c1) * inv + Color.blue(c2) * ratio).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun renderWidgetCardBackground(baseColor: Int, widthPx: Int, heightPx: Int, cornerPx: Float, withBottomShelf: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(widthPx, heightPx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
        val clipPath = Path().apply { addRoundRect(rect, cornerPx, cornerPx, Path.Direction.CW) }
        canvas.clipPath(clipPath)

        val topPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, widthPx.toFloat(), heightPx.toFloat(),
                blendColors(baseColor, Color.WHITE, 0.08f),
                blendColors(baseColor, Color.BLACK, 0.14f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, topPaint)

        if (withBottomShelf) {
            val shelfTop = heightPx * 0.63f
            val shelfPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.argb(66, 0, 0, 0)
            }
            canvas.drawRect(0f, shelfTop, widthPx.toFloat(), heightPx.toFloat(), shelfPaint)
        }
        return bmp
    }

    private fun renderRoundedSquareBitmap(src: Bitmap, sizePx: Int, cornerPx: Float): Bitmap {
        val out = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val path = Path().apply { addRoundRect(rect, cornerPx, cornerPx, Path.Direction.CW) }
        canvas.clipPath(path)

        val minSide = minOf(src.width, src.height)
        val left = (src.width - minSide) / 2
        val top = (src.height - minSide) / 2
        canvas.drawBitmap(src, Rect(left, top, left + minSide, top + minSide), rect, paint)
        return out
    }

    private fun renderPlayPauseCircle(sizePx: Int, isPlaying: Boolean, iconColor: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = sizePx / 2f
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F5F5F5") }
        canvas.drawCircle(r, r, r - 2f, bgPaint)

        val fgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blendColors(iconColor, Color.BLACK, 0.35f)
            style = Paint.Style.FILL
        }
        if (isPlaying) {
            val barW = sizePx * 0.12f
            val barH = sizePx * 0.38f
            val gap = sizePx * 0.08f
            val top = (sizePx - barH) / 2f
            canvas.drawRoundRect(RectF(r - gap - barW, top, r - gap, top + barH), 6f, 6f, fgPaint)
            canvas.drawRoundRect(RectF(r + gap, top, r + gap + barW, top + barH), 6f, 6f, fgPaint)
        } else {
            val path = Path().apply {
                moveTo(sizePx * 0.41f, sizePx * 0.31f)
                lineTo(sizePx * 0.72f, sizePx * 0.50f)
                lineTo(sizePx * 0.41f, sizePx * 0.69f)
                close()
            }
            canvas.drawPath(path, fgPaint)
        }
        return bmp
    }

    private fun renderSkipIcon(sizePx: Int, isNext: Boolean, tint: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = tint; style = Paint.Style.FILL }
        if (isNext) {
            val tri = Path().apply {
                moveTo(sizePx * 0.24f, sizePx * 0.22f)
                lineTo(sizePx * 0.64f, sizePx * 0.50f)
                lineTo(sizePx * 0.24f, sizePx * 0.78f)
                close()
            }
            canvas.drawPath(tri, p)
            canvas.drawRoundRect(RectF(sizePx * 0.68f, sizePx * 0.22f, sizePx * 0.78f, sizePx * 0.78f), 4f, 4f, p)
        } else {
            canvas.drawRoundRect(RectF(sizePx * 0.22f, sizePx * 0.22f, sizePx * 0.32f, sizePx * 0.78f), 4f, 4f, p)
            val tri = Path().apply {
                moveTo(sizePx * 0.76f, sizePx * 0.22f)
                lineTo(sizePx * 0.36f, sizePx * 0.50f)
                lineTo(sizePx * 0.76f, sizePx * 0.78f)
                close()
            }
            canvas.drawPath(tri, p)
        }
        return bmp
    }

    private fun renderWavesLogo(sizePx: Int, circleColor: Int, waveColor: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val r = sizePx / 2f
        canvas.drawCircle(r, r, r - 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = circleColor })
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = blendColors(waveColor, Color.BLACK, 0.3f)
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        stroke.strokeWidth = sizePx * 0.085f
        canvas.drawArc(RectF(sizePx * 0.16f, sizePx * 0.28f, sizePx * 0.84f, sizePx * 0.88f), 220f, 100f, false, stroke)
        stroke.strokeWidth = sizePx * 0.075f
        canvas.drawArc(RectF(sizePx * 0.23f, sizePx * 0.44f, sizePx * 0.77f, sizePx * 0.94f), 220f, 100f, false, stroke)
        stroke.strokeWidth = sizePx * 0.065f
        canvas.drawArc(RectF(sizePx * 0.30f, sizePx * 0.59f, sizePx * 0.70f, sizePx * 0.98f), 220f, 100f, false, stroke)
        return bmp
    }

    private fun renderWavesTile(sizePx: Int, cornerPx: Float): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        canvas.drawRoundRect(rect, cornerPx, cornerPx, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1DB954") })
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#121212")
            style = Paint.Style.STROKE
            strokeCap = Paint.Cap.ROUND
        }
        stroke.strokeWidth = sizePx * 0.08f
        canvas.drawArc(RectF(sizePx * 0.18f, sizePx * 0.30f, sizePx * 0.82f, sizePx * 0.88f), 220f, 100f, false, stroke)
        stroke.strokeWidth = sizePx * 0.07f
        canvas.drawArc(RectF(sizePx * 0.25f, sizePx * 0.46f, sizePx * 0.75f, sizePx * 0.94f), 220f, 100f, false, stroke)
        stroke.strokeWidth = sizePx * 0.06f
        canvas.drawArc(RectF(sizePx * 0.32f, sizePx * 0.61f, sizePx * 0.68f, sizePx * 0.98f), 220f, 100f, false, stroke)
        return bmp
    }

    private fun renderPlaceholderTile(sizePx: Int, cornerPx: Float, bgStart: Int, bgEnd: Int, isHeart: Boolean): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val rect = RectF(0f, 0f, sizePx.toFloat(), sizePx.toFloat())
        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, sizePx.toFloat(), sizePx.toFloat(), bgStart, bgEnd, Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, cornerPx, cornerPx, bgPaint)

        val fg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.FILL }
        if (isHeart) {
            val w = sizePx.toFloat()
            val heart = Path().apply {
                moveTo(w * 0.50f, w * 0.72f)
                cubicTo(w * 0.20f, w * 0.52f, w * 0.22f, w * 0.28f, w * 0.38f, w * 0.28f)
                cubicTo(w * 0.45f, w * 0.28f, w * 0.49f, w * 0.34f, w * 0.50f, w * 0.38f)
                cubicTo(w * 0.51f, w * 0.34f, w * 0.55f, w * 0.28f, w * 0.62f, w * 0.28f)
                cubicTo(w * 0.78f, w * 0.28f, w * 0.80f, w * 0.52f, w * 0.50f, w * 0.72f)
                close()
            }
            canvas.drawPath(heart, fg)
        } else {
            canvas.drawCircle(sizePx * 0.42f, sizePx * 0.64f, sizePx * 0.10f, fg)
            canvas.drawRoundRect(RectF(sizePx * 0.48f, sizePx * 0.28f, sizePx * 0.55f, sizePx * 0.65f), 4f, 4f, fg)
            canvas.drawRoundRect(RectF(sizePx * 0.48f, sizePx * 0.28f, sizePx * 0.68f, sizePx * 0.36f), 4f, 4f, fg)
        }
        return bmp
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
        appWidgetIds.forEach { id ->
            val opts = appWidgetManager.getAppWidgetOptions(id)
            WidgetActionBridge.updateSingleWidget(context, appWidgetManager, id, opts)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle
    ) {
        WidgetActionBridge.updateSingleWidget(context, appWidgetManager, appWidgetId, newOptions)
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
