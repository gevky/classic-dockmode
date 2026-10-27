package com.displaylinkdock.displaylink_dock_companion

import android.os.Bundle
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodCall
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

class MainActivity : FlutterActivity() {
    private lateinit var controller: DockController
    private val worker = Executors.newSingleThreadExecutor()
    private var outputApplyInProgress = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        controller = DockController(applicationContext)
    }

    override fun onStart() {
        super.onStart()
        worker.execute { controller.recoverAndStartMonitoring() }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            CHANNEL,
        ).setMethodCallHandler { call, result -> handleCall(call, result) }
    }

    private fun handleCall(call: MethodCall, result: MethodChannel.Result) {
        when (call.method) {
            "openWriteSettings" -> {
                controller.openWriteSettings()
                result.success(null)
            }
            "getStatus" -> runAsync(result) { controller.status() }
            "probeBackend" -> runAsync(result) { controller.probe() }
            "getPreferences" -> runAsync(result) { controller.getPreferences() }
            "savePreferences" -> {
                @Suppress("UNCHECKED_CAST")
                val values = call.arguments as? Map<String, Any?> ?: emptyMap()
                runAsync(result) {
                    controller.updatePreferences(values, allowTemporaryMonitoring = true)
                }
            }
            "listLaunchableApps" -> runAsync(result) { controller.listLaunchableApplications() }
            "startDockMode" -> {
                runAsync(result) { controller.startDockMode().toMap() }
            }
            "testSelectedOutput" -> {
                outputApplyInProgress = true
                runAsync(result, onComplete = { outputApplyInProgress = false }) {
                    controller.testSelectedOutput().toMap()
                }
            }
            "testOutput" -> {
                val width = call.argument<Int>("width") ?: 0
                val height = call.argument<Int>("height") ?: 0
                outputApplyInProgress = true
                runAsync(result, onComplete = { outputApplyInProgress = false }) {
                    controller.testOutput(width, height).toMap()
                }
            }
            "keepTest" -> runAsync(result) { controller.keepTest().toMap() }
            "restore" -> runAsync(result) { controller.restore().toMap() }
            else -> result.notImplemented()
        }
    }

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations && !outputApplyInProgress) {
            worker.execute {
                controller.restoreIfTestPending()
                controller.stopIdleMonitoringIfDisabled()
            }
        }
    }

    override fun onDestroy() {
        worker.shutdown()
        super.onDestroy()
    }

    private fun runAsync(
        result: MethodChannel.Result,
        onComplete: () -> Unit = {},
        block: () -> Any?,
    ) {
        worker.execute {
            try {
                val value = block()
                runOnUiThread {
                    onComplete()
                    result.success(value)
                }
            } catch (error: Throwable) {
                runOnUiThread {
                    onComplete()
                    result.error("native_error", error.message, error.stackTraceToString())
                }
            }
        }
    }

    companion object {
        private const val CHANNEL = "displaylink_dock/system"
    }
}
