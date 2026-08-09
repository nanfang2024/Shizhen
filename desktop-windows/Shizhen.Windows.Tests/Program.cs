using Shizhen.Windows.Models;
using Shizhen.Windows.Services;
using Shizhen.Windows.Services.Parsing;

var failures = new List<string>();
var executed = 0;

Test("纯链接", () => Equal("https://example.com/video?id=1", UrlExtractor.ExtractFirst("https://example.com/video?id=1")));
Test("分享文字加链接", () => Equal("https://example.com/abc123", UrlExtractor.ExtractFirst("复制这段文字 https://example.com/abc123 打开查看")));
Test("多个链接取第一个", () => Equal("https://first.example/a", UrlExtractor.ExtractFirst("https://first.example/a https://second.example/b")));
Test("无链接", () => Null(UrlExtractor.ExtractFirst("这段文字没有链接")));
Test("非法链接", () => Null(UrlExtractor.ExtractFirst("http://localhost/path")));
Test("短链接", () => Equal("https://t.co/AbC123", UrlExtractor.ExtractFirst("https://t.co/AbC123")));
Test("中文标点", () => Equal("https://example.com/path", UrlExtractor.ExtractFirst("查看：https://example.com/path。谢谢")));
Test("保留查询参数", () => Equal("https://example.com/p?a=1&b=2", UrlExtractor.ExtractFirst("https://example.com/p?a=1&b=2，结束")));
Test("西瓜域名识别", () => Equal("西瓜视频", PlatformDetector.Detect("https://www.ixigua.com/123")));
Test("抖音短链识别", () => Equal("抖音", PlatformDetector.Detect("https://v.douyin.com/abc/")));
Test("YouTube 识别", () => Equal("YouTube", PlatformDetector.Detect("https://youtu.be/abc")));
Test("文件名清理", () => Equal("a_b_c", FileNameSanitizer.Sanitize("a:b?c")));
Test("文件大小格式", () => Equal("1.5 MB", FileSizeFormatter.Format(1572864)));
Test("快手当前作品响应映射视频、图集和音频", () =>
{
    using var document = System.Text.Json.JsonDocument.Parse("""
        {
          "ok": true,
          "source": "rest",
          "finalUrl": "https://www.kuaishou.com/short-video/test-photo",
          "photoId": "test-photo",
          "author": { "user_name": "测试作者" },
          "payload": {
            "caption": "测试作品",
            "duration": 12000,
            "coverUrl": "https://cdn.example.com/cover.jpg",
            "mainMvUrls": [{ "url": "https://video.example.com/work.mp4" }],
            "atlas": {
              "cdn": ["https://image.example.com/media"],
              "list": ["first.jpg", "second.jpg"]
            }
          }
        }
        """);
    var parsed = KuaishouPublicMediaParser.BuildParsedMedia("https://v.kuaishou.com/test", document.RootElement);
    Equal("快手", parsed.Platform);
    Equal(1, parsed.Items.Count(item => item.Type == MediaType.Video));
    Equal(2, parsed.Items.Count(item => item.Type == MediaType.Image));
    Equal(1, parsed.Items.Count(item => item.Type == MediaType.Audio));
    Equal(true, parsed.Items.Where(item => item.Type == MediaType.Image)
        .All(item => item.MediaUrl.StartsWith("https://image.example.com/media/", StringComparison.Ordinal)));
});

if (args.Contains("--integration", StringComparer.OrdinalIgnoreCase))
{
    await TestAsync("FFmpeg 真实转换链路", async () =>
    {
        var root = Path.Combine(Environment.CurrentDirectory, "artifacts", "integration-tests", DateTime.Now.ToString("yyyyMMdd_HHmmss"));
        Directory.CreateDirectory(root);
        AppPaths.EnsureCreated();
        var logger = new DiagnosticLogger();
        var runner = new ProcessRunner(logger);
        var ffmpeg = ToolLocator.FindFfmpeg() ?? throw new InvalidOperationException("未找到 FFmpeg");
        var source = Path.Combine(root, "source.mp4");
        var create = await runner.RunAsync(ffmpeg,
            ["-hide_banner", "-y", "-f", "lavfi", "-i", "testsrc2=size=320x240:rate=12",
             "-f", "lavfi", "-i", "sine=frequency=880:sample_rate=44100", "-t", "3", "-shortest",
             "-pix_fmt", "yuv420p", "-c:a", "aac", source]);
        Equal(0, create.ExitCode);
        var probe = new MediaProbeService(runner);
        var info = await probe.ProbeAsync(source);
        Equal(true, info.IsVideo);
        Equal(320, info.Width);
        var converter = new FfmpegMediaConverter(runner, logger);
        var gif = await converter.VideoToGifAsync(info, new VideoToGifOptions(0, 2, 12, 320, true, "sample", root), null, CancellationToken.None);
        Equal(true, gif.Success);
        Equal(true, File.Exists(gif.OutputPath));
        var gifInfo = await probe.ProbeAsync(gif.OutputPath!);
        var mp4 = await converter.GifToMp4Async(gifInfo, new GifToMp4Options("roundtrip", root), null, CancellationToken.None);
        Equal(true, mp4.Success);
        Equal(true, File.Exists(mp4.OutputPath));
        var frames = await converter.ExtractFramesAsync(info, new ExtractFramesOptions(1, "JPG", "frame", root), null, CancellationToken.None);
        Equal(true, frames.Success);
        Equal(true, Directory.EnumerateFiles(frames.OutputPath!, "*.jpg").Count() >= 2);
    });
}

if (args.Contains("--network", StringComparer.OrdinalIgnoreCase))
{
    await TestAsync("抖音真实公开链接解析", async () =>
    {
        AppPaths.EnsureCreated();
        var logger = new DiagnosticLogger();
        var parser = new DouyinShareMediaParser(logger);
        var parsed = await parser.ParseAsync("https://www.douyin.com/video/7670347612009216667");
        Equal("抖音", parsed.Platform);
        Equal(true, parsed.Items.Any(item => item.Type == MediaType.Video));
        Equal(false, parsed.Items.Where(item => item.Type == MediaType.Video)
            .Any(item => item.MediaUrl.Contains("/playwm/", StringComparison.OrdinalIgnoreCase)));
        var video = parsed.Items.First(item => item.Type == MediaType.Video);
        var folder = Path.Combine(Environment.CurrentDirectory, "artifacts", "network-tests", "douyin-" + DateTime.Now.ToString("yyyyMMdd_HHmmss"));
        var service = new DownloadService(new ProcessRunner(logger), logger);
        var output = await service.DownloadAsync(parsed, video, folder);
        Equal(true, File.Exists(output));
        Equal(true, new FileInfo(output).Length > 100_000);
    });

    await TestAsync("YouTube 真实公开链接解析", async () =>
    {
        AppPaths.EnsureCreated();
        var logger = new DiagnosticLogger();
        var runner = new ProcessRunner(logger);
        var parser = new ParserRegistry(
            [new YtDlpMediaParser(runner), new GenericMediaParser(logger)],
            logger);
        try
        {
            var parsed = await parser.ParseAsync("https://youtu.be/s-ATfXR8BpI?si=GEUDpCOY2q6wB1e_");
            Equal("YouTube", parsed.Platform);
            Equal(true, parsed.Items.Any(item => item.Type == MediaType.Video));
            Equal(true, parsed.Items.Any(item => item.Type == MediaType.Audio));
        }
        catch (UserFacingException exception)
        {
            Equal("请更换节点重试", exception.Message);
        }
    });

    await TestAsync("Instagram 匿名公开视频解析", async () =>
    {
        AppPaths.EnsureCreated();
        var logger = new DiagnosticLogger();
        var runner = new ProcessRunner(logger);
        var parser = new YtDlpMediaParser(runner);
        var parsed = await parser.ParseAsync("https://www.instagram.com/reel/Chunk8-jurw/");
        Equal("Instagram", parsed.Platform);
        Equal(true, parsed.Items.Any(item => item.Type == MediaType.Video));
        var folder = Path.Combine(Environment.CurrentDirectory, "artifacts", "network-tests", "instagram-" + DateTime.Now.ToString("yyyyMMdd_HHmmss"));
        var output = await new DownloadService(runner, logger).DownloadAsync(
            parsed, parsed.Items.First(item => item.Type == MediaType.Video), folder);
        Equal(true, File.Exists(output));
        Equal(true, new FileInfo(output).Length > 100_000);
    });

    await TestAsync("Instagram 当前图集公开解析且不混入无关内容", async () =>
    {
        AppPaths.EnsureCreated();
        var logger = new DiagnosticLogger();
        var runner = new ProcessRunner(logger);
        var parser = new InstagramPublicPostParser(runner, logger);
        var parsed = await parser.ParseAsync("https://www.instagram.com/p/DbxjRwjlJ8K/?igsh=MTBwNHFicXRzaDlyaA==");
        Equal("Instagram", parsed.Platform);
        Equal(4, parsed.Items.Count(item => item.Type == MediaType.Image));
        Equal(false, parsed.Items.Any(item => item.Type == MediaType.Video));
        Equal(true, parsed.Items.Where(item => item.Type == MediaType.Image)
            .All(item => item.Width == 1080 && item.MediaUrl.Contains("cdninstagram.com", StringComparison.OrdinalIgnoreCase)));
        var folder = Path.Combine(Environment.CurrentDirectory, "artifacts", "network-tests", "instagram-carousel-" + DateTime.Now.ToString("yyyyMMdd_HHmmss"));
        var output = await new DownloadService(runner, logger).DownloadAsync(
            parsed, parsed.Items.First(item => item.Type == MediaType.Image), folder);
        Equal(true, File.Exists(output));
        Equal(true, new FileInfo(output).Length > 100_000);
    });

    await TestAsync("Facebook 匿名公开视频解析", async () =>
    {
        AppPaths.EnsureCreated();
        var logger = new DiagnosticLogger();
        var runner = new ProcessRunner(logger);
        var parser = new YtDlpMediaParser(runner);
        var parsed = await parser.ParseAsync("https://www.facebook.com/cnn/videos/10155529876156509/");
        Equal("Facebook", parsed.Platform);
        Equal(true, parsed.Items.Any(item => item.Type == MediaType.Video));
        var folder = Path.Combine(Environment.CurrentDirectory, "artifacts", "network-tests", "facebook-" + DateTime.Now.ToString("yyyyMMdd_HHmmss"));
        var output = await new DownloadService(runner, logger).DownloadAsync(
            parsed, parsed.Items.First(item => item.Type == MediaType.Video), folder);
        Equal(true, File.Exists(output));
        Equal(true, new FileInfo(output).Length > 100_000);
    });

    await TestAsync("Facebook 注册用户作品保留访问限制提示", async () =>
    {
        AppPaths.EnsureCreated();
        var logger = new DiagnosticLogger();
        var runner = new ProcessRunner(logger);
        var registry = new ParserRegistry(
            [new YtDlpMediaParser(runner), new GenericMediaParser(logger)],
            logger);
        try
        {
            await registry.ParseAsync("https://www.facebook.com/share/1UAsENbmRS/");
            throw new InvalidOperationException("该测试作品应当要求注册用户访问");
        }
        catch (UserFacingException exception)
        {
            Equal("该资源需要登录或存在访问限制，本工具不支持提取。", exception.Message);
        }
    });

    await TestAsync("快手移动分享页真实解析与下载", async () =>
    {
        AppPaths.EnsureCreated();
        var logger = new DiagnosticLogger();
        var runner = new ProcessRunner(logger);
        var parser = new ParserRegistry(
            [new KuaishouPublicMediaParser(runner, logger), new YtDlpMediaParser(runner), new GenericMediaParser(logger)],
            logger);
        var parsed = await parser.ParseAsync("https://v.kuaishou.com/Jk2hv461");
        Equal("快手", parsed.Platform);
        Equal(true, parsed.ExtractorName.StartsWith("Kuaishou public work ", StringComparison.Ordinal));
        Equal(true, parsed.Items.Any(item => item.Type == MediaType.Video));
        var video = parsed.Items.First(item => item.Type == MediaType.Video);
        Equal(true, video.ReferrerUrl?.Contains("v.m.chenzhongtech.com/fw/photo/", StringComparison.OrdinalIgnoreCase) == true);
        var folder = Path.Combine(Environment.CurrentDirectory, "artifacts", "network-tests", "kuaishou-" + DateTime.Now.ToString("yyyyMMdd_HHmmss"));
        var output = await new DownloadService(runner, logger).DownloadAsync(parsed, video, folder);
        Equal(true, File.Exists(output));
        Equal(true, new FileInfo(output).Length > 100_000);
    });

    await TestAsync("X 当前图片帖子解析且不混入引用内容", async () =>
    {
        AppPaths.EnsureCreated();
        var logger = new DiagnosticLogger();
        var parser = new XPublicPostParser(new ProcessRunner(logger), logger);
        var parsed = await parser.ParseAsync("https://x.com/xixikawaii/status/2086065703724179675");
        Equal("X / Twitter", parsed.Platform);
        Equal(2, parsed.Items.Count(item => item.Type == MediaType.Image));
        Equal(true, parsed.Items.Where(item => item.Type == MediaType.Image)
            .All(item => new Uri(item.MediaUrl).Host.Equals("pbs.twimg.com", StringComparison.OrdinalIgnoreCase)));
    });

    await TestAsync("B 站真实视频、音频与非 HTML 下载", async () =>
    {
        AppPaths.EnsureCreated();
        var logger = new DiagnosticLogger();
        var runner = new ProcessRunner(logger);
        var parser = new BilibiliPublicMediaParser(logger);
        var parsed = await parser.ParseAsync("https://www.bilibili.com/video/BV1LsMk6vE5f");
        Equal("哔哩哔哩", parsed.Platform);
        Equal(true, parsed.Items.Count(item => item.Type == MediaType.Video) >= 2);
        Equal(true, parsed.Items.Where(item => item.Type == MediaType.Video)
            .Select(item => item.QualityLabel).Distinct().Count() >= 2);
        Equal(true, parsed.Items.Any(item => item.Type == MediaType.Audio));
        Equal(true, parsed.Notice?.Contains("720P", StringComparison.Ordinal) == true);
        Equal(true, parsed.Notice?.Contains("1080P", StringComparison.Ordinal) == true);
        var video = parsed.Items.First(item => item.Type == MediaType.Video);
        var folder = Path.Combine(Environment.CurrentDirectory, "artifacts", "network-tests", "bilibili-" + DateTime.Now.ToString("yyyyMMdd_HHmmss"));
        var service = new DownloadService(runner, logger);
        var output = await service.DownloadAsync(parsed, video, folder);
        Equal(true, File.Exists(output));
        Equal(true, new FileInfo(output).Length > 1_000_000);
        var prefix = new byte[64];
        await using var stream = File.OpenRead(output);
        var read = await stream.ReadAsync(prefix);
        var text = System.Text.Encoding.UTF8.GetString(prefix, 0, read).TrimStart();
        Equal(false, text.StartsWith("<!doctype", StringComparison.OrdinalIgnoreCase) || text.StartsWith("<html", StringComparison.OrdinalIgnoreCase));
    });

    await TestAsync("公开 MP4 真实下载", async () =>
    {
        var logger = new DiagnosticLogger();
        var runner = new ProcessRunner(logger);
        var parser = new GenericMediaParser(logger);
        var parsed = await parser.ParseAsync("https://media.w3.org/2010/05/sintel/trailer.mp4");
        var item = parsed.Items.First(media => media.Type == MediaType.Video);
        var folder = Path.Combine(Environment.CurrentDirectory, "artifacts", "network-tests", DateTime.Now.ToString("yyyyMMdd_HHmmss"));
        var service = new DownloadService(runner, logger);
        var output = await service.DownloadAsync(parsed, item, folder);
        Equal(true, File.Exists(output));
        Equal(true, new FileInfo(output).Length > 10_000);

        var audioItem = new MediaItem
        {
            Id = "test-audio-from-video",
            Type = MediaType.Audio,
            MediaUrl = item.MediaUrl,
            Format = "m4a",
            QualityLabel = "从公开视频提取音频",
            AudioCodec = "aac",
            ExtractAudio = true
        };
        var audio = await service.DownloadAsync(parsed, audioItem, folder);
        Equal(true, File.Exists(audio));
        Equal(true, new FileInfo(audio).Length > 10_000);
    });
}

Console.WriteLine($"执行 {executed} 项测试，失败 {failures.Count} 项。");
foreach (var failure in failures) Console.Error.WriteLine("FAIL: " + failure);
return failures.Count == 0 ? 0 : 1;

void Test(string name, Action action)
{
    executed++;
    try
    {
        action();
        Console.WriteLine("PASS: " + name);
    }
    catch (Exception exception)
    {
        failures.Add($"{name} — {exception.Message}");
    }
}

async Task TestAsync(string name, Func<Task> action)
{
    executed++;
    try
    {
        await action();
        Console.WriteLine("PASS: " + name);
    }
    catch (Exception exception)
    {
        failures.Add($"{name} — {exception.Message}");
    }
}

static void Equal<T>(T expected, T actual)
{
    if (!EqualityComparer<T>.Default.Equals(expected, actual))
        throw new InvalidOperationException($"期望 {expected}，实际 {actual}");
}

static void Null(object? actual)
{
    if (actual is not null) throw new InvalidOperationException($"期望 null，实际 {actual}");
}
