# 测试与验收记录

## 本机自动化测试

执行环境：Windows，JDK 25（项目最低要求仍为 JDK 17），Android SDK Platform 34，Gradle 9.6.1，Android Gradle Plugin 9.3.1。

正式界面版最终执行的自动化命令：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

结果：`BUILD SUCCESSFUL`。Android Lint 成功完成（0 个错误、19 条非阻塞检查结果；主要是依赖/目标版本更新、仅 arm64 的 ChromeOS 提示和代码风格建议），HTML 报告位于 `app/build/reports/lint-results-debug.html`。

已执行 126 个 JVM 单元测试，0 failure，0 error：

| 测试套件 | 数量 | 覆盖内容 |
|---|---:|---|
| `UrlExtractorTest` | 8 | 纯链接、分享文字、多链接、无链接、非法链接、短链接、中文标点，以及 URL 后紧贴中文分享指令 |
| `PlatformRecognizerTest` | 12 | 已知平台、国际分享域名、X/Twitter、Instagram、Facebook、YouTube、豆包、小红书、快手、网易云，以及抖音/西瓜共用短链的跳转前后识别 |
| `InternationalPlatformSupportTest` | 5 | YouTube 短链/Shorts/Embed 规范化、Instagram 当前媒体地址、Facebook 必要参数保留、分享路由展开判定和平台专属访问限制文案 |
| `InternationalPublicPageMediaExtractorTest` | 7 | Instagram 当前 shortcode/数字媒体 ID 隔离、最高尺寸图片、纯图片帖重试条件、Facebook Open Graph 视频/封面、YouTube oEmbed 封面降级，以及不把缩略图冒充视频 |
| `InstagramPlaylistJsonExtractorTest` | 3 | Instagram 轮播中坏子项隔离、可用视频保留、最高可下载格式选择、直链下载策略和仅限 Instagram 的错误回退条件 |
| `DoubaoPublicPageExtractorTest` | 7 | 豆包公开原图/图片/视频字段、`data-fn-args` 多层解码、`video-sharing` 查询参数、`play_info.main` 与 `original_media_info.main_url` 两种 API 响应、明确水印状态，以及无公开媒体的登录会话壳拒绝 |
| `XiguaSsrDataExtractorTest` | 5 | 西瓜公开播放地址解码、当前作品与相关推荐隔离、普通抖音页面拒绝，以及去除不稳定参数后的四条官方页面路由回退 |
| `GenericMediaParserTest` | 7 | 直链、Open Graph、403 映射、结构化媒体优先、正文图片范围，以及 X/豆包禁止退化为整页扫描 |
| `XStructuredMediaParserTest` | 1 | 只接受 X/Twitter 单条 `status` URL |
| `PublicSourceClassifierTest` | 3 | 公开原始播放源、明确带水印格式和未知状态分类 |
| `FfmpegCommandBuilderTest` | 4 | GIF 调色板与时间段、MP4 `yuv420p`/H.264、图片提取间隔和兼容回退命令 |
| `PlatformMediaClassifierTest` | 1 | X/Twitter `tweet_video` 动图识别及普通视频排除 |
| `DiagnosticSanitizerTest` | 2 | 保留可定位的 X 状态路径，同时遮盖查询参数、fragment、授权信息和 content URI |
| `DouyinRouterDataExtractorTest` | 3 | 图文帖全部公开图片、明确 water 地址排除、背景音乐不冒充视频，以及 `/playwm/` 到公开无水印 720p `/play/` 源的安全映射 |
| `AudioDownloadOptionEnricherTest` | 4 | 直链视频本地提取音轨、yt-dlp 最佳公开音轨选择器、无声视频不显示音频项，以及已有音频不重复 |
| `PublicUrlNormalizerTest` | 6 | 已知国内/国际平台 HTTPS 升级、未知域名不改写，以及 `fb.watch`、`instagr.am`、`youtu.be` 等分享短链识别 |
| `XiaohongshuStateExtractorTest` | 2 | 新旧页面状态结构、当前笔记图集隔离、原图 CDN + 普通/`notes_pre_post` fileId、公开原视频和封面 |
| `KuaishouStateExtractorTest` | 2 | 混淆顶层键下的当前作品 manifest、多清晰度排序和单图作品隔离 |
| `NeteaseMusicPageExtractorTest` | 3 | 单曲 ID、标题、歌手、meta/移动页 REDUX 当前歌曲时长、封面 HTTPS 升级，以及无歌曲 ID 页面拒绝 |
| `NeteasePreviewDetectionTest` | 5 | MP3 码率/时长估算、ID3 跳过、短试听判定、未知时长保守处理和最终资源标识 |
| `NeteasePublicAudioResolverTest` | 1 | 只接受网易云官方 outer-song 音频入口 |
| `QqMusicPageExtractorTest` | 3 | QQ SSR 单曲状态中的标题、歌手、封面、公开音频、付费歌曲试听标记和缺失状态拒绝 |
| `MusicLinkCanonicalizerTest` | 2 | QQ 标准单曲页保持，以及带 `songmid` 的移动分享页规范化 |
| `PublicMusicHtmlExtractorTest` | 2 | 音乐页结构化音频候选、相对封面、头像排除，以及 URL 明确试听标记识别 |
| `AudioFormatSelectorTest` | 1 | QQ 音频按码率排序，并排除视频和未知扩展名 |
| `KugouMusicPageExtractorTest` | 4 | 酷狗当前分享页歌曲状态、主/备用音频、公开试听标记、嵌套对象及字符串花括号安全扫描和无状态拒绝 |
| `ParserRegistryTest` | 5 | 登录限制后的专用解析器回退、封面元数据不覆盖视频访问限制、禁止退化为通用整页扫描、保留试听专用错误，以及无干净源时立即停止 |
| `PublicMusicAudioPolicyTest` | 5 | 完整音频优先、无完整音频时保留公开试听、未知候选边界和 URL 去重 |
| `MusicAudioItemPresentationTest` | 2 | 试听项的“仅试听片段”标签、非推荐状态和完整音频标识隔离 |
| `PublicMusicAudioResponsePolicyTest` | 4 | 酷狗 octet-stream 的 ID3/MPEG 签名放行，以及 HTML、错误 MIME 和伪装文件拒绝 |
| `VerifiedQqAudioDirectPolicyTest` | 5 | QQ 官方 HTTPS 音频 CDN 直链识别、MP3/M4A 格式匹配、非音频和伪造域名拒绝 |
| `DownloadMimePolicyTest` | 2 | 通用二进制 MP3 保存为系统音频 MIME，并保留服务器的明确音频类型 |

HTML 测试报告：

```text
app/build/reports/tests/testDebugUnitTest/index.html
```

Room schema 由 KSP 成功生成并导出到：

```text
app/schemas/com.example.mediaextractor.data.database.MediaExtractorDatabase/1.json
```

## 正式界面静态验收

- [x] 全量 Kotlin 编译、资源合并与 `assembleDebug` 成功。
- [x] 扫描全部 Kotlin/Compose 源码，没有 `app_icon_source`、`app_icon_foreground`、`ic_launcher` 或对应 `R.drawable` 引用。
- [x] 页面业务色全部来自 Material `ColorScheme` 或扩展语义色；原始色值集中在 `ui/theme/Color.kt` 与 `Theme.kt`。
- [x] 页面正文使用系统 `SansSerif`；只在诊断日志正文中保留等宽系统字体。
- [x] 关键浅/深色文字组合按 WCAG 公式检查：主要正文 14.89:1 / 15.48:1，次要正文 6.45:1 / 8.96:1，浅色主按钮白字 4.70:1，均达到 AA。
- [x] Launcher 源照片只存在于 `drawable-nodpi` 与多密度 `mipmap` 图标链路；系统启动画面使用 Launcher Icon，Compose 品牌开屏只使用 Canvas 抽象枫叶。
- [x] Adaptive Icon、legacy 方形/圆形图标、arm64 ABI、minSdk 26、targetSdk 34、版本 `1.0.8 (22)` 和 v2 Debug 签名已通过构建工具检查。
- [ ] 由于本机没有连接 Android 设备，浅色/深色、最大字体、小屏、圆形/圆角方形 OEM 图标蒙版和真实触控体验仍需真机确认。

## 已验证的构建内容

- `assembleDebug` 成功，APK 位于 `app/build/outputs/apk/debug/app-debug.apk`。
- APK 使用 Debug 签名。
- `apksigner` 验证通过：APK Signature Scheme v2，1 个 Android Debug 签名者。
- APK 只包含 `arm64-v8a` 原生库。
- APK 包含 yt-dlp/Python 运行组件和真实的 30,043,032 字节 `libffmpeg.so`；不再包含旧 `libffmpeg.zip.so`、`libffprobe.so` 或手工 `libc++_shared.so`。ELF 检查显示新 FFmpeg 是 AArch64 `ET_EXEC` 静态可执行文件且没有动态 `DT_NEEDED` 项。
- Manifest 包含 `ACTION_SEND` / `text/plain`、FileProvider 和 WorkManager dataSync 前台服务声明。
- 未声明 `READ_EXTERNAL_STORAGE`、`WRITE_EXTERNAL_STORAGE` 或 `MANAGE_EXTERNAL_STORAGE`。
- 最终 APK 版本为 `1.0.8 (22)`，大小为 55,827,422 字节（53.24 MiB）；SHA-256 为 `7B0D5690CF1045D72B905624C77135856568CA06E2822F7C1FB40AEB53C86743`。

## 本机公开源验证

- 诊断报告中的抖音短链 `https://v.douyin.com/zerVNUZphAE/` 可展开到公开作品页 `7553576846722403594`，页面 `_ROUTER_DATA` 中的视频作品尺寸字段为 3840×2160、时长约 60 秒，但只暴露 `playwm` 播放入口。
- 对同一公开视频 ID 实际请求并下载后，用桌面 FFprobe/FFmpeg 检查：`/playwm/?ratio=720p` 为 H.264 1280×720、31,702,124 字节且抽帧可见平台水印；`/play/?ratio=720p` 为 H.264/AAC 1280×720、31,410,041 字节且同时间抽帧没有平台水印。
- 这一验证只证明该公开链接在 2026-07-30 的网络响应；平台可随时调整接口。应用因此显示实际公开 720p，不使用页面 4K 元数据虚报下载质量。
- 对诊断报告中的小红书短链 `http://xhslink.cn/o/4D9F2CmqTur` 执行真实联网冒烟测试：Android 等价逻辑先升级 HTTPS、展开到当前 `discovery/item`，新结构化解析器成功只取得当前笔记图片，没有混入相关笔记或评论图片。
- 对本次诊断报告中的小红书短链 `http://xhslink.cn/o/2wUz1K8BIXn` 执行真实联网验证：页面当前笔记公开 10 个图片 `fileId` 和原图 CDN 基址；逐项 Range 请求 10/10 均成功，实际为 1 张 WebP 和 9 张 JPEG，大小约 2.1–12.9 MiB，原图不包含“小红书”平台展示层。底部摄影署名属于作者写入的画面，按设计保留。
- 对本次诊断报告中的网易云短链 `https://163cn.tv/bb2HOiOJ` 执行真实联网验证：短链展开到公开歌曲 `1365898499`，页面返回标题、歌手和封面；官方 outer-song 入口返回 `206 audio/mpeg`，总大小 3,315,714 字节。其 HTTP CDN 重定向改写为同一官方主机的 HTTPS 后再次请求成功。
- 使用 yt-dlp `2026.07.04` 对其官方 QQMusic 提取器公开测试单曲 `004Ti8rT003TaZ` 做真实联网验证：取得 `48aac`（1,718,645 字节）、`96aac`（3,435,792 字节）和 `128mp3`（4,501,244 字节）三档；最高档 Range 请求返回 `206 audio/mpeg`，主机为 `dl.stream.qqmusic.qq.com`。同一官方网页的 SSR 状态还返回歌曲名、两位歌手、500×500 HTTPS 封面和 96 kbps 页面播放源。
- 对诊断报告中的 QQ 单曲 `002FHVgG4btehE` 定位到：解析阶段同时取得 128 kbps MP3、96 kbps M4A 和 48 kbps M4A，只有 128 kbps 在下载时二次调用 yt-dlp 偶发 `expected string or bytes-like object, got 'bool'`；另外两档成功，其中 96 kbps 曾因 `No route to host` 重试。`0.1.9` 因此对 QQ 官方 HTTPS CDN + 匹配扩展名的音频先直接保存解析结果，直链失败才回退重新取源，并为该瞬态提取器错误增加 WorkManager 重试。
- 对诊断报告中 QQ 音乐失败记录暴露的单曲 `002WcwVL2ypOuf` 做等价联网复现：yt-dlp 将其判为需登录，QQ 官方移动页状态的 `pay_play=1` 且直出 MP3 只有 960,887 字节；Range 请求返回 `206 audio/mpeg`，证明页面确实公开了这段试听。`0.1.10` 会显示并允许保存该片段，但在资源卡片、下载按钮、文件名和历史记录中明确写明“仅试听片段”，不会把它当作完整歌曲。
- 对酷狗当前公开分享样例 `chain=2fSR909G0V3` 做联网结构验证：官方页在 `phpParam.song_info.data` 中直出标题、两位歌手、封面、128 kbps 主/备用 MP3；主地址 Range 请求返回 `206 audio/mpeg`，总大小 3,561,358 字节。测试和实现均不生成私有签名、不使用 Cookie，也不把付费页试听标成完整歌曲。
- 对 `0.1.10 (11)` Android 15 诊断报告复核：酷狗页面请求实际返回 `200 text/html`，失败来自 `KugouMusicPageExtractor` 初始化时 Android 正则引擎对 `\{` 抛出 `PatternSyntaxException`，不是网络或链接失效。`0.1.11` 已移除该正则并用字符串感知的平衡花括号扫描替代。
- 对 `0.1.11 (12)` 真机报告再次复核：酷狗已取得两条试听候选，但官方 CDN 返回 `206 application/octet-stream`，实际前16字节为 `49-44-33`（`ID3`）；`0.1.12` 使用音频签名接受并保存为 `audio/mpeg`。同一报告的网易云目标歌曲公开文件估算为 `30.05 s`，官方移动页 `REDUX_STATE.Song.dt=139615 ms`；新解析器取得该目标时长后会把资源标为“仅试听片段”。
- 对诊断报告中的快手短链 `https://v.kuaishou.com/Jk5XGHMc` 执行真实联网冒烟测试：展开到 `v.m.chenzhongtech.com/fw/photo/...` 后，新解析器从当前作品 `manifest` 取得两个 720×1280 公开视频候选。两项联网测试均在 2026-07-30 实际通过，日常测试保留对应离线结构用例以避免平台临时波动造成构建不稳定。
- 对本次西瓜视频样例 `https://v.douyin.com/N0oHncGQg4Y/` 执行真实联网验证：短链展开到 `www.iesdouyin.com/xg/video/7651243563103112484/`，页面当前作品为“第一集：八戒梦中见到悟空残魂，五百年的骗局终于揭开！”，作者“清风漫剧AIGC”。解析出的 `bdxiguavod.com` 公开播放源 Range 请求返回 `206 video/mp4`，总大小 8,286,868 字节，文件头包含 MP4 `ftyp`；未采集页面相关推荐。
- 同日稍后复测时，该作品的 `iesdouyin` 原始落地页和去参数页面均开始返回 HTTP 500，`m.ixigua.com` 路由则返回 500 或 404/JavaScript 壳。新回退逻辑已覆盖这几条官方公开路由和移动/桌面请求头，但服务器全部不可用时仍会如实失败，不接入 Cookie、私有签名接口或第三方代理服务。
- 使用与 Android 运行时更新通道一致的 yt-dlp `2026.07.04` 对官方提取器样例做联网冒烟：Instagram Reel `Chunk8-jurw` 成功返回 `Instagram` 提取器、MP4 和真实格式；Facebook Reel `1195289147628387` 成功返回 `Facebook` 提取器、MP4 和 SD 格式。
- 同一网络出口请求 YouTube 官方测试视频 `YE7VzlLtp-4` 时，默认、`android_vr`、`web_safari` 和 `tv_downgraded` 客户端均被 YouTube 要求登录确认“不是机器人”。本版会把它显示为访问限制，而不是假装解析成功；由于项目不导入 Cookie 或 PO Token，该链接需在其他允许匿名播放的网络环境下再做真机验证。
- 对用户诊断中的 Instagram 图片帖 `DblNQzqCE8o` 使用 yt-dlp `2026.07.04` 联网复测：默认请求明确返回“没有视频”；加上仅用于该错误的 `--ignore-no-formats-error` 后成功得到作者、标题和 14 个公开图片候选，验证了本版图片帖回退路径。YouTube 样例 `5_mn9oF5V4U` 的官方 oEmbed 同时成功返回标题、作者与 480×360 封面。Facebook 样例对应的视频 ID 被官方提取器明确标记为仅注册用户可用，因此按访问限制保留，不尝试绕过。
- 对本次 Instagram 轮播 `Da7xvKooOke` 使用 yt-dlp `2026.07.04` 联网复测：顶层返回 6 个子项，其中 2 个各有 11 个真实 MP4 格式、4 个报无视频格式；新逻辑会保留前两项。实际对首项选出的 1080×1440 MP4 临时 CDN 地址发起带原帖 Referer 的 HEAD 请求，返回 `200 video/mp4`、长度 687,892 字节。
- 对 YouTube 样例 `5_mn9oF5V4U` 使用 yt-dlp `2026.07.04` 分别测试 `web_embedded`、`android_vr` 和 `web_safari` 匿名客户端，当前网络出口三者均返回“确认不是机器人”；因此没有把 oEmbed 封面升级成伪视频，也没有引入 Cookie/PO Token。Facebook 样例解析到视频 ID `1508759137947227` 后被官方提取器明确标为仅注册用户可用，继续按访问限制处理。
- 已审阅 `Qalxry/doubao-no-watermark` 提交 `a6ea8a47fe13f76e72f69b47c809443a9919187d` 的 GPL-3.0 许可证与字段来源，并为 Android 公开页字段解析编写离线测试。没有用户提供的匿名公开豆包媒体分享样例，因此真实豆包链接仍列入真机重点验收，不声称已完成在线成功验证。
- 对诊断中的真实豆包线程 `x4zHaeHxjaWKYqN9W` 执行公开页联网验证：页面的 `data-fn-args` 经 HTML/嵌套 JSON 解码后得到标题、作者和 4 个去重的 `image_ori_raw` 原图；首项 Range 请求返回 `206 image/png`、总大小 4,565,756 字节。水印派生 `i_dld_wm` / `i_pre_wm` 与缩略图不会在存在原图时进入结果。
- 对豆包公开 `/video-sharing` 样例实际请求 `get_video_share_info`，确认响应使用 `data.play_info.main`、`poster_url`、`width`、`height`、`prompt` 和 `user_info.nickname`；同一 `video_id` 的 `get_play_info` 备用接口也返回 `original_media_info.main_url`、1248×704 和 MP4 元数据。两条公开 URL 都明确包含 `video_gen_watermark`，因此测试要求解析器显示视频但保持 `WATERMARKED` 状态。
- 审阅 ReClip 提交 `1d161d15a4fe93d9b3371377f0a421dc3e965b10` 后确认其 `/api/info` 仅执行 `yt-dlp --no-playlist -j URL`。在本机对诊断链接 `OEvM75u_ubU` 运行相同命令仍返回 YouTube“确认不是机器人”，与 Android 日志一致；因此未把 ReClip 作为无效的重复本地解析器集成。

## 真机验收清单

最终执行 `adb devices -l` 时设备列表为空。以下项目必须在 arm64-v8a Android 8.0+ 设备上执行后才能标记为运行时已验证。

### 安装与基础界面

```bash
adb devices
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

- [ ] 应用可启动，不闪退。
- [ ] 链接提取、格式转换、历史记录三个页面可切换。
- [ ] 系统浅色/深色模式下文字与按钮清晰。
- [ ] 竖屏小尺寸手机可以滚动查看全部参数。

### URL 与系统分享

- [ ] 输入 `复制这段文字 https://example.com/video.mp4 查看作品`，识别首个 URL。
- [ ] 输入无链接文字，显示“没有在分享文字中找到有效链接。”
- [ ] 从浏览器分享 `text/plain` 到本 App，自动切到链接页并填入文字，但不自动解析或下载。
- [ ] 应用在后台时再次分享，`onNewIntent` 能更新输入。

可用 ADB 模拟分享：

```bash
adb shell am start -a android.intent.action.SEND -t text/plain \
  --es android.intent.extra.TEXT "公开示例 https://example.com/video.mp4" \
  com.example.mediaextractor/.MainActivity
```

### 公开链接解析与下载

- [ ] 使用用户有权保存的公开 MP4 直链，解析出真实视频项。
- [ ] 使用含 `og:video` / `og:image` 的公开页面，显示标题、封面和资源。
- [ ] 使用会返回 401/403 的页面，显示指定访问限制文案。
- [ ] X/Twitter 某条回复的 `status` URL 只返回该条状态的媒体，不出现头像、推荐帖或其他回复。
- [ ] 小红书 `xhslink.cn` / `xhslink.com` 分享文字能识别为“小红书”，只显示当前笔记图集或视频，不显示相关推荐和评论图片。
- [ ] 上述小红书图集显示“平台公开原始源”，10 张均能预览并按实际 JPEG/WebP 格式下载，画面中央不再出现“小红书”平台展示层；作者摄影署名仍保留。
- [ ] 网易云 `163cn.tv` 公开单曲短链显示音频和 HTTPS 封面两个资源；音频明显短于页面歌曲时长时，卡片顶部、音质、下载按钮、保存文件名和历史来源均显示“仅试听片段”；完整公开音频不误标。
- [ ] QQ 音乐公开单曲将多档 AAC/MP3 标为“音质”而非“清晰度”；选择 128 kbps MP3 后优先使用解析阶段已验证的官方直链，直链过期时自动重新取源，最终文件保持 MP3 且可播放；短链或移动分享页能规范化到同一 `songmid`。
- [ ] QQ 音乐会员/付费歌曲只公开试听时显示试听音频项，卡片顶部、音质、下载按钮、保存文件名和历史来源均明确标注“仅试听片段”；免费公开单曲仍显示完整 AAC/MP3 音质。
- [ ] 酷狗当前单曲分享页能读取 `phpParam.song_info.data`；`audition_status` / `pay_type` 试听候选作为“仅试听片段”进入结果，免费完整候选仍显示标题、歌手、封面及主/备用音频。酷我/咪咕明确含试听标记的 URL 使用相同声明。
- [ ] 快手 `v.kuaishou.com` 分享链接能显示当前作品的视频清晰度或单图，不显示头像、配乐封面和推荐作品。
- [ ] 西瓜视频经 `v.douyin.com` 分享时，解析前显示“抖音/西瓜视频短链”，解析后平台变为“西瓜视频”，资源包含当前视频、封面和可选音频，不混入相关推荐。
- [ ] Instagram 公开 Reel 显示视频质量和封面；公开单图/轮播帖只返回当前 shortcode 的图片或视频，不出现头像、推荐帖和评论图片；私密帖显示访问限制。
- [ ] Instagram 轮播内部分子项失效时，其余视频仍逐项显示、预览和下载；等待较久导致签名地址过期后，重新解析即可刷新。
- [ ] Facebook 公开 Reel、Watch、视频帖和 `fb.watch` 分享链接可以解析并下载；需要登录、地区或年龄授权的帖子显示访问限制。
- [ ] YouTube 的 `youtu.be`、Shorts、Live、Embed 与普通 Watch 单视频链接归一到同一视频，显示可用清晰度与独立音频；播放列表不会被批量解析；“确认不是机器人”不会被显示为普通网络故障。
- [ ] 豆包匿名公开分享页若内嵌 `image_*_raw` / `originalImage` 或公开视频字段，显示对应资源；私有 `/chat/`、区域限制和要求登录的生成结果显示访问限制，不请求 Cookie，也不对两张水印图做像素拼接。
- [ ] 豆包 `/thread/` 分享只显示去重后的公开原图；`/video-sharing` 保留完整查询参数，并通过官方匿名信息/播放路由显示视频封面和预览；明确带水印的视频保持醒目标识，但允许用户下载且不会被标成无水印版本。
- [ ] B 站 `b23.tv` 解析后下载任务使用展开后的 `bilibili.com` 作品地址，封面不再因 `http://i0.hdslb.com` 明文策略而预览失败。
- [ ] 图片/GIF 可在对应资源卡片直接预览；视频可点“预览这个版本”，推荐质量和音轨说明清晰。
- [ ] 选择高分辨率视频后，下载结果分辨率与所选项一致；视频和音频分离时最终文件仍包含音轨。
- [ ] 含音轨的视频结果末尾出现独立“音频”项；点击“下载音频”后在 `Music/MediaExtractorDemo` 得到可播放的 M4A，且无声视频和 GIF 不出现无效音频选项。
- [ ] 同一平台内容同时返回 `play_addr` 与明确 `watermarked` 格式时，只显示并下载公开原始播放源。
- [ ] 只有明确带水印格式时，不显示可下载的“无水印”版本，并给出可读提示。
- [ ] 水印敏感平台未提供可验证原始源时仍可显示未被明确标记水印的候选，但必须显示“水印状态未验证”。
- [ ] 抖音图文帖返回页面结构化数据中的全部图片，不把背景音乐 MP3 当成唯一解析结果；明确含 `water` 的 `download_url_list` 不进入结果。
- [ ] 上述抖音视频短链解析出“公开无水印播放源”视频项和封面；视频项显示 1280×720，预览无平台水印且下载后含 AAC 音轨，不显示成 3840×2160。
- [ ] X/Twitter `tweet_video` 显示为 GIF，下载后的实际文件扩展名和 MIME 均为 GIF，而不是 MP4。
- [ ] 点击下载后显示等待、进度、成功或失败状态。
- [ ] 关闭并重新打开应用，WorkManager 任务状态仍能恢复。
- [ ] 下载成功后“打开文件”和“分享”有效。
- [ ] Android 10+ 在 `Movies/MediaExtractorDemo` 看到视频、`Pictures/MediaExtractorDemo` 看到图片/GIF、`Music/MediaExtractorDemo` 看到音频；应用没有请求全盘存储权限。
- [ ] 断网、签名 URL 过期和空间不足时不崩溃，历史记录保存可读原因。

### 真实 FFmpeg 转换

准备一个短 MP4 和一个动态 GIF：

- [ ] 视频转 GIF：设置 0–3 秒、10 fps、宽 480、循环，输出是真正可播放的 GIF。
- [ ] 诊断日志的 `ffmpeg_initialized` 显示发行包 `io.github.rbaucells:ffmpeg-android:1.22` 和约 30 MB 的 `executableBytes`，不再出现 `libc++_shared.so` 或 `libexpat.so.1 not found`。
- [ ] 取消一个较长的视频转 GIF，进程停止、缓存清理、历史显示“已取消”。
- [ ] GIF 转 MP4：输出可被系统相册或常见播放器打开，像素格式兼容。
- [ ] 首选调色板或 H.264 命令失败时自动执行兼容回退，并显示“正在尝试兼容模式”。
- [ ] 视频每 2 秒提取 JPG，选定目录出现多张不同帧图片。
- [ ] PNG 模式也能写入所选目录。
- [ ] 结束时间小于开始时间、间隔为 0、非法文件名会在启动 FFmpeg 前提示。
- [ ] 选择错误文件类型、取消系统选择器或拒绝目录写入时不崩溃。

### 历史记录

- [ ] 解析、下载、三种转换均生成 Room 历史。
- [ ] 点击记录可查看来源、输出和错误原因。
- [ ] 可打开仍存在的输出文件。
- [ ] 删除单条记录不会删除媒体文件。
- [ ] “清空全部”二次确认后只删除历史，不删除输出文件。
- [ ] 复现解析、预览、下载和 FFmpeg 失败后，“诊断中心”出现对应分类、失败阶段和底层错误。
- [ ] “复制”和“分享”可以导出 UTF-8 文本报告，接收方可以打开 `.txt` 文件。
- [ ] 带查询参数和 Token 的测试 URL 在报告中显示为 `?<redacted>`，不出现原始 Token 或 content URI 文档路径。
- [ ] “清空日志”后旧事件消失，但历史记录和已下载媒体仍保留。
