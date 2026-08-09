using System.Text;

namespace Shizhen.Windows.Services;

public sealed class DiagnosticLogger
{
    private readonly SemaphoreSlim _writeLock = new(1, 1);
    private readonly string _sessionId = DateTime.Now.ToString("yyyyMMdd_HHmmss");

    public string CurrentLogPath => Path.Combine(AppPaths.Logs, $"shizhen_{_sessionId}.log");

    public async Task WriteAsync(string category, string message, Exception? exception = null)
    {
        var builder = new StringBuilder()
            .Append('[').Append(DateTimeOffset.Now.ToString("yyyy-MM-dd HH:mm:ss.fff zzz")).Append("] ")
            .Append('[').Append(category).Append("] ")
            .AppendLine(Sanitize(message));

        if (exception is not null)
        {
            builder.AppendLine(Sanitize(exception.ToString()));
        }

        await _writeLock.WaitAsync();
        try
        {
            await File.AppendAllTextAsync(CurrentLogPath, builder.ToString(), Encoding.UTF8);
        }
        finally
        {
            _writeLock.Release();
        }
    }

    public string ExportDiagnostics(string destinationFolder)
    {
        ArgumentException.ThrowIfNullOrWhiteSpace(destinationFolder);
        Directory.CreateDirectory(destinationFolder);
        var target = Path.Combine(
            destinationFolder,
            $"shizhen_diagnostics_{DateTime.Now:yyyyMMdd_HHmmss}.txt");

        var contents = new StringBuilder()
            .AppendLine("拾帧 Windows 诊断信息")
            .AppendLine($"生成时间：{DateTimeOffset.Now:O}")
            .AppendLine($"系统：{Environment.OSVersion}")
            .AppendLine($"进程架构：{System.Runtime.InteropServices.RuntimeInformation.ProcessArchitecture}")
            .AppendLine($".NET：{Environment.Version}")
            .AppendLine($"yt-dlp：{ToolLocator.FindYtDlp() ?? "未找到"}")
            .AppendLine($"FFmpeg：{ToolLocator.FindFfmpeg() ?? "未找到"}")
            .AppendLine(new string('-', 72));

        if (File.Exists(CurrentLogPath))
        {
            contents.Append(File.ReadAllText(CurrentLogPath, Encoding.UTF8));
        }

        File.WriteAllText(target, contents.ToString(), Encoding.UTF8);
        return target;
    }

    public void WriteImmediate(string category, string message, Exception exception)
    {
        try
        {
            var contents = new StringBuilder()
                .Append('[').Append(DateTimeOffset.Now.ToString("yyyy-MM-dd HH:mm:ss.fff zzz")).Append("] ")
                .Append('[').Append(category).Append("] ")
                .AppendLine(Sanitize(message))
                .AppendLine(Sanitize(exception.ToString()));
            File.AppendAllText(CurrentLogPath, contents.ToString(), Encoding.UTF8);
        }
        catch
        {
            // 崩溃记录本身不应再引发异常。
        }
    }

    private static string Sanitize(string text) =>
        text.Replace(Environment.GetFolderPath(Environment.SpecialFolder.UserProfile), "%USERPROFILE%", StringComparison.OrdinalIgnoreCase);
}
