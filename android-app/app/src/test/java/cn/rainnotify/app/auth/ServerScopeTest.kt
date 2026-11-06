package cn.rainnotify.app.auth

import cn.rainnotify.app.answer.AnswerProtocol
import cn.rainnotify.app.answer.SubmissionJournal
import cn.rainnotify.app.answer.SubmitOutcome
import cn.rainnotify.app.question.*
import cn.rainnotify.app.ui.*
import okhttp3.Cookie
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.UUID

class ServerScopeTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun onlyFourExplicitServersAreAcceptedAndNoMissingSelectionIsDefaulted() {
        assertNull(RainServer.fromId(null))
        assertNull(RainServer.fromId("https://evil.test"))
        assertNull(RainServer.fromHost("www.yuketang.cn.evil.test"))
        assertEquals(4, RainServer.entries.size)
        for (server in RainServer.entries) {
            assertEquals(server, RainServer.fromId(server.id))
            assertEquals(server.host, server.origin.host)
            assertEquals("https", server.origin.scheme)
            assertEquals("wss://${server.host}/wsapp/", server.socket)
        }
        val setup = ModeRequirements(false, false, true, false, true, true, serverSelected = false)
        assertEquals(listOf(Requirement.SERVER), ModePolicy.missing(ClassroomMode.AUTO, setup))
        assertTrue(ModePolicy.missing(ClassroomMode.OFF, setup).isEmpty())
    }

    @Test fun cookiesIncludingParentDomainCookiesNeverLeaveSelectedEnvironment() {
        for (server in RainServer.entries) {
            val jar = RainCookieJar(server.origin)
            jar.saveFromResponse(server.origin, listOf(Cookie.parse(server.origin,
                "sessionid=synthetic; Domain=yuketang.cn; Secure; Path=/")!!))
            assertEquals(1, jar.loadForRequest(server.origin).size)
            for (other in RainServer.entries.filter { it != server }) {
                assertTrue(jar.loadForRequest(other.origin).isEmpty())
                jar.saveFromResponse(other.origin, listOf(Cookie.parse(other.origin,
                    "sessionid=wrong-server; Domain=yuketang.cn; Secure; Path=/")!!))
            }
            assertEquals("synthetic", jar.loadForRequest(server.origin).single().value)
        }
    }

    @Test fun qrImagesMayUseOnlyWechatOrTheSelectedServer() {
        for (server in RainServer.entries) {
            assertEquals(server.host, RainProtocol.qrUrl("https://${server.host}/q", server).host)
            assertEquals("mp.weixin.qq.com", RainProtocol.qrUrl("http://mp.weixin.qq.com/q", server).host)
            for (other in RainServer.entries.filter { it != server }) {
                assertThrows(RainFailure::class.java) { RainProtocol.qrUrl("https://${other.host}/q", server) }
            }
            for (url in listOf("https://${server.host}.evil.test/q", "https://u:p@${server.host}/q", "https://${server.host}:8443/q")) {
                assertThrows(RainFailure::class.java) { RainProtocol.qrUrl(url, server) }
            }
        }
    }

    @Test fun submissionScopePreservesOldOrdinaryReservationsAndSeparatesServers() {
        val cookies = listOf("second", "first")
        val legacy = AnswerProtocol.sha256(cookies.sorted().joinToString("\n"))
        assertEquals(legacy, RainServer.ORDINARY.accountScope(cookies))
        assertEquals(4, RainServer.entries.map { it.accountScope(cookies) }.distinct().size)
        val journal = SubmissionJournal(temporary.newFolder())
        val oldKey = AnswerProtocol.sha256("$legacy:10:20:30")
        journal.reserve(oldKey, "old-task"); journal.complete(oldKey, SubmitOutcome.UNKNOWN)
        assertTrue(journal.used(AnswerProtocol.sha256(RainServer.ORDINARY.accountScope(cookies) + ":10:20:30")))
        assertFalse(journal.used(AnswerProtocol.sha256(RainServer.CHANGJIANG.accountScope(cookies) + ":10:20:30")))
    }

    @Test fun vaultLocationsAndEncryptionContextsAreSeparateWithoutRenamingOrdinaryData() {
        assertEquals("rain-session-v1.enc", RainServer.ORDINARY.vaultFile)
        assertEquals("rainnotify.session.v1", RainServer.ORDINARY.vaultAlias)
        assertEquals("RainNotifySession:v1", RainServer.ORDINARY.vaultAad)
        assertEquals(4, RainServer.entries.map { it.vaultFile }.distinct().size)
        assertEquals(4, RainServer.entries.map { it.vaultAlias }.distinct().size)
        assertEquals(4, RainServer.entries.map { it.vaultAad }.distinct().size)
    }

    private fun record(server: RainServer) = QuestionRecord(UUID.randomUUID().toString(), QuestionRef("10", "20", "30"),
        1, QuestionContent(1, server.label + "合成题", emptyList(), emptyList(), null, emptyList()),
        loading = false, lessonName = "同名课堂", server = server)

    @Test fun duplicateLessonIdsCannotMergeAcrossServersOrLeakIntoExports() {
        val files = QuestionFiles(temporary.newFolder())
        val records = RainServer.entries.map(::record)
        records.forEach { files.write(it.changed(message = "保存完成")) }
        val loaded = files.records()
        assertEquals(4, StudyClassrooms.group(loaded).size)
        for (record in records) {
            assertEquals(record.server, files.record(record.id)!!.server)
            val bytes = ByteArrayOutputStream(); files.exportStudy(bytes, record.classroomKey)
            val html = bytes.toString("UTF-8")
            assertTrue(html.contains(record.server.label + "合成题"))
            for (other in RainServer.entries.filter { it != record.server }) assertFalse(html.contains(other.label + "合成题"))
        }
    }

    @Test fun legacyArchivesStayOrdinaryAndUnknownServerMetadataIsRejected() {
        val json = record(RainServer.ORDINARY).json().also { it.remove("server") }
        val old = QuestionRecord.fromJson(json)
        assertEquals(RainServer.ORDINARY, old.server)
        assertEquals("10", old.classroomKey)
        json.put("server", "unrecognized")
        assertThrows(IllegalArgumentException::class.java) { QuestionRecord.fromJson(json) }
    }

    @Test fun serverSwitchCannotOverlapAClassroomOrPendingSubmission() {
        assertTrue(ServerSwitchPolicy.allowed(false, false, false, false))
        assertFalse(ServerSwitchPolicy.allowed(true, false, false, false))
        assertFalse(ServerSwitchPolicy.allowed(false, true, false, false))
        assertFalse(ServerSwitchPolicy.allowed(false, false, true, false))
        assertFalse(ServerSwitchPolicy.allowed(false, false, false, true))
    }
}
