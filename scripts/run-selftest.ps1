# Unattended in-game self-test: builds both mods, installs them into the separate Prism instance parrot-cages-test,
# copies the QA world, arms -Dparrotcages.selftest while PrismLauncher is closed, launches the game, waits for it to
# quit, restores instance.cfg, deletes the world copy and reopens PrismLauncher. Screenshots stay in the instance's
# screenshots folder; logs in %LOCALAPPDATA%\ParrotCages\selftest\. Refuses to run while javaw.exe is up.
param([string]$ViewerParrots = "$PSScriptRoot\..\..\minecraft-parrot")
$ErrorActionPreference = 'Stop'
$scratch = Join-Path $env:LOCALAPPDATA "ParrotCages\selftest"
New-Item -ItemType Directory -Force $scratch | Out-Null
$repo = Split-Path -Parent $PSScriptRoot
$inst = "$env:APPDATA\PrismLauncher\instances\parrot-cages-test"
$mc = "$inst\minecraft"
$prism = 'C:\Users\bigze\AppData\Local\Programs\PrismLauncher\prismlauncher.exe'
$world = 'parrotcages-selftest'
$log = "$scratch\selftest-run.log"
function Say($t) { $line = "$(Get-Date -Format HH:mm:ss) $t"; $line | Tee-Object -FilePath $log -Append }
"" | Out-File $log
if (Get-CimInstance Win32_Process -Filter "name='javaw.exe'") { throw 'Minecraft is running' }

Say 'build + install'
Set-Location $repo
.\gradlew.bat build -q 2>&1 | Where-Object { $_ -match 'error|FAIL' }
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
Remove-Item "$mc\mods\parrotcages-*.jar", "$mc\mods\viewerparrots-*.jar" -ErrorAction SilentlyContinue
Copy-Item (Get-ChildItem "$repo\build\libs\parrotcages-*.jar" | Where-Object Name -notmatch 'sources' | Select-Object -First 1).FullName "$mc\mods\"
Copy-Item (Get-ChildItem "$ViewerParrots\build\libs\viewerparrots-*.jar" | Where-Object Name -notmatch 'sources' | Sort-Object LastWriteTime -Descending | Select-Object -First 1).FullName "$mc\mods\"
Get-ChildItem "$mc\mods\*.jar" | ForEach-Object { Say "mod: $($_.Name)" }

Say 'world copy'
if (Test-Path "$mc\saves\$world") { Remove-Item -Recurse -Force "$mc\saves\$world" }
Copy-Item -Recurse "$mc\saves\Parrot Cages QA" "$mc\saves\$world"
Remove-Item -Force "$mc\saves\$world\session.lock" -ErrorAction SilentlyContinue
python -I (Join-Path $PSScriptRoot "rename_level.py") "$mc\saves\$world\level.dat" $world | ForEach-Object { Say $_ }

Say 'stop Prism and arm instance.cfg'
Get-Process prismlauncher -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 3
Copy-Item "$inst\instance.cfg" "$scratch\instance.cfg.bak" -Force
$cfg = Get-Content "$inst\instance.cfg" -Raw
$cfg = $cfg -replace '(?m)^OverrideJavaArgs=.*\r?\n', '' -replace '(?m)^JvmArgs=.*\r?\n', ''
$cfg = $cfg -replace '\[General\]\r?\n', "[General]`nOverrideJavaArgs=true`nJvmArgs=-Dparrotcages.selftest=$world`n"
[System.IO.File]::WriteAllText("$inst\instance.cfg", $cfg)

Say 'launch'
Start-Process $prism -ArgumentList '--launch', 'parrot-cages-test'
$started = $false
for ($i = 0; $i -lt 30; $i++) { Start-Sleep -Seconds 5; if (Get-CimInstance Win32_Process -Filter "name='javaw.exe'") { $started = $true; break } }
if (-not $started) { Say 'javaw did not start' } else {
  Say 'javaw running; waiting for the self-test to quit the game'
  for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 10
    if (-not (Get-CimInstance Win32_Process -Filter "name='javaw.exe'")) { Say 'javaw exited'; break }
  }
  if (Get-CimInstance Win32_Process -Filter "name='javaw.exe'") { Say 'timeout: killing javaw'; Get-Process javaw | Stop-Process -Force }
}
Start-Sleep -Seconds 3
Copy-Item "$mc\logs\latest.log" "$scratch\selftest-latest.log" -Force

Say 'restore instance.cfg and the world'
Get-Process prismlauncher -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 3
Copy-Item "$scratch\instance.cfg.bak" "$inst\instance.cfg" -Force
Remove-Item -Recurse -Force "$mc\saves\$world" -ErrorAction SilentlyContinue
Start-Process $prism
Say 'done'
Get-Content "$scratch\selftest-latest.log" | Select-String '\[selftest\]' | ForEach-Object { $_.Line }
