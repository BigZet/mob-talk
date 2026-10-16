param([switch]$Tests)
$ErrorActionPreference = 'Stop'
Push-Location -LiteralPath (Split-Path -Parent $PSScriptRoot)
try {
    $mobTalkTasks = if ($Tests) { @('test', 'assemble', '--console=plain') } else { @('assemble', '--console=plain') }
    & .\gradlew.bat @mobTalkTasks
    if ($LASTEXITCODE -ne 0) { throw 'Mob Talk verification failed.' }
    Write-Host 'Mob Talk JAR assembled. Check stick selection and V in a single-player world.'
} finally { Pop-Location }
