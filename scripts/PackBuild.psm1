# Shared pieces of the pack scripts (Windows PowerShell 5.1): build-pack.ps1 (Prism instance), build-server-pack.ps1
# (dedicated server folder, Docker data) and export-mrpack.ps1 (client .mrpack for friends) take their jars from here, so
# all three ship the same commits. Nothing here touches Prism instances or worlds.
$ErrorActionPreference = 'Stop'
Add-Type -AssemblyName System.IO.Compression.FileSystem
$script:Utf8 = New-Object Text.UTF8Encoding($false)
$script:UserAgent = 'BigZet/mob-talk pack-scripts (github.com/BigZet)'

function Write-Utf8Lines([string]$Path, [string[]]$Lines) { [IO.File]::WriteAllLines($Path, $Lines, $script:Utf8) }
function Write-Utf8Text([string]$Path, [string]$Text) { [IO.File]::WriteAllText($Path, $Text, $script:Utf8) }

function Invoke-GradleBuild([string]$Dir, [string]$Label, [string]$Extra = '') {
    Push-Location $Dir
    try {
        cmd /c ".\gradlew.bat build -x test $Extra -q 2>&1" | Where-Object { $_ -match 'error|FAIL' }
        if ($LASTEXITCODE -ne 0) { throw "$Label build failed" }
    } finally { Pop-Location }
}

# Exports a repo's main with git archive into $Work\src\<repo> (apart from other threads' working copies).
function Export-RepoMain([string]$Repos, [string]$Work, [string]$RepoName) {
    $src = Join-Path $Repos $RepoName
    $dst = Join-Path $Work "src\$RepoName"
    if (Test-Path $dst) { Remove-Item -Recurse -Force -LiteralPath $dst }
    New-Item -ItemType Directory -Force $dst | Out-Null
    $tar = Join-Path $Work "$RepoName.tar"
    git -C $src archive -o $tar main
    & "$env:SystemRoot\System32\tar.exe" -xf $tar -C $dst
    Remove-Item $tar
    $dst
}

function Get-NewestJar([string]$Dir, [string]$Pattern) {
    Get-ChildItem (Join-Path $Dir $Pattern) -ErrorAction SilentlyContinue | Where-Object { $_.Name -notmatch '-sources|-dev' } |
        Sort-Object LastWriteTime -Descending | Select-Object -First 1
}

# Our repos besides Mob Talk, each with its jar pattern.
$script:OwnRepos = @(@('divine-block', 'divineblock-*.jar'), @('rusflora', 'rusflora-*.jar'), @('parrot-cages', 'parrotcages-*.jar'),
    @('minecraft-parrot', 'viewerparrots-*.jar'))

# Builds (unless -NoBuild) and collects every jar of the pack: Mob Talk from $Repo (this checkout), our other mods from
# their repos' main (Viewer Parrots too), CTOV + Lithostitched, the video mods and Fabric API as original jars from Prism.
# Returns @{ Jars = FileInfo[]; Manifest = string[] } — the manifest lines name the commits (pack-manifest.txt format).
function Get-PackJars {
    param([Parameter(Mandatory)][string]$Repo, [Parameter(Mandatory)][string]$Repos, [Parameter(Mandatory)][string]$Work,
        [string]$Base = '1.21.1-fabric-dev', [string]$Instances = (Join-Path $env:APPDATA 'PrismLauncher\instances'),
        [switch]$NoBuild, [scriptblock]$Say = { param($t) Write-Host $t })
    [Console]::OutputEncoding = [Text.Encoding]::UTF8   # git prints commit subjects in UTF-8
    $manifest = @(); $jars = @()
    if (-not $NoBuild) { & $Say 'build Mob Talk'; Invoke-GradleBuild $Repo 'Mob Talk' }
    $manifest += "mob-talk $((git -C $Repo rev-parse --abbrev-ref HEAD).Trim()) $((git -C $Repo rev-parse --short HEAD).Trim())"
    $jars += Get-NewestJar "$Repo\build\libs" 'mobtalk-*.jar'
    # Alice Speaker is its own mod in this checkout (alice-prototype/), a separate jar in the pack.
    if (-not $NoBuild) { & $Say 'build Alice Speaker'; Invoke-GradleBuild "$Repo\alice-prototype" 'Alice Speaker' }
    $jars += Get-NewestJar "$Repo\alice-prototype\build\libs" 'alicespeaker-*.jar'
    foreach ($r in $script:OwnRepos) {
        $dir = Join-Path $Work "src\$($r[0])"
        if (-not $NoBuild) {
            & $Say "export and build $($r[0]) main"
            $dir = Export-RepoMain $Repos $Work $r[0]
            Invoke-GradleBuild $dir $r[0] $(if ($r.Count -gt 2) { $r[2] } else { '' })
            $src = Join-Path $Repos $r[0]
            $manifest += "$($r[0]) main $((git -C $src rev-parse --short main).Trim()) $((git -C $src log -1 --format=%s main).Trim())"
        }
        $jars += Get-NewestJar "$dir\build\libs" $r[1]
    }
    # Viewer Parrots builds from its repo's main like our other mods (Twitch guest mode for the dedicated server, 22 October).
    $third = @(
        (Get-NewestJar "$Instances\mob-talk-ctov\minecraft\mods" 'ctov-*.jar'),
        (Get-NewestJar "$Instances\mob-talk-ctov\minecraft\mods" 'lithostitched-*.jar')) +
        @(Get-ChildItem "$Instances\$Base\minecraft\mods\*.jar" | Where-Object { $_.Name -notmatch '^(mobtalk|alicespeaker|viewerparrots|divineblock|rusflora|parrotcages|selftest-guard)' })
    $jars += $third | Where-Object { $_ }
    # Pinned pack mods from Modrinth (server-mods.json "pack": JourneyMap, Simple Voice Chat, IPN, Mouse Tweaks,
    # LambDynamicLights...): "both" also go to the server, "client" stay on the clients (Test-ClientOnlyJar).
    foreach ($m in @((Get-ServerModPins).pack)) {
        $jars += Save-PinnedFile -Url $m.url -FileName $m.fileName -Sha512 $m.sha512 -Cache (Join-Path $Work 'cache')
        $manifest += "pinned $($m.title) $($m.version) ($($m.side))"
    }
    @{ Jars = @($jars | Where-Object { $_ }); Manifest = $manifest }
}

# True for a jar that is one of the pinned client-only pack mods (server-mods.json "pack" with side "client").
function Test-ClientOnlyJar([string]$Name, $Pins = (Get-ServerModPins)) {
    [bool](@($Pins.pack) | Where-Object { $_.side -eq 'client' -and $_.fileName -eq $Name })
}

# Jars and commit lines of an already built Prism pack instance (the jars the clients run), for a server that matches them.
function Get-InstancePackJars([string]$Instance) {
    $manifestFile = Join-Path $Instance 'pack-manifest.txt'
    if (-not (Test-Path -LiteralPath $manifestFile)) { throw "No pack-manifest.txt in ${Instance}: build the pack first (scripts\build-pack.ps1)." }
    $lines = [IO.File]::ReadAllLines($manifestFile, [Text.Encoding]::UTF8)
    @{ Jars = @(Get-ChildItem -LiteralPath "$Instance\minecraft\mods" -Filter '*.jar' | Where-Object { $_.Name -notmatch '^selftest-guard' })
       Manifest = @($lines | Select-Object -Skip 1 | Where-Object { $_ -notmatch '^mod ' }) }
}

# Reads fabric.mod.json from a jar: id, version, environment ('*', 'client', 'server').
function Get-FabricModInfo([string]$Jar) {
    $zip = [IO.Compression.ZipFile]::OpenRead($Jar)
    try {
        $entry = $zip.GetEntry('fabric.mod.json')
        if (-not $entry) { return $null }
        $reader = New-Object IO.StreamReader($entry.Open(), [Text.Encoding]::UTF8)
        try { $text = $reader.ReadToEnd() } finally { $reader.Dispose() }
    } finally { $zip.Dispose() }
    $json = $text | ConvertFrom-Json
    $environment = if ($json.environment) { [string]$json.environment } else { '*' }
    [pscustomobject]@{ Id = [string]$json.id; Version = [string]$json.version; Environment = $environment }
}

function Get-FileSha([string]$Path, [string]$Algorithm) { (Get-FileHash -LiteralPath $Path -Algorithm $Algorithm).Hash.ToLowerInvariant() }

# Pinned server-side mods (scripts\server-mods.json): Modrinth file URL, file name and SHA-512 per mod.
function Get-ServerModPins([string]$Path = (Join-Path $PSScriptRoot 'server-mods.json')) {
    [IO.File]::ReadAllText($Path, [Text.Encoding]::UTF8) | ConvertFrom-Json
}

# A pinned file from $Cache, downloaded once; a cached or downloaded file with a different SHA-512 is refused.
function Save-PinnedFile {
    param([Parameter(Mandatory)][string]$Url, [Parameter(Mandatory)][string]$FileName, [Parameter(Mandatory)][string]$Sha512,
        [Parameter(Mandatory)][string]$Cache)
    New-Item -ItemType Directory -Force $Cache | Out-Null
    $target = Join-Path $Cache $FileName
    if ((Test-Path -LiteralPath $target) -and (Get-FileSha $target SHA512) -eq $Sha512.ToLowerInvariant()) { return Get-Item -LiteralPath $target }
    $part = "$target.part"
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    Invoke-WebRequest -Uri $Url -OutFile $part -UserAgent $script:UserAgent -UseBasicParsing
    $actual = Get-FileSha $part SHA512
    if ($actual -ne $Sha512.ToLowerInvariant()) { Remove-Item -LiteralPath $part -Force; throw "SHA-512 mismatch for $FileName from $Url" }
    Move-Item -LiteralPath $part $target -Force
    Get-Item -LiteralPath $target
}

# Looks jars up on Modrinth by SHA-1: returns a map sha1 -> Modrinth version (only the jars Modrinth knows).
function Find-ModrinthVersions([string[]]$Sha1) {
    if (-not $Sha1.Count) { return @{} }
    [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    $body = @{ hashes = @($Sha1); algorithm = 'sha1' } | ConvertTo-Json
    $answer = Invoke-RestMethod -Method Post -Uri 'https://api.modrinth.com/v2/version_files' -Body $body -ContentType 'application/json' -UserAgent $script:UserAgent
    $map = @{}
    foreach ($p in $answer.PSObject.Properties) { $map[$p.Name] = $p.Value }
    $map
}

# server.properties: our defaults, then what the admin changed in the existing file, then the keys this script owns
# (online-mode and the allowlist follow -Public; the port follows -Port).
function Merge-ServerProperties([string[]]$Existing, [switch]$Public, [int]$Port = 25565, [string]$Motd = 'Mob Talk: деревня, которая отвечает') {
    $defaults = [ordered]@{
        'motd' = $Motd; 'max-players' = '8'; 'view-distance' = '8'; 'simulation-distance' = '6'; 'difficulty' = 'normal'
        'level-name' = 'world'; 'spawn-protection' = '0'; 'enable-command-block' = 'false'; 'enable-rcon' = 'false'
        'sync-chunk-writes' = 'false'; 'max-tick-time' = '-1'
    }
    $owned = [ordered]@{
        'online-mode' = $(if ($Public) { 'true' } else { 'false' })
        'white-list' = $(if ($Public) { 'true' } else { 'false' })
        'enforce-whitelist' = $(if ($Public) { 'true' } else { 'false' })
        'server-port' = "$Port"
    }
    $values = [ordered]@{}
    foreach ($k in $defaults.Keys) { $values[$k] = $defaults[$k] }
    foreach ($line in @($Existing)) {
        if ($line -match '^\s*#' -or $line -notmatch '^([^=]+)=(.*)$') { continue }
        $values[$Matches[1].Trim()] = $Matches[2]
    }
    foreach ($k in $owned.Keys) { $values[$k] = $owned[$k] }
    @('# Mob Talk server; build-server-pack.ps1 keeps your edits except online-mode, white-list, enforce-whitelist, server-port.') +
        @($values.Keys | ForEach-Object { "$_=$($values[$_])" })
}

# Key bindings the pack moves so no two share a key (server-mods.json "pack" keys: Simple Voice Chat leaves V and G to Mob Talk;
# JourneyMap creates waypoints on K and leaves G, B, N, M, F and C unbound), as options.txt lines "key_<binding>:<key>".
function Get-PackKeyLines($Pins = (Get-ServerModPins)) {
    foreach ($m in @($Pins.pack)) {
        if (-not $m.keys) { continue }
        foreach ($p in $m.keys.PSObject.Properties) { "$($p.Name):$($p.Value)" }
    }
}

# options.txt lines with the pack's key defaults added where the file has no line for that binding yet (a player's own
# choice stays). Without a "version" line Minecraft would run old-format fixes, so a new file gets 1.21.1's data version.
function Merge-PackKeyLines([string[]]$Existing, [string[]]$KeyLines) {
    $lines = [Collections.Generic.List[string]]::new()
    foreach ($l in @($Existing)) { if ($null -ne $l) { $lines.Add($l) } }
    if (-not @($lines | Where-Object { $_ -like 'version:*' }).Count) { $lines.Insert(0, 'version:3955') }
    foreach ($k in @($KeyLines)) {
        $name = $k.Substring(0, $k.IndexOf(':') + 1)
        if (-not @($lines | Where-Object { $_.StartsWith($name) }).Count) { $lines.Add($k) }
    }
    [string[]]$lines
}

# Minecraft's servers.dat (uncompressed NBT) with one entry, for the client pack's multiplayer list:
# { servers: [ { name, ip, acceptTextures: 1 } ] }. Strings are length-prefixed UTF-8 (equal to Java's modified UTF-8 here).
function New-ServersDat([string]$Name, [string]$Address) {
    $ms = New-Object IO.MemoryStream
    $put = { param([byte[]]$b) $ms.Write($b, 0, $b.Length) }
    $str = { param([string]$s) $b = $script:Utf8.GetBytes($s); & $put ([byte[]]@([byte]($b.Length -shr 8), [byte]($b.Length -band 255))); & $put $b }
    & $put ([byte[]]@(10)); & $str ''
    & $put ([byte[]]@(9)); & $str 'servers'; & $put ([byte[]]@(10, 0, 0, 0, 1))
    & $put ([byte[]]@(8)); & $str 'name'; & $str $Name
    & $put ([byte[]]@(8)); & $str 'ip'; & $str $Address
    & $put ([byte[]]@(1)); & $str 'acceptTextures'; & $put ([byte[]]@(1))
    & $put ([byte[]]@(0, 0))
    $ms.ToArray()
}

# Secrets never go into a server folder: refuses a config file that looks like it carries an API key or token.
function Test-SecretFree([string]$Text) {
    -not ($Text -match 'sk-or-v1-[0-9a-f]{16}' -or $Text -match '"(api[_-]?key|token|secret|password)"\s*:\s*"[^"]{8,}"')
}

Export-ModuleMember -Function Write-Utf8Lines, Write-Utf8Text, Invoke-GradleBuild, Export-RepoMain, Get-NewestJar, Get-PackJars,
    Get-InstancePackJars, Get-FabricModInfo, Get-FileSha, Get-ServerModPins, Save-PinnedFile, Find-ModrinthVersions,
    Merge-ServerProperties, Test-SecretFree, Test-ClientOnlyJar, New-ServersDat, Get-PackKeyLines, Merge-PackKeyLines
