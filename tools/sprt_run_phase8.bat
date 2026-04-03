@echo off
setlocal

:: Resolve cutechess-cli from CUTECHESS env var or PATH
if "%CUTECHESS%"=="" for /f "delims=" %%C in ('where cutechess-cli 2^>nul') do set "CUTECHESS=%%C"
if "%CUTECHESS%"=="" (
    echo ERROR: cutechess-cli not found. Set CUTECHESS env var or add cutechess-cli.exe to PATH.
    exit /b 1
)

:: Java: rely on PATH (or JAVA env var override)
if "%JAVA%"=="" set "JAVA=java"

:: Resolve engine jars relative to this script; find latest non-original engine-uci jar
if "%NEW%"=="" for /f "delims=" %%F in ('dir /b /od "%~dp0..\engine-uci\target\engine-uci-*.jar" 2^>nul ^| findstr /v "^original-"') do set "NEW=%~dp0..\engine-uci\target\%%F"
if "%NEW%"=="" (
    echo ERROR: No engine-uci jar found. Run: mvnw -pl engine-uci -am package -DskipTests
    exit /b 1
)

if "%OLD%"=="" set "OLD=%~dp0pre-tuning-0.4.8.jar"

for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TS=%%I"
set PGN=%~dp0results\sprt_phase8_%TS%.pgn

"%CUTECHESS%" ^
  -engine name=Vex-new cmd="%JAVA%" arg=-jar arg="%NEW%" proto=uci ^
  -engine name=Vex-old cmd="%JAVA%" arg=-jar arg="%OLD%" proto=uci ^
  -each tc=10+0.1 ^
  -games 20000 ^
  -repeat ^
  -recover ^
  -resign movecount=5 score=600 ^
  -draw movenumber=40 movecount=8 score=10 ^
  -sprt elo0=0 elo1=50 alpha=0.05 beta=0.05 ^
  -concurrency 2 ^
  -pgnout "%PGN%"

echo.
echo PGN saved to: %PGN%
endlocal
