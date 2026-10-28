package cn.rainnotify.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import cn.rainnotify.app.answer.AnswerTask
import cn.rainnotify.app.question.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun receivedTime(at: Long, full: Boolean = false): String = SimpleDateFormat(if(full) "yyyy/MM/dd HH:mm" else "HH:mm", Locale.CHINA).format(Date(at))

@Composable fun LatestQuestionSummary(record: QuestionRecord?, answer: AnswerTask?, open: () -> Unit) {
    if (record == null) return
    Panel(Modifier.clickable(onClick=open)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceBetween) {
            Text("收到 "+receivedTime(record.receivedAt,true)+" · "+(record.content?.typeName ?: "题型待获取")+(if(record.recovered) " · 补收" else ""),color=Muted,fontSize=13.sp)
            Text("›",color=Muted)
        }
        Text(answer?.answer?.takeIf { it.isNotEmpty() }?.joinToString(if(record.content?.type==4) " / " else "、") ?: "暂无答案",
            fontSize=20.sp,fontWeight=FontWeight.SemiBold,maxLines=3)
        Text(answer?.let { phaseTitle(it.phase) } ?: if(record.loading) "正在获取题目" else "已记录题目",color=Teal,fontSize=14.sp)
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
            InfoRow(group.title,receivedTime(group.firstReceived,true),"${group.records.size} 题","class") { choose(group.key) }
        } }
    }
}

@Composable fun ClassNotesPage(group: StudyClassroom?, answers: Map<String,AnswerTask>, busy: Boolean,
    back:()->Unit, choose:(String)->Unit, export:()->Unit, message: String = "") {
    Page(group?.title ?: "课堂题目",back=back) {
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
