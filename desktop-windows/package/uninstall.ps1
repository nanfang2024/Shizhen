$ErrorActionPreference = 'Stop'

$expectedTarget = [System.IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'Programs\Shizhen'))
$scriptDirectory = [System.IO.Path]::GetFullPath($PSScriptRoot)
if (-not $scriptDirectory.Equals($expectedTarget, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw "For safety, run this script only from $expectedTarget"
}

Get-Process Shizhen -ErrorAction SilentlyContinue | Stop-Process -Force
$appName = ([char]0x62FE).ToString() + [char]0x5E27
$desktopShortcut = Join-Path ([Environment]::GetFolderPath('Desktop')) "$appName.lnk"
$startMenu = Join-Path ([Environment]::GetFolderPath('Programs')) $appName
if (Test-Path -LiteralPath $desktopShortcut) { Remove-Item -LiteralPath $desktopShortcut -Force }
if (Test-Path -LiteralPath $startMenu) { Remove-Item -LiteralPath $startMenu -Recurse -Force }

Write-Host 'Application files will be removed. User history and settings under LocalAppData\Shizhen are preserved.'
Start-Process powershell.exe -WindowStyle Hidden -ArgumentList @(
    '-NoProfile',
    '-Command',
    "Start-Sleep -Milliseconds 700; Remove-Item -LiteralPath '$expectedTarget' -Recurse -Force"
)
