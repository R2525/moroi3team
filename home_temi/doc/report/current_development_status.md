# Temi Project Current Development Status

작성일: 2026-06-02
프로젝트 경로: `C:\Users\dbals\AndroidStudioProjects\Hello`

이 문서는 현재까지 구현된 Android 앱, 매장 Temi 앱, FastAPI 서버, DB/API, Gemini 사진 분석 기능, 남은 작업을 정리한다.

## 1. 현재 프로젝트 구조

```text
Hello/
├─ app/                 가정용 Temi 앱
├─ store_app/           매장용 Temi 앱
├─ api_server/          FastAPI 공용 API 서버
├─ doc/
│  ├─ report/           보고서/API 명세
│  └─ usr/              기획 자료, UI 자료, 순서도
└─ .env                 Gemini API 키 저장 파일, Git 제외
```

역할 구분:

```text
app        = 집 안 Temi: 물건 등록, 물건 검색, DB 상태 확인
store_app  = 매장 Temi: 쇼핑리스트 수신, 물품 선택, 매장 위치 안내
api_server = 공용 DB/API, Gemini 사진 분석, 쇼핑/보관/매장 API
```

## 2. 서버/API 상태

서버 실행 명령:

```powershell
.\.venv\Scripts\python.exe -m uvicorn api_server.main:app --host 0.0.0.0 --port 8000
```

현재 사용 중인 서버 주소:

```text
http://10.136.55.25:8000
```

상태 확인:

```text
GET /api/health
```

현재 확인 결과:

```text
status: ok
```

주의:

```text
Temi, 폰 앱 노트북, 서버 노트북은 같은 Wi-Fi에서 서로 접근 가능해야 한다.
서버 노트북 IP가 바뀌면 앱에 전달한 API 주소도 바꿔야 한다.
```

## 3. 가정용 Temi 앱 상태

모듈:

```text
app/
```

패키지:

```text
org.techtown.hello
```

앱 표시 이름:

```text
Item
```

구현된 화면:

```text
홈 화면
물건 찾기
검색 결과
물건 등록
DB 상태 확인
```

현재 검증된 흐름:

```text
API 서버 연결 성공
Temi에 APK 설치 및 실행 성공
DB 상태 확인 성공
물건 등록 성공
물건 검색 성공
서랍 번호, LED 번호, 수량 표시 성공
```

Temi 실행 명령:

```powershell
C:\Users\dbals\AppData\Local\Android\Sdk\platform-tools\adb.exe -s 172.17.64.121:5555 shell am start -n org.techtown.hello/.MainActivity
```

## 4. 매장 Temi 앱 상태

모듈:

```text
store_app/
```

패키지:

```text
org.techtown.storeitem
```

앱 표시 이름:

```text
Store Item
```

빌드 명령:

```powershell
.\gradlew.bat :store_app:assembleDebug
```

APK 위치:

```text
store_app\build\outputs\apk\debug\store_app-debug.apk
```

구현된 흐름:

```text
앱 실행
-> 전송된 쇼핑리스트 조회
-> 쇼핑리스트 목록 표시
-> 물품 카드 선택
-> 해당 물품의 매장 위치 안내 화면 표시
-> 픽업 완료 또는 목록 복귀
```

현재 조회 대상 API:

```text
GET /api/stores/1/transfers/1
```

서버 데이터가 없거나 연결 실패하면 UI 확인용 샘플 목록을 표시한다.

```text
book
remote
charger
```

Temi 설치/실행 검증:

```text
store_app APK 설치 성공
org.techtown.storeitem/.MainActivity 실행 성공
현재 포커스 확인 성공
```

## 5. Gemini 사진 분석 API 상태

구현된 API:

```text
POST /api/photos/analyze
```

요청 형식:

```text
multipart/form-data
```

필드:

```text
image   필수, 사진 파일
user_id 선택
```

처리 흐름:

```text
1. 이미지 업로드 수신
2. api_server/uploads/에 이미지 저장
3. .env의 GEMINI_API_KEY 읽기
4. Gemini API 호출
5. 물건 인식 결과 JSON 파싱
6. recognition_session 저장
7. recognized_item 저장
8. 분석 결과 응답
```

현재 Gemini 모델:

```text
gemini-flash-latest
```

기존 `gemini-1.5-flash`는 현재 API 키의 `v1beta generateContent`에서 지원되지 않아 404가 발생했다. `listModels`로 사용 가능한 모델을 확인한 뒤 `gemini-flash-latest`로 교체했다.

응답 예시:

```json
{
  "recognition_id": 12,
  "image_path": "...",
  "llm_model": "gemini-flash-latest",
  "total_count": 4,
  "summary": [
    { "item_name": "book", "count": 2 },
    { "item_name": "remote", "count": 1 },
    { "item_name": "charger", "count": 1 }
  ],
  "items": [
    {
      "id": 31,
      "sequence_no": 1,
      "item_name": "book",
      "confidence": 0.92,
      "status": "detected"
    }
  ]
}
```

다른 노트북/폰 앱에 전달할 주소:

```text
POST http://10.136.55.25:8000/api/photos/analyze
```

폰 앱 쪽 역할:

```text
사진 촬영 또는 선택
image 필드로 multipart POST
응답의 total_count, summary, items를 화면에 표시
```

서버 쪽 구현 상태:

```text
python-multipart 설치 완료
/api/photos/analyze 라우트 등록 완료
Gemini 모델 404 문제 해결 완료
사진 분석 호출 성공 확인됨
물건 종류별 개수 응답 추가 완료
```

## 6. 현재 DB 상태

### 6.1 보관 물품

현재 `item`, `item_placement`, `storage_location` 기준 조회 결과:

| 물품 | 서랍 | LED | 수량 |
| --- | ---: | ---: | ---: |
| book | 1 | 1 | 1 |
| demo | 1 | 1 | 2 |
| pen | 1 | 1 | 2 |
| s | 1 | 1 | 11 |
| shampoo | 1 | 1 | 1 |
| remote | 2 | 2 | 1 |
| shampoo | 2 | 2 | 2 |
| charger | 3 | 3 | 2 |
| shampoo | 3 | 3 | 14 |

위치가 아직 없는 item도 존재한다.

```text
codex_flow_item
ice cream bar
lip
soap
샴푸
세제
수건
휴지
```

주의:

```text
테스트 과정에서 demo, pen, s, shampoo 등 데이터가 추가되었다.
시연 전에는 DB를 시연용 데이터로 다시 정리하는 것이 좋다.
```

### 6.2 쇼핑리스트

현재 legacy `shopping_list` 데이터:

| ID | 물품 | 수량 | 상태 |
| ---: | --- | ---: | --- |
| 4 | codex_test | 1 | pending |
| 5 | shampoo | 1 | pending |
| 6 | demo | 1 | pending |
| 7 | lip | 3 | pending |
| 8 | shampoo | 3 | pending |
| 9 | soap | 6 | pending |
| 10 | shampoo | 1 | pending |
| 11 | lip | 3 | pending |
| 12 | shampoo | 1 | pending |
| 13 | lip | 3 | pending |
| 14 | soap | 2 | pending |
| 15 | lip | 3 | pending |

신규 가족 요청/비교/최종 쇼핑리스트 흐름은 아직 실제 시연 데이터가 없다.

```text
shopping_session
shopping_request
shopping_request_item
shopping_result_item
final_shopping_list
final_shopping_list_item
```

### 6.3 사진 인식 데이터

현재 사진 인식 관련 누적 데이터:

```text
recognition_session: 5개
recognized_item: 21개
```

## 7. 주요 API 구현 상태

### 7.1 기본/물건 API

```text
GET    /api/health
GET    /api/users
POST   /api/users
GET    /api/users/{user_id}
GET    /api/items/search
POST   /api/items
POST   /api/placements
POST   /api/sensor-checks
```

### 7.2 사진 인식 API

```text
POST /api/photos/analyze
POST /api/recognitions
GET  /api/recognitions/{recognition_id}
POST /api/recognitions/{recognition_id}/items
GET  /api/recognitions/{recognition_id}/items
PUT  /api/recognized-items/{recognized_item_id}
```

### 7.3 보관/센서 API

```text
POST /api/storage-sessions
PUT  /api/storage-sessions/{storage_session_id}/current-item
GET  /api/storage-sessions/{storage_session_id}/current-item
POST /api/storage-events
POST /api/drawer-camera-snapshots
POST /api/placement-verifications
```

### 7.4 쇼핑리스트 API

```text
GET    /api/shopping-list
POST   /api/shopping-list
PUT    /api/shopping-list/{item_id}
DELETE /api/shopping-list/{item_id}

POST /api/shopping-sessions
POST /api/shopping-sessions/{session_id}/requests
POST /api/shopping-sessions/{session_id}/compare
GET  /api/shopping-sessions/{session_id}/results
GET  /api/shopping-sessions/{session_id}/owned-items
POST /api/shopping-sessions/{session_id}/finalize
GET  /api/final-shopping-lists/{final_list_id}
```

### 7.5 매장 Temi API

```text
POST /api/stores
POST /api/stores/{store_id}/sections
POST /api/stores/{store_id}/products
POST /api/final-shopping-lists/{final_list_id}/transfer
GET  /api/stores/{store_id}/transfers/{transfer_id}
POST /api/transfers/{transfer_id}/navigation
GET  /api/navigation-sessions/{navigation_session_id}/steps
PUT  /api/navigation-steps/{step_id}
```

## 8. 검증 완료 내역

```text
가정용 Temi 앱 빌드/설치/실행 성공
가정용 Temi 앱 DB 연결 성공
물건 등록/검색 흐름 성공
Temi와 서버 노트북 Wi-Fi 대역 문제 확인 및 해결
매장 Temi 앱 모듈 생성 성공
매장 Temi 앱 빌드/설치/실행 성공
API 서버 health 정상
/api/photos/analyze 라우트 등록 성공
Gemini 모델 404 문제 해결
사진 분석 응답에 종류별 개수 추가
```

## 9. 현재 한계와 주의사항

```text
API 주소가 앱 코드에 하드코딩되어 있다.
서버 노트북 IP가 바뀌면 앱 또는 전달 주소를 수정해야 한다.
Gemini API 키는 .env에 있으며 Git 제외 처리되어 있다.
api_server/uploads/도 Git 제외 처리되어 있다.
현재 HTTP 평문 통신을 사용한다.
API 인증이 없다.
SQLite 단일 파일 DB를 사용한다.
센서/LED/서랍 하드웨어 실제 연동은 아직 안 되어 있다.
매장 Temi 앱은 현재 store_id=1, transfer_id=1을 기준으로 조회한다.
쇼핑리스트의 legacy 테이블과 신규 가족 취합 테이블이 공존한다.
시연 전 DB 정리가 필요하다.
```

## 10. 앞으로 해야 할 작업

### 10.1 가장 먼저 할 작업

1. 폰 앱에서 `/api/photos/analyze` 응답의 `summary`, `total_count`, `items`를 화면에 표시한다.
2. 사진 분석 결과를 바탕으로 사용자가 확인/수정할 수 있는 화면을 만든다.
3. 확인된 인식 결과로 보관 세션을 시작한다.

권장 흐름:

```text
사진 업로드
-> Gemini 분석
-> 인식 결과 목록 표시
-> 사용자가 물건명/개수 확인
-> 보관 시작
-> recognized_item 순서대로 서랍에 넣기
-> placement_verifications로 최종 보관 확정
```

### 10.2 가정용 Temi 앱 다음 작업

```text
사진 인식 결과 목록 화면 추가
현재 보관할 물건 순번 표시
보관 완료/다음 물건 버튼 추가
사진 분석 결과와 storage_session 연결
검색/등록 테스트 데이터 정리
```

### 10.3 폰 앱 다음 작업

```text
사진 촬영 또는 갤러리 선택
multipart/form-data 업로드 구현
분석 결과 total_count/summary/items 표시
분석 결과 수정 UI
서버 응답 실패 시 에러 메시지 표시
```

### 10.4 매장 Temi 앱 다음 작업

```text
store_id, transfer_id 입력 또는 선택 화면 추가
실제 final_shopping_list transfer 데이터 생성 후 연동 테스트
상품 위치 카드 UI 개선
픽업 완료 시 PUT /api/navigation-steps/{step_id} 호출
매장 구역/통로/선반 샘플 데이터 정리
```

### 10.5 쇼핑리스트 흐름 다음 작업

```text
shopping_session 생성 테스트
가족별 shopping_request 입력 테스트
compare API로 집에 있는 물건과 구매 필요 물건 분리
finalize API로 최종 쇼핑리스트 생성
transfer API로 매장 Temi에 전송
```

### 10.6 시연 전 정리 작업

```text
DB를 시연용 데이터로 초기화
불필요한 테스트 물품 삭제
book / remote / charger 또는 최종 시연 물품만 남기기
쇼핑리스트 시연 데이터 별도 생성
서버 IP 확인
Temi, 폰 앱 노트북, 서버 노트북 같은 Wi-Fi 확인
API 서버 실행 확인
사진 분석 1회 사전 테스트
```

## 11. 추천 시연 시나리오

```text
1. 폰 앱에서 사진 촬영
2. 서버가 Gemini로 물건 종류/개수 분석
3. 폰 앱에 인식 결과 표시
4. 가정용 Temi 앱에서 물건 등록/검색 확인
5. 쇼핑리스트에 필요한 물품 등록
6. 집에 있는 물건과 구매할 물건 비교
7. 최종 쇼핑리스트를 매장 Temi 앱으로 전송
8. 매장 Temi 앱에서 물품 선택 후 위치 안내
```

## 12. 관련 파일

```text
api_server/main.py
api_server/requirements.txt
app/src/main/java/org/techtown/hello/MainActivity.java
store_app/src/main/java/org/techtown/storeitem/MainActivity.java
doc/report/api_spec.md
doc/report/current_development_status.md
```
