#requires -Version 7.0
[CmdletBinding()]
param(
    [ValidatePattern('^[A-Za-z0-9._/-]+$')][string]$Model = 'small',
    [ValidateSet('auto', 'cpu', 'cuda')][string]$Device = 'auto',
    [ValidatePattern('^[a-z]{2,3}$')][string]$Language = 'ru',
    [ValidateRange(1, 16)][int]$CpuThreads = 4,
    [switch]$SkipGpu,
    [switch]$NoStart
)
$ErrorActionPreference = 'Stop'
if (-not $IsWindows) { throw 'This setup script requires Windows.' }

$taskRoot = Join-Path $env:LOCALAPPDATA 'MobTalk'
$taskVenv = Join-Path $taskRoot 'venv'
$taskPython = Join-Path $taskVenv 'Scripts/python.exe'
$taskModelDir = Join-Path $taskRoot 'models'
$taskHelper = Join-Path $taskRoot 'whisper_server.py'
$taskSource = Join-Path $PSScriptRoot '../tools/whisper_server.py'
if (-not (Test-Path -LiteralPath $taskSource)) { throw 'Missing tools/whisper_server.py beside this project.' }
New-Item -ItemType Directory -Path $taskRoot, $taskModelDir -Force | Out-Null

if (-not (Test-Path -LiteralPath $taskPython)) {
    $taskLauncher = Get-Command py -ErrorAction SilentlyContinue
    if ($taskLauncher) {
        $taskInstalledPython = (& $taskLauncher.Source -3.12 -c 'import sys; print(sys.executable)' | Select-Object -Last 1)
        if ($LASTEXITCODE -ne 0) { throw 'Install Python 3.12 for this user, then rerun setup-whisper.ps1.' }
    } else {
        $taskPythonCommand = Get-Command python -ErrorAction SilentlyContinue
        if (-not $taskPythonCommand) { throw 'Install Python 3.12 for this user, then rerun setup-whisper.ps1.' }
        $taskInstalledPython = $taskPythonCommand.Source
        & $taskInstalledPython -c 'import sys; raise SystemExit(0 if sys.version_info[:2] == (3, 12) else 1)'
        if ($LASTEXITCODE -ne 0) { throw 'Python 3.12 is required for the isolated Whisper environment.' }
    }
    Write-Host 'Creating isolated Python environment in %LOCALAPPDATA%/MobTalk/venv...'
    & $taskInstalledPython -m venv $taskVenv
    if ($LASTEXITCODE -ne 0) { throw 'Could not create the isolated Whisper environment.' }
}

# --isolated ignores global pip config/environment; installation stays in this
# venv and uses published wheels from PyPI, never a global Python interpreter.
# faster-whisper 1.2.1 calls av.open(metadata_errors=...), removed by PyAV 19.
# Use the published compatible Windows wheel rather than patching the decoder.
Write-Host 'Installing faster-whisper 1.2.1 and compatible PyAV 16.1.0 in the isolated environment...'
& $taskPython -m pip --isolated install --index-url 'https://pypi.org/simple' --disable-pip-version-check --only-binary=:all: 'faster-whisper==1.2.1' 'av==16.1.0'
if ($LASTEXITCODE -ne 0) { throw 'Whisper dependency installation failed. Rerun this script when PyPI is reachable.' }

if (-not $SkipGpu -and $Device -ne 'cpu') {
    $taskNvidia = Get-Command nvidia-smi -ErrorAction SilentlyContinue
    $taskHasNvidia = $false
    if ($taskNvidia) {
        $taskGpuNames = & $taskNvidia.Source --query-gpu=name --format=csv,noheader 2>$null
        $taskHasNvidia = $LASTEXITCODE -eq 0 -and [bool]$taskGpuNames
    }
    if ($taskHasNvidia) {
        Write-Host 'Installing official NVIDIA CUDA 12/cuDNN 9 Windows runtime wheels...'
        & $taskPython -m pip --isolated install --index-url 'https://pypi.org/simple' --disable-pip-version-check --only-binary=:all: 'nvidia-cublas-cu12==12.9.2.10' 'nvidia-cudnn-cu12==9.27.0.42'
        if ($LASTEXITCODE -ne 0) {
            if ($Device -eq 'cuda') { throw 'CUDA runtime installation failed; use -Device auto or cpu for CPU fallback.' }
            Write-Host 'CUDA runtime installation was unavailable; the helper will try CPU int8.'
        }
    } else {
        Write-Host 'No usable NVIDIA driver detected; auto uses CPU int8.'
    }
}

Copy-Item -LiteralPath $taskSource -Destination $taskHelper -Force
Write-Host "Loading the multilingual '$Model' model into %LOCALAPPDATA%/MobTalk/models..."
& $taskPython -u $taskHelper --preload --model $Model --device $Device --language $Language --model-dir $taskModelDir --cpu-threads $CpuThreads
if ($LASTEXITCODE -ne 0) { throw 'Model setup failed. Check internet/model availability and rerun setup-whisper.ps1.' }
Write-Host 'Whisper model is cached. The ready message above shows the device actually used.'
if (-not $NoStart) {
    & (Join-Path $PSScriptRoot 'start-whisper.ps1') -Model $Model -Device $Device -Language $Language -CpuThreads $CpuThreads
}
