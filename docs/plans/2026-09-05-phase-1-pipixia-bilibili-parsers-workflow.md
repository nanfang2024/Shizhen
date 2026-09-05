---
intent: 新增皮皮虾与B站结构化解析器并接入解析链路，使两平台解析不再单纯依赖 yt-dlp 上游规则
success_criteria: 皮皮虾分享链接与B站视频/动态图文链接经新解析器返回结构化结果；testDebugUnitTest + lintDebug + assembleDebug 全绿；现有测试零回退
risk_level: medium
auto_approve: true
branch: hotl/phase-1-pipixia-bilibili-parsers
worktree: false
---

# Phase 1 执行工作流：皮皮虾 + B站结构化解析器

设计依据：[Phase 1 设计](../designs/2026-09-05-phase-1-pipixia-bilibili-parsers-design.md)。
环境说明：沙箱无 Android SDK 与 JDK 17（仅有 Java 25），前 3 步为环境就绪与基线。所有 gradle 命令统一使用前缀 `JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew`（在 /workspace 执行）。网络经 http://127.0.0.1:18080 代理（环境变量已全局生效）。

## Steps

- [x] **Step 1: 安装 JDK 17**
action: 执行 `mise install java@17.0.2`，完成后 `mise where java@17.0.2` 应输出安装路径。不改变全局默认 Java（当前默认 25），仅通过 JAVA_HOME 前缀使用 17。
loop: until mise where java@17.0.2 成功输出路径
max_iterations: 3
verify: mise exec java@17.0.2 -- java -version 2>&1 | grep -q 'version "17' && echo JDK17-OK

- [x] **Step 2: 安装 Android SDK 组件**
action: 下载 commandline-tools（https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip，若 404 则从 https://dl.google.com/android/repository/repository2-1.xml 解析最新 commandlinetools-linux 链接），解压到 /opt/android-sdk/cmdline-tools/latest（注意 zip 内层是 cmdline-tools 目录，需重命名为 latest）。然后执行：`yes | mise exec java@17.0.2 -- /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk --licenses` 接受许可，再安装 `platform-tools` 与 `platforms;android-34`（build-tools 版本由 AGP 首次构建按需自动安装，许可已接受即可）。
loop: until sdkmanager --list_installed 列出 platforms;android-34
max_iterations: 3
verify: mise exec java@17.0.2 -- /opt/android-sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=/opt/android-sdk --list_installed | grep -E "platforms;android-34|platform-tools" | head -2

- [x] **Step 3: Gradle 首次同步与基线测试**
action: 在 /workspace 执行 `JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest`。首次运行会下载 Gradle 分发与全部 Maven 依赖（含 yt-dlp/Python、FFmpeg arm64 原生组件，走 settings.gradle.kts 已配置的镜像），耗时较长属预期。现有 126 个 JVM 测试必须全绿，此为「零回退」基线。若因环境问题失败，修复环境后重试；若因个别测试在沙箱环境不稳定失败，记录失败用例并在 gate 中说明。
loop: until testDebugUnitTest 退出码 0
max_iterations: 4
verify: JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest

- [x] **Step 4: 抓取皮皮虾与B站公开页面样本**
action: 经代理 curl 抓取真实公开页面样本（带桌面浏览器 User-Agent）：B站视频页 `https://www.bilibili.com/video/BV1GJ411x7h7` 存 /tmp/bili-sample.html；皮皮虾 Web 首页或公开作品页（pipix.com 域下任一公开 item 页）存 /tmp/pipix-sample.html；B站匿名 playurl 接口样本（`https://api.bilibili.com/x/player/playurl?bvid=BV1GJ411x7h7&cid=73655109&qn=64&fnval=0`，需带 Referer https://www.bilibili.com/）存 /tmp/bili-playurl.json。检查样本内嵌 JSON 状态字段路径（B站 __INITIAL_STATE__.videoData；皮皮虾页面状态对象名与作品字段）。若任一目标抓取失败或返回风控壳，按设计 D5 降级：采用公开已知结构构造 fixture，并在对应测试文件头部注释注明「结构来源：公开已知结构（沙箱抓取失败）」。
loop: false
verify: bash -c 'for f in /tmp/bili-sample.html /tmp/pipix-sample.html /tmp/bili-playurl.json; do test -s "$f" && echo "$f ok" || echo "$f fallback"; done'

- [x] **Step 5: 皮皮虾 StateExtractor 测试与实现**
action: 新建 /workspace/app/src/main/java/com/example/mediaextractor/domain/parser/PipixiaStructuredMediaParser.kt（本步先只包含 internal object PipixiaStateExtractor，解析器类 Step 6 补）：`extract(sourceUrl: String, finalUrl: String, html: String): ParsedMedia?`，从分享页内嵌 JSON 状态读取当前作品的视频直链（含宽高/时长）、图集图片列表、封面、标题、作者，模式参照 KuaishouStructuredMediaParser.kt 内 KuaishouStateExtractor 的实现与字段防御式读取；视频直链项 downloadStrategy=DIRECT、图集/图片 DIRECT、封面 COVER。新建测试 /workspace/app/src/test/java/com/example/mediaextractor/domain/parser/PipixiaStateExtractorTest.kt（JUnit4，模式照 KuaishouStateExtractorTest.kt），至少四个用例：视频作品（断言直链/宽高/hasAudio/封面 COVER 项）、图集作品（多张 IMAGE）、无可读媒体状态返回 null、非作品页（如首页）返回 null。fixture 结构按 Step 4 真实样本优先。
loop: until PipixiaStateExtractorTest 全部通过
max_iterations: 5
verify: JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --tests "*PipixiaStateExtractorTest*"

- [x] **Step 6: 皮皮虾解析器接入解析链路**
action: 在 PipixiaStructuredMediaParser.kt 补全 `PipixiaStructuredMediaParser(client: OkHttpClient) : MediaParser`，HTTP 壳模式照 KuaishouStructuredMediaParser：canHandle 用 PublicUrlNormalizer.hostOf 匹配 pipix.com（含 h5.pipix.com 子域，短链重定向由 OkHttpClient followRedirects 展开）；parse 走 GET + 浏览器 UA，401/403 抛 ACCESS_RESTRICTED，验证码/登录壳检测后抛 ACCESS_RESTRICTED，状态不可读抛 NO_MEDIA，DiagnosticLogger 分类 PIPPIX_STRUCTURED_PARSE。修改 util/PlatformRecognizer.kt：平台表新增「皮皮虾」条目（域名 pipix.com）。修改 MediaExtractorApplication.kt：parserRegistry 列表在 YtDlpMediaParser 之前插入 `PipixiaStructuredMediaParser(httpClient)`。在 PlatformRecognizerTest.kt 补皮皮虾识别断言、ParserRegistryTest.kt 补注册顺序相关断言（若该测试断言解析器集合则同步更新）。
loop: until 全量 testDebugUnitTest 通过（零回退）
max_iterations: 4
verify: JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest

- [x] **Step 7: B站 StateExtractor 测试与实现**
action: 新建 /workspace/app/src/main/java/com/example/mediaextractor/domain/parser/BilibiliStructuredMediaParser.kt（本步先只包含 internal object BilibiliStateExtractor）：三个纯函数——`extractVideoMeta(sourceUrl, html): BiliVideoMeta?`（解析 __INITIAL_STATE__.videoData 的 bvid/cid/title/pic/owner.name/duration/pages）、`extractOpusImages(sourceUrl, html): List<String>?`（动态/opus 图文页图片 URL 列表）、`buildQualityItems(videoPageUrl, meta, playUrlJson): List<MediaItem>`（解析匿名 playurl 响应：fnval=0 的 durl mp4 直链生成 DIRECT 项；响应中 accept_quality/format 明确的匿名档位生成 YT_DLP 策略项，formatSelector 按画质高度/qn 构造，qualityLabel 用接口返回的清晰度名；全部为匿名档位，不出现需要登录的档位也不冒充高画质；封面生成 COVER 项）。新建测试 /workspace/app/src/test/java/com/example/mediaextractor/domain/parser/BilibiliStateExtractorTest.kt：视频页元数据用例、opus 图文用例、匿名 playurl（360p/480p durl）用例、无状态页面返回 null 用例。fixture 按 Step 4 真实样本优先。
loop: until BilibiliStateExtractorTest 全部通过
max_iterations: 5
verify: JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest --tests "*BilibiliStateExtractorTest*"

- [x] **Step 8: B站解析器接入解析链路**
action: 在 BilibiliStructuredMediaParser.kt 补全 `BilibiliStructuredMediaParser(client: OkHttpClient) : MediaParser`：canHandle 匹配 bilibili.com 的 /video/、/opus/、/bangumi/media 路径与 b23.tv 短链域；parse 流程——GET 最终页面（桌面 UA，b23.tv 由重定向展开）→ extractVideoMeta / extractOpusImages → 对视频页再 GET 匿名 playurl（api.bilibili.com/x/player/playurl?bvid=..&cid=..&qn=64&fnval=0，Header Referer: https://www.bilibili.com/，失败或被限时只保留元数据项并如实降级不冒充）→ 组装 ParsedMedia（platform=哔哩哔哩）。访问限制/网络/无媒体异常语义照 MediaParseException 既有 Reason，DiagnosticLogger 分类 BILIBILI_STRUCTURED_PARSE。修改 MediaExtractorApplication.kt：在 YtDlpMediaParser 之前插入 `BilibiliStructuredMediaParser(httpClient)`。补 ParserRegistryTest.kt 注册断言。注意番剧 bangumi 路径 canHandle 可匹配但解析遇登录/地区限制直接 ACCESS_RESTRICTED，交由回退链处理。
loop: until 全量 testDebugUnitTest 通过（零回退）
max_iterations: 4
verify: JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest

- [x] **Step 9: Phase 1 全量验收**
action: 依次执行验收三连（testDebugUnitTest、lintDebug、assembleDebug），全部退出码 0。然后 `git status --short` 确认变更仅限：新增 PipixiaStructuredMediaParser.kt、BilibiliStructuredMediaParser.kt 及两个测试文件，修改 PlatformRecognizer.kt、MediaExtractorApplication.kt、PlatformRecognizerTest.kt、ParserRegistryTest.kt（及必要时 strings.xml）；不应出现其他意外文件。
loop: until 三命令全绿且变更清单符合预期
max_iterations: 3
verify:
  - type: shell
    command: JAVA_HOME=$(mise where java@17.0.2) ANDROID_SDK_ROOT=/opt/android-sdk ./gradlew testDebugUnitTest lintDebug assembleDebug
  - type: shell
    command: git status --short

- [x] **Step 10: 提交并请求人工审查**
action: 在分支 hotl/phase-1-pipixia-bilibili-parsers 上提交全部 Phase 1 变更（含 docs/ 下设计文档），commit 信息：`Phase 1: add pipixia & bilibili structured parsers`。向用户汇总：新增/修改文件清单、测试结果（含新增用例数）、验收三连输出状态、Step 4 样本抓取是否降级。请用户审查并确认是否进入 Phase 2（无水印策略增强）。
loop: false
gate: human
verify:
  type: human-review
  check: 用户确认 Phase 1 代码与测试结果，批准进入 Phase 2
