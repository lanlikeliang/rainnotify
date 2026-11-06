package cn.rainnotify.app.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import cn.rainnotify.app.auth.RainServer

@Composable fun ServerSelection(selected: RainServer?, choose: (RainServer) -> Unit) {
    Panel {
        Text("1. 选择服务器", fontWeight = FontWeight.SemiBold)
        Text("请与学校或老师要求的雨课堂版本保持一致。", color = Muted, fontSize = 13.sp)
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            RainServer.entries.forEach { server ->
                Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("server_${server.id}")
                    .selectable(selected == server, role = Role.RadioButton, onClick = { choose(server) })
                    .padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected == server, onClick = null)
                    Column(Modifier.padding(start = 12.dp).weight(1f)) {
                        Text(server.label, color = Ink)
                        Text(server.host, color = Muted, fontSize = 12.sp)
                    }
                    if (selected == server) Text("已选择", color = Teal, fontSize = 12.sp)
                }
            }
        }
        Text("各服务器分别保存登录。更换服务器前，请先关闭监听并等待当前作答结束。", color = Muted, fontSize = 13.sp)
    }
}
