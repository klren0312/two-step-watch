@echo off
setlocal
set "ADB=adb"
where adb >nul 2>nul || set "ADB=D:\Apps\androidsdk\platform-tools\adb.exe"
if "%~1"=="" (
    echo Drag a .gpx file onto this script, or pass its path as the first argument.
    pause
    exit /b 1
)
"%ADB%" devices | findstr /r /c:"device$" >nul
if errorlevel 1 (
    echo No watch connected. Enable wireless debugging on the watch and run: adb connect WATCH_IP:PORT
    pause
    exit /b 1
)
"%ADB%" shell mkdir -p /sdcard/Android/data/com.klkl.tracknav/files/import
"%ADB%" push "%~1" /sdcard/Android/data/com.klkl.tracknav/files/import/
if errorlevel 1 (
    echo Push failed.
) else (
    echo Done. Open the 轨迹导航 app on the watch and tap 导入新轨迹.
)
pause
