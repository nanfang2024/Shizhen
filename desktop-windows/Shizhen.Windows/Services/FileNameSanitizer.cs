namespace Shizhen.Windows.Services;

public static class FileNameSanitizer
{
    private static readonly string[] ReservedNames =
        ["CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9"];

    public static string Sanitize(string? value, string fallback = "media")
    {
        var name = string.IsNullOrWhiteSpace(value) ? fallback : value.Trim();
        foreach (var invalid in Path.GetInvalidFileNameChars())
        {
            name = name.Replace(invalid, '_');
        }
        name = name.Trim(' ', '.');
        if (name.Length > 100) name = name[..100].Trim();
        if (string.IsNullOrWhiteSpace(name) || ReservedNames.Contains(name, StringComparer.OrdinalIgnoreCase))
        {
            name = fallback;
        }
        return name;
    }
}
