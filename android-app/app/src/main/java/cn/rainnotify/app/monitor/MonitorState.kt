package cn.rainnotify.app.monitor

import androidx.lifecycle.MutableLiveData

data class MonitorUiState(
    val active: Boolean = false,
    val message: String = "尚未开始课堂监听。",
    val questionCount: Int = 0,
    val scanRequiredLessonId: String? = null,
)

/** Main-thread state shared by the visible screen and its explicit foreground service. */
object MonitorState {
    val state = MutableLiveData(MonitorUiState())
    fun update(value: MonitorUiState) { state.value = value }
    fun current() = state.value ?: MonitorUiState()
}
