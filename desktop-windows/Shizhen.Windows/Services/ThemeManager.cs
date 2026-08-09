using System.Windows;
using System.Windows.Media;

namespace Shizhen.Windows.Services;

public static class ThemeManager
{
    private static readonly IReadOnlyDictionary<string, string> Light = new Dictionary<string, string>
    {
        ["AppBackgroundBrush"] = "#F5FAFF",
        ["SidebarBrush"] = "#ECF7FF",
        ["SurfaceBrush"] = "#FFFFFF",
        ["SurfaceSecondaryBrush"] = "#E8F5FF",
        ["PrimaryBrush"] = "#138FD3",
        ["PrimaryHoverBrush"] = "#0A7FBD",
        ["PrimaryPressedBrush"] = "#0A6FA8",
        ["PrimaryContainerBrush"] = "#D9F1FF",
        ["TextPrimaryBrush"] = "#10233D",
        ["TextSecondaryBrush"] = "#52657A",
        ["BorderBrush"] = "#C7DDEC",
        ["AccentBrush"] = "#6574A6",
        ["SuccessBrush"] = "#177A45",
        ["SuccessContainerBrush"] = "#DDF5E7",
        ["WarningBrush"] = "#9A6200",
        ["WarningContainerBrush"] = "#FFF0C7",
        ["ErrorBrush"] = "#B42318",
        ["ErrorContainerBrush"] = "#FDE8E7",
        ["OnPrimaryBrush"] = "#FFFFFF"
    };

    private static readonly IReadOnlyDictionary<string, string> Dark = new Dictionary<string, string>
    {
        ["AppBackgroundBrush"] = "#071521",
        ["SidebarBrush"] = "#091B2A",
        ["SurfaceBrush"] = "#0E2236",
        ["SurfaceSecondaryBrush"] = "#15314A",
        ["PrimaryBrush"] = "#53BEF0",
        ["PrimaryHoverBrush"] = "#71CAF2",
        ["PrimaryPressedBrush"] = "#2FA8DE",
        ["PrimaryContainerBrush"] = "#183F58",
        ["TextPrimaryBrush"] = "#EDF8FF",
        ["TextSecondaryBrush"] = "#B7CDDC",
        ["BorderBrush"] = "#294960",
        ["AccentBrush"] = "#93A1D1",
        ["SuccessBrush"] = "#72D79E",
        ["SuccessContainerBrush"] = "#153B2B",
        ["WarningBrush"] = "#F5C15D",
        ["WarningContainerBrush"] = "#453716",
        ["ErrorBrush"] = "#FFB4AB",
        ["ErrorContainerBrush"] = "#4B2020",
        ["OnPrimaryBrush"] = "#042333"
    };

    public static void Apply(bool dark)
    {
        var palette = dark ? Dark : Light;
        foreach (var (key, value) in palette)
        {
            Application.Current.Resources[key] = new SolidColorBrush((Color)ColorConverter.ConvertFromString(value));
        }
    }
}
