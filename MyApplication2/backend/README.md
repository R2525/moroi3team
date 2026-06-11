# TEMI Smart Home DB Server

모바일 로봇 Temi 기반 스마트 홈 수납 관리 및 매장 쇼핑 안내 시스템용 DB/API 서버입니다.

서버는 Python 표준 라이브러리와 SQLite만 사용합니다. 실행하면 아래 DB 파일이 자동 생성됩니다.

```text
backend/data/temi_shopping.db
```

## 실행

```powershell
python backend\server.py --host 0.0.0.0 --port 8080
```

확인:

```text
http://localhost:8080/health
```

Android 에뮬레이터에서 접속:

```text
http://10.0.2.2:8080
```

Temi 실기기에서 접속:

```text
http://<PC 내부 IP>:8080
```

## ERD 기준 테이블

```text
User
- user_id
- user_name
- phone

Temi
- temi_id
- user_id
- temi_type  // HOME, STORE
- serial_no

Smart_Drawer
- drawer_id
- temi_id
- drawer_name

Items
- item_id
- drawer_id
- item_name
- quantity
- vlm_analysis_tag
- image_url
- updated_at

Shopping_List
- list_id
- user_id
- item_name
- quantity
- is_bought
- created_at

Store_Map
- map_id
- item_name
- section_name
- coord_x
- coord_y
```

## 주요 API

### User

```http
GET /users
POST /users
PATCH /users/{user_id}
```

```json
{
  "user_name": "엄마",
  "phone": "010-1234-5678"
}
```

### Temi

```http
GET /temi
GET /temi?user_id=1
GET /temi?temi_type=HOME
POST /temi
```

```json
{
  "user_id": 1,
  "temi_type": "HOME",
  "serial_no": "HOME-TEMI-01"
}
```

### Smart_Drawer

```http
GET /drawers
GET /drawers?temi_id=1
POST /drawers
```

```json
{
  "temi_id": 1,
  "drawer_name": "1번 서랍"
}
```

### Items

수납리스트/DB 조회 화면에서 사용하는 물품 데이터입니다.

```http
GET /items
GET /items?keyword=세제
POST /items
DELETE /items/{item_id}
```

앱 호환 별칭:

```http
GET /storage-items
POST /storage-items
DELETE /storage-items/{item_id}
GET /db/items?keyword=세제
```

```json
{
  "drawer_id": 1,
  "item_name": "세제",
  "quantity": 1,
  "vlm_analysis_tag": "detergent",
  "image_url": null
}
```

### Shopping_List

쇼핑리스트 품목 단위 데이터입니다. `is_bought`는 매장 체크리스트 상태입니다.

```http
GET /shopping-list
GET /shopping-list?user_id=1
GET /shopping-list?is_bought=false
POST /shopping-list
PATCH /shopping-list/{list_id}
DELETE /shopping-list/{list_id}
```

앱 호환 별칭:

```http
GET /shopping-list-items
POST /shopping-list-items
PATCH /shopping-list-items/{list_id}
DELETE /shopping-list-items/{list_id}
GET /shopping-lists
POST /shopping-lists
```

```json
{
  "user_id": 1,
  "item_name": "샴푸",
  "quantity": 1,
  "is_bought": false
}
```

### Store_Map

쇼핑리스트 품목명과 `item_name`으로 매칭해서 매장 위치와 Temi 자율주행 좌표를 찾습니다.

```http
GET /store-map
GET /store-map?keyword=샴푸
POST /store-map
```

```json
{
  "item_name": "샴푸",
  "section_name": "욕실용품",
  "coord_x": 8.0,
  "coord_y": 9.0
}
```

## 빠른 확인

```powershell
Invoke-RestMethod http://localhost:8080/users
Invoke-RestMethod http://localhost:8080/items
Invoke-RestMethod http://localhost:8080/shopping-list
Invoke-RestMethod http://localhost:8080/store-map
```
