package cn.rainnotify.app.question

import cn.rainnotify.app.auth.FailureReason
import cn.rainnotify.app.auth.RainFailure
import cn.rainnotify.app.auth.RainProtocol
import cn.rainnotify.app.monitor.LessonProtocol
import org.json.JSONArray
import org.json.JSONObject

class QuestionRef(
    val lessonId: String,
    val presentationId: String,
    val problemId: String,
    val slideId: String? = null,
    val slideIndex: Int? = null,
    val groupWork: Boolean = false,
) {
    override fun toString() = "QuestionRef(redacted)"
    fun json(): JSONObject = JSONObject().put("lesson", lessonId).put("presentation", presentationId)
        .put("problem", problemId).put("slide", slideId).put("index", slideIndex).put("group_work", groupWork)

    companion object {
        fun fromJson(value: JSONObject): QuestionRef {
            fun required(key: String) = LessonProtocol.id(value.opt(key)) ?: invalidContent()
            return QuestionRef(required("lesson"), required("presentation"), required("problem"),
                LessonProtocol.id(value.opt("slide")), value.optInt("index", 0).takeIf { it > 0 }, value.optBoolean("group_work", false))
        }
    }
}

internal fun invalidContent(): Nothing =
    throw RainFailure(FailureReason.RESPONSE_FORMAT, "question_content")

class QuestionOption(val label: String, val key: String?, val html: String) {
    override fun toString() = "QuestionOption(redacted)"
    fun json() = JSONObject().put("label", label).put("key", key).put("html", html)
}

class QuestionAsset(val label: String, val url: String) {
    override fun toString() = "QuestionAsset(redacted)"
    fun json() = JSONObject().put("label", label).put("url", url)
}

class QuestionContent(
    val type: Int,
    val bodyHtml: String,
    val options: List<QuestionOption>,
    val assets: List<QuestionAsset>,
    val pageIndex: Int?,
    val notes: List<String>,
    val blankCount: Int? = null,
    val pollingCount: Int? = null,
) {
    val typeName: String get() = when (type) {
        1 -> "单选"; 2 -> "多选"; 3 -> "投票"; 4 -> "填空"; 5 -> "主观"; 6 -> "判断"
        else -> "未知题型（" + type + "）"
    }
    override fun toString() = "QuestionContent(redacted)"
    fun json(): JSONObject = JSONObject().put("type", type).put("body", bodyHtml)
        .put("options", JSONArray(options.map { it.json() })).put("assets", JSONArray(assets.map { it.json() }))
        .put("page", pageIndex).put("notes", JSONArray(notes))
        .put("blank_count", blankCount).put("polling_count", pollingCount)

    companion object {
        fun fromJson(value: JSONObject): QuestionContent {
            val options = value.getJSONArray("options")
            val assets = value.getJSONArray("assets")
            val notes = value.getJSONArray("notes")
            return QuestionContent(value.getInt("type"), value.getString("body"),
                (0 until options.length()).map { i -> options.getJSONObject(i).let {
                    QuestionOption(it.getString("label"), it.opt("key") as? String, it.getString("html"))
                } },
                (0 until assets.length()).map { i -> assets.getJSONObject(i).let {
                    QuestionAsset(it.getString("label"), it.getString("url"))
                } }, value.optInt("page", 0).takeIf { it > 0 },
                (0 until notes.length()).map { notes.getString(it) },
                value.optInt("blank_count", 0).takeIf { it > 0 },
                value.optInt("polling_count", 0).takeIf { it > 0 })
        }
    }
}

/** Select by problem ID, never fall back to an unrelated page or retain answer/result fields. */
object QuestionProtocol {
    const val MAX_PRESENTATION_BYTES = 8 * 1024 * 1024
    const val MAX_ASSETS = 12
    private val imageTag = Regex("""<img\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val source = Regex("""\bsrc\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""", RegexOption.IGNORE_CASE)

    fun parse(ref: QuestionRef, root: JSONObject): QuestionContent {
        val data = RainProtocol.successfulData(root, "question_content") as? JSONObject ?: invalidContent()
        val slides = data.optJSONArray("slides") ?: invalidContent()
        val matches = (0 until slides.length()).mapNotNull { slides.optJSONObject(it) }.filter {
            LessonProtocol.id(it.optJSONObject("problem")?.opt("problemId")) == ref.problemId
        }
        if (matches.size != 1) invalidContent()
        val slide = matches.single()
        val actualSlideId = LessonProtocol.id(slide.opt("id"))
        if (ref.slideId != null && actualSlideId != null && ref.slideId != actualSlideId) invalidContent()
        val problem = slide.getJSONObject("problem")
        val notes = mutableListOf<String>()
        fun html(value: Any?): String {
            if (value == null || value == JSONObject.NULL) return ""
            if (value !is String) { notes.add("部分文本结构暂无法识别，请以原图和微信核对。"); return "" }
            if (value.length > 65_536) invalidContent()
            return value
        }
        val body = html(problem.opt("body"))
        val optionsValue = problem.optJSONArray("bullets")?.takeIf { it.length() > 0 }
            ?: problem.optJSONArray("options") ?: JSONArray()
        if (optionsValue.length() > 64) invalidContent()
        val options = (0 until optionsValue.length()).map { i ->
            val option = optionsValue.optJSONObject(i)
            if (option == null) {
                notes.add("部分选项没有返回结构化标识，请核对原图。")
                QuestionOption("选项 " + (i + 1), null, html(optionsValue.opt(i)))
            } else {
                fun marker(value: Any?): String? = when (value) {
                    is String, is Number -> value.toString().takeIf { it.isNotBlank() }?.take(100)
                    else -> null
                }
                val key = marker(option.opt("key"))
                val label = marker(option.opt("label"))
                    ?: key ?: ("选项 " + (i + 1))
                // Empty option bodies commonly mean that the options are drawn on the slide.
                QuestionOption(label, key, html(option.opt("value") ?: option.opt("body") ?: option.opt("text")))
            }
        }
        val assets = mutableListOf<QuestionAsset>()
        fun addAsset(label: String, url: String?) {
            if (url.isNullOrBlank()) return
            if (url.length > 8192) { notes.add("图片地址过长，未加载该图片。"); return }
            if (assets.any { it.url == url }) return
            if (assets.size >= MAX_ASSETS) { notes.add("本题图片超过 12 张，部分未加载。"); return }
            assets.add(QuestionAsset(label, url))
        }
        addAsset("课件原图", slide.opt("cover") as? String)
        fun embedded(label: String, text: String) {
            imageTag.findAll(text).forEach { tag ->
                val match = source.find(tag.value)
                val url = match?.groupValues?.drop(1)?.firstOrNull { it.isNotEmpty() }
                if (url == null) notes.add("存在无法识别地址的嵌入图片，请核对原图。")
                else addAsset(label, url.replace("&amp;", "&").replace("&#38;", "&")
                    .replace("&quot;", "\"").replace("&#39;", "'"))
            }
        }
        embedded("题干附图", body)
        options.forEach { embedded("选项 " + it.label + " 附图", it.html) }
        if (body.isBlank()) notes.add("接口未返回独立文字题干，请查看课件原图。")
        if (assets.isEmpty()) notes.add("接口未返回图片。")
        val type = problem.opt("problemType")?.toString()?.toIntOrNull() ?: -1
        if (options.isEmpty() && type !in setOf(4, 5)) notes.add("接口未返回结构化选项，请查看原图。")
        // Only the number of blanks is retained. Never cache teacher answers inside blanks.
        val blankCount = if (type == 4) problem.optJSONArray("blanks")?.length()?.takeIf { it > 0 } else null
        val pollingCount = if (type == 3) problem.opt("pollingCount")?.toString()?.toIntOrNull()?.takeIf { it > 0 } else null
        if (type == 4 && blankCount == null) notes.add("未取得空格数量，暂不能自动提交本题。")
        if (type == 3 && pollingCount == null) notes.add("未取得投票数量限制，暂不能自动提交本题。")
        return QuestionContent(type, body, options, assets,
            slide.optInt("index", 0).takeIf { it > 0 } ?: ref.slideIndex, notes.distinct(), blankCount, pollingCount)
    }
}
