package io.github.abdukir.klippercam.stream

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import io.github.abdukir.klippercam.data.AppSettings
import io.github.abdukir.klippercam.data.CameraStream
import io.github.abdukir.klippercam.data.Printer
import io.github.abdukir.klippercam.data.Repository
import io.github.abdukir.klippercam.data.StreamType
import io.github.abdukir.klippercam.data.Urls
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

class Frame(val bitmap: Bitmap, val timeMs: Long = SystemClock.elapsedRealtime())

sealed interface StreamStatus {
    data object Idle : StreamStatus
    data object Connecting : StreamStatus
    data object Live : StreamStatus
    /** Live stream unavailable; showing snapshots instead. */
    data class Fallback(val reason: String) : StreamStatus
    data class Offline(val reason: String) : StreamStatus
}

data class FrameRequest(val maxDim: Int, val fps: Int)

/**
 * Owns at most one connection per camera, shared by every widget and the viewer. Subscribers
 * declare the resolution and frame rate they need; the connection uses the maximum of all of
 * them and is closed when the last subscriber leaves.
 */
class StreamHub(private val context: Context, private val repo: Repository) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val entries = HashMap<String, Entry>()
    private val lastFrames = HashMap<String, MutableStateFlow<Frame?>>()
    private val statuses = HashMap<String, MutableStateFlow<StreamStatus>>()

    private inner class Entry(val streamId: String) {
        val subscribers = HashMap<Any, FrameRequest>()
        var job: Job? = null
        var source: FrameSource? = null
        var config: Any? = null
        val request: FrameRequest
            get() = FrameRequest(
                subscribers.values.maxOfOrNull { it.maxDim } ?: 640,
                subscribers.values.maxOfOrNull { it.fps } ?: 1,
            )
    }

    init {
        // Restart connections whose camera/printer configuration changed.
        scope.launch {
            repo.state.collect { state ->
                synchronized(entries) {
                    for (e in entries.values) {
                        val s = state.stream(e.streamId)
                        val p = s?.let { state.printer(it.printerId) }
                        if (e.job != null && connectionKey(s, p, state.settings) != e.config) restart(e)
                    }
                }
            }
        }
    }

    fun frames(streamId: String): StateFlow<Frame?> = synchronized(entries) {
        lastFrames.getOrPut(streamId) { MutableStateFlow(null) }.asStateFlow()
    }

    fun status(streamId: String): StateFlow<StreamStatus> = synchronized(entries) {
        statuses.getOrPut(streamId) { MutableStateFlow(StreamStatus.Idle) }.asStateFlow()
    }

    fun latest(streamId: String): Frame? = frames(streamId).value

    private fun frameFlow(id: String) = lastFrames.getOrPut(id) { MutableStateFlow(null) }
    private fun statusFlow(id: String) = statuses.getOrPut(id) { MutableStateFlow(StreamStatus.Idle) }

    fun subscribe(streamId: String, owner: Any, request: FrameRequest) = synchronized(entries) {
        val e = entries.getOrPut(streamId) { Entry(streamId) }
        e.subscribers[owner] = request
        e.source?.let { applyRequest(it, e.request) }
        if (e.job == null) start(e)
    }

    fun unsubscribe(streamId: String, owner: Any) = synchronized(entries) {
        val e = entries[streamId] ?: return@synchronized
        e.subscribers.remove(owner)
        if (e.subscribers.isEmpty()) {
            e.job?.cancel()
            e.job = null
            e.source = null
            entries.remove(streamId)
            statusFlow(streamId).value = StreamStatus.Idle
        } else {
            e.source?.let { applyRequest(it, e.request) }
        }
    }

    fun unsubscribeAll(owner: Any) = synchronized(entries) {
        entries.keys.toList().forEach { id -> if (entries[id]?.subscribers?.containsKey(owner) == true) unsubscribe(id, owner) }
    }

    /** Asks a remote-screen source to refresh right away (e.g. after a touch). */
    fun poke(streamId: String) = synchronized(entries) { (entries[streamId]?.source as? ScreenSource)?.poke() }

    fun activeStreamIds(): Set<String> = synchronized(entries) { entries.keys.toSet() }

    private fun applyRequest(src: FrameSource, r: FrameRequest) {
        src.maxDim = r.maxDim
        src.fps = r.fps
    }

    /** What a connection depends on; renames and display options don't require reconnecting. */
    private fun connectionKey(s: CameraStream?, p: Printer?, settings: AppSettings): Any? {
        if (s == null || p == null) return null
        return listOf(
            s.type, s.streamUrl, s.snapshotUrl, s.targetFps, s.snapshotFallback,
            p.address, p.moonrakerPort, settings.useStun, settings.rtspOverTcp,
        )
    }

    private fun restart(e: Entry) {
        val old = e.job
        // Wait for the old connection to close so the camera never sees two viewers from us.
        e.job = scope.launch { old?.cancelAndJoin(); runLoop(e) }
    }

    private fun start(e: Entry) {
        e.job = scope.launch { runLoop(e) }
    }

    private suspend fun runLoop(e: Entry) {
        var failures = 0
        while (scope.isActive) {
            val state = repo.current
            val stream = state.stream(e.streamId)
            val printer = stream?.let { state.printer(it.printerId) }
            if (stream == null || printer == null) {
                statusFlow(e.streamId).value = StreamStatus.Offline("Camera removed")
                return
            }
            synchronized(entries) { e.config = connectionKey(stream, printer, state.settings) }
            val sc = SourceContext(
                context, printer, stream,
                Urls.resolve(printer, stream.streamUrl),
                Urls.resolve(printer, stream.snapshotUrl),
                state.settings,
            )
            val error = attempt(e, sc, primary = true) { failures = 0 }
            failures++
            Log.i(TAG, "${stream.name}: ${error ?: "ended"} (failure #$failures)")
            val reason = error ?: "Disconnected"
            val canFallback = stream.snapshotFallback && sc.snapshotUrl != null && !isSnapshotOnly(sc)
            if (canFallback && failures >= 2) {
                statusFlow(e.streamId).value = StreamStatus.Fallback(reason)
                // Show snapshots for a while, then try the live stream again.
                val snapshotError = withTimeoutOrNull(FALLBACK_MS) { attempt(e, sc, primary = false) {} }
                if (snapshotError != null) {
                    statusFlow(e.streamId).value = StreamStatus.Offline(reason)
                    delay(backoff(failures))
                }
            } else {
                statusFlow(e.streamId).value = if (failures >= 2) StreamStatus.Offline(reason) else StreamStatus.Connecting
                delay(backoff(failures))
            }
        }
    }

    private fun backoff(failures: Int) = minOf(1000L shl minOf(failures, 4), 15_000L)

    /** Runs one connection attempt. Returns the error message, or null if it ended cleanly. */
    private suspend fun attempt(e: Entry, sc: SourceContext, primary: Boolean, onLive: () -> Unit): String? {
        val src = try {
            if (primary) createPrimary(sc) else SnapshotSource(sc, sc.snapshotUrl!!)
        } catch (t: Throwable) {
            return t.message ?: "Invalid camera configuration"
        }
        val status = statusFlow(e.streamId)
        val frames = frameFlow(e.streamId)
        // Keep showing OFFLINE / SNAPSHOT during retries instead of flickering to "Connecting".
        if (primary && (status.value == StreamStatus.Idle || status.value == StreamStatus.Live)) status.value = StreamStatus.Connecting
        var lastFrame = SystemClock.elapsedRealtime()
        src.heartbeat = {
            lastFrame = SystemClock.elapsedRealtime()
            if (primary && status.value != StreamStatus.Live) { status.value = StreamStatus.Live; onLive() }
        }
        src.sink = { bmp ->
            lastFrame = SystemClock.elapsedRealtime()
            frames.value = Frame(bmp)
            if (primary) {
                if (status.value != StreamStatus.Live) { status.value = StreamStatus.Live; onLive() }
            }
        }
        synchronized(entries) {
            e.source = src
            applyRequest(src, e.request)
        }
        return try {
            coroutineScope {
                val runner = launch { src.run() }
                // Watchdog: a connection that stops delivering frames is restarted.
                while (runner.isActive) {
                    delay(1000)
                    val timeout = maxOf(STALL_MS, 4 * src.frameIntervalMs)
                    if (SystemClock.elapsedRealtime() - lastFrame > timeout) {
                        runner.cancel()
                        throw IOException(if (status.value == StreamStatus.Live) "Stream stalled" else "No video received")
                    }
                }
                runner.join()
            }
            null
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            t.message ?: t.javaClass.simpleName
        } finally {
            src.sink = null
            src.heartbeat = null
        }
    }

    private fun isSnapshotOnly(sc: SourceContext): Boolean = sc.streamUrl == null ||
        sc.stream.type == StreamType.MJPEG_ADAPTIVE || sc.stream.type == StreamType.SNAPSHOT || sc.stream.type == StreamType.IFRAME ||
        sc.stream.type == StreamType.SCREEN

    private fun createPrimary(sc: SourceContext): FrameSource {
        val url = sc.streamUrl
        val snapshot = sc.snapshotUrl
        val type = sc.stream.type
        if (type == StreamType.SCREEN) {
            return ScreenSource(sc, RemoteScreen.snapshotUrl(url, snapshot) ?: throw IllegalStateException("Screen needs a snapshot URL"))
        }
        if (url == null) {
            return SnapshotSource(sc, snapshot ?: throw IllegalStateException("Camera has no URL"))
        }
        return when (type) {
            StreamType.WEBRTC_CAMERA_STREAMER -> CameraStreamerSource(sc, url)
            StreamType.WEBRTC_GO2RTC -> Go2rtcSource(sc, url)
            StreamType.WEBRTC_MEDIAMTX -> WhepSource(sc, url)
            StreamType.WEBRTC_JANUS -> JanusSource(sc, url, sc.stream.streamUrl.orEmpty())
            StreamType.MJPEG, StreamType.UV4L_MJPEG -> MjpegSource(sc, url)
            StreamType.MJPEG_ADAPTIVE, StreamType.SNAPSHOT -> SnapshotSource(sc, snapshot ?: url)
            StreamType.H264_STREAM -> H264Source(sc, url)
            StreamType.HLS, StreamType.VIDEO, StreamType.RTSP -> ExoSource(sc, url)
            StreamType.SCREEN -> error("handled above")
            StreamType.IFRAME -> SnapshotSource(sc, snapshot ?: throw IllegalStateException("iframe camera needs a snapshot URL"))
        }
    }

    /** Grabs a single frame without keeping a connection (used for periodic widget refresh). */
    suspend fun grabOnce(streamId: String, maxDim: Int, timeoutMs: Long): Bitmap? {
        latest(streamId)?.takeIf { SystemClock.elapsedRealtime() - it.timeMs < 2000 }?.let { return it.bitmap }
        val owner = Any()
        subscribe(streamId, owner, FrameRequest(maxDim, 2))
        val before = latest(streamId)
        return try {
            withTimeoutOrNull(timeoutMs) {
                frames(streamId).first { it != null && it !== before }?.bitmap
            }
        } finally {
            unsubscribe(streamId, owner)
        }
    }

    private companion object {
        const val TAG = "StreamHub"
        const val STALL_MS = 12_000L
        const val FALLBACK_MS = 30_000L
    }
}
