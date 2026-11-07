package io.github.abdukir.klippercam.stream

import io.github.abdukir.klippercam.net.Http
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.webrtc.IceCandidate
import org.webrtc.PeerConnection
import org.webrtc.SessionDescription
import java.io.IOException
import java.util.UUID

private val json = Json { ignoreUnknownKeys = true }
private val JSON_TYPE = "application/json".toMediaType()

private fun iceServersFrom(arr: JsonArray?): List<PeerConnection.IceServer> = arr?.mapNotNull { el ->
    val o = el as? JsonObject ?: return@mapNotNull null
    val urls = when (val u = o["urls"]) {
        is JsonArray -> u.mapNotNull { it.jsonPrimitive.contentOrNull }
        is JsonPrimitive -> listOfNotNull(u.contentOrNull)
        else -> emptyList()
    }
    if (urls.isEmpty()) return@mapNotNull null
    PeerConnection.IceServer.builder(urls).apply {
        o["username"]?.jsonPrimitive?.contentOrNull?.let { setUsername(it) }
        o["credential"]?.jsonPrimitive?.contentOrNull?.let { setPassword(it) }
    }.createIceServer()
}.orEmpty()

private fun candidateJson(c: IceCandidate) = buildJsonObject {
    put("candidate", c.sdp)
    put("sdpMid", c.sdpMid)
    put("sdpMLineIndex", c.sdpMLineIndex)
}

/**
 * camera-streamer protocol, also implemented by the paxx12 Snapmaker U1 firmware
 * (`POST /webcam/webrtc`): the server makes the offer, we answer, candidates are trickled.
 */
class CameraStreamerSource(ctx: SourceContext, private val url: String) : WebRtcSource(ctx) {
    private suspend fun post(body: JsonObject): JsonObject {
        val req = Request.Builder().url(url).post(body.toString().toRequestBody(JSON_TYPE)).build()
        return Http.client.newCall(req).executeBlocking { resp ->
            if (!resp.isSuccessful) throw IOException("WebRTC signaling HTTP ${resp.code}")
            val text = resp.body?.string().orEmpty()
            if (text.isBlank()) JsonObject(emptyMap()) else json.parseToJsonElement(text).jsonObject
        }
    }

    override suspend fun signal(scope: CoroutineScope, pcFactory: (List<PeerConnection.IceServer>) -> PeerConnection) {
        val offer = post(buildJsonObject {
            put("type", "request")
            if (ctx.settings.useStun) put("iceServers", buildJsonArray {
                add(buildJsonObject { put("urls", buildJsonArray { add(JsonPrimitive("stun:stun.l.google.com:19302")) }) })
            })
            put("keepAlive", true)
            // paxx12 ends sessions after 1h by default; ask for a day (the hub reconnects anyway).
            put("timeout_s", 86_400)
        })
        val sdp = offer["sdp"]?.jsonPrimitive?.contentOrNull ?: throw IOException(offer["error"]?.jsonPrimitive?.contentOrNull ?: "No offer from camera")
        val id = offer["id"]?.jsonPrimitive?.contentOrNull
        val servers = iceServersFrom(offer["iceServers"] as? JsonArray).ifEmpty { stunServers }
        val pc = pcFactory(servers)
        pc.setRemoteAwait(SessionDescription(SessionDescription.Type.OFFER, sdp))
        val answer = pc.createAnswerAwait()
        pc.setLocalAwait(answer)
        post(buildJsonObject {
            put("type", "answer")
            id?.let { put("id", it) }
            put("sdp", answer.description)
        })
        for (c in localCandidates) {
            runCatching {
                post(buildJsonObject {
                    put("type", "remote_candidate")
                    id?.let { put("id", it) }
                    put("candidates", buildJsonArray { add(candidateJson(c)) })
                })
            }
        }
    }
}

/** WHEP (MediaMTX `webrtc-mediamtx`, and any other WHEP server). */
class WhepSource(ctx: SourceContext, streamUrl: String) : WebRtcSource(ctx) {
    private val endpoint = if (streamUrl.trimEnd('/').endsWith("whep")) streamUrl else streamUrl.trimEnd('/') + "/whep"
    private var resource: String? = null

    override suspend fun signal(scope: CoroutineScope, pcFactory: (List<PeerConnection.IceServer>) -> PeerConnection) {
        val pc = pcFactory(stunServers)
        recvOnlyVideo(pc)
        val offer = pc.createOfferAwait()
        pc.setLocalAwait(offer)
        awaitGathering()
        val sdp = pc.localDescription?.description ?: offer.description
        val req = Request.Builder().url(endpoint)
            .post(sdp.toRequestBody("application/sdp".toMediaType()))
            .build()
        val answer = Http.client.newCall(req).executeBlocking { resp ->
            if (resp.code != 201 && !resp.isSuccessful) throw IOException("WHEP HTTP ${resp.code}")
            resp.header("Location")?.let { loc -> resource = endpoint.toHttpUrlOrNull()?.resolve(loc)?.toString() }
            resp.body?.string() ?: throw IOException("Empty WHEP answer")
        }
        pc.setRemoteAwait(SessionDescription(SessionDescription.Type.ANSWER, answer))
        try {
            kotlinx.coroutines.awaitCancellation()
        } finally {
            resource?.let { r ->
                runCatching { Http.client.newCall(Request.Builder().url(r).delete().build()).enqueue(NoopCallback) }
            }
        }
    }
}

private object NoopCallback : okhttp3.Callback {
    override fun onFailure(call: okhttp3.Call, e: IOException) {}
    override fun onResponse(call: okhttp3.Call, response: Response) { response.close() }
}

/** Opens a WebSocket whose messages are delivered through a channel. */
private class JsonSocket(url: String, protocol: String? = null) {
    val messages = Channel<JsonObject>(Channel.UNLIMITED)
    val socket: WebSocket = Http.streaming.newWebSocket(
        Request.Builder().url(url).apply { protocol?.let { header("Sec-WebSocket-Protocol", it) } }.build(),
        object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                runCatching { messages.trySend(json.parseToJsonElement(text).jsonObject) }
            }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { messages.close(IOException("WebSocket: ${t.message}", t)) }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { messages.close(IOException("WebSocket closed ($code)")) }
        },
    )

    fun send(o: JsonObject) { if (!socket.send(o.toString())) throw IOException("WebSocket send failed") }
    fun close() { socket.close(1000, null); messages.close() }
}

private fun toWs(url: String) = when {
    url.startsWith("https://", true) -> "wss://" + url.substring(8)
    url.startsWith("http://", true) -> "ws://" + url.substring(7)
    else -> url
}

/** go2rtc WebSocket signaling (`/api/ws?src=...`), matching Mainsail's implementation. */
class Go2rtcSource(ctx: SourceContext, streamUrl: String) : WebRtcSource(ctx) {
    private val wsUrl: String = run {
        val u = streamUrl.replace("ws://", "http://", true).replace("wss://", "https://", true).toHttpUrlOrNull()
            ?: throw IOException("Invalid go2rtc URL")
        val path = u.encodedPath
        val newPath = when {
            path.endsWith("/api/webrtc") -> path.removeSuffix("webrtc") + "ws"
            path.endsWith("/api/ws") -> path
            else -> path.substringBeforeLast('/') + "/api/ws"
        }
        val b = u.newBuilder().encodedPath(newPath)
        if (u.queryParameter("media") == null) b.setQueryParameter("media", "video")
        toWs(b.build().toString())
    }

    override suspend fun signal(scope: CoroutineScope, pcFactory: (List<PeerConnection.IceServer>) -> PeerConnection) {
        val ws = JsonSocket(wsUrl)
        try {
            val pc = pcFactory(stunServers)
            recvOnlyVideo(pc)
            val offer = pc.createOfferAwait()
            pc.setLocalAwait(offer)
            ws.send(buildJsonObject { put("type", "webrtc/offer"); put("value", offer.description) })
            scope.launch {
                for (c in localCandidates) runCatching { ws.send(buildJsonObject { put("type", "webrtc/candidate"); put("value", c.sdp) }) }
            }
            for (msg in ws.messages) {
                val value = msg["value"]?.jsonPrimitive?.contentOrNull
                when (msg["type"]?.jsonPrimitive?.contentOrNull) {
                    "webrtc/answer" -> pc.setRemoteAwait(SessionDescription(SessionDescription.Type.ANSWER, value ?: throw IOException("Empty answer")))
                    "webrtc/candidate" -> if (!value.isNullOrBlank()) pc.addIceCandidate(IceCandidate("0", 0, value))
                    "error" -> throw IOException("go2rtc: $value")
                }
            }
            throw IOException("go2rtc WebSocket closed")
        } finally {
            ws.close()
        }
    }
}

/**
 * Janus streaming plugin over WebSocket (`webrtc-janus`). Like Mainsail, the stream id is the last
 * path segment of the stream URL and the Janus server listens on port 8188 by default.
 */
class JanusSource(ctx: SourceContext, private val streamUrl: String, private val rawStreamUrl: String) : WebRtcSource(ctx) {
    private fun tx() = UUID.randomUUID().toString().take(12)

    private val streamId: Long = rawStreamUrl.trimEnd('/').substringAfterLast('/').substringBefore('?').toLongOrNull() ?: 1L

    private val serverUrl: String = run {
        val absolute = rawStreamUrl.startsWith("ws", true) || rawStreamUrl.startsWith("http", true)
        val u = streamUrl.replace("ws://", "http://", true).replace("wss://", "https://", true).toHttpUrlOrNull()
            ?: throw IOException("Invalid Janus URL")
        val b = u.newBuilder().encodedPath(u.encodedPath.trimEnd('/').substringBeforeLast('/').ifEmpty { "/" }).query(null)
        if (!absolute) b.port(8188)
        toWs(b.build().toString())
    }

    override suspend fun signal(scope: CoroutineScope, pcFactory: (List<PeerConnection.IceServer>) -> PeerConnection) {
        val ws = JsonSocket(serverUrl, "janus-protocol")
        try {
            suspend fun request(body: JsonObject): JsonObject = withTimeout(10_000) {
                val t = body["transaction"]!!.jsonPrimitive.content
                ws.send(body)
                while (true) {
                    val m = ws.messages.receive()
                    if (m["transaction"]?.jsonPrimitive?.contentOrNull != t) continue
                    when (m["janus"]?.jsonPrimitive?.contentOrNull) {
                        "ack" -> continue
                        "error" -> throw IOException("Janus: " + (m["error"]?.jsonObject?.get("reason")?.jsonPrimitive?.contentOrNull ?: "error"))
                        else -> return@withTimeout m
                    }
                }
                @Suppress("UNREACHABLE_CODE") error("unreachable")
            }

            val session = request(buildJsonObject { put("janus", "create"); put("transaction", tx()) })["data"]!!.jsonObject["id"]!!.jsonPrimitive.longOrNull!!
            val handle = request(buildJsonObject {
                put("janus", "attach"); put("session_id", session); put("plugin", "janus.plugin.streaming"); put("transaction", tx())
            })["data"]!!.jsonObject["id"]!!.jsonPrimitive.longOrNull!!

            val watchTx = tx()
            ws.send(buildJsonObject {
                put("janus", "message"); put("session_id", session); put("handle_id", handle); put("transaction", watchTx)
                putJsonObject("body") { put("request", "watch"); put("id", streamId) }
            })
            scope.launch {
                while (true) {
                    delay(25_000)
                    runCatching { ws.send(buildJsonObject { put("janus", "keepalive"); put("session_id", session); put("transaction", tx()) }) }
                }
            }
            var pc: PeerConnection? = null
            for (m in ws.messages) {
                when (m["janus"]?.jsonPrimitive?.contentOrNull) {
                    "event" -> {
                        val err = m["plugindata"]?.jsonObject?.get("data")?.jsonObject?.get("error")?.jsonPrimitive?.contentOrNull
                        if (err != null) throw IOException("Janus: $err")
                        val jsep = m["jsep"]?.jsonObject ?: continue
                        if (jsep["type"]?.jsonPrimitive?.contentOrNull != "offer") continue
                        val p = pc ?: pcFactory(stunServers).also { pc = it }
                        p.setRemoteAwait(SessionDescription(SessionDescription.Type.OFFER, jsep["sdp"]!!.jsonPrimitive.content))
                        val answer = p.createAnswerAwait()
                        p.setLocalAwait(answer)
                        ws.send(buildJsonObject {
                            put("janus", "message"); put("session_id", session); put("handle_id", handle); put("transaction", tx())
                            putJsonObject("body") { put("request", "start") }
                            putJsonObject("jsep") { put("type", "answer"); put("sdp", answer.description) }
                        })
                        scope.launch {
                            for (c in localCandidates) runCatching {
                                ws.send(buildJsonObject {
                                    put("janus", "trickle"); put("session_id", session); put("handle_id", handle); put("transaction", tx())
                                    put("candidate", candidateJson(c))
                                })
                            }
                        }
                        scope.launch {
                            gatheringComplete.await()
                            runCatching {
                                ws.send(buildJsonObject {
                                    put("janus", "trickle"); put("session_id", session); put("handle_id", handle); put("transaction", tx())
                                    putJsonObject("candidate") { put("completed", true) }
                                })
                            }
                        }
                    }
                    "hangup" -> throw IOException("Janus hung up")
                    "error" -> throw IOException("Janus error")
                }
            }
            throw IOException("Janus WebSocket closed")
        } finally {
            ws.close()
        }
    }
}
