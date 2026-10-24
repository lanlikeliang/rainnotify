package cn.rainnotify.app.monitor

import cn.rainnotify.app.auth.FailureReason
import cn.rainnotify.app.auth.RainFailure
import cn.rainnotify.app.auth.RainProtocol
import cn.rainnotify.app.question.QuestionRef
import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

class LessonCredentials(val lessonId: String, val identityId: Any, val token: String) {
    override fun toString() = "LessonCredentials(redacted)"
}

object LessonProtocol {
    private fun invalid(): Nothing = throw RainFailure(FailureReason.RESPONSE_FORMAT, "lesson_protocol")

    fun id(value: Any?): String? = when (value) {
        is String, is Number -> value.toString().takeIf { it.matches(Regex("[A-Za-z0-9_-]{1,128}")) }
        else -> null
    }

    fun checkinBody(lessonId: String): JSONObject {
        if (id(lessonId) == null) invalid()
        // Official classroom route leaves lessonID as a string, even when it contains digits.
        return JSONObject().put("source", 5).put("lessonId", lessonId)
    }

    fun credentials(root: JSONObject, lessonId: String): LessonCredentials {
        if (root.optString("msg") == "LESSON_END") throw RainFailure(FailureReason.LESSON_ENDED, "lesson_checkin")
        if (root.has("code")) RainProtocol.requireSuccess(root, "lesson_checkin")
        else if (root.optString("msg") != "OK") throw RainFailure(FailureReason.RESPONSE_REJECTED, "lesson_checkin")
        val data = root.optJSONObject("data") ?: invalid()
        val identity = data.opt("identityId")
        if (id(identity) == null) invalid()
        val token = data.opt("lessonToken") as? String
        if (token.isNullOrBlank() || token.length > 8192) invalid()
        return LessonCredentials(lessonId, identity!!, token)
    }

    fun hello(credentials: LessonCredentials): String = JSONObject().put("op", "hello")
        .put("userid", credentials.identityId).put("role", "student").put("auth", credentials.token)
        .put("lessonid", credentials.lessonId).toString()
}

sealed interface LessonSignal {
    data object Ready : LessonSignal
    data object Snapshot : LessonSignal
    data object Extension : LessonSignal
    data object Duplicate : LessonSignal
    data object Finished : LessonSignal
    data object Other : LessonSignal
    data object Unsupported : LessonSignal
    class ProblemClosed(val problemId: String) : LessonSignal
    class TimeChanged(val problemId: String) : LessonSignal
    class Question(val fingerprint: String, val reference: QuestionRef) : LessonSignal
}

/** Candidate event contract from the public student receiver, pending a real classroom trace.
 * A presentation/problem pair alerts once per listening session, including reconnects.
 * Re-publishing the same problem cannot yet be distinguished from server replay.
 */
class LessonEvents(private val lessonId: String) {
    private var presentation: String? = null
    private val seen = LinkedHashSet<String>()
    private var ready = false

    fun disconnected() { ready = false; presentation = null }

    fun receive(text: String): LessonSignal {
        val root = RainProtocol.parseObject(text, "lesson_socket")
        if (root.has("code") && root.opt("code")?.toString() != "0") {
            throw RainFailure(FailureReason.RESPONSE_REJECTED, "lesson_socket")
        }
        return when (root.optString("op")) {
            "hello", "fetchtimeline" -> {
                val timeline = root.optJSONArray("timeline") ?: return LessonSignal.Unsupported
                readSnapshot(root, timeline)
                if (root.optString("op") == "hello") { ready = true; LessonSignal.Ready }
                else LessonSignal.Snapshot
            }
            "showpresentation", "presentationupdated", "presentationcreated", "showfinished" -> {
                LessonProtocol.id(root.opt("presentation"))?.let { presentation = it }
                LessonSignal.Other
            }
            "slidenav" -> {
                LessonProtocol.id(root.optJSONObject("slide")?.opt("pres"))?.let { presentation = it }
                LessonSignal.Other
            }
            "unlockproblem" -> {
                if (!ready) return LessonSignal.Unsupported
                val problem = root.optJSONObject("problem") ?: return LessonSignal.Unsupported
                val key = key(problem) ?: return LessonSignal.Unsupported
                if (remember(key)) LessonSignal.Question(key, QuestionRef(lessonId, presentation!!,
                    LessonProtocol.id(problem.opt("prob"))!!,
                    LessonProtocol.id(problem.opt("sid"))?.takeUnless { it == "0" },
                    problem.optInt("si", 0).takeIf { it > 0 },
                    LessonProtocol.id(problem.opt("groupid"))?.let { it != "0" } == true)) else LessonSignal.Duplicate
            }
            "extendtime" -> {
                val problem = root.optJSONObject("problem")
                val id = LessonProtocol.id(problem?.opt("prob"))
                if (id != null && problem?.has("limit") == true) LessonSignal.TimeChanged(id) else LessonSignal.Extension
            }
            "problemfinished" -> LessonProtocol.id(root.opt("prob"))?.let { LessonSignal.ProblemClosed(it) }
                ?: LessonSignal.Unsupported
            "lessonfinished" -> LessonSignal.Finished
            else -> LessonSignal.Other
        }
    }

    private fun readSnapshot(root: JSONObject, timeline: JSONArray) {
        // Never alert for history. Only seed identifiable unlock entries; unknown shapes stay ignored.
        for (i in 0 until timeline.length()) {
            val item = timeline.optJSONObject(i) ?: continue
            LessonProtocol.id(item.opt("pres"))?.let { presentation = it }
            if (item.optString("op") == "unlockproblem") {
                key(item.optJSONObject("problem") ?: item)?.let { remember(it) }
            }
        }
        LessonProtocol.id(root.opt("presentation"))?.let { presentation = it }
    }

    private fun key(problem: JSONObject): String? {
        val pres = LessonProtocol.id(problem.opt("pres")) ?: presentation ?: return null
        val prob = LessonProtocol.id(problem.opt("prob")) ?: return null
        presentation = pres
        return MessageDigest.getInstance("SHA-256").digest(
            (lessonId + ":" + pres + ":" + prob).toByteArray(Charsets.UTF_8)
        ).joinToString("") { "%02x".format(it) }
    }

    private fun remember(key: String): Boolean {
        val added = seen.add(key)
        if (seen.size > 2048) seen.remove(seen.first())
        return added
    }
}
