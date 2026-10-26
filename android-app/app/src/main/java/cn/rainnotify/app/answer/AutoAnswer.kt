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
        policy.arm(UUID.randomUUID().toString())
        status.value = "自动答题已开启，仅处理本次课堂之后收到的新题。"
        return true
    }
    fun disarm(context: Context) {
        val running = claimed && ticket != null
        policy.disarm(); binding = null; claimed = false
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
        val token = policy.offer(UUID.randomUUID().toString(), id, policy.session, AnswerState.task.value?.active == true)
        if (token == null) {
            status.value = "上一题仍在处理，本题仅通知，未自动作答。"
            return
        }
        this.context = context.applicationContext as Application
        claimed = false
        status.value = "正在通过 DeepSeek API 作答，无需解锁。"
        wake = context.getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
            "rainnotify:api-start").apply { acquire(86_000) }
        Diagnostics.record(context, "auto_event", mapOf("event_id" to token.id, "source" to "real"))
        try {
            context.startForegroundService(Intent(context, AnswerService::class.java).setAction(AnswerService.START)
                .putExtra(AnswerService.QUESTION, id).putExtra(AnswerService.AUTO_TICKET, token.id))
        } catch (_: RuntimeException) {
            block(context, "系统未允许后台作答，请检查后台设置。")
            return
        }
        main.postDelayed({ if (policy.ticket?.id == token.id) {
            if (claimed) context.startService(Intent(context, AnswerService::class.java).setAction(AnswerService.STOP))
            finish(token.id)
            status.value = "本题自动流程已超时，不会补交。"
        } }, 85_000)
    }
    fun valid(id: String): Boolean {
        val current = policy.ticket ?: return false
        return current.live && policy.valid(id) &&
            context?.let { QuestionRepository.get(it).activeBinding(current.question) === binding } == true
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
        status.value = if (armed) "等待下一题。" else ""
    }
    private fun releaseWake() { if (wake?.isHeld == true) wake?.release(); wake = null }
}
