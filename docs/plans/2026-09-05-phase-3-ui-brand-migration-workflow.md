---
intent: 品牌独立迁移至拾光无痕(com.framepick.app)并重构为三Tab信息架构（首页解析/下载管理/设置），含 Material 3 动态取色与阻断式免责声明
success_criteria: 验收三连全绿；app/ 源码与配置零 com.example.mediaextractor 残留（grep 排除 build 目录验证）；免责声明门控有纯 JVM 单测；自适应图标前景/背景/单色三件套齐备
risk_level: medium
auto_approve: true
branch: hotl/phase-3-ui-brand-migration
worktree: false
---

# Phase 3 执行工作流：拾光无痕品牌迁移 + UI 三 Tab 重构

设计依据：[Phase 3 设计](../designs/2026-09-05-phase-3-ui-brand-migration-design.md)。
环境说明：分支 hotl/phase-3-ui-brand-migration 从 hotl/phase-2-watermark-enhancement HEAD（1a54774）派生。所有 gradle 命令统一前缀 `JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew --no-daemon --max-workers=2`（在 /workspace 执行）。assembleDebug/lintDebug 额外加 `-Dorg.gradle.jvmargs=-Xmx2048m` 防止 4GiB cgroup OOM；三任务分开逐个执行，勿单命令并行。
两段推进：Step 1-3 为第一段（品牌迁移，独立 commit `Phase 3a`），Step 4-12 为第二段（UI 重构，独立 commit `Phase 3b`）。

## Steps

- [x] **Step 1: 包名目录全量迁移与类名品牌化**
action: 机械迁移（git mv 保留历史）：① `mkdir -p app/src/main/java/com/framepick && git mv app/src/main/java/com/example/mediaextractor app/src/main/java/com/framepick/app && rmdir app/src/main/java/com/example`；test 目录同法（app/src/test/java/com/example/mediaextractor → app/src/test/java/com/framepick/app）。② 全量替换包声明与导入：`grep -rl --exclude-dir=build 'com\.example\.mediaextractor' app/src | xargs sed -i 's/com\.example\.mediaextractor/com.framepick.app/g'`。③ app/build.gradle.kts：namespace 与 applicationId 改 `com.framepick.app`，versionCode=1，versionName="1.0.0"。④ 类重命名（文件名+类名+全部引用+AndroidManifest android:name）：MediaExtractorApplication → FramePickApplication，MediaExtractorDatabase → FramePickDatabase（Room @Database version 保持 1，databaseBuilder 库名改 "framepick.db"）。⑤ res/values/themes.xml 与 res/values-v31/themes.xml：Theme.MediaExtractorDemo → Theme.FramePick（含 .Starting 派生主题），AndroidManifest 两处 theme 引用同步。⑥ res/values/strings.xml：app_name → 拾光无痕。⑦ 清理旧 Room schema：`git rm -r app/schemas/com.example.mediaextractor* 2>/dev/null || true`（如存在）。
loop: until 两项验证全过
max_iterations: 4
verify:
  - type: shell
    command: cd /workspace && ! grep -rn --exclude-dir=build "com.example.mediaextractor" app/
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2

- [ ] **Step 2: 矢量图标与品牌资源**
action: ① 重绘 res/drawable/ic_launcher_foreground.xml：取景框四角括号 + 对角光痕意象的矢量路径（中心 66% 安全区内留白构图）。② 重绘 res/drawable/ic_launcher_monochrome.xml（同前景路径纯色，供 Android 13+ 主题图标）。③ res/values/colors.xml：新增 ic_launcher_background 品牌底色（沿用暖橙色系衔接回退主题）。④ 更新 res/drawable/ic_splash_brand.xml 为新品牌图形。⑤ 检查 res/mipmap* 目录：minSdk 26 下仅 mipmap-anydpi-v26 自适应引用即可，如存在旧 PNG 密度图则 git rm。⑥ BrandSplashScreen.kt 与 strings.xml 中的品牌名/标语文案更新为「拾光无痕」（标语可定为「拾取光影，不留痕迹」）。
loop: false
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m"
  - type: artifact
    path: app/src/main/res/drawable/ic_launcher_foreground.xml
    assert:
      kind: exists

- [ ] **Step 3: 第一段验收、提交与人工检查点**
action: 依次执行 testDebugUnitTest、lintDebug、assembleDebug（带降堆参数，分开逐个），全部退出码 0。`git status --short` 核对变更仅限：全量源文件包声明/导入、两个重命名类文件、build.gradle.kts、AndroidManifest.xml、res/（主题/图标/字符串/colors）、schemas 清理。提交 commit：`Phase 3a: migrate package to com.framepick.app`。向用户汇报 grep 零残留结果与三连状态，人工确认后进入第二段（UI 重构）。
loop: until 三命令全绿
max_iterations: 3
gate: human
verify:
  - type: shell
    command: cd /workspace && ! grep -rn --exclude-dir=build "com.example.mediaextractor" app/
  - type: shell
    command: cd /workspace && git status --short

- [ ] **Step 4: Material 3 动态取色**
action: 修改 ui/theme/Theme.kt：① MediaExtractorTheme 重命名 FramePickTheme（MainActivity 调用点同步）。② 主题函数内判断 `Build.VERSION.SDK_INT >= Build.VERSION_CODES.S` 时用 `dynamicLightColorScheme(LocalContext.current)` / `dynamicDarkColorScheme(...)`，否则回退现有暖秋色 LightColors/DarkColors。③ ui/theme/Color.kt：AutumnExtendedColors 重命名 FramePickExtendedColors，新增动态模式扩展色映射——success/successContainer/onSuccessContainer 保持固定绿色对（语义状态色与壁纸无关），decoration/brandOrange 动态模式下映射为 colorScheme 的 primary/tertiary 衍生，selectedContainer 动态模式下用 colorScheme.secondaryContainer。④ 5 个使用点（ScreenComponents.kt、ExtractorScreen.kt、HistoryScreen.kt、ConverterScreen.kt、ui/navigation/MediaExtractorApp.kt）的 `autumnColors` 引用改为新访问器 `extendedColors`，回退主题观感保持不变。
loop: false
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2

- [ ] **Step 5: 免责声明门控（TDD）**
action: ① 新建测试 app/src/test/java/com/framepick/app/data/preferences/DisclaimerStoreTest.kt（先红后绿），4 用例：未同意(null)→应弹、同意同版本→不弹、同意旧版本→应弹、空串→应弹。② 新建 app/src/main/java/com/framepick/app/data/preferences/DisclaimerStore.kt：常量 `DISCLAIMER_VERSION = "v1"`、纯函数 `shouldShowDisclaimer(acceptedVersion: String?, currentVersion: String = DISCLAIMER_VERSION): Boolean`（null/空/不等于当前版本 → true）、SharedPreferences 封装 `acceptedVersion(context)` / `accept(context)`（key：`disclaimer_accepted_version`）。
loop: until DisclaimerStoreTest 全部通过
max_iterations: 3
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2 --tests "*DisclaimerStoreTest*"

- [ ] **Step 6: 首启阻断式免责声明 UI**
action: ① strings.xml 新增 5 条免责条款文案：仅供个人学习与研究；请遵守各平台服务条款及著作权法规，下载内容请在授权范围内使用；不提供访问控制/DRM/付费内容绕过；「无水印」指优先提取平台公开无水印播放源，画面内已烧录标识不在处理范围；使用本工具产生的一切责任由使用者自行承担。② 新建 ui/components/DisclaimerDialog.kt：全屏 AlertDialog（标题「免责声明」+ 条款列表 + 「同意并继续」/「退出」按钮；同意 → DisclaimerStore.accept + 状态更新，退出 → (context as Activity).finish()）。③ ui/navigation/MediaExtractorApp.kt：顶层读取 DisclaimerStore.acceptedVersion，shouldShowDisclaimer 为 true 时仅渲染 DisclaimerDialog（阻断三 Tab），同意后进入主界面；已同意则直接进入。
loop: false
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m"

- [ ] **Step 7: 下载管理页（进行中任务区块）**
action: ① 新建 ui/downloads/DownloadsScreen.kt + DownloadsViewModel.kt（包 com.framepick.app.ui.downloads）。② 把 ExtractorScreen 的 DownloadTasksSection 与 downloadLabel 进度文本逻辑（下载中 x%/等待下载/重新下载，基于 WorkInfo.State）迁移至 DownloadsScreen，由 DownloadsViewModel 自 DownloadRepository.observeDownloads() 收集（复用 ExtractorViewModel 同款逻辑）。③ ExtractorScreen 移除内嵌下载任务区，但保留媒体条目下载按钮及其状态文本（ExtractorViewModel 继续观察 downloads）。④ 导航：AppDestination.HISTORY 改为 DOWNLOADS（Icons.Outlined.Download + nav_downloads「下载管理」），DOWNLOADS 目标渲染 DownloadsScreen；HistoryScreen.kt/HistoryViewModel.kt 本步保留在源码树（历史区块 Step 9 迁入、诊断区块 Step 8 迁出后再删除）。
loop: false
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2

- [ ] **Step 8: 设置页与三 Tab 导航定型**
action: ① 新建 ui/settings/SettingsScreen.kt + SettingsViewModel.kt（诊断操作自 HistoryViewModel 迁移：refreshDiagnostics/clearDiagnostics/createDiagnosticReport 及对应 StateFlow）。② SettingsScreen 条目：格式转换工具（子页面入口）、免责声明（重看，复用 DisclaimerDialog，仅「关闭」按钮）、诊断中心（诊断文本展示 + 刷新/复制/分享/清空操作，行为与原 HistoryScreen 一致）、关于（版本 1.0.0、GPL-3.0 开源声明、组件清单：yt-dlp/FFmpeg/Coil/OkHttp/Jackson/Jsoup/Room/WorkManager/Compose）。③ 导航重构：AppDestination 定型为 EXTRACTOR（首页解析，Icons.Outlined.Link）/ DOWNLOADS（下载管理）/ SETTINGS（设置，Icons.Outlined.Settings）三个可见 Tab + 隐藏子目标 CONVERTER（ConverterScreen）；设置页跳转子页，BackHandler 返回设置。④ strings.xml：nav_extractor→「解析」、新增 nav_downloads/nav_settings，废弃 nav_converter/nav_history 顶层标签（子页标题保留「格式转换」）。
loop: false
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m"

- [ ] **Step 9: 历史记录并入下载管理**
action: ① DownloadsViewModel 接入 HistoryRepository（复用原 HistoryViewModel 的历史查询/删除逻辑），DownloadsScreen 在进行中任务区块下方增加历史记录区块（条目展示/打开/删除行为与原 HistoryScreen 一致）。② 删除 ui/history/HistoryScreen.kt 与 ui/history/HistoryViewModel.kt（此时历史已迁下载管理、诊断已迁设置，两文件无剩余职责）。③ 确认 sharedText 分享接收仍定向 EXTRACTOR 目标且 acceptSharedText 链路未破坏（MediaExtractorApp 中既有逻辑）。
loop: false
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2

- [ ] **Step 10: 字符串与资源清理**
action: ① 清理 strings.xml 中已无引用的废弃条目（旧导航标签等），新增条目统一命名规范。② `grep -rn "nav_converter\|nav_history" app/src --exclude-dir=build` 确认仅子页标题类保留引用。③ 跑 lintDebug 处理新引入的告警（如未使用资源）。
loop: until lintDebug 通过
max_iterations: 3
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew lintDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m"

- [ ] **Step 11: Phase 3 全量验收**
action: 依次执行验收三连（testDebugUnitTest、lintDebug、assembleDebug，带降堆参数，分开逐个执行），全部退出码 0。然后 grep 验证旧包名零残留（排除 build 目录）与 `git status --short` 变更清单核对：第一段已提交后，本段变更应仅限 ui/（新增 downloads/settings/DisclaimerDialog、重构 MediaExtractorApp/Theme/Color、瘦身 ExtractorScreen、删除 history/）、data/preferences/（新增 DisclaimerStore）、res/values/strings.xml 及 docs/；不应出现其他意外文件。
loop: until 三命令全绿且清单符合预期
max_iterations: 3
verify:
  - type: shell
    command: cd /workspace && ! grep -rn --exclude-dir=build "com.example.mediaextractor" app/
  - type: shell
    command: cd /workspace && git status --short

- [ ] **Step 12: 提交并请求人工审查**
action: 提交第二段 commit：`Phase 3b: rebuild three-tab UI with dynamic color and disclaimer`。向用户汇总：新增/修改/删除文件清单、测试结果（新增 DisclaimerStoreTest 用例数）、验收三连状态、grep 零残留结果。附真机自测清单：①安装后品牌名「拾光无痕」与新图标 ②首启免责声明阻断（同意后不再弹、设置页可重看） ③三 Tab 导航与子页面返回 ④Android 12+ 动态取色观感 ⑤分享接收定向解析页 ⑥下载进度/历史/转换/诊断导出链路。请用户审查并确认 Phase 3 完成（initiative 收官）。
loop: false
gate: human
verify:
  type: human-review
  check: 用户确认 Phase 3 代码与测试结果，真机自测通过，initiative 验收完成
