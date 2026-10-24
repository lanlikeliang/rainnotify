package cn.rainnotify.app.answer

import android.os.Bundle
import android.widget.TextView
import androidx.activity.ComponentActivity
import cn.rainnotify.app.auth.*
import cn.rainnotify.app.question.*
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** USB-shell-only diagnosis: GET existing questions/images, never enter a class or submit an answer. */
class QuestionComparisonProbeActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val status = TextView(this).apply { text = "正在只读核对题目内容和原图，不会作答或提交。"; setPadding(32, 64, 32, 32) }
        setContentView(status)
        if (AnswerState.task.value?.active == true || AutoAnswer.armed || AutoAnswer.ticket != null ||
            !running.compareAndSet(false, true)) {
            status.text = "请先结束当前作答和自动模式，再进行只读核对。"; return
        }
        val requested = intent.getStringExtra("question")
        val context = applicationContext
        Thread {
            val report = JSONObject().put("started", System.currentTimeMillis()).put("read_only", true)
            val results = JSONArray()
            val images = QuestionImages()
            var api: RainApi? = null
            try {
                val files = QuestionFiles(File(context.noBackupFilesDir, "questions-v1"))
                val records = files.records().filter { !it.offline && !it.loading && it.content != null &&
                    if (requested != null) it.id == requested else System.currentTimeMillis() - it.receivedAt in 0..86_400_000
                }.take(3)
                require(records.isNotEmpty())
                val cookies = SessionVault(context).read()
                check(cookies.isNotEmpty())
                val reader = RainApi(RainCookieJar().apply { restore(cookies) }).also { api = it }
                reader.validateSession()
                for (record in records) {
                    val item = JSONObject().put("record", record.id)
                    results.put(item)
                    try {
                        val before = record.content!!
                        val after = reader.question(record.reference)
                        item.put("legacy_equal", before.json().toString() == after.json().toString())
                            .put("changed_fields", JSONArray(QuestionIdentity.differences(before, after)))
                            .put("body_raw_equal", before.bodyHtml == after.bodyHtml)
                            .put("options_raw_equal", before.options.map { it.json().toString() } == after.options.map { it.json().toString() })
                            .put("page_equal", before.pageIndex == after.pageIndex)
                            .put("notes_equal", before.notes == after.notes)
                            .put("asset_count", after.assets.size)
                            .put("asset_urls_equal", before.assets.map { it.url } == after.assets.map { it.url })
                            .put("asset_resources_equal", before.assets.map { QuestionIdentity.imageReference(it.url) } ==
                                after.assets.map { QuestionIdentity.imageReference(it.url) })
                        val cached = files.image(record.id, 0)
                        if (cached != null && after.assets.isNotEmpty()) {
                            try {
                                val downloaded = images.fetch(after.assets.first().url)
                                item.put("cover_bytes_equal", cached.readBytes().contentEquals(downloaded))
                            } catch (e: Exception) { item.put("image_error", errorCode(e)) }
                        }
                    } catch (e: Exception) { item.put("error", errorCode(e)) }
                }
                report.put("status", "complete")
            } catch (e: Exception) { report.put("status", "failed").put("error", errorCode(e)) }
            finally {
                images.cancel()
                api?.client?.dispatcher?.cancelAll()
                report.put("results", results).put("finished", System.currentTimeMillis())
                try { File(context.noBackupFilesDir, "question-comparison-probe.json").writeText(report.toString()) }
                finally { running.set(false) }
                runOnUiThread { status.text = "只读核对已结束。脱敏结果已保存；没有启动 AI 或提交答案。" }
            }
        }.start()
    }

    private fun errorCode(error: Exception): String = if (error is RainFailure)
        error.reason.name + ":" + (error.httpStatus ?: 0) else error.javaClass.simpleName

    companion object { private val running = AtomicBoolean(false) }
}
