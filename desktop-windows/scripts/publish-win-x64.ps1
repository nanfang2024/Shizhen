param(
    [string]$Version = '1.0.7'
)

$ErrorActionPreference = 'Stop'
$desktopRoot = [System.IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$repoRoot = [System.IO.Path]::GetFullPath((Join-Path $desktopRoot '..'))
$project = Join-Path $desktopRoot 'Shizhen.Windows\Shizhen.Windows.csproj'
$tools = Join-Path $desktopRoot 'Shizhen.Windows\Tools'
$artifacts = [System.IO.Path]::GetFullPath((Join-Path $desktopRoot 'artifacts'))
$output = [System.IO.Path]::GetFullPath((Join-Path $artifacts "Shizhen-Windows-x64-$Version"))
$zip = Join-Path $artifacts "Shizhen-Windows-x64-$Version.zip"

foreach ($required in @('yt-dlp.exe', 'deno.exe', 'ffmpeg.exe', 'ffprobe.exe')) {
    if (-not (Test-Path -LiteralPath (Join-Path $tools $required))) {
        throw "Missing $required. Run scripts\install-tools.ps1 first."
    }
}

$dotnet = (Get-Command dotnet -ErrorAction SilentlyContinue).Source
if ($null -eq $dotnet -or -not (& $dotnet --list-sdks)) {
    $localDotnet = [System.IO.Path]::GetFullPath((Join-Path $desktopRoot '..\..\.dotnet-sdk\dotnet.exe'))
    if (-not (Test-Path -LiteralPath $localDotnet)) { throw 'A .NET 8 SDK is required.' }
    $dotnet = $localDotnet
}

New-Item -ItemType Directory -Force -Path $artifacts | Out-Null
if (-not $output.StartsWith($artifacts + [System.IO.Path]::DirectorySeparatorChar, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Unsafe publish output path.'
}
if (Test-Path -LiteralPath $output) { Remove-Item -LiteralPath $output -Recurse -Force }
if (Test-Path -LiteralPath $zip) { Remove-Item -LiteralPath $zip -Force }

& $dotnet publish $project -c Release -r win-x64 --self-contained true -p:Platform=x64 -p:DebugType=None -p:DebugSymbols=false -o $output
if ($LASTEXITCODE -ne 0) { throw "dotnet publish failed with exit code $LASTEXITCODE" }

Copy-Item -LiteralPath (Join-Path $desktopRoot 'package\install.ps1') -Destination $output -Force
Copy-Item -LiteralPath (Join-Path $desktopRoot 'package\uninstall.ps1') -Destination $output -Force
Copy-Item -LiteralPath (Join-Path $desktopRoot 'README.md') -Destination (Join-Path $output 'README-WINDOWS.md') -Force
Copy-Item -LiteralPath (Join-Path $desktopRoot 'KNOWN_LIMITATIONS.md') -Destination $output -Force
Copy-Item -LiteralPath (Join-Path $desktopRoot 'THIRD_PARTY_NOTICES.md') -Destination (Join-Path $output 'THIRD_PARTY_NOTICES-WINDOWS.md') -Force
Copy-Item -LiteralPath (Join-Path $desktopRoot 'ICON_NOTICE.md') -Destination $output -Force
Copy-Item -LiteralPath (Join-Path $repoRoot 'LICENSE') -Destination $output -Force

Compress-Archive -Path (Join-Path $output '*') -DestinationPath $zip -CompressionLevel Optimal
$hash = Get-FileHash -Algorithm SHA256 -LiteralPath $zip
"$($hash.Hash.ToLowerInvariant())  $([System.IO.Path]::GetFileName($zip))" | Set-Content -LiteralPath "$zip.sha256" -Encoding ASCII

Write-Host "Publish folder: $output"
Write-Host "ZIP: $zip"
Write-Host "SHA256: $($hash.Hash.ToLowerInvariant())"
