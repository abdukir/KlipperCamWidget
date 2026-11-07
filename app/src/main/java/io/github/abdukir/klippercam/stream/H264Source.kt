package io.github.abdukir.klippercam.stream

import android.media.MediaCodec
import android.media.MediaFormat
import android.os.SystemClock
import io.github.abdukir.klippercam.net.Http
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.nio.ByteBuffer

/**
 * Raw Annex-B H.264 over HTTP (paxx12 `/webcam/stream.h264`, camera-streamer `/video.h264`) or
 * over a WebSocket (Moonraker `jmuxer-stream`). Decoded in hardware with MediaCodec into a
 * [GlFrameGrabber].
 */
class H264Source(ctx: SourceContext, private val url: String) : FrameSource(ctx) {
    private var codec: MediaCodec? = null
    private var grabber: GlFrameGrabber? = null
    private val info = MediaCodec.BufferInfo()
    private val startTime = SystemClock.elapsedRealtime()
    private var released = false

    override suspend fun run() {
        val g = GlFrameGrabber(::wantFrame, { maxDim }, ::emit)
        grabber = g
        try {
            withContext(Dispatchers.IO) { g.start() }
            val parser = AnnexBParser { au, key, sps, pps -> decode(au, key, sps, pps) }
            val lower = url.lowercase()
            if (lower.startsWith("ws://") || lower.startsWith("wss://")) runWebSocket(parser) else runHttp(parser)
        } finally {
            synchronized(this) {
                released = true
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                codec = null
            }
            g.release()
        }
    }

    private suspend fun runHttp(parser: AnnexBParser) {
        Http.streaming.newCall(Request.Builder().url(url).build()).executeBlocking { resp ->
            if (!resp.isSuccessful) throw IOException("H.264 HTTP ${resp.code}")
            val input = resp.body?.byteStream() ?: throw IOException("Empty H.264 response")
            val chunk = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(chunk)
                if (n < 0) throw IOException("H.264 stream ended")
                synchronized(this) { parser.feed(chunk, 0, n) }
            }
        }
    }

    private suspend fun runWebSocket(parser: AnnexBParser) {
        val done = CompletableDeferred<Unit>()
        val ws = Http.streaming.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                val arr = bytes.toByteArray()
                try {
                    synchronized(this@H264Source) { parser.feed(arr, 0, arr.size) }
                } catch (e: Exception) {
                    done.completeExceptionally(e)
                }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { done.completeExceptionally(t) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { done.completeExceptionally(IOException("WebSocket closed: $reason")) }
        })
        try { done.await() } finally { ws.cancel() }
    }

    // Called with the H264Source lock held (see feed sites), like teardown in run().
    private fun decode(au: ByteArray, key: Boolean, sps: ByteArray?, pps: ByteArray?) {
        if (released) return
        var c = codec
        if (c == null) {
            if (!key || sps == null || pps == null) return // wait for a keyframe with parameter sets
            val (w, h) = SpsParser.size(sps) ?: (1920 to 1080)
            val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
                setByteBuffer("csd-0", ByteBuffer.wrap(AnnexBParser.START + sps))
                setByteBuffer("csd-1", ByteBuffer.wrap(AnnexBParser.START + pps))
                setInteger(MediaFormat.KEY_PRIORITY, 0)
            }
            grabber?.videoWidth = w
            grabber?.videoHeight = h
            c = MediaCodec.createDecoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            c.configure(format, grabber!!.surface, null, 0)
            c.start()
            codec = c
        }
        var queued = false
        var attempts = 0
        while (!queued && attempts++ < 50) {
            val idx = c.dequeueInputBuffer(10_000)
            if (idx >= 0) {
                val inBuf = c.getInputBuffer(idx)!!
                inBuf.clear()
                if (au.size > inBuf.capacity()) {
                    c.queueInputBuffer(idx, 0, 0, 0, 0)
                    throw IOException("Access unit larger than decoder buffer")
                }
                inBuf.put(au)
                val pts = (SystemClock.elapsedRealtime() - startTime) * 1000
                c.queueInputBuffer(idx, 0, au.size, pts, if (key) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
                queued = true
            }
            drain(c)
        }
    }

    private fun drain(c: MediaCodec) {
        while (true) {
            val idx = c.dequeueOutputBuffer(info, 0)
            when {
                idx >= 0 -> c.releaseOutputBuffer(idx, true)
                idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val f = c.outputFormat
                    var w = f.getInteger(MediaFormat.KEY_WIDTH)
                    var h = f.getInteger(MediaFormat.KEY_HEIGHT)
                    if (f.containsKey("crop-right") && f.containsKey("crop-left")) {
                        w = f.getInteger("crop-right") - f.getInteger("crop-left") + 1
                        h = f.getInteger("crop-bottom") - f.getInteger("crop-top") + 1
                    }
                    grabber?.videoWidth = w
                    grabber?.videoHeight = h
                }
                else -> return
            }
        }
    }
}
