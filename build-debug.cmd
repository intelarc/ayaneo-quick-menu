@echo off
rem Builds app\build\outputs\apk\debug\app-debug.apk with the local toolchain in D:\Claude\toolchain
rem (Temurin JDK 21 + Android SDK). Same as "./gradlew assembleDebug" with JAVA_HOME set.
if not defined JAVA_HOME set "JAVA_HOME=D:\Claude\toolchain\jdk-21"
if not defined ANDROID_HOME set "ANDROID_HOME=D:\Claude\toolchain\android-sdk"
cd /d "%~dp0"
call "%~dp0gradlew.bat" assembleDebug %*
if errorlevel 1 exit /b 1
echo.
echo APK: %~dp0app\build\outputs\apk\debug\app-debug.apk
