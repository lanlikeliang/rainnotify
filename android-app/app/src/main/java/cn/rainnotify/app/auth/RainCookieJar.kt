package cn.rainnotify.app.auth

import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl

class RainCookieJar(private val origin: HttpUrl = RainProtocol.ORIGIN.toHttpUrl()) : CookieJar {
    private val cookies = mutableListOf<Cookie>()

    @Synchronized
    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (url.host != origin.host || url.scheme != origin.scheme) return
        for (cookie in cookies) {
            if (!origin.host.equals(cookie.domain) && !origin.host.endsWith("." + cookie.domain)) continue
            this.cookies.removeAll {
                it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
            }
            if (cookie.expiresAt > System.currentTimeMillis()) this.cookies.add(cookie)
        }
        if (this.cookies.size > 100) this.cookies.subList(0, this.cookies.size - 100).clear()
    }

    @Synchronized
    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        if (url.host != origin.host || url.scheme != origin.scheme) return emptyList()
        cookies.removeAll { it.expiresAt <= System.currentTimeMillis() }
        return cookies.filter { it.matches(url) }
    }

    @Synchronized
    fun snapshot(): List<String> = cookies.filter { it.expiresAt > System.currentTimeMillis() }
        .map { it.toString() }

    fun restore(serialized: List<String>) {
        saveFromResponse(origin, serialized.mapNotNull { Cookie.parse(origin, it) })
    }
}
