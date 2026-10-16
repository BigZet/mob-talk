param([string]$Instance = '1.21.1-fabric-dev')
$ErrorActionPreference = 'Stop'
if ($Instance -match '[\\/]' -or $Instance -in @('.', '..')) { throw 'Pass a Prism instance folder name.' }
$mobTalkProject = Split-Path -Parent $PSScriptRoot
$mobTalkMinecraft = [IO.Path]::GetFullPath((Join-Path $env:APPDATA "PrismLauncher/instances/$Instance/minecraft"))
$mobTalkMods = Join-Path $mobTalkMinecraft 'mods'
if (-not (Test-Path -LiteralPath $mobTalkMods -PathType Container)) { throw "Prism mods folder missing: $mobTalkMods" }
$mobTalkJarDirectory = Join-Path $mobTalkProject 'build/libs'
if (-not (Test-Path -LiteralPath $mobTalkJarDirectory)) { $mobTalkJarDirectory = $mobTalkProject }
$mobTalkJar = Get-ChildItem -LiteralPath $mobTalkJarDirectory -Filter 'mobtalk-*.jar' |
    Where-Object { $_.Name -notmatch '-sources|-dev' } | Sort-Object LastWriteTimeUtc -Descending | Select-Object -First 1
if (-not $mobTalkJar) { throw 'Run scripts/verify.ps1 first.' }
$mobTalkBackup = Join-Path $mobTalkMinecraft ('mobtalk-backups/' + (Get-Date -Format 'yyyyMMdd-HHmmss'))
foreach ($mobTalkOld in @(Get-ChildItem -LiteralPath $mobTalkMods -Filter 'mobtalk-*.jar')) {
    New-Item -ItemType Directory -Path $mobTalkBackup -Force | Out-Null
    Move-Item -LiteralPath $mobTalkOld.FullName -Destination (Join-Path $mobTalkBackup $mobTalkOld.Name)
}
Copy-Item -LiteralPath $mobTalkJar.FullName -Destination (Join-Path $mobTalkMods $mobTalkJar.Name)
Write-Host "Installed $($mobTalkJar.Name) in $mobTalkMods. Restart Minecraft to load it."
