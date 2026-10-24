package cn.rainnotify.app.question

import cn.rainnotify.app.answer.AnswerFailure
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.util.UUID
import java.util.zip.ZipInputStream

/** Exported samples have no live question identity. Imports are always marked offline. */
object QuestionImport {
    fun read(input: InputStream, staging: File, store: QuestionFiles): Int {
        staging.mkdirs()
        var total = 0L
        var entryCount = 0
        val entries = mutableSetOf<String>()
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (++entryCount > 400) throw AnswerFailure("样本 ZIP 包含过多条目。")
                val name = entry.name
                if (entry.isDirectory) { zip.closeEntry(); continue }
                if (!Regex("""(?:manifest\.json|说明\.txt|question-\d{3}/question\.json|question-\d{3}/images/image-\d{1,2}\.(?:jpg|jpeg|png|webp|gif|bmp|bin))""").matches(name) ||
                    !entries.add(name) || entries.size > 262) throw AnswerFailure("样本 ZIP 结构不符合本 App 的导出格式。")
                val target = File(staging, name)
                target.parentFile!!.mkdirs()
                var count = 0L
                val limit = if (name.endsWith(".json")) 2 * 1024 * 1024L else 10 * 1024 * 1024L
                target.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val n = zip.read(buffer)
                        if (n < 0) break
                        count += n; total += n
                        if (count > limit || total > 80 * 1024 * 1024L) throw AnswerFailure("样本 ZIP 超过导入大小限制。")
                        output.write(buffer, 0, n)
                    }
                }
                zip.closeEntry()
            }
        }
        val manifest = JSONObject(File(staging, "manifest.json").readText())
        if (manifest.optInt("schema_version") != 1) throw AnswerFailure("样本版本暂不支持。")
        val samples = manifest.getJSONArray("samples")
        if (samples.length() !in 1..20) throw AnswerFailure("一次仅支持导入 1 至 20 题。")
        val ids = mutableSetOf<String>()
        val prepared = (0 until samples.length()).map { ordinal ->
            val id = samples.getJSONObject(ordinal).getString("sample_id")
            if (!Regex("question-\\d{3}").matches(id) || !ids.add(id)) throw AnswerFailure("样本标识不合法或重复。")
            val root = JSONObject(File(staging, id + "/question.json").readText())
            if (root.optInt("schema_version") != 1 || root.optString("sample_id") != id ||
                !root.optBoolean("content_available") || !root.optBoolean("capture_complete"))
                throw AnswerFailure("样本中有尚未完整获取的题目。")
            val options = root.getJSONArray("options")
            if (options.length() !in 0..26 || (root.getInt("type") in setOf(1, 2, 3, 6) && options.length() < 2))
                throw AnswerFailure("样本选项数不支持。")
            val sourceImages = root.getJSONArray("images")
            if (sourceImages.length() > QuestionProtocol.MAX_ASSETS) throw AnswerFailure("样本图片过多。")
            val images = (0 until sourceImages.length()).map { i ->
                val asset = sourceImages.getJSONObject(i)
                val path = asset.optString("file")
                if (!asset.optBoolean("saved") || !Regex("""images/image-\d{1,2}\.(?:jpg|jpeg|png|webp|gif|bmp|bin)""").matches(path))
                    throw AnswerFailure("样本图片不完整，请重新导出完整题目。")
                File(staging, id + "/" + path).takeIf { it.isFile && it.length() > 0 }
                    ?: throw AnswerFailure("样本 ZIP 缺少声明的图片。")
            }
            val content = QuestionContent(root.getInt("type"), root.getString("body_html"),
                (0 until options.length()).map { i -> options.getJSONObject(i).let {
                    QuestionOption(it.getString("label"), null, it.getString("html"))
                } }, images.mapIndexed { i, _ -> QuestionAsset("导入原图 " + (i + 1), "offline:" + i) },
                null, listOf("导入样本仅用于 AI 演练，不能提交到雨课堂。"),
                root.optInt("blank_count", 0).takeIf { it > 0 }, root.optInt("polling_count", 0).takeIf { it > 0 })
            if (content.bodyHtml.length > 65_536 || content.options.any { it.html.length > 65_536 })
                throw AnswerFailure("样本文本过长。")
            val local = UUID.randomUUID().toString()
            QuestionRecord(local, QuestionRef("offline", "offline", local),
                System.currentTimeMillis() - ordinal, content, "离线样本已导入。", false, offline = true) to images
        }
        prepared.forEach { (record, images) ->
            store.write(record)
            images.forEachIndexed { index, file -> store.saveImage(record.id, index, file.readBytes()) }
        }
        return prepared.size
    }
}
