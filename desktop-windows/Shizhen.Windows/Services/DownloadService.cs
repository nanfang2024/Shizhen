using System.Globalization;
using System.Net;
using System.Net.Http;
using System.Text.RegularExpressions;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services;

public sealed partial class DownloadService(ProcessRunner processRunner, DiagnosticLogger logger)
{
    private readonly HttpClient _client = CreateClient(useProxy: true);
    private readonly HttpClient _directClient = CreateClient(useProxy: false);

    public async Task<string> DownloadAsync(
        ParsedMedia parsed,
        MediaItem item,
        string destinationFolder,
        IProgress<OperationProgress>? progress = null,
        CancellationToken cancellationToken = default)
    {
        Directory.CreateDirectory(destinationFolder);
        EnsureSpace(destinationFolder, item.FileSize);

        var extension = string.IsNullOrWhiteSpace(item.Format) ? GuessExtension(item.MediaUrl, item.Type) : item.Format!;
        var title = FileNameSanitizer.Sanitize(parsed.Title, $"media_{DateTime.Now:yyyyMMdd_HHmmss}");
        if (item.ExtractAudio && string.IsNullOrWhiteSpace(item.FormatId))
        {
            return await ExtractAudioFromDirectMediaAsync(item, destinationFolder, title, progress, cancellationToken);
        }
        if (!string.IsNullOrWhiteSpace(item.FormatId) || extension.Equals("m3u8", StringComparison.OrdinalIgnoreCase))
        {
            return await DownloadWithYtDlpAsync(parsed, item, destinationFolder, title, progress, cancellationToken);
        }

        return await DownloadDirectAsync(item, destinationFolder, title, extension, progress, cancellationToken);
    }

    private async Task<string> DownloadWithYtDlpAsync(
        ParsedMedia parsed,
        MediaItem item,
        string folder,
        string title,
        IProgress<OperationProgress>? progress,
        CancellationToken cancellationToken)
    {
        var ytDlp = ToolLocator.FindYtDlp() ?? throw new UserFacingException("下载组件尚未安装。");
        var outputTemplate = Path.Combine(folder, item.ExtractAudio
            ? $"{title}_音频.%(ext)s"
            : $"{title}_%(format_id)s.%(ext)s");
        var sourceUrl = string.IsNullOrWhiteSpace(item.FormatId) ? item.MediaUrl : parsed.SourceUrl;
        var formatSelector = item.FormatId;
        if (item.RequiresMerge && !string.IsNullOrWhiteSpace(formatSelector))
        {
            formatSelector = $"{formatSelector}+bestaudio/{formatSelector}";
        }
        if (item.ExtractAudio && string.IsNullOrWhiteSpace(formatSelector)) formatSelector = "bestaudio/best";

        var arguments = new List<string>
        {
            "--no-playlist",
            "--newline",
            "--no-warnings",
            "--socket-timeout", "30",
            "--retries", "4",
            "--fragment-retries", "4",
            "--concurrent-fragments", "4",
            "--windows-filenames",
            "--trim-filenames", "120",
            "--progress-template", "download:__SHIZHEN_PROGRESS__:%(progress._percent_str)s|%(progress.downloaded_bytes)s|%(progress.total_bytes,progress.total_bytes_estimate)s",
            "--print", "after_move:__SHIZHEN_FILE__:%(filepath)s",
            "-o", outputTemplate
        };
        if (!string.IsNullOrWhiteSpace(formatSelector))
        {
            arguments.Add("-f");
            arguments.Add(formatSelector);
        }
        var deno = ToolLocator.FindDeno();
        if (deno is not null)
        {
            arguments.Add("--js-runtimes");
            arguments.Add($"deno:{deno}");
        }
        var ffmpeg = ToolLocator.FindFfmpeg();
        if (ffmpeg is not null)
        {
            arguments.Add("--ffmpeg-location");
            arguments.Add(Path.GetDirectoryName(ffmpeg)!);
            if (item.ExtractAudio)
            {
                arguments.Add("--extract-audio");
                arguments.Add("--audio-format");
                arguments.Add("m4a");
                arguments.Add("--audio-quality");
                arguments.Add("0");
            }
            else
            {
                arguments.Add("--merge-output-format");
                arguments.Add("mp4");
            }
        }
        else if (item.ExtractAudio)
        {
            throw new UserFacingException("音频提取组件 FFmpeg 不可用，请重新安装完整版本。");
        }
        AddYtDlpPlatformArguments(arguments, sourceUrl);
        arguments.Add(sourceUrl);

        string? finalPath = null;
        var result = await processRunner.RunAsync(ytDlp, arguments, line =>
        {
            if (line.StartsWith("__SHIZHEN_FILE__:", StringComparison.Ordinal))
            {
                finalPath = line["__SHIZHEN_FILE__:".Length..].Trim();
            }
            else
            {
                ReportYtDlpProgress(line, progress);
            }
        }, cancellationToken: cancellationToken);

        if (result.ExitCode != 0)
        {
            throw new UserFacingException("下载失败，公开媒体地址可能已经过期，请重新解析后重试。", result.StandardError);
        }
        finalPath ??= Directory.EnumerateFiles(folder, title + "_*", SearchOption.TopDirectoryOnly)
            .OrderByDescending(File.GetLastWriteTimeUtc).FirstOrDefault();
        if (string.IsNullOrWhiteSpace(finalPath) || !File.Exists(finalPath))
        {
            throw new UserFacingException("下载工具已结束，但没有找到输出文件。", result.StandardOutput);
        }
        progress?.Report(new OperationProgress(100, "下载完成", new FileInfo(finalPath).Length, new FileInfo(finalPath).Length));
        return finalPath;
    }

    private async Task<string> ExtractAudioFromDirectMediaAsync(
        MediaItem item,
        string folder,
        string title,
        IProgress<OperationProgress>? progress,
        CancellationToken cancellationToken)
    {
        var ffmpeg = ToolLocator.FindFfmpeg()
            ?? throw new UserFacingException("音频提取组件 FFmpeg 不可用，请重新安装完整版本。");
        Directory.CreateDirectory(AppPaths.PreviewCache);
        var sourceExtension = GuessSourceMediaExtension(item.MediaUrl);
        var downloadProgress = new Progress<OperationProgress>(value =>
        {
            progress?.Report(value with
            {
                Percent = value.Percent * 0.82,
                Status = $"正在准备音频源 · {value.Status}"
            });
        });
        var sourceItem = new MediaItem
        {
            Id = item.Id + "-source",
            Type = MediaType.Video,
            MediaUrl = item.MediaUrl,
            Format = sourceExtension,
            ReferrerUrl = item.ReferrerUrl,
            QualityLabel = item.QualityLabel
        };
        string? sourcePath = null;
        var outputPath = GetUniquePath(folder, $"{title}_音频.m4a");
        try
        {
            sourcePath = await DownloadDirectAsync(
                sourceItem,
                AppPaths.PreviewCache,
                $"audio_source_{Guid.NewGuid():N}",
                sourceExtension,
                downloadProgress,
                cancellationToken);
            progress?.Report(new OperationProgress(86, "正在提取音轨…"));
            var copy = await processRunner.RunAsync(ffmpeg,
            [
                "-hide_banner", "-y", "-i", sourcePath,
                "-map", "0:a:0", "-vn", "-c:a", "copy", outputPath
            ], cancellationToken: cancellationToken);
            if (copy.ExitCode != 0 || !File.Exists(outputPath) || new FileInfo(outputPath).Length == 0)
            {
                TryDelete(outputPath);
                progress?.Report(new OperationProgress(92, "正在兼容转换音轨…"));
                var encode = await processRunner.RunAsync(ffmpeg,
                [
                    "-hide_banner", "-y", "-i", sourcePath,
                    "-map", "0:a:0", "-vn", "-c:a", "aac", "-b:a", "192k", outputPath
                ], cancellationToken: cancellationToken);
                if (encode.ExitCode != 0 || !File.Exists(outputPath) || new FileInfo(outputPath).Length == 0)
                {
                    TryDelete(outputPath);
                    throw new UserFacingException("该视频没有可提取的音轨，或音频格式暂不受支持。", encode.StandardError);
                }
            }
            var size = new FileInfo(outputPath).Length;
            progress?.Report(new OperationProgress(100, "音频提取完成", size, size));
            return outputPath;
        }
        finally
        {
            if (sourcePath is not null) TryDelete(sourcePath);
        }
    }

    private async Task<string> DownloadDirectAsync(
        MediaItem item,
        string folder,
        string title,
        string extension,
        IProgress<OperationProgress>? progress,
        CancellationToken cancellationToken)
    {
        var target = GetUniquePath(folder, $"{title}_{item.TypeDisplay}.{extension.TrimStart('.')}");
        var partial = target + ".part";
        try
        {
        using var request = new HttpRequestMessage(HttpMethod.Get, item.MediaUrl);
            if (Uri.TryCreate(item.ReferrerUrl, UriKind.Absolute, out var referrer)) request.Headers.Referrer = referrer;
            else if (ShouldBypassProxy(item.MediaUrl)) request.Headers.Referrer = new Uri("https://www.douyin.com/");
            var client = ShouldBypassProxy(item.MediaUrl) ? _directClient : _client;
            using var response = await client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
            if (response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
            {
                throw new UserFacingException("资源需要登录或访问授权，无法下载。");
            }
            response.EnsureSuccessStatusCode();
            var responseType = response.Content.Headers.ContentType?.MediaType;
            if (IsWebDocument(responseType))
            {
                throw new UserFacingException("媒体地址返回了网页而不是文件，请重新解析后重试。", $"Content-Type: {responseType}");
            }
            var total = response.Content.Headers.ContentLength;
            long completed = 0;
            await using (var input = await response.Content.ReadAsStreamAsync(cancellationToken))
            await using (var output = new FileStream(partial, FileMode.Create, FileAccess.Write, FileShare.None, 1024 * 128, true))
            {
                var buffer = new byte[1024 * 128];
                int read;
                while ((read = await input.ReadAsync(buffer, cancellationToken)) > 0)
                {
                    await output.WriteAsync(buffer.AsMemory(0, read), cancellationToken);
                    completed += read;
                    var percent = total is > 0 ? completed * 100d / total.Value : 0;
                    progress?.Report(new OperationProgress(percent, total.HasValue ? $"正在下载 {percent:0}%" : "正在下载", completed, total));
                }
                await output.FlushAsync(cancellationToken);
            }
            if (LooksLikeWebDocument(partial))
            {
                throw new UserFacingException("下载结果是 HTML/JSON 网页，已拒绝保存为媒体文件。");
            }
            target = FinalizePartialFile(partial, target);
            progress?.Report(new OperationProgress(100, "下载完成", completed, completed));
            return target;
        }
        catch (OperationCanceledException)
        {
            TryDelete(partial);
            throw;
        }
        catch (UserFacingException)
        {
            TryDelete(partial);
            throw;
        }
        catch (Exception exception)
        {
            TryDelete(partial);
            await logger.WriteAsync("DOWNLOAD", "直链下载失败", exception);
            throw new UserFacingException("下载过程中断，请检查网络和剩余空间后重试。", exception.Message, exception);
        }
    }

    private static void ReportYtDlpProgress(string line, IProgress<OperationProgress>? progress)
    {
        var match = ProgressRegex().Match(line);
        if (!match.Success) return;
        var percentText = match.Groups["percent"].Value.Replace("%", string.Empty).Trim();
        _ = double.TryParse(percentText, NumberStyles.Float, CultureInfo.InvariantCulture, out var percent);
        _ = long.TryParse(match.Groups["completed"].Value, out var completed);
        _ = long.TryParse(match.Groups["total"].Value, out var total);
        progress?.Report(new OperationProgress(percent, $"正在下载 {percent:0}%", completed > 0 ? completed : null, total > 0 ? total : null));
    }

    private static void EnsureSpace(string folder, long? expected)
    {
        var root = Path.GetPathRoot(Path.GetFullPath(folder));
        if (string.IsNullOrWhiteSpace(root)) return;
        var available = new DriveInfo(root).AvailableFreeSpace;
        var required = expected.HasValue ? Math.Max(expected.Value * 2, 200L * 1024 * 1024) : 200L * 1024 * 1024;
        if (available < required)
        {
            throw new UserFacingException("磁盘剩余空间不足，请清理空间或更换保存位置。");
        }
    }

    private static string GuessExtension(string url, MediaType type)
    {
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            var extension = Path.GetExtension(uri.AbsolutePath).TrimStart('.');
            if (!string.IsNullOrWhiteSpace(extension) && extension.Length <= 5) return extension;
        }
        return type switch
        {
            MediaType.Video => "mp4",
            MediaType.Audio => "m4a",
            MediaType.Gif => "gif",
            _ => "jpg"
        };
    }

    private static string GuessSourceMediaExtension(string url)
    {
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            var extension = Path.GetExtension(uri.AbsolutePath).TrimStart('.').ToLowerInvariant();
            if (extension is "mp4" or "m4s" or "webm" or "mov" or "mkv" or "ts") return extension;
        }
        return "mp4";
    }

    private static string GetUniquePath(string folder, string fileName)
    {
        var candidate = Path.Combine(folder, fileName);
        var name = Path.GetFileNameWithoutExtension(fileName);
        var extension = Path.GetExtension(fileName);
        var index = 1;
        while (File.Exists(candidate))
        {
            candidate = Path.Combine(folder, $"{name} ({index++}){extension}");
        }
        return candidate;
    }

    private static string FinalizePartialFile(string partial, string preferredTarget)
    {
        var folder = Path.GetDirectoryName(preferredTarget)!;
        var fileName = Path.GetFileName(preferredTarget);
        var target = GetUniquePath(folder, fileName);
        try
        {
            File.Move(partial, target);
            return target;
        }
        catch (IOException)
        {
            if (File.Exists(target) && new FileInfo(target).Length == 0) TryDelete(target);
            target = GetUniquePath(folder, fileName);
            File.Copy(partial, target, overwrite: false);
            File.Delete(partial);
            return target;
        }
    }

    private static void TryDelete(string path)
    {
        try { if (File.Exists(path)) File.Delete(path); } catch { }
    }

    private static bool ShouldBypassProxy(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        var host = uri.IdnHost;
        return host.EndsWith("snssdk.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith("douyinvod.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith("douyinpic.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith("byteimg.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith("bilivideo.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith("bilibili.com", StringComparison.OrdinalIgnoreCase);
    }

    private static void AddYtDlpPlatformArguments(List<string> arguments, string url)
    {
        var platform = PlatformDetector.Detect(url);
        if (platform is "Instagram" or "Facebook" or "TikTok" or "X / Twitter" or "YouTube")
        {
            arguments.Add("--impersonate");
            arguments.Add("chrome");
        }
        if (platform == "X / Twitter")
        {
            arguments.Add("--extractor-args");
            arguments.Add("twitter:api=syndication");
        }
    }

    private static bool IsWebDocument(string? contentType) => contentType is not null
        && (contentType.StartsWith("text/", StringComparison.OrdinalIgnoreCase)
            || contentType.Contains("html", StringComparison.OrdinalIgnoreCase)
            || contentType.Contains("json", StringComparison.OrdinalIgnoreCase));

    private static bool LooksLikeWebDocument(string path)
    {
        try
        {
            using var stream = File.OpenRead(path);
            var buffer = new byte[Math.Min(512, (int)Math.Min(stream.Length, 512))];
            var read = stream.Read(buffer, 0, buffer.Length);
            if (read == 0) return false;
            var prefix = System.Text.Encoding.UTF8.GetString(buffer, 0, read).TrimStart('\uFEFF', ' ', '\t', '\r', '\n');
            return prefix.StartsWith("<!doctype html", StringComparison.OrdinalIgnoreCase)
                   || prefix.StartsWith("<html", StringComparison.OrdinalIgnoreCase)
                   || prefix.StartsWith("<?xml", StringComparison.OrdinalIgnoreCase)
                   || prefix.StartsWith("{") && prefix.Contains("\"code\"", StringComparison.OrdinalIgnoreCase);
        }
        catch
        {
            return false;
        }
    }

    private static HttpClient CreateClient(bool useProxy)
    {
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression = DecompressionMethods.All,
            UseProxy = useProxy
        };
        var client = new HttpClient(handler) { Timeout = TimeSpan.FromMinutes(30) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/136 Safari/537.36");
        client.DefaultRequestHeaders.Accept.ParseAdd("*/*");
        return client;
    }

    [GeneratedRegex(@"__SHIZHEN_PROGRESS__:\s*(?<percent>[\d.]+%)\|(?<completed>\d+|NA)\|(?<total>\d+|NA)", RegexOptions.CultureInvariant)]
    private static partial Regex ProgressRegex();
}
