package cn.rainnotify.app.question

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.text.Html
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import cn.rainnotify.app.Diagnostics
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.min

class QuestionActivity : ComponentActivity() {
    private lateinit var repository: QuestionRepository
    private lateinit var content: LinearLayout
    private val images = Executors.newSingleThreadExecutor()
    private var renderVersion = 0
    private var selectedId: String? = null
    private var padding = 16
    private var exportId: String? = null
    private var exporting = false
    private var pickingExport = false
    private val importLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            Toast.makeText(this, "正在导入离线样本…", Toast.LENGTH_SHORT).show()
            repository.importSamples(uri) { result ->
                if (!isDestroyed) Toast.makeText(this, result, Toast.LENGTH_LONG).show()
            }
        }
    }
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        pickingExport = false
        if (uri == null) return@registerForActivityResult
        exporting = true
        render(repository.records.value.orEmpty())
        repository.export(exportId, uri) { result ->
            if (!isDestroyed) {
                exporting = false
                render(repository.records.value.orEmpty())
                Toast.makeText(this, if (result == null) "导出失败；请重新选择保存位置并重试。" else
                    "已导出 " + result.questions + " 题、" + result.images + " 张原图" +
                        if (result.incomplete > 0) "；其中 " + result.incomplete + " 题尚未完整获取。" else "。可从文件管理器发送 ZIP。",
                    Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repository = QuestionRepository.get(this)
        selectedId = (savedInstanceState?.getString(QUESTION_ID) ?: intent.getStringExtra(QUESTION_ID))
            ?.takeIf { QuestionFiles.validId(it) }
        exportId = savedInstanceState?.getString("export_id")?.takeIf { QuestionFiles.validId(it) }
        pickingExport = savedInstanceState?.getBoolean("picking_export") ?: false
        padding = (16 * resources.displayMetrics.density).toInt()
        content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(padding + bars.left, padding + bars.top, padding + bars.right, padding + bars.bottom)
            insets
        }
        val imageIndex = intent.getIntExtra(IMAGE_INDEX, -1)
        if (imageIndex >= 0 && selectedId != null) {
            renderFullImage(selectedId!!, imageIndex)
        } else {
            setContentView(ScrollView(this).apply { addView(content) })
            repository.records.observe(this) { render(it) }
            repository.liveLesson.observe(this) { render(repository.records.value.orEmpty()) }
        }
        content.requestApplyInsets()
    }

    private fun text(value: CharSequence, large: Boolean = false) = TextView(this).apply {
        text = value
        textSize = if (large) 21f else 16f
        setTextIsSelectable(true)
        setPadding(0, padding / 2, 0, padding / 2)
        content.addView(this)
    }

    private fun button(label: String, action: () -> Unit) = Button(this).apply {
        text = label
        setOnClickListener { action() }
        content.addView(this)
    }

    private fun time(value: Long) = SimpleDateFormat("MM-dd HH:mm:ss", Locale.CHINA).format(Date(value))

    private fun render(records: List<QuestionRecord>) {
        val version = ++renderVersion
        content.removeAllViews()
        if (selectedId == null) {
            text("最近获取的课堂题目", true)
            text("监听后自动保存新题，最多保留最近 20 题（约 80 MB）。可手动作答，或在首页开启本次课堂自动模式。历史题和导入样本可以演练。")
            button("导入样本 ZIP（仅演练）") { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) }
            text(if (exporting) "正在导出，请稍候…" else "可手动导出题目 ZIP，再从文件管理器发给项目负责人。")
            button("导出最近题目 ZIP") { requestExport(null) }.isEnabled =
                records.isNotEmpty() && !exporting && !pickingExport
            if (records.isEmpty()) text("还没有题目。请先开始课堂监听，等待下一次真实发题。")
            records.forEach { record ->
                val summary = record.content?.let { it.typeName + (it.pageIndex?.let { p -> " · 第 " + p + " 页" } ?: "") }
                    ?: "内容获取中或待重试"
                button(time(record.receivedAt) + " · " + summary) {
                    startActivity(Intent(this, QuestionActivity::class.java).putExtra(QUESTION_ID, record.id))
                }
                text(record.message)
            }
            button("清空本机题目与图片") {
                AlertDialog.Builder(this).setTitle("清空已保存题目")
                    .setMessage("删除本 App 已保存的题干、选项和图片。课堂监听会继续，之后的新题仍会保存。")
                    .setPositiveButton("清空") { _, _ -> repository.clear() }
                    .setNegativeButton("取消", null).show()
            }.isEnabled = records.isNotEmpty()
            button("返回") { finish() }
            return
        }
        text("课堂题目 · 内容校验", true)
        val record = records.firstOrNull { it.id == selectedId }
        if (record == null) {
            text("尚未取得本题记录，或记录已清理。获取在后台进行，请稍候；也可返回最近题目列表查看。")
        } else {
            text("收到时间：" + time(record.receivedAt))
            text(record.message)
            if (record.offline) text("离线导入样本 · 只能演练")
            button("AI 演练与本题作答") {
                startActivity(Intent(this, cn.rainnotify.app.answer.AnswerActivity::class.java)
                    .putExtra(cn.rainnotify.app.answer.AnswerService.QUESTION, record.id))
            }
            button("重新获取本题内容") { repository.retry(record.id) }.isEnabled =
                !record.loading && repository.liveLesson.value == record.reference.lessonId
            if (repository.liveLesson.value != record.reference.lessonId) {
                text("本题课堂当前未监听；已保存内容可离线查看。重新获取仅在监听同一课堂时可用。")
            }
            record.content?.let { question ->
                text("题型：" + question.typeName +
                    (question.pageIndex?.let { " · 第 " + it + " 页" } ?: ""), true)
                text("题干", true)
                if (question.bodyHtml.isBlank()) text("无独立文字题干，请查看下方原图。")
                else richText(question.bodyHtml)
                text(if (question.type in setOf(4, 5)) "作答要求" else "选项", true)
                if (question.options.isEmpty() && question.type in setOf(1, 2, 3, 6)) text("未返回结构化选项，请查看原图。")
                if (question.type == 4) text("填空数量：${question.blankCount ?: "未取得"}；答案按空格顺序填写。")
                if (question.type == 3) text("最多可选：${question.pollingCount ?: "未取得"} 项。")
                if (question.type == 5) text("文字主观题：AI 默认简短回答，最多 300 字符。")
                question.options.forEach { option ->
                    text(option.label, true)
                    if (option.html.isBlank()) text("选项文字可能位于原图中。") else richText(option.html)
                }
                text("原图与附图（" + question.assets.size + "）", true)
                question.assets.forEachIndexed { index, asset ->
                    text(asset.label)
                    val error = record.imageErrors[index]
                    val file = if (error == null) repository.image(record.id, index) else null
                    if (file == null) {
                        text(error ?: if (record.loading) "正在等待图片下载…" else "图片尚未保存，可重新获取。")
                    } else {
                        val preview = ImageView(this).apply {
                            adjustViewBounds = true
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            contentDescription = asset.label
                            layoutParams = LinearLayout.LayoutParams(-1, (240 * resources.displayMetrics.density).toInt())
                        }
                        content.addView(preview)
                        images.execute {
                            val bitmap = decode(file, 1200, 1_500_000)
                            runOnUiThread {
                                if (!isDestroyed && renderVersion == version) preview.setImageBitmap(bitmap)
                                else bitmap?.recycle()
                            }
                        }
                        button("放大查看 · " + asset.label) {
                            startActivity(Intent(this, QuestionActivity::class.java)
                                .putExtra(QUESTION_ID, record.id).putExtra(IMAGE_INDEX, index))
                        }
                    }
                }
                question.notes.forEach { text("提示：" + it) }
                text("可先对照微信核验题目，再进入 AI 作答页。查看题目本身不会提交答案。")
            }
            text(if (exporting) "正在导出，请稍候…" else "可导出本题的题干、选项、已保存原图及获取状态。")
            button("导出本题 ZIP") { requestExport(record.id) }.isEnabled = !exporting && !pickingExport
        }
        button("最近题目列表") {
            startActivity(Intent(this, QuestionActivity::class.java))
            finish()
        }
        button("返回") { finish() }
    }

    private fun requestExport(id: String?) {
        if (exporting || pickingExport) return
        AlertDialog.Builder(this).setTitle("手动导出题目样本")
            .setMessage("将导出" + (if (id == null) "最近保存的题目" else "本题") +
                "的题干、选项、已保存原图，以及版本、手机型号和获取状态。不会导出登录凭据、账号信息或课堂接口标识。题目本身可能有授课者或学校信息，请检查后再发送。\n\n建议等图片下载完成后导出；未完成内容会在 ZIP 中注明。本 App 不会自动上传。")
            .setPositiveButton("选择保存位置") { _, _ ->
                exportId = id
                pickingExport = true
                val date = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                try { exportLauncher.launch("rainnotify-questions-" + date + ".zip") }
                catch (_: RuntimeException) {
                    pickingExport = false
                    Toast.makeText(this, "未能打开系统文件保存界面，请重试。", Toast.LENGTH_LONG).show()
                }
            }.setNegativeButton("取消", null).show()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(QUESTION_ID, selectedId)
        outState.putString("export_id", exportId)
        outState.putBoolean("picking_export", pickingExport)
        super.onSaveInstanceState(outState)
    }

    private fun richText(html: String) {
        // Rendering text only: no WebView, JavaScript, navigation, or automatic HTML image requests.
        // Every embedded image is separately fetched through QuestionImagePolicy.
        val safe = html.replace(Regex("""<(script|style)\b[^>]*>[\s\S]*?</\1>""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE), "【附图见下方】")
        val value = Html.fromHtml(safe, Html.FROM_HTML_MODE_LEGACY).toString().trim()
        text(value.ifBlank { "内容主要为附图，请查看下方图片。" })
    }

    private fun renderFullImage(id: String, index: Int) {
        text("双指缩放，单指拖动查看", true)
        button("返回题目") { finish() }
        val zoom = QuestionZoomView(this)
        content.addView(zoom, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(content)
        images.execute {
            val bitmap = repository.image(id, index)?.let { decode(it, 6000, 12_000_000) }
            runOnUiThread {
                if (!isDestroyed) {
                    if (bitmap == null) text("图片已清理或暂无法读取。") else zoom.setBitmap(bitmap)
                } else bitmap?.recycle()
            }
        }
    }

    private fun decode(file: File, maxEdge: Int, maxPixels: Long): Bitmap? = try {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) null else {
            var sample = 1
            while (options.outWidth / sample > maxEdge || options.outHeight / sample > maxEdge ||
                (options.outWidth.toLong() / sample) * (options.outHeight / sample) > maxPixels) sample *= 2
            BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
        }
    } catch (_: Exception) { null }

    override fun onResume() { super.onResume(); Diagnostics.appForeground = true }
    override fun onPause() { Diagnostics.appForeground = false; super.onPause() }
    override fun onDestroy() {
        renderVersion++
        images.shutdownNow()
        super.onDestroy()
    }

    companion object {
        const val QUESTION_ID = "question_id"
        const val IMAGE_INDEX = "image_index"
    }
}

/** Native bitmap viewer; the original downloaded bytes remain unchanged on disk. */
private class QuestionZoomView(context: android.content.Context) : View(context) {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var scale = 1f
    private var x = 0f
    private var y = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val detector = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val previous = scale
            scale = (scale * detector.scaleFactor).coerceIn(0.05f, 12f)
            val ratio = scale / previous
            x = detector.focusX - (detector.focusX - x) * ratio
            y = detector.focusY - (detector.focusY - y) * ratio
            invalidate()
            return true
        }
    })
    init { setBackgroundColor(Color.DKGRAY); contentDescription = "题目原图，可双指缩放和单指拖动" }
    fun setBitmap(value: Bitmap) { bitmap = value; fit(); invalidate() }
    private fun fit() {
        val image = bitmap ?: return
        if (width <= 0 || height <= 0) return
        scale = min(width.toFloat() / image.width, height.toFloat() / image.height)
        x = (width - image.width * scale) / 2
        y = (height - image.height * scale) / 2
    }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { fit() }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        bitmap?.let {
            canvas.save()
            canvas.translate(x, y)
            canvas.scale(scale, scale)
            canvas.drawBitmap(it, 0f, 0f, paint)
            canvas.restore()
        }
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_MOVE && !detector.isInProgress && event.pointerCount == 1) {
            x += event.x - lastX; y += event.y - lastY; invalidate()
        }
        if (event.actionMasked == MotionEvent.ACTION_UP) performClick()
        lastX = event.x; lastY = event.y
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}
