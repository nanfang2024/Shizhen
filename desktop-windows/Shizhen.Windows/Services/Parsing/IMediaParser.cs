using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services.Parsing;

public interface IMediaParser
{
    string Name { get; }
    bool CanHandle(string url);
    Task<ParsedMedia> ParseAsync(string url, CancellationToken cancellationToken = default);
}
