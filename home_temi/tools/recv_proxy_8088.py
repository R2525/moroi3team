"""
TemiLocalServer(8088) 실시간 수신 로깅 프록시.

체인:  LAN 0.0.0.0:8088  ->  [이 프록시]  ->  127.0.0.1:UPSTREAM(=adb forward) -> 에뮬 8088

- Uno Q/아두이노가 8088로 보내는 모든 요청(메서드/경로/클라이언트IP/본문)을 콘솔에 실시간 출력.
- 응답은 그대로 중계하므로 TemiLocalServer 동작에 영향 없음.
- 이 창을 닫으면 8088 LAN 접속도 끊깁니다(프록시가 곧 브리지).
"""
import socket
import threading
import datetime
import os

os.system("")  # Windows 콘솔 ANSI(색상) 이스케이프 활성화

LISTEN_HOST = "0.0.0.0"
LISTEN_PORT = 8088
UPSTREAM_HOST = "127.0.0.1"
UPSTREAM_PORT = 18088  # adb forward tcp:18088 tcp:8088

SENSOR_HINT = ("sensor-events", "placement-batches", "storage-events")


def ts():
    return datetime.datetime.now().strftime("%H:%M:%S")


def log_request(client_ip, data):
    try:
        head, _, body = data.partition(b"\r\n\r\n")
        lines = head.split(b"\r\n")
        if not lines or b"HTTP/" not in lines[0]:
            return
        req_line = lines[0].decode("latin1", "replace")
        parts = req_line.split(" ")
        method = parts[0] if parts else "?"
        path = parts[1] if len(parts) > 1 else "?"
        is_sensor = any(h in path for h in SENSOR_HINT)
        is_post = method == "POST"
        tag = "수신<=" if (is_post and is_sensor) else "      "
        mark = "**외부" if client_ip not in ("127.0.0.1", "::1") else "  로컬"
        line = "[{0}] {1} {2} {3:<15} {4} {5}".format(ts(), mark, tag, client_ip, method, path)
        # 색: 외부 센서 POST=초록, 기타 외부=청록, 로컬=회색
        if is_post and is_sensor:
            color = "\033[92m"
        elif client_ip not in ("127.0.0.1", "::1"):
            color = "\033[96m"
        else:
            color = "\033[90m"
        print(color + line + "\033[0m", flush=True)
        if is_post and body.strip():
            preview = body[:300].decode("utf-8", "replace").replace("\n", " ")
            print("\033[92m        body: " + preview + "\033[0m", flush=True)
    except Exception as exc:  # 로깅 실패는 중계에 영향 주지 않게 무시
        print("[log-err] " + str(exc), flush=True)


def pump(src, dst, on_first=None):
    first = True
    try:
        while True:
            chunk = src.recv(65536)
            if not chunk:
                break
            if first and on_first:
                on_first(chunk)
                first = False
            dst.sendall(chunk)
    except Exception:
        pass
    finally:
        for s in (src, dst):
            try:
                s.shutdown(socket.SHUT_RDWR)
            except Exception:
                pass


def handle(client, addr):
    client_ip = addr[0]
    try:
        upstream = socket.create_connection((UPSTREAM_HOST, UPSTREAM_PORT), timeout=10)
    except Exception as exc:
        print("\033[91m[{0}] upstream 연결 실패({1}:{2}): {3}\033[0m".format(
            ts(), UPSTREAM_HOST, UPSTREAM_PORT, exc), flush=True)
        client.close()
        return
    t1 = threading.Thread(target=pump, args=(client, upstream),
                          kwargs={"on_first": lambda d: log_request(client_ip, d)}, daemon=True)
    t2 = threading.Thread(target=pump, args=(upstream, client), daemon=True)
    t1.start(); t2.start()
    t1.join(); t2.join()
    client.close()


def main():
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((LISTEN_HOST, LISTEN_PORT))
    srv.listen(50)
    print("=" * 62, flush=True)
    print(" Temi 8088 실시간 수신 모니터 (로깅 프록시)", flush=True)
    print(" {0}:{1}  ->  {2}:{3} (adb forward -> 에뮬 8088)".format(
        LISTEN_HOST, LISTEN_PORT, UPSTREAM_HOST, UPSTREAM_PORT), flush=True)
    print(" 초록=센서수신(POST)  청록=외부기기  회색=로컬", flush=True)
    print(" 이 창을 닫으면 8088 LAN 접속도 끊깁니다.", flush=True)
    print("=" * 62, flush=True)
    while True:
        client, addr = srv.accept()
        threading.Thread(target=handle, args=(client, addr), daemon=True).start()


if __name__ == "__main__":
    main()
