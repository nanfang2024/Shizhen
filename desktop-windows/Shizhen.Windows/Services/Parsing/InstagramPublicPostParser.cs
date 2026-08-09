using System.Text.Json;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services.Parsing;

/// <summary>
/// Reads only the anonymously visible media object for the requested Instagram post.
/// yt-dlp currently exposes image carousels as playlist entries but rejects each image
/// because it has no video format, so this parser consumes the same transient public
/// GraphQL response and maps the current post's carousel items directly.
/// </summary>
public sealed class InstagramPublicPostParser(ProcessRunner processRunner, DiagnosticLogger logger) : IMediaParser
{
    public string Name => "Instagram 当前帖子公开解析器";

    public bool CanHandle(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        var host = uri.IdnHost;
        if (!host.Equals("instagram.com", StringComparison.OrdinalIgnoreCase)
            && !host.EndsWith(".instagram.com", StringComparison.OrdinalIgnoreCase)) return false;
        return uri.AbsolutePath.StartsWith("/p/", StringComparison.OrdinalIgnoreCase);
    }

    public async Task<ParsedMedia> ParseAsync(string url, CancellationToken cancellationToken = default)
    {
        var executable = ToolLocator.FindYtDlp()
            ?? throw new UserFacingException("解析组件尚未安装，请重新安装完整版本。");
        var workDirectory = Path.Combine(
            Path.GetTempPath(), "Shizhen", "InstagramParser", Guid.NewGuid().ToString("N"));
        Directory.CreateDirectory(workDirectory);

        try
        {
            var arguments = new List<string>
            {
                "--skip-download",
                "--ignore-errors",
                "--write-pages",
                "--no-warnings",
                "--no-playlist",
                "--socket-timeout", "25",
                "--retries", "2",
                "--extractor-retries", "2",
                "--impersonate", "chrome",
                "--encoding", "utf-8"
            };
            var deno = ToolLocator.FindDeno();
            if (deno is not null)
            {
                arguments.Add("--js-runtimes");
                arguments.Add($"deno:{deno}");
            }
            arguments.Add(url);

            var result = await processRunner.RunAsync(
                executable,
                arguments,
                cancellationToken: cancellationToken,
                workingDirectory: workDirectory);
            var dumpPath = Directory.EnumerateFiles(workDirectory, "*graphql.dump", SearchOption.TopDirectoryOnly)
                .OrderByDescending(File.GetLastWriteTimeUtc)
                .FirstOrDefault();
            if (dumpPath is null)
            {
                throw ClassifyFailure(result.StandardError);
            }
            await logger.WriteAsync("PARSE", "已取得 Instagram 当前帖子的匿名公开媒体响应。");

            await using var stream = File.OpenRead(dumpPath);
            using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
            return BuildParsedMedia(url, document.RootElement);
        }
        catch (JsonException exception)
        {
            throw new UserFacingException(
                "Instagram 返回了无法识别的公开数据，请更新解析组件后重试。",
                exception.Message,
                exception);
        }
        finally
        {
            TryDeleteDirectory(workDirectory);
        }
    }

    private static ParsedMedia BuildParsedMedia(string sourceUrl, JsonElement documentRoot)
    {
        if (!TryGetObject(documentRoot, "data", out var data)
            || !TryGetObject(data, "xig_polaris_media", out var wrapper)
            || !TryGetObject(wrapper, "if_not_gated_logged_out", out var media))
        {
            throw new UserFacingException("该资源需要登录或存在访问限制，本工具不支持提取。");
        }

        var title = GetNestedString(media, "caption", "text");
        if (string.IsNullOrWhiteSpace(title)) title = "Instagram 帖子";
        var author = GetNestedString(media, "user", "username");
        var duration = GetDouble(media, "video_duration");
        var items = new List<MediaItem>();

        if (media.TryGetProperty("carousel_media", out var carousel)
            && carousel.ValueKind == JsonValueKind.Array)
        {
            var index = 0;
            foreach (var child in carousel.EnumerateArray())
            {
                index++;
                AddCurrentMediaItems(child, index, items);
            }
        }
        else
        {
            AddCurrentMediaItems(media, 1, items);
        }

        items = items
            .GroupBy(item => $"{item.Type}|{item.MediaUrl}", StringComparer.OrdinalIgnoreCase)
            .Select(group => group.First())
            .ToList();
        if (items.Count == 0)
        {
            throw new UserFacingException("解析成功，但当前 Instagram 帖子没有公开可下载的媒体资源。");
        }

        var thumbnail = items.FirstOrDefault(item => item.Type == MediaType.Image)?.MediaUrl
            ?? items.FirstOrDefault()?.ThumbnailUrl;
        var videoWithAudio = items.FirstOrDefault(item => item.Type == MediaType.Video
            && !string.Equals(item.AudioCodec, "none", StringComparison.OrdinalIgnoreCase));
        if (videoWithAudio is not null)
        {
            items.Add(new MediaItem
            {
                Id = $"instagram-audio-{videoWithAudio.Id}",
                Type = MediaType.Audio,
                MediaUrl = videoWithAudio.MediaUrl,
                Format = "m4a",
                DurationSeconds = videoWithAudio.DurationSeconds,
                QualityLabel = "从当前帖子视频提取音频",
                AudioCodec = videoWithAudio.AudioCodec,
                ThumbnailUrl = videoWithAudio.ThumbnailUrl,
                ReferrerUrl = "https://www.instagram.com/",
                ExtractAudio = true
            });
        }

        return new ParsedMedia(
            sourceUrl,
            "Instagram",
            title,
            author,
            thumbnail,
            duration,
            items,
            "InstagramPublicGraphQL");
    }

    private static void AddCurrentMediaItems(JsonElement media, int index, ICollection<MediaItem> items)
    {
        var code = GetString(media, "code") ?? index.ToString();
        var width = GetInt(media, "original_width");
        var height = GetInt(media, "original_height");
        var duration = GetDouble(media, "video_duration");
        var thumbnail = GetImageUrl(media);

        if (media.TryGetProperty("video_versions", out var versions)
            && versions.ValueKind == JsonValueKind.Array)
        {
            var versionIndex = 0;
            foreach (var version in versions.EnumerateArray())
            {
                var mediaUrl = GetString(version, "url");
                if (string.IsNullOrWhiteSpace(mediaUrl)) continue;
                versionIndex++;
                var videoWidth = GetInt(version, "width") ?? width;
                var videoHeight = GetInt(version, "height") ?? height;
                var hasAudio = GetBoolean(media, "has_audio") != false;
                items.Add(new MediaItem
                {
                    Id = $"instagram-video-{code}-{versionIndex}",
                    Type = MediaType.Video,
                    MediaUrl = mediaUrl,
                    Format = GuessFormat(mediaUrl, "mp4"),
                    Width = videoWidth,
                    Height = videoHeight,
                    DurationSeconds = duration,
                    QualityLabel = videoHeight.HasValue ? $"{videoHeight}P" : "帖子视频",
                    VideoCodec = "h264",
                    AudioCodec = hasAudio ? "aac" : "none",
                    ThumbnailUrl = thumbnail,
                    ReferrerUrl = "https://www.instagram.com/"
                });
            }
        }

        if (!items.Any(item => item.Id.StartsWith($"instagram-video-{code}-", StringComparison.Ordinal)))
        {
            var imageUrl = GetImageUrl(media);
            if (!string.IsNullOrWhiteSpace(imageUrl))
            {
                items.Add(new MediaItem
                {
                    Id = $"instagram-image-{code}",
                    Type = MediaType.Image,
                    MediaUrl = imageUrl,
                    Format = GuessFormat(imageUrl, "jpg"),
                    Width = width,
                    Height = height,
                    QualityLabel = "帖子原图",
                    ReferrerUrl = "https://www.instagram.com/"
                });
            }
        }
    }

    private static string? GetImageUrl(JsonElement media)
    {
        if (TryGetObject(media, "image_versions2", out var imageVersions)
            && imageVersions.TryGetProperty("candidates", out var candidates)
            && candidates.ValueKind == JsonValueKind.Array)
        {
            string? first = null;
            foreach (var candidate in candidates.EnumerateArray())
            {
                var url = GetString(candidate, "url");
                if (string.IsNullOrWhiteSpace(url)) continue;
                first ??= url;
                if (!url.Contains("stp=", StringComparison.OrdinalIgnoreCase)) return url;
            }
            if (first is not null) return first;
        }
        return GetString(media, "display_uri");
    }

    private static UserFacingException ClassifyFailure(string details)
    {
        var lower = details.ToLowerInvariant();
        if (lower.Contains("login") || lower.Contains("sign in") || lower.Contains("private")
            || lower.Contains("cookie") || lower.Contains("challenge") || lower.Contains("authentication"))
        {
            return new UserFacingException(
                "该资源需要登录或存在访问限制，本工具不支持提取。",
                details);
        }
        if (lower.Contains("timed out") || lower.Contains("connection") || lower.Contains("unable to download"))
        {
            return new UserFacingException("网络连接失败，请检查网络或更换节点后重试。", details);
        }
        return new UserFacingException(
            "暂时无法解析这个 Instagram 帖子，可能是平台规则发生变化。",
            details);
    }

    private static bool TryGetObject(JsonElement element, string name, out JsonElement value)
    {
        if (element.ValueKind == JsonValueKind.Object
            && element.TryGetProperty(name, out value)
            && value.ValueKind == JsonValueKind.Object) return true;
        value = default;
        return false;
    }

    private static string? GetNestedString(JsonElement element, string objectName, string propertyName) =>
        TryGetObject(element, objectName, out var nested) ? GetString(nested, propertyName) : null;

    private static string? GetString(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(name, out var value)) return null;
        return value.ValueKind == JsonValueKind.String ? value.GetString() : value.ToString();
    }

    private static double? GetDouble(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(name, out var value)) return null;
        return value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var number) ? number : null;
    }

    private static int? GetInt(JsonElement element, string name)
    {
        var number = GetDouble(element, name);
        return number.HasValue ? (int?)Math.Round(number.Value) : null;
    }

    private static bool? GetBoolean(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(name, out var value)) return null;
        return value.ValueKind switch
        {
            JsonValueKind.True => true,
            JsonValueKind.False => false,
            _ => null
        };
    }

    private static string GuessFormat(string url, string fallback)
    {
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            var extension = Path.GetExtension(uri.AbsolutePath).TrimStart('.');
            if (!string.IsNullOrWhiteSpace(extension) && extension.Length <= 5) return extension;
        }
        return fallback;
    }

    private static void TryDeleteDirectory(string path)
    {
        try
        {
            if (Directory.Exists(path)) Directory.Delete(path, recursive: true);
        }
        catch
        {
            // Transient parser dumps contain only the current public response and are disposable.
        }
    }
}
