package org.techtown.hello;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class GeminiPhotoAnalyzer {
    private static final String PREFS_NAME = "temi_settings";
    private static final String PREF_API_KEY = "gemini_api_key";
    private static final String MODEL = "gemini-flash-latest";

    private final Context context;

    public GeminiPhotoAnalyzer(Context context) {
        this.context = context.getApplicationContext();
    }

    public JSONObject analyze(byte[] imageBytes, String mimeType) throws Exception {
        String apiKey = readApiKey();
        if (apiKey.length() == 0) {
            JSONObject result = new JSONObject();
            result.put("status", "uploaded");
            result.put("warning", "Gemini API key is not configured on Temi.");
            result.put("summary", new JSONArray());
            return result;
        }

        JSONObject payload = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject content = new JSONObject();
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text",
                "사진 속 수납 물품을 한국어 JSON으로만 답하세요. 형식: {\"summary\":[{\"item_name\":\"물품명\",\"quantity\":1,\"confidence\":0.8}]}"));
        parts.put(new JSONObject().put("inline_data", new JSONObject()
                .put("mime_type", mimeType == null ? "image/jpeg" : mimeType)
                .put("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))));
        content.put("parts", parts);
        contents.put(content);
        payload.put("contents", contents);

        URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + MODEL + ":generateContent?key=" + apiKey);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(10000);
        connection.setReadTimeout(30000);
        connection.setDoOutput(true);
        connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        OutputStream outputStream = connection.getOutputStream();
        outputStream.write(payload.toString().getBytes("UTF-8"));
        outputStream.close();

        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String body = readBody(stream);
        connection.disconnect();
        if (code < 200 || code >= 300) {
            throw new IllegalStateException(body.length() == 0 ? "Gemini HTTP " + code : body);
        }
        return parseGeminiResponse(new JSONObject(body));
    }

    private JSONObject parseGeminiResponse(JSONObject response) throws Exception {
        String text = response
                .getJSONArray("candidates")
                .getJSONObject(0)
                .getJSONObject("content")
                .getJSONArray("parts")
                .getJSONObject(0)
                .optString("text", "");
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        JSONObject parsed = start >= 0 && end > start
                ? new JSONObject(text.substring(start, end + 1))
                : new JSONObject().put("raw_text", text);
        parsed.put("status", "analyzed");
        parsed.put("llm_model", MODEL);
        return parsed;
    }

    private String readApiKey() {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(PREF_API_KEY, "").trim();
    }

    private String readBody(InputStream stream) throws Exception {
        if (stream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }
}
