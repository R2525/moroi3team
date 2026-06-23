# DB 연동 다음 작업 메모

작성일: 2026-05-26

## 다음에 다시 설명해야 할 핵심

폰 앱과 Temi 앱이 같은 DB를 써야 하므로, 앱이 DB에 직접 접속하는 방식은 피한다.

권장 구조:

```text
폰 앱
Temi 앱
센서/서랍 장치
    ↓
API 서버
    ↓
공용 DB
```

## 이유

- 앱에 DB 계정/비밀번호를 넣으면 보안상 위험하다.
- 폰과 Temi가 각각 로컬 DB를 가지면 데이터가 서로 달라진다.
- 사진 분석, 센서 검증, Temi 검색, 쇼핑 리스트 생성을 한 곳에서 관리하려면 서버 API가 필요하다.

## 추천 구현 순서

1. `doc\usr\물건관리_DB_ERD_간단.png` 기준으로 테이블 확정
2. `CREATE TABLE` SQL 작성
3. API 서버 생성
   - 빠른 프로토타입: FastAPI
   - Android/Spring 경험이 있으면 Spring Boot
4. 서버 DB 연결
   - 프로토타입: SQLite 또는 PostgreSQL
   - 장기 운영: PostgreSQL 권장
5. 폰 앱 API 연동
   - 사진 업로드
   - AI 분석 결과 저장
   - 물건 확정
6. Temi 앱 API 연동
   - 물건명 검색
   - 위치/LED 채널 조회
7. 센서 API 연동
   - 물건 수납 감지
   - 위치 검증 결과 저장

## 최소 API 목록

```text
POST /api/photos/analyze
POST /api/items
POST /api/placements
POST /api/sensor-checks
GET  /api/items/search?name=...
GET  /api/shopping-list
```

## 핵심 테이블

```text
USER
PHOTO_ANALYSIS
ITEM
STORAGE_LOCATION
ITEM_PLACEMENT
SENSOR_CHECK
SHOPPING_LIST_ITEM
```

## 다음 작업 시작 문장

사용자가 DB 연동을 다시 물어보면 다음 방향으로 안내한다.

> 이 프로젝트는 폰 앱과 Temi 앱이 같은 데이터를 써야 하니까, 앱이 DB에 직접 붙지 말고 API 서버를 하나 두는 방식으로 가야 합니다. 먼저 간단 ERD 기준으로 SQL 테이블을 만들고, 그 다음 API 서버를 만든 뒤 Android 앱에서는 Retrofit으로 API를 호출하게 연결하면 됩니다.
