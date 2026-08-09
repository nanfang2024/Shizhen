using System.Text.Json;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services;

public sealed class MediaProbeService(ProcessRunner processRunner)
{
    private static readonly string[] VideoExtensions = [".mp4", ".mkv", ".mov", ".webm", ".avi", ".m4v", ".ts"];

    public async Task<LocalMediaInfo> ProbeAsync(string path, CancellationToken cancellationToken = default)
    {
        if (!File.Exists(path)) throw new UserFacingException("选择的文件不存在或已被移动。");
        var extension = Path.GetExtension(path).ToLowerInvariant();
        var isGif = extension == ".gif";
        var isVideo = VideoExtensions.Contains(extension, StringComparer.OrdinalIgnoreCase);
        if (!isGif && !isVideo)
        {
            return new LocalMediaInfo(path, Path.GetFileName(path), extension.TrimStart('.'), new FileInfo(path).Length, null, null, null, false, false);
        }

        var ffprobe = ToolLocator.FindFfprobe() ?? throw new UserFacingException("缺少 FFprobe，无法读取媒体信息。请运行工具安装脚本。");
        var result = await processRunner.RunAsync(ffprobe,
            ["-v", "error", "-print_format", "json", "-show_streams", "-show_format", path],
            cancellationToken: cancellationToken);
        if (result.ExitCode != 0)
        {
            throw new UserFacingException("无法读取这个媒体文件，文件可能损坏或编码不受支持。", result.StandardError);
        }

        using var document = JsonDocument.Parse(result.StandardOutput);
        var root = document.RootElement;
        int? width = null;
        int? height = null;
        double? duration = null;
        if (root.TryGetProperty("streams", out var streams))
        {
            foreach (var stream in streams.EnumerateArray())
            {
                if (stream.TryGetProperty("codec_type", out var codecType) && codecType.GetString() == "video")
                {
                    width = TryGetInt(stream, "width");
                    height = TryGetInt(stream, "height");
                    duration ??= TryGetDouble(stream, "duration");
                    break;
                }
            }
        }
        if (root.TryGetProperty("format", out var format))
        {
            duration ??= TryGetDouble(format, "duration");
        }

        return new LocalMediaInfo(path, Path.GetFileName(path), extension.TrimStart('.'), new FileInfo(path).Length, width, height, duration, isVideo, isGif);
    }

    private static int? TryGetInt(JsonElement element, string name) =>
        element.TryGetProperty(name, out var value) && value.TryGetInt32(out var number) ? number : null;

    private static double? TryGetDouble(JsonElement element, string name)
    {
        if (!element.TryGetProperty(name, out var value)) return null;
        if (value.ValueKind == JsonValueKind.Number && value.TryGetDouble(out var number)) return number;
        return double.TryParse(value.GetString(), System.Globalization.CultureInfo.InvariantCulture, out number) ? number : null;
    }
}
