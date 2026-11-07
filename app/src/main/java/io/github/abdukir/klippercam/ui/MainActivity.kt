package io.github.abdukir.klippercam.ui

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.core.content.ContextCompat
import io.github.abdukir.klippercam.App
import io.github.abdukir.klippercam.service.LiveWidgetService
import io.github.abdukir.klippercam.widget.CamWidgetProvider

sealed interface Screen {
    data object Home : Screen
    data class EditPrinter(val printerId: String?) : Screen
    data class EditCamera(val streamId: String?, val printerId: String) : Screen
    data object Settings : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
        // Opening the app is a good moment to (re)start live widgets: foreground starts are allowed.
        if (App.instance.repo.current.settings.liveWidgets) LiveWidgetService.start(this)
        setContent { KlipperCamTheme { AppNavigation(onPinWidget = ::pinWidget, onFinish = ::finish) } }
    }

    private fun pinWidget(): Boolean {
        val mgr = AppWidgetManager.getInstance(this)
        if (!mgr.isRequestPinAppWidgetSupported) return false
        return mgr.requestPinAppWidget(ComponentName(this, CamWidgetProvider::class.java), null, null)
    }
}

@Composable
fun AppNavigation(onPinWidget: () -> Boolean, onFinish: () -> Unit) {
    val stack = remember { mutableStateListOf<Screen>(Screen.Home) }
    val current by remember { androidx.compose.runtime.derivedStateOf { stack.last() } }
    fun push(s: Screen) { stack.add(s) }
    fun pop() { if (stack.size > 1) stack.removeAt(stack.lastIndex) else onFinish() }
    BackHandler(enabled = stack.size > 1) { pop() }
    when (val s = current) {
        Screen.Home -> HomeScreen(
            onAddPrinter = { push(Screen.EditPrinter(null)) },
            onEditPrinter = { push(Screen.EditPrinter(it)) },
            onEditCamera = { id, printer -> push(Screen.EditCamera(id, printer)) },
            onSettings = { push(Screen.Settings) },
            onPinWidget = onPinWidget,
        )
        is Screen.EditPrinter -> PrinterScreen(s.printerId, onDone = { pop() })
        is Screen.EditCamera -> CameraScreen(s.streamId, s.printerId, onDone = { pop() })
        Screen.Settings -> SettingsScreen(onDone = { pop() })
    }
}
