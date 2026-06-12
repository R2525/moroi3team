import onnxruntime as ort
print("Available providers:", ort.get_available_providers())
try:
    session = ort.InferenceSession("data/input/yolov8n.onnx", providers=['QNNExecutionProvider'])
    print("QNN successful")
except Exception as e:
    print("QNN failed:", e)

try:
    session = ort.InferenceSession("data/input/yolov8n.onnx", providers=['NnapiExecutionProvider'])
    print("NNAPI successful")
except Exception as e:
    print("NNAPI failed:", e)
