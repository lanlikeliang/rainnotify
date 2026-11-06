package cn.rainnotify.app.auth

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.net.InetAddress
import java.util.concurrent.TimeUnit

class ServerApiTest {
    @Test fun separateClientsKeepSessionAndBearerCredentialsOnTheirOwnServer() {
        MockWebServer().use { first -> MockWebServer().use { second ->
            first.start(InetAddress.getByName("127.0.0.1"), 0)
            second.start(InetAddress.getByName("127.0.0.1"), 0)
            val clients = listOf(
                RainApi(RainCookieJar(first.url("/")), first.url("/"), RainServer.CHANGJIANG),
                RainApi(RainCookieJar(second.url("/")), second.url("/"), RainServer.HUANGHE))
            val servers = listOf(first, second)
            servers.forEachIndexed { i, server ->
                server.enqueue(MockResponse().setBody("OK").addHeader("Set-Cookie", "sessionid=fixture-$i; Path=/"))
                server.enqueue(MockResponse().setBody("""{"code":0,"data":{}}""").addHeader("Set-Auth", "bearer-$i"))
                server.enqueue(MockResponse().setBody("""{"code":0,"data":{"onLessonClassrooms":[]}}"""))
                clients[i].exchange(LoginMessage.Grant(1, "grant-$i"))
                clients[i].validateSession()
            }
            clients.forEach { assertTrue(it.classrooms().isEmpty()) }
            servers.forEachIndexed { i, server ->
                assertEquals("/pc/web_login", server.takeRequest(2, TimeUnit.SECONDS)!!.path)
                val check = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("sessionid=fixture-$i", check.getHeader("Cookie"))
                assertNull(check.getHeader("Authorization"))
                val list = server.takeRequest(2, TimeUnit.SECONDS)!!
                assertEquals("Bearer bearer-$i", list.getHeader("Authorization"))
                assertEquals("sessionid=fixture-$i", list.getHeader("Cookie"))
                assertTrue(list.getHeader("Referer")!!.startsWith(server.url("/").toString()))
                assertEquals(3, server.requestCount)
            }
        } }
    }
}
