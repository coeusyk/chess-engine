@echo off
setlocal

set CUTECHESS=C:\Users\yashk\Downloads\cutechess-1.4.0-win64\cutechess-1.4.0-win64\cutechess-cli.exe
set NEW=C:\Users\yashk\WorkDir\Projects\ChessEngine\chess-engine\engine-uci\target\engine-uci-0.4.8-SNAPSHOT.jar
set OLD=C:\Users\yashk\WorkDir\Projects\ChessEngine\chess-engine\tools\pre-tuning-0.4.8.jar

for /f %%I in ('powershell -NoProfile -Command "Get-Date -Format yyyyMMdd_HHmmss"') do set "TS=%%I"
set PGN=C:\Users\yashk\WorkDir\Projects\ChessEngine\chess-engine\tools\results\sprt_phase8_%TS%.pgn

"%CUTECHESS%" ^
  -engine name=Vex-new cmd=java arg=-jar arg="%NEW%" proto=uci ^
  -engine name=Vex-old cmd=java arg=-jar arg="%OLD%" proto=uci ^
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
