using System.Text.RegularExpressions;

namespace Shizhen.Windows.Services;

public static partial class UrlExtractor
{
    private static readonly char[] TrailingPunctuation =
        ['。', '，', '、', '；', '：', '！', '？', '）', '】', '》', '」', '』', '”', '\'', '"', ',', '.', ';', ':', '!', '?', ')', ']', '}'];
    private static readonly char[] HardTerminators =
        ['。', '，', '、', '；', '：', '！', '？', '（', '）', '【', '】', '《', '》', '「', '」', '『', '』', '“', '”'];

    public static string? ExtractFirst(string? text)
    {
        if (string.IsNullOrWhiteSpace(text))
        {
            return null;
        }

        foreach (Match match in HttpUrlRegex().Matches(text))
        {
            var candidate = match.Value.Trim();
            var terminatorIndex = candidate.IndexOfAny(HardTerminators);
            if (terminatorIndex >= 0)
            {
                candidate = candidate[..terminatorIndex];
            }
            candidate = candidate.TrimEnd(TrailingPunctuation);
            if (Uri.TryCreate(candidate, UriKind.Absolute, out var uri)
                && (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps)
                && !string.IsNullOrWhiteSpace(uri.Host)
                && uri.Host.Contains('.'))
            {
                return uri.AbsoluteUri;
            }
        }

        return null;
    }

    [GeneratedRegex(@"https?://[^\s<>\u3000]+", RegexOptions.IgnoreCase | RegexOptions.CultureInvariant)]
    private static partial Regex HttpUrlRegex();
}
