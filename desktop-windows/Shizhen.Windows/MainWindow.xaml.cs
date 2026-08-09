using System.Windows;
using Shizhen.Windows.ViewModels;

namespace Shizhen.Windows;

public partial class MainWindow : Window
{
    public MainWindow()
    {
        InitializeComponent();
    }

    private async void OnDrop(object sender, DragEventArgs e)
    {
        if (DataContext is not MainViewModel viewModel) return;
        if (e.Data.GetDataPresent(DataFormats.FileDrop)
            && e.Data.GetData(DataFormats.FileDrop) is string[] files
            && files.FirstOrDefault() is { } file)
        {
            await viewModel.Converter.AcceptFileAsync(file);
            viewModel.NavigateCommand.Execute("Converter");
            e.Handled = true;
            return;
        }
        if (e.Data.GetDataPresent(DataFormats.Text) && e.Data.GetData(DataFormats.Text) is string text)
        {
            viewModel.Extractor.AcceptSharedText(text);
            viewModel.NavigateCommand.Execute("Extractor");
            e.Handled = true;
        }
    }
}
