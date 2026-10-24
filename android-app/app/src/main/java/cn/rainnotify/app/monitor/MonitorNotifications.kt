package cn.rainnotify.app.monitor

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import cn.rainnotify.app.Diagnostics
import cn.rainnotify.app.NotificationReadiness
import cn.rainnotify.app.ProbeNotifications
import cn.rainnotify.app.RainLoginActivity
import cn.rainnotify.app.question.QuestionActivity
import android.net.Uri
import cn.rainnotify.app.answer.AnswerActivity
import cn.rainnotify.app.answer.AnswerService

object MonitorNotifications {
    const val STATUS_ID = 3
    private fun open(context: Context) = PendingIntent.getActivity(context, 10,
        Intent(context, RainLoginActivity::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)

    fun status(context: Context, message: String): Notification {
        val stop = PendingIntent.getService(context, 11,
            Intent(context, LessonMonitorService::class.java).setAction(LessonMonitorService.STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return Notification.Builder(context, ProbeNotifications.STATUS_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("雨课堂监听").setContentText(message)
            .setContentIntent(open(context)).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(Notification.Action.Builder(null, "停止监听", stop).build()).build()
    }

    fun question(context: Context, questionId: String): Boolean {
        val eventId = questionId
        // Distinct Intent data prevents an older notification from opening the newest question.
        val detail = PendingIntent.getActivity(context, 0,
            Intent(context, QuestionActivity::class.java)
                .setData(Uri.parse("rainnotify://question/" + questionId))
                .putExtra(QuestionActivity.QUESTION_ID, questionId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val readiness = NotificationReadiness.read(context)
        val answer = PendingIntent.getActivity(context, 1,
            Intent(context, AnswerActivity::class.java)
                .setData(Uri.parse("rainnotify://answer/" + questionId))
                .putExtra(AnswerService.QUESTION, questionId).putExtra(AnswerService.LIVE, true)
                .putExtra(AnswerActivity.AUTO, true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val fields = readiness.diagnosticFields() + mapOf("source" to "real", "event_id" to eventId)
        Diagnostics.record(context, "event_received", fields)
        if (!readiness.canPost) {
            Diagnostics.record(context, "notification_skipped", fields)
            return false
        }
        return try {
            context.getSystemService(NotificationManager::class.java).notify(eventId, 4,
                Notification.Builder(context, ProbeNotifications.QUESTION_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info)
                    .setContentTitle("雨课堂有新题")
                    .setContentText("正在获取题干、选项和原图，点击查看。")
                    .setContentIntent(detail).setCategory(Notification.CATEGORY_REMINDER)
                    .addAction(Notification.Action.Builder(null, "查看题目", detail).build())
                    .addAction(Notification.Action.Builder(null, "AI 作答并提交", answer).build())
                    .setVisibility(Notification.VISIBILITY_PRIVATE).setAutoCancel(true).build())
            Diagnostics.record(context, "notification_submitted", fields)
            true
        } catch (error: RuntimeException) {
            Diagnostics.record(context, "notification_failed", fields + ("error_type" to error.javaClass.simpleName))
            false
        }
    }
}
