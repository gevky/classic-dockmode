package com.displaylinkdock.displaylink_dock_companion

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.hardware.usb.UsbManager

/** Gives Classic DockMode an additional wake-up signal for cable and power events. */
class DockTriggerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            UsbManager.ACTION_USB_DEVICE_ATTACHED,
            Intent.ACTION_POWER_CONNECTED,
            -> {
                try {
                    DockService.start(context.applicationContext, scanNow = true)
                } catch (_: RuntimeException) {
                    // A later app open or boot recovery can safely retry.
                }
            }

            UsbManager.ACTION_USB_DEVICE_DETACHED,
            Intent.ACTION_POWER_DISCONNECTED,
            -> restoreIfDockReallyLeft(context)
        }
    }

    private fun restoreIfDockReallyLeft(context: Context) {
        if (!DockSessionStore(context).isSessionPresent) return
        val pendingResult = goAsync()
        Thread {
            try {
                Thread.sleep(DISCONNECT_SETTLE_MILLIS)
                val controller = DockController(context.applicationContext)
                if (!controller.externalDisplayConnected()) controller.restore()
            } finally {
                pendingResult.finish()
            }
        }.start()
    }

    companion object {
        private const val DISCONNECT_SETTLE_MILLIS = 1_500L
    }
}
