# Player installer for Windows and macOS: one zip (mob-talk-installer-<version>.zip) with installer\install-windows.bat
# + .ps1, installer\install-macos.command, the README for players and the client .mrpack (export-mrpack.ps1, the server
# address preset with -ServerAddress). A player unpacks it and double-clicks the script for their system: Prism Launcher
# is installed if missing and the pack is imported. Attach the zip (and the .mrpack) to the GitHub release.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\build-installer.ps1 [-Mrpack <file.mrpack>]
#        [-FromPack mob-talk-pack] [-ServerAddress host[:port]] [-Out <file.zip>]
param([string]$Mrpack = '', [string]$FromPack = 'mob-talk-pack', [string]$ServerAddress = '', [string]$Out = '')
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
function Say($t) { Write-Host "$(Get-Date -Format HH:mm:ss) $t" }
$repo = Split-Path -Parent $PSScriptRoot
$src = Join-Path $repo 'installer'

if (-not $Mrpack) {
    if (-not $ServerAddress) { Say 'no -ServerAddress: the pack will not list the server under Multiplayer' }
    $Mrpack = @(& (Join-Path $PSScriptRoot 'export-mrpack.ps1') -FromPack $FromPack -ServerAddress $ServerAddress)[-1]
}
$Mrpack = (Resolve-Path -LiteralPath $Mrpack).Path
$version = [IO.Path]::GetFileNameWithoutExtension($Mrpack) -replace '^mob-talk-pack-', ''
if (-not $Out) { $Out = Join-Path (Split-Path -Parent $Mrpack) "mob-talk-installer-$version.zip" }

# The macOS script has to reach the zip with LF endings whatever git did to the checkout.
$staging = Join-Path $env:TEMP "mobtalk-installer-$PID"
New-Item -ItemType Directory -Force $staging | Out-Null
try {
    $files = @()
    foreach ($name in @('install-windows.bat', 'install-windows.ps1', 'install-macos.command', 'README.txt')) {
        $to = Join-Path $staging $name
        Copy-Item -LiteralPath (Join-Path $src $name) $to
        if ($name -eq 'install-macos.command') {
            $text = [IO.File]::ReadAllText($to, [Text.Encoding]::UTF8) -replace "`r`n", "`n"
            [IO.File]::WriteAllText($to, $text, (New-Object Text.UTF8Encoding($false)))
        }
        $files += $to
    }
    $files += $Mrpack
    if (Test-Path -LiteralPath $Out) { Remove-Item -LiteralPath $Out -Force }
    python -I (Join-Path $PSScriptRoot 'zip_installer.py') $Out 'mob-talk-installer' @files | Out-Null
    if ($LASTEXITCODE -ne 0) { throw 'zip_installer.py failed' }
} finally { Remove-Item -Recurse -Force -LiteralPath $staging -ErrorAction SilentlyContinue }
Say "installer ready: $Out"
$Out
