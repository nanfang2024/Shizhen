using System.Net;
using System.Net.Http;
using System.Net.Http.Headers;
using System.Text.RegularExpressions;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services.Parsing;

public sealed partial class GenericMediaParser : IMediaParser
{
    private readonly HttpClient _client;
    private readonly DiagnosticLogger _logger;

    public GenericMediaParser(DiagnosticLogger logger)
    {
        _logger = logger;
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression = DecompressionMethods.All
        };
        _client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(25) };
        _client.DefaultRequestHeaders.UserAgent.ParseAdd("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/136 Safari/537.36");
        _client.DefaultRequestHeaders.AcceptLanguage.ParseAdd("zh-CN,zh;q=0.9,en;q=0.7");
    }

    public string Name => "通用公开网页解析器";
    public bool CanHandle(string url) => Uri.TryCreate(url, UriKind.Absolute, out _);

    public async Task<ParsedMedia> ParseAsync(string url, CancellationToken cancellationToken = default)
    {
        using var response = await _client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        if (response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
        {
            throw new UserFacingException("该资源需要登录或存在访问限制，本工具不支持提取。", $"HTTP {(int)response.StatusCode}");
        }

        if (!response.IsSuccessStatusCode)
        {
            throw new UserFacingException("链接已经失效或暂时无法访问。", $"HTTP {(int)response.StatusCode}");
        }

        var finalUrl = response.RequestMessage?.RequestUri?.AbsoluteUri ?? url;
        var platform = PlatformDetector.Detect(finalUrl);
        var contentType = response.Content.Headers.ContentType?.MediaType?.ToLowerInvariant();
        if (IsDirectMedia(contentType, finalUrl, out var directType, out var directFormat))
        {
            var length = response.Content.Headers.ContentLength;
            return new ParsedMedia(finalUrl, platform, Path.GetFileName(new Uri(finalUrl).LocalPath), null, null, null,
                [new MediaItem
                {
                    Id = "direct",
                    Type = directType,
                    MediaUrl = finalUrl,
                    Format = directFormat,
                    FileSize = length,
                    QualityLabel = "原始资源",
                    AudioCodec = directType == MediaType.Audio ? directFormat : null,
                    VideoCodec = directType is MediaType.Video or MediaType.Gif ? directFormat : null
                }], Name);
        }

        var html = await response.Content.ReadAsStringAsync(cancellationToken);
        if (html.Length > 5_000_000)
        {
            html = html[..5_000_000];
        }

        var title = FindMeta(html, "og:title") ?? FindMeta(html, "twitter:title") ?? FindTitle(html);
        var author = FindMeta(html, "author") ?? FindMeta(html, "og:site_name");
        var image = FindMeta(html, "og:image") ?? FindMeta(html, "twitter:image");
        var video = FindMeta(html, "og:video:url") ?? FindMeta(html, "og:video")
            ?? FindMeta(html, "twitter:player:stream");
        var audio = FindMeta(html, "og:audio") ?? FindMeta(html, "og:audio:url");
        var items = new List<MediaItem>();

        AddMetaItem(items, video, finalUrl, MediaType.Video, "网页公开视频");
        AddMetaItem(items, audio, finalUrl, MediaType.Audio, "网页公开音频");
        AddMetaItem(items, image, finalUrl, MediaType.Cover, "作品封面");

        if (!RequiresCurrentWorkOnly(platform))
        {
            foreach (Match match in EmbeddedMediaRegex().Matches(html).Cast<Match>().Take(40))
            {
                var decoded = WebUtility.HtmlDecode(match.Groups["url"].Value)
                    .Replace("\\u002F", "/", StringComparison.OrdinalIgnoreCase)
                    .Replace("\\/", "/", StringComparison.Ordinal);
                if (!Uri.TryCreate(decoded, UriKind.Absolute, out var mediaUri) || IsLikelyUnrelatedAsset(mediaUri)) continue;
                var extension = Path.GetExtension(mediaUri.AbsolutePath).TrimStart('.').ToLowerInvariant();
                var type = extension switch
                {
                    "mp4" or "webm" or "mov" or "m3u8" => MediaType.Video,
                    "gif" => MediaType.Gif,
                    "jpg" or "jpeg" or "png" or "webp" or "avif" => MediaType.Image,
                    "mp3" or "m4a" or "aac" or "flac" or "ogg" => MediaType.Audio,
                    _ => MediaType.Unknown
                };
                if (type != MediaType.Unknown)
                {
                    AddMetaItem(items, mediaUri.AbsoluteUri, finalUrl, type, "页面内公开资源");
                }
            }
        }

        items = items
            .GroupBy(item => item.MediaUrl, StringComparer.OrdinalIgnoreCase)
            .Select(group => group.First())
            .Take(30)
            .ToList();

        if (items.Count == 0)
        {
            await _logger.WriteAsync("GENERIC", $"页面未发现公开媒体元数据：{new Uri(finalUrl).Host}");
            throw new UserFacingException("未发现可下载的公开媒体资源。页面可能需要登录或通过脚本动态加载。", "Open Graph 与静态页面扫描均无结果");
        }

        if (items.All(item => item.Type == MediaType.Cover) && platform is "哔哩哔哩" or "YouTube" or "Facebook")
        {
            throw new UserFacingException("当前作品没有公开可下载的媒体，不会把封面或网页冒充为视频。");
        }

        return new ParsedMedia(finalUrl, platform, title, author, image, null, items, Name);
    }

    private static void AddMetaItem(List<MediaItem> items, string? value, string baseUrl, MediaType type, string quality)
    {
        if (string.IsNullOrWhiteSpace(value)) return;
        if (!Uri.TryCreate(value, UriKind.Absolute, out var uri)
            && !Uri.TryCreate(new Uri(baseUrl), value, out uri)) return;
        var format = Path.GetExtension(uri.AbsolutePath).TrimStart('.');
        items.Add(new MediaItem
        {
            Id = $"generic-{items.Count}",
            Type = type,
            MediaUrl = uri.AbsoluteUri,
            Format = string.IsNullOrWhiteSpace(format) ? null : format,
            QualityLabel = quality
        });
    }

    private static string? FindMeta(string html, string key)
    {
        foreach (Match match in MetaRegex().Matches(html))
        {
            var attributes = match.Value;
            var property = AttributeRegex().Matches(attributes).Cast<Match>()
                .ToDictionary(m => m.Groups["name"].Value.ToLowerInvariant(), m => WebUtility.HtmlDecode(m.Groups["value"].Value));
            if ((property.TryGetValue("property", out var propertyName) || property.TryGetValue("name", out propertyName))
                && propertyName.Equals(key, StringComparison.OrdinalIgnoreCase)
                && property.TryGetValue("content", out var content))
            {
                return content.Trim();
            }
        }
        return null;
    }

    private static string? FindTitle(string html)
    {
        var match = TitleRegex().Match(html);
        return match.Success ? WebUtility.HtmlDecode(match.Groups["title"].Value.Trim()) : null;
    }

    private static bool IsDirectMedia(string? contentType, string url, out MediaType type, out string? format)
    {
        format = Path.GetExtension(new Uri(url).AbsolutePath).TrimStart('.').ToLowerInvariant();
        if (contentType is not null && (contentType.StartsWith("text/")
                                        || contentType.Contains("html", StringComparison.OrdinalIgnoreCase)
                                        || contentType.Contains("json", StringComparison.OrdinalIgnoreCase)))
        {
            type = MediaType.Unknown;
            return false;
        }
        type = contentType switch
        {
            string value when value.StartsWith("video/") => MediaType.Video,
            string value when value.StartsWith("audio/") => MediaType.Audio,
            "image/gif" => MediaType.Gif,
            string value when value.StartsWith("image/") => MediaType.Image,
            _ => format switch
            {
                "mp4" or "webm" or "mov" or "m3u8" => MediaType.Video,
                "mp3" or "m4a" or "aac" or "flac" or "ogg" => MediaType.Audio,
                "gif" => MediaType.Gif,
                "jpg" or "jpeg" or "png" or "webp" => MediaType.Image,
                _ => MediaType.Unknown
            }
        };
        return type != MediaType.Unknown;
    }

    private static bool RequiresCurrentWorkOnly(string platform) => platform is
        "西瓜视频" or "抖音" or "哔哩哔哩" or "小红书" or "快手" or "微博" or "豆包"
        or "YouTube" or "Instagram" or "Facebook" or "X / Twitter" or "TikTok"
        or "网易云音乐" or "QQ音乐" or "酷狗音乐" or "酷我音乐";

    private static bool IsLikelyUnrelatedAsset(Uri uri)
    {
        var value = (uri.AbsolutePath + uri.Query).ToLowerInvariant();
        return value.Contains("avatar") || value.Contains("user-face") || value.Contains("userface")
               || value.Contains("comment") || value.Contains("emoji") || value.Contains("emote")
               || value.Contains("profile") || value.Contains("portrait") || value.Contains("headpic")
               || value.Contains("/face/") || value.Contains("/avatar/");
    }

    [GeneratedRegex(@"<meta\b[^>]*>", RegexOptions.IgnoreCase | RegexOptions.Singleline)]
    private static partial Regex MetaRegex();
    [GeneratedRegex("(?<name>[a-zA-Z_:][-a-zA-Z0-9_:.]*)\\s*=\\s*[\\\"'](?<value>.*?)[\\\"']", RegexOptions.IgnoreCase | RegexOptions.Singleline)]
    private static partial Regex AttributeRegex();
    [GeneratedRegex(@"<title[^>]*>(?<title>.*?)</title>", RegexOptions.IgnoreCase | RegexOptions.Singleline)]
    private static partial Regex TitleRegex();
    [GeneratedRegex("(?<url>https?:\\\\?/\\\\?/[^\\\"'<>\\s]+?\\.(?:mp4|webm|mov|m3u8|gif|jpe?g|png|webp|avif|mp3|m4a|aac|flac|ogg)(?:\\?[^\\\"'<>\\s]*)?)", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex EmbeddedMediaRegex();
}
