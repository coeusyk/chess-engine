@echo off
REM Usage: tools\sprt.bat <new-engine.jar> <old-engine.jar>
REM Runs SPRT(ELO0, ELO1, ALPHA, BETA) to validate whether the new engine
REM is stronger than the old engine.
REM
REM SPRT Parameters (edit these constants as needed):
REM   ELO0  - null hypothesis Elo bound (default 0: no improvement)
REM   ELO1  - alternative hypothesis Elo bound (default 50: meaningful gain)
REM   ALPHA - false positive rate (default 0.05 = 5%)
REM   BETA  - false negative rate (default 0.05 = 5%)
REM
REM Reading Results (from cutechess-cli output):
REM   "H1 accepted" (LLR >= upper bound) - patch improves strength, merge
REM   "H0 accepted" (LLR <= lower bound) - no improvement detected, investigate or discard
REM   If the game limit is reached without a decision, result is inconclusive.

setlocal enabledelayedexpansion

REM --- SPRT parameters (edit here) ---
set ELO0=0
set ELO1=50
set ALPHA=0.05
set BETA=0.05
set MAX_GAMES=20000
set TC=tc=10+0.1

if "%~1"=="" (
    echo Usage: tools\sprt.bat ^<new-engine.jar^> ^<old-engine.jar^>
    exit /b 1
)
if "%~2"=="" (
    echo Usage: tools\sprt.bat ^<new-engine.jar^> ^<old-engine.jar^>
    exit /b 1
)

set NEW=%~1
set OLD=%~2

set RESULTS_DIR=%~dp0results
if not exist "%RESULTS_DIR%" mkdir "%RESULTS_DIR%"

for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TIMESTAMP=%%I"
set PGN_OUT=%RESULTS_DIR%\sprt_%TIMESTAMP%.pgn

cutechess-cli ^
  -engine name=Vex-new cmd="java -jar ^"%NEW%^"" proto=uci ^
  -engine name=Vex-old cmd="java -jar ^"%OLD%^"" proto=uci ^
  -each "%TC%" ^
  -games %MAX_GAMES% ^
  -repeat ^
  -recover ^
  -resign movecount=5 score=600 ^
  -draw movenumber=40 movecount=8 score=10 ^
  -sprt elo0=%ELO0% elo1=%ELO1% alpha=%ALPHA% beta=%BETA% ^
  -concurrency 2 ^
  -pgnout "%PGN_OUT%"

echo PGN saved to: %PGN_OUT%
endlocal
