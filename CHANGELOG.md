# 更新日志

本项目采用面向用户的版本号记录可安装版本。平台网页和匿名接口会随时变化，因此“支持平台”表示当前版本包含对应识别或解析路径，不保证所有地区、网络和内容类型始终可用。

## 1.0.0 (1) - 2026-09-06（拾光无痕二开）

在拾帧 1.0.9 (23) 源码基础上二开的可安装版本（`com.framepick.app`）。本条目只记录二开变更，不覆盖上游版本历史。

### 新增

- Android：新增 YouTube 平台支持。内置 NewPipeExtractor v0.26.3 专用解析器在设备端本地提取公开视频：360p–720p 使用平台合流直链直存，1080p 及以上下载分离视频/音轨后由本地 FFmpeg `-c copy` 无损封装；解析结果保留多清晰度选择与独立音频项（分离音轨直存，输出 M4A）。NewPipe 通道失败时自动回落既有 yt-dlp 通道。应用不导入 Cookie、账号凭据或 PO Token。
- 第三方声明：THIRD_PARTY_NOTICES.md 登记 NewPipeExtractor v0.26.3（GPL-3.0）。

### 验证

- 180 个 Android JVM 单元测试全部通过，其中新增 14 项覆盖 YouTube 流映射（清晰度/容器/编码选择、最佳音轨选择、分离流优先级、质量档位上限）与 YouTube URL 匹配。
- `assembleRelease` 构建通过；release APK 经 dex 检查确认包含 NewPipeExtractor 与 YouTube 解析器类。

## 1.0.9 (23) - 2026-08-09

### 修复

- Android：抖音视频下载和从视频提取音频时保留最终公开作品页 Referer，修复 `/aweme/v1/play/` 跳转到 CDN 后返回 HTTP 403。
- Windows 1.0.7：快手优先读取移动分享页公开的 `window.INIT_STATE`，修复匿名 REST/GraphQL 被风控时无法解析同一公开作品的问题。
- Windows：快手媒体下载携带最终移动作品页 Referer，并增加真实链接解析与 MP4 下载回归测试。

### 验证

- 126 个 Android JVM 单元测试、Android Lint 和 Debug APK 构建通过。
- Windows 15 项本地/FFmpeg 集成测试与 24 项联网解析、真实下载测试通过。

## 1.0.8 (22) - 2026-08-04

拾帧首次公开发行。

### 主要功能

- 识别分享文字中的首个 HTTP/HTTPS 链接，并接收 Android 系统文本分享。
- 解析和预览公开的视频、图片、GIF、封面与音频，合并同一资源的质量选项。
- WorkManager 后台下载、进度、取消、重试、打开与分享。
- 本地视频转 GIF、GIF 转 H.264 MP4、视频按间隔提取 JPG/PNG。
- Room 历史记录与可脱敏导出的诊断中心。
- Material 3 暖秋色浅色/深色主题和 AndroidX SplashScreen 品牌开屏。

### 平台与边界

- 包含国内外常见平台识别、专用公开页面解析器、yt-dlp Android 集成和通用 Open Graph/HTML 回退。
- 不导入 Cookie、账号凭据或播放令牌，不绕过登录、付费、DRM 或访问控制。
- 只展示平台当前匿名公开返回的资源；完整音频与试听片段会明确区分。
- 当前 APK 仅包含 `arm64-v8a`，要求 Android 8.0（API 26）或更高版本。

### 验证

- 126 个 JVM 单元测试通过。
- Android Lint 为 0 errors。
- `assembleDebug` 与正式签名的 `assembleRelease` 均完成构建验证。

完整限制见 [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)，测试记录见 [TESTING.md](TESTING.md)。
