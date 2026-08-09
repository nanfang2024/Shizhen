$ErrorActionPreference = 'Stop'

$source = [System.IO.Path]::GetFullPath($PSScriptRoot)
$target = [System.IO.Path]::GetFullPath((Join-Path $env:LOCALAPPDATA 'Programs\Shizhen'))
New-Item -ItemType Directory -Force -Path $target | Out-Null

if (-not $source.Equals($target, [System.StringComparison]::OrdinalIgnoreCase)) {
    Get-ChildItem -LiteralPath $source -Force | ForEach-Object {
        Copy-Item -LiteralPath $_.FullName -Destination $target -Recurse -Force
    }
}

$exe = Join-Path $target 'Shizhen.exe'
if (-not (Test-Path -LiteralPath $exe)) { throw "Shizhen.exe not found in $target" }

$shell = New-Object -ComObject WScript.Shell
$appName = ([char]0x62FE).ToString() + [char]0x5E27
$desktopShortcut = Join-Path ([Environment]::GetFolderPath('Desktop')) "$appName.lnk"
$startMenu = Join-Path ([Environment]::GetFolderPath('Programs')) $appName
New-Item -ItemType Directory -Force -Path $startMenu | Out-Null

foreach ($shortcutPath in @($desktopShortcut, (Join-Path $startMenu "$appName.lnk"))) {
    $shortcut = $shell.CreateShortcut($shortcutPath)
    $shortcut.TargetPath = $exe
    $shortcut.WorkingDirectory = $target
    $shortcut.IconLocation = "$exe,0"
    $shortcut.Description = 'Shizhen media extractor and local converter'
    $shortcut.Save()
}

Write-Host "Shizhen installed at $target"
Start-Process -FilePath $exe
