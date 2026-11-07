package io.github.abdukir.klippercam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import io.github.abdukir.klippercam.App
import io.github.abdukir.klippercam.data.CameraStream
import io.github.abdukir.klippercam.data.Printer
import io.github.abdukir.klippercam.data.StreamType
import io.github.abdukir.klippercam.data.Urls
import io.github.abdukir.klippercam.net.MoonrakerClient
import kotlinx.coroutines.launch

private class Candidate(val stream: CameraStream, selected: Boolean, val existing: Boolean) {
    var selected by mutableStateOf(selected)
}

/** Default streams of the paxx12 Snapmaker U1 extended firmware. */
private fun paxx12Preset(printerId: String, host: String) = listOf(
    CameraStream(printerId = printerId, name = "Internal camera", type = StreamType.WEBRTC_CAMERA_STREAMER,
        streamUrl = "/webcam/webrtc", snapshotUrl = "/webcam/snapshot.jpg", aspectRatio = "16:9", moonrakerUid = "paxx12-case"),
    CameraStream(printerId = printerId, name = "USB camera", type = StreamType.WEBRTC_CAMERA_STREAMER,
        streamUrl = "/webcam2/webrtc", snapshotUrl = "/webcam2/snapshot.jpg", aspectRatio = "16:9", moonrakerUid = "paxx12-usb"),
    CameraStream(printerId = printerId, name = "Internal camera (MJPEG)", type = StreamType.MJPEG,
        streamUrl = "/webcam/stream.mjpg", snapshotUrl = "/webcam/snapshot.jpg", moonrakerUid = "paxx12-case-mjpeg"),
    CameraStream(printerId = printerId, name = "Internal camera (H.264)", type = StreamType.H264_STREAM,
        streamUrl = "/webcam/stream.h264", snapshotUrl = "/webcam/snapshot.jpg", moonrakerUid = "paxx12-case-h264"),
    CameraStream(printerId = printerId, name = "Internal camera (RTSP)", type = StreamType.RTSP,
        streamUrl = "rtsp://$host:8554/stream", snapshotUrl = "/webcam/snapshot.jpg", moonrakerUid = "rtsp-8554"),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrinterScreen(printerId: String?, onDone: () -> Unit) {
    val repo = App.instance.repo
    val original = printerId?.let { repo.current.printer(it) }
    val id = remember { original?.id ?: io.github.abdukir.klippercam.data.newId() }
    var name by remember { mutableStateOf(original?.name ?: "") }
    var address by remember { mutableStateOf(original?.address ?: "") }
    var port by remember { mutableStateOf(original?.moonrakerPort?.toString() ?: "") }
    var apiKey by remember { mutableStateOf(original?.apiKey ?: "") }
    var user by remember { mutableStateOf(original?.username ?: "") }
    var pass by remember { mutableStateOf(original?.password ?: "") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val candidates = remember { mutableStateListOf<Candidate>() }
    val scope = rememberCoroutineScope()

    fun build() = Printer(
        id = id,
        name = name.ifBlank { address.ifBlank { "Printer" } },
        address = address.trim(),
        moonrakerPort = port.toIntOrNull(),
        apiKey = apiKey.ifBlank { null },
        username = user.ifBlank { null },
        password = pass.ifBlank { null },
    )

    fun addCandidates(found: List<CameraStream>, select: Boolean) {
        val existing = repo.current.streamsOf(id)
        for (s in found) {
            if (candidates.any { it.stream.moonrakerUid == s.moonrakerUid && s.moonrakerUid != null }) continue
            val match = existing.firstOrNull { it.moonrakerUid != null && it.moonrakerUid == s.moonrakerUid }
            candidates += Candidate(s, select || match != null, match != null)
        }
    }

    fun discover() {
        val p = build()
        if (Urls.printerBase(p.address) == null) { message = "Enter a valid IP address or host name"; return }
        busy = true; message = null; candidates.clear()
        scope.launch {
            try {
                val r = MoonrakerClient.discover(p)
                if (name.isBlank()) name = r.info.hostname ?: p.address
                addCandidates(r.cameras, true)
                addCandidates(r.extras, false)
                message = "Connected (Klipper: ${r.info.klippyState ?: "?"}). Found ${r.cameras.size} camera(s)" +
                    if (r.extras.isNotEmpty()) " plus ${r.extras.size} RTSP stream(s)." else "."
            } catch (e: Exception) {
                message = "Could not reach Moonraker: ${e.message}. You can still add cameras manually or use the Snapmaker U1 preset."
            } finally {
                busy = false
            }
        }
    }

    fun save() {
        val p = build()
        if (Urls.printerBase(p.address) == null) { message = "Enter a valid IP address or host name"; return }
        repo.upsertPrinter(p)
        val existing = repo.current.streamsOf(id)
        for (c in candidates) {
            val match = existing.firstOrNull { it.moonrakerUid != null && it.moonrakerUid == c.stream.moonrakerUid }
            when {
                c.selected && match != null -> repo.upsertStream(c.stream.copy(id = match.id, name = match.name, snapshotFallback = match.snapshotFallback))
                c.selected -> repo.upsertStream(c.stream)
                match != null -> repo.deleteStream(match.id)
            }
        }
        onDone()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (original == null) "Add printer" else "Edit printer") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (original != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Default.Delete, "Delete") }
                TextButton(onClick = ::save) { Text("Save") }
            },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            OutlinedTextField(address, { address = it }, Modifier.fillMaxWidth(), label = { Text("IP address or host") },
                placeholder = { Text("192.168.1.50") }, singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, placeholder = { Text("Snapmaker U1") }, singleLine = true)
            OutlinedTextField(port, { port = it.filter(Char::isDigit) }, Modifier.fillMaxWidth(),
                label = { Text("Moonraker port (optional)") },
                supportingText = { Text("Leave empty when Moonraker is behind Mainsail/Fluidd (default). Use 7125 to talk to Moonraker directly.") },
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
            OutlinedTextField(apiKey, { apiKey = it }, Modifier.fillMaxWidth(), label = { Text("API key (optional)") }, singleLine = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(user, { user = it }, Modifier.weight(1f), label = { Text("Username") }, singleLine = true)
                OutlinedTextField(pass, { pass = it }, Modifier.weight(1f), label = { Text("Password") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation())
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = ::discover, enabled = !busy) {
                    Icon(Icons.Default.Search, null); Spacer(Modifier.width(4.dp)); Text("Find cameras")
                }
                OutlinedButton(onClick = {
                    val host = Urls.urlHost(build()) ?: "printer"
                    addCandidates(paxx12Preset(id, host), false)
                    message = "Added Snapmaker U1 (paxx12) streams — tick the ones you use."
                }) { Text("U1 paxx12 preset") }
                if (busy) CircularProgressIndicator(Modifier.size(24.dp))
            }
            message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (candidates.isNotEmpty()) {
                HorizontalDivider()
                Text("Cameras", style = MaterialTheme.typography.titleMedium)
                candidates.forEach { c ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(c.selected, { c.selected = it })
                        Column(Modifier.weight(1f)) {
                            Text(c.stream.name + if (c.existing) " (saved)" else "", style = MaterialTheme.typography.bodyLarge)
                            Text("${c.stream.type.label} · ${c.stream.streamUrl ?: c.stream.snapshotUrl ?: ""}", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Spacer(Modifier.padding(4.dp))
            Text(
                "Tip: on the paxx12 firmware, RTSP must be enabled in firmware-config (Camera → RTSP). " +
                    "WebRTC is the default and works best on your local network.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("Delete printer?") },
            text = { Text("Its cameras are removed from all widgets.") },
            confirmButton = { TextButton(onClick = { repo.deletePrinter(id); confirmDelete = false; onDone() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
        )
    }
}
