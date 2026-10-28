package cn.rainnotify.app.ui

enum class ClassroomMode(val label: String) { OFF("关闭"), NOTIFY("发题通知"), AUTO("自动答题") }
enum class Requirement(val label: String) {
    ACCOUNT("请先授权雨课堂账号"), CLASSROOM("请选择当前课堂"),
    NOTIFICATIONS("请允许通知和题目提醒渠道"), BACKGROUND("本 App 后台运行受限"),
    CLASSROOM_SCAN("请先用微信扫描教师课堂二维码，再返回连接"),
    API_KEY("请配置当前 API Key"), API_CHECK("请完成当前模型自检")
}
data class ModeRequirements(val account: Boolean, val classroom: Boolean, val notifications: Boolean,
    val backgroundRestricted: Boolean, val apiKey: Boolean, val apiChecked: Boolean)

/** The UI has one mode; service preparation is not a successful mode transition. */
object ModePolicy {
    fun effective(active: Boolean, established: Boolean, automatic: Boolean): ClassroomMode = when {
        !active || !established -> ClassroomMode.OFF
        automatic -> ClassroomMode.AUTO
        else -> ClassroomMode.NOTIFY
    }
    fun missing(target: ClassroomMode, state: ModeRequirements): List<Requirement> = buildList {
        if (target == ClassroomMode.OFF) return@buildList
        if (!state.account) add(Requirement.ACCOUNT)
        else if (!state.classroom) add(Requirement.CLASSROOM)
        if (!state.notifications) add(Requirement.NOTIFICATIONS)
        if (state.backgroundRestricted) add(Requirement.BACKGROUND)
        if (target == ClassroomMode.AUTO) {
            if (!state.apiKey) add(Requirement.API_KEY)
            else if (!state.apiChecked) add(Requirement.API_CHECK)
        }
    }
    fun answerBelongsToLatest(latestId: String?, taskQuestionId: String?): Boolean =
        latestId != null && latestId == taskQuestionId
}
