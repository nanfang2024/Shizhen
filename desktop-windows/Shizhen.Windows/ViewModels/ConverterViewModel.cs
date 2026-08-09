using System.Collections.ObjectModel;
using Shizhen.Windows.Core;
using Shizhen.Windows.Models;
using Shizhen.Windows.Services;

namespace Shizhen.Windows.ViewModels;

public enum ConverterState
{
    NoFile,
    FileSelected,
    Configuring,
    Processing,
    Success,
    Error,
    Cancelled
}

public sealed record ConversionOption(ConversionMode Mode, string Title, string Description);
public sealed record WidthOption(string Label, int? Value);

public sealed class ConverterViewModel : ObservableObject
{
    private readonly MediaProbeService _probe;
    private readonly IMediaConverter _converter;
    private readonly HistoryRepository _history;
    private readonly SettingsService _settings;
    private CancellationTokenSource? _cancellation;
    private LocalMediaInfo? _selectedFile;
    private ConversionOption? _selectedOperation;
    private ConverterState _state = ConverterState.NoFile;
    private string? _errorMessage;
    private double _progress;
    private string _progressDetail = string.Empty;
    private string _elapsedText = "—";
    private string _remainingText = "—";
    private string? _outputPath;
    private string _startTime = "0";
    private string _endTime = "10";
    private int _fps = 12;
    private WidthOption _selectedWidth;
    private bool _loopGif = true;
    private string _outputName = "converted";
    private string _intervalSeconds = "5";
    private string _imageFormat = "JPG";
    private string _fileNamePattern = "frame";

    public ConverterViewModel(MediaProbeService probe, IMediaConverter converter, HistoryRepository history, SettingsService settings)
    {
        _probe = probe;
        _converter = converter;
        _history = history;
        _settings = settings;
        WidthOptions = [new("320", 320), new("480", 480), new("720", 720), new("原尺寸", null)];
        _selectedWidth = WidthOptions[1];
        ChooseFileCommand = new AsyncRelayCommand(ChooseFileAsync, () => !IsProcessing);
        ChooseFolderCommand = new RelayCommand(ChooseFolder, () => !IsProcessing);
        StartCommand = new AsyncRelayCommand(StartAsync, () => SelectedFile is not null && SelectedOperation is not null && !IsProcessing);
        CancelCommand = new RelayCommand(() => _cancellation?.Cancel(), () => IsProcessing);
        OpenOutputCommand = new RelayCommand(() => FileLauncher.Open(OutputPath), () => !string.IsNullOrWhiteSpace(OutputPath));
        RevealOutputCommand = new RelayCommand(() => RevealOutput(), () => !string.IsNullOrWhiteSpace(OutputPath));
        ResetCommand = new RelayCommand(Reset, () => !IsProcessing);
    }

    public ObservableCollection<ConversionOption> AvailableOperations { get; } = [];
    public IReadOnlyList<int> FpsOptions { get; } = [5, 10, 12, 15, 20];
    public IReadOnlyList<WidthOption> WidthOptions { get; }
    public IReadOnlyList<string> ImageFormats { get; } = ["JPG", "PNG"];

    public AsyncRelayCommand ChooseFileCommand { get; }
    public RelayCommand ChooseFolderCommand { get; }
    public AsyncRelayCommand StartCommand { get; }
    public RelayCommand CancelCommand { get; }
    public RelayCommand OpenOutputCommand { get; }
    public RelayCommand RevealOutputCommand { get; }
    public RelayCommand ResetCommand { get; }

    public LocalMediaInfo? SelectedFile
    {
        get => _selectedFile;
        private set
        {
            if (SetProperty(ref _selectedFile, value))
            {
                OnPropertyChanged(nameof(HasFile));
                StartCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public bool HasFile => SelectedFile is not null;

    public ConversionOption? SelectedOperation
    {
        get => _selectedOperation;
        set
        {
            if (SetProperty(ref _selectedOperation, value))
            {
                State = value is null ? ConverterState.FileSelected : ConverterState.Configuring;
                OnPropertyChanged(nameof(SelectedMode));
                OnPropertyChanged(nameof(ActionLabel));
                OnPropertyChanged(nameof(OutputFolder));
                StartCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public ConversionMode? SelectedMode => SelectedOperation?.Mode;
    public string ActionLabel => SelectedMode == ConversionMode.ExtractFrames ? "开始提取" : "开始转换";
    public string OutputFolder => SelectedMode == ConversionMode.ExtractFrames ? _settings.Current.FramesFolder : _settings.Current.ConversionFolder;

    public ConverterState State
    {
        get => _state;
        private set
        {
            if (SetProperty(ref _state, value))
            {
                OnPropertyChanged(nameof(IsProcessing));
                OnPropertyChanged(nameof(HasError));
                OnPropertyChanged(nameof(IsSuccess));
                NotifyCommands();
            }
        }
    }

    public bool IsProcessing => State == ConverterState.Processing;
    public bool HasError => State == ConverterState.Error;
    public bool IsSuccess => State == ConverterState.Success;

    public string? ErrorMessage { get => _errorMessage; private set => SetProperty(ref _errorMessage, value); }
    public double Progress { get => _progress; private set => SetProperty(ref _progress, value); }
    public string ProgressDetail { get => _progressDetail; private set => SetProperty(ref _progressDetail, value); }
    public string ElapsedText { get => _elapsedText; private set => SetProperty(ref _elapsedText, value); }
    public string RemainingText { get => _remainingText; private set => SetProperty(ref _remainingText, value); }
    public string? OutputPath
    {
        get => _outputPath;
        private set
        {
            if (SetProperty(ref _outputPath, value))
            {
                OpenOutputCommand.NotifyCanExecuteChanged();
                RevealOutputCommand.NotifyCanExecuteChanged();
            }
        }
    }

    public string StartTime { get => _startTime; set => SetProperty(ref _startTime, value); }
    public string EndTime { get => _endTime; set => SetProperty(ref _endTime, value); }
    public int Fps { get => _fps; set { if (SetProperty(ref _fps, value)) OnPropertyChanged(nameof(EstimatedOutput)); } }
    public WidthOption SelectedWidth { get => _selectedWidth; set { if (SetProperty(ref _selectedWidth, value)) OnPropertyChanged(nameof(EstimatedOutput)); } }
    public bool LoopGif { get => _loopGif; set => SetProperty(ref _loopGif, value); }
    public string OutputName { get => _outputName; set => SetProperty(ref _outputName, value); }
    public string IntervalSeconds { get => _intervalSeconds; set { if (SetProperty(ref _intervalSeconds, value)) OnPropertyChanged(nameof(EstimatedOutput)); } }
    public string ImageFormat { get => _imageFormat; set => SetProperty(ref _imageFormat, value); }
    public string FileNamePattern { get => _fileNamePattern; set => SetProperty(ref _fileNamePattern, value); }

    public string EstimatedOutput
    {
        get
        {
            if (SelectedFile?.DurationSeconds is not { } duration) return "选择文件后显示估算";
            if (SelectedMode == ConversionMode.ExtractFrames && TryParsePositive(IntervalSeconds, out var interval))
                return $"预计提取约 {Math.Max(1, (int)Math.Ceiling(duration / interval))} 张图片";
            if (SelectedMode == ConversionMode.VideoToGif && TryParseNonNegative(StartTime, out var start) && TryParsePositive(EndTime, out var end) && end > start)
            {
                var width = SelectedWidth.Value?.ToString() ?? "原尺寸";
                return $"预计 {Math.Ceiling((end - start) * Fps):0} 帧 · 宽度 {width}";
            }
            return "输出大小取决于画面复杂度";
        }
    }

    private async Task ChooseFileAsync()
    {
        var dialog = new Microsoft.Win32.OpenFileDialog
        {
            Title = "选择本地媒体文件",
            Filter = "支持的媒体|*.mp4;*.mkv;*.mov;*.webm;*.avi;*.m4v;*.ts;*.gif|视频|*.mp4;*.mkv;*.mov;*.webm;*.avi;*.m4v;*.ts|GIF 动图|*.gif|所有文件|*.*"
        };
        if (dialog.ShowDialog() != true) return;
        await AcceptFileAsync(dialog.FileName);
    }

    public async Task AcceptFileAsync(string path)
    {
        if (IsProcessing) return;
        ErrorMessage = null;
        try
        {
            SelectedFile = await _probe.ProbeAsync(path);
            AvailableOperations.Clear();
            if (SelectedFile.IsVideo)
            {
                AvailableOperations.Add(new ConversionOption(ConversionMode.VideoToGif, "视频转 GIF", "截取视频片段并生成高质量动图"));
                AvailableOperations.Add(new ConversionOption(ConversionMode.ExtractFrames, "视频提取图片", "按固定时间间隔保存画面"));
                EndTime = Math.Min(10, SelectedFile.DurationSeconds ?? 10).ToString("0.###");
            }
            else if (SelectedFile.IsGif)
            {
                AvailableOperations.Add(new ConversionOption(ConversionMode.GifToMp4, "GIF 转 MP4", "生成常见播放器兼容的视频"));
            }
            else
            {
                State = ConverterState.Error;
                ErrorMessage = "暂不支持这个文件类型。请选择视频或 GIF 文件。";
                return;
            }
            OutputName = FileNameSanitizer.Sanitize(Path.GetFileNameWithoutExtension(SelectedFile.FileName)) + "_converted";
            SelectedOperation = AvailableOperations.FirstOrDefault();
            State = ConverterState.Configuring;
            OnPropertyChanged(nameof(EstimatedOutput));
        }
        catch (UserFacingException exception)
        {
            State = ConverterState.Error;
            ErrorMessage = exception.Message;
        }
    }

    private async Task StartAsync()
    {
        if (SelectedFile is null || SelectedMode is null) return;
        ErrorMessage = Validate();
        if (ErrorMessage is not null)
        {
            State = ConverterState.Error;
            return;
        }
        State = ConverterState.Processing;
        Progress = 0;
        ProgressDetail = "正在准备任务……";
        OutputPath = null;
        _cancellation = new CancellationTokenSource();
        var operationType = SelectedOperation!.Title;
        var historyId = await _history.AddAsync(operationType, SelectedFile.FileName, OperationStatus.Running);
        try
        {
            var reporter = new Progress<OperationProgress>(value =>
            {
                Progress = value.Percent;
                ProgressDetail = value.Status;
                ElapsedText = FormatTime(value.Elapsed);
                RemainingText = FormatTime(value.Remaining);
            });
            ConversionResult result = SelectedMode switch
            {
                ConversionMode.VideoToGif => await _converter.VideoToGifAsync(SelectedFile,
                    new VideoToGifOptions(Parse(StartTime), Parse(EndTime), Fps, SelectedWidth.Value, LoopGif, OutputName, OutputFolder), reporter, _cancellation.Token),
                ConversionMode.GifToMp4 => await _converter.GifToMp4Async(SelectedFile,
                    new GifToMp4Options(OutputName, OutputFolder), reporter, _cancellation.Token),
                ConversionMode.ExtractFrames => await _converter.ExtractFramesAsync(SelectedFile,
                    new ExtractFramesOptions(Parse(IntervalSeconds), ImageFormat, FileNamePattern, OutputFolder), reporter, _cancellation.Token),
                _ => new ConversionResult(false, null, "不支持的转换操作。")
            };
            if (result.Success)
            {
                OutputPath = result.OutputPath;
                State = ConverterState.Success;
                ProgressDetail = "处理完成";
                await _history.UpdateAsync(historyId, OperationStatus.Success, result.OutputPath);
            }
            else
            {
                State = ConverterState.Error;
                ErrorMessage = result.ErrorMessage;
                await _history.UpdateAsync(historyId, OperationStatus.Failed, errorReason: result.ErrorMessage);
            }
        }
        catch (OperationCanceledException)
        {
            State = ConverterState.Cancelled;
            ProgressDetail = "任务已取消";
            await _history.UpdateAsync(historyId, OperationStatus.Cancelled, errorReason: "用户取消任务");
        }
        catch (UserFacingException exception)
        {
            State = ConverterState.Error;
            ErrorMessage = exception.Message;
            await _history.UpdateAsync(historyId, OperationStatus.Failed, errorReason: exception.Message, details: exception.TechnicalDetails);
        }
        finally
        {
            _cancellation?.Dispose();
            _cancellation = null;
        }
    }

    private string? Validate()
    {
        if (string.IsNullOrWhiteSpace(OutputName)) return "请输入输出文件名。";
        if (OutputName.IndexOfAny(Path.GetInvalidFileNameChars()) >= 0) return "输出文件名包含无效字符。";
        if (SelectedMode == ConversionMode.VideoToGif)
        {
            if (!TryParseNonNegative(StartTime, out var start) || !TryParsePositive(EndTime, out var end) || end <= start)
                return "请输入有效时间，且结束时间必须大于开始时间。";
            if (SelectedFile?.DurationSeconds is { } duration && end > duration + 0.2) return "结束时间不能超过视频时长。";
        }
        if (SelectedMode == ConversionMode.ExtractFrames && !TryParsePositive(IntervalSeconds, out _)) return "提取间隔必须大于 0 秒。";
        return null;
    }

    private void ChooseFolder()
    {
        var dialog = new Microsoft.Win32.OpenFolderDialog
        {
            Title = "选择输出位置",
            InitialDirectory = Directory.Exists(OutputFolder) ? OutputFolder : AppPaths.Conversions
        };
        if (dialog.ShowDialog() != true) return;
        if (SelectedMode == ConversionMode.ExtractFrames) _settings.Current.FramesFolder = dialog.FolderName;
        else _settings.Current.ConversionFolder = dialog.FolderName;
        _settings.Save();
        OnPropertyChanged(nameof(OutputFolder));
    }

    private void RevealOutput()
    {
        if (Directory.Exists(OutputPath)) FileLauncher.Open(OutputPath);
        else FileLauncher.Reveal(OutputPath);
    }

    private void Reset()
    {
        SelectedFile = null;
        SelectedOperation = null;
        AvailableOperations.Clear();
        ErrorMessage = null;
        OutputPath = null;
        Progress = 0;
        ProgressDetail = string.Empty;
        ElapsedText = "—";
        RemainingText = "—";
        State = ConverterState.NoFile;
    }

    private void NotifyCommands()
    {
        ChooseFileCommand.NotifyCanExecuteChanged();
        ChooseFolderCommand.NotifyCanExecuteChanged();
        StartCommand.NotifyCanExecuteChanged();
        CancelCommand.NotifyCanExecuteChanged();
        ResetCommand.NotifyCanExecuteChanged();
    }

    private static double Parse(string value) => double.Parse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.CurrentCulture);
    private static bool TryParsePositive(string value, out double result) => double.TryParse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.CurrentCulture, out result) && result > 0;
    private static bool TryParseNonNegative(string value, out double result) => double.TryParse(value, System.Globalization.NumberStyles.Float, System.Globalization.CultureInfo.CurrentCulture, out result) && result >= 0;
    private static string FormatTime(TimeSpan? value) => value.HasValue ? value.Value.ToString(value.Value.TotalHours >= 1 ? @"h\:mm\:ss" : @"m\:ss") : "—";
}
