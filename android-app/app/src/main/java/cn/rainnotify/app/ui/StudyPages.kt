package cn.rainnotify.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cn.rainnotify.app.answer.AnswerTask
import java.io.File
import cn.rainnotify.app.question.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun receivedTime(at: Long, full: Boolean = false): String = SimpleDateFormat(if(full) "yyyy/MM/dd HH:mm" else "HH:mm", Locale.CHINA).format(Date(at))


@Composable fun ReferenceAnswer(record: QuestionRecord, answer: AnswerTask?, submit: (QuestionRecord, AnswerTask) -> Unit, issue: String?) {
    val ownAnswer = answer?.takeIf { it.questionId == record.id }
    val question = ownAnswer?.sourceQuestion ?: record.content
    Text("参考答案", fontWeight = FontWeight.SemiBold)
    Text(ownAnswer?.answer?.takeIf { it.isNotEmpty() }?.joinToString(if (question?.type == 4) "\n" else "、")
        ?: "等待参考答案", fontSize = 20.sp, lineHeight = 29.sp)
    if (ownAnswer != null) {
        Text(ownAnswer.message, color = Muted, fontSize = 13.sp)
        if (ownAnswer.active) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    Button(onClick = { ownAnswer?.let { submit(record, it) } },
        enabled = ownAnswer?.answer?.isNotEmpty() == true && issue == null) { Text("提交参考答案") }
    if (issue != null && ownAnswer?.answer?.isNotEmpty() == true) Text(issue, color = Muted, fontSize = 12.sp)
}

@Composable fun LatestQuestionSummary(record: QuestionRecord?, answer: AnswerTask?, open: () -> Unit,
    imageFile: (String, Int) -> File? = { _, _ -> null },
    submit: (QuestionRecord, AnswerTask) -> Unit = { _, _ -> },
    submissionIssue: (QuestionRecord, AnswerTask?) -> String? = { _, _ -> "请连接对应课堂后提交。" }) {
    if (record == null) return
    val ownAnswer = answer?.takeIf { it.questionId == record.id }
    val question = ownAnswer?.sourceQuestion ?: record.content
    Panel {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("最新题目", fontWeight = FontWeight.SemiBold, fontSize = 18.sp)
            TextButton(onClick = open) { Text("查看完整题目") }
        }
        Text(question?.typeName ?: "题型待获取", color = Ink)
        Text("发题时间 " + receivedTime(record.receivedAt, true) + "（本机接收）" +
            if (record.recovered) " · 重连补收" else "", color = Muted, fontSize = 13.sp)
        question?.assets?.forEachIndexed { index, asset ->
            Text(asset.label.ifBlank { "课件原图" }, color = Muted, fontSize = 12.sp)
            LocalQuestionImage(imageFile(record.id, index), record.loading, record.imageErrors[index])
        }
        if (question?.assets?.isEmpty() == true) {
            Text(android.text.Html.fromHtml(question.bodyHtml, android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim())
            question.options.forEach { Text(it.label + ". " + android.text.Html.fromHtml(it.html,
                android.text.Html.FROM_HTML_MODE_COMPACT).toString().trim()) }
        }
        HorizontalDivider()
        ReferenceAnswer(record, ownAnswer, submit, submissionIssue(record, ownAnswer))
    }
}

@Composable fun ClassArchivePage(records: List<QuestionRecord>, choose: (String)->Unit) {
    val classrooms = StudyClassrooms.group(records)
    Page("题目") {
        if(classrooms.isEmpty()) Panel {
            Text("暂无课堂题目",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
            Text("开启发题通知或自动答题后，收到的题目会按课堂保存在这里。",color=Muted)
        }
        classrooms.forEach { group -> Panel {
            Text(group.server.label,color=Muted,fontSize=13.sp)
            InfoRow(group.title,receivedTime(group.firstReceived,true),"${group.records.size} 题","class") { choose(group.key) }
        } }
    }
}

@Composable fun ClassNotesPage(group: StudyClassroom?, answers: Map<String,AnswerTask>, busy: Boolean,
    back:()->Unit, choose:(String)->Unit, export:()->Unit, message: String = "") {
    Page(group?.title ?: "课堂题目",subtitle=group?.server?.label.orEmpty(),back=back) {
        if(group==null) Text("本课堂暂无记录。",color=Muted)
        else {
            Text(receivedTime(group.firstReceived,true)+" · 共 ${group.records.size} 题",color=Muted)
            OutlinedButton(onClick=export,enabled=!busy) { Text(if(busy) "正在导出…" else "导出课堂复习笔记") }
            Text("导出题干、选项、原图与 AI 答案，可离线阅读或打印。时间按本机收到题目记录。",color=Muted,fontSize=13.sp)
            if(message.isNotBlank()) Text(message,color=Muted,fontSize=13.sp)
            group.records.forEachIndexed { index, record -> Panel {
                InfoRow("第 ${index+1} 题 · "+(record.content?.typeName ?: "内容待获取"),
                    receivedTime(record.receivedAt)+(if(record.recovered) " · 重连补收" else ""),
                    answers[record.id]?.let { phaseTitle(it.phase) } ?: "已记录") { choose(record.id) }
            } }
        }
    }
}
