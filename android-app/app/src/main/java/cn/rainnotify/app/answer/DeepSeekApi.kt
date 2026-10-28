package cn.rainnotify.app.answer

import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import java.util.concurrent.TimeUnit

class ApiUnavailable(message: String) : AnswerFailure(message)

class ApiImage(val mime: String, val bytes: ByteArray) {
    override fun toString() = "ApiImage(redacted)"
}

/** No Rain cookies, identifiers, download URLs, logging interceptor or automatic retries. */
class DeepSeekApi internal constructor(private val endpoint: HttpUrl = ENDPOINT,
    private val client: OkHttpClient = OkHttpClient()) {
    fun solve(key: String, prompt: String, images: List<ApiImage>, timeoutMs: Long, control: RunControl, model: DeepSeekModel = DeepSeekModel.FLASH): String {
        control.check()
        if (timeoutMs !in 1_000..60_000) throw AnswerFailure("剩余时间不足以调用模型，未发送。")
        val body = requestBody(prompt, images, model).toString().toRequestBody("application/json; charset=utf-8".toMediaType())
        val call = client.newBuilder().followRedirects(false).followSslRedirects(false)
            .retryOnConnectionFailure(false).connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(timeoutMs, TimeUnit.MILLISECONDS).writeTimeout(timeoutMs, TimeUnit.MILLISECONDS)
            .callTimeout(timeoutMs, TimeUnit.MILLISECONDS).build().newCall(Request.Builder().url(endpoint)
                .header("Authorization", "Bearer $key").post(body).build())
        control.onCancel { call.cancel() }
        try {
            return call.execute().use { response ->
                control.check()
                if (!response.isSuccessful) {
                    if (response.code in setOf(400, 401, 402, 403, 422)) throw ApiUnavailable(httpMessage(response.code))
                    throw AnswerFailure(httpMessage(response.code))
                }
                val source = response.body?.source() ?: throw AnswerFailure("DeepSeek 未返回内容。")
                val limit = 128 * 1024L
                if (source.request(limit + 1)) throw AnswerFailure("DeepSeek 回复超过大小限制，未提交。")
                responseText(source.readUtf8())
            }
        } catch (e: IOException) {
            control.check()
            throw AnswerFailure("DeepSeek API 连接失败或超过本题时限，请检查网络后手动重试；未提交。")
        } finally { control.onCancel(null) }
    }

    companion object {
        val ENDPOINT = "https://api.deepseek.com/chat/completions".toHttpUrl()
        const val MAX_IMAGE_BYTES = 24 * 1024 * 1024L
        fun requestBody(prompt: String, images: List<ApiImage>, model: DeepSeekModel = DeepSeekModel.FLASH): JSONObject {
            model.requireSupportedImages(images.isNotEmpty())
            if (prompt.length > 500_000 || images.size > 12 || images.sumOf { it.bytes.size.toLong() } > MAX_IMAGE_BYTES ||
                images.any { it.bytes.isEmpty() || it.mime !in setOf("image/jpeg", "image/png", "image/webp", "image/gif") })
                throw AnswerFailure("本题图文超过 API 发送限制；未发送不完整题目。")
            val content = JSONArray().put(JSONObject().put("type", "text").put("text", prompt))
            images.forEach { image -> content.put(JSONObject().put("type", "image_url")
                .put("image_url", JSONObject().put("url", "data:${image.mime};base64," + Base64.getEncoder().encodeToString(image.bytes))
                    .put("detail", "high"))) }
            return JSONObject().put("model", model.id).put("stream", false)
                .put("thinking", JSONObject().put("type", "disabled"))
                .put("max_tokens", 4096).put("response_format", JSONObject().put("type", "json_object"))
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", if (images.isEmpty()) prompt else content)))
        }
        fun responseText(raw: String): String {
            try {
                val choices = JSONObject(raw).getJSONArray("choices")
                if (choices.length() != 1) throw AnswerFailure("DeepSeek 返回了多个或缺少最终答案，未提交。")
                val choice = choices.getJSONObject(0)
                if (choice.optString("finish_reason") != "stop") throw AnswerFailure("DeepSeek 答案被截断或未正常结束，未提交。")
                val message = choice.getJSONObject("message")
                if (message.optJSONArray("tool_calls")?.length()?.let { it > 0 } == true)
                    throw AnswerFailure("DeepSeek 未返回可用的最终答案。")
                val text = message.opt("content") as? String
                if (text.isNullOrBlank() || text.length > 16_384) throw AnswerFailure("DeepSeek 答案为空或过长，未提交。")
                return text
            } catch (e: AnswerFailure) { throw e }
            catch (_: Exception) { throw AnswerFailure("DeepSeek 回复格式无法识别，未提交。") }
        }
        fun httpMessage(code: Int) = when (code) {
            401, 403 -> "DeepSeek API Key 无效或没有权限，请到设置更新密钥。"
            402 -> "DeepSeek API 余额不足，请在服务商控制台查看。"
            429 -> "DeepSeek 请求过于频繁，本题不会自动重试。"
            400, 422 -> "DeepSeek 未接受模型或图文参数，请到设置重新进行当前模型自检。"
            in 300..399 -> "DeepSeek 返回了重定向，已停止以保护密钥。"
            else -> "DeepSeek 服务暂不可用（HTTP $code），本题未提交。"
        }
    }
}
