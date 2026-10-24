package cn.rainnotify.app.monitor

import cn.rainnotify.app.answer.AnswerFailure
import org.json.JSONObject

class ProblemTiming(val problemId: String, val requestId: String, val deadline: Long?, val observedAt: Long) {
    fun remaining(now: Long): Long = deadline?.minus(now)?.coerceAtLeast(0) ?: Long.MAX_VALUE
    override fun toString() = "ProblemTiming(redacted)"
    companion object {
        fun parse(root: JSONObject, requestedAt: Long, receivedAt: Long): ProblemTiming {
            fun invalid(): Nothing = throw AnswerFailure("题目计时格式尚未识别；本题未提交。")
            val problem = LessonProtocol.id(root.opt("problemid")) ?: invalid()
            val request = LessonProtocol.id(root.opt("msgid")) ?: invalid()
            val limit = root.opt("limit")?.toString()?.toLongOrNull() ?: invalid()
            if (limit !in -1..14_400 || receivedAt < requestedAt) invalid()
            if (limit == -1L) return ProblemTiming(problem, request, null, receivedAt)
            val now = root.opt("now")?.toString()?.toLongOrNull() ?: invalid()
            val start = root.opt("dt")?.toString()?.toLongOrNull() ?: invalid()
            if (now < 946_684_800_000L || start < 946_684_800_000L || now < start ||
                now - start > 86_400_000L) invalid()
            val remaining = (limit * 1000 - (now - start) - (receivedAt - requestedAt)).coerceAtLeast(0)
            return ProblemTiming(problem, request, receivedAt + remaining, receivedAt)
        }
    }
}
