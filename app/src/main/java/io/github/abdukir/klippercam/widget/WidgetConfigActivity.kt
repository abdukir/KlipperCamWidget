package io.github.abdukir.klippercam.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.abdukir.klippercam.App
import io.github.abdukir.klippercam.data.WidgetConfig
import io.github.abdukir.klippercam.service.LiveWidgetService
import io.github.abdukir.klippercam.ui.KlipperCamTheme
import io.github.abdukir.klippercam.ui.MainActivity

/** Lets the user pick which cameras (from any printer) a widget shows, and in which order. */
class WidgetConfigActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val widgetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val repo = App.instance.repo
        val existing = repo.current.widget(widgetId)

        setContent {
            KlipperCamTheme {
                val state by repo.state.collectAsState()
                val selected = remember { mutableStateListOf<String>().apply { addAll(existing?.streamIds.orEmpty()) } }
                var labels by remember { mutableStateOf(existing?.showLabels ?: true) }
                var status by remember { mutableStateOf(existing?.showStatus ?: true) }
                var crop by remember { mutableStateOf(existing?.cropToFill ?: false) }

                Scaffold(topBar = {
                    TopAppBar(
                        title = { Text("Widget cameras") },
                        actions = {
                            TextButton(enabled = selected.isNotEmpty(), onClick = {
                                repo.upsertWidget(WidgetConfig(widgetId, selected.toList(), labels, status, crop))
                                WidgetRenderer.renderFull(this@WidgetConfigActivity, widgetId)
                                App.instance.notifyWidgetsChanged()
                                if (repo.current.settings.liveWidgets) LiveWidgetService.start(this@WidgetConfigActivity)
                                setResult(Activity.RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
                                finish()
                            }) { Text("Save") }
                        },
                    )
                }) { pad ->
                    Column(
                        Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (state.printers.isEmpty()) {
                            Text("Add a printer first.", style = MaterialTheme.typography.bodyLarge)
                            Button(onClick = { startActivity(Intent(this@WidgetConfigActivity, MainActivity::class.java)) }) { Text("Open app") }
                        }
                        Text(
                            "Select up to ${WidgetRenderer.MAX_TILES} cameras. Tiles are arranged automatically to fit the widget size.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        state.printers.forEach { p ->
                            Text(p.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                            state.streamsOf(p.id).filterNot { it.id.startsWith("preview-") }.forEach { s ->
                                val idx = selected.indexOf(s.id)
                                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                                    Checkbox(idx >= 0, { on ->
                                        if (on && selected.size < WidgetRenderer.MAX_TILES) selected.add(s.id) else if (!on) selected.remove(s.id)
                                    })
                                    Column(Modifier.weight(1f)) {
                                        Text((if (idx >= 0) "${idx + 1}. " else "") + s.name)
                                        Text(s.type.label, style = MaterialTheme.typography.bodySmall)
                                    }
                                    if (idx >= 0) {
                                        IconButton(enabled = idx > 0, onClick = { selected.add(idx - 1, selected.removeAt(idx)) }) {
                                            Icon(Icons.Default.KeyboardArrowUp, "Move up")
                                        }
                                        IconButton(enabled = idx < selected.lastIndex, onClick = { selected.add(idx + 1, selected.removeAt(idx)) }) {
                                            Icon(Icons.Default.KeyboardArrowDown, "Move down")
                                        }
                                    }
                                }
                            }
                        }
                        HorizontalDivider(Modifier.padding(vertical = 8.dp))
                        Toggle("Show camera names", labels) { labels = it }
                        Toggle("Show LIVE badge", status) { status = it }
                        Toggle("Crop to fill tiles", crop) { crop = it }
                    }
                }
            }
        }
    }
}

@androidx.compose.runtime.Composable
private fun Toggle(text: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(text, Modifier.weight(1f))
        Switch(value, onChange)
    }
}
