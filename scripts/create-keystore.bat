@echo off
setlocal
cd /d "%~dp0\.."
if not exist keystore mkdir keystore
where keytool >nul 2>&1
if errorlevel 1 (
  echo keytool not found. Install JDK 17 and add to PATH.
  exit /b 1
)
if exist keystore\obhod-release.jks (
  echo keystore already exists: keystore\obhod-release.jks
  exit /b 0
)

if "%OBHOD_KEYSTORE_PASSWORD%"=="" (
  echo Set OBHOD_KEYSTORE_PASSWORD and OBHOD_KEY_PASSWORD, then re-run.
  echo Example:
  echo   set OBHOD_KEYSTORE_PASSWORD=your-secret
  echo   set OBHOD_KEY_PASSWORD=your-secret
  exit /b 1
)
if "%OBHOD_KEY_PASSWORD%"=="" (
  echo Set OBHOD_KEY_PASSWORD, then re-run.
  exit /b 1
)
if "%OBHOD_KEY_ALIAS%"=="" set OBHOD_KEY_ALIAS=obhod

keytool -genkeypair -v -keystore keystore\obhod-release.jks -alias "%OBHOD_KEY_ALIAS%" -keyalg RSA -keysize 2048 -validity 10000 -storepass "%OBHOD_KEYSTORE_PASSWORD%" -keypass "%OBHOD_KEY_PASSWORD%" -dname "CN=Obhod, OU=Dev, O=Obhod, L=Local, ST=Local, C=XX"
echo Created keystore\obhod-release.jks
echo Keep the keystore and passwords private. Never commit keystore\*.jks.
