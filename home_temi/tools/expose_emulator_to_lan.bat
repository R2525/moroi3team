@echo off
REM ============================================================
REM  에뮬레이터(Temi)를 같은 핫스팟의 Uno Q가 접근하도록 노출
REM  PC LAN IP:8088  ->  127.0.0.1:8088 (adb forward) -> 에뮬 8088
REM  관리자 권한이 필요하므로 자동으로 권한상승합니다.
REM ============================================================

net session >nul 2>&1
if %errorLevel% neq 0 (
  echo 관리자 권한으로 다시 실행합니다...
  powershell -Command "Start-Process '%~f0' -Verb RunAs"
  exit /b
)

echo [1/2] 포트 프록시 추가 (0.0.0.0:8088 -^> 127.0.0.1:8088)
netsh interface portproxy add v4tov4 listenaddress=0.0.0.0 listenport=8088 connectaddress=127.0.0.1 connectport=8088

echo [2/2] 방화벽 인바운드 8088 허용
netsh advfirewall firewall add rule name="temi-emu-8088" dir=in action=allow protocol=TCP localport=8088

echo.
echo === 현재 포트 프록시 ===
netsh interface portproxy show v4tov4
echo.
echo 완료. 이제 adb forward 가 켜져 있으면 외부에서 http://(PC_IP):8088 로 접속됩니다.
echo (해제하려면: netsh interface portproxy delete v4tov4 listenaddress=0.0.0.0 listenport=8088)
pause
