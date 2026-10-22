# Runs another mod's own in-game self-test inside a throwaway copy of the pack (mob-talk-pack), so each mod is checked with
# all the others loaded: divine (Divine Block, -Ddivineblock.selftest), church (its church test), cages (Parrot Cages, on a
# copy of the "Parrot Cages QA" world) or hogweed (Rusflora, makes its own world). Mob Talk's stages run through
# scripts\run-selftest.ps1 -Base mob-talk-pack. TTS stays off. The copy gets selftest-guard (no focus, no mouse grab) and
# the watchdog kills only its own javaw when the game stalls. Results: %LOCALAPPDATA%\MobTalkPack\selftest\<copy>\.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\run-pack-modtest.ps1 -Mod divine|church|cages|hogweed [-Keep] [-ShotEverySeconds 60]
param([Parameter(Mandatory)][ValidateSet('divine', 'church', 'cages', 'hogweed')][string]$Mod, [string]$Base = 'mob-talk-pack',
    [switch]$Keep, [int]$MaxGames = 6, [int]$ShotEverySeconds = 60, [int]$TimeoutMinutes = 30)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ParallelPrism.psm1') -Force
$instances = Join-Path $env:APPDATA 'PrismLauncher\instances'
$name = New-TestInstanceName 'pk' $Mod
$scratch = Join-Path $env:LOCALAPPDATA "MobTalkPack\selftest\$name"
New-Item -ItemType Directory -Force $scratch | Out-Null
$mc = "$instances\$name\minecraft"
$log = "$scratch\selftest-run.log"
function Say($t) { $line = "$(Get-Date -Format HH:mm:ss) $t"; Write-Host $line; Add-Content -Path $log -Value $line -Encoding UTF8 }
$report = $scratch.Replace('\', '/')
$jvm = switch ($Mod) {
    'divine' { "-Ddivineblock.selftest=true -Ddivineblock.selftest.report=$report" }
    'church' { "-Ddivineblock.churchtest=true -Ddivineblock.selftest.report=$report" }
    'cages' { '-Dparrotcages.selftest=parrotcages-selftest' }
    'hogweed' { '-Drusflora.hogweedtest=true' }
}
$marker = switch ($Mod) { 'cages' { 'ALL CHECKS PASSED|FINISHED WITH FAILURES' } 'hogweed' { 'ошибок всего' } default { 'самотест|selftest' } }
Say "copy $name of $Base for $Mod"

$slot = Enter-GameSlot -Owner $name -Max $MaxGames -Say ${function:Say}
try {
    $port = 63919 + $slot.Index
    Say "slot $($slot.Index), Whisper port $port"
    New-TestInstance -Base $Base -Name $name -JvmArgs $jvm -WhisperPort $port | Out-Null
    if ($Mod -eq 'cages') {
        Copy-Item -Recurse -LiteralPath "$instances\parrot-cages-test\minecraft\saves\Parrot Cages QA" "$mc\saves\parrotcages-selftest"
        Remove-Item -Force -LiteralPath "$mc\saves\parrotcages-selftest\session.lock" -ErrorAction SilentlyContinue
    }
    Get-ChildItem "$mc\mods\*.jar" | ForEach-Object { Say "mod: $($_.Name)" }
    Say 'launch'
    $game = Start-TestInstance $name
    if (-not $game) { Say 'javaw did not start' } else {
        Say "javaw $($game.ProcessId) running"
        $deadline = (Get-Date).AddMinutes($TimeoutMinutes); $nextShot = (Get-Date).AddSeconds($ShotEverySeconds); $i = 0
        while (Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue) {
            if ((Get-Date) -gt $deadline) { Say 'timeout: killing this copy''s javaw'; Stop-Process -Id $game.ProcessId -Force; break }
            $stall = Get-TestGameStall $name
            if ($stall) { Say "watchdog: $stall; killing this copy's javaw"; Save-GameWindowShot $name "$scratch\window-stuck.png" | Out-Null; Stop-Process -Id $game.ProcessId -Force; break }
            Start-Sleep -Seconds 10; $i++
            if ($ShotEverySeconds -gt 0 -and (Get-Date) -gt $nextShot) { Save-GameWindowShot $name "$scratch\window-$(Get-Date -Format HHmmss).png" | Out-Null; $nextShot = (Get-Date).AddSeconds($ShotEverySeconds) }
            if ($i % 6 -eq 0) { $last = Get-Content "$mc\logs\latest.log" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String $marker | Select-Object -Last 1; Say "... $last" }
        }
        Say 'javaw exited'
    }
    Start-Sleep -Seconds 3
    Copy-Item "$mc\logs\latest.log" "$scratch\selftest-latest.log" -Force -ErrorAction SilentlyContinue
    Copy-Item "$mc\hogweed-selftest.txt" $scratch -Force -ErrorAction SilentlyContinue
    if (Test-Path "$mc\screenshots") { Copy-Item "$mc\screenshots\*" $scratch -Force -ErrorAction SilentlyContinue }
    if (Test-Path "$mc\crash-reports") { Copy-Item "$mc\crash-reports\*" $scratch -Force -ErrorAction SilentlyContinue }
    Get-WhisperHelper $port | ForEach-Object { Say "stopping this copy's Whisper helper $($_.ProcessId)"; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
} finally {
    Exit-GameSlot $slot
    if ($Keep) { Say "kept $name" } else { try { Remove-TestInstance $name; Say "deleted $name" } catch { Say "could not delete ${name}: $_" } }
}
Say "done; results in $scratch"
