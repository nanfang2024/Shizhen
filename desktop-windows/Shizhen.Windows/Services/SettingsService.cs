using System.Text.Json;

namespace Shizhen.Windows.Services;

public sealed class AppSettings
{
    public bool UseDarkTheme { get; set; }
    public string DownloadFolder { get; set; } = AppPaths.Downloads;
    public string ConversionFolder { get; set; } = AppPaths.Conversions;
    public string FramesFolder { get; set; } = AppPaths.Frames;
    public string DiagnosticsFolder { get; set; } = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
}

public sealed class SettingsService
{
    public AppSettings Current { get; private set; } = new();

    public void Load()
    {
        try
        {
            if (File.Exists(AppPaths.SettingsPath))
            {
                Current = JsonSerializer.Deserialize<AppSettings>(File.ReadAllText(AppPaths.SettingsPath)) ?? new AppSettings();
            }
        }
        catch
        {
            Current = new AppSettings();
        }
    }

    public void Save()
    {
        File.WriteAllText(AppPaths.SettingsPath, JsonSerializer.Serialize(Current, new JsonSerializerOptions { WriteIndented = true }));
    }
}
