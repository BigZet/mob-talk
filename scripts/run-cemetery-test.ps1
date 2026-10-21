# Runs the cemetery test unattended on a throwaway copy of the Prism instance divine-block-test (ParallelPrism.psm1):
# builds, copies the instance with -Ddivineblock.cemeterytest=true, installs the fresh JAR into the copy, launches it through
# the running PrismLauncher (never closes Prism, never touches other games or the base instance), waits for that copy's own
# game to quit and deletes the copy. Report and screenshots (cemetery-*.png): %LOCALAPPDATA%\DivineBlock\cemeterytest.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\run-cemetery-test.ps1
$ErrorActionPreference = 'Stop'
$scratch = Join-Path $env:LOCALAPPDATA "DivineBlock\cemeterytest"
New-Item -ItemType Directory -Force $scratch | Out-Null
$repo = Split-Path -Parent $PSScriptRoot
$module = @("$repo\scripts\ParallelPrism.psm1", "$repo\..\parallel-selftest\scripts\ParallelPrism.psm1",
    "$env:USERPROFILE\Documents\GH\divine-block\scripts\ParallelPrism.psm1",
    "$env:USERPROFILE\Documents\GH\divine-block\.claude\worktrees\parallel-selftest\scripts\ParallelPrism.psm1") |
    Where-Object { Test-Path $_ } | Select-Object -First 1
if (-not $module) { throw 'ParallelPrism.psm1 not found' }
Import-Module $module -Force
$log = "$scratch\cemeterytest-run.log"
function Say($t) { $line = "$(Get-Date -Format HH:mm:ss) $t"; $line | Tee-Object -FilePath $log -Append }
"" | Out-File $log

Say 'build'
Set-Location $repo
.\gradlew.bat build -x test -q 2>&1 | Where-Object { $_ -match 'error|FAIL' }
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
$jar = Get-ChildItem "$repo\build\libs\divineblock-*.jar" | Where-Object { $_.Name -notmatch '-sources' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1

$slot = Enter-GameSlot -Owner 'divine-block cemetery test' -Say { param($t) Say $t }
$name = New-TestInstanceName 'cem' 'cemetery'
try {
  Say "copy divine-block-test -> $name"
  $dst = New-TestInstance -Base 'divine-block-test' -Name $name -JvmArgs '-Ddivineblock.cemeterytest=true'
  Get-ChildItem "$dst\minecraft\mods\divineblock-*.jar" | Remove-Item -Force
  Copy-Item $jar.FullName "$dst\minecraft\mods\"
  Get-ChildItem "$dst\minecraft\mods\*.jar" | ForEach-Object { Say "mod: $($_.Name)" }
  Remove-Item "$scratch\report.txt", "$scratch\cemetery-*.png" -Force -ErrorAction SilentlyContinue

  Say 'launch'
  $game = Start-TestInstance $name
  if (-not $game) { Say 'the game of the copy did not start' } else {
    Say "game pid $($game.ProcessId) running; waiting for the cemetery test to quit it"
    for ($i = 0; $i -lt 120; $i++) {
      Start-Sleep -Seconds 10
      if (-not (Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue)) { Say 'game exited'; break }
      if ($i % 3 -eq 2) { $last = Get-Content "$dst\minecraft\logs\latest.log" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String 'тест кладбища' | Select-Object -Last 1; Say "… $last" }
    }
    if (Get-Process -Id $game.ProcessId -ErrorAction SilentlyContinue) { Say 'timeout: stopping the copy''s game'; Stop-Process -Id $game.ProcessId -Force; Start-Sleep -Seconds 3 }
  }
  Copy-Item "$dst\minecraft\logs\latest.log" "$scratch\cemeterytest-latest.log" -Force -ErrorAction SilentlyContinue
  Get-ChildItem "$dst\minecraft\screenshots\cemetery-*.png" -ErrorAction SilentlyContinue | Copy-Item -Destination $scratch -Force
} finally {
  Start-Sleep -Seconds 2
  try { Remove-TestInstance $name; Say "deleted $name" } catch { Say "could not delete ${name}: $_" }
  Exit-GameSlot $slot
  Say 'done'
}
