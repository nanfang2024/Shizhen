namespace Shizhen.Windows.Models;

public enum ConversionMode
{
    VideoToGif,
    GifToMp4,
    ExtractFrames
}

public sealed record LocalMediaInfo(
    string Path,
    string FileName,
    string Extension,
    long FileSize,
    int? Width,
    int? Height,
    double? DurationSeconds,
    bool IsVideo,
    bool IsGif)
{
    public string TypeDisplay => IsGif ? "GIF 动图" : IsVideo ? "视频" : "不支持的媒体";
    public string SizeDisplay => FileSizeFormatter.Format(FileSize);
    public string ResolutionDisplay => Width.HasValue && Height.HasValue ? $"{Width} × {Height}" : "未知";
    public string DurationDisplay => DurationSeconds.HasValue ? TimeSpan.FromSeconds(DurationSeconds.Value).ToString(DurationSeconds >= 3600 ? @"h\:mm\:ss" : @"m\:ss") : "未知";
}

public sealed record VideoToGifOptions(
    double StartSeconds,
    double EndSeconds,
    int FramesPerSecond,
    int? OutputWidth,
    bool Loop,
    string OutputName,
    string OutputFolder);

public sealed record GifToMp4Options(string OutputName, string OutputFolder);

public sealed record ExtractFramesOptions(
    double IntervalSeconds,
    string ImageFormat,
    string FileNamePattern,
    string OutputFolder);
