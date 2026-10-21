# Runs the in-game self-test unattended on a throwaway copy of the Prism instance, so several branches can test at once:
# builds this checkout, waits for a free game slot (scripts\ParallelPrism.psm1, max 3 games machine-wide), copies the base
# instance 1.21.1-fabric-dev into instances\t-mt-<branch>-<time> with this branch's JAR, a copy of the world "stream 1" and
# its own Whisper port (63919 + slot), launches it through the running PrismLauncher, waits for this copy's javaw only,
# then deletes the copy. Never closes Prism, never touches the base instance, its mods or its worlds.
# Logs, latest.log and screenshots: %LOCALAPPDATA%\MobTalk\selftest\<copy name>\ (selftest-run.log is UTF-8).
# Usage (Windows PowerShell): powershell -ExecutionPolicy Bypass -File scripts\run-selftest.ps1 [-Only wedding|family|place|names|voices|looks|ctov]
#   [-Keep] keeps the copy for inspection (delete later with scripts\clean-test-instances.ps1); [-MaxGames 3]; [-ShotEverySeconds 60]
param([string]$Only = '', [switch]$Keep, [int]$MaxGames = 3, [int]$ShotEverySeconds = 0, [string]$Base = '1.21.1-fabric-dev')
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ParallelPrism.psm1') -Force
$repo = Split-Path -Parent $PSScriptRoot
$branch = (git -C $repo rev-parse --abbrev-ref HEAD).Trim()
$name = New-TestInstanceName 'mt' $branch
$scratch = Join-Path $env:LOCALAPPDATA "MobTalk\selftest\$name"
New-Item -ItemType Directory -Force $scratch | Out-Null
$mc = "$env:APPDATA\PrismLauncher\instances\$name\minecraft"
$world = 'mobtalk-selftest'
$log = "$scratch\selftest-run.log"
function Say($t) { $line = "$(Get-Date -Format HH:mm:ss) $t"; Write-Host $line; Add-Content -Path $log -Value $line -Encoding UTF8 }
Say "copy $name of $Base, branch $branch"

Say 'build'
Set-Location $repo
# cmd merges gradle's stderr notes (deprecated API) so PowerShell's Stop preference does not turn them into errors.
cmd /c '.\gradlew.bat build -x test -q 2>&1' | Where-Object { $_ -match 'error|FAIL' }
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
$jar = Get-ChildItem "$repo\build\libs\mobtalk-*.jar" | Where-Object { $_.Name -notmatch '-sources|-dev' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1

$slot = Enter-GameSlot -Owner $name -Max $MaxGames -Say ${function:Say}
try {
  $port = 63919 + $slot.Index
  Say "slot $($slot.Index), Whisper port $port"
  # A helper left on this slot's port by a crashed run belongs to no live game of this slot: stop it.
  Get-WhisperHelper $port | ForEach-Object { Say "stopping a stale Whisper helper $($_.ProcessId) on $port"; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }

  $jvm = "-Dmobtalk.selftest=$world"
  if ($Only) { $jvm = "$jvm -Dmobtalk.selftest.only=$Only" }
  New-TestInstance -Base $Base -Name $name -JvmArgs $jvm -World 'stream 1' -WorldName $world -WhisperPort $port | Out-Null
  Get-ChildItem "$mc\mods\mobtalk-*.jar" | Remove-Item -Force
  Copy-Item $jar.FullName "$mc\mods\"
  Say "mod: $($jar.Name) $($jar.LastWriteTime)"
  python -I (Join-Path $PSScriptRoot 'rename_level.py') "$mc\saves\$world\level.dat" $world | ForEach-Object { Say $_ }

  Say 'launch'
  $game = Start-TestInstance $name
  if (-not $game) { Say 'javaw did not start' } else {
    Say "javaw $($game.ProcessId) running; waiting for the self-test to quit the game"
    $deadline = (Get-Date).AddMinutes(30); $nextShot = (Get-Date).AddSeconds($ShotEverySeconds); $i = 0
    while (Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue) {
      if ((Get-Date) -gt $deadline) { Say 'timeout: killing this copy''s javaw'; Stop-Process -Id $game.ProcessId -Force; break }
      Start-Sleep -Seconds 10; $i++
      if ($ShotEverySeconds -gt 0 -and (Get-Date) -gt $nextShot) {
        Save-GameWindowShot $name "$scratch\window-$(Get-Date -Format HHmmss).png" | Out-Null; $nextShot = (Get-Date).AddSeconds($ShotEverySeconds)
      }
      if ($i % 6 -eq 0) { $last = Get-Content "$mc\logs\latest.log" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String '\[selftest\]' | Select-Object -Last 1; Say "... $last" }
    }
    Say 'javaw exited'
  }
  Start-Sleep -Seconds 3
  Copy-Item "$mc\logs\latest.log" "$scratch\selftest-latest.log" -Force -ErrorAction SilentlyContinue
  if (Test-Path "$mc\screenshots") { Copy-Item "$mc\screenshots\*" $scratch -Force -ErrorAction SilentlyContinue }
  Get-WhisperHelper $port | ForEach-Object { Say "stopping this copy's Whisper helper $($_.ProcessId)"; Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
} finally {
  Exit-GameSlot $slot
  if ($Keep) { Say "kept $name (scripts\clean-test-instances.ps1 -Name $name deletes it)" }
  else { try { Remove-TestInstance $name; Say "deleted $name" } catch { Say "could not delete ${name}: $_" } }
}
Say "done; logs in $scratch"
Get-Content "$scratch\selftest-latest.log" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String '\[selftest\]' | ForEach-Object { $_.Line }
