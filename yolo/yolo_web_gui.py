import cv2
import numpy as np
import onnxruntime as ort
import time
from flask import Flask, Response

app = Flask(__name__)

MODEL_PATH = 'data/input/yolov8n.onnx'
CONF_THRESHOLD = 0.4
INPUT_SIZE = (640, 640)

CLASSES = [
    'person', 'bicycle', 'car', 'motorcycle', 'airplane', 'bus', 'train', 'truck', 'boat', 
    'traffic light', 'fire hydrant', 'stop sign', 'parking meter', 'bench', 'bird', 'cat', 
    'dog', 'horse', 'sheep', 'cow', 'elephant', 'bear', 'zebra', 'giraffe', 'backpack', 
    'umbrella', 'handbag', 'tie', 'suitcase', 'frisbee', 'skis', 'snowboard', 'sports ball', 
    'kite', 'baseball bat', 'baseball glove', 'skateboard', 'surfboard', 'tennis racket', 
    'bottle', 'wine glass', 'cup', 'fork', 'knife', 'spoon', 'bowl', 'banana', 'apple', 
    'sandwich', 'orange', 'broccoli', 'carrot', 'hot dog', 'pizza', 'donut', 'cake', 
    'chair', 'couch', 'potted plant', 'bed', 'dining table', 'toilet', 'tv', 'laptop', 
    'mouse', 'remote', 'keyboard', 'cell phone', 'microwave', 'oven', 'toaster', 'sink', 
    'refrigerator', 'book', 'clock', 'vase', 'scissors', 'teddy bear', 'hair drier', 'toothbrush'
]

def initialize_session():
    providers = ['QNNExecutionProvider', 'NnapiExecutionProvider', 'CPUExecutionProvider']
    try:
        session = ort.InferenceSession(MODEL_PATH, providers=providers)
        return session
    except Exception as e:
        return ort.InferenceSession(MODEL_PATH, providers=['CPUExecutionProvider'])

session = initialize_session()

def preprocess(frame):
    img = cv2.resize(frame, INPUT_SIZE)
    img = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    img = img.astype(np.float32) / 255.0
    img = np.transpose(img, (2, 0, 1))
    img = np.expand_dims(img, axis=0)
    return img

def postprocess(frame, output):
    output = output[0].transpose()
    boxes = []
    confidences = []
    class_ids = []

    h, w = frame.shape[:2]
    x_factor = w / INPUT_SIZE[0]
    y_factor = h / INPUT_SIZE[1]

    for row in output:
        scores = row[4:]
        class_id = np.argmax(scores)
        confidence = scores[class_id]
        if confidence > CONF_THRESHOLD:
            cx, cy, bw, bh = row[0:4]
            left = int((cx - bw/2) * x_factor)
            top = int((cy - bh/2) * y_factor)
            width = int(bw * x_factor)
            height = int(bh * y_factor)
            boxes.append([left, top, width, height])
            confidences.append(float(confidence))
            class_ids.append(class_id)

    indices = cv2.dnn.NMSBoxes(boxes, confidences, CONF_THRESHOLD, 0.45)
    if len(indices) > 0:
        for i in indices.flatten():
            x, y, bw, bh = boxes[i]
            label = f"{CLASSES[class_ids[i]]}: {confidences[i]:.2f}"
            cv2.rectangle(frame, (x, y), (x + bw, y + bh), (0, 255, 0), 2)
            cv2.putText(frame, label, (x, y - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 2)
    return frame

def generate_frames():
    cap = cv2.VideoCapture(10)
    while True:
        success, frame = cap.read()
        if not success: break
        
        input_data = preprocess(frame)
        start_time = time.time()
        outputs = session.run(None, {session.get_inputs()[0].name: input_data})
        inference_time = (time.time() - start_time) * 1000
        
        frame = postprocess(frame, outputs[0])
        cv2.putText(frame, f"Inference: {inference_time:.1f}ms", (10, 30), 
                    cv2.FONT_HERSHEY_SIMPLEX, 1, (0, 0, 255), 2)
        
        ret, buffer = cv2.imencode('.jpg', frame)
        yield (b'--frame\r\n'
               b'Content-Type: image/jpeg\r\n\r\n' + buffer.tobytes() + b'\r\n')

@app.route('/')
def index():
    return "<html><body><h1>YOLO GPU Stream</h1><img src='/video_feed'></body></html>"

@app.route('/video_feed')
def video_feed():
    return Response(generate_frames(), mimetype='multipart/x-mixed-replace; boundary=frame')

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, threaded=True)
