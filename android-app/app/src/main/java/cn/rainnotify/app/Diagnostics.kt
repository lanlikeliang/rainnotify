package cn.rainnotify.app

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.util.UUID

object Diagnostics {
    private val runId = UUID.randomUUID().toString()
    @Volatile var appForeground = false
    private val allowedFields = setOf(
        "source", "event_id", "delay_ms", "error_type", "notifications_enabled",
        "channel_importance", "manufacturer", "model", "android_release", "sdk", "app_version",
        "channel_sound_configured", "channel_vibration_enabled", "interruption_filter", "ringer_mode",
        "notification_volume",
        "wake_lock_held", "timer_lateness_ms", "test_mode",
        "runtime_notification_permission", "channel_exists", "vibration_permission",
        "wake_lock_permission", "background_restricted", "channel_lockscreen_visibility",
        "timer_uptime_lateness_ms", "settings_target", "remaining_ms",
        "protocol_stage", "http_status", "business_code", "lesson_count",
        "authenticated", "session_saved", "login_message_kind",
        "transport_error", "websocket_close_code",
        "network_available",
        "request_has_authorization", "request_has_csrf", "response_has_authorization", "response_json",
        "question_type", "option_count", "asset_count", "loaded_asset_count", "body_present",
        "comparison_stage", "changed_fields", "asset_url_changed", "content_same",
    )

    @Synchronized
    fun record(context: Context, kind: String, fields: Map<String, Any> = emptyMap()) {
        require(fields.keys.all { it in allowedFields }) { "Diagnostic field is not allowlisted" }
        val power = context.getSystemService(PowerManager::class.java)
        val entry = JSONObject()
            .put("kind", kind)
            .put("run_id", runId)
            .put("timestamp_ms", System.currentTimeMillis())
            .put("elapsed_ms", SystemClock.elapsedRealtime())
            .put("uptime_ms", SystemClock.uptimeMillis())
            .put("interactive", power.isInteractive)
            .put("app_foreground", appForeground)
            .put("device_idle", power.isDeviceIdleMode)
            .put("power_save", power.isPowerSaveMode)
            .put("battery_optimization_exempt", power.isIgnoringBatteryOptimizations(context.packageName))
        fields.forEach { (key, value) -> entry.put(key, value) }
        val file = reportFile(context)
        // Keep a bounded diagnostic file, retaining whole JSONL entries.
        if (file.exists() && file.length() > 1024 * 1024) {
            file.writeText(file.readLines().takeLast(1000).joinToString("\n", postfix = "\n"))
        }
        file.appendText(entry.toString() + "\n")
        Log.i("RainNotifyDiag", entry.toString())
    }

    fun recordDevice(context: Context) = record(context, "device", mapOf(
        "manufacturer" to Build.MANUFACTURER,
        "model" to Build.MODEL,
        "android_release" to Build.VERSION.RELEASE,
        "sdk" to Build.VERSION.SDK_INT,
        "app_version" to BuildConfig.VERSION_NAME,
    ))

    fun reportFile(context: Context): File = File(context.filesDir, "diagnostics.jsonl")
}
