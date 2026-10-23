@echo off
setlocal
set "ADB=adb"
where adb >nul 2>nul || set "ADB=D:\Apps\androidsdk\platform-tools\adb.exe"
"%ADB%" devices | findstr /r /c:"device$" >nul
if errorlevel 1 (
    echo No watch connected. Enable wireless debugging on the watch and run: adb connect WATCH_IP:PORT
    pause
    exit /b 1
)
if not exist "%USERPROFILE%\Desktop\tracknav-exports" mkdir "%USERPROFILE%\Desktop\tracknav-exports"
"%ADB%" pull /sdcard/Android/data/com.klkl.tracknav/files/exports "%USERPROFILE%\Desktop\tracknav-exports"
echo Exported recordings are in %USERPROFILE%\Desktop\tracknav-exports
pause
