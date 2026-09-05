---
design_type: initiative
created_at: 2026-09-05
---

# 拾光无痕（FramePick）二次开发战略设计

基于开源项目「拾帧 / Shizhen」（GPL-3.0，Kotlin + Jetpack Compose + Material 3，插件式媒体解析架构）的二次开发 initiative，目标是交付一款品牌独立、界面现代化的本地无水印媒体解析 Android APP。

## Problem

拾帧已具备 30+ 平台识别、抖音/快手/小红书/西瓜等结构化解析器、WorkManager 下载、本地 FFmpeg 转换与诊断中心，但：

1. 皮皮虾无解析器，B站、微博等依赖 yt-dlp，稳定性受制于上游规则更新；
2. 现有平台（抖音/快手/小红书）的无水印源策略分散在各解析器内，未统一审查与增强；
3. UI 为「提取/转换/历史」三 Tab，与目标产品「首页解析/下载管理/设置」的信息架构不符，且为固定暖秋色主题（无动态取色）；
4. 品牌、包名（`com.example.mediaextractor`）为原项目遗留，需迁移为独立产品 `com.framepick.app`（拾光无痕）。

## Vision

交付「拾光无痕」：

- 覆盖抖音、快手、小红书、B站、皮皮虾等主流平台的**设备端**公开媒体解析，优先返回平台公开的无水印源；
- 三 Tab（首页解析 / 下载管理 / 设置）+ Material 3 动态取色（Android 12+，低版本回退现有暖秋色）；
- 首次启动与设置页内置免责声明；
- 格式转换收进设置页工具入口，历史记录与下载任务合并到下载管理页；
- 品牌独立：新名称、新包名、新图标。

## Non-goals

- 不做算法擦除、裁切或修复水印；「无水印」仅指优先提取平台公开的无水印播放源，作者上传前烧录在画面内的标识不处理。
- 不提供 Cookie 导入、账号登录、付费/会员/DRM/地区限制绕过。
- 不调用来源不明的远程解析服务；全部解析在设备端发起。
- 不支持 32 位 ABI 与 Android 8.0 以下（维持 arm64-v8a、minSdk 26）。
- 不以上架 Google Play 为目标（平台政策与法律审核由使用者自行承担）。

## Stakeholders

- **产品所有者（用户）**：需求决策、每 Phase 人工验收门禁、真机自测。
- **实现代理（TRAE）**：设计、编码、测试、编译验收。
- **上游社区**：拾帧及 yt-dlp / FFmpeg 等第三方组件（GPL-3.0 / 各自许可证），二开分发须保持 GPL-3.0 并保留第三方声明。

## Architecture

**继承不动（稳定层）**：

- `MediaParser` 插件接口 + `ParserRegistry` 回退链（含访问限制、水印、元数据回退策略）；
- WorkManager 后台下载 + MediaStore 存储；
- Room 历史与诊断中心；
- `MediaConverter` / `FfmpegMediaConverter` 本地转换（FFmpeg 8.0.1 arm64 静态二进制）。

**改动层（按 Phase 推进）**：

- P1–P2 仅动解析层：新增 `PipixiaStructuredMediaParser`、`BilibiliStructuredMediaParser` 并注册；无水印增强聚焦 `PlatformMediaClassifier` / `PublicSourceClassifier` 与各结构化解析器的水印分类统一。
- P3 动 UI 与品牌层：
  - 三 Tab 重构：首页解析（ExtractorScreen 演化）、下载管理（下载任务 + 历史合并）、设置（新建：转换工具入口、免责声明、诊断中心入口）；
  - Material 3 动态取色：Android 12+ `dynamicColorScheme`，低版本回退现有暖秋色主题；
  - 品牌迁移：`com.example.mediaextractor` → `com.framepick.app`（applicationId、namespace、源码目录、Room schema 导出路径、FileProvider、资源、图标、应用名）。

**关键决策**：

| # | 决策 | 选择 | 被否方案 |
|---|---|---|---|
| D1 | 实施顺序 | 解析优先（P1 解析器 → P2 无水印 → P3 UI+品牌），每 Phase 独立可验收 | 换壳优先（最高风险操作前置）；并行推进（UI 与解析器代码耦合，冲突风险高） |
| D2 | 转换/历史去向 | 转换入设置页工具入口；历史并入下载管理 | 砍掉转换（损失现有能力）；四 Tab（偏离目标信息架构） |
| D3 | 品牌 | 新名「拾光无痕」+ 新包名 `com.framepick.app` | 沿用拾帧品牌（用户需要独立产品） |
| D4 | 验收方式 | 单测 + lint + 编译；真机自测由用户完成 | Mock 服务器集成测试（与真实页面结构存在偏差，价值有限） |
| D5 | 许可证 | 继续 GPL-3.0 开源分发 | 闭源（GPL 传染性不允许） |

## Phase Breakdown

| Phase | 内容 | 验收 |
|---|---|---|
| **P1: 皮皮虾 + B站解析器** | 两个结构化解析器（公开分享页/API 结构化数据，含视频清晰度、图文、封面）+ 平台识别接入 + JVM 单测（页面结构 fixture） | `testDebugUnitTest` + `lintDebug` + `assembleDebug` 全绿 |
| **P2: 现有平台无水印增强** | 抖音/快手/小红书水印分类审查、无水印源优先级统一、水印边界提示完善 | 同上 + 现有水印相关测试零回退 |
| **P3: UI 三 Tab + 品牌迁移** | 三 Tab 重构、动态取色、免责声明（首启 + 设置页）、品牌包名全量迁移 | 同上 + 全量资源引用无旧包名残留 |

每个 Phase 单独走 brainstorming → writing-plans 循环（Phase 1 需求已在本 initiative 轮澄清，直接进入轻量设计与计划）。

## HOTL Contracts

### Intent Contract

```
intent: 交付品牌独立（拾光无痕/com.framepick.app）、三 Tab UI、覆盖皮皮虾与B站且现有平台无水印策略增强的本地解析 Android APP
constraints: 不破坏现有解析/下载/转换/历史能力；不引入 Cookie/登录/访问控制绕过；GPL-3.0 合规；维持 arm64-v8a + minSdk 26
success_criteria: 三个 Phase 验收命令全绿；APK 可安装；真机核心链路（分享接收→解析→下载）由用户验收通过
risk_level: medium（包名全量迁移 + 平台接口变动风险；无安全/计费敏感项）
```

### Verification Contract

```
verify_steps:
  - run tests: ./gradlew testDebugUnitTest（每 Phase）
  - run lint: ./gradlew lintDebug（每 Phase）
  - build: ./gradlew assembleDebug（每 Phase）
  - check: 新增解析器有 fixture 单测；旧测试零回退；P3 后源码与配置无 com.example.mediaextractor 残留
  - confirm: 三命令退出码 0；用户真机自测通过
```

### Governance Contract

```
approval_gates: 每 Phase 完成后用户人工审查确认，方可进入下一 Phase
rollback: 每 Phase 独立分支，出问题时分支级回退（git revert / 分支重建）
ownership: 实现代理负责设计/编码/自动化验收；产品所有者负责需求决策、Phase 门禁与真机验收
```

## Risks & Open Questions

1. **GPL-3.0 传染性**（已确认接受）：二开分发必须继续 GPL-3.0 开源并保留第三方声明。
2. **平台接口变动**：皮皮虾/B站页面结构或接口变更会导致解析失效；通过 fixture 单测 + 诊断中心快速定位，无法根除。
3. **包名迁移破坏面大**：Room schema 路径、FileProvider authority、资源引用全量改动；靠编译 + 测试 + grep 兜底；新装用户无历史数据迁移负担。
4. **沙箱无真机**：解析器真实链路、动态取色实际效果、厂商适配需用户真机验收。
5. **B站 DASH 分离流**：B站视频普遍音视频分离，下载合并依赖现有 yt-dlp/FFmpeg 管线，解析器只负责元数据与流地址抽取。
6. **开放问题**：皮皮虾/B站具体页面结构以实施时的真实公开页为准，fixture 以实际抓取样本构建（Phase 1 计划内解决）。
