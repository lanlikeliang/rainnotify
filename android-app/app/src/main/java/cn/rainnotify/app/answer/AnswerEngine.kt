package cn.rainnotify.app.answer

import cn.rainnotify.app.monitor.ProblemTiming
import cn.rainnotify.app.question.QuestionContent

enum class AnswerPhase { PREPARING, SOLVING, CHECKING, SUBMITTING, SUBMITTED, ALREADY_ANSWERED,
    REHEARSED, CANCELLED, FAILED, UNCERTAIN }
class AnswerResult(val phase: AnswerPhase, val message: String, val answer: List<String> = emptyList())

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
    fun submit(answer: List<String>, timeoutMs: Long): SubmitOutcome
}

/** Shared live/rehearsal coordinator. A rehearsal never has a submission gateway. */
class AnswerEngine(private val clock: () -> Long, private val journal: SubmissionJournal) {
    fun run(task: String, question: QuestionContent, startedAt: Long, control: RunControl,
        gateway: AnswerGateway?, submissionKey: String?,
        solve: (Long) -> String, progress: (AnswerPhase, String) -> Unit,
        comparison: (String, List<String>, Boolean) -> Unit = { _, _, _ -> }): AnswerResult {
        var answer = emptyList<String>()
        var deadline = startedAt + 85_000
        fun check(extra: Long = 0) {
            control.check()
            if (clock() + extra >= deadline) throw AnswerFailure("本题剩余时间不足，未提交答案。")
            if (gateway != null && !gateway.current()) throw AnswerFailure("本题课堂已断开、收题或监听已切换，未继续提交。")
        }
        fun fresh(stage: String) {
            val (current, answered) = gateway!!.refresh()
            check()
            if (answered == null) throw AnswerFailure("暂无法确认本账号是否已作答；请在微信核对。")
            if (answered) throw AlreadyAnswered()
            val differences = QuestionIdentity.differences(question, current)
            val transportChanged = question.assets.map { it.url } != current.assets.map { it.url }
            // Diagnostics must never affect a submission decision.
            try { comparison(stage, differences, transportChanged) } catch (_: Exception) { }
            if (differences.isNotEmpty())
                throw AnswerFailure("题目或选项发生变化，请重新查看；未提交旧答案。")
        }
        try {
            AnswerProtocol.validateQuestion(question, gateway != null)
            check()
            if (gateway != null) {
                requireNotNull(submissionKey)
                if (journal.used(submissionKey)) throw AnswerFailure("本题已有提交尝试，请在微信核对，不会重复发送。")
                fresh("before_solve")
                val time = gateway.timing()
                deadline = minOf(deadline, time.deadline ?: deadline)
                check(12_000)
            }
            progress(AnswerPhase.SOLVING, "正在发送完整题目并等待 AI 最终答案…")
            val response = solve(minOf(60_000, deadline - clock() - if (gateway != null) 12_000 else 1_000))
            check()
            answer = AnswerProtocol.parse(response, task, question)
            progress(AnswerPhase.CHECKING, "已取得 AI 答案，正在核对题目状态…")
            if (gateway == null) return AnswerResult(AnswerPhase.REHEARSED, "演练完成；答案格式已校验，未向雨课堂提交。", answer)
            fresh("before_submit")
            val time = gateway.timing()
            deadline = minOf(startedAt + 85_000, time.deadline ?: deadline)
            check(4_000)
            journal.reserve(submissionKey!!, task)
            check(4_000)
            control.beginSubmission()
            progress(AnswerPhase.SUBMITTING, "正在提交本题；请求发出后无法撤回，请等待回执。")
            val outcome = try { gateway.submit(answer, minOf(8_000, deadline - clock() - 1_000)) }
                catch (_: Exception) { SubmitOutcome.UNKNOWN }
            // A failed journal update cannot turn a known successful server receipt into a retry.
            try { journal.complete(submissionKey, outcome) } catch (_: Exception) { }
            return when (outcome) {
                SubmitOutcome.ACCEPTED -> AnswerResult(AnswerPhase.SUBMITTED, "雨课堂已返回提交成功回执；请在微信核对首次实测结果。", answer)
                SubmitOutcome.ALREADY_ANSWERED -> AnswerResult(AnswerPhase.ALREADY_ANSWERED, "雨课堂提示本题已有作答；不代表本次 AI 答案已被采用。", answer)
                SubmitOutcome.REJECTED -> AnswerResult(AnswerPhase.FAILED, "雨课堂未接受本次提交；请在微信查看，不会自动重发。", answer)
                SubmitOutcome.UNKNOWN -> AnswerResult(AnswerPhase.UNCERTAIN, "提交结果待核对：请求可能已送达。请到微信查看，不会自动重发。", answer)
            }
        } catch (_: AlreadyAnswered) {
            return AnswerResult(AnswerPhase.ALREADY_ANSWERED, "本账号已作答，本次流程已停止。")
        } catch (e: Exception) {
            return AnswerResult(if (control.cancelled) AnswerPhase.CANCELLED else AnswerPhase.FAILED,
                (e as? AnswerFailure)?.display ?: "本题流程未完成，请查看设置或稍后重试。", answer)
        }
    }
    private class AlreadyAnswered : Exception()
}
