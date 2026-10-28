# 黑白应用图标

按用户要求，以雨课堂官方发布的蓝底白色图标为参考，仅改为黑底白色图形。

- 官方发布页：https://apps.apple.com/cn/app/id1515487012
- 官方开发者：北京慕华信息科技有限公司。
- 参考图来自 Apple lookup API 的 artworkUrl512，读取于 2026-10-27。
- 使用 imagegen 编辑背景，保留雨滴与书页的布局；原图与生成中间文件不进入仓库。
- 本项目为独立辅助工具，并非雨课堂官方应用。
- 发布资源：`android-app/app/src/main/res/drawable-nodpi/ic_rainnotify_art.png`。
- SHA-256：`5ddcb7497b62ff7719e749e8a59d5ca7ba30ded29b535b699e548bc579c4735a`。仓库审查仅对这个确切路径与摘要放行，其他二进制限制不变。

0.1.3-internal 使用 `mipmap-anydpi/ic_launcher.xml` 的自适应图标层：纯黑背景，原图前景四周 18dp 边距（108dp 画布内居中 72dp）。PNG 摘要未改变；支持桌面统一裁切。实际厂商桌面比例仍需设备验证。
