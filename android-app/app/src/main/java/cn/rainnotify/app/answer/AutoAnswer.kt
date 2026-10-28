package cn.rainnotify.app.answer

import android.content.Context
import android.app.Application
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import androidx.lifecycle.MutableLiveData
import cn.rainnotify.app.Diagnostics
import cn.rainnotify.app.question.QuestionRepository
import java.util.UUID

/** Main-thread controller. There is no offline/AI-app entry point in the product. */
object AutoAnswer {
    private val policy = AutoPolicy(SystemClock::elapsedRealtime)
    private val pending = PendingAnswers()
    private var drainScheduled = false
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var binding: QuestionRepository.Binding? = null
    private var wake: PowerManager.WakeLock? = null
    private var context: Application? = null
    var claimed = false; private set
    val status = MutableLiveData("")
    val armed get() = policy.session != null
    val ticket get() = policy.ticket
    fun arm(context: Context): Boolean {
        if (cn.rainnotify.app.monitor.MonitorState.stopRequested ||
            cn.rainnotify.app.monitor.MonitorState.current().connection != cn.rainnotify.app.monitor.ConnectionState.LISTENING) return false
        val current = QuestionRepository.get(context).currentBinding() ?: return false
        if (AiReadiness.issue(context) != null || AnswerState.task.value?.active == true || ticket != null) return false
        this.context = context.applicationContext as Application
        binding = current
        pending.clear()
        policy.arm(UUID.randomUUID().toString())
        status.value = "自动答题已开启，仅处理本次课堂之后收到的新题。"
        return true
    }
    fun disarm(context: Context) {
        cn.rainnotify.app.monitor.MonitorState.automaticRequested = false
        val running = claimed && ticket != null
        pending.clear().forEach { recordSkipped(context, it, AnswerPhase.CANCELLED, "已关闭自动答题，未继续处理。") }
        policy.disarm(); binding = null; claimed = false; drainScheduled = false
        main.removeCallbacksAndMessages(null)
        releaseWake()
        status.value = ""
        if (running) context.startService(Intent(context, AnswerService::class.java).setAction(AnswerService.STOP))
    }
    fun block(context: Context, message: String) {
        disarm(context)
        status.value = "已切回发题通知：$message"
    }
    fun onQuestion(context: Context, id: String) {
        if (!armed) return
        if (QuestionRepository.get(context).activeBinding(id) !== binding) { disarm(context); return }
        AiReadiness.issue(context)?.let { block(context, it); return }
        this.context = context.applicationContext as Application
        val entry = PendingAnswers.Entry(UUID.randomUUID().toString(), id, SystemClock.elapsedRealtime())
        if (!pending.offer(entry)) return
        holdWake(context)
        recordSkipped(context, entry, AnswerPhase.QUEUED, "已接收，等待前题完成；开始前会核对剩余时间。")
        drain()
    }
    private fun recordSkipped(context: Context, entry: PendingAnswers.Entry, phase: AnswerPhase, message: String) {
        QuestionRepository.get(context).saveAnswer(AnswerTask(entry.taskId, entry.question, true, phase, message,
            model = SolverSettings.model(context)))
        if (phase == AnswerPhase.FAILED) try {
            val open = android.app.PendingIntent.getActivity(context, 50,
                Intent(context, cn.rainnotify.app.MainActivity::class.java).putExtra(cn.rainnotify.app.MainActivity.TAB,"classroom"),
                android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
            context.getSystemService(android.app.NotificationManager::class.java).notify("answer-"+entry.taskId,9,
                android.app.Notification.Builder(context,cn.rainnotify.app.ProbeNotifications.QUESTION_CHANNEL)
                    .setSmallIcon(android.R.drawable.ic_dialog_info).setContentTitle("本题未自动提交")
                    .setContentText(message).setContentIntent(open).setAutoCancel(true)
                    .setVisibility(android.app.Notification.VISIBILITY_PRIVATE).build())
        } catch (_: SecurityException) { }
    }
    private fun holdWake(context: Context) {
        releaseWake()
        wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "rainnotify:api-start").apply { acquire(86_000) }
    }
    private fun scheduleDrain() {
        if (drainScheduled || !armed || pending.size == 0) return
        drainScheduled = true
        main.postDelayed({ drainScheduled = false; drain() }, 500)
    }
    private fun drain() {
        val context = context ?: return
        if (!armed || ticket != null) return
        if (AnswerState.task.value?.active == true) { scheduleDrain(); return }
        if (cn.rainnotify.app.monitor.MonitorState.current().connection != cn.rainnotify.app.monitor.ConnectionState.LISTENING) {
            scheduleDrain(); return
        }
        AiReadiness.issue(context)?.let { block(context, it); return }
        while (true) {
            val entry = pending.poll() ?: run { releaseWake(); return }
            if (SystemClock.elapsedRealtime() - entry.received >= 73_000) {
                recordSkipped(context, entry, AnswerPhase.FAILED, "排队后剩余时间不足，未提交；请在微信查看。")
                continue
            }
            if (QuestionRepository.get(context).activeBinding(entry.question) !== binding) {
                recordSkipped(context, entry, AnswerPhase.CANCELLED, "课堂已切换，未提交。")
                continue
            }
            val token = policy.offer(entry.taskId, entry.question, policy.session, false, entry.received) ?: continue
            claimed = false
            status.value = "正在通过 DeepSeek API 作答，无需解锁。"
            holdWake(context)
            Diagnostics.record(context, "auto_event", mapOf("event_id" to token.id, "source" to "real"))
            try {
                context.startForegroundService(Intent(context, AnswerService::class.java).setAction(AnswerService.START)
                    .putExtra(AnswerService.QUESTION, entry.question).putExtra(AnswerService.AUTO_TICKET, token.id))
            } catch (_: RuntimeException) {
                recordSkipped(context, entry, AnswerPhase.FAILED, "系统未允许后台作答，请检查后台设置。")
                block(context, "系统未允许后台作答，请检查后台设置。")
                return
            }
            main.postDelayed({ if (ticket?.id == token.id) {
                if (claimed) context.startService(Intent(context, AnswerService::class.java).setAction(AnswerService.STOP))
                else {
                    recordSkipped(context, entry, AnswerPhase.FAILED, "本题自动流程未启动，已超时。")
                    finish(token.id)
                }
            } }, (85_000 - (SystemClock.elapsedRealtime() - token.received)).coerceAtLeast(1))
            return
        }
    }
    fun valid(id: String): Boolean {
        val current = policy.ticket ?: return false
        return current.live && policy.valid(id) &&
            context?.let { QuestionRepository.get(it).activeBinding(current.question) === binding } == true
    }
    fun claim(id: String): Boolean {
        if (claimed || !valid(id)) return false
        claimed = true
        if (pending.size == 0) releaseWake()
        return true
    }
    fun finish(id: String) {
        if (ticket?.id != id) return
        policy.finish(id); claimed = false; if (pending.size == 0) releaseWake()
        status.value = ""
        scheduleDrain()
    }
    private fun releaseWake() { if (wake?.isHeld == true) wake?.release(); wake = null }
}
