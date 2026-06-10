# Hello Android 프로젝트 분석 보고서

작성일: 2026-05-19  
최종 업데이트: 2026-05-26  
대상 경로: `C:\Users\dbals\AndroidStudioProjects\Hello`

## 1. 요약

이 프로젝트는 Java 기반의 단일 모듈 Android 애플리케이션이다. 초기에는 Android 기본 예제에 가까운 버튼 3개 화면이었으나, 현재는 `서랍 내 물건찾기` UI 프로토타입으로 재구성되어 있다.

현재 앱은 Temi 로봇, 음성 인식, 카메라, LED/서랍 하드웨어, 로컬 DB와 직접 연동하지 않는다. 대신 홈, 물건 찾기, 검색 결과의 핵심 흐름을 화면으로 검증할 수 있는 프로토타입 상태다.

## 2. 프로젝트 구조

```text
Hello/
├─ build.gradle
├─ settings.gradle
├─ gradle.properties
├─ gradle/wrapper/
├─ app/
│  ├─ build.gradle
│  ├─ proguard-rules.pro
│  └─ src/
│     ├─ main/
│     │  ├─ AndroidManifest.xml
│     │  ├─ java/org/techtown/hello/MainActivity.java
│     │  └─ res/
│     │     ├─ layout/activity_main.xml
│     │     ├─ values/
│     │     ├─ values-night/
│     │     ├─ drawable/
│     │     └─ mipmap-*/
│     ├─ test/
│     └─ androidTest/
└─ doc/
   ├─ usr/
   └─ report/
```

주요 분석 대상은 `app/src/main`, Gradle 설정, `doc/report` 문서다.

## 3. 빌드 및 의존성

현재 빌드 설정:

| 항목 | 값 |
| --- | --- |
| Android Gradle Plugin | 4.2.2 |
| Gradle Wrapper | 6.7.1 |
| compileSdk | 30 |
| minSdk | 23 |
| targetSdk | 30 |
| Java source/target | 1.8 |
| applicationId | `org.techtown.hello` |

주요 의존성:

- `androidx.appcompat:appcompat:1.2.0`
- `com.google.android.material:material:1.2.1`
- `androidx.constraintlayout:constraintlayout:2.0.1`
- `junit:junit:4.+`
- `androidx.test.ext:junit:1.1.2`
- `androidx.test.espresso:espresso-core:3.3.0`

주의 사항:

- `junit:junit:4.+`는 동적 버전이라 재현성 측면에서 고정 버전으로 바꾸는 것이 좋다.
- AGP 4.2.2와 Gradle 6.7.1은 오래된 조합이다.
- `targetSdkVersion 30`은 최신 Android 정책 대응 관점에서 낮다.
- 루트 `build.gradle`에 `jcenter()`가 남아 있다면 제거하는 것이 좋다.

## 4. 빌드 환경

기존에는 로컬 Java 25 환경에서 구버전 Gradle/AGP와 충돌해 manifest 처리 및 Java 컴파일 단계에서 오류가 발생했다.

현재는 `gradle.properties`에 Android Studio 내장 JDK 11을 지정해 빌드가 통과한다.

```properties
org.gradle.java.home=C\:\\Program Files\\Android\\Android Studio\\jre
```

2026-05-26 검증 결과:

```powershell
.\gradlew.bat testDebugUnitTest
.\gradlew.bat assembleDebug
```

두 명령 모두 `BUILD SUCCESSFUL`로 확인됐다.

## 5. 앱 실행 흐름

### Manifest

파일:

```text
app\src\main\AndroidManifest.xml
```

현재 설정:

- 패키지: `org.techtown.hello`
- 런처 Activity: `.MainActivity`
- `android:exported="true"`
- 앱 테마: `@style/Theme.Hello`

런처 Activity이므로 `exported=true`는 타당하다.

### MainActivity

파일:

```text
app\src\main\java\org\techtown\hello\MainActivity.java
```

현재 `MainActivity`는 `AppCompatActivity`를 상속한다. `onCreate()`에서 ActionBar를 숨기고, `FrameLayout`을 코드로 생성해 루트 화면으로 사용한다.

주요 화면 상태:

- `PAGE_HOME`
- `PAGE_FIND`
- `PAGE_RESULT`

주요 화면 렌더링 메서드:

- `showHome()`
- `showFindPage()`
- `showResult(String keyword)`

뒤로가기 흐름:

- 홈: 기본 종료 동작
- 결과 화면: 물건 찾기 화면으로 이동
- 그 외: 홈으로 이동

## 6. 화면 구성

파일:

```text
app\src\main\res\layout\activity_main.xml
```

현재 XML은 동적 UI를 담기 위한 `FrameLayout` 루트만 가진다.

실제 화면 구성은 Java 코드에서 다음 위젯을 생성해 처리한다.

- `ScrollView`
- `LinearLayout`
- `TextView`
- `Button`
- `EditText`

현재 구현된 화면:

| 화면 | 주요 요소 |
| --- | --- |
| 홈 | 제목, 설명, `서랍 내 물건 찾기` 카드 |
| 물건 찾기 | 입력 필드, 음성 인식 버튼, 검색 버튼 |
| 검색 결과 | 검색 물건명, 위치, 안내, LED 상태, 재검색/종료 버튼 |

## 7. 데이터 구조

현재 검색 데이터는 `MainActivity` 내부 `HashMap<String, ItemInfo>`로 관리된다.

```text
itemDb: Map<String, ItemInfo>
ItemInfo.drawer
ItemInfo.description
```

이 구조는 프로토타입용이다. 실제 서비스 수준으로 확장하려면 다음 중 하나를 선택해야 한다.

- Room/SQLite
- SharedPreferences
- JSON 파일
- 서버 API
- Temi 또는 외부 장치의 DB 연동

## 8. 현재 확인된 문제점

### 8.1 한글 인코딩 확인 필요

PowerShell 출력에서 한글 문자열이 깨져 보이는 구간이 있다. 콘솔 인코딩 문제일 수 있지만, 실제 앱 화면에서도 깨질 가능성을 배제할 수 없다.

권장 조치:

- Android Studio에서 `MainActivity.java`와 보고서 파일을 UTF-8로 열어 확인
- 실제 AVD 화면에서 한글 표시 확인
- 깨진 문자열이 있으면 UTF-8 기준으로 복구

### 8.2 UI 문자열 하드코딩

현재 화면 문자열이 Java 코드에 직접 들어가 있다.

권장 조치:

- `strings.xml`로 이동
- 화면 텍스트, Toast 문구, 버튼 문구를 리소스로 관리

### 8.3 동적 UI 코드 집중

현재 UI가 대부분 Java 코드에 집중되어 있어 화면이 커질수록 유지보수가 어려워질 수 있다.

권장 조치:

- 단순 프로토타입이면 현 구조 유지 가능
- 화면 수가 늘어나면 XML 레이아웃, Fragment, ViewBinding 등으로 분리 검토

### 8.4 실제 기능 미연동

현재는 다음 기능이 실제 연동되지 않았다.

- Temi SDK
- 음성 인식
- 카메라/QR
- LED/서랍 제어
- 로컬 DB

현재 구현은 화면 흐름 검증용으로 보는 것이 맞다.

### 8.5 오래된 빌드 도구

현재 빌드 도구는 동작하지만 오래된 조합이다.

권장 조치:

- Gradle Wrapper 업그레이드
- Android Gradle Plugin 업그레이드
- compileSdk/targetSdk 상향
- `jcenter()` 제거
- 테스트 의존성 버전 고정

## 9. 테스트 상태

현재 테스트 파일:

- `app\src\test\java\org\techtown\hello\ExampleUnitTest.java`
- `app\src\androidTest\java\org\techtown\hello\ExampleInstrumentedTest.java`

테스트는 아직 예제 수준이다. 실제 화면 흐름이나 검색 로직 검증은 없다.

권장 테스트:

- Activity 실행 테스트
- 홈에서 물건 찾기 화면 이동 테스트
- 빈 검색어 입력 시 Toast 또는 상태 확인
- 등록된 물건 검색 결과 확인
- 미등록 물건 검색 결과 확인
- 뒤로가기 흐름 확인

## 10. 결론

현재 프로젝트는 기본 예제 앱에서 `서랍 내 물건찾기` Android 프로토타입으로 방향이 전환되었고, 핵심 화면 흐름은 구현되어 있다. `testDebugUnitTest`와 `assembleDebug`는 2026-05-26 기준 성공했다.

다음 단계에서는 한글 표시 상태를 실제 AVD에서 확인하고, 샘플 데이터/문자열을 정리한 뒤 음성 인식, 저장소, LED/서랍 제어 연동 범위를 확정하는 것이 적절하다.
