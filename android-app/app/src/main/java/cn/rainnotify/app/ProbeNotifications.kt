package cn.rainnotify.app

import android.app.Notification
import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import java.util.UUID

object ProbeNotifications {
    const val STATUS_CHANNEL = "monitor_status"
    const val QUESTION_CHANNEL = "classroom_question"
    const val STATUS_ID = 1

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(
            STATUS_CHANNEL, "监听状态", NotificationManager.IMPORTANCE_LOW,
        ))
        manager.createNotificationChannel(NotificationChannel(
            QUESTION_CHANNEL, "课堂题目提醒", NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            enableVibration(true)
            vibrationPattern = longArrayOf(0, 180, 120, 180)
            setSound(RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION),
                AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION).build())
        })
    }

    private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
        context, 0, Intent(context, MainActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun simulate(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        val readiness = NotificationReadiness.read(context)
        val eventId = UUID.randomUUID().toString()
        val fields = readiness.diagnosticFields() + mapOf("source" to "simulation", "event_id" to eventId)
        Diagnostics.record(context, "event_received", fields)
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            || !readiness.canPost) {
            Diagnostics.record(context, "notification_skipped", fields)
            return
        }
        try {
            manager.notify(eventId, 2, Notification.Builder(context, QUESTION_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("通知测试")
                .setContentText("请确认横幅、锁屏通知、声音或振动。这不是雨课堂真实题目。")
                .setContentIntent(openApp(context))
                .setCategory(Notification.CATEGORY_REMINDER)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .build())
            Diagnostics.record(context, "notification_submitted", fields)
        } catch (error: RuntimeException) {
            Diagnostics.record(context, "notification_failed", fields + ("error_type" to error.javaClass.simpleName))
        }
    }
}
