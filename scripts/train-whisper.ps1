#requires -Version 7.0
<#
.SYNOPSIS
Fine-tunes Whisper on the player's own stream recordings and installs it for Mob Talk.

.DESCRIPTION
Steps: extract -> segment -> label -> speaker -> dataset -> train -> convert -> evaluate.
Audio, datasets and checkpoints stay in -WorkDir (default %LOCALAPPDATA%\MobTalk\train),
never in git. Uses its own venv (%LOCALAPPDATA%\MobTalk\venv-train); the mod's Whisper venv
and running server are not touched. Run with the game closed or with enough free VRAM.

.EXAMPLE
pwsh scripts/train-whisper.ps1
pwsh scripts/train-whisper.ps1 -From train -Epochs 2
#>
[CmdletBinding()]
param(
    [string]$Videos = (Join-Path $HOME 'Videos'),
    [string]$WorkDir = (Join-Path $env:LOCALAPPDATA 'MobTalk/train'),
    [ValidateSet('setup', 'extract', 'segment', 'label', 'speaker', 'dataset', 'train', 'convert', 'evaluate')]
    [string]$From = 'setup',
    [ValidateSet('setup', 'extract', 'segment', 'label', 'speaker', 'dataset', 'train', 'convert', 'evaluate')]
    [string]$To = 'evaluate',
    [string]$Base = 'openai/whisper-small',
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')][string]$Name = 'mobtalk-small-ru',
    [ValidateRange(1, 10)][int]$Epochs = 4,
    [ValidateRange(1, 32)][int]$Batch = 8,
    [ValidateRange(1, 64)][int]$Accumulate = 2,
    [double]$LearningRate = 3e-4,
    [ValidateSet('cuda', 'cpu')][string]$LabelDevice = 'cuda',
    [switch]$NoInstall
)
$ErrorActionPreference = 'Stop'
$steps = 'setup', 'extract', 'segment', 'label', 'speaker', 'dataset', 'train', 'convert', 'evaluate'
$first = $steps.IndexOf($From); $last = $steps.IndexOf($To)
$venv = Join-Path $env:LOCALAPPDATA 'MobTalk/venv-train'
$python = Join-Path $venv 'Scripts/python.exe'
$tools = Join-Path $PSScriptRoot '../tools/whisper-train'
$env:MOBTALK_TRAIN_DIR = $WorkDir
$env:PYTHONUTF8 = '1'
$env:HF_HUB_DISABLE_SYMLINKS_WARNING = '1'

function Invoke-Step([string]$Step, [string[]]$Arguments) {
    if ($steps.IndexOf($Step) -lt $first -or $steps.IndexOf($Step) -gt $last) { return }
    Write-Host "== $Step"
    & $python -u (Join-Path $tools $Arguments[0]) @($Arguments | Select-Object -Skip 1)
    if ($LASTEXITCODE -ne 0) { throw "Step '$Step' failed." }
}

if ($first -le 0) {
    if (-not (Test-Path -LiteralPath $python)) {
        & py -3.12 -m venv $venv
        if ($LASTEXITCODE -ne 0) { throw 'Python 3.12 is required (py -3.12).' }
    }
    & $python -m pip install --disable-pip-version-check -q torch torchaudio --index-url 'https://download.pytorch.org/whl/cu126'
    & $python -m pip install --disable-pip-version-check -q -r (Join-Path $tools 'requirements.txt')
    if ($LASTEXITCODE -ne 0) { throw 'Training dependencies failed to install.' }
}
Invoke-Step 'extract' @('extract.py', '--videos', $Videos)
Invoke-Step 'segment' @('segment.py')
Invoke-Step 'label' @('label.py', '--model', 'large-v3', '--device', $LabelDevice)
Invoke-Step 'speaker' @('speaker.py')
Invoke-Step 'dataset' @('build_dataset.py')
Invoke-Step 'train' @('train.py', '--base', $Base, '--name', $Name, '--epochs', "$Epochs", '--batch', "$Batch",
    '--accumulate', "$Accumulate", '--lr', $LearningRate.ToString([cultureinfo]::InvariantCulture))
$convert = @('convert.py', '--run', $Name, '--base', $Base, '--name', $Name)
if (-not $NoInstall) { $convert += '--install' }
Invoke-Step 'convert' $convert
Invoke-Step 'evaluate' @('evaluate.py', 'large-v3', 'small', (Join-Path $WorkDir "ct2/$Name"))
Write-Host "Done. Set ""whisperModel"": ""$Name"" in config/mobtalk.json; see docs/whisper-finetune.md."
