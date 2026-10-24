package cn.rainnotify.app.answer

/** The 2.6.1 welcome text rotates. Match page structure, never its wording. */
object DeepSeekPage {
    data class Node(val parent: Int, val kind: String, val description: String, val hasText: Boolean,
        val visible: Boolean, val editable: Boolean, val clickable: Boolean, val scrollable: Boolean,
        val left: Int, val top: Int, val right: Int, val bottom: Int)
    fun blank(nodes: List<Node>): Boolean {
        val newChat = nodes.indices.singleOrNull { nodes[it].visible && nodes[it].description == "开启新对话" } ?: return false
        val upload = nodes.indices.singleOrNull { nodes[it].visible && nodes[it].description == "上传文件" } ?: return false
        fun parents(index: Int): List<Int> {
            val result = mutableListOf<Int>()
            var current = nodes[index].parent
            while (current in nodes.indices && current !in result && result.size < 30) {
                result += current; current = nodes[current].parent
            }
            return result
        }
        val uploadParents = parents(upload)
        val page = parents(newChat).firstOrNull { it in uploadParents } ?: return false
        val frame = nodes[page]
        val controlsTop = nodes[newChat].bottom
        val controlsBottom = nodes[upload].top
        if (frame.right <= frame.left || controlsBottom <= controlsTop) return false
        val welcome = nodes.filter { it.parent == page && it.visible && it.kind == "android.widget.TextView" &&
            it.hasText && !it.editable && !it.clickable && it.top > controlsTop && it.bottom < controlsBottom }.singleOrNull() ?: return false
        // Welcome is a direct child of the page, separate from toolbar and composer.
        // Messages are nested inside the conversation container.
        if (kotlin.math.abs((welcome.left + welcome.right) - (frame.left + frame.right)) > (frame.right - frame.left) / 5) return false
        val centerY = (welcome.top + welcome.bottom) / 2
        val gap = controlsBottom - controlsTop
        if (centerY !in (controlsTop + gap / 4)..(controlsBottom - gap / 4)) return false
        if (nodes.any { it.visible && (it.scrollable || it.description in setOf("复制", "重新生成", "点赞", "点踩", "停止", "停止生成", "停止回答")) }) return false
        return true
    }
}
