@echo off
setlocal
if "%~1"=="" (
  echo Usage: run-example.cmd ^<Forge-1.20.1-MDK-directory^>
  exit /b 2
)
java -jar "%~dp0VoxPilot.jar" run --project "%~1" --scenario "%~dp0examples\walk-third-person.json"
pause
