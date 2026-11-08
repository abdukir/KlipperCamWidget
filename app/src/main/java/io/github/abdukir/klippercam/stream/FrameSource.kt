package io.github.abdukir.klippercam.stream

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import io.github.abdukir.klippercam.data.AppSettings
import io.github.abdukir.klippercam.data.CameraStream
import io.github.abdukir.klippercam.data.Printer

/** Everything a source needs to connect. URLs are already resolved against the printer address. */
class SourceContext(
    val context: Context,
    val printer: Printer,
    val stream: CameraStream,
    val streamUrl: String?,
    val snapshotUrl: String?,
    val settings: AppSettings,
)

/**
 * Produces frames of a camera. [run] connects and suspends while frames are flowing; it throws
 * (or returns) when the connection ends, and the [StreamHub] decides when to retry.
 *
 * Sources decode/convert only as many frames as [fps] asks for, scaled so the longest edge is at
 * most [maxDim]. Each emitted bitmap is a fresh, immutable-by-convention instance that consumers
 * may keep.
 */
abstract class FrameSource(protected val ctx: SourceContext) {
    @Volatile var maxDim: Int = 640
    @Volatile var fps: Int = 2

    internal var sink: ((Bitmap) -> Unit)? = null
    /** Tells the hub the connection is healthy even though no new frame was produced. */
    internal var heartbeat: (() -> Unit)? = null
    private var lastEmit = 0L

    abstract suspend fun run()

    /** Cheap check sources call before doing any decoding work for a frame. */
    protected fun wantFrame(): Boolean {
        val interval = 1000L / fps.coerceIn(1, 60)
        return SystemClock.elapsedRealtime() - lastEmit >= interval - 5
    }

    protected fun alive() {
        heartbeat?.invoke()
    }

    protected fun emit(bitmap: Bitmap) {
        lastEmit = SystemClock.elapsedRealtime()
        sink?.invoke(bitmap)
    }

    val frameIntervalMs: Long get() = 1000L / fps.coerceIn(1, 60)
}
