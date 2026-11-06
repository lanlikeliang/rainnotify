# 伞课堂 Android 正式工程

当前公开基线：**v0.1.3 / versionCode 25**（2026-10-29）。本目录属于正式工程 `D:\GPTitems\RainNotify`；旧原型位于 `D:\GPTitems\插件`，不再用于正式开发。

新对话请先读 [接手入口与目录导航](../docs/handoff/2026-10-29-v0.1.3/00_新对话必读_接手入口与目录导航.md)，再读 [根 README](../README.md)、[开发与发布](../docs/DEVELOPMENT.md) 和 [对应版本验证记录](../docs/VALIDATION.md)。本文件替代原来停留在 0.0.19 的模块说明；旧说明仍保留在原型归档的同名文件中。

当前开发版本为 **0.1.4-internal / versionCode 27**。支持四服务器选择、参考答案先生成后复核、图片实际内容比较，以及用户确认后的手动提交。详见 [实现与验证](../docs/REFERENCE_ANSWERS.md)。

## 当前实现

单个 `app` 模块，Kotlin + Jetpack Compose + Material 3，最低 Android 16 / API 36，支持普通版、长江版、黄河版及荷塘版雨课堂，新增服务器保留测试支持标记。手机直连 DeepSeek API，用户界面只有课堂、题目、设置三页；模式为关闭／发题通知／自动答题。提供微信授权、扫码后选课连接、题目采集与通知、五类题目求解提交、课堂归档与离线 HTML 复习导出。

已移除 AI App 自动化、无障碍服务和产品演练入口。内部旧格式和合成测试不代表这些功能仍对用户开放。Release 使用 `cn.rainnotify.app`；Debug 为 `.debug`，隔离设备测试为 `.smoke`。

## 开发入口

Android Studio 打开本目录。固定 JDK 17、AGP 8.13.2、Gradle Wrapper 8.13、Kotlin 2.3.21、SDK Platform 36 和 Build Tools 35.0.0。SDK／JDK／缓存／调试证书路径由仓库外或被忽略的本机配置传入，不使用旧的临时盘符映射。

在**仓库根目录**执行 `./tools/build_android.ps1 -Lint -UnitTests`。直接使用 Wrapper 时，在本目录执行 `./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug`，Windows 使用 `gradlew.bat`。发布、依赖校验、Python 测试、设备测试和签名要求见开发说明。

`ui/AppUi.kt` 中有 Compose Preview；预览不代表真实后台／权限／课堂提交验收。独立设备测试使用合成数据，不需要真实账号或 Key。正式版诊断从关于与帮助导出，不依赖 `run-as`。

## 源码与测试

- `app/src/main/java/cn/rainnotify/app/`：`ui` 页面、`auth` 授权与请求、`monitor` 监听、`question` 归档、`answer` API 与提交。
- `app/src/test/`：协议、模型、队列、提交与导出的合成回归。
- `app/src/androidTest/`：独立 Smoke 包设备检查。
- `app/src/debug/`：只读题目比较诊断，不进入 Release。

27 版 Debug、Release 各 155 项 JVM 测试和 12 项独立设备测试通过。真实课堂提交与新增服务器登录仍待实测。只使用所选版本的实际测试报告。25 版 JVM／CI 已通过，7 项设备测试与 23 → 24 覆盖升级证据属于 24 版；没有把它们记作 25 版新设备测试。详细边界见验证记录。
