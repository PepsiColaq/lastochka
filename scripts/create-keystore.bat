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
keytool -genkeypair -v -keystore keystore\obhod-release.jks -alias obhod -keyalg RSA -keysize 2048 -validity 10000 -storepass obhod-dev-change-me -keypass obhod-dev-change-me -dname "CN=Obhod, OU=Dev, O=Obhod, L=Moscow, ST=Moscow, C=RU"
echo Created keystore\obhod-release.jks
echo Change passwords via OBHOD_KEYSTORE_PASSWORD / OBHOD_KEY_PASSWORD before public release.
