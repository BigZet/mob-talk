# Установщик Mob Talk для игрока (Windows 10/11): ставит Prism Launcher, если его нет, и импортирует нашу сборку
# (файл .mrpack рядом со скриптом, иначе последний из релизов github.com/BigZet/mob-talk). Сервер уже в списке
# «Сетевая игра». Права администратора не нужны: Prism ставится портативно в %LOCALAPPDATA%\MobTalk\PrismLauncher
# с ярлыками на рабочем столе и в «Пуске». Уже установленный Prism используется как есть.
# Запуск: двойной щелчок по install-windows.bat (или powershell -ExecutionPolicy Bypass -File install-windows.ps1)
#   [-Pack <файл.mrpack>] [-PrismDir <папка>] [-NoShortcuts] [-NoImport]
#   -PrismDir: поставить портативный Prism в эту папку, даже если другой Prism уже есть (для проверки в песочнице).
param([string]$Pack = '', [string]$PrismDir = '', [switch]$NoShortcuts, [switch]$NoImport)
$ErrorActionPreference = 'Stop'
$ProgressPreference = 'SilentlyContinue'   # Invoke-WebRequest без полосы прогресса качает в разы быстрее
[Console]::OutputEncoding = [Text.Encoding]::UTF8
[Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
Add-Type -AssemblyName System.IO.Compression.FileSystem
function Say($t) { Write-Host $t }

# Prism Launcher 11.1.1, официальные сборки с github.com/PrismLauncher/PrismLauncher (SHA-256 из их релиза).
$prismVersion = '11.1.1'
$builds = @{
    'AMD64' = @{ File = "PrismLauncher-Windows-MSVC-Portable-$prismVersion.zip"; Sha256 = 'ab35a770fb06d89d2ccc098079db5db329fb4e68f42b72babd8b095efde3d2d7' }
    'ARM64' = @{ File = "PrismLauncher-Windows-MSVC-arm64-Portable-$prismVersion.zip"; Sha256 = 'f33b34a6f313fc46c1d5912256942d50216814577ad2ff9dfc28102de7208e53' }
}
$here = Split-Path -Parent $MyInvocation.MyCommand.Path
$work = Join-Path $env:TEMP 'MobTalkInstall'
New-Item -ItemType Directory -Force $work | Out-Null

function Get-Checked([string]$Url, [string]$Target, [string]$Sha256) {
    Say "Скачиваю $Url"
    Invoke-WebRequest -Uri $Url -OutFile $Target -UseBasicParsing -UserAgent 'BigZet/mob-talk installer'
    if ($Sha256 -and (Get-FileHash -LiteralPath $Target -Algorithm SHA256).Hash.ToLowerInvariant() -ne $Sha256) {
        Remove-Item -LiteralPath $Target -Force
        throw "Файл $Url пришёл повреждённым (не та контрольная сумма). Попробуйте ещё раз."
    }
}

# 1. Prism: уже установленный (обычный установщик кладёт его сюда) или портативный наш.
$exe = $null
$installed = @("$env:LOCALAPPDATA\Programs\PrismLauncher\prismlauncher.exe", "$env:ProgramFiles\PrismLauncher\prismlauncher.exe")
if (-not $PrismDir) {
    $exe = $installed | Where-Object { Test-Path -LiteralPath $_ } | Select-Object -First 1
    $PrismDir = Join-Path $env:LOCALAPPDATA 'MobTalk\PrismLauncher'
}
if (-not $exe -and (Test-Path -LiteralPath "$PrismDir\prismlauncher.exe")) { $exe = "$PrismDir\prismlauncher.exe" }
if ($exe) {
    Say "Prism Launcher уже есть: $exe"
} else {
    $arch = if ($env:PROCESSOR_ARCHITEW6432) { $env:PROCESSOR_ARCHITEW6432 } else { $env:PROCESSOR_ARCHITECTURE }
    $b = if ($builds.ContainsKey($arch)) { $builds[$arch] } else { $builds['AMD64'] }
    $zip = Join-Path $work $b.File
    Get-Checked "https://github.com/PrismLauncher/PrismLauncher/releases/download/$prismVersion/$($b.File)" $zip $b.Sha256
    New-Item -ItemType Directory -Force $PrismDir | Out-Null
    [IO.Compression.ZipFile]::ExtractToDirectory($zip, $PrismDir)
    # Архив может лежать с папкой внутри: ищем prismlauncher.exe.
    $found = Get-ChildItem -LiteralPath $PrismDir -Recurse -Filter 'prismlauncher.exe' | Select-Object -First 1
    if (-not $found) { throw "В архиве Prism нет prismlauncher.exe" }
    $exe = $found.FullName
    # Портативный режим: настройки, аккаунты и сборки лежат рядом с программой, а не в %APPDATA%.
    $portable = Join-Path (Split-Path -Parent $exe) 'portable.txt'
    if (-not (Test-Path -LiteralPath $portable)) { [IO.File]::WriteAllText($portable, '') }
    Remove-Item -LiteralPath $zip -Force
    Say "Prism Launcher $prismVersion установлен: $exe"
    if (-not $NoShortcuts) {
        $shell = New-Object -ComObject WScript.Shell
        foreach ($dir in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
            $lnk = $shell.CreateShortcut((Join-Path $dir 'Prism Launcher (Mob Talk).lnk'))
            $lnk.TargetPath = $exe; $lnk.WorkingDirectory = Split-Path -Parent $exe; $lnk.Save()
        }
        Say 'Ярлыки «Prism Launcher (Mob Talk)» — на рабочем столе и в «Пуске».'
    }
}

# 2. Сборка: .mrpack рядом со скриптом, иначе из последнего релиза на GitHub.
if (-not $Pack) {
    $Pack = Get-ChildItem -LiteralPath $here -Filter '*.mrpack' | Sort-Object LastWriteTime -Descending | Select-Object -First 1 -ExpandProperty FullName
}
if (-not $Pack) {
    $release = Invoke-RestMethod -Uri 'https://api.github.com/repos/BigZet/mob-talk/releases/latest' -UserAgent 'BigZet/mob-talk installer'
    $asset = @($release.assets | Where-Object { $_.name -like '*.mrpack' }) | Select-Object -First 1
    if (-not $asset) { throw "В последнем релизе Mob Talk нет файла .mrpack: попросите его у Дмитрия и положите рядом с установщиком." }
    $Pack = Join-Path $work $asset.name
    $sha = if ($asset.digest -like 'sha256:*') { $asset.digest.Substring(7) } else { '' }
    Get-Checked $asset.browser_download_url $Pack $sha
}
$Pack = (Resolve-Path -LiteralPath $Pack).Path
Say "Сборка: $Pack"

# 3. Импорт: Prism откроет окно добавления сборки с этим файлом, остаётся нажать OK.
if ($NoImport) { Say "Импорт пропущен (-NoImport). Prism: $exe"; return }
Start-Process -FilePath $exe -ArgumentList @('--import', "`"$Pack`"")
Say ''
Say 'Prism Launcher открывается. Дальше:'
Say '  1. В окне импорта нажмите OK — Prism сам скачает Minecraft, Fabric и моды.'
Say '  2. Справа вверху: Аккаунты -> Управление аккаунтами -> Добавить Microsoft, войдите своим аккаунтом Minecraft.'
Say '  3. Выберите сборку Mob Talk -> Запустить. Если Prism спросит про Java, согласитесь скачать (Java 21).'
Say '  4. Сетевая игра -> сервер Mob Talk уже в списке. Ваш ник должен быть в списке сервера: напишите его Дмитрию.'
