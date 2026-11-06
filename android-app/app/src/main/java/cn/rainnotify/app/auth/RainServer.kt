package cn.rainnotify.app.auth

import okhttp3.HttpUrl.Companion.toHttpUrl
import java.security.MessageDigest

/** Fixed official environments. Never accept an arbitrary credential destination. */
enum class RainServer(val id: String, val label: String, val host: String) {
    ORDINARY("ordinary", "普通版雨课堂", "www.yuketang.cn"),
    CHANGJIANG("changjiang", "长江雨课堂", "changjiang.yuketang.cn"),
    HUANGHE("huanghe", "黄河雨课堂", "huanghe.yuketang.cn"),
    HETANG("hetang", "荷塘雨课堂", "pro.yuketang.cn");

    val origin get() = "https://$host/".toHttpUrl()
    val socket get() = "wss://$host/wsapp/"
    val experimental get() = this != ORDINARY
    // Preserve v25 ordinary archive keys and durable submission reservations exactly.
    fun classroomKey(lesson: String) = if (this == ORDINARY) lesson else "$id:$lesson"
    fun accountScope(cookies: List<String>): String {
        val legacy = cookies.sorted().joinToString("\n")
        val material = if (this == ORDINARY) legacy else "$id:$legacy"
        return MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    val vaultFile get() = if (this == ORDINARY) "rain-session-v1.enc" else "rain-session-$id-v1.enc"
    val vaultAlias get() = if (this == ORDINARY) "rainnotify.session.v1" else "rainnotify.session.$id.v1"
    val vaultAad get() = if (this == ORDINARY) "RainNotifySession:v1" else "RainNotifySession:$id:v1"

    companion object {
        fun fromId(value: String?): RainServer? = entries.firstOrNull { it.id == value }
        fun fromHost(value: String): RainServer? = entries.firstOrNull { it.host == value }
    }
}
