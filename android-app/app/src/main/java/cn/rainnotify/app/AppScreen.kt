package cn.rainnotify.app

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*

class AppScreen(private val activity: Activity, title: String, subtitle: String) {
    val body = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val pad = (20 * activity.resources.displayMetrics.density).toInt()
    init {
        activity.window.insetsController?.setSystemBarsAppearance(
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
            WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS)
        body.setBackgroundColor(Color.rgb(246, 248, 252))
        body.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars())
            view.setPadding(pad + bars.left, pad + bars.top, pad + bars.right, pad + bars.bottom); insets
        }
        text(title, 27f).setTypeface(null, Typeface.BOLD)
        text(subtitle, 15f)
        activity.setContentView(ScrollView(activity).apply { isFillViewport = true; addView(body) })
        body.requestApplyInsets()
    }
    fun text(value: String, size: Float = 16f, parent: LinearLayout = body) = TextView(activity).apply {
        text = value; textSize = size; setTextColor(Color.rgb(30, 45, 65)); setPadding(0, pad / 3, 0, pad / 3)
        setLineSpacing(5f, 1.05f); parent.addView(this)
    }
    fun section(title: String): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL; setPadding(pad * 3 / 4, pad / 2, pad * 3 / 4, pad / 2)
        background = GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = 14 * resources.displayMetrics.density }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = pad * 3 / 4 }
        body.addView(this); text(title, 19f, this).setTypeface(null, Typeface.BOLD)
    }
    fun button(label: String, parent: LinearLayout = body, action: () -> Unit) = Button(activity).apply {
        text = label; isAllCaps = false; minHeight = (48 * resources.displayMetrics.density).toInt()
        setOnClickListener { action() }; parent.addView(this, LinearLayout.LayoutParams(-1, -2))
    }
    fun back() = button("返回") { activity.finish() }
}
