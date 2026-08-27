@echo off
setlocal

set "MAVEN_VERSION=3.9.16"
set "MAVEN_HOME=%USERPROFILE%\.m2\wrapper\dists\cyfuture-maven-%MAVEN_VERSION%\apache-maven-%MAVEN_VERSION%"
set "MAVEN_CMD=%MAVEN_HOME%\bin\mvn.cmd"

if not exist "%MAVEN_CMD%" (
  powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0.mvn\wrapper\maven-wrapper.ps1"
  if errorlevel 1 exit /b 1
)

call "%MAVEN_CMD%" %*
exit /b %ERRORLEVEL%
