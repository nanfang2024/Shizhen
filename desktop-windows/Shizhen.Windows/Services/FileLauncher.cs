using System.Diagnostics;

namespace Shizhen.Windows.Services;

public static class FileLauncher
{
    public static void Open(string? path)
    {
        if (string.IsNullOrWhiteSpace(path) || (!File.Exists(path) && !Directory.Exists(path)))
        {
            throw new UserFacingException("文件已经移动或删除。");
        }
        Process.Start(new ProcessStartInfo(path) { UseShellExecute = true });
    }

    public static void Reveal(string? path)
    {
        if (string.IsNullOrWhiteSpace(path) || !File.Exists(path))
        {
            throw new UserFacingException("文件已经移动或删除。");
        }
        Process.Start(new ProcessStartInfo("explorer.exe", $"/select,\"{path}\"") { UseShellExecute = true });
    }
}
