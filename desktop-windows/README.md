# 拾帧 Windows x64

拾帧 Windows 是 Android 项目的独立桌面客户端，使用 WPF / .NET 8 构建。它保留“链接提取、格式转换、历史记录”三个核心页面，与 Android 版不共享运行时、数据库或用户文件。

> 只处理无需登录、无 DRM 且你有权保存的公开内容。程序不提供 Cookie 导入、账号登录、付费绕过、DRM 绕过或访问控制规避功能。

## 已实现功能

- 从纯链接或整段分享文字中提取第一条有效 HTTP / HTTPS URL，兼容中文标点。
- 识别 YouTube、Instagram、Facebook、X/Twitter、TikTok、抖音、西瓜视频、哔哩哔哩、小红书、快手、微博、豆包及常见公开音乐域名。
- 插件式 `IMediaParser` / `ParserRegistry`；抖音、B 站、X、快手和 Instagram 图片帖使用仅针对当前作品的匿名公开解析器，其他平台优先使用带浏览器模拟能力的 yt-dlp nightly。已知社交/视频平台解析失败时不会扫描头像、评论和表情素材。
- Instagram 公开 `p` 图集会读取当前帖子的一次性公开媒体响应，保留原尺寸候选并逐张列出，不再把图片子项误判为“没有视频格式”；Reel 视频仍由 yt-dlp 解析真实视频格式。
- 快手分享短链优先使用 Android 移动浏览器标识读取公开移动分享页内的 `window.INIT_STATE`，返回当前作品的视频清晰度、图集、封面以及从视频提取音频；页面没有结构化数据时才尝试匿名公开作品接口。触发平台滑块/反爬验证时会明确提示，不会绕过验证码或登录。
- B 站会读取匿名公开接口实际提供的全部清晰度（例如 720P、360P），不会用同一地址伪造多个选项；如果作品标称存在 1080P/4K、但匿名接口没有返回可下载地址，结果状态会明确说明登录/会员限制。
- X 图片帖通过当前帖子公开响应提取原图，不混入引用帖、评论、头像或推荐内容。
- 解析标题、作者、缩略图、视频、图片、GIF、音频与封面；相同视频的不同清晰度合并选择。
- 使用 yt-dlp 或 HTTP 直链真实下载，拒绝把 HTML/JSON 网页保存成媒体；显示进度、大小和取消状态。
- 预览会先缓存所选资源，再在应用内播放视频/音频或显示图片/GIF，不打开网页；缓存超过 3 天自动清理。
- 当公开结果只有带音轨的视频时，提供“从公开视频提取音频”，由 FFmpeg 真正生成 M4A。
- 使用真实 FFmpeg 完成视频转 GIF、GIF 转 MP4（H.264 / yuv420p）和视频按间隔抽帧。
- 使用 SQLite 保存解析、下载、转换、失败、取消和意外中断历史。
- 一键导出脱敏诊断文件，导出前由用户选择保存文件夹；技术异常写入 `%LOCALAPPDATA%\Shizhen\Logs`。
- 支持浅色/深色主题、键盘焦点、高 DPI、窗口缩放、剪贴板粘贴、文本拖放和命令行传入分享文字。

## 安装发布包

推荐 Windows 10/11 x64。

1. 解压 `Shizhen-Windows-x64-1.0.7.zip`，不要直接在压缩包预览器中运行。
2. 进入解压目录，右键 `install.ps1`，选择“使用 PowerShell 运行”；如果执行策略阻止脚本，可在该目录执行：

   ```powershell
   powershell -ExecutionPolicy Bypass -File .\install.ps1
   ```

3. 安装脚本会把程序复制到 `%LOCALAPPDATA%\Programs\Shizhen`，并创建桌面和开始菜单快捷方式，不要求管理员权限。
4. 也可以不安装，直接双击 `Shizhen.exe` 运行便携版。

卸载时运行安装目录中的 `uninstall.ps1`。卸载程序文件不会删除 `%LOCALAPPDATA%\Shizhen` 下的历史数据库、设置和诊断日志；如不再需要，可手动删除该数据目录。

## 使用方法

### 链接提取

1. 在“链接提取”页粘贴链接或整段分享文字。
2. 确认识别出的域名，点击“解析链接”。程序不会未经确认自动下载。
3. 在同一资源卡中选择所需清晰度或音轨；试听资源会明确标注“仅试听片段”。
4. 点击“本地预览”会先缓存资源并在拾帧内部显示，不会跳转到网页；或点击对应下载按钮。
5. 每次点击下载后都会先打开文件夹选择器；取消选择时不会创建任务或写入历史。

若资源必须登录或存在权限限制，会显示“该资源需要登录或存在访问限制，本工具不支持提取”。YouTube 在当前网络节点失败时显示“请更换节点重试”。

### 本地转换

1. 在“格式转换”页选择视频或 GIF。
2. 程序使用 FFprobe 读取真实类型、大小、分辨率和时长，只显示当前文件可用的功能。
3. 视频转 GIF 默认 12 FPS、宽 480；可设置起止时间、帧率、宽度和循环。
4. GIF 转 MP4 自动输出兼容性较好的 H.264 / yuv420p 文件。
5. 视频抽帧可设置秒数间隔、JPG/PNG、文件名规则和输出目录。
6. 任务可取消；成功后可打开输出或显示所在位置。

### 历史与诊断

历史页可以打开输出、删除单条记录或清空历史。删除历史不会删除输出媒体。遇到解析或转换问题时点击“导出诊断文件”，选择保存文件夹后生成 `shizhen_diagnostics_*.txt`。

## Visual Studio 运行

1. 安装 Visual Studio 2022，选择“.NET 桌面开发”工作负载和 .NET 8 SDK。
2. 打开 `desktop-windows/Shizhen.Windows.sln`。
3. 首次运行前执行：

   ```powershell
   .\scripts\install-tools.ps1
   ```

4. 将 `Shizhen.Windows` 设为启动项目，平台选择 `x64`，按 F5。

## 命令行构建

```powershell
cd desktop-windows
powershell -ExecutionPolicy Bypass -File .\scripts\install-tools.ps1
dotnet build .\Shizhen.Windows.sln -c Debug
dotnet run --project .\Shizhen.Windows.Tests\Shizhen.Windows.Tests.csproj -c Debug -- --integration --network
powershell -ExecutionPolicy Bypass -File .\scripts\publish-win-x64.ps1
```

发布脚本生成自包含 x64 文件夹、ZIP 和 SHA-256 文件。最终用户无需另装 .NET Runtime。

## 项目结构

```text
desktop-windows/
  Shizhen.Windows/
    Core/                  MVVM 命令、通知与转换器
    Models/                媒体、转换和历史模型
    Services/
      Parsing/             抖音、B 站、X、快手、Instagram 当前作品、yt-dlp 与受限通用解析器
    ViewModels/            三个页面与主窗口状态
    Views/                 WPF 页面和品牌开屏
    Themes/                天蓝/海军蓝浅色与深色主题
    Assets/                Windows ICO（普通页面不引用原图）
    Tools/                 本地工具，EXE 不提交 Git
  Shizhen.Windows.Tests/   无外部测试框架的可执行测试集
  scripts/                 工具安装和 x64 发布脚本
  package/                 安装/卸载脚本
```

## 许可证与图标

Windows 客户端与仓库其余代码按 GPL-3.0-only 发布。主要依赖、版本与许可证见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。提供的动漫图片仅被转换为 Windows EXE/桌面图标，任何 XAML 页面和普通界面都没有加载该照片。

该图片是用户提供素材，不自动获得 GPL 授权。公开发布前必须确认拥有复制、修改和分发权，详见 [ICON_NOTICE.md](ICON_NOTICE.md)。

已知限制见 [KNOWN_LIMITATIONS.md](KNOWN_LIMITATIONS.md)，实际测试记录见 [TESTING.md](TESTING.md)。
