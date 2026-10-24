# Android 通知授权与熄屏诊断

更新：2026-10-03；对应通知验证版 `0.0.4-probe`。目标设备为 Xiaomi 14 / Android 16 / HyperOS OS3.0.303.0.WNCCNXM，其他品牌尚未实测。

## 本次问题的准确范围

用户已确认横幅时有时无受到系统“悬浮通知临时屏蔽”影响；这项不再作为未定位的 App 故障。锁定但屏幕亮着时，30 秒测试可以发声和展示通知；屏幕完全关闭时，用户希望同样及时发声，不要求必须自动亮屏。

已导出的 0.0.3 日志中，有一次延时测试从安排到触发约 86.15 秒，比目标晚 56.15 秒；触发时 `interactive=true`，随后立即提交系统通知。另有测试接近 30 秒触发，但也在交互状态。由此可确认部分延迟发生在生成通知之前，不能全部归因于通知显示或声音权限。日志中的 `wake_lock_held=true` 仅表示客户端认为锁仍被持有，不能证明系统实际执行了保持 CPU 运行的请求。

0.0.4 安装后，用户确认本 App 原先存在省电限制，并手动改为“无限制”；随后确认熄屏约 30 秒时听到声音。日志核对：安排到触发约 30.029 秒，触发与提交通知时均为 `interactive=false`，模拟事件收到到提交为 11 ms，随后服务停止。该结果支持后台省电限制参与了此前的延迟，尚不能定位厂商内部具体机制。完整条件见 [实测记录](实测记录-2026-10-03.md)。

## 官方指导如何对应到实现

| 层次 | 官方说明 | 0.0.4 处理 |
| --- | --- | --- |
| 通知运行时授权 | Android 13 起，普通通知需要 `POST_NOTIFICATIONS`；已有授权应先检查 | 用户点击后申请，采用 Activity Result API；拒绝后保留诊断功能，提供系统设置入口 |
| App 总开关 | `areNotificationsEnabled()` 判断 App 通知是否可用 | 与运行时授权分开显示；关闭时引导至本 App 通知设置 |
| 通知渠道 | 渠道建立后，声音、振动、重要级别由用户设置控制 | 保留高重要级别题目渠道和低重要级别状态渠道，读取实际配置，不通过换渠道 ID 重置用户选择 |
| 展示与发声 | 高重要级别支持提醒，但具体行为仍受系统设置影响 | 显示通知音量、响铃模式和勿扰状态；提供题目渠道及悬浮／锁屏设置入口 |
| 熄屏执行 | `Handler.postDelayed()` 依赖 uptime，深睡会增加实际延迟 | 同时记录 elapsedRealtime 和 uptime 的计时误差，区分睡眠时间与其他调度停顿 |
| 后台省电 | Doze 会限制网络和唤醒锁；省电豁免也并非解除所有限制 | 展示标准豁免和后台限制状态，提供本 App 详情与标准电池优化列表入口；厂商策略仍需用户确认 |

依据：[通知权限](https://developer.android.com/develop/ui/compose/notifications/notification-permission)、[运行时权限流程](https://developer.android.com/training/permissions/requesting)、[通知渠道](https://developer.android.com/develop/ui/compose/notifications/channels)、[Handler 计时说明](https://developer.android.com/reference/android/os/Handler#postDelayed(java.lang.Runnable,%20long))、[Doze 与 App Standby](https://developer.android.com/training/monitoring-device-state/doze-standby)。

Android 15 及以上的通知冷却还可能降低密集提醒的表现，因此测试按钮增加两分钟的启动间隔，减少重复点击干扰；这不是对用户已确认的“悬浮通知临时屏蔽”原因的替代解释，也不保证间隔后系统一定发声。[通知概览](https://developer.android.com/develop/ui/compose/notifications)

`VIBRATE` 和 `WAKE_LOCK` 在清单中声明，不像通知运行时权限一样申请授权弹窗。现有 75 秒上限的 partial wake lock 仅服务于本地对照测试，用后释放；不将长期持锁作为课堂监听的默认方案。

## 开源项目参考

| 项目与已查看材料 | 借鉴内容 | 适用边界 |
| --- | --- | --- |
| [ntfy Android MainActivity](https://github.com/binwiederhier/ntfy-android/blob/main/app/src/main/java/io/heckel/ntfy/ui/MainActivity.kt)、[手机订阅文档](https://docs.ntfy.sh/subscribe/phone/) | 通知权限、渠道与电池优化分别检查；通过界面解释后台条件 | 使用其设计经验，本项目按标准设置入口重新实现；其服务端推送能力不等于本地雨课堂连接已可靠 |
| [Gotify Android README](https://github.com/gotify/android/blob/master/README.md)、[WebSocketService](https://github.com/gotify/android/blob/master/app/src/main/kotlin/com/github/gotify/service/WebSocketService.kt) | 持续连接的状态通知与实际消息渠道分开，说明电池设置要求 | 不照搬项目的全部权限；当前没有网络连接，不能用本地测试替代 WebSocket 实测 |
| [Android 官方权限示例说明](https://github.com/android/platform-samples/blob/main/samples/privacy/permissions/README.md) | 使用 Activity Result 合约处理权限结果 | 示例不是可直接交付的生产实现，仍需拒绝、恢复设置等路径 |
| [Signal 的 Xiaomi 通知帮助](https://support.signal.org/hc/en-us/articles/7821674889242-Xiaomi-Notifications) | 分开排查后台电量管理与悬浮通知设置 | 页面路径随系统版本不同，不照搬要求用户全局开启性能模式 |

小米官方针对 Xiaomi 15 的说明指出，后台电池保护可能限制锁屏后的联网，并提供单 App “No restrictions” 设置建议。这支持将应用省电策略列为实测变量，但不是本台 Xiaomi 14 延时计时器问题的直接证据。[小米官方说明](https://www.mi.com/my/support/faq/details/KA-538010/)

## 本轮验证与后续判断

- 0.0.4 已通过 `assembleDebug` 与 `lintDebug`，lint 为 0 errors、4 warnings，并通过 ADB 更新安装、启动。警告涉及依赖可更新和 KTX 写法建议，无权限错误。
- 用户将本 App 省电策略设为“无限制”，仅进行一次 30 秒延时测试，按电源键关闭屏幕，确认熄屏期间听到声音。
- 若后续 `simulation_timer_fired` 和 `notification_submitted` 在 `interactive=false` 时准时出现，但没有声音，则继续调查通知／音频策略。
- 若计时器迟发，比较两种时钟误差。elapsed 延迟明显而 uptime 接近预期，与深睡造成的额外延迟一致；两者都晚则需继续调查线程或进程调度，不能直接归因于 Doze。
- 触发时 `device_idle=false` 不代表此前一直没有休眠；标准省电豁免状态也不能替代厂商“无限制”策略的人工确认。

本地通知测试通过后，仍需单独验证不充电时的自然熄屏、网络接收和真实课堂事件。USB 充电会影响休眠条件。本轮没有接入雨课堂或部署服务器，尚不能承诺多品牌后台可靠性。
