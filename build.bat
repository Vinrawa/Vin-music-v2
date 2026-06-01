@echo off
setlocal enabledelayedexpansion

echo Stopping Gradle daemons...
call gradlew --stop

echo Waiting for daemon shutdown...
timeout /t 5 /nobreak

echo Building APK...
set GRADLE_OPTS=-Xmx2048m -XX:MaxMetaspaceSize=768m
call gradlew.bat clean assembleDebug --no-daemon --max-workers=2

if %ERRORLEVEL% EQU 0 (
    echo.
    echo ========================================
    echo BUILD SUCCESSFUL!
    echo APK location: app\build\outputs\apk\debug\app-debug.apk
    echo ========================================
    pause
) else (
    echo.
    echo Build failed with error code %ERRORLEVEL%
    pause
)
