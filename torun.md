# Roadchell 실행 정리

## 현재 만들어진 상태

프로젝트 위치:

```bash
cd /home/arduino/ArduinoApps/roadchell
```

현재 구현된 주요 기능:

- `python/main.py`: Flask 웹 서버
- `/`: 카메라 영상, 로드셀 값, YOLO 상태, 손 추적 상태를 보여주는 웹 UI
- `/video_feed`: 카메라 MJPEG 스트림
- `/status`: 현재 상태 JSON
- `/logs`: 로드셀, 자석, YOLO/NPU, 통신 로그를 채널별 JSON으로 제공
- 카메라 자동 탐색: 기본값은 `/dev/video*` 중 읽을 수 있는 첫 장치
- 로드셀 읽기:
  - 기본은 Arduino RouterBridge의 `loadcell_read`
  - Bridge가 없거나 실패하면 `/dev/ttyHS1` 시리얼로 읽기
- 리드 스위치 자석 감지:
  - 센서 모듈: SZH-SSBH-040
  - DO 핀: D4
  - MCU Monitor에 0.5초마다 `Magnet: DETECTED` 또는 `Magnet: NOT_DETECTED` 로그 출력
  - RouterBridge의 `magnet_read`로 Linux/Python 쪽에서도 상태 확인
- YOLOv8 ONNX 추론:
  - 모델: `data/input/yolov8n.onnx`
  - 기본 백엔드: QNN HTP/NPU (`/dev/fastrpc-adsp`)
- MediaPipe Hands 손 추적:
  - 설치되어 있으면 손 랜드마크를 오버레이
  - UNO Q의 현재 Python/aarch64 환경에서는 MediaPipe wheel이 없어 `MediaPipe not installed`로 표시될 수 있음
- MCU 스케치:
  - `sketch/sketch.ino`
  - HX711 로드셀을 읽고 `Bridge.provide("loadcell_read", read_loadcell)`로 Linux 쪽에 제공
  - 리드 스위치를 읽고 `Bridge.provide("magnet_read", read_magnet)`로 Linux 쪽에 제공

하드웨어 핀:

- 보드: Arduino UNO Q
- 로드셀: PSC-LC5-AL50 5KG
- HX711 모듈: SZH-SSBH-016
- VCC -> 5V
- GND -> GND
- DT -> D3
- SCK -> D2
- 리드 스위치 DO -> D4
- 리드 스위치 VCC -> 3.3V
- 리드 스위치 GND -> GND

## 일반 App Lab 실행

Arduino App Lab에서 앱을 실행할 때는 App Lab의 **Run** 버튼을 누르거나 CLI에서 같은 공식 경로를 사용합니다.

```bash
cd /home/arduino/ArduinoApps/roadchell
arduino-app-cli app restart .
```

이 방식은 `app.yaml`, App Lab bricks, `.cache/app-compose.yaml`의 컨테이너 환경을 사용합니다. `scripts/run_local.sh`는 이 경로가 아니므로 App Lab 검증용으로 쓰지 않습니다.

기본 웹 포트는 `5001`입니다.

브라우저:

```text
http://<보드_IP>:5001/
```

보드 내부에서 상태 확인:

```bash
curl http://localhost:5001/status
```

분리된 실시간 로그 확인:

```bash
curl http://localhost:5001/logs
```

Python 앱 로그 확인:

```bash
arduino-app-cli app logs . --follow
```

MCU/스케치 모니터 확인:

```bash
arduino-app-cli monitor
```

App Lab 웹 화면에서는 오른쪽 로그 패널에 다음 채널이 따로 표시됩니다.

- `LOAD CELL`: 로드셀 Bridge/시리얼 값
- `MAGNET`: 리드 스위치 자석 감지 상태
- `YOLO / NPU`: QNN HTP/NPU provider 상태, 감지 박스 수, 추론 시간, FPS
- `COMMS`: 이후 통신 기능용 로그 채널

리드 스위치 자석 감지 로그 예:

```text
Reed Switch Raw: 0 / Magnet: DETECTED
Reed Switch Raw: 1 / Magnet: NOT_DETECTED
```

주의: App Lab 실행과 로컬 실행을 동시에 켜면 카메라나 로드셀 시리얼 장치를 동시에 잡아서 충돌할 수 있습니다.

## NPU App Lab 실행

기본 YOLO 설정은 ONNX Runtime QNN HTP/NPU backend를 사용합니다. 이 경로는 `/dev/fastrpc-adsp` 장치를 사용하며, 이전에 동작하던 방식과 맞추기 위해 CPU fallback은 기본 허용합니다.

```bash
cd /home/arduino/ArduinoApps/roadchell
./scripts/start_app_lab_npu.sh
```

로컬에서 같은 조건을 직접 확인할 때:

```bash
cd /home/arduino/ArduinoApps/roadchell
YOLO_BACKEND=qnn_htp ONNX_REQUIRE_QNN_ONLY=0 venv/bin/python -c "from python import main; s=main.create_yolo_session(); print(s.get_providers())"
```

HTP/NPU 경로로 실행하려면 최소한 다음 조건이 필요합니다.

- `/dev/fastrpc-adsp`가 사용자 공간과 App Lab 컨테이너에 보여야 함
- 앱 사용자가 해당 장치를 읽고 쓸 수 있어야 함
- QNN HTP backend가 초기화되어야 함
- strict QNN-only 검증을 하려면 YOLO ONNX 그래프의 모든 실행 노드가 QNN HTP에 배정되어야 함

현재 스크립트가 처리하는 항목:

- `.cache/app-compose-overrides.yaml`의 `main` 서비스에 `c 10:* rmw` cgroup rule 추가
- `YOLO_BACKEND=qnn_htp`
- `ONNX_REQUIRE_QNN_ONLY=0`
- 컨테이너 내부 `/dev/fastrpc-adsp` 권한 열기

strict QNN-only 검증:

```bash
cd /home/arduino/ArduinoApps/roadchell
YOLO_BACKEND=qnn_htp ONNX_REQUIRE_QNN_ONLY=1 venv/bin/python -c "from python import main; s=main.create_yolo_session(); print(s.get_providers())"
```

수동으로 할 때 필요한 핵심 명령:

```bash
sudo chmod 666 /dev/fastrpc-adsp
ls -l /dev/fastrpc-adsp
arduino-app-cli app restart /home/arduino/ArduinoApps/roadchell
arduino-app-cli monitor
```

## 로컬 실행

App Lab이 아니라 프로젝트의 `venv`로 직접 실행할 때는 `scripts/run_local.sh`를 사용합니다.

```bash
cd /home/arduino/ArduinoApps/roadchell
bash scripts/run_local.sh
```

로컬 실행의 기본 포트는 App Lab과 충돌하지 않도록 `5002`입니다.

브라우저:

```text
http://<보드_IP>:5002/
```

보드 내부에서 상태 확인:

```bash
curl http://localhost:5002/status
```

포트나 카메라를 지정해서 실행:

```bash
WEB_PORT=5003 CAMERA_DEVICE=/dev/video1 bash scripts/run_local.sh
```

로드셀 시리얼을 직접 지정:

```bash
LOADCELL_SOURCE=serial LOADCELL_PORT=/dev/ttyHS1 bash scripts/run_local.sh
```

손 추적을 끄고 실행:

```bash
HAND_TRACKING_ENABLED=0 bash scripts/run_local.sh
```

## 의존성 설치

로컬 `venv`가 없거나 패키지가 빠졌을 때:

```bash
cd /home/arduino/ArduinoApps/roadchell
python3 -m venv venv
venv/bin/python3 -m pip install -r python/requirements.txt
```

현재 의존성 파일:

- `requirements.txt`
- `python/requirements.txt`

주요 패키지:

- Flask
- OpenCV headless
- NumPy
- ONNX Runtime
- ONNX Runtime QNN
- pyserial
- arduino-app-bricks

## 테스트와 점검

카메라 10프레임 캡처 테스트:

```bash
cd /home/arduino/ArduinoApps/roadchell
venv/bin/python python/test_camera.py
```

YOLO 이미지 테스트:

```bash
cd /home/arduino/ArduinoApps/roadchell
venv/bin/python yolo/test_image.py
```

ONNX Runtime provider 확인:

```bash
cd /home/arduino/ArduinoApps/roadchell
venv/bin/python check_providers.py
```

장치 확인:

```bash
ls -l /dev/ttyHS1 /dev/fastrpc-adsp
find /dev -maxdepth 1 -name 'video*' | sort
```

## 자주 보는 상태값

`/status`에서 확인할 필드:

- `camera_status`: 카메라 연결 상태
- `loadcell_status`: 로드셀 Bridge/시리얼 상태
- `loadcell_line`: 마지막 로드셀 원문
- `loadcell_value`: 숫자로 파싱된 로드셀 값
- `magnet_status`: 리드 스위치 Bridge 상태
- `magnet_line`: 마지막 자석 감지 원문
- `magnet_detected`: 자석 감지 여부
- `yolo_status`: NPU/YOLO 상태
- `yolo_inference_ms`: YOLO 추론 시간
- `yolo_fps`: YOLO 추론 FPS
- `detections`: 감지된 객체 박스
- `hand_status`: MediaPipe Hands 상태
- `hands`: 손 랜드마크 목록

`/logs`에서 확인할 필드:

- `loadcell`: 로드셀 로그 목록
- `magnet`: 자석 감지 로그 목록
- `yolo`: YOLO/NPU 로그 목록
- `comms`: 이후 통신 기능 로그 목록
- `system`: 카메라/손 추적 등 시스템 로그 목록

## 문제 해결

HTP/NPU fallback 오류:

```text
NPU device /dev/fastrpc-adsp is not readable/writable
```

해결:

```bash
sudo chmod 666 /dev/fastrpc-adsp
```

카메라가 안 열릴 때:

```bash
find /dev -maxdepth 1 -name 'video*' | sort
CAMERA_DEVICE=/dev/video10 bash scripts/run_local.sh
```

로드셀이 안 읽힐 때:

```bash
ls -l /dev/ttyHS1
LOADCELL_SOURCE=serial LOADCELL_PORT=/dev/ttyHS1 bash scripts/run_local.sh
```

포트 충돌이 날 때:

```bash
WEB_PORT=5003 bash scripts/run_local.sh
```
