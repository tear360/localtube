@echo off
chcp 65001 >nul
powershell -NoProfile -Command "Get-CimInstance Win32_Process | Where-Object { ($_.Name -eq 'pythonw.exe' -or $_.Name -eq 'python.exe') -and $_.CommandLine -match 'serveur_videos.py' } | ForEach-Object { Stop-Process -Id $_.ProcessId -Force; Write-Output ('Arrete : PID ' + $_.ProcessId + ' (' + $_.Name + ')') }"
echo Termine.
pause
