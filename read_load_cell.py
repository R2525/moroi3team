import serial
import time

# Arduino Uno Q (MPU to MCU) Serial Port
SERIAL_PORT = '/dev/ttyHS1'
BAUD_RATE = 115200

def read_load_cell():
    print(f"Connecting to {SERIAL_PORT}...")
    try:
        ser = serial.Serial(SERIAL_PORT, BAUD_RATE, timeout=1)
        ser.reset_input_buffer()
        print("Connected! (Ctrl+C to stop)")
        
        while True:
            if ser.in_waiting > 0:
                line = ser.readline().decode('utf-8', errors='ignore').strip()
                if line:
                    print(f"[LOAD CELL] {line}")
            time.sleep(0.01)
    except Exception as e:
        print(f"Error: {e}")

if __name__ == "__main__":
    read_load_cell()
