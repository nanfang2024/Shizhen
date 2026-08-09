using System.Net;
using System.Text.Json;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services.Parsing;

/// <summary>
/// Reads only the requested public X post through X's syndication response. It does not
/// inspect timelines, replies, avatars or quoted-post media.
/// </summary>
public sealed class XPublicPostParser(ProcessRunner processRunner, DiagnosticLogger logger) : IMediaParser
{
    private const string Referrer = "https://x.com/";
    private readonly HttpClient _client = CreateClient();

    public string Name => "X 当前公开帖子解析器";

    public bool CanHandle(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        var host = uri.IdnHost;
        return host.Equals("x.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith(".x.com", StringComparison.OrdinalIgnoreCase)
               || host.Equals("twitter.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith(".twitter.com", StringComparison.OrdinalIgnoreCase)
               || host.Equals("t.co", StringComparison.OrdinalIgnoreCase);
    }

    public async Task<ParsedMedia> ParseAsync(string url, CancellationToken cancellationToken = default)
    {
        try
        {
            var resolvedUrl = await ResolveUrlAsync(url, cancellationToken);
            var statusId = ExtractStatusId(resolvedUrl) ?? ExtractStatusId(url)
                ?? throw new UserFacingException("这条 X 链接没有包含可识别的帖子 ID。");
            var token = await GenerateSyndicationTokenAsync(statusId, cancellationToken);
            using var request = new HttpRequestMessage(HttpMethod.Get,
                $"https://cdn.syndication.twimg.com/tweet-result?id={statusId}&token={Uri.EscapeDataString(token)}&lang=zh-cn");
            request.Headers.UserAgent.ParseAdd("Googlebot");
            using var response = await _client.SendAsync(request, HttpCompletionOption.ResponseContentRead, cancellationToken);
            response.EnsureSuccessStatusCode();
            await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
            using var document = await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
            return BuildParsedMedia(resolvedUrl, statusId, document.RootElement);
        }
        catch (OperationCanceledException)
        {
            throw;
        }
        catch (UserFacingException)
        {
            throw;
        }
        catch (Exception exception) when (exception is HttpRequestException or JsonException or InvalidOperationException)
        {
            await logger.WriteAsync("X_PUBLIC", "X 当前公开帖子解析失败", exception);
            throw new UserFacingException("暂时无法读取这条 X 公开帖子，请稍后重试。", exception.Message, exception);
        }
    }

    internal static ParsedMedia BuildParsedMedia(string sourceUrl, string statusId, JsonElement root)
    {
        var title = GetString(root, "text");
        var author = root.TryGetProperty("user", out var user) ? GetString(user, "name") : null;
        var items = new List<MediaItem>();
        string? thumbnail = null;

        if (root.TryGetProperty("photos", out var photos) && photos.ValueKind == JsonValueKind.Array)
        {
            var photoIndex = 0;
            foreach (var photo in photos.EnumerateArray())
            {
                var photoUrl = GetString(photo, "url");
                if (!Uri.TryCreate(photoUrl, UriKind.Absolute, out _)) continue;
                thumbnail ??= photoUrl;
                items.Add(new MediaItem
                {
                    Id = $"x-photo-{photoIndex++}",
                    Type = MediaType.Image,
                    MediaUrl = photoUrl!,
                    Format = GuessFormat(photoUrl!, "jpg"),
                    Width = GetInt(photo, "width"),
                    Height = GetInt(photo, "height"),
                    QualityLabel = "帖子原图",
                    ReferrerUrl = Referrer
                });
            }
        }

        if (root.TryGetProperty("mediaDetails", out var mediaDetails) && mediaDetails.ValueKind == JsonValueKind.Array)
        {
            foreach (var detail in mediaDetails.EnumerateArray())
            {
                var mediaKind = GetString(detail, "type");
                if (mediaKind == "photo" || !detail.TryGetProperty("video_info", out var videoInfo)) continue;
                double? duration = GetDouble(videoInfo, "duration_millis") is { } milliseconds ? milliseconds / 1000d : null;
                var width = detail.TryGetProperty("original_info", out var originalInfo) ? GetInt(originalInfo, "width") : null;
                var height = detail.TryGetProperty("original_info", out originalInfo) ? GetInt(originalInfo, "height") : null;
                thumbnail ??= GetString(detail, "media_url_https");
                if (!videoInfo.TryGetProperty("variants", out var variants) || variants.ValueKind != JsonValueKind.Array) continue;
                foreach (var variant in variants.EnumerateArray()
                             .Where(value => string.Equals(GetString(value, "content_type"), "video/mp4", StringComparison.OrdinalIgnoreCase))
                             .OrderByDescending(value => GetLong(value, "bitrate") ?? 0))
                {
                    var mediaUrl = GetString(variant, "url");
                    if (!Uri.TryCreate(mediaUrl, UriKind.Absolute, out _)) continue;
                    var bitrate = GetLong(variant, "bitrate");
                    items.Add(new MediaItem
                    {
                        Id = $"x-video-{items.Count}",
                        Type = MediaType.Video,
                        MediaUrl = mediaUrl!,
                        Format = "mp4",
                        Width = width,
                        Height = height,
                        DurationSeconds = duration,
                        QualityLabel = bitrate is > 0 ? $"{bitrate.Value / 1000} kbps" : "帖子视频",
                        VideoCodec = "h264",
                        AudioCodec = mediaKind == "animated_gif" ? "none" : "aac",
                        ThumbnailUrl = thumbnail,
                        ReferrerUrl = Referrer
                    });
                }
            }
        }

        items = items.GroupBy(item => item.MediaUrl, StringComparer.OrdinalIgnoreCase)
            .Select(group => group.First()).ToList();
        var videoWithAudio = items.FirstOrDefault(item => item.Type == MediaType.Video && item.AudioCodec == "aac");
        if (videoWithAudio is not null)
        {
            items.Add(new MediaItem
            {
                Id = "x-audio-from-video",
                Type = MediaType.Audio,
                MediaUrl = videoWithAudio.MediaUrl,
                Format = "m4a",
                DurationSeconds = videoWithAudio.DurationSeconds,
                QualityLabel = "从帖子视频提取音频",
                AudioCodec = "aac",
                ReferrerUrl = Referrer,
                ExtractAudio = true
            });
        }

        if (items.Count == 0)
        {
            throw new UserFacingException("这条 X 帖子没有公开图片或视频，或媒体已经失效。");
        }
        return new ParsedMedia(sourceUrl, "X / Twitter", title, author, thumbnail, null, items, $"X syndication {statusId}");
    }

    private async Task<string> GenerateSyndicationTokenAsync(string statusId, CancellationToken cancellationToken)
    {
        var deno = ToolLocator.FindDeno()
            ?? throw new UserFacingException("X 公开帖子解析组件不完整，请重新安装完整版本。");
        var script = $"console.log(((Number('{statusId}')/1e15)*Math.PI).toString(36).replace(/(0+|\\.)/g,''))";
        var result = await processRunner.RunAsync(deno, ["eval", script], cancellationToken: cancellationToken);
        var token = result.StandardOutput.Trim();
        if (result.ExitCode != 0 || string.IsNullOrWhiteSpace(token))
            throw new InvalidOperationException("无法生成 X 公开响应令牌。");
        return token;
    }

    private async Task<string> ResolveUrlAsync(string url, CancellationToken cancellationToken)
    {
        if (ExtractStatusId(url) is not null) return url;
        using var response = await _client.GetAsync(url, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        return response.RequestMessage?.RequestUri?.AbsoluteUri ?? url;
    }

    private static string? ExtractStatusId(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return null;
        var parts = uri.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries);
        for (var index = 0; index < parts.Length - 1; index++)
        {
            if ((parts[index].Equals("status", StringComparison.OrdinalIgnoreCase)
                 || parts[index].Equals("statuses", StringComparison.OrdinalIgnoreCase))
                && parts[index + 1].All(char.IsDigit)) return parts[index + 1];
        }
        return null;
    }

    private static string GuessFormat(string url, string fallback)
    {
        var extension = Uri.TryCreate(url, UriKind.Absolute, out var uri)
            ? Path.GetExtension(uri.AbsolutePath).TrimStart('.').ToLowerInvariant()
            : string.Empty;
        return extension is "jpg" or "jpeg" or "png" or "webp" ? extension : fallback;
    }

    private static string? GetString(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object && element.TryGetProperty(name, out var value)
        && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static int? GetInt(JsonElement element, string name)
    {
        var value = GetLong(element, name);
        return value is >= int.MinValue and <= int.MaxValue ? (int)value.Value : null;
    }

    private static long? GetLong(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(name, out var value)) return null;
        return value.ValueKind == JsonValueKind.Number && value.TryGetInt64(out var number) ? number : null;
    }

    private static double? GetDouble(JsonElement element, string name)
    {
        if (element.ValueKind != JsonValueKind.Object || !element.TryGetProperty(name, out var value)) return null;
        return value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var number) ? number : null;
    }

    private static HttpClient CreateClient()
    {
        var handler = new HttpClientHandler
        {
            AllowAutoRedirect = true,
            AutomaticDecompression = DecompressionMethods.All
        };
        var client = new HttpClient(handler) { Timeout = TimeSpan.FromSeconds(35) };
        client.DefaultRequestHeaders.Accept.ParseAdd("application/json,text/html;q=0.9,*/*;q=0.8");
        return client;
    }
}
