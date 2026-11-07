package io.github.abdukir.klippercam

import io.github.abdukir.klippercam.data.Printer
import io.github.abdukir.klippercam.data.Urls
import org.junit.Assert.assertEquals
import org.junit.Test

class UrlsTest {
    private val u1 = Printer(name = "U1", address = "192.168.1.50")

    @Test fun relativePathsResolveAgainstWebUi() {
        assertEquals("http://192.168.1.50/webcam/webrtc", Urls.resolve(u1, "/webcam/webrtc"))
        assertEquals("http://192.168.1.50/webcam/?action=stream", Urls.resolve(u1, "/webcam/?action=stream"))
        assertEquals("http://192.168.1.50/webcam2/snapshot.jpg", Urls.resolve(u1.copy(address = "http://192.168.1.50/"), "webcam2/snapshot.jpg"))
    }

    @Test fun absoluteAndNonHttpUrlsAreKept() {
        assertEquals("rtsp://192.168.1.50:8554/stream", Urls.resolve(u1, "rtsp://192.168.1.50:8554/stream"))
        assertEquals("http://other:1984/stream.html?src=cam", Urls.resolve(u1, "http://other:1984/stream.html?src=cam"))
        assertEquals("ws://host:8080/h264", Urls.resolve(u1, "ws://host:8080/h264"))
    }

    @Test fun moonrakerPortOverride() {
        assertEquals("http://192.168.1.50/", Urls.moonrakerBase(u1).toString())
        assertEquals("http://192.168.1.50:7125/", Urls.moonrakerBase(u1.copy(moonrakerPort = 7125)).toString())
        // Webcams still resolve against the web UI port.
        assertEquals("http://192.168.1.50/webcam/", Urls.resolve(u1.copy(moonrakerPort = 7125), "/webcam/"))
    }

    @Test fun addressNormalisation() {
        assertEquals("http://u1.local:8080/", Urls.printerBase("u1.local:8080/some/path")?.toString())
        assertEquals("https://printer.example/", Urls.printerBase("https://printer.example")?.toString())
        assertEquals(null, Urls.printerBase("  "))
    }
}
