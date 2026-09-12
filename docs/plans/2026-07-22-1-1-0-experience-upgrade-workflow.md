---
intent: 1.1.0 体验升级版构建——以可靠性基线、用户感知体验、工程可发布性三条主线收敛 1.0.1 遗留债务，并交付四项面向用户的体验增强（剪贴板自动识别/重试与错误透明/解析可取消/转换器独立入口），伴随解析失败诊断埋点
success_criteria: 验收三连全绿（testDebugUnitTest/lintDebug/assembleDebug）；Room schema 升至 2.json 并经迁移单测验证；Android 13+ dataExtractionRules 生效；仓库零"拾帧"残留（grep 排除 build）；ConverterWorker 经 Worker 测试覆盖 RUNNING/SUCCESS/FAILED/CANCELED 四态；剪贴板监听与重试入口经 JVM 单测；versionCode=3 versionName="1.1.0"；CHANGELOG 1.1.0 段落叙事完整；release CI 可在无签名密钥时回退 assembleRelease-as-debug
risk_level: medium
auto_approve: false
branch: hotl/1-1-0-experience-upgrade
worktree: false
---

# 1.1.0 执行工作流：拾光无痕体验升级版

设计依据：[拾光无痕倡议](../designs/shiguang-wuhen-initiative.md) + 本轮代码审计结论（1.0.1 遗留 17 处品牌残留、Room 无迁移策略、Converter 走 ViewModel 非 WorkManager、pendingDownloads 用 remember 非 rememberSaveable、X 解析器 NO_MEDIA 语义误用、share() 缺 ClipData）。
环境说明：分支 hotl/1-1-0-experience-upgrade 从当前主干 HEAD 派生。所有 gradle 命令统一前缀 `JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew --no-daemon --max-workers=2`（在 /workspace 执行）。assembleDebug/lintDebug/assembleRelease 额外加 `-Dorg.gradle.jvmargs=-Xmx2048m` 防止 4GiB cgroup OOM；多任务分开逐个执行，勿单命令并行。
五段推进：Step 1-4 为第一段（可靠性基线），Step 5-7 为第二段（转换器 WorkManager 化），Step 8-11 为第三段（用户感知体验），Step 12-13 为第四段（解析诊断埋点），Step 14-16 为第五段（发布工程与验收）。

## Steps

- [ ] **Step 1: 5 分钟语义修复三连（X 解析器 NO_MEDIA→INVALID_RESPONSE / share() 补 ClipData / DiagnosticLogger async write）**
action: ① domain/parser/XStructuredMediaParser.kt L85 的 else 分支与 L119 的 catch-all，将 `MediaParseException(Reason.NO_MEDIA, ...)` 改为 `MediaParseException(Reason.INVALID_RESPONSE, ...)`（区分"接口返回但无媒体"与"响应结构非法"两种语义；NO_MEDIA 仅用于确认平台公开无水印源确实不存在）。② util/FileIntentUtils.kt share()（L23-36）：在 `Intent.ACTION_SEND` 后补 `clipData = ClipData.newRawUri(null, uri)` 并 `addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)`（修复分享目标丢失 URI 权限导致微信/QQ 打不开的体感问题）。③ util/DiagnosticLogger.kt write()（L89 附近）：用 `scope.launch(Dispatchers.IO) { ... }` 包裹文件写入，避免主线程磁盘 IO；runCatching 保留。④ 跑现有测试确认无回归。
loop: until testDebugUnitTest 全绿
max_iterations: 3
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2

- [ ] **Step 2: Room schema v2——新增 retryPayload 列 + 迁移策略**
action: ① data/database/HistoryEntity.kt：新增 `retryPayload: String? = null` 字段（JSON 序列化的重试上下文：platform、originalUrl、selectedMediaItem 子集）。② data/database/FramePickDatabase.kt：`@Database(..., version = 2, exportSchema = true)`。③ FramePickApplication.kt L79-85 的 databaseBuilder：补 `.fallbackToDestructiveMigrationFrom(1)`（1.0.x→1.1.0 用户历史可接受丢失，免责声明已声明仅供个人学习）并加 `.addMigrations()` 占位（未来 v3 起改非破坏式）。④ 跑 KSP 生成 2.json schema 导出到 app/schemas/。⑤ 新增 HistoryEntityRetryPayloadColumnTest：验证迁移后旧记录 retryPayload 为 null、新记录可写入读出。
loop: until schema 导出 + 迁移测试通过
max_iterations: 4
gate: human
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2 --tests "*HistoryEntity*"
  - type: artifact
    path: app/schemas/com.framepick.app.data.database.FramePickDatabase/2.json
    assert:
      kind: exists

- [ ] **Step 3: Android 13+ dataExtractionRules + allowBackup 收敛**
action: ① 新建 res/xml/data_extraction_rules.xml：`<data-extraction-rules>` 内 `cloud-backup` 与 `device-transfer` 均仅包含 `<include domain="shared" path="."/>` 与 `<include domain="database" path="."/>`（排除 cache 与 shared_prefs 中 disclaimer/diagnostic 等本地状态——首启阻断与诊断快照不应跨设备迁移）。② 新建 res/xml/backup_rules.xml（旧设备 fallback，<full-backup-content> 同样排除 cache 与敏感 prefs）。③ AndroidManifest.xml L15：`android:allowBackup="true"` 保留，补 `android:dataExtractionRules="@xml/data_extraction_rules"` 与 `android:fullBackupContent="@xml/backup_rules"`。④ 验证 manifest merge 无冲突。
loop: false
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m"

- [ ] **Step 4: 17 处"拾帧"品牌残留全量清理 + CHANGELOG 1.1.0 叙事**
action: ① grep -rn "拾帧" app/src --exclude-dir=build 确认 10 文件 17 处：worker/MediaDownloadWorker.kt(2)、domain/parser/InternationalPublicPageMediaParser.kt(4)、PublicMusicPageMediaParser.kt(1)、InstagramPlaylistJsonExtractor.kt(1)、MediaParser.kt(2)、DoubaoPublicMediaParser.kt(2)、YtDlpMediaParser.kt(1)、util/FileIntentUtils.kt(1)、util/DiagnosticLogger.kt(1)、util/InternationalPlatformSupport.kt(2)。② 逐文件按语境替换为"拾光无痕"或移除（日志 tag 改 FramePick 或 ShiguangWuhen；用户可见文案一律"拾光无痕"；诊断报告标题 L47 改"拾光无痕诊断报告"）。③ CHANGELOG.md 新增 `## [1.1.0] - 2026-07-22` 段落，分 Added/Changed/Fixed/Internal 四组叙事：Added=剪贴板识别/重试/可取消/转换器入口；Changed=Room v2/dataExtractionRules/品牌统一；Fixed=X 解析器语义/share URI 权限/旋转丢失；Internal=ConverterWorker/诊断埋点/release CI。④ 再 grep 确认零残留。
loop: until grep 零残留
max_iterations: 3
verify:
  - type: shell
    command: cd /workspace && ! grep -rn --exclude-dir=build "拾帧" app/src
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2

- [ ] **Step 5: ConverterWorker 骨架——镜像 DownloadRepository 模式**
action: ① 新建 worker/ConverterWorker.kt（继承 CoroutineWorker）：构造接收 inputData（mode/videoUri/outputParams），doWork() 内根据 ConversionMode 调用现有 ffmpeg/youtubedl 转换逻辑（自 ConverterViewModel L280-400 区段抽取），返回 Result.success()/failure()/retry()。② 新建 data/converter/ConverterRepository.kt：镜像 data/download/DownloadRepository.kt（enqueue/workId/observe/StateFlow<ConverterTask>），ConverterTask data class 含 workId/mediaItemId/mode/state/progress/outputUri/errorMessage。③ 新建 data/converter/ConverterModels.kt（ConverterTask + 复用 ConversionMode/ConversionStatus）。④ FGS：ConverterWorker 配 `foregroundServiceType = Data`（AndroidManifest 补 foregroundServiceType="dataSync" 如未声明）。⑤ 不动 ConverterViewModel（Step 6 改造其接线）。
loop: until ConverterWorker 可独立编译
max_iterations: 3
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m"

- [ ] **Step 6: ConverterViewModel 改为 ConverterRepository 观察者 + 历史 RUNNING 覆盖**
action: ① ui/converter/ConverterViewModel.kt 重构：移除 viewModelScope 内联转换逻辑，改为注入 ConverterRepository，startConversion() 调 repository.enqueue(...)，UiState 收集 repository.converterTask StateFlow。② HistoryDao.markInterruptedConversions()（L32-40）扩充 operationType IN 列表：新增 'DOWNLOAD'（启动时把所有 RUNNING 的下载与转换一并改 CANCELED，避免重启后幽灵任务）。③ FramePickApplication.kt L46-48 调用点注释同步。④ ConverterViewModel 的 IDLE/RUNNING/SUCCESS/FAILED/CANCELED 五态映射保持 UI 语义不变。⑤ 保留 ConverterScreen 接口不动（Step 11 才迁移入口）。
loop: until testDebugUnitTest 全绿
max_iterations: 4
gate: human
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2

- [ ] **Step 7: ConverterWorker 四态测试 + 启动时中断覆盖测试**
action: ① 新建 worker/ConverterWorkerTest.kt：四用例 RUNNING→进度更新、SUCCESS→outputUri 落地+历史 SUCCESS、FAILED→历史 FAILED+errorMessage、CANCELED→历史 CANCELED。② 新建 data/database/HistoryDaoMarkInterruptedTest.kt：插入 DOWNLOAD+VIDEO_TO_GIF 各一条 RUNNING，调用 markInterruptedConversions()，验证两条均变 CANCELED。③ 跑测试直至全绿。
loop: until 两测试类全绿
max_iterations: 4
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2 --tests "*ConverterWorkerTest*" --tests "*HistoryDaoMarkInterruptedTest*"

- [ ] **Step 8: 剪贴板自动识别 + 解析提示（用户感知体验 #1）**
action: ① 新建 util/ClipboardMonitor.kt：纯 JVM 可测函数 `extractShareableUrl(text: String): String?`（正则匹配支持平台的 URL 模式，复用 ParserRegistry 已识别的域名集合）。② MainActivity.kt：剪贴板管理器 onPrimaryClipChanged 监听，提取后写入 sharedText StateFlow（L21 既有），触发 MediaExtractorApp 的 LaunchedEffect（L71-76 既有）已有链路。③ ExtractorScreen.kt：当 sharedText 非空且与当前 inputText 不同时，底部弹 Snackbar「检测到链接，是否解析？」+ action「解析」按钮，点击后 setInputText + parse()。④ 仅在 onResume 后首次剪贴板变化时触发（避免每次切回都弹）。⑤ 新增 ClipboardMonitorTest：覆盖抖音/小红书/B站/X 等平台 URL 与非 URL 文本。
loop: until ClipboardMonitorTest 全绿
max_iterations: 3
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2 --tests "*ClipboardMonitorTest*"

- [ ] **Step 9: 重试 + 错误透明（用户感知体验 #2）**
action: ① ExtractorViewModel.parse() 失败时：把 platform、originalUrl、选中 MediaItem 的关键字段（downloadStrategy/formatSelector/backupUrl 等）序列化为 JSON 写入 HistoryEntity.retryPayload（Step 2 新增列），历史 status=FAILED。② HistoryScreen/DownloadsScreen 历史条目：FAILED 状态显示「重试」按钮，点击后读 retryPayload 反序列化 → 重新走 parse() 或 download() 链路（区分 operationType：PARSE 重走 parse、DOWNLOAD 重走 download）。③ 错误透明：FAILED 条目点击「详情」展示 Reason 枚举的人类可读文案（ACCESS_RESTRICTED→"平台访问受限，稍后重试或换网络"、NO_CLEAN_SOURCE→"未找到无水印源，该平台可能不支持"、INVALID_RESPONSE→"响应结构异常，已记录诊断"、NO_MEDIA→"该链接无可提取媒体"）。④ 新增 RetryPayloadSerdeTest：JSON 往返一致性。
loop: until RetryPayloadSerdeTest 全绿
max_iterations: 4
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2 --tests "*RetryPayloadSerdeTest*"

- [ ] **Step 10: 解析可取消 + 旋转安全（用户感知体验 #3）**
action: ① ExtractorViewModel.clear()（L194-197）：parseJob.cancel() 后追加 historyRepository 把当前 RUNNING 的 PARSE 历史改 CANCELED（避免脏数据）。② ExtractorScreen.kt L107：`var pendingDownloads by remember { mutableStateOf(emptyList<MediaItem>()) }` 改为 `var pendingDownloads by rememberSaveable { mutableStateOf(emptyList<MediaItem>()) }`，并为 MediaItem 补 @Serializable 或自定义 Saver（保证旋转后不丢）。③ 清空按钮（L215）逻辑保持 `enabled = !state.isParsing && state.inputText.isNotEmpty()`，新增 parse 进行中时按钮文案改「取消」并触发 cancelParse()（新方法：cancel parseJob + 历史 CANCELED）。④ 新增 ClearUpdatesHistoryTest：clear() 后历史 RUNNING→CANCELED。
loop: until ClearUpdatesHistoryTest 全绿
max_iterations: 3
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2 --tests "*ClearUpdatesHistoryTest*"

- [ ] **Step 11: 转换器独立入口（用户感知体验 #4）**
action: ① ui/navigation/MediaExtractorApp.kt：AppDestination 新增 CONVERTER（R.string.nav_converter, Icons.Outlined.SwapHoriz），bottomBar listOf(EXTRACTOR, CONVERTER, DOWNLOADS, SETTINGS) 四 Tab。② strings.xml：nav_converter 文案改「格式转换」（原「格式转换」子页标题保留）。③ SettingsScreen.kt：移除 showConverter rememberSaveable 与 onOpenConverter 入口（L72/L92/L100），ConverterScreen 不再嵌入设置页。④ ConverterScreen 接 ConverterViewModel（Step 6 已改接线），独立 Tab 渲染。⑤ 验证四 Tab 导航与 BackHandler 行为。
loop: false
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m"

- [ ] **Step 12: 解析失败诊断埋点——DiagnosticSanitizer 接入**
action: ① 三个易碎解析器（Doubao/Xigua/Kuaishou）的 catch 分支：在抛 MediaParseException 前，调用 DiagnosticSanitizer.sanitize(originalResponse) + sanitizeUrl(originalUrl) 后写入 DiagnosticLogger（Step 1 已 async write）。② util/DiagnosticSanitizer.kt（已存在 sanitize/sanitizeUrl）：补 sanitizeHeaders(map: Map<String,String>): Map<String,String>（脱敏 Authorization/Cookie/Set-Cookie 值为 "***"）。③ DiagnosticLogger 新增 logParseFailure(platform: String, url: String, reason: Reason, sanitizedSnippet: String)：结构化写入「[PARSE_FAIL] platform=X reason=Y url=Z snippet=...」。④ 新增 DiagnosticSanitizerTest：覆盖 URL 脱敏、Authorization/Cookie 替换、响应片段截断。
loop: until DiagnosticSanitizerTest 全绿
max_iterations: 3
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2 --tests "*DiagnosticSanitizerTest*"

- [ ] **Step 13: 三个易碎解析器埋点接线 + 集成验证**
action: ① DoubaoPublicMediaParser.kt：catch 分支调 logParseFailure("doubao", url, Reason.X, DiagnosticSanitizer.sanitize(response))。② KuaishouParser（如存在；否则跳过并记录）。③ XiguaParser（如存在；否则记录到 follow-up）。④ 跑全量测试确认埋点不破坏现有解析路径（runCatching 包裹诊断写入，诊断失败不影响主流程抛出）。⑤ lintDebug 处理新告警。
loop: until testDebugUnitTest + lintDebug 全绿
max_iterations: 3
verify:
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew lintDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m"

- [ ] **Step 14: 版本号升级 + release CI 工作流**
action: ① app/build.gradle.kts L39-40：versionCode=3，versionName="1.1.0"。② 新建 .github/workflows/release.yml：触发 on push tag v*；job：checkout → setup-java@17 → setup-android-sdk → 解密 keystore.properties（如 secrets.KEYSTORE_BASE64 存在则 base64 -d > keystore.properties，否则跳过——build.gradle.kts L15 hasReleaseSigning 守卫会自然回退）→ ./gradlew testDebugUnitTest assembleRelease（带降堆参数）→ upload-artifact app/build/outputs/apk/release/*.apk。③ 验证 CI yaml 语法（actionlint 或本地 act --check）。
loop: until release.yml 语法校验通过
max_iterations: 3
verify:
  - type: artifact
    path: .github/workflows/release.yml
    assert:
      kind: exists
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m"

- [ ] **Step 15: 1.1.0 全量验收三连**
action: 依次执行 testDebugUnitTest、lintDebug、assembleDebug（带降堆参数，分开逐个执行），全部退出码 0。然后 grep 验证：① ! grep -rn "拾帧" app/src --exclude-dir=build；② grep 确认 dataExtractionRules 引用生效；③ ls app/schemas/.../2.json 存在。`git status --short` 核对变更清单：应仅限 worker/(新增 ConverterWorker + 测试)、data/converter/(新增 ConverterRepository + Models)、data/database/(HistoryEntity +2 列、FramePickDatabase v2)、ui/extractor/(clear + clipboard + rememberSaveable)、ui/converter/(ViewModel 重构)、ui/navigation/(四 Tab)、ui/settings/(移除 converter 嵌入)、util/(ClipboardMonitor + DiagnosticSanitizer 补充 + FileIntentUtils fix + DiagnosticLogger async)、res/xml/(data_extraction_rules + backup_rules)、AndroidManifest.xml、res/values/strings.xml、app/build.gradle.kts、.github/workflows/release.yml、CHANGELOG.md、app/schemas/2.json、docs/。不应出现其他意外文件。
loop: until 三命令全绿且清单符合预期
max_iterations: 3
gate: human
verify:
  - type: shell
    command: cd /workspace && ! grep -rn --exclude-dir=build "拾帧" app/src
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew lintDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m"
  - type: shell
    command: cd /workspace && JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m"

- [ ] **Step 16: 提交、打 tag、请求人工审查**
action: 提交 commit：`1.1.0: experience upgrade — reliability baseline, converter WorkManager, clipboard/retry/cancel/converter-tab, parser diagnostics, release CI`。打 tag v1.1.0（触发 release CI 构建 APK artifact）。向用户汇总：①新增/修改/删除文件清单与行数 ②测试用例新增数（ClipboardMonitor/RetryPayload/ConverterWorker/HistoryDaoMarkInterrupted/ClearUpdatesHistory/DiagnosticSanitizer 等）与总用例数 ③验收三连状态 ④品牌零残留 grep 结果 ⑤CHANGELOG 1.1.0 段落摘要 ⑥release CI artifact 链接（如已触发）。附真机自测清单：①剪贴板自动识别 Snackbar ②FAILED 历史重试 ③解析中取消 ④旋转后 pendingDownloads 不丢 ⑤四 Tab 导航含格式转换 ⑥分享到微信可打开 ⑦Android 13+ 备份规则生效。请用户审查并确认 1.1.0 发布。
loop: false
gate: human
verify:
  type: human-review
  check: 用户确认 1.1.0 代码、测试、CHANGELOG、真机自测通过，可发布
