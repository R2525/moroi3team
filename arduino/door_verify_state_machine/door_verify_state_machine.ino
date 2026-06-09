/*
 * Arduino Uno Q Door + Camera + Load Cell Verification State Machine
 *
 * Hardware:
 * - HX711 DT  -> D3
 * - HX711 SCK -> D2
 * - Reed DO   -> D4
 * - Serial    -> 115200 baud
 *
 * Protocol to upper server:
 * - Arduino -> Server: DOOR_OPEN
 * - Server  -> Arduino: HAND_RELEASED
 * - Arduino -> Server: VERIFY_SUCCESS or VERIFY_FAIL
 */

#include <HX711.h>

const uint8_t LOADCELL_DOUT_PIN = 3;
const uint8_t LOADCELL_SCK_PIN = 2;
const uint8_t REED_SWITCH_PIN = 4;

// With INPUT_PULLUP, a common reed module/switch reads LOW when magnet is present.
const int REED_CLOSED_LEVEL = LOW;

const long SERIAL_BAUD = 115200;
const float CALIBRATION_FACTOR = 1.0f;  // Replace with your calibrated scale factor for grams.
const float VERIFY_THRESHOLD_G = 10.0f;
const uint8_t WEIGHT_SAMPLES = 5;

const unsigned long DEBOUNCE_MS = 40;
const size_t COMMAND_BUFFER_SIZE = 48;

enum SystemState {
  STATE_IDLE,
  STATE_WAIT_CAMERA,
  STATE_VERIFY,
  STATE_WAIT_CLOSE
};

HX711 scale;

SystemState currentState = STATE_IDLE;

float initialWeight = 0.0f;
float finalWeight = 0.0f;

int lastRawDoorReading = HIGH;
bool stableDoorClosed = false;
bool previousDoorClosed = false;
unsigned long lastDoorChangeMs = 0;

char serialBuffer[COMMAND_BUFFER_SIZE];
size_t serialIndex = 0;

const char* stateName(SystemState state) {
  switch (state) {
    case STATE_IDLE:
      return "STATE_IDLE";
    case STATE_WAIT_CAMERA:
      return "STATE_WAIT_CAMERA";
    case STATE_VERIFY:
      return "STATE_VERIFY";
    case STATE_WAIT_CLOSE:
      return "STATE_WAIT_CLOSE";
    default:
      return "UNKNOWN";
  }
}

void debugStateChange(SystemState nextState) {
  Serial.print("STATE:");
  Serial.println(stateName(nextState));
}

void setState(SystemState nextState) {
  if (currentState == nextState) {
    return;
  }

  currentState = nextState;
  debugStateChange(currentState);
}

bool updateDoorDebounce() {
  const int raw = digitalRead(REED_SWITCH_PIN);
  const unsigned long now = millis();

  if (raw != lastRawDoorReading) {
    lastRawDoorReading = raw;
    lastDoorChangeMs = now;
  }

  if (now - lastDoorChangeMs >= DEBOUNCE_MS) {
    stableDoorClosed = (raw == REED_CLOSED_LEVEL);
  }

  return stableDoorClosed;
}

bool doorOpenedEdge() {
  return previousDoorClosed && !stableDoorClosed;
}

bool doorClosedEdge() {
  return !previousDoorClosed && stableDoorClosed;
}

float readWeightGrams() {
  if (!scale.is_ready()) {
    Serial.println("WARN:HX711_NOT_READY");
    return scale.get_units(1);
  }

  return scale.get_units(WEIGHT_SAMPLES);
}

bool readSerialLine(char* out, size_t outSize) {
  while (Serial.available() > 0) {
    const char c = (char)Serial.read();

    if (c == '\r') {
      continue;
    }

    if (c == '\n') {
      serialBuffer[serialIndex] = '\0';

      const size_t copyLen = min(serialIndex, outSize - 1);
      memcpy(out, serialBuffer, copyLen);
      out[copyLen] = '\0';

      serialIndex = 0;
      return copyLen > 0;
    }

    if (serialIndex < COMMAND_BUFFER_SIZE - 1) {
      serialBuffer[serialIndex++] = c;
    } else {
      serialIndex = 0;
      Serial.println("WARN:SERIAL_BUFFER_OVERFLOW");
    }
  }

  return false;
}

void handleIdle() {
  if (!doorOpenedEdge()) {
    return;
  }

  initialWeight = readWeightGrams();

  Serial.print("INITIAL_WEIGHT:");
  Serial.println(initialWeight, 2);
  Serial.println("DOOR_OPEN");

  setState(STATE_WAIT_CAMERA);
}

void handleWaitCamera() {
  char command[COMMAND_BUFFER_SIZE];

  if (!readSerialLine(command, sizeof(command))) {
    return;
  }

  Serial.print("RX:");
  Serial.println(command);

  if (strcmp(command, "HAND_RELEASED") == 0) {
    setState(STATE_VERIFY);
  }
}

void handleVerify() {
  finalWeight = readWeightGrams();
  const float delta = finalWeight - initialWeight;

  Serial.print("FINAL_WEIGHT:");
  Serial.println(finalWeight, 2);
  Serial.print("WEIGHT_DELTA:");
  Serial.println(delta, 2);

  if (delta >= VERIFY_THRESHOLD_G) {
    Serial.println("VERIFY_SUCCESS");
  } else {
    Serial.println("VERIFY_FAIL");
  }

  setState(STATE_WAIT_CLOSE);
}

void handleWaitClose() {
  if (doorClosedEdge()) {
    setState(STATE_IDLE);
  }
}

void setup() {
  Serial.begin(SERIAL_BAUD);

  pinMode(REED_SWITCH_PIN, INPUT_PULLUP);

  scale.begin(LOADCELL_DOUT_PIN, LOADCELL_SCK_PIN);
  scale.set_scale(CALIBRATION_FACTOR);

  Serial.println("BOOT:DOOR_VERIFY_STATE_MACHINE");
  Serial.println("TARE:START");
  scale.tare();
  Serial.println("TARE:DONE");

  lastRawDoorReading = digitalRead(REED_SWITCH_PIN);
  stableDoorClosed = (lastRawDoorReading == REED_CLOSED_LEVEL);
  previousDoorClosed = stableDoorClosed;
  lastDoorChangeMs = millis();

  debugStateChange(currentState);
}

void loop() {
  updateDoorDebounce();

  switch (currentState) {
    case STATE_IDLE:
      handleIdle();
      break;
    case STATE_WAIT_CAMERA:
      handleWaitCamera();
      break;
    case STATE_VERIFY:
      handleVerify();
      break;
    case STATE_WAIT_CLOSE:
      handleWaitClose();
      break;
  }

  previousDoorClosed = stableDoorClosed;
}
