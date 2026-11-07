package io.github.abdukir.klippercam

import io.github.abdukir.klippercam.stream.MultipartJpegReader
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.EOFException

class MultipartJpegReaderTest {
    private val jpeg = javaClass.classLoader!!.getResourceAsStream("frame.jpg")!!.readBytes()

    private fun read(stream: ByteArray, count: Int): List<ByteArray> {
        val r = MultipartJpegReader(ByteArrayInputStream(stream))
        return List(count) { val n = r.next(); r.buffer.copyOf(n) }
    }

    @Test fun paxx12Format() {
        // Exactly what paxx12's stream-http.py writes for /webcam/stream.mjpg
        val out = ByteArrayOutputStream()
        repeat(3) {
            out.write("--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray())
            out.write(jpeg); out.write("\r\n".toByteArray())
        }
        read(out.toByteArray(), 3).forEach { assertArrayEquals(jpeg, it) }
    }

    @Test fun mjpgStreamerFormat() {
        val out = ByteArrayOutputStream()
        repeat(2) {
            out.write("\r\n--boundarydonotcross\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\nX-Timestamp: 123.456\r\n\r\n".toByteArray())
            out.write(jpeg)
        }
        read(out.toByteArray(), 2).forEach { assertArrayEquals(jpeg, it) }
    }

    @Test fun withoutContentLength() {
        val out = ByteArrayOutputStream()
        repeat(2) {
            out.write("--myboundary\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray())
            out.write(jpeg); out.write("\r\n".toByteArray())
        }
        read(out.toByteArray(), 2).forEach { assertArrayEquals(jpeg, it) }
    }

    @Test(expected = EOFException::class) fun endOfStream() {
        val stream = "--frame\r\nContent-Type: image/jpeg\r\nContent-Length: ${jpeg.size}\r\n\r\n".toByteArray() + jpeg
        val r = MultipartJpegReader(ByteArrayInputStream(stream))
        assertEquals(jpeg.size, r.next())
        r.next()
    }
}
