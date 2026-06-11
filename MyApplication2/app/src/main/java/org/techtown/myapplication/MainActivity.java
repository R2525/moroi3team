package org.techtown.myapplication;

import android.graphics.Color;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {

    private static final int REQUEST_PICK_IMAGE = 1001;
    private static final String DEFAULT_API_BASE_URL = "http://10.0.2.2:18000";
    private static final String IP_CONFIG_FILE = "ip.json";
    private static final String PREFS_NAME = "temi_settings";
    private static final String PREF_API_BASE_URL = "api_base_url";

    private FrameLayout screenRoot;
    private Uri selectedImageUri;
    private final List<DetectedPhotoItem> detectedPhotoItems = new ArrayList<>();
    private String autoDrawerStatusText = "서랍 센서: 대기 중";
    private String apiBaseUrl = DEFAULT_API_BASE_URL;
    private final List<String> shoppingUsers = new ArrayList<>(Arrays.asList("사용자 1", "사용자 2"));
    private final List<Integer> shoppingUserIds = new ArrayList<>(Arrays.asList(null, null));
    private final List<List<String>> cartItemsByUser = initialCartItems();
    private final List<List<Integer>> cartItemIdsByUser = initialCartItemIds();
    private final List<String> storageItems = new ArrayList<>(Arrays.asList("세제 / 1번 서랍", "수건 / 2번 서랍", "샴푸 / 3번 서랍", "휴지 / 1번 서랍"));
    private final List<String> dbItems = new ArrayList<>(Arrays.asList("세제 1개 / 1번 서랍", "수건 3개 / 2번 서랍", "샴푸 1개 / 3번 서랍", "휴지 2개 / 1번 서랍"));
    private String dbStatusText = "DB 서버: 확인 전";
    private final List<IntegratedShoppingList> integratedShoppingLists = new ArrayList<>();
    private int selectedShoppingUserIndex = 0;
    private int selectedIntegratedListIndex = -1;
    private int nextShoppingUserNumber = 3;
    private Integer storeTemiStoreId = null;

    private static List<List<String>> initialCartItems() {
        List<List<String>> items = new ArrayList<>();
        items.add(new ArrayList<>(Arrays.asList("세제 1개", "수건 3개", "샴푸 1개", "휴지 2개")));
        items.add(new ArrayList<String>());
        return items;
    }

    private static List<List<Integer>> initialCartItemIds() {
        List<List<Integer>> itemIds = new ArrayList<>();
        itemIds.add(new ArrayList<Integer>(Arrays.asList(null, null, null, null)));
        itemIds.add(new ArrayList<Integer>());
        return itemIds;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        screenRoot = findViewById(R.id.screenRoot);

        String persistedApiBaseUrl = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getString(PREF_API_BASE_URL, null);
        String assetApiBaseUrl = loadApiBaseUrlFromAssets();

        if (assetApiBaseUrl != null && assetApiBaseUrl.length() > 0) {
            apiBaseUrl = assetApiBaseUrl;
        } else if (persistedApiBaseUrl != null && persistedApiBaseUrl.length() > 0) {
            apiBaseUrl = persistedApiBaseUrl;
        } else {
            apiBaseUrl = DEFAULT_API_BASE_URL;
        }

        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putString(PREF_API_BASE_URL, apiBaseUrl)
                .apply();

        showHome();
    }

    private void showHome() {
        LinearLayout page = page("TEMI Shopping", "수납, 쇼핑리스트, DB 조회, Temi 연결을 한 곳에서 관리합니다.");

        LinearLayout status = card();
        status.addView(label("연결 상태"));
        status.addView(body("가정 Temi: 미연결"));
        status.addView(body("매장 Temi: 미연결"));
        status.addView(body("서랍: 1개 연결 대기"));
        page.addView(status);

        page.addView(navButton("수납추가", "카메라로 물품을 확인하고 서랍 수납을 진행합니다.", new View.OnClickListener() {
            @Override public void onClick(View v) { showCamera(); }
        }));
        page.addView(navButton("쇼핑리스트", "여러 사용자의 구매 요청을 합치고 저장합니다.", new View.OnClickListener() {
            @Override public void onClick(View v) { showShoppingInput(); }
        }));
        page.addView(navButton("DB 조회", "물품명, 개수, 위치를 빠르게 검색합니다.", new View.OnClickListener() {
            @Override public void onClick(View v) { showDb(); }
        }));
        page.addView(navButton("설정", "서버 주소와 Temi 연결을 설정합니다.", new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        }));

        setScreen(page);
    }

    private void showCamera() {
        LinearLayout page = page("카메라촬영", "수납할 물품을 촬영하거나 갤러리에서 선택합니다.");
        page.addView(backButton());

        TextView camera = new TextView(this);
        if (selectedImageUri == null) {
            camera.setText("카메라 미리보기\n\n가이드 프레임 안에 물품을 놓아주세요");
        } else {
            camera.setText("갤러리 이미지 선택됨\n\n" + selectedImageUri);
        }
        camera.setGravity(Gravity.CENTER);
        camera.setTextColor(Color.WHITE);
        camera.setTextSize(18);
        camera.setBackgroundResource(R.drawable.temi_camera_panel);
        page.addView(camera, params(-1, dp(260), 0, 8, 0, 16));

        LinearLayout actions = row();
        actions.addView(button("촬영", true, null), weightParams());
        actions.addView(button("갤러리", false, new View.OnClickListener() {
            @Override public void onClick(View v) { openGallery(); }
        }), weightParams());
        page.addView(actions);

        LinearLayout drawerSetup = card();
        drawerSetup.addView(label("자동 서랍 감지"));
        drawerSetup.addView(body("서랍 위 센서가 서버에 올린 최신 서랍 번호를 저장 시 자동으로 사용합니다."));
        drawerSetup.addView(body(autoDrawerStatusText));
        drawerSetup.addView(button("서랍 센서 정보 새로고침", false, new View.OnClickListener() {
            @Override public void onClick(View v) { refreshAutoDrawerStatus(); }
        }));
        page.addView(drawerSetup);

        page.addView(button("분석", true, new View.OnClickListener() {
            @Override public void onClick(View v) { analyzeSelectedPhoto(); }
        }));

        LinearLayout form = card();
        form.addView(label("수동 DB 서버 저장"));
        final EditText itemNameInput = input("물품명입력");
        final EditText quantityInput = input("개수입력");
        quantityInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        if (!detectedPhotoItems.isEmpty()) {
            itemNameInput.setText(detectedPhotoItems.get(0).name);
            quantityInput.setText(String.valueOf(detectedPhotoItems.get(0).quantity));
        }
        form.addView(itemNameInput);
        form.addView(quantityInput);
        form.addView(button("DB 서버에 저장", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveDbItemWithAutoDrawer(itemNameInput, quantityInput, new Runnable() {
                    @Override public void run() {
                        showCamera();
                    }
                });
            }
        }));
        page.addView(form);

        LinearLayout note = card();
        note.addView(label("Mock 분석 결과"));
        note.addView(body("세제, 수건, 샴푸를 인식한 상황으로 다음 화면을 구성합니다."));
        page.addView(note);

        LinearLayout analysisResult = card();
        analysisResult.addView(label("Gemini \uBD84\uC11D \uACB0\uACFC"));
        if (detectedPhotoItems.isEmpty()) {
            analysisResult.addView(body("\uC0AC\uC9C4\uC744 \uC120\uD0DD\uD55C \uB4A4 \uBD84\uC11D \uBC84\uD2BC\uC744 \uB20C\uB7EC\uC8FC\uC138\uC694."));
        } else {
            analysisResult.addView(body("물품명별로 DB에 자동 저장했습니다. 잘못 인식된 항목은 아래에서 수정 후 다시 저장할 수 있습니다."));
            for (int i = 0; i < detectedPhotoItems.size(); i++) {
                analysisResult.addView(detectedPhotoItemEditor(detectedPhotoItems.get(i), i));
            }
        }
        page.addView(analysisResult);

        setScreen(page);
    }

    private LinearLayout detectedPhotoItemEditor(DetectedPhotoItem item, final int index) {
        LinearLayout editor = new LinearLayout(this);
        editor.setOrientation(LinearLayout.VERTICAL);
        editor.setPadding(0, dp(10), 0, dp(10));

        editor.addView(label((index + 1) + ". " + item.name + " / confidence " + item.confidence));

        final EditText itemNameInput = input("물품명입력");
        itemNameInput.setText(item.name);
        final EditText quantityInput = input("개수입력");
        quantityInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        quantityInput.setText(String.valueOf(item.quantity));

        editor.addView(itemNameInput);
        editor.addView(quantityInput);
        LinearLayout actions = row();
        actions.addView(button("수정 후 저장", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveDbItemWithAutoDrawer(itemNameInput, quantityInput, new Runnable() {
                    @Override public void run() {
                        showCamera();
                    }
                });
            }
        }), weightParams());
        actions.addView(dangerButton("삭제", new View.OnClickListener() {
            @Override public void onClick(View v) {
                deleteDetectedPhotoItem(index);
            }
        }), weightParams());
        editor.addView(actions);
        return editor;
    }

    private void deleteDetectedPhotoItem(int index) {
        if (index < 0 || index >= detectedPhotoItems.size()) {
            return;
        }
        DetectedPhotoItem removedItem = detectedPhotoItems.remove(index);
        Toast.makeText(this, removedItem.name + " 후보를 삭제했습니다.", Toast.LENGTH_SHORT).show();
        showCamera();
    }

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("image/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);

        try {
            startActivityForResult(intent, REQUEST_PICK_IMAGE);
        } catch (ActivityNotFoundException openDocumentMissing) {
            Intent fallback = new Intent(Intent.ACTION_PICK);
            fallback.setType("image/*");
            try {
                startActivityForResult(fallback, REQUEST_PICK_IMAGE);
            } catch (ActivityNotFoundException pickMissing) {
                Toast.makeText(this, "이미지 선택 앱을 찾을 수 없습니다.", Toast.LENGTH_SHORT).show();
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQUEST_PICK_IMAGE && resultCode == RESULT_OK && data != null && data.getData() != null) {
            selectedImageUri = data.getData();
            detectedPhotoItems.clear();
            int readPermission = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            try {
                if (readPermission != 0) {
                    getContentResolver().takePersistableUriPermission(selectedImageUri, readPermission);
                }
            } catch (SecurityException ignored) {
                // Some gallery apps return a temporary URI. It is still usable for this screen.
            }
            Toast.makeText(this, "갤러리 이미지를 선택했습니다.", Toast.LENGTH_SHORT).show();
            showCamera();
        }
    }

    private void analyzeSelectedPhoto() {
        if (selectedImageUri == null) {
            Toast.makeText(this, "\uBA3C\uC800 \uAC24\uB7EC\uB9AC\uC5D0\uC11C \uC0AC\uC9C4\uC744 \uC120\uD0DD\uD574\uC8FC\uC138\uC694.", Toast.LENGTH_SHORT).show();
            return;
        }
        Toast.makeText(this, "Gemini \uBD84\uC11D\uC744 \uC694\uCCAD\uD569\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show();
        uploadPhotoForAnalysis(selectedImageUri, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                detectedPhotoItems.clear();
                detectedPhotoItems.addAll(parseDetectedPhotoItems(response));
                if (detectedPhotoItems.isEmpty()) {
                    Toast.makeText(MainActivity.this, "\uC778\uC2DD\uB41C \uBB3C\uD488\uC774 \uC5C6\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_LONG).show();
                    showCamera();
                } else {
                    Toast.makeText(MainActivity.this, "Gemini\uAC00 " + detectedPhotoItems.size() + "\uAC1C \uBB3C\uD488\uC744 \uC778\uC2DD\uD588\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show();
                    saveDetectedItemsWithAutoDrawer();
                }
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "\uC0AC\uC9C4 \uBD84\uC11D \uC2E4\uD328: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void uploadPhotoForAnalysis(final Uri imageUri, final ApiCallback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection connection = null;
                InputStream imageStream = null;
                OutputStream outputStream = null;
                try {
                    String boundary = "temi-photo-" + System.currentTimeMillis();
                    URL url = new URL(apiBaseUrl + "/api/photos/analyze");
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("POST");
                    connection.setConnectTimeout(10000);
                    connection.setReadTimeout(30000);
                    connection.setDoOutput(true);
                    connection.setRequestProperty("Accept", "application/json");
                    connection.setRequestProperty("Content-Type", "multipart/form-data; boundary=" + boundary);
                    Log.d("TemiApi", "POST " + url + " multipart image=" + imageUri);

                    outputStream = connection.getOutputStream();
                    String mimeType = getContentResolver().getType(imageUri);
                    if (mimeType == null) {
                        mimeType = "image/jpeg";
                    }
                    writeString(outputStream, "--" + boundary + "\r\n");
                    writeString(outputStream, "Content-Disposition: form-data; name=\"image\"; filename=\"temi_photo.jpg\"\r\n");
                    writeString(outputStream, "Content-Type: " + mimeType + "\r\n\r\n");
                    imageStream = getContentResolver().openInputStream(imageUri);
                    if (imageStream == null) {
                        throw new IOException("\uC0AC\uC9C4 \uD30C\uC77C\uC744 \uC5F4 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.");
                    }
                    byte[] buffer = new byte[8192];
                    int read;
                    while ((read = imageStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, read);
                    }
                    writeString(outputStream, "\r\n--" + boundary + "--\r\n");
                    outputStream.flush();

                    int responseCode = connection.getResponseCode();
                    InputStream responseStream = responseCode >= 200 && responseCode < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream();
                    String responseBody = readStream(responseStream);
                    Log.d("TemiApi", "response " + responseCode + " " + responseBody);
                    final JSONObject responseJson = parseJsonObject(responseBody);
                    if (responseCode >= 200 && responseCode < 300) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                callback.onSuccess(responseJson);
                            }
                        });
                    } else {
                        final String errorMessage = responseJson.optString(
                                "detail",
                                responseJson.optString("error", responseBody.length() == 0 ? "HTTP " + responseCode : responseBody));
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                callback.onFailure(errorMessage);
                            }
                        });
                    }
                } catch (final Exception error) {
                    Log.e("TemiApi", "POST /api/photos/analyze failed", error);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            callback.onFailure(error.getMessage() == null ? "\uC0AC\uC9C4 \uBD84\uC11D \uC5F0\uACB0 \uC2E4\uD328" : error.getMessage());
                        }
                    });
                } finally {
                    try {
                        if (imageStream != null) {
                            imageStream.close();
                        }
                        if (outputStream != null) {
                            outputStream.close();
                        }
                    } catch (IOException ignored) {
                    }
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }

    private void writeMultipartText(OutputStream outputStream, String boundary, String name, String value) throws IOException {
        writeString(outputStream, "--" + boundary + "\r\n");
        writeString(outputStream, "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n");
        writeString(outputStream, value + "\r\n");
    }

    private void writeString(OutputStream outputStream, String value) throws IOException {
        outputStream.write(value.getBytes(StandardCharsets.UTF_8));
    }

    private List<DetectedPhotoItem> parseDetectedPhotoItems(JSONObject response) {
        List<DetectedPhotoItem> detectedItems = new ArrayList<>();
        JSONArray summary = response.optJSONArray("summary");
        if (summary != null && summary.length() > 0) {
            for (int i = 0; i < summary.length(); i++) {
                JSONObject item = summary.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                String itemName = item.optString("item_name", item.optString("name", ""));
                if (itemName.length() == 0) {
                    continue;
                }
                detectedItems.add(new DetectedPhotoItem(
                        item.optInt("id"),
                        itemName,
                        extractDetectedQuantity(item),
                        item.optDouble("confidence", 0.0)));
            }
            return detectedItems;
        }

        JSONArray items = response.optJSONArray("items");
        if (items == null) {
            return detectedItems;
        }

        Map<String, Integer> quantityByName = new HashMap<>();
        Map<String, Double> confidenceByName = new HashMap<>();
        Map<String, Integer> idByName = new HashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String itemName = item.optString("item_name", "");
            if (itemName.length() == 0) {
                continue;
            }
            int quantity = extractDetectedQuantity(item);
            Integer currentQuantity = quantityByName.get(itemName);
            quantityByName.put(itemName, (currentQuantity == null ? 0 : currentQuantity) + quantity);
            if (!confidenceByName.containsKey(itemName)) {
                confidenceByName.put(itemName, item.optDouble("confidence", 0.0));
                idByName.put(itemName, item.optInt("id"));
            }
        }

        for (String itemName : quantityByName.keySet()) {
            detectedItems.add(new DetectedPhotoItem(
                    idByName.get(itemName),
                    itemName,
                    quantityByName.get(itemName),
                    confidenceByName.get(itemName)));
        }
        return detectedItems;
    }

    private void showStorageList() {
        LinearLayout page = page("수납리스트", "촬영 이후 탐색된 물품을 확인하고 수납 순서를 정합니다.");
        page.addView(backTo("카메라로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { showCamera(); }
        }));

        LinearLayout list = card();
        list.addView(label("임시 리스트"));
        for (String item : storageItems) {
            list.addView(listItem(item, "탐색됨"));
        }
        page.addView(list);

        LinearLayout actions = row();
        actions.addView(button("음성추가", false, null), weightParams());
        actions.addView(button("수동추가", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showManualStorageAdd(); }
        }), weightParams());
        page.addView(actions);

        page.addView(button("수납 시작", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showStorageGuide(); }
        }));

        setScreen(page);
    }

    private void showManualStorageAdd() {
        LinearLayout page = page("수동추가", "수납할 물품과 서랍 위치를 직접 입력합니다.");
        page.addView(backTo("리스트로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { showStorageList(); }
        }));

        LinearLayout form = card();
        form.addView(label("수납 물품"));
        final EditText itemNameInput = input("물품명입력");
        final EditText quantityInput = input("개수입력");
        final EditText drawerNumberInput = input("서랍번호입력");
        quantityInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        drawerNumberInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(itemNameInput);
        form.addView(quantityInput);
        form.addView(drawerNumberInput);
        form.addView(button("DB 서버에 저장", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveDbItem(itemNameInput, quantityInput, drawerNumberInput, new Runnable() {
                    @Override public void run() {
                        showStorageList();
                    }
                });
            }
        }));
        page.addView(form);

        LinearLayout list = card();
        list.addView(label("현재 수납리스트 - " + storageItems.size() + "개"));
        for (String item : storageItems) {
            list.addView(listItem(item, "대기"));
        }
        page.addView(list);

        setScreen(page);
    }

    private void addStorageItem(EditText itemNameInput, EditText drawerNumberInput) {
        String itemName = itemNameInput.getText().toString().trim();
        String drawerNumberText = drawerNumberInput.getText().toString().trim();

        if (itemName.length() == 0) {
            Toast.makeText(this, "물품명을 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (drawerNumberText.length() == 0) {
            Toast.makeText(this, "서랍 번호를 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        int drawerNumber;
        try {
            drawerNumber = Integer.parseInt(drawerNumberText);
        } catch (NumberFormatException invalidDrawerNumber) {
            Toast.makeText(this, "서랍 번호는 숫자로 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        if (drawerNumber <= 0) {
            Toast.makeText(this, "서랍 번호는 1번 이상이어야 합니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("item_name", itemName);
            payload.put("location_name", drawerNumber + "번 서랍");
            payload.put("drawer_number", drawerNumber);
            payload.put("led_channel", drawerNumber);
            payload.put("description", drawerNumber + "번 서랍에 보관된 " + itemName);
            payload.put("quantity", 1);
        } catch (JSONException error) {
            Toast.makeText(this, "저장 데이터를 만들 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        requestJson("POST", "/api/placements", payload, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                storageItems.add(itemName + " / " + drawerNumber + "번 서랍");
                Toast.makeText(MainActivity.this, "DB 서버에 수납 물품을 저장했습니다.", Toast.LENGTH_SHORT).show();
                showStorageList();
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "DB 저장 실패: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showStorageGuide() {
        LinearLayout page = page("수납가이드", "현재 대상 물품과 센서 상태를 보며 수납을 진행합니다.");
        page.addView(backTo("리스트로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { showStorageList(); }
        }));

        LinearLayout current = card();
        current.addView(label("지금 수납"));
        current.addView(big("1번 서랍에 세제를 넣어주세요"));
        current.addView(body("진행률 2 / 5"));
        ProgressBar progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setProgress(40);
        current.addView(progress, params(-1, dp(12), 0, 12, 0, 8));
        current.addView(body("카메라 ● 정상    로드셀 ● 대기"));
        page.addView(current);

        LinearLayout order = card();
        order.addView(label("수납 순서"));
        for (String item : storageItems) {
            order.addView(listItem(item, "대기"));
        }
        page.addView(order);

        page.addView(button("먼저진행", false, null));
        page.addView(button("후위순위로 변경", false, null));
        page.addView(button("완료처리", true, null));
        page.addView(button("미완료 처리", false, null));
        page.addView(button("완료", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showHome(); }
        }));

        setScreen(page);
    }

    private void showShoppingInput() {
        LinearLayout page = page("쇼핑리스트입력", "사용자별 구매 요청을 입력하고 하나의 리스트로 통합합니다.");
        page.addView(backButton());

        LinearLayout users = card();
        users.addView(label("사용자"));
        users.addView(shoppingUserRow());
        final EditText userNameInput = input("사용자 이름");
        userNameInput.setText(shoppingUsers.get(selectedShoppingUserIndex));
        users.addView(userNameInput);
        users.addView(button("이름 수정", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                renameShoppingUser(userNameInput);
            }
        }));
        page.addView(users);

        LinearLayout input = card();
        input.addView(label(shoppingUsers.get(selectedShoppingUserIndex) + " 물품 추가"));
        final EditText itemNameInput = input("물품명입력");
        final EditText itemCountInput = input("개수입력");
        itemCountInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.addView(itemNameInput);
        input.addView(itemCountInput);
        input.addView(button("추가", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                addShoppingItem(itemNameInput, itemCountInput);
            }
        }));
        page.addView(input);

        List<String> selectedCartItems = selectedCartItems();
        LinearLayout cart = card();
        cart.addView(label(shoppingUsers.get(selectedShoppingUserIndex) + " 구매 품목 - " + selectedCartItems.size() + "개"));
        if (selectedCartItems.isEmpty()) {
            cart.addView(body("추가된 구매 품목이 없습니다."));
        } else {
            for (int i = 0; i < selectedCartItems.size(); i++) {
                cart.addView(shoppingCartItem(selectedCartItems.get(i), i));
            }
        }
        page.addView(cart);

        page.addView(button("통합 리스트 생성", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showShoppingListName(); }
        }));

        setScreen(page);
    }

    private void showShoppingListName() {
        LinearLayout page = page("\uB9AC\uC2A4\uD2B8 \uC774\uB984 \uC9C0\uC815", "\uD1B5\uD569 \uC1FC\uD551\uB9AC\uC2A4\uD2B8\uB97C DB \uC11C\uBC84\uC5D0 \uC800\uC7A5\uD569\uB2C8\uB2E4.");
        page.addView(backTo("\uC1FC\uD551\uB9AC\uC2A4\uD2B8\uB85C \uB3CC\uC544\uAC00\uAE30", new View.OnClickListener() {
            @Override public void onClick(View v) { showShoppingInput(); }
        }));

        LinearLayout form = card();
        form.addView(label("\uD1B5\uD569 \uB9AC\uC2A4\uD2B8"));
        final EditText listNameInput = input("\uB9AC\uC2A4\uD2B8 \uC774\uB984 \uC9C0\uC815");
        form.addView(listNameInput);
        form.addView(button("\uD1B5\uD569 \uB9AC\uC2A4\uD2B8 DB \uC800\uC7A5", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                String title = listNameInput.getText().toString().trim();
                if (title.length() == 0) {
                    Toast.makeText(MainActivity.this, "\uB9AC\uC2A4\uD2B8 \uC774\uB984\uC744 \uC785\uB825\uD574\uC8FC\uC138\uC694.", Toast.LENGTH_SHORT).show();
                    return;
                }
                createIntegratedShoppingList(title);
            }
        }));
        page.addView(form);

        setScreen(page);
    }

    private void showShoppingSave() {
        LinearLayout page = page("쇼핑리스트저장", "보유 항목과 구매 필요 항목을 분류한 뒤 저장합니다.");
        page.addView(backTo("입력으로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { showShoppingInput(); }
        }));

        LinearLayout summary = card();
        summary.addView(label("분류완료 - " + totalCartItemCount() + "개 항목"));
        if (totalCartItemCount() == 0) {
            summary.addView(body("저장할 구매 품목이 없습니다."));
        } else {
            Map<String, Integer> ownedQuantityByName = buildOwnedQuantityByName();
            for (int userIndex = 0; userIndex < shoppingUsers.size(); userIndex++) {
                List<String> userCartItems = cartItemsByUser.get(userIndex);
                for (String item : userCartItems) {
                    ItemQuantity requestedItem = parseItemQuantity(item);
                    int ownedQuantity = ownedQuantityByName.containsKey(requestedItem.name)
                            ? ownedQuantityByName.get(requestedItem.name)
                            : 0;
                    String result = ownedQuantity >= requestedItem.quantity
                            ? "이미 보유하고 있습니다"
                            : "구매필요";
                    summary.addView(listItem(
                            item,
                            shoppingUsers.get(userIndex) + " / " + result
                                    + " (보유 " + ownedQuantity + "개, 요청 " + requestedItem.quantity + "개)"));
                }
            }
        }
        page.addView(summary);

        LinearLayout actions = row();
        actions.addView(button("보유항목제외", false, null), weightParams());
        actions.addView(button("리스트 저장", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showStoreTemi(); }
        }), weightParams());
        page.addView(actions);

        setScreen(page);
    }

    private void addShoppingItem(EditText itemNameInput, EditText itemCountInput) {
        String itemName = itemNameInput.getText().toString().trim();
        String itemCountText = itemCountInput.getText().toString().trim();

        if (itemName.length() == 0) {
            Toast.makeText(this, "물품명을 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        int itemCount = 1;
        if (itemCountText.length() > 0) {
            try {
                itemCount = Integer.parseInt(itemCountText);
            } catch (NumberFormatException invalidCount) {
                Toast.makeText(this, "개수는 숫자로 입력해주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (itemCount <= 0) {
            Toast.makeText(this, "개수는 1개 이상이어야 합니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        final String finalItemNameForPurchase = itemName;
        final int finalItemCountForPurchase = itemCount;
        if (isAlreadyOwned(itemName, itemCount)) {
            showAlreadyOwnedNotice(new View.OnClickListener() {
                @Override public void onClick(View v) {
                    addShoppingItemToServer(finalItemNameForPurchase, finalItemCountForPurchase);
                }
            });
            return;
        }

        addShoppingItemToServer(itemName, itemCount);
    }

    private void addShoppingItemToServer(final String itemName, int itemCount) {
        Integer userId = shoppingUserIds.get(selectedShoppingUserIndex);
        if (false && userId == null) {
            Toast.makeText(this, "사용자 정보가 DB에 저장되지 않았습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        final int finalItemCount = itemCount;
        JSONObject payload = new JSONObject();
        try {
            payload.put("name", itemName);
            payload.put("quantity", finalItemCount);
            payload.put("user_id", userId == null ? JSONObject.NULL : userId);
        } catch (JSONException error) {
            Toast.makeText(this, "저장 데이터를 만들 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        requestJson("POST", "/api/shopping-list", payload, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                Integer listId = null;
                if (response.has("id")) {
                    listId = response.optInt("id");
                } else if (response.has("item_id")) {
                    listId = response.optInt("item_id");
                } else if (response.has("list_id")) {
                    listId = response.optInt("list_id");
                }
                selectedCartItems().add(itemName + " " + finalItemCount + "개");
                selectedCartItemIds().add(listId);
                Toast.makeText(MainActivity.this, "DB 서버 쇼핑 리스트에 저장했습니다.", Toast.LENGTH_SHORT).show();
                showShoppingInput();
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "DB 저장 실패: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void deleteShoppingItem(int itemIndex) {
        List<String> selectedCartItems = selectedCartItems();
        if (itemIndex < 0 || itemIndex >= selectedCartItems.size()) {
            return;
        }
        final List<Integer> selectedCartItemIds = selectedCartItemIds();
        final Integer listId = itemIndex < selectedCartItemIds.size() ? selectedCartItemIds.get(itemIndex) : null;
        if (listId == null) {
            selectedCartItems.remove(itemIndex);
            if (itemIndex < selectedCartItemIds.size()) {
                selectedCartItemIds.remove(itemIndex);
            }
            Toast.makeText(this, "화면에서 물품을 삭제했습니다.", Toast.LENGTH_SHORT).show();
            showShoppingInput();
            return;
        }

        requestJson("DELETE", "/api/shopping-list/" + listId, null, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                selectedCartItems.remove(itemIndex);
                selectedCartItemIds.remove(itemIndex);
                Toast.makeText(MainActivity.this, "DB 서버 쇼핑 리스트에서 삭제했습니다.", Toast.LENGTH_SHORT).show();
                showShoppingInput();
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "DB 삭제 실패: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void selectShoppingUser(int userIndex) {
        if (userIndex < 0 || userIndex >= shoppingUsers.size()) {
            return;
        }
        selectedShoppingUserIndex = userIndex;
        showShoppingInput();
    }

    private void addShoppingUser() {
        final String userName = "사용자 " + nextShoppingUserNumber;
        JSONObject payload = new JSONObject();
        try {
            payload.put("name", userName);
        } catch (JSONException error) {
            Toast.makeText(this, "저장 데이터를 만들 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        requestJson("POST", "/api/users", payload, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                Integer userId = extractServerId(response, "user_id", "id");
                shoppingUsers.add(userName);
                shoppingUserIds.add(userId);
                cartItemsByUser.add(new ArrayList<String>());
                cartItemIdsByUser.add(new ArrayList<Integer>());
                selectedShoppingUserIndex = shoppingUsers.size() - 1;
                nextShoppingUserNumber++;
                Toast.makeText(MainActivity.this, "DB 서버에 사용자를 저장했습니다.", Toast.LENGTH_SHORT).show();
                showShoppingInput();
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "DB 저장 실패: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void renameShoppingUser(EditText userNameInput) {
        String newUserName = userNameInput.getText().toString().trim();

        if (newUserName.length() == 0) {
            Toast.makeText(this, "사용자 이름을 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        for (int i = 0; i < shoppingUsers.size(); i++) {
            if (i != selectedShoppingUserIndex && shoppingUsers.get(i).equals(newUserName)) {
                Toast.makeText(this, "이미 사용 중인 이름입니다.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("name", newUserName);
        } catch (JSONException error) {
            Toast.makeText(this, "저장 데이터를 만들 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        requestJson("POST", "/api/users", payload, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                Integer newUserId = extractServerId(response, "user_id", "id");
                shoppingUsers.set(selectedShoppingUserIndex, newUserName);
                shoppingUserIds.set(selectedShoppingUserIndex, newUserId);
                Toast.makeText(MainActivity.this, "DB 서버에 새 사용자 이름을 저장했습니다.", Toast.LENGTH_SHORT).show();
                showShoppingInput();
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "DB 저장 실패: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private Integer extractServerId(JSONObject response, String primaryKey, String fallbackKey) {
        if (response.has(primaryKey)) {
            return response.optInt(primaryKey);
        }
        if (response.has(fallbackKey)) {
            return response.optInt(fallbackKey);
        }
        JSONObject user = response.optJSONObject("user");
        if (user != null) {
            if (user.has(primaryKey)) {
                return user.optInt(primaryKey);
            }
            if (user.has(fallbackKey)) {
                return user.optInt(fallbackKey);
            }
        }
        return null;
    }

    private void createIntegratedShoppingList(final String title) {
        if (totalCartItemCount() == 0) {
            Toast.makeText(this, "\uD1B5\uD569\uD560 \uC1FC\uD551 \uD488\uBAA9\uC774 \uC5C6\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show();
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("title", title);
            payload.put("created_by_user_id", JSONObject.NULL);
        } catch (JSONException error) {
            Toast.makeText(this, "\uD1B5\uD569 \uB9AC\uC2A4\uD2B8 \uB370\uC774\uD130\uB97C \uB9CC\uB4E4 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show();
            return;
        }

        requestJson("POST", "/api/shopping-sessions", payload, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                Integer sessionId = extractServerId(response, "session_id", "id");
                if (sessionId == null) {
                    Toast.makeText(MainActivity.this, "\uD1B5\uD569 \uB9AC\uC2A4\uD2B8 \uC138\uC158 ID\uB97C \uBC1B\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_LONG).show();
                    return;
                }
                postShoppingRequestsForSession(title, sessionId, 0);
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "\uD1B5\uD569 \uB9AC\uC2A4\uD2B8 \uC0DD\uC131 \uC2E4\uD328: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void postShoppingRequestsForSession(final String title, final int sessionId, final int userIndex) {
        if (userIndex >= cartItemsByUser.size()) {
            compareAndFinalizeShoppingSession(title, sessionId);
            return;
        }

        final List<String> userCartItems = cartItemsByUser.get(userIndex);
        if (userCartItems.isEmpty()) {
            postShoppingRequestsForSession(title, sessionId, userIndex + 1);
            return;
        }

        ensureShoppingUserId(userIndex, new IntCallback() {
            @Override public void onSuccess(int userId) {
                JSONArray items = new JSONArray();
                for (String item : userCartItems) {
                    ItemQuantity itemQuantity = parseItemQuantity(item);
                    JSONObject itemJson = new JSONObject();
                    try {
                        itemJson.put("item_name", itemQuantity.name);
                        itemJson.put("quantity", itemQuantity.quantity);
                    } catch (JSONException ignored) {
                    }
                    items.put(itemJson);
                }

                JSONObject payload = new JSONObject();
                try {
                    payload.put("user_id", userId);
                    payload.put("items", items);
                } catch (JSONException error) {
                    Toast.makeText(MainActivity.this, "\uC1FC\uD551 \uC694\uCCAD \uB370\uC774\uD130\uB97C \uB9CC\uB4E4 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show();
                    return;
                }

                requestJson("POST", "/api/shopping-sessions/" + sessionId + "/requests", payload, new ApiCallback() {
                    @Override public void onSuccess(JSONObject response) {
                        postShoppingRequestsForSession(title, sessionId, userIndex + 1);
                    }

                    @Override public void onFailure(String message) {
                        Toast.makeText(MainActivity.this, "\uC0AC\uC6A9\uC790 \uC1FC\uD551 \uC694\uCCAD \uC800\uC7A5 \uC2E4\uD328: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "\uC0AC\uC6A9\uC790 DB \uC0DD\uC131 \uC2E4\uD328: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void ensureShoppingUserId(final int userIndex, final IntCallback callback) {
        Integer userId = shoppingUserIds.get(userIndex);
        if (userId != null) {
            callback.onSuccess(userId);
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("name", shoppingUsers.get(userIndex));
        } catch (JSONException error) {
            callback.onFailure(error.getMessage());
            return;
        }

        requestJson("POST", "/api/users", payload, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                Integer newUserId = extractServerId(response, "user_id", "id");
                if (newUserId == null) {
                    callback.onFailure("\uC0AC\uC6A9\uC790 ID\uB97C \uBC1B\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.");
                    return;
                }
                shoppingUserIds.set(userIndex, newUserId);
                callback.onSuccess(newUserId);
            }

            @Override public void onFailure(String message) {
                if (message != null && message.contains("already exists")) {
                    findExistingShoppingUserId(userIndex, callback);
                } else {
                    callback.onFailure(message);
                }
            }
        });
    }

    private void findExistingShoppingUserId(final int userIndex, final IntCallback callback) {
        requestJson("GET", "/api/users", null, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                JSONArray users = response.optJSONArray("users");
                if (users == null) {
                    callback.onFailure("\uAE30\uC874 \uC0AC\uC6A9\uC790 \uBAA9\uB85D\uC744 \uCC3E\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.");
                    return;
                }
                String targetName = shoppingUsers.get(userIndex);
                for (int i = 0; i < users.length(); i++) {
                    JSONObject user = users.optJSONObject(i);
                    if (user != null && targetName.equals(user.optString("name"))) {
                        int userId = user.optInt("id");
                        shoppingUserIds.set(userIndex, userId);
                        callback.onSuccess(userId);
                        return;
                    }
                }
                callback.onFailure("\uAE30\uC874 \uC0AC\uC6A9\uC790 ID\uB97C \uCC3E\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.");
            }

            @Override public void onFailure(String message) {
                callback.onFailure(message);
            }
        });
    }

    private void compareAndFinalizeShoppingSession(final String title, final int sessionId) {
        requestJson("POST", "/api/shopping-sessions/" + sessionId + "/compare", null, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                finalizeShoppingSession(title, sessionId);
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "\uBCF4\uC720 \uBB3C\uD488 \uBE44\uAD50 \uC2E4\uD328: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void finalizeShoppingSession(final String title, final int sessionId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("title", title);
        } catch (JSONException error) {
            Toast.makeText(this, "\uCD5C\uC885 \uB9AC\uC2A4\uD2B8 \uB370\uC774\uD130\uB97C \uB9CC\uB4E4 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show();
            return;
        }

        requestJson("POST", "/api/shopping-sessions/" + sessionId + "/finalize", payload, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                Integer finalListId = extractServerId(response, "final_list_id", "id");
                if (finalListId == null) {
                    Toast.makeText(MainActivity.this, "\uCD5C\uC885 \uB9AC\uC2A4\uD2B8 ID\uB97C \uBC1B\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_LONG).show();
                    return;
                }
                List<String> items = parseFinalListItems(response);
                integratedShoppingLists.add(new IntegratedShoppingList(finalListId, title, collectRequestedShoppingItems(), items));
                selectedIntegratedListIndex = integratedShoppingLists.size() - 1;
                Toast.makeText(MainActivity.this, "\uD1B5\uD569 \uB9AC\uC2A4\uD2B8\uB97C DB \uC11C\uBC84\uC5D0 \uC800\uC7A5\uD588\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show();
                showStoreTemi();
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "\uCD5C\uC885 \uB9AC\uC2A4\uD2B8 \uC800\uC7A5 \uC2E4\uD328: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private List<String> parseFinalListItems(JSONObject response) {
        List<String> items = new ArrayList<>();
        JSONArray itemArray = response.optJSONArray("items");
        if (itemArray == null) {
            return items;
        }
        for (int i = 0; i < itemArray.length(); i++) {
            JSONObject item = itemArray.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String itemName = item.optString("item_name", item.optString("name", "\uBB3C\uD488"));
            int quantity = item.optInt("quantity", 1);
            items.add(itemName + " " + quantity + "\uAC1C");
        }
        return items;
    }

    private List<String> collectRequestedShoppingItems() {
        List<String> requestedItems = new ArrayList<>();
        for (int userIndex = 0; userIndex < shoppingUsers.size(); userIndex++) {
            List<String> userCartItems = cartItemsByUser.get(userIndex);
            for (String item : userCartItems) {
                requestedItems.add(shoppingUsers.get(userIndex) + " / " + item);
            }
        }
        return requestedItems;
    }

    private int extractDetectedQuantity(JSONObject item) {
        int quantity = item.optInt("quantity", 0);
        if (quantity <= 0) {
            quantity = item.optInt("count", 0);
        }
        if (quantity <= 0) {
            quantity = item.optInt("detected_count", 0);
        }
        if (quantity <= 0) {
            quantity = item.optInt("item_count", 0);
        }
        return quantity <= 0 ? 1 : quantity;
    }

    private List<String> selectedCartItems() {
        return cartItemsByUser.get(selectedShoppingUserIndex);
    }

    private List<Integer> selectedCartItemIds() {
        return cartItemIdsByUser.get(selectedShoppingUserIndex);
    }

    private int totalCartItemCount() {
        int count = 0;
        for (List<String> userCartItems : cartItemsByUser) {
            count += userCartItems.size();
        }
        return count;
    }

    private Map<String, Integer> buildOwnedQuantityByName() {
        Map<String, Integer> ownedQuantityByName = new HashMap<>();
        for (String dbItem : dbItems) {
            String itemPart = dbItem;
            int separatorIndex = dbItem.indexOf("/");
            if (separatorIndex >= 0) {
                itemPart = dbItem.substring(0, separatorIndex).trim();
            }
            ItemQuantity ownedItem = parseItemQuantity(itemPart);
            Integer currentQuantity = ownedQuantityByName.get(ownedItem.name);
            ownedQuantityByName.put(
                    ownedItem.name,
                    (currentQuantity == null ? 0 : currentQuantity) + ownedItem.quantity);
        }
        return ownedQuantityByName;
    }

    private boolean isAlreadyOwned(String itemName, int requestedQuantity) {
        Integer ownedQuantity = buildOwnedQuantityByName().get(itemName.trim());
        return ownedQuantity != null && ownedQuantity >= requestedQuantity;
    }

    private void showAlreadyOwnedNotice(final View.OnClickListener extraPurchaseClickListener) {
        final LinearLayout notice = row();
        notice.setGravity(Gravity.CENTER);
        notice.setBackgroundColor(Color.rgb(35, 92, 65));
        notice.setPadding(dp(24), dp(18), dp(24), dp(18));

        TextView message = new TextView(this);
        message.setText("\uC774\uBBF8 \uBCF4\uC720\uD558\uACE0 \uC788\uC2B5\uB2C8\uB2E4");
        message.setTextColor(Color.WHITE);
        message.setTextSize(22);
        message.setGravity(Gravity.CENTER);
        notice.addView(message);

        Button extraPurchase = new Button(this);
        extraPurchase.setText("\uFF0B \uCD94\uAC00 \uAD6C\uB9E4");
        extraPurchase.setTextSize(16);
        extraPurchase.setAllCaps(false);
        notice.addView(extraPurchase, params(dp(170), dp(58), 18, 0, 0, 0));
        extraPurchase.setOnClickListener(new View.OnClickListener() {
            @Override public void onClick(View v) {
                screenRoot.removeView(notice);
                extraPurchaseClickListener.onClick(v);
            }
        });

        FrameLayout.LayoutParams noticeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER);
        screenRoot.addView(notice, noticeParams);
        notice.bringToFront();
    }

    private ItemQuantity parseItemQuantity(String value) {
        String text = value == null ? "" : value.trim();
        int quantity = 1;
        String name = text;

        int unitIndex = text.lastIndexOf("개");
        if (unitIndex > 0) {
            int numberEnd = unitIndex;
            int numberStart = numberEnd - 1;
            while (numberStart >= 0 && Character.isDigit(text.charAt(numberStart))) {
                numberStart--;
            }
            if (numberStart < numberEnd - 1) {
                String numberText = text.substring(numberStart + 1, numberEnd);
                try {
                    quantity = Integer.parseInt(numberText);
                    name = text.substring(0, numberStart + 1).trim();
                } catch (NumberFormatException ignored) {
                    quantity = 1;
                }
            }
        }

        if (name.length() == 0) {
            name = text;
        }
        return new ItemQuantity(name, quantity);
    }

    private static class ItemQuantity {
        final String name;
        final int quantity;

        ItemQuantity(String name, int quantity) {
            this.name = name;
            this.quantity = quantity;
        }
    }

    private static class DetectedPhotoItem {
        final int id;
        final String name;
        final int quantity;
        final double confidence;

        DetectedPhotoItem(int id, String name, int quantity, double confidence) {
            this.id = id;
            this.name = name;
            this.quantity = quantity;
            this.confidence = confidence;
        }
    }

    private static class IntegratedShoppingList {
        final int id;
        final String title;
        final List<String> requestedItems;
        final List<String> items;

        IntegratedShoppingList(int id, String title, List<String> requestedItems, List<String> items) {
            this.id = id;
            this.title = title;
            this.requestedItems = requestedItems;
            this.items = items;
        }
    }

    private interface IntCallback {
        void onSuccess(int value);
        void onFailure(String message);
    }

    private interface ApiCallback {
        void onSuccess(JSONObject response);
        void onFailure(String message);
    }

    private interface DrawerNumberCallback {
        void onSuccess(int drawerNumber);
        void onFailure(String message);
    }

    private void requestJson(final String method, final String path, final JSONObject payload, final ApiCallback callback) {
        new Thread(new Runnable() {
            @Override public void run() {
                HttpURLConnection connection = null;
                try {
                    URL url = new URL(apiBaseUrl + path);
                    connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod(method);
                    connection.setConnectTimeout(5000);
                    connection.setReadTimeout(5000);
                    connection.setRequestProperty("Accept", "application/json");
                    Log.d("TemiApi", method + " " + url + " payload=" + (payload == null ? "{}" : payload.toString()));

                    if (payload != null) {
                        byte[] body = payload.toString().getBytes(StandardCharsets.UTF_8);
                        connection.setDoOutput(true);
                        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                        connection.setRequestProperty("Content-Length", String.valueOf(body.length));
                        OutputStream outputStream = connection.getOutputStream();
                        outputStream.write(body);
                        outputStream.flush();
                        outputStream.close();
                    }

                    int responseCode = connection.getResponseCode();
                    InputStream responseStream = responseCode >= 200 && responseCode < 300
                            ? connection.getInputStream()
                            : connection.getErrorStream();
                    String responseBody = readStream(responseStream);
                    Log.d("TemiApi", "response " + responseCode + " " + responseBody);
                    final JSONObject responseJson = parseJsonObject(responseBody);

                    if (responseCode >= 200 && responseCode < 300) {
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                callback.onSuccess(responseJson);
                            }
                        });
                    } else {
                        final String errorMessage = responseJson.optString(
                                "detail",
                                responseJson.optString("error", responseBody.length() == 0 ? "HTTP " + responseCode : responseBody));
                        runOnUiThread(new Runnable() {
                            @Override public void run() {
                                callback.onFailure(errorMessage);
                            }
                        });
                    }
                } catch (final Exception error) {
                    Log.e("TemiApi", method + " " + path + " failed", error);
                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            callback.onFailure(error.getMessage() == null ? "서버 연결 실패" : error.getMessage());
                        }
                    });
                } finally {
                    if (connection != null) {
                        connection.disconnect();
                    }
                }
            }
        }).start();
    }

    private String readStream(InputStream inputStream) throws IOException {
        if (inputStream == null) {
            return "";
        }
        BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }

    private String loadApiBaseUrlFromAssets() {
        try {
            InputStream inputStream = getAssets().open(IP_CONFIG_FILE);
            String jsonText = readStream(inputStream);
            inputStream.close();
            if (jsonText == null || jsonText.length() == 0) {
                return null;
            }
            JSONObject config = new JSONObject(jsonText);
            String apiBaseUrl = config.optString("api_base_url", null);
            if (apiBaseUrl != null && apiBaseUrl.length() > 0) {
                return apiBaseUrl;
            }
            String emulatorUrl = config.optString("emulator_base_url", null);
            if (emulatorUrl != null && emulatorUrl.length() > 0) {
                return emulatorUrl;
            }
            return config.optString("server_base_url", null);
        } catch (Exception ignored) {
            return null;
        }
    }

    private JSONObject parseJsonObject(String responseBody) throws JSONException {
        if (responseBody == null || responseBody.length() == 0) {
            return new JSONObject();
        }
        String trimmedBody = responseBody.trim();
        if (!trimmedBody.startsWith("{")) {
            JSONObject fallback = new JSONObject();
            fallback.put("detail", responseBody);
            return fallback;
        }
        return new JSONObject(trimmedBody);
    }

    private void showDb() {
        LinearLayout page = page("DB", "물건 이름과 위치를 조회하고 DB 서버에 저장합니다.");
        page.addView(backButton());

        LinearLayout status = card();
        status.addView(label(dbStatusText));
        status.addView(body("현재 호출 주소: " + apiBaseUrl));
        status.addView(body("에뮬레이터에서 내 PC 서버는 10.0.2.2:8000, 실제 기기/Temi에서는 PC 내부 IP 또는 팀원 서버 주소를 씁니다."));
        status.addView(button("DB 서버 연결 확인", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                checkDbServer();
            }
        }));
        status.addView(button("DB 서버 목록 새로고침", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                loadDbItemsFromServer();
            }
        }));
        page.addView(status);

        LinearLayout results = card();
        results.addView(label("DB 리스트"));
        for (String item : dbItems) {
            results.addView(listItem(item, "저장됨"));
        }
        page.addView(results);

        setScreen(page);
    }

    private void checkDbServer() {
        requestJson("GET", "/api/health", null, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                dbStatusText = "DB 서버: 연결됨";
                Toast.makeText(MainActivity.this, "DB 서버 연결 성공", Toast.LENGTH_SHORT).show();
                showDb();
            }

            @Override public void onFailure(String message) {
                dbStatusText = "DB 서버: 연결 실패 - " + message;
                Toast.makeText(MainActivity.this, dbStatusText, Toast.LENGTH_LONG).show();
                showDb();
            }
        });
    }

    private void loadDbItemsFromServer() {
        requestJson("GET", "/api/items/search?name=", null, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                JSONArray items = response.optJSONArray("items");
                dbItems.clear();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        JSONObject item = items.optJSONObject(i);
                        if (item == null) {
                            continue;
                        }
                        String itemName = item.optString("item_name", "이름없음");
                        int quantity = item.optInt("quantity", 1);
                        String drawerName = item.optString("drawer_name", item.optInt("drawer_id", 1) + "번 서랍");
                        dbItems.add(itemName + " " + quantity + "개 / " + drawerName);
                    }
                }
                dbStatusText = "DB 서버: 목록 " + dbItems.size() + "개 불러옴";
                Toast.makeText(MainActivity.this, "DB 서버 목록을 불러왔습니다.", Toast.LENGTH_SHORT).show();
                showDb();
            }

            @Override public void onFailure(String message) {
                dbStatusText = "DB 서버: 목록 실패 - " + message;
                Toast.makeText(MainActivity.this, dbStatusText, Toast.LENGTH_LONG).show();
                showDb();
            }
        });
    }

    private void refreshAutoDrawerStatus() {
        fetchLatestDrawerNumber(new DrawerNumberCallback() {
            @Override public void onSuccess(int drawerNumber) {
                autoDrawerStatusText = "서랍 센서: " + drawerNumber + "번 서랍 감지";
                Toast.makeText(MainActivity.this, autoDrawerStatusText, Toast.LENGTH_SHORT).show();
                showCamera();
            }

            @Override public void onFailure(String message) {
                autoDrawerStatusText = "서랍 센서: 정보 없음 - " + message;
                Toast.makeText(MainActivity.this, autoDrawerStatusText, Toast.LENGTH_LONG).show();
                showCamera();
            }
        });
    }

    private void saveDetectedItemsWithAutoDrawer() {
        fetchLatestDrawerNumber(new DrawerNumberCallback() {
            @Override public void onSuccess(int drawerNumber) {
                autoDrawerStatusText = "서랍 센서: " + drawerNumber + "번 서랍 감지";
                saveDetectedItemsToDb(drawerNumber);
            }

            @Override public void onFailure(String message) {
                autoDrawerStatusText = "서랍 센서: 정보 없음 - " + message;
                Toast.makeText(MainActivity.this, "서랍 센서 정보를 받을 수 없어 DB 저장을 중단했습니다: " + message, Toast.LENGTH_LONG).show();
                showCamera();
            }
        });
    }

    private void saveDetectedItemsToDb(final int drawerNumber) {
        if (detectedPhotoItems.isEmpty()) {
            showCamera();
            return;
        }

        final int[] remaining = new int[] { detectedPhotoItems.size() };
        final int[] successCount = new int[] { 0 };
        final int[] failureCount = new int[] { 0 };

        for (DetectedPhotoItem item : detectedPhotoItems) {
            saveDbItemValues(item.name, item.quantity, drawerNumber, new ApiCallback() {
                @Override public void onSuccess(JSONObject response) {
                    successCount[0]++;
                    finishDetectedAutoSave(remaining, successCount[0], failureCount[0]);
                }

                @Override public void onFailure(String message) {
                    failureCount[0]++;
                    finishDetectedAutoSave(remaining, successCount[0], failureCount[0]);
                }
            });
        }
    }

    private void finishDetectedAutoSave(int[] remaining, int successCount, int failureCount) {
        remaining[0]--;
        if (remaining[0] > 0) {
            return;
        }

        if (failureCount == 0) {
            dbStatusText = "DB 서버: Gemini 분석 항목 " + successCount + "개 자동 저장";
            Toast.makeText(this, "Gemini 분석 항목을 DB 서버에 자동 저장했습니다.", Toast.LENGTH_SHORT).show();
        } else {
            dbStatusText = "DB 서버: 자동 저장 " + successCount + "개 성공, " + failureCount + "개 실패";
            Toast.makeText(this, dbStatusText, Toast.LENGTH_LONG).show();
        }
        showCamera();
    }

    private void saveDbItemWithAutoDrawer(final EditText itemNameInput, final EditText quantityInput, final Runnable afterSave) {
        final String itemName = itemNameInput.getText().toString().trim();
        String quantityText = quantityInput.getText().toString().trim();

        if (itemName.length() == 0) {
            Toast.makeText(this, "물품명을 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        final int quantity = parsePositiveNumber(quantityText, 1, "개수");
        if (quantity <= 0) {
            return;
        }

        fetchLatestDrawerNumber(new DrawerNumberCallback() {
            @Override public void onSuccess(int drawerNumber) {
                autoDrawerStatusText = "서랍 센서: " + drawerNumber + "번 서랍 감지";
                saveDbItemValues(itemName, quantity, drawerNumber, new ApiCallback() {
                    @Override public void onSuccess(JSONObject response) {
                        dbStatusText = "DB 서버: 마지막 저장 성공";
                        Toast.makeText(MainActivity.this, "DB 서버에 저장했습니다.", Toast.LENGTH_SHORT).show();
                        if (afterSave == null) {
                            showStorageList();
                        } else {
                            afterSave.run();
                        }
                    }

                    @Override public void onFailure(String message) {
                        dbStatusText = "DB 서버: 저장 실패 - " + message;
                        Toast.makeText(MainActivity.this, "DB 저장 실패: " + message, Toast.LENGTH_LONG).show();
                    }
                });
            }

            @Override public void onFailure(String message) {
                autoDrawerStatusText = "서랍 센서: 정보 없음 - " + message;
                Toast.makeText(MainActivity.this, "서랍 센서 정보를 받을 수 없어 저장할 수 없습니다: " + message, Toast.LENGTH_LONG).show();
                showCamera();
            }
        });
    }

    private void fetchLatestDrawerNumber(final DrawerNumberCallback callback) {
        fetchLatestDrawerNumberFromPaths(new String[] {
                "/api/storage-events/latest",
                "/api/drawer-camera-snapshots/latest",
                "/api/placement-verifications/latest"
        }, 0, callback);
    }

    private void fetchLatestDrawerNumberFromPaths(final String[] paths, final int index, final DrawerNumberCallback callback) {
        if (index >= paths.length) {
            callback.onFailure("서버에 최신 서랍 조회 API가 없습니다.");
            return;
        }

        requestJson("GET", paths[index], null, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                int drawerNumber = extractDrawerNumber(response);
                if (drawerNumber > 0) {
                    callback.onSuccess(drawerNumber);
                } else {
                    fetchLatestDrawerNumberFromPaths(paths, index + 1, callback);
                }
            }

            @Override public void onFailure(String message) {
                fetchLatestDrawerNumberFromPaths(paths, index + 1, callback);
            }
        });
    }

    private int extractDrawerNumber(JSONObject response) {
        int drawerNumber = response.optInt("drawer_number", 0);
        if (drawerNumber > 0) {
            return drawerNumber;
        }

        String drawerName = response.optString("drawer_name", "");
        drawerNumber = parseDrawerNumberFromText(drawerName);
        if (drawerNumber > 0) {
            return drawerNumber;
        }

        String[] objectKeys = new String[] {
                "event",
                "storage_event",
                "latest_event",
                "snapshot",
                "drawer_camera_snapshot",
                "verification",
                "placement_verification",
                "data"
        };
        for (String key : objectKeys) {
            JSONObject nested = response.optJSONObject(key);
            if (nested == null) {
                continue;
            }
            drawerNumber = extractDrawerNumber(nested);
            if (drawerNumber > 0) {
                return drawerNumber;
            }
        }

        JSONArray arrays[] = new JSONArray[] {
                response.optJSONArray("events"),
                response.optJSONArray("snapshots"),
                response.optJSONArray("verifications"),
                response.optJSONArray("items"),
                response.optJSONArray("results")
        };
        for (JSONArray array : arrays) {
            if (array == null) {
                continue;
            }
            for (int i = 0; i < array.length(); i++) {
                JSONObject item = array.optJSONObject(i);
                if (item == null) {
                    continue;
                }
                drawerNumber = extractDrawerNumber(item);
                if (drawerNumber > 0) {
                    return drawerNumber;
                }
            }
        }
        return 0;
    }

    private int parseDrawerNumberFromText(String value) {
        if (value == null) {
            return 0;
        }
        String digits = "";
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            if (character >= '0' && character <= '9') {
                digits += character;
            } else if (digits.length() > 0) {
                break;
            }
        }
        if (digits.length() == 0) {
            return 0;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private void saveDbItem(EditText itemNameInput, EditText quantityInput, EditText drawerNumberInput, final Runnable afterSave) {
        String itemName = itemNameInput.getText().toString().trim();
        String quantityText = quantityInput.getText().toString().trim();
        String drawerNumberText = drawerNumberInput.getText().toString().trim();

        if (itemName.length() == 0) {
            Toast.makeText(this, "물품명을 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        int quantity = parsePositiveNumber(quantityText, 1, "개수");
        if (quantity <= 0) {
            return;
        }

        int drawerNumber = parsePositiveNumber(drawerNumberText, 1, "서랍 번호");
        if (drawerNumber <= 0) {
            return;
        }

        saveDbItemValues(itemName, quantity, drawerNumber, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                dbStatusText = "DB 서버: 마지막 저장 성공";
                Toast.makeText(MainActivity.this, "DB 서버에 저장했습니다.", Toast.LENGTH_SHORT).show();
                if (afterSave == null) {
                    showStorageList();
                } else {
                    afterSave.run();
                }
            }

            @Override public void onFailure(String message) {
                dbStatusText = "DB 서버: 저장 실패 - " + message;
                Toast.makeText(MainActivity.this, "DB 저장 실패: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void saveDbItemValues(final String itemName, final int quantity, final int drawerNumber, final ApiCallback callback) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("item_name", itemName);
            payload.put("quantity", quantity);
            payload.put("location_name", drawerNumber + "번 서랍");
            payload.put("drawer_number", drawerNumber);
            payload.put("led_channel", drawerNumber);
            payload.put("description", drawerNumber + "번 서랍에 보관된 " + itemName);
        } catch (JSONException error) {
            Toast.makeText(this, "저장 데이터를 만들 수 없습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        requestJson("POST", "/api/placements", payload, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                dbItems.add(itemName + " " + quantity + "개 / " + drawerNumber + "번 서랍");
                storageItems.add(itemName + " / " + drawerNumber + "번 서랍");
                callback.onSuccess(response);
            }

            @Override public void onFailure(String message) {
                callback.onFailure(message);
            }
        });
    }

    private int parsePositiveNumber(String value, int defaultValue, String label) {
        if (value.length() == 0) {
            return defaultValue;
        }

        try {
            int number = Integer.parseInt(value);
            if (number > 0) {
                return number;
            }
        } catch (NumberFormatException ignored) {
        }

        Toast.makeText(this, label + "는 1 이상의 숫자로 입력해주세요.", Toast.LENGTH_SHORT).show();
        return -1;
    }

    private void showSettings() {
        LinearLayout page = page("설정", "Temi 연결과 서랍 관리를 설정합니다.");
        page.addView(backButton());

        LinearLayout server = card();
        server.addView(label("DB/API 서버 주소"));
        server.addView(body("현재 주소: " + apiBaseUrl));
        final EditText serverUrlInput = input("예: http://172.17.82.227:8080");
        serverUrlInput.setText(apiBaseUrl);
        server.addView(serverUrlInput);
        LinearLayout serverActions = row();
        serverActions.addView(button("주소 저장", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveApiBaseUrl(serverUrlInput);
            }
        }), weightParams());
        serverActions.addView(button("연결 확인", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                checkDbServer();
            }
        }), weightParams());
        server.addView(serverActions);
        page.addView(server);

        page.addView(navButton("가정 Temi", "집에서 사용하는 Temi를 QR로 연결합니다.", new View.OnClickListener() {
            @Override public void onClick(View v) { showHomeTemi(); }
        }));
        page.addView(navButton("매장 Temi", "저장된 쇼핑리스트를 매장 Temi로 전송합니다.", new View.OnClickListener() {
            @Override public void onClick(View v) { showStoreTemi(); }
        }));
        page.addView(navButton("서랍 관리", "연동 서랍을 QR로 등록하고 상태를 확인합니다.", new View.OnClickListener() {
            @Override public void onClick(View v) { showDrawerManager(); }
        }));

        setScreen(page);
    }

    private void saveApiBaseUrl(EditText serverUrlInput) {
        String value = normalizeApiBaseUrl(serverUrlInput.getText().toString().trim());
        if (value.length() == 0) {
            Toast.makeText(this, "서버 주소를 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        apiBaseUrl = value;
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(PREF_API_BASE_URL, apiBaseUrl);
        editor.apply();
        dbStatusText = "DB 서버: 주소 저장됨";
        Toast.makeText(this, "DB/API 서버 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
        showSettings();
    }

    private String normalizeApiBaseUrl(String value) {
        if (value.length() == 0) {
            return "";
        }
        if (!value.startsWith("http://") && !value.startsWith("https://")) {
            value = "http://" + value;
        }
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private void showHomeTemi() {
        showQrScreen("가정용 Temi 설정", "연결된 가정용 Temi: 없음", "검색된 Temi: HOME-TEMI-01", "연결", new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        });
    }

    private void showStoreTemi() {
        LinearLayout page = page("매장 Temi", "매장 Temi 연결 후 저장된 리스트를 전송합니다.");
        page.addView(backTo("설정으로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        }));
        page.addView(qrPanel("검색된 Temi: STORE-TEMI-03"));
        LinearLayout savedLists = card();
        savedLists.addView(label("\uC2E4\uC81C DB \uC800\uC7A5 \uD1B5\uD569 \uB9AC\uC2A4\uD2B8"));
        if (integratedShoppingLists.isEmpty()) {
            savedLists.addView(body("\uC804\uC1A1\uD560 \uD1B5\uD569 \uB9AC\uC2A4\uD2B8\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4."));
        } else {
            for (int i = 0; i < integratedShoppingLists.size(); i++) {
                savedLists.addView(integratedListRow(integratedShoppingLists.get(i), i));
            }
        }
        page.addView(savedLists);
        page.addView(button("\uC120\uD0DD\uD55C \uB9AC\uC2A4\uD2B8\uB97C \uB9E4\uC7A5 Temi\uC5D0\uAC8C \uC804\uC1A1", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                transferSelectedIntegratedList();
            }
        }));

        setScreen(page);
    }

    private LinearLayout integratedListRow(final IntegratedShoppingList integratedList, final int index) {
        LinearLayout row = card();
        String selectedMark = selectedIntegratedListIndex == index ? "\u2713 " : "";
        row.addView(label(selectedMark + integratedList.title + " / ID " + integratedList.id));
        row.addView(label("\uC0AC\uC6A9\uC790 \uC694\uCCAD \uC804\uCCB4"));
        if (integratedList.requestedItems.isEmpty()) {
            row.addView(body("\uC694\uCCAD\uD55C \uD488\uBAA9\uC774 \uC5C6\uC2B5\uB2C8\uB2E4."));
        } else {
            for (String requestedItem : integratedList.requestedItems) {
                row.addView(body(requestedItem));
            }
        }
        row.addView(label("\uCD5C\uC885 \uAD6C\uB9E4 \uB9AC\uC2A4\uD2B8"));
        if (integratedList.items.isEmpty()) {
            row.addView(body("\uAD6C\uB9E4\uD560 \uD488\uBAA9\uC774 \uC5C6\uC2B5\uB2C8\uB2E4."));
        } else {
            for (String item : integratedList.items) {
                row.addView(body(item));
            }
        }
        row.addView(button("\uC774 \uB9AC\uC2A4\uD2B8 \uC120\uD0DD", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                selectedIntegratedListIndex = index;
                showStoreTemi();
            }
        }));
        return row;
    }

    private void transferSelectedIntegratedList() {
        if (selectedIntegratedListIndex < 0 || selectedIntegratedListIndex >= integratedShoppingLists.size()) {
            Toast.makeText(this, "\uC804\uC1A1\uD560 \uD1B5\uD569 \uB9AC\uC2A4\uD2B8\uB97C \uC120\uD0DD\uD574\uC8FC\uC138\uC694.", Toast.LENGTH_SHORT).show();
            return;
        }
        final IntegratedShoppingList selectedList = integratedShoppingLists.get(selectedIntegratedListIndex);
        ensureStoreTemiStore(new IntCallback() {
            @Override public void onSuccess(int storeId) {
                transferIntegratedListToStore(selectedList, storeId);
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "\uB9E4\uC7A5 Temi DB \uC900\uBE44 \uC2E4\uD328: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void ensureStoreTemiStore(final IntCallback callback) {
        if (storeTemiStoreId != null) {
            callback.onSuccess(storeTemiStoreId);
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("name", "STORE-TEMI-03");
            payload.put("store_type", "TEMI_STORE");
            payload.put("temi_device_id", "STORE-TEMI-03");
        } catch (JSONException error) {
            callback.onFailure(error.getMessage());
            return;
        }

        requestJson("POST", "/api/stores", payload, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                Integer storeId = extractServerId(response, "store_id", "id");
                if (storeId == null) {
                    callback.onFailure("\uB9E4\uC7A5 ID\uB97C \uBC1B\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.");
                    return;
                }
                storeTemiStoreId = storeId;
                callback.onSuccess(storeId);
            }

            @Override public void onFailure(String message) {
                callback.onFailure(message);
            }
        });
    }

    private void transferIntegratedListToStore(final IntegratedShoppingList integratedList, int storeId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("store_id", storeId);
        } catch (JSONException error) {
            Toast.makeText(this, "\uC804\uC1A1 \uB370\uC774\uD130\uB97C \uB9CC\uB4E4 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_SHORT).show();
            return;
        }

        requestJson("POST", "/api/final-shopping-lists/" + integratedList.id + "/transfer", payload, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                Toast.makeText(MainActivity.this, integratedList.title + " \uB9AC\uC2A4\uD2B8\uB97C \uB9E4\uC7A5 Temi\uC5D0\uAC8C \uC804\uC1A1\uD588\uC2B5\uB2C8\uB2E4.", Toast.LENGTH_LONG).show();
                showStoreTemi();
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "\uB9E4\uC7A5 Temi \uC804\uC1A1 \uC2E4\uD328: " + message, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showDrawerManager() {
        LinearLayout page = page("서랍관리", "QR로 서랍을 연결하고 연결된 서랍을 확인합니다.");
        page.addView(backTo("설정으로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        }));
        page.addView(qrPanel("검색된 서랍: DRAWER-01"));
        page.addView(button("연결", true, null));

        LinearLayout drawers = card();
        drawers.addView(label("연결된 서랍 리스트"));
        drawers.addView(listItem("서랍 1", "정상"));
        page.addView(drawers);
        page.addView(button("확인", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        }));
        setScreen(page);
    }

    private void showQrScreen(String title, String connected, String searched, String action, View.OnClickListener listener) {
        LinearLayout page = page(title, "QR을 인식해 장치를 검색하고 연결합니다.");
        page.addView(backTo("설정으로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        }));

        LinearLayout status = card();
        status.addView(label(connected));
        page.addView(status);
        page.addView(qrPanel(searched));
        page.addView(button("QR 인식", false, null));
        page.addView(button(action, true, listener));
        setScreen(page);
    }

    private LinearLayout page(String title, String subtitle) {
        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setPadding(dp(18), dp(22), dp(18), dp(28));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColorCompat(R.color.temi_text));
        titleView.setTextSize(28);
        titleView.setGravity(Gravity.START);
        titleView.setTypeface(null, 1);
        page.addView(titleView);

        TextView subtitleView = body(subtitle);
        subtitleView.setTextColor(getColorCompat(R.color.temi_muted));
        page.addView(subtitleView, params(-1, -2, 0, 6, 0, 16));
        return page;
    }

    private void setScreen(LinearLayout page) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(false);
        scrollView.addView(page);
        screenRoot.removeAllViews();
        screenRoot.addView(scrollView, new FrameLayout.LayoutParams(-1, -1));
    }

    private Button backButton() {
        return backTo("홈으로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { showHome(); }
        });
    }

    private Button backTo(String text, View.OnClickListener listener) {
        Button button = button(text, false, listener);
        button.setTextColor(getColorCompat(R.color.purple_500));
        return button;
    }

    private LinearLayout navButton(String title, String subtitle, View.OnClickListener listener) {
        LinearLayout container = card();
        TextView titleView = big(title);
        TextView subtitleView = body(subtitle);
        Button open = button("열기", true, listener);
        container.addView(titleView);
        container.addView(subtitleView);
        container.addView(open);
        return container;
    }

    private LinearLayout card() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(16), dp(14), dp(16), dp(14));
        card.setBackgroundResource(R.drawable.temi_card);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClipToOutline(true);
        card.setLayoutParams(params(-1, -2, 0, 0, 0, 12));
        return card;
    }

    private TextView qrPanel(String searchedText) {
        TextView qr = new TextView(this);
        qr.setText("QR 카메라\n\n" + searchedText);
        qr.setGravity(Gravity.CENTER);
        qr.setTextColor(Color.WHITE);
        qr.setTextSize(17);
        qr.setBackgroundResource(R.drawable.temi_camera_panel);
        qr.setLayoutParams(params(-1, dp(220), 0, 0, 0, 12));
        return qr;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColorCompat(R.color.temi_text));
        view.setTextSize(15);
        view.setTypeface(null, 1);
        return view;
    }

    private TextView big(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColorCompat(R.color.temi_text));
        view.setTextSize(20);
        view.setTypeface(null, 1);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColorCompat(R.color.temi_muted));
        view.setTextSize(14);
        view.setLineSpacing(2, 1.0f);
        return view;
    }

    private EditText input(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextSize(15);
        editText.setBackgroundResource(R.drawable.temi_input);
        editText.setPadding(dp(12), dp(8), dp(12), dp(8));
        editText.setLayoutParams(params(-1, dp(48), 0, 8, 0, 8));
        return editText;
    }

    private TextView listItem(String title, String meta) {
        TextView item = new TextView(this);
        item.setText(title + "    " + meta);
        item.setTextColor(getColorCompat(R.color.temi_text));
        item.setTextSize(15);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(12), dp(10), dp(12), dp(10));
        item.setBackgroundResource(R.drawable.temi_input);
        item.setLayoutParams(params(-1, -2, 0, 8, 0, 0));
        return item;
    }

    private LinearLayout shoppingCartItem(String title, final int itemIndex) {
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackgroundResource(R.drawable.temi_input);
        item.setPadding(dp(12), dp(6), dp(8), dp(6));
        item.setLayoutParams(params(-1, -2, 0, 8, 0, 0));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColorCompat(R.color.temi_text));
        titleView.setTextSize(15);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        item.addView(titleView, new LinearLayout.LayoutParams(0, dp(48), 1));

        Button deleteButton = button("삭제", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                deleteShoppingItem(itemIndex);
            }
        });
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(88), dp(44));
        deleteParams.setMargins(dp(8), 0, 0, 0);
        item.addView(deleteButton, deleteParams);
        return item;
    }

    private LinearLayout shoppingUserRow() {
        LinearLayout row = row();
        for (int i = 0; i < shoppingUsers.size(); i++) {
            final int userIndex = i;
            row.addView(chip(shoppingUsers.get(i), i == selectedShoppingUserIndex, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    selectShoppingUser(userIndex);
                }
            }), weightParams());
        }
        row.addView(chip("+ 사용자", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                addShoppingUser();
            }
        }), weightParams());
        return row;
    }

    private TextView chip(String text, boolean selected, View.OnClickListener listener) {
        TextView chip = new TextView(this);
        chip.setText(text);
        chip.setTextColor(selected ? Color.WHITE : getColorCompat(R.color.temi_text));
        chip.setGravity(Gravity.CENTER);
        chip.setTextSize(14);
        chip.setBackgroundResource(selected ? R.drawable.temi_primary_button : R.drawable.temi_input);
        chip.setPadding(dp(8), dp(10), dp(8), dp(10));
        chip.setOnClickListener(listener);
        return chip;
    }

    private Button button(String text, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(15);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(primary ? Color.WHITE : getColorCompat(R.color.temi_text));
        button.setBackgroundResource(primary ? R.drawable.temi_primary_button : R.drawable.temi_outline_button);
        button.setOnClickListener(listener);
        button.setMinHeight(dp(48));
        button.setLayoutParams(params(-1, dp(50), 0, 8, 0, 8));
        return button;
    }

    private Button dangerButton(String text, View.OnClickListener listener) {
        Button button = button(text, false, listener);
        button.setTextColor(Color.WHITE);
        button.setBackgroundResource(R.drawable.temi_danger_button);
        return button;
    }

    private LinearLayout row() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        row.setLayoutParams(params(-1, -2, 0, 0, 0, 8));
        return row;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(50), 1);
        params.setMargins(dp(4), dp(4), dp(4), dp(4));
        return params;
    }

    private LinearLayout.LayoutParams params(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private int getColorCompat(int colorRes) {
        return getResources().getColor(colorRes);
    }
}
