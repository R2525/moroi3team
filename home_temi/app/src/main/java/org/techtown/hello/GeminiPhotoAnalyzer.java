package org.techtown.hello;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

public class GeminiPhotoAnalyzer {
    private static final String PREFS_NAME = "temi_settings";
    private static final String PREF_API_KEY = "gemini_api_key";
    private static final String[] MODELS = new String[]{
            "gemini-2.0-flash-lite",
            "gemini-2.0-flash",
            "gemini-1.5-flash",
            "gemini-flash-latest"
    };

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

        String effectiveMime = mimeType == null ? "image/jpeg" : mimeType;
        byte[] downscaled = downscaleToJpeg(imageBytes);
        if (downscaled != null) {
            imageBytes = downscaled;
            effectiveMime = "image/jpeg";
        }

        JSONObject payload = new JSONObject();
        JSONArray contents = new JSONArray();
        JSONObject content = new JSONObject();
        JSONArray parts = new JSONArray();
        parts.put(new JSONObject().put("text",
                "사진 속 수납할 물품들을 인식하세요. 서랍장은 3개이고 위에서부터 1번, 2번, 맨 아래가 3번입니다. "
                        + "각 물품의 추천 서랍 번호(drawer_number)를 1~3 중에서만 지정하세요. "
                        + "무겁거나 큰 물품은 아래쪽 서랍(3번), 가볍고 작은 물품은 위쪽 서랍(1번)을 권장합니다. "
                        + "서랍에 넣기 좋은 순서(order, 1부터 시작하는 정수)로 정렬하되, 같은 서랍 물품은 연속으로 묶고 아래쪽 서랍부터 넣도록 배정하세요. "
                        + "한국어 JSON으로만 응답하세요. "
                        + "형식: {\"summary\":[{\"item_name\":\"물품명\",\"quantity\":1,\"drawer_number\":1,\"order\":1,\"confidence\":0.8}]}"));
        parts.put(new JSONObject().put("inline_data", new JSONObject()
                .put("mime_type", effectiveMime)
                .put("data", Base64.encodeToString(imageBytes, Base64.NO_WRAP))));
        content.put("parts", parts);
        contents.put(content);
        payload.put("contents", contents);

        byte[] payloadBytes = payload.toString().getBytes("UTF-8");
        int code = 0;
        String body = "";
        String usedModel = MODELS[0];
        for (String model : MODELS) {
            usedModel = model;
            for (int attempt = 1; attempt <= 2; attempt++) {
                URL url = new URL("https://generativelanguage.googleapis.com/v1beta/models/" + model + ":generateContent?key=" + apiKey);
                HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("POST");
                connection.setConnectTimeout(10000);
                connection.setReadTimeout(60000);
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
                OutputStream outputStream = connection.getOutputStream();
                outputStream.write(payloadBytes);
                outputStream.close();

                code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
                body = readBody(stream);
                connection.disconnect();

                if (code >= 200 && code < 300) {
                    JSONObject parsed = parseGeminiResponse(new JSONObject(body));
                    parsed.put("llm_model", model);
                    return parsed;
                }
                if (code == 429) {
                    break;
                }
                if (code != 503) {
                    break;
                }
                if (attempt < 2) {
                    try {
                        Thread.sleep(1200L * attempt);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        throw new IllegalStateException(body.length() == 0 ? "Gemini HTTP " + code + " (" + usedModel + ")" : body);
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
        return parsed;
    }

    private byte[] downscaleToJpeg(byte[] input) {
        try {
            Bitmap bitmap = BitmapFactory.decodeByteArray(input, 0, input.length);
            if (bitmap == null) {
                return null;
            }
            int max = 1024;
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            float scale = Math.min(1f, (float) max / Math.max(width, height));
            if (scale < 1f) {
                bitmap = Bitmap.createScaledBitmap(bitmap, Math.round(width * scale), Math.round(height * scale), true);
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 80, output);
            return output.toByteArray();
        } catch (Throwable t) {
            return null;
        }
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
