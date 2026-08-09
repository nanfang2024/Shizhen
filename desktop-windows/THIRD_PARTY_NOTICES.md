# 拾帧 Windows 第三方依赖与许可证

拾帧 Windows 源代码按 GPL-3.0-only 发布，仓库根目录 `LICENSE` 为完整条款。下表列出 Windows 客户端主要运行时和构建依赖；版权归各原作者所有。

| 组件 | 使用版本 | 许可证 | 上游地址 |
| --- | --- | --- | --- |
| .NET SDK | 8.0.423（构建） | MIT | https://github.com/dotnet/sdk |
| .NET / Windows Desktop Runtime | 8.0.29（自包含发布） | MIT | https://github.com/dotnet/runtime |
| WPF | .NET 8 | MIT | https://github.com/dotnet/wpf |
| Microsoft.Data.Sqlite | 8.0.20 | MIT | https://www.nuget.org/packages/Microsoft.Data.Sqlite/8.0.20 |
| SQLite | Microsoft.Data.Sqlite 传递依赖 | Public Domain | https://www.sqlite.org/copyright.html |
| yt-dlp Windows nightly executable | 2026.08.04.234419 | Unlicense；官方 PyInstaller EXE 还包含其 `THIRD_PARTY_LICENSES` 所列组件 | https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/tag/2026.08.04.234419 |
| Deno Windows x64 | 2.8.1 | MIT | https://github.com/denoland/deno/releases/tag/v2.8.1 |
| FFmpeg Gyan full build | 8.1 x64 | GPL-3.0（构建启用 `--enable-gpl --enable-version3`、libx264 等 GPL 组件） | https://www.gyan.dev/ffmpeg/builds/ |

## 二进制分发

`scripts/install-tools.ps1` 从 yt-dlp 和 Deno 官方 GitHub Release 下载固定版本并校验 SHA-256。FFmpeg/FFprobe 从本机 Gyan FFmpeg 安装复制，`Tools/LICENSES` 保存上游许可证和构建说明，`Tools/VERSIONS.txt` 保存实际版本。

FFmpeg 官方仅发布源代码，并在其下载页面链接 Gyan 和 BtbN 的 Windows 构建。当前使用的 Gyan full build 启用 GPL 与 version 3 组件，因此 Windows 整体发布包继续采用 GPL-3.0-compatible 分发方式，不把 FFmpeg 二进制误标为 LGPL。

yt-dlp 官方发布说明指出 PyInstaller EXE 除项目本身的 Unlicense 外还包含 ISC、MIT 等第三方代码；完整上游归属以官方可执行文件内的第三方许可清单和发布页为准。

## 图标例外说明

`Assets/Shizhen.ico` 来自用户提供的图片，不自动适用 GPL-3.0，也没有随仓库提供版权归属证明。代码的开源许可不构成该图片的分发授权。发布者必须确认权利或替换素材，详见 [ICON_NOTICE.md](ICON_NOTICE.md)。
