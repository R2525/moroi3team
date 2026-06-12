# Arduino UNO Q Project: Sensor + YOLO

This project is set up for the Arduino UNO Q board, which features a dual-processor architecture:
- **MPU (Qualcomm Dragonwing):** Runs Debian Linux. Used for YOLO AI inference.
- **MCU (STM32):** Handles real-time sensor data and hardware control.

## Directory Structure
- `arduino/`: STM32 sketches (.ino) for sensor reading and actuator control.
- `yolo/`: Python scripts for running YOLO models on the Linux side (MPU).
- `data/`: Local storage for datasets, logs, and captured images.
- `scripts/`: Helper scripts for deployment, data sync, or testing.
- `docs/`: Technical documentation and project notes.

## Workflow
1.  **Sensors:** Write and upload Arduino sketches in the `arduino/` folder to the STM32.
2.  **AI Inference:** Use the `yolo/` folder for Python scripts running on the Linux environment.
3.  **Communication:** Use serial or shared memory (as per Arduino Q documentation) to exchange data between MPU and MCU.

## Running
For normal Arduino App Lab operation, use the App Lab **Run** button or the CLI equivalent:

```bash
cd ~/ArduinoApps/roadchell
arduino-app-cli app restart .
```

This runs the app through `app.yaml`, App Lab bricks, and the App Lab container environment. Logs can be viewed with:

```bash
arduino-app-cli app logs . --follow
```

Use `scripts/run_local.sh` only for local debugging outside App Lab:

```bash
bash scripts/run_local.sh
```

The local script runs `venv/bin/python3` directly on port `5002` and bypasses the App Lab container/bricks path. Do not run App Lab and `run_local.sh` at the same time because the camera and load cell serial device can be busy.

## Temi Placement Integration
The UNO Q Linux app talks directly to the Temi web server over WiFi. Put the UNO Q and Temi on the same network, then set:

```bash
export TEMI_SERVER_URL="http://<Temi_IP>:8088"
```

The drawer scenario uses this contract:
- `GET /api/placement-batches/current` to read the active target. `current.item_name` and `current.drawer_number` are shown in the monitor for operator confirmation.
- `POST /api/sensor-events` with `{"event_type":"VERIFY_SUCCESS"}` only when the camera sees a grab/closed-fist state, then an open/released-hand state, and the load-cell delta is at least `DRAWER_WEIGHT_THRESHOLD`.
- `POST /api/sensor-events` with `{"event_type":"VERIFY_FAIL"}` when the verification step runs but either the hand-state check or the weight check failed.

The camera model is not expected to identify the object itself. It only needs to classify the hand state as holding/grabbing (`fist`, `Closed_Fist`, `hand_grab`) or released/open (`open`, `Open_Palm`, `hand_open`). The load cell is the second confirmation that something was actually placed.

The MCU sketch in `sketch/sketch.ino` already exposes `loadcell_read`, `loadcell_init`, `magnet_read`, and `magnet_raw_read` through RouterBridge. The Linux app reads those values and sends the Temi HTTP events; no PC serial bridge is required.

## Hand Gesture Recognition
`python/main.py` can overlay ONNX hand landmarks, classify canned gestures, and exposes the current hand state in `/status`.

The Python `mediapipe` package is not used on this Arduino UNO Q image because `pip install mediapipe` currently fails on Linux `aarch64` with Python 3.13. Instead, the app runs the uploaded Qualcomm AI Hub ONNX model bundle at `data/input/mediapipe_hand_gesture-onnx-w8a8`:

- `palm_detector.onnx`
- `hand_landmark_detector.onnx`
- `canned_gesture_classifier.onnx`

Recognized gesture labels are `None`, `Closed_Fist`, `Open_Palm`, `Pointing_Up`, `Thumb_Down`, `Thumb_Up`, `Victory`, and `ILoveYou`.

The web UI exposes ONNX controls for switching `Hand` and `YOLO` on or off at runtime. Hand gesture recognition is the default priority, so `Hand` starts enabled and `YOLO` starts disabled unless overridden by environment variables.

Useful environment variables:
- `HAND_TRACKING_ENABLED=1` starts hand gesture recognition enabled. The default is `0` on QRB2210 so the app starts on the YOLO HTP/NPU path instead of the older hand-tracking path.
- `HAND_TRACKING_ENABLED=0` starts hand gesture recognition disabled.
- `YOLO_ENABLED=1` starts YOLO enabled. This is the default for the QRB2210 HTP/NPU path.
- `YOLO_MODEL=data/input/yolov8n.onnx` selects the default YOLO model for the HTP/NPU path.
- `YOLO_BACKEND=qnn_htp` runs YOLO through ONNX Runtime QNN with the Qualcomm HTP/NPU backend. This is the default path and uses `/dev/fastrpc-adsp`.
- `ONNX_REQUIRE_QNN_ONLY=0` allows CPU EP fallback for unsupported graph nodes. This matches the previous working HTP/NPU app behavior.
- `YOLO_BACKEND=litert_cpu YOLO_MODEL=data/input/best_int8.tflite` runs YOLO through LiteRT/TFLite on CPU with XNNPACK as a fallback.
- `YOLO_THREADS=4` sets the LiteRT CPU thread count.
- `YOLO_TARGET_FPS=0` lets YOLO run as fast as the model can execute. Set a positive value to throttle detection updates.
- `YOLO_CONF=0.35` sets the YOLO confidence threshold. Raise it for fewer false positives, lower it if the fist/open model misses too many hands.
- `TARGET_FPS=8` sets the camera capture loop rate. QRB2210 shares CPU between camera decode and inference, so this default favors YOLO throughput.
- `TEMI_SERVER_URL=http://<Temi_IP>:8088` selects the Temi HTTP server. The default is `http://172.20.10.2:8088`; override it if the robot IP changes.
- `TEMI_REQUEST_TIMEOUT=5.0` limits each Temi HTTP request.
- `TEMI_IDLE_POLL_INTERVAL=1.0` controls how often the app polls Temi for the current placement while idle.
- `DRAWER_GRAB_LABELS=fist,closed_fist,closedfist,hand_grab,handgrab,grab,grabbing` maps camera labels that mean the hand is holding/grabbing.
- `DRAWER_RELEASE_LABELS=open,open_palm,openpalm,hand_open,handopen,released,release` maps camera labels that mean the hand has opened/released.
- `LOADCELL_SOURCE=auto` reads load cell and reed-switch magnet state through RouterBridge first, then `arduino-router-cli`, and only falls back to `/dev/ttyHS1` serial when the router socket is not present.
- `ROUTER_CLI_TIMEOUT=1.0` limits each router RPC call so sensor issues do not block YOLO or the web server.
- `DRAWER_MARKER_ENABLED=1` tracks a colored drawer marker from the camera for open-position verification.
- `DRAWER_MARKER_HSV_LOW=45,70,70` and `DRAWER_MARKER_HSV_HIGH=90,255,255` set the HSV color range. The default is a green marker.
- `DRAWER_MARKER_AXIS=x` selects whether horizontal (`x`) or vertical (`y`) marker movement represents drawer travel.
- `DRAWER_MARKER_CLOSED_POS=0.20` and `DRAWER_MARKER_OPEN_POS=0.80` calibrate the marker position as normalized image coordinates. Set these after observing `/status`.
- `DRAWER_MARKER_SECURITY_ENABLED=1` logs a security mismatch when the reed switch and calibrated camera marker disagree.
- `HAND_MAX_NUM=2` sets the maximum number of hands to track.
- `HAND_DETECTION_CONF=0.50` sets the minimum detection confidence.
- `HAND_TRACKING_CONF=0.50` sets the minimum tracking confidence.
- `HAND_TARGET_FPS=0.2` sets the target hand gesture loop rate. The ONNX gesture pipeline is CPU-heavy, so the default is intentionally low to avoid slowing YOLO.
- `HAND_GESTURE_MODEL_DIR=data/input/mediapipe_hand_gesture-onnx-w8a8` selects the ONNX gesture model bundle.
- `HAND_GESTURE_BACKEND=cpu` runs the already-quantized MediaPipe hand ONNX bundle through CPUExecutionProvider. This is the default on QRB2210.
- `HAND_GESTURE_BACKEND=qnn_gpu` runs the hand gesture ONNX models through ONNX Runtime QNN with the Qualcomm GPU backend.
- `HAND_GESTURE_BACKEND=qnn_only` requires QNN HTP/NPU to attach for the hand gesture ONNX models and is not the default path for QRB2210.
- `ONNX_REQUIRE_QNN_ONLY=1` disables CPU EP fallback for strict QNN-only validation. Use this only with a model that is fully supported by the selected QNN backend.

## Holding Detection Plan
The next model target is not object classification. The app only needs to know that something is an object, keep tracking objects while YOLO runs continuously, and decide whether the hand is holding one of them.

Recommended YOLO classes:
- `object`: any item on the floor that can be picked up. Do not split this into cup, bottle, remote, and so on unless the product later needs object identity.
- `hand_open`: an empty or non-grasping open hand.
- `hand_grab`: a closed/grasping hand shape, including the hand shape used while grabbing an object.

Runtime holding logic:
1. Run YOLO continuously and collect all `object`, `hand_open`, and `hand_grab` detections.
2. Assign simple tracking IDs to detected objects so the same floor item remains `object_1`, `object_2`, etc. across frames.
3. Select only object candidates near or overlapping the detected hand.
4. Treat `hand_open` with no nearby object as `empty`.
5. Treat `hand_open` with overlap as `touching`, not `holding`.
6. Treat `hand_grab` with overlap as a `holding` candidate.
7. Confirm `holding` only when the same object stays near the grabbing hand for several frames or moves together with the hand.
8. Emit `released` when a previously held object separates from the hand or the hand changes back to `hand_open`.

Data collection plan:
- Record the real camera view with many objects on the floor.
- Include frames with no hand, empty open hand, empty grab hand, hand near objects, touching, grabbing, moving while holding, and releasing.
- Label every visible pickup item as `object`.
- Label the hand as either `hand_open` or `hand_grab`.
- Start with this 3-class detector and rule-based temporal logic before adding a separate hand-object pair classifier.

Upgrade path if rule-based holding is not accurate enough:
- Crop the hand plus nearest object candidate.
- Train a small classifier with `holding`, `touching`, and `not_holding`.
- Run that classifier only on the nearest 1-3 candidates to keep QRB2210 CPU usage manageable.
