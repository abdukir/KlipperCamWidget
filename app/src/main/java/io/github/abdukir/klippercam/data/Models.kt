package io.github.abdukir.klippercam.data

import kotlinx.serialization.Serializable
import java.util.UUID

fun newId(): String = UUID.randomUUID().toString()

/** A Klipper/Moonraker host. */
@Serializable
data class Printer(
    val id: String = newId(),
    val name: String,
    /** Host, IP or URL of the web UI, e.g. `192.168.1.50` or `http://u1.local`. */
    val address: String,
    /** Optional Moonraker port when it is not proxied by the web server (usually 7125). */
    val moonrakerPort: Int? = null,
    val apiKey: String? = null,
    val username: String? = null,
    val password: String? = null,
)

/**
 * Every camera service Moonraker/Mainsail/Fluidd know about, plus RTSP which the paxx12
 * firmware can serve outside of Moonraker.
 */
@Serializable
enum class StreamType(val label: String, val moonrakerService: String?) {
    WEBRTC_CAMERA_STREAMER("WebRTC (camera-streamer / paxx12)", "webrtc-camerastreamer"),
    WEBRTC_GO2RTC("WebRTC (go2rtc)", "webrtc-go2rtc"),
    WEBRTC_MEDIAMTX("WebRTC (MediaMTX / WHEP)", "webrtc-mediamtx"),
    WEBRTC_JANUS("WebRTC (Janus)", "webrtc-janus"),
    MJPEG("MJPEG stream", "mjpegstreamer"),
    MJPEG_ADAPTIVE("Adaptive MJPEG (snapshots)", "mjpegstreamer-adaptive"),
    UV4L_MJPEG("UV4L MJPEG", "uv4l-mjpeg"),
    H264_STREAM("Raw H.264 (HTTP / WebSocket)", "jmuxer-stream"),
    HLS("HLS", "hlsstream"),
    VIDEO("Video URL (ipstream)", "ipstream"),
    RTSP("RTSP", null),
    IFRAME("Web page (iframe)", "iframe"),
    SNAPSHOT("Snapshot polling", null),
    /** paxx12 "Remote Screen": the printer's touchscreen, viewable and controllable. */
    SCREEN("Printer touchscreen (paxx12)", null);

    val isWebRtc: Boolean
        get() = this == WEBRTC_CAMERA_STREAMER || this == WEBRTC_GO2RTC ||
            this == WEBRTC_MEDIAMTX || this == WEBRTC_JANUS

    companion object {
        fun fromMoonrakerService(service: String?): StreamType = when (service?.lowercase()) {
            "webrtc-camerastreamer" -> WEBRTC_CAMERA_STREAMER
            "webrtc-go2rtc" -> WEBRTC_GO2RTC
            "webrtc-mediamtx" -> WEBRTC_MEDIAMTX
            "webrtc-janus" -> WEBRTC_JANUS
            "mjpegstreamer" -> MJPEG
            "mjpegstreamer-adaptive" -> MJPEG_ADAPTIVE
            "uv4l-mjpeg" -> UV4L_MJPEG
            "jmuxer-stream" -> H264_STREAM
            "hlsstream" -> HLS
            "ipstream", "html-video" -> VIDEO
            "iframe" -> IFRAME
            "device" -> SNAPSHOT
            else -> MJPEG
        }
    }
}

/** One camera stream of a printer. URLs may be relative to the printer address. */
@Serializable
data class CameraStream(
    val id: String = newId(),
    val printerId: String,
    val name: String,
    val type: StreamType,
    val streamUrl: String? = null,
    val snapshotUrl: String? = null,
    val rotation: Int = 0,
    val flipHorizontal: Boolean = false,
    val flipVertical: Boolean = false,
    val aspectRatio: String? = null,
    val targetFps: Int? = null,
    /** Moonraker webcam uid, used to update discovered cameras instead of duplicating them. */
    val moonrakerUid: String? = null,
    /** Fall back to snapshot polling while the live stream is unavailable. */
    val snapshotFallback: Boolean = true,
)

@Serializable
data class WidgetConfig(
    val widgetId: Int,
    val streamIds: List<String>,
    val showLabels: Boolean = true,
    val showStatus: Boolean = true,
    /** Crop frames to fill each tile instead of letterboxing. */
    val cropToFill: Boolean = false,
)

@Serializable
data class AppSettings(
    /** Frames per second pushed to home-screen widgets while live. */
    val widgetFps: Int = 2,
    /** Longest edge (px) of frames rendered into widgets. */
    val widgetMaxResolution: Int = 640,
    /** Keep widgets live while the screen is on (foreground service). */
    val liveWidgets: Boolean = true,
    /** Max frames per second in the full-screen viewer. */
    val viewerFps: Int = 24,
    /** Prefer RTP over TCP for RTSP streams (more reliable over Wi-Fi/VPN). */
    val rtspOverTcp: Boolean = true,
    /** Add a public STUN server to WebRTC connections (only needed outside your LAN). */
    val useStun: Boolean = false,
)

@Serializable
data class AppState(
    val printers: List<Printer> = emptyList(),
    val streams: List<CameraStream> = emptyList(),
    val widgets: List<WidgetConfig> = emptyList(),
    val settings: AppSettings = AppSettings(),
) {
    fun printer(id: String) = printers.firstOrNull { it.id == id }
    fun stream(id: String) = streams.firstOrNull { it.id == id }
    fun widget(id: Int) = widgets.firstOrNull { it.widgetId == id }
    /** Cameras of a printer, excluding temporary "Test stream" previews. */
    fun streamsOf(printerId: String) = streams.filter { it.printerId == printerId && !it.id.startsWith("preview-") }
}
