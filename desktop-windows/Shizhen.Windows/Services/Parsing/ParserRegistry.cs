using Shizhen.Windows.Models;

namespace Shizhen.Windows.Services.Parsing;

public sealed class ParserRegistry(IEnumerable<IMediaParser> parsers, DiagnosticLogger logger)
{
    private readonly IReadOnlyList<IMediaParser> _parsers = parsers.ToList();

    public async Task<ParsedMedia> ParseAsync(string url, CancellationToken cancellationToken = default)
    {
        var errors = new List<UserFacingException>();
        foreach (var parser in _parsers.Where(parser => parser.CanHandle(url)))
        {
            try
            {
                await logger.WriteAsync("PARSE", $"使用 {parser.Name} 解析 {new Uri(url).Host}");
                var result = await parser.ParseAsync(url, cancellationToken);
                if (result.Items.Count > 0)
                {
                    return result;
                }
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch (UserFacingException exception)
            {
                await logger.WriteAsync("PARSE", $"{parser.Name} 解析未成功：{exception.Message}", exception);
                if (PlatformDetector.Detect(url) == "YouTube"
                    && exception.Message.Contains("请更换节点重试", StringComparison.Ordinal))
                {
                    throw;
                }
                if (PlatformDetector.Detect(url) == "Facebook"
                    && exception.Message.Contains("登录", StringComparison.Ordinal))
                {
                    throw;
                }
                if (PlatformDetector.Detect(url) == "快手"
                    && exception.Message.Contains("平台访问验证", StringComparison.Ordinal))
                {
                    throw;
                }
                errors.Add(exception);
            }
            catch (Exception exception)
            {
                await logger.WriteAsync("PARSE", $"{parser.Name} 出现未处理异常", exception);
                errors.Add(new UserFacingException("暂时无法解析这个链接，可能是平台规则发生变化。", exception.Message, exception));
            }
        }

        if (PlatformDetector.Detect(url) == "Facebook" && errors.Count > 0)
        {
            throw new UserFacingException(
                "该资源需要登录或存在访问限制，本工具不支持提取。",
                string.Join(Environment.NewLine, errors.Select(error => error.TechnicalDetails ?? error.Message)));
        }

        var preferred = errors.FirstOrDefault(error => error.Message.Contains("登录", StringComparison.Ordinal))
            ?? errors.FirstOrDefault(error => error.Message.Contains("节点", StringComparison.Ordinal))
            ?? errors.LastOrDefault();
        throw preferred ?? new UserFacingException("未发现可下载的公开媒体资源。");
    }
}
