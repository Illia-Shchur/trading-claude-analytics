@echo off
setlocal
set "REPO_ROOT=%~dp0.."
set "JAR=%REPO_ROOT%\analytics-cli\target\analytics-cli-1.0.0-SNAPSHOT-exec.jar"
if not exist "%JAR%" call "%REPO_ROOT%\mvnw.cmd" -q -pl analytics-cli -am package -DskipTests
java -jar "%JAR%" %*
exit /b %ERRORLEVEL%
