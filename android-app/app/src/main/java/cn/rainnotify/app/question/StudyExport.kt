package cn.rainnotify.app.question

import cn.rainnotify.app.answer.AnswerTask
import cn.rainnotify.app.answer.AnswerPhase
import cn.rainnotify.app.answer.ImageMime
import java.io.File
import java.io.OutputStream
import java.io.FilterOutputStream
import java.util.Base64
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Self-contained, offline study notes. No account IDs, credentials, source URLs or executable content. */
object StudyExport {
    fun write(output: OutputStream, records: List<QuestionRecord>, answers: Map<String, AnswerTask>, image: (String, Int) -> File?) {
        require(records.isNotEmpty()) { "本课堂尚无题目" }
        require(records.map { it.classroomKey }.distinct().size == 1) { "只能导出一个课堂" }
        val ordered = records.sortedBy { it.receivedAt }
        val title = ordered.first().lessonName.ifBlank { "课堂复习笔记" }
        val date = SimpleDateFormat("yyyy年MM月dd日 HH:mm", Locale.CHINA)
        val out = output.bufferedWriter(Charsets.UTF_8)
        fun html(text: String) { out.write(text) }
        html("<!doctype html><html lang=zh-CN><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>")
        html("""<meta http-equiv=Content-Security-Policy content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'">""")
        html("<title>" + escape(title) + " · 复习笔记</title>")
        html("<style>body{font:17px/1.75 system-ui,sans-serif;color:#182d34;background:#f5f7f8;margin:0}main{max-width:860px;margin:auto;padding:28px 20px}article{background:white;padding:24px;border-radius:16px;margin:20px 0}h1{font-size:28px}h2{font-size:20px}small,figcaption{color:#63767c}img{max-width:100%;height:auto}figure{margin:16px 0}details{border-top:1px solid #ddd;padding-top:12px;margin-top:20px}summary{cursor:pointer;color:#087f76}p{white-space:pre-wrap;overflow-wrap:anywhere}.missing{color:#946009}@media print{body{background:white}main{max-width:none;padding:0}article{border:1px solid #ddd;break-inside:avoid}details{display:block}details> *{display:block!important}summary{display:none!important}}</style><main>")
        html("<h1>" + escape(title) + "</h1><p>" + date.format(Date(ordered.first().receivedAt)) + " · 共 " + ordered.size + " 题</p>")
        html("<small>时间为本机收到题目的时间，补收题会标注。点击答案栏可折叠自测；可用浏览器打印或保存为 PDF。AI 答案仅供参考，不代表教师标准答案。</small>")
        ordered.forEachIndexed { n, record ->
            val q = record.content
            html("<article><h2>" + (n+1) + ". " + escape(q?.typeName ?: "内容未完整获取") + "</h2><small>" + date.format(Date(record.receivedAt)) + (if(record.recovered) " · 重连补收" else "") + "</small>")
            if (q == null) html("<p class=missing>" + escape(record.message) + "</p>")
            else {
                html("<div>" + QuestionExport.staticHtml(q.bodyHtml).replace("【附图见 images 列表】", "【见下方原图】") + "</div>")
                q.options.forEach { html("<p><b>" + escape(it.label) + ". </b>" + QuestionExport.staticHtml(it.html).replace("【附图见 images 列表】", "【见下方原图】") + "</p>") }
                q.assets.forEachIndexed { index, asset ->
                    val file = image(record.id, index)?.takeIf { it.isFile && record.imageErrors[index] == null }
                    html("<figure><figcaption>" + escape(asset.label.ifBlank { "题目原图" }) + "</figcaption>")
                    if(file == null) html("<p class=missing>原图尚未保存，无法导出。</p>")
                    else {
                        html("<img alt='题目原图' src='data:" + ImageMime.mime(file) + ";base64,"); out.flush()
                        val encoded = Base64.getEncoder().wrap(object: FilterOutputStream(output) { override fun close() { flush() } })
                        file.inputStream().use { it.copyTo(encoded) }; encoded.close()
                        html("'>")
                    }
                    html("</figure>")
                }
            }
            val answer = answers[record.id]
            html("<details open><summary>答案与作答状态</summary>")
            html("<p>" + escape(answer?.answer?.takeIf { it.isNotEmpty() }?.joinToString(if(q?.type==4) "\n" else "、") ?: "未记录 AI 答案") + "</p>")
            val state = when(answer?.phase) {
                AnswerPhase.SUBMITTED -> "已提交（雨课堂已确认）"
                AnswerPhase.ALREADY_ANSWERED -> "已有作答，未重复提交"
                AnswerPhase.UNCERTAIN -> "提交结果待确认"
                AnswerPhase.FAILED -> "处理或提交失败"
                AnswerPhase.CANCELLED -> "已停止，未提交"
                AnswerPhase.QUEUED -> "等待处理"
                null -> "仅记录题目"
                else -> "处理尚未完成"
            }
            html("<small>" + escape(state) + (answer?.let { " · " + escape(it.model.displayName) } ?: "") + "</small></details></article>")
        }
        html("</main></html>"); out.flush()
    }
    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;")
}
