@echo off
chcp 65001 >nul
cd /d "%~dp0"
set DOSSIER=%USERPROFILE%\Videos\LocalTube
set PORT=8000
python "server\serveur_videos.py" "%DOSSIER%" %PORT%
pause
