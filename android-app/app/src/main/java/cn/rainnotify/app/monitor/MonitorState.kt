package cn.rainnotify.app.monitor

import androidx.lifecycle.MutableLiveData

data class MonitorUiState(
    val active: Boolean = false,
    val message: String = "尚未开始课堂监听。",
    val questionCount: Int = 0,
    val scanRequiredLessonId: String? = null,
    val lessonId: String? = null,
    val lessonName: String? = null,
    val connection: ConnectionState? = null,
    val established: Boolean = false,
)

/** Main-thread state shared by the visible screen and its explicit foreground service. */
object MonitorState {
    @Volatile var stopRequested = false
    @Volatile var automaticRequested = false
    val state = MutableLiveData(MonitorUiState())
    fun update(value: MonitorUiState) { state.value = value }
    fun current() = state.value ?: MonitorUiState()
}
