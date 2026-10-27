package com.displaylinkdock.displaylink_dock_companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED &&
            intent?.action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        Thread {
            try {
                DockController(context.applicationContext).restoreStaleSessionIfNeeded()
            } finally {
                pending.finish()
            }
        }.start()
    }
}
