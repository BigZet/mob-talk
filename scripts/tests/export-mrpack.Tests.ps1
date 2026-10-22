# Pester 3.4 (Windows PowerShell 5.1): Invoke-Pester scripts\tests
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$scripts = Split-Path -Parent $here
. (Join-Path $here 'PackFixtures.ps1')
Import-Module (Join-Path $scripts 'PackBuild.psm1') -Force
$export = Join-Path $scripts 'export-mrpack.ps1'

# What Modrinth would answer for the given jars (sha1 -> version with files[]).
function New-Known([string[]]$Jars) {
    $known = @{}
    foreach ($j in $Jars) {
        $sha1 = Get-FileSha $j SHA1
        $known[$sha1] = [pscustomobject]@{ files = @([pscustomobject]@{
            hashes = [pscustomobject]@{ sha1 = $sha1; sha512 = Get-FileSha $j SHA512 }
            url = "https://cdn.modrinth.com/data/xxxx/versions/yyyy/$(Split-Path -Leaf $j)"; size = (Get-Item $j).Length }) }
    }
    $known
}

Describe 'export-mrpack.ps1' {
    $inst = Join-Path $TestDrive 'instances'
    $pack = New-FakePack $inst
    $mods = "$pack\minecraft\mods"
    $known = New-Known @("$mods\ctov-fabric-3.6.3.jar", "$mods\lithostitched-1.8.1-fabric-21.1.jar", "$mods\fabric-api-0.116.17+1.21.1.jar", "$mods\watervision-0.1.0.jar")
    $file = Join-Path $TestDrive 'pack.mrpack'
    & $export -FromPack fake-pack -Instances $inst -Out $file -Known $known *> $null
    $index = (Read-ZipText $file 'modrinth.index.json') | ConvertFrom-Json
    $entries = Get-ZipEntries $file

    It 'writes a modrinth.index.json that follows the format' {
        $index.formatVersion | Should Be 1
        $index.game | Should Be 'minecraft'
        $index.name | Should Be 'Mob Talk'
        $index.versionId | Should Be '0.7.0+5749f5b'
        $index.dependencies.minecraft | Should Be '1.21.1'
        $index.dependencies.'fabric-loader' | Should Be '0.19.5'
        @($index.files).Count | Should Be 4
        foreach ($f in $index.files) {
            $f.path | Should Match '^mods/[^/\\]+\.jar$'
            $f.hashes.sha1 | Should Match '^[0-9a-f]{40}$'
            $f.hashes.sha512 | Should Match '^[0-9a-f]{128}$'
            @($f.downloads).Count | Should Be 1
            @($f.downloads)[0] | Should Match '^https://cdn\.modrinth\.com/'
            $f.env.client | Should Be 'required'
            $f.env.server | Should Be 'required'
            $f.fileSize | Should BeGreaterThan 0
            (Get-FileSha "$mods\$(Split-Path -Leaf $f.path)" SHA512) | Should Be $f.hashes.sha512
        }
    }

    It 'links CTOV and Lithostitched and never ships them inside' {
        ($index.files | ForEach-Object path) -contains 'mods/ctov-fabric-3.6.3.jar' | Should Be $true
        ($index.files | ForEach-Object path) -contains 'mods/lithostitched-1.8.1-fabric-21.1.jar' | Should Be $true
        @($entries | Where-Object { $_ -match 'ctov|lithostitched' }).Count | Should Be 0
    }

    It 'ships our jars in overrides/mods and nothing else' {
        $entries -contains 'overrides/mods/mobtalk-0.7.0.jar' | Should Be $true
        $entries -contains 'overrides/mods/divineblock-0.1.0.jar' | Should Be $true
        $entries -contains 'overrides/mods/viewerparrots-1.2.1.jar' | Should Be $true
        @($entries | Where-Object { $_ -notmatch '^(modrinth\.index\.json|overrides/mods/[^/]+\.jar)$' }).Count | Should Be 0
        $entries -contains 'overrides/mods/selftest-guard.jar' | Should Be $false
    }

    It 'fails when CTOV is not a known Modrinth file' {
        $partial = New-Known @("$mods\lithostitched-1.8.1-fabric-21.1.jar")
        { & $export -FromPack fake-pack -Instances $inst -Out (Join-Path $TestDrive 'bad.mrpack') -Known $partial *> $null } | Should Throw 'only be linked'
    }
}
