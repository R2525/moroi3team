# Temi 실행 가이드 (무엇을 켜야 하는가)

매번 새로 시작할 때 **무엇을, 어떤 순서로 켜는지** 정리한 런북.
(2026-06-21 기준. 환경: PC `yulee`, AVD `Temi_Tablet_API29`)

---

## 0. 핵심 요약 — 켜야 할 것

| # | 대상 | 용도 | 필수? |
|---|------|------|-------|
| 1 | 에뮬레이터 `Temi_Tablet_API29` | Temi 화면 | 필수 |
| 2 | **`org.techtown.hello` (home_temi) 앱 하나만** | TemiLocalServer(8088) = Uno Q 수신 | 필수 |
| 3 | `adb forward tcp:18088 tcp:8088` | 에뮬 8088을 PC로 끌어옴 | Uno Q 쓸 때 |
| 4 | portproxy + 방화벽 (8088) | LAN→에뮬 8088 노출 | Uno Q 쓸 때 (영구) |
| 5 | api_server (8000) | 초록 QR 웹페이지 / 사진 수납 | 사진 흐름 쓸 때 |
| 6 | 모니터 스크립트 | 수신 확인용 | 선택 |

> ⚠️ **에뮬레이터엔 `org.techtown.hello` 하나만 설치/실행.** MyApplication2(`org.techtown.myapplication`)·mock(`com.roboteam.teamy.usa`)이 떠 있으면 엉뚱한 앱이 켜지는 원인. 필요 없으면 지운다.

경로 변수(이 PC):
- adb: `C:\Users\yulee\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- emulator: `C:\Users\yulee\AppData\Local\Android\Sdk\emulator\emulator.exe`
- 프로젝트: `C:\project\moroi\moroi3team`

---

## 1. 매번 켜는 순서 (Uno Q 센서 테스트)

```powershell
$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"
$emu = "$env:LOCALAPPDATA\Android\Sdk\emulator\emulator.exe"

# (1) 에뮬레이터
& $emu -avd Temi_Tablet_API29 -netdelay none -netspeed full
# 부팅 대기
& $adb wait-for-device

# (2) home_temi 앱 실행 (이것만!)  -> 8088 TemiLocalServer 가동
& $adb -s emulator-5554 shell monkey -p org.techtown.hello -c android.intent.category.LAUNCHER 1

# (3) adb forward (에뮬/adb 재시작마다 다시 해야 함!)
& $adb -s emulator-5554 forward tcp:18088 tcp:8088

# (4) 현재 PC IP 확인 (DHCP라 매번 바뀔 수 있음 -> Uno Q에 이 IP:8088 설정)
Get-NetIPAddress -AddressFamily IPv4 | ? { $_.PrefixOrigin -eq 'Dhcp' } | Select IPAddress,InterfaceAlias
```

확인:
```powershell
# 8088 체인 정상? (200 {"active":false} 나오면 OK)
Invoke-WebRequest "http://127.0.0.1:8088/api/placement-batches/current" -UseBasicParsing
```

---

## 2. 한 번만 하면 되는 것 (영구 — 재부팅해도 유지)

> portproxy / 방화벽은 시스템에 남는다. **`adb forward`만 매번 다시** 해야 함.

```powershell
# (관리자 PowerShell) 8088 LAN 노출 — 중간포트 18088 경유 (직결 8088<->8088은 10013 충돌!)
netsh interface portproxy add v4tov4 listenport=8088 listenaddress=0.0.0.0 connectport=18088 connectaddress=127.0.0.1
# (관리자) 방화벽 인바운드 허용
New-NetFirewallRule -DisplayName "TemiLocalServer-8088" -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8088
New-NetFirewallRule -DisplayName "TemiApiServer-8000"  -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8000
```
체인: `LAN 0.0.0.0:8088 → portproxy → 127.0.0.1:18088 → adb forward → 에뮬 8088`

---

## 3. Uno Q(아두이노) 설정

- **보낼 주소**: `http://<현재 PC IP>:8088`  (예: `http://10.34.255.29:8088`)
- **경로**: `POST /api/sensor-events`  ← `/api/storage-events` 아님! (그건 8000용, 8088에선 404)
- 폴링: `GET /api/placement-batches/current`
- 페이로드 예: `{"event_type":"VERIFY_SUCCESS"}` / `{"event_type":"weight_changed","drawer_number":2,"value":45.0}`

> 데이터 수신 시점: POST 즉시 **항상 기록**됨. 단 VERIFY_SUCCESS가 "물품 진행"에 반영되려면 **활성 수납 배치(placement-batch)** 가 있어야 함(없으면 기록만, "진행 중 수납 없음").

---

## 4. (선택) api_server (8000) — 초록 QR 웹페이지 / 사진 수납

```powershell
# home_temi 디렉토리에서
cd C:\project\moroi\moroi3team\home_temi
python -m uvicorn api_server.main:app --host 0.0.0.0 --port 8000
# 의존성 없으면: pip install google-generativeai==0.8.3
```
- 초록 페이지: `http://<PC IP>:8000/api/drawers/link?device=temi`
- 사진 분석엔 `home_temi/.env` 에 `GEMINI_API_KEY=...` 필요

---

## 5. (선택) 수신 모니터

```powershell
# 8088 sensor-events 폴링 모니터 (비침투, 권장)
python C:\project\moroi\moroi3team\home_temi\tools\recv_monitor_8088.py

# 8000 api_server 요청 모니터 (uvicorn 로그 tail)
powershell -File C:\project\moroi\moroi3team\home_temi\tools\recv_monitor.ps1
```

---

## 6. 자주 겪는 문제

| 증상 | 원인/해결 |
|------|-----------|
| 에뮬에 엉뚱한 앱이 뜸 | home_temi 외 앱 설치됨 → `adb uninstall org.techtown.myapplication` 등으로 제거, home_temi만 유지 |
| Uno Q 404 반복 | 경로가 `/api/storage-events`(8000용) → `/api/sensor-events`로 변경 |
| Uno Q 연결 타임아웃 | `adb forward tcp:18088 tcp:8088` 다시 실행 (에뮬 재시작 시 사라짐) / PC IP 바뀌었는지 확인 |
| 8088 안 열림 | home_temi 앱이 꺼져 있음 → 다시 실행 |
| `adb forward` 10013 에러 | 8088 직결 portproxy가 점유 중 → 중간포트(18088) 방식 사용 |
| 수신은 되는데 진행 안 됨 | 활성 placement-batch 없음 → 사진 업로드/앱에서 "넣기 시작" |
