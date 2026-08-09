using System.Net;
using System.Text.Json;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services.Parsing;

/// <summary>
/// Uses Bilibili's anonymous public metadata and progressive playback endpoints.
/// It deliberately returns only the selected work and never scans avatars or comments.
/// </summary>
public sealed class BilibiliPublicMediaParser : IMediaParser
{
    private const string UserAgent = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136.0.0.0 Safari/537.36";
    private const string Referrer = "https://www.bilibili.com/";
    private readonly DiagnosticLogger _logger;
    private readonly HttpClient _client = CreateClient();

    public BilibiliPublicMediaParser(DiagnosticLogger logger)
    {
        _logger = logger;
    }

    public string Name => "B 站公开作品解析器";

    public bool CanHandle(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        var host = uri.IdnHost;
        return host.Equals("b23.tv", StringComparison.OrdinalIgnoreCase)
               || host.Equals("bilibili.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith(".bilibili.com", StringComparison.OrdinalIgnoreCase);
    }

    public async Task<ParsedMedia> ParseAsync(string url, CancellationToken cancellationToken = default)
    {
        try
        {
            var resolvedUrl = await ResolveUrlAsync(url, cancellationToken);
            var bvid = ExtractBvid(resolvedUrl) ?? ExtractBvid(url)
                ?? throw new UserFacingException("这条 B 站链接没有包含可识别的 BV 号。");
            using var view = await GetJsonAsync($"https://api.bilibili.com/x/web-interface/view?bvid={Uri.EscapeDataString(bvid)}", cancellationToken);
            EnsureApiSuccess(view.RootElement, "B 站作品信息");
            var data = view.RootElement.GetProperty("data");
            var title = GetString(data, "title");
            var cover = NormalizeHttps(GetString(data, "pic"));
            var duration = GetDouble(data, "duration");
            var author = data.TryGetProperty("owner", out var owner) ? GetString(owner, "name") : null;
            var pages = data.GetProperty("pages");
            var requestedPage = GetRequestedPage(resolvedUrl);
            var page = SelectPage(pages, requestedPage);
            var cid = GetLong(page, "cid") ?? throw new JsonException("B 站页面未返回 cid。");
            var dimension = page.TryGetProperty("dimension", out var dimensionNode) ? dimensionNode : default;
            var width = dimension.ValueKind == JsonValueKind.Object ? GetInt(dimension, "width") : null;
            var height = dimension.ValueKind == JsonValueKind.Object ? GetInt(dimension, "height") : null;

            var playUrl = $"https://api.bilibili.com/x/player/playurl?bvid={Uri.EscapeDataString(bvid)}&cid={cid}&fnval=1&fourk=1";
            using var play = await GetJsonAsync($"{playUrl}&qn=127", cancellationToken);
            EnsureApiSuccess(play.RootElement, "B 站公开播放地址");
            var playData = play.RootElement.GetProperty("data");
            var initialQuality = GetInt(playData, "quality");
            var acceptedQualities = GetIntArray(playData, "accept_quality")
                .Append(initialQuality ?? 0)
                .Where(quality => quality > 0)
                .Distinct()
                .OrderByDescending(quality => quality)
                .ToList();
            var qualityDescriptions = GetQualityDescriptions(playData);
            if (acceptedQualities.Count == 0)
            {
                throw new UserFacingException("B 站当前未向匿名用户提供可下载的公开视频。");
            }

            var items = new List<MediaItem>();
            var returnedQualities = new HashSet<int>();
            foreach (var requestedQuality in acceptedQualities)
            {
                JsonDocument? qualityDocument = null;
                try
                {
                    var qualityData = playData;
                    if (requestedQuality != initialQuality)
                    {
                        qualityDocument = await GetJsonAsync($"{playUrl}&qn={requestedQuality}", cancellationToken);
                        EnsureApiSuccess(qualityDocument.RootElement, $"B 站 {QualityLabel(requestedQuality)} 播放地址");
                        qualityData = qualityDocument.RootElement.GetProperty("data");
                    }

                    if (!qualityData.TryGetProperty("durl", out var durls) || durls.ValueKind != JsonValueKind.Array) continue;
                    var actualQuality = GetInt(qualityData, "quality") ?? requestedQuality;
                    returnedQualities.Add(actualQuality);
                    var qualityLabel = qualityDescriptions.GetValueOrDefault(actualQuality) ?? QualityLabel(actualQuality);
                    var (variantWidth, variantHeight) = ScaleDimensions(width, height, actualQuality);
                    var segment = 0;
                    var segmentCount = durls.GetArrayLength();
                    foreach (var durl in durls.EnumerateArray())
                    {
                        var mediaUrl = GetString(durl, "url");
                        if (!Uri.TryCreate(mediaUrl, UriKind.Absolute, out _)) continue;
                        var fileSize = GetLong(durl, "size");
                        var segmentDuration = GetDouble(durl, "length") is { } milliseconds ? milliseconds / 1000d : duration;
                        items.Add(new MediaItem
                        {
                            Id = $"bilibili-video-{actualQuality}-{segment}",
                            Type = MediaType.Video,
                            MediaUrl = mediaUrl!,
                            Format = "mp4",
                            Width = variantWidth,
                            Height = variantHeight,
                            FileSize = fileSize,
                            DurationSeconds = segmentDuration,
                            QualityLabel = segmentCount > 1 ? $"{qualityLabel} · 第 {segment + 1} 段" : qualityLabel,
                            VideoCodec = "h264",
                            AudioCodec = "aac",
                            ThumbnailUrl = cover,
                            ReferrerUrl = Referrer
                        });
                        segment++;
                    }
                }
                finally
                {
                    qualityDocument?.Dispose();
                }
            }

            var primaryVideo = items.FirstOrDefault();
            if (primaryVideo is not null)
            {
                items.Add(new MediaItem
                {
                    Id = "bilibili-audio-from-video",
                    Type = MediaType.Audio,
                    MediaUrl = primaryVideo.MediaUrl,
                    Format = "m4a",
                    DurationSeconds = primaryVideo.DurationSeconds,
                    QualityLabel = "从公开视频提取音频",
                    AudioCodec = "aac",
                    ReferrerUrl = Referrer,
                    ExtractAudio = true
                });
            }

            if (!string.IsNullOrWhiteSpace(cover))
            {
                items.Add(new MediaItem
                {
                    Id = "bilibili-cover",
                    Type = MediaType.Cover,
                    MediaUrl = cover,
                    Format = Path.GetExtension(new Uri(cover).AbsolutePath).TrimStart('.'),
                    QualityLabel = "作品封面",
                    ReferrerUrl = Referrer
                });
            }

            if (items.All(item => item.Type == MediaType.Cover))
            {
                throw new UserFacingException("B 站作品存在，但未获得可下载的公开视频。");
            }

            var highestAnonymousQuality = items
                .Where(item => item.Type == MediaType.Video)
                .Select(item => item.QualityLabel)
                .FirstOrDefault(label => !string.IsNullOrWhiteSpace(label)) ?? "当前清晰度";
            var notice = returnedQualities.Count > 0 && returnedQualities.Max() < 80
                ? $"B站匿名接口实际最高只返回 {highestAnonymousQuality}；1080P、4K 等档位需要登录或会员，本工具不会显示无法下载的选项。"
                : null;

            return new ParsedMedia(resolvedUrl, "哔哩哔哩", title, author, cover, duration, items, Name, notice);
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
            await _logger.WriteAsync("BILIBILI", "B 站公开作品解析失败", exception);
            throw new UserFacingException("暂时无法解析这条 B 站链接，请稍后重试。", exception.Message, exception);
        }
    }

    private async Task<string> ResolveUrlAsync(string url, CancellationToken cancellationToken)
    {
        if (ExtractBvid(url) is not null) return url;
        using var request = CreateRequest(url);
        using var response = await _client.SendAsync(request, HttpCompletionOption.ResponseHeadersRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        return response.RequestMessage?.RequestUri?.AbsoluteUri ?? url;
    }

    private async Task<JsonDocument> GetJsonAsync(string url, CancellationToken cancellationToken)
    {
        using var request = CreateRequest(url);
        using var response = await _client.SendAsync(request, HttpCompletionOption.ResponseContentRead, cancellationToken);
        response.EnsureSuccessStatusCode();
        await using var stream = await response.Content.ReadAsStreamAsync(cancellationToken);
        return await JsonDocument.ParseAsync(stream, cancellationToken: cancellationToken);
    }

    private static HttpRequestMessage CreateRequest(string url)
    {
        var request = new HttpRequestMessage(HttpMethod.Get, url);
        request.Headers.Referrer = new Uri(Referrer);
        return request;
    }

    private static void EnsureApiSuccess(JsonElement root, string operation)
    {
        var code = GetInt(root, "code");
        if (code == 0 && root.TryGetProperty("data", out _)) return;
        var message = GetString(root, "message") ?? "未知错误";
        throw new UserFacingException($"{operation}请求失败：{message}");
    }

    private static JsonElement SelectPage(JsonElement pages, int requestedPage)
    {
        if (pages.ValueKind != JsonValueKind.Array || pages.GetArrayLength() == 0)
            throw new JsonException("B 站作品没有可用分 P。");
        var index = Math.Clamp(requestedPage - 1, 0, pages.GetArrayLength() - 1);
        return pages[index];
    }

    private static int GetRequestedPage(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return 1;
        foreach (var part in uri.Query.TrimStart('?').Split('&', StringSplitOptions.RemoveEmptyEntries))
        {
            var pair = part.Split('=', 2);
            if (pair.Length == 2 && pair[0].Equals("p", StringComparison.OrdinalIgnoreCase)
                                 && int.TryParse(pair[1], out var page) && page > 0) return page;
        }
        return 1;
    }

    private static string? ExtractBvid(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return null;
        return uri.AbsolutePath.Split('/', StringSplitOptions.RemoveEmptyEntries)
            .FirstOrDefault(segment => segment.StartsWith("BV", StringComparison.OrdinalIgnoreCase) && segment.Length >= 10);
    }

    private static string QualityLabel(int? quality) => quality switch
    {
        >= 120 => "4K",
        >= 116 => "1080P 60帧",
        >= 80 => "1080P",
        >= 64 => "720P",
        >= 32 => "480P",
        _ => "360P"
    };

    private static IEnumerable<int> GetIntArray(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var values) || values.ValueKind != JsonValueKind.Array) yield break;
        foreach (var value in values.EnumerateArray())
        {
            if (value.ValueKind == JsonValueKind.Number && value.TryGetInt32(out var number)) yield return number;
        }
    }

    private static IReadOnlyDictionary<int, string> GetQualityDescriptions(JsonElement playData)
    {
        var qualities = GetIntArray(playData, "accept_quality").ToList();
        if (!playData.TryGetProperty("accept_description", out var descriptions)
            || descriptions.ValueKind != JsonValueKind.Array) return new Dictionary<int, string>();
        var result = new Dictionary<int, string>();
        var index = 0;
        foreach (var description in descriptions.EnumerateArray())
        {
            if (index >= qualities.Count) break;
            if (description.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(description.GetString()))
            {
                result[qualities[index]] = description.GetString()!;
            }
            index++;
        }
        return result;
    }

    private static (int? Width, int? Height) ScaleDimensions(int? width, int? height, int quality)
    {
        if (width is not > 0 || height is not > 0) return (width, height);
        var targetHeight = quality switch
        {
            >= 120 => 2160,
            >= 80 => 1080,
            >= 64 => 720,
            >= 32 => 480,
            _ => 360
        };
        if (height <= targetHeight) return (width, height);
        var scaledWidth = (int)Math.Round(width.Value * targetHeight / (double)height.Value);
        return (scaledWidth, targetHeight);
    }

    private static string? NormalizeHttps(string? url) => url?.StartsWith("http://", StringComparison.OrdinalIgnoreCase) == true
        ? "https://" + url[7..]
        : url;

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
        var number = GetDouble(element, name);
        return number.HasValue ? (int)Math.Round(number.Value) : null;
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
        client.DefaultRequestHeaders.UserAgent.ParseAdd(UserAgent);
        client.DefaultRequestHeaders.AcceptLanguage.ParseAdd("zh-CN,zh;q=0.9");
        client.DefaultRequestHeaders.Accept.ParseAdd("application/json,text/html;q=0.9,*/*;q=0.8");
        return client;
    }
}
