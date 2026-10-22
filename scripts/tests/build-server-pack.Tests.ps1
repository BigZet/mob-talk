# Pester 3.4 (Windows PowerShell 5.1): Invoke-Pester scripts\tests
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$scripts = Split-Path -Parent $here
. (Join-Path $here 'PackFixtures.ps1')
Import-Module (Join-Path $scripts 'PackBuild.psm1') -Force
$build = Join-Path $scripts 'build-server-pack.ps1'

function Read-Lines([string]$Path) { [IO.File]::ReadAllLines($Path, [Text.Encoding]::UTF8) }
function Get-Prop([string]$Out, [string]$Key) {
    (Read-Lines "$Out\server.properties" | Where-Object { $_ -like "$Key=*" } | Select-Object -First 1) -replace '^[^=]+=', ''
}

Describe 'build-server-pack.ps1' {
    $inst = Join-Path $TestDrive 'instances'
    $pack = New-FakePack $inst
    $cache = Join-Path $TestDrive 'cache'
    $pins = New-FakePins (Join-Path $TestDrive 'pins.json') $cache
    $out = Join-Path $TestDrive 'server'
    & $build -FromPack fake-pack -Instances $inst -ConfigFrom fake-pack -Out $out -Pins $pins -Cache $cache *> $null
    $manifest = Read-Lines "$out\server-manifest.txt"

    It 'names the same commits as pack-manifest.txt' {
        $commits = Read-Lines "$pack\pack-manifest.txt" | Select-Object -Skip 1 | Where-Object { $_ -notmatch '^mod ' }
        $commits.Count | Should Be 4
        foreach ($c in $commits) { ($manifest -contains $c) | Should Be $true }
    }

    It 'adds the pinned admin mods with their versions and SHA-512' {
        $p = Get-ServerModPins $pins
        foreach ($m in $p.mods) {
            ($manifest -contains "admin $($m.title) $($m.version) $($m.fileName) sha512=$($m.sha512)") | Should Be $true
            (Get-FileSha "$out\mods\$($m.fileName)" SHA512) | Should Be $m.sha512
        }
        Test-Path "$out\mods\luckperms-test.jar" | Should Be $false
        Test-Path "$out\$($p.launcher.fileName)" | Should Be $true
    }

    It 'records every jar with its SHA-512 and leaves client-only jars out' {
        foreach ($jar in Get-ChildItem "$out\mods\*.jar" | Where-Object Name -notlike '*-test.jar') {
            ($manifest -contains "mod $($jar.Name) sha512=$(Get-FileSha $jar.FullName SHA512)") | Should Be $true
        }
        Test-Path "$out\mods\divineblock-0.1.0.jar" | Should Be $true
        Test-Path "$out\mods\ctov-fabric-3.6.3.jar" | Should Be $true
        Test-Path "$out\mods\watervision-0.1.0.jar" | Should Be $false
        Test-Path "$out\mods\selftest-guard.jar" | Should Be $false
    }

    It 'writes a local server.properties by default' {
        Get-Prop $out 'online-mode' | Should Be 'false'
        Get-Prop $out 'white-list' | Should Be 'false'
        Get-Prop $out 'view-distance' | Should Be '8'
        Get-Prop $out 'simulation-distance' | Should Be '6'
        Get-Prop $out 'max-players' | Should Be '8'
        Get-Prop $out 'enable-rcon' | Should Be 'false'
        (Read-Lines "$out\eula.txt") -contains 'eula=true' | Should Be $true
    }

    It 'copies configs once, never one holding a key, and starts mobtalk-server.json empty' {
        Test-Path "$out\config\mobtalk.json" | Should Be $true
        Test-Path "$out\config\divineblock.json" | Should Be $true
        Test-Path "$out\config\viewerparrots.json" | Should Be $false
        ([IO.File]::ReadAllText("$out\config\mobtalk-server.json")).Trim() | Should Be '{}'
    }

    It 'goes public on -Public and keeps the world and admin edits on a rebuild' {
        $props = Read-Lines "$out\server.properties" | ForEach-Object { $_ -replace '^motd=.*', 'motd=Наш сервер' }
        Write-Utf8Lines "$out\server.properties" $props
        New-Item -ItemType Directory -Force "$out\world" | Out-Null
        Set-Content "$out\world\level.dat" 'world'
        Set-Content "$out\config\divineblock.json" '{"wrath": 9}'
        & $build -FromPack fake-pack -Instances $inst -ConfigFrom fake-pack -Out $out -Pins $pins -Cache $cache -Public -WithLuckPerms *> $null
        Get-Prop $out 'online-mode' | Should Be 'true'
        Get-Prop $out 'white-list' | Should Be 'true'
        Get-Prop $out 'enforce-whitelist' | Should Be 'true'
        Get-Prop $out 'motd' | Should Be 'Наш сервер'
        (Get-Content "$out\world\level.dat") | Should Be 'world'
        (Get-Content "$out\config\divineblock.json") | Should Be '{"wrath": 9}'
        Test-Path "$out\mods\luckperms-test.jar" | Should Be $true
    }

    It 'skips Mob Talk on -NoMobTalk' {
        Test-Path "$out\mods\mobtalk-0.7.0.jar" | Should Be $true
        & $build -FromPack fake-pack -Instances $inst -ConfigFrom fake-pack -Out $out -Pins $pins -Cache $cache -NoMobTalk *> $null
        Test-Path "$out\mods\mobtalk-0.7.0.jar" | Should Be $false
        @(Read-Lines "$out\server-manifest.txt" | Where-Object { $_ -like 'mod mobtalk-*' }).Count | Should Be 0
    }

    It 'skips a client-only Mob Talk without the flag' {
        $inst2 = Join-Path $TestDrive 'instances2'
        New-FakePack $inst2 'client-pack' 'client' | Out-Null
        $out2 = Join-Path $TestDrive 'server2'
        & $build -FromPack client-pack -Instances $inst2 -ConfigFrom client-pack -Out $out2 -Pins $pins -Cache $cache *> $null
        Test-Path "$out2\mods\mobtalk-0.7.0.jar" | Should Be $false
        Test-Path "$out2\mods\divineblock-0.1.0.jar" | Should Be $true
    }

    It 'refuses while the server holds its world lock' {
        $lock = [IO.File]::Open("$out\world\session.lock", 'OpenOrCreate', 'ReadWrite', 'None')
        try { { & $build -FromPack fake-pack -Instances $inst -Out $out -Pins $pins -Cache $cache *> $null } | Should Throw 'running' }
        finally { $lock.Dispose() }
    }
}

Describe 'server-mods.json' {
    $p = Get-ServerModPins (Join-Path $scripts 'server-mods.json')
    It 'pins Minecraft 1.21.1, the loader of the client pack and the admin mods' {
        $p.minecraft | Should Be '1.21.1'
        $p.fabricLoader | Should Be '0.19.5'
        ($p.mods | ForEach-Object id) -join ',' | Should Be 'lithium,ferrite-core,spark,chunky'
        ($p.mods | Where-Object id -eq 'lithium').version | Should Be 'mc1.21.1-0.15.4-fabric'
        ($p.mods | Where-Object id -eq 'ferrite-core').version | Should Be '7.0.3-fabric'
        ($p.mods | Where-Object id -eq 'spark').version | Should Be '1.10.109-fabric'
        ($p.mods | Where-Object id -eq 'chunky').version | Should Be '1.4.23'
        $p.launcher.url | Should Match '/loader/1\.21\.1/0\.19\.5/1\.1\.2/server/jar$'
    }
    It 'has a SHA-512 and a Modrinth download for every mod' {
        foreach ($m in @($p.mods) + @($p.optional)) {
            $m.sha512 | Should Match '^[0-9a-f]{128}$'
            $m.url | Should Match '^https://cdn\.modrinth\.com/data/'
        }
        $p.launcher.sha512 | Should Match '^[0-9a-f]{128}$'
    }
}

Describe 'PackBuild helpers' {
    It 'refuses a pinned file whose SHA-512 differs' {
        $src = Join-Path $TestDrive 'evil.jar'
        Set-Content $src 'not the pinned bytes'
        $cache = Join-Path $TestDrive 'cache-evil'
        { Save-PinnedFile -Url ([Uri]$src).AbsoluteUri -FileName 'pinned.jar' -Sha512 ('0' * 128) -Cache $cache } | Should Throw 'SHA-512 mismatch'
        Test-Path (Join-Path $cache 'pinned.jar') | Should Be $false
    }
    It 'keeps server.properties edits but owns online-mode and the allowlist' {
        $lines = Merge-ServerProperties @('online-mode=true', 'difficulty=hard', 'white-list=true')
        $lines -contains 'online-mode=false' | Should Be $true
        $lines -contains 'white-list=false' | Should Be $true
        $lines -contains 'difficulty=hard' | Should Be $true
        (Merge-ServerProperties @() -Public) -contains 'online-mode=true' | Should Be $true
    }
    It 'spots configs with keys' {
        Test-SecretFree '{"llmModel": "x"}' | Should Be $true
        Test-SecretFree '{"token": "abcdefghijkl"}' | Should Be $false
        Test-SecretFree 'key sk-or-v1-0123456789abcdef0' | Should Be $false
    }
}
