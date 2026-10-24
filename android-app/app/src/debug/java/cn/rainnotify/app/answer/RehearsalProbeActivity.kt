package cn.rainnotify.app.answer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import cn.rainnotify.app.question.QuestionRepository

/** Shell-only, debug-only probe. It cannot construct a live submission request. */
class RehearsalProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (intent.getBooleanExtra("stop", false)) {
            startService(Intent(this, AnswerService::class.java).setAction(AnswerService.STOP)); finish(); return
        }
        val provider = AiProvider.entries.firstOrNull { it.name == intent.getStringExtra("provider") } ?: AiProvider.KIMI
        AiProvider.select(this, provider)
        val verifyType = intent.getIntExtra("verify_type", 0)
        if (verifyType in CompatibilityPolicy.requiredTypes || intent.getBooleanExtra("compatibility_only", false)) {
            startActivity(Intent(this, AiCompatibilityActivity::class.java).putExtra("provider", provider.name)
                .putExtra("debug_verify_type", verifyType))
            finish(); return
        }
        QuestionRepository.get(this).records.observe(this) { records ->
            val record = records.firstOrNull { it.content != null && !it.loading &&
                (intent.getStringExtra("question") == null || it.id == intent.getStringExtra("question")) } ?: return@observe
            if (!isFinishing) {
                val delayed = intent.getBooleanExtra("delayed", false)
                if (delayed) startForegroundService(Intent(this, AnswerService::class.java).setAction(AnswerService.SIMULATE)
                    .putExtra(AnswerService.QUESTION, record.id))
                startActivity(Intent(this, AnswerActivity::class.java).putExtra(AnswerService.QUESTION, record.id)
                    .putExtra(AnswerService.LIVE, false).putExtra(AnswerActivity.AUTO, !delayed && !intent.getBooleanExtra("open_only", false)))
                finish()
            }
        }
    }
}
