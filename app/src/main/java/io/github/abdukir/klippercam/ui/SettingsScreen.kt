package io.github.abdukir.klippercam.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import io.github.abdukir.klippercam.App
import io.github.abdukir.klippercam.BuildConfig
import io.github.abdukir.klippercam.service.LiveWidgetService

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onDone: () -> Unit) {
    val repo = App.instance.repo
    val state by repo.state.collectAsState()
    val s = state.settings
    val ctx = LocalContext.current
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Settings") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Widgets", style = MaterialTheme.typography.titleMedium)
            SwitchRow(
                "Live widgets",
                "Stream into home-screen widgets while the screen is on (shows a silent notification). " +
                    "When off, widgets refresh a snapshot every 30 minutes or when tapped ▶.",
                s.liveWidgets,
            ) { on ->
                repo.updateSettings { it.copy(liveWidgets = on) }
                if (on) LiveWidgetService.start(ctx, explicit = true) else LiveWidgetService.stop(ctx)
            }
            Choice("Widget frame rate", listOf(1, 2, 5, 10, 15), s.widgetFps, { "$it fps" }) { v -> repo.updateSettings { it.copy(widgetFps = v) } }
            Text(
                "Launchers redraw widgets from bitmaps, so higher rates cost battery. 2–5 fps works well.",
                style = MaterialTheme.typography.bodySmall,
            )
            Choice("Widget resolution", listOf(320, 480, 640, 960), s.widgetMaxResolution, { "${it}px" }) { v -> repo.updateSettings { it.copy(widgetMaxResolution = v) } }
            HorizontalDivider()
            Text("Viewer", style = MaterialTheme.typography.titleMedium)
            Choice("Viewer frame rate", listOf(10, 15, 24, 30), s.viewerFps, { "$it fps" }) { v -> repo.updateSettings { it.copy(viewerFps = v) } }
            HorizontalDivider()
            Text("Network", style = MaterialTheme.typography.titleMedium)
            SwitchRow("RTSP over TCP", "More reliable over Wi-Fi and VPNs than UDP.", s.rtspOverTcp) { on -> repo.updateSettings { it.copy(rtspOverTcp = on) } }
            SwitchRow("Use STUN server", "Only needed when watching from outside your network (e.g. through a VPN with NAT).", s.useStun) { on ->
                repo.updateSettings { it.copy(useStun = on) }
            }
            HorizontalDivider()
            Text("Klipper Cam Widget ${BuildConfig.VERSION_NAME} · open source (GPL-3.0)", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title)
            Text(subtitle, style = MaterialTheme.typography.bodySmall)
        }
        Switch(checked, onChange)
    }
}

@Composable
private fun <T> Choice(title: String, options: List<T>, selected: T, label: (T) -> String, onSelect: (T) -> Unit) {
    Column {
        Text(title)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { o -> FilterChip(o == selected, { onSelect(o) }, label = { Text(label(o)) }) }
        }
    }
}
