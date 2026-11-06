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
    val message: String, val answer: List<String> = emptyList(), val startedAt: Long = System.currentTimeMillis(),
    val model: DeepSeekModel = DeepSeekModel.FLASH,
    val sourceQuestion: cn.rainnotify.app.question.QuestionContent? = null,
    val submissionAttempted: Boolean = phase in setOf(AnswerPhase.SUBMITTING, AnswerPhase.SUBMITTED, AnswerPhase.UNCERTAIN)) {
    val active get() = phase in setOf(AnswerPhase.PREPARING, AnswerPhase.SOLVING, AnswerPhase.CHECKING, AnswerPhase.SUBMITTING)
    fun changed(phase: AnswerPhase, message: String, answer: List<String> = this.answer,
        sourceQuestion: cn.rainnotify.app.question.QuestionContent? = this.sourceQuestion,
        submissionAttempted: Boolean = this.submissionAttempted || phase == AnswerPhase.SUBMITTING) =
        AnswerTask(id, questionId, live, phase, message, answer, startedAt, model, sourceQuestion, submissionAttempted)
    fun json(): JSONObject = JSONObject().put("id", id).put("question", questionId).put("live", live)
        .put("phase", phase.name).put("message", message).put("answer", JSONArray(answer))
        .put("model", model.id).put("started", startedAt).put("source_question", sourceQuestion?.json())
        .put("submission_attempted", submissionAttempted)
    companion object {
        fun fromJson(data: JSONObject): AnswerTask {
            val answers = data.getJSONArray("answer")
            return AnswerTask(data.getString("id"), data.getString("question"), data.getBoolean("live"),
                AnswerPhase.valueOf(data.getString("phase")), data.getString("message"),
                (0 until answers.length()).map { answers.getString(it) }, data.getLong("started"),
                DeepSeekModel.fromId(data.optString("model")),
                data.optJSONObject("source_question")?.let { cn.rainnotify.app.question.QuestionContent.fromJson(it) },
                data.optBoolean("submission_attempted", data.getString("phase") in setOf("SUBMITTING", "SUBMITTED", "UNCERTAIN")))
        }
    }
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
            stream.write(value.json().toString().toByteArray(Charsets.UTF_8))
            storage.finishWrite(stream)
        } catch (e: Exception) { storage.failWrite(stream); throw e }
        cn.rainnotify.app.question.QuestionRepository.get(context).saveAnswer(value)
        main.post { task.value = value }
    }
    @Synchronized fun restore(context: Context) {
        if (task.value != null) return
        val saved = try {
            val data = JSONObject(file(context).readFully().toString(Charsets.UTF_8))
            AnswerTask.fromJson(data)
        } catch (_: Exception) { return }
        val recovered = when {
            saved.phase == AnswerPhase.SUBMITTING -> saved.changed(AnswerPhase.UNCERTAIN, "上次提交时流程被中断；请到微信核对，不会重发。")
            saved.active -> saved.changed(AnswerPhase.FAILED, "上次流程已中断，没有自动恢复。")
            else -> saved
        }
        task.value = recovered
        cn.rainnotify.app.question.QuestionRepository.get(context).saveAnswer(recovered)
    }
}
