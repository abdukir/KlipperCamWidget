package io.github.abdukir.klippercam

import android.app.Application
import io.github.abdukir.klippercam.data.Repository
import io.github.abdukir.klippercam.stream.StreamHub
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

class App : Application() {
    lateinit var repo: Repository
        private set
    lateinit var hub: StreamHub
        private set

    private val _widgetEvents = MutableSharedFlow<Unit>(extraBufferCapacity = 8)
    /** Emitted when widgets are resized/added so the live service recomputes layouts. */
    val widgetEvents: SharedFlow<Unit> = _widgetEvents
    fun notifyWidgetsChanged() { _widgetEvents.tryEmit(Unit) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        repo = Repository(this)
        // Drop temporary "Test stream" cameras left behind if the app was killed.
        if (repo.current.streams.any { it.id.startsWith("preview-") }) {
            repo.update { st -> st.copy(streams = st.streams.filterNot { it.id.startsWith("preview-") }) }
        }
        hub = StreamHub(this, repo)
    }

    companion object {
        lateinit var instance: App
            private set
    }
}
