using Shizhen.Windows.Core;
using Shizhen.Windows.Services;

namespace Shizhen.Windows.ViewModels;

public enum AppPage
{
    Extractor,
    Converter,
    History
}

public sealed class MainViewModel : ObservableObject
{
    private readonly SettingsService _settings;
    private object _currentViewModel;
    private AppPage _currentPage = AppPage.Extractor;
    private bool _isDarkTheme;

    public MainViewModel(ExtractorViewModel extractor, ConverterViewModel converter, HistoryViewModel history, SettingsService settings)
    {
        Extractor = extractor;
        Converter = converter;
        History = history;
        _settings = settings;
        _isDarkTheme = settings.Current.UseDarkTheme;
        _currentViewModel = extractor;
        NavigateCommand = new AsyncRelayCommand<string>(NavigateAsync);
        ToggleThemeCommand = new RelayCommand(ToggleTheme);
    }

    public ExtractorViewModel Extractor { get; }
    public ConverterViewModel Converter { get; }
    public HistoryViewModel History { get; }
    public AsyncRelayCommand<string> NavigateCommand { get; }
    public RelayCommand ToggleThemeCommand { get; }

    public object CurrentViewModel
    {
        get => _currentViewModel;
        private set => SetProperty(ref _currentViewModel, value);
    }

    public AppPage CurrentPage
    {
        get => _currentPage;
        private set
        {
            if (SetProperty(ref _currentPage, value))
            {
                OnPropertyChanged(nameof(IsExtractorSelected));
                OnPropertyChanged(nameof(IsConverterSelected));
                OnPropertyChanged(nameof(IsHistorySelected));
            }
        }
    }

    public bool IsExtractorSelected => CurrentPage == AppPage.Extractor;
    public bool IsConverterSelected => CurrentPage == AppPage.Converter;
    public bool IsHistorySelected => CurrentPage == AppPage.History;
    public bool IsDarkTheme { get => _isDarkTheme; private set => SetProperty(ref _isDarkTheme, value); }
    public string ThemeLabel => IsDarkTheme ? "切换浅色" : "切换深色";

    private async Task NavigateAsync(string? page)
    {
        switch (page)
        {
            case "Converter":
                CurrentPage = AppPage.Converter;
                CurrentViewModel = Converter;
                break;
            case "History":
                CurrentPage = AppPage.History;
                CurrentViewModel = History;
                await History.RefreshAsync();
                break;
            default:
                CurrentPage = AppPage.Extractor;
                CurrentViewModel = Extractor;
                break;
        }
    }

    private void ToggleTheme()
    {
        IsDarkTheme = !IsDarkTheme;
        _settings.Current.UseDarkTheme = IsDarkTheme;
        _settings.Save();
        ThemeManager.Apply(IsDarkTheme);
        OnPropertyChanged(nameof(ThemeLabel));
    }
}
