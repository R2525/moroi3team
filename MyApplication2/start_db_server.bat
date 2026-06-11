@echo off
cd /d "%~dp0"
echo TEMI DB server starting...
echo.
echo If this window says the port is already in use, close the other DB server window first.
echo Keep this window open while using the Android emulator.
echo.
python backend\server.py --host 0.0.0.0 --port 8080
echo.
echo DB server stopped. Press any key to close this window.
pause > nul
