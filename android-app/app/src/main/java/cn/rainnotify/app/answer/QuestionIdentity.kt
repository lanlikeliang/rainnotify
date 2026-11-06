package cn.rainnotify.app.answer

import cn.rainnotify.app.question.QuestionContent
import cn.rainnotify.app.question.QuestionExport
import org.json.JSONArray
import org.json.JSONObject

/** URLs are transport details. Image contents are checked separately against the solver's snapshot. */
object QuestionIdentity {
    private val inlineFormatting = Regex("""</?(?:b|strong|i|em|u|s|span|code)\b[^>]*>""", RegexOption.IGNORE_CASE)
    private val formatting = Regex("""</?(?:div|p|h[1-6]|blockquote|pre|ol|ul|li|table|thead|tbody|tr|td|th)\b[^>]*>|<br\s*/?>""", RegexOption.IGNORE_CASE)
    private val entities = Regex("""&(#x[0-9a-fA-F]+|#[0-9]+|amp|lt|gt|quot|apos|nbsp);""")

    fun html(raw: String): String {
        // Keep sub/sup boundaries significant, e.g. x squared differs from x2.
        val static = formatting.replace(inlineFormatting.replace(QuestionExport.staticHtml(raw), ""), " ")
        val decoded = entities.replace(static) { match ->
            val entity = match.groupValues[1]
            val code = when {
                entity.startsWith("#x") -> entity.drop(2).toIntOrNull(16)
                entity.startsWith("#") -> entity.drop(1).toIntOrNull()
                else -> mapOf("amp" to 38, "lt" to 60, "gt" to 62, "quot" to 34,
                    "apos" to 39, "nbsp" to 32)[entity]
            }
            if (code != null && Character.isValidCodePoint(code)) String(Character.toChars(code)) else match.value
        }
        return decoded.replace(Regex("[\\s\\u00a0]+"), " ").trim()
    }

    fun fingerprint(question: QuestionContent): String {
        val content = JSONArray().put("rain-question-text-v3").put(question.type).put(html(question.bodyHtml))
            .put(JSONArray(question.options.map { JSONArray().put(it.label).put(it.key ?: JSONObject.NULL).put(html(it.html)) }))
            .put(JSONArray(question.assets.map { it.label }))
            .put(question.blankCount ?: JSONObject.NULL).put(question.pollingCount ?: JSONObject.NULL)
        return AnswerProtocol.sha256(content.toString())
    }

    /** Only fixed category names enter diagnostics. No text, image data or URLs. */
    fun differences(before: QuestionContent, after: QuestionContent): List<String> = buildList {
        if (before.type != after.type) add("type")
        if (before.blankCount != after.blankCount || before.pollingCount != after.pollingCount) add("answer_rules")
        if (html(before.bodyHtml) != html(after.bodyHtml)) add("body")
        if (before.options.size != after.options.size) add("option_count")
        else {
            if (before.options.zip(after.options).any { (a, b) -> a.label != b.label || a.key != b.key }) add("option_mapping")
            if (before.options.zip(after.options).any { (a, b) -> html(a.html) != html(b.html) }) add("option_content")
        }
        if (before.assets.size != after.assets.size) add("asset_count")
        else if (before.assets.zip(after.assets).any { (a, b) -> a.label != b.label }) add("asset_role")
    }
}
