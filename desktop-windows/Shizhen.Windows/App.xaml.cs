using System.Windows;
using System.Windows.Threading;
using Shizhen.Windows.Services;
using Shizhen.Windows.Services.Parsing;
using Shizhen.Windows.ViewModels;
using Shizhen.Windows.Views;

namespace Shizhen.Windows;

public partial class App : Application
{
    private SettingsService? _settings;
    private DiagnosticLogger? _logger;
    private int _showingUnhandledError;

    protected override async void OnStartup(StartupEventArgs e)
    {
        base.OnStartup(e);
        AppPaths.EnsureCreated();
        DispatcherUnhandledException += OnDispatcherUnhandledException;
        AppDomain.CurrentDomain.UnhandledException += OnAppDomainUnhandledException;
        TaskScheduler.UnobservedTaskException += OnUnobservedTaskException;
        _settings = new SettingsService();
        _settings.Load();
        ThemeManager.Apply(_settings.Current.UseDarkTheme);

        var splash = new SplashWindow();
        splash.Show();
        var minimumSplash = Task.Delay(650);

        try
        {
            var logger = _logger = new DiagnosticLogger();
            await logger.WriteAsync("APP", "拾帧 Windows 启动");
            var processRunner = new ProcessRunner(logger);
            var history = new HistoryRepository(logger);
            await history.InitializeAsync();
            var douyinParser = new DouyinShareMediaParser(logger);
            var bilibiliParser = new BilibiliPublicMediaParser(logger);
            var xParser = new XPublicPostParser(processRunner, logger);
            var instagramParser = new InstagramPublicPostParser(processRunner, logger);
            var kuaishouParser = new KuaishouPublicMediaParser(processRunner, logger);
            var ytDlpParser = new YtDlpMediaParser(processRunner);
            var genericParser = new GenericMediaParser(logger);
            var parserRegistry = new ParserRegistry(
                [douyinParser, bilibiliParser, xParser, instagramParser, kuaishouParser, ytDlpParser, genericParser],
                logger);
            var downloadService = new DownloadService(processRunner, logger);
            var probeService = new MediaProbeService(processRunner);
            var converter = new FfmpegMediaConverter(processRunner, logger);

            var extractorViewModel = new ExtractorViewModel(parserRegistry, downloadService, history, _settings, logger);
            var converterViewModel = new ConverterViewModel(probeService, converter, history, _settings);
            var historyViewModel = new HistoryViewModel(history, logger, _settings);
            var mainViewModel = new MainViewModel(extractorViewModel, converterViewModel, historyViewModel, _settings);

            if (e.Args.Length > 0)
            {
                extractorViewModel.AcceptSharedText(string.Join(' ', e.Args));
            }

            var mainWindow = new MainWindow { DataContext = mainViewModel };
            await minimumSplash;
            MainWindow = mainWindow;
            ShutdownMode = ShutdownMode.OnMainWindowClose;
            splash.Close();
            mainWindow.Show();
        }
        catch (Exception exception)
        {
            splash.Close();
            MessageBox.Show($"拾帧启动失败：{exception.Message}", "启动失败", MessageBoxButton.OK, MessageBoxImage.Error);
            Shutdown(-1);
        }
    }

    protected override void OnExit(ExitEventArgs e)
    {
        _settings?.Save();
        base.OnExit(e);
    }

    private void OnDispatcherUnhandledException(object sender, DispatcherUnhandledExceptionEventArgs e)
    {
        _logger?.WriteImmediate("UNHANDLED", "界面发生未处理异常，已阻止应用退出。", e.Exception);
        e.Handled = true;
        if (Interlocked.Exchange(ref _showingUnhandledError, 1) != 0) return;
        try
        {
            MessageBox.Show("操作未能完成，拾帧已阻止程序闪退。\n请在“历史记录”中导出诊断日志。", "拾帧", MessageBoxButton.OK, MessageBoxImage.Error);
        }
        finally
        {
            Interlocked.Exchange(ref _showingUnhandledError, 0);
        }
    }

    private void OnAppDomainUnhandledException(object? sender, UnhandledExceptionEventArgs e)
    {
        if (e.ExceptionObject is Exception exception)
        {
            _logger?.WriteImmediate("FATAL", "应用发生致命异常。", exception);
        }
    }

    private void OnUnobservedTaskException(object? sender, UnobservedTaskExceptionEventArgs e)
    {
        _logger?.WriteImmediate("TASK", "后台任务发生未观察异常。", e.Exception);
        e.SetObserved();
    }
}
