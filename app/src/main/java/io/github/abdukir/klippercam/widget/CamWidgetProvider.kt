package io.github.abdukir.klippercam.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.os.Bundle
import io.github.abdukir.klippercam.App
import io.github.abdukir.klippercam.service.LiveWidgetService
import io.github.abdukir.klippercam.stream.StreamStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch

class CamWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(context: Context, mgr: AppWidgetManager, ids: IntArray) {
        ids.forEach { WidgetRenderer.renderFull(context, it) }
        val app = App.instance
        if (LiveWidgetService.isRunning) return
        // start() only queues the service; if it was accepted the service will push frames itself.
        val started = app.repo.current.settings.liveWidgets && LiveWidgetService.start(context)
        if (!started) refreshSnapshots(context, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle) {
        WidgetRenderer.renderFull(context, id)
        App.instance.notifyWidgetsChanged()
    }

    override fun onDeleted(context: Context, ids: IntArray) {
        App.instance.repo.deleteWidgets(ids)
        App.instance.notifyWidgetsChanged()
    }

    override fun onDisabled(context: Context) {
        LiveWidgetService.stop(context)
    }

    /** One-shot refresh used while live mode is off (periodic update / boot). */
    private fun refreshSnapshots(context: Context, ids: IntArray) {
        val pending = goAsync()
        val app = App.instance
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val state = app.repo.current
                val mgr = AppWidgetManager.getInstance(context)
                ids.map { id ->
                    async {
                        val config = state.widget(id) ?: return@async
                        val streams = WidgetRenderer.streamsOf(state, config)
                        if (streams.isEmpty()) return@async
                        val g = WidgetRenderer.grid(context, mgr, id, streams.size, state.settings.widgetMaxResolution)
                        val frames = streams.mapIndexed { i, s ->
                            async { app.hub.grabOnce(s.id, state.settings.widgetMaxResolution, 7000)?.let { i to it } }
                        }.awaitAll().filterNotNull().toMap()
                        val statuses = streams.indices.associateWith { i -> StreamStatus.Idle to (i in frames) }
                        WidgetRenderer.renderFrames(context, mgr, id, config, streams, g, frames, statuses)
                    }
                }.awaitAll()
            } finally {
                pending.finish()
            }
        }
    }
}
