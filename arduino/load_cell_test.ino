/*
 * 3x Load Cell Test Code (HX711)
 * 
 * Hardware: 
 * - Module: SZH-SSBH-016 (HX711)
 * - Board: Arduino Uno
 * 
 * Connections follow pin.md:
 * - HX711 1 DT: D5,  SCK: D6
 * - HX711 2 DT: D7,  SCK: D8
 * - HX711 3 DT: D12, SCK: D13
 * 
 * Note: Requires "HX711 Arduino Library" by bogde.
 */

#include "HX711.h"

const int SENSOR_COUNT = 3;
const int LOADCELL_DOUT_PINS[SENSOR_COUNT] = {5, 7, 12};
const int LOADCELL_SCK_PINS[SENSOR_COUNT] = {6, 8, 13};

HX711 scales[SENSOR_COUNT];

void setup() {
  Serial.begin(115200);
  Serial.println("3x HX711 Load Cell Test with Auto-Tare");

  for (int i = 0; i < SENSOR_COUNT; i++) {
    scales[i].begin(LOADCELL_DOUT_PINS[i], LOADCELL_SCK_PINS[i]);
  }

  Serial.println("Starting zero-point calibration...");
  Serial.println("Please do not touch the load cell.");

  for (int i = 0; i < SENSOR_COUNT; i++) {
    Serial.print("Taring HX711 ");
    Serial.println(i + 1);
    scales[i].set_offset(scales[i].read_average(30));
  }

  Serial.println("Calibration complete.");
  for (int i = 0; i < SENSOR_COUNT; i++) {
    Serial.print("Zero-point offset ");
    Serial.print(i + 1);
    Serial.print(": ");
    Serial.println(scales[i].get_offset());
  }

  Serial.println("Readings (Tared):");
}

void loop() {
  Serial.print("Raw Readings:");
  for (int i = 0; i < SENSOR_COUNT; i++) {
    Serial.print(" ");
    Serial.print(i + 1);
    Serial.print("=");
    if (scales[i].is_ready()) {
      Serial.print(scales[i].get_value(5));
    } else {
      Serial.print("NOT_READY");
    }
  }
  Serial.println();
  
  delay(1000);
}
