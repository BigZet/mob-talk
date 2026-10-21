# Restarts PrismLauncher through explorer.exe (the desktop's environment, real %LOCALAPPDATA%) without breaking anyone's run:
# takes every game slot so no parallel self-test starts meanwhile, waits until no Prism game is running, closes Prism,
# starts it cleanly and releases the slots. Use when Prism was started from a Codex shell.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\restart-prism-clean.ps1 [-Slots 5] [-TimeoutMinutes 60]
param([int]$Slots = 5, [int]$TimeoutMinutes = 60)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ParallelPrism.psm1') -Force
$dir = Join-Path $env:LOCALAPPDATA 'PrismParallel\slots'
New-Item -ItemType Directory -Force $dir | Out-Null
$deadline = (Get-Date).AddMinutes($TimeoutMinutes)
$held = @{}
try {
    while ($held.Count -lt $Slots) {
        if ((Get-Date) -gt $deadline) { throw 'timed out waiting for the game slots' }
        for ($i = 1; $i -le $Slots; $i++) {
            if ($held.ContainsKey($i)) { continue }
            try {
                $s = [IO.File]::Open((Join-Path $dir "slot-$i.lock"), 'OpenOrCreate', 'ReadWrite', 'Read')
                $s.SetLength(0); $b = [Text.Encoding]::UTF8.GetBytes("prism restart pid=$PID since=$(Get-Date -Format s)"); $s.Write($b, 0, $b.Length); $s.Flush()
                $held[$i] = $s
            } catch { }
        }
        if ($held.Count -lt $Slots) { Write-Host "$(Get-Date -Format HH:mm:ss) holding $($held.Count)/$Slots slots, waiting for the rest"; Start-Sleep -Seconds 15 }
    }
    while (@(Get-AllGames).Count) {
        if ((Get-Date) -gt $deadline) { throw 'timed out waiting for running games' }
        Write-Host "$(Get-Date -Format HH:mm:ss) waiting for games: $((Get-AllGames | ForEach-Object { [regex]::Match($_.CommandLine.Replace('\', '/'), 'instances/[^/]+').Value }) -join ', ')"
        Start-Sleep -Seconds 15
    }
    Get-Process prismlauncher -ErrorAction SilentlyContinue | Stop-Process -Force
    for ($i = 0; $i -lt 20 -and (Get-Process prismlauncher -ErrorAction SilentlyContinue); $i++) { Start-Sleep -Milliseconds 500 }
    $p = Start-PrismClean
    Write-Host "$(Get-Date -Format HH:mm:ss) PrismLauncher $($p.Id) started through explorer.exe"
} finally {
    foreach ($s in $held.Values) { $s.Dispose() }
}
