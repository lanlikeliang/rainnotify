package cn.rainnotify.app.question

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class QuestionExportInfo(
    val appVersion: String,
    val sdk: Int,
    val manufacturer: String,
    val model: String,
)

data class QuestionExportSummary(val questions: Int, val images: Int, val incomplete: Int)

/** An explicit sample export, never an archive of the app's private directory or API responses. */
object QuestionExport {
    private val tags = Regex("""<(?:"[^"]*"|'[^']*'|[^'">])*?>""")
    private val tagName = Regex("""^<\s*(/?)\s*([a-z][a-z0-9]*)\b""", RegexOption.IGNORE_CASE)
    private val allowedTags = setOf("p", "br", "b", "strong", "i", "em", "u", "sub", "sup",
        "table", "tbody", "thead", "tr", "td", "th", "div", "span", "ol", "ul", "li",
        "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "code", "pre", "s")

    // Retain static text formatting, without href/src, signed URLs, scripts, or other attributes.
    fun staticHtml(html: String): String {
        val withoutActiveContent = html
            .replace(Regex("""<(script|style)\b[^>]*>[\s\S]*?</\1\s*>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<!--[\s\S]*?-->"""), "")
        return tags.replace(withoutActiveContent) { tag ->
            val match = tagName.find(tag.value)
            val name = match?.groupValues?.get(2)?.lowercase()
            when {
                name == "img" -> "【附图见 images 列表】"
                name in allowedTags -> "<" + match!!.groupValues[1] + name + ">"
                else -> ""
            }
        }
    }

    fun write(output: OutputStream, records: List<QuestionRecord>, info: QuestionExportInfo,
        image: (String, Int) -> File?, now: Long = System.currentTimeMillis()): QuestionExportSummary {
        require(records.isNotEmpty()) { "No cached questions to export" }
        var savedImages = 0
        var incomplete = 0
        val samples = JSONArray()
        ZipOutputStream(output.buffered()).use { zip ->
            // Images are already compressed. Avoid holding the cache snapshot lock during deflation.
            zip.setLevel(0)
            fun entry(path: String, value: String) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(value.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            records.forEachIndexed { ordinal, record ->
                val sampleId = "question-" + (ordinal + 1).toString().padStart(3, '0')
                val question = record.content
                val exportedImages = JSONArray()
                var missingImage = false
                question?.assets?.forEachIndexed { index, asset ->
                    val file = if (record.imageErrors[index] == null) image(record.id, index) else null
                    val saved = file?.isFile == true
                    val relativePath = if (saved) "images/image-" + (index + 1) + "." + extension(file!!) else null
                    if (saved) {
                        zip.putNextEntry(ZipEntry(sampleId + "/" + relativePath))
                        file!!.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                        savedImages++
                    } else missingImage = true
                    exportedImages.put(JSONObject().put("label", asset.label).put("saved", saved)
                        .put("file", relativePath ?: JSONObject.NULL)
                        .put("message", record.imageErrors[index] ?: if (saved) "" else "导出时图片尚未保存"))
                }
                val complete = !record.loading && question != null && !missingImage
                if (!complete) incomplete++
                val data = JSONObject().put("schema_version", 1).put("sample_id", sampleId)
                    .put("received_at_ms", record.receivedAt).put("loading_at_export", record.loading)
                    .put("content_available", question != null).put("capture_complete", complete)
                    .put("fetch_message", record.message).put("images", exportedImages)
                if (question != null) {
                    data.put("type", question.type).put("type_name", question.typeName)
                        .put("blank_count", question.blankCount).put("polling_count", question.pollingCount)
                        .put("body_html", staticHtml(question.bodyHtml))
                        .put("options", JSONArray(question.options.map {
                            JSONObject().put("label", it.label).put("html", staticHtml(it.html))
                        })).put("notes", JSONArray(question.notes))
                }
                entry(sampleId + "/question.json", data.toString(2))
                samples.put(JSONObject().put("sample_id", sampleId).put("file", sampleId + "/question.json")
                    .put("capture_complete", complete))
            }
            entry("manifest.json", JSONObject().put("schema_version", 1).put("exported_at_ms", now)
                .put("app_version", info.appVersion).put("sdk", info.sdk)
                .put("manufacturer", info.manufacturer).put("model", info.model)
                .put("question_count", records.size).put("image_count", savedImages)
                .put("incomplete_count", incomplete).put("samples", samples).toString(2))
            entry("说明.txt", "雨课堂题目样本（用户手动导出）\n" +
                "manifest.json 包含版本、手机型号、时间及样本清单。\n" +
                "question-XXX/question.json 包含题型、静态题干 HTML、选项和获取状态。\n" +
                "images/ 中为已下载的原图文件，原始字节未压缩改写。\n" +
                "capture_complete 仅表示导出时内容和预期图片已保存，不代表内容已经人工核对。\n" +
                "题干或选项可能全部绘制在原图中。没有执行 OCR；请结合图片核验。\n" +
                "缺失图片和未完成内容会标明。请勿将这些样本当作完整题目。\n" +
                "不包含登录凭据、账号信息、课堂接口标识或原图下载地址。\n" +
                "题目本身仍可能显示授课者或学校信息，请自行检查后再发送。\n")
        }
        return QuestionExportSummary(records.size, savedImages, incomplete)
    }

    private fun extension(file: File): String {
        val header = ByteArray(12)
        val length = file.inputStream().use { it.read(header) }
        fun byte(index: Int) = header[index].toInt() and 255
        fun ascii(start: Int, end: Int) = String(header, start, end - start, Charsets.US_ASCII)
        return when {
            length >= 8 && byte(0) == 137 && ascii(1, 4) == "PNG" -> "png"
            length >= 3 && byte(0) == 255 && byte(1) == 216 && byte(2) == 255 -> "jpg"
            length >= 12 && ascii(0, 4) == "RIFF" && ascii(8, 12) == "WEBP" -> "webp"
            length >= 6 && ascii(0, 6) in setOf("GIF87a", "GIF89a") -> "gif"
            length >= 2 && ascii(0, 2) == "BM" -> "bmp"
            else -> "bin"
        }
    }
}
