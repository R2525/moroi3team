package org.techtown.hello;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.text.format.Formatter;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class TemiLocalServer {
    public static final int PORT = 8088;
    private static final String PREFS_NAME = "temi_settings";
    private static final String PREF_API_KEY = "gemini_api_key";

    private final Context context;
    private final TemiDbHelper db;
    private final GeminiPhotoAnalyzer analyzer;
    private ServerSocket serverSocket;
    private volatile boolean running;

    // Uno Q가 보내는 sensor-event를 실시간으로 보기 위한 인메모리 링버퍼 (최신이 앞)
    private static final int LOG_CAPACITY = 100;
    private final java.util.ArrayDeque<JSONObject> sensorLog = new java.util.ArrayDeque<>();

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
            } else if ("GET".equals(request.method) && "/api/health".equals(path)) {
                writeJson(socket, 200, db.health());
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
                if (upperType.contains("VERIFY_SUCCESS") || "success".equalsIgnoreCase(type)) {
                    // Uno Q: 카메라 YOLO + 로드셀 둘 다 만족 → 현재 물품 완료, 다음으로 진행
                    writeJson(socket, 200, db.advancePlacement("success"));
                } else if (upperType.contains("VERIFY_FAIL") || "fail".equalsIgnoreCase(type)) {
                    writeJson(socket, 200, db.advancePlacement("fail"));
                } else {
                    Double value = body.has("value") ? body.optDouble("value") : null;
                    writeJson(socket, 201, db.recordSensorEvent(body.optInt("drawer_number"), type, value));
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
