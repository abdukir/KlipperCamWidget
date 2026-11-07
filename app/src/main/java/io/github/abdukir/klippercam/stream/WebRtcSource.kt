package io.github.abdukir.klippercam.stream

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SoftwareVideoEncoderFactory
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.io.IOException
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Process-wide WebRTC setup (hardware H.264 decoding through a shared EGL context). */
object WebRtcEngine {
    @Volatile private var factory: PeerConnectionFactory? = null
    lateinit var eglBase: EglBase
        private set

    fun factory(context: Context): PeerConnectionFactory = factory ?: synchronized(this) {
        factory ?: run {
            val app = context.applicationContext
            PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(app).createInitializationOptions())
            eglBase = EglBase.create()
            val adm = JavaAudioDeviceModule.builder(app)
                .setUseHardwareAcousticEchoCanceler(false)
                .setUseHardwareNoiseSuppressor(false)
                .createAudioDeviceModule()
            PeerConnectionFactory.builder()
                .setVideoDecoderFactory(DefaultVideoDecoderFactory(eglBase.eglBaseContext))
                .setVideoEncoderFactory(SoftwareVideoEncoderFactory())
                .setAudioDeviceModule(adm)
                .createPeerConnectionFactory()
                .also { factory = it }
        }
    }
}

/**
 * Common WebRTC receive pipeline. Subclasses implement only the signaling exchange
 * ([signal]); this class owns the PeerConnection, keep-alive data channel, failure detection and
 * frame conversion.
 */
abstract class WebRtcSource(ctx: SourceContext) : FrameSource(ctx) {
    protected val stunServers: List<PeerConnection.IceServer>
        get() = if (ctx.settings.useStun) listOf(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer()) else emptyList()

    protected val localCandidates = Channel<IceCandidate>(Channel.UNLIMITED)
    protected val gatheringComplete = CompletableDeferred<Unit>()
    private val failure = CompletableDeferred<Unit>()
    private val converting = AtomicBoolean(false)
    private val converter = Executors.newSingleThreadExecutor { r -> Thread(r, "webrtc-convert") }

    private val videoSink = VideoSink { frame -> onVideoFrame(frame) }

    /** Performs signaling. May keep suspending (e.g. WebSocket session) until the stream ends. */
    protected abstract suspend fun signal(scope: CoroutineScope, pcFactory: (List<PeerConnection.IceServer>) -> PeerConnection)

    protected fun fail(reason: String) {
        failure.completeExceptionally(IOException(reason))
    }

    override suspend fun run() {
        val factory = WebRtcEngine.factory(ctx.context)
        var pc: PeerConnection? = null
        try {
            coroutineScope {
                val disconnectWatch = Channel<PeerConnection.PeerConnectionState>(Channel.CONFLATED)
                val create: (List<PeerConnection.IceServer>) -> PeerConnection = { servers ->
                    val config = PeerConnection.RTCConfiguration(servers).apply {
                        sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
                        continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_ONCE
                    }
                    factory.createPeerConnection(config, Observer(disconnectWatch))
                        ?.also { pc = it } ?: throw IOException("Could not create PeerConnection")
                }
                launch {
                    // "disconnected" can recover; give it a few seconds before restarting.
                    for (state in disconnectWatch) {
                        when (state) {
                            PeerConnection.PeerConnectionState.FAILED -> fail("WebRTC connection failed")
                            PeerConnection.PeerConnectionState.CLOSED -> fail("WebRTC connection closed")
                            PeerConnection.PeerConnectionState.DISCONNECTED -> launch {
                                delay(5000)
                                if (pc?.connectionState() == PeerConnection.PeerConnectionState.DISCONNECTED) fail("WebRTC disconnected")
                            }
                            else -> Unit
                        }
                    }
                }
                val signaling = launch { signal(this, create) }
                try {
                    failure.await()
                } finally {
                    signaling.cancel()
                    coroutineContext.cancelChildren()
                }
            }
        } finally {
            pc?.let { p -> runCatching { p.close() }; runCatching { p.dispose() } }
            converter.shutdown()
        }
    }

    /** Waits (bounded) until local ICE gathering finished, for non-trickle signaling. */
    protected suspend fun awaitGathering(timeoutMs: Long = 2500) {
        try { withTimeout(timeoutMs) { gatheringComplete.await() } } catch (_: TimeoutCancellationException) { }
    }

    private fun onVideoFrame(frame: VideoFrame) {
        if (!wantFrame() || !converting.compareAndSet(false, true)) return
        frame.retain()
        try {
            converter.execute {
                try {
                    val buffer = frame.buffer
                    val (sw, sh) = Frames.fit(buffer.width, buffer.height, maxDim)
                    val scaled = buffer.cropAndScale(0, 0, buffer.width, buffer.height, sw, sh)
                    val i420 = scaled.toI420()
                    scaled.release()
                    if (i420 != null) {
                        val bmp = Frames.i420ToBitmap(i420)
                        i420.release()
                        emit(Frames.rotate(bmp, frame.rotation))
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "Frame conversion failed", t)
                } finally {
                    frame.release()
                    converting.set(false)
                }
            }
        } catch (t: Throwable) {
            frame.release()
            converting.set(false)
        }
    }

    private inner class Observer(private val states: Channel<PeerConnection.PeerConnectionState>) : PeerConnection.Observer {
        override fun onSignalingChange(state: PeerConnection.SignalingState?) {}
        override fun onIceConnectionChange(state: PeerConnection.IceConnectionState?) {}
        override fun onConnectionChange(newState: PeerConnection.PeerConnectionState?) {
            newState?.let { states.trySend(it) }
        }
        override fun onIceConnectionReceivingChange(receiving: Boolean) {}
        override fun onIceGatheringChange(state: PeerConnection.IceGatheringState?) {
            if (state == PeerConnection.IceGatheringState.COMPLETE) gatheringComplete.complete(Unit)
        }
        override fun onIceCandidate(candidate: IceCandidate?) {
            candidate?.let { localCandidates.trySend(it) }
        }
        override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {}
        override fun onAddStream(stream: MediaStream?) {}
        override fun onRemoveStream(stream: MediaStream?) {}
        override fun onDataChannel(dc: DataChannel?) {
            dc ?: return
            // camera-streamer / paxx12 send "ping" on "keepalive" and expect "pong".
            dc.registerObserver(object : DataChannel.Observer {
                override fun onBufferedAmountChange(previousAmount: Long) {}
                override fun onStateChange() {}
                override fun onMessage(buffer: DataChannel.Buffer?) {
                    if (dc.label() == "keepalive") {
                        runCatching { dc.send(DataChannel.Buffer(ByteBuffer.wrap("pong".toByteArray()), false)) }
                    }
                }
            })
        }
        override fun onRenegotiationNeeded() {}
        override fun onAddTrack(receiver: RtpReceiver?, streams: Array<out MediaStream>?) {}
        override fun onTrack(transceiver: RtpTransceiver?) {
            when (val track = transceiver?.receiver?.track()) {
                is VideoTrack -> track.addSink(videoSink)
                null -> Unit
                else -> if (track.kind() == MediaStreamTrack.AUDIO_TRACK_KIND) track.setEnabled(false)
            }
        }
    }

    companion object {
        private const val TAG = "WebRtcSource"

        fun recvOnlyVideo(pc: PeerConnection) {
            pc.addTransceiver(
                MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
                RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY),
            )
        }
    }
}

// --- Suspend helpers around the callback-based SDP API -------------------------------------

private open class SdpAdapter : SdpObserver {
    override fun onCreateSuccess(sdp: SessionDescription?) {}
    override fun onSetSuccess() {}
    override fun onCreateFailure(error: String?) {}
    override fun onSetFailure(error: String?) {}
}

suspend fun PeerConnection.createOfferAwait(): SessionDescription = suspendCancellableCoroutine { cont ->
    createOffer(object : SdpAdapter() {
        override fun onCreateSuccess(sdp: SessionDescription?) { cont.resume(sdp!!) }
        override fun onCreateFailure(error: String?) { cont.resumeWithException(IOException("createOffer: $error")) }
    }, MediaConstraints())
}

suspend fun PeerConnection.createAnswerAwait(): SessionDescription = suspendCancellableCoroutine { cont ->
    createAnswer(object : SdpAdapter() {
        override fun onCreateSuccess(sdp: SessionDescription?) { cont.resume(sdp!!) }
        override fun onCreateFailure(error: String?) { cont.resumeWithException(IOException("createAnswer: $error")) }
    }, MediaConstraints())
}

suspend fun PeerConnection.setLocalAwait(sdp: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
    setLocalDescription(object : SdpAdapter() {
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onSetFailure(error: String?) { cont.resumeWithException(IOException("setLocalDescription: $error")) }
    }, sdp)
}

suspend fun PeerConnection.setRemoteAwait(sdp: SessionDescription): Unit = suspendCancellableCoroutine { cont ->
    setRemoteDescription(object : SdpAdapter() {
        override fun onSetSuccess() { cont.resume(Unit) }
        override fun onSetFailure(error: String?) { cont.resumeWithException(IOException("setRemoteDescription: $error")) }
    }, sdp)
}
