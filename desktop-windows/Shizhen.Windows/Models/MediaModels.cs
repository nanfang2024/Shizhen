using Shizhen.Windows.Core;

namespace Shizhen.Windows.Models;

public enum MediaType
{
    Video,
    Image,
    Gif,
    Cover,
    Audio,
    Unknown
}

public sealed class MediaItem : ObservableObject
{
    private bool _isSelected;
    private double _downloadProgress;
    private string _downloadStatus = "等待下载";
    private string? _localPath;

    public required string Id { get; init; }
    public required MediaType Type { get; init; }
    public required string MediaUrl { get; init; }
    public string? Format { get; init; }
    public int? Width { get; init; }
    public int? Height { get; init; }
    public long? FileSize { get; init; }
    public double? DurationSeconds { get; init; }
    public string? QualityLabel { get; init; }
    public string? FormatId { get; init; }
    public string? VideoCodec { get; init; }
    public string? AudioCodec { get; init; }
    public string? ThumbnailUrl { get; init; }
    public string? ReferrerUrl { get; init; }
    public bool IsPreviewOnly { get; init; }
    public bool ExtractAudio { get; init; }
    public bool RequiresMerge => Type == MediaType.Video && string.Equals(AudioCodec, "none", StringComparison.OrdinalIgnoreCase);

    public bool IsSelected
    {
        get => _isSelected;
        set => SetProperty(ref _isSelected, value);
    }

    public double DownloadProgress
    {
        get => _downloadProgress;
        set => SetProperty(ref _downloadProgress, value);
    }

    public string DownloadStatus
    {
        get => _downloadStatus;
        set => SetProperty(ref _downloadStatus, value);
    }

    public string? LocalPath
    {
        get => _localPath;
        set => SetProperty(ref _localPath, value);
    }

    public string TypeDisplay => Type switch
    {
        MediaType.Video => "视频",
        MediaType.Image => "图片",
        MediaType.Gif => "GIF 动图",
        MediaType.Cover => "封面",
        MediaType.Audio => IsPreviewOnly ? "音频（仅试听片段）" : "音频",
        _ => "媒体"
    };

    public string ResolutionDisplay => Width.HasValue && Height.HasValue ? $"{Width} × {Height}" : "未知";
    public string SizeDisplay => FileSize.HasValue ? FileSizeFormatter.Format(FileSize.Value) : "未知";
    public string DurationDisplay => DurationSeconds.HasValue ? TimeSpan.FromSeconds(DurationSeconds.Value).ToString(DurationSeconds >= 3600 ? @"h\:mm\:ss" : @"m\:ss") : "未知";
    public string QualityDisplay => string.IsNullOrWhiteSpace(QualityLabel) ? ResolutionDisplay : QualityLabel!;
    public string FormatDisplay => string.IsNullOrWhiteSpace(Format) ? "未知" : Format!.ToUpperInvariant();
}

public sealed record ParsedMedia(
    string SourceUrl,
    string Platform,
    string? Title,
    string? Author,
    string? ThumbnailUrl,
    double? DurationSeconds,
    IReadOnlyList<MediaItem> Items,
    string ExtractorName,
    string? Notice = null);

public static class FileSizeFormatter
{
    public static string Format(long bytes)
    {
        string[] units = ["B", "KB", "MB", "GB", "TB"];
        var size = (double)Math.Max(0, bytes);
        var unit = 0;
        while (size >= 1024 && unit < units.Length - 1)
        {
            size /= 1024;
            unit++;
        }

        return $"{size:0.#} {units[unit]}";
    }
}
