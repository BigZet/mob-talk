# In-game self-test of the Alice Speaker prototype on a throwaway copy of the Prism instance alice-speaker-test.
# Uses mob-talk's scripts\ParallelPrism.psm1 (shared machine-wide game slots): builds this prototype, copies the base
# instance into instances\t-as-<branch>-<time> with the fresh JAR, launches it through the running Prism, waits for this
# copy's javaw only, then deletes the copy. Never closes Prism, never touches the base instance or other games.
# The game creates its own new world; no player world is copied. Turn the VPN off: Yandex stays silent through it.
# Logs and screenshots: %LOCALAPPDATA%\AliceSpeaker\selftest\<copy name>\
# Usage: powershell -ExecutionPolicy Bypass -File alice-prototype\scripts\run-selftest.ps1 [-Keep] [-ShotEverySeconds 20] [-MaxGames 6]
param([switch]$Keep, [int]$MaxGames = 6, [int]$ShotEverySeconds = 20, [string]$Base = 'alice-speaker-test',
    [string]$Module = 'C:\Users\bigze\Documents\GH\mob-talk\scripts\ParallelPrism.psm1')
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $Module)) { throw "ParallelPrism.psm1 not found: $Module (it lives on mob-talk main)" }
Import-Module $Module -Force
$project = Split-Path -Parent $PSScriptRoot
$branch = (git -C $project rev-parse --abbrev-ref HEAD).Trim()
if ($branch -eq 'HEAD') { $branch = 'alice' }
$name = New-TestInstanceName 'as' $branch
$scratch = Join-Path $env:LOCALAPPDATA "AliceSpeaker\selftest\$name"
New-Item -ItemType Directory -Force $scratch | Out-Null
$mc = "$env:APPDATA\PrismLauncher\instances\$name\minecraft"
$log = "$scratch\selftest-run.log"
function Say($t) { $line = "$(Get-Date -Format HH:mm:ss) $t"; Write-Host $line; Add-Content -Path $log -Value $line -Encoding UTF8 }
Say "copy $name of $Base"

Say 'build'
Set-Location $project
cmd /c '.\gradlew.bat build -q 2>&1' | Where-Object { $_ -match 'error|FAIL' }
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
$jar = Get-ChildItem "$project\build\libs\alicespeaker-*.jar" | Where-Object { $_.Name -notmatch '-sources|-dev' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1

$slot = Enter-GameSlot -Owner $name -Max $MaxGames -Say ${function:Say}
try {
  Say "slot $($slot.Index)"
  New-TestInstance -Base $Base -Name $name -JvmArgs '-Dalicespeaker.selftest=alice-selftest' | Out-Null
  Get-ChildItem "$mc\mods\alicespeaker-*.jar" | Remove-Item -Force
  Copy-Item $jar.FullName "$mc\mods\"
  Say "mod: $($jar.Name) $($jar.LastWriteTime)"

  Say 'launch'
  $game = Start-TestInstance $name
  if (-not $game) { Say 'javaw did not start' } else {
    Say "javaw $($game.ProcessId) running; waiting for the self-test to quit the game"
    $deadline = (Get-Date).AddMinutes(15); $nextShot = (Get-Date).AddSeconds($ShotEverySeconds); $i = 0
    while (Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue) {
      if ((Get-Date) -gt $deadline) { Say 'timeout: killing this copy''s javaw'; Stop-Process -Id $game.ProcessId -Force; break }
      $stall = Get-TestGameStall $name; if ($stall) { Say "watchdog: $stall; killing this copy's javaw"; Save-GameWindowShot $name "$scratch\window-stuck.png" | Out-Null; Stop-Process -Id $game.ProcessId -Force; break }
      Start-Sleep -Seconds 5; $i++
      if ($ShotEverySeconds -gt 0 -and (Get-Date) -gt $nextShot) {
        Save-GameWindowShot $name "$scratch\window-$(Get-Date -Format HHmmss).png" | Out-Null; $nextShot = (Get-Date).AddSeconds($ShotEverySeconds)
      }
      if ($i % 6 -eq 0) { $last = Get-Content "$mc\logs\latest.log" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String '\[selftest\]' | Select-Object -Last 1; Say "... $last" }
    }
    Say 'javaw exited'
  }
  Start-Sleep -Seconds 3
  Copy-Item "$mc\logs\latest.log" "$scratch\selftest-latest.log" -Force -ErrorAction SilentlyContinue
  Copy-Item "$mc\logs\alicespeaker-protocol.log" "$scratch\" -Force -ErrorAction SilentlyContinue
  if (Test-Path "$mc\screenshots") { Copy-Item "$mc\screenshots\*" $scratch -Force -ErrorAction SilentlyContinue }
  if (Test-Path "$mc\crash-reports") { Copy-Item "$mc\crash-reports\*" $scratch -Force -ErrorAction SilentlyContinue }
} finally {
  Exit-GameSlot $slot
  if ($Keep) { Say "kept $name" }
  else { try { Remove-TestInstance $name; Say "deleted $name" } catch { Say "could not delete ${name}: $_" } }
}
Say "done; logs in $scratch"
Get-Content "$scratch\selftest-latest.log" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String '\[selftest\]' | ForEach-Object { $_.Line }
