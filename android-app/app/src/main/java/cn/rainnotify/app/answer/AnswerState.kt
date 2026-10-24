package cn.rainnotify.app.answer

import android.content.Context
import androidx.lifecycle.MutableLiveData
import android.os.Handler
import android.os.Looper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import android.util.AtomicFile

class AnswerTask(val id: String, val questionId: String, val live: Boolean, val phase: AnswerPhase,
    val message: String, val answer: List<String> = emptyList(), val startedAt: Long = System.currentTimeMillis()) {
    val active get() = phase in setOf(AnswerPhase.PREPARING, AnswerPhase.SOLVING, AnswerPhase.CHECKING, AnswerPhase.SUBMITTING)
    fun changed(phase: AnswerPhase, message: String, answer: List<String> = this.answer) =
        AnswerTask(id, questionId, live, phase, message, answer, startedAt)
    override fun toString() = "AnswerTask(redacted)"
}

object AnswerState {
    val task = MutableLiveData<AnswerTask?>(null)
    private val main = Handler(Looper.getMainLooper())
    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, "last-answer-task.json"))
    @Synchronized fun publish(context: Context, value: AnswerTask) {
        val storage = file(context)
        val stream = storage.startWrite()
        try {
            stream.write(JSONObject().put("id", value.id).put("question", value.questionId).put("live", value.live)
                .put("phase", value.phase.name).put("message", value.message).put("answer", JSONArray(value.answer))
                .put("started", value.startedAt).toString().toByteArray(Charsets.UTF_8))
            storage.finishWrite(stream)
        } catch (e: Exception) { storage.failWrite(stream); throw e }
        main.post { task.value = value }
    }
    @Synchronized fun restore(context: Context) {
        if (task.value != null) return
        val saved = try {
            val data = JSONObject(file(context).readFully().toString(Charsets.UTF_8))
            val phase = AnswerPhase.valueOf(data.getString("phase"))
            val answers = data.getJSONArray("answer")
            AnswerTask(data.getString("id"), data.getString("question"), data.getBoolean("live"), phase,
                data.getString("message"), (0 until answers.length()).map { answers.getString(it) }, data.getLong("started"))
        } catch (_: Exception) { return }
        val recovered = when {
            saved.phase == AnswerPhase.SUBMITTING -> saved.changed(AnswerPhase.UNCERTAIN, "上次提交时流程被中断；请到微信核对，不会重发。")
            saved.active -> saved.changed(AnswerPhase.FAILED, "上次流程已中断，没有自动恢复。")
            else -> saved
        }
        task.value = recovered
    }
}
