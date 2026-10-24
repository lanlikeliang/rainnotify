# 自动模式与多 AI 适配（0.0.13）

## 范围

本阶段保留纯手机方案。在一次课堂监听中手动开启自动模式后，新题触发唤起入口、等待系统解锁、分享原图与提示词、读取模型最终答案、验证题目状态并尝试提交。GLM 等 API 可以继续复用 AnswerEngine 的求解回调；本次没有增加密钥输入或 API 调用。

自动模式绑定当前监听会话，只处理开启后收到的实时发题事件。停止监听、切换会话、退出进程后失效；不会开机自动启用。单题运行期间遇到下一题，只通知，不排队补交。任务从收到事件起最多 85 秒，等待解锁包含在内。提交之前仍要核对当前课堂、剩余时间、题目指纹、账号是否已有作答及持久化提交记录；超时和结果不明均不自动重发。

历史题的“30 秒后模拟发题并自动演练”通过同一唤起入口和求解流程，任务本身固定为演练，没有雨课堂提交网关。调试包另有受 android.permission.DUMP 保护的 USB 演练入口，仅 shell/受权限保护的系统调用方可启动，不能构造真实提交。

## 屏幕与系统限制

唤起入口使用 Activity.setTurnScreenOn、setShowWhenLocked；入口只显示通用提示，不显示题目或账号。系统安全锁屏需要用户通过指纹、密码等验证，应用不会输入解锁凭据。解锁后自动继续，在单题时限内短暂保持屏幕运行，以完成跨 App 操作。

后台 Activity 启动依赖已授权的无障碍服务和机型策略；前台服务本身不保证可任意弹出界面。若系统阻止唤起，可点击通知继续。尚不能把一台手机的短时验证推广为 Android 16 全品牌或长时间 Doze 保证。

官方依据：

- [Android 后台 Activity 启动限制](https://developer.android.com/guide/components/activities/secure-bal)
- [Activity 唤醒屏幕接口](https://developer.android.com/reference/android/app/Activity#setTurnScreenOn(boolean))
- [KeyguardManager 请求系统解锁](https://developer.android.com/reference/android/app/KeyguardManager#requestDismissKeyguard(android.app.Activity,%20android.app.KeyguardManager.KeyguardDismissCallback))
- [Doze 与后台限制](https://developer.android.com/training/monitoring-device-state/doze-standby)

## App 适配

本机版本：Kimi 3.1.3（com.moonshot.kimichat）、DeepSeek 2.6.1（com.deepseek.chat）、豆包 15.3.0（com.larus.nova）。版本不匹配会停止，不猜测按钮位置。豆包的其他包名未验收。

每题固定选定一个提供方；运行过程中修改设置不改变正在处理的任务。DeepSeek、豆包先尝试创建新对话，分享后等待加载；核对输入框提示词、原图附件数量和发送按钮。已有未发送附件会拦截，防止叠加测试草稿。只点击一次发送，必须看到本题文字进入对话；未确认时停止。答案必须携带本次随机任务标识，满足严格 JSON 格式和题型选项约束，并出现对应的回答完成控件。

DeepSeek 的空白输入框在未聚焦时可能不暴露 EditText，因此新对话使用欢迎页语义核验。豆包空白首页没有“创建新对话”按钮，使用其专用欢迎页节点和可见空输入框共同确认；已有聊天时才点击新建按钮。豆包的纯文字分享可能立即发送到当前对话，图片分享则显示附件输入区；两者不能套用同一个“分享后必然等待点击”的假设。

豆包图片答疑的回答节点会在 JSON 前添加固定的“💡解答”标题，仅移除这个已实测标题。不会从任意解释文字中截取 JSON 来放宽校验。模型明确无法作答、任务标识错误、非法选项或额外解释均仍拒绝。

## 无障碍服务自动关闭调查

用户报告服务有时自动关闭，已列为待定位问题。代码中未发现 disableSelf、改写系统无障碍授权或主动强行停止应用的逻辑。

本次观察到一次系统 Enabled services 为空；重新授权后，通过一次安装更新的对照，Enabled services 保留，Bound services 短暂为空后自动恢复，Crashed services 为空。因此不能认定“每次更新都会关闭授权”，也尚不能证明是省电策略导致。

本版设置页每秒刷新，区分“已连接”“已授权但未绑定”和“系统授权已关闭”，并记录连接、销毁、中断事件。tools/check_accessibility.ps1 只检查本 App 授权与生命周期，不读取其他 App 的无障碍内容。若再次发生，先运行该脚本保留状态，再手动开关服务。

运行 AI 自动化时不使用 uiautomator dump，因为 Android 的默认 UiAutomation 会抑制普通无障碍服务。仅在任务停止后读取界面；运行中只读本 App 状态和脱敏控件元数据。[官方 UiAutomation 标志说明](https://developer.android.com/reference/android/app/UiAutomation#FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)

## 验收记录

2026-10-16 至 2026-10-17，Xiaomi 14 / Android 16；题目图片哈希已与用户提供的 ZIP 样本匹配。

| App | 样本 | 最终选项 | 从准备到演练完成 | 流程结果 |
| --- | --- | --- | --- | --- |
| DeepSeek 2.6.1 | question-001 单选 | B | 9,880 ms | REHEARSED，live=false |
| 豆包 15.3.0 | question-001 单选 | B | 12,082 ms | REHEARSED，live=false |
| 豆包 15.3.0 | question-004 多选 | A、C | 11,249 ms | REHEARSED，live=false |
| DeepSeek 2.6.1 | question-004 多选 | B、C | 8,530 ms | REHEARSED，live=false |
| Kimi 3.1.3 | question-004 多选，熄屏触发 | B、C | 解锁后 11,895 ms | REHEARSED，live=false |

上述成功指原图分享、自动填写、单次自动发送、对话回显、最终答案回读及格式校验完成，不代表答对。多选题两个 App 的答案不一致，豆包结果也与人工预期 B、C 不同；没有教师标准答案，不能据此给出正式准确率，更不能保证自动提交一定正确。

构建检查：69 项 JVM 测试通过，Lint 0 错误、38 警告、2 提示。新增测试覆盖自动模式会话绑定、重启失效、重复与并发事件、事件起算的过期时间，以及豆包标题规范化不能绕过严格答案校验。

熄屏自动启动于 2026-10-17 在本机通过一次：auto_event 日志 interactive=false，62 ms 后记录 auto_gate_opened；用户确认自动亮屏，并正常完成系统解锁；约 10.4 秒后进入准备阶段，随后 Kimi 自动求解并完成演练。事件至完成共 22,258 ms，包含等待解锁；没有再点击“开始”或“发送”，没有向雨课堂提交。安全锁屏仍需身份验证，不能据此声称无人解锁也能运行 AI App。

唤起页面设置独立的剩余时限，即使系统验证界面使其暂停，也不会无限期保留本 App 的亮屏窗口。任务取消、过期、版本不匹配、附件不齐及答案格式错误都不提交。

真实课堂提交、判断题真实提交、其他机型、长时后台联网均未验收。服务端计时回包是否回显请求 msgid 仍待真实课堂确认，无法关联时会停止提交，不会绕过检查。
