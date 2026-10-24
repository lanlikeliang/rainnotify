# Android 雨课堂提醒验证工程

状态：**0.0.19-api-five-types 已接入 DeepSeek API、五种题型和新首页。** API 密钥由用户在手机输入，图文自检及历史题演练已通过，短时熄屏演练各阶段均在屏幕关闭时完成。见[本版实现与验证记录](../docs/五种题型与DeepSeekAPI-0.0.19.md)。保留入课、截屏与题目身份校验修复，真实提交仍待实课验收。

工程用 Kotlin 和 Android 原生控件提供通知验证页，以及普通版微信二维码授权、加密会话保存、当前课堂列表查询和手动单课堂监听。通知验证页包含通知授权、立即模拟、30 秒延时模拟、停止验证和脱敏 JSONL 导出。

所有模拟事件均标为 `source=simulation`。本地定时通知通过不代表课堂 WebSocket 在锁屏时能收消息，后续必须添加网络链路与真实课堂验证。

当前固定 Android 16 / API 36；AGP 8.13.2、Kotlin 2.3.21、Gradle 8.13、JDK 17。依据：[AGP 官方兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)、[Kotlin 官方兼容表](https://kotlinlang.org/docs/gradle-configure-project.html)。

本机已在项目 `.local-tools/` 配置完整 Microsoft OpenJDK 17.0.20.1、Gradle 8.13、SDK Platform 36 和 Build Tools 35.0.0。生成了带官方分发包 SHA-256 的 Gradle Wrapper。复用本地缓存构建：

```powershell
.\tools\build_android.ps1 -Lint -UnitTests
```

上述命令从项目根目录执行。产物为 `android-app/app/build/outputs/apk/debug/app-debug.apk`，当前版本 `0.0.19-api-five-types`；版本化分发文件在 `dist/0.0.19/`。本机 Windows 构建脚本会为中文路径临时建立 ASCII 盘符映射，结束后取消映射，避免 JDK 17 测试运行器的参数文件编码问题。新电脑需配置包含 javac 和 jlink 的完整 JDK 17 与 SDK。标准构建可在 `android-app/` 中使用 `./gradlew :app:assembleDebug`（Windows 为 `./gradlew.bat`）。

手机启用 USB 调试并授权后，执行 `./tools/install_probe.ps1` 安装并打开 App。Xiaomi 若出现 `INSTALL_FAILED_USER_RESTRICTED`，须由用户开启“USB 安装”并确认安装；不会自动修改系统设置。

打开 App 后，按需授权系统通知、查看渠道与后台设置。测试启动至少间隔两分钟；熄屏测试需要按电源键关闭屏幕，至少 50 秒后再点亮，并记录是否在屏幕关闭期间听到声音。延时测试申请最长 75 秒的 partial wake lock，完成后停止服务；系统仍可能限制执行。用户需要确认实际可见性与声音／振动。执行 `./tools/pull_diagnostics.ps1` 导出本 App 的 JSONL 报告，再用 `python tools/analyze_diagnostics.py <报告路径>` 分析。原始报告保存在被忽略的 `diagnostic-reports/`。

课堂监听独立于模拟通知服务，确认“进入课堂并监听”后才执行可能记录签到的入课操作。若返回 50070，用户可选择去微信扫码，返回后为同一课堂接续一次入课；也可点击已扫码后的手动继续。重连复用本次凭据，不重试入课；区分新题、延时和历史事件，并在同一监听中去重。每次最长 4 小时，停止、下课或系统结束进程后不自动重启。当前同题再次发布可能漏报，长期熄屏联网仍待验证。新题取内容使用单独工作线程，只执行读取请求，保存最近 20 题供课后查看。已有单题 AI 演练、作答与单次提交、本次课堂自动模式；演练不能取得真实提交通道，线上提交仍需实课验收。

`specialUse` 用途为明确用户启动的课堂提醒和作答会话，公开发布前需再次核验服务用途和发布渠道要求。AI App 作答路线需要用户开启本 App 的无障碍辅助服务；通知采集本身无需该权限。安全锁屏仍由用户自行解锁。

题目导出使用系统 CreateDocument 保存 ZIP，不增加广泛存储权限。单独后台队列先在本机生成缓存快照，再写用户选择的目标。只导出必要内容与原始图片，不归档整个 App 目录，也不导出账号、课堂接口标识、图片下载地址或登录凭据；缺失图片与获取状态会明确标记。5 项新增测试覆盖内容/原图保持、协议信息排除、单题隔离、部分失败与无效导出。
