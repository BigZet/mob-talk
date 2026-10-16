#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidateRange(1, 65535)][int]$Port = 63919,
    [ValidatePattern('^[A-Za-z0-9._/-]+$')][string]$Model = 'large-v3',
    [ValidateSet('auto', 'cpu', 'cuda')][string]$Device = 'auto',
    [ValidatePattern('^[a-z]{2,3}$')][string]$Language = 'ru',
    [ValidateRange(1, 16)][int]$CpuThreads = 4,
    [ValidateRange(1, 300)][int]$WaitSeconds = 120
)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'This start script requires Windows.' }
$taskRoot = Join-Path $env:LOCALAPPDATA 'MobTalk'
$taskPython = Join-Path $taskRoot 'venv/Scripts/python.exe'
$taskHelper = Join-Path $taskRoot 'whisper_server.py'
$taskModelDir = Join-Path $taskRoot 'models'
$taskUrl = "http://127.0.0.1:$Port"

function Get-WhisperHealth {
    try {
        $taskHealth = Invoke-RestMethod -Uri "$taskUrl/health" -TimeoutSec 2 -NoProxy
        if ($null -ne $taskHealth.ready -and $taskHealth.status -and $taskHealth.model -and $taskHealth.device) {
            return $taskHealth
        }
    } catch { }
    return $null
}

$taskHealth = Get-WhisperHealth
$taskProcess = $null
if (-not $taskHealth) {
    if (-not (Test-Path -LiteralPath $taskPython) -or -not (Test-Path -LiteralPath $taskHelper)) {
        throw 'Run scripts/setup-whisper.ps1 once before starting local Whisper.'
    }
    $taskArguments = @('-u', "`"$taskHelper`"", '--port', "$Port", '--model', $Model,
        '--device', $Device, '--language', $Language, '--model-dir', "`"$taskModelDir`"",
        '--cpu-threads', "$CpuThreads")
    $taskProcess = Start-Process -FilePath $taskPython -ArgumentList $taskArguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $taskRoot 'whisper-out.log') `
        -RedirectStandardError (Join-Path $taskRoot 'whisper-error.log')
}

$taskDeadline = [DateTime]::UtcNow.AddSeconds($WaitSeconds)
do {
    if ($taskHealth) {
        if ($taskHealth.model -ne $Model) { throw "A Whisper helper for a different model is already using port $Port." }
        if ($taskHealth.ready) {
            Write-Host "Whisper ready: $taskUrl (model=$($taskHealth.model), device=$($taskHealth.device))"
            return
        }
        if ($taskHealth.status -eq 'error') { throw 'Whisper model could not load. Run scripts/setup-whisper.ps1.' }
    }
    if ($taskProcess -and $taskProcess.HasExited) { throw "Whisper helper exited with code $($taskProcess.ExitCode). Run scripts/setup-whisper.ps1." }
    Start-Sleep -Milliseconds 250
    $taskHealth = Get-WhisperHealth
} while ([DateTime]::UtcNow -lt $taskDeadline)
throw "Whisper is still loading at $taskUrl. The first model download may take longer; rerun start-whisper.ps1 to check the same helper."
