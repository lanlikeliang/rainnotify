# 开发与发布

正式工程位于 `D:\GPTitems\RainNotify`，Android Studio 打开其 `android-app` 子目录。原型目录保留课堂原件、PDF、历史安装包和实测资料；这些内容不进入 Git。基线标签为 `v0.0.19-baseline`。

## 环境

固定 AGP 8.13.2、Gradle Wrapper 8.13、Kotlin 2.3.21、完整 JDK 17、SDK Platform 36、Build Tools 35.0.0。Android Studio Rabbit 1 / 2026.2.1 自身使用随附 JBR，Gradle 使用 JDK 17。首轮不安装模拟器。

Windows 本机可复制 `toolchain.example.json` 为 `.toolchain.local.json`，填写绝对路径；或传入 `-JdkPath`、`-SdkPath`，使用 `JAVA_HOME`、`ANDROID_HOME`。不要提交本机配置。IDE 的 Gradle JDK、Gradle user home 应与命令行一致；SDK 写在忽略的 `android-app/local.properties`。

调试证书可通过 `RAINNOTIFY_DEBUG_KEYSTORE` 或 `android-app/local.properties` 的 `rainnotify.debugKeystore` 指定。迁移现有开发环境须保留原调试证书；全新开发者可使用自己生成的证书，但不能覆盖原证书签名的预览版。Java properties 文件内路径用 `/`，非 ASCII 字符用 Unicode 转义。

## 常用命令

在仓库根目录运行：

```powershell
./tools/build_android.ps1 -Lint -UnitTests
python -m unittest discover -s tests -v
python tools/check_repository.py
python tools/check_lint.py android-app/app/build/reports/lint-results-debug.xml
./tools/build_android.ps1 -DeviceTests
./tools/build_android.ps1 -Variant Release -Lint -UnitTests
```

Linux/macOS 从 `android-app` 调用 `./gradlew`，Windows 也可直接调用 `gradlew.bat`。不使用临时盘符映射或本地 Gradle 解压路径。版本集中于 `gradle/libs.versions.toml`；Wrapper ZIP、Wrapper JAR 和依赖均校验摘要。新增依赖时审核官方来源，再显式使用 `-WriteVerification` 更新依赖校验文件并检查差异；日常构建不关闭校验。

构建脚本失败返回非零结果，成功输出 APK 路径及 `.build.json` 收据，记录提交号、工作区状态、校验项和 APK 摘要。发布只接受当前干净提交构建的 Release，且必须运行 Lint 和对应单元测试。

设备测试使用独立的 `cn.rainnotify.app.smoke` 包，连接并解锁一台 Android 16 手机，允许 USB 调试与测试组件安装。`-DeviceTests` 构建测试包并通过 `run_device_tests.ps1` 普通安装运行，不使用会自动卸载目标 App 的测试清理流程，不清空预览版或正式版数据。测试覆盖首页、设置、缺少课堂时的反馈、合成题导入和离线演练；不需要账号或 API Key，不进入真实课堂。模拟服务器覆盖单选、多选、投票、填空和主观题的获取、求解、格式校验、提交及防重复提交。

## 模块与边界

保留单个 `app` 模块：`auth` 管理登录与雨课堂请求，`monitor` 管理课堂连接与事件，`question` 管理题目和 ZIP，`answer` 管理 AI 适配、求解及提交。`src/debug` 中的 USB 测试入口不进入 Release。诊断不得含账号凭据、题目原文或答案；Release 通过设置页导出诊断，不能依赖 `run-as`。

本轮不迁移页面。后续 Compose/Material 3 与 ViewModel 按页面迁移；工具版本升级和 UI 迁移分别验证。真实课堂提交、多品牌、长时休眠属于产品验收，不由构建通过替代。

## Git 与 CI

私有仓库采用短分支与 PR。每个升级步骤独立提交；提交和 PR 执行 Linux 检查。发布前手动执行 Windows release readiness。CI 仅用合成数据，运行 Python 测试、JVM 测试、Lint 和 Debug/未签名 Release 构建；设备测试在已授权真机执行。禁止把签名密钥、登录票据、真实课堂导出或 API Key 上传至仓库或 CI。

`tools/lint-reviewed.json` 记录逐项审核的旧警告，新增警告或任何 Error/Fatal 均失败。现有 UI 文案拼接和 KTX 建议留待页面迁移；静态 Context 警告已核对只保存 applicationContext；二维码相册 URI 的同步持久化保留原行为。版本提示与固定编译基线分开管理。权限、生命周期、导出组件、发布行为类的新问题必须先修复。

## 正式签名

首次在仓库外创建签名目录：

```powershell
./tools/initialize_signing.ps1 -SigningDirectory D:\DevTools\Android\private\release-signing
./tools/package_release.ps1 -SigningDirectory D:\DevTools\Android\private\release-signing
```

密码受当前 Windows 账号的 DPAPI 保护，不输出到终端，也不写入仓库。密钥一旦用于分发不得替换。用户须在自己的交互式终端运行 `backup_signing.ps1`，选择仓库外的备份位置并设置独立密码；密码不发送到聊天。仅复制 DPAPI 文件不能作为跨电脑恢复方案。

```powershell
./tools/backup_signing.ps1 -SigningDirectory D:\DevTools\Android\private\release-signing -Destination E:\PrivateBackup\RainNotify-release.p12
```

每次发布递增 VersionCode，使用相同密钥；包内记录 Git 提交号，产物包含 APK、SHA-256、签名指纹、构建收据、验证报告和安装说明。CI 不保存正式密钥。

安装须显式指定产物；工具从 APK 读取包名和版本：

```powershell
./tools/install_probe.ps1 -ApkPath <absolute-apk-path> -Serial <adb-device-serial>
```

发布前确认同签名升级保留本地数据，独立正式包与 `.debug` 包共存。细节见 INSTALL.md 和 VALIDATION.md。
