# Sensor Integration Progress Report

작성일: 2026-06-09

프로젝트 경로: `C:\Users\dbals\AndroidStudioProjects\Hello`

## 1. 현재 서버 상태

FastAPI 서버는 현재 `8000` 포트에서 실행 중이다.

```text
0.0.0.0:8000 LISTENING
```

현재 Wi-Fi 기준 외부 접속 주소:

```text
http://10.136.55.25:8000
```

상태 확인 API:

```text
GET http://10.136.55.25:8000/api/health
```

정상 응답:

```json
{"status": "ok"}
```

## 2. Android 가정용 Temi UI 진행상황

대상 파일:

```text
app/src/main/java/org/techtown/hello/MainActivity.java
```

완료한 UI 정리:

- 물건찾기 화면의 샘플 버튼 제거
  - `리모컨`
  - `약`
  - `충전기`
- 물건등록 화면의 `예시 채우기` 버튼 제거
- 앱 전체의 회색 부가 설명 문구 제거
  - 페이지 제목 아래 subtitle 제거
  - 홈 메뉴 카드 설명 제거
  - 하단 안내 문구 제거
- 홈 화면 메뉴는 제목 중심으로 단순화
  - 서랍 속 물건 찾기
  - 물건 등록
  - DB 상태

최근 확인:

```text
빌드 성공
에뮬레이터 설치 성공
org.techtown.hello/.MainActivity 실행 확인
```

## 3. 센서 노트북 연동 방향

센서 노트북 IP는 현재 구조에서 필요하지 않다.

권장 구조:

```text
센서 노트북 -> 내 FastAPI 서버 조회
센서 노트북 -> 센서 이벤트를 내 FastAPI 서버로 POST
내 서버 -> DB 저장
```

서버가 센서 노트북으로 직접 push하지 않고, 센서 노트북이 서버를 polling하는 구조로 진행한다.

이 방식의 장점:

- 센서 노트북의 IP를 몰라도 됨
- 방화벽/NAT 문제 감소
- 센서 노트북 프로그램이 단순해짐
- 서버가 전체 보관 상태를 DB 기준으로 관리 가능

## 4. 새로 추가한 API

대상 파일:

```text
api_server/main.py
```

추가 API:

```text
GET /api/storage-sessions/{storage_session_id}/current-item
```

용도:

센서 노트북이 현재 어떤 물건을 서랍에 넣는 중인지 조회한다.

현재 테스트 URL:

```text
GET http://10.136.55.25:8000/api/storage-sessions/1/current-item
```

현재 응답 예시:

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

현재 DB 상태:

```text
storage_session id=1
recognition_session_id=12
current_sequence_no=1
status=in_progress
```

## 5. 센서 이벤트 수신 확인

센서 노트북에서 `/api/sensor-checks`로 보낸 데이터가 DB에 저장된 것을 확인했다.

최근 수신 데이터:

```text
id=2
item_name=arduino_uno_q_live_sensor_status
detected=0
note=camera_status=capture failed; yolo=running 2.27fps 0 boxes; loadcell_status=connected RouterBridge; loadcell_value=0.0; magnet=NOT_DETECTED
created_at=2026-06-09 11:32:59
```

이전 테스트 데이터:

```text
id=1
item_name=arduino_uno_q_connection_test
detected=1
note=UNO Q send/receive test: camera=/dev/video8, loadcell=0.0, magnet=NOT_DETECTED, yolo=fist/open
created_at=2026-06-09 11:32:31
```

판단:

```text
네트워크 연결: 정상
서버 POST 수신: 정상
DB 저장: 정상
카메라 캡처: 실패 상태
YOLO: 실행 중이지만 감지 박스 0개
로드셀: 연결됨
자석 센서: 미감지 상태
```

## 6. 센서 노트북이 사용할 API 흐름

### 6.1 현재 물건 조회

```text
GET http://10.136.55.25:8000/api/storage-sessions/1/current-item
```

센서 노트북은 응답에서 아래 값을 사용한다.

```text
storage_session_id
recognized_item_id
current_sequence_no
item_name
```

### 6.2 서랍/로드셀 이벤트 전송

```text
POST http://10.136.55.25:8000/api/storage-events
```

로드셀 이벤트 예시:

```json
{
  "storage_session_id": 1,
  "recognized_item_id": 49,
  "drawer_number": 2,
  "sensor_type": "load_cell",
  "event_type": "weight_changed",
  "weight_before": 0.0,
  "weight_after": 125.3,
  "weight_delta": 125.3
}
```

스위치 이벤트 예시:

```json
{
  "storage_session_id": 1,
  "recognized_item_id": 49,
  "drawer_number": 2,
  "sensor_type": "reed_switch",
  "event_type": "drawer_open"
}
```

### 6.3 최종 보관 확정

센서 이벤트를 받은 뒤, 현재 물건이 특정 서랍에 들어갔다고 확정할 때 사용한다.

```text
POST http://10.136.55.25:8000/api/placement-verifications
```

예시:

```json
{
  "storage_session_id": 1,
  "recognized_item_id": 49,
  "drawer_number": 2,
  "result": "success",
  "weight_delta": 125.3,
  "note": "load cell and drawer switch confirmed"
}
```

## 7. 다음 작업

우선순위:

1. 센서 노트북에서 `GET /api/storage-sessions/1/current-item` 호출 확인
2. 센서 노트북에서 실제 로드셀/스위치 이벤트를 `/api/storage-events`로 전송
3. 서버에서 이벤트 수신 후 `placement_verifications`까지 호출하는 흐름 테스트
4. 물건 하나 저장 완료 후 다음 물건으로 넘어가는 API 호출 연결

다음 물건으로 넘어가는 API:

```text
PUT http://10.136.55.25:8000/api/storage-sessions/1/current-item
```

요청 예시:

```json
{
  "current_sequence_no": 2
}
```

5. Android 가정용 Temi 앱에서 보관 세션 시작/다음 물건 이동 UI 연결
6. 시연 전 DB 테스트 데이터 정리

## 8. 현재 결론

센서 노트북과 서버 간 통신은 이미 성공했다.

현재 남은 핵심은 센서 노트북 프로그램이 아래 순서대로 동작하도록 맞추는 것이다.

```text
current-item 조회
-> 센서 변화 감지
-> storage-events 전송
-> placement-verifications 전송 또는 서버/앱에서 확정 처리
-> current_sequence_no 증가
-> 다음 물건 반복
```
