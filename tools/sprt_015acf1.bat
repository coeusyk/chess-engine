@echo off
setlocal

set "CUTECHESS=C:\Users\yashk\Downloads\cutechess\cutechess-1.4.0-win64\cutechess-cli.exe"
set "JAVA=java"
set "NEW=C:\Users\yashk\WorkDir\Projects\ChessEngine\chess-engine\engine-uci\target\engine-uci-0.4.10-SNAPSHOT-shaded.jar"
set "OLD=C:\Users\yashk\WorkDir\Projects\ChessEngine\chess-engine\tools\engine-uci-0.4.9.jar"

for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TS=%%I"
set "PGN=%~dp0results\sprt_015acf1_%TS%.pgn"

echo SPRT: 0.4.10-SNAPSHOT (015acf1) vs 0.4.9
echo New: %NEW%
echo Old: %OLD%
echo.

"%CUTECHESS%" ^
  -engine name=new cmd="%JAVA%" arg=-jar arg="%NEW%" proto=uci ^
  -engine name=old cmd="%JAVA%" arg=-jar arg="%OLD%" proto=uci ^
  -each tc=10+0.1 ^
  -rounds 200 -games 2 ^
  -repeat ^
  -recover ^
  -concurrency 2 ^
  -sprt elo0=0 elo1=50 alpha=0.05 beta=0.05 ^
  -ratinginterval 10 ^
  -pgnout "%PGN%"

echo.
echo PGN saved to: %PGN%
endlocal
