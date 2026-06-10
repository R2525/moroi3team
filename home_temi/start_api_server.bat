@echo off
cd /d C:\Users\dbals\AndroidStudioProjects\Hello
.\.venv\Scripts\python.exe -m uvicorn api_server.main:app --host 0.0.0.0 --port 8000
pause
