package cn.rainnotify.app.answer

import android.app.KeyguardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import cn.rainnotify.app.Diagnostics

/** Lock-screen surface contains no question or account content. Android handles identity verification. */
class AutoGateActivity : ComponentActivity() {
    private val main = Handler(Looper.getMainLooper())
    private var requestedUnlock = false
    private var ticketId = ""
    private lateinit var text: TextView
    private val poll = object : Runnable {
        override fun run() { proceed(); if (!isFinishing) main.postDelayed(this, 500) }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ticketId = intent.getStringExtra("ticket").orEmpty()
        setShowWhenLocked(true); setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val layout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(36, 120, 36, 60) }
        text = TextView(this).apply { textSize = 20f; text = "有一道题等待自动作答。请完成系统解锁；解锁后自动继续。" }
        layout.addView(text)
        layout.addView(Button(this).apply { text = "取消本次自动流程"; setOnClickListener { AutoAnswer.disarm(this@AutoGateActivity); finish() } })
        setContentView(layout)
        // System credential UI may pause this Activity. Its expiry must still close our wake window.
        val remaining = AutoAnswer.ticket?.let { it.received + 85_000 - SystemClock.elapsedRealtime() } ?: 0
        main.postDelayed({ finish() }, remaining.coerceIn(0, 85_000))
        Diagnostics.record(this, "auto_gate_opened", mapOf("event_id" to ticketId))
    }
    override fun onResume() { super.onResume(); main.removeCallbacks(poll); main.post(poll) }
    override fun onPause() { main.removeCallbacks(poll); super.onPause() }
    override fun onDestroy() { main.removeCallbacksAndMessages(null); super.onDestroy() }
    private fun proceed() {
        if (isFinishing) return
        if (!AutoAnswer.valid(ticketId) || AutoAnswer.claimed) { finish(); return }
        val keyguard = getSystemService(KeyguardManager::class.java)
        if (keyguard.isKeyguardLocked) {
            if (!requestedUnlock) {
                requestedUnlock = true
                keyguard.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() {
                    override fun onDismissCancelled() { AutoAnswer.disarm(this@AutoGateActivity); finish() }
                    override fun onDismissSucceeded() { proceed() }
                    override fun onDismissError() { text.text = "请先手动解锁手机；本题仍受 85 秒总时限约束。" }
                })
            }
            return
        }
        val ticket = AutoAnswer.ticket ?: return
        // Claim occurs in AnswerService, after it has actually accepted this ticket.
        startActivity(Intent(this, AnswerActivity::class.java).putExtra(AnswerService.QUESTION, ticket.question)
            .putExtra(AnswerService.AUTO_TICKET, ticket.id).putExtra(AnswerActivity.AUTO, true))
        finish()
    }
}
