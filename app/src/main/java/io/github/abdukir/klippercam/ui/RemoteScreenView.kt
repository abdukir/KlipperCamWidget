package io.github.abdukir.klippercam.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.abdukir.klippercam.App
import io.github.abdukir.klippercam.data.CameraStream
import io.github.abdukir.klippercam.data.Urls
import io.github.abdukir.klippercam.stream.FrameRequest
import io.github.abdukir.klippercam.stream.RemoteScreen
import io.github.abdukir.klippercam.stream.ScreenTouchSender
import io.github.abdukir.klippercam.stream.StreamStatus

/**
 * The printer's touchscreen (paxx12 remote screen), mirrored and controllable: taps, long presses
 * and drags on the image are forwarded to the printer as touch events.
 */
@Composable
fun RemoteScreenView(stream: CameraStream, modifier: Modifier = Modifier, fps: Int = 10) {
    val app = App.instance
    val hub = app.hub
    val printer = app.repo.current.printer(stream.printerId) ?: return
    DisposableEffect(stream.id) {
        val owner = Any()
        // Full resolution: touch coordinates are framebuffer pixels.
        hub.subscribe(stream.id, owner, FrameRequest(4096, fps.coerceIn(2, 15)))
        onDispose { hub.unsubscribe(stream.id, owner) }
    }
    val frame by hub.frames(stream.id).collectAsState()
    val status by hub.status(stream.id).collectAsState()

    val touchUrl = remember(stream) {
        RemoteScreen.snapshotUrl(Urls.resolve(printer, stream.streamUrl), Urls.resolve(printer, stream.snapshotUrl))
            ?.let(RemoteScreen::touchUrl)
    }
    val sender = remember(touchUrl) { touchUrl?.let { ScreenTouchSender(printer, it) { hub.poke(stream.id) } } }
    DisposableEffect(sender) { onDispose { sender?.close() } }

    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val currentFrame by rememberUpdatedState(frame)
    var ring by remember { mutableStateOf<Offset?>(null) }
    val ringAnim = remember { Animatable(0f) }
    LaunchedEffect(ring) {
        if (ring != null) { ringAnim.snapTo(0f); ringAnim.animateTo(1f, tween(400)); ring = null }
    }

    /** Maps a point in the view to framebuffer pixels, or null when outside the image. */
    fun toScreen(p: Offset): Pair<Int, Int>? {
        val bmp = currentFrame?.bitmap ?: return null
        if (boxSize.width == 0 || boxSize.height == 0) return null
        val scale = minOf(boxSize.width.toFloat() / bmp.width, boxSize.height.toFloat() / bmp.height)
        val ox = (boxSize.width - bmp.width * scale) / 2
        val oy = (boxSize.height - bmp.height * scale) / 2
        val x = ((p.x - ox) / scale).toInt()
        val y = ((p.y - oy) / scale).toInt()
        if (x < 0 || y < 0 || x >= bmp.width || y >= bmp.height) return null
        return x to y
    }

    Box(
        modifier
            .background(Color.Black)
            .onSizeChanged { boxSize = it }
            .pointerInput(sender) {
                if (sender == null) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    val start = toScreen(down.position) ?: return@awaitEachGesture
                    down.consume()
                    ring = down.position
                    sender.down(start.first, start.second)
                    var last = start
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        if (change.position != change.previousPosition) {
                            change.consume()
                            toScreen(change.position)?.let {
                                // Clamp drags that leave the image to its edge instead of dropping them.
                                last = it
                                sender.move(it.first, it.second)
                            }
                        }
                    }
                    sender.up(last.first, last.second)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        frame?.let { f ->
            val img = remember(f) { f.bitmap.asImageBitmap() }
            Image(img, stream.name, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        }
        ring?.let { center ->
            Canvas(Modifier.fillMaxSize()) {
                drawCircle(
                    Color.White.copy(alpha = 1f - ringAnim.value),
                    radius = 12.dp.toPx() + 24.dp.toPx() * ringAnim.value,
                    center = center,
                    style = Stroke(width = 3.dp.toPx()),
                )
            }
        }
        if (frame == null && status !is StreamStatus.Offline) CircularProgressIndicator(color = Color.White)
        if (status is StreamStatus.Offline) StatusChip(status, Modifier.align(Alignment.Center).padding(16.dp))
    }
}
