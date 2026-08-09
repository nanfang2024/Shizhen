using System.Windows;
using System.Windows.Media.Imaging;
using System.Windows.Threading;
using Shizhen.Windows.Models;

namespace Shizhen.Windows.Views;

public partial class MediaPreviewWindow : Window
{
    private readonly string _path;
    private readonly MediaType _type;
    private readonly DispatcherTimer _gifTimer = new() { Interval = TimeSpan.FromMilliseconds(100) };
    private IReadOnlyList<BitmapFrame> _gifFrames = [];
    private int _gifFrameIndex;
    private bool _isPlaying = true;

    public MediaPreviewWindow(string path, MediaType type, string? title)
    {
        InitializeComponent();
        _path = path;
        _type = type;
        FileTitleText.Text = string.IsNullOrWhiteSpace(title)
            ? Path.GetFileName(path)
            : $"{title} · {Path.GetFileName(path)}";
        _gifTimer.Tick += OnGifTick;
        Loaded += OnLoaded;
        Closed += OnClosed;
    }

    private void OnLoaded(object sender, RoutedEventArgs e)
    {
        try
        {
            if (_type is MediaType.Image or MediaType.Cover)
            {
                PreviewImage.Source = LoadBitmap(_path);
                PreviewImage.Visibility = Visibility.Visible;
                PlaybackControls.Visibility = Visibility.Collapsed;
                return;
            }

            if (_type == MediaType.Gif)
            {
                using var stream = File.OpenRead(_path);
                var decoder = new GifBitmapDecoder(stream, BitmapCreateOptions.PreservePixelFormat, BitmapCacheOption.OnLoad);
                _gifFrames = decoder.Frames.ToArray();
                if (_gifFrames.Count == 0) throw new InvalidDataException("GIF 中没有可显示的帧。");
                PreviewImage.Source = _gifFrames[0];
                PreviewImage.Visibility = Visibility.Visible;
                PlaybackControls.Visibility = Visibility.Collapsed;
                _gifTimer.Start();
                return;
            }

            PreviewMedia.Source = new Uri(_path, UriKind.Absolute);
            PreviewMedia.Visibility = Visibility.Visible;
            AudioPlaceholder.Visibility = _type == MediaType.Audio ? Visibility.Visible : Visibility.Collapsed;
            PreviewMedia.Volume = 0.8;
            PreviewMedia.Play();
        }
        catch (Exception exception)
        {
            ShowError($"无法在应用内预览这个文件。\n{exception.Message}");
        }
    }

    private static BitmapImage LoadBitmap(string path)
    {
        var bitmap = new BitmapImage();
        bitmap.BeginInit();
        bitmap.CacheOption = BitmapCacheOption.OnLoad;
        bitmap.UriSource = new Uri(path, UriKind.Absolute);
        bitmap.EndInit();
        bitmap.Freeze();
        return bitmap;
    }

    private void OnMediaOpened(object sender, RoutedEventArgs e)
    {
        PreviewErrorText.Visibility = Visibility.Collapsed;
    }

    private void OnMediaEnded(object sender, RoutedEventArgs e)
    {
        PreviewMedia.Position = TimeSpan.Zero;
        PreviewMedia.Play();
    }

    private void OnMediaFailed(object sender, ExceptionRoutedEventArgs e)
    {
        ShowError("Windows 当前缺少播放该编码所需的解码器。文件已正常缓存，可在下载后用其他播放器打开。");
    }

    private void OnPlayPause(object sender, RoutedEventArgs e)
    {
        if (_isPlaying)
        {
            PreviewMedia.Pause();
            PlayPauseButton.Content = "播放";
        }
        else
        {
            PreviewMedia.Play();
            PlayPauseButton.Content = "暂停";
        }
        _isPlaying = !_isPlaying;
    }

    private void OnRestart(object sender, RoutedEventArgs e)
    {
        PreviewMedia.Position = TimeSpan.Zero;
        PreviewMedia.Play();
        _isPlaying = true;
        PlayPauseButton.Content = "暂停";
    }

    private void OnVolumeChanged(object sender, RoutedPropertyChangedEventArgs<double> e)
    {
        if (PreviewMedia is not null) PreviewMedia.Volume = e.NewValue;
    }

    private void OnGifTick(object? sender, EventArgs e)
    {
        if (_gifFrames.Count == 0) return;
        _gifFrameIndex = (_gifFrameIndex + 1) % _gifFrames.Count;
        PreviewImage.Source = _gifFrames[_gifFrameIndex];
    }

    private void ShowError(string message)
    {
        PreviewMedia.Stop();
        PreviewMedia.Visibility = Visibility.Collapsed;
        AudioPlaceholder.Visibility = Visibility.Collapsed;
        PlaybackControls.Visibility = Visibility.Collapsed;
        PreviewErrorText.Text = message;
        PreviewErrorText.Visibility = Visibility.Visible;
    }

    private void OnClose(object sender, RoutedEventArgs e) => Close();

    private void OnClosed(object? sender, EventArgs e)
    {
        _gifTimer.Stop();
        PreviewMedia.Stop();
        PreviewMedia.Source = null;
    }
}
