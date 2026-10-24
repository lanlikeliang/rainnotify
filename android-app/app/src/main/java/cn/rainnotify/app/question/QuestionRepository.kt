package cn.rainnotify.app.question

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.DocumentsContract
import androidx.lifecycle.MutableLiveData
import cn.rainnotify.app.Diagnostics
import cn.rainnotify.app.BuildConfig
import cn.rainnotify.app.auth.RainApi
import cn.rainnotify.app.auth.RainFailure
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import cn.rainnotify.app.answer.AnswerFailure
import cn.rainnotify.app.monitor.LessonConnection

/** All content IO is separate from the socket queue and notification path. */
class QuestionRepository private constructor(context: Context) {
    private val app = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private val exports = Executors.newSingleThreadExecutor()
    private val files = QuestionFiles(File(app.noBackupFilesDir, "questions-v1"))
    private val images = QuestionImages()
    private val pending = ConcurrentHashMap.newKeySet<String>()
    private val generation = AtomicLong(0)
    class Binding(val lesson: String, val api: RainApi, val connection: LessonConnection, val accountScope: String)
    private val liveRecords = ConcurrentHashMap<String, Binding>()
    @Volatile private var binding: Binding? = null
    val records = MutableLiveData<List<QuestionRecord>>(emptyList())
    val liveLesson = MutableLiveData<String?>(null)

    init {
        worker.execute {
            files.records().filter { it.loading }.forEach {
                files.write(it.changed(loading = false, message = "上次获取被中断；已保留获取到的内容。"))
            }
            publish()
        }
    }

    fun attach(lesson: String, api: RainApi, connection: LessonConnection, accountScope: String) {
        liveRecords.clear()
        binding = Binding(lesson, api, connection, accountScope)
        liveLesson.value = lesson
    }

    fun detach(api: RainApi?) {
        if (binding?.api !== api) return
        binding = null
        liveRecords.clear()
        images.cancel()
        liveLesson.value = null
    }

    fun capture(reference: QuestionRef): String {
        val id = UUID.randomUUID().toString()
        val record = QuestionRecord(id, reference, System.currentTimeMillis())
        val version = generation.get()
        val active = binding
        if (active != null) liveRecords[id] = active
        pending.add(id)
        worker.execute {
            try {
                if (version != generation.get()) return@execute
                files.write(record)
                publish()
                load(record, active, version)
            } catch (_: Exception) {
                Diagnostics.record(app, "question_storage_failed", mapOf("event_id" to id))
            } finally { pending.remove(id); publish() }
        }
        return id
    }

    fun retry(id: String) {
        if (!QuestionFiles.validId(id) || !pending.add(id)) return
        val version = generation.get()
        val active = binding
        worker.execute {
            try {
                val record = files.records().firstOrNull { it.id == id } ?: return@execute
                if (version == generation.get()) load(record, active, version)
            } catch (_: Exception) {
                Diagnostics.record(app, "question_storage_failed", mapOf("event_id" to id))
            } finally { pending.remove(id); publish() }
        }
    }

    private fun load(original: QuestionRecord, active: Binding?, version: Long) {
        var record = original
        fun current() = generation.get() == version && binding === active &&
            active != null && active.lesson == original.reference.lessonId
        fun save() {
            if (generation.get() == version) { files.write(record); publish() }
        }
        try {
            if (!current()) {
                record = record.changed(loading = false, message = "监听已停止或不是本题所在课堂；已保存的内容仍可查看。")
                save()
                return
            }
            record = record.changed(loading = true, message = "正在获取题干与选项…")
            save()
            // Refresh only while the same listener still owns the same authenticated RainApi.
            val content = active!!.api.question(original.reference)
            if (!current()) return finishInterrupted(record, version)
            // A refreshed slide can reorder or replace images; stale index-based files must not be shown.
            files.removeImages(record.id)
            val errors = mutableMapOf<Int, String>()
            record = record.changed(content = content, message = "题干与选项已获取，正在下载图片…",
                imageErrors = emptyMap())
            save()
            var totalBytes = 0
            var loaded = 0
            content.assets.forEachIndexed { index, asset ->
                if (!current()) return finishInterrupted(record, version)
                try {
                    val bytes = images.fetch(asset.url)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 ||
                        bounds.outWidth.toLong() * bounds.outHeight > 100_000_000L ||
                        totalBytes + bytes.size > 32 * 1024 * 1024) invalidContent()
                    if (!current()) return finishInterrupted(record, version)
                    files.saveImage(record.id, index, bytes)
                    totalBytes += bytes.size
                    loaded++
                } catch (error: Exception) {
                    val failure = error as? RainFailure
                    errors[index] = when (failure?.stage) {
                        "question_image_origin" -> "图片来源尚未适配，未加载。"
                        else -> failure?.userMessage() ?: "图片无法解码或保存，请重试。"
                    }
                    Diagnostics.record(app, "question_image_failed", mapOf("event_id" to record.id,
                        "protocol_stage" to (failure?.stage ?: "question_image"),
                        "error_type" to (failure?.reason?.name ?: "IMAGE")))
                }
                record = record.changed(imageErrors = errors.toMap())
                save()
            }
            record = record.changed(loading = false, message = if (errors.isEmpty())
                "本题内容已保存，可与微信课堂核对。" else "文字已保存，部分图片未获取；可在监听期间重试。")
            save()
            Diagnostics.record(app, "question_content_saved", mapOf("event_id" to record.id,
                "question_type" to content.type, "option_count" to content.options.size,
                "asset_count" to content.assets.size, "loaded_asset_count" to loaded,
                "body_present" to content.bodyHtml.isNotBlank()))
        } catch (error: Exception) {
            val failure = error as? RainFailure
            record = record.changed(loading = false,
                message = failure?.userMessage() ?: "题目获取或保存失败，请在监听期间重试。")
            save()
            Diagnostics.record(app, "question_content_failed", mapOf("event_id" to record.id,
                "protocol_stage" to (failure?.stage ?: "question_content"),
                "error_type" to (failure?.reason?.name ?: "STORAGE")))
        }
    }

    private fun finishInterrupted(record: QuestionRecord, version: Long) {
        if (generation.get() != version) return
        files.write(record.changed(loading = false, message = "监听已停止，已保留获取到的内容。"))
        publish()
    }

    fun image(id: String, index: Int) = files.image(id, index)

    fun activeBinding(id: String): Binding? = liveRecords[id]?.takeIf { it === binding }
    fun currentBinding(): Binding? = binding
    fun reloadLocal() { worker.execute { publish() } }
    fun bindingStillCurrent(value: Binding) = binding === value
    fun prepare(id: String, directory: File) = files.prepare(id, directory)
    fun isLoading(id: String) = id in pending || files.records().firstOrNull { it.id == id }?.loading == true

    fun importSamples(source: Uri, completed: (String) -> Unit) {
        exports.execute {
            val staging = File(app.cacheDir, "question-import-" + UUID.randomUUID())
            val result = try {
                val count = app.contentResolver.openInputStream(source)?.use { QuestionImport.read(it, staging, files) }
                    ?: throw AnswerFailure("无法读取样本 ZIP。")
                publish()
                "已导入 " + count + " 道离线样本，可进行 AI 演练。"
            } catch (e: Exception) {
                (e as? AnswerFailure)?.display ?: "样本导入失败，请选择本 App 导出的完整 ZIP。"
            } finally { staging.deleteRecursively() }
            main.post { completed(result) }
        }
    }

    fun export(id: String?, destination: Uri, completed: (QuestionExportSummary?) -> Unit) {
        exports.execute {
            var archive: File? = null
            val result = try {
                val directory = File(app.cacheDir, "question-exports").apply { mkdirs() }
                // Single export queue: clean only our staging archives left by an interrupted export.
                directory.listFiles()?.filter { it.isFile && it.name.endsWith(".zip") &&
                    QuestionFiles.validId(it.name.removeSuffix(".zip")) }?.forEach { it.delete() }
                val staging = File(directory, UUID.randomUUID().toString() + ".zip")
                archive = staging
                val summary = staging.outputStream().use {
                    files.export(it, QuestionExportInfo(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT,
                        Build.MANUFACTURER, Build.MODEL), id)
                }
                app.contentResolver.openOutputStream(destination, "wt")?.use { output ->
                    staging.inputStream().use { it.copyTo(output) }
                } ?: error("Export destination unavailable")
                summary
            } catch (_: Exception) {
                // Never leave a partial archive looking like a successful export.
                try { DocumentsContract.deleteDocument(app.contentResolver, destination) } catch (_: Exception) { }
                Diagnostics.record(app, "question_export_failed")
                null
            } finally { archive?.delete() }
            main.post { completed(result) }
        }
    }

    fun clear() {
        generation.incrementAndGet()
        liveRecords.clear()
        images.cancel()
        worker.execute { files.clear(); publish() }
    }

    private fun publish() {
        val snapshot = files.records()
        main.post { records.value = snapshot }
    }

    companion object {
        // The repository stores applicationContext only, never an Activity or Service context.
        @SuppressLint("StaticFieldLeak")
        @Volatile private var instance: QuestionRepository? = null
        fun get(context: Context): QuestionRepository = instance ?: synchronized(this) {
            instance ?: QuestionRepository(context).also { instance = it }
        }
    }
}
