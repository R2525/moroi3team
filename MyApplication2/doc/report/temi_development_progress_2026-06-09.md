# Temi 스마트 수납/쇼핑 앱 개발 진행 보고서

작성일: 2026-06-09  
프로젝트: `MyApplication2` Android 앱  
대상 에뮬레이터: `Temi_UI_API_29`  
대상 서버: `http://10.136.55.25:8000`

## 1. 작업 목적

오늘 작업의 목표는 Temi 스마트폰 앱이 옆 팀원의 DB/API 서버와 안정적으로 연결되도록 맞추고, Gemini 사진 분석 결과와 서랍장 센서 정보를 실제 수납 DB 저장 흐름에 더 안전하게 반영하는 것이었다.

특히 사진 한 장에 여러 물품이 함께 찍힌 경우에도 물품명별로 분리 저장하고, Gemini가 주변 물건을 잘못 인식했을 때 사용자가 저장 전 후보를 삭제하거나 수정할 수 있는 UX를 보강했다. 또한 서랍 번호는 사용자가 직접 입력하는 값이 아니라, 서랍장 위 카메라/센서가 DB 서버에 올린 값을 앱이 받아 자동 적용하는 구조로 방향을 정정했다.

## 2. 에뮬레이터 실행 및 앱 적용

사용한 Android 가상기기는 `Temi_UI_API_29`이다.

확인 내용:

- ADB 연결 장치: `emulator-5554`
- AVD 이름: `Temi_UI_API_29`
- 앱 패키지: `org.techtown.myapplication`

수정 후 APK를 다시 빌드하고 에뮬레이터에 재설치했다.

```text
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

설치 결과:

```text
Success
```

## 3. DB/API 서버 주소 변경

기존 서버 주소에서 새 서버 주소로 변경했다.

변경 후 주소:

```text
http://10.136.55.25:8000
```

반영 위치:

- `app/src/main/java/org/techtown/myapplication/MainActivity.java`
- 앱 SharedPreferences `temi_settings.xml`

앱 기본값:

```java
private static final String DEFAULT_API_BASE_URL = "http://10.136.55.25:8000";
```

서버 연결 확인 결과:

```json
{
  "status": "ok"
}
```

에뮬레이터 내부에서도 `POST /api/photos/analyze` 요청이 서버까지 도달하는 것을 확인했다. 빈 이미지 요청 시 서버가 다음 응답을 반환했으므로, HTTP 연결과 API 라우팅은 정상이다.

```json
{
  "detail": [
    {
      "type": "missing",
      "loc": ["body", "image"],
      "msg": "Field required"
    }
  ]
}
```

## 4. Gemini 사진 분석 UX 개선

### 4.1 변경 전 흐름

기존 흐름은 다음과 같았다.

1. 사용자가 갤러리에서 사진을 선택한다.
2. `분석` 버튼을 누른다.
3. Gemini 분석 결과 중 첫 번째 항목이 `물품명`, `개수` 입력칸에 자동 입력된다.
4. 사용자가 `서랍 번호`를 입력한다.
5. 사용자가 `DB 서버에 저장` 버튼을 눌러 저장한다.

이 방식은 사진 한 장에 `shampoo`, `body wash`처럼 여러 물품이 함께 찍힌 경우 첫 번째 항목 중심으로 처리되는 한계가 있었다.

### 4.2 변경 후 흐름

변경 후 흐름은 다음과 같다.

1. 사용자가 갤러리에서 사진을 선택한다.
2. 앱 화면은 수동 서랍 번호 입력 대신 `자동 서랍 감지` 상태를 보여준다.
3. 사용자가 `분석` 버튼을 누른다.
4. 서버가 Gemini 사진 분석을 수행한다.
5. Gemini가 인식한 물품을 물품명별로 분리한다.
6. 앱이 서버에서 최신 서랍 센서 정보를 조회해 `drawer_number`를 받아온다.
7. 각 물품을 서버에서 받은 서랍 번호로 DB 서버에 자동 저장한다.
8. 분석 결과 카드는 항목별로 `물품명`, `개수` 수정 입력칸을 보여준다.
9. 잘못 인식된 후보는 사용자가 삭제할 수 있다.

예시:

잘못된 저장 방식:

```text
shampoo, body wash, 5, 1
```

원하는 저장 방식:

```text
shampoo, 1, 1
body wash, 1, 1
```

서랍 번호는 위 예시의 마지막 값처럼 사용자가 입력하는 것이 아니라, 서랍장 센서가 서버에 올린 최신 감지값을 앱이 조회해서 채운다.

## 5. 물품명별 자동 저장 구현

Gemini 응답 파싱은 기존 구조를 유지하되, 결과를 물품명별 후보 리스트로 사용하도록 했다.

주요 구현:

- `summary` 배열이 있으면 `summary` 기준으로 후보를 만든다.
- `items` 배열만 있으면 같은 `item_name`을 묶어 수량을 합산한다.
- 수량 필드가 없으면 기본값 `1`을 사용한다.
- 서버에서 조회한 최신 서랍 번호를 모든 후보에 적용한다.
- 후보마다 `POST /api/placements`를 개별 호출한다.

자동 저장 함수:

```text
saveDetectedItemsWithAutoDrawer()
saveDetectedItemsToDb(drawerNumber)
```

공통 저장 함수:

```text
fetchLatestDrawerNumber(callback)
saveDbItemWithAutoDrawer(itemNameInput, quantityInput, afterSave)
saveDbItemValues(itemName, quantity, drawerNumber, callback)
```

저장 요청 예시:

```json
{
  "item_name": "body wash",
  "quantity": 1,
  "location_name": "1번 서랍",
  "drawer_number": 1,
  "led_channel": 1,
  "description": "1번 서랍에 보관된 body wash"
}
```

## 6. 자동 서랍 번호 수신 구조

작업 중 정정된 핵심 내용은 `서랍 번호를 앱 사용자가 직접 입력하지 않는다`는 점이다. 실제 수납 과정에서는 서랍장 위 카메라와 센서가 물건이 들어간 서랍을 확인하고, 그 정보를 DB 서버에 전송한다. 따라서 앱은 저장 시 서버에서 최신 서랍 정보를 받아와야 한다.

앱 변경 내용:

- 카메라/수납추가 화면에서 분석 전 `서랍번호입력` UI를 제거했다.
- 대신 `자동 서랍 감지` 카드를 추가했다.
- `서랍 센서 정보 새로고침` 버튼을 추가했다.
- Gemini 분석 후보 자동 저장 시 먼저 서버에서 최신 `drawer_number`를 조회한다.
- 수동 저장도 사용자가 서랍 번호를 입력하지 않고, 서버에서 받은 `drawer_number`로 저장하도록 바꿨다.

앱이 현재 조회를 시도하는 후보 API:

```text
GET /api/storage-events/latest
GET /api/drawer-camera-snapshots/latest
GET /api/placement-verifications/latest
```

앱이 받을 수 있도록 구현한 응답 예시:

```json
{
  "drawer_number": 1
}
```

또는 다음처럼 중첩된 형태도 파싱할 수 있다.

```json
{
  "event": {
    "drawer_number": 1
  }
}
```

현재 확인한 서버 상태:

```text
GET /api/storage-events/latest -> 404 Not Found
GET /api/drawer-camera-snapshots/latest -> 404 Not Found
```

즉 서버에는 센서 정보 생성용 POST API는 있지만, 앱이 최신 서랍 번호를 받아올 GET API는 아직 없다. 앱 쪽은 자동 수신 구조로 준비했으며, 서버 쪽에 최신 서랍 번호 조회 API가 추가되어야 실제 자동 채움이 완성된다.

## 7. Gemini 후보 삭제 UX 추가

Gemini가 서랍에 넣지 않을 주변 물건까지 인식할 수 있으므로, 분석 결과 후보 카드에 삭제 버튼을 추가했다.

변경 전:

```text
[수정 후 DB 서버에 저장]
```

변경 후:

```text
[수정 후 저장] [삭제]
```

삭제 버튼은 붉은색으로 표시된다. 사용자가 삭제를 누르면 해당 Gemini 후보만 `detectedPhotoItems` 목록에서 제거되고, 화면이 다시 그려진다.

추가된 주요 코드:

- `detectedPhotoItemEditor(...)`
- `deleteDetectedPhotoItem(...)`
- `dangerButton(...)`
- `app/src/main/res/drawable/temi_danger_button.xml`

## 8. 테스트 이미지 준비

Gemini 분석 테스트를 위해 에뮬레이터 `Download` 폴더에 테스트 이미지를 추가했다.

추가한 이미지:

```text
/sdcard/Download/temi_bodywash_shampoo.png
/sdcard/Download/temi_clean_bodywash_shampoo.png
```

미디어 스캔도 실행하여 앱의 갤러리/파일 선택기에서 선택할 수 있게 했다.

```text
am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Download/temi_clean_bodywash_shampoo.png
```

테스트 이미지 내용:

- `Milk Baobab Body Wash`
- `Daeng Gi Meo Ri Shampoo`

의도한 분석 결과:

```text
body wash 1개
shampoo 1개
```

## 9. Gemini 분석 실패 원인 확인

앱에서 사진 분석 실패가 발생하여 원인을 확인했다.

앱 로그:

```text
POST http://10.136.55.25:8000/api/photos/analyze multipart image=...
response 502
Gemini API error:
code 503
message: This model is currently experiencing high demand.
status: UNAVAILABLE
```

PC에서 같은 이미지를 직접 서버에 업로드해도 동일한 응답이 나왔다.

결론:

- 앱에서 사진을 읽는 기능은 정상이다.
- 앱에서 서버로 사진을 업로드하는 기능도 정상이다.
- 서버의 `/api/photos/analyze` 엔드포인트도 정상이다.
- 실패 원인은 Gemini 모델이 `503 UNAVAILABLE`을 반환한 것이다.

따라서 현재 문제는 앱 로직 문제가 아니라 Gemini API 측 과부하 또는 서버의 모델 선택/재시도 정책 문제이다.

## 10. 빌드 환경 이슈 및 해결

기본 `gradlew.bat assembleDebug`는 실패했다.

원인:

- 현재 기본 Java: `Java 26`
- Gradle wrapper: `8.9`
- Android Gradle Plugin: `4.2.2`

이 조합은 호환되지 않아 Android 빌드 태스크 생성 단계에서 실패했다.

해결 방법:

로컬에 캐시된 Gradle `6.7.1`과 Android Studio 내장 Java `11`을 사용하여 빌드했다.

사용한 조합:

```text
Java: C:\Program Files\Android\Android Studio\jre
Gradle: C:\Users\t3p0u\.gradle\wrapper\dists\gradle-6.7.1-bin\...\gradle.bat
```

빌드 결과:

```text
BUILD SUCCESSFUL
```

## 11. 남은 이슈

### 11.1 최신 서랍 번호 조회 API 추가

현재 서버 OpenAPI에는 센서 정보 생성용 API가 있다.

```text
POST /api/storage-events
POST /api/drawer-camera-snapshots
POST /api/placement-verifications
```

하지만 앱이 최신 서랍 번호를 받아올 조회 API는 아직 없다. 서버에는 다음 중 하나가 추가되어야 한다.

```text
GET /api/storage-events/latest
GET /api/drawer-camera-snapshots/latest
GET /api/placement-verifications/latest
```

최소 응답 형태:

```json
{
  "drawer_number": 1
}
```

이 API가 추가되면 앱은 수동 입력 없이 센서가 감지한 서랍 번호로 `POST /api/placements`를 호출할 수 있다.

### 11.2 Gemini 503 대응

현재 Gemini가 과부하 상태일 때 앱에는 사진 분석 실패로 표시된다. 서버 또는 앱에서 다음 개선이 필요하다.

- Gemini 503 발생 시 2~3회 자동 재시도
- 재시도 간격을 1초, 3초, 5초처럼 점진적으로 증가
- 모델을 더 안정적인 Gemini 모델로 변경
- 앱에 "Gemini 과부하입니다. 잠시 후 다시 시도해주세요." 같은 사용자 친화적 메시지 표시

### 11.3 시연용 fallback

시연 안정성을 위해 Gemini 실패 시 임시 분석 결과를 반환하는 fallback이 있으면 좋다.

예시 fallback:

```json
{
  "summary": [
    {
      "item_name": "body wash",
      "quantity": 1,
      "confidence": 0.9
    },
    {
      "item_name": "shampoo",
      "quantity": 1,
      "confidence": 0.9
    }
  ]
}
```

### 11.4 Gradle 환경 정리

현재는 수동으로 Java 11과 Gradle 6.7.1을 지정해 빌드했다. 추후에는 다음 중 하나로 정리하는 것이 좋다.

- Gradle wrapper를 AGP 4.2.2와 맞는 버전으로 되돌리기
- Android Gradle Plugin을 최신 Gradle wrapper와 맞는 버전으로 업그레이드하기
- 프로젝트 문서에 빌드용 Java/Gradle 실행 방법을 명시하기

## 12. 오늘 작업 요약

- `Temi_UI_API_29` 에뮬레이터 실행 및 앱 재설치
- DB/API 서버 주소를 `http://10.136.55.25:8000`으로 변경
- 앱 설정에도 새 서버 주소 저장
- 서버 health 및 사진 분석 API 연결 확인
- 사진 분석 전 서랍 번호 수동 입력 UX 제거
- `자동 서랍 감지` 카드 및 새로고침 버튼 추가
- 서버에서 최신 `drawer_number`를 조회해 저장에 사용하는 구조 구현
- 현재 서버에 최신 서랍 번호 조회 GET API가 없어 404가 나는 상태 확인
- Gemini 분석 결과를 물품명별 후보로 분리
- 후보별 자동 DB 저장 흐름을 서버 서랍 번호 기반으로 변경
- Gemini 후보별 수정 후 저장 UI 구현
- 주변 물건 오인식 대응용 붉은색 삭제 버튼 추가
- 테스트 이미지를 에뮬레이터 Download 폴더에 추가
- Gemini 실패 원인이 서버/Gemini 503 과부하임을 확인
- Java 11 + Gradle 6.7.1 조합으로 APK 빌드 성공
- 수정된 APK를 에뮬레이터에 설치 완료
