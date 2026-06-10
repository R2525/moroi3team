# Gemini Photo Analysis API

작성일: 2026-06-02

## 목적

폰 앱 또는 다른 클라이언트가 사진 파일을 서버로 보내면, 서버가 Gemini API를 호출해 사진 속 물건 종류와 개수를 분석한다. 분석 결과는 기존 DB 테이블인 `recognition_session`, `recognized_item`에 저장하고, 클라이언트에는 JSON으로 반환한다.

## Endpoint

```text
POST /api/photos/analyze
```

개발 서버 예시:

```text
POST http://10.136.55.25:8000/api/photos/analyze
```

## Request

형식:

```text
multipart/form-data
```

필드:

```text
image   필수, 이미지 파일
user_id 선택
```

## Response

```json
{
  "recognition_id": 12,
  "image_path": "api_server/uploads/...",
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

## Server Processing

```text
1. 이미지 업로드 수신
2. api_server/uploads/에 이미지 저장
3. .env의 GEMINI_API_KEY 읽기
4. Gemini generateContent 호출
5. JSON 응답 파싱
6. recognition_session 저장
7. recognized_item 저장
8. total_count, summary, items 반환
```

## Current Model

```text
gemini-flash-latest
```

`gemini-1.5-flash`는 현재 API 키의 `v1beta generateContent`에서 404가 발생해 사용하지 않는다. `listModels`로 확인한 사용 가능한 flash 모델 중 `gemini-flash-latest`를 사용한다.

## Client Notes

폰 앱은 Gemini API 키를 갖지 않는다.

```text
폰 앱
-> 사진 파일만 API 서버로 업로드
-> 서버가 Gemini 호출
-> 폰 앱은 응답 JSON만 표시
```

필수 확인:

```text
서버 노트북과 폰 앱 노트북/기기가 같은 Wi-Fi에 있어야 한다.
서버 노트북 IP가 바뀌면 API URL도 바뀐다.
```
