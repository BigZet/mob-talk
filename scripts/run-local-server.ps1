# Starts the dedicated server built by build-server-pack.ps1 (default %LOCALAPPDATA%\MobTalkPack\server) in this window;
# the log goes to the window and to logs\latest.log. Keys come from the environment; a key missing there is read from its
# DPAPI file (the ones the mods' settings menus save) and handed to the server process only, never written to disk.
# Refuses when the port is taken. -Fresh moves the old world to world-backups\ and starts a new one; -World copies a world
# of the pack instance (saves\<name>, e.g. "stream 1 (сборка)") in as the server world. The base instance is never read.
# -Public: online-mode=true and the allowlist on, for friends over the internet (port forwarded on the router).
# -Smoke: start, wait until the server is up, stop it and list the log's ERROR lines (for tests and the integrator).
# -SetSecret <VARIABLE>: asks for a value (hidden input) and saves it DPAPI-encrypted for this Windows user, then exits.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\run-local-server.ps1 [-Fresh] [-World <name>] [-Public]
#        [-Memory 6G] [-NewWindow] [-Dir <folder>] | -SetSecret MOBTALK_ADMIN_TOKEN
param([string]$Dir = (Join-Path $env:LOCALAPPDATA 'MobTalkPack\server'), [switch]$Fresh, [string]$World = '', [switch]$Public,
    [string]$Memory = '6G', [int]$Port = 0, [switch]$NewWindow, [string]$Pack = 'mob-talk-pack',
    [string]$Instances = (Join-Path $env:APPDATA 'PrismLauncher\instances'), [string]$SetSecret = '', [switch]$CheckOnly, [switch]$Smoke, [int]$SmokeSeconds = 900)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'PackBuild.psm1') -Force
function Say($t) { Write-Host "$(Get-Date -Format HH:mm:ss) $t" }

# Where each secret may already live, encrypted for this Windows user (ConvertFrom-SecureString blobs).
$secrets = [ordered]@{
    'OPENROUTER_API_KEY' = @('MobTalk\openrouter-key.dpapi', 'DivineBlock\openrouter-key.dpapi')
    'VENICE_API_KEY' = @('MobTalk\venice-key.dpapi', 'DivineBlock\venice-key.dpapi')
    'ELEVENLABS_API_KEY' = @('MobTalk\elevenlabs-key.dpapi')
    'MOBTALK_ADMIN_TOKEN' = @('MobTalk\admin-token.dpapi')
}

if ($SetSecret) {
    if (-not $secrets.Contains($SetSecret)) { throw "Unknown secret '$SetSecret'; known: $($secrets.Keys -join ', ')" }
    $value = Read-Host "Value for $SetSecret (hidden)" -AsSecureString
    if ($value.Length -eq 0) { throw 'Empty value: nothing saved.' }
    $file = Join-Path $env:LOCALAPPDATA $secrets[$SetSecret][0]
    New-Item -ItemType Directory -Force (Split-Path -Parent $file) | Out-Null
    Write-Utf8Text $file (ConvertFrom-SecureString $value)
    Say "$SetSecret saved for this Windows user in $file"
    return
}

$launcher = Get-ChildItem (Join-Path $Dir 'fabric-server-*.jar') -ErrorAction SilentlyContinue | Select-Object -First 1
if (-not $launcher) { throw "No server in ${Dir}: run scripts\build-server-pack.ps1 first." }

$props = Join-Path $Dir 'server.properties'
$existing = if (Test-Path -LiteralPath $props) { [IO.File]::ReadAllLines($props, [Text.Encoding]::UTF8) } else { @() }
if (-not $Port) {
    $Port = 25565
    foreach ($l in $existing) { if ($l -match '^server-port=(\d+)$') { $Port = [int]$Matches[1] } }
}
if (@(Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue).Count) {
    throw "Port $Port is already in use (another server or game is listening): stop it or pass -Port."
}
$lock = Join-Path $Dir 'world\session.lock'
if (Test-Path -LiteralPath $lock) {
    try { $s = [IO.File]::Open($lock, 'Open', 'ReadWrite', 'None'); $s.Dispose() } catch { throw "The server in $Dir is already running." }
}
Write-Utf8Lines $props (Merge-ServerProperties $existing -Public:$Public -Port $Port)

if ($Fresh -or $World) {
    $old = Join-Path $Dir 'world'
    if (Test-Path -LiteralPath $old) {
        $backup = Join-Path $Dir "world-backups\world-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        New-Item -ItemType Directory -Force (Split-Path -Parent $backup) | Out-Null
        Move-Item -LiteralPath $old $backup
        Say "old world moved to $backup"
    }
}
if ($World) {
    $from = Join-Path $Instances "$Pack\minecraft\saves\$World"
    if (-not (Test-Path -LiteralPath "$from\level.dat")) { throw "No world '$World' in the pack instance $Pack." }
    Copy-Item -Recurse -LiteralPath $from (Join-Path $Dir 'world')
    Remove-Item -Force -LiteralPath (Join-Path $Dir 'world\session.lock') -ErrorAction SilentlyContinue
    Say "world '$World' copied from $Pack"
}

# Keys: the environment first, then DPAPI; values stay in this process and its child, and are never printed.
foreach ($name in $secrets.Keys) {
    if ([Environment]::GetEnvironmentVariable($name)) { Say "${name}: from the environment"; continue }
    $found = $false
    foreach ($rel in $secrets[$name]) {
        $file = Join-Path $env:LOCALAPPDATA $rel
        if (-not (Test-Path -LiteralPath $file)) { continue }
        try {
            $secure = ConvertTo-SecureString ([IO.File]::ReadAllText($file, [Text.Encoding]::UTF8).Trim())
            $plain = (New-Object Net.NetworkCredential('', $secure)).Password
            if ($plain) { [Environment]::SetEnvironmentVariable($name, $plain, 'Process'); $found = $true; Say "${name}: from $rel"; break }
        } catch { Say "${name}: could not decrypt $rel" }
    }
    if (-not $found) { Say "${name}: not set" }
}
if ($CheckOnly) { Say 'check only: not starting'; return }

$java = if ($env:JAVA_HOME -and (Test-Path "$env:JAVA_HOME\bin\java.exe")) { "$env:JAVA_HOME\bin\java.exe" } else { (Get-Command java -ErrorAction Stop).Source }
$javaArgs = @("-Xms2G", "-Xmx$Memory", '-XX:+UseG1GC', '-Dfile.encoding=UTF-8', '-jar', $launcher.Name, 'nogui')
Say "start: $java $($javaArgs -join ' ') in $Dir ($(if ($Public) { 'public' } else { 'local test, online-mode=false' }), port $Port)"
if ($NewWindow) {
    Start-Process -FilePath $java -ArgumentList $javaArgs -WorkingDirectory $Dir
    return
}
# -Smoke: start, wait for "Done", send "stop", then list ERROR lines of the log; exit 1 if it never came up.
if ($Smoke) {
    $psi = New-Object Diagnostics.ProcessStartInfo $java
    $psi.Arguments = ($javaArgs | ForEach-Object { if ($_ -match '\s') { "`"$_`"" } else { $_ } }) -join ' '
    $psi.WorkingDirectory = $Dir; $psi.UseShellExecute = $false; $psi.RedirectStandardInput = $true
    $started = Get-Date
    # .NET Framework writes the child's stdin in Console.InputEncoding; UTF-8 there carries a BOM the console would not know.
    [Console]::InputEncoding = New-Object Text.UTF8Encoding($false)
    $p = [Diagnostics.Process]::Start($psi)
    $log = Join-Path $Dir 'logs\latest.log'
    $up = $false
    while (-not $p.HasExited -and ((Get-Date) - $started).TotalSeconds -lt $SmokeSeconds) {
        Start-Sleep -Seconds 5
        if ((Test-Path $log) -and (Get-Item $log).LastWriteTime -ge $started -and (Select-String -LiteralPath $log -Pattern 'Done \(\d' -Quiet)) { $up = $true; break }
    }
    if (-not $p.HasExited) { $p.StandardInput.WriteLine('stop'); $p.StandardInput.Flush() }
    if (-not $p.WaitForExit(180000)) { $p.Kill(); Say 'server did not stop in 3 minutes: killed' }
    $errors = @(if (Test-Path $log) { Select-String -LiteralPath $log -Pattern '/(ERROR|FATAL)\]' | ForEach-Object Line |
        Where-Object { $_ -notmatch 'No data fixer registered for' } })   # vanilla noise for every modded entity
    Say "smoke: $(if ($up) { 'server came up' } else { 'server did NOT come up' }), exit $($p.ExitCode), $($errors.Count) ERROR lines in latest.log"
    $errors | Select-Object -First 40 | ForEach-Object { Write-Host $_ }
    if (-not $up) { exit 1 }
    return
}
Push-Location $Dir
try { & $java @javaArgs } finally { Pop-Location }
Say "server stopped (exit $LASTEXITCODE)"
