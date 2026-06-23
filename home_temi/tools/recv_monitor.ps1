# Temi api_server(8000) 실시간 수신 모니터 - 아두이노/Uno Q POST만 부각, 폴링 노이즈 숨김
$log = "C:\project\moroi\moroi3team\home_temi\api_server\_uvicorn.log"
$Host.UI.RawUI.WindowTitle = "Temi 8000 RecvMonitor"
Clear-Host
Write-Host "=============================================================" -ForegroundColor Cyan
Write-Host " Temi api_server  실시간 수신 모니터  (http://0.0.0.0:8000)"   -ForegroundColor Cyan
Write-Host " 초록=센서 수신(POST)  하늘=외부기기  회색=기타  (폴링GET 숨김)" -ForegroundColor DarkGray
Write-Host " 아두이노/Uno Q가 보내면 여기에 즉시 표시됩니다. 닫기=창 닫기"   -ForegroundColor DarkGray
Write-Host "=============================================================" -ForegroundColor Cyan
while (-not (Test-Path $log)) { Start-Sleep -Seconds 1 }
# 에뮬레이터 앱이 1초마다 호출하는 폴링은 숨긴다
$mute = 'GET /api/(drawers/link-status|storage-events/latest|web-intake/drawer-status|health)'
Get-Content $log -Wait -Tail 1 | ForEach-Object {
    $line = $_
    if ($line -notmatch '"([A-Z]+) (\S+) HTTP') { return }
    $method = $matches[1]; $path = $matches[2]
    if ($line -match $mute) { return }
    $ip = if ($line -match '(\d{1,3}(\.\d{1,3}){3}):\d+') { $matches[1] } else { '?' }
    $status = if ($line -match 'HTTP/1\.1" (\d{3})') { $matches[1] } else { '' }
    $ts = (Get-Date).ToString('HH:mm:ss')
    $external = ($ip -ne '127.0.0.1' -and $ip -ne '?')
    $isSensor = $path -match 'storage-events|sensor|drawer-camera|placement-verif|web-intake'
    $tag = if ($external) { '** 외부' } else { '  로컬' }
    if ($method -eq 'POST' -and $isSensor) {
        Write-Host ("[{0}] {1} 수신<= {2,-15} {3} {4} ({5})" -f $ts,$tag,$ip,$method,$path,$status) -ForegroundColor Green
    } elseif ($external) {
        Write-Host ("[{0}] {1}        {2,-15} {3} {4} ({5})" -f $ts,$tag,$ip,$method,$path,$status) -ForegroundColor Cyan
    } else {
        Write-Host ("[{0}] {1}        {2,-15} {3} {4} ({5})" -f $ts,$tag,$ip,$method,$path,$status) -ForegroundColor DarkGray
    }
}
