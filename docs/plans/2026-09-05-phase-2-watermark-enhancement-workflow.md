---
intent: 统一五大平台结构化解析的无水印源标注与优先级策略，快手视频改标同源 PUBLIC_CLEAN，抖音在无干净源时降级返回带水印项并禁下载，完善水印边界提示
success_criteria: WatermarkPolicy 有独立单测且五解析器接入；抖音 playwm 降级、快手 PUBLIC_CLEAN 有 fixture 断言；testDebugUnitTest + lintDebug + assembleDebug 全绿且现有水印相关测试零回退
risk_level: medium
auto_approve: true
branch: hotl/phase-2-watermark-enhancement
worktree: false
---

# Phase 2 执行工作流：现有平台无水印策略增强

设计依据：[Phase 2 设计](../designs/2026-09-05-phase-2-watermark-enhancement-design.md)。
环境说明：JDK 17 与 Android SDK 已就绪（Phase 1 装好）。当前分支为 hotl/phase-1-pipixia-bilibili-parsers，本工作流分支 hotl/phase-2-watermark-enhancement 从其 HEAD 派生（Phase 1 已验收未合并，Phase 2 代码依赖其解析器）。所有 gradle 命令统一前缀 `JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew --no-daemon --max-workers=2`（在 /workspace 执行；前两项为沙箱容器兼容参数，不影响正常环境）。assembleDebug 额外加 `-Dorg.gradle.jvmargs=-Xmx2048m -Dkotlin.daemon.jvmargs=-Xmx1024m` 防止 4GiB cgroup OOM。

## Steps

- [x] **Step 1: WatermarkPolicy 失败测试先行**
action: 新建 /workspace/app/src/test/java/com/example/mediaextractor/domain/model/WatermarkPolicyTest.kt（JUnit4，目录不存在则创建）。测试对象为待实现的 `domain/model/WatermarkPolicy.kt`（object，两个函数：`ranked(items: List<MediaItem>): List<MediaItem>` 按 SourceWatermark 等级升序（PUBLIC_ORIGINAL→PUBLIC_CLEAN→UNKNOWN→WATERMARKED）、同级按 width*height 降序稳定排序；`withRecommendation(items: List<MediaItem>): List<MediaItem>` 在 ranked 基础上重置推荐位——首个非 COVER 项 isRecommended=true，其余项 false，全为 COVER 时首项推荐）。至少五个用例：混合等级排序、同等级分辨率排序、稳定排序（同等级同分辨率保序）、空列表与单元素、推荐位重置（含 COVER 在前时跳过封面选首个视频/图片）。fixture 直接构造 MediaItem（模式参照 PublicSourceClassifierTest 的构造方式）。
loop: false
verify:
  - type: artifact
    path: app/src/test/java/com/example/mediaextractor/domain/model/WatermarkPolicyTest.kt
    assert:
      kind: exists

- [x] **Step 2: 实现 WatermarkPolicy**
action: 新建 /workspace/app/src/main/java/com/example/mediaextractor/domain/model/WatermarkPolicy.kt，按 Step 1 定义实现 object WatermarkPolicy（纯函数、无 IO；等级映射用私有 val rank: SourceWatermark → Int；withRecommendation 用 MediaItem.copy 重置 isRecommended）。实现后 WatermarkPolicyTest 必须全绿（Step 1 的红色测试转绿）。
loop: until WatermarkPolicyTest 全部通过
max_iterations: 4
verify: JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2 --tests "*WatermarkPolicyTest*"

- [x] **Step 3: 抖音降级返回带水印源**
action: 修改 /workspace/app/src/main/java/com/example/mediaextractor/domain/parser/DouyinStructuredMediaParser.kt：① `toCleanVideoCandidate` 中 cleanUrl 仍含 playwm（重写失败）的候选不再返回 null 丢弃，改为保留原始 playwm URL 进入带水印候选集（VideoCandidate data class 视需要加 `watermarked: Boolean = false` 字段或平行列表）；② `toVideoItems` 组装：存在干净候选时维持现状（仅返回 PUBLIC_CLEAN 项，排序不变）；干净候选为空且带水印候选非空时，对每个带水印候选生成 WATERMARKED 项——sourceWatermark=WATERMARKED、mediaUrl 保留原始 playwm 链接、qualityLabel 追加「带水印」、watermarkNote=「公开页面仅提供带水印播放源；已禁用下载，可预览确认。」（UI 的 isDownloadAllowed 已有禁下载兜底）。③ 更新 /workspace/app/src/test/java/com/example/mediaextractor/domain/parser/DouyinRouterDataExtractorTest.kt：新增 playwm-only fixture 用例（bit_rate/play_addr 全部指向 /aweme/v1/playwm/ 且带 watermark=1 参数），断言返回项 sourceWatermark==WATERMARKED、mediaUrl 含 playwm、videos 非空；既有 `convertsPublicPlaywmEndpointToVerifiedClean720pVideoSource` 用例必须保持绿（重写成功路径行为不变）。
loop: until DouyinRouterDataExtractorTest 全部通过
max_iterations: 4
verify: JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2 --tests "*DouyinRouterDataExtractorTest*"

- [x] **Step 4: 快手视频改标 PUBLIC_CLEAN**
action: 修改 /workspace/app/src/main/java/com/example/mediaextractor/domain/parser/KuaishouStructuredMediaParser.kt 中 KuaishouStateExtractor 的视频项组装：sourceWatermark 从 SourceWatermark.UNKNOWN 改为 SourceWatermark.PUBLIC_CLEAN，watermarkNote 改为「与快手网页播放器同源（manifest/mainMvUrls）；平台未对网页播放源附加分享水印，作者上传时烧录的标识不在处理范围。」。图片项与封面项维持 UNKNOWN 及现有 note 不变。更新 /workspace/app/src/test/java/com/example/mediaextractor/domain/parser/KuaishouStateExtractorTest.kt：视频用例断言从 UNKNOWN 改为 PUBLIC_CLEAN 且 note 含「网页播放器同源」；图片/封面 UNKNOWN 断言保留。
loop: until KuaishouStateExtractorTest 全部通过
max_iterations: 4
verify: JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2 --tests "*KuaishouStateExtractorTest*"

- [x] **Step 5: 五解析器接入统一优先级**
action: 五个结构化解析器在 ParsedMedia 组装处对 items 调用 `WatermarkPolicy.withRecommendation(items)`：DouyinStructuredMediaParser（toVideoItems 返回前与图片项组装处）、KuaishouStructuredMediaParser（StateExtractor.extract 的 items）、XiaohongshuStructuredMediaParser（StateExtractor 组装处）、PipixiaStructuredMediaParser、BilibiliStructuredMediaParser（各自 items 组装返回处；B站需保持匿名档位 distinctBy 语义，withRecommendation 只重排不删除）。小红书 note 现状已是「源+依据」句式，无需改动文案。检查五个解析器对应测试（DouyinRouterDataExtractorTest / KuaishouStateExtractorTest / XiaohongshuStateExtractorTest / PipixiaStateExtractorTest / BilibiliStateExtractorTest）中依赖 items 顺序或 isRecommended 的断言，按新排序规则更新（预期：等级优先→同级分辨率优先；B站匿名档位仍按高度降序；图集同等级同尺寸保序，首图推荐不变）。
loop: until 全量 testDebugUnitTest 通过（零回退）
max_iterations: 4
verify: JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --no-daemon --max-workers=2

- [x] **Step 6: Phase 2 全量验收**
action: 依次执行验收三连（testDebugUnitTest、lintDebug、assembleDebug，assembleDebug 带降堆参数），全部退出码 0（沙箱内存限制下三任务须分开逐个执行，勿单命令并行）。然后 `git status --short` 确认变更仅限：新增 WatermarkPolicy.kt、WatermarkPolicyTest.kt，修改 DouyinStructuredMediaParser.kt、KuaishouStructuredMediaParser.kt、XiaohongshuStructuredMediaParser.kt、PipixiaStructuredMediaParser.kt、BilibiliStructuredMediaParser.kt、DouyinRouterDataExtractorTest.kt、KuaishouStateExtractorTest.kt（及 Step 5 涉及的其他解析器测试）、docs/ 下设计与工作流文档；不应出现其他意外文件。
loop: until 三命令全绿且变更清单符合预期
max_iterations: 3
verify:
  - type: shell
    command: JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug --no-daemon --max-workers=2 -Dorg.gradle.jvmargs="-Xmx2048m -Dfile.encoding=UTF-8" "-Dkotlin.daemon.jvmargs=-Xmx1024m"
  - type: shell
    command: git status --short

- [x] **Step 7: 提交并请求人工审查**
action: 在分支 hotl/phase-2-watermark-enhancement 上提交全部 Phase 2 变更（含 docs/ 下设计文档与工作流），commit 信息：`Phase 2: unify watermark source policy across structured parsers`。向用户汇总：新增/修改文件清单、测试结果（新增用例数）、验收三连状态、快手 PUBLIC_CLEAN 标注依据与抖音降级返回行为。请用户审查并确认是否进入 Phase 3（UI 三 Tab + 品牌迁移）。
loop: false
gate: human
verify:
  type: human-review
  check: 用户确认 Phase 2 代码与测试结果，批准进入 Phase 3
