# Parallel in-game self-tests on throwaway Prism instances (Windows PowerShell 5.1).
# Each run copies a base instance into %APPDATA%\PrismLauncher\instances\t-<...> with its own mods, config, options and
# world, launches it through the running PrismLauncher (never closes Prism, never edits the base instance), waits for
# that instance's own javaw only, and deletes the copy afterwards. A machine-wide slot lock in
# %LOCALAPPDATA%\PrismParallel\slots caps simultaneous games for every repo that uses this module (mob-talk, divine-block).
# The same file lives in mob-talk and divine-block; keep them in sync.
$ErrorActionPreference = 'Stop'
$script:PrismExe = 'C:\Users\bigze\AppData\Local\Programs\PrismLauncher\prismlauncher.exe'
$script:Instances = Join-Path $env:APPDATA 'PrismLauncher\instances'
$script:SlotDir = Join-Path $env:LOCALAPPDATA 'PrismParallel\slots'
$script:Staging = Join-Path $env:APPDATA 'PrismLauncher\parallel-staging'

function Assert-TestName([string]$Name) {
    if ($Name -notmatch '^t-[A-Za-z0-9._-]{1,60}$') { throw "Test instance names start with 't-' and use [A-Za-z0-9._-]: '$Name'" }
}

# 't-<prefix>-<branch leaf>-<HHmmss>-<nn>': unique per run, so Prism never reuses a cached instance with old JVM arguments.
function New-TestInstanceName([string]$Prefix, [string]$Branch) {
    $leaf = (($Branch -split '/')[-1] -replace '[^A-Za-z0-9.-]', '-')
    if ($leaf.Length -gt 28) { $leaf = $leaf.Substring(0, 28) }
    "t-$Prefix-$leaf-$(Get-Date -Format HHmmss)-$(Get-Random -Minimum 10 -Maximum 99)"
}

# Minecraft clients started by Prism carry the instance's own natives/game paths on the command line.
function Get-InstanceGame([string]$Name) {
    $needle = "\instances\$Name\"
    @(Get-CimInstance Win32_Process -Filter "name='javaw.exe' or name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Replace('/', '\').IndexOf($needle, [StringComparison]::OrdinalIgnoreCase) -ge 0 })
}

function Get-AllGames {
    @(Get-CimInstance Win32_Process -Filter "name='javaw.exe' or name='java.exe'" |
        Where-Object { $_.CommandLine -and $_.CommandLine.Replace('/', '\') -match '\\PrismLauncher\\instances\\' })
}

# Holds one of $Max lock files open for the caller's lifetime (a crashed script frees its slot). Also waits while
# $Max games of any kind are already running, so runs from older scripts and the user's own game count too.
function Enter-GameSlot([string]$Owner, [int]$Max = 3, [int]$TimeoutMinutes = 90, [scriptblock]$Say = { param($t) Write-Host $t }) {
    New-Item -ItemType Directory -Force $script:SlotDir | Out-Null
    $deadline = (Get-Date).AddMinutes($TimeoutMinutes)
    $told = $false
    while ((Get-Date) -lt $deadline) {
        for ($i = 1; $i -le $Max; $i++) {
            $path = Join-Path $script:SlotDir "slot-$i.lock"
            try { $stream = [IO.File]::Open($path, 'OpenOrCreate', 'ReadWrite', 'Read') } catch { continue }
            if (@(Get-AllGames).Count -ge $Max) { $stream.Dispose(); break }
            $stream.SetLength(0)
            $info = [Text.Encoding]::UTF8.GetBytes("$Owner pid=$PID since=$(Get-Date -Format s)")
            $stream.Write($info, 0, $info.Length); $stream.Flush()
            return [pscustomobject]@{ Index = $i; Stream = $stream; Path = $path }
        }
        if (-not $told) {
            $busy = @(Get-ChildItem $script:SlotDir -Filter 'slot-*.lock' | ForEach-Object {
                try { $s = [IO.File]::Open($_.FullName, 'Open', 'Read', 'ReadWrite'); $r = New-Object IO.StreamReader($s); $t = $r.ReadToEnd(); $s.Dispose(); "$($_.BaseName): $t" } catch { } })
            & $Say "waiting for a free game slot (max $Max; games running: $(@(Get-AllGames).Count)); $($busy -join '; ')"
            $told = $true
        }
        Start-Sleep -Seconds 15
    }
    throw "no free game slot after $TimeoutMinutes minutes"
}

function Exit-GameSlot($Slot) { if ($Slot -and $Slot.Stream) { $Slot.Stream.Dispose() } }

# Copies the base instance without its saves, logs, screenshots and backups. -World copies one world in as -WorldName.
# -WhisperPort points this copy's Mob Talk at its own Whisper helper (if the copy has config\mobtalk.json).
function New-TestInstance {
    param([Parameter(Mandatory)][string]$Base, [Parameter(Mandatory)][string]$Name, [string]$JvmArgs = '',
        [string]$World = '', [string]$WorldName = '', [int]$WhisperPort = 0)
    Assert-TestName $Name
    $src = Join-Path $script:Instances $Base
    $final = Join-Path $script:Instances $Name
    if (-not (Test-Path -LiteralPath "$src\instance.cfg")) { throw "Base instance missing: $src" }
    if (Test-Path -LiteralPath $final) { Remove-TestInstance $Name }
    # Built outside instances\ and moved in whole: Prism rescans when a folder appears there, and a half-copied folder
    # (no instance.cfg yet) would be skipped until some later rescan.
    $dst = Join-Path $script:Staging $Name
    if (Test-Path -LiteralPath $dst) { Remove-Item -Recurse -Force -LiteralPath $dst }
    New-Item -ItemType Directory -Force "$dst\minecraft\saves" | Out-Null
    Get-ChildItem -LiteralPath $src -File | Where-Object Name -ne 'instance.cfg' | Copy-Item -Destination $dst
    if (Test-Path -LiteralPath "$src\patches") { Copy-Item -Recurse -LiteralPath "$src\patches" $dst }

    $keep = '^(OverrideJavaArgs|JvmArgs|name|uuid|notes|lastLaunchTime|lastTimePlayed|totalTimePlayed)='
    $lines = [IO.File]::ReadAllLines("$src\instance.cfg", [Text.Encoding]::UTF8) | Where-Object { $_ -notmatch $keep }
    $out = foreach ($l in $lines) {
        $l
        if ($l -eq '[General]') { "name=$Name"; 'notes=Throwaway self-test copy; deleted after the run.'; 'OverrideJavaArgs=true'; "JvmArgs=$JvmArgs" }
    }
    [IO.File]::WriteAllLines("$dst\instance.cfg", [string[]]$out, (New-Object Text.UTF8Encoding($false)))

    $skip = @('saves', 'logs', 'screenshots', 'crash-reports', 'downloads', 'data') + @(Get-ChildItem -LiteralPath "$src\minecraft" -Directory -Filter '*-backups' | ForEach-Object Name)
    Get-ChildItem -LiteralPath "$src\minecraft" -Force | Where-Object { $skip -notcontains $_.Name } | ForEach-Object {
        Copy-Item -Recurse -LiteralPath $_.FullName "$dst\minecraft\"
    }
    # Two windows share the screen: a client that loses focus must not open its pause menu and stall the test.
    $opt = "$dst\minecraft\options.txt"
    $o = if (Test-Path -LiteralPath $opt) { @([IO.File]::ReadAllLines($opt) | Where-Object { $_ -notmatch '^pauseOnLostFocus:' }) } else { @() }
    [IO.File]::WriteAllLines($opt, [string[]]($o + 'pauseOnLostFocus:false'), (New-Object Text.UTF8Encoding($false)))

    # Mob Talk (mobtalk.json) and Divine Block (divineblock.json) both keep whisperUrl; point every one at this copy's port.
    if ($WhisperPort -gt 0) {
        foreach ($cfg in @(Get-ChildItem "$dst\minecraft\config\*.json" -ErrorAction SilentlyContinue)) {
            $json = [IO.File]::ReadAllText($cfg.FullName, [Text.Encoding]::UTF8)
            if ($json -notmatch '"whisperUrl"') { continue }
            $json = $json -replace '"whisperUrl":\s*"[^"]*"', "`"whisperUrl`": `"http://127.0.0.1:$WhisperPort`""
            [IO.File]::WriteAllText($cfg.FullName, $json, (New-Object Text.UTF8Encoding($false)))
        }
    }
    if ($World) {
        if (-not $WorldName) { $WorldName = $World }
        Copy-Item -Recurse -LiteralPath "$src\minecraft\saves\$World" "$dst\minecraft\saves\$WorldName"
        Remove-Item -Force -LiteralPath "$dst\minecraft\saves\$WorldName\session.lock" -ErrorAction SilentlyContinue
    }
    Move-Item -LiteralPath $dst $final
    $final
}

# Makes a running Prism rescan instances\ (it does so when a folder appears or disappears there).
function Invoke-PrismRescan {
    $poke = Join-Path $script:Instances "t-rescan-$(Get-Random)"
    New-Item -ItemType Directory $poke | Out-Null
    Start-Sleep -Seconds 2
    Remove-Item -LiteralPath $poke -Force
}

# Removes only t-* instances whose own game is not running.
function Remove-TestInstance([string]$Name) {
    Assert-TestName $Name
    if (@(Get-InstanceGame $Name).Count) { throw "Game of $Name is still running" }
    $dst = Join-Path $script:Instances $Name
    for ($i = 0; $i -lt 10 -and (Test-Path -LiteralPath $dst); $i++) {
        try { Remove-Item -Recurse -Force -LiteralPath $dst } catch { Start-Sleep -Seconds 2 }
    }
    if (Test-Path -LiteralPath $dst) { throw "Could not delete $dst" }
}

# Hands the launch to the running PrismLauncher (or starts it) and returns this instance's javaw process.
# A running Prism that has not rescanned instances\ yet answers 'Launch command requires an valid instance ID ... resolves to
# nothing' in its log: then we make it rescan and send again. An old-style script elsewhere may kill Prism mid-launch: sent
# again too. A launch Prism accepted gets $QuietSeconds to produce a javaw before it is sent once more.
function Start-TestInstance([string]$Name, [int]$TimeoutSeconds = 600, [int]$QuietSeconds = 180) {
    Assert-TestName $Name
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $log = Join-Path $env:APPDATA 'PrismLauncher\logs\PrismLauncher-0.log'   # the running Prism's own log
    $refusal = "`"$Name`" resolves to nothing"
    while ((Get-Date) -lt $deadline) {
        $prism = @(Get-Process prismlauncher -ErrorAction SilentlyContinue | ForEach-Object Id)
        if ($prism.Count -and -not ((Test-Path $log) -and (Select-String -LiteralPath $log -SimpleMatch "Loaded instance `"$Name`"" -Quiet))) {
            Invoke-PrismRescan; Start-Sleep -Seconds 3
        }
        $before = if (Test-Path $log) { @(Select-String -LiteralPath $log -SimpleMatch $refusal).Count } else { 0 }
        Start-Process $script:PrismExe -ArgumentList '--launch', $Name
        $quiet = (Get-Date).AddSeconds($QuietSeconds)
        $retry = $false
        while (-not $retry -and (Get-Date) -lt $deadline) {
            Start-Sleep -Seconds 3
            $g = @(Get-InstanceGame $Name)
            if ($g.Count) { return $g[0] }
            $now = @(Get-Process prismlauncher -ErrorAction SilentlyContinue | ForEach-Object Id)
            if ($prism.Count -and -not @($now | Where-Object { $prism -contains $_ }).Count) { $retry = $true }   # Prism was restarted
            elseif ((Test-Path $log) -and @(Select-String -LiteralPath $log -SimpleMatch $refusal).Count -gt $before) { $retry = $true }
            elseif ((Get-Date) -gt $quiet) { $retry = $true }
        }
        if ($retry) { Start-Sleep -Seconds 2 }
    }
    $null
}

# Python Whisper helpers listening on a port (Mob Talk passes --port on the command line).
function Get-WhisperHelper([int]$Port) {
    @(Get-CimInstance Win32_Process -Filter "name='python.exe' or name='pythonw.exe'" |
        Where-Object { $_.CommandLine -match 'whisper_server\.py' -and $_.CommandLine -match "--port\s+$Port(\s|$)" })
}

Add-Type -ErrorAction SilentlyContinue -ReferencedAssemblies System.Drawing -TypeDefinition @'
using System; using System.Drawing; using System.Runtime.InteropServices;
public static class ParallelPrismWindow {
    [DllImport("user32.dll")] static extern bool GetWindowRect(IntPtr h, out RECT r);
    [DllImport("user32.dll")] static extern bool PrintWindow(IntPtr h, IntPtr dc, uint flags);
    [StructLayout(LayoutKind.Sequential)] struct RECT { public int L, T, R, B; }
    public static bool Save(IntPtr h, string path) {
        RECT r; if (h == IntPtr.Zero || !GetWindowRect(h, out r) || r.R - r.L < 50) return false;
        using (var bmp = new Bitmap(r.R - r.L, r.B - r.T)) using (var g = Graphics.FromImage(bmp)) {
            IntPtr dc = g.GetHdc(); bool ok = PrintWindow(h, dc, 2); g.ReleaseHdc(dc);
            if (ok) bmp.Save(path, System.Drawing.Imaging.ImageFormat.Png);
            return ok;
        }
    }
}
'@

# Saves a PNG of this instance's game window even when it is behind other windows (PrintWindow, full content).
function Save-GameWindowShot([string]$Name, [string]$Path) {
    $g = @(Get-InstanceGame $Name)
    if (-not $g.Count) { return $false }
    $p = Get-Process -Id $g[0].ProcessId -ErrorAction SilentlyContinue
    if (-not $p -or $p.MainWindowHandle -eq 0) { return $false }
    [ParallelPrismWindow]::Save($p.MainWindowHandle, $Path)
}

Export-ModuleMember -Function New-TestInstanceName, Get-InstanceGame, Get-AllGames, Enter-GameSlot, Exit-GameSlot,
    New-TestInstance, Remove-TestInstance, Start-TestInstance, Invoke-PrismRescan, Get-WhisperHelper, Save-GameWindowShot
