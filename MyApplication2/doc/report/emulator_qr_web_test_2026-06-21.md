# 에뮬레이터 빌드 & QR→웹 연동 확인 보고서

**날짜:** 2026-06-21
**작업자:** Yu Yiho (`r2525dev@gmail.com`)
**대상:** `MyApplication2`(가정/매장 Temi 앱) · `home_temi/api_server`(웹 수납 입력 페이지)

---

## 1. 목표

QR 코드로 연결되는 **웹 수납 입력 페이지(초록색 UI)** 와, 그 QR을 띄우는 **Temi 태블릿 앱 화면**을
에뮬레이터에서 실제로 빌드·실행하여 연동 흐름을 눈으로 확인.

## 2. 환경

| 항목 | 값 |
|---|---|
| 에뮬레이터(AVD) | **`Temi_Tablet_API29`** (API 29) — `emulator-5554` |
| ※ 팀원 문서의 `Temi_UI_API_29`는 이 PC(`yulee`)에 없음 | 설치된 AVD: `Pixel_2_API_29`, `Pixel_2_API_29_2`, `Temi_Tablet_API29` |
| JDK | OpenJDK 11.0.8 (`C:\Program Files\Android\Android Studio\jre`) — `gradle.properties`의 `org.gradle.java.home` |
| Android SDK | `C:\Users\yulee\AppData\Local\Android\Sdk` |
| Python (api_server) | 3.14.6 (글로벌) — fastapi/uvicorn/python-multipart 설치됨, `google-generativeai`는 추가 설치 필요했음 |

## 3. 실행한 것

### 3-1. home_temi 앱 (`org.techtown.hello`)
- `gradlew :app:assembleDebug` → 설치/실행 성공. 홈 화면(서랍 속 물건 찾기 / 물건 등록 / 사진 업로드 / DB 상태 / 설정) 흰색 카드 UI.
- 이 앱은 사용자가 찾던 "초록색 다양한 디자인"이 **아니었음**.

### 3-2. api_server 웹 페이지 (= 찾던 초록색 UI)
- 실행: `python -m uvicorn api_server.main:app --host 0.0.0.0 --port 8000` (작업 디렉토리: `home_temi/`)
- 초기 실패: `ModuleNotFoundError: No module named 'google'` → `pip install google-generativeai==0.8.3` 후 정상 기동.
- **초록 페이지: `GET http://127.0.0.1:8000/api/drawers/link?device=temi`**
  - 정의 위치: `home_temi/api_server/main.py:211` `LINK_PAGE_TEMPLATE`
  - 색상: 제목 `#2ecc71`(초록), 캡처 버튼 `#2c7a7b`, 성공 박스 `#e8f8f0/#2ecc71`, 오류 박스 빨강
  - 카드 다수(연동 완료 / 사진 촬영 / 상태 / 미리보기 / 결과 / 오류) = "다양한" 구성
  - 페이지 JS가 호출하는 API:
    - `POST /api/web-intake/photo` (`main.py:554`) — 사진 업로드 → Gemini 분석 → DB 기입
    - `GET /api/web-intake/drawer-status` (`main.py:593`) — 아두이노 서랍 센서 상태 폴링

### 3-3. MyApplication2 앱 (`org.techtown.myapplication`) — QR 화면
- `local.properties` 없어서 생성: `sdk.dir=...\yulee\AppData\Local\Android\Sdk`
- `gradlew :app:assembleDebug` → 빌드 성공, 설치 성공.
- **앱이 시작 즉시 self-kill(SIG 9)되며 크래시 루프** 발생.
- QR 화면 도달 경로: 홈("가정 Temi") → **서랍관리 → 열기** → "서랍 연동" QR 카드.
  - QR 화면 UI 코드: `MainActivity.java:577` `createQrCodeContainer(title, linkUrl)`, 호출은 `showCamera()`(`:419/:429`)
  - QR 내용(연동 URL): `MainActivity.java:69` `DEFAULT_TEMI_DB_INPUT_WEB_URL = "http://172.17.65.144:8000/api/drawers/link?device=temi"`
  - 연동 폴링: `startLinkPolling()`(`:530`) → `GET /api/drawers/link-status`(`:574`)

## 4. 핵심 트러블슈팅 — SDK provider self-kill (SIG 9)

- **증상:** `org.techtown.myapplication` 프로세스가 실행 직후 스스로 `SIG: 9`로 죽고 무한 재시작.
- **원인:** 메인 앱이 `com.robotemi:sdk:1.131.4`에 의존(`app/build.gradle:33`). robotemi SDK 초기화 시 `com.robotemi.sdk.action.BIND` 서비스에 바인드를 시도하고, 그 대상이 없으면 프로세스를 강제 종료함.
  - 앱 코드의 `Robot.getInstance()` 호출은 `isRunningOnEmulator()`로 가드돼 있지만, SDK 라이브러리 자체의 바인드는 막지 못함.
- **해결:** 팀원이 추가한 **`mock_temi_service` 모듈**(별도 APK, package `com.roboteam.teamy.usa`, `SdkService`가 `com.robotemi.sdk.action.BIND` 제공)을 **함께 설치**.
  - `gradlew :mock_temi_service:assembleDebug` → `mock_temi_service-debug.apk` 설치 → 메인 앱 정상 실행.

> **결론: 에뮬레이터에서 MyApplication2를 돌리려면 `app` APK + `mock_temi_service` APK 두 개를 모두 설치해야 함.**

## 5. 연동 흐름 (확인 완료)

```
[Temi 태블릿: MyApplication2]  서랍관리 → "서랍 연동" QR 표시 (createQrCodeContainer)
        │  (휴대폰으로 QR 스캔)
        ▼
[휴대폰 브라우저]  /api/drawers/link  ← 초록색 LINK_PAGE_TEMPLATE 페이지
        │  사진 촬영 → POST /api/web-intake/photo
        ▼
[api_server]  Gemini 분석 + DB 기입, /api/web-intake/drawer-status 폴링
        │
        ▼
[Temi 태블릿]  /api/drawers/link-status 폴링으로 연동/결과 반영
```

## 6. 재현용 명령 모음 (PowerShell)

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"

# 1) 에뮬레이터
& $emu -avd Temi_Tablet_API29 -netdelay none -netspeed full

# 2) api_server (초록 웹페이지) — home_temi 디렉토리에서
python -m uvicorn api_server.main:app --host 0.0.0.0 --port 8000
#   브라우저: http://127.0.0.1:8000/api/drawers/link?device=temi

# 3) MyApplication2 빌드 + 두 APK 설치
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jre"
.\gradlew.bat :app:assembleDebug :mock_temi_service:assembleDebug
& $adb -s emulator-5554 install -r app\build\outputs\apk\debug\app-debug.apk
& $adb -s emulator-5554 install -r mock_temi_service\build\outputs\apk\debug\mock_temi_service-debug.apk
& $adb -s emulator-5554 shell am start -n org.techtown.myapplication/.MainActivity
```

## 7. 남은 작업 / 참고

- QR 연동 URL이 `172.17.65.144:8000` 하드코딩 기본값 → 현재 PC LAN IP와 다르면 설정 화면(`showSettings`, `MainActivity.java:2498`)에서 변경 필요. (실기기 스캔 테스트 시)
- api_server는 `0.0.0.0`으로 떠 있어 같은 LAN의 휴대폰에서 PC IP로 접속 가능.
- 캡처 이미지: `MyApplication2/myapp_home2.png`(홈), `MyApplication2/myapp_drawer.png`(서랍 연동 QR).
