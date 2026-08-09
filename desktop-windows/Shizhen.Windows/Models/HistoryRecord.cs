namespace Shizhen.Windows.Models;

public enum OperationStatus
{
    Waiting,
    Running,
    Success,
    Failed,
    Cancelled,
    Interrupted
}

public sealed record HistoryRecord(
    long Id,
    DateTimeOffset CreatedAt,
    string OperationType,
    string Source,
    string? OutputPath,
    OperationStatus Status,
    string? ErrorReason,
    string? Details);

public sealed record OperationProgress(
    double Percent,
    string Status,
    long? CompletedBytes = null,
    long? TotalBytes = null,
    TimeSpan? Elapsed = null,
    TimeSpan? Remaining = null);

public sealed record ConversionResult(bool Success, string? OutputPath, string? ErrorMessage);
