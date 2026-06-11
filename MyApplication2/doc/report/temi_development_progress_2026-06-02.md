# Temi 스마트 수납/쇼핑 앱 개발 진행 보고서

작성일: 2026-06-02  
프로젝트: `MyApplication2` Android 앱  
대상 서버: `http://172.17.76.159:8000`

## 1. 작업 목적

오늘 작업의 목표는 기획안의 핵심 흐름인 "집 Temi가 보유 물품과 쇼핑 요청을 비교하고, 필요한 물건만 매장 Temi로 전송하는 구조"를 앱과 DB 서버에 실제로 연결하는 것이었다. 특히 사용자가 입력한 쇼핑리스트와 수납 물품 정보를 DB 서버에 저장하고, Gemini 사진 분석 결과를 수납 추가 화면에 반영하는 기능을 구현했다.

## 2. DB 서버 연결

앱의 기본 API 서버 주소를 옆 분 서버인 `http://172.17.76.159:8000`으로 설정했다.

확인한 서버 상태:

```json
{
  "status": "ok"
}
```

사용한 주요 API는 다음과 같다.

- `GET /api/health`
- `POST /api/placements`
- `POST /api/shopping-list`
- `POST /api/shopping-sessions`
- `POST /api/shopping-sessions/{session_id}/requests`
- `POST /api/shopping-sessions/{session_id}/compare`
- `POST /api/shopping-sessions/{session_id}/finalize`
- `POST /api/final-shopping-lists/{final_list_id}/transfer`
- `POST /api/photos/analyze`

## 3. 수납 추가 기능

수납추가 화면에서 수동 추가와 사진 분석 기반 추가를 함께 사용할 수 있도록 구성했다.

구현 내용:

- 기존 DB 조회 화면에 있던 `DB 서버 저장`, `물품명`, `개수`, `서랍 번호` 입력 UI를 수납추가 화면으로 이동했다.
- 사용자가 물품명, 개수, 서랍 번호를 입력하면 `POST /api/placements`로 실제 DB 서버에 저장되도록 했다.
- 저장 성공 시 앱 내부 수납 목록과 DB 목록에도 반영되도록 했다.
- 저장 실패 시 실패 메시지를 표시하도록 했다.

수납 저장 요청 예시:

```json
{
  "item_name": "shampoo",
  "quantity": 1,
  "drawer_number": 1
}
```

## 4. Gemini 사진 분석 연동

Gemini API 키는 앱에 넣지 않고, 앱은 사진 파일만 서버에 전송하도록 구현했다.

사진 분석 API:

```text
POST /api/photos/analyze
multipart/form-data
image = 사진 파일
```

구현 내용:

- 갤러리에서 사진을 선택할 수 있게 했다.
- `분석` 버튼을 누르면 선택한 사진을 `image` 필드로 multipart POST 전송한다.
- 서버가 Gemini로 분석한 결과를 받아 `Gemini 분석 결과` 카드에 표시한다.
- 첫 번째 분석 결과의 물품명을 `물품명` 입력칸에 자동 입력한다.
- Gemini가 분석한 개수도 `개수` 입력칸에 자동 입력한다.

수량 처리 방식:

- 서버 응답에 `summary`가 있으면 `summary[].count`를 우선 사용한다.
- `summary`가 없으면 `items` 배열에서 같은 물품명을 묶어 개수를 합산한다.
- 수량 필드가 없으면 기본값 `1`을 사용한다.

아이스크림바 5개 분석 응답 예시:

```json
{
  "total_count": 5,
  "summary": [
    {
      "item_name": "ice cream bar",
      "count": 5
    }
  ],
  "items": [
    { "item_name": "ice cream bar" },
    { "item_name": "ice cream bar" },
    { "item_name": "ice cream bar" },
    { "item_name": "ice cream bar" },
    { "item_name": "ice cream bar" }
  ]
}
```

이 경우 화면에는 다음처럼 자동 입력된다.

- 물품명: `ice cream bar`
- 개수: `5`

## 5. 쇼핑리스트 입력 기능

쇼핑리스트 화면에서 사용자별로 구매하려는 물건과 개수를 입력할 수 있도록 했다.

구현 내용:

- 사용자 버튼을 선택할 수 있도록 했다.
- 사용자 이름 수정 기능을 추가했다.
- 물품명과 개수를 입력해 쇼핑리스트에 추가할 수 있도록 했다.
- 추가된 물품은 `POST /api/shopping-list`로 DB 서버에 저장되도록 했다.
- 삭제 버튼을 통해 쇼핑리스트 항목을 삭제할 수 있도록 했다.

서버 사용자 ID 문제도 수정했다. 기존에는 기본 사용자 ID를 `1`, `2`로 보내 서버에서 500 오류가 발생했으나, 현재는 기본 사용자는 `user_id: null`로 저장하고, 통합 리스트 생성 시 필요한 경우 서버 사용자 ID를 생성하거나 기존 ID를 조회하도록 했다.

## 6. 보유 물품 비교 및 "이미 보유" 처리

쇼핑리스트에 물건을 추가할 때, 수납 DB에 이미 충분한 수량이 있으면 바로 추가하지 않도록 했다.

적용 로직:

```text
구매 물품명 == 보유 물품명
그리고
구매 요청 개수 <= 보유 개수
이면
"이미 보유하고 있습니다" 표시
```

구현 내용:

- 이미 보유한 물품은 쇼핑리스트에 자동 추가되지 않는다.
- 화면 중앙에 `이미 보유하고 있습니다` 문구를 표시한다.
- 사용자가 그래도 구매하고 싶을 수 있으므로 `＋ 추가 구매` 버튼을 추가했다.
- `＋ 추가 구매`를 누르면 예외적으로 쇼핑리스트에 추가되고 DB 서버에도 저장된다.

## 7. 통합 쇼핑리스트 생성

기존 쇼핑리스트 화면에 있던 `리스트 이름 지정` 입력칸은 제거했다. 이제 `통합 리스트 생성` 버튼을 누른 뒤 별도 화면에서 리스트 이름을 지정하도록 변경했다.

변경된 흐름:

1. 쇼핑리스트 화면에서 사용자별 물품을 추가한다.
2. `통합 리스트 생성` 버튼을 누른다.
3. 새 화면에서 `리스트 이름 지정` 입력칸이 나타난다.
4. `통합 리스트 DB 저장` 버튼을 누른다.
5. DB 서버에 쇼핑 세션, 사용자별 요청, 비교 결과, 최종 리스트가 순서대로 저장된다.

서버 저장 흐름:

```text
POST /api/shopping-sessions
POST /api/shopping-sessions/{session_id}/requests
POST /api/shopping-sessions/{session_id}/compare
POST /api/shopping-sessions/{session_id}/finalize
```

중복 사용자 문제도 처리했다. 서버가 `409 User already exists`를 반환하면 앱이 `/api/users`에서 기존 사용자 ID를 찾아 이어서 저장하도록 수정했다.

## 8. 매장 Temi 전송 기능

통합 리스트를 만든 뒤 매장 Temi 화면에서 실제 전송할 수 있도록 했다.

구현 내용:

- DB에 저장된 통합 리스트를 매장 Temi 화면에 표시한다.
- 리스트를 선택할 수 있도록 했다.
- 선택한 리스트를 `POST /api/final-shopping-lists/{final_list_id}/transfer`로 전송한다.
- 매장 정보가 없으면 `POST /api/stores`로 `STORE-TEMI-03` 매장을 생성한 뒤 전송한다.

전송 성공 응답 예시:

```json
{
  "transfer_id": 1,
  "store_id": 1,
  "status": "sent"
}
```

매장 Temi 화면에는 두 종류의 목록을 구분해 보여주도록 했다.

- 사용자 요청 전체: 사용자가 추가한 모든 물품
- 최종 구매 리스트: 보유 물품과 비교 후 실제 구매해야 하는 물품

이렇게 분리한 이유는, 이미 보유한 물품이 최종 구매 리스트에서 제외되더라도 사용자가 무엇을 요청했는지는 확인할 수 있어야 하기 때문이다.

## 9. 에뮬레이터 테스트 이미지 추가

Gemini 사진 분석 기능을 테스트하기 위해 에뮬레이터에 이미지 파일을 추가했다.

샴푸 테스트 이미지:

```text
/sdcard/Pictures/temi_shampoo_demo.jpg
/sdcard/Download/temi_shampoo_demo.jpg
```

아이스크림바 테스트 이미지:

```text
/sdcard/Pictures/temi_icecream_bars_demo.jpg
/sdcard/Download/temi_icecream_bars_demo.jpg
```

파일 선택기에서 `Downloads`를 보고 있을 때 바로 선택할 수 있도록 Download 폴더에도 복사했다.

## 10. 검증 결과

확인한 항목:

- 앱 빌드 성공
- 에뮬레이터 APK 설치 성공
- DB 서버 health 확인 성공
- 수납 물품 저장 성공
- 쇼핑리스트 단일 항목 저장 성공
- 통합 쇼핑 세션 생성 성공
- 사용자별 요청 저장 성공
- 보유 물품 비교 성공
- 최종 통합 리스트 저장 성공
- 매장 Temi 전송 성공
- Gemini 사진 분석 API 호출 성공
- `summary.count` 기반 수량 자동 입력 로직 반영

## 11. 남은 작업 및 주의사항

현재 앱의 일부 화면 데이터는 앱 메모리에 저장된다. 따라서 앱을 종료하거나 에뮬레이터를 재시작하면 화면 목록이 초기화될 수 있다. DB 서버에는 저장되어 있으므로, 다음 단계에서는 화면 진입 시 DB 서버에서 저장된 데이터를 다시 불러오는 기능이 필요하다.

남은 작업:

- 앱 시작 시 쇼핑리스트/수납리스트/통합리스트를 DB에서 다시 불러오기
- 카메라 직접 촬영 기능 구현
- Gemini 분석 결과가 여러 종류일 때 사용자가 저장할 물품을 선택하는 UI 개선
- 매장 Temi UI에서 전송된 리스트를 조회하고 경로 안내에 활용
- 실제 Temi 기기와 QR/네트워크 연결 기능 구현

## 12. 결론

오늘 작업을 통해 앱은 단순 UI 목업에서 실제 DB 서버와 Gemini 분석 서버를 사용하는 구조로 발전했다. 사용자는 수납 물품을 DB에 저장하고, 쇼핑리스트를 입력하고, 보유 물품과 비교된 통합 리스트를 생성한 뒤 매장 Temi로 전송할 수 있다. 또한 사진 분석 결과를 수납 추가 화면에 자동 반영하는 기능까지 연결되어 기획안의 핵심 동작을 실제 앱 흐름으로 구현했다.
