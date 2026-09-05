---
design_type: phase
created_at: 2026-09-05
---

# Phase 3 设计：拾光无痕品牌迁移 + UI 三 Tab 重构

上游设计：[拾光无痕 initiative](shiguang-wuhen-initiative.md)。Phase 1（皮皮虾/B站解析器）与 Phase 2（无水印策略统一）已完成并验收。

## Intent Contract

```
intent: 将 App 品牌独立迁移至「拾光无痕」(com.framepick.app) 并重构为三 Tab 信息架构（首页解析/下载管理/设置），含 Material 3 动态取色与阻断式免责声明
constraints: 解析/下载/转换/历史/诊断能力零回退（现有 150 个单测全绿）；不新增第三方依赖；GPL-3.0 与第三方组件声明保留；arm64-v8a + minSdk 26 不变；分享接收链路不变
success_criteria: testDebugUnitTest + lintDebug + assembleDebug 全绿；app/ 源码与配置零 com.example.mediaextractor 残留（grep 验证）；免责声明门控有纯 JVM 单测；自适应图标前景/背景/单色三件套齐备
risk_level: medium（包名全量迁移是机械性大改动，编译+测试+grep 三重兜底；无安全/隐私/计费敏感项）
```

## Verification Contract

```
verify_steps:
  - run tests: ./gradlew testDebugUnitTest（现有用例零回退；新增免责声明门控单测全绿）
  - run lint: ./gradlew lintDebug
  - build: ./gradlew assembleDebug
  - check: grep -r "com.example.mediaextractor" app/ 零命中（含旧 Room schema JSON 清理）
  - check: AndroidManifest 引用新 applicationId 下的 Application/主题/图标资源齐备
  - confirm: 三命令退出码 0；真机安装后品牌/三 Tab/动态取色/分享接收/免责声明由用户验收
```

## Governance Contract

```
approval_gates: 设计批准（已过）；第一段迁移完成后的中间检查点（grep 零残留 + 验收三连，人工确认后进入 UI 段）；Phase 3 完成人工审查
rollback: 分支 hotl/phase-3-ui-brand；两段各自独立 commit（迁移 commit / UI commit），可分别 revert
ownership: 实现代理负责迁移/重构/自动化验收；产品所有者负责设计决策、中间检查点与真机验收
```

## Scope

**In（第一段：品牌迁移，机械性）**

| 项 | 内容 |
|---|---|
| 包名迁移 | `com.example.mediaextractor` → `com.framepick.app`：main/test 源码目录、全部 package/import、namespace、applicationId |
| 版本重置 | versionCode 1 / versionName 1.0.0 |
| 品牌资源 | 应用名「拾光无痕」；矢量自适应图标（前景+背景+单色三件套）；启动图与主题名品牌化 |
| 类名品牌化 | `FramePickApplication`、`FramePickDatabase`（Room v1 不变）、`Theme.FramePick`（含 values-v31 splash） |
| 残留清理 | 旧 Room schema JSON、旧包名引用全部清零 |

**In（第二段：UI 三 Tab 重构，语义性）**

| 项 | 内容 |
|---|---|
| 首页解析 | ExtractorScreen 演化：剥离内嵌下载任务区，保留解析结果与各清晰度下载按钮 |
| 下载管理 | 新 DownloadsScreen：进行中任务（WorkManager 实时进度）+ 历史记录（Room）单页两区块 |
| 设置 | 新 SettingsScreen：格式转换工具入口（子页面）、免责声明重看、诊断中心（自历史页迁入）、关于（版本/GPL-3.0/开源组件） |
| 动态取色 | Android 12+ `dynamicColorScheme` 纯自动；低版本回退暖秋色；语义扩展色（success）固定；导航指示器改用 `secondaryContainer` |
| 免责声明 | 首启阻断式（必须同意），SharedPreferences 版本化标记 `disclaimer_v1`；条款更新可重新弹出 |
| 导航 | 沿用 enum/BackHandler 轻量模式；分享接收仍定向首页解析 Tab |

**Out**

- 不引入 navigation-compose / DataStore 等新依赖
- 不改解析器/下载/转换领域逻辑（仅 UI 与品牌层）
- 不做多语言拆分（维持单 strings.xml）
- 不做真机自动化测试（无模拟器环境）

## Decisions

| # | 决策 | 选择 | 被否方案 |
|---|---|---|---|
| D1 | 实施顺序 | 两段推进：先机械迁移后 UI 重构，段间验收 | 先 UI 后迁移（UI 文件双重触碰）；混合进行（不可 bisect） |
| D2 | 免责声明存储 | SharedPreferences 版本化标记，无新依赖 | DataStore（为一个布尔引入依赖） |
| D3 | 图标 | 代码绘制矢量自适应图标三件套 | AI 位图（自适应裁切不可控，需多密度图） |
| D4 | 动态取色 | Android 12+ 纯自动无开关；语义扩展色保持固定 | 设置开关（设置页复杂化，收益低） |
| D5 | 子页面导航 | enum/BackHandler 轻量模式 | navigation-compose（新依赖，两级页面收益低） |
| D6 | 版本与数据库 | 重置 1.0.0/1；DB 类重命名 FramePickDatabase（新装无迁移负担） | 沿用 1.0.9/23（不体现品牌独立） |
| D7 | 下载管理形态 | 单页两区块（进行中+历史） | 二级分段导航（多一层导航，YAGNI） |

## Surface

- **API**：无对外 API 变化；UI 层新增 `SettingsScreen`、`DownloadsScreen` 与配套 ViewModel，`ExtractorScreen` 瘦身（下载任务区迁出）
- **存储**：Room schema 导出路径不变，DB 类与库名品牌化；SharedPreferences 新增 `disclaimer_v1` 标记；WorkManager/FileProvider authority 自动跟随 applicationId
- **组件**：`MediaExtractorApp`（导航重构）、`Theme.kt`（动态取色）、Application/Database 类重命名、诊断中心 UI 自 `HistoryScreen` 迁至设置
- **涉及文件**：全部 main/test 源文件（包声明与导入）、build.gradle.kts、AndroidManifest.xml、res/（图标/主题/字符串/colors）、docs/

## Risks & Open Questions

1. **包名迁移机械性风险**：FileProvider authority 与 WorkManager 均跟随 applicationId，新包名即全新安装、无数据迁移负担；靠编译 + 150 单测 + grep 三重兜底。
2. **动态取色观感**：扩展语义色（success/decoration/brandOrange）与壁纸取色的协调性无法在沙箱验证，语义色保持固定绿系降低冲突面；真机验收。
3. **诊断中心拆分**：自 HistoryScreen 迁移时复制/分享/清空操作需原样搬移，避免行为回退；靠代码审查 + 编译兜底。
4. **splash 主题联动**：themes.xml 与 values-v31 改名漏配会在 Android 12+ 启动崩溃，属编译期可发现问题。
5. **沙箱无真机**：动态取色实际效果、启动器图标渲染、分享接收与免责声明链路需用户真机验收（既有约定）。
