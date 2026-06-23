# Drawer Scenario Test Tracker

## Current Test Setup

- App Lab app: `YOLO NPU Person Detector`
- Web UI: `http://<UNO_Q_IP>:5001/`
- Server URL: `http://10.34.255.29:8088`
- Event endpoint: `POST /api/sensor-events`
- Manual scenario target:
  - `drawer_label`: `first drawer`
  - `drawer_number`: `1`
  - `item_name`: `manual-test`
  - `direction`: `in`
- YOLO model: `data/input/best_int8.tflite`
- YOLO backend: `LiteRT CPU/XNNPACK`
- YOLO classes: `fist`, `open`
- Camera: `/dev/video10`
- Magnet testing: UI override available
  - `Real`
  - `Force closed`
  - `Force open`
- Load-cell direction: UI selectable
  - `up`
  - `down`
  - `either`
- Load-cell threshold: `500`

## What Happened

The scenario did detect the expected YOLO sequence:

```text
HAND_GRAB labels=['fist']
HAND_RELEASED camera_ok=True hand_seen=True labels=['open']
```

The scenario sent a log/event, but it was sent as `VERIFY_FAIL`, not `VERIFY_SUCCESS`.

Observed verification log:

```text
VERIFY_FAIL camera_ok=True weight_ok=False direction=either initial=-2873.0 final=-2832.0 delta=41.0 effective_delta=41.0
```

Reason:

```text
weight_threshold = 500
effective_delta = 41
```

Since `41 < 500`, the load-cell condition failed.

## Server Response

The app did POST to the server.

Observed server log:

```text
POST http://10.34.255.29:8088/api/sensor-events payload={
  'event_type': 'VERIFY_FAIL',
  'drawer': 'first drawer',
  'drawer_number': 1,
  'no_drawer': 1,
  'direction': 'in',
  'in_out': 'in',
  'item_name': 'manual-test',
  'camera_ok': True,
  'weight_ok': False,
  'initial_weight': -2873.0,
  'final_weight': -2832.0,
  'weight_delta': 41.0,
  'weight_effective_delta': 41.0,
  'weight_direction': 'either',
  'weight_threshold': 500.0,
  'hand_seen': True,
  'hand_released': True
}
```

Server response:

```text
HTTP 200: {"active":false,"message":"진행 중인 수납이 없습니다."}
```

Interpretation:

- Network/server connection is working.
- The server received the request.
- The server says there is no active placement/session.

## Current Code/Behavior Notes

- `VERIFY_SUCCESS` is sent only when both are true:
  - `camera_ok=True`
  - `weight_ok=True`
- With `Weight = either`, load-cell success means:

```text
abs(final_weight - initial_weight) >= 500
```

- The final weight is captured when YOLO sees `open`.
- Therefore the load-cell value must already differ by at least `500` when `open` is detected.
- The drawer reset logic was adjusted so `STATE_WAIT_CLOSE` returns to idle if the magnet is already closed.

## Retest Checklist

1. Open the app UI:

```text
http://<UNO_Q_IP>:5001/
```

2. Confirm Scenario Setup:

```text
Manual: enabled
Drawer: first drawer
No.: 1
Weight: either
Item: manual-test
```

3. Set magnet override:

```text
Force closed
```

4. Start the scenario:

```text
Force open
```

Expected COMMS log:

```text
DOOR_OPEN item=manual-test drawer=1 initial_weight=<value>
```

5. Change load-cell value by at least `500`.

Example:

```text
initial=-2873
success if final <= -3373
success if final >= -2373
```

6. Show `fist` to camera.

Expected COMMS log:

```text
HAND_GRAB labels=['fist']
```

7. Keep the load-cell delta at `500+`, then show `open` to camera.

Expected COMMS log:

```text
HAND_RELEASED camera_ok=True hand_seen=True labels=['open']
VERIFY_SUCCESS camera_ok=True weight_ok=True ...
```

Expected SERVER POST log:

```text
POST ... event_type='VERIFY_SUCCESS'
```

## UI Places To Check

- `Comms`: scenario state and last event
- `Last Send`: most recent server response
- `COMMS` log panel: state-machine events
- `SERVER POST` log panel: payload and server response
- `LOAD CELL` log panel: raw weight changes
- `YOLO` log panel: fist/open detections

## Open Questions

- Should the Temi/server side have an active placement before sensor events are accepted?
- Should `manual-test` be replaced with the exact item name expected by the server?
- Should `VERIFY_SUCCESS` be allowed even when server reports `active:false`?
- Is `500` the final desired threshold, or only a temporary test value?
