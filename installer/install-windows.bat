@echo off
rem Mob Talk: installs Prism Launcher and our pack. Double-click this file.
chcp 65001 >nul
if not exist "%~dp0install-windows.ps1" (
  echo Сначала распакуйте архив: правый щелчок по нему, "Извлечь все", затем запустите этот файл из папки.
  pause
  exit /b 1
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0install-windows.ps1" %*
echo.
pause
