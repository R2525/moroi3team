"""
TemiLocalServer(8088) sensor-events 실시간 폴링 모니터 (비침투식).

- http://127.0.0.1:18088/api/sensor-events/recent 를 1초마다 조회 (adb forward -> 에뮬 8088).
- 새로 들어온 sensor-event만 출력. 8088 LAN 브리지(portproxy)는 건드리지 않음.
- Uno Q가 POST /api/sensor-events 로 보내면 여기에 뜸. (POST /api/storage-events 는 404라 안 뜸)
"""
import json
import os
import time
import urllib.request
import datetime

os.system("")  # Windows 콘솔 ANSI 색상 활성화

URL = "http://127.0.0.1:18088/api/sensor-events/recent"
POLL_SEC = 1.0

GREEN = "\033[92m"
RED = "\033[91m"
CYAN = "\033[96m"
GRAY = "\033[90m"
RESET = "\033[0m"


def fetch():
    with urllib.request.urlopen(URL, timeout=4) as resp:
        data = json.loads(resp.read().decode("utf-8", "replace"))
    return data.get("events", [])


def fmt(ev):
    ts = ev.get("ts", 0)
    t = datetime.datetime.fromtimestamp(ts / 1000.0).strftime("%H:%M:%S") if ts else "--:--:--"
    etype = ev.get("event_type") or "(no type)"
    drawer = ev.get("drawer_number", 0)
    line = "[{0}] {1}".format(t, etype)
    if drawer:
        line += "  · {0}번 서랍".format(drawer)
    if "value" in ev:
        line += "  · value={0}".format(ev.get("value"))
    up = etype.upper()
    if "VERIFY_SUCCESS" in up or up == "SUCCESS":
        color = GREEN
    elif "VERIFY_FAIL" in up or up == "FAIL":
        color = RED
    else:
        color = CYAN
    return color + "  수신<= " + line + RESET


def main():
    print("=" * 60)
    print(" Temi 8088 sensor-events 실시간 모니터 (폴링)")
    print(" " + URL + "  (1초 간격)")
    print(" 초록=VERIFY_SUCCESS  빨강=VERIFY_FAIL  청록=기타 센서")
    print(" Uno Q가 POST /api/sensor-events 로 보내면 표시됩니다.")
    print("=" * 60)

    seen_max_ts = None
    baseline_done = False
    err_shown = False
    while True:
        try:
            events = fetch()  # 최신이 앞(newest-first)
            err_shown = False
            if not baseline_done:
                # 시작 시점: 기존 누적은 건너뛰고 기준선만 잡는다
                seen_max_ts = events[0]["ts"] if events else 0
                baseline_done = True
                print(GRAY + "  (기준선 설정: 기존 {0}건 무시, 이후 새 이벤트만 표시)".format(len(events)) + RESET, flush=True)
            else:
                # ts가 기준선보다 큰 새 이벤트만, 오래된 것부터 출력
                fresh = [e for e in events if e.get("ts", 0) > (seen_max_ts or 0)]
                for e in reversed(fresh):
                    print(fmt(e), flush=True)
                if fresh:
                    seen_max_ts = max(e.get("ts", 0) for e in fresh)
        except Exception as exc:
            if not err_shown:
                print(RED + "[연결 오류] {0}  (adb forward 18088 살아있는지 확인)".format(exc) + RESET, flush=True)
                err_shown = True
        time.sleep(POLL_SEC)


if __name__ == "__main__":
    main()
