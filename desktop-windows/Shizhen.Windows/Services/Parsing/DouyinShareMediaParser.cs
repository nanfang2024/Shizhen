using System.Net;
using System.Text.Json;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services.Parsing;

/// <summary>
/// Reads Douyin's public mobile share page. This path uses an anonymous session and never
/// imports browser cookies or account credentials.
/// </summary>
public sealed class DouyinShareMediaParser : IMediaParser
{
    private const string MobileUserAgent = "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36";
    private readonly DiagnosticLogger _logger;
    private readonly HttpClient _client = CreateClient();

    public DouyinShareMediaParser(DiagnosticLogger logger)
    {
        _logger = logger;
    }

    public string Name => "抖音公开分享页解析器";

    public bool CanHandle(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        var host = uri.IdnHost;
        return host.Equals("douyin.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith(".douyin.com", StringComparison.OrdinalIgnoreCase)
               || host.Equals("iesdouyin.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith(".iesdouyin.com", StringComparison.OrdinalIgnoreCase);
    }

    public async Task<ParsedMedia> ParseAsync(string url, CancellationToken cancellationToken = default)
    {
        try
        {
            var resolvedUrl = await ResolveUrlAsync(url, cancellationToken);
            var videoId = ExtractVideoId(resolvedUrl) ?? ExtractVideoId(url);
            if (videoId is null)
            {
                throw new UserFacingException("这条抖音链接没有包含可识别的作品 ID。");
            }

            var shareUrl = $"https://www.iesdouyin.com/share/video/{videoId}/";
            using var request = new HttpRequestMessage(HttpMethod.Get, shareUrl);
            request.Headers.Referrer = new Uri("https://www.douyin.com/");
            using var response = await _client.SendAsync(request, HttpCompletionOption.ResponseContentRead, cancellationToken);
            if (response.StatusCode is HttpStatusCode.Unauthorized or HttpStatusCode.Forbidden)
            {
                throw new UserFacingException("该资源需要登录或存在访问限制，本工具不支持提取。");
            }
            response.EnsureSuccessStatusCode();
            var html = await response.Content.ReadAsStringAsync(cancellationToken);
            return ParseSharePage(url, html, videoId);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (UserFacingException)
        {
            throw;
        }
        catch (HttpRequestException exception)
        {
            await _logger.WriteAsync("DOUYIN", "抖音公开分享页请求失败", exception);
            throw new UserFacingException("抖音公开分享页暂时无法访问，请检查网络后重试。", exception.Message, exception);
        }
        catch (JsonException exception)
        {
            await _logger.WriteAsync("DOUYIN", "抖音公开分享页数据格式已变化", exception);
            throw new UserFacingException("暂时无法解析这条抖音链接，可能是平台规则发生变化。", exception.Message, exception);
        }
    }

    internal static ParsedMedia ParseSharePage(string sourceUrl, string html, string videoId)
    {
        const string marker = "window._ROUTER_DATA = ";
        var start = html.IndexOf(marker, StringComparison.Ordinal);
        if (start < 0)
        {
            throw new UserFacingException("抖音页面未提供公开媒体数据，资源可能已过期或存在访问限制。");
        }

        start += marker.Length;
        var end = html.IndexOf("</script>", start, StringComparison.OrdinalIgnoreCase);
        if (end < 0) throw new JsonException("抖音分享页路由数据不完整。");

        using var document = JsonDocument.Parse(html[start..end].Trim());
        var item = FindMediaItem(document.RootElement)
            ?? throw new UserFacingException("抖音分享页未发现可下载的公开作品。");

        var title = GetString(item, "desc");
        var author = TryGetObject(item, "author", out var authorNode) ? GetString(authorNode, "nickname") : null;
        var mediaItems = new List<MediaItem>();
        string? thumbnail = null;
        double? durationSeconds = null;

        if (TryGetObject(item, "video", out var video))
        {
            var width = GetInt(video, "width");
            var height = GetInt(video, "height");
            var duration = GetDouble(video, "duration");
            durationSeconds = duration is > 1000 ? duration / 1000d : duration;
            var videoUrls = GetAddressUrls(video, "play_addr")
                .Concat(GetAddressUrls(video, "download_addr"))
                .Select(NormalizeVideoUrl)
                .Distinct(StringComparer.OrdinalIgnoreCase)
                .ToList();

            for (var index = 0; index < videoUrls.Count; index++)
            {
                mediaItems.Add(new MediaItem
                {
                    Id = $"douyin-video-{index}",
                    Type = MediaType.Video,
                    MediaUrl = videoUrls[index],
                    Format = "mp4",
                    Width = width,
                    Height = height,
                    FileSize = GetAddressSize(video, "play_addr"),
                    DurationSeconds = durationSeconds,
                    QualityLabel = index == 0 ? "公开原画" : $"公开版本 {index + 1}",
                    VideoCodec = "h264",
                    AudioCodec = "aac"
                });
            }

            thumbnail = GetAddressUrls(video, "origin_cover")
                .Concat(GetAddressUrls(video, "cover"))
                .FirstOrDefault();
        }

        foreach (var imageUrl in GetImageUrls(item).Distinct(StringComparer.OrdinalIgnoreCase))
        {
            mediaItems.Add(new MediaItem
            {
                Id = $"douyin-image-{mediaItems.Count}",
                Type = MediaType.Image,
                MediaUrl = imageUrl,
                Format = GuessImageFormat(imageUrl),
                QualityLabel = "公开原图"
            });
            thumbnail ??= imageUrl;
        }

        if (TryGetObject(item, "music", out var music))
        {
            var audioUrl = GetAddressUrls(music, "play_url").FirstOrDefault();
            if (!string.IsNullOrWhiteSpace(audioUrl))
            {
                mediaItems.Add(new MediaItem
                {
                    Id = "douyin-audio",
                    Type = MediaType.Audio,
                    MediaUrl = audioUrl,
                    Format = GuessAudioFormat(audioUrl),
                    DurationSeconds = durationSeconds,
                    QualityLabel = "作品原声",
                    AudioCodec = "aac"
                });
            }
        }

        if (!mediaItems.Any(media => media.Type == MediaType.Audio))
        {
            var videoWithAudio = mediaItems.FirstOrDefault(media => media.Type == MediaType.Video);
            if (videoWithAudio is not null)
            {
                mediaItems.Add(new MediaItem
                {
                    Id = "douyin-audio-from-video",
                    Type = MediaType.Audio,
                    MediaUrl = videoWithAudio.MediaUrl,
                    Format = "m4a",
                    DurationSeconds = durationSeconds,
                    QualityLabel = "从公开视频提取音频",
                    AudioCodec = videoWithAudio.AudioCodec,
                    ReferrerUrl = "https://www.douyin.com/",
                    ExtractAudio = true
                });
            }
        }

        if (!string.IsNullOrWhiteSpace(thumbnail))
        {
            mediaItems.Add(new MediaItem
            {
                Id = "douyin-cover",
                Type = MediaType.Cover,
                MediaUrl = thumbnail,
                Format = GuessImageFormat(thumbnail),
                QualityLabel = "作品封面"
            });
        }

        if (mediaItems.Count == 0)
        {
            throw new UserFacingException("抖音分享页未发现可下载的公开媒体资源。");
        }

        return new ParsedMedia(sourceUrl, "抖音", title, author, thumbnail, durationSeconds, mediaItems, "Douyin public share page");
    }

    private async Task<string> ResolveUrlAsync(string url, CancellationToken cancellationToken)
    {
        if (ExtractVideoId(url) is not null) return url;
        using var request = new HttpRequestMessage(HttpMethod.Get, url);
        using var response = await _client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        return response.RequestMessage?.RequestUri?.AbsoluteUri ?? url;
    }

    private static JsonElement? FindMediaItem(JsonElement root)
    {
        if (!root.TryGetProperty("loaderData", out var loaderData) || loaderData.ValueKind != JsonValueKind.Object) return null;
        foreach (var page in loaderData.EnumerateObject())
        {
            if (page.Value.ValueKind != JsonValueKind.Object
                || !page.Value.TryGetProperty("videoInfoRes", out var videoInfo)
                || !videoInfo.TryGetProperty("item_list", out var items)
                || items.ValueKind != JsonValueKind.Array) continue;
            foreach (var item in items.EnumerateArray())
            {
                if (item.ValueKind == JsonValueKind.Object) return item;
            }
        }
        return null;
    }

    private static IEnumerable<string> GetImageUrls(JsonElement item)
    {
        if (item.TryGetProperty("images", out var images) && images.ValueKind == JsonValueKind.Array)
        {
            foreach (var image in images.EnumerateArray())
            {
                foreach (var url in GetUrls(image)) yield return url;
            }
        }

        if (!TryGetObject(item, "image_post_info", out var postInfo)
            || !postInfo.TryGetProperty("images", out var postImages)
            || postImages.ValueKind != JsonValueKind.Array) yield break;
        foreach (var image in postImages.EnumerateArray())
        {
            foreach (var key in new[] { "display_image", "download_image", "owner_watermark_image" })
            {
                if (!TryGetObject(image, key, out var address)) continue;
                var preferred = GetUrls(address).FirstOrDefault();
                if (preferred is not null)
                {
                    yield return preferred;
                    break;
                }
            }
        }
    }

    private static IEnumerable<string> GetAddressUrls(JsonElement parent, string name)
    {
        if (!TryGetObject(parent, name, out var address)) return [];
        return GetUrls(address);
    }

    private static IEnumerable<string> GetUrls(JsonElement address)
    {
        foreach (var key in new[] { "url_list", "download_url_list", "UrlList" })
        {
            if (!address.TryGetProperty(key, out var urls) || urls.ValueKind != JsonValueKind.Array) continue;
            foreach (var value in urls.EnumerateArray())
            {
                var url = value.ValueKind == JsonValueKind.String ? value.GetString() : null;
                if (Uri.TryCreate(url, UriKind.Absolute, out _)) yield return url!;
            }
        }
    }

    private static long? GetAddressSize(JsonElement parent, string name) =>
        TryGetObject(parent, name, out var address) ? GetLong(address, "data_size") : null;

    private static string NormalizeVideoUrl(string url) => url
        .Replace("/playwm/", "/play/", StringComparison.OrdinalIgnoreCase)
        .Replace("watermark=1", "watermark=0", StringComparison.OrdinalIgnoreCase);

    private static string? ExtractVideoId(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return null;
        var segments = uri.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
        for (var index = 0; index < segments.Length - 1; index++)
        {
            if ((segments[index].Equals("video", StringComparison.OrdinalIgnoreCase)
                 || segments[index].Equals("note", StringComparison.OrdinalIgnoreCase))
                && segments[index + 1].All(char.IsDigit)) return segments[index + 1];
        }
        return null;
    }

    private static string GuessImageFormat(string url)
    {
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            var extension = Path.GetExtension(uri.AbsolutePath).TrimStart('.').ToLowerInvariant();
            if (extension is "jpg" or "jpeg" or "png" or "webp" or "avif") return extension;
        }
        return "jpg";
    }

    private static string GuessAudioFormat(string url)
    {
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            var extension = Path.GetExtension(uri.AbsolutePath).TrimStart('.').ToLowerInvariant();
            if (extension is "mp3" or "m4a" or "aac" or "ogg" or "opus") return extension;
        }
        return "m4a";
    }

    private static bool TryGetObject(JsonElement parent, string name, out JsonElement value)
    {
        if (parent.ValueKind == JsonValueKind.Object
            && parent.TryGetProperty(name, out value)
            && value.ValueKind == JsonValueKind.Object) return true;
        value = default;
        return false;
    }

    private static string? GetString(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static double? GetDouble(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value)) return null;
        if (value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var number)) return number;
        return double.TryParse(value.ToString(), System.Globalization.CultureInfo.InvariantCulture, out number) ? number : null;
    }

    private static int? GetInt(JsonElement element, string name)
    {
        var value = GetDouble(element, name);
        return value.HasValue ? (int)Math.Round(value.Value) : null;
    }

    private static long? GetLong(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value)) return null;
        if (value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var number)) return number;
        return long.TryParse(value.ToString(), out number) ? number : null;
    }

    private static HttpClient CreateClient()
    {
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression = DecompressionMethods.All,
            UseProxy = false
        };
        var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(35) };
        client.DefaultRequestHeaders.UserAgent.ParseAdd(MobileUserAgent);
        client.DefaultRequestHeaders.AcceptLanguage.ParseAdd("zh-CN,zh;q=0.9");
        client.DefaultRequestHeaders.Accept.ParseAdd("text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8");
        return client;
    }
}
