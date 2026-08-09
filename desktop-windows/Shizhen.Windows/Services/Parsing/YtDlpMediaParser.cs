using System.Text.Json;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services.Parsing;

public sealed class YtDlpMediaParser(ProcessRunner processRunner) : IMediaParser
{
    public string Name => "yt-dlp 公开媒体解析器";
    public bool CanHandle(string url) => ToolLocator.FindYtDlp() is not null && Uri.TryCreate(url, UriKind.Absolute, out _);

    public async Task<ParsedMedia> ParseAsync(string url, CancellationToken cancellationToken = default)
    {
        var executable = ToolLocator.FindYtDlp()
            ?? throw new UserFacingException("解析组件尚未安装，请运行工具安装脚本后重试。");
        var arguments = new List<string>
        {
            "--dump-single-json",
            "--no-playlist",
            "--skip-download",
            "--no-warnings",
            "--socket-timeout", "25",
            "--retries", "2",
            "--fragment-retries", "2",
            "--extractor-retries", "2",
            "--encoding", "utf-8"
        };
        var deno = ToolLocator.FindDeno();
        if (deno is not null)
        {
            arguments.Add("--js-runtimes");
            arguments.Add($"deno:{deno}");
        }
        AddPlatformArguments(arguments, url);
        arguments.Add(url);

        var result = await processRunner.RunAsync(executable, arguments, cancellationToken: cancellationToken);
        if (result.ExitCode != 0 || string.IsNullOrWhiteSpace(result.StandardOutput))
        {
            throw ClassifyFailure(url, result.StandardError);
        }

        try
        {
            using var document = JsonDocument.Parse(result.StandardOutput);
            var root = SelectEntry(document.RootElement);
            return BuildParsedMedia(url, root);
        }
        catch (JsonException exception)
        {
            throw new UserFacingException("解析组件返回了无法识别的数据，请更新解析组件后重试。", exception.Message, exception);
        }
    }

    private static JsonElement SelectEntry(JsonElement root)
    {
        if (root.TryGetProperty("entries", out var entries) && entries.ValueKind == JsonValueKind.Array)
        {
            foreach (var entry in entries.EnumerateArray())
            {
                if (entry.ValueKind == JsonValueKind.Object) return entry;
            }
        }
        return root;
    }

    private static ParsedMedia BuildParsedMedia(string originalUrl, JsonElement root)
    {
        var webpageUrl = GetString(root, "webpage_url") ?? originalUrl;
        var extractor = GetString(root, "extractor_key") ?? GetString(root, "extractor") ?? "yt-dlp";
        var platform = InferPlatform(extractor, webpageUrl);
        var title = GetString(root, "title") ?? GetString(root, "fulltitle");
        var author = GetString(root, "uploader") ?? GetString(root, "channel") ?? GetString(root, "creator");
        var duration = GetDouble(root, "duration");
        var thumbnail = GetString(root, "thumbnail");
        var items = new List<MediaItem>();

        if (root.TryGetProperty("formats", out var formats) && formats.ValueKind == JsonValueKind.Array)
        {
            foreach (var format in formats.EnumerateArray())
            {
                var mediaUrl = GetString(format, "url");
                if (string.IsNullOrWhiteSpace(mediaUrl)) continue;
                var formatId = GetString(format, "format_id") ?? items.Count.ToString();
                var extension = GetString(format, "ext");
                var videoCodec = GetString(format, "vcodec");
                var audioCodec = GetString(format, "acodec");
                var width = GetInt(format, "width");
                var height = GetInt(format, "height");
                var fileSize = GetLong(format, "filesize") ?? GetLong(format, "filesize_approx");
                var formatNote = GetString(format, "format_note");
                var type = InferType(extension, videoCodec, audioCodec);
                if (type == MediaType.Unknown) continue;

                var isPreview = type == MediaType.Audio && IsPreviewAudio(platform, duration, formatNote, mediaUrl);
                items.Add(new MediaItem
                {
                    Id = $"{type}-{formatId}",
                    Type = type,
                    MediaUrl = mediaUrl,
                    Format = extension,
                    Width = width,
                    Height = height,
                    FileSize = fileSize,
                    DurationSeconds = duration,
                    QualityLabel = BuildQualityLabel(type, width, height, formatNote, GetDouble(format, "abr")),
                    FormatId = formatId,
                    VideoCodec = videoCodec,
                    AudioCodec = audioCodec,
                    ThumbnailUrl = thumbnail,
                    IsPreviewOnly = isPreview
                });
            }
        }

        if (items.Count == 0)
        {
            var direct = GetString(root, "url");
            var extension = GetString(root, "ext");
            if (!string.IsNullOrWhiteSpace(direct))
            {
                var type = InferType(extension, GetString(root, "vcodec"), GetString(root, "acodec"));
                if (type != MediaType.Unknown)
                {
                    items.Add(new MediaItem
                    {
                        Id = "primary",
                        Type = type,
                        MediaUrl = direct,
                        Format = extension,
                        Width = GetInt(root, "width"),
                        Height = GetInt(root, "height"),
                        FileSize = GetLong(root, "filesize") ?? GetLong(root, "filesize_approx"),
                        DurationSeconds = duration,
                        QualityLabel = "原始资源",
                        FormatId = GetString(root, "format_id"),
                        VideoCodec = GetString(root, "vcodec"),
                        AudioCodec = GetString(root, "acodec"),
                        ThumbnailUrl = thumbnail,
                        IsPreviewOnly = type == MediaType.Audio && IsPreviewAudio(platform, duration, null, direct)
                    });
                }
            }
        }

        if (!items.Any(item => item.Type == MediaType.Audio))
        {
            var videoWithAudio = items
                .Where(item => item.Type == MediaType.Video
                               && !string.IsNullOrWhiteSpace(item.AudioCodec)
                               && !string.Equals(item.AudioCodec, "none", StringComparison.OrdinalIgnoreCase))
                .OrderByDescending(item => item.Height ?? 0)
                .ThenByDescending(item => item.FileSize ?? 0)
                .FirstOrDefault();
            if (videoWithAudio is not null)
            {
                items.Add(new MediaItem
                {
                    Id = $"audio-from-{videoWithAudio.FormatId ?? videoWithAudio.Id}",
                    Type = MediaType.Audio,
                    MediaUrl = videoWithAudio.MediaUrl,
                    Format = "m4a",
                    FileSize = null,
                    DurationSeconds = duration,
                    QualityLabel = "从公开视频提取音频",
                    FormatId = videoWithAudio.FormatId,
                    AudioCodec = videoWithAudio.AudioCodec,
                    ThumbnailUrl = thumbnail,
                    ExtractAudio = true
                });
            }
        }

        if (!string.IsNullOrWhiteSpace(thumbnail))
        {
            items.Add(new MediaItem
            {
                Id = "cover",
                Type = MediaType.Cover,
                MediaUrl = thumbnail,
                Format = GuessThumbnailFormat(thumbnail),
                QualityLabel = "作品封面"
            });
        }

        items = items
            .GroupBy(item => $"{item.Type}|{item.FormatId}|{item.MediaUrl}", StringComparer.OrdinalIgnoreCase)
            .Select(group => group.First())
            .OrderBy(item => TypeOrder(item.Type))
            .ThenByDescending(item => item.Height ?? 0)
            .ThenByDescending(item => item.FileSize ?? 0)
            .ToList();

        // Keep a useful, readable set of variants without silently discarding media types.
        var compactItems = items
            .GroupBy(item => item.Type)
            .SelectMany(group => group.Key switch
            {
                MediaType.Video => group.GroupBy(item => $"{item.Height}|{item.Format}|{item.AudioCodec}").Select(item => item.First()).Take(12),
                MediaType.Audio => group.GroupBy(item => $"{Math.Round((item.FileSize ?? 0) / Math.Max(1, duration ?? 1) / 1000d)}|{item.Format}").Select(item => item.First()).Take(8),
                _ => group.Take(30)
            })
            .ToList();

        if (compactItems.Count == 0)
        {
            throw new UserFacingException("解析成功，但未发现可下载的公开媒体资源。");
        }

        if (compactItems.All(item => item.Type == MediaType.Cover)
            && platform is "YouTube" or "Facebook")
        {
            throw new UserFacingException(platform == "YouTube" ? "请更换节点重试" : "该页面未公开提供可下载的视频，可能需要登录或存在访问限制。");
        }

        return new ParsedMedia(webpageUrl, platform, title, author, thumbnail, duration, compactItems, extractor);
    }

    private static UserFacingException ClassifyFailure(string url, string details)
    {
        var lower = details.ToLowerInvariant();
        if (PlatformDetector.Detect(url) == "YouTube")
        {
            return new UserFacingException("请更换节点重试", details);
        }
        if (lower.Contains("login") || lower.Contains("sign in") || lower.Contains("private")
            || lower.Contains("cookie") || lower.Contains("authentication") || lower.Contains("members-only"))
        {
            return new UserFacingException("该资源需要登录或存在访问限制，本工具不支持提取。", details);
        }

        if (lower.Contains("unsupported url"))
        {
            return new UserFacingException("当前解析组件暂不支持这个平台，将尝试通用网页解析。", details);
        }

        if (lower.Contains("http error 404") || lower.Contains("not found") || lower.Contains("unavailable"))
        {
            return new UserFacingException("链接已经失效，或该资源已被发布者删除。", details);
        }

        if (lower.Contains("timed out") || lower.Contains("connection") || lower.Contains("network")
            || lower.Contains("unable to download"))
        {
            return new UserFacingException("网络连接失败，请检查网络或稍后重试。", details);
        }

        return new UserFacingException("暂时无法解析这个链接，可能是平台规则发生变化。", details);
    }

    private static void AddPlatformArguments(List<string> arguments, string url)
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

    private static string InferPlatform(string extractor, string url)
    {
        var lower = extractor.ToLowerInvariant();
        if (lower.Contains("youtube")) return "YouTube";
        if (lower.Contains("instagram")) return "Instagram";
        if (lower.Contains("facebook")) return "Facebook";
        if (lower.Contains("twitter")) return "X / Twitter";
        if (lower.Contains("tiktok")) return "TikTok";
        if (lower.Contains("bilibili")) return "哔哩哔哩";
        if (lower.Contains("douyin")) return "抖音";
        if (lower.Contains("ixigua") || lower.Contains("xigua")) return "西瓜视频";
        return PlatformDetector.Detect(url);
    }

    private static string GuessThumbnailFormat(string url)
    {
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            var extension = Path.GetExtension(uri.AbsolutePath).TrimStart('.');
            if (!string.IsNullOrWhiteSpace(extension) && extension.Length <= 5) return extension;
        }
        return "jpg";
    }

    private static MediaType InferType(string? extension, string? videoCodec, string? audioCodec)
    {
        if (string.Equals(extension, "gif", StringComparison.OrdinalIgnoreCase)) return MediaType.Gif;
        var hasVideo = !string.IsNullOrWhiteSpace(videoCodec) && !string.Equals(videoCodec, "none", StringComparison.OrdinalIgnoreCase);
        var hasAudio = !string.IsNullOrWhiteSpace(audioCodec) && !string.Equals(audioCodec, "none", StringComparison.OrdinalIgnoreCase);
        if (hasVideo) return MediaType.Video;
        if (hasAudio) return MediaType.Audio;
        return extension?.ToLowerInvariant() switch
        {
            "jpg" or "jpeg" or "png" or "webp" or "avif" => MediaType.Image,
            "mp4" or "webm" or "mov" or "mkv" or "m3u8" => MediaType.Video,
            "mp3" or "m4a" or "aac" or "flac" or "ogg" or "opus" => MediaType.Audio,
            _ => MediaType.Unknown
        };
    }

    private static bool IsPreviewAudio(string platform, double? duration, string? note, string mediaUrl)
    {
        if (note?.Contains("preview", StringComparison.OrdinalIgnoreCase) == true
            || mediaUrl.Contains("preview", StringComparison.OrdinalIgnoreCase)) return true;
        var isMusicPlatform = platform is "网易云音乐" or "QQ音乐" or "酷狗音乐" or "酷我音乐";
        return isMusicPlatform && duration is > 0 and <= 90;
    }

    private static string BuildQualityLabel(MediaType type, int? width, int? height, string? note, double? abr)
    {
        if (!string.IsNullOrWhiteSpace(note) && note.Length <= 36) return note;
        if (type == MediaType.Audio && abr.HasValue) return $"{abr:0} kbps";
        if (height.HasValue) return height >= 2160 ? "4K" : $"{height}P";
        if (width.HasValue) return $"宽 {width}";
        return type == MediaType.Audio ? "音频轨" : "默认";
    }

    private static int TypeOrder(MediaType type) => type switch
    {
        MediaType.Video => 0,
        MediaType.Image => 1,
        MediaType.Gif => 2,
        MediaType.Audio => 3,
        MediaType.Cover => 4,
        _ => 5
    };

    private static string? GetString(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value)) return null;
        return value.ValueKind == JsonValueKind.String ? value.GetString() : value.ToString();
    }

    private static double? GetDouble(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value)) return null;
        if (value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var number)) return number;
        return double.TryParse(value.ToString(), System.Globalization.CultureInfo.InvariantCulture, out number) ? number : null;
    }

    private static int? GetInt(JsonElement element, string name)
    {
        var number = GetDouble(element, name);
        return number.HasValue ? (int?)Math.Round(number.Value) : null;
    }

    private static long? GetLong(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value)) return null;
        if (value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var number)) return number;
        return long.TryParse(value.ToString(), out number) ? number : null;
    }
}
