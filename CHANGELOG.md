# 更新日志

本项目采用面向用户的版本号记录可安装版本。平台网页和匿名接口会随时变化，因此“支持平台”表示当前版本包含对应识别或解析路径，不保证所有地区、网络和内容类型始终可用。

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
