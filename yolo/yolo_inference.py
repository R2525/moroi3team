import cv2
import numpy as np
import onnxruntime as ort
import time
import os

MODEL_PATH = 'data/input/yolov8n.onnx'
CONF_THRESHOLD = 0.4

def run_inference():
    providers = ['QNNExecutionProvider', 'NnapiExecutionProvider', 'CPUExecutionProvider']
    print(f"Initializing with providers: {providers}")
    try:
        session = ort.InferenceSession(MODEL_PATH, providers=providers)
    except Exception as e:
        print(f"Fallback to CPU: {e}")
        session = ort.InferenceSession(MODEL_PATH, providers=['CPUExecutionProvider'])
    
    print(f"Active providers: {session.get_providers()}")

    # Use index 10 which was found to be working
    cap = cv2.VideoCapture(10)
    if not cap.isOpened():
        print("Camera not found on index 10")
        return

    try:
        print("Starting inference loop...")
        while True:
            ret, frame = cap.read()
            if not ret: 
                print("Failed to read frame")
                break
            
            # Preprocess
            img = cv2.resize(cv2.cvtColor(frame, cv2.COLOR_BGR2RGB), (640, 640))
            input_data = np.transpose(img, (2, 0, 1)).astype(np.float32) / 255.0
            input_data = np.expand_dims(input_data, axis=0)
            
            # Inference
            start = time.time()
            outputs = session.run(None, {session.get_inputs()[0].name: input_data})
            inference_time = (time.time() - start) * 1000
            
            # Postprocess (simplified)
            output = outputs[0][0].transpose()
            max_conf = np.max(output[:, 4])
            
            if max_conf > CONF_THRESHOLD:
                print(f"Object detected! Confidence: {max_conf:.4f} ({inference_time:.2f}ms)")
            else:
                # Print status in-place to show activity
                print(f"Running... (Max Conf: {max_conf:.4f}, Time: {inference_time:.2f}ms)", end='\r')
    except KeyboardInterrupt:
        print("\nInterrupted by user.")
    finally:
        cap.release()

if __name__ == '__main__':
    run_inference()
