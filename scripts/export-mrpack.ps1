# Client pack for friends: a Modrinth .mrpack (Prism: Add Instance -> Import -> the file) with the jars of the built Prism
# pack. Every jar Modrinth knows by its SHA-1 (CTOV, Lithostitched, Fabric API, the video mods, CreativeCore...) goes in as
# a link with SHA-1 and SHA-512; the rest (our mods, Viewer Parrots) ride inside under overrides/mods. CTOV and
# Lithostitched may only ever be links. No configs, keys or worlds go in.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\export-mrpack.ps1 [-FromPack mob-talk-pack] [-Out <file.mrpack>]
param([string]$FromPack = 'mob-talk-pack', [string]$Out = '',
    [string]$Instances = (Join-Path $env:APPDATA 'PrismLauncher\instances'),
    [string]$Pins = (Join-Path $PSScriptRoot 'server-mods.json'), [hashtable]$Known = $null)
$ErrorActionPreference = 'Stop'
[Console]::OutputEncoding = [Text.Encoding]::UTF8
Import-Module (Join-Path $PSScriptRoot 'PackBuild.psm1') -Force
function Say($t) { Write-Host "$(Get-Date -Format HH:mm:ss) $t" }
# Jars that must never be shipped inside the file (their licences allow links only).
$linkOnly = '^(ctov|lithostitched)-'

$pinned = Get-ServerModPins $Pins
$pack = Get-InstancePackJars (Join-Path $Instances $FromPack)
$jars = @($pack.Jars)
$mobtalk = $jars | Where-Object Name -like 'mobtalk-*' | Select-Object -First 1
$commit = (($pack.Manifest | Where-Object { $_ -like 'mob-talk *' } | Select-Object -First 1) -split ' ')[2]
$version = if ($mobtalk) { (Get-FabricModInfo $mobtalk.FullName).Version } else { '0' }
$versionId = if ($commit) { "$version+$commit" } else { $version }
if (-not $Out) { $Out = Join-Path $env:LOCALAPPDATA "MobTalkPack\dist\mob-talk-pack-$versionId.mrpack" }

$sha1 = @{}
foreach ($jar in $jars) { $sha1[$jar.FullName] = Get-FileSha $jar.FullName SHA1 }
# -Known (tests) replaces the Modrinth lookup: sha1 -> object with files[] { hashes.sha1, hashes.sha512, url, size }.
$found = if ($Known) { $Known } else { Find-ModrinthVersions @($sha1.Values) }

$files = @(); $inside = @()
foreach ($jar in $jars) {
    $h = $sha1[$jar.FullName]
    $file = if ($found.ContainsKey($h)) { @($found[$h].files) | Where-Object { $_.hashes.sha1 -eq $h } | Select-Object -First 1 }
    if ($file) {
        $files += [ordered]@{
            path = "mods/$($jar.Name)"
            hashes = [ordered]@{ sha1 = $h; sha512 = Get-FileSha $jar.FullName SHA512 }
            env = [ordered]@{ client = 'required'; server = 'required' }
            downloads = @([string]$file.url)
            fileSize = [long]$jar.Length
        }
        Say "link   $($jar.Name)"
    } else {
        if ($jar.Name -match $linkOnly) { throw "$($jar.Name) is not on Modrinth by its hash: it may only be linked, not shipped." }
        $inside += $jar
        Say "inside $($jar.Name)"
    }
}
$index = [ordered]@{
    formatVersion = 1; game = 'minecraft'; versionId = $versionId; name = 'Mob Talk'
    summary = 'Mob Talk, Божественный блок, борщевик, клетки для попугаев, Viewer Parrots, CTOV, видео-моды'
    files = $files
    dependencies = [ordered]@{ minecraft = $pinned.minecraft; 'fabric-loader' = $pinned.fabricLoader }
}
$json = $index | ConvertTo-Json -Depth 8

New-Item -ItemType Directory -Force (Split-Path -Parent $Out) | Out-Null
if (Test-Path -LiteralPath $Out) { Remove-Item -LiteralPath $Out -Force }
$zip = [IO.Compression.ZipFile]::Open($Out, 'Create')
try {
    $entry = $zip.CreateEntry('modrinth.index.json')
    $writer = New-Object IO.StreamWriter($entry.Open(), (New-Object Text.UTF8Encoding($false)))
    try { $writer.Write($json) } finally { $writer.Dispose() }
    foreach ($jar in $inside) { [IO.Compression.ZipFileExtensions]::CreateEntryFromFile($zip, $jar.FullName, "overrides/mods/$($jar.Name)") | Out-Null }
} finally { $zip.Dispose() }
Say "mrpack ready: $Out ($($files.Count) links, $($inside.Count) jars inside)"
$Out
