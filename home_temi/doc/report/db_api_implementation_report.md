# DB/API 구현 보고서

작성일: 2026-05-27  
최종 업데이트: 2026-05-27

## 1. 전체 구조

Temi 앱과 휴대폰 앱이 같은 데이터를 사용할 수 있도록, 공용 API 서버를 두는 구조로 구현했다.

```text
Temi 앱 (172.17.65.250)  ──┐
                            ├──  HTTP (JSON)  ──  FastAPI 서버 (172.17.67.210:8000)
휴대폰 앱 (다른 노트북)    ──┘                         │
                                                   sqlite3
                                                       │
                                                SQLite DB (inventory.db)
```

## 2. API 서버

### 2.1 기술 스택

| 항목 | 값 |
| --- | --- |
| 언어 | Python 3 |
| 프레임워크 | FastAPI 0.115.6 |
| ASGI 서버 | uvicorn 0.34.0 |
| DB | SQLite (프로토타입) |
| 가상환경 | `.venv` (프로젝트 루트) |

### 2.2 파일 구조

```text
api_server/
├── main.py             # FastAPI 앱, 라우트, DB 초기화
├── schema.sql          # 테이블 생성 DDL (5개 테이블)
├── requirements.txt    # fastapi, uvicorn
├── README.md           # 실행 방법 안내
└── inventory.db        # 실행 시 생성 (.gitignore 대상)
```

### 2.3 실행 방법

```powershell
.\.venv\Scripts\pip.exe install -r api_server\requirements.txt
.\.venv\Scripts\python.exe -m uvicorn api_server.main:app --host 0.0.0.0 --port 8000
```

서버가 `0.0.0.0:8000`에서 시작되면 같은 Wi-Fi 내 모든 장치에서 접속 가능하다.

## 3. DB 스키마

### 3.1 테이블 구성

총 6개 테이블. 5개는 `schema.sql`에서, 1개(`sensor_check`)는 `main.py`에서 생성한다.

| 테이블 | 역할 | 생성 위치 |
| --- | --- | --- |
| `user` | 사용자/가족 구성원 | schema.sql |
| `storage_location` | 서랍/보관 위치 및 LED 채널 | schema.sql |
| `item` | 물건 정보 (이름, 상태) | schema.sql |
| `item_placement` | 물건-위치 매핑 (수량, user_id) | schema.sql |
| `shopping_list` | 구매 필요 품목 (수량, 상태, user_id, 메모) | schema.sql |
| `sensor_check` | 센서 기반 위치 검증 기록 | main.py 내 DDL |

### 3.2 DDL 상세

**user**

```sql
CREATE TABLE IF NOT EXISTS user (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**storage_location**

```sql
CREATE TABLE IF NOT EXISTS storage_location (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    drawer_number INTEGER NOT NULL,
    led_channel INTEGER NOT NULL,
    description TEXT NOT NULL,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**item**

```sql
CREATE TABLE IF NOT EXISTS item (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL UNIQUE,
    status TEXT NOT NULL DEFAULT 'available',
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

**item_placement**

```sql
CREATE TABLE IF NOT EXISTS item_placement (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    item_id INTEGER NOT NULL,
    storage_location_id INTEGER NOT NULL,
    user_id INTEGER,
    quantity INTEGER NOT NULL DEFAULT 1,
    last_checked_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (item_id) REFERENCES item(id),
    FOREIGN KEY (storage_location_id) REFERENCES storage_location(id),
    FOREIGN KEY (user_id) REFERENCES user(id),
    UNIQUE(item_id, storage_location_id)
);
```

**shopping_list**

```sql
CREATE TABLE IF NOT EXISTS shopping_list (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    name TEXT NOT NULL,
    quantity INTEGER NOT NULL DEFAULT 1,
    status TEXT NOT NULL DEFAULT 'pending',
    user_id INTEGER,
    note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES user(id)
);
```

**sensor_check**

```sql
CREATE TABLE IF NOT EXISTS sensor_check (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    item_name TEXT NOT NULL,
    detected INTEGER NOT NULL,
    note TEXT,
    created_at TEXT NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

### 3.3 ERD 관계

```text
user (1) ──── (N) item_placement (N) ──── (1) item
                       │
                       └──── (1) storage_location

user (1) ──── (N) shopping_list

sensor_check (독립 기록)
```

- `item`과 `storage_location`은 `item_placement`를 통해 N:N 관계
- `item_placement`의 `(item_id, storage_location_id)` 조합은 UNIQUE
- 같은 조합으로 재등록하면 수량과 시간이 갱신됨 (UPSERT)
- `user`는 `item_placement`와 `shopping_list`에 선택적 FK로 연결

### 3.4 샘플 데이터

서버 시작 시 `seed_db()`가 다음 데이터를 자동 삽입한다.

| 물건 | 위치 | 서랍 | LED | 설명 | 수량 |
| --- | --- | --- | --- | --- | --- |
| 리모컨 | 거실 2번 서랍 | 2 | 2 | 거실 오른쪽 두 번째 서랍 | 1 |
| 약 | 주방 1번 서랍 | 1 | 1 | 주방 왼쪽 첫 번째 서랍 | 1 |
| 충전기 | 침실 3번 서랍 | 3 | 3 | 침실 아래쪽 세 번째 서랍 | 2 |

`INSERT OR IGNORE`를 사용하므로 서버를 재시작해도 중복 삽입되지 않는다.

## 4. API 엔드포인트

### 4.1 헬스체크

```text
GET /api/health → {"status": "ok"}
```

### 4.2 사용자 관리

```text
GET  /api/users           → 사용자 목록
POST /api/users           → 사용자 생성 (name)
GET  /api/users/{user_id} → 사용자 조회
```

### 4.3 물건 검색

```text
GET /api/items/search?name={키워드}
```

정확 일치를 우선하고, 없으면 부분 일치(`LIKE`)로 검색한다.

검색 성공 응답:

```json
{
    "found": true,
    "name": "리모컨",
    "status": "available",
    "quantity": 1,
    "location": "거실 2번 서랍",
    "drawer_number": 2,
    "led_channel": 2,
    "description": "거실 오른쪽 두 번째 서랍"
}
```

미등록 물건 응답:

```json
{"found": false, "name": "키워드"}
```

### 4.4 물건 등록

```text
POST /api/items → 물건 단독 등록 (name, status)
```

### 4.5 물건 위치 등록 (통합)

```text
POST /api/placements
```

요청:

```json
{
    "item_name": "열쇠",
    "location_name": "1번 서랍",
    "drawer_number": 1,
    "led_channel": 1,
    "description": "1번 서랍",
    "quantity": 1,
    "user_id": null
}
```

물건과 위치가 없으면 자동 생성하고, 이미 있는 조합이면 수량과 시간을 갱신한다 (UPSERT).

### 4.6 센서 검증 기록

```text
POST /api/sensor-checks → 센서 감지 기록 저장 (item_name, detected, note)
```

### 4.7 쇼핑 목록

```text
GET    /api/shopping-list          → 목록 조회 (?status=pending 필터 가능)
POST   /api/shopping-list          → 항목 추가 (name, quantity, user_id, note)
PUT    /api/shopping-list/{id}     → 상태 변경 (pending/done)
DELETE /api/shopping-list/{id}     → 항목 삭제
```

PUT을 사용한 이유: Android `HttpURLConnection`이 PATCH 메서드에서 `ProtocolException`을 발생시키기 때문.

## 5. Android 앱 연동

### 5.1 네트워크 구조

```java
private static final String[] API_BASE_URLS = {
    "http://127.0.0.1:8000",    // ADB reverse 개발 환경
    "http://10.0.2.2:8000",     // Android 에뮬레이터
    "http://172.17.67.210:8000"  // 노트북 Wi-Fi IP (Temi 실기기)
};
```

첫 번째 주소부터 시도하고, 연결 실패 시 다음 주소로 넘어간다. 타임아웃 2500ms.

### 5.2 호출 흐름

모든 API 호출은 별도 스레드에서 수행하고 결과를 `runOnUiThread()`로 UI에 반영한다.

```text
searchItem()   → Thread → fetchItem()      → runOnUiThread → showResult()
registerItem() → Thread → postPlacement()   → runOnUiThread → showRegisterSuccess()
checkDbStatus()→ Thread → fetchHealth()     → runOnUiThread → showDbStatus()
```

### 5.3 한글 문자열 처리

파일 인코딩 문제를 피하기 위해 모든 UI 문자열을 Java 유니코드 이스케이프 기반 상수(`K` 클래스)로 관리한다.

## 6. Temi 앱 화면 구성

### 6.1 홈 화면

3개 메뉴 카드를 가로 배치.

```text
┌──────────────────┐ ┌──────────────────┐ ┌──────────────────┐
│ 서랍 속 물건 찾기 │ │   물건 등록       │ │   DB 상태         │
│ 입력/음성 검색    │ │ 물건+위치 저장    │ │ API 연결 확인     │
└──────────────────┘ └──────────────────┘ └──────────────────┘
```

### 6.2 물건 찾기 화면

- 직접 입력 검색
- 음성 인식 버튼 (현재 미연동, Toast 표시)
- 검색 버튼 → API 호출
- 샘플 검색 버튼: 리모컨, 약, 충전기

### 6.3 물건 등록 화면 (간소화 완료)

```text
[물건명                    ]
[서랍 번호    ] [수량       ]
[예시 채우기] [등록]
```

사용자는 물건명, 서랍 번호, 수량만 입력한다. 나머지 값은 자동 생성:
- `led_channel` = `drawer_number`
- `location_name` = 서랍번호 + "번 서랍"
- `description` = `location_name`과 동일

### 6.4 검색 결과 화면

등록된 물건:

```text
리모컨 위치를 찾았습니다.
서랍     2번 서랍
수량     1개
안내     거실 오른쪽 두 번째 서랍
[LED 2번 점등 중]
[Temi 음성 안내: 거실 오른쪽 두 번째 서랍으로 이동하세요.]
```

### 6.5 DB 상태 화면

- 연결 성공: API 주소 표시 + "물건 등록과 검색 사용 가능" 배지
- 연결 실패: 오류 메시지 + 서버/Wi-Fi 확인 안내

## 7. Temi 실기기 적용

### 7.1 장치 정보

```text
장치 주소: 172.17.65.250:5555
화면: 1920 x 1200 landscape
```

### 7.2 Temi 전용 설정

| 항목 | 설정 |
| --- | --- |
| 화면 방향 | 가로 고정 (`screenOrientation="landscape"`) |
| 전체화면 | 내비게이션/상태바 숨김 (Immersive Sticky) |
| 화면 켜짐 | `FLAG_KEEP_SCREEN_ON` |
| 글자/버튼 크기 | 텍스트 26~42sp, 버튼 높이 92dp |

### 7.3 설치 및 실행

```powershell
.\gradlew assembleDebug
adb -s 172.17.65.250:5555 install -r app\build\outputs\apk\debug\app-debug.apk
adb -s 172.17.65.250:5555 shell am start -n org.techtown.hello/.MainActivity
```

## 8. 검증 결과

### 8.1 API 서버

```text
GET  /api/health                → {"status": "ok"}
GET  /api/items/search?name=리모컨 → 정상 응답
POST /api/placements            → 물건+위치 등록 성공
POST /api/sensor-checks         → 센서 기록 저장 성공
GET  /api/shopping-list         → 쇼핑 목록 조회 성공
GET  /api/users                 → 사용자 목록 조회 성공
```

### 8.2 Temi 실기기

```text
APK 빌드 → 설치 → 실행 성공
홈 화면 3개 메뉴 표시 성공
물건 등록 → 3개 필드 폼 정상 동작 (스크린샷 확인 완료)
물건 찾기 → 검색 → 결과 표시 성공
DB 상태 → 연결 성공, 주소 표시 정상
```

### 8.3 검증 스크린샷

```text
temi_register_v3.png   등록 화면 (3개 필드, 최신)
temi_register_v2.png   홈 화면 (최신)
temi_screen3.png       검색 결과 화면
temi_db_status.png     DB 연결 성공 화면
```

## 9. 현재 한계 및 알려진 이슈

1. **SQLite 단일 파일 DB**: 프로토타입용. 동시 접속이 많아지면 전환 필요.
2. **HTTP 평문 통신**: `usesCleartextTraffic="true"` 사용 중. 운영 시 HTTPS 필요.
3. **API 주소 하드코딩**: 노트북 IP 변경 시 소스 수정 필요.
4. **음성 인식 미연동**: 버튼은 있으나 Toast만 표시.
5. **LED/서랍 하드웨어 미연동**: LED 점등은 화면 텍스트로만 표현.
6. **인증/권한 없음**: API는 누구나 호출 가능.

## 10. 앞으로 해야 할 일

### 단기 (프로토타입 완성)

1. `search_log` 테이블 추가 — 검색 키워드, 시간, 기기 기록
2. `item_checkout` 테이블 추가 — 물건 꺼냄/반납 이력
3. 휴대폰 앱 UI를 현재 DB 구조에 맞춤 (category 제거, user_id 연동)
4. API 주소 설정 파일 분리

### 중기 (기능 고도화)

5. 음성 인식 연동 (Android SpeechRecognizer 또는 Temi 음성 API)
6. LED/서랍 제어 장치 통신 인터페이스 정의
7. 사진 분석 기능 (사진 촬영 → AI 분석 → 물건 자동 인식)
8. Temi 이동 연동 (검색 결과에서 해당 서랍으로 이동)

### 장기 (운영 준비)

9. HTTPS 적용 + API 인증
10. SQLite → PostgreSQL 전환
11. 서버 배포 환경 구성 (Docker 또는 클라우드)
12. Gradle/AGP/SDK 버전 업그레이드
