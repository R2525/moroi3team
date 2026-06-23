import os
import re
import shutil
import threading
import time
import json
import subprocess
import zipfile
import urllib.error
import urllib.request
from collections import deque
from glob import glob
from pathlib import Path

import cv2
import numpy as np
import onnxruntime as ort
import serial
from flask import Flask, Response, jsonify, request


def parse_csv_floats(text, count, default):
    try:
        values = [float(part.strip()) for part in str(text).split(",")]
        if len(values) != count:
            return default
        return tuple(values)
    except (TypeError, ValueError):
        return default


def parse_optional_float(text):
    if text is None or str(text).strip() == "":
        return None
    try:
        return float(text)
    except (TypeError, ValueError):
        return None


try:
    from arduino.app_utils import Bridge
except ImportError:
    Bridge = None

try:
    import onnxruntime_qnn as qnn_ep
except ImportError:
    qnn_ep = None

try:
    from ai_edge_litert.interpreter import Interpreter as LiteRtInterpreter
except ImportError:
    try:
        from tflite_runtime.interpreter import Interpreter as LiteRtInterpreter
    except ImportError:
        LiteRtInterpreter = None


CAMERA_DEVICE = os.environ.get("CAMERA_DEVICE", "auto")
SERIAL_PORT = os.environ.get("LOADCELL_PORT", "/dev/ttyHS1")
BAUD_RATE = int(os.environ.get("LOADCELL_BAUD", "115200"))
WEB_PORT = int(os.environ.get("WEB_PORT", "5001"))
ROUTER_CLI_TIMEOUT = float(os.environ.get("ROUTER_CLI_TIMEOUT", "1.0"))

MODEL_PATH = os.environ.get("YOLO_MODEL", "data/input/best_int8.tflite")
CONF_THRESHOLD = float(os.environ.get("YOLO_CONF", "0.35"))
NMS_THRESHOLD = float(os.environ.get("YOLO_NMS", "0.45"))
YOLO_INTERVAL = float(os.environ.get("YOLO_INTERVAL", "0"))
YOLO_TARGET_FPS = float(os.environ.get("YOLO_TARGET_FPS", "0"))
YOLO_BACKEND = os.environ.get("YOLO_BACKEND", "litert_cpu")
YOLO_THREADS = int(os.environ.get("YOLO_THREADS", "4"))
YOLO_ENABLED_DEFAULT = os.environ.get("YOLO_ENABLED", "1").lower() not in {"0", "false", "no"}
LOADCELL_SOURCE = os.environ.get("LOADCELL_SOURCE", "auto").lower()
HAND_TRACKING_ENABLED = os.environ.get("HAND_TRACKING_ENABLED", "0").lower() not in {"0", "false", "no"}
HAND_MAX_NUM = int(os.environ.get("HAND_MAX_NUM", "2"))
HAND_DETECTION_CONF = float(os.environ.get("HAND_DETECTION_CONF", "0.50"))
HAND_TRACKING_CONF = float(os.environ.get("HAND_TRACKING_CONF", "0.50"))
HAND_TARGET_FPS = float(os.environ.get("HAND_TARGET_FPS", "10"))
HAND_MODEL_KIND = os.environ.get("HAND_MODEL_KIND", "mediapipe").lower()
HAND_GESTURE_MODEL_DIR = Path(
    os.environ.get("HAND_GESTURE_MODEL_DIR", "data/input/mediapipe_hand_gesture-onnx-w8a8")
)
HAND_GESTURE_CONF = float(os.environ.get("HAND_GESTURE_CONF", "0.50"))
HAND_GESTURE_BACKEND = os.environ.get("HAND_GESTURE_BACKEND", "cpu")
HAND_YOLO_MODEL = Path(
    os.environ.get("HAND_YOLO_MODEL", "data/input/hand gesture yolo onnx w8a8/model.floatfix.onnx")
)
HAND_YOLO_DATA = Path(
    os.environ.get("HAND_YOLO_DATA", "data/input/hand gesture yolo onnx w8a8/data.yaml")
)
HAND_YOLO_CONF = float(os.environ.get("HAND_YOLO_CONF", "0.35"))
HAND_YOLO_NMS = float(os.environ.get("HAND_YOLO_NMS", "0.45"))
ONNX_REQUIRE_QNN_ONLY = os.environ.get("ONNX_REQUIRE_QNN_ONLY", "0").lower() not in {"0", "false", "no"}

DRAWER_SCENARIO_ENABLED = os.environ.get("DRAWER_SCENARIO_ENABLED", "1").lower() not in {"0", "false", "no"}
VALID_WEIGHT_DIRECTIONS = {"up", "down", "either"}
DRAWER_WEIGHT_DIRECTION_DEFAULT = os.environ.get("DRAWER_WEIGHT_DIRECTION", "up").lower()
if DRAWER_WEIGHT_DIRECTION_DEFAULT not in VALID_WEIGHT_DIRECTIONS:
    DRAWER_WEIGHT_DIRECTION_DEFAULT = "up"
TEMI_SERVER_URL = (
    os.environ.get("TEMI_SERVER_URL")
    or os.environ.get("DRAWER_SERVER_URL")
    or "http://10.34.255.29:8088"
).rstrip("/")
DRAWER_SERVER_URL = TEMI_SERVER_URL
TEMI_REQUEST_TIMEOUT = float(os.environ.get("TEMI_REQUEST_TIMEOUT", "5.0"))
TEMI_IDLE_POLL_INTERVAL = float(os.environ.get("TEMI_IDLE_POLL_INTERVAL", "1.0"))
DRAWER_WEIGHT_THRESHOLD = float(os.environ.get("DRAWER_WEIGHT_THRESHOLD", "800.0"))
TEMI_SENSOR_EVENTS_PATH = "/api/sensor-events"
DRAWER_GRAB_LABELS = {
    name.strip().lower()
    for name in os.environ.get("DRAWER_GRAB_LABELS", "fist,closed_fist,closedfist,hand_grab,handgrab,grab,grabbing").split(",")
    if name.strip()
}
DRAWER_LOOP_INTERVAL = float(os.environ.get("DRAWER_LOOP_INTERVAL", "0.2"))
DRAWER_VERIFY_WEIGHT_TIMEOUT = float(os.environ.get("DRAWER_VERIFY_WEIGHT_TIMEOUT", "5.0"))
DRAWER_MARKER_ENABLED = os.environ.get("DRAWER_MARKER_ENABLED", "1").lower() not in {"0", "false", "no"}
DRAWER_MARKER_SECURITY_ENABLED = os.environ.get("DRAWER_MARKER_SECURITY_ENABLED", "1").lower() not in {"0", "false", "no"}
DRAWER_MARKER_HSV_LOW = parse_csv_floats(os.environ.get("DRAWER_MARKER_HSV_LOW", "45,70,70"), 3, (45.0, 70.0, 70.0))
DRAWER_MARKER_HSV_HIGH = parse_csv_floats(os.environ.get("DRAWER_MARKER_HSV_HIGH", "90,255,255"), 3, (90.0, 255.0, 255.0))
DRAWER_MARKER_MIN_AREA = float(os.environ.get("DRAWER_MARKER_MIN_AREA", "120"))
DRAWER_MARKER_AXIS = os.environ.get("DRAWER_MARKER_AXIS", "x").lower()
DRAWER_MARKER_CLOSED_POS = parse_optional_float(os.environ.get("DRAWER_MARKER_CLOSED_POS"))
DRAWER_MARKER_OPEN_POS = parse_optional_float(os.environ.get("DRAWER_MARKER_OPEN_POS"))
DRAWER_MARKER_OPEN_THRESHOLD = float(os.environ.get("DRAWER_MARKER_OPEN_THRESHOLD", "0.25"))
DRAWER_MARKER_CLOSED_THRESHOLD = float(os.environ.get("DRAWER_MARKER_CLOSED_THRESHOLD", "0.10"))
DRAWER_MARKER_INTERVAL = float(os.environ.get("DRAWER_MARKER_INTERVAL", "0.25"))

FRAME_WIDTH = int(os.environ.get("FRAME_WIDTH", "320"))
FRAME_HEIGHT = int(os.environ.get("FRAME_HEIGHT", "240"))
TARGET_FPS = int(os.environ.get("TARGET_FPS", "8"))
INPUT_SIZE = (640, 640)
LOG_LIMIT = int(os.environ.get("LOG_LIMIT", "80"))
SENSOR_COUNT = 3
try:
    PRIMARY_SENSOR_INDEX = int(os.environ.get("PRIMARY_SENSOR_INDEX", "1")) - 1
except ValueError:
    PRIMARY_SENSOR_INDEX = 0
PRIMARY_SENSOR_INDEX = max(0, min(SENSOR_COUNT - 1, PRIMARY_SENSOR_INDEX))


def parse_weight_directions(text, fallback):
    directions = []
    for part in str(text or "").split(","):
        direction = part.strip().lower()
        if direction in VALID_WEIGHT_DIRECTIONS:
            directions.append(direction)
    if not directions:
        directions = [fallback] * SENSOR_COUNT
    if len(directions) < SENSOR_COUNT:
        directions += [fallback] * (SENSOR_COUNT - len(directions))
    return directions[:SENSOR_COUNT]


DRAWER_WEIGHT_DIRECTIONS_DEFAULT = parse_weight_directions(
    os.environ.get("DRAWER_WEIGHT_DIRECTIONS"),
    DRAWER_WEIGHT_DIRECTION_DEFAULT,
)

DEFAULT_CLASSES = [
    "person", "bicycle", "car", "motorcycle", "airplane", "bus", "train", "truck",
    "boat", "traffic light", "fire hydrant", "stop sign", "parking meter", "bench",
    "bird", "cat", "dog", "horse", "sheep", "cow", "elephant", "bear", "zebra",
    "giraffe", "backpack", "umbrella", "handbag", "tie", "suitcase", "frisbee",
    "skis", "snowboard", "sports ball", "kite", "baseball bat", "baseball glove",
    "skateboard", "surfboard", "tennis racket", "bottle", "wine glass", "cup",
    "fork", "knife", "spoon", "bowl", "banana", "apple", "sandwich", "orange",
    "broccoli", "carrot", "hot dog", "pizza", "donut", "cake", "chair", "couch",
    "potted plant", "bed", "dining table", "toilet", "tv", "laptop", "mouse",
    "remote", "keyboard", "cell phone", "microwave", "oven", "toaster", "sink",
    "refrigerator", "book", "clock", "vase", "scissors", "teddy bear",
    "hair drier", "toothbrush",
]
CLASSES = [
    name.strip()
    for name in os.environ.get("YOLO_CLASSES", ",".join(DEFAULT_CLASSES)).split(",")
    if name.strip()
]


def read_tflite_metadata(model_path):
    if not zipfile.is_zipfile(model_path):
        return {}
    try:
        with zipfile.ZipFile(model_path) as archive:
            if "metadata.json" not in archive.namelist():
                return {}
            return json.loads(archive.read("metadata.json").decode("utf-8"))
    except Exception as exc:
        add_log("yolo", f"metadata read failed: {exc}")
        return {}


def normalize_class_names(names):
    if isinstance(names, dict):
        return [
            str(names[key])
            for key in sorted(names, key=lambda value: int(value) if str(value).isdigit() else str(value))
        ]
    if isinstance(names, list):
        return [str(name) for name in names]
    return []


def set_yolo_classes(names):
    global CLASSES
    if names:
        CLASSES = names
        add_log("yolo", "classes: " + ", ".join(CLASSES))

app = Flask(__name__)

frame_lock = threading.Lock()
state_lock = threading.RLock()
log_lock = threading.Lock()
qnn_lock = threading.Lock()
qnn_registered = False
control_lock = threading.Lock()

latest_frame = None
latest_loadcell_line = "Waiting for load cell..."
latest_loadcell_value = None
latest_loadcell_values = [None] * SENSOR_COUNT
loadcell_baseline_pending = False
latest_magnet_line = "Waiting for reed switch..."
latest_magnet_detected = None
latest_magnet_detected_values = [None] * SENSOR_COUNT
latest_magnet_raw_values = [None] * SENSOR_COUNT
magnet_override = os.environ.get("MAGNET_OVERRIDE", "real").lower()
latest_detections = []
latest_hands = []
model_controls = {
    "yolo_enabled": YOLO_ENABLED_DEFAULT,
    "hand_enabled": HAND_TRACKING_ENABLED,
    "marker_enabled": DRAWER_MARKER_ENABLED,
}
camera_status = "starting"
loadcell_status = "starting"
magnet_status = "starting"
yolo_status = "starting"
yolo_runtime = "not initialized"
hand_status = "starting"
hand_runtime = "not initialized"
yolo_inference_ms = None
yolo_fps = None
hand_inference_ms = None
hand_fps = None


def env_optional_int(name):
    value = os.environ.get(name)
    if value is None or str(value).strip() == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


drawer_state = "STATE_IDLE"
drawer_initial_weight = None
drawer_initial_weights = [None] * SENSOR_COUNT
drawer_final_weight = None
drawer_weight_delta = None
drawer_weight_deltas = [None] * SENSOR_COUNT
drawer_selected_sensor_index = None
drawer_magnet_not_detected_count = 0
drawer_hand_seen = False
drawer_last_hand_seen_ts = None
drawer_last_event = "waiting"
drawer_last_server_response = None
drawer_current_active = False
drawer_current_item = None
drawer_current_drawer = None
drawer_current_index = None
drawer_current_total = None
drawer_server_url = TEMI_SERVER_URL
drawer_event_direction = os.environ.get("DRAWER_EVENT_DIRECTION", "in")
drawer_weight_direction = DRAWER_WEIGHT_DIRECTION_DEFAULT
drawer_weight_directions = list(DRAWER_WEIGHT_DIRECTIONS_DEFAULT)
manual_target_active = os.environ.get("DRAWER_MANUAL_ACTIVE", "0").lower() not in {"0", "false", "no", "off"}
manual_target_item = os.environ.get("DRAWER_MANUAL_ITEM", "manual-test")
manual_target_drawer = env_optional_int("DRAWER_MANUAL_NUMBER")
manual_target_drawer_label = os.environ.get("DRAWER_MANUAL_LABEL", "")
manual_target_index = env_optional_int("DRAWER_MANUAL_INDEX")
manual_target_total = env_optional_int("DRAWER_MANUAL_TOTAL")
drawer_storage_session_id = env_optional_int("DRAWER_STORAGE_SESSION_ID") or 1
temi_connected = None
temi_status = "not checked"
latest_drawer_marker = {
    "enabled": DRAWER_MARKER_ENABLED,
    "detected": False,
    "status": "starting" if DRAWER_MARKER_ENABLED else "disabled",
    "bbox": None,
    "center": None,
    "area": None,
    "position": None,
    "open_ratio": None,
    "open_percent": None,
    "mismatch": False,
}

log_channels = {
    "loadcell": deque(maxlen=LOG_LIMIT),
    "magnet": deque(maxlen=LOG_LIMIT),
    "marker": deque(maxlen=LOG_LIMIT),
    "yolo": deque(maxlen=LOG_LIMIT),
    "hand": deque(maxlen=LOG_LIMIT),
    "comms": deque(maxlen=LOG_LIMIT),
    "server": deque(maxlen=LOG_LIMIT),
    "system": deque(maxlen=LOG_LIMIT),
}

HAND_GESTURE_LABELS = [
    "None",
    "Closed_Fist",
    "Open_Palm",
    "Pointing_Up",
    "Thumb_Down",
    "Thumb_Up",
    "Victory",
    "ILoveYou",
]

HAND_CONNECTIONS = [
    (0, 1), (1, 2), (2, 3), (3, 4),
    (0, 5), (5, 6), (6, 7), (7, 8),
    (5, 9), (9, 10), (10, 11), (11, 12),
    (9, 13), (13, 14), (14, 15), (15, 16),
    (13, 17), (17, 18), (18, 19), (19, 20),
    (0, 17),
]


def add_log(channel, message):
    entry = {
        "ts": time.strftime("%H:%M:%S"),
        "message": str(message),
    }
    with log_lock:
        if channel not in log_channels:
            log_channels[channel] = deque(maxlen=LOG_LIMIT)
        log_channels[channel].append(entry)


def model_enabled(name):
    key = f"{name}_enabled"
    with control_lock:
        return bool(model_controls.get(key, False))


def set_model_enabled(name, enabled):
    key = f"{name}_enabled"
    with control_lock:
        model_controls[key] = bool(enabled)
    if name == "marker":
        update_drawer_marker(
            {
                "enabled": bool(enabled),
                "detected": False,
                "status": "starting" if enabled else "disabled",
                "bbox": None,
                "center": None,
                "area": None,
                "position": None,
                "open_ratio": None,
                "open_percent": None,
                "mismatch": False,
            }
        )
    add_log("system", f"{name}: {'enabled' if enabled else 'disabled'}")


def get_model_controls():
    with control_lock:
        return dict(model_controls)


def parse_number(text):
    matches = re.findall(r"-?\d+(?:\.\d+)?", text)
    return float(matches[-1]) if matches else None


def parse_indexed_numbers(text):
    values = [None] * SENSOR_COUNT
    for index_text, value_text in re.findall(r"\b([1-3])\s*=\s*(-?\d+(?:\.\d+)?)", str(text)):
        values[int(index_text) - 1] = float(value_text)
    return values


def parse_reed_switches(text):
    raw_values = [None] * SENSOR_COUNT
    detected_values = [None] * SENSOR_COUNT
    pattern = r"\b([1-3])\s*=\s*(-?\d+)\((DETECTED|NOT_DETECTED)\)"
    for index_text, raw_text, state in re.findall(pattern, str(text)):
        index = int(index_text) - 1
        raw_values[index] = int(raw_text)
        detected_values[index] = state == "DETECTED"
    return raw_values, detected_values


def first_available(values):
    for value in values:
        if value is not None:
            return value
    return None


def primary_value(values):
    if values and values[PRIMARY_SENSOR_INDEX] is not None:
        return values[PRIMARY_SENSOR_INDEX]
    return first_available(values)


def normalized_sensor_values(values):
    result = list(values or [])
    if len(result) < SENSOR_COUNT:
        result += [None] * (SENSOR_COUNT - len(result))
    return result[:SENSOR_COUNT]


def applied_value_for_direction(value, direction):
    if value is None:
        return None
    if direction == "down":
        return -float(value)
    if direction == "either":
        return abs(float(value))
    return float(value)


def applied_loadcell_values(values, directions):
    values = normalized_sensor_values(values)
    directions = parse_weight_directions(",".join(directions or []), drawer_weight_direction)
    return [
        applied_value_for_direction(value, directions[index])
        for index, value in enumerate(values)
    ]


def drawer_closed_from_magnets(values):
    values = normalized_sensor_values(values)
    if any(value is False for value in values):
        return False
    if any(value is True for value in values):
        return True
    return None


def not_detected_indices(values):
    return [index for index, value in enumerate(normalized_sensor_values(values)) if value is False]


def format_loadcell_values(values):
    parts = []
    for index, value in enumerate(values, start=1):
        if value is None:
            parts.append(f"{index}=--")
        else:
            parts.append(f"{index}={float(value):.0f}")
    return "Raw Readings: " + " ".join(parts)


def format_magnet_values(detected_values, raw_values=None):
    parts = []
    raw_values = raw_values or [None] * SENSOR_COUNT
    for index, detected in enumerate(detected_values, start=1):
        raw = raw_values[index - 1]
        if detected is None:
            parts.append(f"{index}=--")
            continue
        state = "DETECTED" if detected else "NOT_DETECTED"
        if raw is None:
            parts.append(f"{index}={state}")
        else:
            parts.append(f"{index}={raw}({state})")
    return "Reed Switches: " + " ".join(parts)


def set_camera_status(status):
    global camera_status
    changed = False
    with state_lock:
        changed = camera_status != status
        camera_status = status
    if changed:
        add_log("system", f"camera: {status}")


def set_loadcell_status(status):
    global loadcell_status, latest_loadcell_line
    changed = False
    with state_lock:
        changed = loadcell_status != status
        loadcell_status = status
        if latest_loadcell_value is None:
            latest_loadcell_line = status
    if changed:
        add_log("loadcell", f"status: {status}")


def set_magnet_status(status):
    global magnet_status, latest_magnet_line
    changed = False
    with state_lock:
        changed = magnet_status != status
        magnet_status = status
        if latest_magnet_detected is None:
            latest_magnet_line = status
    if changed:
        add_log("magnet", f"status: {status}")


def set_yolo_status(status):
    global yolo_status
    changed = False
    with state_lock:
        changed = yolo_status != status
        yolo_status = status
    if changed:
        add_log("yolo", f"status: {status}")


def set_hand_status(status):
    global hand_status
    changed = False
    with state_lock:
        changed = hand_status != status
        hand_status = status
    if changed:
        add_log("hand", f"status: {status}")


def update_loadcell(line, values=None):
    global latest_loadcell_line, latest_loadcell_value, latest_loadcell_values
    global loadcell_baseline_pending, drawer_initial_weight, drawer_initial_weights, drawer_weight_delta, drawer_weight_deltas
    if values is None and str(line).startswith("Raw Readings:"):
        values = parse_indexed_numbers(line)
    with state_lock:
        latest_loadcell_line = line
        if values is not None:
            latest_loadcell_values = list(values)
            latest_loadcell_value = primary_value(latest_loadcell_values)
            if loadcell_baseline_pending:
                drawer_initial_weights = normalized_sensor_values(latest_loadcell_values)
                drawer_initial_weight = primary_value(drawer_initial_weights)
                drawer_weight_delta = None
                drawer_weight_deltas = [None] * SENSOR_COUNT
                loadcell_baseline_pending = False
                add_log("loadcell", f"software baseline reset: {format_loadcell_values(drawer_initial_weights)}")
        else:
            latest_loadcell_value = parse_number(line)
            latest_loadcell_values = [latest_loadcell_value] + [None] * (SENSOR_COUNT - 1)
    add_log("loadcell", line)


def update_magnet(line, detected, detected_values=None, raw_values=None):
    global latest_magnet_line, latest_magnet_detected, latest_magnet_detected_values, latest_magnet_raw_values
    if detected_values is None and str(line).startswith("Reed Switches:"):
        raw_values, detected_values = parse_reed_switches(line)
    with state_lock:
        latest_magnet_line = line
        if detected_values is not None:
            latest_magnet_detected_values = list(detected_values)
            latest_magnet_detected = primary_value(latest_magnet_detected_values)
        else:
            latest_magnet_detected = detected
            latest_magnet_detected_values = [detected] + [None] * (SENSOR_COUNT - 1)
        if raw_values is not None:
            latest_magnet_raw_values = list(raw_values)
    add_log("magnet", line)


def effective_magnet_values():
    with state_lock:
        mode = magnet_override
        values = normalized_sensor_values(latest_magnet_detected_values)
        detected = primary_value(values)
    if mode in {"closed", "detected", "1", "true"}:
        return [True] * SENSOR_COUNT, True, "closed"
    if mode in {"open", "not_detected", "not-detected", "0", "false"}:
        return [False] * SENSOR_COUNT, False, "open"
    return values, detected, "real"


def effective_magnet_state():
    values, detected, mode = effective_magnet_values()
    if mode == "closed":
        return "FORCED Magnet: DETECTED", detected, mode
    if mode == "open":
        return "FORCED Magnet: NOT_DETECTED", detected, mode
    with state_lock:
        line = latest_magnet_line
    return line, detected, "real"


def set_magnet_override(mode):
    global magnet_override
    normalized = str(mode or "real").strip().lower()
    aliases = {
        "": "real",
        "auto": "real",
        "off": "real",
        "real": "real",
        "closed": "closed",
        "detected": "closed",
        "1": "closed",
        "true": "closed",
        "open": "open",
        "not_detected": "open",
        "not-detected": "open",
        "0": "open",
        "false": "open",
    }
    if normalized not in aliases:
        raise ValueError("magnet override must be real, closed, or open")
    with state_lock:
        magnet_override = aliases[normalized]
    add_log("magnet", f"override: {magnet_override}")
    add_log("comms", f"magnet override: {magnet_override}")


def update_detections(detections, inference_ms):
    global latest_detections, yolo_inference_ms, yolo_fps, yolo_status
    with state_lock:
        latest_detections = detections
        yolo_inference_ms = inference_ms
        yolo_fps = 1000.0 / inference_ms if inference_ms > 0 else None
        runtime = yolo_runtime
        yolo_status = f"YOLO [{runtime}]: {len(detections)} boxes, {inference_ms:.1f}ms, {yolo_fps:.2f}fps"
    add_log("yolo", yolo_status)


def update_hands(hands, inference_ms):
    global latest_hands, hand_inference_ms, hand_fps, hand_status
    with state_lock:
        latest_hands = hands
        hand_inference_ms = inference_ms
        hand_fps = 1000.0 / inference_ms if inference_ms > 0 else None
        runtime = hand_runtime
        if hands:
            gesture = hands[0].get("gesture") or "Unknown"
            score = hands[0].get("gesture_score")
            suffix = f"{gesture} {score:.2f}" if score is not None else gesture
            hand_status = f"ONNX Hand Gesture [{runtime}]: {suffix}, {inference_ms:.1f}ms, {hand_fps:.2f}fps"
        else:
            hand_status = f"ONNX Hand Gesture [{runtime}]: no hand, {inference_ms:.1f}ms, {hand_fps:.2f}fps"
    add_log("hand", hand_status)


def set_drawer_status(state=None, **updates):
    global drawer_state, drawer_initial_weight, drawer_final_weight, drawer_weight_delta, drawer_weight_deltas
    global drawer_initial_weights
    global drawer_selected_sensor_index, drawer_magnet_not_detected_count
    global drawer_hand_seen, drawer_last_hand_seen_ts, drawer_last_event, drawer_last_server_response
    global drawer_current_active, drawer_current_item, drawer_current_drawer, drawer_current_index, drawer_current_total

    with state_lock:
        if state is not None:
            drawer_state = state
        if "initial_weight" in updates:
            drawer_initial_weight = updates["initial_weight"]
        if "initial_weights" in updates:
            drawer_initial_weights = normalized_sensor_values(updates["initial_weights"])
        if "final_weight" in updates:
            drawer_final_weight = updates["final_weight"]
        if "weight_delta" in updates:
            drawer_weight_delta = updates["weight_delta"]
        if "weight_deltas" in updates:
            drawer_weight_deltas = normalized_sensor_values(updates["weight_deltas"])
        if "selected_sensor_index" in updates:
            drawer_selected_sensor_index = updates["selected_sensor_index"]
        if "magnet_not_detected_count" in updates:
            drawer_magnet_not_detected_count = updates["magnet_not_detected_count"]
        if "hand_seen" in updates:
            drawer_hand_seen = updates["hand_seen"]
        if "last_hand_seen_ts" in updates:
            drawer_last_hand_seen_ts = updates["last_hand_seen_ts"]
        if "last_event" in updates:
            drawer_last_event = updates["last_event"]
        if "last_server_response" in updates:
            drawer_last_server_response = updates["last_server_response"]
        if "current_active" in updates:
            drawer_current_active = updates["current_active"]
        if "current_item" in updates:
            drawer_current_item = updates["current_item"]
        if "current_drawer" in updates:
            drawer_current_drawer = updates["current_drawer"]
        if "current_index" in updates:
            drawer_current_index = updates["current_index"]
        if "current_total" in updates:
            drawer_current_total = updates["current_total"]


def set_temi_connection(connected, status):
    global temi_connected, temi_status
    status = str(status)
    with state_lock:
        changed = temi_connected is None or temi_connected != bool(connected)
        temi_connected = bool(connected)
        temi_status = status
    if changed:
        message = f"Temi {'connected' if connected else 'disconnected'}: {status}"
        add_log("comms", message)
        add_log("server", message)


def normalize_server_url(value):
    text = str(value or "").strip()
    if not text:
        return TEMI_SERVER_URL
    if not text.startswith(("http://", "https://")):
        text = "http://" + text
    return text.rstrip("/")


def parse_optional_int(value):
    if value is None or str(value).strip() == "":
        return None
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def utc_timestamp():
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())


def set_runtime_scenario_config(data):
    global drawer_server_url, drawer_event_direction, drawer_weight_direction, drawer_weight_directions
    global manual_target_active, manual_target_item, manual_target_drawer
    global manual_target_drawer_label, manual_target_index, manual_target_total

    with state_lock:
        if "server_url" in data:
            drawer_server_url = normalize_server_url(data.get("server_url"))
        if "direction" in data:
            direction = str(data.get("direction") or "").strip().lower()
            if direction in {"in", "out"}:
                drawer_event_direction = direction
        if "weight_direction" in data:
            direction = str(data.get("weight_direction") or "").strip().lower()
            if direction in VALID_WEIGHT_DIRECTIONS:
                drawer_weight_direction = direction
                drawer_weight_directions = [direction] * SENSOR_COUNT
        if "weight_directions" in data:
            if isinstance(data.get("weight_directions"), (list, tuple)):
                raw_directions = ",".join(str(value) for value in data.get("weight_directions"))
            else:
                raw_directions = data.get("weight_directions")
            drawer_weight_directions = parse_weight_directions(raw_directions, drawer_weight_direction)
        if "manual_active" in data:
            manual_target_active = bool(data.get("manual_active"))
        if "item_name" in data:
            manual_target_item = str(data.get("item_name") or "manual-test").strip() or "manual-test"
        if "drawer_number" in data:
            manual_target_drawer = parse_optional_int(data.get("drawer_number"))
        if "drawer_label" in data:
            manual_target_drawer_label = str(data.get("drawer_label") or "").strip()
        if "current_index" in data:
            manual_target_index = parse_optional_int(data.get("current_index"))
        if "total" in data:
            manual_target_total = parse_optional_int(data.get("total"))

    add_log(
        "comms",
        "scenario config updated "
        f"manual={manual_target_active} url={drawer_server_url} "
        f"drawer={manual_target_drawer} direction={drawer_event_direction} "
        f"weight_direction={drawer_weight_direction} weight_directions={drawer_weight_directions}",
    )


def scenario_config_snapshot():
    with state_lock:
        return {
            "server_url": drawer_server_url,
            "direction": drawer_event_direction,
            "weight_direction": drawer_weight_direction,
            "weight_directions": list(drawer_weight_directions),
            "weight_threshold": DRAWER_WEIGHT_THRESHOLD,
            "manual_active": manual_target_active,
            "item_name": manual_target_item,
            "drawer_number": manual_target_drawer,
            "drawer_label": manual_target_drawer_label,
            "current_index": manual_target_index,
            "total": manual_target_total,
        }


def manual_target_snapshot():
    with state_lock:
        if not manual_target_active:
            return None
        return {
            "item_name": manual_target_item or "manual-test",
            "drawer_number": manual_target_drawer,
            "drawer_label": manual_target_drawer_label,
            "current_index": manual_target_index,
            "total": manual_target_total,
            "manual": True,
        }


def drawer_snapshot():
    with state_lock:
        return {
            "state": drawer_state,
            "initial_weight": drawer_initial_weight,
            "initial_weights": drawer_initial_weights,
            "final_weight": drawer_final_weight,
            "weight_delta": drawer_weight_delta,
            "weight_deltas": drawer_weight_deltas,
            "selected_sensor_index": None if drawer_selected_sensor_index is None else drawer_selected_sensor_index + 1,
            "magnet_not_detected_count": drawer_magnet_not_detected_count,
            "hand_seen": drawer_hand_seen,
            "last_hand_seen_ts": drawer_last_hand_seen_ts,
            "last_event": drawer_last_event,
            "last_server_response": drawer_last_server_response,
            "temi_server_url": drawer_server_url,
            "current_active": drawer_current_active,
            "current_item": drawer_current_item,
            "current_drawer": drawer_current_drawer,
            "current_index": drawer_current_index,
            "current_total": drawer_current_total,
            "direction": drawer_event_direction,
            "weight_direction": drawer_weight_direction,
            "weight_directions": list(drawer_weight_directions),
            "weight_threshold": DRAWER_WEIGHT_THRESHOLD,
            "manual_target_active": manual_target_active,
            "temi_connected": temi_connected,
            "temi_status": temi_status,
        }


def marker_snapshot():
    with state_lock:
        return dict(latest_drawer_marker)


def marker_open_ratio(position):
    if DRAWER_MARKER_CLOSED_POS is None or DRAWER_MARKER_OPEN_POS is None:
        return None
    span = DRAWER_MARKER_OPEN_POS - DRAWER_MARKER_CLOSED_POS
    if abs(span) < 1e-6:
        return None
    return float(np.clip((position - DRAWER_MARKER_CLOSED_POS) / span, 0.0, 1.0))


def update_drawer_marker(marker):
    status = marker.get("status", "marker updated")
    with state_lock:
        previous_status = latest_drawer_marker.get("status")
        mismatch = latest_drawer_marker.get("mismatch", False)
        latest_drawer_marker.update(marker)
        latest_drawer_marker["mismatch"] = bool(marker.get("mismatch", mismatch))
    if status != previous_status:
        add_log("marker", status)


def set_marker_mismatch(mismatch):
    with state_lock:
        latest_drawer_marker["mismatch"] = bool(mismatch)


def detect_drawer_marker(frame):
    hsv = cv2.cvtColor(frame, cv2.COLOR_BGR2HSV)
    low = np.array(DRAWER_MARKER_HSV_LOW, dtype=np.uint8)
    high = np.array(DRAWER_MARKER_HSV_HIGH, dtype=np.uint8)

    if low[0] <= high[0]:
        mask = cv2.inRange(hsv, low, high)
    else:
        lower_wrap = cv2.inRange(hsv, np.array([0, low[1], low[2]], dtype=np.uint8), high)
        upper_wrap = cv2.inRange(hsv, low, np.array([179, high[1], high[2]], dtype=np.uint8))
        mask = cv2.bitwise_or(lower_wrap, upper_wrap)

    mask = cv2.erode(mask, None, iterations=1)
    mask = cv2.dilate(mask, None, iterations=2)
    contours, _ = cv2.findContours(mask, cv2.RETR_EXTERNAL, cv2.CHAIN_APPROX_SIMPLE)

    if not contours:
        return {
            "enabled": True,
            "detected": False,
            "status": "marker not detected",
            "bbox": None,
            "center": None,
            "area": None,
            "position": None,
            "open_ratio": None,
            "open_percent": None,
        }

    contour = max(contours, key=cv2.contourArea)
    area = float(cv2.contourArea(contour))
    if area < DRAWER_MARKER_MIN_AREA:
        return {
            "enabled": True,
            "detected": False,
            "status": f"marker too small: {area:.0f}px",
            "bbox": None,
            "center": None,
            "area": area,
            "position": None,
            "open_ratio": None,
            "open_percent": None,
        }

    x, y, w, h = cv2.boundingRect(contour)
    frame_h, frame_w = frame.shape[:2]
    cx = x + w / 2
    cy = y + h / 2
    position = cy / frame_h if DRAWER_MARKER_AXIS == "y" else cx / frame_w
    open_ratio = marker_open_ratio(position)
    open_percent = None if open_ratio is None else round(open_ratio * 100, 1)
    status = f"marker {DRAWER_MARKER_AXIS}={position:.3f}"
    if open_percent is not None:
        status += f" open={open_percent:.1f}%"

    return {
        "enabled": True,
        "detected": True,
        "status": status,
        "bbox": [int(x), int(y), int(x + w), int(y + h)],
        "center": [round(cx / frame_w, 4), round(cy / frame_h, 4)],
        "area": area,
        "position": round(float(position), 4),
        "open_ratio": open_ratio,
        "open_percent": open_percent,
    }


def request_json(url, method="GET", payload_obj=None):
    payload = None if payload_obj is None else json.dumps(payload_obj).encode("utf-8")
    request_obj = urllib.request.Request(
        url,
        data=payload,
        headers={"Content-Type": "application/json"},
        method=method,
    )
    with urllib.request.urlopen(request_obj, timeout=TEMI_REQUEST_TIMEOUT) as response:
        body = response.read().decode("utf-8", errors="replace")
        if not body.strip():
            return {}, f"HTTP {response.status}"
        try:
            parsed = json.loads(body)
        except json.JSONDecodeError:
            parsed = {"raw": body}
        return parsed, f"HTTP {response.status}: {body}"


def get_temi_current_placement():
    with state_lock:
        server_url = drawer_server_url
    url = f"{server_url}/api/placement-batches/current"
    try:
        data, result = request_json(url)
        set_drawer_status(last_server_response=result)
        active = bool(data.get("active")) if isinstance(data, dict) else False
        current = data.get("current") if isinstance(data, dict) else None
        item_name = current.get("item_name") if isinstance(current, dict) else None
        drawer_number = current.get("drawer_number") if isinstance(current, dict) else None
        set_temi_connection(
            True,
            f"GET current ok active={active} item={item_name or '--'} drawer={drawer_number if drawer_number is not None else '--'}",
        )
        return data
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError) as exc:
        result = f"GET current placement failed: {exc}"
        set_drawer_status(last_server_response=result)
        set_temi_connection(False, result)
        return {"active": False}


def extract_temi_target(batch):
    if not batch or not batch.get("active") or not isinstance(batch.get("current"), dict):
        return None
    current = batch["current"]
    item_name = current.get("item_name")
    drawer_number = current.get("drawer_number")
    if not item_name:
        return None
    return {
        "item_name": str(item_name),
        "drawer_number": drawer_number,
        "drawer_label": current.get("drawer_label") or current.get("drawer_name") or current.get("drawer"),
        "current_index": batch.get("current_index"),
        "total": batch.get("total"),
    }


def build_sensor_event_payload(ok, target=None, details=None):
    with state_lock:
        direction = drawer_event_direction
        current_item = drawer_current_item
        current_drawer = drawer_current_drawer
        current_index = drawer_current_index
        current_total = drawer_current_total

    target = target or {}
    details = details or {}
    drawer_number = target.get("drawer_number", current_drawer)
    drawer_label = target.get("drawer_label") or (f"drawer-{drawer_number}" if drawer_number is not None else None)

    payload_obj = {
        "storage_session_id": target.get("storage_session_id") or details.get("storage_session_id") or drawer_storage_session_id,
        "sensor_type": "load_cell",
        "event_type": "sequence_completed" if ok else "verification_failed",
        "drawer": drawer_label,
        "drawer_number": drawer_number,
        "no_drawer": drawer_number,
        "direction": direction,
        "in_out": direction,
        "timestamp": utc_timestamp(),
        "item_name": target.get("item_name") or current_item,
        "current_index": target.get("current_index", current_index),
        "total": target.get("total", current_total),
    }
    payload_obj.update({key: value for key, value in details.items() if value is not None})
    return payload_obj


def post_temi_sensor_event(ok, target=None, details=None):
    with state_lock:
        server_url = drawer_server_url
    url = f"{server_url}{TEMI_SENSOR_EVENTS_PATH}"
    payload_obj = build_sensor_event_payload(ok, target=target, details=details)
    add_log("server", f"POST {url} payload={payload_obj}")
    try:
        _, result = request_json(url, method="POST", payload_obj=payload_obj)
        set_drawer_status(last_server_response=result)
        set_temi_connection(True, f"POST {payload_obj['event_type']} ok")
        add_log("comms", f"POST {payload_obj['event_type']}: {result}")
        add_log("server", f"POST {payload_obj['event_type']} response={result}")
        return True
    except (urllib.error.URLError, TimeoutError, OSError) as exc:
        result = f"POST {payload_obj['event_type']} failed: {exc}"
        set_drawer_status(last_server_response=result)
        set_temi_connection(False, result)
        add_log("comms", result)
        add_log("server", result)
        return False


def send_debug_temi_success_event(options=None):
    add_log("comms", "DEBUG VERIFY_SUCCESS manual send requested")
    options = options or {}
    target = manual_target_snapshot() or {}
    details = {"debug": True}
    if options.get("drawer_number") is not None:
        drawer_number = int(options["drawer_number"])
        target.update(
            {
                "drawer_number": drawer_number,
                "drawer_label": options.get("drawer_label") or f"drawer-{drawer_number}",
            }
        )
    if options.get("timestamp"):
        details["timestamp"] = options["timestamp"]
    payload = build_sensor_event_payload(True, target=target, details=details)
    ok = post_temi_sensor_event(True, target=target, details=details)
    if ok:
        set_drawer_status(last_event="DEBUG_VERIFY_SUCCESS_SENT")
        return {
            "ok": True,
            "url": f"{drawer_server_url}{TEMI_SENSOR_EVENTS_PATH}",
            "payload": payload,
            "result": "VERIFY_SUCCESS sent",
        }
    return {
        "ok": False,
        "url": f"{drawer_server_url}{TEMI_SENSOR_EVENTS_PATH}",
        "payload": payload,
        "error": "VERIFY_SUCCESS send failed",
    }


def normalize_match_label(text):
    return re.sub(r"[^a-z0-9]+", "", str(text).strip().lower())


def camera_hand_state(detections, hands):
    grab_labels = {normalize_match_label(label) for label in DRAWER_GRAB_LABELS}
    grab_seen = False
    hand_present = False
    labels = []

    def classify_label(label):
        nonlocal grab_seen, hand_present
        normalized = normalize_match_label(label)
        if not normalized or normalized == "none":
            return
        labels.append(str(label))
        if normalized in grab_labels:
            grab_seen = True
            hand_present = True
        elif "hand" in normalized or "fist" in normalized or "palm" in normalized:
            hand_present = True

    for hand in hands:
        classify_label(hand.get("gesture"))
        if hand.get("box") or hand.get("landmarks"):
            hand_present = True

    for detection in detections:
        classify_label(detection.get("label"))

    return {
        "grab_seen": grab_seen,
        "hand_present": hand_present,
        "labels": labels,
    }


def evaluate_weight_change(initial_weight, final_weight, weight_direction):
    if initial_weight is None or final_weight is None:
        return None, None, False

    delta = final_weight - initial_weight
    if weight_direction == "down":
        effective_delta = -delta
    elif weight_direction == "either":
        effective_delta = abs(delta)
    else:
        effective_delta = delta
    return delta, effective_delta, effective_delta >= DRAWER_WEIGHT_THRESHOLD


def evaluate_sensor_weight_changes(initial_values, final_values, weight_directions):
    initial_values = normalized_sensor_values(initial_values)
    final_values = normalized_sensor_values(final_values)
    weight_directions = parse_weight_directions(",".join(weight_directions or []), drawer_weight_direction)
    results = []
    for index in range(SENSOR_COUNT):
        weight_direction = weight_directions[index]
        delta, effective_delta, weight_ok = evaluate_weight_change(
            initial_values[index],
            final_values[index],
            weight_direction,
        )
        results.append(
            {
                "sensor_index": index,
                "initial_weight": initial_values[index],
                "final_weight": final_values[index],
                "weight_delta": delta,
                "weight_effective_delta": effective_delta,
                "weight_ok": weight_ok,
                "weight_direction": weight_direction,
            }
        )
    return results


def select_verification_sensor(initial_values, final_values, magnet_values, weight_directions):
    weight_results = evaluate_sensor_weight_changes(initial_values, final_values, weight_directions)
    candidates = not_detected_indices(magnet_values)
    selected_index = None

    if len(candidates) == 1:
        selected_index = candidates[0]
    elif len(candidates) >= 2:
        selected_index = max(
            candidates,
            key=lambda index: (
                -1.0
                if weight_results[index]["weight_delta"] is None
                else abs(weight_results[index]["weight_delta"])
            ),
        )
    else:
        available = [
            result["sensor_index"]
            for result in weight_results
            if result["weight_delta"] is not None
        ]
        selected_index = available[0] if available else PRIMARY_SENSOR_INDEX

    selected = weight_results[selected_index]
    raw_delta = selected["weight_delta"]
    adjusted_delta = selected["weight_effective_delta"]
    return {
        **selected,
        "weight_raw_delta": raw_delta,
        "weight_delta": adjusted_delta,
        "selected_sensor_index": selected_index,
        "magnet_not_detected": selected_index in candidates,
        "magnet_not_detected_count": len(candidates),
        "weight_raw_deltas": [result["weight_delta"] for result in weight_results],
        "weight_deltas": [result["weight_effective_delta"] for result in weight_results],
        "weight_effective_deltas": [result["weight_effective_delta"] for result in weight_results],
        "weight_directions": [result["weight_direction"] for result in weight_results],
        "not_detected_sensor_indices": [index + 1 for index in candidates],
    }


def drawer_note(event, **extra):
    data = drawer_snapshot()
    data.update(extra)
    parts = [f"event={event}", f"state={data['state']}"]
    for key in (
        "initial_weight",
        "final_weight",
        "weight_delta",
        "hand_seen",
        "current_item",
        "current_drawer",
        "camera_status",
        "magnet_detected",
        "marker_detected",
        "marker_open_percent",
    ):
        if key in data and data[key] is not None:
            parts.append(f"{key}={data[key]}")
    return "; ".join(parts)


def drawer_scenario_thread():
    if not DRAWER_SCENARIO_ENABLED:
        set_drawer_status("DISABLED", last_event="disabled")
        add_log("comms", "drawer scenario disabled")
        return

    local_state = "STATE_IDLE"
    last_door_closed = None
    hand_seen = False
    last_hand_seen_ts = None
    initial_weight = None
    initial_weight_values = [None] * SENSOR_COUNT
    verification_result = None
    current_target = None
    camera_ok = False
    marker_mismatch_active = False
    next_temi_poll_ts = 0.0
    last_target_signature = None

    set_drawer_status(local_state, last_event="started")
    add_log("comms", f"drawer scenario started; server={drawer_server_url}")

    while True:
        with state_lock:
            loadcell_values = normalized_sensor_values(latest_loadcell_values)
            loadcell_value = latest_loadcell_value
            detections = list(latest_detections)
            hands = list(latest_hands)
            cam_status = camera_status
            marker = dict(latest_drawer_marker)
        magnet_values, magnet_detected, _ = effective_magnet_values()

        door_closed = drawer_closed_from_magnets(magnet_values)
        now = time.time()
        hand_state = camera_hand_state(detections, hands)
        marker_detected = bool(marker.get("detected"))
        marker_open_ratio_value = marker.get("open_ratio")
        marker_open_percent = marker.get("open_percent")

        marker_mismatch = False
        if (
            DRAWER_MARKER_SECURITY_ENABLED
            and door_closed is not None
            and marker_detected
            and marker_open_ratio_value is not None
        ):
            marker_says_open = marker_open_ratio_value >= DRAWER_MARKER_OPEN_THRESHOLD
            marker_says_closed = marker_open_ratio_value <= DRAWER_MARKER_CLOSED_THRESHOLD
            marker_mismatch = (door_closed and marker_says_open) or ((not door_closed) and marker_says_closed)

        if marker_mismatch and not marker_mismatch_active:
            set_marker_mismatch(True)
            marker_mismatch_active = True
            message = f"MARKER_MISMATCH reed_closed={door_closed} marker_open={marker_open_percent}%"
            add_log("marker", message)
            add_log("comms", message)
        elif not marker_mismatch and marker_mismatch_active:
            set_marker_mismatch(False)
            marker_mismatch_active = False
            add_log("marker", "MARKER_MISMATCH_RESOLVED")

        if local_state == "STATE_IDLE":
            manual_target = manual_target_snapshot()
            if manual_target is not None:
                current_target = manual_target
                target_signature = (
                    "manual",
                    current_target["item_name"],
                    current_target.get("drawer_number"),
                    current_target.get("drawer_label"),
                    current_target.get("current_index"),
                    current_target.get("total"),
                )
                if target_signature != last_target_signature:
                    add_log(
                        "comms",
                        "Manual target: "
                        f"item={current_target['item_name']} "
                        f"drawer={current_target.get('drawer_number')} "
                        f"label={current_target.get('drawer_label') or '--'} "
                        f"direction={drawer_event_direction}",
                    )
                    last_target_signature = target_signature
                set_drawer_status(
                    current_active=True,
                    current_item=current_target["item_name"],
                    current_drawer=current_target.get("drawer_number"),
                    current_index=current_target.get("current_index"),
                    current_total=current_target.get("total"),
                    initial_weight=None,
                    final_weight=None,
                    weight_delta=None,
                    hand_seen=False,
                    last_hand_seen_ts=None,
                    last_event="WAIT_DRAWER_OPEN",
                )
            elif now >= next_temi_poll_ts:
                batch = get_temi_current_placement()
                current_target = extract_temi_target(batch)
                next_temi_poll_ts = now + TEMI_IDLE_POLL_INTERVAL

                if current_target is None:
                    if last_target_signature is not None:
                        add_log("comms", "Temi placement inactive; waiting")
                    last_target_signature = None
                    set_drawer_status(
                        current_active=False,
                        current_item=None,
                        current_drawer=None,
                        current_index=None,
                        current_total=None,
                        hand_seen=False,
                        last_event="WAIT_TEMI",
                    )
                else:
                    target_signature = (
                        "temi",
                        current_target["item_name"],
                        current_target.get("drawer_number"),
                        current_target.get("drawer_label"),
                        current_target.get("current_index"),
                        current_target.get("total"),
                    )
                    if target_signature != last_target_signature:
                        add_log(
                            "comms",
                            "Temi target: "
                            f"item={current_target['item_name']} "
                            f"drawer={current_target.get('drawer_number')} "
                            f"index={current_target.get('current_index')} "
                            f"total={current_target.get('total')}",
                        )
                        last_target_signature = target_signature
                    set_drawer_status(
                        current_active=True,
                        current_item=current_target["item_name"],
                        current_drawer=current_target.get("drawer_number"),
                        current_index=current_target.get("current_index"),
                        current_total=current_target.get("total"),
                        initial_weight=None,
                        initial_weights=[None] * SENSOR_COUNT,
                        final_weight=None,
                        weight_delta=None,
                        hand_seen=False,
                        last_hand_seen_ts=None,
                        last_event="WAIT_DRAWER_OPEN",
                    )

            if current_target is None:
                if door_closed is not None:
                    last_door_closed = door_closed
                time.sleep(DRAWER_LOOP_INTERVAL)
                continue

            if door_closed is False:
                initial_weight = loadcell_value
                initial_weight_values = loadcell_values
                hand_seen = False
                last_hand_seen_ts = None
                verification_result = None
                camera_ok = False
                local_state = "STATE_WAIT_CAMERA"
                set_drawer_status(
                    local_state,
                    initial_weight=initial_weight,
                    initial_weights=initial_weight_values,
                    final_weight=None,
                    weight_delta=None,
                    weight_deltas=[None] * SENSOR_COUNT,
                    selected_sensor_index=None,
                    magnet_not_detected_count=len(not_detected_indices(magnet_values)),
                    hand_seen=False,
                    last_hand_seen_ts=None,
                    last_event="DOOR_OPEN",
                )
                add_log(
                    "comms",
                    "DOOR_OPEN "
                    f"item={current_target['item_name']} "
                    f"drawer={current_target.get('drawer_number')} "
                    f"initial_weights={initial_weight_values} "
                    f"not_detected_sensors={[index + 1 for index in not_detected_indices(magnet_values)]}",
                )

        elif local_state == "STATE_WAIT_CAMERA":
            if hand_state["hand_present"]:
                if not hand_seen:
                    add_log("comms", f"HAND_DETECTED labels={hand_state['labels']}")
                hand_seen = True
                last_hand_seen_ts = now
                local_state = "STATE_WAIT_WEIGHT"
                set_drawer_status(
                    local_state,
                    hand_seen=True,
                    last_hand_seen_ts=last_hand_seen_ts,
                    last_event="HAND_DETECTED",
                )

        elif local_state == "STATE_WAIT_WEIGHT":
            with state_lock:
                weight_directions = list(drawer_weight_directions)
            verification = select_verification_sensor(
                initial_weight_values,
                loadcell_values,
                magnet_values,
                weight_directions,
            )
            final_weight = verification["final_weight"]
            delta = verification["weight_delta"]
            effective_delta = verification["weight_effective_delta"]
            weight_ok = verification["weight_ok"]
            weight_direction = verification["weight_direction"]
            elapsed = 0.0 if last_hand_seen_ts is None else now - last_hand_seen_ts
            timed_out = elapsed >= DRAWER_VERIFY_WEIGHT_TIMEOUT
            set_drawer_status(
                local_state,
                final_weight=final_weight,
                weight_delta=delta,
                weight_deltas=verification["weight_deltas"],
                selected_sensor_index=verification["selected_sensor_index"],
                magnet_not_detected_count=verification["magnet_not_detected_count"],
                last_event="WAIT_WEIGHT",
            )
            if weight_ok:
                verification_result = verification
                camera_ok = bool(hand_seen)
                add_log(
                    "comms",
                    f"WEIGHT_READY sensor={verification['selected_sensor_index'] + 1} "
                    f"direction={weight_direction} directions={verification['weight_directions']} "
                    f"initial={verification['initial_weight']} final={final_weight} "
                    f"delta={delta} effective_delta={effective_delta} "
                    f"not_detected_sensors={verification['not_detected_sensor_indices']}",
                )
                local_state = "STATE_VERIFY"
                set_drawer_status(local_state, last_event="WEIGHT_READY")
            elif timed_out or door_closed is True:
                reason = "door_closed" if door_closed is True else "timeout"
                verification_result = verification
                camera_ok = False
                add_log(
                    "comms",
                    f"WEIGHT_WAIT_END reason={reason} sensor={verification['selected_sensor_index'] + 1} "
                    f"weight_ok={weight_ok} direction={weight_direction} directions={verification['weight_directions']} "
                    f"initial={verification['initial_weight']} final={final_weight} "
                    f"delta={delta} effective_delta={effective_delta} "
                    f"not_detected_sensors={verification['not_detected_sensor_indices']}",
                )
                local_state = "STATE_VERIFY"

        elif local_state == "STATE_VERIFY":
            with state_lock:
                weight_directions = list(drawer_weight_directions)
            verification = verification_result or select_verification_sensor(
                initial_weight_values,
                loadcell_values,
                magnet_values,
                weight_directions,
            )
            final_weight = verification["final_weight"]
            delta = verification["weight_delta"]
            effective_delta = verification["weight_effective_delta"]
            weight_ok = verification["weight_ok"]
            weight_direction = verification["weight_direction"]

            magnet_not_detected = verification["magnet_not_detected"]
            success = bool(magnet_not_detected and camera_ok and weight_ok)

            result = "VERIFY_SUCCESS" if success else "VERIFY_FAIL"
            set_drawer_status(
                local_state,
                final_weight=final_weight,
                weight_delta=delta,
                weight_deltas=verification["weight_deltas"],
                selected_sensor_index=verification["selected_sensor_index"],
                magnet_not_detected_count=verification["magnet_not_detected_count"],
                last_event=result,
            )
            add_log(
                "comms",
                f"{result} sensor={verification['selected_sensor_index'] + 1} "
                f"magnet_not_detected={magnet_not_detected} camera_ok={camera_ok} weight_ok={weight_ok} "
                f"direction={weight_direction} directions={verification['weight_directions']} "
                f"initial={verification['initial_weight']} final={final_weight} "
                f"delta={delta} effective_delta={effective_delta} "
                f"not_detected_sensors={verification['not_detected_sensor_indices']}",
            )
            post_temi_sensor_event(
                success,
                target=current_target,
                details={
                    "camera_ok": camera_ok,
                    "weight_ok": weight_ok,
                    "magnet_not_detected": magnet_not_detected,
                    "selected_sensor_index": verification["selected_sensor_index"] + 1,
                    "not_detected_sensor_indices": verification["not_detected_sensor_indices"],
                    "magnet_not_detected_count": verification["magnet_not_detected_count"],
                    "initial_weight": verification["initial_weight"],
                    "final_weight": final_weight,
                    "weight_raw_delta": verification["weight_raw_delta"],
                    "weight_delta": delta,
                    "weight_effective_delta": effective_delta,
                    "weight_raw_deltas": verification["weight_raw_deltas"],
                    "weight_deltas": verification["weight_deltas"],
                    "weight_effective_deltas": verification["weight_effective_deltas"],
                    "weight_direction": weight_direction,
                    "weight_directions": verification["weight_directions"],
                    "weight_threshold": DRAWER_WEIGHT_THRESHOLD,
                    "hand_seen": hand_seen,
                },
            )
            local_state = "STATE_WAIT_CLOSE"
            set_drawer_status(local_state, last_event=result)

        elif local_state == "STATE_WAIT_CLOSE":
            if door_closed is True:
                local_state = "STATE_IDLE"
                set_drawer_status(local_state, last_event="DOOR_CLOSE")
                add_log("comms", "DOOR_CLOSE")
                current_target = None
                next_temi_poll_ts = 0.0

        if door_closed is not None:
            last_door_closed = door_closed
        time.sleep(DRAWER_LOOP_INTERVAL)


def set_hand_runtime(runtime):
    global hand_runtime
    with state_lock:
        hand_runtime = runtime


def set_yolo_runtime(runtime):
    global yolo_runtime
    with state_lock:
        yolo_runtime = runtime


def dequantize(value, scale, zero_point):
    return (value.astype(np.float32) - float(zero_point)) * float(scale)


def quantize(value, scale, zero_point):
    return np.clip(np.rint(value / float(scale) + float(zero_point)), 0, 255).astype(np.uint8)


def preprocess_uint8_chw(frame, size):
    resized = cv2.resize(frame, (size, size))
    rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
    return np.expand_dims(np.transpose(rgb, (2, 0, 1)), axis=0).astype(np.uint8)


def generate_palm_anchors():
    anchors = []
    strides = [8, 16, 32, 32, 32]
    input_size = 256
    layer_id = 0
    while layer_id < len(strides):
        same_stride_layers = 0
        stride = strides[layer_id]
        while layer_id + same_stride_layers < len(strides) and strides[layer_id + same_stride_layers] == stride:
            same_stride_layers += 1

        feature_map_size = int(np.ceil(input_size / stride))
        anchors_per_cell = same_stride_layers * 2
        for y in range(feature_map_size):
            for x in range(feature_map_size):
                x_center = (x + 0.5) / feature_map_size
                y_center = (y + 0.5) / feature_map_size
                for _ in range(anchors_per_cell):
                    anchors.append((x_center, y_center))
        layer_id += same_stride_layers
    return np.array(anchors, dtype=np.float32)


PALM_ANCHORS = generate_palm_anchors()


def decode_best_palm(frame_shape, box_coords, box_scores):
    scores = dequantize(box_scores[0], 0.00390625, 0)
    best_idx = int(np.argmax(scores))
    best_score = float(scores[best_idx])
    if best_score < HAND_DETECTION_CONF:
        return None, best_score

    raw = dequantize(box_coords[0, best_idx], 1.6417218446731567, 50)
    anchor_x, anchor_y = PALM_ANCHORS[best_idx]

    x_center = raw[0] / 256.0 + anchor_x
    y_center = raw[1] / 256.0 + anchor_y
    box_w = max(raw[2] / 256.0, 0.04)
    box_h = max(raw[3] / 256.0, 0.04)

    frame_h, frame_w = frame_shape[:2]
    side = max(box_w * frame_w, box_h * frame_h) * 2.2
    cx = x_center * frame_w
    cy = y_center * frame_h
    x1 = int(np.clip(cx - side / 2, 0, frame_w - 1))
    y1 = int(np.clip(cy - side / 2, 0, frame_h - 1))
    x2 = int(np.clip(cx + side / 2, x1 + 1, frame_w))
    y2 = int(np.clip(cy + side / 2, y1 + 1, frame_h))
    return (x1, y1, x2, y2), best_score


def landmark_features(landmarks, handedness):
    points = landmarks.reshape(21, 3).astype(np.float32)
    points[:, :2] /= 224.0
    points[:, 2] /= 224.0
    points -= points[0]
    scale = np.max(np.linalg.norm(points[:, :2], axis=1))
    if scale > 1e-6:
        points /= scale
    hand = np.concatenate([points.reshape(-1), np.array([handedness], dtype=np.float32)])
    mirrored = hand.copy()
    mirrored[0:63:3] *= -1.0
    mirrored[-1] = 1.0 - mirrored[-1]
    return hand.reshape(1, 64), mirrored.reshape(1, 64)


def classify_gesture(classifier, landmarks, handedness):
    hand, mirrored = landmark_features(landmarks, handedness)
    hand_q = quantize(hand, 0.012942158617079258, 147)
    mirrored_q = quantize(mirrored, 0.012942158617079258, 147)
    outputs = classifier.run(None, {"hand": hand_q, "mirrored_hand": mirrored_q})
    scores = dequantize(outputs[0], 0.00390625, 0)[0]
    idx = int(np.argmax(scores))
    label = HAND_GESTURE_LABELS[idx] if idx < len(HAND_GESTURE_LABELS) else str(idx)
    return label, float(scores[idx])


def get_camera_candidates():
    if CAMERA_DEVICE.lower() != "auto":
        return [CAMERA_DEVICE]

    devices = glob("/dev/video*")

    def sort_key(path):
        match = re.search(r"video(\d+)$", path)
        return int(match.group(1)) if match else 999

    return sorted(devices, key=sort_key)


def open_camera(device):
    cap = cv2.VideoCapture(device, cv2.CAP_V4L2)
    if not cap.isOpened():
        cap.release()
        return None

    cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*"MJPG"))
    cap.set(cv2.CAP_PROP_FRAME_WIDTH, FRAME_WIDTH)
    cap.set(cv2.CAP_PROP_FRAME_HEIGHT, FRAME_HEIGHT)
    cap.set(cv2.CAP_PROP_FPS, TARGET_FPS)

    for _ in range(8):
        ok, frame = cap.read()
        if ok and frame is not None:
            return cap
        time.sleep(0.05)

    cap.release()
    return None


def router_cli_call(method):
    result = subprocess.run(
        ["arduino-router-cli", method],
        check=True,
        capture_output=True,
        text=True,
        timeout=ROUTER_CLI_TIMEOUT,
    )
    text = result.stdout.strip()
    match = re.search(r"Got RPC response:\s*(.+)", text)
    if match:
        text = match.group(1).strip()
    if ":" in text:
        _, value = text.split(":", 1)
        return value.strip()
    return text


def to_optional_float(value):
    if value is None:
        return None
    if isinstance(value, (int, float, bool)):
        return float(value)
    text = str(value).strip()
    if text == "":
        return None
    return float(text)


def read_numbered_bridge(base_method, legacy_method=None):
    values = []
    try:
        for index in range(1, SENSOR_COUNT + 1):
            values.append(to_optional_float(Bridge.call(f"{base_method}_{index}")))
        return values
    except Exception:
        if legacy_method is None:
            raise
        return [to_optional_float(Bridge.call(legacy_method))] + [None] * (SENSOR_COUNT - 1)


def read_numbered_router(base_method, legacy_method=None):
    values = []
    try:
        for index in range(1, SENSOR_COUNT + 1):
            values.append(to_optional_float(router_cli_call(f"{base_method}_{index}")))
        return values
    except Exception:
        if legacy_method is None:
            raise
        return [to_optional_float(router_cli_call(legacy_method))] + [None] * (SENSOR_COUNT - 1)


def number_list_to_bool(values):
    return [None if value is None else float(value) != 0.0 for value in values]


def router_cli_available():
    has_socket = Path("/var/run/arduino-router.sock").exists() or Path("/run/arduino-router.sock").exists()
    return has_socket and shutil.which("arduino-router-cli") is not None


def init_loadcell_tare():
    if LOADCELL_SOURCE in {"0", "false", "no", "off", "disabled"}:
        raise RuntimeError("load cell is disabled")

    errors = []

    if LOADCELL_SOURCE in {"auto", "bridge"} and Bridge is not None:
        try:
            result = Bridge.call("loadcell_init")
            set_loadcell_status("tare requested: RouterBridge")
            update_loadcell("TARE:COLLECTING")
            add_log("loadcell", f"tare init result: {result}")
            return "RouterBridge", result
        except Exception as exc:
            errors.append(f"RouterBridge: {exc}")
            if LOADCELL_SOURCE == "bridge":
                raise RuntimeError("; ".join(errors))

    if LOADCELL_SOURCE in {"auto", "bridge"} and router_cli_available():
        try:
            result = router_cli_call("loadcell_init")
            set_loadcell_status("tare requested: arduino-router-cli")
            update_loadcell("TARE:COLLECTING")
            add_log("loadcell", f"tare init result: {result}")
            return "arduino-router-cli", result
        except Exception as exc:
            errors.append(f"arduino-router-cli: {exc}")

    if LOADCELL_SOURCE == "auto" and not errors:
        errors.append("no Bridge or arduino-router-cli path is available")
    elif LOADCELL_SOURCE not in {"auto", "bridge"}:
        errors.append(f"load cell source '{LOADCELL_SOURCE}' does not support web init")

    raise RuntimeError("; ".join(errors))


def camera_thread():
    global latest_frame

    candidates = get_camera_candidates()
    cap = None
    selected_device = None
    for device in candidates:
        set_camera_status(f"probing: {device}")
        cap = open_camera(device)
        if cap is not None:
            selected_device = device
            break

    if cap is None:
        set_camera_status("failed: no readable camera")
        return

    set_camera_status(f"connected: {selected_device}")

    while True:
        start = time.time()
        ok, frame = cap.read()
        if ok and frame is not None:
            with frame_lock:
                latest_frame = frame
        else:
            set_camera_status("capture failed")
            time.sleep(0.2)
            continue

        target_delay = 1 / TARGET_FPS if TARGET_FPS > 0 else 0
        sleep_time = target_delay - (time.time() - start)
        if sleep_time > 0:
            time.sleep(sleep_time)


def drawer_marker_thread():
    add_log("marker", f"marker tracking started axis={DRAWER_MARKER_AXIS}")
    marker_disabled_logged = False
    while True:
        if not model_enabled("marker"):
            if not marker_disabled_logged:
                update_drawer_marker(
                    {
                        "enabled": False,
                        "detected": False,
                        "status": "disabled",
                        "bbox": None,
                        "center": None,
                        "area": None,
                        "position": None,
                        "open_ratio": None,
                        "open_percent": None,
                        "mismatch": False,
                    }
                )
                marker_disabled_logged = True
            time.sleep(DRAWER_MARKER_INTERVAL)
            continue

        marker_disabled_logged = False
        with frame_lock:
            frame = None if latest_frame is None else latest_frame.copy()

        if frame is None:
            update_drawer_marker(
                {
                    "enabled": True,
                    "detected": False,
                    "status": "waiting for camera frame",
                    "bbox": None,
                    "center": None,
                    "area": None,
                    "position": None,
                    "open_ratio": None,
                    "open_percent": None,
                }
            )
            time.sleep(DRAWER_MARKER_INTERVAL)
            continue

        try:
            update_drawer_marker(detect_drawer_marker(frame))
        except Exception as exc:
            update_drawer_marker(
                {
                    "enabled": True,
                    "detected": False,
                    "status": f"marker detection failed: {exc}",
                    "bbox": None,
                    "center": None,
                    "area": None,
                    "position": None,
                    "open_ratio": None,
                    "open_percent": None,
                }
            )
        time.sleep(DRAWER_MARKER_INTERVAL)


def loadcell_thread():
    if LOADCELL_SOURCE in {"0", "false", "no", "off", "disabled"}:
        set_loadcell_status("disabled")
        set_magnet_status("disabled")
        return

    if LOADCELL_SOURCE in {"auto", "bridge"} and Bridge is not None:
        while True:
            try:
                set_loadcell_status("connected: RouterBridge")
                set_magnet_status("connected: RouterBridge")
                values = read_numbered_bridge("loadcell_read", "loadcell_read")
                update_loadcell(format_loadcell_values(values), values)
                magnet_values = number_list_to_bool(read_numbered_bridge("magnet_read", "magnet_read"))
                raw_values = None
                try:
                    raw_values = read_numbered_bridge("magnet_raw_read", "magnet_raw_read")
                except Exception:
                    pass
                update_magnet(
                    format_magnet_values(magnet_values, raw_values),
                    primary_value(magnet_values),
                    magnet_values,
                    raw_values,
                )
            except Exception as exc:
                set_loadcell_status(f"bridge failed: {exc}")
                set_magnet_status(f"bridge failed: {exc}")
                if LOADCELL_SOURCE == "auto":
                    break
            time.sleep(0.5)

    if LOADCELL_SOURCE in {"auto", "bridge"} and router_cli_available():
        while True:
            try:
                values = read_numbered_router("loadcell_read", "loadcell_read")
                set_loadcell_status("connected: arduino-router-cli")
                update_loadcell(format_loadcell_values(values), values)
            except subprocess.TimeoutExpired:
                set_loadcell_status(f"router timeout after {ROUTER_CLI_TIMEOUT:.1f}s")
            except Exception as exc:
                set_loadcell_status(f"router failed: {exc}")
                if LOADCELL_SOURCE == "auto":
                    break

            try:
                magnet_values = number_list_to_bool(read_numbered_router("magnet_read", "magnet_read"))
                raw_values = None
                try:
                    raw_values = read_numbered_router("magnet_raw_read", "magnet_raw_read")
                except Exception:
                    pass
                set_magnet_status("connected: arduino-router-cli")
                update_magnet(
                    format_magnet_values(magnet_values, raw_values),
                    primary_value(magnet_values),
                    magnet_values,
                    raw_values,
                )
            except subprocess.TimeoutExpired:
                set_magnet_status(f"router timeout after {ROUTER_CLI_TIMEOUT:.1f}s")
            except Exception as exc:
                set_magnet_status(f"router failed: {exc}")
            time.sleep(0.5)

    if LOADCELL_SOURCE == "bridge":
        set_loadcell_status("Bridge not available")
        set_magnet_status("Bridge not available")
        return

    if LOADCELL_SOURCE == "auto" and router_cli_available():
        return

    set_magnet_status(f"waiting for sensor serial: {SERIAL_PORT}")
    while True:
        try:
            set_loadcell_status(f"connecting: {SERIAL_PORT}")
            with serial.Serial(SERIAL_PORT, BAUD_RATE, timeout=1) as ser:
                ser.reset_input_buffer()
                set_loadcell_status(f"connected: {SERIAL_PORT}")
                set_magnet_status(f"connected: {SERIAL_PORT}")
                while True:
                    line = ser.readline().decode("utf-8", errors="ignore").strip()
                    if line.startswith("Raw Reading:"):
                        update_loadcell(line)
                    elif line.startswith("Raw Readings:"):
                        update_loadcell(line)
                    elif line.startswith("Reed Switch Raw:"):
                        update_magnet(line, "DETECTED" in line and "NOT_DETECTED" not in line)
                    elif line.startswith("Reed Switches:"):
                        update_magnet(line, None)
        except Exception as exc:
            set_loadcell_status(f"failed: {exc}")
            set_magnet_status(f"serial failed: {exc}")
            time.sleep(5)


def magnet_thread():
    if Bridge is None:
        set_magnet_status("Bridge not available")
        return

    while True:
        try:
            set_magnet_status("connected: RouterBridge")
            magnet_values = number_list_to_bool(read_numbered_bridge("magnet_read", "magnet_read"))
            raw_values = None
            try:
                raw_values = read_numbered_bridge("magnet_raw_read", "magnet_raw_read")
            except Exception:
                pass
            update_magnet(
                format_magnet_values(magnet_values, raw_values),
                primary_value(magnet_values),
                magnet_values,
                raw_values,
            )
        except Exception as exc:
            set_magnet_status(f"bridge failed: {exc}")
        time.sleep(0.5)


def get_qnn_backend_path(backend):
    backend = backend.lower()
    if backend in {"qnn_gpu", "gpu"}:
        return qnn_ep.get_qnn_gpu_path()
    if backend in {"qnn_cpu"}:
        return qnn_ep.get_qnn_cpu_path()
    if backend in {"qnn", "qnn_only", "qnn_htp", "qnn_htp_only", "qnn_npu", "npu"}:
        return qnn_ep.get_qnn_htp_path()
    raise RuntimeError(f"Unsupported QNN backend: {backend}")


def get_qnn_backend_label(backend):
    backend = backend.lower()
    if backend in {"qnn_gpu", "gpu"}:
        return "QNN GPU"
    if backend == "qnn_cpu":
        return "QNN CPU"
    if backend in {"qnn", "qnn_only", "qnn_htp", "qnn_htp_only", "qnn_npu", "npu"}:
        return "QNN HTP/NPU"
    return backend


def qnn_requires_fastrpc(backend):
    return backend.lower() in {"qnn", "qnn_only", "qnn_htp", "qnn_htp_only", "qnn_npu", "npu"}


def get_qnn_devices(backend="qnn_htp"):
    global qnn_registered
    if qnn_ep is None:
        raise RuntimeError("onnxruntime_qnn is not installed")
    if qnn_requires_fastrpc(backend) and not os.access("/dev/fastrpc-adsp", os.R_OK | os.W_OK):
        raise RuntimeError(
            "DSP/QNN device /dev/fastrpc-adsp is not readable/writable. "
            "Run: sudo chmod 666 /dev/fastrpc-adsp"
        )

    registration_name = qnn_ep.get_ep_name()
    with qnn_lock:
        if not qnn_registered:
            ort.register_execution_provider_library(registration_name, qnn_ep.get_library_path())
            qnn_registered = True
    qnn_devices = [d for d in ort.get_ep_devices() if d.ep_name == registration_name]
    if not qnn_devices:
        raise RuntimeError("QNNExecutionProvider device was not found")
    return qnn_devices


def require_qnn_only(session_options):
    if ONNX_REQUIRE_QNN_ONLY:
        session_options.add_session_config_entry("session.disable_cpu_ep_fallback", "1")


def create_yolo_session():
    runner = create_yolo_runner()
    if not isinstance(runner, OnnxYoloRunner):
        raise RuntimeError("create_yolo_session is ONNX-only. Use create_yolo_runner for TFLite YOLO.")
    return runner.session


class LiteRtYoloRunner:
    def __init__(self, model_path):
        if LiteRtInterpreter is None:
            raise RuntimeError("LiteRT/TFLite runtime is not installed. Install ai-edge-litert.")
        if not Path(model_path).exists():
            raise RuntimeError(f"YOLO model missing: {model_path}")

        self.metadata = read_tflite_metadata(model_path)
        names = normalize_class_names(self.metadata.get("names"))
        set_yolo_classes(names)

        self.interpreter = LiteRtInterpreter(model_path=str(model_path), num_threads=YOLO_THREADS)
        self.interpreter.allocate_tensors()
        self.input = self.interpreter.get_input_details()[0]
        self.outputs = self.interpreter.get_output_details()

        shape = [int(v) for v in self.input["shape"]]
        if len(shape) != 4:
            raise RuntimeError(f"Unsupported YOLO input shape: {shape}")
        if shape[1] in {1, 3}:
            self.layout = "nchw"
            self.input_size = (shape[3], shape[2])
        else:
            self.layout = "nhwc"
            self.input_size = (shape[2], shape[1])

        dtype_name = np.dtype(self.input["dtype"]).name
        class_text = ",".join(CLASSES[:4])
        self.runtime = (
            f"LiteRT CPU/XNNPACK {dtype_name} {self.input_size[0]}x{self.input_size[1]} "
            f"t{YOLO_THREADS} classes={class_text}"
        )

    def preprocess(self, frame):
        resized = cv2.resize(frame, self.input_size)
        rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
        dtype = self.input["dtype"]

        if np.issubdtype(dtype, np.floating):
            tensor = rgb.astype(np.float32) / 255.0
        else:
            tensor = rgb.astype(np.float32)
            scale, zero_point = self.input.get("quantization", (0.0, 0))
            if scale:
                tensor = np.rint(tensor / 255.0 / float(scale) + float(zero_point))
            info = np.iinfo(dtype)
            tensor = np.clip(tensor, info.min, info.max).astype(dtype)

        if self.layout == "nchw":
            tensor = np.transpose(tensor, (2, 0, 1))
        return np.expand_dims(tensor.astype(dtype, copy=False), axis=0)

    def run(self, frame):
        input_data = self.preprocess(frame)
        self.interpreter.set_tensor(self.input["index"], input_data)
        start = time.time()
        self.interpreter.invoke()
        inference_ms = (time.time() - start) * 1000
        outputs = [self.interpreter.get_tensor(output["index"]) for output in self.outputs]
        return outputs, inference_ms


class OnnxYoloRunner:
    def __init__(self, model_path):
        session_options = ort.SessionOptions()
        backend = YOLO_BACKEND.lower()
        if backend in {"qnn", "qnn_only", "qnn_htp", "qnn_htp_only", "qnn_npu", "npu", "qnn_gpu", "gpu", "qnn_cpu"}:
            qnn_devices = get_qnn_devices(backend)
            require_qnn_only(session_options)
            session_options.add_provider_for_devices(
                qnn_devices,
                {"backend_path": get_qnn_backend_path(backend)},
            )
        self.session = ort.InferenceSession(str(model_path), sess_options=session_options)
        self.input_name = self.session.get_inputs()[0].name
        self.run_options = None
        providers = self.session.get_providers()
        if backend in {"qnn", "qnn_only", "qnn_htp", "qnn_htp_only", "qnn_npu", "npu", "qnn_gpu", "gpu", "qnn_cpu"}:
            fallback = " + CPU fallback" if "CPUExecutionProvider" in providers else ""
            self.runtime = f"ONNX Runtime {get_qnn_backend_label(backend)}{fallback}: " + ", ".join(providers)
        else:
            self.runtime = "ONNX Runtime " + ", ".join(providers)
        if "QNNExecutionProvider" in providers:
            self.run_options = ort.RunOptions()
            self.run_options.add_run_config_entry("qnn.perf_mode", "burst")
            self.run_options.add_run_config_entry("qnn.rpc_control_latency", "100")

    def run(self, frame):
        input_data = preprocess(frame)
        start = time.time()
        outputs = self.session.run(None, {self.input_name: input_data}, self.run_options)
        inference_ms = (time.time() - start) * 1000
        return outputs, inference_ms


def create_yolo_runner():
    model_path = Path(MODEL_PATH)
    backend = YOLO_BACKEND.lower()
    if backend in {"qnn", "qnn_only", "qnn_htp", "qnn_htp_only", "qnn_npu", "npu", "qnn_gpu", "gpu", "qnn_cpu"}:
        if model_path.suffix.lower() != ".onnx":
            raise RuntimeError(f"{YOLO_BACKEND} requires an ONNX YOLO model, got: {model_path}")
        runner = OnnxYoloRunner(model_path)
    elif model_path.suffix.lower() == ".tflite" or backend in {"litert", "litert_cpu", "tflite", "tflite_cpu"}:
        runner = LiteRtYoloRunner(model_path)
    else:
        runner = OnnxYoloRunner(model_path)
    set_yolo_runtime(runner.runtime)
    set_yolo_status(f"YOLO ready: {runner.runtime}")
    return runner


def create_qnn_session(model_path, backend="qnn_htp"):
    qnn_devices = get_qnn_devices(backend)
    session_options = ort.SessionOptions()
    require_qnn_only(session_options)
    session_options.add_provider_for_devices(
        qnn_devices,
        {"backend_path": get_qnn_backend_path(backend)},
    )
    session = ort.InferenceSession(str(model_path), sess_options=session_options)
    providers = session.get_providers()
    if "QNNExecutionProvider" not in providers:
        raise RuntimeError(f"QNNExecutionProvider was not enabled: {providers}")
    return session


def preprocess(frame):
    resized = cv2.resize(frame, INPUT_SIZE)
    rgb = cv2.cvtColor(resized, cv2.COLOR_BGR2RGB)
    tensor = rgb.astype(np.float32) / 255.0
    tensor = np.transpose(tensor, (2, 0, 1))
    return np.expand_dims(tensor, axis=0)


def normalize_yolo_output(output):
    output = np.squeeze(output)
    if output.ndim != 2:
        return np.empty((0, 0), dtype=np.float32)
    if output.shape[0] < output.shape[1]:
        output = output.T
    return output


def output_looks_like_nms(rows):
    if rows.shape[1] != 6:
        return False
    sample = rows[: min(len(rows), 32)]
    if len(sample) == 0:
        return False
    ordered_boxes = np.mean((sample[:, 2] >= sample[:, 0]) & (sample[:, 3] >= sample[:, 1]))
    integer_classes = np.mean(np.isclose(sample[:, 5], np.rint(sample[:, 5]), atol=1e-3))
    return ordered_boxes > 0.8 and integer_classes > 0.8


def scale_box_to_frame(box, frame_w, frame_h, input_size):
    x1, y1, x2, y2 = [float(v) for v in box]
    if max(abs(x1), abs(y1), abs(x2), abs(y2)) <= 2.0:
        x1 *= frame_w
        x2 *= frame_w
        y1 *= frame_h
        y2 *= frame_h
    else:
        x_factor = frame_w / input_size[0]
        y_factor = frame_h / input_size[1]
        x1 *= x_factor
        x2 *= x_factor
        y1 *= y_factor
        y2 *= y_factor
    return [
        max(0, int(round(x1))),
        max(0, int(round(y1))),
        min(frame_w - 1, int(round(x2))),
        min(frame_h - 1, int(round(y2))),
    ]


def postprocess(frame_shape, output, input_size=INPUT_SIZE):
    rows = normalize_yolo_output(output)
    detections = []
    if rows.size == 0:
        return detections

    frame_h, frame_w = frame_shape[:2]

    if output_looks_like_nms(rows):
        boxes = []
        confidences = []
        class_ids = []
        for row in rows:
            confidence = float(row[4])
            if confidence < CONF_THRESHOLD:
                continue
            class_id = int(round(float(row[5])))
            x1, y1, x2, y2 = scale_box_to_frame(row[:4], frame_w, frame_h, input_size)
            if x2 <= x1 or y2 <= y1:
                continue
            boxes.append([x1, y1, x2 - x1, y2 - y1])
            confidences.append(confidence)
            class_ids.append(class_id)

        indices = cv2.dnn.NMSBoxes(boxes, confidences, CONF_THRESHOLD, NMS_THRESHOLD)
        if len(indices) == 0:
            return detections

        for i in np.array(indices).flatten():
            x, y, w, h = boxes[i]
            class_id = class_ids[i]
            label = CLASSES[class_id] if class_id < len(CLASSES) else str(class_id)
            detections.append(
                {
                    "box": [x, y, x + w, y + h],
                    "label": label,
                    "confidence": confidences[i],
                }
            )
        return detections

    x_factor = frame_w / input_size[0]
    y_factor = frame_h / input_size[1]

    boxes = []
    confidences = []
    class_ids = []

    for row in rows:
        if len(row) < 6:
            continue
        scores = row[4:]
        class_id = int(np.argmax(scores))
        confidence = float(scores[class_id])
        if confidence < CONF_THRESHOLD:
            continue

        cx, cy, bw, bh = row[:4]
        x = int((cx - bw / 2) * x_factor)
        y = int((cy - bh / 2) * y_factor)
        w = int(bw * x_factor)
        h = int(bh * y_factor)
        boxes.append([x, y, w, h])
        confidences.append(confidence)
        class_ids.append(class_id)

    indices = cv2.dnn.NMSBoxes(boxes, confidences, CONF_THRESHOLD, NMS_THRESHOLD)
    if len(indices) == 0:
        return detections

    for i in np.array(indices).flatten():
        x, y, w, h = boxes[i]
        x1 = max(0, x)
        y1 = max(0, y)
        x2 = min(frame_w - 1, x + w)
        y2 = min(frame_h - 1, y + h)
        class_id = class_ids[i]
        label = CLASSES[class_id] if class_id < len(CLASSES) else str(class_id)
        detections.append(
            {
                "box": [x1, y1, x2, y2],
                "label": label,
                "confidence": confidences[i],
            }
        )
    return detections


def read_yolo_labels(path):
    if not path.exists():
        return ["hand-gestures", "left", "mvefrd", "right", "stop"]
    text = path.read_text(errors="ignore")
    match = re.search(r"names:\s*\[(.*?)\]", text, re.S)
    if not match:
        return ["hand-gestures", "left", "mvefrd", "right", "stop"]
    return [
        item.strip().strip("'\"")
        for item in match.group(1).split(",")
        if item.strip().strip("'\"")
    ]


def postprocess_hand_yolo(frame_shape, output, labels):
    rows = normalize_yolo_output(output)
    hands = []
    if rows.size == 0:
        return hands

    frame_h, frame_w = frame_shape[:2]
    x_factor = frame_w / INPUT_SIZE[0]
    y_factor = frame_h / INPUT_SIZE[1]
    boxes = []
    confidences = []
    class_ids = []

    for row in rows:
        if len(row) < 6:
            continue
        scores = row[4:]
        class_id = int(np.argmax(scores))
        confidence = float(scores[class_id])
        if confidence < HAND_YOLO_CONF:
            continue

        cx, cy, bw, bh = row[:4]
        x = int((cx - bw / 2) * x_factor)
        y = int((cy - bh / 2) * y_factor)
        w = int(bw * x_factor)
        h = int(bh * y_factor)
        boxes.append([x, y, w, h])
        confidences.append(confidence)
        class_ids.append(class_id)

    indices = cv2.dnn.NMSBoxes(boxes, confidences, HAND_YOLO_CONF, HAND_YOLO_NMS)
    if len(indices) == 0:
        return hands

    for i in np.array(indices).flatten():
        x, y, w, h = boxes[i]
        x1 = max(0, x)
        y1 = max(0, y)
        x2 = min(frame_w - 1, x + w)
        y2 = min(frame_h - 1, y + h)
        class_id = class_ids[i]
        label = labels[class_id] if class_id < len(labels) else str(class_id)
        hands.append(
            {
                "handedness": label if label in {"left", "right"} else "",
                "score": confidences[i],
                "gesture": label,
                "gesture_score": confidences[i],
                "box": [x1, y1, x2, y2],
                "landmarks": [],
            }
        )
    return hands


def yolo_thread():
    runner = None

    while True:
        if not model_enabled("yolo"):
            runner = None
            with state_lock:
                latest_detections.clear()
            set_yolo_status("disabled")
            set_yolo_runtime("not initialized")
            time.sleep(0.5)
            continue

        if runner is None:
            try:
                runner = create_yolo_runner()
            except Exception as exc:
                set_yolo_runtime("failed")
                set_yolo_status(f"YOLO runtime failed: {exc}")
                time.sleep(2)
                continue

        with frame_lock:
            frame = latest_frame

        if frame is None:
            time.sleep(0.05)
            continue

        try:
            outputs, inference_ms = runner.run(frame)
            input_size = getattr(runner, "input_size", INPUT_SIZE)
            detections = postprocess(frame.shape, outputs[0], input_size)
            update_detections(detections, inference_ms)
        except Exception as exc:
            set_yolo_status(f"YOLO failed: {exc}")
            runner = None
            time.sleep(2)
            continue

        target_delay = 1 / YOLO_TARGET_FPS if YOLO_TARGET_FPS > 0 else 0
        sleep_time = max(YOLO_INTERVAL, target_delay - (inference_ms / 1000))
        if sleep_time > 0:
            time.sleep(sleep_time)


def hand_yolo_thread():
    target_delay = 1 / HAND_TARGET_FPS if HAND_TARGET_FPS > 0 else 0
    labels = read_yolo_labels(HAND_YOLO_DATA)
    session = None
    input_name = None
    run_options = None

    while True:
        if not model_enabled("hand"):
            session = None
            with state_lock:
                latest_hands.clear()
            set_hand_status("disabled")
            time.sleep(0.5)
            continue

        if session is None:
            try:
                if not HAND_YOLO_MODEL.exists():
                    raise RuntimeError(f"hand YOLO model missing: {HAND_YOLO_MODEL}")
                session = create_qnn_session(HAND_YOLO_MODEL, "qnn_htp")
                input_name = session.get_inputs()[0].name
                run_options = ort.RunOptions()
                run_options.add_run_config_entry("qnn.perf_mode", "burst")
                run_options.add_run_config_entry("qnn.rpc_control_latency", "100")
                set_hand_runtime("QNNExecutionProvider/HTP")
                providers = ", ".join(session.get_providers())
                set_hand_status(f"Hand YOLO ready: {providers}")
            except Exception as exc:
                set_hand_runtime("failed")
                set_hand_status(f"Hand YOLO QNN HTP failed: {exc}")
                session = None
                time.sleep(2)
                continue

        with frame_lock:
            frame = None if latest_frame is None else latest_frame.copy()

        if frame is None:
            time.sleep(0.05)
            continue

        try:
            input_data = preprocess(frame)
            start = time.time()
            outputs = session.run(None, {input_name: input_data}, run_options)
            inference_ms = (time.time() - start) * 1000
            hands = postprocess_hand_yolo(frame.shape, outputs[0], labels)
            update_hands(hands, inference_ms)
        except Exception as exc:
            set_hand_status(f"Hand YOLO failed: {exc}")
            session = None
            time.sleep(1)
            continue

        sleep_time = target_delay - (inference_ms / 1000)
        if sleep_time > 0:
            time.sleep(sleep_time)


def hand_tracking_thread():
    if HAND_MODEL_KIND in {"yolo", "gesture_yolo", "hand_yolo"}:
        hand_yolo_thread()
        return

    palm_path = HAND_GESTURE_MODEL_DIR / "palm_detector.onnx"
    landmark_path = HAND_GESTURE_MODEL_DIR / "hand_landmark_detector.repaired.onnx"
    if not landmark_path.exists():
        landmark_path = HAND_GESTURE_MODEL_DIR / "hand_landmark_detector.fixed.onnx"
    if not landmark_path.exists():
        landmark_path = HAND_GESTURE_MODEL_DIR / "hand_landmark_detector.onnx"
    classifier_path = HAND_GESTURE_MODEL_DIR / "canned_gesture_classifier.onnx"
    target_delay = 1 / HAND_TARGET_FPS if HAND_TARGET_FPS > 0 else 0
    palm_session = None
    landmark_session = None
    classifier_session = None

    def load_hand_sessions():
        missing = [str(path) for path in [palm_path, landmark_path, classifier_path] if not path.exists()]
        if missing:
            raise RuntimeError("ONNX hand gesture model missing: " + ", ".join(missing))

        backend = HAND_GESTURE_BACKEND.lower()
        hand_session_options = ort.SessionOptions()
        hand_session_options.graph_optimization_level = ort.GraphOptimizationLevel.ORT_DISABLE_ALL
        providers = ["CPUExecutionProvider"]
        if backend in {"qnn", "qnn_only", "qnn_gpu", "gpu", "qnn_cpu", "qnn_htp", "qnn_npu", "npu"}:
            require_qnn_only(hand_session_options)
            qnn_devices = get_qnn_devices(backend)
            hand_session_options.add_provider_for_devices(
                qnn_devices,
                {"backend_path": get_qnn_backend_path(backend)},
            )
            providers = None

        sessions = (
            ort.InferenceSession(str(palm_path), sess_options=hand_session_options, providers=providers),
            ort.InferenceSession(str(landmark_path), sess_options=hand_session_options, providers=providers),
            ort.InferenceSession(str(classifier_path), sess_options=hand_session_options, providers=providers),
        )
        provider_sets = [session.get_providers() for session in sessions]
        if backend in {"qnn", "qnn_only", "qnn_gpu", "gpu", "qnn_cpu", "qnn_htp", "qnn_npu", "npu"}:
            for path, active_providers in zip([palm_path, landmark_path, classifier_path], provider_sets):
                if "QNNExecutionProvider" not in active_providers:
                    raise RuntimeError(f"{path.name} did not enable QNNExecutionProvider: {active_providers}")
            return (*sessions, f"QNNExecutionProvider/{backend}")
        return (*sessions, "CPUExecutionProvider/int8")

    while True:
        if not model_enabled("hand"):
            palm_session = None
            landmark_session = None
            classifier_session = None
            with state_lock:
                latest_hands.clear()
            set_hand_status("disabled")
            time.sleep(0.5)
            continue

        if palm_session is None or landmark_session is None or classifier_session is None:
            try:
                palm_session, landmark_session, classifier_session, backend = load_hand_sessions()
                set_hand_runtime(backend)
                set_hand_status(f"ONNX Hand Gesture ready: {backend}")
            except Exception as exc:
                set_hand_runtime("failed")
                set_hand_status(f"ONNX hand gesture QNN failed: {exc}")
                time.sleep(2)
                continue

        with frame_lock:
            frame = None if latest_frame is None else latest_frame.copy()

        if frame is None:
            time.sleep(0.05)
            continue

        try:
            start = time.time()
            palm_input = preprocess_uint8_chw(frame, 256)
            palm_outputs = {
                output.name: value
                for output, value in zip(palm_session.get_outputs(), palm_session.run(None, {"image": palm_input}))
            }
            crop_box, palm_score = decode_best_palm(
                frame.shape,
                palm_outputs["box_coords"],
                palm_outputs["box_scores"],
            )
            if crop_box is None:
                update_hands([], (time.time() - start) * 1000)
                if target_delay > 0:
                    time.sleep(target_delay)
                continue

            x1, y1, x2, y2 = crop_box
            crop = frame[y1:y2, x1:x2]
            landmark_input = preprocess_uint8_chw(crop, 224)
            landmark_outputs = {
                output.name: value
                for output, value in zip(
                    landmark_session.get_outputs(),
                    landmark_session.run(None, {"image": landmark_input}),
                )
            }

            score = float(dequantize(landmark_outputs["scores"], 0.00390625, 0)[0, 0])
            lr = float(dequantize(landmark_outputs["lr"], 0.00390625, 0)[0, 0])
            landmarks = dequantize(landmark_outputs["landmarks"], 1.1389596462249756, 47)[0]
            gesture, gesture_score = classify_gesture(classifier_session, landmarks, lr)

            crop_w = max(1, x2 - x1)
            crop_h = max(1, y2 - y1)
            points = landmarks.reshape(21, 3)
            hand_landmarks = []
            for point in points:
                hand_landmarks.append(
                    {
                        "x": float(np.clip((x1 + (point[0] / 224.0) * crop_w) / frame.shape[1], 0.0, 1.0)),
                        "y": float(np.clip((y1 + (point[1] / 224.0) * crop_h) / frame.shape[0], 0.0, 1.0)),
                        "z": float(point[2] / 224.0),
                    }
                )

            hands = [
                {
                    "handedness": "Right" if lr >= 0.5 else "Left",
                    "score": score,
                    "palm_score": palm_score,
                    "gesture": gesture,
                    "gesture_score": gesture_score,
                    "box": [x1, y1, x2, y2],
                    "landmarks": hand_landmarks,
                }
            ]
            update_hands(hands, (time.time() - start) * 1000)
        except Exception as exc:
            set_hand_status(f"ONNX hand gesture failed: {exc}")
            palm_session = None
            landmark_session = None
            classifier_session = None
            time.sleep(1)

        if target_delay > 0:
            time.sleep(target_delay)


def draw_hands(img, hands):
    h, w = img.shape[:2]

    for hand in hands:
        if "box" in hand:
            x1, y1, x2, y2 = hand["box"]
            cv2.rectangle(img, (x1, y1), (x2, y2), (255, 190, 80), 2)

        points = []
        for lm in hand.get("landmarks", []):
            x = int(np.clip(lm["x"], 0.0, 1.0) * (w - 1))
            y = int(np.clip(lm["y"], 0.0, 1.0) * (h - 1))
            points.append((x, y))

        for start_idx, end_idx in HAND_CONNECTIONS:
            if start_idx < len(points) and end_idx < len(points):
                cv2.line(img, points[start_idx], points[end_idx], (255, 160, 40), 2)

        for idx, point in enumerate(points):
            radius = 5 if idx in {0, 4, 8, 12, 16, 20} else 3
            cv2.circle(img, point, radius, (40, 220, 255), -1)

        label = hand.get("gesture") or hand.get("handedness") or "Hand"
        gesture_score = hand.get("gesture_score")
        if gesture_score is not None:
            label = f"{label} {gesture_score:.2f}"
        if points:
            label_x = min(p[0] for p in points)
            label_y = max(22, min(p[1] for p in points) - 8)
            cv2.putText(img, label, (label_x, label_y), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (40, 220, 255), 2)
        elif "box" in hand:
            x1, y1, _, _ = hand["box"]
            cv2.putText(img, label, (x1, max(22, y1 - 8)), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (40, 220, 255), 2)


def draw_overlay(frame):
    with state_lock:
        loadcell_line = latest_loadcell_line
        loadcell_value = latest_loadcell_value
        loadcell_values = list(latest_loadcell_values)
        weight_directions = list(drawer_weight_directions)
        overlay_sensor_index = drawer_selected_sensor_index
        if overlay_sensor_index is None:
            overlay_sensor_index = PRIMARY_SENSOR_INDEX
        detections = list(latest_detections)
        hands = list(latest_hands)
        cam_status = camera_status
        lc_status = loadcell_status
        mm_status = magnet_status
        yy_status = yolo_status
        hh_status = hand_status
        dd_state = drawer_state
        dd_event = drawer_last_event
        dd_delta = drawer_weight_delta
        dd_item = drawer_current_item
        dd_drawer = drawer_current_drawer
        marker = dict(latest_drawer_marker)
    magnet_line, magnet_detected, magnet_mode = effective_magnet_state()

    img = frame.copy()

    for det in detections:
        x1, y1, x2, y2 = det["box"]
        label = f"{det['label']} {det['confidence']:.2f}"
        cv2.rectangle(img, (x1, y1), (x2, y2), (0, 255, 0), 2)
        label_y = max(20, y1 - 8)
        cv2.putText(img, label, (x1, label_y), cv2.FONT_HERSHEY_SIMPLEX, 0.55, (0, 255, 0), 2)

    draw_hands(img, hands)

    if marker.get("detected") and marker.get("bbox"):
        x1, y1, x2, y2 = marker["bbox"]
        color = (40, 40, 255) if marker.get("mismatch") else (255, 80, 220)
        cv2.rectangle(img, (x1, y1), (x2, y2), color, 2)
        marker_label = "Marker"
        if marker.get("open_percent") is not None:
            marker_label = f"Marker {marker['open_percent']:.1f}%"
        elif marker.get("position") is not None:
            marker_label = f"Marker {marker['position']:.3f}"
        cv2.putText(img, marker_label, (x1, max(22, y1 - 8)), cv2.FONT_HERSHEY_SIMPLEX, 0.55, color, 2)

    panel_height = 236
    overlay = img.copy()
    cv2.rectangle(overlay, (0, 0), (img.shape[1], panel_height), (0, 0, 0), -1)
    cv2.addWeighted(overlay, 0.62, img, 0.38, 0, img)

    applied_values = applied_loadcell_values(loadcell_values, weight_directions)
    overlay_raw_value = loadcell_values[overlay_sensor_index] if 0 <= overlay_sensor_index < SENSOR_COUNT else loadcell_value
    overlay_applied_value = applied_values[overlay_sensor_index] if 0 <= overlay_sensor_index < SENSOR_COUNT else loadcell_value
    overlay_direction = weight_directions[overlay_sensor_index] if 0 <= overlay_sensor_index < SENSOR_COUNT else drawer_weight_direction
    value_text = "Load cell: --"
    if overlay_applied_value is not None:
        value_text = f"Load cell {overlay_sensor_index + 1}: {overlay_applied_value:.0f} applied"
        if overlay_raw_value is not None:
            value_text += f" raw={overlay_raw_value:.0f} {overlay_direction}"
    magnet_text = "Magnet: --"
    if magnet_detected is not None:
        magnet_text = "Magnet: DETECTED" if magnet_detected else "Magnet: NOT DETECTED"
        if magnet_mode != "real":
            magnet_text += " (FORCED)"

    cv2.putText(img, value_text, (18, 34), cv2.FONT_HERSHEY_SIMPLEX, 0.85, (50, 255, 80), 2)
    cv2.putText(img, magnet_text, (300, 34), cv2.FONT_HERSHEY_SIMPLEX, 0.7, (80, 220, 255), 2)
    cv2.putText(img, loadcell_line[:72], (18, 62), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (230, 230, 230), 1)
    cv2.putText(img, magnet_line[:72], (18, 86), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (80, 220, 255), 1)
    cv2.putText(img, yy_status[:72], (18, 110), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (80, 220, 255), 1)
    cv2.putText(img, hh_status[:72], (18, 132), cv2.FONT_HERSHEY_SIMPLEX, 0.48, (255, 190, 80), 1)
    drawer_text = f"Drawer: {dd_state} / {dd_event}"
    if dd_delta is not None:
        drawer_text += f" / d={dd_delta:.1f}"
    cv2.putText(img, drawer_text[:78], (18, 154), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (255, 230, 90), 1)
    target_text = (
        f"Temi: item={dd_item or '--'} "
        f"drawer={dd_drawer if dd_drawer is not None else '--'}"
    )
    cv2.putText(img, target_text[:78], (18, 176), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (255, 230, 90), 1)
    marker_text = marker.get("status", "marker: --")
    if marker.get("mismatch"):
        marker_text = "SECURITY " + marker_text
    cv2.putText(img, marker_text[:78], (18, 198), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (255, 80, 220), 1)
    cv2.putText(img, cam_status[:32], (18, 220), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (200, 200, 200), 1)
    cv2.putText(img, lc_status[:34], (220, 220), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (200, 200, 200), 1)
    cv2.putText(img, mm_status[:34], (430, 220), cv2.FONT_HERSHEY_SIMPLEX, 0.38, (200, 200, 200), 1)
    return img


@app.route("/")
def index():
    return """
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>Roadchell Live Monitor</title>
  <style>
    * { box-sizing: border-box; }
    body {
      margin: 0;
      background: #101214;
      color: #e9edf0;
      font-family: Arial, sans-serif;
    }
    main {
      min-height: 100vh;
      display: grid;
      grid-template-columns: minmax(420px, 1.3fr) minmax(360px, 0.7fr);
      gap: 14px;
      padding: 14px;
    }
    .video-panel, .side-panel, .log-panel {
      border: 1px solid #2a3036;
      background: #161a1e;
      border-radius: 8px;
      overflow: hidden;
    }
    .video-panel img {
      width: 100%;
      background: #000;
      display: block;
      aspect-ratio: 4 / 3;
      object-fit: contain;
    }
    .status-grid {
      display: grid;
      grid-template-columns: repeat(2, minmax(0, 1fr));
      gap: 8px;
      padding: 10px;
      border-top: 1px solid #2a3036;
      font-size: 13px;
    }
    .status-item {
      min-height: 42px;
      padding: 8px;
      background: #111519;
      border: 1px solid #252b31;
      border-radius: 6px;
    }
    .label {
      display: block;
      margin-bottom: 4px;
      color: #95a1aa;
      font-size: 11px;
      text-transform: uppercase;
    }
    .value {
      display: block;
      color: #f2f5f6;
      overflow-wrap: anywhere;
      line-height: 1.25;
    }
    .ok { color: #60d979; }
    .warn { color: #ffcc66; }
    .yolo { color: #51d8ff; }
    .controls {
      display: flex;
      gap: 12px;
      flex-wrap: wrap;
      align-items: center;
    }
    .loadcell-actions {
      display: flex;
      gap: 8px;
      align-items: center;
      flex-wrap: wrap;
      margin-top: 8px;
    }
    .debug-send-form {
      display: grid;
      grid-template-columns: minmax(180px, 1fr) 72px auto auto;
      gap: 8px;
      align-items: end;
      width: 100%;
    }
    .sensor-switch {
      display: grid;
      grid-template-columns: repeat(3, minmax(0, 1fr));
      gap: 6px;
    }
    .sensor-button {
      min-width: 0;
      min-height: 36px;
      border: 1px solid #33404a;
      background: #131920;
      color: #d8dee2;
      border-radius: 6px;
      padding: 7px 8px;
      font: inherit;
      font-size: 12px;
      cursor: pointer;
    }
    .sensor-button:hover {
      background: #202a32;
      border-color: #4c5b66;
    }
    .sensor-button.active {
      background: #1d3b2a;
      border-color: #55b96b;
      color: #f2f5f6;
      font-weight: 700;
    }
    .action-button {
      border: 1px solid #33404a;
      background: #1e272e;
      color: #f2f5f6;
      border-radius: 6px;
      padding: 7px 10px;
      min-height: 34px;
      font: inherit;
      font-size: 12px;
      cursor: pointer;
    }
    .action-button:hover:not(:disabled) {
      background: #26313a;
      border-color: #4c5b66;
    }
    .action-button:disabled {
      cursor: wait;
      opacity: 0.65;
    }
    .inline-status {
      color: #95a1aa;
      font-size: 12px;
      line-height: 1.25;
      overflow-wrap: anywhere;
    }
    .toggle {
      display: inline-flex;
      gap: 6px;
      align-items: center;
      color: #d8dee2;
      cursor: pointer;
      user-select: none;
    }
    .toggle input {
      width: 18px;
      height: 18px;
      accent-color: #51d8ff;
    }
    .wide { grid-column: 1 / -1; }
    .scenario-form {
      display: grid;
      grid-template-columns: repeat(6, minmax(0, 1fr)) auto;
      gap: 8px;
      align-items: end;
    }
    .field {
      display: grid;
      gap: 4px;
      min-width: 0;
    }
    .field span {
      color: #95a1aa;
      font-size: 11px;
      text-transform: uppercase;
    }
    .field input, .field select {
      width: 100%;
      min-height: 34px;
      border: 1px solid #33404a;
      background: #0f1317;
      color: #f2f5f6;
      border-radius: 6px;
      padding: 7px 8px;
      font: inherit;
      font-size: 12px;
    }
    .debug-send-form .field input,
    .debug-send-form .field select {
      min-height: 34px;
    }
    .field.toggle-field {
      align-self: center;
      padding-top: 18px;
    }
    @media (max-width: 1100px) {
      .scenario-form { grid-template-columns: repeat(2, minmax(0, 1fr)); }
      .debug-send-form { grid-template-columns: repeat(2, minmax(0, 1fr)); }
    }
    .side-panel {
      display: grid;
      grid-auto-rows: minmax(126px, auto);
      gap: 0;
      max-height: calc(100vh - 28px);
      overflow-y: auto;
    }
    .side-panel.logs-collapsed { display: none; }
    .log-panel {
      display: flex;
      flex-direction: column;
      border-width: 0 0 1px 0;
      border-radius: 0;
      min-height: 0;
    }
    .log-panel:last-child { border-bottom: 0; }
    .log-head {
      display: flex;
      align-items: center;
      justify-content: space-between;
      padding: 8px 10px;
      border-bottom: 1px solid #2a3036;
      background: #1c2126;
      font-size: 12px;
      font-weight: 700;
    }
    .log-count {
      color: #95a1aa;
      font-weight: 400;
    }
    pre {
      flex: 1;
      min-height: 90px;
      max-height: 220px;
      margin: 0;
      padding: 8px 10px;
      overflow: auto;
      white-space: pre-wrap;
      word-break: break-word;
      color: #d8dee2;
      background: #12161a;
      font-size: 12px;
      line-height: 1.35;
    }
    @media (max-width: 900px) {
      main { grid-template-columns: 1fr; }
      .side-panel { max-height: 58vh; }
    }
  </style>
</head>
<body>
  <main>
    <section class="video-panel">
      <img src="/video_feed" alt="Camera stream">
      <div class="status-grid">
        <div class="status-item"><span class="label">Camera</span><span id="camera" class="value">...</span></div>
        <div class="status-item">
          <span class="label">Sensor Set</span>
          <span class="sensor-switch" role="group" aria-label="Sensor set selector">
            <button class="sensor-button active" data-sensor-set="1" type="button">1세트</button>
            <button class="sensor-button" data-sensor-set="2" type="button">2세트</button>
            <button class="sensor-button" data-sensor-set="3" type="button">3세트</button>
          </span>
        </div>
        <div class="status-item">
          <span class="label">Load Cell</span>
          <span id="loadcell" class="value ok">...</span>
          <span class="loadcell-actions">
            <button id="loadcell-init" class="action-button" type="button">초기화</button>
            <span id="loadcell-init-status" class="inline-status"></span>
          </span>
        </div>
        <div class="status-item wide"><span class="label">Load Cells</span><span id="loadcell-details" class="value">...</span></div>
        <div class="status-item">
          <span class="label">Magnet</span>
          <span id="magnet" class="value">...</span>
          <span class="loadcell-actions">
            <select id="magnet-override" class="action-button">
              <option value="real">Real</option>
              <option value="closed">Force closed</option>
              <option value="open">Force open</option>
            </select>
            <span id="magnet-override-status" class="inline-status"></span>
          </span>
        </div>
        <div class="status-item"><span class="label">Marker</span><span id="marker" class="value">...</span></div>
        <div class="status-item"><span class="label">YOLO</span><span id="yolo" class="value yolo">...</span></div>
        <div class="status-item"><span class="label">Hands</span><span id="hands" class="value">...</span></div>
        <div class="status-item">
          <span class="label">Model Controls</span>
          <span class="value controls">
            <label class="toggle"><input id="hand-toggle" type="checkbox"> Hand</label>
            <label class="toggle"><input id="yolo-toggle" type="checkbox"> YOLO</label>
            <label class="toggle"><input id="marker-toggle" type="checkbox"> Marker</label>
          </span>
        </div>
        <div class="status-item">
          <span class="label">Debug Logs</span>
          <div class="loadcell-actions debug-send-form">
            <label class="field"><span>Time</span><input id="debug-success-time" type="datetime-local"></label>
            <label class="field"><span>No.</span><select id="debug-success-drawer"><option value="1">1</option><option value="2">2</option><option value="3">3</option></select></label>
            <button id="logs-toggle" class="action-button" type="button" aria-expanded="true">로그 닫기</button>
            <button id="debug-success-log" class="action-button" type="button">성공 전송</button>
            <span id="debug-success-status" class="inline-status"></span>
          </div>
        </div>
        <div class="status-item wide">
          <span class="label">Scenario Setup</span>
          <div class="scenario-form">
            <label class="field"><span>Server/IP</span><input id="scenario-server" type="text"></label>
            <div class="field toggle-field"><span>Manual</span><label class="toggle"><input id="scenario-manual" type="checkbox"> Web target</label></div>
            <label class="field"><span>Drawer</span><input id="scenario-drawer-label" type="text"></label>
            <label class="field"><span>No.</span><input id="scenario-drawer-number" type="number" inputmode="numeric"></label>
            <label class="field"><span>In/Out</span><select id="scenario-direction"><option value="in">in</option><option value="out">out</option></select></label>
            <label class="field"><span>Weight</span><select id="scenario-weight-direction"><option value="up">up</option><option value="down">down</option><option value="either">either</option></select></label>
            <label class="field"><span>Weight Sets</span><input id="scenario-weight-directions" type="text" placeholder="down,up,up"></label>
            <label class="field"><span>Item</span><input id="scenario-item" type="text"></label>
            <button id="scenario-save" class="action-button" type="button">저장</button>
          </div>
          <span id="scenario-save-status" class="inline-status"></span>
        </div>
        <div class="status-item"><span class="label">Comms</span><span id="comms" class="value warn">not configured</span></div>
        <div class="status-item wide"><span class="label">Last Send</span><span id="last-send" class="value">...</span></div>
      </div>
    </section>
    <aside id="logs-panel" class="side-panel">
      <section class="log-panel">
        <div class="log-head"><span>LOAD CELL</span><span id="loadcell-count" class="log-count">0</span></div>
        <pre id="loadcell-log"></pre>
      </section>
      <section class="log-panel">
        <div class="log-head"><span>MAGNET</span><span id="magnet-count" class="log-count">0</span></div>
        <pre id="magnet-log"></pre>
      </section>
      <section class="log-panel">
        <div class="log-head"><span>MARKER</span><span id="marker-count" class="log-count">0</span></div>
        <pre id="marker-log"></pre>
      </section>
      <section class="log-panel">
        <div class="log-head"><span>YOLO</span><span id="yolo-count" class="log-count">0</span></div>
        <pre id="yolo-log"></pre>
      </section>
      <section class="log-panel">
        <div class="log-head"><span>HAND GESTURE</span><span id="hand-count" class="log-count">0</span></div>
        <pre id="hand-log"></pre>
      </section>
      <section class="log-panel">
        <div class="log-head"><span>COMMS</span><span id="comms-count" class="log-count">0</span></div>
        <pre id="comms-log"></pre>
      </section>
      <section class="log-panel">
        <div class="log-head"><span>SERVER POST</span><span id="server-count" class="log-count">0</span></div>
        <pre id="server-log"></pre>
      </section>
    </aside>
  </main>
  <script>
    let selectedSensorSet = Number(localStorage.getItem('selectedSensorSet') || '1');
    if (!Number.isInteger(selectedSensorSet) || selectedSensorSet < 1 || selectedSensorSet > 3) {
      selectedSensorSet = 1;
    }

    function selectedIndex() {
      return selectedSensorSet - 1;
    }

    function formatNumber(value) {
      if (value === null || value === undefined || Number.isNaN(Number(value))) {
        return '--';
      }
      return Number(value).toFixed(0);
    }

    function formatSelectedLoadcell(data) {
      const values = Array.isArray(data.loadcell_values) ? data.loadcell_values : [];
      const appliedValues = Array.isArray(data.loadcell_applied_values) ? data.loadcell_applied_values : [];
      const drawer = data.drawer_scenario || {};
      const deltas = Array.isArray(drawer.weight_deltas) ? drawer.weight_deltas : [];
      const liveDeltas = Array.isArray(data.loadcell_live_deltas) ? data.loadcell_live_deltas : [];
      const liveEffectiveDeltas = Array.isArray(data.loadcell_live_effective_deltas) ? data.loadcell_live_effective_deltas : [];
      const directions = Array.isArray(drawer.weight_directions)
        ? drawer.weight_directions
        : (Array.isArray(data.scenario_config?.weight_directions) ? data.scenario_config.weight_directions : []);
      const value = values[selectedIndex()];
      const appliedValue = appliedValues[selectedIndex()];
      const direction = directions[selectedIndex()] || data.scenario_config?.weight_direction || 'up';
      const delta = deltas[selectedIndex()];
      const liveDelta = liveDeltas[selectedIndex()];
      const liveEff = liveEffectiveDeltas[selectedIndex()];
      const eff = liveEff ?? effectiveDelta(delta, direction);
      let text = selectedSensorSet + '세트: ';
      if (eff !== null && eff !== undefined) {
        text += 'move=' + Number(eff).toFixed(0) + ' applied';
        const shownDelta = liveDelta ?? delta;
        if (shownDelta !== null && shownDelta !== undefined) {
          text += ' / delta=' + Number(shownDelta).toFixed(0);
        }
      } else {
        text += formatNumber(appliedValue) + ' applied';
      }
      if (value !== null && value !== undefined) {
        text += ' / raw=' + formatNumber(value);
      }
      text += ' / ' + direction;
      if (data.primary_sensor_index === selectedSensorSet) {
        text += ' (검증 기준)';
      }
      if (appliedValue === null || appliedValue === undefined) {
        text += ' / ' + (data.loadcell_status || 'waiting');
      }
      return text;
    }

    function effectiveDelta(delta, direction) {
      if (delta === null || delta === undefined || Number.isNaN(Number(delta))) {
        return null;
      }
      const value = Number(delta);
      if (direction === 'down') {
        return -value;
      }
      if (direction === 'either') {
        return Math.abs(value);
      }
      return value;
    }

    function formatLoadcellDetails(data) {
      const values = Array.isArray(data.loadcell_values) ? data.loadcell_values : [];
      const appliedValues = Array.isArray(data.loadcell_applied_values) ? data.loadcell_applied_values : [];
      const drawer = data.drawer_scenario || {};
      const deltas = Array.isArray(drawer.weight_deltas) ? drawer.weight_deltas : [];
      const liveDeltas = Array.isArray(data.loadcell_live_deltas) ? data.loadcell_live_deltas : [];
      const liveEffectiveDeltas = Array.isArray(data.loadcell_live_effective_deltas) ? data.loadcell_live_effective_deltas : [];
      const directions = Array.isArray(drawer.weight_directions)
        ? drawer.weight_directions
        : (Array.isArray(data.scenario_config?.weight_directions) ? data.scenario_config.weight_directions : []);
      const threshold = drawer.weight_threshold ?? data.scenario_config?.weight_threshold;
      const parts = [0, 1, 2].map((index) => {
        const direction = directions[index] || data.scenario_config?.weight_direction || 'up';
        const delta = liveDeltas[index] ?? deltas[index];
        const eff = liveEffectiveDeltas[index] ?? effectiveDelta(delta, direction);
        let text = `${index + 1}: ${formatNumber(appliedValues[index])} applied`;
        if (values[index] !== null && values[index] !== undefined) {
          text += ` raw=${formatNumber(values[index])}`;
        }
        text += ` ${direction}`;
        if (delta !== null && delta !== undefined) {
          text += ` delta=${Number(delta).toFixed(0)} eff=${Number(eff).toFixed(0)}`;
        }
        return text;
      });
      if (threshold !== null && threshold !== undefined) {
        parts.push(`threshold=${Number(threshold).toFixed(0)}`);
      }
      return parts.join(' | ');
    }

    function formatSelectedMagnet(data) {
      const detectedValues = Array.isArray(data.magnet_detected_values) ? data.magnet_detected_values : [];
      const rawValues = Array.isArray(data.magnet_raw_values) ? data.magnet_raw_values : [];
      const detected = detectedValues[selectedIndex()];
      const raw = rawValues[selectedIndex()];
      let state = '--';
      if (detected !== null && detected !== undefined) {
        state = detected ? 'DETECTED' : 'NOT_DETECTED';
      }
      let text = selectedSensorSet + '세트: ' + state;
      if (raw !== null && raw !== undefined) {
        text += ' / raw=' + raw;
      }
      if (data.primary_sensor_index === selectedSensorSet) {
        text += ' (검증 기준)';
      }
      if (data.magnet_override && data.magnet_override !== 'real') {
        text += ' / forced=' + data.magnet_override;
      }
      return text;
    }

    function updateSensorButtons() {
      document.querySelectorAll('[data-sensor-set]').forEach((button) => {
        const isActive = Number(button.dataset.sensorSet) === selectedSensorSet;
        button.classList.toggle('active', isActive);
        button.setAttribute('aria-pressed', String(isActive));
      });
    }

    function renderLog(channel, entries) {
      const logEl = document.getElementById(channel + '-log');
      const countEl = document.getElementById(channel + '-count');
      logEl.textContent = entries.map((entry) => `[${entry.ts}] ${entry.message}`).join('\\n');
      countEl.textContent = entries.length;
      logEl.scrollTop = logEl.scrollHeight;
    }

    function setFieldValue(id, value) {
      const el = document.getElementById(id);
      if (document.activeElement !== el) {
        el.value = value ?? '';
      }
    }

    async function refreshStatus() {
      const res = await fetch('/status');
      const data = await res.json();
      document.getElementById('camera').textContent = data.camera_status;
      updateSensorButtons();
      document.getElementById('loadcell').textContent = formatSelectedLoadcell(data);
      document.getElementById('loadcell-details').textContent = formatLoadcellDetails(data);
      document.getElementById('magnet').textContent = formatSelectedMagnet(data);
      if (data.magnet_override) {
        const magnetOverride = document.getElementById('magnet-override');
        if (document.activeElement !== magnetOverride) {
          magnetOverride.value = data.magnet_override;
        }
      }
      if (data.drawer_marker) {
        let markerText = data.drawer_marker.status || '...';
        if (data.drawer_marker.mismatch) {
          markerText = 'SECURITY ' + markerText;
        }
        document.getElementById('marker').textContent = markerText;
      }
      document.getElementById('yolo').textContent = data.yolo_status;
      document.getElementById('hands').textContent = data.hand_status;
      if (data.drawer_scenario) {
        const drawer = data.drawer_scenario;
        let text = drawer.state + ' / ' + drawer.last_event;
        if (drawer.temi_connected === true) {
          text += ' / temi=connected';
        } else if (drawer.temi_connected === false) {
          text += ' / temi=disconnected';
        } else {
          text += ' / temi=not checked';
        }
        if (drawer.current_item) {
          text += ' / item=' + drawer.current_item;
        }
        if (drawer.current_drawer !== null && drawer.current_drawer !== undefined) {
          text += ' / drawer=' + drawer.current_drawer;
        }
        if (drawer.weight_delta !== null && drawer.weight_delta !== undefined) {
          text += ' / delta=' + Number(drawer.weight_delta).toFixed(1);
        }
        document.getElementById('comms').textContent = text;
        document.getElementById('last-send').textContent = drawer.last_server_response || 'no server response yet';
      }
      document.getElementById('hand-toggle').checked = !!data.controls.hand_enabled;
      document.getElementById('yolo-toggle').checked = !!data.controls.yolo_enabled;
      document.getElementById('marker-toggle').checked = !!data.controls.marker_enabled;
      if (data.scenario_config) {
        setFieldValue('scenario-server', data.scenario_config.server_url);
        document.getElementById('scenario-manual').checked = !!data.scenario_config.manual_active;
        setFieldValue('scenario-drawer-label', data.scenario_config.drawer_label);
        setFieldValue('scenario-drawer-number', data.scenario_config.drawer_number);
        setFieldValue('scenario-direction', data.scenario_config.direction || 'in');
        setFieldValue('scenario-weight-direction', data.scenario_config.weight_direction || 'up');
        setFieldValue(
          'scenario-weight-directions',
          Array.isArray(data.scenario_config.weight_directions)
            ? data.scenario_config.weight_directions.join(',')
            : ''
        );
        setFieldValue('scenario-item', data.scenario_config.item_name);
      }
    }

    async function refreshLogs() {
      const res = await fetch('/logs');
      const data = await res.json();
      renderLog('loadcell', data.loadcell || []);
      renderLog('magnet', data.magnet || []);
      renderLog('marker', data.marker || []);
      renderLog('yolo', data.yolo || []);
      renderLog('hand', data.hand || []);
      renderLog('comms', data.comms || []);
      renderLog('server', data.server || []);
    }

    async function refreshAll() {
      await refreshStatus();
      await refreshLogs();
    }

    function setDefaultDebugSuccessTime() {
      const input = document.getElementById('debug-success-time');
      if (!input || input.value) {
        return;
      }
      const now = new Date();
      now.setSeconds(0, 0);
      const local = new Date(now.getTime() - now.getTimezoneOffset() * 60000);
      input.value = local.toISOString().slice(0, 16);
    }

    setInterval(refreshStatus, 1000);
    setInterval(refreshLogs, 2500);
    setDefaultDebugSuccessTime();
    refreshAll();

    async function setControl(name, enabled) {
      await fetch('/control', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ [name]: enabled })
      });
      refreshAll();
    }

    async function saveScenarioConfig() {
      const button = document.getElementById('scenario-save');
      const status = document.getElementById('scenario-save-status');
      button.disabled = true;
      status.textContent = '저장 중...';
      const drawerNumber = document.getElementById('scenario-drawer-number').value;
      try {
        const res = await fetch('/scenario-config', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({
            server_url: document.getElementById('scenario-server').value,
            manual_active: document.getElementById('scenario-manual').checked,
            drawer_label: document.getElementById('scenario-drawer-label').value,
            drawer_number: drawerNumber === '' ? null : Number(drawerNumber),
            direction: document.getElementById('scenario-direction').value,
            weight_direction: document.getElementById('scenario-weight-direction').value,
            weight_directions: document.getElementById('scenario-weight-directions').value,
            item_name: document.getElementById('scenario-item').value
          })
        });
        const data = await res.json();
        if (!res.ok || !data.ok) {
          throw new Error(data.error || 'request failed');
        }
        status.textContent = '저장됨';
      } catch (err) {
        status.textContent = '실패: ' + err.message;
      } finally {
        button.disabled = false;
        refreshAll();
        setTimeout(() => {
          if (!button.disabled && status.textContent !== '저장 중...') {
            status.textContent = '';
          }
        }, 3500);
      }
    }

    async function initLoadcell() {
      const button = document.getElementById('loadcell-init');
      const status = document.getElementById('loadcell-init-status');
      button.disabled = true;
      status.textContent = '초기화 중...';
      try {
        const res = await fetch('/loadcell/init', { method: 'POST' });
        const data = await res.json();
        if (!res.ok || !data.ok) {
          throw new Error(data.error || 'request failed');
        }
        status.textContent = '요청 완료';
      } catch (err) {
        status.textContent = '실패: ' + err.message;
      } finally {
        button.disabled = false;
        refreshAll();
        setTimeout(() => {
          if (!button.disabled && status.textContent !== '초기화 중...') {
            status.textContent = '';
          }
        }, 3500);
      }
    }

    async function sendDebugSuccessLog() {
      const button = document.getElementById('debug-success-log');
      const status = document.getElementById('debug-success-status');
      const timeInput = document.getElementById('debug-success-time');
      const drawerSelect = document.getElementById('debug-success-drawer');
      button.disabled = true;
      status.textContent = '전송 중...';
      try {
        const payload = {
          drawer_number: Number(drawerSelect.value)
        };
        if (timeInput.value) {
          const selectedTime = new Date(timeInput.value);
          if (Number.isNaN(selectedTime.getTime())) {
            throw new Error('invalid time');
          }
          payload.timestamp = selectedTime.toISOString().replace('.000Z', 'Z');
        }
        const res = await fetch('/debug/temi-success', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify(payload)
        });
        const data = await res.json();
        if (!res.ok || !data.ok) {
          throw new Error(data.error || 'request failed');
        }
        status.textContent = `성공 전송됨: ${payload.drawer_number}`;
      } catch (err) {
        status.textContent = '실패: ' + err.message;
      } finally {
        button.disabled = false;
        refreshAll();
        setTimeout(() => {
          if (!button.disabled && status.textContent !== '전송 중...') {
            status.textContent = '';
          }
        }, 3500);
      }
    }

    async function setMagnetOverride(mode) {
      const status = document.getElementById('magnet-override-status');
      status.textContent = '변경 중...';
      try {
        const res = await fetch('/magnet-override', {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          body: JSON.stringify({ mode })
        });
        const data = await res.json();
        if (!res.ok || !data.ok) {
          throw new Error(data.error || 'request failed');
        }
        status.textContent = '적용됨';
      } catch (err) {
        status.textContent = '실패: ' + err.message;
      } finally {
        refreshAll();
        setTimeout(() => {
          if (status.textContent !== '변경 중...') {
            status.textContent = '';
          }
        }, 2500);
      }
    }

    document.getElementById('loadcell-init').addEventListener('click', initLoadcell);
    document.getElementById('debug-success-log').addEventListener('click', sendDebugSuccessLog);
    document.getElementById('scenario-save').addEventListener('click', saveScenarioConfig);
    document.getElementById('magnet-override').addEventListener('change', (event) => {
      setMagnetOverride(event.target.value);
    });
    document.querySelectorAll('[data-sensor-set]').forEach((button) => {
      button.addEventListener('click', () => {
        selectedSensorSet = Number(button.dataset.sensorSet);
        localStorage.setItem('selectedSensorSet', String(selectedSensorSet));
        updateSensorButtons();
        refreshStatus();
      });
    });
    document.getElementById('logs-toggle').addEventListener('click', () => {
      const panel = document.getElementById('logs-panel');
      const button = document.getElementById('logs-toggle');
      const collapsed = panel.classList.toggle('logs-collapsed');
      button.textContent = collapsed ? '로그 열기' : '로그 닫기';
      button.setAttribute('aria-expanded', String(!collapsed));
    });
    document.getElementById('hand-toggle').addEventListener('change', (event) => {
      setControl('hand_enabled', event.target.checked);
    });
    document.getElementById('yolo-toggle').addEventListener('change', (event) => {
      setControl('yolo_enabled', event.target.checked);
    });
    document.getElementById('marker-toggle').addEventListener('change', (event) => {
      setControl('marker_enabled', event.target.checked);
    });
  </script>
</body>
</html>
"""


@app.route("/logs")
def logs():
    with log_lock:
        return jsonify({name: list(entries) for name, entries in log_channels.items()})


@app.route("/control", methods=["POST"])
def control():
    data = request.get_json(silent=True) or {}
    if "hand_enabled" in data:
        set_model_enabled("hand", bool(data["hand_enabled"]))
    if "yolo_enabled" in data:
        set_model_enabled("yolo", bool(data["yolo_enabled"]))
    if "marker_enabled" in data:
        set_model_enabled("marker", bool(data["marker_enabled"]))
    return jsonify(controls=get_model_controls())


@app.route("/scenario-config", methods=["POST"])
def scenario_config():
    data = request.get_json(silent=True) or {}
    try:
        set_runtime_scenario_config(data)
        return jsonify(ok=True, scenario_config=scenario_config_snapshot())
    except Exception as exc:
        add_log("comms", f"scenario config failed: {exc}")
        return jsonify(ok=False, error=str(exc)), 400


@app.route("/loadcell/init", methods=["POST"])
def loadcell_init():
    global loadcell_baseline_pending
    try:
        with state_lock:
            loadcell_baseline_pending = True
        source, result = init_loadcell_tare()
        return jsonify(ok=True, source=source, result=result, software_baseline="pending")
    except Exception as exc:
        with state_lock:
            loadcell_baseline_pending = False
        add_log("loadcell", f"tare init failed: {exc}")
        return jsonify(ok=False, error=str(exc)), 503


@app.route("/magnet-override", methods=["POST"])
def magnet_override_route():
    data = request.get_json(silent=True) or {}
    try:
        set_magnet_override(data.get("mode", "real"))
        line, detected, mode = effective_magnet_state()
        return jsonify(ok=True, mode=mode, magnet_line=line, magnet_detected=detected)
    except Exception as exc:
        add_log("magnet", f"override failed: {exc}")
        return jsonify(ok=False, error=str(exc)), 400


@app.route("/debug/temi-success", methods=["POST"])
def debug_temi_success():
    data = request.get_json(silent=True) or {}
    options = {}
    if data.get("drawer_number") not in (None, ""):
        try:
            drawer_number = int(data.get("drawer_number"))
        except (TypeError, ValueError):
            return jsonify(ok=False, error="drawer_number must be 1, 2, or 3"), 400
        if drawer_number not in {1, 2, 3}:
            return jsonify(ok=False, error="drawer_number must be 1, 2, or 3"), 400
        options["drawer_number"] = drawer_number
    timestamp = str(data.get("timestamp") or "").strip()
    if timestamp:
        options["timestamp"] = timestamp
    result = send_debug_temi_success_event(options)
    return jsonify(result), 200 if result.get("ok") else 503


@app.route("/status")
def status():
    magnet_line, magnet_detected, magnet_mode = effective_magnet_state()
    with state_lock:
        verification_sensor_index = (
            drawer_selected_sensor_index + 1
            if drawer_selected_sensor_index is not None
            else PRIMARY_SENSOR_INDEX + 1
        )
        loadcell_values = list(latest_loadcell_values)
        weight_directions = list(drawer_weight_directions)
        live_weight_results = evaluate_sensor_weight_changes(
            drawer_initial_weights,
            loadcell_values,
            weight_directions,
        )
        return jsonify(
            controls=get_model_controls(),
            camera_status=camera_status,
            loadcell_status=loadcell_status,
            loadcell_baseline_pending=loadcell_baseline_pending,
            loadcell_line=latest_loadcell_line,
            loadcell_value=latest_loadcell_value,
            loadcell_values=loadcell_values,
            loadcell_applied_values=applied_loadcell_values(loadcell_values, weight_directions),
            loadcell_live_raw_deltas=[result["weight_delta"] for result in live_weight_results],
            loadcell_live_deltas=[result["weight_effective_delta"] for result in live_weight_results],
            loadcell_live_effective_deltas=[result["weight_effective_delta"] for result in live_weight_results],
            primary_sensor_index=verification_sensor_index,
            verification_sensor_index=verification_sensor_index,
            magnet_status=magnet_status,
            magnet_line=magnet_line,
            magnet_detected=magnet_detected,
            magnet_detected_values=latest_magnet_detected_values,
            magnet_raw_values=latest_magnet_raw_values,
            magnet_override=magnet_mode,
            magnet_raw_line=latest_magnet_line,
            magnet_raw_detected=latest_magnet_detected,
            yolo_status=yolo_status,
            yolo_inference_ms=yolo_inference_ms,
            yolo_fps=yolo_fps,
            detections=latest_detections,
            hand_status=hand_status,
            hand_runtime=hand_runtime,
            hand_inference_ms=hand_inference_ms,
            hand_fps=hand_fps,
            hands=latest_hands,
            drawer_scenario=drawer_snapshot(),
            drawer_marker=marker_snapshot(),
            scenario_config=scenario_config_snapshot(),
        )


@app.route("/video_feed")
def video_feed():
    def stream():
        while True:
            with frame_lock:
                frame = None if latest_frame is None else latest_frame.copy()

            if frame is None:
                time.sleep(0.05)
                continue

            frame = draw_overlay(frame)
            ok, buffer = cv2.imencode(".jpg", frame, [cv2.IMWRITE_JPEG_QUALITY, 80])
            if ok:
                yield b"--frame\r\nContent-Type: image/jpeg\r\n\r\n" + buffer.tobytes() + b"\r\n"
            time.sleep(1 / TARGET_FPS)

    return Response(stream(), mimetype="multipart/x-mixed-replace; boundary=frame")


if __name__ == "__main__":
    add_log("system", "app starting")
    add_log("comms", f"Temi configured: {TEMI_SERVER_URL}")
    threading.Thread(target=camera_thread, daemon=True).start()
    threading.Thread(target=loadcell_thread, daemon=True).start()
    threading.Thread(target=drawer_marker_thread, daemon=True).start()
    threading.Thread(target=yolo_thread, daemon=True).start()
    threading.Thread(target=hand_tracking_thread, daemon=True).start()
    threading.Thread(target=drawer_scenario_thread, daemon=True).start()
    app.run(host="0.0.0.0", port=WEB_PORT, threaded=True, use_reloader=False)
