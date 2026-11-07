package io.github.abdukir.klippercam.stream

import android.graphics.BitmapFactory
import android.os.SystemClock
import io.github.abdukir.klippercam.data.Printer
import io.github.abdukir.klippercam.net.Http
import io.github.abdukir.klippercam.net.MoonrakerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.CacheControl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * The paxx12 firmware's "Remote Screen" (fb-http): `GET /screen/snapshot` returns the printer's
 * framebuffer as PNG with an ETag (304 while unchanged), `POST /screen/touch?a=&x=&y=` injects
 * touch events in framebuffer pixel coordinates. Both go through Moonraker's auth check.
 */
object RemoteScreen {
    /** `/screen/snapshot` → `/screen/touch` (also accepts the `/screen/` page URL). */
    fun touchUrl(snapshotOrPageUrl: String): String? {
        val u = snapshotOrPageUrl.toHttpUrlOrNull() ?: return null
        val path = u.encodedPath
        val base = when {
            path.endsWith("/snapshot") -> path.removeSuffix("snapshot")
            path.endsWith("/") -> path
            else -> "$path/"
        }
        return u.newBuilder().encodedPath(base + "touch").query(null).build().toString()
    }

    /** Snapshot URL for a screen stream given its configured URLs. */
    fun snapshotUrl(streamUrl: String?, snapshotUrl: String?): String? {
        snapshotUrl?.let { return it }
        val u = streamUrl?.toHttpUrlOrNull() ?: return null
        val path = u.encodedPath.let { if (it.endsWith("/")) it else "$it/" }
        return u.newBuilder().encodedPath(path + "snapshot").query(null).build().toString()
    }
}

/** Polls the remote screen, downloading a new PNG only when the screen changed. */
class ScreenSource(ctx: SourceContext, private val url: String) : FrameSource(ctx) {
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var etag: String? = null

    /** Fetch immediately (after a touch) instead of waiting for the next poll. */
    fun poke() { wake.trySend(Unit) }

    override suspend fun run() {
        while (true) {
            val started = SystemClock.elapsedRealtime()
            fetch()
            val wait = frameIntervalMs - (SystemClock.elapsedRealtime() - started)
            if (wait > 0) withTimeoutOrNull(wait) { wake.receive() }
        }
    }

    private suspend fun fetch() {
        val headers = withContext(Dispatchers.IO) { MoonrakerClient.authHeaders(ctx.printer) }
        val request = Request.Builder().url(url).cacheControl(CacheControl.FORCE_NETWORK).apply {
            headers.forEach { (k, v) -> header(k, v) }
            etag?.let { header("If-None-Match", it) }
        }.build()
        Http.client.newCall(request).executeBlocking { resp ->
            when {
                resp.code == 304 -> alive()
                resp.code == 401 || resp.code == 403 -> {
                    MoonrakerClient.invalidateToken(ctx.printer)
                    throw IOException("Unauthorized – set the printer's API key or login")
                }
                resp.code == 404 || resp.code == 502 ->
                    throw IOException("Remote screen not enabled (firmware-config → Web → Remote Screen)")
                !resp.isSuccessful -> throw IOException("Screen HTTP ${resp.code}")
                else -> {
                    val bytes = resp.body?.bytes() ?: throw IOException("Empty screen snapshot")
                    // The screen is small; keep full resolution so touch coordinates map 1:1.
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: throw IOException("Screen snapshot is not an image")
                    etag = resp.header("ETag")
                    emit(bmp)
                }
            }
        }
    }
}

/**
 * Sends touch events to the remote screen in order. Consecutive moves are coalesced so a slow
 * link never builds up a backlog of stale drag positions.
 */
class ScreenTouchSender(private val printer: Printer, private val touchUrl: String, private val onSent: () -> Unit) {
    private data class Touch(val action: String, val x: Int, val y: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = Channel<Touch>(Channel.UNLIMITED)

    init {
        scope.launch {
            while (true) {
                var t = queue.receive()
                // Drop intermediate moves: only the latest position matters.
                while (t.action == "move") {
                    val next = queue.tryReceive().getOrNull() ?: break
                    if (next.action != "move") { post(t); t = next; break }
                    t = next
                }
                post(t)
            }
        }
    }

    fun down(x: Int, y: Int) { queue.trySend(Touch("down", x, y)) }
    fun move(x: Int, y: Int) { queue.trySend(Touch("move", x, y)) }
    fun up(x: Int, y: Int) { queue.trySend(Touch("up", x, y)) }

    private fun post(t: Touch) {
        val url = touchUrl.toHttpUrlOrNull()?.newBuilder()
            ?.addQueryParameter("a", t.action)
            ?.addQueryParameter("x", t.x.toString())
            ?.addQueryParameter("y", t.y.toString())
            ?.build() ?: return
        val req = Request.Builder().url(url).post(ByteArray(0).toRequestBody()).apply {
            MoonrakerClient.authHeaders(printer).forEach { (k, v) -> header(k, v) }
        }.build()
        runCatching { Http.client.newCall(req).execute().close() }
        if (t.action != "move") onSent()
    }

    fun close() = scope.cancel()
}
