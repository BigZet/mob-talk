# Stage "server" of the in-game self-test (track G, docs\server-plan.md 16.4): a dedicated server from this checkout and two
# throwaway clients play one scenario together, with no hands. The server (ServerSelfTestPlay, -Dmobtalk.selftest.play=Test1,Test2)
# conducts: it builds a stage, summons villagers and sends each client its next move; the clients (SelfTestServer,
# -Dmobtalk.selftest.play.client) act with their real packets and report what they saw. Test1 is the streamer, Test2 a player,
# the server console the operator. No Whisper, no paid TTS (tones instead; Divine Block's voice off); the LLM is real and cheap.
# Everything lives in %LOCALAPPDATA%\MobTalk\selftest\<name>\: the server folder (deleted unless -Keep), server.log, client logs,
# client screenshots (mobtalk-server-*.png) and window shots (window-*.png). Never touches another server, the base instances,
# the pack instance's mods or worlds (the world is copied), nor another run's games. Games count toward the machine's limit (6).
# Usage: powershell -ExecutionPolicy Bypass -File scripts\run-selftest.ps1 -Only server   (or this script directly)
#   [-ModsFrom <folder with our other mods' jars>] [-World 'stream 1 (сборка)'] [-ShotEverySeconds 60] [-Keep] [-NoBuild]
param([string]$ModsFrom = (Join-Path $env:LOCALAPPDATA 'MobTalkPack\server-integration\mods'), [string]$World = 'stream 1 (сборка)',
    [int]$ShotEverySeconds = 60, [switch]$Keep, [switch]$NoBuild, [string]$Pack = 'mob-talk-pack', [int]$TimeoutMinutes = 45)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ParallelPrism.psm1') -Force
$repo = Split-Path -Parent $PSScriptRoot
$branch = (git -C $repo rev-parse --abbrev-ref HEAD).Trim()
$name = New-TestInstanceName 'srv' $branch
$scratch = Join-Path $env:LOCALAPPDATA "MobTalk\selftest\$name"
$srv = Join-Path $scratch 'server'
New-Item -ItemType Directory -Force $scratch | Out-Null
$log = Join-Path $scratch 'selftest-run.log'
# Appends with shared access, so a reader tailing the log never makes the run fail.
function Say($t) {
    $line = "$(Get-Date -Format HH:mm:ss) $t"; Write-Host $line
    try { $f = [IO.File]::Open($log, 'Append', 'Write', 'ReadWrite'); $b = [Text.Encoding]::UTF8.GetBytes("$line`r`n"); $f.Write($b, 0, $b.Length); $f.Dispose() } catch { }
}
$nicks = @('Test1', 'Test2')
$ours = '^(mobtalk|divineblock|rusflora|parrotcages|viewerparrots)-'
Say "server stage $name, branch $branch"

if (-not $NoBuild) {
    Say 'build'
    Set-Location $repo
    cmd /c '.\gradlew.bat build -x test -q 2>&1' | Where-Object { $_ -match 'error|FAIL' }
    if ($LASTEXITCODE -ne 0) { throw 'build failed' }
}
$jar = Get-ChildItem "$repo\build\libs\mobtalk-*.jar" | Where-Object { $_.Name -notmatch '-sources|-dev' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
$jars = @($jar.FullName) + @(Get-ChildItem -LiteralPath $ModsFrom -Filter '*.jar' -ErrorAction SilentlyContinue | Where-Object { $_.Name -match $ours -and $_.Name -notmatch '^mobtalk-' } | ForEach-Object FullName)
Say "our jars: $(($jars | ForEach-Object { Split-Path -Leaf $_ }) -join ', ') (other mods from $ModsFrom)"

function Get-FreePort([int]$From, [int]$To) {
    for ($p = $From; $p -le $To; $p++) { if (-not @(Get-NetTCPConnection -LocalPort $p -ErrorAction SilentlyContinue).Count) { return $p } }
    throw "no free port in $From-$To"
}
$port = Get-FreePort 25570 25599
$adminPort = Get-FreePort 8797 8830
Say "server port $port, admin port $adminPort"

$serverProc = $null; $clients = @{}
# Only this run's client copies: someone else may be testing with the same nicks at the same time.
$started = Get-Date
try {
    # The server folder: the pack's third-party jars and configs, our jars swapped in. BlueMap is left out (it downloads the
    # Minecraft client jar on every fresh folder and renders; the 3D map is not part of this scenario).
    Say 'server folder'
    & (Join-Path $PSScriptRoot 'build-server-pack.ps1') -FromPack $Pack -Out $srv -Port $port | Out-Null
    foreach ($j in $jars) {
        $prefix = ((Split-Path -Leaf $j) -split '-')[0]
        Get-ChildItem "$srv\mods\$prefix-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
        Copy-Item -LiteralPath $j "$srv\mods\"
    }
    Get-ChildItem "$srv\mods\bluemap-*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
    [IO.File]::WriteAllText("$srv\config\mobtalk-admin.json", "{`"port`": $adminPort}`n", (New-Object Text.UTF8Encoding($false)))
    $divine = "$srv\config\divineblock.json"
    if (Test-Path -LiteralPath $divine) {
        $json = [IO.File]::ReadAllText($divine, [Text.Encoding]::UTF8) -replace '"voice":\s*true', '"voice": false'
        [IO.File]::WriteAllText($divine, $json, (New-Object Text.UTF8Encoding($false)))
    }
    Get-ChildItem "$srv\mods\*.jar" | ForEach-Object { Say "server mod $($_.Name)" }

    Say 'start server'
    $jvm = "-Dmobtalk.selftest.play=$($nicks -join ',') -Dmobtalk.selftest.notts=true"
    # Start-Process joins arguments with spaces as they are: values with spaces carry their own quotes.
    $serverProc = Start-Process powershell -PassThru -WindowStyle Hidden -RedirectStandardOutput "$scratch\server-console.log" -RedirectStandardError "$scratch\server-console.err" -ArgumentList @(
        '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'run-local-server.ps1'), '-Dir', "`"$srv`"", '-World', "`"$World`"",
        '-Port', $port, '-JvmArgs', "`"$jvm`"", '-Smoke', '-SmokeSeconds', '600', '-SmokeHoldSeconds', ($TimeoutMinutes * 60))
    $serverLog = "$srv\logs\latest.log"
    $until = (Get-Date).AddMinutes(10)
    while (-not ((Test-Path $serverLog) -and (Select-String -LiteralPath $serverLog -Pattern 'Done \(\d' -Quiet))) {
        if ($serverProc.HasExited) { throw 'server exited before it came up' }
        if ((Get-Date) -gt $until) { throw 'server did not come up in 10 minutes' }
        Start-Sleep -Seconds 5
    }
    Say 'server is up'

    foreach ($nick in $nicks) {
        $clients[$nick] = Start-Process powershell -PassThru -WindowStyle Hidden -RedirectStandardOutput "$scratch\client-$nick.log" -RedirectStandardError "$scratch\client-$nick.err" -ArgumentList (@(
            '-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', (Join-Path $PSScriptRoot 'new-test-client.ps1'), '-Nick', $nick, '-Server', "127.0.0.1:$port",
            '-Keep', '-TimeoutSeconds', '900', '-JvmArgs', "`"-Dmobtalk.selftest.play.client=$nick -Dmobtalk.selftest.notts=true`"", '-Jar', "`"$($jars -join ',')`""))
        Say "client $nick launcher pid $($clients[$nick].Id)"
        Start-Sleep -Seconds 20   # Prism takes one launch at a time more reliably
    }

    # Watch: window shots of both clients, the stall watchdog per client, the conductor's progress, the overall timeout.
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes); $nextShot = Get-Date; $i = 0; $seenLines = 0
    while (-not $serverProc.HasExited) {
        Start-Sleep -Seconds 10; $i++
        $copies = @{}
        foreach ($nick in $nicks) { $copies[$nick] = @(Get-ChildItem (Join-Path $env:APPDATA 'PrismLauncher\instances') -Directory -Filter "t-client-$nick-*" | Where-Object { $_.CreationTime -gt $started } | Sort-Object CreationTime -Descending | Select-Object -First 1 | ForEach-Object Name) }
        if ((Get-Date) -gt $nextShot -and $ShotEverySeconds -gt 0) {
            foreach ($nick in $nicks) { if ($copies[$nick]) { Save-GameWindowShot $copies[$nick][0] "$scratch\window-$nick-$(Get-Date -Format HHmmss).png" | Out-Null } }
            $nextShot = (Get-Date).AddSeconds($ShotEverySeconds)
        }
        foreach ($nick in $nicks) {
            if (-not $copies[$nick]) { continue }
            $stall = Get-TestGameStall $copies[$nick][0] 8
            if ($stall -and @(Get-InstanceGame $copies[$nick][0]).Count) {
                Say "watchdog $nick`: $stall; killing its game"
                Save-GameWindowShot $copies[$nick][0] "$scratch\window-$nick-stuck.png" | Out-Null
                Get-InstanceGame $copies[$nick][0] | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            }
        }
        $lines = @(Get-Content -LiteralPath $serverLog -Encoding UTF8 -ErrorAction SilentlyContinue | Where-Object { $_ -match '\[selftest-play\]' })
        if ($lines.Count -gt $seenLines) { $lines[$seenLines..($lines.Count - 1)] | Where-Object { $_ -match 'OK:|FAIL|ALL CHECKS' } | ForEach-Object { Say ($_ -replace '^.*\[selftest-play\] ', '') }; $seenLines = $lines.Count }
        if ((Get-Date) -gt $deadline) { Say 'timeout: stopping the server'; break }
    }
} finally {
    if ($serverProc -and -not $serverProc.HasExited) {
        Get-CimInstance Win32_Process -Filter "name='java.exe'" | Where-Object { $_.ParentProcessId -eq $serverProc.Id } |
            ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
        Stop-Process -Id $serverProc.Id -Force -ErrorAction SilentlyContinue
    }
    # The clients quit on the conductor's cue or 15 s after losing the server; give them a minute, then stop what is left.
    $until = (Get-Date).AddMinutes(1)
    foreach ($nick in $nicks) {
        foreach ($copy in @(Get-ChildItem (Join-Path $env:APPDATA 'PrismLauncher\instances') -Directory -Filter "t-client-$nick-*" | Where-Object { $_.CreationTime -gt $started })) {
            while (@(Get-InstanceGame $copy.Name).Count -and (Get-Date) -lt $until) { Start-Sleep -Seconds 3 }
            Get-InstanceGame $copy.Name | ForEach-Object { Stop-Process -Id $_.ProcessId -Force -ErrorAction SilentlyContinue }
            Start-Sleep -Seconds 2
            Copy-Item "$($copy.FullName)\minecraft\logs\latest.log" "$scratch\client-$nick-latest.log" -Force -ErrorAction SilentlyContinue
            if (Test-Path "$($copy.FullName)\minecraft\screenshots") { Copy-Item "$($copy.FullName)\minecraft\screenshots\*" $scratch -Force -ErrorAction SilentlyContinue }
            if (-not $Keep) { try { Remove-TestInstance $copy.Name; Say "deleted $($copy.Name)" } catch { Say "could not delete $($copy.Name): $_" } }
        }
    }
    foreach ($p in $clients.Values) { if (-not $p.HasExited) { $p.WaitForExit(30000) | Out-Null; if (-not $p.HasExited) { Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue } } }
    Copy-Item "$srv\logs\latest.log" "$scratch\server.log" -Force -ErrorAction SilentlyContinue
    if (-not $Keep -and (Test-Path $srv)) { try { Remove-Item -Recurse -Force -LiteralPath $srv; Say 'deleted the server folder' } catch { Say "could not delete ${srv}: $_" } }
}

$serverLines = @(Get-Content -LiteralPath "$scratch\server.log" -Encoding UTF8 -ErrorAction SilentlyContinue)
$checks = @($serverLines | Where-Object { $_ -match '\[selftest-play\] (OK:|FAIL|ALL CHECKS)' } | ForEach-Object { $_ -replace '^.*\[selftest-play\] ', '' })
$errors = @($serverLines | Where-Object { $_ -match '/(ERROR|FATAL)\]' -and $_ -notmatch 'selftest-play' -and $_ -notmatch 'No data fixer registered for' -and $_ -match '(?i)mobtalk|divine|rusflora|parrot' })
Say "done; logs and shots in $scratch"
$checks | ForEach-Object { Write-Host $_ }
if ($errors.Count) { Say "ERROR lines from our mods in server.log: $($errors.Count)"; $errors | Select-Object -First 20 | ForEach-Object { Write-Host "  $_" } }
$passed = @($checks | Where-Object { $_ -eq 'ALL CHECKS PASSED' }).Count -gt 0
if ($passed -and -not $errors.Count) { Say '[selftest] ALL CHECKS PASSED (server stage)'; exit 0 }
Say '[selftest] server stage FAILED'
exit 1
