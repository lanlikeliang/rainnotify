package cn.rainnotify.app.auth

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RainProtocolTest {
    @Test fun emptyClassroomIsValidButMalformedResponseIsNotEmpty() {
        assertTrue(RainProtocol.lessons(JSONObject("""{"code":0,"data":{"onLessonClassrooms":[]}}""")).isEmpty())
        val failure = assertThrows(RainFailure::class.java) {
            RainProtocol.lessons(JSONObject("""{"code":0,"data":{}}"""))
        }
        assertEquals(FailureReason.RESPONSE_FORMAT, failure.reason)
        assertThrows(RainFailure::class.java) {
            RainProtocol.lessons(JSONObject("""{"code":401,"data":{"onLessonClassrooms":[]}}"""))
        }
    }

    @Test fun partialMalformedClassroomListFailsInsteadOfHidingClass() {
        assertThrows(RainFailure::class.java) {
            RainProtocol.lessons(JSONObject("""{"code":0,"data":{"onLessonClassrooms":[{"classroomName":"fixture"}]}}"""))
        }
        val lessons = RainProtocol.lessons(JSONObject("""{"code":0,"data":{"onLessonClassrooms":[
            {"lessonId":42,"classroomName":"fixture"},{"lessonId":42,"classroomName":"fixture"}]}}"""))
        assertEquals(1, lessons.size)
        assertEquals("42", lessons.single().id)
    }

    @Test fun ticketIsAnImageUrlAndNeverBecomesLoginCredentials() {
        val message = RainProtocol.loginMessage("""{"ticket":"http://mp.weixin.qq.com/cgi-bin/showqrcode?ticket=fixture"}""")
        assertTrue(message is LoginMessage.Qr)
        assertEquals("https", (message as LoginMessage.Qr).imageUrl.scheme)
        for (url in listOf("https://mp.weixin.qq.com.evil.test/q", "file:///tmp/q", "https://u:p@www.yuketang.cn/q")) {
            assertThrows(RainFailure::class.java) { RainProtocol.qrUrl(url) }
        }
    }

    @Test fun grantRejectsBlankCredentialsAndDoesNotPrintSecrets() {
        assertThrows(RainFailure::class.java) { RainProtocol.loginMessage("""{"UserID":1,"Auth":""}""") }
        val message = RainProtocol.loginMessage("""{"UserID":1,"Auth":"synthetic-secret"}""")
        assertTrue(message is LoginMessage.Grant)
        assertFalse(message.toString().contains("synthetic-secret"))
    }

    @Test fun cookiesAreScopedExpireAndRoundTrip() {
        val origin = RainProtocol.ORIGIN.toHttpUrl()
        val jar = RainCookieJar()
        jar.saveFromResponse(origin, listOf(
            Cookie.parse(origin, "sessionid=fixture; Secure; HttpOnly; Path=/")!!,
            Cookie.parse(origin, "csrftoken=test; Secure; Path=/api/")!!))
        assertEquals(1, jar.loadForRequest(origin).size)
        assertEquals(2, jar.loadForRequest(origin.resolve("api/v3/user/basic-info")!!).size)
        assertTrue(jar.loadForRequest("https://mp.weixin.qq.com/".toHttpUrl()).isEmpty())
        assertTrue(jar.loadForRequest("http://www.yuketang.cn/".toHttpUrl()).isEmpty())
        val restored = RainCookieJar().apply { restore(jar.snapshot()) }
        assertEquals(jar.loadForRequest(origin), restored.loadForRequest(origin))
        restored.saveFromResponse(origin, listOf(Cookie.parse(origin, "sessionid=gone; Path=/; Max-Age=0")!!))
        assertTrue(restored.loadForRequest(origin).isEmpty())
    }

    @Test fun cancelledOrSupersededAuthorizationCannotCommit() {
        val gate = AttemptGate()
        val cancelled = gate.begin()
        val active = gate.begin()
        var committed = false
        assertFalse(gate.runIfCurrent(cancelled) { committed = true })
        assertFalse(committed)
        assertTrue(gate.runIfCurrent(active) { committed = true })
        assertTrue(committed)
    }
}
