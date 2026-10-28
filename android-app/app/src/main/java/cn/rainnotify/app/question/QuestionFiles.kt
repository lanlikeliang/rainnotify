package cn.rainnotify.app.question

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.OutputStream
import cn.rainnotify.app.answer.AnswerFailure
import cn.rainnotify.app.answer.AnswerTask
import cn.rainnotify.app.answer.PreparedQuestion
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

class QuestionRecord(
    val id: String,
    val reference: QuestionRef,
    val receivedAt: Long,
    val content: QuestionContent? = null,
    val message: String = "正在获取题目…",
    val loading: Boolean = true,
    val imageErrors: Map<Int, String> = emptyMap(),
    val offline: Boolean = false,
    val lessonName: String = "", val accountScope: String = "", val recovered: Boolean = false,
) {
    val classroomKey get() = reference.lessonId
    override fun toString() = "QuestionRecord(redacted)"
    fun changed(content: QuestionContent? = this.content, message: String = this.message,
        loading: Boolean = this.loading, imageErrors: Map<Int, String> = this.imageErrors) =
        QuestionRecord(id, reference, receivedAt, content, message, loading, imageErrors, offline, lessonName, accountScope, recovered)

    fun json(): JSONObject = JSONObject().put("id", id).put("ref", reference.json()).put("received", receivedAt)
        .put("content", content?.json()).put("message", message).put("loading", loading).put("offline", offline)
        .put("lesson_name", lessonName).put("account_scope", accountScope).put("recovered", recovered)
        .put("image_errors", JSONArray(imageErrors.map { JSONObject().put("index", it.key).put("message", it.value) }))

    companion object {
        fun fromJson(value: JSONObject): QuestionRecord {
            val errors = value.optJSONArray("image_errors") ?: JSONArray()
            return QuestionRecord(value.getString("id"), QuestionRef.fromJson(value.getJSONObject("ref")),
                value.getLong("received"), value.optJSONObject("content")?.let { QuestionContent.fromJson(it) },
                value.getString("message"), value.getBoolean("loading"),
                (0 until errors.length()).associate { i -> errors.getJSONObject(i).let {
                    it.getInt("index") to it.getString("message")
                } }, value.optBoolean("offline", false), value.optString("lesson_name"), value.optString("account_scope"), value.optBoolean("recovered"))
        }
    }
}

/** App-private question and answer archive, excluded from Android backup. No cookies or full presentation responses. */
class QuestionFiles(private val root: File, private val maxRecords: Int = Int.MAX_VALUE,
    private val maxBytes: Long = Long.MAX_VALUE) {
    init { root.mkdirs() }
    private fun folder(id: String): File {
        require(validId(id))
        return File(root, id)
    }

    @Synchronized fun records(): List<QuestionRecord> =
        root.listFiles()?.filter { it.isDirectory && validId(it.name) }?.mapNotNull { dir ->
            try {
                val file = File(dir, "question.json")
                if (file.length() > 2 * 1024 * 1024) return@mapNotNull null
                QuestionRecord.fromJson(JSONObject(file.readText())).takeIf { it.id == dir.name }
            } catch (_: Exception) { null }
        }?.sortedByDescending { it.receivedAt } ?: emptyList()

    @Synchronized fun record(id: String): QuestionRecord? {
        if (!validId(id)) return null
        return try {
            val file = File(folder(id), "question.json")
            if (!file.isFile || file.length() > 2 * 1024 * 1024) null
            else QuestionRecord.fromJson(JSONObject(file.readText())).takeIf { it.id == id }
        } catch (_: Exception) { null }
    }

    @Synchronized fun write(record: QuestionRecord) {
        val dir = folder(record.id).apply { mkdirs() }
        atomicWrite(File(dir, "question.json"), record.json().toString().toByteArray(Charsets.UTF_8))
        prune(record.id)
    }

    @Synchronized fun saveImage(id: String, index: Int, bytes: ByteArray) {
        require(index in 0 until QuestionProtocol.MAX_ASSETS && bytes.size <= QuestionImages.MAX_BYTES)
        val dir = folder(id)
        check(File(dir, "question.json").exists())
        atomicWrite(File(dir, "image-" + index + ".bin"), bytes)
        prune(id)
    }

    @Synchronized fun image(id: String, index: Int): File? {
        if (!validId(id) || index !in 0 until QuestionProtocol.MAX_ASSETS) return null
        return File(folder(id), "image-" + index + ".bin").takeIf { it.isFile }
    }

    @Synchronized fun saveAnswer(value: AnswerTask): Boolean {
        val dir = folder(value.questionId)
        if (!File(dir, "question.json").isFile) return false
        atomicWrite(File(dir, "answer.json"), value.json().toString().toByteArray(Charsets.UTF_8))
        return true
    }
    @Synchronized fun answers(): Map<String, AnswerTask> = root.listFiles().orEmpty()
        .filter { it.isDirectory && validId(it.name) }.mapNotNull { directory ->
            try {
                val file = File(directory, "answer.json")
                if (!file.isFile || file.length() > 65_536) null else AnswerTask.fromJson(JSONObject(file.readText()))
                    .takeIf { it.questionId == directory.name }?.let { directory.name to it }
            } catch (_: Exception) { null }
        }.toMap()
    fun exportStudy(output: OutputStream, classroom: String) {
        val snapshot = synchronized(this) { records().filter { it.classroomKey == classroom } to answers() }
        StudyExport.write(output, snapshot.first, snapshot.second, ::image)
    }
    @Synchronized fun removeImages(id: String) {
        val dir = folder(id)
        for (index in 0 until QuestionProtocol.MAX_ASSETS) File(dir, "image-" + index + ".bin").delete()
    }

    /** Hold one disk snapshot while building a local archive; destination IO happens separately. */
    @Synchronized fun export(output: OutputStream, info: QuestionExportInfo, id: String? = null): QuestionExportSummary {
        require(id == null || validId(id))
        val selected = records().filter { id == null || it.id == id }
        return QuestionExport.write(output, selected, info, ::image)
    }

    @Synchronized fun prepare(id: String, directory: File): PreparedQuestion {
        val record = record(id) ?: throw AnswerFailure("题目记录已清理，请重新查看题目。")
        val question = record.content ?: throw AnswerFailure("尚未获取完整题目内容。")
        if (record.loading || record.imageErrors.isNotEmpty()) throw AnswerFailure("题目或图片尚未完整获取，请稍后重试。")
        if (question.bodyHtml.isBlank() && question.assets.isEmpty()) throw AnswerFailure("题目缺少题干和原图。")
        if (question.options.any { it.html.isBlank() } && question.assets.isEmpty())
            throw AnswerFailure("选项文字为空且没有原图，无法可靠作答。")
        directory.mkdirs()
        val copies = question.assets.mapIndexed { index, _ ->
            val source = image(id, index) ?: throw AnswerFailure("原图缺失，请重新获取本题。")
            if (source.length() !in 1..QuestionImages.MAX_BYTES.toLong()) throw AnswerFailure("图片大小异常。")
            File(directory, "image-" + index + "." + cn.rainnotify.app.answer.ImageMime.extension(source))
                .also { source.copyTo(it, overwrite = true) }
        }
        return PreparedQuestion(record, directory, copies)
    }

    @Synchronized fun clear() {
        root.listFiles()?.filter { it.isDirectory && validId(it.name) }?.forEach { it.deleteRecursively() }
    }

    private fun prune(current: String) {
        if (maxRecords == Int.MAX_VALUE && maxBytes == Long.MAX_VALUE) return
        val records = records().toMutableList()
        fun size() = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        var bytes = size()
        for (record in records.sortedBy { it.receivedAt }) {
            if (records.size <= maxRecords && bytes <= maxBytes) break
            if (record.id == current) continue
            val dir = folder(record.id)
            val removed = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            if (dir.deleteRecursively()) { records.remove(record); bytes -= removed }
        }
    }

    private fun atomicWrite(file: File, bytes: ByteArray) {
        val temporary = File(file.parentFile, file.name + ".tmp")
        temporary.outputStream().use { it.write(bytes); it.fd.sync() }
        try {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun validId(id: String?) = id != null && Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(id)
    }
}
