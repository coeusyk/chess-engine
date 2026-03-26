@echo off
REM Usage: tools\match.bat <engine1.jar> <engine2.jar> [games] [tc]
REM Example: tools\match.bat engine-v1.jar engine-v2.jar 100 "tc=10+0.1"

setlocal enabledelayedexpansion

if "%~1"=="" (
    echo Usage: tools\match.bat ^<engine1.jar^> ^<engine2.jar^> [games] [tc]
    exit /b 1
)
if "%~2"=="" (
    echo Usage: tools\match.bat ^<engine1.jar^> ^<engine2.jar^> [games] [tc]
    exit /b 1
)

set ENGINE1=%~1
set ENGINE2=%~2
set GAMES=%~3
if "%GAMES%"=="" set GAMES=100
set TC=%~4
if "%TC%"=="" set TC=tc=10+0.1

set RESULTS_DIR=%~dp0results
if not exist "%RESULTS_DIR%" mkdir "%RESULTS_DIR%"

for /f "tokens=2 delims==" %%I in ('wmic os get localdatetime /value ^| find "="') do set DATETIME=%%I
set TIMESTAMP=%DATETIME:~0,8%_%DATETIME:~8,6%
set PGN_OUT=%RESULTS_DIR%\match_%TIMESTAMP%.pgn

cutechess-cli ^
  -engine name=Vex-new cmd="java -jar %ENGINE1%" proto=uci ^
  -engine name=Vex-old cmd="java -jar %ENGINE2%" proto=uci ^
  -each "%TC%" ^
  -games %GAMES% ^
  -repeat ^
  -recover ^
  -resign movecount=5 score=600 ^
  -draw movenumber=40 movecount=8 score=10 ^
  -pgnout "%PGN_OUT%" ^
  -concurrency 2

echo PGN saved to: %PGN_OUT%
endlocal
