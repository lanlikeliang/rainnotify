package cn.rainnotify.app.answer

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import cn.rainnotify.app.Diagnostics
import cn.rainnotify.app.ProbeNotifications
import cn.rainnotify.app.question.QuestionRepository
import java.util.UUID

/** All mutations are on the main thread. Real events and offline timers use the same wake gate. */
object AutoAnswer {
    private val policy = AutoPolicy(SystemClock::elapsedRealtime)
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var binding: QuestionRepository.Binding? = null
    private var wake: PowerManager.WakeLock? = null
    private var context: Context? = null
    var provider = AiProvider.KIMI; private set
    var claimed = false; private set
    val status = MutableLiveData("本次课堂自动模式未开启。")
    val armed get() = policy.session != null
    val ticket get() = policy.ticket
    fun arm(context: Context): Boolean {
        val current = QuestionRepository.get(context).currentBinding() ?: return false
        if (AiReadiness.issue(context, AiProvider.selected(context)) != null || AnswerState.task.value?.active == true || ticket != null) return false
        this.context = context.applicationContext
        binding = current
        policy.arm(UUID.randomUUID().toString())
        status.value = "本次课堂自动模式已开启；新题将交给所选 AI，并尝试提交。"
        notice(context, status.value!!)
        return true
    }
    fun disarm(context: Context) {
        val running = claimed && ticket != null
        policy.disarm(); binding = null; claimed = false
        releaseWake()
        status.value = "自动模式已关闭。已发出的提交请求无法撤回。"
        context.getSystemService(NotificationManager::class.java).cancel(12)
        if (running) context.startService(Intent(context, AnswerService::class.java).setAction(AnswerService.STOP))
    }
    fun accessibilityLost(context: Context) {
        if (SolverSettings.isApi(context)) return
        if (!armed && ticket == null) return
        disarm(context)
        status.value = "辅助服务已断开，自动模式已关闭；请检查 AI 设置后手动重新开启。"
        notice(context, status.value!!)
    }
    fun onQuestion(context: Context, id: String) {
        if (!armed) return
        if (QuestionRepository.get(context).activeBinding(id) !== binding) { disarm(context); return }
        offer(context, id, policy.session)
    }
    fun simulate(context: Context, id: String) { offer(context, id, null) }
    private fun offer(context: Context, id: String, session: String?) {
        AiReadiness.issue(context, AiProvider.selected(context))?.let {
            if (armed) disarm(context)
            status.value = "自动作答未启动：$it"
            notice(context, status.value!!)
            return
        }
        val ticket = policy.offer(UUID.randomUUID().toString(), id, session, AnswerState.task.value?.active == true)
        if (ticket == null) {
            status.value = "另一题正在处理，本次新题未自动作答，请查看题目通知。"
            notice(context, status.value!!); return
        }
        this.context = context.applicationContext
        provider = AiProvider.selected(context)
        claimed = false
        val api = SolverSettings.isApi(context)
        status.value = "已收到" + (if (ticket.live) "新题" else "演练事件") +
            if (api) "，正在通过 DeepSeek API 作答，无需解锁。" else "，正在唤起作答；安全锁屏需解锁。"
        wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "rainnotify:auto-gate").apply { acquire(86_000) }
        Diagnostics.record(context, "auto_event", mapOf("event_id" to ticket.id,
            "source" to if (ticket.live) "real" else "simulation"))
        notice(context, status.value!!)
        try {
            if (api) context.startForegroundService(Intent(context, AnswerService::class.java).setAction(AnswerService.START)
                .putExtra(AnswerService.QUESTION, id).putExtra(AnswerService.AUTO_TICKET, ticket.id))
            else KimiAccessibilityService.instance?.startActivity(gateIntent(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: RuntimeException) {
            finish(ticket.id)
            status.value = "系统未允许后台作答，请从题目页手动开始。"
            notice(context, status.value!!)
        }
        main.postDelayed({ if (policy.ticket?.id == ticket.id) {
            if (claimed) context.startService(Intent(context, AnswerService::class.java).setAction(AnswerService.STOP))
            finish(ticket.id)
            status.value = "本题自动流程已超时，请手动查看；不会补交。"
            notice(context, status.value!!)
        } }, 85_000)
    }
    fun valid(id: String): Boolean {
        val current = policy.ticket ?: return false
        return policy.valid(id) && (!current.live ||
            context?.let { QuestionRepository.get(it).activeBinding(current.question) === binding } == true)
    }
    fun claim(id: String): Boolean {
        if (claimed || !valid(id)) return false
        claimed = true
        releaseWake()
        return true
    }
    fun finish(id: String) {
        if (ticket?.id != id) return
        policy.finish(id); claimed = false; releaseWake()
        context?.getSystemService(NotificationManager::class.java)?.cancel(12)
        status.value = if (armed) "本次课堂自动模式已开启，等待下一题。" else "自动演练已结束。"
        if (armed) context?.let { notice(it, status.value!!) }
    }
    private fun releaseWake() { if (wake?.isHeld == true) wake?.release(); wake = null }
    fun gateIntent(context: Context) = Intent(context, AutoGateActivity::class.java)
        .putExtra("ticket", ticket?.id).setData(android.net.Uri.parse("rainnotify://auto/" + ticket?.id))
    private fun notice(context: Context, message: String) {
        ProbeNotifications.createChannels(context)
        val open = PendingIntent.getActivity(context, 80, if (ticket != null && SolverSettings.isApi(context))
            Intent(context, AnswerActivity::class.java).putExtra(AnswerService.QUESTION, ticket?.question)
            else if (ticket != null) gateIntent(context)
            else Intent(context, AiSettingsActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val stop = PendingIntent.getService(context, 81, Intent(context, AnswerService::class.java).setAction(AnswerService.DISARM),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try { context.getSystemService(NotificationManager::class.java).notify(12,
            Notification.Builder(context, ProbeNotifications.STATUS_CHANNEL).setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("雨课堂自动作答" + if (ticket?.live == false) " · 演练" else "")
                .setContentText(message).setOngoing(armed || ticket != null).setOnlyAlertOnce(true)
                .setContentIntent(open).addAction(Notification.Action.Builder(null, "停止自动模式", stop).build())
                .setVisibility(Notification.VISIBILITY_PRIVATE).build()) } catch (_: SecurityException) { }
    }
}
