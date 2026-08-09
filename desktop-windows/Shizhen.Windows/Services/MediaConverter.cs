using System.Globalization;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services;

public interface IMediaConverter
{
    Task<ConversionResult> VideoToGifAsync(LocalMediaInfo input, VideoToGifOptions options, IProgress<OperationProgress>? progress, CancellationToken cancellationToken);
    Task<ConversionResult> GifToMp4Async(LocalMediaInfo input, GifToMp4Options options, IProgress<OperationProgress>? progress, CancellationToken cancellationToken);
    Task<ConversionResult> ExtractFramesAsync(LocalMediaInfo input, ExtractFramesOptions options, IProgress<OperationProgress>? progress, CancellationToken cancellationToken);
}

public sealed class FfmpegMediaConverter(ProcessRunner processRunner, DiagnosticLogger logger) : IMediaConverter
{
    public async Task<ConversionResult> VideoToGifAsync(
        LocalMediaInfo input,
        VideoToGifOptions options,
        IProgress<OperationProgress>? progress,
        CancellationToken cancellationToken)
    {
        if (!input.IsVideo) return new ConversionResult(false, null, "请选择视频文件。");
        if (options.StartSeconds < 0 || options.EndSeconds <= options.StartSeconds)
            return new ConversionResult(false, null, "结束时间必须大于开始时间。");
        if (input.DurationSeconds.HasValue && options.EndSeconds > input.DurationSeconds.Value + 0.2)
            return new ConversionResult(false, null, "结束时间超出了视频时长。");

        var ffmpeg = RequireFfmpeg();
        Directory.CreateDirectory(options.OutputFolder);
        var output = GetUniquePath(options.OutputFolder, FileNameSanitizer.Sanitize(options.OutputName, "video_to_gif") + ".gif");
        var duration = options.EndSeconds - options.StartSeconds;
        var scale = options.OutputWidth.HasValue ? $"scale={options.OutputWidth}:-2:flags=lanczos" : "scale=iw:ih:flags=lanczos";
        var filter = $"fps={options.FramesPerSecond},{scale},split[s0][s1];[s0]palettegen=max_colors=256[p];[s1][p]paletteuse=dither=sierra2_4a";
        var args = new List<string>
        {
            "-hide_banner", "-y",
            "-ss", FormatSeconds(options.StartSeconds),
            "-t", FormatSeconds(duration),
            "-i", input.Path,
            "-filter_complex", filter,
            "-loop", options.Loop ? "0" : "-1",
            "-progress", "pipe:1", "-nostats",
            output
        };
        return await RunConversionAsync(ffmpeg, args, output, duration, progress, cancellationToken);
    }

    public async Task<ConversionResult> GifToMp4Async(
        LocalMediaInfo input,
        GifToMp4Options options,
        IProgress<OperationProgress>? progress,
        CancellationToken cancellationToken)
    {
        if (!input.IsGif) return new ConversionResult(false, null, "请选择 GIF 文件。");
        var ffmpeg = RequireFfmpeg();
        Directory.CreateDirectory(options.OutputFolder);
        var output = GetUniquePath(options.OutputFolder, FileNameSanitizer.Sanitize(options.OutputName, "gif_to_mp4") + ".mp4");
        var args = new List<string>
        {
            "-hide_banner", "-y", "-i", input.Path,
            "-vf", "scale=trunc(iw/2)*2:trunc(ih/2)*2:flags=lanczos",
            "-c:v", "libx264", "-preset", "medium", "-crf", "20",
            "-pix_fmt", "yuv420p", "-movflags", "+faststart", "-an",
            "-progress", "pipe:1", "-nostats",
            output
        };
        return await RunConversionAsync(ffmpeg, args, output, input.DurationSeconds ?? 0, progress, cancellationToken);
    }

    public async Task<ConversionResult> ExtractFramesAsync(
        LocalMediaInfo input,
        ExtractFramesOptions options,
        IProgress<OperationProgress>? progress,
        CancellationToken cancellationToken)
    {
        if (!input.IsVideo) return new ConversionResult(false, null, "请选择视频文件。");
        if (options.IntervalSeconds <= 0) return new ConversionResult(false, null, "提取间隔必须大于 0 秒。");
        var ffmpeg = RequireFfmpeg();
        var format = options.ImageFormat.Equals("PNG", StringComparison.OrdinalIgnoreCase) ? "png" : "jpg";
        var outputDirectory = GetUniqueDirectory(options.OutputFolder, FileNameSanitizer.Sanitize(Path.GetFileNameWithoutExtension(input.FileName)) + "_frames");
        Directory.CreateDirectory(outputDirectory);
        var pattern = FileNameSanitizer.Sanitize(options.FileNamePattern, "frame");
        var outputPattern = Path.Combine(outputDirectory, $"{pattern}_%05d.{format}");
        var args = new List<string>
        {
            "-hide_banner", "-y", "-i", input.Path,
            "-vf", $"fps=1/{FormatSeconds(options.IntervalSeconds)}"
        };
        if (format == "jpg")
        {
            args.AddRange(["-q:v", "2"]);
        }
        args.AddRange(["-progress", "pipe:1", "-nostats", outputPattern]);
        var result = await RunConversionAsync(ffmpeg, args, outputDirectory, input.DurationSeconds ?? 0, progress, cancellationToken, expectDirectory: true);
        return result;
    }

    private async Task<ConversionResult> RunConversionAsync(
        string executable,
        IReadOnlyList<string> arguments,
        string output,
        double durationSeconds,
        IProgress<OperationProgress>? progress,
        CancellationToken cancellationToken,
        bool expectDirectory = false)
    {
        var started = DateTimeOffset.UtcNow;
        try
        {
            progress?.Report(new OperationProgress(0, "正在准备转换", Elapsed: TimeSpan.Zero));
            var result = await processRunner.RunAsync(executable, arguments, line =>
            {
                if (!line.StartsWith("out_time_ms=", StringComparison.Ordinal)) return;
                if (!long.TryParse(line["out_time_ms=".Length..], out var microseconds)) return;
                var processed = microseconds / 1_000_000d;
                var percent = durationSeconds > 0 ? Math.Clamp(processed * 100d / durationSeconds, 0, 99.5) : 0;
                var elapsed = DateTimeOffset.UtcNow - started;
                TimeSpan? remaining = percent > 1 ? TimeSpan.FromSeconds(elapsed.TotalSeconds * (100 - percent) / percent) : null;
                progress?.Report(new OperationProgress(percent, $"正在处理 {percent:0}%", Elapsed: elapsed, Remaining: remaining));
            }, cancellationToken: cancellationToken);

            if (result.ExitCode != 0)
            {
                TryDelete(output, expectDirectory);
                return new ConversionResult(false, null, HumanizeFfmpegError(result.StandardError));
            }
            var exists = expectDirectory ? Directory.Exists(output) && Directory.EnumerateFiles(output).Any() : File.Exists(output);
            if (!exists)
            {
                return new ConversionResult(false, null, "转换工具已结束，但没有生成输出文件。");
            }
            progress?.Report(new OperationProgress(100, "处理完成", Elapsed: DateTimeOffset.UtcNow - started, Remaining: TimeSpan.Zero));
            return new ConversionResult(true, output, null);
        }
        catch (OperationCanceledException)
        {
            TryDelete(output, expectDirectory);
            throw;
        }
        catch (Exception exception)
        {
            TryDelete(output, expectDirectory);
            await logger.WriteAsync("FFMPEG", "转换发生未处理异常", exception);
            return new ConversionResult(false, null, "转换失败，请在诊断中心查看技术详情。");
        }
    }

    private static string HumanizeFfmpegError(string error)
    {
        var lower = error.ToLowerInvariant();
        if (lower.Contains("permission denied") || lower.Contains("access is denied")) return "输出路径不可写，请更换保存位置。";
        if (lower.Contains("no space left")) return "磁盘剩余空间不足。";
        if (lower.Contains("invalid data") || lower.Contains("could not find codec")) return "媒体文件损坏或编码不受支持。";
        if (lower.Contains("libc++") || lower.Contains("cannot link executable")) return "FFmpeg 组件不完整，请重新运行工具安装脚本。";
        return "FFmpeg 执行失败，请在诊断中心导出日志查看详情。";
    }

    private static string RequireFfmpeg() => ToolLocator.FindFfmpeg()
        ?? throw new UserFacingException("缺少 FFmpeg，无法执行真实转换。请运行工具安装脚本。");

    private static string FormatSeconds(double seconds) => seconds.ToString("0.###", CultureInfo.InvariantCulture);

    private static string GetUniquePath(string folder, string fileName)
    {
        var path = Path.Combine(folder, fileName);
        var name = Path.GetFileNameWithoutExtension(fileName);
        var extension = Path.GetExtension(fileName);
        var index = 1;
        while (File.Exists(path)) path = Path.Combine(folder, $"{name} ({index++}){extension}");
        return path;
    }

    private static string GetUniqueDirectory(string parent, string name)
    {
        var path = Path.Combine(parent, name);
        var index = 1;
        while (Directory.Exists(path)) path = Path.Combine(parent, $"{name} ({index++})");
        return path;
    }

    private static void TryDelete(string path, bool directory)
    {
        try
        {
            if (directory && Directory.Exists(path)) Directory.Delete(path, true);
            else if (File.Exists(path)) File.Delete(path);
        }
        catch { }
    }
}
