# 雨课堂助手 RainNotify

面向普通版雨课堂的原生 Kotlin Android 辅助 App，当前最低 Android 16。已有登录、课堂监听、新题通知、题干/选项/原图采集、ZIP 导入导出、AI App 辅助及 DeepSeek API 求解与提交流程。

本仓库从 `v0.0.19-baseline` 原型建立正式开发基础，当前产物为内部验证包。真实课堂提交、长时休眠和多品牌稳定性仍待验收，不标记为 1.0 稳定版。

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
