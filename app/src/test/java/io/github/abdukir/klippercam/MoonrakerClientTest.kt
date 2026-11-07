package io.github.abdukir.klippercam

import io.github.abdukir.klippercam.data.Printer
import io.github.abdukir.klippercam.data.StreamType
import io.github.abdukir.klippercam.net.MoonrakerClient
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class MoonrakerClientTest {
    private lateinit var server: MockWebServer

    @Before fun setUp() { server = MockWebServer(); server.start() }
    @After fun tearDown() { server.shutdown() }

    private fun printer() = Printer(name = "U1", address = "http://${server.hostName}:${server.port}", apiKey = "secret")

    @Test fun paxx12Webcams() = runBlocking {
        // Shape of GET /server/webcams/list with paxx12's 02_internal_camera.cfg and the
        // USB camera enabled in iframe (H.264 player) mode.
        server.enqueue(MockResponse().setBody("""
            {"result": {"webcams": [
              {"name": "case", "location": "printer", "service": "webrtc-camerastreamer", "enabled": true,
               "target_fps": 15, "stream_url": "/webcam/webrtc", "snapshot_url": "/webcam/snapshot.jpg",
               "flip_horizontal": false, "flip_vertical": false, "rotation": 0, "aspect_ratio": "16:9",
               "uid": "1111"},
              {"name": "usb", "service": "iframe", "enabled": true, "stream_url": "/webcam2/player",
               "snapshot_url": "/webcam2/snapshot.jpg", "rotation": 180, "uid": "2222"},
              {"name": "mjpeg", "service": "mjpegstreamer-adaptive", "enabled": true, "stream_url": "",
               "snapshot_url": "/webcam/snapshot.jpg", "uid": "3333"},
              {"name": "off", "service": "mjpegstreamer", "enabled": false, "stream_url": "/x", "uid": "4444"}
            ]}}
        """.trimIndent()))
        val cams = MoonrakerClient.webcams(printer())
        assertEquals(3, cams.size)
        assertEquals(StreamType.WEBRTC_CAMERA_STREAMER, cams[0].type)
        assertEquals("/webcam/webrtc", cams[0].streamUrl)
        assertEquals("1111", cams[0].moonrakerUid)
        assertEquals(StreamType.H264_STREAM, cams[1].type)
        assertEquals("/webcam2/stream.h264", cams[1].streamUrl)
        assertEquals(180, cams[1].rotation)
        assertEquals(StreamType.MJPEG_ADAPTIVE, cams[2].type)
        val req = server.takeRequest()
        assertEquals("/server/webcams/list", req.path)
        assertEquals("secret", req.getHeader("X-Api-Key"))
    }

    @Test fun serverInfo() = runBlocking {
        server.enqueue(MockResponse().setBody("""{"result": {"klippy_state": "ready", "moonraker_version": "v0.9.3"}}"""))
        server.enqueue(MockResponse().setResponseCode(404))
        val info = MoonrakerClient.serverInfo(printer())
        assertEquals("ready", info.klippyState)
    }
}
