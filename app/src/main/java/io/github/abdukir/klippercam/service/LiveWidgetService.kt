package io.github.abdukir.klippercam.service

import android.app.KeyguardManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.appwidget.AppWidgetManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import io.github.abdukir.klippercam.App
import io.github.abdukir.klippercam.R
import io.github.abdukir.klippercam.data.AppState
import io.github.abdukir.klippercam.stream.FrameRequest
import io.github.abdukir.klippercam.stream.StreamStatus
import io.github.abdukir.klippercam.ui.MainActivity
import io.github.abdukir.klippercam.widget.Grid
import io.github.abdukir.klippercam.widget.WidgetRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Keeps home-screen widgets live while the screen is on. App widgets cannot host video views, so
 * the service pulls frames from the [io.github.abdukir.klippercam.stream.StreamHub] and pushes
 * them into the widgets as bitmaps a few times per second. With the screen off (or locked) every
 * camera connection is closed.
 */
class LiveWidgetService : Service() {
    // Single-threaded so layout caches and subscriptions are never touched concurrently.
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))
    private val app get() = App.instance

    @Volatile private var screenOn = true
    private val layouts = HashMap<Int, Grid>()
    private val sentFrames = HashMap<Pair<Int, Int>, Long>()
    private val sentStatus = HashMap<Pair<Int, Int>, Pair<StreamStatus, Boolean>>()

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> scope.launch { screenOn = false; resubscribe() }
                // Widgets aren't visible on the lock screen, so wait for unlock when it's showing.
                Intent.ACTION_SCREEN_ON -> if (!keyguardLocked()) scope.launch { if (!screenOn) { screenOn = true; resubscribe() } }
                Intent.ACTION_USER_PRESENT -> scope.launch { if (!screenOn) { screenOn = true; resubscribe() } }
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun keyguardLocked() = (getSystemService(KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked

    override fun onCreate() {
        super.onCreate()
        screenOn = (getSystemService(POWER_SERVICE) as PowerManager).isInteractive && !keyguardLocked()
        ContextCompat.registerReceiver(this, screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF); addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_USER_PRESENT)
        }, ContextCompat.RECEIVER_NOT_EXPORTED)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!goForeground()) { stopSelf(); return START_NOT_STICKY }
        if (intent?.action == ACTION_STOP) {
            app.repo.updateSettings { it.copy(liveWidgets = false) }
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_START) app.repo.updateSettings { it.copy(liveWidgets = true) }
        if (!isRunning) {
            isRunning = true
            WidgetRenderer.renderAll(this)
            startLoops()
        }
        return START_STICKY
    }

    private fun goForeground(): Boolean = try {
        ensureChannel()
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, LiveWidgetService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.live_notification_title))
            .setContentText(getString(R.string.live_notification_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.pause), stop)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
        val type = if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, n, type)
        true
    } catch (e: Exception) {
        Log.w(TAG, "Cannot start foreground", e)
        false
    }

    private fun ensureChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(NotificationChannel(CHANNEL, getString(R.string.live_channel), NotificationManager.IMPORTANCE_MIN))
        }
    }

    private fun startLoops() {
        scope.launch {
            app.repo.state.map { Triple(it.widgets, it.streams, it.settings) }.distinctUntilChanged().collect {
                layouts.clear(); sentFrames.clear(); sentStatus.clear()
                WidgetRenderer.renderAll(this@LiveWidgetService)
                resubscribe()
            }
        }
        scope.launch {
            app.widgetEvents.collect {
                layouts.clear(); sentFrames.clear(); sentStatus.clear()
                resubscribe()
            }
        }
        scope.launch {
            while (isActive) {
                val state = app.repo.current
                if (screenOn) runCatching { pushFrames(state) }.onFailure { Log.w(TAG, "Widget update failed", it) }
                delay(1000L / state.settings.widgetFps.coerceIn(1, 15))
            }
        }
    }

    private fun gridFor(state: AppState, mgr: AppWidgetManager, id: Int, n: Int) =
        layouts.getOrPut(id) { WidgetRenderer.grid(this, mgr, id, n, state.settings.widgetMaxResolution) }

    private fun resubscribe() {
        val state = app.repo.current
        val hub = app.hub
        if (!screenOn) { hub.unsubscribeAll(this); return }
        val mgr = AppWidgetManager.getInstance(this)
        val wanted = HashMap<String, Int>()
        for (id in WidgetRenderer.allWidgetIds(this)) {
            val streams = WidgetRenderer.streamsOf(state, state.widget(id))
            if (streams.isEmpty()) continue
            val g = gridFor(state, mgr, id, streams.size)
            streams.forEachIndexed { i, s ->
                val (w, h) = g.tileSizes[i]
                // Frames are rendered with letterboxing/cropping, so ask for the longer edge.
                wanted[s.id] = max(wanted[s.id] ?: 0, max(w, h))
            }
        }
        for (id in hub.activeStreamIds()) if (id !in wanted) hub.unsubscribe(id, this)
        val fps = state.settings.widgetFps.coerceIn(1, 15)
        for ((id, dim) in wanted) hub.subscribe(id, this, FrameRequest(dim, fps))
    }

    private fun pushFrames(state: AppState) {
        val mgr = AppWidgetManager.getInstance(this)
        val hub = app.hub
        for (id in WidgetRenderer.allWidgetIds(this)) {
            val config = state.widget(id) ?: continue
            val streams = WidgetRenderer.streamsOf(state, config)
            if (streams.isEmpty()) continue
            val g = gridFor(state, mgr, id, streams.size)
            val frames = HashMap<Int, android.graphics.Bitmap>()
            val statuses = HashMap<Int, Pair<StreamStatus, Boolean>>()
            streams.forEachIndexed { i, s ->
                val key = id to i
                val frame = hub.latest(s.id)
                if (frame != null && sentFrames[key] != frame.timeMs) {
                    frames[i] = frame.bitmap
                    sentFrames[key] = frame.timeMs
                }
                val st = hub.status(s.id).value to (frame != null)
                if (sentStatus[key] != st) {
                    statuses[i] = st
                    sentStatus[key] = st
                }
            }
            WidgetRenderer.renderFrames(this, mgr, id, config, streams, g, frames, statuses)
        }
    }

    override fun onDestroy() {
        isRunning = false
        scope.cancel()
        runCatching { unregisterReceiver(screenReceiver) }
        app.hub.unsubscribeAll(this)
        WidgetRenderer.renderAll(this)
        super.onDestroy()
    }

    companion object {
        private const val TAG = "LiveWidgetService"
        private const val CHANNEL = "live"
        private const val NOTIFICATION_ID = 1
        const val ACTION_START = "io.github.abdukir.klippercam.START_LIVE"
        const val ACTION_STOP = "io.github.abdukir.klippercam.STOP_LIVE"

        @Volatile var isRunning = false
            private set

        /** Starts live mode if there is at least one configured widget. Safe to call from anywhere. */
        fun start(context: Context, explicit: Boolean = false): Boolean {
            val state = App.instance.repo.current
            val hasWidgets = WidgetRenderer.allWidgetIds(context).any { WidgetRenderer.streamsOf(state, state.widget(it)).isNotEmpty() }
            if (!hasWidgets) return false
            return try {
                val intent = Intent(context, LiveWidgetService::class.java)
                if (explicit) intent.action = ACTION_START
                ContextCompat.startForegroundService(context, intent)
                true
            } catch (e: Exception) {
                // Android 12+ forbids starting from the background; the widget shows a play button.
                Log.i(TAG, "Live mode not started: ${e.message}")
                false
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LiveWidgetService::class.java))
        }

        /** Widget play/pause button. Widget clicks may start foreground services. */
        fun toggleIntent(context: Context, running: Boolean): PendingIntent {
            val intent = Intent(context, LiveWidgetService::class.java).setAction(if (running) ACTION_STOP else ACTION_START)
            return PendingIntent.getForegroundService(context, if (running) 2 else 3, intent, PendingIntent.FLAG_IMMUTABLE)
        }
    }
}
