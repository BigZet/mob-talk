# Fixtures for the pack script tests (Pester 3.4, Windows PowerShell 5.1): fake Prism pack instances with tiny jars.
# Nothing here touches real instances, the network or %LOCALAPPDATA%.
Add-Type -AssemblyName System.IO.Compression.FileSystem

function New-FakeJar([string]$Path, [string]$Id, [string]$Environment = '*', [string]$Version = '1.0.0') {
    New-Item -ItemType Directory -Force (Split-Path -Parent $Path) | Out-Null
    if (Test-Path -LiteralPath $Path) { Remove-Item -LiteralPath $Path -Force }
    $zip = [IO.Compression.ZipFile]::Open($Path, 'Create')
    try {
        $w = New-Object IO.StreamWriter($zip.CreateEntry('fabric.mod.json').Open())
        try { $w.Write("{`"schemaVersion`":1,`"id`":`"$Id`",`"version`":`"$Version`",`"environment`":`"$Environment`"}") } finally { $w.Dispose() }
    } finally { $zip.Dispose() }
    Get-Item -LiteralPath $Path
}

# A built pack instance: jars, pack-manifest.txt (build-pack.ps1 format) and a few configs, one of them with a fake key.
function New-FakePack([string]$Instances, [string]$Name = 'fake-pack', [string]$MobTalkEnvironment = '*') {
    $mods = Join-Path $Instances "$Name\minecraft\mods"
    New-FakeJar "$mods\mobtalk-0.7.0.jar" 'mobtalk' $MobTalkEnvironment '0.7.0' | Out-Null
    New-FakeJar "$mods\divineblock-0.1.0.jar" 'divineblock' | Out-Null
    New-FakeJar "$mods\viewerparrots-1.2.1.jar" 'viewerparrots' | Out-Null
    New-FakeJar "$mods\ctov-fabric-3.6.3.jar" 'ctov' | Out-Null
    New-FakeJar "$mods\lithostitched-1.8.1-fabric-21.1.jar" 'lithostitched' | Out-Null
    New-FakeJar "$mods\fabric-api-0.116.17+1.21.1.jar" 'fabric-api' | Out-Null
    New-FakeJar "$mods\watervision-0.1.0.jar" 'watervision' 'client' | Out-Null
    New-FakeJar "$mods\selftest-guard.jar" 'selftestguard' 'client' | Out-Null
    $utf8 = New-Object Text.UTF8Encoding($false)
    [IO.File]::WriteAllLines((Join-Path $Instances "$Name\pack-manifest.txt"), [string[]]@(
        "Сборка $Name, собрана 2026-10-22 12:28", 'mob-talk main 5749f5b',
        'divine-block main 856dfca Слияние: третий вариант церкви', 'rusflora main acd7d82 Меню настроек борщевика',
        'parrot-cages main f5c6b96 README: рецепт маленькой клетки', 'mod mobtalk-0.7.0.jar', 'mod divineblock-0.1.0.jar'), $utf8)
    $cfg = Join-Path $Instances "$Name\minecraft\config"
    New-Item -ItemType Directory -Force $cfg | Out-Null
    [IO.File]::WriteAllText("$cfg\mobtalk.json", '{"llmModel": "openai/gpt-6-luna", "whisperUrl": "http://127.0.0.1:8765"}', $utf8)
    [IO.File]::WriteAllText("$cfg\divineblock.json", '{"wrath": 3}', $utf8)
    [IO.File]::WriteAllText("$cfg\viewerparrots.json", '{"apiKey": "sk-or-v1-0123456789abcdef0123456789abcdef"}', $utf8)
    Join-Path $Instances $Name
}

# Pins in the server-mods.json format whose files already sit in $Cache, so nothing is downloaded.
function New-FakePins([string]$Path, [string]$Cache) {
    New-Item -ItemType Directory -Force $Cache | Out-Null
    $entry = {
        param($file, $text)
        [IO.File]::WriteAllText((Join-Path $Cache $file), $text)
        (Get-FileHash (Join-Path $Cache $file) -Algorithm SHA512).Hash.ToLowerInvariant()
    }
    $pins = [ordered]@{
        minecraft = '1.21.1'; fabricLoader = '0.19.5'; fabricInstaller = '1.1.2'
        launcher = [ordered]@{ fileName = 'fabric-server-test-launcher.jar'; url = 'https://invalid.example/launcher.jar'; sha512 = (& $entry 'fabric-server-test-launcher.jar' 'launcher') }
        mods = @(
            [ordered]@{ id = 'lithium'; title = 'Lithium'; version = 'mc1.21.1-0.15.4-fabric'; fileName = 'lithium-test.jar'; url = 'https://invalid.example/l.jar'; sha512 = (& $entry 'lithium-test.jar' 'lithium') },
            [ordered]@{ id = 'spark'; title = 'spark'; version = '1.10.109-fabric'; fileName = 'spark-test.jar'; url = 'https://invalid.example/s.jar'; sha512 = (& $entry 'spark-test.jar' 'spark') })
        optional = @([ordered]@{ id = 'luckperms'; title = 'LuckPerms'; version = 'v5.4.140-fabric'; fileName = 'luckperms-test.jar'; url = 'https://invalid.example/lp.jar'; sha512 = (& $entry 'luckperms-test.jar' 'luckperms') })
    }
    [IO.File]::WriteAllText($Path, ($pins | ConvertTo-Json -Depth 6), (New-Object Text.UTF8Encoding($false)))
    $Path
}

function Read-ZipText([string]$Zip, [string]$Entry) {
    $z = [IO.Compression.ZipFile]::OpenRead($Zip)
    try {
        $e = $z.GetEntry($Entry)
        if (-not $e) { return $null }
        $r = New-Object IO.StreamReader($e.Open(), [Text.Encoding]::UTF8)
        try { $r.ReadToEnd() } finally { $r.Dispose() }
    } finally { $z.Dispose() }
}

function Get-ZipEntries([string]$Zip) {
    $z = [IO.Compression.ZipFile]::OpenRead($Zip)
    try { @($z.Entries | ForEach-Object FullName) } finally { $z.Dispose() }
}
