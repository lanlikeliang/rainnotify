package cn.rainnotify.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import androidx.core.net.toUri
import cn.rainnotify.app.answer.*
import cn.rainnotify.app.auth.RainLoginViewModel
import cn.rainnotify.app.ui.*
import cn.rainnotify.app.question.StudyClassrooms
import cn.rainnotify.app.monitor.ConnectionState

enum class AppPage { CLASSROOM, QUESTION, SETTINGS, ACCOUNT, API, NOTIFICATIONS, BACKGROUND, ABOUT, CLASSES, CLASS_NOTES, QUESTION_DETAIL }

class MainActivity : ComponentActivity() {
    private lateinit var home: HomeViewModel
    private lateinit var login: RainLoginViewModel
    private var navigationEvent by mutableIntStateOf(0)
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { home.refresh() }
    private var exportClassKey: String? = null
    private val studyExport = registerForActivityResult(ActivityResultContracts.CreateDocument("text/html")) { uri ->
        val key = exportClassKey; exportClassKey = null
        if(uri != null && key != null) home.exportClassroom(uri,key)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString("export_classroom",exportClassKey); super.onSaveInstanceState(outState)
    }
    private val export = registerForActivityResult(ActivityResultContracts.CreateDocument("application/x-ndjson")) { uri ->
        if (uri != null) try {
            contentResolver.openOutputStream(uri)?.use { out -> Diagnostics.reportFile(this).inputStream().use { it.copyTo(out) } }
                ?: error("unavailable")
            toast("脱敏诊断已导出。")
        } catch (_: Exception) { toast("导出失败，请重试。") }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        exportClassKey = savedInstanceState?.getString("export_classroom")
        enableEdgeToEdge(SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT))
        ProbeNotifications.createChannels(this); Diagnostics.recordDevice(this); AnswerState.restore(this)
        home = ViewModelProvider(this)[HomeViewModel::class.java]
        login = ViewModelProvider(this)[RainLoginViewModel::class.java]
        home.bindLogin(login.state)
        setContent { RainTheme { App() } }
    }
    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); navigationEvent++ }
    override fun onResume() { super.onResume(); Diagnostics.appForeground = true; if (::home.isInitialized) home.refresh() }
    override fun onPause() { Diagnostics.appForeground = false; super.onPause() }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable private fun App() {
        val s by home.state.observeAsState(HomeState())
        var route by rememberSaveable { mutableStateOf(AppPage.CLASSROOM.name) }
        val page = AppPage.valueOf(route.substringAfterLast('>'))
        fun go(target: AppPage) { if(target != page) route += ">" + target.name }
        fun back() { route = if('>' in route) route.substringBeforeLast('>') else AppPage.CLASSROOM.name }
        var selectedClass by rememberSaveable { mutableStateOf("") }
        var selectedQuestion by rememberSaveable { mutableStateOf("") }
        var scanConfirmation by remember { mutableStateOf(false) }
        var missing by remember { mutableStateOf<List<Requirement>>(emptyList()) }
        var confirmation by remember { mutableStateOf<ClassroomMode?>(null) }
        var logoutConfirmation by remember { mutableStateOf(false) }
        var deleteConfirmation by remember { mutableStateOf(false) }
        LaunchedEffect(navigationEvent) {
            when (intent.getStringExtra(TAB)) {
                "question" -> route = AppPage.CLASSROOM.name
                "classroom" -> route = AppPage.CLASSROOM.name
                "account" -> route = "CLASSROOM>ACCOUNT"
            }
            intent.removeExtra(TAB)
        }

        BackHandler(page != AppPage.CLASSROOM) { back() }
        Scaffold(containerColor = CanvasColor, bottomBar = {
            if (page in listOf(AppPage.CLASSROOM, AppPage.QUESTION, AppPage.SETTINGS)) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf(Triple(AppPage.CLASSROOM,"课堂","class"), Triple(AppPage.QUESTION,"题目","question"), Triple(AppPage.SETTINGS,"设置","settings")).forEach { (target,label,icon) ->
                    NavigationBarItem(selected = page == target, onClick = { route = target.name }, icon = { LineIcon(icon, color=if(page==target) Teal else Muted) }, label = { Text(label) })
                }
            }
        }) { padding ->
            Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
                when(page) {
                    AppPage.CLASSROOM -> ClassroomPage(s, { go(AppPage.ACCOUNT) }, { go(AppPage.CLASSES) }, { go(AppPage.API) }, choose = { target ->
                        if (target == ClassroomMode.OFF || (target == ClassroomMode.NOTIFY && s.monitor.active)) home.request(target)
                        else if (target != s.mode) {
                            val required = home.requirements(target)
                            if (required.isNotEmpty()) missing=required else confirmation=target
                        }
                    },
                        latest = { s.latest?.let { selectedQuestion=it.id;go(AppPage.QUESTION_DETAIL) } })
                    AppPage.QUESTION -> ClassArchivePage(s.records) { selectedClass=it;go(AppPage.CLASS_NOTES) }
                    AppPage.CLASS_NOTES -> {
                        val group = StudyClassrooms.group(s.records).firstOrNull { it.key==selectedClass }
                        ClassNotesPage(group,s.answers,s.exportBusy,::back,{selectedQuestion=it;go(AppPage.QUESTION_DETAIL)},{
                            group?.let {
                                exportClassKey=it.key
                                studyExport.launch("课堂复习-"+it.title.replace(Regex("[\\\\/:*?\"<>|]"),"-").take(50)+"-"+receivedTime(it.firstReceived,true).take(10).replace("/", "-")+".html")
                            }
                        },s.message)
                    }
                    AppPage.QUESTION_DETAIL -> {
                        val record = s.records.firstOrNull { it.id==selectedQuestion } ?: s.latest?.takeIf { it.id==selectedQuestion }
                        QuestionPage(record,s.answers[selectedQuestion] ?: s.answer?.takeIf { it.questionId==selectedQuestion },ClassroomMode.OFF,
                            { id,index -> home.repository.image(id,index) },::back)
                    }
                    AppPage.CLASSES -> Page("当前课堂","先在微信扫码入课，再连接本 App",::back) {
                        if(!s.login.authenticated) Button(onClick={go(AppPage.ACCOUNT)}) { Text("先授权雨课堂账号") }
                        else {
                            if(s.login.lessons.isEmpty()) Text("暂未发现正在上课的课堂。请先在微信扫码，再刷新。",color=Muted)
                            s.login.lessons.forEach { lesson -> Panel {
                                InfoRow(lesson.courseName ?: "课程名称暂未返回",lesson.classroomName,
                                    if(s.lesson?.id==lesson.id || s.monitor.lessonId==lesson.id) "已选择" else "选择", "class") { home.selectLesson(lesson) }
                            } }
                            OutlinedButton(onClick={login.refresh()},enabled=!s.login.busy) { Text("刷新课堂") }
                            if(s.lesson!=null || s.monitor.lessonId!=null) Panel {
                                Text(s.monitor.lessonName.takeIf { s.monitor.active } ?: s.lesson?.displayName.orEmpty(),fontWeight=FontWeight.SemiBold)
                                Text(when {
                                    s.monitor.active && s.monitor.connection==ConnectionState.LISTENING -> "课堂已连接，可以接收新题。"
                                    s.monitor.active -> "正在验证课堂连接…"
                                    else -> "尚未确认连接。请用微信扫描教师展示的课堂二维码。"
                                },color=if(s.monitor.established && s.monitor.active) Teal else Muted)
                                Text("扫码入课或签到是否成功，以微信提示为准；这里会单独显示监听连接结果。",color=Muted,fontSize=13.sp)
                                if(!s.monitor.active) {
                                    OutlinedButton(onClick={openWeChat()}) { Text("打开微信扫码") }
                                    Button(onClick={scanConfirmation=true},enabled=s.lesson!=null) { Text("我已扫码，连接此课堂") }
                                    if(s.monitor.scanRequiredLessonId!=null) Text(s.monitor.message,color=Amber)
                                } else Text("如需更换课堂，请先回课堂页关闭监听。",color=Muted)
                            }
                            if(s.message.isNotBlank()) Text(s.message,color=Amber)
                        }
                    }
                    AppPage.SETTINGS -> Page("设置","连接与运行配置") {
                        Text("账号",fontWeight=FontWeight.SemiBold)
                        Panel { InfoRow("雨课堂账号",s.login.displayName ?: if(s.login.authenticated) "已授权" else "未授权",kind="account") { go(AppPage.ACCOUNT) } }
                        Text("AI 服务",fontWeight=FontWeight.SemiBold)
                        Panel {
                            InfoRow("服务商与模型",s.model.displayName, s.model.capability, "model") { go(AppPage.API) }
                            HorizontalDivider()
                            InfoRow("API Key",if(s.apiSaved) "已加密保存" else "未配置") { go(AppPage.API) }
                            Text(if(s.apiVerified) s.model.checkName+"通过" else "当前模型待自检",color=if(s.apiVerified) Teal else Muted)
                            TextButton(onClick={go(AppPage.API)}) { Text(if(s.apiVerified) "查看 / 重新自检" else "配置并自检") }
                        }
                        Text("运行保障",fontWeight=FontWeight.SemiBold)
                        Panel {
                            InfoRow("通知与声音",if(s.notification?.canPost==true) "已允许" else "待设置") { go(AppPage.NOTIFICATIONS) }
                            HorizontalDivider()
                            InfoRow("后台运行",if(s.notification?.backgroundRestricted==true) "系统限制中" else "查看运行条件") { go(AppPage.BACKGROUND) }
                        }
                        TextButton(onClick={go(AppPage.ABOUT)}) { Text("关于与帮助") }
                    }
                    AppPage.ACCOUNT -> Page("雨课堂账号","微信授权后读取当前课堂",::back) {
                        Panel {
                            Text(s.login.displayName ?: if(s.login.authenticated) "雨课堂已授权" else "普通版雨课堂",fontSize=20.sp,fontWeight=FontWeight.SemiBold)
                            if(s.login.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(s.login.message,color=Muted)
                            s.login.qr?.let { qr ->
                                Image(qr.asImageBitmap(),"微信授权二维码",Modifier.fillMaxWidth().heightIn(max=280.dp))
                                Button(onClick={login.saveQr()},enabled=!s.login.qrSaved) { Text(if(s.login.qrSaved) "二维码已保存" else "保存二维码到相册") }
                                OutlinedButton(onClick={openWeChat()}) { Text("打开微信扫码授权") }
                            }
                            if(!s.login.authenticated && !s.login.busy) Button(onClick={home.beforeLogout();login.startLogin()}) { Text("生成微信授权二维码") }
                            if(s.login.busy) TextButton(onClick={login.cancelLogin()}) { Text("取消本次操作") }
                            if(s.login.authenticated) {
                                OutlinedButton(onClick={login.refresh()},enabled=!s.login.busy) { Text("刷新当前课堂") }
                                TextButton(onClick={logoutConfirmation=true}) { Text("退出本 App 账号") }
                            }
                        }
                    }
                    AppPage.API -> ApiPage(s,::back,{value->home.saveAndCheck(value)},{home.verifyApi()},{home.cancelApiCheck()},{deleteConfirmation=true},{home.selectModel(it)})
                    AppPage.NOTIFICATIONS -> Page("通知与声音","新题和作答结果使用系统通知",::back) {
                        Panel {
                            InfoRow("通知权限",if(s.notification?.canPost==true) "已允许" else "待设置") {
                                if(s.notification?.runtimeGranted!=true) permission.launch(Manifest.permission.POST_NOTIFICATIONS) else openNotificationSettings()
                            }
                            InfoRow("提醒声音",if(s.notification?.soundConfigured==true && (s.notification?.volume ?: 0)>0) "已配置，仍需实际确认" else "声音或通知音量未开启") { openChannelSettings() }
                            Button(onClick={
                                if(!NotificationReadiness.read(this@MainActivity).canPost) toast("请先允许系统通知和题目渠道。")
                                else if(!SimulationGate.reserve(this@MainActivity)) toast("请间隔两分钟再测试，避免通知冷却影响。")
                                else { ProbeNotifications.simulate(this@MainActivity);toast("已发送，请确认是否看到或听到提醒。") }
                            }) { Text("发送测试通知") }
                            TextButton(onClick={openNotificationSettings()}) { Text("打开系统通知设置") }
                            TextButton(onClick={openChannelSettings()}) { Text("题目渠道：声音、振动与横幅") }
                        }
                        Text("静音、勿扰、通知音量及厂商的横幅设置都可能影响提醒。通知已允许不等于实际有声音。",color=Muted)
                    }
                    AppPage.BACKGROUND -> Page("后台运行","只需配置伞课堂本身",::back) {
                        Panel {
                            InfoRow("系统后台限制",if(s.notification?.backgroundRestricted==true) "已检测到" else "未检测到") { openAppDetails() }
                            InfoRow("系统省电优化",if(s.notification?.batteryExempt==true) "已豁免" else "未豁免，熄屏联网可能受限") { openSystem(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) }
                            Text("小米等机型建议允许本 App 后台自启动，并将应用省电策略设为无限制。厂商自启动状态需手动确认。",color=Muted)
                            Button(onClick={openAppDetails()}) { Text("前往本 App 系统设置") }
                        }
                        Text("无需无障碍、关联启动、安装 AI App 或提供锁屏密码。后台运行仍依赖网络与手机省电策略。",color=Muted)
                    }
                    AppPage.ABOUT -> Page("关于与帮助",getString(R.string.app_name)+" · "+BuildConfig.VERSION_NAME,::back) {
                        Panel {
                            Text("关闭：停止监听与自动处理。\n发题通知：接收、提醒并保存新题。\n自动答题：调用当前 API，校验后提交。",lineHeight=26.sp)
                            Text("自动答题只对本次课堂开启后收到的新题生效。题目与结果按课堂保存在本机，可从题目页导出复习笔记。AI 可能答错；已发出的提交请求无法撤销。",color=Muted)
                            Text("API Key 与雨课堂凭据加密保存在本机；只有题干与原图发往当前模型服务商。",color=Muted)
                            InfoRow("GitHub 仓库", "lanlikeliang/rainnotify", "打开") { openRepository() }
                            OutlinedButton(onClick={export.launch("rainnotify-diagnostics.jsonl")}) { Text("导出脱敏诊断") }
                            Text("长时熄屏、多品牌及真实课堂提交仍需持续验证。",color=Muted,fontSize=12.sp)
                        }
                    }
                }
            }
        }
        if(missing.isNotEmpty()) ModalBottomSheet(onDismissRequest={missing=emptyList()}) {
            Column(Modifier.padding(24.dp).padding(bottom=24.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("开始前还需要",fontSize=24.sp,fontWeight=FontWeight.Bold)
                missing.forEach { requirement -> TextButton(onClick={
                    val destination=when(requirement) {
                        Requirement.ACCOUNT -> AppPage.ACCOUNT
                        Requirement.CLASSROOM,Requirement.CLASSROOM_SCAN -> AppPage.CLASSES
                        Requirement.NOTIFICATIONS -> AppPage.NOTIFICATIONS
                        Requirement.BACKGROUND -> AppPage.BACKGROUND
                        Requirement.API_KEY,Requirement.API_CHECK -> AppPage.API
                    };go(destination);missing=emptyList()
                }) { Text(requirement.label+"  ›") } }
                Text("完成后返回课堂，再选择模式。当前模式保持不变。",color=Muted)
            }
        }
        if(scanConfirmation) AlertDialog(onDismissRequest={scanConfirmation=false},title={Text("连接扫码后的课堂？")},
            text={Text("请确认微信已提示入课成功，且这里选中了同一课堂。接下来获取监听凭据；该接口可能刷新签到记录。连接成功后先接收发题通知，自动答题需回课堂主动开启。")},
            confirmButton={TextButton(onClick={
                scanConfirmation=false;home.confirmScanned()
                val required=home.requirements(ClassroomMode.NOTIFY)
                if(required.isNotEmpty()) missing=required else home.request(ClassroomMode.NOTIFY)
            }){Text("确认并连接")}},dismissButton={TextButton(onClick={scanConfirmation=false}){Text("取消")}})
        confirmation?.let { target -> AlertDialog(onDismissRequest={confirmation=null},title={Text("开启"+target.label+"？")},text={
            Text((if(!s.monitor.active) "请先确认已在微信扫码进入此课堂。连接需获取监听凭据，该接口仍可能刷新签到记录。\n\n" else "")+
                if(target==ClassroomMode.AUTO) "新题的题干和原图会发送到 DeepSeek，按服务商规则计费；有效答案将自动提交到雨课堂。AI 可能答错。\n\n停止或切回发题通知可取消后续处理，已发出的提交无法撤销。"
                else "只接收并提醒新题，不调用 API，不自动提交。\n\n切出 App 或熄屏时，请保持本 App 后台运行与网络可用。")
        },confirmButton={TextButton(onClick={confirmation=null;home.request(target)}){Text("确认开启")}},dismissButton={TextButton(onClick={confirmation=null}){Text("取消")}}) }
        if(logoutConfirmation) AlertDialog(onDismissRequest={logoutConfirmation=false},title={Text("退出雨课堂账号？")},text={Text("会关闭监听与自动答题。已发出的提交无法撤销，API 配置保留。")},
            confirmButton={TextButton(onClick={logoutConfirmation=false;home.beforeLogout();login.logout()}){Text("退出")}},dismissButton={TextButton(onClick={logoutConfirmation=false}){Text("取消")}})
        if(deleteConfirmation) AlertDialog(onDismissRequest={deleteConfirmation=false},title={Text("删除本机 API Key？")},text={Text("请先关闭自动答题再删除密钥；发题通知不受影响。")},
            confirmButton={TextButton(onClick={deleteConfirmation=false;home.deleteKey()}){Text("删除")}},dismissButton={TextButton(onClick={deleteConfirmation=false}){Text("取消")}})
    }
    private fun openRepository() {
        val url = "https://github.com/lanlikeliang/rainnotify"
        try { startActivity(Intent(Intent.ACTION_VIEW, url.toUri())) }
        catch (_: RuntimeException) { toast("无法打开浏览器，请手动访问：" + url) }
    }
    private fun openWeChat() {
        val launch=packageManager.getLaunchIntentForPackage("com.tencent.mm")
        if(launch==null) toast("请手动打开微信完成扫码，再返回本 App。") else openSystem(launch)
    }
    private fun openNotificationSettings() = openSystem(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE,packageName))
    private fun openChannelSettings() = openSystem(Intent(Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE,packageName).putExtra(Settings.EXTRA_CHANNEL_ID,ProbeNotifications.QUESTION_CHANNEL))
    private fun openAppDetails() = openSystem(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,"package:$packageName".toUri()))
    private fun openSystem(intent:Intent) { try { startActivity(intent) } catch(_:RuntimeException){ toast("无法打开该页面，请在系统设置中查找伞课堂。") } }
    private fun toast(text:String) = Toast.makeText(this,text,Toast.LENGTH_LONG).show()
    companion object { const val TAB="tab" }
}

@Composable private fun ApiPage(s:HomeState, back:()->Unit, save:(String)->Unit, verify:()->Unit, cancel:()->Unit, delete:()->Unit, select:(DeepSeekModel)->Unit) {
    var input by remember { mutableStateOf("") } // Never save a key in Activity/Compose saved state.
    var editing by remember { mutableStateOf(false) }
    var selecting by remember { mutableStateOf(false) }
    if(selecting) AlertDialog(onDismissRequest={selecting=false},title={Text("选择模型")},text={
        Column {
            DeepSeekModel.entries.forEach { model ->
                InfoRow(model.label,model.capability,if(model==s.model) "已选择" else "") { selecting=false;select(model) }
            }
            Text("切换后需重新自检。Pro 遇到图片会停止本题并提示切换 Flash。",color=Muted,fontSize=13.sp)
        }
    },confirmButton={TextButton(onClick={selecting=false}){Text("取消")}})
    Page("AI 服务","当前使用 DeepSeek 官方 API",back) {
        Panel {
            InfoRow("服务商","DeepSeek",kind="model")
            InfoRow("当前模型",s.model.label,s.model.capability,"model",action=if(s.apiBusy) null else ({selecting=true}))
            if(!s.model.supportsImages) Text("Pro 仅处理纯文字题。含图题不会发送或提交，请切换 Flash 并通过图文自检。",color=Amber,fontSize=13.sp)
            HorizontalDivider()
            if(!s.apiSaved || editing) {
                OutlinedTextField(value=input,onValueChange={input=it},label={Text("API Key")},singleLine=true,
                    visualTransformation=PasswordVisualTransformation(),modifier=Modifier.fillMaxWidth(),enabled=!s.apiBusy)
                Button(onClick={val value=input;input="";editing=false;save(value)},enabled=input.isNotBlank()&&!s.apiBusy) { Text("保存并进行"+s.model.checkName) }
            } else {
                Text("密钥已加密保存，不回显。",color=Muted)
                TextButton(onClick={editing=true},enabled=!s.apiBusy) { Text("更换密钥") }
            }
            Text("题干和原图会发送到 api.deepseek.com，按服务商规则计费；雨课堂登录凭据不会发给模型。",color=Muted,fontSize=13.sp)
        }
        Panel {
            Text("当前模型"+s.model.checkName,fontWeight=FontWeight.SemiBold)
            if(s.apiBusy) LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(if(s.apiVerified) s.model.checkName+"通过" else if(s.apiBusy) "正在自检…" else "尚未通过自检",color=if(s.apiVerified) Teal else Muted)
            if(s.apiMessage.isNotBlank()) Text(s.apiMessage,color=Muted)
            if(s.apiBusy) TextButton(onClick=cancel){Text("停止自检")}
            else OutlinedButton(onClick=verify,enabled=s.apiSaved){Text(if(s.apiVerified) "重新自检" else "开始"+s.model.checkName)}
            Text((if(s.model.supportsImages) "验证单图、多图和答案格式。" else "验证单选、多选文字题和答案格式，不验证图片。")+"不向课堂提交，自检时请保持本页可见。",fontSize=13.sp,color=Muted)
        }
        Text("自动答题运行时不能自检或修改 API；请先在课堂切回发题通知。自检不会悄悄改变当前模式。",color=Muted,fontSize=13.sp)
        if(s.apiSaved) TextButton(onClick=delete,enabled=!s.apiBusy){Text("删除本机密钥")}
    }
}
