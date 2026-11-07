package io.github.abdukir.klippercam.stream

import android.os.SystemClock
import io.github.abdukir.klippercam.net.Http
import kotlinx.coroutines.delay
import okhttp3.CacheControl
import okhttp3.Request
import java.io.IOException

/** Polls a JPEG snapshot URL (Moonraker's `mjpegstreamer-adaptive`, or fallback for any camera). */
class SnapshotSource(ctx: SourceContext, private val url: String) : FrameSource(ctx) {
    override suspend fun run() {
        val fpsCap = ctx.stream.targetFps?.takeIf { it > 0 }
        while (true) {
            val started = SystemClock.elapsedRealtime()
            val request = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).build()
            val bmp = Http.client.newCall(request).executeBlocking { resp ->
                if (!resp.isSuccessful) throw IOException("Snapshot HTTP ${resp.code}")
                val bytes = resp.body?.bytes() ?: throw IOException("Empty snapshot")
                Frames.decodeJpeg(bytes, maxDim = maxDim) ?: throw IOException("Snapshot is not an image")
            }
            emit(bmp)
            val effectiveFps = fpsCap?.let { minOf(it, fps) } ?: fps
            val wait = 1000L / effectiveFps.coerceAtLeast(1) - (SystemClock.elapsedRealtime() - started)
            if (wait > 0) delay(wait)
        }
    }
}
