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
    # Pinned pack mods as build-pack.ps1 lays them in: Mouse Tweaks claims both sides, the pins say client only.
    New-FakeJar "$pack\minecraft\mods\voicechat-test.jar" 'voicechat' | Out-Null
    New-FakeJar "$pack\minecraft\mods\mousetweaks-test.jar" 'mousetweaks' | Out-Null
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

    It 'prepares the maps for the admin: no web servers of their own, overworld and nether, BlueMap paused' {
        $sq = [IO.File]::ReadAllText("$out\squaremap\config.yml")
        $sq | Should Match 'internal-webserver:\s+enabled: false'
        $sq | Should Match "'minecraft:the_end':\s+map:\s+enabled: false"
        $core = [IO.File]::ReadAllText("$out\config\bluemap\core.conf")
        $core | Should Match 'accept-download: true'
        $core | Should Match 'render-thread-count: 1'
        [IO.File]::ReadAllText("$out\config\bluemap\webserver.conf") | Should Match 'enabled: false'
        @(Get-ChildItem "$out\config\bluemap\maps").Name -join ',' | Should Be 'world.conf,world_the_nether.conf'
        ([IO.File]::ReadAllText("$out\bluemap\pluginState.json") | ConvertFrom-Json).'render-threads-enabled' | Should Be $false
    }

    It 'goes public on -Public and keeps the world and admin edits on a rebuild' {
        $props = Read-Lines "$out\server.properties" | ForEach-Object { $_ -replace '^motd=.*', 'motd=Наш сервер' }
        Write-Utf8Lines "$out\server.properties" $props
        New-Item -ItemType Directory -Force "$out\world" | Out-Null
        Set-Content "$out\world\level.dat" 'world'
        Set-Content "$out\config\divineblock.json" '{"wrath": 9}'
        Set-Content "$out\config\bluemap\core.conf" 'render-thread-count: 3'
        & $build -FromPack fake-pack -Instances $inst -ConfigFrom fake-pack -Out $out -Pins $pins -Cache $cache -Public -WithLuckPerms *> $null
        Get-Prop $out 'online-mode' | Should Be 'true'
        Get-Prop $out 'white-list' | Should Be 'true'
        Get-Prop $out 'enforce-whitelist' | Should Be 'true'
        Get-Prop $out 'motd' | Should Be 'Наш сервер'
        (Get-Content "$out\world\level.dat") | Should Be 'world'
        (Get-Content "$out\config\divineblock.json") | Should Be '{"wrath": 9}'
        (Get-Content "$out\config\bluemap\core.conf") | Should Be 'render-thread-count: 3'
        Test-Path "$out\mods\luckperms-test.jar" | Should Be $true
    }

    It 'skips Mob Talk on -NoMobTalk' {
        Test-Path "$out\mods\mobtalk-0.7.0.jar" | Should Be $true
        & $build -FromPack fake-pack -Instances $inst -ConfigFrom fake-pack -Out $out -Pins $pins -Cache $cache -NoMobTalk *> $null
        Test-Path "$out\mods\mobtalk-0.7.0.jar" | Should Be $false
        @(Read-Lines "$out\server-manifest.txt" | Where-Object { $_ -like 'mod mobtalk-*' }).Count | Should Be 0
    }

    It 'leaves the pinned client-only pack mods out and keeps the ones for both sides' {
        Test-Path "$out\mods\mousetweaks-test.jar" | Should Be $false
        ($manifest -contains 'skip mousetweaks-test.jar client-only') | Should Be $true
        Test-Path "$out\mods\voicechat-test.jar" | Should Be $true
    }

    It 'skips a client-only Mob Talk without the flag' {
        $inst2 = Join-Path $TestDrive 'instances2'
        New-FakePack $inst2 'client-pack' 'client' | Out-Null
        $out2 = Join-Path $TestDrive 'server2'
        & $build -FromPack client-pack -Instances $inst2 -ConfigFrom client-pack -Out $out2 -Pins $pins -Cache $cache *> $null
        Test-Path "$out2\mods\mobtalk-0.7.0.jar" | Should Be $false
        Test-Path "$out2\mods\divineblock-0.1.0.jar" | Should Be $true
    }

    It 'opens the admin to the container on -Docker only, without a password hash' {
        Test-Path "$out\config\mobtalk-admin.json" | Should Be $false
        $out3 = Join-Path $TestDrive 'docker-data'
        & $build -FromPack fake-pack -Instances $inst -ConfigFrom fake-pack -Out $out3 -Pins $pins -Cache $cache -Docker *> $null
        $admin = [IO.File]::ReadAllText("$out3\config\mobtalk-admin.json") | ConvertFrom-Json
        $admin.bind | Should Be '0.0.0.0'
        $admin.port | Should Be 8787
        $admin.PSObject.Properties.Name -contains 'passwordHash' | Should Be $false
        Get-Prop $out3 'online-mode' | Should Be 'true'
        Test-Path "$out3\eula.txt" | Should Be $false
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
        ($p.mods | ForEach-Object id) -join ',' | Should Be 'lithium,ferrite-core,spark,chunky,squaremap,bluemap,worldedit,universal-graves,polymer'
        ($p.mods | Where-Object id -eq 'lithium').version | Should Be 'mc1.21.1-0.15.4-fabric'
        ($p.mods | Where-Object id -eq 'ferrite-core').version | Should Be '7.0.3-fabric'
        ($p.mods | Where-Object id -eq 'spark').version | Should Be '1.10.109-fabric'
        ($p.mods | Where-Object id -eq 'chunky').version | Should Be '1.4.23'
        ($p.mods | Where-Object id -eq 'squaremap').fileName | Should Be 'squaremap-fabric-mc1.21.1-1.3.2.jar'
        ($p.mods | Where-Object id -eq 'bluemap').fileName | Should Be 'bluemap-5.7-fabric.jar'
        $p.launcher.url | Should Match '/loader/1\.21\.1/0\.19\.5/1\.1\.2/server/jar$'
    }
    It 'pins the pack mods for both sides or the client only' {
        ($p.pack | Where-Object side -eq 'both' | ForEach-Object id) -join ',' | Should Be 'journeymap,simple-voice-chat'
        ($p.pack | Where-Object side -eq 'client' | ForEach-Object id) -join ',' |
            Should Be 'fabric-language-kotlin,libipn,inventory-profiles-next,mouse-tweaks,lambdynamiclights'
    }
    It 'has a SHA-512 and a Modrinth download for every mod' {
        foreach ($m in @($p.mods) + @($p.pack) + @($p.optional)) {
            $m.sha512 | Should Match '^[0-9a-f]{128}$'
            $m.url | Should Match '^https://cdn\.modrinth\.com/data/'
        }
        $p.launcher.sha512 | Should Match '^[0-9a-f]{128}$'
    }
}

Describe 'PackBuild helpers' {
    It 'moves the pack mods off our keys V, G and B, and only to free keys' {
        $keys = @(Get-PackKeyLines (Get-ServerModPins (Join-Path $scripts 'server-mods.json')))
        $keys -contains 'key_key.voice_chat:key.keyboard.y' | Should Be $true
        $keys -contains 'key_key.voice_chat_group:key.keyboard.u' | Should Be $true
        $keys -contains 'key_key.journeymap.create_waypoint:key.keyboard.k' | Should Be $true
        $ours = 'key.keyboard.(v|g|b|r|f7|f8|f9)$'
        @($keys | Where-Object { ($_ -split ':', 2)[1] -match $ours }).Count | Should Be 0
    }

    It 'keeps a binding the player already set and adds the missing ones' {
        $merged = Merge-PackKeyLines @('version:3955', 'key_key.voice_chat:key.keyboard.o') @('key_key.voice_chat:key.keyboard.y', 'key_key.push_to_talk:key.keyboard.caps.lock')
        $merged -contains 'key_key.voice_chat:key.keyboard.o' | Should Be $true
        $merged -contains 'key_key.voice_chat:key.keyboard.y' | Should Be $false
        $merged -contains 'key_key.push_to_talk:key.keyboard.caps.lock' | Should Be $true
        @($merged | Where-Object { $_ -like 'version:*' }).Count | Should Be 1
        (Merge-PackKeyLines @() @('key_a:key.keyboard.k'))[0] | Should Be 'version:3955'
    }

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
