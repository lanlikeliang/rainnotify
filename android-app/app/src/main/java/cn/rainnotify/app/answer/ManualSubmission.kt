package cn.rainnotify.app.answer

import cn.rainnotify.app.auth.RainServer
import cn.rainnotify.app.question.QuestionRecord

/** Check only local routing and answer availability. No remote content/timing eligibility checks. */
object ManualSubmission {
    fun issue(record: QuestionRecord, task: AnswerTask?, server: RainServer?, accountScope: String?, lesson: String?): String? {
        if (record.offline || record.reference.groupWork) return "此题需要在雨课堂中完成。"
        if (task == null || task.questionId != record.id || task.answer.isEmpty()) return "等待参考答案生成。"
        if (task.active || task.phase == AnswerPhase.QUEUED) return "本题正在处理，请等待当前流程结束。"
        if (task.submissionAttempted) return "本题已有提交尝试，请到雨课堂核对回执。"
        if (record.server != server || record.accountScope.isBlank() || record.accountScope != accountScope ||
            record.reference.lessonId != lesson) return "请连接本题所属服务器、账号和课堂后提交。"
        val question = task.sourceQuestion ?: record.content ?: return "题目内容尚未获取。"
        return try { AnswerProtocol.validateAnswer(question, task.answer, true); null }
        catch (e: AnswerFailure) { e.display }
    }
}
