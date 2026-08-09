using System.Collections.ObjectModel;
using System.Windows;
using Shizhen.Windows.Core;
using Shizhen.Windows.Services;

namespace Shizhen.Windows.ViewModels;

public sealed class HistoryViewModel : ObservableObject
{
    private readonly HistoryRepository _history;
    private readonly DiagnosticLogger _logger;
    private readonly SettingsService _settings;
    private HistoryItemViewModel? _selectedItem;
    private string _statusMessage = "历史记录保存在本机。";

    public HistoryViewModel(HistoryRepository history, DiagnosticLogger logger, SettingsService settings)
    {
        _history = history;
        _logger = logger;
        _settings = settings;
        RefreshCommand = new AsyncRelayCommand(RefreshAsync);
        DeleteCommand = new AsyncRelayCommand<HistoryItemViewModel>(DeleteAsync, item => item is not null);
        ClearCommand = new AsyncRelayCommand(ClearAsync, () => Items.Count > 0);
        OpenCommand = new RelayCommand<HistoryItemViewModel>(Open, item => item?.HasOutput == true);
        ExportDiagnosticsCommand = new RelayCommand(ExportDiagnostics);
        OpenLogFolderCommand = new RelayCommand(() => FileLauncher.Open(AppPaths.Logs));
    }

    public ObservableCollection<HistoryItemViewModel> Items { get; } = [];
    public AsyncRelayCommand RefreshCommand { get; }
    public AsyncRelayCommand<HistoryItemViewModel> DeleteCommand { get; }
    public AsyncRelayCommand ClearCommand { get; }
    public RelayCommand<HistoryItemViewModel> OpenCommand { get; }
    public RelayCommand ExportDiagnosticsCommand { get; }
    public RelayCommand OpenLogFolderCommand { get; }

    public HistoryItemViewModel? SelectedItem
    {
        get => _selectedItem;
        set => SetProperty(ref _selectedItem, value);
    }

    public string StatusMessage { get => _statusMessage; private set => SetProperty(ref _statusMessage, value); }
    public bool IsEmpty => Items.Count == 0;

    public async Task RefreshAsync()
    {
        var records = await _history.GetAllAsync();
        Items.Clear();
        foreach (var record in records) Items.Add(new HistoryItemViewModel(record));
        OnPropertyChanged(nameof(IsEmpty));
        ClearCommand.NotifyCanExecuteChanged();
        StatusMessage = records.Count == 0 ? "还没有历史记录。" : $"共 {records.Count} 条记录";
    }

    private async Task DeleteAsync(HistoryItemViewModel? item)
    {
        if (item is null) return;
        if (MessageBox.Show("只删除这条记录，不会删除输出文件。", "删除历史记录", MessageBoxButton.OKCancel, MessageBoxImage.Question) != MessageBoxResult.OK) return;
        await _history.DeleteAsync(item.Id);
        Items.Remove(item);
        OnPropertyChanged(nameof(IsEmpty));
        ClearCommand.NotifyCanExecuteChanged();
        StatusMessage = "已删除一条历史记录。";
    }

    private async Task ClearAsync()
    {
        if (MessageBox.Show("清空全部历史记录？输出文件不会被删除。", "清空历史", MessageBoxButton.OKCancel, MessageBoxImage.Warning) != MessageBoxResult.OK) return;
        await _history.ClearAsync();
        Items.Clear();
        OnPropertyChanged(nameof(IsEmpty));
        ClearCommand.NotifyCanExecuteChanged();
        StatusMessage = "历史记录已清空。";
    }

    private void Open(HistoryItemViewModel? item)
    {
        if (item?.OutputPath is null) return;
        try { FileLauncher.Open(item.OutputPath); }
        catch (UserFacingException exception) { StatusMessage = exception.Message; }
    }

    private void ExportDiagnostics()
    {
        try
        {
            var initialDirectory = Directory.Exists(_settings.Current.DiagnosticsFolder)
                ? _settings.Current.DiagnosticsFolder
                : Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            var dialog = new Microsoft.Win32.OpenFolderDialog
            {
                Title = "选择诊断日志保存位置",
                InitialDirectory = initialDirectory
            };
            if (dialog.ShowDialog() != true)
            {
                StatusMessage = "已取消导出诊断日志。";
                return;
            }

            _settings.Current.DiagnosticsFolder = dialog.FolderName;
            _settings.Save();
            var path = _logger.ExportDiagnostics(dialog.FolderName);
            StatusMessage = $"诊断文件已导出：{Path.GetFileName(path)}";
            FileLauncher.Reveal(path);
        }
        catch (Exception exception)
        {
            StatusMessage = $"导出诊断失败：{exception.Message}";
        }
    }
}
