# Hello 프로젝트 작업 정리 보고서

작성일: 2026-05-19  
최종 업데이트: 2026-05-27  
프로젝트 경로: `C:\Users\dbals\AndroidStudioProjects\Hello`

## 1. 작업 목적

기존 Android 예제 앱을 기획 자료의 방향에 맞춰 `서랍 내 물건찾기` 시스템으로 구현했다. Temi 로봇 앱과 휴대폰 앱이 같은 DB를 공유하여 물건 위치를 검색/등록할 수 있는 구조다.

참고 자료:

- `doc\usr\서랍 내 물건찾기 ui 기획안.pptx`
- `doc\usr\temi 순서도.png`

## 2. 시스템 구조

```text
Temi 앱 (172.17.65.250)  ──┐
                            ├──  HTTP (JSON)  ──  FastAPI 서버 (172.17.67.210:8000)
휴대폰 앱 (다른 노트북)    ──┘                         │
                                                   sqlite3
                                                       │
                                                SQLite DB (inventory.db)
```

- FastAPI + SQLite 공용 API 서버를 중심으로 Temi 앱과 휴대폰 앱이 동일 데이터를 사용
- 앱 내부 DB 없이 모든 데이터를 API를 통해 조회/등록

## 3. 현재 구현 상태

### 3.1 DB 테이블 (6개)

| 테이블 | 역할 | 상태 |
| --- | --- | --- |
| `user` | 사용자/가족 구성원 | 구현됨 |
| `storage_location` | 서랍/보관 위치 + LED 채널 | 구현됨 |
| `item` | 물건 정보 (이름, 상태) | 구현됨 |
| `item_placement` | 물건↔위치 매핑 (수량, user_id) | 구현됨 |
| `shopping_list` | 구매 필요 품목 (수량, 상태, user_id) | 구현됨 |
| `sensor_check` | 센서 감지 기록 | 구현됨 |

### 3.2 API 엔드포인트 (12개)

| 메서드 | 경로 | 용도 |
| --- | --- | --- |
| GET | `/api/health` | 서버 상태 확인 |
| GET | `/api/users` | 사용자 목록 |
| POST | `/api/users` | 사용자 생성 |
| GET | `/api/users/{id}` | 사용자 조회 |
| GET | `/api/items/search` | 물건 검색 |
| POST | `/api/items` | 물건 등록 |
| POST | `/api/placements` | 물건 위치 등록 (통합) |
| POST | `/api/sensor-checks` | 센서 기록 등록 |
| GET | `/api/shopping-list` | 쇼핑 목록 조회 |
| POST | `/api/shopping-list` | 쇼핑 항목 추가 |
| PUT | `/api/shopping-list/{id}` | 쇼핑 항목 상태 변경 |
| DELETE | `/api/shopping-list/{id}` | 쇼핑 항목 삭제 |

### 3.3 Temi 앱 화면 (5개)

| 화면 | 상수 | 설명 |
| --- | --- | --- |
| 홈 | `PAGE_HOME` | 3개 메뉴 카드 (물건 찾기, 물건 등록, DB 상태) |
| 물건 찾기 | `PAGE_FIND` | 검색 입력 + 샘플 버튼 3개 |
| 검색 결과 | `PAGE_RESULT` | 서랍/LED/수량 표시 + 음성 안내 |
| 물건 등록 | `PAGE_REGISTER` | 3개 필드 (물건명, 서랍 번호, 수량) |
| DB 상태 | `PAGE_DB_STATUS` | API 연결 확인 + 주소 표시 |

### 3.4 휴대폰 앱

- 다른 노트북에서 개발 중
- 같은 API 서버에 연결되어 DB 공유 확인 완료

## 4. 주요 변경 내역 (2026-05-27)

### 4.1 DB 정리

- `item`, `shopping_list` 테이블에서 `category` 컬럼 삭제
- 테스트 데이터 정리 (remote, 마우스, 메모지 등 삭제)
- `item.status='needed'`였던 항목을 `shopping_list`로 이관 후 삭제

### 4.2 테이블 추가

- `user` 테이블 생성 (이름, 생성일)
- `shopping_list` 테이블 생성 (이름, 수량, 상태, user_id, 메모)
- `item_placement`, `shopping_list`에 `user_id` FK 추가

### 4.3 Temi 등록 폼 간소화

이전 등록 폼 (6개 필드):
```text
1행: [물건명] [위치 이름]
2행: [서랍 번호] [LED 번호] [수량]
3행: [안내 문구]
```

현재 등록 폼 (3개 필드):
```text
1행: [물건명]
2행: [서랍 번호] [수량]
```

자동 생성 규칙:
- `led_channel` = `drawer_number` (항상 동일)
- `location_name` = 서랍번호 + "번 서랍"
- `description` = location_name과 동일

### 4.4 API 수정

- 모든 엔드포인트에서 `category` 파라미터 제거
- `PATCH` → `PUT` 변경 (Android HttpURLConnection 호환)
- `user` CRUD 엔드포인트 추가
- `shopping_list` CRUD 엔드포인트 추가

## 5. 수정된 주요 파일

| 파일 | 변경 내용 |
| --- | --- |
| `api_server/main.py` | category 제거, user/shopping_list 엔드포인트 추가, PATCH→PUT |
| `api_server/schema.sql` | user 테이블 추가, category 제거, user_id FK 추가 |
| `MainActivity.java` | 등록 폼 3개 필드로 간소화, LED/위치/안내 자동 생성 |

## 6. 검증 결과

### 6.1 Temi 실기기 (172.17.65.250:5555)

- APK 빌드 → 설치 → 실행 성공
- 홈 화면: 3개 메뉴 카드 정상 표시
- 물건 등록: 3개 필드 폼 정상 동작 (스크린샷 확인 완료)
- 물건 찾기: API 검색 정상 동작
- DB 상태: 연결 성공 표시

### 6.2 API 서버

- 서버 시작 시 6개 테이블 자동 생성
- 샘플 데이터 3건 자동 삽입 (리모컨, 약, 충전기)
- 모든 엔드포인트 정상 동작

## 7. 현재 한계 및 알려진 이슈

1. **SQLite 단일 파일 DB**: 프로토타입용. 동시 접속이 많아지면 전환 필요
2. **HTTP 평문 통신**: `usesCleartextTraffic="true"` 사용 중
3. **API 주소 하드코딩**: 노트북 IP 변경 시 소스 수정 필요
4. **음성 인식 미연동**: 버튼만 있고 Toast만 표시
5. **LED/서랍 하드웨어 미연동**: 화면 텍스트로만 표현
6. **인증 없음**: API는 누구나 호출 가능

## 8. 앞으로 해야 할 일

### 단기 (프로토타입 완성)

| 순번 | 작업 | 설명 |
| --- | --- | --- |
| 1 | 검색 기록 테이블 추가 | `search_log` — 검색 키워드, 시간, 검색 기기 기록 |
| 2 | 물건 꺼냄/반납 기록 테이블 추가 | `item_checkout` — 물건 이용 이력 관리 |
| 3 | 휴대폰 앱 UI를 현재 DB 구조에 맞춤 | category 제거, user_id 연동 |
| 4 | API 주소 설정 파일 분리 | 앱 내 하드코딩 제거 |

### 중기 (기능 고도화)

| 순번 | 작업 | 설명 |
| --- | --- | --- |
| 5 | 음성 인식 연동 | Android SpeechRecognizer 또는 Temi 음성 API |
| 6 | LED/서랍 하드웨어 연동 | 서랍 제어 장치 통신 인터페이스 정의 |
| 7 | 사진 분석 기능 | 사진 촬영 → AI 분석 → 물건 자동 인식 |
| 8 | Temi 이동 연동 | 검색 결과에서 Temi가 해당 서랍으로 이동 |

### 장기 (운영 준비)

| 순번 | 작업 | 설명 |
| --- | --- | --- |
| 9 | HTTPS 적용 + API 인증 | 보안 강화 |
| 10 | SQLite → PostgreSQL | 동시 접속 대응 |
| 11 | 서버 배포 환경 구성 | Docker 또는 클라우드 |
| 12 | Gradle/AGP/SDK 업그레이드 | 최신 빌드 환경 대응 |

## 9. 관련 문서

- `doc\report\db_api_implementation_report.md` — DB/API 구현 상세 보고서
- `doc\report\project_analysis_report.md` — 프로젝트 분석 보고서
- `doc\report\build_error_resolution.md` — 빌드 에러 해결 보고서
- `doc\report\db_connection_next_steps.md` — DB 연동 다음 작업 메모
