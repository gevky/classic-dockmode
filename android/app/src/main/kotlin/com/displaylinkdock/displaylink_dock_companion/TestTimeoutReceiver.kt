package com.displaylinkdock.displaylink_dock_companion

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock

class TestTimeoutReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_TEST_TIMEOUT) return
        val pending = goAsync()
        Thread {
            try {
                DockController(context.applicationContext).restoreIfTestPending()
            } finally {
                pending.finish()
            }
        }.start()
    }

    companion object {
        private const val ACTION_TEST_TIMEOUT = "com.displaylinkdock.action.TEST_TIMEOUT"
        private const val REQUEST_CODE = 4109

        fun schedule(context: Context, delayMillis: Long) {
            val alarmManager = context.getSystemService(AlarmManager::class.java)
            alarmManager.setAndAllowWhileIdle(
                AlarmManager.ELAPSED_REALTIME_WAKEUP,
                SystemClock.elapsedRealtime() + delayMillis,
                pendingIntent(context),
            )
        }

        fun cancel(context: Context) {
            context.getSystemService(AlarmManager::class.java).cancel(pendingIntent(context))
        }

        private fun pendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, TestTimeoutReceiver::class.java)
                .setAction(ACTION_TEST_TIMEOUT)
            return PendingIntent.getBroadcast(
                context,
                REQUEST_CODE,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }
    }
}
