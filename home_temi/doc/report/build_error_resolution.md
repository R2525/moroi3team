# 빌드 에러 원인 및 해결방안

작성일: 2026-05-19

## 1. 발생한 에러

실행 명령:

```powershell
./gradlew.bat testDebugUnitTest
```

실패 지점:

```text
:app:processDebugMainManifest
```

대표 에러:

```text
Unable to make field private final java.lang.String java.io.File.path accessible:
module java.base does not "opens java.io" to unnamed module
```

`--stacktrace` 확인 결과 내부적으로는 Android Manifest Merger가 Gson을 사용해 데이터를 읽는 과정에서 Java 모듈 접근 제한에 걸렸습니다.

관련 스택:

```text
com.google.gson.internal.reflect.UnsafeReflectionAccessor.makeAccessible
com.android.manifmerger.ManifestMerger2.createNavigationMap
com.android.build.gradle.tasks.ProcessApplicationManifest.doFullTaskAction
```

## 2. 원인

앱 코드나 `AndroidManifest.xml` 문법 문제가 아니라, 빌드 도구와 JDK 버전 조합 문제입니다.

현재 조합:

| 항목 | 현재 값 |
| --- | --- |
| Java | 25.0.2 |
| Gradle Wrapper | 6.7.1 |
| Android Gradle Plugin | 4.2.2 |
| compileSdk | 30 |
| targetSdk | 30 |

Android Gradle Plugin 4.2.2와 Gradle 6.7.1은 오래된 조합입니다. 이 조합은 최신 JDK 25의 강한 모듈 캡슐화와 맞지 않습니다. 그래서 Manifest 처리, Java 컴파일 단계에서 JDK 내부 API 접근이 차단됩니다.

## 3. 검증한 내용

### 3.1 단순 실행

```powershell
./gradlew.bat testDebugUnitTest
```

결과: 실패

```text
:app:processDebugMainManifest FAILED
```

### 3.2 `java.io` 모듈 open 옵션만 추가

```powershell
& .\gradlew.bat '--no-daemon' `
  '-Dorg.gradle.jvmargs=--add-opens=java.base/java.io=ALL-UNNAMED -Xmx2048m -Dfile.encoding=UTF-8' `
  'testDebugUnitTest'
```

결과: Manifest 단계는 통과했지만 Java 컴파일 단계에서 다시 실패

```text
:app:compileDebugJavaWithJavac FAILED
java.lang.IllegalAccessError:
class org.gradle.internal.compiler.java.ClassNameCollector cannot access
class com.sun.tools.javac.code.Symbol$TypeSymbol
```

### 3.3 필요한 `--add-opens`, `--add-exports` 옵션 추가

```powershell
& .\gradlew.bat '--no-daemon' `
  '-Dorg.gradle.jvmargs=--add-opens=java.base/java.io=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED -Xmx2048m -Dfile.encoding=UTF-8' `
  'testDebugUnitTest'
```

결과: 성공

```text
BUILD SUCCESSFUL in 11s
16 actionable tasks: 4 executed, 12 up-to-date
```

다만 다음 경고는 남습니다.

```text
source value 8 is obsolete and will be removed in a future release
target value 8 is obsolete and will be removed in a future release
```

이 경고 역시 Java 25에서 Java 8 target/source를 사용하는 데서 오는 경고입니다.

## 4. 해결방안

## 방안 A. JDK 11로 빌드하기 - 권장

가장 안정적인 해결책입니다.

이 프로젝트의 Android Gradle Plugin 4.2.2는 JDK 11과 맞추는 것이 현실적입니다. JDK 25를 계속 쓰면 추가 모듈 옵션이 필요하고, 향후 다른 Gradle task에서 비슷한 문제가 다시 나올 수 있습니다.

권장 절차:

1. JDK 11 설치
2. Android Studio에서 Gradle JDK를 JDK 11로 변경
3. 또는 환경 변수 `JAVA_HOME`을 JDK 11 경로로 지정
4. 다시 빌드

예시:

```powershell
$env:JAVA_HOME='C:\Program Files\Java\jdk-11'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat testDebugUnitTest
```

현재 PC에서는 `C:\Program Files\Java` 아래에 `jdk-25.0.2`만 확인됐습니다. 따라서 JDK 11은 별도 설치가 필요합니다.

## 방안 B. Java 25 유지 + Gradle JVM 옵션 추가

JDK 25를 당장 유지해야 한다면 다음 옵션을 `gradle.properties`의 `org.gradle.jvmargs`에 추가하면 됩니다.

현재:

```properties
org.gradle.jvmargs=-Xmx2048m -Dfile.encoding=UTF-8
```

변경 예:

```properties
org.gradle.jvmargs=--add-opens=java.base/java.io=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED --add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED -Xmx2048m -Dfile.encoding=UTF-8
```

장점:

- JDK 설치 없이 현재 환경에서 테스트 빌드가 통과했습니다.

단점:

- 근본 해결은 아닙니다.
- Gradle/AGP가 오래된 상태라 다른 최신 JDK 이슈가 추가로 발생할 수 있습니다.
- 옵션이 길고 유지보수성이 떨어집니다.

## 방안 C. Gradle/Android Gradle Plugin 업그레이드

장기적으로 가장 좋은 방향입니다.

현재:

```text
Gradle 6.7.1
Android Gradle Plugin 4.2.2
compileSdk 30
targetSdk 30
```

업그레이드 방향:

- Android Gradle Plugin 최신 안정 버전으로 업그레이드
- Gradle Wrapper도 해당 AGP와 호환되는 버전으로 업그레이드
- compileSdk/targetSdk 상향
- 오래된 의존성 버전 정리
- `jcenter()` 제거

주의할 점:

- AGP를 크게 올리면 Gradle DSL 변경, namespace 설정 추가, SDK/빌드툴 요구사항 변경이 발생할 수 있습니다.
- 작은 프로젝트라 작업량은 크지 않지만, 한 번에 올리기보다는 단계적으로 검증하는 편이 안전합니다.

## 5. 추천 결론

단기 추천:

```text
JDK 11 설치 후 Gradle JDK를 JDK 11로 지정
```

현재 프로젝트를 크게 건드리지 않고 가장 안정적으로 빌드할 수 있습니다.

## 6. 적용 결과

2026-05-19에 방안 A를 적용했습니다.

이 PC에는 별도 JDK 11 설치본은 없었지만, Android Studio 내장 JDK가 확인되었습니다.

```text
C:\Program Files\Android\Android Studio\jre
openjdk version "11.0.8"
javac 11.0.8
```

프로젝트의 `gradle.properties`에 다음 설정을 추가했습니다.

```properties
org.gradle.java.home=C\:\\Program Files\\Android\\Android Studio\\jre
```

적용 후 일반 테스트 명령을 다시 실행했습니다.

```powershell
./gradlew.bat testDebugUnitTest
```

결과:

```text
BUILD SUCCESSFUL in 8s
16 actionable tasks: 4 executed, 12 up-to-date
```

참고: `./gradlew.bat --version`은 wrapper를 시작한 PATH상의 Java를 표시하므로 여전히 Java 25로 보일 수 있습니다. 하지만 프로젝트 빌드에는 `org.gradle.java.home` 설정이 적용되어 JDK 11 기반으로 테스트가 통과했습니다.

임시 우회:

```text
Java 25 유지 + org.gradle.jvmargs에 --add-opens/--add-exports 추가
```

테스트상 이 방법으로 `testDebugUnitTest`는 성공했습니다.

장기 추천:

```text
Gradle Wrapper, Android Gradle Plugin, compileSdk/targetSdk 업그레이드
```

최신 Android 개발 환경과 맞추려면 이 방향이 필요합니다.

## 7. 2026-05-26 재검증

UI 프로토타입을 최신 기획안 기준으로 다시 수정한 뒤 빌드와 단위 테스트를 재실행했습니다.

실행 명령:

```powershell
.\gradlew.bat assembleDebug
```

결과:

```text
BUILD SUCCESSFUL
```

실행 명령:

```powershell
.\gradlew.bat testDebugUnitTest
```

결과:

```text
BUILD SUCCESSFUL
```

따라서 현재 코드와 리소스는 APK 생성 및 단위 테스트 기준으로 정상 상태입니다.

추가 확인 사항:

- 사용 가능한 AVD는 `Pixel_2_API_29`입니다.
- 이전 실행에서는 APK 설치와 `org.techtown.hello/.MainActivity` 실행까지 성공했습니다.
- 2026-05-26 최신 UI 변경 후 재설치 확인 중에는 에뮬레이터가 ADB에서 사라지거나 Android `package` 서비스가 준비되지 않아 설치 확인이 중단됐습니다.

관찰된 메시지:

```text
adb.exe: failed to install app\build\outputs\apk\debug\app-debug.apk: cmd: Can't find service: package
adb.exe: no devices/emulators found
```

이 문제는 현재 앱 빌드 오류가 아니라 AVD 부팅/ADB 연결 안정성 문제로 판단됩니다. Android Studio에서 AVD를 완전히 부팅한 뒤 Run을 실행하거나 새 AVD를 생성해 설치를 재확인하는 것이 좋습니다.
