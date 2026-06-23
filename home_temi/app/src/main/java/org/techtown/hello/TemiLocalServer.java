package org.techtown.hello;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class TemiLocalServer {
    public static final int PORT = 8088;
    private static final String PREFS_NAME = "temi_settings";
    private static final String PREF_API_KEY = "gemini_api_key";

    private final Context context;
    private final TemiDbHelper db;
    private final GeminiPhotoAnalyzer analyzer;
    private final Map<String, String> shoppingQrPayloads = new ConcurrentHashMap<>();
    private ServerSocket serverSocket;
    private volatile boolean running;

    // Uno Q가 보내는 sensor-event를 실시간으로 보기 위한 인메모리 링버퍼 (최신이 앞)
    private static final int LOG_CAPACITY = 100;
    private final java.util.ArrayDeque<JSONObject> sensorLog = new java.util.ArrayDeque<>();

    // 시간 비교: 진행 신호가 너무 짧은 간격으로 중복 도착하면 한 번만 처리한다.
    private static final long PLACEMENT_DEBOUNCE_MS = 800;
    private volatile long lastPlacementAdvanceTs = 0;

    public TemiLocalServer(Context context, TemiDbHelper db) {
        this.context = context.getApplicationContext();
        this.db = db;
        this.analyzer = new GeminiPhotoAnalyzer(context);
    }

    public void start() {
        if (running) {
            return;
        }
        running = true;
        new Thread(() -> {
            try {
                serverSocket = new ServerSocket(PORT);
                while (running) {
                    Socket socket = serverSocket.accept();
                    new Thread(() -> handle(socket)).start();
                }
            } catch (Exception ignored) {
                running = false;
            }
        }).start();
    }

    public void stop() {
        running = false;
        try {
            if (serverSocket != null) {
                serverSocket.close();
            }
        } catch (Exception ignored) {
        }
    }

    public String getBaseUrl() {
        return "http://" + getLocalIpAddress() + ":" + PORT;
    }

    public String createShoppingQrShare(JSONObject payload) {
        String id = UUID.randomUUID().toString();
        shoppingQrPayloads.put(id, payload.toString());
        return id;
    }

    private synchronized void addSensorLog(JSONObject body) {
        try {
            JSONObject entry = new JSONObject();
            entry.put("ts", System.currentTimeMillis());
            entry.put("event_type", body.optString("event_type", body.optString("type", "")).trim());
            entry.put("drawer_number", body.optInt("drawer_number", 0));
            if (body.has("value")) {
                entry.put("value", body.optDouble("value"));
            }
            entry.put("raw", body.toString());
            sensorLog.addFirst(entry);
            while (sensorLog.size() > LOG_CAPACITY) {
                sensorLog.removeLast();
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized JSONArray recentSensorLog(int limit) {
        JSONArray arr = new JSONArray();
        int n = 0;
        for (JSONObject entry : sensorLog) {
            if (n++ >= limit) {
                break;
            }
            arr.put(entry);
        }
        return arr;
    }

    public synchronized void clearSensorLog() {
        sensorLog.clear();
    }

    private void handle(Socket socket) {
        try {
            socket.setSoTimeout(30000);
            InputStream inputStream = socket.getInputStream();
            HttpRequest request = HttpRequest.read(inputStream);
            if (request == null) {
                return;
            }

            String path = request.path;
            if ("GET".equals(request.method) && "/".equals(path)) {
                writeHtml(socket, uploadPage());
            } else if ("GET".equals(request.method) && "/upload".equals(path)) {
                writeHtml(socket, uploadPage());
            } else if ("GET".equals(request.method) && "/upload-qr".equals(path)) {
                writeHtml(socket, uploadQrPage(requestBaseUrl(request)));
            } else if ("GET".equals(request.method) && "/upload-qr.png".equals(path)) {
                writeUploadQrPng(socket, requestBaseUrl(request));
            } else if ("GET".equals(request.method) && "/api/health".equals(path)) {
                writeJson(socket, 200, db.health());
            } else if ("GET".equals(request.method) && "/shopping-qr".equals(path)) {
                writeHtml(socket, shoppingQrPage(valueOrEmpty(request.query.get("id"))));
            } else if ("GET".equals(request.method) && "/shopping-qr.png".equals(path)) {
                writeShoppingQrPng(socket, valueOrEmpty(request.query.get("id")));
            } else if ("GET".equals(request.method) && "/api/items".equals(path)) {
                writeJson(socket, 200, new JSONObject().put("items", db.listItems()));
            } else if ("GET".equals(request.method) && "/api/items/search".equals(path)) {
                writeJson(socket, 200, db.findItem(valueOrEmpty(request.query.get("name"))));
            } else if ("POST".equals(request.method) && "/api/placements".equals(path)) {
                JSONObject body = new JSONObject(request.bodyAsString());
                writeJson(socket, 201, db.savePlacement(
                        body.optString("item_name", body.optString("name")),
                        body.optInt("drawer_number"),
                        body.optInt("quantity", 1),
                        "manual"));
            } else if ("DELETE".equals(request.method) && path.startsWith("/api/items/")) {
                String name = URLDecoder.decode(path.substring("/api/items/".length()), "UTF-8");
                writeJson(socket, 200, new JSONObject().put("deleted", db.deleteItemByName(name)));
            } else if ("POST".equals(request.method) && "/api/storage-sessions".equals(path)) {
                JSONObject body = new JSONObject(request.bodyAsString());
                writeJson(socket, 201, db.startStorageSession(
                        body.optString("item_name"),
                        body.optInt("drawer_number", body.optInt("target_drawer_number")),
                        body.optInt("quantity", 1)));
            } else if ("GET".equals(request.method) && "/api/storage-sessions/current".equals(path)) {
                JSONObject session = db.getActiveStorageSession();
                writeJson(socket, 200, session == null ? new JSONObject().put("active", false) : session.put("active", true));
            } else if ("GET".equals(request.method) && "/api/sensor-events/recent".equals(path)) {
                writeJson(socket, 200, new JSONObject().put("events", recentSensorLog(LOG_CAPACITY)));
            } else if ("POST".equals(request.method) && "/api/sensor-events".equals(path)) {
                JSONObject body = new JSONObject(request.bodyAsString());
                addSensorLog(body);
                String type = body.optString("event_type", body.optString("type")).trim();
                String upperType = type.toUpperCase();
                boolean isAdvance = upperType.contains("VERIFY_SUCCESS")
                        || "success".equalsIgnoreCase(type)
                        || upperType.contains("SEQUENCE_COMPLETED"); // 로드셀 적재 완료 신호
                boolean isFail = upperType.contains("VERIFY_FAIL") || "fail".equalsIgnoreCase(type);
                if (isAdvance) {
                    // Uno Q: 카메라/로드셀 적재 완료 → 현재 물품(순서)을 placed로 진행.
                    // 시간 비교: 직전 진행과 너무 가까우면 중복으로 보고 무시한다.
                    long nowTs = System.currentTimeMillis();
                    JSONObject activeBatch = db.getActivePlacementBatch();
                    if (activeBatch != null && activeBatch.optBoolean("mismatch_pending", false)) {
                        writeJson(socket, 200, activeBatch
                                .put("ignored", true)
                                .put("message", "\uC11C\uB78D \uBD88\uC77C\uCE58 \uD655\uC778 \uB300\uAE30 \uC911"));
                    } else if (nowTs - lastPlacementAdvanceTs < PLACEMENT_DEBOUNCE_MS) {
                        writeJson(socket, 200, new JSONObject()
                                .put("ignored", true)
                                .put("message", "중복 로그 무시 (시간 비교)"));
                    } else {
                        lastPlacementAdvanceTs = nowTs;
                        // 검증 단계: 보고된 서랍번호/무게를 기대값과 대조 후 진행/실패 판정
                        int reportedDrawer = inferDrawerFromSensorData(body);
                        Double weight = null;
                        if (body.has("value")) {
                            weight = body.optDouble("value");
                        } else if (body.has("weight_delta")) {
                            weight = body.optDouble("weight_delta");
                        }
                        long eventAt = parseEventTimestampMs(body, nowTs);
                        writeJson(socket, 200, db.verifyAndAdvancePlacement(reportedDrawer, weight, eventAt));
                    }
                } else if (isFail) {
                    writeJson(socket, 200, db.advancePlacement("fail"));
                } else {
                    // weight_changed 등 그 외 센서값은 기록만 한다.
                    Double value = body.has("value") ? body.optDouble("value") : null;
                    int reportedDrawer = inferDrawerFromSensorData(body);
                    JSONObject activeBatch = db.getActivePlacementBatch();
                    if (activeBatch != null && activeBatch.optBoolean("mismatch_pending", false)) {
                        writeJson(socket, 200, activeBatch
                                .put("ignored", true)
                                .put("message", "\uC11C\uB78D \uBD88\uC77C\uCE58 \uD655\uC778 \uB300\uAE30 \uC911"));
                    } else if (activeBatch != null && reportedDrawer > 0) {
                        long nowTs = System.currentTimeMillis();
                        if (nowTs - lastPlacementAdvanceTs < PLACEMENT_DEBOUNCE_MS) {
                            writeJson(socket, 200, new JSONObject()
                                    .put("ignored", true)
                                    .put("message", "\uC911\uBCF5 \uB85C\uADF8 \uBB34\uC2DC (\uC2DC\uAC04 \uBE44\uAD50)"));
                        } else {
                            lastPlacementAdvanceTs = nowTs;
                            long eventAt = parseEventTimestampMs(body, nowTs);
                            writeJson(socket, 200, db.verifyAndAdvancePlacement(reportedDrawer, value, eventAt));
                        }
                    } else {
                        writeJson(socket, 201, db.recordSensorEvent(body.optInt("drawer_number"), type, value));
                    }
                }
            } else if ("POST".equals(request.method) && "/api/placement-batches".equals(path)) {
                JSONObject body = new JSONObject(request.bodyAsString());
                JSONArray items = body.optJSONArray("items");
                writeJson(socket, 201, db.createPlacementBatch(items == null ? new JSONArray() : items));
            } else if ("GET".equals(request.method) && "/api/placement-batches/current".equals(path)) {
                JSONObject batch = db.getActivePlacementBatch();
                writeJson(socket, 200, batch == null ? new JSONObject().put("active", false) : batch.put("active", true));
            } else if ("POST".equals(request.method) && "/api/placement-batches/advance".equals(path)) {
                JSONObject body = new JSONObject(request.bodyAsString());
                writeJson(socket, 200, db.advancePlacement(body.optString("result", "success")));
            } else if ("POST".equals(request.method) && "/api/settings/gemini-key".equals(path)) {
                JSONObject body = new JSONObject(request.bodyAsString());
                String key = body.optString("gemini_api_key", body.optString("key", "")).trim();
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit().putString(PREF_API_KEY, key).apply();
                writeJson(socket, 200, new JSONObject().put("saved", true).put("has_key", key.length() > 0));
            } else if ("GET".equals(request.method) && "/api/settings/gemini-key".equals(path)) {
                String key = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).getString(PREF_API_KEY, "").trim();
                writeJson(socket, 200, new JSONObject().put("has_key", key.length() > 0));
            } else if ("GET".equals(request.method) && "/api/photos/latest".equals(path)) {
                writeJson(socket, 200, db.latestPhotoUpload());
            } else if ("POST".equals(request.method) && ("/upload".equals(path) || "/api/photos/analyze".equals(path))) {
                handlePhotoUpload(socket, request);
            } else {
                writeJson(socket, 404, new JSONObject().put("error", "not found"));
            }
        } catch (Exception e) {
            try {
                writeJson(socket, 500, new JSONObject().put("error", e.getMessage()));
            } catch (Exception ignored) {
            }
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {
            }
        }
    }

    private void handlePhotoUpload(Socket socket, HttpRequest request) throws Exception {
        String contentType = request.headers.get("content-type");
        if (contentType == null || !contentType.contains("multipart/form-data")) {
            writeJson(socket, 400, new JSONObject().put("error", "multipart/form-data required"));
            return;
        }
        String boundary = null;
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.startsWith("boundary=")) {
                boundary = part.substring("boundary=".length());
            }
        }
        if (boundary == null) {
            writeJson(socket, 400, new JSONObject().put("error", "boundary required"));
            return;
        }
        MultipartFile image = extractImage(request.body, boundary);
        if (image == null || image.bytes.length == 0) {
            writeJson(socket, 400, new JSONObject().put("error", "image required"));
            return;
        }
        String apiKey = extractField(request.body, boundary, "gemini_api_key");
        if (apiKey != null && apiKey.trim().length() > 0) {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(PREF_API_KEY, apiKey.trim())
                    .apply();
        }

        File dir = new File(context.getFilesDir(), "uploads");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String extension = image.filename != null && image.filename.contains(".")
                ? image.filename.substring(image.filename.lastIndexOf('.'))
                : ".jpg";
        File file = new File(dir, UUID.randomUUID().toString() + extension);
        java.io.FileOutputStream fileOutputStream = new java.io.FileOutputStream(file);
        fileOutputStream.write(image.bytes);
        fileOutputStream.close();

        JSONObject analysis;
        String status;
        try {
            analysis = analyzer.analyze(image.bytes, image.mimeType);
            // 물품은 '넣기 완료' 단계에서만 최종 DB에 저장한다. 여기서는 분석 결과만 기록.
            status = analysis.optString("status", "analyzed");
        } catch (Exception e) {
            analysis = new JSONObject().put("status", "uploaded").put("warning", e.getMessage()).put("summary", new JSONArray());
            status = "uploaded";
        }
        JSONObject saved = db.savePhotoUpload(file.getAbsolutePath(), status, analysis);
        JSONObject response = new JSONObject()
                .put("status", status)
                .put("image_path", file.getAbsolutePath())
                .put("upload", saved)
                .put("summary", analysis.optJSONArray("summary") == null ? new JSONArray() : analysis.optJSONArray("summary"))
                .put("result", analysis);

        if ("/upload".equals(request.path)) {
            writeHtml(socket, resultPage(response));
        } else {
            writeJson(socket, 201, response);
        }
    }

    private MultipartFile extractImage(byte[] body, String boundary) throws Exception {
        String marker = "--" + boundary;
        String text = new String(body, "ISO-8859-1");
        int position = 0;
        while (true) {
            int start = text.indexOf(marker, position);
            if (start < 0) {
                return null;
            }
            int headerStart = start + marker.length();
            if (text.startsWith("--", headerStart)) {
                return null;
            }
            if (text.startsWith("\r\n", headerStart)) {
                headerStart += 2;
            }
            int headerEnd = text.indexOf("\r\n\r\n", headerStart);
            if (headerEnd < 0) {
                return null;
            }
            String headers = text.substring(headerStart, headerEnd);
            int dataStart = headerEnd + 4;
            int next = text.indexOf("\r\n" + marker, dataStart);
            if (next < 0) {
                return null;
            }
            if (headers.contains("name=\"image\"") || headers.contains("name=\"file\"")) {
                byte[] data = new byte[next - dataStart];
                System.arraycopy(body, dataStart, data, 0, data.length);
                String filename = headerValue(headers, "filename");
                String mimeType = "image/jpeg";
                for (String line : headers.split("\r\n")) {
                    if (line.toLowerCase().startsWith("content-type:")) {
                        mimeType = line.substring(line.indexOf(':') + 1).trim();
                    }
                }
                return new MultipartFile(filename, mimeType, data);
            }
            position = next + marker.length();
        }
    }

    private String extractField(byte[] body, String boundary, String fieldName) throws Exception {
        String marker = "--" + boundary;
        String text = new String(body, "ISO-8859-1");
        int position = 0;
        while (true) {
            int start = text.indexOf(marker, position);
            if (start < 0) {
                return null;
            }
            int headerStart = start + marker.length();
            if (text.startsWith("--", headerStart)) {
                return null;
            }
            if (text.startsWith("\r\n", headerStart)) {
                headerStart += 2;
            }
            int headerEnd = text.indexOf("\r\n\r\n", headerStart);
            if (headerEnd < 0) {
                return null;
            }
            String headers = text.substring(headerStart, headerEnd);
            int dataStart = headerEnd + 4;
            int next = text.indexOf("\r\n" + marker, dataStart);
            if (next < 0) {
                return null;
            }
            if (headers.contains("name=\"" + fieldName + "\"")) {
                byte[] data = new byte[next - dataStart];
                System.arraycopy(body, dataStart, data, 0, data.length);
                return new String(data, "UTF-8").trim();
            }
            position = next + marker.length();
        }
    }

    private String headerValue(String headers, String key) {
        String needle = key + "=\"";
        int start = headers.indexOf(needle);
        if (start < 0) {
            return null;
        }
        start += needle.length();
        int end = headers.indexOf('"', start);
        return end > start ? headers.substring(start, end) : null;
    }

    private String uploadPage() {
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<title>Temi Upload</title><style>body{font-family:sans-serif;padding:24px;background:#f5f7fa;color:#17202a}" +
                "form{display:grid;gap:16px;max-width:420px}button,input{font-size:18px;padding:14px}" +
                "button{background:#1e40af;color:white;border:0;border-radius:8px}</style></head><body>" +
                "<h1>Temi 사진 업로드</h1>" +
                "<p>수납할 물건 사진을 선택해 올리면 Temi가 자동으로 분석합니다.</p>" +
                "<form action=\"/upload\" method=\"post\" enctype=\"multipart/form-data\">" +
                "<input type=\"file\" name=\"image\" accept=\"image/*\" required>" +
                "<button type=\"submit\">업로드</button></form></body></html>";
    }

    private long parseEventTimestampMs(JSONObject body, long fallback) {
        String timestamp = body.optString("timestamp", "").trim();
        if (timestamp.length() == 0) {
            return fallback;
        }
        try {
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US);
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(timestamp).getTime();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private int inferDrawerFromSensorData(JSONObject body) {
        int fromDelta = maxPositiveDeltaIndex(body.optJSONArray("weight_effective_deltas"));
        if (fromDelta > 0) {
            return fromDelta;
        }
        fromDelta = maxPositiveDeltaIndex(body.optJSONArray("weight_raw_deltas"));
        if (fromDelta > 0) {
            return fromDelta;
        }
        int selected = body.optInt("selected_sensor_index", 0);
        if (selected > 0) {
            return selected;
        }
        return body.optInt("drawer_number", 0);
    }

    private int maxPositiveDeltaIndex(JSONArray values) {
        if (values == null || values.length() == 0) {
            return 0;
        }
        double best = 0;
        int bestIndex = 0;
        for (int i = 0; i < values.length(); i++) {
            double value = values.optDouble(i, 0);
            if (value > best) {
                best = value;
                bestIndex = i + 1;
            }
        }
        return bestIndex;
    }

    private String resultPage(JSONObject response) {
        JSONArray summary = response.optJSONArray("summary");
        JSONArray itemsJs = new JSONArray();
        if (summary != null) {
            for (int i = 0; i < summary.length(); i++) {
                JSONObject item = summary.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String name = item.optString("item_name", item.optString("name", "")).trim();
                if (name.length() == 0) {
                    continue;
                }
                try {
                    itemsJs.put(new JSONObject()
                            .put("item_name", name)
                            .put("quantity", Math.max(1, item.optInt("quantity", 1)))
                            .put("drawer_number", item.optInt("drawer_number", 0)));
                } catch (Exception ignored) {
                }
            }
        }
        String dataJson = itemsJs.toString();

        String css = "body{font-family:sans-serif;padding:20px;background:#f5f7fa;color:#17202a;max-width:520px;margin:0 auto}"
                + "h1{font-size:22px}.card{background:#fff;border-radius:10px;margin:10px 0;overflow:hidden;border:1px solid #e3e8f0;transition:all .15s}"
                + ".head{padding:16px;font-size:17px;cursor:pointer}.card.open{box-shadow:0 6px 20px rgba(30,64,175,.18)}"
                + ".card.open .head{font-size:21px;font-weight:bold;background:#eef2ff}"
                + ".body{padding:0 16px 16px}.body label{display:block;font-size:13px;color:#657184;margin:12px 0 4px}"
                + ".body input{width:100%;box-sizing:border-box;font-size:18px;padding:11px;border:1px solid #d7dee8;border-radius:8px}"
                + ".del{margin-top:14px;background:#fff;color:#b42318;border:1px solid #e5b7b0;border-radius:8px;padding:10px 14px;font-size:16px}"
                + "button.main{width:100%;font-size:18px;padding:14px;border:0;border-radius:8px;color:#fff;margin-top:8px}"
                + "#add{background:#475569}#start{background:#1e40af}#msg{margin-top:14px;font-size:16px;color:#0f7a3b;text-align:center}";

        String js = "var items=" + dataJson + ";var open=-1;var listEl=document.getElementById('list');"
                + "function field(l,v,t,cb){var w=document.createElement('label');w.textContent=l;var i=document.createElement('input');i.type=t;i.value=v;i.oninput=function(){cb(i.value);};i.onclick=function(e){e.stopPropagation();};w.appendChild(i);return w;}"
                + "function render(){listEl.innerHTML='';items.forEach(function(it,idx){var c=document.createElement('div');c.className='card'+(idx===open?' open':'');"
                + "var h=document.createElement('div');h.className='head';h.textContent=(idx+1)+'. '+(it.item_name||'(이름 없음)')+'  ·  '+it.drawer_number+'번 서랍  ·  '+it.quantity+'개';"
                + "h.onclick=function(e){e.stopPropagation();open=(open===idx?-1:idx);render();};c.appendChild(h);"
                + "if(idx===open){var b=document.createElement('div');b.className='body';b.onclick=function(e){e.stopPropagation();};"
                + "b.appendChild(field('물품명',it.item_name,'text',function(v){it.item_name=v;}));"
                + "b.appendChild(field('서랍 번호',it.drawer_number,'number',function(v){it.drawer_number=parseInt(v)||0;}));"
                + "b.appendChild(field('수량',it.quantity,'number',function(v){it.quantity=parseInt(v)||1;}));"
                + "var d=document.createElement('button');d.className='del';d.textContent='이 항목 삭제';d.onclick=function(e){e.stopPropagation();items.splice(idx,1);open=-1;render();};b.appendChild(d);c.appendChild(b);}"
                + "listEl.appendChild(c);});}"
                + "document.body.onclick=function(){if(open!==-1){open=-1;render();}};"
                + "document.getElementById('add').onclick=function(e){e.stopPropagation();items.push({item_name:'',quantity:1,drawer_number:0});open=items.length-1;render();};"
                + "document.getElementById('start').onclick=function(e){e.stopPropagation();if(items.length===0){document.getElementById('msg').textContent='물품을 추가하세요.';return;}fetch('/api/placement-batches',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({items:items})}).then(function(r){return r.json();}).then(function(){document.getElementById('msg').textContent='Temi로 전송했습니다. Temi 화면에서 수납이 시작됩니다.';}).catch(function(){document.getElementById('msg').textContent='전송 실패 - WiFi를 확인하세요.';});};"
                + "render();";

        String intro = itemsJs.length() > 0
                ? "<p>인식된 물품입니다. 항목을 <b>누르면 커지고</b> 수정할 수 있어요. 다른 곳을 누르면 접힙니다.</p>"
                : "<p>물품을 자동 인식하지 못했어요. 아래 <b>+ 물품 추가</b>로 직접 넣어 보세요.</p>";

        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>수납 리스트 수정</title><style>" + css + "</style></head><body>"
                + "<h1>인식 결과 · 수정</h1>" + intro
                + "<div id='list'></div>"
                + "<button class='main' id='add'>+ 물품 추가</button>"
                + "<button class='main' id='start'>Temi로 전송 · 수납 시작</button>"
                + "<div id='msg'></div>"
                + "<script>" + js + "</script></body></html>";
    }

    private String uploadQrPage(String baseUrl) {
        String uploadUrl = baseUrl + "/upload";
        String imageUrl = "/upload-qr.png";
        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Temi Upload QR</title><style>" + mobilePageCss()
                + ".qr{display:block;width:100%;max-width:360px;margin:18px auto;background:#fff;border:1px solid #d7dee8;border-radius:8px;padding:14px;box-sizing:border-box}"
                + ".primary{display:block;text-align:center;background:#1e40af;color:white;text-decoration:none;border-radius:8px;padding:15px;font-size:18px;font-weight:700;margin:12px 0}"
                + ".secondary{display:block;text-align:center;background:#fff;color:#17202a;text-decoration:none;border:1px solid #d7dee8;border-radius:8px;padding:13px;font-size:16px;margin:10px 0}"
                + ".url{word-break:break-all;background:#fff;border:1px solid #d7dee8;border-radius:8px;padding:12px;font-size:14px;color:#475569}</style></head><body>"
                + "<h1>\uC0AC\uC9C4 \uC218\uB0A9 QR</h1>"
                + "<p>\uC544\uB798 QR \uC774\uBBF8\uC9C0\uB97C \uB2E4\uC6B4\uB85C\uB4DC\uD574 \uC0AC\uC9C4\uCCA9\uC5D0 \uC800\uC7A5\uD558\uACE0, \uD544\uC694\uD560 \uB54C \uC2A4\uCE94\uD574 \uC5C5\uB85C\uB4DC \uD398\uC774\uC9C0\uB97C \uC5EC\uC138\uC694.</p>"
                + "<img class='qr' src='" + imageUrl + "' alt='Temi upload QR'>"
                + "<a class='primary' href='" + imageUrl + "' download='temi-upload-qr.png'>\uC774\uBBF8\uC9C0 \uB2E4\uC6B4\uB85C\uB4DC</a>"
                + "<a class='secondary' href='" + escapeHtml(uploadUrl) + "'>\uBE0C\uB77C\uC6B0\uC800\uC5D0\uC11C \uC5C5\uB85C\uB4DC \uD398\uC774\uC9C0 \uC5F4\uAE30</a>"
                + "<div class='url'>" + escapeHtml(uploadUrl) + "</div>"
                + "</body></html>";
    }

    private void writeUploadQrPng(Socket socket, String baseUrl) throws Exception {
        Bitmap bitmap = createQrBitmap(baseUrl + "/upload", 900);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        byte[] bytes = output.toByteArray();
        writeRaw(socket, "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Disposition: attachment; filename=\"temi-upload-qr.png\"\r\nContent-Length: "
                + bytes.length + "\r\nConnection: close\r\n\r\n", bytes);
    }

    private String shoppingQrPage(String id) {
        String payload = shoppingQrPayloads.get(id);
        if (payload == null) {
            return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                    + "<title>Shopping QR</title><style>" + mobilePageCss() + "</style></head><body>"
                    + "<h1>쇼핑 QR을 찾을 수 없습니다</h1>"
                    + "<p>Temi에서 쇼핑 QR을 다시 생성한 뒤 QR을 다시 스캔해주세요.</p>"
                    + "</body></html>";
        }

        JSONArray items = new JSONArray();
        try {
            JSONObject json = new JSONObject(payload);
            JSONArray source = json.optJSONArray("items");
            if (source != null) {
                items = source;
            }
        } catch (Exception ignored) {
        }

        String imageUrl = "/shopping-qr.png?id=" + escapeHtml(id);
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            rows.append("<li>")
                    .append(escapeHtml(item.optString("item_name", "")))
                    .append(" <span>")
                    .append(Math.max(1, item.optInt("quantity", 1)))
                    .append("개</span></li>");
        }

        return "<!doctype html><html><head><meta charset='utf-8'><meta name='viewport' content='width=device-width,initial-scale=1'>"
                + "<title>Temi Shopping QR</title><style>" + mobilePageCss()
                + ".qr{display:block;width:100%;max-width:360px;margin:18px auto;background:#fff;border:1px solid #d7dee8;border-radius:8px;padding:14px;box-sizing:border-box}"
                + ".primary{display:block;text-align:center;background:#1e40af;color:white;text-decoration:none;border-radius:8px;padding:15px;font-size:18px;font-weight:700;margin:12px 0}"
                + ".secondary{display:block;text-align:center;background:#fff;color:#17202a;text-decoration:none;border:1px solid #d7dee8;border-radius:8px;padding:13px;font-size:16px;margin:10px 0}"
                + "ul{padding-left:20px}li{margin:8px 0;font-size:16px}span{color:#657184}</style></head><body>"
                + "<h1>매장 Temi에 보여줄 쇼핑 QR</h1>"
                + "<p>아래 QR 이미지를 사진첩에 저장한 뒤 매장 Temi에 보여주세요.</p>"
                + "<img class='qr' src='" + imageUrl + "' alt='쇼핑 QR'>"
                + "<a class='primary' href='" + imageUrl + "' download='temi-shopping-qr.png'>사진첩에 저장</a>"
                + "<a class='secondary' href='" + imageUrl + "' target='_blank'>QR 이미지만 열기</a>"
                + "<h2>품목 " + items.length() + "개</h2><ul>" + rows + "</ul>"
                + "</body></html>";
    }

    private String mobilePageCss() {
        return "body{font-family:sans-serif;padding:22px;background:#f5f7fa;color:#17202a;max-width:520px;margin:0 auto}"
                + "h1{font-size:24px;margin:8px 0 12px}h2{font-size:18px;margin-top:24px}p{font-size:17px;line-height:1.5;color:#475569}";
    }

    private void writeShoppingQrPng(Socket socket, String id) throws Exception {
        String payload = shoppingQrPayloads.get(id);
        if (payload == null) {
            writeJson(socket, 404, new JSONObject().put("error", "shopping qr not found"));
            return;
        }
        Bitmap bitmap = createQrBitmap(payload, 900);
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        byte[] bytes = output.toByteArray();
        writeRaw(socket, "HTTP/1.1 200 OK\r\nContent-Type: image/png\r\nContent-Disposition: attachment; filename=\"temi-shopping-qr.png\"\r\nContent-Length: "
                + bytes.length + "\r\nConnection: close\r\n\r\n", bytes);
    }

    private Bitmap createQrBitmap(String text, int size) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
        BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bitmap;
    }

    private void writeHtml(Socket socket, String html) throws Exception {
        byte[] bytes = html.getBytes("UTF-8");
        writeRaw(socket, "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=UTF-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n", bytes);
    }

    private void writeJson(Socket socket, int code, JSONObject json) throws Exception {
        byte[] bytes = json.toString().getBytes("UTF-8");
        String status = code == 200 || code == 201 ? "OK" : "ERROR";
        writeRaw(socket, "HTTP/1.1 " + code + " " + status + "\r\nContent-Type: application/json; charset=UTF-8\r\nContent-Length: " + bytes.length + "\r\nConnection: close\r\n\r\n", bytes);
    }

    private void writeRaw(Socket socket, String headers, byte[] body) throws Exception {
        OutputStream outputStream = socket.getOutputStream();
        outputStream.write(headers.getBytes("UTF-8"));
        outputStream.write(body);
        outputStream.flush();
    }

    private String getLocalIpAddress() {
        try {
            WifiManager wifiManager = (WifiManager) context.getApplicationContext().getSystemService(Context.WIFI_SERVICE);
            if (wifiManager != null && wifiManager.getConnectionInfo() != null) {
                String ip = Formatter.formatIpAddress(wifiManager.getConnectionInfo().getIpAddress());
                if (ip != null && !"0.0.0.0".equals(ip)) {
                    return ip;
                }
            }
        } catch (Exception ignored) {
        }
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                for (java.net.InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                    if (!address.isLoopbackAddress() && address instanceof Inet4Address) {
                        return address.getHostAddress();
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "127.0.0.1";
    }

    private String requestBaseUrl(HttpRequest request) {
        String host = request.headers.get("host");
        if (host != null && host.trim().length() > 0) {
            return "http://" + host.trim();
        }
        return getBaseUrl();
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private String valueOrEmpty(String value) {
        return value == null ? "" : value;
    }

    private static class MultipartFile {
        final String filename;
        final String mimeType;
        final byte[] bytes;

        MultipartFile(String filename, String mimeType, byte[] bytes) {
            this.filename = filename;
            this.mimeType = mimeType;
            this.bytes = bytes;
        }
    }

    private static class HttpRequest {
        final String method;
        final String path;
        final Map<String, String> query;
        final Map<String, String> headers;
        final byte[] body;

        HttpRequest(String method, String path, Map<String, String> query, Map<String, String> headers, byte[] body) {
            this.method = method;
            this.path = path;
            this.query = query;
            this.headers = headers;
            this.body = body;
        }

        static HttpRequest read(InputStream inputStream) throws Exception {
            ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
            int matched = 0;
            int b;
            byte[] delimiter = new byte[]{'\r', '\n', '\r', '\n'};
            while ((b = inputStream.read()) != -1) {
                headerBuffer.write(b);
                if (b == delimiter[matched]) {
                    matched++;
                    if (matched == delimiter.length) {
                        break;
                    }
                } else {
                    matched = b == delimiter[0] ? 1 : 0;
                }
            }
            if (headerBuffer.size() == 0) {
                return null;
            }
            String headerText = new String(headerBuffer.toByteArray(), "ISO-8859-1");
            String[] lines = headerText.split("\r\n");
            String[] requestLine = lines[0].split(" ");
            String method = requestLine[0];
            String target = requestLine[1];
            String path = target;
            Map<String, String> query = new HashMap<>();
            int queryIndex = target.indexOf('?');
            if (queryIndex >= 0) {
                path = target.substring(0, queryIndex);
                for (String pair : target.substring(queryIndex + 1).split("&")) {
                    int eq = pair.indexOf('=');
                    if (eq > 0) {
                        query.put(URLDecoder.decode(pair.substring(0, eq), "UTF-8"), URLDecoder.decode(pair.substring(eq + 1), "UTF-8"));
                    }
                }
            }
            Map<String, String> headers = new HashMap<>();
            for (int i = 1; i < lines.length; i++) {
                int colon = lines[i].indexOf(':');
                if (colon > 0) {
                    headers.put(lines[i].substring(0, colon).trim().toLowerCase(), lines[i].substring(colon + 1).trim());
                }
            }
            int contentLength = 0;
            if (headers.containsKey("content-length")) {
                contentLength = Integer.parseInt(headers.get("content-length"));
            }
            byte[] body = new byte[contentLength];
            int offset = 0;
            while (offset < contentLength) {
                int read = inputStream.read(body, offset, contentLength - offset);
                if (read < 0) {
                    break;
                }
                offset += read;
            }
            return new HttpRequest(method, path, query, headers, body);
        }

        String bodyAsString() throws Exception {
            return new String(body, "UTF-8");
        }
    }
}
