void setup() {
  Serial.begin(115200);
}

void loop() {
  if (Serial.available() > 0) {
    char cmd = Serial.read();
    if (cmd == '1') {
      // Act based on YOLO detection
    }
  }
  delay(100);
}
