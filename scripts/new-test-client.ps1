# A second player for the local server test: a throwaway copy of the pack instance (New-TestInstance), launched by the
# running Prism with an offline account under -Nick and straight into the server (--server, Minecraft's quick play).
# Takes a game slot (Enter-GameSlot, max 6 games on the machine), waits for this copy's own game to close, then deletes the
# copy (-Keep leaves it). Needs the server started with online-mode=false (run-local-server.ps1 without -Public).
# Usage: powershell -ExecutionPolicy Bypass -File scripts\new-test-client.ps1 [-Nick Test2] [-Server 127.0.0.1:25565]
#        [-Base mob-talk-pack] [-Keep] [-NoWait]
param([string]$Nick = 'Test2', [string]$Server = '127.0.0.1:25565', [string]$Base = 'mob-talk-pack', [switch]$Keep,
    [switch]$NoWait, [int]$TimeoutSeconds = 600)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ParallelPrism.psm1') -Force
function Say($t) { Write-Host "$(Get-Date -Format HH:mm:ss) $t" }
# The server checks names: 3-16 Latin letters, digits or '_' (a Cyrillic nick is refused at login).
if ($Nick -notmatch '^[A-Za-z0-9_]{3,16}$') { throw "Nick '$Nick': use 3-16 Latin letters, digits or '_'." }

$prism = 'C:\Users\bigze\AppData\Local\Programs\PrismLauncher\prismlauncher.exe'
$instances = Join-Path $env:APPDATA 'PrismLauncher\instances'
$cfg = Join-Path $instances "$Base\instance.cfg"
if (-not (Test-Path -LiteralPath $cfg)) { throw "No instance ${Base}: build it with scripts\build-pack.ps1." }
# The copy keeps the pack's own memory settings (New-TestInstance overrides the JVM arguments with what it is given).
$jvm = ''
foreach ($l in [IO.File]::ReadAllLines($cfg, [Text.Encoding]::UTF8)) { if ($l -match '^JvmArgs=(.*)$') { $jvm = $Matches[1] } }

$slot = Enter-GameSlot -Owner "new-test-client $Nick" -Say ${function:Say}
$name = New-TestInstanceName 'client' $Nick
try {
    Say "copy $Base -> $name"
    New-TestInstance -Base $Base -Name $name -JvmArgs $jvm | Out-Null
    Invoke-PrismRescan
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $game = $null
    while (-not $game -and (Get-Date) -lt $deadline) {
        if (-not (Get-Process prismlauncher -ErrorAction SilentlyContinue)) { Start-PrismClean | Out-Null }
        Say "launch $name as $Nick (offline) -> $Server"
        Start-Process $prism -ArgumentList '--launch', $name, '--server', $Server, '--offline', $Nick
        $quiet = (Get-Date).AddSeconds(120)
        while (-not $game -and (Get-Date) -lt $quiet) { Start-Sleep -Seconds 3; $game = @(Get-InstanceGame $name) | Select-Object -First 1 }
        if (-not $game) { Invoke-PrismRescan }
    }
    if (-not $game) { throw "Prism did not start $name in $TimeoutSeconds s" }
    Say "game pid $($game.ProcessId) is up"
    if ($NoWait) { $Keep = $true; Say "not waiting: delete it later with scripts\clean-test-instances.ps1"; return }
    while (Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue) { Start-Sleep -Seconds 5 }
    Say 'game closed'
} finally {
    Exit-GameSlot $slot
    if ($Keep) { Say "kept $name" }
    else { try { Remove-TestInstance $name; Say "deleted $name" } catch { Say "could not delete ${name}: $_" } }
}
