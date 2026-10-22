# A second player for the local server test: a throwaway copy of the pack instance (New-TestInstance), launched by the
# running Prism with an offline account under -Nick and straight into the server (--server, Minecraft's quick play).
# Takes a game slot (Enter-GameSlot, max 6 games on the machine), waits for this copy's own game to close, then deletes the
# copy (-Keep leaves it). Needs the server started with online-mode=false (run-local-server.ps1 without -Public).
# Usage: powershell -ExecutionPolicy Bypass -File scripts\new-test-client.ps1 [-Nick Test2] [-Server 127.0.0.1:25565]
#        [-Base mob-talk-pack] [-Keep] [-NoWait] [-Jar <our jar>...] [-ModsFrom <server mods folder>] [-JvmArgs '-Dname=value']
# Our jars (mobtalk, divineblock, rusflora, parrotcages) in the copy are replaced with -Jar, else with the ones in the
# local server's mods folder, so the client matches the server it joins; -ModsFrom '' keeps the base instance's jars.
param([string]$Nick = 'Test2', [string]$Server = '127.0.0.1:25565', [string]$Base = 'mob-talk-pack', [switch]$Keep,
    [switch]$NoWait, [int]$TimeoutSeconds = 600, [string[]]$Jar = @(), [string]$JvmArgs = '',
    [string]$ModsFrom = (Join-Path $env:LOCALAPPDATA 'MobTalkPack\server\mods'))
$ours = '^(mobtalk|divineblock|rusflora|parrotcages)-'
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
# -JvmArgs adds flags, e.g. the server self-test's -Dmobtalk.selftest.play.client=Test1.
if ($JvmArgs) { $jvm = "$jvm $JvmArgs".Trim() }

$slot = Enter-GameSlot -Owner "new-test-client $Nick" -Say ${function:Say}
$name = New-TestInstanceName 'client' $Nick
try {
    Say "copy $Base -> $name"
    $copy = @(New-TestInstance -Base $Base -Name $name -JvmArgs $jvm)[-1]
    # Our mods in the copy are swapped for the server's (or -Jar's) so client and server run the same build.
    # powershell -File passes "a.jar,b.jar" as one string.
    $fresh = @($Jar | ForEach-Object { $_ -split ',' } | Where-Object { $_ } | ForEach-Object { Get-Item -LiteralPath $_ })
    if (-not $fresh.Count -and $ModsFrom -and (Test-Path -LiteralPath $ModsFrom)) {
        $fresh = @(Get-ChildItem -LiteralPath $ModsFrom -Filter '*.jar' | Where-Object { $_.Name -match $ours })
    }
    foreach ($j in $fresh) {
        $prefix = ($j.Name -split '-')[0]
        Get-ChildItem "$copy\minecraft\mods\$prefix-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
        Copy-Item -LiteralPath $j.FullName "$copy\minecraft\mods\"
        Say "mod $($j.Name) from $(Split-Path -Parent $j.FullName)"
    }
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
