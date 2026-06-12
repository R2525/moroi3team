import cv2
import numpy as np
import time
import os

try:
    from arduino.app_peripherals.camera.base_camera import BaseCamera
    from arduino.app_utils import Logger
except ModuleNotFoundError:
    class BaseCamera:
        def __init__(self, resolution=(640, 480), fps=10):
            self.resolution = resolution
            self.fps = fps
            self.status = "disconnected"

        def _set_status(self, status):
            self.status = status

    class Logger:
        def __init__(self, name):
            self.name = name

class QualcommCamera(BaseCamera):
    def __init__(self, device="/dev/video10", resolution=(640, 480), fps=10):
        super().__init__(resolution=resolution, fps=fps)
        self.device = device
        self._cap = None
        self.logger = Logger("QualcommCamera")

    def _open_camera(self):
        print(f"Opening {self.device}...")
        self._cap = cv2.VideoCapture(self.device, cv2.CAP_V4L2)
        if not self._cap.isOpened():
            raise RuntimeError(f"Could not open {self.device}")
        
        self._cap.set(cv2.CAP_PROP_FOURCC, cv2.VideoWriter_fourcc(*'MJPG'))
        self._cap.set(cv2.CAP_PROP_FRAME_WIDTH, self.resolution[0])
        self._cap.set(cv2.CAP_PROP_FRAME_HEIGHT, self.resolution[1])
        print("Camera opened and properties set.")
        self._set_status("connected")

    def _close_camera(self):
        if self._cap:
            self._cap.release()
            self._cap = None
        self._set_status("disconnected")

    def capture(self):
        if self.status != "connected":
            self._open_camera()
        ret, frame = self._cap.read()
        return frame if ret else None

if __name__ == "__main__":
    os.makedirs("data/output", exist_ok=True)
    cam = QualcommCamera()
    print("Starting 10 frames capture test...")
    for i in range(10):
        start = time.time()
        frame = cam.capture()
        end = time.time()
        if frame is not None:
            print(f"Frame {i} captured: {frame.shape} in {(end-start)*1000:.1f}ms")
            cv2.imwrite(f"data/output/test_frame_{i}.jpg", frame)
        else:
            print(f"Frame {i} failed to capture")
        time.sleep(0.5)
    cam._close_camera()
    print("Test finished.")
