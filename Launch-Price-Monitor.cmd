@echo off
setlocal
powershell.exe -NoProfile -File "%~dp0Start-PriceMonitor.ps1"
if errorlevel 1 pause
endlocal
