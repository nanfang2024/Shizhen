# 拾帧 Windows 测试记录

测试环境：Windows 11 x64，.NET SDK 8.0.423，.NET Runtime 8.0.29，yt-dlp nightly 2026.08.04.234419，Deno 2.8.1，FFmpeg 8.1 full build。执行日期：2026-08-09。

## 编译

```powershell
dotnet build .\Shizhen.Windows\Shizhen.Windows.csproj -c Debug -p:Platform=x64
```

结果：成功，0 warnings，0 errors。

## 自动测试

纯逻辑测试覆盖：

- 纯链接。
- 分享文字加链接。
- 多链接取第一条。
- 无链接。
- 非法链接。
- 短链接。
- 中文标点终止。
- 保留查询参数。
- 西瓜视频、抖音和 YouTube 域名识别。
- Windows 文件名清理。
- 文件大小格式化。
- 快手公开响应中的视频、图集、封面和音频映射。

真实 FFmpeg 集成测试会生成带 AAC 音轨的 320×240、12 FPS、3 秒测试视频，并依次验证：

- FFprobe 读取分辨率和类型。
- 视频 0–2 秒转 12 FPS GIF。
- 生成的 GIF 转 H.264 / yuv420p MP4。
- 视频每 1 秒提取 JPG，实际得到至少 2 张图片。

命令：

```powershell
dotnet run --project .\Shizhen.Windows.Tests\Shizhen.Windows.Tests.csproj -c Debug -- --integration
```

结果：15 项通过，0 项失败。

## 真实网络测试

命令：

```powershell
dotnet run --project .\Shizhen.Windows.Tests\Shizhen.Windows.Tests.csproj -c Debug -- --network
```

结果：24 项通过，0 项失败。附加验证包括：

- 通过专用 `DouyinShareMediaParser` 直接解析诊断中的真实抖音公开作品，实际返回视频，确认地址不再使用 `/playwm/`，并通过应用自身 `DownloadService` 完整下载和校验文件。
- 通过应用自身 `YtDlpMediaParser` 请求公开视频 `https://www.youtube.com/watch?v=dQw4w9WgXcQ`；当前出口触发机器人验证时，确认只返回“请更换节点重试”。
- 通过 yt-dlp nightly 和 Chrome 模拟真实解析 Instagram 公共 Reel，并由 `DownloadService` 实际保存 148,817 字节 MP4。
- 通过专用 `InstagramPublicPostParser` 复现用户诊断中的公开图片帖 `DbxjRwjlJ8K`，确认只返回当前帖子的 4 张 1080 宽图片，不混入头像、评论或推荐内容；再由 `DownloadService` 实际保存第一张 109,720 字节 WEBP。
- 通过同一链路真实解析 Facebook 公共视频，并由 `DownloadService` 实际保存 1,826,654 字节 MP4；需要注册用户的视频仍明确拒绝。
- 对诊断中的 Facebook 分享链接 `1UAsENbmRS` 验证平台明确返回“仅注册用户可用”；解析注册表立即保留访问限制提示，不再继续进行无意义的通用网页扫描。另一个匿名公开视频仍成功解析和下载，确认不是整个平台被禁用。
- 对诊断中的快手链接 `Jk2hv461` 使用与 Android 版一致的移动分享页路径：HTTP 200 页面包含 `window.INIT_STATE`，专用解析器返回当前作品的视频、封面和音频选项，并由 `DownloadService` 实际保存 MP4。匿名 REST/GraphQL 只作为页面无结构化数据时的后备路径。
- 通过专用 `XPublicPostParser` 解析诊断中的 X 图片帖子，实际只返回当前帖子 2 张 `pbs.twimg.com` 原图，不混入引用帖内容。
- 通过专用 `BilibiliPublicMediaParser` 解析诊断中的 `BV1LsMk6vE5f`：作品源尺寸为 3840×2076，匿名接口实际只返回 720P 和 360P 两种清晰度及音频选项；状态信息明确说明 1080P/4K 的登录或会员限制。再由 `DownloadService` 完整下载 MP4 并检查文件头不是 HTML。
- 通过 `GenericMediaParser` 识别 W3C 公开 MP4 `https://media.w3.org/2010/05/sintel/trailer.mp4`。
- 通过应用自身 `DownloadService` 下载约 4.37 MB 文件，并从同一公开视频真实提取 M4A 音轨。

网络测试依赖外部公开页面，未来可能因平台或节点变化而失败；失败不应被误判为纯代码回归。

## 运行时界面检查

- 实际启动 `Shizhen.exe`，开屏后主窗口标题为“拾帧”，进程响应正常。
- 通过 Windows UI Automation 调用主题切换按钮；浅色和深色资源即时更新。
- 检查天蓝/海军蓝主题、导航选中状态、主按钮文字对比、空历史状态和窗口最大化布局。
- 链接、转换和历史页始终预留纵向滚动条空间；链接输入框固定高度，粘贴或显示结果时不会引起内容宽度跳变。
- “本地预览”不会启动浏览器，视频/音频/图片/GIF 使用应用缓存和独立预览窗口；缓存文件不写入下载历史。
- 源图片仅在标题栏/EXE 图标链路出现；普通页面使用抽象取景框和标准功能图标，没有加载源照片。
- 修复了两项只有运行时才能发现的问题：只读进度属性的错误双向绑定，以及直链下载完成前文件流未释放。
- 根据 Windows .NET Runtime 崩溃栈修复解析结果 `QualityDisplay` 和 GIF 文件 `TypeDisplay` 的只读属性双向绑定；静态检查确认所有 `Run.Text` 展示绑定均显式使用 `Mode=OneWay`。
- 验证下载和诊断日志导出都会先要求用户选择保存文件夹。

## 发布前建议手测

1. 100%、125%、150% 和 200% Windows 缩放。
2. 900×640 最小窗口、1080P 和 4K 显示器。
3. 一条公开视频、一组图片和一个公开音轨的解析/预览/下载。
4. 下载中取消、网络断开、磁盘空间不足和链接过期。
5. 长文件名、中文目录、只读目录和外接磁盘。
6. 长视频转 GIF、大 GIF 转 MP4、PNG 抽帧和任务取消。
7. 应用处理中强制结束后重启，确认历史标记“意外中断”。
8. 由权利人确认 Windows 图标素材的公开分发授权。
