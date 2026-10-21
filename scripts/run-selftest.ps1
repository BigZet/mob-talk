# Runs the in-game self-test unattended: builds the mod, installs it into the Prism instance, copies the source world,
# arms -Dmobtalk.selftest in instance.cfg while PrismLauncher is closed, launches the game, waits for it to quit,
# restores instance.cfg, deletes the world copy and reopens PrismLauncher. Logs: %LOCALAPPDATA%\MobTalk\selftest\
# (selftest-run.log is UTF-16 from Tee-Object; read it with Get-Content or iconv -f UTF-16). Refuses to run while javaw.exe is up.
# Usage (Windows PowerShell): powershell -ExecutionPolicy Bypass -File scripts\run-selftest.ps1 [-Only wedding|family|place|names|voices|ctov]
#   [-Instance <Prism instance>] [-SourceWorld <world folder to copy>]; defaults 1.21.1-fabric-dev and "stream 1", which is only copied.
param([string]$Only = '', [string]$Instance = '1.21.1-fabric-dev', [string]$SourceWorld = 'stream 1')
$ErrorActionPreference = 'Stop'
$scratch = Join-Path $env:LOCALAPPDATA "MobTalk\selftest"
New-Item -ItemType Directory -Force $scratch | Out-Null
$repo = Split-Path -Parent $PSScriptRoot
$inst = "$env:APPDATA\PrismLauncher\instances\$Instance"
$mc = "$inst\minecraft"
$prism = 'C:\Users\bigze\AppData\Local\Programs\PrismLauncher\prismlauncher.exe'
$world = 'mobtalk-selftest'
$log = "$scratch\selftest-run.log"
function Say($t) { $line = "$(Get-Date -Format HH:mm:ss) $t"; $line | Tee-Object -FilePath $log -Append }
"" | Out-File $log

if (Get-CimInstance Win32_Process -Filter "name='javaw.exe'") { throw 'Minecraft is running' }

Say 'build + install'
Set-Location $repo
.\gradlew.bat build -x test -q 2>&1 | Where-Object { $_ -match 'error|FAIL' }
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
powershell -ExecutionPolicy Bypass -File scripts\install-prism.ps1 -Instance $Instance | Out-Null
Get-ChildItem "$mc\mods\mobtalk*.jar" | ForEach-Object { Say "mod: $($_.Name) $($_.LastWriteTime)" }

Say 'world copy'
if (Test-Path "$mc\saves\$world") { Remove-Item -Recurse -Force "$mc\saves\$world" }
Copy-Item -Recurse "$mc\saves\$SourceWorld" "$mc\saves\$world"
Remove-Item -Force "$mc\saves\$world\session.lock" -ErrorAction SilentlyContinue
python -I (Join-Path $PSScriptRoot "rename_level.py") "$mc\saves\$world\level.dat" $world | ForEach-Object { Say $_ }

Say 'stop Prism and arm instance.cfg'
Get-Process prismlauncher -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 3
Copy-Item "$inst\instance.cfg" "$scratch\instance.cfg.bak" -Force
$cfg = Get-Content "$inst\instance.cfg" -Raw
$cfg = $cfg -replace '(?m)^OverrideJavaArgs=.*\r?\n', '' -replace '(?m)^JvmArgs=.*\r?\n', ''
$jvm = "-Dmobtalk.selftest=$world"
if ($Only) { $jvm = "$jvm -Dmobtalk.selftest.only=$Only" }
$cfg = $cfg -replace '\[General\]\r?\n', "[General]`nOverrideJavaArgs=true`nJvmArgs=$jvm`n"
[System.IO.File]::WriteAllText("$inst\instance.cfg", $cfg)
Say ((Get-Content "$inst\instance.cfg" | Select-String 'JvmArgs|OverrideJavaArgs') -join ' | ')

Say 'launch'
Start-Process $prism -ArgumentList '--launch', $Instance
$started = $false
for ($i = 0; $i -lt 30; $i++) { Start-Sleep -Seconds 5; if (Get-CimInstance Win32_Process -Filter "name='javaw.exe'") { $started = $true; break } }
if (-not $started) { Say 'javaw did not start' } else {
  Say 'javaw running; waiting for the self-test to quit the game'
  for ($i = 0; $i -lt 180; $i++) {
    Start-Sleep -Seconds 10
    if (-not (Get-CimInstance Win32_Process -Filter "name='javaw.exe'")) { Say 'javaw exited'; break }
    if ($i % 6 -eq 5) { $last = Get-Content "$mc\logs\latest.log" -ErrorAction SilentlyContinue | Select-String '\[selftest\]' | Select-Object -Last 1; Say "… $last" }
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
