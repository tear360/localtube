Option Explicit
Dim sh, fso, base, python, dossier
Set sh = CreateObject("WScript.Shell")
Set fso = CreateObject("Scripting.FileSystemObject")
base = fso.GetParentFolderName(WScript.ScriptFullName)
python = sh.ExpandEnvironmentStrings("%LOCALAPPDATA%") & "\Python\pythoncore-3.14-64\pythonw.exe"
If Not fso.FileExists(python) Then python = "pythonw.exe"
dossier = sh.ExpandEnvironmentStrings("%USERPROFILE%") & "\Videos\LocalTube"
sh.CurrentDirectory = base
sh.Run """" & python & """ """ & base & "\server\serveur_videos.py"" """ & dossier & """ 8000", 0, False
