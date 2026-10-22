# Прогон выделенного сервера без клиента (трек S1, раздел 16.3 плана): Loom поднимает сервер из исходников в run\server,
# Mob Talk с -Dmobtalk.selftest.server=true проверяет мост к миру, кодеки пакетов, JSON возможностей и хранилище игроков,
# пишет "[mobtalk-server-selftest] ALL CHECKS PASSED" и останавливает сервер. Скрипт готовит eula и server.properties
# (online-mode=false, маленький мир), запускает gradlew runServer и разбирает лог. Игра (клиент) не запускается, Prism не трогается,
# секреты не нужны. Код выхода 0 — всё прошло; 1 — есть FAIL, ошибки миксинов или ERROR от mobtalk в логе.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\run-server-selftest.ps1 [-TimeoutSeconds 600] [-Keep]
param([int]$TimeoutSeconds = 600, [switch]$Keep)
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
$run = Join-Path $root 'run\server'
function Say($t) { Write-Host "$(Get-Date -Format HH:mm:ss) $t" }

New-Item -ItemType Directory -Force $run | Out-Null
[System.IO.File]::WriteAllText((Join-Path $run 'eula.txt'), "eula=true`n")
$props = @(
    'online-mode=false', 'server-port=25599', 'level-name=selftest', 'level-type=minecraft\:flat', 'generate-structures=false',
    'view-distance=4', 'simulation-distance=4', 'max-players=2', 'spawn-protection=0', 'enable-rcon=false', 'motd=Mob Talk server selftest'
) -join "`n"
[System.IO.File]::WriteAllText((Join-Path $run 'server.properties'), $props + "`n")
if (-not $Keep) { Remove-Item -Recurse -Force (Join-Path $run 'selftest') -ErrorAction SilentlyContinue }
$log = Join-Path $run 'logs\latest.log'
Remove-Item -Force $log -ErrorAction SilentlyContinue

Say "gradlew runServer -PmobtalkSelftest=true (run\server, порт 25599, таймаут $TimeoutSeconds с)"
$gradle = Join-Path $root 'gradlew.bat'
$process = Start-Process -FilePath $gradle -ArgumentList @('runServer', '-PmobtalkSelftest=true', '--console=plain', '-q') -WorkingDirectory $root -PassThru -NoNewWindow `
    -RedirectStandardOutput (Join-Path $run 'gradle-out.log') -RedirectStandardError (Join-Path $run 'gradle-err.log')
if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
    Say "Таймаут: сервер не остановился за $TimeoutSeconds с, завершаю"
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
}

$lines = @()
foreach ($file in @($log, (Join-Path $run 'gradle-out.log'), (Join-Path $run 'gradle-err.log'))) {
    if (Test-Path $file) { $lines += Get-Content $file -Encoding UTF8 }
}
$checks = $lines | Where-Object { $_ -match '\[mobtalk-server-selftest\]' }
$checks | ForEach-Object { Write-Host $_ }
$mixinErrors = $lines | Where-Object { $_ -match 'Mixin apply failed|MixinApplyError|MixinTransformerError|InvalidMixinException' }
$modErrors = $lines | Where-Object { $_ -match '/ERROR\]' -and $_ -match 'mobtalk|Mob Talk' }
$passed = ($checks | Where-Object { $_ -match 'ALL CHECKS PASSED' }).Count -gt 0
$failed = ($checks | Where-Object { $_ -match ' FAIL ' }).Count -gt 0
$features = $lines | Where-Object { $_ -match 'Mob Talk: мост к миру поднят|выделенный сервер' }
$features | ForEach-Object { Write-Host $_ }
if ($mixinErrors) { Say 'Ошибки миксинов:'; $mixinErrors | ForEach-Object { Write-Host "  $_" } }
if ($modErrors) { Say 'ERROR от Mob Talk:'; $modErrors | ForEach-Object { Write-Host "  $_" } }
if ($passed -and -not $failed -and -not $mixinErrors -and -not $modErrors) { Say "[server-selftest] ALL CHECKS PASSED (лог: $log)"; exit 0 }
Say "[server-selftest] FAILED (лог: $log)"
exit 1
