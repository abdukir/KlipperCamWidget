package io.github.abdukir.klippercam.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import io.github.abdukir.klippercam.App
import io.github.abdukir.klippercam.data.CameraStream
import io.github.abdukir.klippercam.stream.FrameRequest
import io.github.abdukir.klippercam.stream.Frames
import io.github.abdukir.klippercam.stream.StreamStatus

/** Shows a camera from the shared [io.github.abdukir.klippercam.stream.StreamHub]. */
@Composable
fun LiveStreamView(
    stream: CameraStream,
    modifier: Modifier = Modifier,
    maxDim: Int = 1280,
    fps: Int = 24,
    zoomable: Boolean = false,
    showStatus: Boolean = true,
) {
    val hub = App.instance.hub
    DisposableEffect(stream.id, maxDim, fps) {
        val owner = Any()
        hub.subscribe(stream.id, owner, FrameRequest(maxDim, fps))
        onDispose { hub.unsubscribe(stream.id, owner) }
    }
    val frame by hub.frames(stream.id).collectAsState()
    // Rotation/flip is applied to the bitmap off the main thread (layout then fits it correctly).
    val image by produceState<ImageBitmap?>(null, stream.id, stream.rotation, stream.flipHorizontal, stream.flipVertical) {
        hub.frames(stream.id).collect { f ->
            value = f?.let { withContext(Dispatchers.Default) { Frames.orient(it.bitmap, stream.rotation, stream.flipHorizontal, stream.flipVertical).asImageBitmap() } }
        }
    }
    val status by hub.status(stream.id).collectAsState()

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        offset = if (scale == 1f) Offset.Zero else offset + pan
    }

    Box(modifier.background(Color.Black), contentAlignment = Alignment.Center) {
        image?.let { img ->
            Image(
                bitmap = img,
                contentDescription = stream.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .then(if (zoomable) Modifier.transformable(transform).pointerInput(Unit) {
                        detectTapGestures(onDoubleTap = { scale = if (scale > 1f) 1f else 2.5f; offset = Offset.Zero })
                    } else Modifier)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
        if (frame == null && status !is StreamStatus.Offline) {
            CircularProgressIndicator(color = Color.White)
        }
        if (showStatus) {
            StatusChip(status, Modifier.align(Alignment.TopStart).padding(8.dp))
        }
    }
}

@Composable
fun StatusChip(status: StreamStatus, modifier: Modifier = Modifier) {
    val (text, color) = when (status) {
        StreamStatus.Live -> "LIVE" to Color(0xFF4CD964)
        is StreamStatus.Fallback -> "SNAPSHOT · ${status.reason}" to Color(0xFFFFC107)
        is StreamStatus.Offline -> "OFFLINE · ${status.reason}" to Color(0xFFFF5252)
        StreamStatus.Connecting -> "Connecting…" to Color.White
        StreamStatus.Idle -> "Idle" to Color.LightGray
    }
    Text(
        text,
        color = color,
        style = MaterialTheme.typography.labelSmall,
        maxLines = 2,
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
