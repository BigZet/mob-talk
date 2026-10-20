# Runs the in-game self-test unattended on the Prism instance divine-block-test (Fabric 1.21.1 with Mob Talk and Viewer
# Parrots beside this mod): builds, installs the JAR, arms -Ddivineblock.selftest=true in instance.cfg while PrismLauncher is
# closed, launches, waits for the game to quit, restores instance.cfg, deletes the test world and reopens PrismLauncher.
# Report: %LOCALAPPDATA%\DivineBlock\selftest\report.txt; screenshots in the instance's screenshots folder.
# Refuses to run while javaw.exe is up. Usage: powershell -ExecutionPolicy Bypass -File scripts\run-selftest.ps1
$ErrorActionPreference = 'Stop'
$scratch = Join-Path $env:LOCALAPPDATA "DivineBlock\selftest"
New-Item -ItemType Directory -Force $scratch | Out-Null
$repo = Split-Path -Parent $PSScriptRoot
$name = 'divine-block-test'
$inst = "$env:APPDATA\PrismLauncher\instances\$name"
$mc = "$inst\minecraft"
$prism = 'C:\Users\bigze\AppData\Local\Programs\PrismLauncher\prismlauncher.exe'
$world = 'divineblock-selftest'
$log = "$scratch\selftest-run.log"
function Say($t) { $line = "$(Get-Date -Format HH:mm:ss) $t"; $line | Tee-Object -FilePath $log -Append }
"" | Out-File $log

if (Get-CimInstance Win32_Process -Filter "name='javaw.exe'") { throw 'Minecraft is running' }
if (-not (Test-Path "$mc\mods")) { throw "Prism instance missing: $inst" }

Say 'build + install'
Set-Location $repo
.\gradlew.bat build -x test -q 2>&1 | Where-Object { $_ -match 'error|FAIL' }
if ($LASTEXITCODE -ne 0) { throw 'build failed' }
$jar = Get-ChildItem "$repo\build\libs\divineblock-*.jar" | Where-Object { $_.Name -notmatch '-sources' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
Get-ChildItem "$mc\mods\divineblock-*.jar" | Remove-Item -Force
Copy-Item $jar.FullName "$mc\mods\"
Get-ChildItem "$mc\mods\*.jar" | ForEach-Object { Say "mod: $($_.Name)" }
Remove-Item "$scratch\report.txt" -Force -ErrorAction SilentlyContinue
if (Test-Path "$mc\saves\$world") { Remove-Item -Recurse -Force "$mc\saves\$world" }

Say 'stop Prism and arm instance.cfg'
Get-Process prismlauncher -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 3
Copy-Item "$inst\instance.cfg" "$scratch\instance.cfg.bak" -Force
$cfg = Get-Content "$inst\instance.cfg" -Raw
$cfg = $cfg -replace '(?m)^OverrideJavaArgs=.*\r?\n', '' -replace '(?m)^JvmArgs=.*\r?\n', ''
$cfg = $cfg -replace '\[General\]\r?\n', "[General]`nOverrideJavaArgs=true`nJvmArgs=-Ddivineblock.selftest=true`n"
[System.IO.File]::WriteAllText("$inst\instance.cfg", $cfg)

Say 'launch'
Start-Process $prism -ArgumentList '--launch', $name
$started = $false
for ($i = 0; $i -lt 30; $i++) { Start-Sleep -Seconds 5; if (Get-CimInstance Win32_Process -Filter "name='javaw.exe'") { $started = $true; break } }
if (-not $started) { Say 'javaw did not start' } else {
  Say 'javaw running; waiting for the self-test to quit the game'
  for ($i = 0; $i -lt 90; $i++) {
    Start-Sleep -Seconds 10
    if (-not (Get-CimInstance Win32_Process -Filter "name='javaw.exe'")) { Say 'javaw exited'; break }
    if ($i % 3 -eq 2) { $last = Get-Content "$mc\logs\latest.log" -Encoding UTF8 -ErrorAction SilentlyContinue | Select-String 'самотест' | Select-Object -Last 1; Say "… $last" }
  }
  if (Get-CimInstance Win32_Process -Filter "name='javaw.exe'") { Say 'timeout: killing javaw'; Get-Process javaw | Stop-Process -Force }
}
Start-Sleep -Seconds 3
Copy-Item "$mc\logs\latest.log" "$scratch\selftest-latest.log" -Force

Say 'restore instance.cfg, delete the test world'
Get-Process prismlauncher -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 3
Copy-Item "$scratch\instance.cfg.bak" "$inst\instance.cfg" -Force
Remove-Item -Recurse -Force "$mc\saves\$world" -ErrorAction SilentlyContinue
Start-Process $prism
Say 'done'
