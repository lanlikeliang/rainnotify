package cn.rainnotify.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import cn.rainnotify.app.answer.AiReadinessActivity
import cn.rainnotify.app.answer.AiSettingsActivity

class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = AppScreen(this, "设置", "首次使用，依次完成以下配置。无需登录即可设置。")
        val required = ui.section("监听与提醒 · 必需")
        ui.button("1 · 通知与声音", required) { startActivity(Intent(this, NotificationSettingsActivity::class.java)) }
        ui.button("2 · 本 App 后台运行", required) { startActivity(Intent(this, AiReadinessActivity::class.java).putExtra("background_only", true)) }
        ui.text("允许本 App 自启动，将省电策略设为无限制。横幅、锁屏展示和声音仍受手机通知设置控制。", 14f, required)
        val optional = ui.section("AI 作答 · 可选")
        ui.button("3 · AI 作答方式与配置", optional) { startActivity(Intent(this, AiSettingsActivity::class.java)) }
        ui.text("API 模式：密钥 + 图文自检。\nAI App 模式：本 App 的无障碍服务 + 对应 App 图文自检。\n只用题目提醒时无需配置 AI。", 14f, optional)
        ui.text("关于本版本\n${BuildConfig.VERSION_NAME}\n普通版雨课堂 · Android 16\n题目、登录会话及 API Key 保存在手机。题目手动导出不包含密钥和登录凭据。", 14f)
        ui.back()
    }
}
