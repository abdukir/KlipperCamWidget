package io.github.abdukir.klippercam

import io.github.abdukir.klippercam.data.Printer
import io.github.abdukir.klippercam.data.StreamType
import io.github.abdukir.klippercam.net.MoonrakerClient
import io.github.abdukir.klippercam.stream.RemoteScreen
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RemoteScreenTest {
    @Test fun touchUrlDerivation() {
        assertEquals("http://u1/screen/touch", RemoteScreen.touchUrl("http://u1/screen/snapshot"))
        assertEquals("http://u1/screen/touch", RemoteScreen.touchUrl("http://u1/screen/"))
        assertEquals("http://u1/screen/touch", RemoteScreen.touchUrl("http://u1/screen"))
        assertEquals("http://u1/screen/touch", RemoteScreen.touchUrl("http://u1/screen/snapshot?ts=1"))
    }

    @Test fun snapshotUrlDerivation() {
        assertEquals("http://u1/screen/snapshot", RemoteScreen.snapshotUrl("http://u1/screen/", null))
        assertEquals("http://u1/screen/snapshot", RemoteScreen.snapshotUrl("http://u1/screen", null))
        assertEquals("http://u1/custom.png", RemoteScreen.snapshotUrl("http://u1/screen/", "http://u1/custom.png"))
    }

    @Test fun webcamGuiEntryIsDiscoveredAsScreen() = runBlocking {
        MockWebServer().use { server ->
            // paxx12 04_remote_screen.cfg: [webcam gui] service: iframe, stream_url: /screen/
            server.enqueue(MockResponse().setBody("""
                {"result": {"webcams": [{"name": "gui", "service": "iframe", "enabled": true,
                  "stream_url": "/screen/", "snapshot_url": "/screen/snapshot", "aspect_ratio": "480:320", "uid": "g"}]}}
            """.trimIndent()))
            val cams = MoonrakerClient.webcams(Printer(name = "U1", address = "http://${server.hostName}:${server.port}"))
            assertEquals(StreamType.SCREEN, cams.single().type)
            assertEquals("/screen/snapshot", cams.single().snapshotUrl)
        }
    }

    @Test fun probeDetectsEnabledScreen() = runBlocking {
        MockWebServer().use { server ->
            val printer = Printer(name = "U1", address = "http://${server.hostName}:${server.port}", apiKey = "k")
            server.enqueue(MockResponse().setResponseCode(200).setHeader("Content-Type", "image/png").setBody("png"))
            assertEquals(StreamType.SCREEN, MoonrakerClient.probePaxx12Screen(printer)?.type)
            val req = server.takeRequest()
            assertEquals("/screen/snapshot", req.path)
            assertEquals("k", req.getHeader("X-Api-Key"))
            // nginx answers 502 when fb-http isn't running (remote screen disabled).
            server.enqueue(MockResponse().setResponseCode(502))
            assertNull(MoonrakerClient.probePaxx12Screen(printer))
        }
    }
}
