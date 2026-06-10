# Temi Item Finder API

This is the shared DB/API prototype for the Temi app and the phone app.

## Run

```powershell
.\.venv\Scripts\pip.exe install -r api_server\requirements.txt
.\.venv\Scripts\python.exe -m uvicorn api_server.main:app --host 0.0.0.0 --port 8000
```

## Test

```text
http://127.0.0.1:8000/api/health
http://127.0.0.1:8000/api/items/search?name=리모컨
```

Android emulator uses this base URL:

```text
http://10.0.2.2:8000
```

Real devices and other laptops must use this laptop's Wi-Fi IP address.
