package io.github.abdukir.klippercam.stream

import io.github.abdukir.klippercam.net.Http
import okhttp3.Request
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * Reads `multipart/x-mixed-replace` MJPEG (mjpg-streamer, ustreamer, camera-streamer, paxx12
 * `/webcam/stream.mjpg`, uv4l). Every part is read off the wire, but only parts that are due
 * according to [fps] are decoded.
 */
class MjpegSource(ctx: SourceContext, private val url: String) : FrameSource(ctx) {
    override suspend fun run() {
        val request = Request.Builder().url(url).header("Accept", "multipart/x-mixed-replace, image/jpeg").build()
        Http.streaming.newCall(request).executeBlocking { resp ->
            if (!resp.isSuccessful) throw IOException("MJPEG HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("Empty MJPEG response")
            val type = body.contentType()?.toString().orEmpty().lowercase()
            val input = BufferedInputStream(body.byteStream(), 64 * 1024)
            if (type.startsWith("image/")) {
                // A single image: the server is really a snapshot endpoint.
                val bytes = input.readBytes()
                Frames.decodeJpeg(bytes, maxDim = maxDim)?.let(::emit)
                throw IOException("Not an MJPEG stream (single image)")
            }
            readParts(input)
        }
    }

    private fun readParts(input: InputStream) {
        val reader = MultipartJpegReader(input)
        while (true) {
            val size = reader.next()
            if (wantFrame()) Frames.decodeJpeg(reader.buffer, size, maxDim)?.let(::emit)
        }
    }
}

/**
 * Pulls JPEG parts out of a multipart MJPEG body. Uses the part's Content-Length when present
 * and falls back to scanning for the JPEG end-of-image marker.
 */
class MultipartJpegReader(private val input: InputStream) {
    var buffer = ByteArray(256 * 1024)
        private set
    private val scratch = ByteArrayOutputStream(256 * 1024)

    private companion object { const val MAX_FRAME = 16 * 1024 * 1024 }

    /** Reads the next part into [buffer] and returns its length. */
    fun next(): Int {
        var line: String
        // Skip to the next part: boundary line, or straight into headers.
        do { line = readLine() } while (!line.startsWith("--") && !line.startsWith("content-", ignoreCase = true))
        var length = -1
        if (line.startsWith("content-", ignoreCase = true)) length = parseLength(line, length)
        while (true) {
            line = readLine()
            if (line.isEmpty()) break
            length = parseLength(line, length)
        }
        if (length > MAX_FRAME) throw IOException("JPEG frame too large ($length bytes)")
        if (length > 0) {
            if (buffer.size < length) buffer = ByteArray(length + length / 2)
            readFully(buffer, length)
            return length
        }
        scratch.reset()
        readUntilEoi(scratch)
        val size = scratch.size()
        if (buffer.size < size) buffer = ByteArray(size * 3 / 2)
        System.arraycopy(scratch.toByteArray(), 0, buffer, 0, size)
        return size
    }

    private fun parseLength(line: String, current: Int): Int {
        val idx = line.indexOf(':')
        if (idx <= 0) return current
        if (!line.substring(0, idx).trim().equals("content-length", ignoreCase = true)) return current
        return line.substring(idx + 1).trim().toIntOrNull() ?: current
    }

    private fun readLine(): String {
        val sb = StringBuilder()
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("MJPEG stream ended")
            if (b == '\n'.code) break
            if (b != '\r'.code) sb.append(b.toChar())
            if (sb.length > 4096) sb.setLength(0) // binary garbage, keep scanning
        }
        return sb.toString()
    }

    private fun readFully(buf: ByteArray, len: Int) {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) throw EOFException("MJPEG stream ended")
            off += n
        }
    }

    private fun readUntilEoi(out: ByteArrayOutputStream) {
        var prev = -1
        var started = false
        while (true) {
            val b = input.read()
            if (b < 0) throw EOFException("MJPEG stream ended")
            if (!started) {
                if (prev == 0xFF && b == 0xD8) { started = true; out.write(0xFF); out.write(0xD8) }
            } else {
                out.write(b)
                if (prev == 0xFF && b == 0xD9) return
                if (out.size() > MAX_FRAME) throw IOException("JPEG frame too large")
            }
            prev = b
        }
    }
}
