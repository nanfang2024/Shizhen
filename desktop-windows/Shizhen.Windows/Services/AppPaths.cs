namespace Shizhen.Windows.Services;

public static class AppPaths
{
    public static string DataRoot { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "Shizhen");

    public static string Downloads { get; } = Path.Combine(
        Environment.GetFolderPath(Environment.SpecialFolder.MyVideos), "拾帧");

    public static string Conversions { get; } = Path.Combine(Downloads, "转换");
    public static string Frames { get; } = Path.Combine(Conversions, "提取图片");
    public static string Logs { get; } = Path.Combine(DataRoot, "Logs");
    public static string PreviewCache { get; } = Path.Combine(DataRoot, "PreviewCache");
    public static string DatabasePath { get; } = Path.Combine(DataRoot, "shizhen.db");
    public static string SettingsPath { get; } = Path.Combine(DataRoot, "settings.json");
    public static string BundledTools { get; } = Path.Combine(AppContext.BaseDirectory, "Tools");

    public static void EnsureCreated()
    {
        Directory.CreateDirectory(DataRoot);
        Directory.CreateDirectory(Downloads);
        Directory.CreateDirectory(Conversions);
        Directory.CreateDirectory(Frames);
        Directory.CreateDirectory(Logs);
        Directory.CreateDirectory(PreviewCache);
        CleanupPreviewCache();
    }

    private static void CleanupPreviewCache()
    {
        try
        {
            var cutoff = DateTime.UtcNow.AddDays(-3);
            foreach (var file in Directory.EnumerateFiles(PreviewCache, "*", SearchOption.TopDirectoryOnly))
            {
                if (File.GetLastWriteTimeUtc(file) < cutoff) File.Delete(file);
            }
        }
        catch
        {
            // Preview files are disposable. Cache cleanup must never block app startup.
        }
    }
}
