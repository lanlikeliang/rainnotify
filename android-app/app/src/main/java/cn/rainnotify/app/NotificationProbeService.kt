package cn.rainnotify.app

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock

class NotificationProbeService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var expectedElapsed = 0L
    private var expectedUptime = 0L
    private val pendingNotification = Runnable {
        Diagnostics.record(this, "simulation_timer_fired", mapOf(
            "test_mode" to "controlled_notification_display",
            "wake_lock_held" to (wakeLock?.isHeld ?: false),
            "timer_lateness_ms" to (SystemClock.elapsedRealtime() - expectedElapsed).coerceAtLeast(0),
            "timer_uptime_lateness_ms" to (SystemClock.uptimeMillis() - expectedUptime).coerceAtLeast(0),
        ))
        try { ProbeNotifications.simulate(this) }
        finally {
            releaseTestWakeLock()
            stopSelf()
        }
    }

    override fun onCreate() {
        super.onCreate()
        ProbeNotifications.createChannels(this)
        startForeground(ProbeNotifications.STATUS_ID, ProbeNotifications.status(this),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        Diagnostics.record(this, "service_started")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopSelf()
            ACTION_DELAYED -> {
                handler.removeCallbacks(pendingNotification)
                releaseTestWakeLock()
                // A bounded test-only wake lock separates notification visibility
                // from Handler uptime suspension. This is not a Doze network test.
                wakeLock = getSystemService(PowerManager::class.java)
                    .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "RainNotify:NotificationProbe")
                    .apply { setReferenceCounted(false); acquire(75_000) }
                expectedElapsed = SystemClock.elapsedRealtime() + 30_000
                expectedUptime = SystemClock.uptimeMillis() + 30_000
                handler.postDelayed(pendingNotification, 30_000)
                Diagnostics.record(this, "simulation_scheduled", mapOf(
                    "delay_ms" to 30_000, "test_mode" to "controlled_notification_display",
                    "wake_lock_held" to (wakeLock?.isHeld ?: false),
                ))
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        releaseTestWakeLock()
        Diagnostics.record(this, "service_stopped")
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun releaseTestWakeLock() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
    }

    companion object {
        const val ACTION_DELAYED = "cn.rainnotify.app.SIMULATE_AFTER_DELAY"
        const val ACTION_STOP = "cn.rainnotify.app.STOP_PROBE"
    }
}
