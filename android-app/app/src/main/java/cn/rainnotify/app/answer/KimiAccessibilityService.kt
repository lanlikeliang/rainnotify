package cn.rainnotify.app.answer

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.KeyguardManager
import android.graphics.Rect
import android.graphics.Path
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import cn.rainnotify.app.BuildConfig
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/** The component name is retained so existing accessibility grants survive upgrades. */
class KimiAccessibilityService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val poll = object : Runnable {
        override fun run() { inspect(); main.postDelayed(this, 500) }
    }
    override fun onServiceConnected() {
        connected = true
        instance = this
        cn.rainnotify.app.Diagnostics.record(this, "accessibility_connected")
        main.removeCallbacks(poll)
        main.post(poll)
    }
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.packageName?.toString() == KimiBridge.active?.provider?.packageName) inspect()
    }
    private var inspecting = false
    private fun inspect() {
        if (inspecting) return
        val request = KimiBridge.active ?: return
        if (!request.launched.get() || request.result.isDone) return
        inspecting = true
        try {
            val now = SystemClock.elapsedRealtime()
            if (now >= request.deadline) throw AnswerFailure("AI 作答超时；未提交。")
            KimiBridge.checkInstalled(this, request)
            if (now - maxOf(request.launchedAt, request.providerSeenAt) > 20_000) {
                throw AnswerFailure("${request.provider.title} 未能进入可操作的前台页面。请检查本 App 的后台弹出界面权限，或处理 AI App 的首次启动/登录提示后重试。")
            }
            if (getSystemService(KeyguardManager::class.java).isKeyguardLocked)
                throw AnswerFailure("手机已锁屏，AI 自动操作已停止。")
            val root = rootInActiveWindow ?: run { request.lastHint = "等待可读取的 AI 窗口"; return }
            if (root.packageName?.toString() != request.provider.packageName) {
                request.lastHint = "等待所选 AI App 到前台"; return
            }
            request.providerSeenAt = now
            val nodes = collect(root)
            debugControls(request, nodes)
            val visible = nodes.filter { it.isVisibleToUser }
            val attachments = visible.any { it.contentDescription?.toString() == "删除" ||
                it.viewIdResourceName == "com.larus.nova:id/file_resource_remove" }
            val draftText = visible.any { it.isEditable && !it.isShowingHintText && !it.text.isNullOrEmpty() &&
                it.text != it.hintText && it.text?.toString() !in setOf("给 DeepSeek 发送消息", "发消息...") }
            val answered = visible.any { it.contentDescription?.toString() in setOf("复制", "重新生成", "点赞", "点踩") }
            val deepseekBlank = request.provider == AiProvider.DEEPSEEK && !answered && DeepSeekPage.blank(nodes.map { node ->
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                DeepSeekPage.Node(nodes.indexOf(node.parent), node.className?.toString().orEmpty(),
                    node.contentDescription?.toString().orEmpty(), !node.text.isNullOrBlank(), node.isVisibleToUser,
                    node.isEditable, node.isClickable, node.isScrollable, bounds.left, bounds.top, bounds.right, bounds.bottom)
            })
            val doubaoBlank = request.provider == AiProvider.DOUBAO &&
                !answered && visible.any { it.viewIdResourceName == "com.larus.nova:id/larus_mode_switch_welcome_title" } &&
                visible.any { it.viewIdResourceName == "com.larus.nova:id/input_text" &&
                    (it.isShowingHintText || it.text.isNullOrEmpty() || it.text == it.hintText) }
            if (!request.shared) {
                val newChat = visible.firstOrNull { it.isEnabled && (it.contentDescription?.toString() ==
                        (if (request.provider == AiProvider.DEEPSEEK) "开启新对话" else "创建新对话") ||
                        (request.provider == AiProvider.DOUBAO && it.viewIdResourceName == "com.larus.nova:id/larus_chat_top_left_create_new_cvs")) }
                when (request.preparation.next(now, deepseekBlank || doubaoBlank, attachments || draftText, newChat != null)) {
                    ChatPreparation.Step.CREATE -> {
                        request.lastHint = "正在创建本题新对话"
                        if (!sendOnce(newChat!!, root)) throw AnswerFailure("无法创建 AI 新对话。")
                    }
                    ChatPreparation.Step.SHARE -> {
                        KimiBridge.share(this, request)
                        request.lastHint = "等待分享图片加载"
                    }
                    ChatPreparation.Step.BLOCK_DRAFT -> throw AnswerFailure("${request.provider.title} 有未发送草稿，请先处理草稿再重试；本 App 不会覆盖它。")
                    ChatPreparation.Step.FAILED -> throw AnswerFailure("未识别到 ${request.provider.title} 的空白对话页面，已停止；请反馈当前界面以继续适配。")
                    ChatPreparation.Step.WAIT -> request.lastHint = if (deepseekBlank || doubaoBlank) "已识别空白对话，等待页面稳定" else "等待 AI 加载或确认新对话"
                }
                return
            }
            // Share receivers append content asynchronously; do not fill/click an old composer during navigation.
            if (now - request.sharedAt < 2_500) return
            if (nodes.any { (it.text?.toString().orEmpty() + it.contentDescription?.toString().orEmpty()).contains("登录后") })
                throw AnswerFailure("所选 AI App 需要先登录，自动操作已停止。")
            if (!request.sent) {
                if (request.sendAttemptAt != 0L) {
                    val echoed = nodes.any { !it.isEditable && it.text?.toString() == request.prompt }
                    if (echoed) {
                        request.sent = true
                        request.sentAt = now
                        request.lastHint = "等待带本题标识的完整答案"
                    } else if (now - request.sendAttemptAt > 5_000) {
                        throw AnswerFailure("已尝试发送一次，但未确认题目进入对话；为避免重复发送已停止。")
                    }
                    return
                }
                if (nodes.any { !it.isEditable && it.text?.toString() == request.prompt }) {
                    // Some share receivers send immediately. Do not issue a second send.
                    if (request.prepared.images.isNotEmpty())
                        throw AnswerFailure("分享入口已发送文字，但尚未确认全部原图，已停止本题；不会再次发送或提交。")
                    request.sent = true
                    request.sentAt = now
                    return
                }
                val editor = nodes.singleOrNull { it.isEditable && it.isVisibleToUser } ?: return
                if (editor.text?.toString() != request.prompt) {
                    request.readySince = 0
                    val args = Bundle().apply { putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, request.prompt) }
                    if (!editor.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                        throw AnswerFailure("AI 输入框不允许自动填入本题。")
                    return
                }
                request.editorVerified = true
                request.lastHint = "等待所有图片附件就绪"
                // Attachment semantics are checked separately from the text. Never silently send a text-only image question.
                if (!attachmentsReady(nodes, request.prepared.images.size, request.provider)) { request.readySince = 0; return }
                if (nodes.any { it.text?.toString()?.contains("上传中") == true || it.contentDescription?.toString()?.contains("上传中") == true }) return
                if (request.readySince == 0L) { request.readySince = now; return }
                if (now - request.readySince < 1_000) return
                request.lastHint = "等待发送控件"
                val send = nodes.singleOrNull { it.contentDescription?.toString() ==
                    (if (request.provider == AiProvider.KIMI) "发送讯息" else "发送") && it.isVisibleToUser } ?: return
                request.sendAttemptAt = now
                request.lastHint = "确认题目已进入对话"
                if (!sendOnce(send, root)) throw AnswerFailure("系统未允许点击 AI 发送图标；已停止本题。")
                return
            }
            val stopping = nodes.any { (it.contentDescription?.toString().orEmpty() + it.text?.toString().orEmpty()) in
                setOf("停止生成", "停止回答", "停止", "Stop generating") }
            if (stopping) return
            val copy = nodes.filter { (it.contentDescription?.toString() == "复制" || it.text?.toString() == "复制") && it.isVisibleToUser }
            val voice = nodes.filter { it.isVisibleToUser && when (request.provider) {
                AiProvider.KIMI -> it.contentDescription?.toString() == "read_aloud"
                AiProvider.DOUBAO -> it.viewIdResourceName == "com.larus.nova:id/msg_action_re_tts"
                AiProvider.DEEPSEEK -> it.contentDescription?.toString() in setOf("重新生成", "点赞", "点踩", "朗读")
            } }
            val candidate = nodes.asSequence().filter { !it.isEditable && it.isVisibleToUser }.mapNotNull { node ->
                val value = node.text?.toString() ?: return@mapNotNull null
                if (!value.contains(request.task) || value == request.prompt) return@mapNotNull null
                val bounds = Rect().also { node.getBoundsInScreen(it) }
                val finished = copy.any { c ->
                    val cb = Rect().also { c.getBoundsInScreen(it) }
                    cb.top >= bounds.bottom - 24 && voice.any { v ->
                        val vb = Rect().also { v.getBoundsInScreen(it) }
                        kotlin.math.abs(vb.top - cb.top) < 30
                    }
                }
                // The engine validates the completed reply. Invalid/"unable" replies should report
                // their actual failure instead of silently waiting until the UI timer expires.
                if (finished) AiReplyText.normalize(request.provider.name, value) else null
            }.firstOrNull() ?: return
            if (request.candidate != candidate) {
                request.candidate = candidate
                request.candidateSince = now
            } else if (now - request.candidateSince >= 1_000 && now - request.sentAt >= 1_000) {
                request.result.complete(candidate)
            }
        } catch (e: Exception) {
            request.result.completeExceptionally((e as? AnswerFailure) ?: AnswerFailure("AI 界面与当前适配不一致，已停止本题。"))
        } finally { inspecting = false }
    }

    private fun attachmentsReady(nodes: List<AccessibilityNodeInfo>, expected: Int, provider: AiProvider): Boolean {
        if (expected == 0) return true
        val editor = nodes.singleOrNull { it.isEditable && it.isVisibleToUser } ?: return false
        val input = Rect().also { editor.getBoundsInScreen(it) }
        if (provider == AiProvider.DEEPSEEK) {
            // 2.6.1: each thumbnail has a semantic delete icon inside a separate clickable corner.
            val cards = nodes.filter { it.contentDescription?.toString() == "删除" && it.isVisibleToUser }
                .mapNotNull { close ->
                    val button = close.parent?.parent ?: return@mapNotNull null
                    val card = button.parent ?: return@mapNotNull null
                    val bounds = Rect().also { card.getBoundsInScreen(it) }
                    val thumbnail = (0 until card.childCount).mapNotNull { card.getChild(it) }
                        .any { it != button && it.isClickable && it.isVisibleToUser }
                    if (button.isClickable && button.isEnabled && thumbnail && bounds.bottom <= input.top && !bounds.isEmpty)
                        bounds.toShortString() else null
                }.distinct()
            return cards.size == expected
        }
        if (provider == AiProvider.DOUBAO) {
            val cards = nodes.filter { it.viewIdResourceName == "com.larus.nova:id/file_resource_remove" &&
                it.isVisibleToUser && it.isClickable && it.isEnabled }.mapNotNull { close ->
                val card = close.parent ?: return@mapNotNull null
                val bounds = Rect().also { card.getBoundsInScreen(it) }
                val image = collect(card).any { it.viewIdResourceName == "com.larus.nova:id/image" && it.isVisibleToUser }
                if (image && bounds.bottom <= input.top && !bounds.isEmpty) bounds.toShortString() else null
            }.distinct()
            return cards.size == expected
        }
        // Observed in Kimi 3.1.3: a thumbnail contains an edit marker and a clickable
        // close parent. Restrict matching to that card above the composer, not any dialog close.
        val cards = nodes.filter { it.contentDescription?.toString() == "关闭" && it.isVisibleToUser }
            .mapNotNull { close ->
                val button = close.parent ?: return@mapNotNull null
                val card = button.parent ?: return@mapNotNull null
                val bounds = Rect().also { card.getBoundsInScreen(it) }
                if (!button.isClickable || !button.isEnabled || bounds.bottom > input.top || bounds.isEmpty)
                    return@mapNotNull null
                val hasEdit = (0 until card.childCount).any {
                    card.getChild(it)?.contentDescription?.toString() == "edit"
                }
                if (hasEdit) bounds.toShortString() else null
            }.distinct()
        return cards.size == expected
    }
    private fun sendOnce(start: AccessibilityNodeInfo, root: AccessibilityNodeInfo): Boolean {
        val target = Rect().also { start.getBoundsInScreen(it) }
        val screen = Rect().also { root.getBoundsInScreen(it) }
        if (target.isEmpty || !screen.contains(target) || !start.isEnabled) return false
        var node: AccessibilityNodeInfo? = start
        repeat(5) {
            val value = node
            if (value != null) {
                val bounds = Rect().also { value.getBoundsInScreen(it) }
                // Never click the whole composer merely because it is an ancestor of send.
                if (value.isClickable && value.isEnabled && bounds.width() <= target.width() * 2 &&
                    bounds.height() <= target.height() * 2 && bounds.contains(target))
                    return value.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                node = value.parent
            }
        }
        // Kimi 3.1.3 exposes a semantic send icon but no actionable send ancestor.
        // Use its currently observed bounds once; there are no fixed device coordinates.
        val path = Path().apply { moveTo(target.exactCenterX(), target.exactCenterY()) }
        return dispatchGesture(GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 80)).build(), null, null)
    }
    private fun collect(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val nodes = ArrayList<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && nodes.size < 1200) {
            val node = queue.removeFirst()
            nodes.add(node)
            for (i in 0 until node.childCount) node.getChild(i)?.let { queue.add(it) }
        }
        return nodes
    }
    override fun onInterrupt() {
        cn.rainnotify.app.Diagnostics.record(this, "accessibility_interrupted")
        KimiBridge.cancel()
    }
    override fun onDestroy() {
        connected = false
        instance = null
        cn.rainnotify.app.Diagnostics.record(this, "accessibility_destroyed")
        AutoAnswer.accessibilityLost(this)
        main.removeCallbacksAndMessages(null)
        KimiBridge.cancel()
        super.onDestroy()
    }
    private var debugAt = 0L
    private fun debugControls(request: KimiBridge.Request, nodes: List<AccessibilityNodeInfo>) {
        if (!BuildConfig.DEBUG || SystemClock.elapsedRealtime() - debugAt < 1_000) return
        debugAt = SystemClock.elapsedRealtime()
        // Control metadata only. No arbitrary chat text, input prompt, account data or screenshots.
        val safe = setOf("复制", "发送", "发送讯息", "关闭", "删除", "上传文件", "停止生成", "停止回答", "停止",
            "read_aloud", "edit", "图片", "附件", "点赞", "点踩", "重新生成", "分享", "发消息...", "给 DeepSeek 发送消息",
            "开启新对话", "创建新对话")
        val array = JSONArray()
        nodes.forEachIndexed { index, node ->
            val text = node.text?.toString().orEmpty()
            array.put(JSONObject().put("i", index).put("parent", nodes.indexOf(node.parent))
                .put("class", node.className).put("id", node.viewIdResourceName)
                .put("desc", node.contentDescription?.toString()?.takeIf { it in safe }.orEmpty())
                .put("text", text.takeIf { it in safe }.orEmpty()).put("prompt", text == request.prompt)
                .put("nonce", text.contains(request.task)).put("editable", node.isEditable)
                .put("hasText", text.isNotBlank()).put("scrollable", node.isScrollable)
                .put("clickable", node.isClickable).put("enabled", node.isEnabled).put("visible", node.isVisibleToUser)
                .put("bounds", Rect().also { node.getBoundsInScreen(it) }.toShortString()))
        }
        try { File(noBackupFilesDir, "ai-controls.json").writeText(JSONObject().put("task", request.task)
            .put("provider", request.provider.name).put("sent", request.sent).put("hint", request.lastHint).put("nodes", array).toString()) }
        catch (_: Exception) { }
    }
    companion object {
        @Volatile var connected = false; private set
        @Volatile var instance: KimiAccessibilityService? = null; private set
    }
}

