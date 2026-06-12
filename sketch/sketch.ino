/*
 * UNO Q MCU sensor bridge for load cell and reed switch.
 *
 * Provides RPC methods for the Linux/Python side:
 * - loadcell_read
 * - loadcell_init
 * - magnet_read
 * - magnet_raw_read
 *
 * HX711 reads are intentionally non-blocking. The RPC callbacks return the
 * latest sampled value so RouterBridge stays responsive even if HX711 is slow.
 */

#include "HX711.h"
#include <Arduino_RouterBridge.h>

const int LOADCELL_DOUT_PIN = 3;
const int LOADCELL_SCK_PIN = 2;
const int REED_SWITCH_PIN = 4;
const int REED_DETECTED_LEVEL = LOW;

const unsigned long SENSOR_POLL_MS = 20;
const unsigned long MONITOR_PRINT_MS = 500;
const int TARE_SAMPLE_COUNT = 20;
const int HX711_READ_SAMPLES = 5;
const long HX711_SATURATION_LIMIT = 8300000L;

HX711 scale;

float latestReading = 0.0;
float latestRawReading = 0.0;
float latestStableRawReading = 0.0;
float tareAccumulator = 0.0;
float tareOffset = 0.0;
int tareSamples = 0;
bool tareComplete = false;
bool hx711Ready = false;
bool hx711Saturated = false;
bool latestMagnetDetected = false;
int latestMagnetRaw = HIGH;

unsigned long lastSensorPollMs = 0;
unsigned long lastMonitorPrintMs = 0;

float read_loadcell() {
  return latestReading;
}

float init_loadcell() {
  tareAccumulator = 0.0;
  tareOffset = 0.0;
  tareSamples = 0;
  tareComplete = false;
  latestReading = 0.0;
  scale.set_offset(0);
  Monitor.println("TARE:COLLECTING");
  return 1.0;
}

float read_magnet() {
  return latestMagnetDetected ? 1.0 : 0.0;
}

float read_magnet_raw() {
  return (float)latestMagnetRaw;
}

float read_hx711_ready() {
  return hx711Ready ? 1.0 : 0.0;
}

void updateReedSwitch() {
  latestMagnetRaw = digitalRead(REED_SWITCH_PIN);
  latestMagnetDetected = latestMagnetRaw == REED_DETECTED_LEVEL;
}

void updateLoadCell() {
  hx711Ready = scale.is_ready();
  if (!hx711Ready) {
    return;
  }

  latestRawReading = scale.read_average(HX711_READ_SAMPLES);
  hx711Saturated = labs((long)latestRawReading) >= HX711_SATURATION_LIMIT;
  latestStableRawReading = latestRawReading;

  if (!tareComplete) {
    tareAccumulator += latestStableRawReading;
    tareSamples += 1;

    if (tareSamples >= TARE_SAMPLE_COUNT) {
      tareOffset = tareAccumulator / (float)tareSamples;
      scale.set_offset((long)tareOffset);
      tareComplete = true;
      Monitor.print("TARE:DONE offset=");
      Monitor.println(tareOffset);
    }
    latestReading = 0.0;
    return;
  }

  latestReading = scale.get_value(HX711_READ_SAMPLES);
}

void setup() {
  Bridge.begin();
  Monitor.begin(115200);

  pinMode(REED_SWITCH_PIN, INPUT_PULLUP);
  scale.begin(LOADCELL_DOUT_PIN, LOADCELL_SCK_PIN);
  scale.set_scale(1.0);
  scale.set_offset(0);

  Bridge.provide("loadcell_read", read_loadcell);
  Bridge.provide("loadcell_init", init_loadcell);
  Bridge.provide("magnet_read", read_magnet);
  Bridge.provide("magnet_raw_read", read_magnet_raw);
  Bridge.provide("hx711_ready", read_hx711_ready);

  Monitor.println("BOOT:SENSOR_BRIDGE_NON_BLOCKING");
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
    Monitor.print("Raw Reading: ");
    Monitor.println(latestReading);
    Monitor.print("HX711 Ready: ");
    Monitor.println(hx711Ready ? "YES" : "NO");
    Monitor.print("HX711 Saturated: ");
    Monitor.println(hx711Saturated ? "YES" : "NO");
    Monitor.print("HX711 Stable Raw: ");
    Monitor.println(latestStableRawReading);
    Monitor.print("Reed Switch Raw: ");
    Monitor.print(latestMagnetRaw);
    Monitor.print(" / Magnet: ");
    Monitor.println(latestMagnetDetected ? "DETECTED" : "NOT_DETECTED");
  }
}
