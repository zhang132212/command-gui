@echo off
setlocal
cd /d "%~dp0.."
echo Starting Command-GUI DevStudio at http://127.0.0.1:8765 ...
where python >nul 2>nul
if %errorlevel%==0 (
  python devtools\server.py %*
) else (
  py -3 devtools\server.py %*
)
endlocal
