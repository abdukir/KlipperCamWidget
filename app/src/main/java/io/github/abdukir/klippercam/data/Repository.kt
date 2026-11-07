package io.github.abdukir.klippercam.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.Json

/**
 * Tiny JSON-in-SharedPreferences store. The whole state is small (a handful of printers and
 * cameras), so it is kept in memory and written synchronously-enough with apply().
 */
class Repository(context: Context) {
    private val prefs = context.getSharedPreferences("klippercam", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private val _state = MutableStateFlow(load())
    val state: StateFlow<AppState> = _state.asStateFlow()
    val current: AppState get() = _state.value

    private fun load(): AppState {
        val raw = prefs.getString(KEY, null) ?: return AppState()
        return try {
            json.decodeFromString(AppState.serializer(), raw)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load state, starting fresh", e)
            AppState()
        }
    }

    private val lock = Any()

    /** Serialized so the persisted JSON always matches the latest in-memory state. */
    fun update(transform: (AppState) -> AppState) = synchronized(lock) {
        _state.update(transform)
        prefs.edit().putString(KEY, json.encodeToString(AppState.serializer(), _state.value)).apply()
    }

    fun upsertPrinter(printer: Printer) = update { s ->
        val exists = s.printers.any { it.id == printer.id }
        s.copy(printers = if (exists) s.printers.map { if (it.id == printer.id) printer else it } else s.printers + printer)
    }

    fun deletePrinter(id: String) = update { s ->
        val removed = s.streams.filter { it.printerId == id }.map { it.id }.toSet()
        s.copy(
            printers = s.printers.filterNot { it.id == id },
            streams = s.streams.filterNot { it.printerId == id },
            widgets = s.widgets.map { w -> w.copy(streamIds = w.streamIds - removed) },
        )
    }

    fun upsertStream(stream: CameraStream) = update { s ->
        val exists = s.streams.any { it.id == stream.id }
        s.copy(streams = if (exists) s.streams.map { if (it.id == stream.id) stream else it } else s.streams + stream)
    }

    fun deleteStream(id: String) = update { s ->
        s.copy(
            streams = s.streams.filterNot { it.id == id },
            widgets = s.widgets.map { w -> w.copy(streamIds = w.streamIds - id) },
        )
    }

    fun upsertWidget(config: WidgetConfig) = update { s ->
        s.copy(widgets = s.widgets.filterNot { it.widgetId == config.widgetId } + config)
    }

    fun deleteWidgets(ids: IntArray) = update { s ->
        s.copy(widgets = s.widgets.filterNot { it.widgetId in ids })
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = update { it.copy(settings = transform(it.settings)) }

    private companion object {
        const val KEY = "state_v1"
        const val TAG = "Repository"
    }
}
