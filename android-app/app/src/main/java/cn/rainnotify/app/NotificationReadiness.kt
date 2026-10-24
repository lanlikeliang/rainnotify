package cn.rainnotify.app

import android.Manifest
import android.app.ActivityManager
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioManager
import android.os.PowerManager

data class NotificationReadiness(
    val runtimeGranted: Boolean,
    val appEnabled: Boolean,
    val channelExists: Boolean,
    val importance: Int,
    val soundConfigured: Boolean,
    val vibrationEnabled: Boolean,
    val vibrationPermission: Boolean,
    val wakeLockPermission: Boolean,
    val ringerMode: Int,
    val volume: Int,
    val interruptionFilter: Int,
    val batteryExempt: Boolean,
    val backgroundRestricted: Boolean,
    val lockscreenVisibility: Int,
) {
    // A posting prerequisite, never a claim that a banner or sound was observed.
    val canPost get() = runtimeGranted && appEnabled && channelExists && importance > 0

    fun diagnosticFields(): Map<String, Any> = mapOf(
        "runtime_notification_permission" to runtimeGranted,
        "notifications_enabled" to appEnabled,
        "channel_exists" to channelExists,
        "channel_importance" to importance,
        "channel_sound_configured" to soundConfigured,
        "channel_vibration_enabled" to vibrationEnabled,
        "vibration_permission" to vibrationPermission,
        "wake_lock_permission" to wakeLockPermission,
        "ringer_mode" to ringerMode,
        "notification_volume" to volume,
        "interruption_filter" to interruptionFilter,
        "background_restricted" to backgroundRestricted,
        "channel_lockscreen_visibility" to lockscreenVisibility,
    )

    companion object {
        fun read(context: Context): NotificationReadiness {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = manager.getNotificationChannel(ProbeNotifications.QUESTION_CHANNEL)
            val audio = context.getSystemService(AudioManager::class.java)
            val power = context.getSystemService(PowerManager::class.java)
            fun granted(permission: String) =
                context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
            return NotificationReadiness(
                granted(Manifest.permission.POST_NOTIFICATIONS), manager.areNotificationsEnabled(),
                channel != null, channel?.importance ?: 0, channel?.sound != null,
                channel?.shouldVibrate() ?: false, granted(Manifest.permission.VIBRATE),
                granted(Manifest.permission.WAKE_LOCK), audio.ringerMode,
                audio.getStreamVolume(AudioManager.STREAM_NOTIFICATION), manager.currentInterruptionFilter,
                power.isIgnoringBatteryOptimizations(context.packageName),
                context.getSystemService(ActivityManager::class.java).isBackgroundRestricted,
                channel?.lockscreenVisibility ?: NotificationManager.IMPORTANCE_UNSPECIFIED,
            )
        }
    }
}
