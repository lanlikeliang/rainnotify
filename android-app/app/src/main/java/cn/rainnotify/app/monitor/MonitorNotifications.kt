package cn.rainnotify.app.monitor

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import cn.rainnotify.app.*

object MonitorNotifications {
    const val STATUS_ID = 3
    private fun open(context: Context, question: Boolean = false) = PendingIntent.getActivity(context, if(question) 14 else 10,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.TAB, if(question) "question" else "classroom")
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun status(context: Context, message: String): Notification {
        val stop = PendingIntent.getService(context,11,Intent(context,LessonMonitorService::class.java).setAction(LessonMonitorService.STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context,ProbeNotifications.STATUS_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("雨课堂正在监听").setContentText(message).setContentIntent(open(context))
            .setOngoing(true).setOnlyAlertOnce(true).setVisibility(Notification.VISIBILITY_PRIVATE)
            .addAction(Notification.Action.Builder(null,"关闭监听",stop).build()).build()
    }
    fun question(context:Context,questionId:String):Boolean {
        val readiness=NotificationReadiness.read(context)
        val fields=readiness.diagnosticFields()+mapOf("source" to "real","event_id" to questionId)
        Diagnostics.record(context,"event_received",fields)
        if(!readiness.canPost){Diagnostics.record(context,"notification_skipped",fields);return false}
        return try {
            // One latest-question notification, matching the single latest-question screen.
            context.getSystemService(NotificationManager::class.java).notify(4,
                Notification.Builder(context,ProbeNotifications.QUESTION_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("雨课堂有新题").setContentText("正在获取题干、选项和原图，点击查看最新题目。")
                    .setContentIntent(open(context,true)).setCategory(Notification.CATEGORY_REMINDER)
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build())
            Diagnostics.record(context,"notification_submitted",fields);true
        }catch(error:RuntimeException){Diagnostics.record(context,"notification_failed",fields+("error_type" to error.javaClass.simpleName));false}
    }
}
