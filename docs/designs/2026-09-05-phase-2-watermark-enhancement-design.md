---
design_type: phase
created_at: 2026-09-05
---

# Phase 2 设计：现有平台无水印策略增强

设计依据：[拾光无痕 initiative](shiguang-wuhen-initiative.md)。聚焦抖音/快手/小红书的水印分类审查、无水印源优先级统一、水印边界提示完善；Phase 1 新增的皮皮虾/B站解析器同步接入统一优先级。

## Intent Contract

```
intent: 统一五大平台结构化解析的无水印源标注与优先级策略，快手视频改标同源 PUBLIC_CLEAN，抖音在无无水印源时降级返回带水印项并禁下载，完善水印边界提示文案
constraints: 不改 SourceWatermark 枚举语义；不做算法擦除；不绕过平台访问控制；现有水印相关测试零回退；UI 布局不动（P3 处理）
success_criteria: testDebugUnitTest + lintDebug + assembleDebug 全绿；WatermarkPolicy 有独立单测；抖音 playwm 降级、快手 PUBLIC_CLEAN 有 fixture 断言
risk_level: medium（快手同源标注依赖页面结构持续可读；抖音降级路径改变失败语义，需测试锢定）
```

## Verification Contract

```
verify_steps:
  - run tests: JAVA_HOME=<jdk17> ANDROID_SDK_ROOT=<sdk> ./gradlew testDebugUnitTest（沙箱加 JAVA_TOOL_OPTIONS="-XX:-UseContainerSupport" --no-daemon --max-workers=2）
  - run lint: 同前缀 ./gradlew lintDebug
  - build: 同前缀 ./gradlew assembleDebug（沙箱加 -Dorg.gradle.jvmargs=-Xmx2048m）
  - check: 新增 WatermarkPolicyTest；DouyinStateExtractorTest/KuaishouStateExtractorTest 断言更新；PublicSourceClassifierTest 等现有水印测试零回退
  - confirm: 三命令退出码 0；git status 变更清单符合预期
```

## Governance Contract

```
approval_gates: 设计批准（本轮）；实施完成后用户人工审查确认进入 Phase 3
rollback: 独立分支 hotl/phase-2-watermark-enhancement，出问题分支级回退
ownership: 实现代理负责编码与自动化验收；产品所有者负责决策与 Phase 门禁（含真机自测水印标注实际表现）
```

## Scope

**In**

- 新增 domain 层 `WatermarkPolicy`：按 PUBLIC_ORIGINAL > PUBLIC_CLEAN > UNKNOWN > WATERMARKED 排序（同级按分辨率/码率），提供推荐位选择；抖音、快手、小红书、皮皮虾、B站五个结构化解析器组装时接入
- 快手视频项改标 `PUBLIC_CLEAN`，note 改为「与快手网页播放器同源；平台未对网页播放源附加分享水印」；图片/封面维持 UNKNOWN（无同源播放依据）
- 抖音视频候选：`/playwm/` 等带水印端点不再静默丢弃——无无水印源时降级返回并标 `WATERMARKED`（UI 既有 `isDownloadAllowed` 禁下载兜底）；有干净源时仍优先干净源
- 小红书：标注逻辑维持 `isOriginal` 区分，统一 watermarkNote 句式（源是什么 + 为什么这么标）
- 皮皮虾/B站：仅接入统一排序，标注结论不变

**Out**

- 不改 SourceWatermark 枚举值与语义
- 不改 UI 布局/新增控件（SourceWatermarkLabel 现有四态文案已覆盖 WATERMARKED 展示）
- 不动 PublicSourceClassifier 的 yt-dlp 元数据规则与感知平台表
- 不做算法擦除/裁切/修复水印（initiative Non-goal）

## Decisions

| # | 决策 | 选择 | 被否方案 |
|---|---|---|---|
| D1 | 快手视频水印标注 | 同源可标 PUBLIC_CLEAN：播放流与网页播放器同源，平台仅对 App 分享导出链附加水印；note 如实说明依据 | 维持 UNKNOWN（用户永远看到「未验证」，可用性预期差）——用户已选同源标注 |
| D2 | 抖音带水印源处置 | 降级返回 + WATERMARKED + UI 禁下载：仅当公开页面只提供带水印版本时返回，用户可见失败原因 | 完全排除并抛 NO_CLEAN_SOURCE（用户看不到原因）；无条件返回（有干净源时仍应优先干净源）——用户已选降级返回 |
| D3 | 优先级统一实现 | domain 层 WatermarkPolicy，各结构化解析器组装时调用 | ParserRegistry 统一后处理（职责膨胀、与元数据回退策略叠加混乱）；仅 UI 排序（不解决数据层推荐位） |
| D4 | 接入范围 | 五个结构化解析器全部接入统一排序（含 Phase 1 两个） | 仅三个增强平台（皮皮虾/B站排序不一致残留） |
| D5 | 小红书改动 | 维持 isOriginal 区分标注，仅统一 note 句式 | 引入新的判断字段（页面无可验证增量，YAGNI） |

## Surface

**domain/model（新增 1 个文件）**

`WatermarkPolicy`：纯函数对象，两个入口——`ranked(items: List<MediaItem>): List<MediaItem>`（按水印等级+分辨率稳定排序）与 `bestRecommended(items)`（选推荐位项）。无状态、无 IO，JVM 可测。

**解析器层（修改 5 个文件）**

- `DouyinStructuredMediaParser`：视频候选收集时保留 playwm 候选；存在干净源时仅返回干净源（现状），否则降级返回 WATERMARKED 项；组装末尾过 `WatermarkPolicy`
- `KuaishouStructuredMediaParser`：视频项 sourceWatermark 改 PUBLIC_CLEAN + 新 note；组装末尾过 `WatermarkPolicy`
- `XiaohongshuStructuredMediaParser`：note 句式统一；组装末尾过 `WatermarkPolicy`
- `PipixiaStructuredMediaParser` / `BilibiliStructuredMediaParser`：组装末尾过 `WatermarkPolicy`（标注不变）

**测试（修改 2 个 + 新增 1 个文件）**

- 新增 `WatermarkPolicyTest`：排序等级、同级分辨率、空/单元素
- `DouyinStateExtractorTest`：playwm-only fixture → WATERMARKED 降级断言
- `KuaishouStateExtractorTest`：视频项 PUBLIC_CLEAN 断言更新（原 UNKNOWN 断言改写）

## Risks & Open Questions

1. **快手同源标注的持续性**：若快手网页播放流开始附加水印，PUBLIC_CLEAN 标注将失真；靠 fixture 单测锢定结构 + 诊断中心定位，无法根除（同 initiative 风险 2）。
2. **抖音降级语义变化**：从「静默丢弃带水印源」到「降级返回」，下游（下载禁用、历史记录）需确认兼容——`isDownloadAllowed` 已有禁下载逻辑，历史记录仅存解析结果不触发下载，风险低。
3. **WatermarkPolicy 与各解析器既有排序的冲突**：抖音已按分辨率排序，快手按 manifest 顺序；统一排序可能改变返回顺序——需逐解析器检查推荐位是否符合预期，靠现有 fixture 断言兜底。
4. **真机验证盲区**：水印标注的实际准确性（尤其快手）沙箱无法验证，需用户真机自测抽查。
