# Builds the dedicated Fabric server folder from the same commits as build-pack.ps1: our mods and the original third-party
# jars (client-only jars are left out), the pinned admin mods from Modrinth (scripts\server-mods.json: Lithium, FerriteCore,
# spark, Chunky, squaremap, BlueMap, WorldEdit, Universal Graves with Polymer; -WithLuckPerms adds LuckPerms), the pinned
# Fabric server launcher, server.properties, eula.txt and the
# server configs. A rebuild replaces only the jars and the manifest: the world, the allowlist and config edits stay.
# Secrets never land here: keys come from the environment at start (run-local-server.ps1, docker/.env).
# Usage: powershell -ExecutionPolicy Bypass -File scripts\build-server-pack.ps1 [-FromPack mob-talk-pack] [-NoBuild]
#        [-NoMobTalk] [-Public] [-Docker] [-WithLuckPerms] [-Out <folder>]
#   -FromPack <instance>  take the jars and commits of an already built Prism pack (exactly what the clients run)
#   -Public               online-mode=true and the allowlist on (friends over the internet); default is the local test
#   -Docker               lay mods/ and config/ out in docker\data for docker-compose (itzg/minecraft-server)
param([string]$Out = '', [string]$FromPack = '', [switch]$NoBuild, [switch]$NoMobTalk, [switch]$Public, [switch]$Docker,
    [switch]$WithLuckPerms, [int]$Port = 25565, [string]$ConfigFrom = 'mob-talk-pack', [string]$Base = '1.21.1-fabric-dev',
    [string]$Instances = (Join-Path $env:APPDATA 'PrismLauncher\instances'),
    [string]$Repos = (Split-Path -Parent (Split-Path -Parent (Split-Path -Parent $PSScriptRoot))),
    [string]$Pins = (Join-Path $PSScriptRoot 'server-mods.json'),
    [string]$Cache = (Join-Path $env:LOCALAPPDATA 'MobTalkPack\cache'), [switch]$Offline)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Import-Module (Join-Path $PSScriptRoot 'PackBuild.psm1') -Force
$repo = Split-Path -Parent $PSScriptRoot
if (-not (Test-Path "$Repos\divine-block")) { $Repos = Split-Path -Parent $repo }
if (-not (Test-Path "$Repos\divine-block")) { $Repos = 'C:\Users\bigze\Documents\GH' }
$work = Join-Path $env:LOCALAPPDATA 'MobTalkPack'
if (-not $Out) { $Out = if ($Docker) { Join-Path $repo 'docker\data' } else { Join-Path $work 'server' } }
if ($Docker) { $Public = $true }
function Say($t) { Write-Host "$(Get-Date -Format HH:mm:ss) $t" }

# A running server holds world\session.lock: never swap jars under it.
$lock = Join-Path $Out 'world\session.lock'
if (Test-Path -LiteralPath $lock) {
    try { $s = [IO.File]::Open($lock, 'Open', 'ReadWrite', 'None'); $s.Dispose() } catch { throw "The server in $Out is running: stop it first." }
}

$pinned = Get-ServerModPins $Pins
$label = if ($FromPack) { "из инстанса $FromPack" } else { 'из репозиториев' }
$manifest = @("Сервер Mob Talk, собран $(Get-Date -Format 'yyyy-MM-dd HH:mm') $label; Minecraft $($pinned.minecraft), Fabric Loader $($pinned.fabricLoader)")
$pack = if ($FromPack) { Get-InstancePackJars (Join-Path $Instances $FromPack) }
        else { Get-PackJars -Repo $repo -Repos $Repos -Work $work -Base $Base -Instances $Instances -NoBuild:$NoBuild -Say ${function:Say} }
$manifest += $pack.Manifest

$jars = @()
foreach ($jar in $pack.Jars) {
    $info = Get-FabricModInfo $jar.FullName
    if ($NoMobTalk -and $jar.Name -like 'mobtalk-*') { Say "skip $($jar.Name) (-NoMobTalk)"; continue }
    if (Test-ClientOnlyJar $jar.Name $pinned) { Say "skip $($jar.Name): client-only pack mod"; $manifest += "skip $($jar.Name) client-only"; continue }
    if ($info -and $info.Environment -eq 'client') { Say "skip $($jar.Name): client-only mod"; $manifest += "skip $($jar.Name) client-only"; continue }
    $jars += $jar
}

New-Item -ItemType Directory -Force "$Out\mods", "$Out\config" | Out-Null
Say 'replace mods'
Get-ChildItem "$Out\mods\*.jar" -ErrorAction SilentlyContinue | Remove-Item -Force
foreach ($jar in $jars) {
    Copy-Item -LiteralPath $jar.FullName "$Out\mods\"
    $manifest += "mod $($jar.Name) sha512=$(Get-FileSha $jar.FullName SHA512)"
}
$admin = @($pinned.mods) + $(if ($WithLuckPerms) { @($pinned.optional | Where-Object id -eq 'luckperms') } else { @() })
foreach ($m in $admin) {
    if ($Offline -and -not (Test-Path -LiteralPath (Join-Path $Cache $m.fileName))) { throw "-Offline: $($m.fileName) is not in $Cache" }
    $file = Save-PinnedFile -Url $m.url -FileName $m.fileName -Sha512 $m.sha512 -Cache $Cache
    Copy-Item -LiteralPath $file.FullName "$Out\mods\"
    $manifest += "admin $($m.title) $($m.version) $($m.fileName) sha512=$($m.sha512)"
}

# itzg/minecraft-server installs Fabric itself from FABRIC_LOADER_VERSION; the Windows folder gets the pinned launcher.
if (-not $Docker) {
    $l = $pinned.launcher
    $file = Save-PinnedFile -Url $l.url -FileName $l.fileName -Sha512 $l.sha512 -Cache $Cache
    Get-ChildItem "$Out\fabric-server-*.jar" -ErrorAction SilentlyContinue | Where-Object Name -ne $l.fileName | Remove-Item -Force
    Copy-Item -LiteralPath $file.FullName (Join-Path $Out $l.fileName) -Force
    $manifest += "launcher $($l.fileName) sha512=$($l.sha512)"
    # The Minecraft EULA (https://aka.ms/MinecraftEULA) is accepted by the server's admin; the plan of 22 October lists it.
    Write-Utf8Lines "$Out\eula.txt" @('# https://aka.ms/MinecraftEULA', 'eula=true')
}

$props = Join-Path $Out 'server.properties'
$existing = if (Test-Path -LiteralPath $props) { [IO.File]::ReadAllLines($props, [Text.Encoding]::UTF8) } else { @() }
Write-Utf8Lines $props (Merge-ServerProperties $existing -Public:$Public -Port $Port)

# Server configs: copied once from the pack's own (an admin's later edits win), never a file that carries a secret.
# mobtalk-server.json starts empty (every field at its default); ServerConfig migrates mobtalk.json into it.
$seed = Join-Path $Instances "$ConfigFrom\minecraft\config"
foreach ($name in @('mobtalk.json', 'divineblock.json', 'ctov.json', 'lithostitched.json', 'rusflora-hogweed.json', 'waterframes.json', 'viewerparrots.json')) {
    $from = Join-Path $seed $name; $to = Join-Path "$Out\config" $name
    if ((Test-Path -LiteralPath $to) -or -not (Test-Path -LiteralPath $from)) { continue }
    if (-not (Test-SecretFree ([IO.File]::ReadAllText($from, [Text.Encoding]::UTF8)))) { Say "skip config ${name}: looks like it holds a key"; continue }
    Copy-Item -LiteralPath $from $to
}
if (-not (Test-Path -LiteralPath "$Out\config\mobtalk-server.json")) { Write-Utf8Text "$Out\config\mobtalk-server.json" "{}`n" }
# squaremap (2D map of the admin): no web server of its own, the admin serves squaremap\web\tiles behind its password;
# overworld and nether only. Written once; squaremap fills in every other key with its defaults on the first start.
if (-not (Test-Path -LiteralPath "$Out\squaremap\config.yml")) {
    New-Item -ItemType Directory -Force "$Out\squaremap" | Out-Null
    Write-Utf8Lines "$Out\squaremap\config.yml" @(
        'settings:', '    internal-webserver:', '        enabled: false', '    update-checker: false',
        'world-settings:', '    default:', '        map:', '            max-render-threads: 2',
        "    'minecraft:the_end':", '        map:', '            enabled: false')
}
# BlueMap (3D map of the admin): no web server of its own (the admin serves bluemap\web behind its password), one
# render thread, overworld and nether. accept-download fetches Minecraft's client jar for textures: Dmitriy accepted the
# Minecraft EULA for it on 22 October. Rendering is paused (pluginState.json) until the admin starts it while the map
# is open. Written once; BlueMap generates plugin.conf, webapp.conf and storages\ with defaults on the first start.
$bm = "$Out\config\bluemap"
if (-not (Test-Path -LiteralPath $bm)) {
    New-Item -ItemType Directory -Force "$bm\maps" | Out-Null
    Write-Utf8Lines "$bm\core.conf" @('accept-download: true', 'data: "bluemap"', 'render-thread-count: 1',
        'scan-for-mod-resources: true', 'metrics: false', 'log: {', '  file: "bluemap/logs/debug.log"', '  append: false', '}')
    Write-Utf8Lines "$bm\webserver.conf" @('enabled: false', 'webroot: "bluemap/web"', 'port: 8100')
    Write-Utf8Lines "$bm\maps\world.conf" @('world: "world"', 'dimension: "minecraft:overworld"', 'name: "Обычный мир"',
        'sorting: 0', 'sky-color: "#7dabff"', 'void-color: "#000000"', 'ambient-light: 0.1', 'remove-caves-below-y: 55', 'storage: "file"')
    Write-Utf8Lines "$bm\maps\world_the_nether.conf" @('world: "world"', 'dimension: "minecraft:the_nether"', 'name: "Незер"',
        'sorting: 100', 'sky-color: "#290000"', 'void-color: "#150000"', 'ambient-light: 0.6', 'remove-caves-below-y: -10000',
        'max-y: 90', 'storage: "file"')
}
if (-not (Test-Path -LiteralPath "$Out\bluemap\pluginState.json")) {
    New-Item -ItemType Directory -Force "$Out\bluemap" | Out-Null
    Write-Utf8Text "$Out\bluemap\pluginState.json" "{`"render-threads-enabled`": false}`n"
}
# In Docker the admin must listen on all interfaces of the container; the host publishes 8787 on loopback only.
# Written once without a password hash: the admin generates the password on its first start.
if ($Docker -and -not (Test-Path -LiteralPath "$Out\config\mobtalk-admin.json")) {
    Write-Utf8Text "$Out\config\mobtalk-admin.json" "{`"bind`": `"0.0.0.0`", `"port`": 8787}`n"
}

Write-Utf8Lines "$Out\server-manifest.txt" $manifest
$manifest | ForEach-Object { Say $_ }
Say "server ready: $Out ($(if ($Public) { 'public: online-mode=true, allowlist on' } else { 'local test: online-mode=false' }))"
