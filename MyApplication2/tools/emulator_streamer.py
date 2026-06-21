import io
import threading
import subprocess
import time
from http.server import HTTPServer, BaseHTTPRequestHandler

ADB_PATH = r"C:\Users\yulee\AppData\Local\Android\Sdk\platform-tools\adb.exe"
HOST = '0.0.0.0'
PORT = 8080
CAPTURE_INTERVAL = 0.5

latest_frame = None
frame_lock = threading.Lock()
running = True


def capture_loop():
    global latest_frame, running
    while running:
        try:
            p = subprocess.Popen([ADB_PATH, 'exec-out', 'screencap', '-p'], stdout=subprocess.PIPE)
            data = p.stdout.read()
            p.wait(timeout=2)
            if data:
                with frame_lock:
                    latest_frame = data
        except Exception:
            pass
        time.sleep(CAPTURE_INTERVAL)


class StreamHandler(BaseHTTPRequestHandler):
    def do_GET(self):
        if self.path == '/frame.png':
            with frame_lock:
                frame = latest_frame
            if not frame:
                self.send_response(503)
                self.end_headers()
                self.wfile.write(b'No frame yet')
                return
            self.send_response(200)
            self.send_header('Content-Type', 'image/png')
            self.send_header('Content-Length', str(len(frame)))
            self.end_headers()
            self.wfile.write(frame)
            return

        if self.path == '/stream':
            self.send_response(200)
            boundary = b'--frameboundary'
            self.send_header('Content-Type', 'multipart/x-mixed-replace; boundary=%s' % boundary.decode())
            self.end_headers()
            try:
                while True:
                    with frame_lock:
                        frame = latest_frame
                    if frame:
                        self.wfile.write(boundary + b'\r\n')
                        self.wfile.write(b'Content-Type: image/png\r\n')
                        self.wfile.write(b'Content-Length: ' + str(len(frame)).encode() + b'\r\n\r\n')
                        self.wfile.write(frame + b'\r\n')
                    time.sleep(CAPTURE_INTERVAL)
            except BrokenPipeError:
                return
            except Exception:
                return

        # fallback
        self.send_response(404)
        self.end_headers()


if __name__ == '__main__':
    t = threading.Thread(target=capture_loop, daemon=True)
    t.start()
    server = HTTPServer((HOST, PORT), StreamHandler)
    print('Serving on http://%s:%d (endpoints: /frame.png /stream)' % (HOST, PORT))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        running = False
        server.shutdown()
