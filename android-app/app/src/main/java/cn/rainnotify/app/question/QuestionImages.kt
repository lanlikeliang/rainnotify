package cn.rainnotify.app.question

import cn.rainnotify.app.auth.FailureReason
import cn.rainnotify.app.auth.RainFailure
import cn.rainnotify.app.auth.RainProtocol
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.concurrent.TimeUnit

/** No session headers or cookies, including on redirects. Only reviewed content hosts. */
object QuestionImagePolicy {
    fun url(raw: String): HttpUrl {
        val value = RainProtocol.ORIGIN.toHttpUrl().resolve(raw.trim()) ?: rejected()
        if (value.username.isNotEmpty() || value.password.isNotEmpty() ||
            value.port !in setOf(80, 443) ||
            listOf("yuketang.cn", "xuetangx.com").none { value.host == it || value.host.endsWith("." + it) }) rejected()
        return value.newBuilder().scheme("https").port(443).fragment(null).build()
    }
    private fun rejected(): Nothing = throw RainFailure(FailureReason.RESPONSE_FORMAT, "question_image_origin")
}

class QuestionImages {
    private val client = OkHttpClient.Builder().cookieJar(CookieJar.NO_COOKIES)
        .followRedirects(false).followSslRedirects(false).connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS).callTimeout(20, TimeUnit.SECONDS).build()

    fun fetch(raw: String): ByteArray {
        var url = QuestionImagePolicy.url(raw)
        try {
            repeat(4) {
                client.newCall(Request.Builder().url(url).build()).execute().use { response ->
                    if (response.code in listOf(301, 302, 303, 307, 308)) {
                        val location = response.header("Location") ?: invalidContent()
                        url = QuestionImagePolicy.url(url.resolve(location)?.toString() ?: location)
                    } else {
                        if (!response.isSuccessful) throw RainFailure(FailureReason.HTTP, "question_image", response.code)
                        val body = response.body ?: invalidContent()
                        val mime = body.contentType()?.toString()?.substringBefore(';')
                        if (mime !in setOf("image/png", "image/jpeg", "image/webp", "image/gif",
                                "image/bmp", "application/octet-stream")) invalidContent()
                        if (body.source().request(MAX_BYTES + 1L)) invalidContent()
                        return body.bytes()
                    }
                }
            }
        } catch (_: IOException) {
            throw RainFailure(FailureReason.NETWORK, "question_image")
        }
        invalidContent()
    }

    fun cancel() = client.dispatcher.cancelAll()
    companion object { const val MAX_BYTES = 10 * 1024 * 1024 }
}
