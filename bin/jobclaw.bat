@echo off
setlocal
set "APP_HOME=%~dp0.."
set "JAR=%APP_HOME%\target\jobclaw-cli.jar"
java --sun-misc-unsafe-memory-access=allow --enable-native-access=ALL-UNNAMED -jar "%JAR%" %*
