package cn.rainnotify.app.ui

import android.graphics.BitmapFactory
import android.text.Html
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import cn.rainnotify.app.answer.*
import cn.rainnotify.app.auth.*
import cn.rainnotify.app.monitor.*
import cn.rainnotify.app.question.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

val Ink = Color(0xFF182D34)
val Teal = Color(0xFF087F76)
val Muted = Color(0xFF63767C)
val CanvasColor = Color(0xFFF5F7F8)
private val Outline = Color(0xFFD8E0E3)
val Amber = Color(0xFF946009)

@Composable fun RainTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = lightColorScheme(primary = Teal, onPrimary = Color.White,
        primaryContainer = Color(0xFFE0F2EE), onPrimaryContainer = Ink,
        background = CanvasColor, onBackground = Ink, surface = Color.White, onSurface = Ink,
        surfaceVariant = Color(0xFFEAF0F1), onSurfaceVariant = Muted, outline = Outline), content = content)
}

/** Minimal local line icons avoid a separate, large icon dependency. */
@Composable fun LineIcon(kind: String, modifier: Modifier = Modifier, color: Color = Muted) {
    Canvas(modifier.size(24.dp)) {
        val w = size.width; val h = size.height; val stroke = 1.8.dp.toPx()
        fun line(a: Float, b: Float, c: Float, d: Float) = drawLine(color, Offset(w*a,h*b),Offset(w*c,h*d),stroke)
        when(kind) {
            "class" -> { line(.12f,.45f,.5f,.12f);line(.5f,.12f,.88f,.45f);line(.22f,.4f,.22f,.86f);line(.22f,.86f,.78f,.86f);line(.78f,.86f,.78f,.4f);line(.45f,.85f,.45f,.6f);line(.45f,.6f,.6f,.6f) }
            "account" -> { drawCircle(color,w*.17f,Offset(w*.5f,h*.28f),style=Stroke(stroke));drawArc(color,180f,180f,false,Offset(w*.16f,h*.55f),androidx.compose.ui.geometry.Size(w*.68f,h*.52f),style=Stroke(stroke)) }
            "settings" -> { drawCircle(color,w*.34f,Offset(w*.5f,h*.5f),style=Stroke(stroke));drawCircle(color,w*.12f,Offset(w*.5f,h*.5f),style=Stroke(stroke));line(.5f,.05f,.5f,.18f);line(.5f,.82f,.5f,.95f);line(.05f,.5f,.18f,.5f);line(.82f,.5f,.95f,.5f) }
            "model" -> { line(.15f,.28f,.5f,.08f);line(.5f,.08f,.85f,.28f);line(.15f,.28f,.5f,.48f);line(.5f,.48f,.85f,.28f);line(.15f,.28f,.15f,.72f);line(.85f,.28f,.85f,.72f);line(.15f,.72f,.5f,.92f);line(.5f,.92f,.85f,.72f);line(.5f,.48f,.5f,.92f) }
            else -> { drawRect(color,Offset(w*.2f,h*.1f),androidx.compose.ui.geometry.Size(w*.6f,h*.8f),style=Stroke(stroke));line(.32f,.32f,.68f,.32f);line(.32f,.5f,.68f,.5f);line(.32f,.68f,.55f,.68f) }
        }
    }
}

@Composable fun Page(title: String, subtitle: String = "", back: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(top = 18.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (back != null) TextButton(onClick = back, modifier = Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)) { Text("‹", fontSize = 30.sp) }
            Column {
                Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = Ink)
                if (subtitle.isNotBlank()) Text(subtitle, color = Muted, fontSize = 14.sp, modifier = Modifier.padding(top=5.dp))
            }
        }
        content()
    }
}
@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(modifier.fillMaxWidth(), color = Color.White, shape = RoundedCornerShape(20.dp)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(14.dp), content = content)
    }
}
@Composable fun InfoRow(label: String, value: String, hint: String = "", kind: String = "question", action: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().then(if (action != null) Modifier.clickable(onClick = action) else Modifier)
        .sizeIn(minHeight = 54.dp).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        LineIcon(kind)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(label, fontSize = 12.sp, color = Muted)
            Text(value, color = Ink, fontWeight = FontWeight.Medium, fontSize = 16.sp)
        }
        if (hint.isNotEmpty()) Text(hint, color = Teal, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.widthIn(max = 90.dp))
        if (action != null) Text("›", color = Muted, fontSize = 22.sp)
    }
}

/** One bounded track, one raised thumb, three mutually exclusive radio targets. */
@Composable fun ModeSelector(mode: ClassroomMode, busy: Boolean = false, onSelect: (ClassroomMode) -> Unit) {
    val trackHeight = if (LocalDensity.current.fontScale > 1.3f) 90.dp else 60.dp
    BoxWithConstraints(Modifier.fillMaxWidth().testTag("mode-selector")
        .border(1.dp, Outline, RoundedCornerShape(50)).background(Color(0xFFE7ECEE), RoundedCornerShape(50)).padding(6.dp)) {
        val segment = maxWidth / 3
        val offset by animateDpAsState(segment * mode.ordinal, label = "selected-mode")
        Box(Modifier.offset { IntOffset(offset.roundToPx(), 0) }.width(segment).height(trackHeight)
            .shadow(4.dp, RoundedCornerShape(50)).background(Color.White, RoundedCornerShape(50)))
        Row(Modifier.fillMaxWidth().selectableGroup()) {
            ClassroomMode.entries.forEach { item ->
                Box(Modifier.weight(1f).height(trackHeight).clip(RoundedCornerShape(50))
                    .testTag("mode_${item.name}").selectable(item == mode, enabled = !busy || item == ClassroomMode.OFF,
                        role = Role.RadioButton, onClick = { onSelect(item) }).padding(horizontal = 4.dp), contentAlignment = Alignment.Center) {
                    Text(item.label, fontSize = 15.sp, textAlign = TextAlign.Center,
                        fontWeight = if (mode == item) FontWeight.Bold else FontWeight.Normal,
                        color = if (mode == item) Teal else Muted)
                }
            }
        }
    }
}

@Composable fun ClassroomPage(s: HomeState, account: () -> Unit, classroom: () -> Unit, api: () -> Unit,
    choose: (ClassroomMode) -> Unit, scan: (() -> Unit)? = null) {
    val confirmed = s.login.authenticated || s.monitor.established
    val connecting = s.monitor.active && !s.monitor.established
    Page("课堂") {
        Panel {
            InfoRow("当前账户", s.login.displayName ?: if (confirmed) "已授权账户" else "未授权",
                if (confirmed) "已授权" else if (s.login.busy) "验证中" else "去授权", "account", account)
            HorizontalDivider(color = CanvasColor)
            InfoRow("当前课堂", s.monitor.lessonName.takeIf { s.monitor.active } ?: s.lesson?.classroomName ?: "未选择",
                if (s.monitor.connection == ConnectionState.LISTENING) "已连接" else "选择课堂", "class", classroom)
            HorizontalDivider(color = CanvasColor)
            InfoRow("当前模型", if (s.apiSaved) s.model.displayName else "未配置",
                if (s.apiVerified) s.model.checkName + "通过" else if (s.apiSaved) "待自检" else "去配置", "model", api)
        }
        Surface(color = Color(0xFFE5F4F0), shape = RoundedCornerShape(24.dp)) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 24.dp), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)) {
                val title = when {
                    s.stopping -> "正在关闭…"
                    connecting -> "正在连接课堂…"
                    s.monitor.active && s.monitor.connection != ConnectionState.LISTENING -> "课堂连接恢复中"
                    s.mode == ClassroomMode.OFF -> "当前已关闭"
                    else -> s.mode.label + "已开启"
                }
                Text(title, fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
                Text(when(s.mode) {
                    ClassroomMode.OFF -> "选择模式，开始本次课堂"
                    ClassroomMode.NOTIFY -> "接收并提醒新题，不调用 AI"
                    ClassroomMode.AUTO -> "收到新题后自动作答并提交"
                }, color = Muted, fontSize = 14.sp, textAlign = TextAlign.Center)
                ModeSelector(s.mode, connecting || s.stopping, choose)
                Text(if (s.mode == ClassroomMode.AUTO) "仅对本次课堂开启后收到的新题生效" else "发题通知无需配置 AI 服务",
                    fontSize = 12.sp, color = Teal, textAlign = TextAlign.Center)
            }
        }
        if (!confirmed) Text("首次使用：先授权账户，再选择课堂。", color = Muted, fontSize = 14.sp)
        else Text("最新题目与处理结果，请到「题目」查看。", color = Muted, fontSize = 14.sp)
        if (s.message.isNotBlank()) Text(s.message, color = Muted, fontSize = 14.sp)
        if ((s.monitor.active && s.monitor.connection != ConnectionState.LISTENING) ||
            (!s.monitor.active && s.monitor.message !in setOf(MonitorUiState().message,
                "监听已关闭。", "已停止课堂监听。", "监听已停止，需要时请手动重新开始。")))
            Text(s.monitor.message, color = Amber, fontSize = 14.sp)
        if (scan != null) OutlinedButton(onClick = scan) { Text("前往微信完成课堂扫码") }
    }
}

@Composable fun QuestionPage(record: QuestionRecord?, answer: AnswerTask?, mode: ClassroomMode, imageFile: (String, Int) -> File?) {
    Page("题目") {
        if (record == null) {
            Panel { Text("等待课堂发题", fontSize = 20.sp, fontWeight = FontWeight.SemiBold);Text("在课堂页开启发题通知或自动答题后，最新题目会显示在这里。", color = Muted) }
        } else {
            val q = record.content
            Text((q?.typeName ?: "正在获取") + " · " + SimpleDateFormat("HH:mm", Locale.CHINA).format(Date(record.receivedAt)), color = Muted)
            Panel {
                if (record.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
                if (q == null) Text(record.message, color = Muted)
                else {
                    val body = Html.fromHtml(q.bodyHtml, Html.FROM_HTML_MODE_COMPACT).toString().trim()
                    if (body.isNotBlank()) Text(body, fontSize = 18.sp, lineHeight = 28.sp)
                    q.options.forEach { Text(it.label + ". " + Html.fromHtml(it.html, Html.FROM_HTML_MODE_COMPACT).toString().trim(), lineHeight = 25.sp) }
                    q.assets.forEachIndexed { index, asset ->
                        Text(asset.label.ifBlank { "题目原图 ${index + 1}" }, fontSize = 12.sp, color = Muted)
                        LocalQuestionImage(imageFile(record.id, index), record.loading, record.imageErrors[index])
                    }
                    if (record.imageErrors.isNotEmpty()) Text("部分原图未获取，自动作答将检查题目完整性。", color = Amber)
                }
            }
            val ownAnswer = answer?.takeIf { ModePolicy.answerBelongsToLatest(record.id, it.questionId) }
            if (ownAnswer?.answer?.isNotEmpty() == true) Panel {
                Text("AI 答案", fontWeight = FontWeight.SemiBold)
                Text(ownAnswer.answer.joinToString(if (q?.type == 4) "\n" else "、"), fontSize = 22.sp, lineHeight = 32.sp)
                Text(ownAnswer.model.displayName, color = Muted, fontSize = 12.sp)
            }
            Panel {
                Text("处理状态", fontWeight = FontWeight.SemiBold)
                if (ownAnswer?.active == true) LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (ownAnswer == null) {
                    when {
                        record.loading -> "正在获取题目…"
                        mode == ClassroomMode.AUTO -> "本题尚无作答结果；只处理开启后收到且可作答的新题。"
                        mode == ClassroomMode.NOTIFY -> "当前为发题通知，不会自动作答。"
                        else -> "当前已关闭，保留本题最后状态。"
                    }
                } else phaseTitle(ownAnswer.phase), fontSize = 20.sp,
                    color = if (ownAnswer?.phase == AnswerPhase.SUBMITTED) Teal else Ink)
                Text(ownAnswer?.message ?: record.message, color = Muted, lineHeight = 23.sp)
            }
            Text("收到新题后，本页自动更新。", color = Muted, fontSize = 12.sp)
        }
    }
}
fun phaseTitle(phase: AnswerPhase) = when(phase) {
    AnswerPhase.QUEUED -> "等待处理"
    AnswerPhase.PREPARING -> "正在获取题目"
    AnswerPhase.SOLVING -> "AI 正在作答"
    AnswerPhase.CHECKING -> "正在校验答案"
    AnswerPhase.SUBMITTING -> "正在提交"
    AnswerPhase.SUBMITTED -> "已提交 · 雨课堂已确认"
    AnswerPhase.ALREADY_ANSWERED -> "本题已作答，未重复提交"
    AnswerPhase.UNCERTAIN -> "提交结果待确认"
    AnswerPhase.CANCELLED -> "已停止"
    AnswerPhase.FAILED -> "未提交"
    AnswerPhase.REHEARSED -> "未提交"
}

@Composable private fun LocalQuestionImage(file: File?, loading: Boolean, error: String?) {
    if (file == null) { Text(error ?: if (loading) "正在加载原图…" else "原图暂不可用", color = Muted); return }
    val stamp = file.lastModified()
    val bitmap by produceState<android.graphics.Bitmap?>(null, file.path, stamp, loading) {
        value = withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, options)
                if (options.outWidth < 1 || options.outHeight < 1) null else {
                    options.inJustDecodeBounds = false
                    while (maxOf(options.outWidth, options.outHeight) / options.inSampleSize > 2048) options.inSampleSize *= 2
                    BitmapFactory.decodeFile(file.path, options)
                }
            } catch (_: Exception) { null }
        }
    }
    var expanded by remember(file.path) { mutableStateOf(false) }
    val loaded = bitmap
    if (loaded != null) {
        Image(loaded.asImageBitmap(), "题目原图，点击放大", Modifier.fillMaxWidth().heightIn(max = 420.dp)
            .clip(RoundedCornerShape(10.dp)).clickable { expanded = true }, contentScale = ContentScale.Fit)
        if (expanded) Dialog(onDismissRequest = { expanded = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
            var scale by remember { mutableFloatStateOf(1f) }
            var pan by remember { mutableStateOf(Offset.Zero) }
            Column(Modifier.fillMaxSize().background(Ink).systemBarsPadding()) {
                TextButton(onClick = { expanded = false }) { Text("关闭原图", color = Color.White) }
                Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(0.dp)).pointerInput(Unit) {
                    detectTransformGestures { _, offset, zoom, _ -> scale = (scale * zoom).coerceIn(1f, 6f); pan = if (scale == 1f) Offset.Zero else pan + offset }
                }, contentAlignment = Alignment.Center) {
                    Image(loaded.asImageBitmap(), "可双指缩放的原图", Modifier.fillMaxSize().graphicsLayer {
                        scaleX = scale; scaleY = scale; translationX = pan.x; translationY = pan.y
                    }, contentScale = ContentScale.Fit)
                }
            }
        }
    } else Text(error ?: if (loading) "正在加载原图…" else "原图暂不可用", color = Muted)
}

@Preview(showBackground = true, widthDp = 412, heightDp = 880)
@Composable private fun FirstUsePreview() = RainTheme {
    ClassroomPage(HomeState(login = LoginUiState(busy = false)), {}, {}, {}, {})
}
@Preview(showBackground = true, widthDp = 412, heightDp = 880)
@Composable private fun AutoModePreview() = RainTheme {
    ClassroomPage(HomeState(login = LoginUiState(busy=false, authenticated=true, displayName="用户 A"),
        lesson=LessonSummary("demo","通识课程"), monitor=MonitorUiState(active=true, established=true,
            connection=ConnectionState.LISTENING,lessonName="通识课程"), mode=ClassroomMode.AUTO, apiSaved=true,apiVerified=true), {}, {}, {}, {})
}
@Preview(showBackground = true, widthDp = 360, heightDp = 900, fontScale = 1.5f)
@Composable private fun LargeTypePreview() = RainTheme { ClassroomPage(HomeState(), {}, {}, {}, {}) }
