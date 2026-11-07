package io.github.abdukir.klippercam.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.view.View
import android.widget.RemoteViews
import io.github.abdukir.klippercam.App
import io.github.abdukir.klippercam.R
import io.github.abdukir.klippercam.data.AppState
import io.github.abdukir.klippercam.data.CameraStream
import io.github.abdukir.klippercam.data.WidgetConfig
import io.github.abdukir.klippercam.service.LiveWidgetService
import io.github.abdukir.klippercam.stream.Frames
import io.github.abdukir.klippercam.stream.StreamStatus
import io.github.abdukir.klippercam.ui.ViewerActivity
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Grid of up to [MAX_TILES] tiles: [rows] rows, row i holding rowCounts[i] tiles. */
data class Grid(val rowCounts: List<Int>, val tileSizes: List<Pair<Int, Int>>)

object WidgetRenderer {
    const val MAX_TILES = 9
    /** Upper bound of bitmap pixels sent per widget update; keeps binder transactions small. */
    private const val PIXEL_BUDGET = 320_000

    private val ROWS = intArrayOf(R.id.row_0, R.id.row_1, R.id.row_2)
    private val TILES = intArrayOf(R.id.tile_0, R.id.tile_1, R.id.tile_2, R.id.tile_3, R.id.tile_4, R.id.tile_5, R.id.tile_6, R.id.tile_7, R.id.tile_8)
    private val IMAGES = intArrayOf(R.id.img_0, R.id.img_1, R.id.img_2, R.id.img_3, R.id.img_4, R.id.img_5, R.id.img_6, R.id.img_7, R.id.img_8)
    private val LABELS = intArrayOf(R.id.label_0, R.id.label_1, R.id.label_2, R.id.label_3, R.id.label_4, R.id.label_5, R.id.label_6, R.id.label_7, R.id.label_8)
    private val STATUSES = intArrayOf(R.id.status_0, R.id.status_1, R.id.status_2, R.id.status_3, R.id.status_4, R.id.status_5, R.id.status_6, R.id.status_7, R.id.status_8)

    fun allWidgetIds(context: Context): IntArray =
        AppWidgetManager.getInstance(context).getAppWidgetIds(ComponentName(context, CamWidgetProvider::class.java))

    fun streamsOf(state: AppState, config: WidgetConfig?): List<CameraStream> =
        config?.streamIds?.mapNotNull { state.stream(it) }?.take(MAX_TILES).orEmpty()

    /** Widget size in px for the current orientation. */
    private fun widgetSizePx(context: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
        val o = mgr.getAppWidgetOptions(id)
        val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        var wDp = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
        var hDp = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
        if (wDp <= 0) wDp = 250
        if (hDp <= 0) hDp = 160
        val d = context.resources.displayMetrics.density
        return ((wDp - 8) * d).roundToInt() to ((hDp - 8) * d).roundToInt()
    }

    fun grid(context: Context, mgr: AppWidgetManager, id: Int, n: Int, maxRes: Int): Grid {
        if (n <= 0) return Grid(emptyList(), emptyList())
        val (w, h) = widgetSizePx(context, mgr, id)
        var bestCols = 1
        var bestScore = -1.0
        for (cols in 1..min(n, 3)) {
            val rows = ceil(n / cols.toDouble()).toInt()
            if (rows > 3) continue
            val tw = w.toDouble() / cols
            val th = h.toDouble() / rows
            val fitW = min(tw, th * 16 / 9)
            val score = fitW * fitW * 9 / 16 * n
            if (score > bestScore) { bestScore = score; bestCols = cols }
        }
        val rows = ceil(n / bestCols.toDouble()).toInt()
        val counts = List(rows) { r -> if (r < rows - 1) bestCols else n - bestCols * (rows - 1) }
        val raw = counts.flatMap { c -> List(c) { (w / c) to (h / rows) } }
        // Scale all tiles down uniformly to the pixel budget and the configured max resolution.
        val total = raw.sumOf { it.first.toLong() * it.second }
        var k = if (total > PIXEL_BUDGET) sqrt(PIXEL_BUDGET.toDouble() / total) else 1.0
        val longest = raw.maxOf { max(it.first, it.second) }
        if (longest * k > maxRes) k = maxRes.toDouble() / longest
        val sizes = raw.map { (tw, th) -> (tw * k).roundToInt().coerceAtLeast(16) to (th * k).roundToInt().coerceAtLeast(16) }
        return Grid(counts, sizes)
    }

    /** Full re-render: layout, labels, click targets, and the latest frames if available. */
    fun renderFull(context: Context, id: Int) {
        val app = App.instance
        val state = app.repo.current
        val mgr = AppWidgetManager.getInstance(context)
        val config = state.widget(id)
        val streams = streamsOf(state, config)
        val views = RemoteViews(context.packageName, R.layout.widget_cam)

        if (config == null || streams.isEmpty()) {
            views.setViewVisibility(R.id.grid, View.GONE)
            views.setViewVisibility(R.id.empty, View.VISIBLE)
            views.setViewVisibility(R.id.live_toggle, View.GONE)
            views.setOnClickPendingIntent(R.id.empty, configIntent(context, id))
            mgr.updateAppWidget(id, views)
            return
        }

        views.setViewVisibility(R.id.grid, View.VISIBLE)
        views.setViewVisibility(R.id.empty, View.GONE)
        val g = grid(context, mgr, id, streams.size, state.settings.widgetMaxResolution)
        var tile = 0
        for (r in ROWS.indices) {
            val count = g.rowCounts.getOrElse(r) { 0 }
            views.setViewVisibility(ROWS[r], if (count > 0) View.VISIBLE else View.GONE)
            for (c in 0 until 3) {
                val t = r * 3 + c
                views.setViewVisibility(TILES[t], if (c < count) View.VISIBLE else View.GONE)
                if (c < count) {
                    val s = streams[tile]
                    val printer = state.printer(s.printerId)
                    val label = if (state.printers.size > 1 && printer != null) "${printer.name} · ${s.name}" else s.name
                    views.setTextViewText(LABELS[t], label)
                    views.setViewVisibility(LABELS[t], if (config.showLabels) View.VISIBLE else View.GONE)
                    views.setOnClickPendingIntent(TILES[t], viewerIntent(context, id, streams.map { it.id }, tile))
                    val (tw, th) = g.tileSizes[tile]
                    val frame = app.hub.latest(s.id)
                    if (frame != null) {
                        views.setImageViewBitmap(IMAGES[t], Frames.renderTile(frame.bitmap, tw, th, s.rotation, s.flipHorizontal, s.flipVertical, config.cropToFill))
                    } else {
                        views.setImageViewResource(IMAGES[t], R.drawable.widget_placeholder)
                    }
                    applyStatus(views, t, config, app.hub.status(s.id).value, frame != null)
                    tile++
                }
            }
        }
        val running = LiveWidgetService.isRunning
        views.setViewVisibility(R.id.live_toggle, View.VISIBLE)
        views.setImageViewResource(R.id.live_toggle, if (running) R.drawable.ic_pause else R.drawable.ic_play)
        views.setOnClickPendingIntent(R.id.live_toggle, LiveWidgetService.toggleIntent(context, running))
        mgr.updateAppWidget(id, views)
    }

    /** Index of the tile slot for the n-th visible tile. */
    fun slotFor(g: Grid, index: Int): Int {
        var i = index
        for (r in g.rowCounts.indices) {
            if (i < g.rowCounts[r]) return r * 3 + i
            i -= g.rowCounts[r]
        }
        return 0
    }

    /** Partial update with new frames / status for the given tile indices. */
    fun renderFrames(
        context: Context, mgr: AppWidgetManager, id: Int, config: WidgetConfig, streams: List<CameraStream>,
        g: Grid, frames: Map<Int, Bitmap>, statuses: Map<Int, Pair<StreamStatus, Boolean>>,
    ) {
        if (frames.isEmpty() && statuses.isEmpty()) return
        val views = RemoteViews(context.packageName, R.layout.widget_cam)
        for ((i, bmp) in frames) {
            val s = streams[i]
            val (tw, th) = g.tileSizes[i]
            views.setImageViewBitmap(IMAGES[slotFor(g, i)], Frames.renderTile(bmp, tw, th, s.rotation, s.flipHorizontal, s.flipVertical, config.cropToFill))
        }
        for ((i, st) in statuses) applyStatus(views, slotFor(g, i), config, st.first, st.second)
        mgr.partiallyUpdateAppWidget(id, views)
    }

    private fun applyStatus(views: RemoteViews, slot: Int, config: WidgetConfig, status: StreamStatus, hasFrame: Boolean) {
        val running = LiveWidgetService.isRunning
        val (text, color) = when {
            status is StreamStatus.Live -> "● LIVE" to Color.rgb(0x4C, 0xD9, 0x64)
            status is StreamStatus.Fallback -> "● SNAPSHOT" to Color.rgb(0xFF, 0xC1, 0x07)
            status is StreamStatus.Offline -> "● OFFLINE" to Color.rgb(0xFF, 0x52, 0x52)
            status is StreamStatus.Connecting -> "Connecting…" to Color.WHITE
            !running -> (if (hasFrame) "Paused" else "Tap ▶ to go live") to Color.LTGRAY
            else -> "Waiting…" to Color.LTGRAY
        }
        // Offline/connecting is always shown; "LIVE" only when status badges are enabled.
        val show = config.showStatus || status is StreamStatus.Offline || status is StreamStatus.Connecting || !hasFrame
        views.setViewVisibility(STATUSES[slot], if (show) View.VISIBLE else View.GONE)
        views.setTextViewText(STATUSES[slot], text)
        views.setTextColor(STATUSES[slot], color)
    }

    fun renderAll(context: Context) = allWidgetIds(context).forEach { renderFull(context, it) }

    private fun configIntent(context: Context, id: Int): PendingIntent {
        val i = Intent(context, WidgetConfigActivity::class.java)
            .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return PendingIntent.getActivity(context, id * 16 + 15, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun viewerIntent(context: Context, id: Int, ids: List<String>, index: Int): PendingIntent {
        val i = ViewerActivity.intent(context, ids, index).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return PendingIntent.getActivity(context, id * 16 + index, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }
}
