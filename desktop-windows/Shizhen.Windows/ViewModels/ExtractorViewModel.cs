using System.Collections.ObjectModel;
using System.Windows;
using Shizhen.Windows.Core;
using Shizhen.Windows.Models;
using Shizhen.Windows.Services;
using Shizhen.Windows.Services.Parsing;
using Shizhen.Windows.Views;

namespace Shizhen.Windows.ViewModels;

public enum ExtractorState
{
    Idle,
    Parsing,
    Success,
    Error,
    Downloading,
    Previewing,
    DownloadSuccess,
    DownloadError,
    Cancelled
}

public sealed class ExtractorViewModel : ObservableObject
{
    private readonly ParserRegistry _parsers;
    private readonly DownloadService _downloads;
    private readonly HistoryRepository _history;
    private readonly SettingsService _settings;
    private readonly DiagnosticLogger _logger;
    private readonly Dictionary<string, string> _previewCache = new(StringComparer.Ordinal);
    private CancellationTokenSource? _taskCancellation;
    private string _inputText = string.Empty;
    private string? _extractedUrl;
    private string _platform = "等待识别";
    private string _statusMessage = "粘贴或输入公开分享文字，然后点击解析链接。";
    private string? _errorMessage;
    private ParsedMedia? _parsedMedia;
    private ExtractorState _state = ExtractorState.Idle;
    private double _progress;
    private string _progressDetail = string.Empty;
    private string? _lastOutputPath;

    public ExtractorViewModel(
        ParserRegistry parsers,
        DownloadService downloads,
        HistoryRepository history,
        SettingsService settings,
        DiagnosticLogger logger)
    {
        _parsers = parsers;
        _downloads = downloads;
        _history = history;
        _settings = settings;
        _logger = logger;
        ParseCommand = new AsyncRelayCommand(ParseAsync, () => !IsBusy);
        ClearCommand = new RelayCommand(Clear, () => !IsBusy);
        PasteCommand = new RelayCommand(Paste, () => !IsBusy);
        DownloadCommand = new AsyncRelayCommand<MediaGroupViewModel>(DownloadAsync, group => group?.SelectedItem is not null && !IsBusy);
        PreviewCommand = new AsyncRelayCommand<MediaGroupViewModel>(PreviewAsync, group => group?.SelectedItem is not null && !IsBusy);
        CancelCommand = new RelayCommand(Cancel, () => IsBusy);
        ChooseFolderCommand = new RelayCommand(ChooseDownloadFolder, () => !IsBusy);
        OpenOutputCommand = new RelayCommand(() => FileLauncher.Open(LastOutputPath), () => !string.IsNullOrWhiteSpace(LastOutputPath));
        RevealOutputCommand = new RelayCommand(RevealLastOutput, () => !string.IsNullOrWhiteSpace(LastOutputPath));
    }

    public ObservableCollection<MediaGroupViewModel> Groups { get; } = [];
    public AsyncRelayCommand ParseCommand { get; }
    public RelayCommand ClearCommand { get; }
    public RelayCommand PasteCommand { get; }
    public AsyncRelayCommand<MediaGroupViewModel> DownloadCommand { get; }
    public AsyncRelayCommand<MediaGroupViewModel> PreviewCommand { get; }
    public RelayCommand CancelCommand { get; }
    public RelayCommand ChooseFolderCommand { get; }
    public RelayCommand OpenOutputCommand { get; }
    public RelayCommand RevealOutputCommand { get; }

    public string InputText
    {
        get => _inputText;
        set
        {
            if (SetProperty(ref _inputText, value))
            {
                ExtractedUrl = UrlExtractor.ExtractFirst(value);
                Platform = ExtractedUrl is null ? "等待识别" : PlatformDetector.Detect(ExtractedUrl);
            }
        }
    }

    public string? ExtractedUrl
    {
        get => _extractedUrl;
        private set => SetProperty(ref _extractedUrl, value);
    }

    public string Platform
    {
        get => _platform;
        private set => SetProperty(ref _platform, value);
    }

    public string StatusMessage
    {
        get => _statusMessage;
        private set => SetProperty(ref _statusMessage, value);
    }

    public string? ErrorMessage
    {
        get => _errorMessage;
        private set => SetProperty(ref _errorMessage, value);
    }

    public ParsedMedia? ParsedMedia
    {
        get => _parsedMedia;
        private set => SetProperty(ref _parsedMedia, value);
    }

    public ExtractorState State
    {
        get => _state;
        private set
        {
            if (SetProperty(ref _state, value))
            {
                OnPropertyChanged(nameof(IsBusy));
                OnPropertyChanged(nameof(HasResults));
                OnPropertyChanged(nameof(HasError));
                OnPropertyChanged(nameof(HasProgress));
                OnPropertyChanged(nameof(IsParsing));
                NotifyCommands();
            }
        }
    }

    public bool IsBusy => State is ExtractorState.Parsing or ExtractorState.Downloading or ExtractorState.Previewing;
    public bool HasResults => ParsedMedia is not null && Groups.Count > 0;
    public bool HasError => State is ExtractorState.Error or ExtractorState.DownloadError;
    public bool HasProgress => State is ExtractorState.Parsing or ExtractorState.Downloading or ExtractorState.Previewing;
    public bool IsParsing => State == ExtractorState.Parsing;

    public double Progress
    {
        get => _progress;
        private set => SetProperty(ref _progress, value);
    }

    public string ProgressDetail
    {
        get => _progressDetail;
        private set => SetProperty(ref _progressDetail, value);
    }

    public string DownloadFolder => _settings.Current.DownloadFolder;

    public string? LastOutputPath
    {
        get => _lastOutputPath;
        private set
        {
            if (SetProperty(ref _lastOutputPath, value))
            {
                OpenOutputCommand.NotifyCanExecuteChanged();
                RevealOutputCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public string ResultSummary
    {
        get
        {
            if (ParsedMedia is null) return string.Empty;
            var parts = ParsedMedia.Items.GroupBy(item => item.Type).Select(group => group.Key switch
            {
                MediaType.Video => $"{group.Count()} 个视频版本",
                MediaType.Image => $"{group.Count()} 张图片",
                MediaType.Gif => $"{group.Count()} 个 GIF",
                MediaType.Audio => $"{group.Count()} 个音轨",
                MediaType.Cover => $"{group.Count()} 张封面",
                _ => $"{group.Count()} 个资源"
            });
            return string.Join(" · ", parts);
        }
    }

    public void AcceptSharedText(string text)
    {
        InputText = text;
        StatusMessage = ExtractedUrl is null ? "收到的文本中没有有效链接。" : "已从启动参数中识别链接，请确认后点击解析。";
    }

    private async Task ParseAsync()
    {
        ExtractedUrl = UrlExtractor.ExtractFirst(InputText);
        if (ExtractedUrl is null)
        {
            State = ExtractorState.Error;
            ErrorMessage = string.IsNullOrWhiteSpace(InputText) ? "请先粘贴或输入分享文字。" : "没有在分享文字中找到有效链接。";
            StatusMessage = "解析未开始";
            return;
        }

        Platform = PlatformDetector.Detect(ExtractedUrl);
        ErrorMessage = null;
        Groups.Clear();
        ParsedMedia = null;
        Progress = 0;
        ProgressDetail = "正在解析公开媒体资源……";
        StatusMessage = "正在解析公开媒体资源……";
        State = ExtractorState.Parsing;
        _taskCancellation = new CancellationTokenSource();
        var historyId = await _history.AddAsync("链接解析", ExtractedUrl, OperationStatus.Running);
        try
        {
            ParsedMedia = await _parsers.ParseAsync(ExtractedUrl, _taskCancellation.Token);
            Platform = ParsedMedia.Platform;
            foreach (var group in ParsedMedia.Items.GroupBy(item => item.Type).OrderBy(group => group.Key))
            {
                Groups.Add(new MediaGroupViewModel(group.Key, group));
            }
            OnPropertyChanged(nameof(ResultSummary));
            OnPropertyChanged(nameof(HasResults));
            State = ExtractorState.Success;
            StatusMessage = ParsedMedia.Notice ?? "解析成功";
            await _history.UpdateAsync(historyId, OperationStatus.Success, details: $"{ParsedMedia.Platform} · {ResultSummary}");
        }
        catch (OperationCanceledException)
        {
            State = ExtractorState.Cancelled;
            StatusMessage = "解析已取消";
            await _history.UpdateAsync(historyId, OperationStatus.Cancelled, errorReason: "用户取消解析");
        }
        catch (UserFacingException exception)
        {
            State = ExtractorState.Error;
            ErrorMessage = exception.Message;
            StatusMessage = "解析失败";
            await _history.UpdateAsync(historyId, OperationStatus.Failed, errorReason: exception.Message, details: exception.TechnicalDetails);
        }
        finally
        {
            _taskCancellation?.Dispose();
            _taskCancellation = null;
        }
    }

    private async Task DownloadAsync(MediaGroupViewModel? group)
    {
        if (group?.SelectedItem is not { } item || ParsedMedia is null) return;
        var destinationFolder = PromptForDownloadFolder();
        if (destinationFolder is null)
        {
            StatusMessage = "已取消下载。";
            return;
        }
        if (group.Type == MediaType.Image && group.Items.Count > 1)
        {
            await DownloadImageCollectionAsync(group, destinationFolder);
            return;
        }
        State = ExtractorState.Downloading;
        ErrorMessage = null;
        Progress = 0;
        ProgressDetail = $"正在准备{group.DownloadLabel}……";
        item.DownloadStatus = "等待下载";
        _taskCancellation = new CancellationTokenSource();
        var historyId = await _history.AddAsync($"下载{item.TypeDisplay}", ParsedMedia.SourceUrl, OperationStatus.Running);
        try
        {
            var reporter = new Progress<OperationProgress>(value =>
            {
                Progress = value.Percent;
                ProgressDetail = BuildProgressDetail(value);
                item.DownloadProgress = value.Percent;
                item.DownloadStatus = value.Status;
            });
            var output = await _downloads.DownloadAsync(ParsedMedia, item, destinationFolder, reporter, _taskCancellation.Token);
            item.LocalPath = output;
            item.DownloadStatus = "下载成功";
            LastOutputPath = output;
            State = ExtractorState.DownloadSuccess;
            StatusMessage = "下载完成";
            await _history.UpdateAsync(historyId, OperationStatus.Success, output);
        }
        catch (OperationCanceledException)
        {
            item.DownloadStatus = "已取消";
            State = ExtractorState.Cancelled;
            StatusMessage = "下载已取消";
            await _history.UpdateAsync(historyId, OperationStatus.Cancelled, errorReason: "用户取消下载");
        }
        catch (UserFacingException exception)
        {
            item.DownloadStatus = "下载失败";
            State = ExtractorState.DownloadError;
            ErrorMessage = exception.Message;
            StatusMessage = "下载失败";
            await _history.UpdateAsync(historyId, OperationStatus.Failed, errorReason: exception.Message, details: exception.TechnicalDetails);
        }
        finally
        {
            _taskCancellation?.Dispose();
            _taskCancellation = null;
        }
    }

    private async Task DownloadImageCollectionAsync(MediaGroupViewModel group, string destinationFolder)
    {
        if (ParsedMedia is null) return;
        State = ExtractorState.Downloading;
        ErrorMessage = null;
        Progress = 0;
        ProgressDetail = $"正在准备保存 {group.Items.Count} 张图片……";
        _taskCancellation = new CancellationTokenSource();
        var historyId = await _history.AddAsync("下载图集", ParsedMedia.SourceUrl, OperationStatus.Running);
        var completed = 0;
        try
        {
            foreach (var image in group.Items)
            {
                var index = completed;
                image.DownloadStatus = "正在下载";
                var reporter = new Progress<OperationProgress>(value =>
                {
                    Progress = (index * 100d + value.Percent) / group.Items.Count;
                    ProgressDetail = $"第 {index + 1} / {group.Items.Count} 张 · {BuildProgressDetail(value)}";
                    image.DownloadProgress = value.Percent;
                    image.DownloadStatus = value.Status;
                });
                var output = await _downloads.DownloadAsync(ParsedMedia, image, destinationFolder, reporter, _taskCancellation.Token);
                image.LocalPath = output;
                image.DownloadStatus = "下载成功";
                completed++;
            }

            Progress = 100;
            LastOutputPath = destinationFolder;
            State = ExtractorState.DownloadSuccess;
            StatusMessage = $"已保存 {completed} 张图片";
            await _history.UpdateAsync(historyId, OperationStatus.Success, destinationFolder, details: $"共 {completed} 张图片");
        }
        catch (OperationCanceledException)
        {
            State = ExtractorState.Cancelled;
            StatusMessage = $"已取消，成功保存 {completed} 张图片";
            await _history.UpdateAsync(historyId, OperationStatus.Cancelled, destinationFolder, $"用户取消；已完成 {completed} 张");
        }
        catch (UserFacingException exception)
        {
            State = ExtractorState.DownloadError;
            ErrorMessage = $"第 {completed + 1} 张图片下载失败：{exception.Message}";
            StatusMessage = "图集下载未完成";
            await _history.UpdateAsync(historyId, OperationStatus.Failed, destinationFolder, ErrorMessage, exception.TechnicalDetails);
        }
        finally
        {
            _taskCancellation?.Dispose();
            _taskCancellation = null;
        }
    }

    private static string BuildProgressDetail(OperationProgress value)
    {
        if (value.CompletedBytes.HasValue && value.TotalBytes.HasValue)
        {
            return $"{value.Status} · {FileSizeFormatter.Format(value.CompletedBytes.Value)} / {FileSizeFormatter.Format(value.TotalBytes.Value)}";
        }
        return value.Status;
    }

    private async Task PreviewAsync(MediaGroupViewModel? group)
    {
        if (group?.SelectedItem is not { } item || ParsedMedia is null) return;
        try
        {
            var cacheKey = $"{item.Id}|{item.MediaUrl}";
            var localPath = !string.IsNullOrWhiteSpace(item.LocalPath) && File.Exists(item.LocalPath)
                ? item.LocalPath
                : _previewCache.GetValueOrDefault(cacheKey);
            if (string.IsNullOrWhiteSpace(localPath) || !File.Exists(localPath))
            {
                State = ExtractorState.Previewing;
                ErrorMessage = null;
                Progress = 0;
                ProgressDetail = "正在准备本地预览…";
                StatusMessage = "正在缓存所选资源，本过程不会打开网页";
                _taskCancellation = new CancellationTokenSource();
                var reporter = new Progress<OperationProgress>(value =>
                {
                    Progress = value.Percent;
                    ProgressDetail = BuildProgressDetail(value);
                });
                localPath = await _downloads.DownloadAsync(
                    ParsedMedia,
                    item,
                    AppPaths.PreviewCache,
                    reporter,
                    _taskCancellation.Token);
                _previewCache[cacheKey] = localPath;
            }

            State = ExtractorState.Success;
            StatusMessage = "本地预览已准备完成";
            var preview = new MediaPreviewWindow(localPath, item.Type, ParsedMedia.Title)
            {
                Owner = Application.Current.MainWindow
            };
            preview.ShowDialog();
        }
        catch (OperationCanceledException)
        {
            State = ExtractorState.Cancelled;
            StatusMessage = "已取消准备预览";
        }
        catch (UserFacingException exception)
        {
            State = ExtractorState.DownloadError;
            ErrorMessage = $"无法准备本地预览：{exception.Message}";
            StatusMessage = "本地预览失败";
            await _logger.WriteAsync("PREVIEW", ErrorMessage, exception);
        }
        catch (Exception exception)
        {
            State = ExtractorState.DownloadError;
            ErrorMessage = "无法打开本地预览，文件格式可能不受系统播放器支持。";
            StatusMessage = "本地预览失败";
            await _logger.WriteAsync("PREVIEW", ErrorMessage, exception);
        }
        finally
        {
            _taskCancellation?.Dispose();
            _taskCancellation = null;
        }
    }

    private void Paste()
    {
        if (Clipboard.ContainsText()) InputText = Clipboard.GetText();
    }

    private void Clear()
    {
        InputText = string.Empty;
        ExtractedUrl = null;
        Platform = "等待识别";
        ParsedMedia = null;
        Groups.Clear();
        ErrorMessage = null;
        LastOutputPath = null;
        Progress = 0;
        ProgressDetail = string.Empty;
        StatusMessage = "粘贴或输入公开分享文字，然后点击解析链接。";
        State = ExtractorState.Idle;
        OnPropertyChanged(nameof(ResultSummary));
        OnPropertyChanged(nameof(HasResults));
    }

    private void Cancel() => _taskCancellation?.Cancel();

    private void RevealLastOutput()
    {
        if (Directory.Exists(LastOutputPath)) FileLauncher.Open(LastOutputPath);
        else FileLauncher.Reveal(LastOutputPath);
    }

    private void ChooseDownloadFolder()
    {
        _ = PromptForDownloadFolder();
    }

    private string? PromptForDownloadFolder()
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog
        {
            Title = "选择下载保存位置",
            InitialDirectory = Directory.Exists(DownloadFolder) ? DownloadFolder : AppPaths.Downloads
        };
        if (dialog.ShowDialog() == true)
        {
            _settings.Current.DownloadFolder = dialog.FolderName;
            _settings.Save();
            OnPropertyChanged(nameof(DownloadFolder));
            return dialog.FolderName;
        }

        return null;
    }

    private void NotifyCommands()
    {
        ParseCommand.NotifyCanExecuteChanged();
        ClearCommand.NotifyCanExecuteChanged();
        PasteCommand.NotifyCanExecuteChanged();
        DownloadCommand.NotifyCanExecuteChanged();
        PreviewCommand.NotifyCanExecuteChanged();
        CancelCommand.NotifyCanExecuteChanged();
        ChooseFolderCommand.NotifyCanExecuteChanged();
    }
}
