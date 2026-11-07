package io.github.abdukir.klippercam.net

import io.github.abdukir.klippercam.data.CameraStream
import io.github.abdukir.klippercam.data.Printer
import io.github.abdukir.klippercam.data.StreamType
import io.github.abdukir.klippercam.data.Urls
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

class MoonrakerException(message: String) : IOException(message)

data class PrinterInfo(val klippyState: String?, val moonrakerVersion: String?, val hostname: String?)

data class DiscoveryResult(
    val info: PrinterInfo,
    val cameras: List<CameraStream>,
    /** Extra streams found by probing (e.g. paxx12 RTSP servers on :8554 / :8555). */
    val extras: List<CameraStream>,
)

/** Minimal Moonraker HTTP API client (https://moonraker.readthedocs.io/en/latest/web_api/). */
object MoonrakerClient {
    private val json = Json { ignoreUnknownKeys = true }
    private val tokens = ConcurrentHashMap<String, String>()

    suspend fun discover(printer: Printer): DiscoveryResult = coroutineScope {
        val info = async { serverInfo(printer) }
        val cams = async { webcams(printer) }
        val rtsp = async { probePaxx12Rtsp(printer) }
        DiscoveryResult(info.await(), cams.await(), rtsp.await())
    }

    suspend fun serverInfo(printer: Printer): PrinterInfo {
        val r = get(printer, "server/info").jsonObject
        val host = runCatching { get(printer, "printer/info").jsonObject.str("hostname") }.getOrNull()
        return PrinterInfo(
            klippyState = r["klippy_state"]?.jsonPrimitive?.contentOrNull,
            moonrakerVersion = r["moonraker_version"]?.jsonPrimitive?.contentOrNull,
            hostname = host,
        )
    }

    /** `GET /server/webcams/list` mapped to [CameraStream]s. */
    suspend fun webcams(printer: Printer): List<CameraStream> {
        val r = get(printer, "server/webcams/list").jsonObject
        val list = r["webcams"]?.jsonArray ?: return emptyList()
        return list.mapNotNull { el ->
            val o = el.jsonObject
            if (o.bool("enabled") == false) return@mapNotNull null
            val service = o.str("service")
            var type = StreamType.fromMoonrakerService(service)
            var streamUrl = o.str("stream_url")
            val snapshotUrl = o.str("snapshot_url")
            // paxx12's "iframe" mode points at /webcam/player, which plays /webcam/stream.h264.
            if (type == StreamType.IFRAME && streamUrl != null && streamUrl.trimEnd('/').endsWith("/player")) {
                type = StreamType.H264_STREAM
                streamUrl = streamUrl.trimEnd('/').removeSuffix("player") + "stream.h264"
            }
            // Without a stream URL the only thing we can show is the snapshot.
            if (streamUrl.isNullOrBlank() && snapshotUrl != null) type = StreamType.MJPEG_ADAPTIVE
            CameraStream(
                printerId = printer.id,
                name = o.str("name") ?: "Camera",
                type = type,
                streamUrl = streamUrl,
                snapshotUrl = snapshotUrl,
                rotation = o.int("rotation") ?: 0,
                flipHorizontal = o.bool("flip_horizontal") ?: false,
                flipVertical = o.bool("flip_vertical") ?: false,
                aspectRatio = o.str("aspect_ratio"),
                targetFps = o.int("target_fps"),
                moonrakerUid = o.str("uid") ?: o.str("name"),
            )
        }
    }

    /**
     * The paxx12 extended firmware can expose RTSP on 8554 (internal camera) and 8555 (USB camera).
     * Those are not listed in Moonraker, so check whether the ports are open.
     */
    suspend fun probePaxx12Rtsp(printer: Printer): List<CameraStream> = withContext(Dispatchers.IO) {
        val host = Urls.host(printer) ?: return@withContext emptyList()
        val urlHost = Urls.urlHost(printer)!!
        listOf(8554 to "Internal camera (RTSP)", 8555 to "USB camera (RTSP)").mapNotNull { (port, name) ->
            val open = runCatching {
                Socket().use { it.connect(InetSocketAddress(host, port), 800); true }
            }.getOrDefault(false)
            if (!open) null else CameraStream(
                printerId = printer.id,
                name = name,
                type = StreamType.RTSP,
                streamUrl = "rtsp://$urlHost:$port/stream",
                snapshotUrl = if (port == 8554) "/webcam/snapshot.jpg" else "/webcam2/snapshot.jpg",
                moonrakerUid = "rtsp-$port",
            )
        }
    }

    private suspend fun get(printer: Printer, path: String): JsonElement = withContext(Dispatchers.IO) {
        val base = Urls.moonrakerBase(printer) ?: throw MoonrakerException("Invalid address")
        val url = base.resolve(path) ?: throw MoonrakerException("Invalid address")
        fun build(): Request = Request.Builder().url(url).apply { authHeaders(printer).forEach { (k, v) -> header(k, v) } }.build()
        var resp = Http.client.newCall(build()).execute()
        if (resp.code == 401 && !printer.username.isNullOrBlank()) {
            resp.close()
            login(printer)
            resp = Http.client.newCall(build()).execute()
        }
        resp.use {
            if (it.code == 401 || it.code == 403) throw MoonrakerException("Unauthorized – set an API key or login")
            if (!it.isSuccessful) throw MoonrakerException("HTTP ${it.code} for /$path")
            val body = it.body?.string() ?: throw MoonrakerException("Empty response")
            json.parseToJsonElement(body).jsonObject["result"] ?: throw MoonrakerException("Unexpected response")
        }
    }

    private fun authHeaders(printer: Printer): Map<String, String> = buildMap {
        printer.apiKey?.takeIf { it.isNotBlank() }?.let { put("X-Api-Key", it.trim()) }
        tokens[printer.id]?.let { put("Authorization", "Bearer $it") }
    }

    private fun login(printer: Printer) {
        val base = Urls.moonrakerBase(printer) ?: return
        val body = buildJsonObject {
            put("username", printer.username.orEmpty())
            put("password", printer.password.orEmpty())
            put("source", "moonraker")
        }.toString().toRequestBody("application/json".toMediaType())
        Http.client.newCall(Request.Builder().url(base.resolve("access/login")!!).post(body).build()).execute().use {
            if (!it.isSuccessful) throw MoonrakerException("Login failed (HTTP ${it.code})")
            val token = json.parseToJsonElement(it.body!!.string()).jsonObject["result"]?.jsonObject?.str("token")
                ?: throw MoonrakerException("Login failed")
            tokens[printer.id] = token
        }
    }

    private fun JsonObject.str(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.contentOrNull }.getOrNull() }?.takeIf { it.isNotBlank() }
    private fun JsonObject.int(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.intOrNull }.getOrNull() }
    private fun JsonObject.bool(k: String) = this[k]?.let { runCatching { it.jsonPrimitive.booleanOrNull }.getOrNull() }
}
