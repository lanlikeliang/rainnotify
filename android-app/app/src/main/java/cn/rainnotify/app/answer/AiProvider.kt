package cn.rainnotify.app.answer

import android.content.Context

enum class AiProvider(val title: String, val packageName: String, val version: String, val shareActivity: String) {
    KIMI("Kimi", "com.moonshot.kimichat", "3.1.3", "com.moonshot.kimichat.share.image"),
    DEEPSEEK("DeepSeek", "com.deepseek.chat", "2.6.1", "com.deepseek.chat.ShareProxyActivity"),
    DOUBAO("豆包", "com.larus.nova", "15.3.0", "com.larus.home.impl.OuterShareDeliverActivity");

    companion object {
        fun selected(context: Context): AiProvider = entries.firstOrNull {
            it.name == context.getSharedPreferences("ai-provider", Context.MODE_PRIVATE).getString("provider", null)
        } ?: KIMI
        fun select(context: Context, provider: AiProvider) {
            context.getSharedPreferences("ai-provider", Context.MODE_PRIVATE).edit().putString("provider", provider.name).apply()
        }
    }
}
