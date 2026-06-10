# Temi Shared API Specification

작성일: 2026-05-31

이 문서는 가정용 Temi 앱, 핸드폰 앱, 센서 장치, 매장 Temi가 공유할 API 계약을 정의한다. 현재 구현된 API와 앞으로 구현할 API를 함께 정리한다.

## 1. 기본 규칙

### 1.1 Base URL

개발 서버:

```text
http://{SERVER_IP}:8000
```

Android Emulator에서 같은 노트북 서버로 접속할 때:

```text
http://10.0.2.2:8000
```

다른 노트북이나 실제 기기에서 접속할 때는 API 서버가 실행 중인 노트북의 Wi-Fi IP를 사용한다.

### 1.2 공통 형식

- 요청/응답 본문은 `application/json; charset=UTF-8`을 사용한다.
- 시간 값은 서버 DB의 `CURRENT_TIMESTAMP` 기준 문자열을 사용한다.
- id 필드는 SQLite `AUTOINCREMENT` 정수 id를 사용한다.
- 아직 인증은 적용하지 않는다.

### 1.3 공통 에러 응답

```json
{
  "detail": "Error message"
}
```

주요 HTTP 상태:

```text
200 OK
201 Created
400 Bad Request
404 Not Found
409 Conflict
422 Validation Error
500 Internal Server Error
```

## 2. 현재 구현된 API

### 2.1 Health Check

```text
GET /api/health
```

응답:

```json
{
  "status": "ok"
}
```

### 2.2 사용자 목록 조회

```text
GET /api/users
```

응답:

```json
{
  "users": [
    {
      "id": 1,
      "name": "아빠",
      "created_at": "2026-05-31 12:00:00"
    }
  ]
}
```

### 2.3 사용자 생성

```text
POST /api/users
```

요청:

```json
{
  "name": "아빠"
}
```

응답:

```json
{
  "id": 1,
  "name": "아빠"
}
```

### 2.4 사용자 단건 조회

```text
GET /api/users/{user_id}
```

응답:

```json
{
  "id": 1,
  "name": "아빠",
  "created_at": "2026-05-31 12:00:00"
}
```

### 2.5 물건 검색

가정용 Temi 앱이 서랍에 보관된 물건을 찾을 때 사용한다.

```text
GET /api/items/search?name={item_name}
```

응답, 찾음:

```json
{
  "found": true,
  "name": "볼펜",
  "status": "available",
  "quantity": 3,
  "location": "1번 서랍",
  "drawer_number": 1,
  "led_channel": 1
}
```

응답, 없음:

```json
{
  "found": false,
  "name": "볼펜"
}
```

### 2.6 물건 생성

```text
POST /api/items
```

요청:

```json
{
  "name": "볼펜",
  "status": "available"
}
```

응답:

```json
{
  "id": 1,
  "name": "볼펜"
}
```

### 2.7 물건 보관 위치 등록

핸드폰 앱 또는 Temi 앱이 원격으로 물건을 서랍에 등록할 때 사용한다.

```text
POST /api/placements
```

요청:

```json
{
  "item_name": "볼펜",
  "drawer_number": 1,
  "quantity": 3
}
```

서버 처리:

```text
1. item.name이 없으면 생성
2. storage_location이 없으면 "{drawer_number}번 서랍"으로 생성
3. led_channel은 기본적으로 drawer_number와 같게 저장
4. item_placement에 수량 저장
```

응답:

```json
{
  "item_id": 1,
  "storage_location_id": 1
}
```

주의:

```text
같은 item + 같은 drawer면 현재 수량은 덮어쓴다.
예: 볼펜 3개 등록 후 볼펜 5개를 같은 서랍에 등록하면 최종 수량은 5개다.
```

### 2.8 센서 간단 확인 로그

현재 임시 센서 확인용 API다. 최종 센서 흐름은 `storage_event` API를 사용한다.

```text
POST /api/sensor-checks
```

요청:

```json
{
  "item_name": "볼펜",
  "detected": true,
  "note": "테스트 감지"
}
```

응답:

```json
{
  "id": 1
}
```

### 2.9 구버전 쇼핑리스트 API

아래 API는 기존 단순 쇼핑리스트용이다. 신규 가족 취합 시나리오는 5장을 사용한다.

```text
GET    /api/shopping-list
POST   /api/shopping-list
PUT    /api/shopping-list/{item_id}
DELETE /api/shopping-list/{item_id}
```

## 3. 사진 인식 및 물건 번호 API

상태: 예정

### 3.1 사진 인식 세션 생성

핸드폰 앱이 사진을 업로드했거나, LLM 인식 결과를 저장하기 전 작업 단위를 만든다.

```text
POST /api/recognitions
```

요청:

```json
{
  "user_id": 1,
  "image_path": "uploads/recognitions/20260531_001.jpg",
  "llm_model": "gpt-4.1",
  "raw_response": "{...}"
}
```

응답:

```json
{
  "id": 1,
  "status": "uploaded"
}
```

DB:

```text
recognition_session
```

### 3.2 인식 세션 조회

```text
GET /api/recognitions/{recognition_id}
```

응답:

```json
{
  "id": 1,
  "user_id": 1,
  "image_path": "uploads/recognitions/20260531_001.jpg",
  "llm_model": "gpt-4.1",
  "status": "detected",
  "created_at": "2026-05-31 12:00:00"
}
```

### 3.3 인식 물건 목록 저장

LLM이 인식한 물건들을 번호 순서대로 저장한다. 같은 물건 3개도 각각 별도 번호로 저장한다.

```text
POST /api/recognitions/{recognition_id}/items
```

요청:

```json
{
  "items": [
    {
      "sequence_no": 1,
      "item_name": "볼펜",
      "confidence": 0.92
    },
    {
      "sequence_no": 2,
      "item_name": "볼펜",
      "confidence": 0.89
    },
    {
      "sequence_no": 3,
      "item_name": "면도기",
      "confidence": 0.95
    }
  ]
}
```

응답:

```json
{
  "recognition_id": 1,
  "items": [
    {
      "id": 1,
      "sequence_no": 1,
      "item_name": "볼펜",
      "status": "detected"
    },
    {
      "id": 2,
      "sequence_no": 2,
      "item_name": "볼펜",
      "status": "detected"
    },
    {
      "id": 3,
      "sequence_no": 3,
      "item_name": "면도기",
      "status": "detected"
    }
  ]
}
```

DB:

```text
recognized_item
```

### 3.4 인식 물건 목록 조회

```text
GET /api/recognitions/{recognition_id}/items
```

응답:

```json
{
  "items": [
    {
      "id": 1,
      "sequence_no": 1,
      "item_name": "볼펜",
      "confidence": 0.92,
      "status": "detected"
    }
  ]
}
```

### 3.5 인식 물건 수정

사용자가 LLM 결과를 수정할 때 사용한다.

```text
PUT /api/recognized-items/{recognized_item_id}
```

요청:

```json
{
  "item_name": "검정 볼펜",
  "status": "corrected"
}
```

응답:

```json
{
  "id": 1,
  "item_name": "검정 볼펜",
  "status": "corrected"
}
```

## 4. 번호 순서 보관 및 센서 API

상태: 예정

### 4.1 보관 세션 시작

사진 인식 결과를 바탕으로 사용자가 1번부터 순서대로 물건을 서랍에 넣기 시작한다.

```text
POST /api/storage-sessions
```

요청:

```json
{
  "recognition_session_id": 1
}
```

응답:

```json
{
  "id": 1,
  "recognition_session_id": 1,
  "current_sequence_no": 1,
  "status": "in_progress"
}
```

DB:

```text
storage_session
```

### 4.2 현재 보관 순번 변경

핸드폰 앱 또는 Temi 앱이 “지금 N번 물건을 넣는 중”이라고 서버에 알린다.

```text
PUT /api/storage-sessions/{storage_session_id}/current-item
```

요청:

```json
{
  "current_sequence_no": 2
}
```

응답:

```json
{
  "id": 1,
  "current_sequence_no": 2
}
```

### 4.3 센서 이벤트 저장

ESP32, 센서 중계 서버, Temi 앱이 서랍/로드셀/상단 카메라 이벤트를 서버에 보낸다.

### 4.3.1 Current Storage Item Lookup

```text
GET /api/storage-sessions/{storage_session_id}/current-item
```

Response:

```json
{
  "storage_session_id": 1,
  "recognition_session_id": 12,
  "current_sequence_no": 1,
  "status": "in_progress",
  "recognized_item_id": 49,
  "item_name": "body wash",
  "item_status": "detected",
  "confidence": 0.98
}
```

Sensor laptop polls this endpoint to know which recognized item is currently waiting for drawer/load-cell/switch events.

```text
POST /api/storage-events
```

리드 스위치, 서랍 열림:

```json
{
  "storage_session_id": 1,
  "recognized_item_id": 1,
  "drawer_number": 2,
  "sensor_type": "reed_switch",
  "event_type": "drawer_open"
}
```

로드셀, 무게 변화:

```json
{
  "storage_session_id": 1,
  "recognized_item_id": 1,
  "drawer_number": 2,
  "sensor_type": "load_cell",
  "event_type": "weight_changed",
  "weight_before": 120.5,
  "weight_after": 155.2,
  "weight_delta": 34.7
}
```

상단 카메라 촬영 이벤트:

```json
{
  "storage_session_id": 1,
  "recognized_item_id": 1,
  "drawer_number": 2,
  "sensor_type": "top_camera",
  "event_type": "camera_snapshot",
  "payload": "{\"image_path\":\"uploads/drawer/20260531_001.jpg\"}"
}
```

응답:

```json
{
  "id": 1
}
```

DB:

```text
storage_event
```

허용 sensor_type:

```text
reed_switch
load_cell
top_camera
system
```

허용 event_type:

```text
drawer_open
drawer_close
weight_before
weight_after
weight_changed
camera_snapshot
sequence_started
sequence_completed
verification_failed
```

### 4.4 상단 카메라 스냅샷 저장

서랍 위에 설치된 카메라가 찍은 이미지 경로를 저장한다.

```text
POST /api/drawer-camera-snapshots
```

요청:

```json
{
  "storage_session_id": 1,
  "recognized_item_id": 1,
  "drawer_number": 2,
  "image_path": "uploads/drawer/20260531_001.jpg",
  "note": "2번 서랍 투입 확인"
}
```

응답:

```json
{
  "id": 1
}
```

DB:

```text
drawer_camera_snapshot
```

### 4.5 보관 확정

현재 순번 물건과 센서 이벤트를 묶어서 최종적으로 “이 물건은 이 서랍에 보관됨”을 확정한다.

```text
POST /api/placement-verifications
```

요청:

```json
{
  "storage_session_id": 1,
  "recognized_item_id": 1,
  "drawer_number": 2,
  "open_event_id": 10,
  "weight_event_id": 11,
  "close_event_id": 12,
  "camera_snapshot_id": 3,
  "result": "success",
  "weight_delta": 34.7,
  "note": "서랍 열림, 무게 변화, 닫힘 확인"
}
```

서버 처리:

```text
1. recognized_item에서 item_name 확인
2. item에 같은 이름이 없으면 생성
3. storage_location에서 drawer_number에 맞는 위치 확인 또는 생성
4. placement_verification 저장
5. item_placement에 최종 보관 상태 반영
6. recognized_item.status를 stored로 변경
```

응답:

```json
{
  "id": 1,
  "item_id": 1,
  "storage_location_id": 2,
  "result": "success"
}
```

DB:

```text
placement_verification
item
storage_location
item_placement
recognized_item
```

## 5. 가족 쇼핑리스트 API

상태: 예정

### 5.1 쇼핑 세션 생성

가족들이 제출할 쇼핑 요청 묶음을 만든다.

```text
POST /api/shopping-sessions
```

요청:

```json
{
  "title": "이번 주 장보기",
  "created_by_user_id": 1
}
```

응답:

```json
{
  "id": 1,
  "title": "이번 주 장보기",
  "status": "open"
}
```

DB:

```text
shopping_session
```

### 5.2 가족별 쇼핑 요청 제출

핸드폰 앱에서 가족 구성원이 원하는 품목 리스트를 제출한다.

```text
POST /api/shopping-sessions/{session_id}/requests
```

요청:

```json
{
  "user_id": 2,
  "note": "학교 준비물",
  "items": [
    {
      "item_name": "볼펜",
      "quantity": 2,
      "note": "검정색"
    },
    {
      "item_name": "공책",
      "quantity": 3
    }
  ]
}
```

응답:

```json
{
  "request_id": 1,
  "item_count": 2
}
```

DB:

```text
shopping_request
shopping_request_item
```

### 5.3 쇼핑 요청 취합 및 집 보유품 비교

가족별 요청을 하나로 합치고, 집 DB에 있는 물건과 비교한다.

```text
POST /api/shopping-sessions/{session_id}/compare
```

요청:

```json
{}
```

서버 처리:

```text
1. shopping_request_item을 item_name 기준으로 합산
2. item/item_placement에서 집 보유 수량 확인
3. 집에 충분히 있으면 status = owned
4. 일부만 있으면 status = partial
5. 없으면 status = need_to_buy
6. 집에 있는 물건은 storage_location_id와 led_channel 저장
```

응답:

```json
{
  "session_id": 1,
  "results": [
    {
      "item_name": "볼펜",
      "requested_quantity": 2,
      "owned_quantity": 3,
      "need_to_buy_quantity": 0,
      "status": "owned",
      "drawer_number": 1,
      "led_channel": 1
    },
    {
      "item_name": "공책",
      "requested_quantity": 3,
      "owned_quantity": 0,
      "need_to_buy_quantity": 3,
      "status": "need_to_buy"
    }
  ]
}
```

DB:

```text
shopping_result_item
```

### 5.4 쇼핑 비교 결과 조회

```text
GET /api/shopping-sessions/{session_id}/results
```

응답:

```json
{
  "session_id": 1,
  "items": [
    {
      "item_name": "볼펜",
      "requested_quantity": 2,
      "owned_quantity": 3,
      "need_to_buy_quantity": 0,
      "status": "owned",
      "drawer_number": 1,
      "led_channel": 1
    }
  ]
}
```

### 5.5 집에 있는 물건 LED 안내 목록 조회

가정용 Temi가 집에 이미 있는 물건의 서랍 LED를 켜기 위해 사용한다.

```text
GET /api/shopping-sessions/{session_id}/owned-items
```

응답:

```json
{
  "items": [
    {
      "item_name": "볼펜",
      "owned_quantity": 3,
      "drawer_number": 1,
      "led_channel": 1
    }
  ]
}
```

### 5.6 최종 쇼핑리스트 생성

없는 물건과 부족한 수량만 최종 쇼핑리스트로 만든다.

```text
POST /api/shopping-sessions/{session_id}/finalize
```

요청:

```json
{
  "title": "이번 주 장보기 최종 리스트"
}
```

응답:

```json
{
  "final_list_id": 1,
  "items": [
    {
      "item_name": "공책",
      "quantity": 3,
      "status": "pending"
    }
  ]
}
```

DB:

```text
final_shopping_list
final_shopping_list_item
```

### 5.7 최종 쇼핑리스트 조회

```text
GET /api/final-shopping-lists/{final_list_id}
```

응답:

```json
{
  "id": 1,
  "session_id": 1,
  "title": "이번 주 장보기 최종 리스트",
  "status": "ready",
  "items": [
    {
      "id": 1,
      "item_name": "공책",
      "quantity": 3,
      "status": "pending"
    }
  ]
}
```

## 6. 매장 Temi API

상태: 예정

### 6.1 매장 등록

```text
POST /api/stores
```

요청:

```json
{
  "name": "이마트 죽전점",
  "store_type": "mart",
  "address": "경기도 용인시 ...",
  "temi_device_id": "temi-store-001"
}
```

응답:

```json
{
  "id": 1,
  "name": "이마트 죽전점"
}
```

DB:

```text
store
```

### 6.2 매장 구역 등록

```text
POST /api/stores/{store_id}/sections
```

요청:

```json
{
  "name": "문구 코너",
  "aisle": "A3",
  "shelf": "2",
  "floor": 1,
  "map_x": 12.5,
  "map_y": 8.0
}
```

응답:

```json
{
  "id": 1,
  "name": "문구 코너"
}
```

DB:

```text
store_section
```

### 6.3 매장 상품 등록 또는 갱신

```text
POST /api/stores/{store_id}/products
```

요청:

```json
{
  "item_name": "공책",
  "store_section_id": 1,
  "in_stock": true,
  "stock_quantity": 30,
  "price": 1500
}
```

응답:

```json
{
  "id": 1,
  "item_name": "공책",
  "in_stock": true
}
```

DB:

```text
store_product
```

### 6.4 최종 쇼핑리스트를 매장 Temi로 전송

```text
POST /api/final-shopping-lists/{final_list_id}/transfer
```

요청:

```json
{
  "store_id": 1
}
```

서버 처리:

```text
1. final_shopping_list_item 조회
2. store_product와 비교해 매장 재고/위치 확인
3. shopping_list_transfer 저장
4. final_shopping_list.status = sent_to_store
```

응답:

```json
{
  "transfer_id": 1,
  "store_id": 1,
  "status": "sent",
  "items": [
    {
      "item_name": "공책",
      "quantity": 3,
      "in_stock": true,
      "section": "문구 코너",
      "aisle": "A3",
      "shelf": "2"
    }
  ]
}
```

DB:

```text
shopping_list_transfer
```

### 6.5 매장 Temi 전송 목록 조회

매장 Temi가 자신에게 전송된 쇼핑리스트를 가져간다.

```text
GET /api/stores/{store_id}/transfers/{transfer_id}
```

응답:

```json
{
  "transfer_id": 1,
  "final_list_id": 1,
  "store_id": 1,
  "items": [
    {
      "item_name": "공책",
      "quantity": 3,
      "in_stock": true,
      "section": "문구 코너",
      "aisle": "A3",
      "shelf": "2",
      "map_x": 12.5,
      "map_y": 8.0
    }
  ]
}
```

### 6.6 매장 안내 세션 생성

매장 Temi가 받은 쇼핑리스트를 바탕으로 안내 경로를 만든다.

```text
POST /api/transfers/{transfer_id}/navigation
```

요청:

```json
{
  "store_id": 1
}
```

서버 처리:

```text
1. 전송된 최종 쇼핑리스트 조회
2. store_product, store_section 기준으로 위치 확인
3. step_order를 만들어 store_navigation_step 저장
```

응답:

```json
{
  "navigation_session_id": 1,
  "steps": [
    {
      "step_order": 1,
      "item_name": "공책",
      "instruction": "문구 코너 A3 통로 2번 선반으로 이동하세요.",
      "map_x": 12.5,
      "map_y": 8.0
    }
  ]
}
```

DB:

```text
store_navigation_session
store_navigation_step
```

### 6.7 매장 안내 단계 조회

```text
GET /api/navigation-sessions/{navigation_session_id}/steps
```

응답:

```json
{
  "navigation_session_id": 1,
  "steps": [
    {
      "id": 1,
      "step_order": 1,
      "item_name": "공책",
      "instruction": "문구 코너 A3 통로 2번 선반으로 이동하세요.",
      "status": "pending"
    }
  ]
}
```

### 6.8 매장 안내 단계 상태 변경

```text
PUT /api/navigation-steps/{step_id}
```

요청:

```json
{
  "status": "picked"
}
```

응답:

```json
{
  "id": 1,
  "status": "picked"
}
```

## 7. 주요 시나리오별 호출 순서

### 7.1 사진 인식 기반 물건 보관

```text
1. POST /api/recognitions
2. POST /api/recognitions/{id}/items
3. POST /api/storage-sessions
4. PUT  /api/storage-sessions/{id}/current-item
5. POST /api/storage-events              # drawer_open
6. POST /api/storage-events              # weight_changed
7. POST /api/storage-events              # drawer_close
8. POST /api/drawer-camera-snapshots     # optional
9. POST /api/placement-verifications
```

### 7.2 가족 쇼핑리스트 취합

```text
1. POST /api/shopping-sessions
2. POST /api/shopping-sessions/{id}/requests  # 가족 A
3. POST /api/shopping-sessions/{id}/requests  # 가족 B
4. POST /api/shopping-sessions/{id}/compare
5. GET  /api/shopping-sessions/{id}/owned-items
6. POST /api/shopping-sessions/{id}/finalize
```

### 7.3 매장 Temi 안내

```text
1. POST /api/final-shopping-lists/{id}/transfer
2. GET  /api/stores/{store_id}/transfers/{transfer_id}
3. POST /api/transfers/{transfer_id}/navigation
4. GET  /api/navigation-sessions/{id}/steps
5. PUT  /api/navigation-steps/{step_id}
```

## 8. 구현 우선순위

1. 사진 인식 결과 저장 API
2. 보관 세션 및 센서 이벤트 API
3. 보관 확정 API
4. 가족 쇼핑 요청 제출 API
5. 쇼핑 요청 취합 및 보유품 비교 API
6. 최종 쇼핑리스트 생성 API
7. 매장 Temi 전송 및 안내 API
