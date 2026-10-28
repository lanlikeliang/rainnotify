# 雨课堂助手 RainNotify

面向普通版雨课堂的原生 Kotlin Android 辅助 App，当前最低 Android 16。通过雨课堂授权、课堂监听与手机直接调用 DeepSeek API，实现新题提醒、题干/选项/原图展示、AI 求解与校验提交。

本仓库从 `v0.0.19-baseline` 原型建立正式开发基础，当前产物为内部验证包。真实课堂提交、长时休眠和多品牌稳定性仍待验收，不标记为 1.0 稳定版。

0.1.3-internal 使用 API-only 的 Compose 界面：课堂、题目、设置三个入口；同一个胶囊控件选择“关闭 / 发题通知 / 自动答题”。先在微信扫码，再选择课堂并确认连接。课堂页显示课程与班级、当前模型和最新题摘要；题目页按课堂保存收到的题目，并导出含原图与答案的离线复习笔记。发题通知不需要 API Key，自动答题须完成当前模型自检。产品不提供 AI App 自动化、无障碍、题目导入或演练入口。最新范围与限制见 [课堂反馈修复记录](docs/CLASSROOM_REVIEW.md)。

- [开发、环境、测试与发布](docs/DEVELOPMENT.md)
- [内部验证包安装](docs/INSTALL.md)
- [已验证范围与待验收事项](docs/VALIDATION.md)
- [五题型与 API 原型记录](docs/五种题型与DeepSeekAPI-0.0.19.md)
- [项目技术规划](docs/雨课堂移动提醒-技术规划.md)

Android Studio 打开 `android-app`。Windows 标准检查：

```powershell
./tools/build_android.ps1 -Lint -UnitTests
python -m unittest discover -s tests -v
```

版本固定在 Version Catalog 和 Gradle Wrapper。私有仓库只收录源码、合成测试、工具与审核后的文档；课堂原件、登录信息、API Key、签名密钥和本机构建产物不进入 Git。`docs` 下其他版本文档为历史记录，其中旧安装包路径属于原型归档。
