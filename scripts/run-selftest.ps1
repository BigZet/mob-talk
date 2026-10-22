# Runs the in-game self-test unattended on a throwaway copy of the Prism instance divine-block-test (Fabric 1.21.1 with
# Mob Talk and Viewer Parrots beside this mod), so several branches can test at once: builds this checkout, waits for a free
# game slot (scripts\ParallelPrism.psm1, max 3 games machine-wide, shared with mob-talk), copies the base instance into
# instances\t-db-<branch>-<time> with this branch's JAR and its own Whisper port (63919 + slot), launches it through the
# running PrismLauncher with -Ddivineblock.selftest=<Mode>, waits for this copy's javaw only, then deletes the copy.
# Never closes Prism and never touches divine-block-test, its mods or its worlds.
# Report, latest.log and screenshots: %LOCALAPPDATA%\DivineBlock\selftest\<copy name>\ (report.txt, selftest-run.log in UTF-8).
# Usage: powershell -ExecutionPolicy Bypass -File scripts\run-selftest.ps1 [-Mode true|cruel|...] [-Jvm '-Dmore=1']
#   [-Keep] keeps the copy (delete later with scripts\clean-test-instances.ps1); [-MaxGames 6]; [-ShotEverySeconds 60]
# -Church runs the church self-test instead (ChurchSelfTest) and copies its screenshots to -Shots when given.
param([switch]$Church, [string]$Shots = '', [string]$Mode = 'true', [string]$Jvm = '', [switch]$Keep, [int]$MaxGames = 6, [int]$ShotEverySeconds = 0,
  [string]$Base = 'divine-block-test', [int]$TimeoutMinutes = 20)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ParallelPrism.psm1') -Force
$repo = Split-Path -Parent $PSScriptRoot
$branch = (git -C $repo rev-parse --abbrev-ref HEAD).Trim()
$name = New-TestInstanceName 'db' $branch
$scratch = Join-Path $env:LOCALAPPDATA "DivineBlock\selftest\$name"
New-Item -ItemType Directory -Force $scratch | Out-Null
$mc = "$env:APPDATA\PrismLauncher\instances\$name\minecraft"
$log = "$scratch\selftest-run.log"
function Say($t) { $line = "$(Get-Date -Format HH:mm:ss) $t"; Write-Host $line; Add-Content -Path $log -Value $line -Encoding UTF8 }
Say "copy $name of $Base, branch $branch"

Say 'build'
Set-Location $repo
# cmd merges gradle's stderr notes so PowerShell's Stop preference does not turn them into errors.
cmd /c '.\gradlew.bat build -x test -q 2>&1' | Where-Object { $_ -match 'error|FAIL' }
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
$jar = Get-ChildItem "$repo\build\libs\divineblock-*.jar" | Where-Object { $_.Name -notmatch '-sources|-dev' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1

$slot = Enter-GameSlot -Owner $name -Max $MaxGames -Say ${function:Say}
try {
  $port = 63919 + $slot.Index
  Say "slot $($slot.Index), Whisper port $port"
  Get-WhisperHelper $port | ForEach-Object { Say "stopping a stale Whisper helper $($_.ProcessId) on $port"; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

  $flag = if ($Church) { '-Ddivineblock.churchtest=true' } else { "-Ddivineblock.selftest=$Mode" }
  # Prism reads backslashes in JvmArgs as escapes: the report folder goes with forward slashes.
  $jvmArgs = "$flag -Ddivineblock.selftest.report=$($scratch.Replace('\', '/'))"
  if ($Jvm) { $jvmArgs = "$jvmArgs $Jvm" }
  New-TestInstance -Base $Base -Name $name -JvmArgs $jvmArgs -WhisperPort $port | Out-Null
  Get-ChildItem "$mc\mods\divineblock-*.jar" | Remove-Item -Force
  Copy-Item $jar.FullName "$mc\mods\"
  Get-ChildItem "$mc\mods\*.jar" | ForEach-Object { Say "mod: $($_.Name)" }

  Say 'launch'
  $game = Start-TestInstance $name
  if (-not $game) { Say 'javaw did not start' } else {
    Say "javaw $($game.ProcessId) running; waiting for the self-test to quit the game"
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes); $nextShot = (Get-Date).AddSeconds($ShotEverySeconds); $i = 0
    while (Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue) {
      if ((Get-Date) -gt $deadline) { Say 'timeout: killing this copy''s javaw'; Stop-Process -Id $game.ProcessId -Force; break }
      $stall = Get-TestGameStall $name; if ($stall) { Say "stalled ($stall): killing this copy's javaw"; Stop-Process -Id $game.ProcessId -Force; break }
      Start-Sleep -Seconds 10; $i++
      if ($ShotEverySeconds -gt 0 -and (Get-Date) -gt $nextShot) {
        Save-GameWindowShot $name "$scratch\window-$(Get-Date -Format HHmmss).png" | Out-Null; $nextShot = (Get-Date).AddSeconds($ShotEverySeconds)
      }
      if ($i % 3 -eq 0) { $last = Get-Content "$mc\logs\latest.log" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String 'самотест' | Select-Object -Last 1; Say "… $last" }
    }
    Say 'javaw exited'
  }
  Start-Sleep -Seconds 3
  Copy-Item "$mc\logs\latest.log" "$scratch\selftest-latest.log" -Force -ErrorAction SilentlyContinue
  if (Test-Path "$mc\screenshots") { Copy-Item "$mc\screenshots\*" $scratch -Force -ErrorAction SilentlyContinue }
  if ($Church -and $Shots) {
    New-Item -ItemType Directory -Force $Shots | Out-Null
    Get-ChildItem "$mc\screenshots\divineblock-church-*.png" -ErrorAction SilentlyContinue | Copy-Item -Destination $Shots -Force
    Say "screenshots copied to $Shots"
  }
  Get-WhisperHelper $port | ForEach-Object { Say "stopping this copy's Whisper helper $($_.ProcessId)"; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
} finally {
  Exit-GameSlot $slot
  if ($Keep) { Say "kept $name (scripts\clean-test-instances.ps1 -Name $name deletes it)" }
  else { try { Remove-TestInstance $name; Say "deleted $name" } catch { Say "could not delete ${name}: $_" } }
}
Say "done; report in $scratch\report.txt"
Get-Content "$scratch\report.txt" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-Object -Last 15
