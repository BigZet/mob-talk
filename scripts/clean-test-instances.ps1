# Deletes throwaway self-test copies (Prism instances named t-*) whose game is not running.
# Default: this repo's copies (t-db-*). -Name deletes one copy; -All deletes every t-* copy of any repo. -WhatIf lists only.
# Usage: powershell -ExecutionPolicy Bypass -File scripts\clean-test-instances.ps1 [-Name t-db-...] [-All] [-WhatIf]
param([string]$Name = '', [switch]$All, [switch]$WhatIf)
$ErrorActionPreference = 'Stop'
Import-Module (Join-Path $PSScriptRoot 'ParallelPrism.psm1') -Force
$pattern = if ($Name) { $Name } elseif ($All) { 't-*' } else { 't-db-*' }
foreach ($dir in @(Get-ChildItem "$env:APPDATA\PrismLauncher\instances" -Directory -Filter $pattern)) {
    if (@(Get-InstanceGame $dir.Name).Count) { Write-Host "running, kept: $($dir.Name)"; continue }
    if ($WhatIf) { Write-Host "would delete: $($dir.Name)"; continue }
    Remove-TestInstance $dir.Name
    Write-Host "deleted: $($dir.Name)"
}
