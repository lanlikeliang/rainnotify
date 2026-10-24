package cn.rainnotify.app.answer

import org.junit.Assert.*
import org.junit.Test

class DeepSeekPageTest {
    private fun page() = listOf(
        DeepSeekPage.Node(-1,"android.view.View","",false,true,false,false,false,0,0,1200,2670),
        DeepSeekPage.Node(0,"android.view.View","",false,true,false,false,false,0,134,1200,290),
        DeepSeekPage.Node(1,"android.view.View","开启新对话",false,true,false,false,false,1092,176,1164,248),
        DeepSeekPage.Node(0,"android.view.View","",false,true,false,false,false,36,2256,1164,2574),
        DeepSeekPage.Node(3,"android.view.View","上传文件",false,true,false,false,false,903,2451,978,2529),
        DeepSeekPage.Node(0,"android.widget.TextView","",true,true,false,false,false,270,1309,930,1393))
    @Test fun recognizesWelcomeStructureWithoutReadingGreetingWords() { assertTrue(DeepSeekPage.blank(page())) }
    @Test fun nestedMessageAndHistoryAreNotWelcome() {
        val nodes = page().toMutableList()
        nodes[5] = nodes[5].copy(parent=3)
        assertFalse(DeepSeekPage.blank(nodes))
        nodes[5] = page()[5]
        nodes += page()[0].copy(parent=0, scrollable=true)
        assertFalse(DeepSeekPage.blank(nodes))
    }
    @Test fun loadingMissingControlsAndResponseActionsFailClosed() {
        assertFalse(DeepSeekPage.blank(page().dropLast(1)))
        assertFalse(DeepSeekPage.blank(page().map { if (it.description == "上传文件") it.copy(visible=false) else it }))
        assertFalse(DeepSeekPage.blank(page() + page()[2].copy(description="复制")))
    }
    @Test fun scalesWithScreenInsteadOfFixedCoordinates() {
        assertTrue(DeepSeekPage.blank(page().map { it.copy(left=it.left/2, top=it.top/2, right=it.right/2, bottom=it.bottom/2) }))
    }
}
