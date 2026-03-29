@echo off
REM Usage: tools\sprt_smp.bat <engine.jar> [threads]
REM Runs SPRT(0, 50, 0.05, 0.05): Vex-NThread vs Vex-1Thread
REM Tests whether Lazy SMP with N threads is genuinely stronger than single-threaded.

setlocal enabledelayedexpansion

if "%~1"=="" (
    echo Usage: tools\sprt_smp.bat ^<engine.jar^> [threads]
    exit /b 1
)

set JAR=%~1
set THREADS=%~2
if "%THREADS%"=="" set THREADS=2

set ELO0=0
set ELO1=50
set ALPHA=0.05
set BETA=0.05
set MAX_GAMES=20000
set TC=tc=5+0.05

set CUTECHESS=C:\Users\yashk\Downloads\cutechess-1.4.0-win64\cutechess-1.4.0-win64\cutechess-cli.exe

set RESULTS_DIR=%~dp0results
if not exist "%RESULTS_DIR%" mkdir "%RESULTS_DIR%"

for /f "usebackq delims=" %%I in (`powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"`) do set "TIMESTAMP=%%I"
set PGN_OUT=%RESULTS_DIR%\sprt_smp_%THREADS%T_%TIMESTAMP%.pgn

echo Running SPRT: Vex-%THREADS%T vs Vex-1T
echo Engine JAR : %JAR%
echo Time control: %TC%
echo PGN output : %PGN_OUT%
echo.

"%CUTECHESS%" ^
  -engine name=Vex-%THREADS%T cmd=java arg=-jar arg="%JAR%" proto=uci option.Threads=%THREADS% ^
  -engine name=Vex-1T cmd=java arg=-jar arg="%JAR%" proto=uci option.Threads=1 ^
  -each %TC% ^
  -games %MAX_GAMES% ^
  -repeat ^
  -recover ^
  -resign movecount=5 score=600 ^
  -draw movenumber=40 movecount=8 score=10 ^
  -sprt elo0=%ELO0% elo1=%ELO1% alpha=%ALPHA% beta=%BETA% ^
  -concurrency 1 ^
  -pgnout "%PGN_OUT%"

echo.
echo PGN saved to: %PGN_OUT%
endlocal
