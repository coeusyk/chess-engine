@echo off
setlocal

:: SPRT: d2c9a5b (proportional hanging penalty + precomputed table) vs Vex-0.4.9
:: H0=0, H1=5, alpha=0.05, beta=0.05, TC=10+0.1

if "%CUTECHESS%"=="" for /f "delims=" %%C in ('where cutechess-cli 2^>nul') do set "CUTECHESS=%%C"
if "%CUTECHESS%"=="" set "CUTECHESS=C:\Users\yashk\Downloads\cutechess\cutechess-1.4.0-win64\cutechess-cli.exe"
if not exist "%CUTECHESS%" (
    echo ERROR: cutechess-cli not found at %CUTECHESS%
    exit /b 1
)

if "%JAVA%"=="" set "JAVA=java"

set "NEW=%~dp0..\engine-uci\target\new-d2c9a5b.jar"
set "OLD=%~dp0engine-uci-0.4.9.jar"

if not exist "%NEW%" (
    echo ERROR: %NEW% not found. Run: mvnw -pl engine-uci -am package -DskipTests
    exit /b 1
)
if not exist "%OLD%" (
    echo ERROR: %OLD% not found.
    exit /b 1
)

for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TS=%%I"
set "PGN=%~dp0results\sprt_d2c9a5b_%TS%.pgn"

echo SPRT: d2c9a5b vs 0.4.9  H0=0 H1=5 alpha=0.05 beta=0.05  TC=10+0.1
echo NEW: %NEW%
echo OLD: %OLD%
echo PGN: %PGN%
echo.

"%CUTECHESS%" ^
  -engine name=Vex-new cmd="%JAVA%" arg=-jar arg="%NEW%" proto=uci ^
  -engine name=Vex-old cmd="%JAVA%" arg=-jar arg="%OLD%" proto=uci ^
  -each tc=10+0.1 ^
  -games 20000 ^
  -repeat ^
  -recover ^
  -resign movecount=5 score=600 ^
  -draw movenumber=40 movecount=8 score=10 ^
  -sprt elo0=0 elo1=5 alpha=0.05 beta=0.05 ^
  -concurrency 2 ^
  -ratinginterval 10 ^
  -pgnout "%PGN%"

echo.
echo PGN saved to: %PGN%
