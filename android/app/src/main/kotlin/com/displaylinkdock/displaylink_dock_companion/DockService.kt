package com.displaylinkdock.displaylink_dock_companion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import java.util.concurrent.Executors

class DockService : Service(), DisplayManager.DisplayListener {
    private lateinit var displayManager: DisplayManager
    private lateinit var store: DockSessionStore
    private lateinit var preferences: DockPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var temporaryMonitoringAllowed = false

    private val testTimeout = Runnable {
        if (store.isTestPending && System.currentTimeMillis() >= store.testDeadlineMillis) {
            restoreAsync()
        }
    }

    private val delayedAutoStart = Runnable { autoStartAsync() }

    override fun onCreate() {
        super.onCreate()
        store = DockSessionStore(this)
        preferences = DockPreferences(this)
        displayManager = getSystemService(DisplayManager::class.java)
        displayManager.registerDisplayListener(this, handler)
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_RESTORE) {
            restoreAsync()
            return START_STICKY
        }
        if (intent?.action == ACTION_DISABLE_TEMPORARY) {
            temporaryMonitoringAllowed = false
            if (!store.isSessionPresent && !preferences.read().backgroundDetection) {
                stopSelf()
                return START_NOT_STICKY
            }
            return START_STICKY
        }
        val scanNow = intent?.getBooleanExtra(EXTRA_SCAN_NOW, false) ?: false
        temporaryMonitoringAllowed =
            temporaryMonitoringAllowed ||
                (intent?.getBooleanExtra(EXTRA_ALLOW_TEMPORARY, false) ?: false)
        if (!store.isSessionPresent &&
            !preferences.read().backgroundDetection &&
            !temporaryMonitoringAllowed
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleTestTimeout()
        if (scanNow && !store.isSessionPresent) {
            handler.removeCallbacks(delayedAutoStart)
            handler.postDelayed(delayedAutoStart, AUTO_START_DELAY_MILLIS)
        }
        return START_STICKY
    }

    override fun onDisplayAdded(displayId: Int) {
        if (!store.isSessionPresent &&
            (preferences.read().backgroundDetection || temporaryMonitoringAllowed)
        ) {
            handler.removeCallbacks(delayedAutoStart)
            handler.postDelayed(delayedAutoStart, AUTO_START_DELAY_MILLIS)
        }
    }

    override fun onDisplayChanged(displayId: Int) = Unit

    override fun onDisplayRemoved(displayId: Int) {
        val savedId = store.readSavedState()?.externalDisplayId
        if (store.isSessionPresent && savedId == displayId) restoreAsync()
    }

    override fun onDestroy() {
        handler.removeCallbacks(testTimeout)
        handler.removeCallbacks(delayedAutoStart)
        displayManager.unregisterDisplayListener(this)
        worker.shutdownNow()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        if (!preferences.read().backgroundDetection) temporaryMonitoringAllowed = false
        if (store.isTestPending) restoreAsync()
        super.onTaskRemoved(rootIntent)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun scheduleTestTimeout() {
        handler.removeCallbacks(testTimeout)
        if (!store.isTestPending) return
        val delay = (store.testDeadlineMillis - System.currentTimeMillis()).coerceAtLeast(0L)
        handler.postDelayed(testTimeout, delay)
    }

    private fun autoStartAsync() {
        if (store.isSessionPresent) return
        worker.execute {
            val controller = DockController(applicationContext)
            if (!controller.externalDisplayConnected()) {
                return@execute
            }
            val result = controller.autoStartIfReady()
            handler.post {
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    buildNotification(if (result.succeeded) null else result.message),
                )
                scheduleTestTimeout()
            }
        }
    }

    private fun restoreAsync() {
        worker.execute {
            val result = DockController(applicationContext).restore()
            handler.post {
                handler.removeCallbacks(testTimeout)
                if (!result.succeeded) {
                    getSystemService(NotificationManager::class.java).notify(
                        NOTIFICATION_ID,
                        buildNotification(result.message),
                    )
                }
                if (preferences.read().backgroundDetection || temporaryMonitoringAllowed) {
                    startForeground(NOTIFICATION_ID, buildNotification())
                } else {
                    stopSelf()
                }
            }
        }
    }

    private fun buildNotification(messageOverride: String? = null): Notification {
        val restoreIntent = Intent(this, DockService::class.java).setAction(ACTION_RESTORE)
        val restorePendingIntent = PendingIntent.getService(
            this,
            1,
            restoreIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent(this, MainActivity::class.java)
        val openPendingIntent = PendingIntent.getActivity(
            this,
            2,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = when {
            store.isTestPending -> "Output test active"
            store.isActive -> "Dock Mode active"
            messageOverride != null -> "Classic DockMode needs attention"
            else -> "Connecting Dock Mode"
        }
        val text = messageOverride ?: when {
            store.isSessionPresent ->
                "${store.targetLandscapeWidth} × ${store.targetLandscapeHeight} • DisplayLink"
            else -> "Waiting for DisplayLink Presenter"
        }
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_dock_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openPendingIntent)
        if (store.isSessionPresent) {
            builder.addAction(
                Notification.Action.Builder(null, "Restore handheld", restorePendingIntent).build(),
            )
        }
        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Classic DockMode",
            NotificationManager.IMPORTANCE_LOW,
        ).apply { description = "Keeps automatic docking ready" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "dock_session"
        private const val NOTIFICATION_ID = 4108
        private const val ACTION_RESTORE = "com.displaylinkdock.action.RESTORE"
        private const val ACTION_DISABLE_TEMPORARY =
            "com.displaylinkdock.action.DISABLE_TEMPORARY"
        private const val EXTRA_SCAN_NOW = "scan_now"
        private const val EXTRA_ALLOW_TEMPORARY = "allow_temporary"
        private const val AUTO_START_DELAY_MILLIS = 1_500L

        fun start(context: Context, scanNow: Boolean, allowTemporary: Boolean = false) {
            val intent = Intent(context, DockService::class.java)
                .putExtra(EXTRA_SCAN_NOW, scanNow)
                .putExtra(EXTRA_ALLOW_TEMPORARY, allowTemporary)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, DockService::class.java))
        }

        fun disableTemporaryMonitoring(context: Context) {
            context.startService(
                Intent(context, DockService::class.java).setAction(ACTION_DISABLE_TEMPORARY),
            )
        }
    }
}
