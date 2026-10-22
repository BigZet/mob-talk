﻿# Runs the in-game BOSS test (BossSelfTest) unattended on a throwaway copy of the Prism instance divine-block-test, the way
# run-selftest.ps1 runs the self-test: builds this checkout, waits for a free game slot (scripts\ParallelPrism.psm1, max
# -MaxGames games machine-wide, shared with mob-talk), copies the base instance into instances\t-dbboss-<branch>-<time> with
# this branch's JAR, launches it through the running PrismLauncher with -Ddivineblock.bosstest=true, waits for this copy's
# javaw only (killing it when latest.log is silent for 6 minutes or a crash report appears), then deletes the copy.
# Never closes Prism and never touches divine-block-test, its instance.cfg, mods or worlds.
# Every boss in turn: spawn, an ordinary attack, the ultimate, its affliction, the weak point opened by its counter-play, a
# later phase, death and its relic, then a model of the whole fight (time and blows to kill through the weak points).
# Results: -Shots (default %LOCALAPPDATA%\DivineBlock\boss-shots) gets the screenshots boss-<id>-<n>-<stage>.png, report.txt
# and the game's latest.log; the run's own log is bosstest-run.log there.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\run-bosstest.ps1 [-Only id,id] [-Showcase] [-God] [-Shots dir]
#   [-Keep] keeps the copy (scripts\clean-test-instances.ps1 deletes it later); [-MaxGames 6]; [-TimeoutMinutes 30]
param([string]$Only = '', [switch]$Showcase, [switch]$God, [string]$Shots = '', [switch]$Keep, [int]$MaxGames = 6,
  [string]$Base = 'divine-block-test', [int]$TimeoutMinutes = 30)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ParallelPrism.psm1') -Force
$repo = Split-Path -Parent $PSScriptRoot
$branch = (git -C $repo rev-parse --abbrev-ref HEAD).Trim()
$name = New-TestInstanceName 'dbboss' $branch
if (-not $Shots) { $Shots = Join-Path $env:LOCALAPPDATA 'DivineBlock\boss-shots' }
New-Item -ItemType Directory -Force $Shots | Out-Null
$report = Join-Path $env:LOCALAPPDATA 'DivineBlock\bosstest\report.txt'   # BossSelfTest writes it here
$mc = "$env:APPDATA\PrismLauncher\instances\$name\minecraft"
$log = Join-Path $Shots 'bosstest-run.log'
function Say($t) { $line = "$(Get-Date -Format HH:mm:ss) $t"; Write-Host $line; Add-Content -Path $log -Value $line -Encoding UTF8 }
"" | Out-File $log -Encoding UTF8
Say "copy $name of $Base, branch $branch"

Say 'build'
Set-Location $repo
# cmd merges gradle's stderr notes so PowerShell's Stop preference does not turn them into errors.
cmd /c '.\gradlew.bat build -x test -q 2>&1' | Where-Object { $_ -match 'error|FAIL' }
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
$jar = Get-ChildItem "$repo\build\libs\divineblock-*.jar" | Where-Object { $_.Name -notmatch '-sources|-dev' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Remove-Item $report -Force -ErrorAction SilentlyContinue

$slot = Enter-GameSlot -Owner $name -Max $MaxGames -Say ${function:Say}
try {
  Say "slot $($slot.Index)"
  $jvmArgs = '-Ddivineblock.bosstest=true'
  if ($Only) { $jvmArgs = "$jvmArgs -Ddivineblock.bosstest.only=$Only" }
  if ($Showcase) { $jvmArgs = "$jvmArgs -Ddivineblock.bosstest.showcase=true" }
  if ($God) { $jvmArgs = "$jvmArgs -Ddivineblock.bosstest.god=true" }
  New-TestInstance -Base $Base -Name $name -JvmArgs $jvmArgs | Out-Null
  Get-ChildItem "$mc\mods\divineblock-*.jar" | Remove-Item -Force
  Copy-Item $jar.FullName "$mc\mods\"
  Get-ChildItem "$mc\mods\*.jar" | ForEach-Object { Say "mod: $($_.Name)" }

  Say 'launch'
  $game = Start-TestInstance $name
  if (-not $game) { Say 'javaw did not start' } else {
    Say "javaw $($game.ProcessId) running; waiting for the boss test to quit the game"
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes); $i = 0
    while (Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue) {
      if ((Get-Date) -gt $deadline) { Say 'timeout: killing this copy''s javaw'; Stop-Process -Id $game.ProcessId -Force; break }
      $stall = Get-TestGameStall $name; if ($stall) { Say "stalled ($stall): killing this copy's javaw"; Stop-Process -Id $game.ProcessId -Force; break }
      Start-Sleep -Seconds 10; $i++
      if ($i % 3 -eq 0) { $last = Get-Content "$mc\logs\latest.log" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String 'тест боссов' | Select-Object -Last 1; Say "… $last" }
    }
    Say 'javaw exited'
  }
  Start-Sleep -Seconds 3
  Copy-Item "$mc\logs\latest.log" (Join-Path $Shots 'bosstest-latest.log') -Force -ErrorAction SilentlyContinue
  Copy-Item $report $Shots -Force -ErrorAction SilentlyContinue
  $pngs = @(Get-ChildItem "$mc\screenshots\boss-*.png" -ErrorAction SilentlyContinue)
  $pngs | Copy-Item -Destination $Shots -Force
  Say "screenshots: $($pngs.Count) -> $Shots"
} finally {
  Exit-GameSlot $slot
  if ($Keep) { Say "kept $name (scripts\clean-test-instances.ps1 -Name $name deletes it)" }
  else { try { Remove-TestInstance $name; Say "deleted $name" } catch { Say "could not delete ${name}: $_" } }
}
Say "done; report in $Shots\report.txt"
Get-Content (Join-Path $Shots 'report.txt') -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String 'FAIL|модель боя|ошибок' | Select-Object -Last 40
