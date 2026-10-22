# Builds the single pack of all our mods into its own Prism instance (default mob-talk-pack): Mob Talk from this checkout,
# Divine Block, Rusflora (hogweed) and Parrot Cages from their repos' main (exported with git archive, built apart from the
# other threads' working copies), Viewer Parrots, CTOV + Lithostitched and the video mods as original jars.
# The first run copies the base instance (1.21.1-fabric-dev, without its worlds except a copy of "stream 1"); later runs
# only replace the mods. Never touches the base instance or its worlds, refuses while the pack's own game is running.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\build-pack.ps1 [-Name mob-talk-pack] [-NoBuild]
param([string]$Name = 'mob-talk-pack', [string]$Base = '1.21.1-fabric-dev', [switch]$NoBuild,
    [string]$Repos = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))))
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ParallelPrism.psm1') -Force
$repo = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path "$Repos\divine-block")) { $Repos = Split-Path -Parent $repo }
if (-not (Test-Path "$Repos\divine-block")) { $Repos = 'C:\Users\bigze\Documents\GH' }
$instances = Join-Path $env:APPDATA 'PrismLauncher\instances'
$work = Join-Path $env:LOCALAPPDATA 'MobTalkPack'
$inst = Join-Path $instances $Name
$mc = "$inst\minecraft"
function Say($t) { Write-Host "$(Get-Date -Format HH:mm:ss) $t" }
if (@(Get-InstanceGame $Name).Count -gt 0) { throw "The pack '$Name' is running: close the game first." }

$manifest = @("Сборка $Name, собрана $(Get-Date -Format 'yyyy-MM-dd HH:mm')")
function Build-Repo([string]$Dir, [string]$Label) {
    Push-Location $Dir
    try {
        cmd /c '.\gradlew.bat build -x test -q 2>&1' | Where-Object { $_ -match 'error|FAIL' }
        if ($LASTEXITCODE -ne 0) { throw "$Label build failed" }
    } finally { Pop-Location }
}
function Export-Main([string]$RepoName) {
    $src = Join-Path $Repos $RepoName
    $dst = Join-Path $work "src\$RepoName"
    if (Test-Path $dst) { Remove-Item -Recurse -Force -LiteralPath $dst }
    New-Item -ItemType Directory -Force $dst | Out-Null
    $commit = (git -C $src rev-parse --short main).Trim()
    $tar = Join-Path $work "$RepoName.tar"
    git -C $src archive -o $tar main
    & "$env:SystemRoot\System32\tar.exe" -xf $tar -C $dst
    Remove-Item $tar
    $script:manifest += "$RepoName main $commit $((git -C $src log -1 --format=%s main).Trim())"
    $dst
}
function Newest-Jar([string]$Dir, [string]$Pattern) {
    Get-ChildItem (Join-Path $Dir $Pattern) | Where-Object { $_.Name -notmatch '-sources|-dev' } | Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

$jars = @()
if (-not $NoBuild) { Say 'build Mob Talk'; Build-Repo $repo 'Mob Talk' }
$manifest += "mob-talk $((git -C $repo rev-parse --abbrev-ref HEAD).Trim()) $((git -C $repo rev-parse --short HEAD).Trim())"
$jars += Newest-Jar "$repo\build\libs" 'mobtalk-*.jar'
foreach ($r in @(@('divine-block', 'divineblock-*.jar'), @('rusflora', 'rusflora-*.jar'), @('parrot-cages', 'parrotcages-*.jar'))) {
    $dir = Join-Path $work "src\$($r[0])"
    if (-not $NoBuild) { Say "export and build $($r[0]) main"; $dir = Export-Main $r[0]; Build-Repo $dir $r[0] }
    $jars += Newest-Jar "$dir\build\libs" $r[1]
}
# Original third-party jars: Viewer Parrots 1.2.1 (knows the cages), CTOV + Lithostitched, the video mods and Fabric API.
$third = @(
    (Newest-Jar "$instances\parrot-cages-test\minecraft\mods" 'viewerparrots-*.jar'),
    (Newest-Jar "$instances\mob-talk-ctov\minecraft\mods" 'ctov-*.jar'),
    (Newest-Jar "$instances\mob-talk-ctov\minecraft\mods" 'lithostitched-*.jar')) +
    @(Get-ChildItem "$instances\$Base\minecraft\mods\*.jar" | Where-Object { $_.Name -notmatch '^(mobtalk|viewerparrots|divineblock|rusflora|parrotcages|selftest-guard)' })
$jars += $third | Where-Object { $_ }

if (-not (Test-Path "$inst\instance.cfg")) {
    Say "create instance $Name from $Base"
    $src = Join-Path $instances $Base
    $staging = Join-Path $env:APPDATA "PrismLauncher\parallel-staging\$Name"
    if (Test-Path $staging) { Remove-Item -Recurse -Force -LiteralPath $staging }
    New-Item -ItemType Directory -Force "$staging\minecraft\saves", "$staging\minecraft\mods" | Out-Null
    Get-ChildItem -LiteralPath $src -File | Where-Object Name -ne 'instance.cfg' | Copy-Item -Destination $staging
    if (Test-Path "$src\patches") { Copy-Item -Recurse "$src\patches" $staging }
    $drop = '^(name|uuid|notes|lastLaunchTime|lastTimePlayed|totalTimePlayed|OverrideJavaArgs|JvmArgs)='
    $cfg = foreach ($l in ([IO.File]::ReadAllLines("$src\instance.cfg", [Text.Encoding]::UTF8) | Where-Object { $_ -notmatch $drop })) {
        $l
        if ($l -eq '[General]') { 'name=Наша сборка (все моды)'; 'notes=Mob Talk, Божественный блок, борщевик, клетки для попугаев, Viewer Parrots, CTOV, видео. Собирает scripts\build-pack.ps1 из mob-talk.' }
    }
    [IO.File]::WriteAllLines("$staging\instance.cfg", [string[]]$cfg, (New-Object Text.UTF8Encoding($false)))
    $skip = @('saves', 'mods', 'logs', 'screenshots', 'crash-reports', 'downloads', 'data') + @(Get-ChildItem -LiteralPath "$src\minecraft" -Directory -Filter '*-backups' | ForEach-Object Name)
    Get-ChildItem -LiteralPath "$src\minecraft" -Force | Where-Object { $skip -notcontains $_.Name } | ForEach-Object { Copy-Item -Recurse -LiteralPath $_.FullName "$staging\minecraft\" }
    Copy-Item -Recurse -LiteralPath "$src\minecraft\saves\stream 1" "$staging\minecraft\saves\stream 1 (сборка)"
    Remove-Item -Force -LiteralPath "$staging\minecraft\saves\stream 1 (сборка)\session.lock" -ErrorAction SilentlyContinue
    $env:PYTHONIOENCODING = 'utf-8'
    python -I (Join-Path $PSScriptRoot 'rename_level.py') "$staging\minecraft\saves\stream 1 (сборка)\level.dat" 'stream 1 (сборка)' | Out-Null
    $divineCfg = "$instances\divine-block-test\minecraft\config\divineblock.json"
    if (Test-Path $divineCfg) { Copy-Item $divineCfg "$staging\minecraft\config\divineblock.json" }
    Move-Item -LiteralPath $staging $inst
    Invoke-PrismRescan
}

Say 'replace mods'
New-Item -ItemType Directory -Force "$mc\mods" | Out-Null
Get-ChildItem "$mc\mods\*.jar" | Remove-Item -Force
foreach ($jar in $jars) { Copy-Item -LiteralPath $jar.FullName "$mc\mods\"; $manifest += "mod $($jar.Name)" }
# One Whisper for the pack: both mods on the port Mob Talk uses.
$mt = "$mc\config\mobtalk.json"; $db = "$mc\config\divineblock.json"
if ((Test-Path $mt) -and (Test-Path $db)) {
    $url = ([IO.File]::ReadAllText($mt, [Text.Encoding]::UTF8) | Select-String '"whisperUrl":\s*"([^"]*)"').Matches[0].Groups[1].Value
    $json = [IO.File]::ReadAllText($db, [Text.Encoding]::UTF8) -replace '"whisperUrl":\s*"[^"]*"', "`"whisperUrl`": `"$url`""
    [IO.File]::WriteAllText($db, $json, (New-Object Text.UTF8Encoding($false)))
}
[IO.File]::WriteAllLines("$inst\pack-manifest.txt", [string[]]$manifest, (New-Object Text.UTF8Encoding($false)))
$manifest | ForEach-Object { Say $_ }
Say "pack ready: Prism instance '$Name'"
