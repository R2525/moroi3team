/*
 * Load Cell Test Code (HX711)
 * 
 * Hardware: 
 * - Module: SZH-SSBH-016 (HX711)
 * - Board: Arduino Uno
 * 
 * Connections:
 * - VCC: 5V
 * - GND: GND
 * - DT: Pin 3
 * - SCK: Pin 2
 * 
 * Note: Requires "HX711 Arduino Library" by bogde.
 */

#include "HX711.h"

// HX711 circuit wiring
const int LOADCELL_DOUT_PIN = 3;
const int LOADCELL_SCK_PIN = 2;

HX711 scale;

void setup() {
  Serial.begin(115200);
  Serial.println("HX711 Load Cell Test with 3s Auto-Tare");

  scale.begin(LOADCELL_DOUT_PIN, LOADCELL_SCK_PIN);

  Serial.println("Starting 3-second zero-point calibration...");
  Serial.println("Please do not touch the load cell.");

  // 3초 동안 최대한 많은 샘플을 뽑아 평균을 냅니다.
  // get_value(n)은 n번 읽어서 평균을 내는 함수입니다. 
  // HX711은 보통 초당 10번~80번 측정하므로 30번 정도 읽으면 충분히 안정적입니다.
  scale.set_offset(scale.read_average(30)); 

  Serial.println("Calibration complete.");
  Serial.print("Zero-point offset set to: ");
  Serial.println(scale.get_offset());

  Serial.println("Readings (Tared):");
}

void loop() {
  Serial.print("Raw Reading: ");
  Serial.println(scale.get_value(5));
  
  delay(1000);
}
