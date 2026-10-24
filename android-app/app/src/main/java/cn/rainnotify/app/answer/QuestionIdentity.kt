package cn.rainnotify.app.answer

import cn.rainnotify.app.question.QuestionContent
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

/** Compare answer-bearing content, not a serialized server response or temporary access grant. */
object QuestionIdentity {
    // Observed on ordinary Rain Classroom covers. Do not strip arbitrary query parameters/hosts.
    private val signedImageHosts = setOf("rain-pri-ups.yuketang.cn")
    private val imageTag = Regex("""<img(?=[\s/>])(?:[^"'<>]|"[^"]*"|'[^']*')*>""", RegexOption.IGNORE_CASE)
    // Consume whole attributes so text such as alt="src='...'" is never treated as an image source.
    private val attributes = Regex("""([\w:-]+)\s*=\s*(?:"([^"]*)"|'([^']*)'|([^\s>]+))""")

    fun imageReference(raw: String): String {
        val url = raw.toHttpUrlOrNull() ?: return raw
        if (url.scheme != "https" || url.host !in signedImageHosts || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty()) return raw
        // Only used for comparison. Downloads keep the original signed URL.
        return url.newBuilder().removeAllQueryParameters("auth_key").build().toString()
    }

    fun html(raw: String): String = imageTag.replace(raw) { tag ->
        attributes.replace(tag.value) attribute@{ attribute ->
            if (!attribute.groupValues[1].equals("src", ignoreCase = true)) return@attribute attribute.value
            val value = attribute.groups.drop(2).filterNotNull().firstOrNull() ?: return@attribute attribute.value
            val decoded = value.value.replace("&amp;", "&").replace("&#38;", "&")
                .replace("&quot;", "\"").replace("&#39;", "'")
            val normalized = imageReference(decoded)
            if (normalized == decoded) attribute.value else attribute.value.replaceRange(
                value.range.first - attribute.range.first, value.range.last - attribute.range.first + 1, normalized)
        }
    }

    fun fingerprint(question: QuestionContent): String {
        // Arrays make field/option order explicit on both Android and JVM JSON implementations.
        val content = JSONArray().put("rain-question-content-v2").put(question.type).put(html(question.bodyHtml))
            .put(JSONArray(question.options.map { JSONArray().put(it.label).put(it.key ?: JSONObject.NULL).put(html(it.html)) }))
            .put(JSONArray(question.assets.map { JSONArray().put(it.label).put(imageReference(it.url)) }))
            .put(question.blankCount ?: JSONObject.NULL).put(question.pollingCount ?: JSONObject.NULL)
        // Page position and explanatory parser notes do not alter the question or answer mapping.
        return AnswerProtocol.sha256(content.toString())
    }

    /** Only these fixed category names may enter diagnostics; never include text, URLs or signatures. */
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
        else {
            if (before.assets.zip(after.assets).any { (a, b) -> a.label != b.label }) add("asset_role")
            if (before.assets.zip(after.assets).any { (a, b) -> imageReference(a.url) != imageReference(b.url) }) add("asset_reference")
        }
    }
}
