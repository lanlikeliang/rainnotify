package cn.rainnotify.app

import android.content.Context
import android.os.SystemClock

object SimulationGate {
    private const val INTERVAL = 120_000L
    private const val LAST_START = "last_simulation_start_elapsed"

    fun remaining(context: Context): Long {
        val preferences = context.getSharedPreferences("notification_probe", Context.MODE_PRIVATE)
        if (!preferences.contains(LAST_START)) return 0
        val delta = SystemClock.elapsedRealtime() - preferences.getLong(LAST_START, 0)
        // elapsedRealtime can reset after a reboot.
        return if (delta < 0 || delta >= INTERVAL) 0 else INTERVAL - delta
    }

    @Synchronized
    fun reserve(context: Context): Boolean {
        if (remaining(context) > 0) return false
        context.getSharedPreferences("notification_probe", Context.MODE_PRIVATE).edit()
            .putLong(LAST_START, SystemClock.elapsedRealtime()).apply()
        return true
    }
}
