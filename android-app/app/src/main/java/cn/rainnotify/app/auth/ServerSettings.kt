package cn.rainnotify.app.auth

import android.content.Context
import androidx.core.content.edit

/** No default: the user must choose a server before restoring or starting a login. */
object ServerSettings {
    private fun prefs(context: Context) = context.getSharedPreferences("rain-server-v1", Context.MODE_PRIVATE)
    fun selected(context: Context) = RainServer.fromId(prefs(context).getString("selected", null))
    fun select(context: Context, server: RainServer) {
        prefs(context).edit { putString("selected", server.id) }
    }
}
