package io.github.abdukir.klippercam.ui

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.abdukir.klippercam.App
import io.github.abdukir.klippercam.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAddPrinter: () -> Unit,
    onEditPrinter: (String) -> Unit,
    onEditCamera: (String?, String) -> Unit,
    onSettings: () -> Unit,
    onPinWidget: () -> Boolean,
) {
    val state by App.instance.repo.state.collectAsState()
    val ctx = LocalContext.current
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(ctx.getString(R.string.app_name)) },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onAddPrinter, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add printer") })
        },
    ) { pad ->
        if (state.printers.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(pad).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("No printers yet", style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.padding(8.dp))
                Text(
                    "Add your Klipper printer by IP address. Cameras configured in Moonraker " +
                        "(Mainsail/Fluidd) are discovered automatically — including the Snapmaker U1 " +
                        "paxx12 firmware's WebRTC, MJPEG, H.264 and RTSP streams.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Home-screen widget", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Long-press your home screen → Widgets → Klipper Cam, then pick which cameras to show. " +
                                "Each widget can show up to 9 streams from any printers.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        TextButton(onClick = {
                            if (!onPinWidget()) Toast.makeText(ctx, "Add the widget from your launcher's widget picker", Toast.LENGTH_LONG).show()
                        }) { Text("Add widget now") }
                    }
                }
            }
            items(state.printers, key = { it.id }) { printer ->
                val streams = state.streamsOf(printer.id)
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(printer.name, style = MaterialTheme.typography.titleMedium)
                                Text(printer.address, style = MaterialTheme.typography.bodySmall)
                            }
                            IconButton(onClick = { onEditPrinter(printer.id) }) { Icon(Icons.Default.Edit, "Edit printer") }
                        }
                        if (streams.isEmpty()) {
                            Text("No cameras. Edit the printer to discover them.", style = MaterialTheme.typography.bodySmall)
                        }
                        streams.chunked(2).forEach { pair ->
                            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                pair.forEach { s ->
                                    Column(
                                        Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).clickable {
                                            ctx.startActivity(ViewerActivity.intent(ctx, streams.map { it.id }, streams.indexOf(s)))
                                        },
                                    ) {
                                        LiveStreamView(s, Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), maxDim = 480, fps = 5)
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Column(Modifier.weight(1f)) {
                                                Text(s.name, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                Text(s.type.label, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                            }
                                            IconButton(onClick = { onEditCamera(s.id, printer.id) }) { Icon(Icons.Default.Edit, "Edit camera") }
                                        }
                                    }
                                }
                                if (pair.size == 1) Spacer(Modifier.weight(1f))
                            }
                        }
                        OutlinedButton(onClick = { onEditCamera(null, printer.id) }, Modifier.padding(top = 8.dp)) {
                            Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("Add camera manually")
                        }
                    }
                }
            }
        }
    }
}
