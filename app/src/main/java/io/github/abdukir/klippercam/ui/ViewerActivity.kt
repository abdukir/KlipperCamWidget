package io.github.abdukir.klippercam.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.abdukir.klippercam.App
import kotlin.math.max

/** Full-screen live view; swipe between the cameras of the widget that opened it. */
class ViewerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val ids = intent.getStringArrayExtra(EXTRA_IDS)?.toList().orEmpty()
        val start = intent.getIntExtra(EXTRA_INDEX, 0)
        setContent {
            KlipperCamTheme {
                val state by App.instance.repo.state.collectAsState()
                val streams = ids.mapNotNull { state.stream(it) }
                if (streams.isEmpty()) { finish(); return@KlipperCamTheme }
                val pager = rememberPagerState(initialPage = start.coerceIn(0, streams.lastIndex)) { streams.size }
                val config = LocalConfiguration.current
                val density = LocalDensity.current
                val screenMax = with(density) { max(config.screenWidthDp, config.screenHeightDp).dp.roundToPx() }
                Box(Modifier.fillMaxSize().background(Color.Black)) {
                    HorizontalPager(pager, Modifier.fillMaxSize(), beyondViewportPageCount = 0) { page ->
                        LiveStreamView(
                            streams[page.coerceIn(0, streams.lastIndex)],
                            Modifier.fillMaxSize(),
                            maxDim = screenMax.coerceAtMost(1920),
                            fps = state.settings.viewerFps,
                            zoomable = true,
                            showStatus = false,
                        )
                    }
                    val current = streams[pager.currentPage.coerceIn(0, streams.lastIndex)]
                    val status by App.instance.hub.status(current.id).collectAsState()
                    Column(
                        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.4f)).safeDrawingPadding().padding(4.dp),
                    ) {
                        androidx.compose.foundation.layout.Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                            }
                            Column(Modifier.weight(1f)) {
                                Text(current.name, color = Color.White, style = MaterialTheme.typography.titleMedium)
                                Text(
                                    (state.printer(current.printerId)?.name ?: "") + " · " + current.type.label +
                                        if (streams.size > 1) "  (${pager.currentPage + 1}/${streams.size})" else "",
                                    color = Color.White.copy(alpha = 0.7f), style = MaterialTheme.typography.bodySmall,
                                )
                            }
                            StatusChip(status, Modifier.padding(end = 8.dp))
                        }
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_IDS = "ids"
        private const val EXTRA_INDEX = "index"
        fun intent(context: Context, ids: List<String>, index: Int) = Intent(context, ViewerActivity::class.java)
            .putExtra(EXTRA_IDS, ids.toTypedArray())
            .putExtra(EXTRA_INDEX, index)
    }
}
