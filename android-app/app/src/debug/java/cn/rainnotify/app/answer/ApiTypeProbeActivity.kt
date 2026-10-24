package cn.rainnotify.app.answer

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import cn.rainnotify.app.question.*
import java.io.File
import java.util.UUID

/** Shell-only synthetic exercises, always offline. No caller-provided prompt, key or live flag. */
class ApiTypeProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!SolverSettings.isApi(this) || AnswerState.task.value?.active == true || AutoAnswer.armed || AutoAnswer.ticket != null) { finish(); return }
        val type = intent.getIntExtra("sample_type", 0)
        val content = when (type) {
            3 -> QuestionContent(3, "请选择属于中国古代四大发明的两项。本题按客观知识作答，不涉及个人意愿。",
                listOf(QuestionOption("A", null, "造纸术"), QuestionOption("B", null, "蒸汽机"), QuestionOption("C", null, "指南针")),
                emptyList(), null, emptyList(), pollingCount = 2)
            4 -> QuestionContent(4, "按顺序填两个空：《史记》的作者是（1）；《资治通鉴》的作者是（2）。只写姓名。",
                emptyList(), emptyList(), null, emptyList(), blankCount = 2)
            5 -> QuestionContent(5, "用一句话说明：研究历史时，为什么需要交叉核对不同来源的史料？",
                emptyList(), emptyList(), null, emptyList())
            else -> { finish(); return }
        }
        val id = UUID.randomUUID().toString()
        QuestionFiles(File(noBackupFilesDir, "questions-v1")).write(QuestionRecord(id,
            QuestionRef("offline", "offline", id), System.currentTimeMillis(), content, "本地题型练习（不提交）", false, offline = true))
        QuestionRepository.get(this).reloadLocal()
        startActivity(Intent(this, AnswerActivity::class.java).putExtra(AnswerService.QUESTION, id)
            .putExtra(AnswerService.LIVE, false).putExtra(AnswerActivity.AUTO, true))
        finish()
    }
}
