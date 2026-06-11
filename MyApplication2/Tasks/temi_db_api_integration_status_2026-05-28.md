# Temi 스마트폰 앱 DB/API 연동 작업 보고서

작성일: 2026-05-28  
프로젝트: Smart Temi Inventory & Shopping System  
앱 서버 주소: `http://172.17.79.72:8000`

## 1. 작업 목적

스마트폰 앱 또는 에뮬레이터에서 사용자가 입력한 물품 정보와 쇼핑 리스트 정보가 옆 팀원의 DB/API 서버에 저장되고, 필요할 때 삭제까지 가능하도록 연동하는 것이 목적이다.

특히 다음 흐름을 확인하고 수정했다.

- 앱에서 물품을 추가하면 DB 서버에 저장된다.
- 앱에서 쇼핑 리스트를 추가하면 DB 서버에 저장된다.
- 앱에서 쇼핑 리스트 삭제 버튼을 누르면 DB 서버에서도 해당 정보가 삭제되어야 한다.
- Temi는 같은 DB/API 서버를 통해 저장된 정보를 조회할 수 있어야 한다.

## 2. 현재까지 확인한 API 정보

옆 팀원 서버의 기본 주소는 다음과 같다.

```text
http://172.17.79.72:8000
```

서버 상태 확인 주소는 다음과 같다.

```text
GET /api/health
```

예상 응답:

```json
{
  "status": "ok"
}
```

물품 저장 API는 다음과 같다.

```text
POST /api/placements
```

앱에서 보내는 데이터 예시는 다음과 같다.

```json
{
  "item_name": "키보드",
  "location_name": "1번 서랍",
  "drawer_number": 1,
  "led_channel": 1,
  "description": "1번 서랍에 보관된 키보드",
  "quantity": 1
}
```

물품 검색 API는 다음과 같다.

```text
GET /api/items/search?name=물건명
```

## 3. 지금까지 한 작업

앱의 기본 API 서버 주소를 옆 팀원 서버로 맞췄다.

```text
http://172.17.79.72:8000
```

앱에서 DB 서버 연결 확인 버튼을 통해 서버 상태 확인을 할 수 있게 했다.

물품 추가 화면에서 사용자가 물품명, 개수, 서랍 번호를 입력하면 `POST /api/placements`로 서버에 저장하도록 구성했다.

쇼핑 리스트 화면에서 사용자가 구매할 물품을 입력하면 서버에 저장되도록 수정했다.

기존에는 쇼핑 리스트 저장을 일반 물품 API인 `/api/items`로 보내고 있었다. 이 경우 화면에는 저장 성공처럼 보일 수 있지만, 실제로는 서버의 쇼핑 리스트 테이블이나 쇼핑 리스트 API에 저장되지 않을 수 있다.

그래서 앱 코드를 다음 방식으로 수정했다.

```text
POST /api/items
```

에서

```text
POST /api/shopping-list
```

로 변경했다.

쇼핑 리스트 삭제도 기존에는 다음 주소로 요청하고 있었다.

```text
DELETE /api/items/{id}
```

그러나 쇼핑 리스트 삭제라면 다음 주소가 필요하다고 판단하여 앱 코드를 수정했다.

```text
DELETE /api/shopping-list/{id}
```

수정 위치:

```text
app/src/main/java/org/techtown/myapplication/MainActivity.java
```

주요 수정 내용:

- 쇼핑 리스트 추가 요청 주소: `/api/shopping-list`
- 쇼핑 리스트 추가 데이터: `name`, `quantity`
- 쇼핑 리스트 삭제 요청 주소: `/api/shopping-list/{id}`
- 서버 오류 메시지에서 `detail` 값을 보여주도록 수정

## 4. 현재 문제

사용자 확인 결과, 여전히 삭제 실패가 발생했다.

현재 가장 가능성이 높은 원인은 다음 중 하나이다.

1. 옆 팀원 서버에 실제로 `DELETE /api/shopping-list/{id}` 경로가 없을 수 있다.
2. 삭제 경로는 있지만 앱에서 받은 쇼핑 리스트 ID와 서버의 삭제 ID가 서로 다를 수 있다.
3. 쇼핑 리스트 추가 API가 반환하는 ID 필드명이 앱에서 예상한 `id`, `item_id`, `list_id` 중 하나가 아닐 수 있다.
4. 서버는 쇼핑 리스트 삭제를 지원하지 않고, 상태 변경만 지원할 수 있다.
5. 앱이 아직 새 코드로 다시 빌드/설치되지 않아, 이전 앱이 계속 실행되고 있을 수 있다.

## 5. 옆 팀원에게 확인해야 하는 내용

옆 팀원에게 아래 내용을 그대로 물어보면 된다.

```text
쇼핑 리스트 삭제 API가 정확히 있나요?

제가 앱에서 쇼핑 리스트를 삭제할 때 아래 주소로 요청하려고 합니다.

DELETE /api/shopping-list/{id}

이 경로가 실제 서버에 구현되어 있나요?
그리고 POST /api/shopping-list로 저장했을 때 응답으로 오는 ID 필드 이름이 무엇인가요?
예: id, item_id, list_id, shopping_list_id 중 어떤 이름인가요?
```

추가로 상태 변경 방식만 있다면 아래도 확인해야 한다.

```text
삭제가 아니라 구매 완료 또는 비활성화 처리만 가능한 구조인가요?
예를 들어 PATCH /api/shopping-list/{id} 로 status를 done으로 바꾸는 방식인가요?
```

## 6. 서버에 필요한 삭제 API 예시

만약 옆 팀원 서버에 삭제 경로가 없다면, 서버에 다음 기능이 필요하다.

```text
DELETE /api/shopping-list/{item_id}
```

성공 응답 예시는 다음과 같이 단순해도 된다.

```json
{
  "deleted": true,
  "id": 3
}
```

없는 항목을 삭제하려고 할 때는 다음처럼 응답하면 된다.

```json
{
  "detail": "Shopping list item not found"
}
```

HTTP 상태 코드는 `404`를 사용하면 된다.

## 7. 앱 쪽에서 앞으로 해야 할 일

1. Android Studio에서 Gradle Sync를 실행한다.
2. 필요한 Android Gradle Plugin이 다운로드되도록 허용한다.
3. 앱을 다시 빌드한다.
4. 에뮬레이터 또는 갤럭시 탭에 새 앱을 설치한다.
5. 쇼핑 리스트에 새 항목을 추가한다.
6. 방금 추가한 항목을 삭제한다.
7. 삭제 실패 시 앱 로그에서 실제 요청 주소와 서버 응답을 확인한다.

주의할 점:

이전에 추가했던 쇼핑 리스트 항목은 예전 코드에서 `/api/items`에 저장되었을 수 있다. 그런 항목은 새 삭제 API인 `/api/shopping-list/{id}`로 삭제되지 않을 수 있다.

따라서 삭제 테스트는 반드시 새 코드가 설치된 뒤 새로 추가한 항목으로 해야 한다.

## 8. 서버 쪽에서 앞으로 해야 할 일

옆 팀원 서버에서 다음 중 하나를 확정해야 한다.

첫 번째 방법: 실제 삭제 지원

```text
POST /api/shopping-list
DELETE /api/shopping-list/{id}
```

두 번째 방법: 삭제 대신 상태 변경

```text
POST /api/shopping-list
PATCH /api/shopping-list/{id}
```

예시:

```json
{
  "status": "done"
}
```

앱에서 사용자가 삭제 버튼을 누르면 실제 삭제가 아니라 `status = done` 또는 `status = deleted`로 바꾸는 방식도 가능하다.

## 9. 현재 결론

앱 코드는 쇼핑 리스트 저장/삭제가 일반 물품 API가 아니라 쇼핑 리스트 API를 사용하도록 수정했다.

하지만 삭제 실패가 계속된다면 앱 문제만이 아니라 서버에 삭제 경로가 없거나, 서버가 반환하는 ID와 앱이 사용하는 ID가 맞지 않는 문제일 가능성이 높다.

다음 단계는 옆 팀원 서버에서 `DELETE /api/shopping-list/{id}`가 실제로 구현되어 있는지 확인하는 것이다.

