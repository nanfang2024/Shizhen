using System.Collections.ObjectModel;
using Shizhen.Windows.Core;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.ViewModels;

public sealed class MediaGroupViewModel : ObservableObject
{
    private MediaItem? _selectedItem;

    public MediaGroupViewModel(MediaType type, IEnumerable<MediaItem> items)
    {
        Type = type;
        Items = new ObservableCollection<MediaItem>(items);
        SelectedItem = Items.FirstOrDefault();
    }

    public MediaType Type { get; }
    public ObservableCollection<MediaItem> Items { get; }
    public MediaItem? SelectedItem
    {
        get => _selectedItem;
        set => SetProperty(ref _selectedItem, value);
    }

    public string Title => Type switch
    {
        MediaType.Video => "视频资源",
        MediaType.Image => "图片资源",
        MediaType.Gif => "GIF 动图",
        MediaType.Audio => "音频资源",
        MediaType.Cover => "作品封面",
        _ => "其他资源"
    };

    public string Summary => Type switch
    {
        MediaType.Video => $"{Items.Count} 个清晰度",
        MediaType.Image => $"{Items.Count} 张图片",
        MediaType.Audio => $"{Items.Count} 个音轨",
        _ => $"{Items.Count} 个资源"
    };

    public string DownloadLabel => Type switch
    {
        MediaType.Video => "下载视频",
        MediaType.Image when Items.Count > 1 => "保存全部图片",
        MediaType.Image => "下载图片",
        MediaType.Gif => "下载动图",
        MediaType.Audio when SelectedItem?.IsPreviewOnly == true => "下载试听片段",
        MediaType.Audio => "下载音频",
        MediaType.Cover => "下载封面",
        _ => "下载资源"
    };
}
