# 第三方依赖与许可证说明

本文件记录拾帧直接使用的主要依赖及重要的传递依赖。版权归各原作者所有；本项目没有删除或替换上游版权声明。

| 组件 | 使用版本 | 许可证 | 上游地址 |
|---|---:|---|---|
| Android Gradle Plugin | 9.3.1 | Apache-2.0 | https://developer.android.com/build |
| Kotlin / Compose Compiler plugin | 2.3.21 | Apache-2.0 | https://kotlinlang.org/ |
| AndroidX Core / SplashScreen / Activity / Lifecycle | 1.13.1 / 1.0.1 / 1.9.3 / 2.8.7 | Apache-2.0 | https://github.com/androidx/androidx |
| Jetpack Compose / Material 3 / Material Icons | BOM 2024.09.03 | Apache-2.0 | https://github.com/androidx/androidx |
| Room | 2.8.4 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/room |
| WorkManager | 2.9.1 | Apache-2.0 | https://developer.android.com/jetpack/androidx/releases/work |
| DocumentFile | 1.0.1 | Apache-2.0 | https://github.com/androidx/androidx |
| Coil Compose / GIF decoder | 2.7.0 | Apache-2.0 | https://github.com/coil-kt/coil |
| OkHttp / MockWebServer | 4.12.0 | Apache-2.0 | https://github.com/square/okhttp |
| jsoup | 1.17.2 | MIT | https://github.com/jhy/jsoup |
| youtubedl-android library | 0.18.1 | GPL-3.0 | https://github.com/yausername/youtubedl-android |
| ffmpeg-android Kotlin wrapper/AAR | 1.22 | 包装代码发布元数据为 Apache-2.0；内含 FFmpeg 二进制按下项许可证处理 | https://github.com/rbaucells/ffmpeg-android |
| yt-dlp（由 youtubedl-android 打包） | 上游模块内置版本 | Unlicense | https://github.com/yt-dlp/yt-dlp |
| doubao-no-watermark（仅参考兼容字段名；未打包脚本） | 审阅提交 `a6ea8a47fe13f76e72f69b47c809443a9919187d` | GPL-3.0；作者 Qalxry、Zhanghuaimin-233 | https://github.com/Qalxry/doubao-no-watermark |
| doubao-nomark（仅参考公开播放响应兼容；未打包服务） | 审阅提交 `24e0089eaacbb993e492632e4a9ddaa2e4ed4b08` | MIT；Copyright (c) 2026 Hmily | https://github.com/ihmily/doubao-nomark |
| CPython（由 youtubedl-android 打包） | 上游模块内置版本 | Python Software Foundation License | https://www.python.org/psf/license/ |
| QuickJS（由 youtubedl-android 打包） | 上游模块内置版本 | MIT | https://bellard.org/quickjs/ |
| FFmpeg 及静态编解码依赖（由 ffmpeg-android AAR 打包） | FFmpeg 8.0.1 arm64 | 二进制自报 GPL-2.0-or-later；本项目按 GPL-3.0 兼容方式整体分发 | https://ffmpeg.org/legal.html |
| Jackson Databind / Core / Annotations | 2.11.1（传递） | Apache-2.0 | https://github.com/FasterXML/jackson |
| Apache Commons IO / Compress | 2.5 / 1.12（传递） | Apache-2.0 | https://commons.apache.org/ |
| JUnit 4 | 4.13.2（仅测试） | EPL-1.0 | https://github.com/junit-team/junit4 |
| Gradle Wrapper | 9.6.1 | Apache-2.0 | https://github.com/gradle/gradle |

## GPL 说明

`io.github.junkfood02.youtubedl-android:library:0.18.1` 的发布元数据声明 GPL-3.0。因为应用与该组件一同分发，拾帧采用 GPL-3.0，完整许可证在仓库根目录 [LICENSE](LICENSE)。

`com.github.teamnewpipe:NewPipeExtractor:v0.26.3`（经 JitPack 发布）声明 GPL-3.0，用于 YouTube 公开视频流的设备端本地提取。本项目未修改其源码，按未修改版本与其源码依赖一同分发，并保留上游版权与许可证声明；应用整体继续按 GPL-3.0 分发。

豆包公开页字段兼容实现审阅了 Qalxry、Zhanghuaimin-233 的 `doubao-no-watermark` GPL-3.0 用户脚本，并在源码中保留作者、许可证、仓库和审阅提交信息。APK 不包含该 Tampermonkey 脚本，不复制其登录会话网络拦截、提示词管理或双图像素拼接实现。

豆包公开播放响应兼容实现还审阅了 Hmily 的 `doubao-nomark` MIT 项目中匿名 `get_play_info` 的请求与 `original_media_info` 字段。APK 不包含其 Python/FastAPI 服务或 Docker 镜像；源码注释及本文件保留了项目、提交、作者和许可证信息。

`io.github.rbaucells:ffmpeg-android:1.22` 的 Maven 元数据将 Kotlin 包装代码声明为 Apache-2.0；其中 `libffmpeg.so` 自报 FFmpeg 8.0.1，配置和组件字符串表明启用了 GPL 组件并以 GPL-2.0-or-later 分发。本项目采取 GPL-3.0 兼容的整体分发方式，不把 AAR 的 Apache 元数据误当成 FFmpeg 二进制本身的许可证。

该 `libffmpeg.so` 是 AArch64 `ET_EXEC` 静态可执行文件。构建产物的 ELF 动态段为空，不需要旧 `youtubedl-android:ffmpeg:0.18.1` 包中缺失的 `libexpat.so.1`，也不再单独打包 LLVM `libc++_shared.so`。

Apache-2.0、MIT、BSD、PSF、Unlicense 和 EPL 测试依赖均仅以与 GPL-3.0 兼容的方式使用。各组件的完整许可证与版权信息请以上游仓库和 Maven 发布产物为准。

## Windows x64 客户端

Windows 客户端使用 .NET / WPF 8.0.29（MIT）、Microsoft.Data.Sqlite 8.0.20（MIT）、SQLite（Public Domain）、yt-dlp 2026.06.09（Unlicense 及官方 EXE 内列出的第三方许可）、Deno 2.8.1（MIT）和 Gyan FFmpeg 8.1 full build。该 FFmpeg 构建启用了 `--enable-gpl --enable-version3`、libx264 等 GPL 组件，因此 Windows 发布包继续按 GPL-3.0-compatible 方式整体分发。版本、校验方式、二进制许可证和图标例外说明见 [desktop-windows/THIRD_PARTY_NOTICES.md](desktop-windows/THIRD_PARTY_NOTICES.md)。
