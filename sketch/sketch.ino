/*
 * UNO Q MCU sensor bridge for 3 load cells and 3 reed switches.
 *
 * Provides RPC methods for the Linux/Python side:
 * - loadcell_read, loadcell_read_1, loadcell_read_2, loadcell_read_3
 * - loadcell_init
 * - magnet_read, magnet_read_1, magnet_read_2, magnet_read_3
 * - magnet_raw_read, magnet_raw_read_1, magnet_raw_read_2, magnet_raw_read_3
 *
 * HX711 reads are intentionally non-blocking. The RPC callbacks return the
 * latest sampled value so RouterBridge stays responsive even if HX711 is slow.
 */

#include "HX711.h"
#include <Arduino_RouterBridge.h>

const int SENSOR_COUNT = 3;
const int LOADCELL_DOUT_PINS[SENSOR_COUNT] = {5, 7, 12};
const int LOADCELL_SCK_PINS[SENSOR_COUNT] = {6, 8, 13};
const int REED_SWITCH_PINS[SENSOR_COUNT] = {9, 10, 11};
const int REED_DETECTED_LEVEL = LOW;

const unsigned long SENSOR_POLL_MS = 20;
const unsigned long MONITOR_PRINT_MS = 500;
const int TARE_SAMPLE_COUNT = 20;
const long HX711_SATURATION_LIMIT = 8300000L;

HX711 scales[SENSOR_COUNT];

float latestReadings[SENSOR_COUNT] = {0.0, 0.0, 0.0};
float latestRawReadings[SENSOR_COUNT] = {0.0, 0.0, 0.0};
float latestStableRawReadings[SENSOR_COUNT] = {0.0, 0.0, 0.0};
float tareAccumulators[SENSOR_COUNT] = {0.0, 0.0, 0.0};
float tareOffsets[SENSOR_COUNT] = {0.0, 0.0, 0.0};
int tareSamples[SENSOR_COUNT] = {0, 0, 0};
bool tareComplete[SENSOR_COUNT] = {false, false, false};
bool hx711Ready[SENSOR_COUNT] = {false, false, false};
bool hx711Saturated[SENSOR_COUNT] = {false, false, false};
bool latestMagnetDetected[SENSOR_COUNT] = {false, false, false};
int latestMagnetRaw[SENSOR_COUNT] = {HIGH, HIGH, HIGH};

unsigned long lastSensorPollMs = 0;
unsigned long lastMonitorPrintMs = 0;

float read_loadcell() {
  return latestReadings[0];
}

float read_loadcell_1() {
  return latestReadings[0];
}

float read_loadcell_2() {
  return latestReadings[1];
}

float read_loadcell_3() {
  return latestReadings[2];
}

float init_loadcell() {
  for (int i = 0; i < SENSOR_COUNT; i++) {
    tareAccumulators[i] = 0.0;
    tareOffsets[i] = 0.0;
    tareSamples[i] = 0;
    tareComplete[i] = false;
    latestReadings[i] = 0.0;
    scales[i].set_offset(0);
  }
  Monitor.println("TARE:COLLECTING");
  return 1.0;
}

float read_magnet() {
  return latestMagnetDetected[0] ? 1.0 : 0.0;
}

float read_magnet_1() {
  return latestMagnetDetected[0] ? 1.0 : 0.0;
}

float read_magnet_2() {
  return latestMagnetDetected[1] ? 1.0 : 0.0;
}

float read_magnet_3() {
  return latestMagnetDetected[2] ? 1.0 : 0.0;
}

float read_magnet_raw() {
  return (float)latestMagnetRaw[0];
}

float read_magnet_raw_1() {
  return (float)latestMagnetRaw[0];
}

float read_magnet_raw_2() {
  return (float)latestMagnetRaw[1];
}

float read_magnet_raw_3() {
  return (float)latestMagnetRaw[2];
}

float read_hx711_ready() {
  return hx711Ready[0] ? 1.0 : 0.0;
}

float read_hx711_ready_1() {
  return hx711Ready[0] ? 1.0 : 0.0;
}

float read_hx711_ready_2() {
  return hx711Ready[1] ? 1.0 : 0.0;
}

float read_hx711_ready_3() {
  return hx711Ready[2] ? 1.0 : 0.0;
}

void updateReedSwitch() {
  for (int i = 0; i < SENSOR_COUNT; i++) {
    latestMagnetRaw[i] = digitalRead(REED_SWITCH_PINS[i]);
    latestMagnetDetected[i] = latestMagnetRaw[i] == REED_DETECTED_LEVEL;
  }
}

void updateLoadCell() {
  for (int i = 0; i < SENSOR_COUNT; i++) {
    hx711Ready[i] = scales[i].is_ready();
    if (!hx711Ready[i]) {
      continue;
    }

    latestRawReadings[i] = scales[i].read();
    hx711Saturated[i] = labs((long)latestRawReadings[i]) >= HX711_SATURATION_LIMIT;
    latestStableRawReadings[i] = latestRawReadings[i];

    if (!tareComplete[i]) {
      tareAccumulators[i] += latestStableRawReadings[i];
      tareSamples[i] += 1;

      if (tareSamples[i] >= TARE_SAMPLE_COUNT) {
        tareOffsets[i] = tareAccumulators[i] / (float)tareSamples[i];
        scales[i].set_offset((long)tareOffsets[i]);
        tareComplete[i] = true;
        Monitor.print("TARE:DONE ");
        Monitor.print(i + 1);
        Monitor.print(" offset=");
        Monitor.println(tareOffsets[i]);
      }
      latestReadings[i] = 0.0;
      continue;
    }

    latestReadings[i] = latestStableRawReadings[i] - tareOffsets[i];
  }
}

void setup() {
  Bridge.begin();
  Monitor.begin(115200);

  for (int i = 0; i < SENSOR_COUNT; i++) {
    pinMode(REED_SWITCH_PINS[i], INPUT_PULLUP);
    scales[i].begin(LOADCELL_DOUT_PINS[i], LOADCELL_SCK_PINS[i]);
    scales[i].set_scale(1.0);
    scales[i].set_offset(0);
  }

  Bridge.provide("loadcell_read", read_loadcell);
  Bridge.provide("loadcell_read_1", read_loadcell_1);
  Bridge.provide("loadcell_read_2", read_loadcell_2);
  Bridge.provide("loadcell_read_3", read_loadcell_3);
  Bridge.provide("loadcell_init", init_loadcell);
  Bridge.provide("magnet_read", read_magnet);
  Bridge.provide("magnet_read_1", read_magnet_1);
  Bridge.provide("magnet_read_2", read_magnet_2);
  Bridge.provide("magnet_read_3", read_magnet_3);
  Bridge.provide("magnet_raw_read", read_magnet_raw);
  Bridge.provide("magnet_raw_read_1", read_magnet_raw_1);
  Bridge.provide("magnet_raw_read_2", read_magnet_raw_2);
  Bridge.provide("magnet_raw_read_3", read_magnet_raw_3);
  Bridge.provide("hx711_ready", read_hx711_ready);
  Bridge.provide("hx711_ready_1", read_hx711_ready_1);
  Bridge.provide("hx711_ready_2", read_hx711_ready_2);
  Bridge.provide("hx711_ready_3", read_hx711_ready_3);

  Monitor.println("BOOT:SENSOR_BRIDGE_3X_NON_BLOCKING");
  Monitor.println("PINS:HX711_1_DT=D5 HX711_1_SCK=D6 HX711_2_DT=D7 HX711_2_SCK=D8 HX711_3_DT=D12 HX711_3_SCK=D13 REED_1=D9 REED_2=D10 REED_3=D11");
  Monitor.println("TARE:COLLECTING");
}

void loop() {
  const unsigned long now = millis();

  if (now - lastSensorPollMs >= SENSOR_POLL_MS) {
    lastSensorPollMs = now;
    updateReedSwitch();
    updateLoadCell();
  }

  if (now - lastMonitorPrintMs >= MONITOR_PRINT_MS) {
    lastMonitorPrintMs = now;
    Monitor.print("Raw Readings:");
    for (int i = 0; i < SENSOR_COUNT; i++) {
      Monitor.print(" ");
      Monitor.print(i + 1);
      Monitor.print("=");
      Monitor.print(latestReadings[i]);
    }
    Monitor.println();

    Monitor.print("HX711 Ready:");
    for (int i = 0; i < SENSOR_COUNT; i++) {
      Monitor.print(" ");
      Monitor.print(i + 1);
      Monitor.print("=");
      Monitor.print(hx711Ready[i] ? "YES" : "NO");
    }
    Monitor.println();

    Monitor.print("HX711 Saturated:");
    for (int i = 0; i < SENSOR_COUNT; i++) {
      Monitor.print(" ");
      Monitor.print(i + 1);
      Monitor.print("=");
      Monitor.print(hx711Saturated[i] ? "YES" : "NO");
    }
    Monitor.println();

    Monitor.print("HX711 Stable Raw:");
    for (int i = 0; i < SENSOR_COUNT; i++) {
      Monitor.print(" ");
      Monitor.print(i + 1);
      Monitor.print("=");
      Monitor.print(latestStableRawReadings[i]);
    }
    Monitor.println();

    Monitor.print("Reed Switches:");
    for (int i = 0; i < SENSOR_COUNT; i++) {
      Monitor.print(" ");
      Monitor.print(i + 1);
      Monitor.print("=");
      Monitor.print(latestMagnetRaw[i]);
      Monitor.print("(");
      Monitor.print(latestMagnetDetected[i] ? "DETECTED" : "NOT_DETECTED");
      Monitor.print(")");
    }
    Monitor.println();
  }
}
