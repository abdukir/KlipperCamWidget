package io.github.abdukir.klippercam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.abdukir.klippercam.App
import io.github.abdukir.klippercam.data.CameraStream
import io.github.abdukir.klippercam.data.StreamType

private fun urlHint(type: StreamType) = when (type) {
    StreamType.WEBRTC_CAMERA_STREAMER -> "/webcam/webrtc"
    StreamType.WEBRTC_GO2RTC -> "http://host:1984/stream.html?src=cam"
    StreamType.WEBRTC_MEDIAMTX -> "http://host:8889/cam/"
    StreamType.WEBRTC_JANUS -> "http://host/janus/1"
    StreamType.MJPEG, StreamType.UV4L_MJPEG -> "/webcam/?action=stream  or  /webcam/stream.mjpg"
    StreamType.MJPEG_ADAPTIVE, StreamType.SNAPSHOT -> "(not needed — uses snapshot URL)"
    StreamType.H264_STREAM -> "/webcam/stream.h264  or  ws://host:8080/stream"
    StreamType.HLS -> "http://host/hls/stream.m3u8"
    StreamType.VIDEO -> "http://host/video.mp4"
    StreamType.RTSP -> "rtsp://host:8554/stream"
    StreamType.IFRAME -> "(shown via snapshot URL)"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CameraScreen(streamId: String?, printerId: String, onDone: () -> Unit) {
    val repo = App.instance.repo
    val original = streamId?.let { repo.current.stream(it) }
    val id = remember { original?.id ?: io.github.abdukir.klippercam.data.newId() }
    var name by remember { mutableStateOf(original?.name ?: "Camera") }
    var type by remember { mutableStateOf(original?.type ?: StreamType.WEBRTC_CAMERA_STREAMER) }
    var streamUrl by remember { mutableStateOf(original?.streamUrl ?: "") }
    var snapshotUrl by remember { mutableStateOf(original?.snapshotUrl ?: "") }
    var rotation by remember { mutableStateOf(original?.rotation ?: 0) }
    var flipH by remember { mutableStateOf(original?.flipHorizontal ?: false) }
    var flipV by remember { mutableStateOf(original?.flipVertical ?: false) }
    var fallback by remember { mutableStateOf(original?.snapshotFallback ?: true) }
    var typeMenu by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<CameraStream?>(null) }

    fun build() = (original ?: CameraStream(id = id, printerId = printerId, name = name, type = type)).copy(
        name = name.ifBlank { "Camera" }, type = type,
        streamUrl = streamUrl.ifBlank { null }, snapshotUrl = snapshotUrl.ifBlank { null },
        rotation = rotation, flipHorizontal = flipH, flipVertical = flipV, snapshotFallback = fallback,
    )

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (original == null) "Add camera" else "Edit camera") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (original != null) IconButton(onClick = { repo.deleteStream(id); onDone() }) { Icon(Icons.Default.Delete, "Delete") }
                TextButton(onClick = { repo.upsertStream(build()); onDone() }) { Text("Save") }
            },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
            ExposedDropdownMenuBox(expanded = typeMenu, onExpandedChange = { typeMenu = it }) {
                OutlinedTextField(
                    type.label, {}, readOnly = true, label = { Text("Stream type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeMenu) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = typeMenu, onDismissRequest = { typeMenu = false }) {
                    StreamType.entries.forEach { t ->
                        DropdownMenuItem(text = { Text(t.label) }, onClick = { type = t; typeMenu = false })
                    }
                }
            }
            OutlinedTextField(streamUrl, { streamUrl = it }, Modifier.fillMaxWidth(), label = { Text("Stream URL") },
                placeholder = { Text(urlHint(type)) }, singleLine = true,
                supportingText = { Text("Relative paths are resolved against the printer address.") })
            OutlinedTextField(snapshotUrl, { snapshotUrl = it }, Modifier.fillMaxWidth(), label = { Text("Snapshot URL") },
                placeholder = { Text("/webcam/snapshot.jpg  or  /webcam/?action=snapshot") }, singleLine = true)
            Text("Rotation", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0, 90, 180, 270).forEach { r -> FilterChip(rotation == r, { rotation = r }, label = { Text("$r°") }) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(flipH, { flipH = !flipH }, label = { Text("Flip horizontal") })
                FilterChip(flipV, { flipV = !flipV }, label = { Text("Flip vertical") })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Snapshot fallback")
                    Text("Show snapshots while the live stream can't connect", style = MaterialTheme.typography.bodySmall)
                }
                Switch(fallback, { fallback = it })
            }
            OutlinedButton(onClick = {
                // Preview uses a temporary copy so it doesn't disturb running widgets.
                val s = build().copy(id = "preview-$id")
                repo.upsertStream(s)
                preview = s
            }) { Text("Test stream") }
            preview?.let { p ->
                LiveStreamView(p, Modifier.fillMaxWidth().aspectRatio(16f / 9f), maxDim = 960, fps = 15)
                androidx.compose.runtime.DisposableEffect(p.id) { onDispose { repo.deleteStream(p.id) } }
            }
        }
    }
}
