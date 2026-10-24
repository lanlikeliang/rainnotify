package cn.rainnotify.app.auth

import cn.rainnotify.app.BuildConfig
import cn.rainnotify.app.monitor.LessonCredentials
import cn.rainnotify.app.monitor.LessonProtocol
import cn.rainnotify.app.question.QuestionRef
import cn.rainnotify.app.question.QuestionContent
import cn.rainnotify.app.question.QuestionProtocol
import cn.rainnotify.app.answer.AnswerProtocol
import cn.rainnotify.app.answer.AnswerFailure
import cn.rainnotify.app.answer.SubmitOutcome

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

class RainApi(
    val cookies: RainCookieJar,
    private val origin: HttpUrl = RainProtocol.ORIGIN.toHttpUrl(),
    private val report: (String, Map<String, Any>) -> Unit = { _, _ -> },
) {
    // Official v3 client reuses Set-Auth from prior responses, only in this client session.
    @Volatile private var authorization: String? = null
    val client: OkHttpClient = OkHttpClient.Builder().cookieJar(cookies)
        .connectTimeout(15, TimeUnit.SECONDS).readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS).pingInterval(20, TimeUnit.SECONDS)
        .followRedirects(false).followSslRedirects(false).build()

    fun exchange(grant: LoginMessage.Grant) {
        val body = JSONObject().put("UserID", grant.userId).put("Auth", grant.auth)
        // Upstream checks response cookies, not a JSON body. Verify them via basic-info next.
        executeBytes(client, buildRequest("pc/web_login", body), "login_exchange")
        if (cookies.snapshot().isEmpty()) throw RainFailure(FailureReason.RESPONSE_FORMAT, "login_exchange")
    }

    fun validateSession() {
        val root = request("api/v3/user/basic-info", "session_check")
        RainProtocol.requireSuccess(root, "session_check")
    }

    fun displayName(): String? = try {
        val root = request("v2/api/web/userinfo", "profile")
        val data = root.opt("data")
        val item = when (data) {
            is JSONArray -> data.optJSONObject(0)
            is JSONObject -> data
            else -> null
        }
        (item?.opt("name") as? String)?.takeIf { it.isNotBlank() }?.take(100)
    } catch (_: RainFailure) { null }

    fun classrooms(): List<LessonSummary> =
        RainProtocol.lessons(request("api/v3/classroom/on-lesson-upcoming-exam", "classrooms"))

    /** Read-only fetch using the existing authorized session; never enters a lesson. */
    fun question(ref: QuestionRef): QuestionContent {
        return QuestionProtocol.parse(ref, questionResponse(ref))
    }

    class AnswerableQuestion(val content: QuestionContent, val alreadyAnswered: Boolean?)

    /** Read just our answer-presence flag. Never return reference answers or previous answer values. */
    fun answerableQuestion(ref: QuestionRef): AnswerableQuestion {
        val root = questionResponse(ref, 8_000)
        val content = QuestionProtocol.parse(ref, root)
        val slides = root.getJSONObject("data").getJSONArray("slides")
        val problem = (0 until slides.length()).mapNotNull { slides.optJSONObject(it)?.optJSONObject("problem") }
            .single { LessonProtocol.id(it.opt("problemId")) == ref.problemId }
        val answered = if (!problem.has("result")) null else !problem.isNull("result")
        return AnswerableQuestion(content, answered)
    }

    private fun questionResponse(ref: QuestionRef, timeoutMs: Long = 25_000): JSONObject {
        if (listOf(ref.lessonId, ref.presentationId, ref.problemId).any { LessonProtocol.id(it) == null }) {
            throw RainFailure(FailureReason.RESPONSE_FORMAT, "question_content")
        }
        val base = buildRequest("api/v3/lesson/presentation/fetch")
        val request = base.newBuilder()
            .url(base.url.newBuilder().addQueryParameter("presentation_id", ref.presentationId).build())
            .header("Referer", origin.resolve("lesson/fullscreen/v3/" + ref.lessonId + "?source=5").toString())
            .build()
        val reader = client.newBuilder().callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build()
        val bytes = executeBytes(reader, request, "question_content", QuestionProtocol.MAX_PRESENTATION_BYTES)
        val root = try { JSONObject(bytes.toString(Charsets.UTF_8)) }
            catch (_: Exception) { throw RainFailure(FailureReason.RESPONSE_FORMAT, "question_content") }
        return root
    }

    /** Exactly one HTTP attempt. Caller must durably reserve this question before calling. */
    fun submitAnswer(ref: QuestionRef, question: QuestionContent, answer: List<String>, timeoutMs: Long): SubmitOutcome {
        if (ref.groupWork || LessonProtocol.id(ref.problemId) == null || LessonProtocol.id(ref.lessonId) == null ||
            timeoutMs !in 1_000..8_000)
            throw AnswerFailure("提交参数未通过校验。")
        val body = JSONObject().put("problemId", ref.problemId).put("problemType", question.type)
            .put("dt", System.currentTimeMillis()).put("result", AnswerProtocol.submissionResult(question, answer))
        val request = buildRequest("api/v3/lesson/problem/answer", body).newBuilder()
            .header("Referer", origin.resolve("lesson/fullscreen/v3/" + ref.lessonId + "?source=5").toString()).build()
        val once = client.newBuilder().retryOnConnectionFailure(false).callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .followRedirects(false).followSslRedirects(false).build()
        return try {
            val value = RainProtocol.parseObject(executeBytes(once, request, "answer_submit").toString(Charsets.UTF_8),
                "answer_submit")
            when (value.opt("code")?.toString()?.toIntOrNull()) {
                0 -> SubmitOutcome.ACCEPTED
                50028 -> SubmitOutcome.ALREADY_ANSWERED
                null -> SubmitOutcome.UNKNOWN
                else -> SubmitOutcome.REJECTED
            }
        } catch (error: RainFailure) {
            when {
                error.businessCode == 50028 -> SubmitOutcome.ALREADY_ANSWERED
                error.httpStatus in listOf(400, 401, 403, 404, 409, 422) -> SubmitOutcome.REJECTED
                else -> SubmitOutcome.UNKNOWN
            }
        }
    }

    /** Call only after explicit user confirmation; never from a reconnect path. */
    fun enterLesson(lessonId: String): LessonCredentials {
        val body = LessonProtocol.checkinBody(lessonId)
        val request = buildRequest("api/v3/lesson/checkin", body).newBuilder()
            .header("Referer", origin.resolve("lesson/fullscreen/v3/" + lessonId + "?source=5").toString())
            .build()
        // A failed POST may already have recorded attendance. Do not retry it automatically.
        val once = client.newBuilder().retryOnConnectionFailure(false).build()
        val root = RainProtocol.parseObject(executeBytes(once, request, "lesson_checkin").toString(Charsets.UTF_8),
            "lesson_checkin")
        return LessonProtocol.credentials(root, lessonId)
    }

    fun image(url: HttpUrl): ByteArray {
        val safeUrl = RainProtocol.qrUrl(url.toString())
        // A separate client has no login cookie jar or request headers.
        val imageClient = client.newBuilder().cookieJar(okhttp3.CookieJar.NO_COOKIES).build()
        return executeBytes(imageClient, Request.Builder().url(safeUrl).build(), "qr_image")
    }

    fun cancel() { client.dispatcher.cancelAll() }

    private fun request(path: String, stage: String): JSONObject =
        RainProtocol.parseObject(executeBytes(client, buildRequest(path), stage).toString(Charsets.UTF_8), stage)

    private fun buildRequest(path: String, body: JSONObject? = null): Request {
        val url = origin.resolve(path)!!
        val request = Request.Builder().url(url)
            .header("Referer", origin.resolve("web?next=/v2/web/index&type=3").toString())
            .header("User-Agent", "RainNotify/" + BuildConfig.VERSION_NAME + " (Android 16; authorized companion)")
            .header("Accept", "application/json")
            .header("X-Client", "h5")
            .header("xtbz", "ykt")
        authorization?.let { request.header("Authorization", "Bearer " + it) }
        if (body != null) {
            request.header("Origin", origin.toString().trimEnd('/'))
            cookies.loadForRequest(url).firstOrNull { it.name == "csrftoken" }?.let {
                request.header("X-CSRFToken", it.value)
            }
            request.post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
        }
        return request.build()
    }

    private fun executeBytes(client: OkHttpClient, request: Request, stage: String, maxBytes: Int = 1_048_576): ByteArray {
        report("protocol_request", mapOf("protocol_stage" to stage,
            "request_has_authorization" to (request.header("Authorization") != null),
            "request_has_csrf" to (request.header("X-CSRFToken") != null)))
        try {
            client.newCall(request).execute().use { response ->
                val auth = response.header("Set-Auth")
                val sameOrigin = request.url.scheme == origin.scheme && request.url.host == origin.host &&
                    request.url.port == origin.port
                if (sameOrigin && stage != "qr_image" && response.isSuccessful && !auth.isNullOrBlank() && auth.length <= 16384
                    && auth.all { it.code in 33..126 }) authorization = auth
                report("protocol_response", mapOf("protocol_stage" to stage, "http_status" to response.code,
                    "response_has_authorization" to !auth.isNullOrBlank()))
                if (response.code == 401) throw RainFailure(FailureReason.SESSION_EXPIRED, stage, 401)
                if (!response.isSuccessful) {
                    // Keep only numeric business codes, never response text or credentials.
                    val error = try { JSONObject(response.peekBody(8192).string()) } catch (_: Exception) { null }
                    val code = error?.opt("code")?.toString()?.toIntOrNull()
                    report("protocol_error_details", mapOf("protocol_stage" to stage,
                        "response_json" to (error != null)) +
                        (code?.let { mapOf("business_code" to it) } ?: emptyMap()))
                    throw RainFailure(FailureReason.HTTP, stage, response.code, code)
                }
                val body = response.body ?: throw RainFailure(FailureReason.RESPONSE_FORMAT, stage)
                val source = body.source()
                if (source.request(maxBytes + 1L)) throw RainFailure(FailureReason.RESPONSE_FORMAT, stage)
                return source.readByteArray()
            }
        } catch (_: IOException) {
            // Exception messages can contain URLs and tickets. Never propagate them.
            throw RainFailure(FailureReason.NETWORK, stage)
        }
    }
}
