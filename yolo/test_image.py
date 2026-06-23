import cv2
import numpy as np
import onnxruntime as ort
import os
import sys

def test_yolo_image(image_path, model_path):
    if not os.path.exists(model_path):
        print(f'Model {model_path} not found.')
        return

    # Load ONNX model
    session = ort.InferenceSession(model_path)
    
    # Preprocess image
    img = cv2.imread(image_path)
    h, w = img.shape[:2]
    img_rgb = cv2.cvtColor(img, cv2.COLOR_BGR2RGB)
    img_resized = cv2.resize(img_rgb, (640, 640))
    input_data = np.transpose(img_resized, (2, 0, 1)).astype(np.float32) / 255.0
    input_data = np.expand_dims(input_data, axis=0)

    # Run inference
    outputs = session.run(None, {session.get_inputs()[0].name: input_data})
    
    # YOLOv8 Post-processing
    output = outputs[0][0]
    output = output.transpose()
    
    boxes = []
    scores = []
    class_ids = []

    for i in range(len(output)):
        classes_scores = output[i][4:]
        max_score = np.amax(classes_scores)
        
        if max_score > 0.4:
            class_id = np.argmax(classes_scores)
            x, y, sw, sh = output[i][0:4]
            
            left = int((x - sw/2) * w / 640)
            top = int((y - sh/2) * h / 640)
            width = int(sw * w / 640)
            height = int(sh * h / 640)
            
            boxes.append([left, top, width, height])
            scores.append(float(max_score))
            class_ids.append(class_id)

    # Apply Non-Maximum Suppression
    indices = cv2.dnn.NMSBoxes(boxes, scores, 0.4, 0.5)
    
    if len(indices) > 0:
        for i in indices.flatten():
            box = boxes[i]
            left, top, width, height = box
            cv2.rectangle(img, (left, top), (left + width, top + height), (0, 255, 0), 2)
            label = f'ID:{class_ids[i]} {scores[i]:.2f}'
            cv2.putText(img, label, (left, top - 10), cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 255, 0), 2)

    output_path = os.path.join('data/output', 'result_' + os.path.basename(image_path))
    os.makedirs('data/output', exist_ok=True)
    cv2.imwrite(output_path, img)
    print(f'Saved result with BBoxes to {output_path}')

if __name__ == '__main__':
    test_yolo_image('data/input/2023011001501_0.jpg', 'data/input/yolov8n.onnx')
