namespace Shizhen.Windows.Services;

public static class PlatformDetector
{
    private static readonly (string Name, string[] Hosts)[] Platforms =
    [
        ("西瓜视频", ["ixigua.com", "xigua.com"]),
        ("抖音", ["douyin.com", "iesdouyin.com"]),
        ("哔哩哔哩", ["bilibili.com", "b23.tv"]),
        ("小红书", ["xiaohongshu.com", "xhslink.com"]),
        ("快手", ["kuaishou.com", "chenzhongtech.com"]),
        ("微博", ["weibo.com", "weibo.cn", "weibocdn.com"]),
        ("豆包", ["doubao.com", "volces.com", "byteimg.com"]),
        ("YouTube", ["youtube.com", "youtu.be"]),
        ("Instagram", ["instagram.com"]),
        ("Facebook", ["facebook.com", "fb.watch"]),
        ("X / Twitter", ["twitter.com", "x.com", "t.co"]),
        ("TikTok", ["tiktok.com"]),
        ("网易云音乐", ["music.163.com", "163cn.tv"]),
        ("QQ音乐", ["y.qq.com", "i.y.qq.com", "c.y.qq.com"]),
        ("酷狗音乐", ["kugou.com", "kugou.net"]),
        ("酷我音乐", ["kuwo.cn"])
    ];

    public static string Detect(string url)
    {
        if (!Uri.TryCreate(url, UriKind.Absolute, out var uri))
        {
            return "未知平台";
        }

        var host = uri.IdnHost.ToLowerInvariant();
        if (host.StartsWith("www.", StringComparison.Ordinal))
        {
            host = host[4..];
        }
        foreach (var (name, hosts) in Platforms)
        {
            if (hosts.Any(candidate => host.Equals(candidate, StringComparison.OrdinalIgnoreCase)
                || host.EndsWith('.' + candidate, StringComparison.OrdinalIgnoreCase)))
            {
                return name;
            }
        }

        return uri.Host;
    }
}
