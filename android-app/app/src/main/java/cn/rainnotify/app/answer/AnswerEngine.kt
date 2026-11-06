package cn.rainnotify.app.answer

import cn.rainnotify.app.monitor.ProblemTiming
import cn.rainnotify.app.question.QuestionContent

enum class AnswerPhase { QUEUED, PREPARING, SOLVING, CHECKING, SUBMITTING, SUBMITTED, ALREADY_ANSWERED,
    REHEARSED, CANCELLED, FAILED, UNCERTAIN }
class AnswerResult(val phase: AnswerPhase, val message: String, val answer: List<String> = emptyList(),
    val submissionAttempted: Boolean = false)
enum class ImageComparison { MATCH, DIFFERENT, UNAVAILABLE }

class RunControl {
    @Volatile var cancelled = false; private set
    @Volatile var submitting = false; private set
    private var cancellation: (() -> Unit)? = null
    @Synchronized fun cancel(): Boolean {
        if (submitting) return false
        cancelled = true
        cancellation?.invoke()
        return true
    }
    @Synchronized fun onCancel(action: (() -> Unit)?) {
        cancellation = action
        if (cancelled) action?.invoke()
    }
    fun check() { if (cancelled) throw AnswerFailure("已停止本题，未提交答案。") }
    @Synchronized fun beginSubmission() { check(); submitting = true }
}

interface AnswerGateway {
    fun current(): Boolean
    fun timing(): ProblemTiming
    /** null means the server schema cannot confirm whether this account has already answered. */
    fun refresh(): Pair<QuestionContent, Boolean?>
    fun compareImages(current: QuestionContent, timeoutMs: Long): ImageComparison = ImageComparison.UNAVAILABLE
    fun submit(answer: List<String>, timeoutMs: Long): SubmitOutcome
}


/** Generate a reference first. Only automatic submission requires refreshed content and timing. */
class AnswerEngine(private val clock: () -> Long, private val journal: SubmissionJournal) {
    fun run(task: String, question: QuestionContent, startedAt: Long, control: RunControl,
        gateway: AnswerGateway?, submissionKey: String?,
        solve: (Long) -> String, progress: (AnswerPhase, String) -> Unit,
        comparison: (String, List<String>, Boolean) -> Unit = { _, _, _ -> },
        referenceReady: (List<String>) -> Unit = {}): AnswerResult {
        var answer = emptyList<String>()
        val generationDeadline = startedAt + 85_000
        fun check(deadline: Long, extra: Long = 0) {
            control.check()
            if (clock() + extra >= deadline) throw AnswerFailure("本题剩余时间不足，已保留参考答案，可核对后手动提交。")
        }
        fun checkTarget() {
            if (gateway != null && !gateway.current())
                throw AnswerFailure("课堂连接、题目状态或自动答题资格已改变，已保留参考答案。")
        }
        try {
            AnswerProtocol.validateQuestion(question, false)
            check(generationDeadline, 1_000)
            progress(AnswerPhase.SOLVING, "正在发送完整题目并等待 AI 最终答案…")
            val response = solve(minOf(60_000, generationDeadline - clock() - 1_000))
            answer = AnswerProtocol.parse(response, task, question)
            referenceReady(answer)
            progress(AnswerPhase.CHECKING, "参考答案已生成，正在检查自动提交条件…")
            check(generationDeadline)
            if (gateway == null) return AnswerResult(AnswerPhase.REHEARSED, "参考答案已生成。", answer)
            AnswerProtocol.validateAnswer(question, answer, true)
            val key = requireNotNull(submissionKey)
            if (journal.used(key)) return AnswerResult(AnswerPhase.FAILED,
                "本题已有提交尝试，请在雨课堂核对结果。参考答案已保留。", answer, true)
            checkTarget()
            val (current, answered) = gateway.refresh()
            check(generationDeadline)
            checkTarget()
            val differences = QuestionIdentity.differences(question, current).toMutableList()
            if (differences.isEmpty() && question.assets.isNotEmpty()) {
                val budget = minOf(6_000, generationDeadline - clock() - 5_000)
                val images = if (budget > 0) gateway.compareImages(current, budget) else ImageComparison.UNAVAILABLE
                if (images == ImageComparison.DIFFERENT) differences.add("image_content")
            }
            try { comparison("before_submit", differences, question.assets.map { it.url } != current.assets.map { it.url }) }
            catch (_: Exception) { /* Diagnostics cannot affect the decision. */ }
            if (differences.isNotEmpty()) throw AnswerFailure("题目内容与参考答案所依据的题目不同，已保留答案，请核对后手动提交。")
            if (answered == null) throw AnswerFailure("暂无法确认本账号是否已作答，已保留参考答案，可核对后手动提交。")
            if (answered) return AnswerResult(AnswerPhase.ALREADY_ANSWERED, "雨课堂显示本账号已作答，参考答案已保留。", answer)
            val timing = gateway.timing()
            val deadline = minOf(generationDeadline, timing.deadline ?: generationDeadline)
            check(deadline, 4_000)
            checkTarget()
            return submit(task, question, answer, control, gateway, key,
                minOf(8_000, deadline - clock() - 1_000), progress)
        } catch (e: Exception) {
            return failure(e, control, answer)
        }
    }

    /** Explicit user confirmation bypasses refresh, image comparison, timing and answered-state checks. */
    fun submitConfirmed(task: String, question: QuestionContent, answer: List<String>, control: RunControl,
        gateway: AnswerGateway, submissionKey: String, progress: (AnswerPhase, String) -> Unit): AnswerResult =
        submit(task, question, answer, control, gateway, submissionKey, 8_000, progress)

    private fun submit(task: String, question: QuestionContent, answer: List<String>, control: RunControl,
        gateway: AnswerGateway, key: String, timeoutMs: Long, progress: (AnswerPhase, String) -> Unit): AnswerResult {
        var reserved = false
        try {
            control.check()
            AnswerProtocol.validateAnswer(question, answer, true)
            if (!gateway.current()) throw AnswerFailure("账号、服务器或课堂已切换，请重新连接对应课堂后提交。")
            if (journal.used(key)) return AnswerResult(AnswerPhase.FAILED,
                "本题已有提交尝试，请在雨课堂核对结果。参考答案已保留。", answer, true)
            journal.reserve(key, task)
            reserved = true
            control.beginSubmission()
            progress(AnswerPhase.SUBMITTING, "正在提交本题，请等待雨课堂回执。")
            val outcome = try { gateway.submit(answer, timeoutMs) } catch (_: Exception) { SubmitOutcome.UNKNOWN }
            try { journal.complete(key, outcome) } catch (_: Exception) { }
            return when (outcome) {
                SubmitOutcome.ACCEPTED -> AnswerResult(AnswerPhase.SUBMITTED, "雨课堂已返回提交成功回执。", answer, true)
                SubmitOutcome.ALREADY_ANSWERED -> AnswerResult(AnswerPhase.ALREADY_ANSWERED, "雨课堂返回本题已有作答，请查看已记录的答案。", answer, true)
                SubmitOutcome.REJECTED -> AnswerResult(AnswerPhase.FAILED, "雨课堂未接受本次提交，请到雨课堂查看。参考答案已保留。", answer, true)
                SubmitOutcome.UNKNOWN -> AnswerResult(AnswerPhase.UNCERTAIN, "提交结果待核对，请到雨课堂查看回执。参考答案已保留。", answer, true)
            }
        } catch (e: Exception) {
            return failure(e, control, answer, reserved)
        }
    }

    private fun failure(e: Exception, control: RunControl, answer: List<String>, reserved: Boolean = false) =
        AnswerResult(if (control.cancelled) AnswerPhase.CANCELLED else AnswerPhase.FAILED,
            (e as? AnswerFailure)?.display ?: if (answer.isEmpty()) "参考答案生成失败，请查看配置和网络。"
                else "自动提交检查未完成，已保留参考答案，可核对后手动提交。", answer, reserved)
}
