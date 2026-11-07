package io.github.abdukir.klippercam.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.abdukir.klippercam.App

/** Resumes live widgets after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        if (App.instance.repo.current.settings.liveWidgets) LiveWidgetService.start(context)
    }
}
