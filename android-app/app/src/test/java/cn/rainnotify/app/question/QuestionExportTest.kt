package cn.rainnotify.app.question

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

class QuestionExportTest {
    @get:Rule val temporary = TemporaryFolder()
    private val info = QuestionExportInfo("0.0.11-volunteer", 36, "TEST", "TEST-MODEL")
    private val secretUrl = "https://cdn.yuketang.cn/image.png?ticket=private-ticket"
    private val png = byteArrayOf(137.toByte(), 80, 78, 71, 13, 10, 26, 10, 1, 2, 3, 4)

    private fun record(content: QuestionContent? = QuestionContent(1,
        """<p>题干<img src="$secretUrl"><a href="$secretUrl">阅读文字</a></p>""",
        listOf(QuestionOption("A", "internal-option-key", """<b data-url="$secretUrl">选项一</b>""")),
        listOf(QuestionAsset("课件原图", secretUrl)), 1, listOf("等待核验")),
        loading: Boolean = false, errors: Map<Int, String> = emptyMap(), at: Long = 1) =
        QuestionRecord(UUID.randomUUID().toString(), QuestionRef("private-lesson-id",
            "private-presentation-id", "private-problem-id"), at, content, "已获取", loading, errors)

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                assertFalse(entry.name.startsWith("/"))
                assertFalse(entry.name.contains(".."))
                result[entry.name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }
    private fun json(entries: Map<String, ByteArray>, path: String) =
        JSONObject(entries.getValue(path).toString(Charsets.UTF_8))

    @Test fun exportsUsefulQuestionAndExactOriginalImageWithoutProtocolSecrets() {
        val store = QuestionFiles(temporary.newFolder())
        val record = record()
        store.write(record)
        store.saveImage(record.id, 0, png)
        val output = ByteArrayOutputStream()
        val summary = store.export(output, info)
        val entries = unzip(output.toByteArray())
        assertEquals(QuestionExportSummary(1, 1, 0), summary)
        assertArrayEquals(png, entries.getValue("question-001/images/image-1.png"))
        val sample = json(entries, "question-001/question.json")
        assertEquals("单选", sample.getString("type_name"))
        assertTrue(sample.getString("body_html").contains("题干"))
        assertEquals("<b>选项一</b>", sample.getJSONArray("options").getJSONObject(0).getString("html"))
        assertTrue(sample.getBoolean("capture_complete"))
        val text = entries.values.joinToString("\n") { it.toString(Charsets.UTF_8) }
        listOf(record.id, "private-lesson-id", "private-presentation-id", "private-problem-id",
            "private-ticket", secretUrl, "internal-option-key").forEach { assertFalse(text.contains(it)) }
        val manifest = json(entries, "manifest.json")
        assertEquals(36, manifest.getInt("sdk"))
        assertEquals(info.appVersion, manifest.getString("app_version"))
    }

    @Test fun recordsFailuresAndPendingDownloadsRatherThanPretendingTheyAreComplete() {
        val store = QuestionFiles(temporary.newFolder())
        val missing = record(errors = mapOf(0 to "图片来源尚未适配"), at = 1)
        val pending = record(content = null, loading = true, at = 2)
        store.write(missing); store.saveImage(missing.id, 0, png)
        store.write(pending)
        val output = ByteArrayOutputStream()
        assertEquals(QuestionExportSummary(2, 0, 2), store.export(output, info))
        val entries = unzip(output.toByteArray())
        assertFalse(entries.keys.any { it.contains("/images/") })
        val pendingJson = json(entries, "question-001/question.json")
        assertFalse(pendingJson.getBoolean("content_available"))
        assertTrue(pendingJson.getBoolean("loading_at_export"))
        val missingJson = json(entries, "question-002/question.json")
        assertFalse(missingJson.getBoolean("capture_complete"))
        val image = missingJson.getJSONArray("images").getJSONObject(0)
        assertTrue(image.isNull("file"))
        assertEquals("图片来源尚未适配", image.getString("message"))
    }

    @Test fun singleQuestionExportDoesNotIncludeOtherQuestionsOrPrivateFiles() {
        val root = temporary.newFolder()
        val store = QuestionFiles(root)
        val first = record(at = 1)
        val second = record(content = QuestionContent(2, "另一道题", emptyList(), emptyList(), null, emptyList()), at = 2)
        store.write(first); store.saveImage(first.id, 0, png); store.write(second)
        File(root, "cookies.json").writeText("private-cookie")
        File(File(root, first.id), "token.txt").writeText("private-authorization")
        val output = ByteArrayOutputStream()
        assertEquals(QuestionExportSummary(1, 1, 0), store.export(output, info, first.id))
        val entries = unzip(output.toByteArray())
        assertEquals(setOf("question-001/images/image-1.png", "question-001/question.json",
            "manifest.json", "说明.txt"), entries.keys)
        val text = entries.values.joinToString { it.toString(Charsets.UTF_8) }
        assertFalse(text.contains("另一道题"))
        assertFalse(text.contains("private-cookie"))
        assertFalse(text.contains("private-authorization"))
    }

    @Test fun emptyOrExpiredSelectionFailsInsteadOfCreatingAnEmptySuccess() {
        val store = QuestionFiles(temporary.newFolder())
        assertThrows(IllegalArgumentException::class.java) { store.export(ByteArrayOutputStream(), info) }
        store.write(record())
        assertThrows(IllegalArgumentException::class.java) {
            store.export(ByteArrayOutputStream(), info, UUID.randomUUID().toString())
        }
        assertThrows(IllegalArgumentException::class.java) {
            store.export(ByteArrayOutputStream(), info, "../other")
        }
    }

    @Test fun staticFormattingRetainsTextAndRemovesExecutableMarkupAndAttributes() {
        val input = """<p title="a > b" data-token="private-ticket">通识题<br/>
            <script>private-script</script><style>private-style</style><!-- private-comment -->
            <img src="$secretUrl" onerror="private-handler"><a href="$secretUrl">参考文字</a>
            <strong>强调</strong><sup>2</sup></p>"""
        val result = QuestionExport.staticHtml(input)
        assertTrue(result.contains("<p>通识题<br>"))
        assertTrue(result.contains("<strong>强调</strong><sup>2</sup>"))
        assertTrue(result.contains("参考文字"))
        assertTrue(result.contains("【附图见 images 列表】"))
        listOf("private-", "href", "src=", "onerror", "title=", secretUrl).forEach {
            assertFalse(result.contains(it))
        }
    }
}
