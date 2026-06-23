# Moroi 3 Team

Temi 기반 홈 수납, 매장 안내, Arduino 센서 연동을 함께 관리하는 통합 저장소입니다.

## 병합 우선순위

main에는 각 브랜치의 실제 실행 코드와 설정만 선별 반영합니다.

| 영역 | 우선 브랜치 | main 반영 기준 |
| --- | --- | --- |
| Home Temi | `TemiServer` | `home_temi/app`, `home_temi/api_server`, 홈 Temi 설정/도구 우선 |
| Store Temi | `store-temi-updates-2026-06-22` | `home_temi/store_app`, `MyApplication2`의 매장/쇼핑 앱 소스 우선 |
| Arduino | `origin/arduino` | `arduino`, `sketch`, 센서 실행 보조 스크립트 우선 |

스크린샷, UI 덤프 XML, 임시 캡처, 대용량 모델/입력 데이터, 개인 기획 원본 파일은 main에 넣지 않습니다.

## 디렉터리 개요

- `home_temi/app`: Home Temi Android 앱입니다. 물품 검색, 수납 등록, 사진 업로드, Gemini 분석, 서랍 배치 확인, 센서 이벤트 연동을 담당합니다.
- `home_temi/store_app`: Store Temi Android 앱입니다. 쇼핑 목록 QR/서버 데이터를 읽고 매장 위치 안내 및 Temi 이동을 담당합니다.
- `home_temi/api_server`: Home Temi와 QR 웹 페이지용 FastAPI 서버입니다. 사진 분석, 물품/서랍 API, 센서 이벤트 API를 제공합니다.
- `MyApplication2`: 휴대폰/에뮬레이터용 쇼핑/DB 앱과 테스트용 backend입니다.
- `arduino`: 서랍 센서, 로드셀, 검증 상태 머신 Arduino 스케치입니다.
- `python`, `scripts`, `yolo`, `sketch`: Arduino/비전/로컬 실행 보조 코드입니다.
- `docs`: 센서 시나리오 테스트 문서입니다.

## 필수 준비

- Windows PowerShell
- Android Studio 또는 Android Gradle 환경
- JDK 11 이상
- Android SDK Platform Tools (`adb`)
- Python 3.10 이상
- Arduino IDE 또는 arduino-cli
- Temi 실기기는 같은 네트워크에서 ADB 연결 가능해야 합니다.

기본 Temi ADB 대상:

```powershell
$adb = 'C:\Users\yulee\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb connect 10.34.255.116:5555
```

## Home Temi 빌드 및 설치

```powershell
cd C:\project\moroi\moroi3team\home_temi
.\gradlew.bat :app:assembleDebug
```

Temi 설치:

```powershell
$adb = 'C:\Users\yulee\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s 10.34.255.116:5555 install -r 'app\build\outputs\apk\debug\app-debug.apk'
```

실행:

```powershell
& $adb -s 10.34.255.116:5555 shell am start -n org.techtown.hello/.MainActivity
```

## Store Temi 빌드 및 설치

```powershell
cd C:\project\moroi\moroi3team\home_temi
.\gradlew.bat :store_app:assembleDebug
```

설치:

```powershell
$adb = 'C:\Users\yulee\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s 10.34.255.116:5555 install -r 'store_app\build\outputs\apk\debug\store_app-debug.apk'
```

## API 서버 실행

Home Temi QR/사진/서랍 API:

```powershell
cd C:\project\moroi\moroi3team\home_temi
python -m venv .venv
.\.venv\Scripts\pip.exe install -r api_server\requirements.txt
.\.venv\Scripts\python.exe -m uvicorn api_server.main:app --host 0.0.0.0 --port 8000
```

확인:

```text
http://127.0.0.1:8000/api/health
http://<PC_LAN_IP>:8000/api/drawers/link?device=temi
```

MyApplication2 테스트 backend:

```powershell
cd C:\project\moroi\moroi3team\MyApplication2
python backend\server.py --host 0.0.0.0 --port 8080
```

## 앱 설정

Home Temi 앱의 설정 화면에서 다음 값을 맞춥니다.

- Gemini API 키: 사진 분석을 사용할 때 입력합니다. 저장소에는 키를 커밋하지 않습니다.
- 외부 접속 IP: QR 페이지를 열 PC의 LAN IP 또는 Temi IP를 입력합니다.
- 쇼핑 API 주소: 에뮬레이터는 `http://10.0.2.2:8080`, 실기기는 `http://<PC_LAN_IP>:8080` 또는 팀 서버 주소를 사용합니다.

Store Temi 앱은 기본적으로 여러 `8000` 포트 API 후보를 순서대로 시도합니다. 실기기에서 실패하면 `home_temi/store_app/src/main/java/org/techtown/storeitem/MainActivity.java`의 `API_BASE_URLS`에 현재 PC LAN IP를 추가합니다.

## Arduino

주요 스케치:

- `arduino/door_verify_state_machine/door_verify_state_machine.ino`: 서랍 열림/닫힘 검증 상태 머신
- `arduino/load_cell_test.ino`: 로드셀 테스트
- `arduino/sensor_handler.ino`: 센서 이벤트 처리
- `sketch/sketch.ino`: 보조 스케치

Arduino IDE에서 해당 `.ino`를 열고 보드/포트를 선택해 업로드합니다. Uno Q 또는 센서 장치가 HTTP 이벤트를 보내야 할 때는 Home Temi 서버 주소를 `http://<PC_LAN_IP>:8088/api/sensor-events`로 맞춥니다. 자세한 실행 순서는 `home_temi/doc/startup_guide.md`를 참고합니다.

## 검증 명령

Android 빌드:

```powershell
cd C:\project\moroi\moroi3team\home_temi
.\gradlew.bat :app:assembleDebug
.\gradlew.bat :store_app:assembleDebug
```

Git 상태 확인:

```powershell
git status --short --branch
```
