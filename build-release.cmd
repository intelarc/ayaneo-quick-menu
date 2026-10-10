@echo off
rem Builds the signed release APK. Signing uses AYANEO RGB Control's keystore (..\AmbientRGB\keystore.properties
rem + keystore\), which is not in any repo. Output: app\build\outputs\apk\release\app-release.apk
if not defined JAVA_HOME set "JAVA_HOME=D:\Claude\toolchain\jdk-21"
if not defined ANDROID_HOME set "ANDROID_HOME=D:\Claude\toolchain\android-sdk"
cd /d "%~dp0"
call "%~dp0gradlew.bat" assembleRelease %*
if errorlevel 1 exit /b 1
echo.
echo APK: %~dp0app\build\outputs\apk\release\app-release.apk
