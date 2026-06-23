import cv2
import numpy as np
import onnxruntime as ort
import os
import time

def test_yolo_performance(image_path, model_path):
    session = ort.InferenceSession(model_path)
    img = cv2.imread(image_path)
    img_resized = cv2.resize(cv2.cvtColor(img, cv2.COLOR_BGR2RGB), (640, 640))
    input_data = np.transpose(img_resized, (2, 0, 1)).astype(np.float32) / 255.0
    input_data = np.expand_dims(input_data, axis=0)

    # Measure time
    start_time = time.time()
    outputs = session.run(None, {session.get_inputs()[0].name: input_data})
    end_time = time.time()
    
    latency = (end_time - start_time) * 1000
    fps = 1000 / latency
    print(f'Inference Latency: {latency:.2f} ms')
    print(f'Estimated FPS: {fps:.2f}')

if __name__ == '__main__':
    test_yolo_performance('data/input/2023011001501_0.jpg', 'data/input/yolov8n.onnx')
