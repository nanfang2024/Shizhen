namespace Shizhen.Windows.Services;

public static class ToolLocator
{
    public static string? FindYtDlp() => Find("yt-dlp.exe");
    public static string? FindFfmpeg() => Find("ffmpeg.exe");
    public static string? FindFfprobe() => Find("ffprobe.exe");
    public static string? FindDeno() => Find("deno.exe");

    private static string? Find(string executable)
    {
        var bundled = Path.Combine(AppPaths.BundledTools, executable);
        if (File.Exists(bundled))
        {
            return bundled;
        }

        var appLocal = Path.Combine(AppContext.BaseDirectory, executable);
        if (File.Exists(appLocal))
        {
            return appLocal;
        }

        var paths = (Environment.GetEnvironmentVariable("PATH") ?? string.Empty)
            .Split(Path.PathSeparator, StringSplitOptions.RemoveEmptyEntries | StringSplitOptions.TrimEntries);
        return paths.Select(path => Path.Combine(path, executable)).FirstOrDefault(File.Exists);
    }
}
