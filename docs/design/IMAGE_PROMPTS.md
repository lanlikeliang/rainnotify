# 精简界面效果图 V2 生成提示词

2026-10-26。内置 image_gen；无需用户 API Key，未使用 CLI。图中全部为合成示例。

最终统一首页以“同一首页的三档状态”图中的圆角三段控件为准，日常页面图据此对齐；生成提示词如下，首轮图仅用于迭代。

## 最终对齐编辑（以首轮日常页面为编辑目标，以三档状态图为布局参考）

```text
Edit image 1 (the three-screen board for 雨课堂助手) using image 2 only as a precise layout/style reference. Keep image 1's entire middle latest-question screen and right settings screen, board title and labels unchanged except correcting middle subtitle to the exact simplified Chinese text 本次课堂 · 最新一题. Replace only the LEFT phone's interior with the exact HOME AUTO MODE screen shown in the RIGHTMOST phone of image 2. Preserve that reference screen's structure faithfully: three-row current-account/current-class/current-model white card WITH thin line row icons; then pale mint mode panel WITHOUT robot icon, heading 自动答题已开启, description 收到新题后自动作答并提交; a single large PILL-SHAPED three-segment control 关闭 / 发题通知 / 自动答题 with final pill selected deep teal; footnote 仅对本次课堂开启后收到的新题生效; one quiet line 最新题目与处理结果，请到「题目」查看. Use the same 3-row card and mode panel vertical proportions as image2. Fit naturally into image1's phone frame, consistent stroke widths and typography across all 3 screens. Do not retain the robot icon or rectangular segmented buttons from image1. No history, no rehearsal, no recent processing. Chinese text legible, precise, original composition preserved.
```

## 日常页面

```text
Use case: ui-mockup. Generate a beautifully restrained, high fidelity Chinese Android native app UI board. Landscape 2400x1500 ratio, three large identical upright phone screens side by side, realistic 412x880 screen proportions, straight-on flat interface, slim dark neutral frames, no angled mockup. Board heading "雨课堂助手" and subtitle "API 正式版 · 精简界面方案"; small note "设计稿 · 示例数据". Labels below "01 课堂" / "02 最新题目" / "03 设置".
Art direction: premium practical Material 3 inspired, soft off-white #F5F7F8 background, clean white surfaces, dark ink #182D34 text, deep muted teal #087F76 accent, very pale mint selected areas; extremely legible simplified Chinese; 24px page titles and 16px body at phone scale; fine line icons, restrained separators, 20px card corners, generous whitespace. No gradients, heavy drop shadows, glass, 3D decorative objects, charts, fake metrics or marketing illustration. Bottom navigation EXACTLY "课堂" "题目" "设置" on all three, appropriate selected tab teal.
Important design rule: the home page contains a SINGLE prominent three-state MODE CONTROL, NO separate listening button or automatic switch, NO recent processing list, NO rehearsal or import/export. Three mode labels must always be simultaneously visible and readable: "关闭" / "发题通知" / "自动答题". Implement it as one large rounded mint panel with a heading indicating current mode, one short status description, and a full-width THREE-SEGMENT control strip; selected segment deep teal with white text and others neutral. All segments are equal size with large hit targets. It must read as one coherent primary mode selector, not three disparate cards. Selected mode describes actual operational state.
SCREEN1:
Android clock14:05. Title "课堂", subtitle "当前连接，一目了然".
One clean white information card contains three stacked rows with subtle separators: row label "当前账户" value "用户 A" small "已授权"; row label "当前课堂" value "通识课程" small green dot "已连接"; row label "当前模型" value "DeepSeek · Flash" small teal "图文自检通过". Account and course are fictional. No big redundant change buttons.
Below, the main large mint MODE CONTROL roughly 190px tall: small icon and large heading "自动答题已开启"; caption "收到新题后自动作答并提交"; bottom strip three exact labels "关闭" / "发题通知" / "自动答题", only final segment selected deep teal. Under the strip within the same panel small line "仅对本次课堂开启后收到的新题生效".
Below panel subtle plain helper "最新题目与处理结果，请到「题目」查看". Small unobtrusive note "AI 可能答错，运行期间保持网络可用". Spacious lower area; no recent processing card. Bottom nav class selected.
SCREEN2:
Clock14:05. Title "题目", subtitle "本次课堂 · 最新一题". Top thin line label "多选题" small pill then "14:02 收到". A clean white original question area displays a synthetic exercise with exact text "以下哪些属于中国传统节日？" and options "A. 春节" "B. 清明节" "C. 中秋节" "D. 元旦". Small label "题目原图" and discreet corner expand icon. It should look like original text image inside question card, no user real data.
Next answer card: title "AI 答案", large "A、B、C", small "DeepSeek · Flash".
Next compact status area with small timeline completed checks: "已获取题目" / "AI 作答完成" / "雨课堂已确认提交"; strongest green chip "已提交". Tiny helper "收到新题后，本页自动更新". No historical lists, rehearsal, import, export, extra solve toggle, recent processing navigation or retry submitted answers. Bottom nav题目selected.
SCREEN3:
Clock14:05. Title "设置", subtitle "连接与运行配置".
First modest group label "账号"; compact white row "雨课堂账号" with value "已授权" and chevron.
Second group "AI 服务"; single white card containing "服务商" value "DeepSeek", "模型" value "Flash · 支持图片", "API Key" value "已加密保存" with chevron. Thin divider then inline green check "图文自检通过" and subtle outlined text button "重新自检". Small helper under it "自检仅验证图文能力，不向课堂提交".
Third group "运行保障"; white rows "通知与声音" value "已允许" teal chevron; "后台运行" value "需检查" amber chevron.
Quiet footer "仅需配置雨课堂助手本身"; tiny text "关于与帮助  ›". No more groups or full-page diagnostics button; no AI app options/accessibility/import/export/exercises. Bottom nav设置selected.
All six required concepts (account, classroom, model, notification, background, mode) should be clear, not cluttered. Do not invent additional controls. All body text is legible and aligned, nothing clipped.
```

## 同一首页的三档状态

```text
Use case: ui-mockup. High fidelity UI design board showing the SAME Chinese Android app HOME SCREEN in THREE operating states. Landscape 2400x1500 ratio, three identical slim portrait phones, no perspective. Heading "一套界面，三种状态"; subline "关闭 → 发题通知 → 自动答题"; small note "首次使用也保持相同布局 · 示例数据".
Use premium clean restrained Material3 inspired style: warm gray-white #F5F7F8 canvas, white information card, dark ink #182D34 text, muted teal #087F76, pale mint mode panel, small amber for prerequisites. Native Chinese sans-serif, readable 24px titles16pxbody, generous whitespace, subtle separators20pxcorners, thin line icons. No gradients, illustrations, graphs or inflated feature list.
Critical: all three screens use exactly the same element coordinates and dimensions: title "课堂", subtitle "当前连接，一目了然"; white three-row current-information card; large rounded MODE CONTROL panel with current-mode heading and description plus a single fullwidth three-segment choice "关闭" / "发题通知" / "自动答题"; identical bottomnavigation "课堂"selected,"题目","设置". This is one big coherent three-state button component, NOT separate switches and listening start/stop buttons. All three labels always visible; current choice strongly highlighted.
Left screen FIRST OPEN, CLOSED:
three info rows "当前账户" value "未授权" with small actionable teal "去授权 ›"; "当前课堂" value "未选择" muted "授权后选择"; "当前模型" value "未配置" teal "去配置 ›".
Mode panel heading "当前已关闭"; explanation "选择模式，开始本次课堂"; segmented strip "关闭" SELECTED darkink, "发题通知" and "自动答题" unselected neutral. Small panelfootnote "发题通知无需配置 AI 服务".
One small inline helper beneath with infoicon and text "首次使用：先授权账户，再选择课堂". A second quiet line "通知与后台配置会在开始前检查". No separate wizard, no checklist of large cards, no rehearsal, no imports, no large welcome illustration.
Center screen NOTIFICATION:
three info rows "当前账户" value "用户 A" tag "已授权"; "当前课堂" value "通识课程" dot "已连接"; "当前模型" value "未配置" small "通知模式无需配置".
Modepanelheading "发题通知已开启"; description "接收并提醒新题，不调用 AI"; strip "关闭","发题通知" SELECTED teal,"自动答题"; smallpanelfootnote "切到自动答题前，将检查 API 配置".
Below quiet helper "新题内容与状态在「题目」更新". No ongoing-answer/status cards.
Right screen AUTOMATIC:
three info rows "当前账户" "用户 A" "已授权"; "当前课堂" "通识课程" "已连接"; "当前模型" "DeepSeek · Flash" green "图文自检通过".
Modepanelheading "自动答题已开启"; description "收到新题后自动作答并提交"; strip "关闭","发题通知","自动答题" SELECTED teal; smallpanelfootnote "仅对本次课堂开启后收到的新题生效".
Below quiethelper "最新题目与处理结果，请到「题目」查看".
Androidclock14:05 all. Below respectivephone captions "首次打开 · 关闭" / "只收题目 · 发题通知" / "自动处理 · 自动答题". Maintain same information architecture and spatial layout across all states. Do not add any old history rehearsal, AI App, import/export, recent processing, extra on/off controls or buttons.
```
