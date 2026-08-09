param(
    [string]$Destination = (Join-Path $PSScriptRoot '..\Shizhen.Windows\Tools')
)

$ErrorActionPreference = 'Stop'
$destinationPath = [System.IO.Path]::GetFullPath($Destination)
$licensesPath = Join-Path $destinationPath 'LICENSES'
New-Item -ItemType Directory -Force -Path $destinationPath, $licensesPath | Out-Null

$ytDlpVersion = '2026.08.04.234419'
$ytDlpSha256 = 'e78500d301b5de3a9280a418f6dd45604c4d85b718b0a2447c1b0aa9699e2689'
$ytDlpTarget = Join-Path $destinationPath 'yt-dlp.exe'
$ytDlpDownload = "$ytDlpTarget.download"
Invoke-WebRequest -UseBasicParsing "https://github.com/yt-dlp/yt-dlp-nightly-builds/releases/download/$ytDlpVersion/yt-dlp.exe" -OutFile $ytDlpDownload
$actualHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $ytDlpDownload).Hash.ToLowerInvariant()
if ($actualHash -ne $ytDlpSha256) {
    Remove-Item -LiteralPath $ytDlpDownload -Force
    throw "yt-dlp checksum mismatch. Expected $ytDlpSha256, actual $actualHash"
}
Move-Item -LiteralPath $ytDlpDownload -Destination $ytDlpTarget -Force
Invoke-WebRequest -UseBasicParsing "https://raw.githubusercontent.com/yt-dlp/yt-dlp/5d6b8c8cd19785c3086ae3a9ec618c45e25eb3bc/LICENSE" -OutFile (Join-Path $licensesPath 'yt-dlp-LICENSE.txt')

$denoVersion = '2.8.1'
$denoZip = Join-Path $env:TEMP "deno-$denoVersion-x64.zip"
$denoSum = "$denoZip.sha256sum"
$denoUrl = "https://github.com/denoland/deno/releases/download/v$denoVersion/deno-x86_64-pc-windows-msvc.zip"
Invoke-WebRequest -UseBasicParsing $denoUrl -OutFile $denoZip
Invoke-WebRequest -UseBasicParsing "$denoUrl.sha256sum" -OutFile $denoSum
$denoSumText = Get-Content -LiteralPath $denoSum -Raw
$hashMatch = [regex]::Match($denoSumText, '(?im)^Hash\s*:\s*([0-9a-f]{64})\s*$')
if (-not $hashMatch.Success) { throw 'Unable to parse the official Deno checksum file.' }
$expectedDenoHash = $hashMatch.Groups[1].Value.ToLowerInvariant()
$actualDenoHash = (Get-FileHash -Algorithm SHA256 -LiteralPath $denoZip).Hash.ToLowerInvariant()
if ($actualDenoHash -ne $expectedDenoHash) {
    throw "Deno checksum mismatch. Expected $expectedDenoHash, actual $actualDenoHash"
}
Expand-Archive -LiteralPath $denoZip -DestinationPath $destinationPath -Force
Invoke-WebRequest -UseBasicParsing "https://raw.githubusercontent.com/denoland/deno/v$denoVersion/LICENSE.md" -OutFile (Join-Path $licensesPath 'deno-LICENSE.md')

$ffmpegCommand = Get-Command ffmpeg -ErrorAction SilentlyContinue
$ffprobeCommand = Get-Command ffprobe -ErrorAction SilentlyContinue
if ($null -eq $ffmpegCommand -or $null -eq $ffprobeCommand) {
    throw 'FFmpeg/FFprobe not found. Run: winget install Gyan.FFmpeg'
}
Copy-Item -LiteralPath $ffmpegCommand.Source -Destination (Join-Path $destinationPath 'ffmpeg.exe') -Force
Copy-Item -LiteralPath $ffprobeCommand.Source -Destination (Join-Path $destinationPath 'ffprobe.exe') -Force
$ffmpegRoot = Split-Path (Split-Path $ffmpegCommand.Source -Parent) -Parent
$ffmpegLicense = Join-Path $ffmpegRoot 'LICENSE'
$ffmpegReadme = Join-Path $ffmpegRoot 'README.txt'
if (Test-Path -LiteralPath $ffmpegLicense) { Copy-Item -LiteralPath $ffmpegLicense -Destination (Join-Path $licensesPath 'ffmpeg-GPL-3.0.txt') -Force }
if (Test-Path -LiteralPath $ffmpegReadme) { Copy-Item -LiteralPath $ffmpegReadme -Destination (Join-Path $licensesPath 'ffmpeg-build-README.txt') -Force }

@(
    "yt-dlp=$ytDlpVersion sha256=$ytDlpSha256",
    "deno=$denoVersion sha256=$actualDenoHash",
    (& $ffmpegCommand.Source -version | Select-Object -First 1)
) | Set-Content -LiteralPath (Join-Path $destinationPath 'VERSIONS.txt') -Encoding UTF8

Write-Host "Tools installed at $destinationPath"
& $ytDlpTarget --version
& (Join-Path $destinationPath 'deno.exe') --version | Select-Object -First 1
& (Join-Path $destinationPath 'ffmpeg.exe') -version | Select-Object -First 1
