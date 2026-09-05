---
design_type: phase
created_at: 2026-09-05
---

# Phase 1 设计：皮皮虾 + B站结构化解析器

所属 initiative：[拾光无痕二次开发](shiguang-wuhen-initiative.md)

## Intent Contract

```
intent: 新增皮皮虾与B站的结构化解析器并接入解析链路，使两平台解析不再单纯依赖 yt-dlp 上游规则
constraints: 不修改现有解析器行为与下载管线；不引入 Cookie/登录/访问控制绕过；匿名公开数据之外的清晰度如实标注或放弃
success_criteria: 皮皮虾分享链接与B站视频/动态图文链接经新解析器返回结构化结果；全部验收命令退出码 0；现有测试零回退
risk_level: medium（平台页面结构以实施时真实公开页为准）
```

## Verification Contract

```
verify_steps:
  - run tests: ./gradlew testDebugUnitTest
  - run lint: ./gradlew lintDebug
  - build: ./gradlew assembleDebug
  - check: 新增解析器有 fixture 单测覆盖（视频/图文/访问限制分支）；ParserRegistryTest 或平台识别测试覆盖新域名；旧测试零回退
  - confirm: 三命令退出码 0
```

## Governance Contract

```
approval_gates: Phase 1 完成后用户人工审查（代码 + 测试结果），确认后进入 Phase 2
rollback: 分支级回退（git revert 或分支重建）
ownership: 实现代理负责编码与自动化验收；产品所有者负责真机链接解析自测
```

## Scope

| In | Out |
|---|---|
| `PipixiaStructuredMediaParser`（皮皮虾分享短链展开 + h5 页面结构化数据） | 无水印策略统一增强（Phase 2） |
| `BilibiliStructuredMediaParser`（B站普通视频 + 动态图文） | UI / 三 Tab 重构（Phase 3） |
| 皮皮虾域名接入平台识别；B站识别保持并接入新解析器 | 下载管线 / WorkManager 改动 |
| 两解析器的 JVM fixture 单测 | B站番剧/影视（bangumi）专用支持（地区与会员限制多，走现有回退） |
| 平台名中文文案 | 登录态清晰度、高画质绕过 |

## Decisions

| # | 决策 | 选择 | 被否方案 |
|---|---|---|---|
| D1 | B站视频流获取 | 解析器从公开页面结构化状态取标题/封面/清晰度元数据；匿名 playurl `fnval=0` 响应的 `durl` 为音视频合一 mp4 直链，按 DIRECT 策略下载（Referer 携带作品页，模式同抖音公开播放源）；yt-dlp 保留为解析失败时的回退链路 | 解析器直取 DASH 分离直链（fnval=16，DIRECT 管线无音视频合并能力）；全程 yt-dlp 策略（多一层上游依赖，匿名档位画质与 durl 相同）——D1 初版误将 fnval=0 的 durl 当作 DASH 分离流，实施时修正 |
| D2 | B站清晰度边界 | 只展示匿名公开请求可得的清晰度；需要登录的档位不出现，不冒充 | 引入 Cookie/凭据换取高清晰度（违反 Non-goals） |
| D3 | B站覆盖形态 | 普通视频（BV/av）+ 动态图文（opus/动态图片，走图片直链下载）；番剧遇到访问限制按既有 ACCESS_RESTRICTED 语义回退 | 为番剧做专用解析（限制多、价值低） |
| D4 | 皮皮虾数据源 | 分享短链展开后的公开 h5 页面内嵌 JSON 状态，取当前作品的视频直链与图集；视频直链按 DIRECT 下载 | 调用私有签名接口（违反边界） |
| D5 | 测试 fixture | 以实施时抓取的真实公开页面结构为样本构造 fixture（沙箱网络出口可用时抓取；不可用时按公开已知结构构造并在测试中注明） | 无 fixture 的纯 mock 断言（无法防结构回归） |

## Surface

**APIs / 组件**：新增两个 `MediaParser` 实现——`PipixiaStructuredMediaParser`（短链展开、页面状态抽取、视频/图集映射）与 `BilibiliStructuredMediaParser`（页面状态抽取、清晰度元数据映射、图文直链），实现 `canHandle` + `parse`，返回既有 `ParsedMedia`/`MediaItem` 模型，水印标记沿用 `SourceWatermark` 语义。

**平台识别**：`PlatformRecognizer` 增加 皮皮虾 域名条目（`pipix.com` 及其分享域名）；哔哩哔哩条目已存在，无需改动识别，仅在解析器 `canHandle` 中匹配视频/动态/opus 路径。

**注册**：`MediaExtractorApplication` 的 `parserRegistry` 列表中，两个新解析器插入在 `YtDlpMediaParser` 之前（结构化优先，yt-dlp 作为回退），保持 Generic 最后。

**存储**：无变化。

**文件**：新增 `domain/parser/PipixiaStructuredMediaParser.kt`、`domain/parser/BilibiliStructuredMediaParser.kt` 及对应测试；修改 `util/PlatformRecognizer.kt`、`MediaExtractorApplication.kt`、`res/values/strings.xml`（平台显示名，如需要）。

## Risks & Open Questions

1. **页面结构以实施时为准**：皮皮虾与B站的内嵌状态字段名可能随版本变化；fixture 测试可在结构回归时快速定位，但不承诺永久有效。
2. **B站匿名清晰度有限**：匿名请求通常只能获得较低清晰度；这是平台策略，应用如实标注「匿名公开档位」而不是冒充高画质。
3. **沙箱抓取依赖网络出口**：真实页面抓取若失败，fixture 按公开已知结构构造，真机自测时如发现偏差由用户反馈修正（诊断中心可导出失败现场）。
4. **开放问题**：皮皮虾图集与B站动态图文的图片字段结构需在实施时以真实样本确认（已列入执行计划首个任务）。
