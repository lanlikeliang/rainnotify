package cn.rainnotify.app.auth

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

enum class FailureReason {
    NETWORK, HTTP, SESSION_EXPIRED, RESPONSE_REJECTED, RESPONSE_FORMAT, QR_ORIGIN,
    QR_IMAGE, STORAGE, TIMEOUT, CONNECTION_CLOSED, LESSON_ENDED
}

class RainFailure(
    val reason: FailureReason,
    val stage: String,
    val httpStatus: Int? = null,
    val businessCode: Int? = null,
) : Exception(reason.name) {
    val requiresClassroomScan: Boolean get() = stage == "lesson_checkin" && businessCode == 50070
    fun userMessage(): String = if (requiresClassroomScan)
        "该课堂已开启动态二维码签到（50070）。请用微信扫描教师当前展示的课堂二维码，完成签到后返回继续监听；无需重新登录账号。"
    else when (reason) {
        FailureReason.NETWORK -> "网络连接失败，请检查网络后重试。"
        FailureReason.HTTP -> "雨课堂请求未成功（HTTP " + (httpStatus ?: 0) +
            (businessCode?.let { "，业务码 " + it } ?: "") + "），请稍后重试。"
        FailureReason.SESSION_EXPIRED -> "登录会话已失效，请重新扫码授权。"
        FailureReason.RESPONSE_REJECTED -> "雨课堂未接受本次请求，请重新授权或稍后重试。"
        FailureReason.RESPONSE_FORMAT -> "接口返回格式与候选协议不一致，已记录脱敏诊断。"
        FailureReason.QR_ORIGIN -> "二维码来源与已核对的登录来源不一致，已停止加载。"
        FailureReason.QR_IMAGE -> "二维码图片无法读取，请重新生成。"
        FailureReason.STORAGE -> "无法安全保存本地登录，请退出辅助 App 登录后重试。"
        FailureReason.TIMEOUT -> "本次授权等待已结束，请重新生成二维码。"
        FailureReason.CONNECTION_CLOSED -> "登录连接已关闭，请重新生成二维码。"
        FailureReason.LESSON_ENDED -> "所选课堂已结束或不再出现在当前课堂列表，请刷新。"
    }
}

sealed interface LoginMessage {
    class Qr(val imageUrl: HttpUrl) : LoginMessage
    // Avoid data-class toString leaking credentials in diagnostics or exceptions.
    class Grant(val userId: Any, val auth: String) : LoginMessage {
        override fun toString() = "LoginGrant(redacted)"
    }
    data object Waiting : LoginMessage
}

data class LessonSummary(val id: String, val classroomName: String)

object RainProtocol {
    const val ORIGIN = "https://www.yuketang.cn/"
    const val LOGIN_SOCKET = "wss://www.yuketang.cn/wsapp/"

    fun loginRequest(): String = JSONObject()
        .put("op", "requestlogin").put("role", "web").put("version", 1.4)
        .put("type", "qrcode").put("from", "web").toString()

    fun parseObject(text: String, stage: String): JSONObject {
        if (text.length > 1_048_576) throw RainFailure(FailureReason.RESPONSE_FORMAT, stage)
        return try { JSONObject(text) }
        catch (_: Exception) { throw RainFailure(FailureReason.RESPONSE_FORMAT, stage) }
    }

    fun loginMessage(text: String): LoginMessage {
        val value = parseObject(text, "login_socket")
        if (value.has("code") && value.opt("code")?.toString() != "0") {
            throw RainFailure(FailureReason.RESPONSE_REJECTED, "login_socket",
                businessCode = value.opt("code")?.toString()?.toIntOrNull())
        }
        if (value.has("UserID") && value.has("Auth")) {
            val id = value.opt("UserID")
                ?: throw RainFailure(FailureReason.RESPONSE_FORMAT, "login_socket")
            val auth = value.opt("Auth") as? String
            if ((id !is Number && id !is String) || id.toString().isBlank()
                || id.toString().length > 128 || auth.isNullOrBlank() || auth.length > 8192) {
                throw RainFailure(FailureReason.RESPONSE_FORMAT, "login_socket")
            }
            return LoginMessage.Grant(id, auth)
        }
        val ticket = value.opt("ticket") as? String
        return if (!ticket.isNullOrBlank()) LoginMessage.Qr(qrUrl(ticket)) else LoginMessage.Waiting
    }

    fun qrUrl(ticket: String): HttpUrl {
        val candidate = ticket.toHttpUrlOrNull()
            ?: throw RainFailure(FailureReason.QR_ORIGIN, "qr_image")
        val allowed = setOf("www.yuketang.cn", "yuketang.cn", "mp.weixin.qq.com")
        if (candidate.host !in allowed || candidate.username.isNotEmpty()
            || candidate.password.isNotEmpty() || candidate.port !in setOf(80, 443)) {
            throw RainFailure(FailureReason.QR_ORIGIN, "qr_image")
        }
        // Known legacy image URLs may use HTTP. Fetch the same trusted host over TLS.
        return candidate.newBuilder().scheme("https").port(443).fragment(null).build()
    }

    fun requireSuccess(root: JSONObject, stage: String) {
        val code = root.opt("code")?.toString()
        if (code != "0") {
            if (code == null) throw RainFailure(FailureReason.RESPONSE_FORMAT, stage)
            throw RainFailure(FailureReason.RESPONSE_REJECTED, stage,
                businessCode = code.toIntOrNull())
        }
    }

    fun successfulData(root: JSONObject, stage: String): Any {
        requireSuccess(root, stage)
        return root.opt("data")?.takeUnless { it == JSONObject.NULL }
            ?: throw RainFailure(FailureReason.RESPONSE_FORMAT, stage)
    }

    fun lessons(root: JSONObject): List<LessonSummary> {
        val data = successfulData(root, "classrooms") as? JSONObject
            ?: throw RainFailure(FailureReason.RESPONSE_FORMAT, "classrooms")
        val items = data.opt("onLessonClassrooms") as? JSONArray
            ?: throw RainFailure(FailureReason.RESPONSE_FORMAT, "classrooms")
        return (0 until items.length()).map { index ->
            val item = items.optJSONObject(index)
                ?: throw RainFailure(FailureReason.RESPONSE_FORMAT, "classrooms")
            val rawId = item.opt("lessonId")
            val name = item.opt("classroomName") as? String
            if ((rawId !is Number && rawId !is String) || rawId.toString().isBlank()
                || name.isNullOrBlank()) throw RainFailure(FailureReason.RESPONSE_FORMAT, "classrooms")
            LessonSummary(rawId.toString(), name.take(200))
        }.distinctBy { it.id }
    }
}
