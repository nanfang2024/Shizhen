using Shizhen.Windows.Models;

namespace Shizhen.Windows.ViewModels;

public sealed class HistoryItemViewModel(HistoryRecord record)
{
    public HistoryRecord Record { get; } = record;
    public long Id => Record.Id;
    public string OperationType => Record.OperationType;
    public string Source => Record.Source;
    public string? OutputPath => Record.OutputPath;
    public string? ErrorReason => Record.ErrorReason;
    public string? Details => Record.Details;
    public string TimeDisplay => Record.CreatedAt.LocalDateTime.ToString("yyyy-MM-dd HH:mm");
    public string StatusDisplay => Record.Status switch
    {
        OperationStatus.Waiting => "等待中",
        OperationStatus.Running => "处理中",
        OperationStatus.Success => "成功",
        OperationStatus.Failed => "失败",
        OperationStatus.Cancelled => "已取消",
        OperationStatus.Interrupted => "意外中断",
        _ => "未知"
    };
    public bool HasOutput => !string.IsNullOrWhiteSpace(OutputPath) && (File.Exists(OutputPath) || Directory.Exists(OutputPath));
    public bool HasError => !string.IsNullOrWhiteSpace(ErrorReason);
}
