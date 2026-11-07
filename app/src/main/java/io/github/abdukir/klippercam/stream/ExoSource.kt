package io.github.abdukir.klippercam.stream

import android.os.Handler
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.rtsp.RtspMediaSource
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import io.github.abdukir.klippercam.data.StreamType
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.android.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.IOException

/** RTSP (paxx12 `rtsp://host:8554/stream`), HLS and generic video URLs via Media3/ExoPlayer. */
@OptIn(UnstableApi::class)
class ExoSource(ctx: SourceContext, private val url: String) : FrameSource(ctx) {

    override suspend fun run() {
        val grabber = GlFrameGrabber(::wantFrame, { maxDim }, ::emit)
        val main = Handler(grabber.handler.looper)
        val dispatcher = main.asCoroutineDispatcher()
        val failed = CompletableDeferred<Unit>()
        var player: ExoPlayer? = null
        try {
            withContext(Dispatchers.IO) { grabber.start() }
            withContext(dispatcher) {
                val loadControl = DefaultLoadControl.Builder()
                    .setBufferDurationsMs(300, 2000, 100, 100)
                    .setPrioritizeTimeOverSizeThresholds(true)
                    .build()
                val p = ExoPlayer.Builder(ctx.context)
                    .setLooper(main.looper)
                    .setLoadControl(loadControl)
                    .build()
                player = p
                p.volume = 0f
                p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true)
                    .build()
                p.setVideoSurface(grabber.surface)
                p.addListener(object : Player.Listener {
                    override fun onVideoSizeChanged(videoSize: VideoSize) {
                        grabber.videoWidth = videoSize.width
                        grabber.videoHeight = videoSize.height
                        grabber.pixelAspect = videoSize.pixelWidthHeightRatio.takeIf { it > 0f } ?: 1f
                    }
                    override fun onPlayerError(error: PlaybackException) {
                        failed.completeExceptionally(IOException(error.errorCodeName + ": " + (error.cause?.message ?: error.message)))
                    }
                    override fun onPlaybackStateChanged(state: Int) {
                        if (state == Player.STATE_ENDED) failed.completeExceptionally(IOException("Stream ended"))
                    }
                })
                p.setMediaSource(mediaSource())
                p.playWhenReady = true
                p.prepare()
            }
            failed.await()
        } finally {
            withContext(NonCancellable + dispatcher) { player?.release() }
            grabber.release()
        }
    }

    private fun mediaSource(): MediaSource {
        val item = MediaItem.Builder()
            .setUri(url)
            .setLiveConfiguration(MediaItem.LiveConfiguration.Builder().setTargetOffsetMs(500).build())
            .build()
        val http = DefaultHttpDataSource.Factory().setConnectTimeoutMs(5000).setReadTimeoutMs(10000)
        return when {
            ctx.stream.type == StreamType.RTSP || url.lowercase().startsWith("rtsp") ->
                RtspMediaSource.Factory().setForceUseRtpTcp(ctx.settings.rtspOverTcp).setTimeoutMs(8000).createMediaSource(item)
            ctx.stream.type == StreamType.HLS -> HlsMediaSource.Factory(http).createMediaSource(item)
            else -> DefaultMediaSourceFactory(http).createMediaSource(item)
        }
    }
}
