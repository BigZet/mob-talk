# Runs the in-game DODGE test (DodgeSelfTest) unattended on a throwaway copy of the Prism instance divine-block-test, the
# same way run-bosstest.ps1 runs the boss test: the client player shoots a Seraph with a bow, first with the learned dodge
# off, then on (ml/seraph_dodge), and counts the hits. Never closes Prism and never touches divine-block-test.
# Results: -Shots (default %LOCALAPPDATA%\DivineBlock\dodge-shots) gets dodge-off-NNN.png / dodge-on-NNN.png, report.txt
# and the game's latest.log; the run's own log is dodgetest-run.log there.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\run-dodgetest.ps1 [-ShotsPerRound 40] [-Shots dir] [-Keep]
param([int]$ShotsPerRound = 40, [string]$Shots = '', [switch]$Keep, [int]$MaxGames = 6,
  [string]$Base = 'divine-block-test', [int]$TimeoutMinutes = 30)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ParallelPrism.psm1') -Force
$repo = Split-Path -Parent $PSScriptRoot
$branch = (git -C $repo rev-parse --abbrev-ref HEAD).Trim()
$name = New-TestInstanceName 'dbdodge' $branch
if (-not $Shots) { $Shots = Join-Path $env:LOCALAPPDATA 'DivineBlock\dodge-shots' }
New-Item -ItemType Directory -Force $Shots | Out-Null
$report = Join-Path $env:LOCALAPPDATA 'DivineBlock\dodgetest\report.txt'   # DodgeSelfTest writes it here
$mc = "$env:APPDATA\PrismLauncher\instances\$name\minecraft"
$log = Join-Path $Shots 'dodgetest-run.log'
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
  $jvmArgs = "-Ddivineblock.dodgetest=true -Ddivineblock.dodgetest.shots=$ShotsPerRound"
  New-TestInstance -Base $Base -Name $name -JvmArgs $jvmArgs | Out-Null
  Get-ChildItem "$mc\mods\divineblock-*.jar" | Remove-Item -Force
  Copy-Item $jar.FullName "$mc\mods\"
  Get-ChildItem "$mc\mods\*.jar" | ForEach-Object { Say "mod: $($_.Name)" }

  Say 'launch'
  $game = Start-TestInstance $name
  if (-not $game) { Say 'javaw did not start' } else {
    Say "javaw $($game.ProcessId) running; waiting for the dodge test to quit the game"
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes); $i = 0
    while (Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue) {
      if ((Get-Date) -gt $deadline) { Say 'timeout: killing this copy''s javaw'; Stop-Process -Id $game.ProcessId -Force; break }
      $stall = Get-TestGameStall $name; if ($stall) { Say "stalled ($stall): killing this copy's javaw"; Stop-Process -Id $game.ProcessId -Force; break }
      Start-Sleep -Seconds 10; $i++
      if ($i % 3 -eq 0) { $last = Get-Content "$mc\logs\latest.log" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String 'тест уклонения' | Select-Object -Last 1; Say "… $last" }
    }
    Say 'javaw exited'
  }
  Start-Sleep -Seconds 3
  Copy-Item "$mc\logs\latest.log" (Join-Path $Shots 'dodgetest-latest.log') -Force -ErrorAction SilentlyContinue
  Copy-Item $report $Shots -Force -ErrorAction SilentlyContinue
  if (Test-Path "$mc\seraph-logs") { Copy-Item "$mc\seraph-logs\*.csv" $Shots -Force; Say "seraph-logs -> $Shots" }   # лог боя Серафима
  $pngs = @(Get-ChildItem "$mc\screenshots\dodge-*.png" -ErrorAction SilentlyContinue)
  $pngs | Copy-Item -Destination $Shots -Force
  Say "screenshots: $($pngs.Count) -> $Shots"
} finally {
  Exit-GameSlot $slot
  if ($Keep) { Say "kept $name (scripts\clean-test-instances.ps1 -Name $name deletes it)" }
  else { try { Remove-TestInstance $name; Say "deleted $name" } catch { Say "could not delete ${name}: $_" } }
}
Say "done; report in $Shots\report.txt"
Get-Content (Join-Path $Shots 'report.txt') -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String 'FAIL|раунд' | Select-Object -Last 20
