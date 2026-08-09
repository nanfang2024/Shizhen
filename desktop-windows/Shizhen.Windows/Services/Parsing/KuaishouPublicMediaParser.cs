using System.Text.Json;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services.Parsing;

/// <summary>
/// Resolves one Kuaishou share link and asks Kuaishou's anonymous current-work APIs for
/// that photo id. It does not inspect feeds, comments, avatars, or browser login data.
/// </summary>
public sealed class KuaishouPublicMediaParser(ProcessRunner processRunner, DiagnosticLogger logger) : IMediaParser
{
    private const string Referrer = "https://www.kuaishou.com/";

    public string Name => "快手当前公开作品解析器";

    public bool CanHandle(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        var host = uri.IdnHost;
        return host.Equals("kuaishou.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith(".kuaishou.com", StringComparison.OrdinalIgnoreCase)
               || host.Equals("chenzhongtech.com", StringComparison.OrdinalIgnoreCase)
               || host.EndsWith(".chenzhongtech.com", StringComparison.OrdinalIgnoreCase);
    }

    public async Task<ParsedMedia> ParseAsync(string url, CancellationToken cancellationToken = default)
    {
        var deno = ToolLocator.FindDeno()
            ?? throw new UserFacingException("快手解析组件不完整，请重新安装完整版本。");
        var result = await processRunner.RunAsync(
            deno,
            ["eval", DenoScript, url],
            cancellationToken: cancellationToken);
        if (result.ExitCode != 0 || string.IsNullOrWhiteSpace(result.StandardOutput))
        {
            await logger.WriteAsync("KUAISHOU", "快手当前作品公开接口请求失败。", new InvalidOperationException(result.StandardError));
            throw new UserFacingException("快手公开作品暂时无法访问，请检查网络后重试。", result.StandardError);
        }

        try
        {
            using var document = JsonDocument.Parse(result.StandardOutput);
            var root = document.RootElement;
            if (!GetBoolean(root, "ok"))
            {
                var kind = GetString(root, "kind");
                var details = GetString(root, "details") ?? result.StandardError;
                if (kind == "node")
                {
                    throw new UserFacingException("快手触发了平台访问验证，请更换节点重试。", details);
                }
                if (kind == "restricted")
                {
                    throw new UserFacingException("该资源需要登录或存在访问限制，本工具不支持提取。", details);
                }
                throw new UserFacingException("暂时无法解析这条快手链接，可能是作品已失效或平台规则发生变化。", details);
            }
            return BuildParsedMedia(url, root);
        }
        catch (JsonException exception)
        {
            await logger.WriteAsync("KUAISHOU", "快手公开响应格式无法识别。", exception);
            throw new UserFacingException("快手返回了无法识别的公开数据，请更新到最新版本后重试。", exception.Message, exception);
        }
    }

    internal static ParsedMedia BuildParsedMedia(string sourceUrl, JsonElement root)
    {
        if (!TryGetObject(root, "payload", out var payload))
            throw new UserFacingException("快手公开响应中没有当前作品数据。");

        var sourceKind = GetString(root, "source");
        var resolvedUrl = GetString(root, "finalUrl") ?? sourceUrl;
        var photoId = GetString(root, "photoId") ?? "unknown";
        JsonElement photo;
        JsonElement author = default;
        if (sourceKind == "graphql")
        {
            if (!TryGetObject(payload, "photo", out photo))
                throw new UserFacingException("快手当前作品没有公开媒体信息。");
            _ = TryGetObject(payload, "author", out author);
        }
        else
        {
            photo = payload;
            _ = TryGetObject(root, "author", out author);
        }

        var title = GetFirstString(photo, "caption", "title", "description") ?? "快手作品";
        var authorName = GetFirstString(author, "name", "user_name", "userName", "nickname")
            ?? GetFirstString(photo, "user_name", "userName", "authorName");
        var duration = GetFirstDouble(photo, "duration", "durationMs", "videoDuration");
        if (duration is > 1000) duration /= 1000d;
        var cover = GetFirstString(photo, "coverUrl", "cover_url", "poster")
            ?? GetFirstUrlFromProperty(photo, "coverUrls", "cover_urls");
        var items = new List<MediaItem>();

        AddManifestVideos(photo, duration, cover, resolvedUrl, items);
        AddVideoUrls(photo, duration, cover, resolvedUrl, items);
        AddAtlasImages(photo, resolvedUrl, items);

        items = items
            .Where(item => Uri.TryCreate(item.MediaUrl, UriKind.Absolute, out _))
            .GroupBy(item => $"{item.Type}|{item.MediaUrl}", StringComparer.OrdinalIgnoreCase)
            .Select(group => group.First())
            .ToList();
        var videoWithAudio = items.FirstOrDefault(item => item.Type == MediaType.Video
            && !string.Equals(item.AudioCodec, "none", StringComparison.OrdinalIgnoreCase));
        if (videoWithAudio is not null)
        {
            items.Add(new MediaItem
            {
                Id = "kuaishou-audio-from-video",
                Type = MediaType.Audio,
                MediaUrl = videoWithAudio.MediaUrl,
                Format = "m4a",
                DurationSeconds = videoWithAudio.DurationSeconds,
                QualityLabel = "从当前作品视频提取音频",
                AudioCodec = videoWithAudio.AudioCodec,
                ThumbnailUrl = cover,
                ReferrerUrl = resolvedUrl,
                ExtractAudio = true
            });
        }

        if (!string.IsNullOrWhiteSpace(cover))
        {
            items.Add(new MediaItem
            {
                Id = "kuaishou-cover",
                Type = MediaType.Cover,
                MediaUrl = cover,
                Format = GuessFormat(cover, "jpg"),
                QualityLabel = "作品封面",
                ReferrerUrl = resolvedUrl
            });
        }
        if (items.All(item => item.Type == MediaType.Cover) || items.Count == 0)
        {
            throw new UserFacingException("快手当前作品没有向匿名访问者公开可下载的媒体。");
        }

        return new ParsedMedia(
            resolvedUrl,
            "快手",
            title,
            authorName,
            cover,
            duration,
            items,
            $"Kuaishou public work {photoId}");
    }

    private static void AddManifestVideos(
        JsonElement photo,
        double? duration,
        string? cover,
        string referrerUrl,
        ICollection<MediaItem> items)
    {
        if (!TryGetObject(photo, "manifest", out var manifest)
            || !TryGetArray(manifest, "adaptationSet", out var sets)) return;
        foreach (var set in sets.EnumerateArray())
        {
            if (!TryGetArray(set, "representation", out var representations)) continue;
            foreach (var representation in representations.EnumerateArray())
            {
                var mediaUrl = GetString(representation, "url")
                    ?? GetFirstUrlFromProperty(representation, "backupUrl", "backupUrls");
                if (!Uri.TryCreate(mediaUrl, UriKind.Absolute, out _)) continue;
                var width = GetInt(representation, "width");
                var height = GetInt(representation, "height");
                var quality = GetString(representation, "qualityLabel")
                    ?? (height.HasValue ? $"{height}P" : "公开视频");
                items.Add(CreateVideoItem(
                    $"kuaishou-manifest-{items.Count}", mediaUrl!, width, height, duration, quality, cover, referrerUrl));
            }
        }
    }

    private static void AddVideoUrls(
        JsonElement photo,
        double? duration,
        string? cover,
        string referrerUrl,
        ICollection<MediaItem> items)
    {
        foreach (var property in new[]
                 {
                     "photoUrl", "photo_url", "mainMvUrls", "main_mv_urls", "videoResource",
                     "croppedPhotoUrl", "cropped_photo_url"
                 })
        {
            if (!photo.TryGetProperty(property, out var value)) continue;
            foreach (var mediaUrl in EnumerateAbsoluteUrls(value)
                         .Where(IsProbableVideoUrl))
            {
                items.Add(CreateVideoItem(
                    $"kuaishou-video-{items.Count}", mediaUrl, null, null, duration, "公开视频", cover, referrerUrl));
            }
        }
    }

    private static void AddAtlasImages(JsonElement photo, string referrerUrl, ICollection<MediaItem> items)
    {
        JsonElement atlas = default;
        JsonDocument? parsedAtlas = null;
        try
        {
            if (!TryGetObject(photo, "atlas", out atlas)
                && TryGetObject(photo, "ext_params", out var extension))
            {
                if (!TryGetObject(extension, "atlas", out atlas)
                    && extension.TryGetProperty("atlas", out var serialized)
                    && serialized.ValueKind == JsonValueKind.String)
                {
                    parsedAtlas = JsonDocument.Parse(serialized.GetString()!);
                    atlas = parsedAtlas.RootElement;
                }
            }
            if (atlas.ValueKind != JsonValueKind.Object) return;

            var cdn = GetStringValues(atlas, "cdn", "cdns", "hosts")
                .Select(NormalizeCdn)
                .FirstOrDefault(value => value is not null);
            var imageIndex = 0;
            foreach (var value in GetStringValues(atlas, "list", "urls", "imageUrls", "images"))
            {
                var imageUrl = Uri.TryCreate(value, UriKind.Absolute, out var absolute)
                    ? absolute.AbsoluteUri
                    : CombineCdn(cdn, value);
                if (!Uri.TryCreate(imageUrl, UriKind.Absolute, out _)) continue;
                items.Add(new MediaItem
                {
                    Id = $"kuaishou-image-{imageIndex++}",
                    Type = MediaType.Image,
                    MediaUrl = imageUrl!,
                    Format = GuessFormat(imageUrl!, "jpg"),
                    QualityLabel = "图集原图",
                    ReferrerUrl = referrerUrl
                });
            }
        }
        finally
        {
            parsedAtlas?.Dispose();
        }
    }

    private static MediaItem CreateVideoItem(
        string id,
        string url,
        int? width,
        int? height,
        double? duration,
        string quality,
        string? cover,
        string referrerUrl) => new()
    {
        Id = id,
        Type = MediaType.Video,
        MediaUrl = url,
        Format = GuessFormat(url, "mp4"),
        Width = width,
        Height = height,
        DurationSeconds = duration,
        QualityLabel = quality,
        VideoCodec = "h264",
        AudioCodec = "aac",
        ThumbnailUrl = cover,
        ReferrerUrl = referrerUrl
    };

    private static IEnumerable<string> EnumerateAbsoluteUrls(JsonElement element)
    {
        if (element.ValueKind == JsonValueKind.String)
        {
            var text = element.GetString();
            if (Uri.TryCreate(text, UriKind.Absolute, out _)) yield return text!;
            yield break;
        }
        if (element.ValueKind == JsonValueKind.Array)
        {
            foreach (var child in element.EnumerateArray())
            foreach (var url in EnumerateAbsoluteUrls(child)) yield return url;
            yield break;
        }
        if (element.ValueKind != JsonValueKind.Object) yield break;
        foreach (var property in element.EnumerateObject())
        foreach (var url in EnumerateAbsoluteUrls(property.Value)) yield return url;
    }

    private static bool IsProbableVideoUrl(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri)) return false;
        var extension = Path.GetExtension(uri.AbsolutePath).ToLowerInvariant();
        return extension is ".mp4" or ".m3u8" or ".webm" or ".mov"
               || uri.AbsolutePath.Contains("play", StringComparison.OrdinalIgnoreCase)
               || uri.Host.Contains("video", StringComparison.OrdinalIgnoreCase);
    }

    private static IEnumerable<string> GetStringValues(JsonElement parent, params string[] names)
    {
        foreach (var name in names)
        {
            if (!parent.TryGetProperty(name, out var value)) continue;
            if (value.ValueKind == JsonValueKind.String)
            {
                var text = value.GetString();
                if (!string.IsNullOrWhiteSpace(text)) yield return text;
            }
            else if (value.ValueKind == JsonValueKind.Array)
            {
                foreach (var child in value.EnumerateArray())
                {
                    if (child.ValueKind == JsonValueKind.String && !string.IsNullOrWhiteSpace(child.GetString()))
                        yield return child.GetString()!;
                    else if (child.ValueKind == JsonValueKind.Object)
                    {
                        var url = GetFirstString(child, "url", "src", "path");
                        if (!string.IsNullOrWhiteSpace(url)) yield return url;
                    }
                }
            }
        }
    }

    private static string? GetFirstUrlFromProperty(JsonElement parent, params string[] names)
    {
        foreach (var name in names)
        {
            if (!parent.TryGetProperty(name, out var value)) continue;
            var url = EnumerateAbsoluteUrls(value).FirstOrDefault();
            if (url is not null) return url;
        }
        return null;
    }

    private static string? GetFirstString(JsonElement element, params string[] names)
    {
        foreach (var name in names)
        {
            var value = GetString(element, name);
            if (!string.IsNullOrWhiteSpace(value)) return value;
        }
        return null;
    }

    private static double? GetFirstDouble(JsonElement element, params string[] names)
    {
        foreach (var name in names)
        {
            if (element.TryGetProperty(name, out var value)
                && value.ValueKind == JsonValueKind.Number
                && value.TryGetDouble(out var number)) return number;
        }
        return null;
    }

    private static bool TryGetObject(JsonElement parent, string name, out JsonElement value)
    {
        if (parent.ValueKind == JsonValueKind.Object
            && parent.TryGetProperty(name, out value)
            && value.ValueKind == JsonValueKind.Object) return true;
        value = default;
        return false;
    }

    private static bool TryGetArray(JsonElement parent, string name, out JsonElement value)
    {
        if (parent.ValueKind == JsonValueKind.Object
            && parent.TryGetProperty(name, out value)
            && value.ValueKind == JsonValueKind.Array) return true;
        value = default;
        return false;
    }

    private static bool GetBoolean(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.ValueKind == JsonValueKind.True;

    private static string? GetString(JsonElement element, string name) =>
        element.ValueKind == JsonValueKind.Object
        && element.TryGetProperty(name, out var value)
        && value.ValueKind == JsonValueKind.String ? value.GetString() : null;

    private static int? GetInt(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value)
        && value.ValueKind == JsonValueKind.Number
        && value.TryGetInt32(out var number) ? number : null;

    private static string GuessFormat(string url, string fallback)
    {
        if (Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            var extension = Path.GetExtension(uri.AbsolutePath).TrimStart('.').ToLowerInvariant();
            if (!string.IsNullOrWhiteSpace(extension) && extension.Length <= 5) return extension;
        }
        return fallback;
    }

    private static string? NormalizeCdn(string value)
    {
        if (string.IsNullOrWhiteSpace(value)) return null;
        if (Uri.TryCreate(value, UriKind.Absolute, out var uri)) return uri.AbsoluteUri.TrimEnd('/');
        return Uri.TryCreate("https://" + value.TrimStart('/'), UriKind.Absolute, out uri)
            ? uri.AbsoluteUri.TrimEnd('/')
            : null;
    }

    private static string? CombineCdn(string? cdn, string path)
    {
        if (cdn is null || string.IsNullOrWhiteSpace(path)) return null;
        return $"{cdn.TrimEnd('/')}/{path.TrimStart('/')}";
    }

    private const string DenoScript = """
        (async () => {
          const input = Deno.args[0];
          const desktopUa = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136 Safari/537.36';
          const mobileUa = 'Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/136 Mobile Safari/537.36';
          const jar = new Map();
          const absorb = headers => {
            for (const cookie of headers.getSetCookie()) {
              const pair = cookie.split(';', 1)[0];
              const separator = pair.indexOf('=');
              if (separator > 0) jar.set(pair.slice(0, separator), pair.slice(separator + 1));
            }
          };
          const cookies = () => [...jar].map(([key, value]) => `${key}=${value}`).join('; ');
          const readAssignedJson = (html, marker) => {
            const markerIndex = html.indexOf(marker);
            if (markerIndex < 0) return null;
            const assignmentIndex = html.indexOf('=', markerIndex + marker.length);
            const start = html.indexOf('{', assignmentIndex + 1);
            if (assignmentIndex < 0 || start < 0) return null;
            let depth = 0;
            let quoted = false;
            let escaped = false;
            for (let index = start; index < html.length; index++) {
              const character = html[index];
              if (quoted) {
                if (escaped) escaped = false;
                else if (character === '\\') escaped = true;
                else if (character === '"') quoted = false;
                continue;
              }
              if (character === '"') { quoted = true; continue; }
              if (character === '{' || character === '[') depth++;
              else if (character === '}' || character === ']') {
                depth--;
                if (depth === 0) {
                  try { return JSON.parse(html.slice(start, index + 1)); } catch { return null; }
                }
              }
            }
            return null;
          };

          const pageResponse = await fetch(input, {
            redirect: 'follow',
            headers: {
              'user-agent': mobileUa,
              accept: 'text/html,application/xhtml+xml,application/json;q=0.9,*/*;q=0.8',
              'accept-language': 'zh-CN,zh;q=0.9'
            }
          });
          absorb(pageResponse.headers);
          const pageHtml = await pageResponse.text();
          const finalUrl = pageResponse.url;
          const state = readAssignedJson(pageHtml, 'window.INIT_STATE');
          const pagePayload = state && (
            (state.photo && typeof state.photo === 'object' ? state : null) ||
            Object.values(state).find(value => value && typeof value === 'object' && value.photo && typeof value.photo === 'object')
          );
          if (pagePayload && pagePayload.photo) {
            const pagePhotoId = pagePayload.photo.photoId || pagePayload.photo.id || 'unknown';
            console.log(JSON.stringify({
              ok: true,
              source: 'page',
              finalUrl,
              photoId: String(pagePhotoId),
              payload: pagePayload.photo,
              author: pagePayload.user || pagePayload.author || {}
            }));
            return;
          }

          const parsed = new URL(finalUrl);
          const match = parsed.pathname.match(/\/(?:short-video|fw\/photo)\/([^/?]+)/i);
          const photoId = parsed.searchParams.get('photoId') || (match && match[1]);
          if (!photoId) {
            console.log(JSON.stringify({ ok: false, kind: 'unavailable', details: 'missing photo id' }));
            return;
          }

          const restUrl = new URL('https://v.m.chenzhongtech.com/rest/wd/photo/info');
          restUrl.searchParams.set('photoId', photoId);
          for (const key of ['shareToken', 'shareId', 'shareObjectId']) {
            const value = parsed.searchParams.get(key);
            if (value) restUrl.searchParams.set(key, value);
          }
          const restResponse = await fetch(restUrl, {
            headers: {
              'user-agent': mobileUa, accept: 'application/json,text/plain,*/*', cookie: cookies(),
              referer: finalUrl, 'x-requested-with': 'com.smile.gifmaker', kpn: 'KUAISHOU', kpf: 'ANDROID_PHONE'
            }
          });
          absorb(restResponse.headers);
          let rest = null;
          try { rest = await restResponse.json(); } catch {}
          if (rest && rest.photo) {
            console.log(JSON.stringify({ ok: true, source: 'rest', finalUrl, photoId, payload: rest.photo, author: rest.user || rest.author || {} }));
            return;
          }

          jar.set('kpf', 'PC_WEB'); jar.set('kpn', 'KUAISHOU_VISION'); jar.set('clientid', '3');
          const query = `query visionVideoDetail($photoId: String, $type: String, $page: String, $webPageArea: String) {
            visionVideoDetail(photoId: $photoId, type: $type, page: $page, webPageArea: $webPageArea) {
              status type author { id name headerUrl }
              photo { id duration caption coverUrl photoUrl timestamp videoRatio musicBlocked
                manifest { mediaType businessType version adaptationSet { id duration representation {
                  id defaultSelect backupUrl codecs url height width avgBitrate maxBitrate qualityType qualityLabel frameRate hidden disableAdaptive
                } } }
                manifestH265 photoH265Url croppedPhotoUrl videoResource
              }
              llsid
            }
          }`;
          const graphResponse = await fetch('https://www.kuaishou.com/graphql', {
            method: 'POST',
            headers: {
              'user-agent': desktopUa, accept: 'application/json,*/*', 'content-type': 'application/json',
              origin: 'https://www.kuaishou.com', referer: finalUrl, cookie: cookies()
            },
            body: JSON.stringify({ operationName: 'visionVideoDetail', variables: { photoId, page: 'detail', webPageArea: 'detail' }, query })
          });
          let graph = null;
          try { graph = await graphResponse.json(); } catch {}
          const detail = graph && graph.data && graph.data.visionVideoDetail;
          if (detail && detail.photo) {
            console.log(JSON.stringify({ ok: true, source: 'graphql', finalUrl, photoId, payload: detail }));
            return;
          }
          const restCode = rest && rest.result;
          const graphCode = graph && (graph.result || (graph.data && graph.data.result));
          const nodeBlocked = restCode === 2 || restCode === 2001 || graphCode === 400002 || graphCode === 2;
          const restricted = detail && detail.status && detail.status !== 1;
          console.log(JSON.stringify({
            ok: false,
            kind: nodeBlocked ? 'node' : (restricted ? 'restricted' : 'unavailable'),
            details: `rest=${restCode ?? 'none'}; graph=${graphCode ?? 'none'}; status=${detail && detail.status || 'none'}`
          }));
        })().catch(error => { console.error(error && error.stack || String(error)); Deno.exit(1); });
        """;
}
