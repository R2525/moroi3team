package org.techtown.hello;

import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final String PAGE_HOME = "HOME";
    private static final String PAGE_FIND = "FIND";
    private static final String PAGE_RESULT = "RESULT";
    private static final String PAGE_REGISTER = "REGISTER";
    private static final String PAGE_DB_STATUS = "DB_STATUS";
    private static final String PAGE_UPLOAD = "UPLOAD";
    private static final String PAGE_CONFIRM = "CONFIRM";
    private static final String PAGE_PLACEMENT = "PLACEMENT";
    private static final String PAGE_COMPLETE = "COMPLETE";
    private static final String PAGE_SETTINGS = "SETTINGS";

    private static final String PREFS_NAME = "temi_settings";
    private static final String PREF_API_KEY = "gemini_api_key";

    private FrameLayout pageRoot;
    private TemiDbHelper localDb;
    private TemiLocalServer localServer;
    private String localServerUrl = "";
    private String currentPage = PAGE_HOME;
    private EditText searchInput;
    private EditText registerNameInput;
    private EditText registerDrawerInput;
    private EditText registerQuantityInput;

    private final List<PendingItem> confirmItems = new ArrayList<>();
    private final Handler placementHandler = new Handler();
    private Runnable placementPoller;
    private Runnable homeBatchWatcher;
    private int placementShownIndex = -1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyTemiImmersiveMode();
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        pageRoot = new FrameLayout(this);
        pageRoot.setBackgroundColor(Color.parseColor("#F5F7FA"));
        setContentView(pageRoot);

        localDb = new TemiDbHelper(this);
        localServer = new TemiLocalServer(this, localDb);
        localServer.start();
        localServerUrl = localServer.getBaseUrl();

        String launchKeyword = getIntent().getStringExtra("search_keyword");
        if (launchKeyword != null && !launchKeyword.trim().isEmpty()) {
            searchItem(launchKeyword.trim());
        } else {
            showHome();
        }
    }

    @Override
    protected void onDestroy() {
        stopPlacementPolling();
        stopHomeBatchWatch();
        if (localServer != null) {
            localServer.stop();
        }
        if (localDb != null) {
            localDb.close();
        }
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyTemiImmersiveMode();
        }
    }

    @Override
    public void onBackPressed() {
        if (PAGE_HOME.equals(currentPage)) {
            super.onBackPressed();
        } else if (PAGE_PLACEMENT.equals(currentPage)) {
            stopPlacementPolling();
            showHome();
        } else if (PAGE_RESULT.equals(currentPage)) {
            showFindPage();
        } else {
            showHome();
        }
    }

    private void showHome() {
        currentPage = PAGE_HOME;

        LinearLayout page = basePage(K.HOME_TITLE, false);
        LinearLayout menuRow = horizontal(page);
        addMenuCard(menuRow, K.FIND_CARD_TITLE, v -> showFindPage());
        addMenuCard(menuRow, K.REGISTER_CARD_TITLE, v -> showRegisterPage());
        addMenuCard(menuRow, K.UPLOAD_CARD_TITLE, v -> showUploadPage());
        addMenuCard(menuRow, K.DB_STATUS_CARD_TITLE, v -> checkDbStatus());
        addMenuCard(menuRow, "설정", v -> showSettings());
        setPage(page);
        startHomeBatchWatch();
    }

    private void showFindPage() {
        currentPage = PAGE_FIND;

        LinearLayout page = basePage(K.FIND_TITLE, true);

        searchInput = input(K.ITEM_NAME_INPUT);
        page.addView(searchInput, matchHeight(72, 6, 18));

        LinearLayout row = horizontal(page);
        addButton(row, K.VOICE_BUTTON, false, v -> toast(K.VOICE_TODO));
        addButton(row, K.SEARCH_BUTTON, true, v -> {
            String keyword = searchInput.getText().toString().trim();
            if (keyword.isEmpty()) {
                toast(K.EMPTY_KEYWORD);
                return;
            }
            searchItem(keyword);
        });

        setPage(page);
    }

    private void showRegisterPage() {
        currentPage = PAGE_REGISTER;

        LinearLayout page = basePage(K.REGISTER_TITLE, true);
        LinearLayout form = card(page);

        registerNameInput = input(K.REGISTER_NAME_HINT);
        registerDrawerInput = input(K.REGISTER_DRAWER_HINT);
        registerQuantityInput = input(K.REGISTER_QUANTITY_HINT);

        form.addView(registerNameInput, matchHeight(76, 0, 12));

        LinearLayout secondRow = compactRow(form);
        addCompactInput(secondRow, registerDrawerInput);
        addCompactInput(secondRow, registerQuantityInput);

        addButton(page, K.REGISTER_BUTTON, true, v -> registerItem());
        setPage(page);
    }

    private void registerItem() {
        String name = registerNameInput.getText().toString().trim();
        String drawerText = registerDrawerInput.getText().toString().trim();
        String quantityText = registerQuantityInput.getText().toString().trim();

        if (name.isEmpty() || drawerText.isEmpty() || quantityText.isEmpty()) {
            toast(K.REGISTER_EMPTY);
            return;
        }

        int drawerNumber;
        int quantity;
        try {
            drawerNumber = Integer.parseInt(drawerText);
            quantity = Integer.parseInt(quantityText);
        } catch (NumberFormatException e) {
            toast(K.REGISTER_NUMBER_ERROR);
            return;
        }
        showSimpleLoading(K.REGISTERING_TITLE, K.REGISTERING_MESSAGE);
        new Thread(() -> {
            try {
                JSONObject session = localDb.startStorageSession(name, drawerNumber, quantity);
                runOnUiThread(() -> showStorageWaiting(session));
            } catch (Exception e) {
                runOnUiThread(() -> showSimpleError(K.REGISTER_FAIL_TITLE, e.getMessage()));
            }
        }).start();
    }

    private void showStorageWaiting(JSONObject session) {
        currentPage = PAGE_RESULT;
        LinearLayout page = basePage(K.STORAGE_WAIT_TITLE, true);
        LinearLayout resultCard = card(page);
        String name = session.optString("item_name");
        int drawerNumber = session.optInt("target_drawer_number");
        int quantity = session.optInt("quantity", 1);
        TextView headline = text(K.STORAGE_WAIT_MESSAGE, 32, "#17202A", true);
        resultCard.addView(headline);
        addInfoRow(resultCard, K.ITEM_LABEL, name);
        addInfoRow(resultCard, K.DRAWER_LABEL, drawerNumber + K.DRAWER_SUFFIX);
        addInfoRow(resultCard, K.QUANTITY_LABEL, quantity + K.QUANTITY_SUFFIX);
        addInfoRow(resultCard, K.STATUS_LABEL, session.optString("message", K.SENSOR_WAITING));
        addInfoRow(resultCard, K.ARDUINO_LABEL, localServerUrl + "/api/sensor-events");

        LinearLayout row = horizontal(page);
        addButton(row, K.REFRESH_BUTTON, false, v -> refreshStorageSession());
        addButton(row, K.MANUAL_SAVE_BUTTON, true, v -> saveSessionManually(name, drawerNumber, quantity));
        setPage(page);
    }

    private void refreshStorageSession() {
        try {
            JSONObject session = localDb.getActiveStorageSession();
            if (session == null) {
                showSimpleError(K.STORAGE_WAIT_TITLE, K.STORAGE_DONE_OR_NONE);
            } else {
                showStorageWaiting(session);
            }
        } catch (Exception e) {
            showSimpleError(K.STORAGE_WAIT_TITLE, e.getMessage());
        }
    }

    private void saveSessionManually(String name, int drawerNumber, int quantity) {
        try {
            postPlacement(name, drawerNumber, quantity);
            showRegisterSuccess(name, drawerNumber, quantity);
        } catch (Exception e) {
            showSimpleError(K.REGISTER_FAIL_TITLE, e.getMessage());
        }
    }

    private void showRegisterSuccess(String name, int drawerNumber, int quantity) {
        currentPage = PAGE_RESULT;

        LinearLayout page = basePage(K.REGISTER_DONE_TITLE, true);
        LinearLayout resultCard = card(page);
        TextView headline = text(K.REGISTER_DONE_MESSAGE, 34, "#17202A", true);
        resultCard.addView(headline);
        addInfoRow(resultCard, K.DRAWER_LABEL, drawerNumber + K.DRAWER_SUFFIX);
        addInfoRow(resultCard, K.QUANTITY_LABEL, quantity + K.QUANTITY_SUFFIX);

        LinearLayout row = horizontal(page);
        addButton(row, K.SEARCH_NOW_BUTTON, false, v -> searchItem(name));
        addButton(row, K.HOME_BUTTON, true, v -> showHome());
        setPage(page);
    }

    private void checkDbStatus() {
        currentPage = PAGE_DB_STATUS;
        showSimpleLoading(K.DB_STATUS_TITLE, K.DB_CHECKING_MESSAGE);
        new Thread(() -> {
            try {
                String baseUrl = fetchHealth();
                runOnUiThread(() -> showDbStatus(true, baseUrl, null));
            } catch (Exception e) {
                runOnUiThread(() -> showDbStatus(false, "", e.getMessage()));
            }
        }).start();
    }

    private void searchItem(String keyword) {
        showLoading(keyword);
        new Thread(() -> {
            try {
                ItemInfo item = fetchItem(keyword);
                runOnUiThread(() -> showResult(keyword, item, null));
            } catch (Exception e) {
                runOnUiThread(() -> showResult(keyword, null, e.getMessage()));
            }
        }).start();
    }

    private ItemInfo fetchItem(String keyword) throws Exception {
        return parseItem(keyword, localDb.findItem(keyword).toString());
    }

    private ItemInfo parseItem(String keyword, String body) throws Exception {
        JSONObject json = new JSONObject(body);
        if (!json.optBoolean("found", false)) {
            return null;
        }

        return new ItemInfo(
                json.optString("name", keyword),
                json.optString("location", ""),
                json.optInt("drawer_number", 0),
                json.optInt("quantity", 0)
        );
    }

    private void postPlacement(String name, int drawerNumber, int quantity) throws Exception {
        localDb.savePlacement(name, drawerNumber, quantity, "manual");
    }

    private String fetchHealth() throws Exception {
        localDb.health();
        localServerUrl = localServer.getBaseUrl();
        return localServerUrl;
    }

    private void showLoading(String keyword) {
        currentPage = PAGE_RESULT;
        LinearLayout page = basePage(K.LOADING_TITLE, true);

        LinearLayout resultCard = card(page);
        ProgressBar progressBar = new ProgressBar(this);
        resultCard.setGravity(Gravity.CENTER_HORIZONTAL);
        resultCard.addView(progressBar, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView loading = text(K.LOADING_MESSAGE, 22, "#17202A", true);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(18), 0, 0);
        resultCard.addView(loading);
        setPage(page);
    }

    private void showResult(String keyword, ItemInfo item, String errorMessage) {
        currentPage = PAGE_RESULT;

        LinearLayout page = basePage(K.RESULT_TITLE, true);
        LinearLayout resultCard = card(page);

        if (errorMessage != null) {
            TextView headline = text(K.API_UNAVAILABLE, 30, "#17202A", true);
            resultCard.addView(headline);
            addInfoRow(resultCard, K.STATUS_LABEL, K.API_CHECK_GUIDE);
            addInfoRow(resultCard, K.ADDRESS_LABEL, localServerUrl);
        } else if (item != null) {
            TextView headline = text(item.name + K.FOUND_SUFFIX, 30, "#17202A", true);
            resultCard.addView(headline);
            addInfoRow(resultCard, K.LOCATION_LABEL, item.location);
            addInfoRow(resultCard, K.DRAWER_LABEL, item.drawerNumber + K.DRAWER_SUFFIX);
            addInfoRow(resultCard, K.QUANTITY_LABEL, item.quantity + K.QUANTITY_SUFFIX);
        } else {
            TextView headline = text(K.NOT_FOUND_TITLE, 30, "#17202A", true);
            resultCard.addView(headline);
            addInfoRow(resultCard, K.STATUS_LABEL, K.NOT_FOUND_STATUS);
            addInfoRow(resultCard, K.GUIDE_LABEL, K.NOT_FOUND_GUIDE);
        }

        LinearLayout row = horizontal(page);
        if (item != null) {
            addButton(row, K.DELETE_BUTTON, false, v -> deleteItem(item.name));
        } else {
            addButton(row, K.EXIT_BUTTON, false, v -> showHome());
        }
        addButton(row, K.SEARCH_AGAIN_BUTTON, true, v -> showFindPage());

        setPage(page);
    }

    private void deleteItem(String name) {
        if (localDb.deleteItemByName(name)) {
            toast(K.DELETE_DONE);
            showHome();
        } else {
            toast(K.DELETE_FAIL);
        }
    }

    private void showUploadPage() {
        currentPage = PAGE_UPLOAD;
        localServerUrl = localServer.getBaseUrl();
        String uploadUrl = localServerUrl + "/upload";

        LinearLayout page = basePage(K.UPLOAD_TITLE, true);
        LinearLayout resultCard = card(page);
        TextView headline = text(K.UPLOAD_GUIDE, 32, "#17202A", true);
        resultCard.addView(headline);
        addInfoRow(resultCard, K.ADDRESS_LABEL, uploadUrl);

        ImageView qrView = new ImageView(this);
        qrView.setBackgroundColor(Color.WHITE);
        qrView.setPadding(dp(16), dp(16), dp(16), dp(16));
        try {
            qrView.setImageBitmap(createQrBitmap(uploadUrl, dp(360)));
        } catch (Exception e) {
            TextView fallback = text(uploadUrl, 26, "#17202A", true);
            fallback.setGravity(Gravity.CENTER);
            resultCard.addView(fallback, matchHeight(130, 18, 0));
        }
        resultCard.addView(qrView, new LinearLayout.LayoutParams(dp(390), dp(390)));

        addButton(page, "이 사진으로 수납 시작", true, v -> startConfirmFromLatest());
        LinearLayout row = horizontal(page);
        addButton(row, K.LATEST_UPLOAD_BUTTON, false, v -> showLatestUpload());
        addButton(row, K.HOME_BUTTON, false, v -> showHome());
        setPage(page);
    }

    // --- 수납 워크플로우: 확인 단계 → 넣기 단계 → 완료 ---

    private void startConfirmFromLatest() {
        confirmItems.clear();
        try {
            JSONObject latest = localDb.latestPhotoUpload();
            if (latest.optBoolean("found", false)) {
                JSONObject result = latest.optJSONObject("result");
                JSONArray summary = result == null ? null : result.optJSONArray("summary");
                if (summary != null) {
                    for (int i = 0; i < summary.length(); i++) {
                        JSONObject it = summary.optJSONObject(i);
                        if (it == null) {
                            continue;
                        }
                        String name = it.optString("item_name", it.optString("name", "")).trim();
                        if (name.length() == 0) {
                            continue;
                        }
                        confirmItems.add(new PendingItem(name, Math.max(1, it.optInt("quantity", 1)),
                                it.optInt("drawer_number", 0), it.optInt("order", confirmItems.size() + 1)));
                    }
                    Collections.sort(confirmItems, (a, b) -> Integer.compare(a.order, b.order));
                }
            }
        } catch (Exception ignored) {
        }
        showConfirmStep();
    }

    private void showConfirmStep() {
        currentPage = PAGE_CONFIRM;
        LinearLayout page = basePage("수납 확인", true);

        if (confirmItems.isEmpty()) {
            LinearLayout empty = card(page);
            empty.addView(text("인식된 물품이 없습니다. 아래에서 직접 추가하세요.", 26, "#657184", false));
        }
        for (int i = 0; i < confirmItems.size(); i++) {
            final int index = i;
            PendingItem it = confirmItems.get(i);
            LinearLayout rowCard = card(page);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            TextView info = text((index + 1) + ". " + it.name + "  ·  " + it.drawer + "번 서랍  ·  " + it.quantity + "개", 28, "#17202A", true);
            row.addView(info, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button del = new Button(this);
            del.setText("삭제");
            del.setAllCaps(false);
            del.setTextSize(22);
            del.setTextColor(Color.parseColor("#B42318"));
            del.setBackground(bg("#FFFFFF", "#E5B7B0", 8));
            del.setOnClickListener(v -> {
                confirmItems.remove(index);
                showConfirmStep();
            });
            row.addView(del, new LinearLayout.LayoutParams(dp(120), dp(64)));
            rowCard.addView(row);
        }

        LinearLayout form = card(page);
        form.addView(text("물품 추가", 24, "#657184", true));
        EditText nameInput = input("물품명");
        EditText drawerInput = input("서랍 번호");
        EditText quantityInput = input("수량");
        drawerInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        quantityInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        form.addView(nameInput, matchHeight(72, 12, 0));
        LinearLayout addRow = compactRow(form);
        addCompactInput(addRow, drawerInput);
        addCompactInput(addRow, quantityInput);
        addButton(form, "추가", false, v -> {
            String name = nameInput.getText().toString().trim();
            if (name.length() == 0) {
                toast("물품명을 입력하세요.");
                return;
            }
            int drawer = parseIntOrDefault(drawerInput.getText().toString(), 0);
            int quantity = Math.max(1, parseIntOrDefault(quantityInput.getText().toString(), 1));
            confirmItems.add(new PendingItem(name, quantity, drawer, confirmItems.size() + 1));
            showConfirmStep();
        });

        addButton(page, "수납 시작", true, v -> startPlacement());
        setPage(page);
    }

    private void startPlacement() {
        if (confirmItems.isEmpty()) {
            toast("수납할 물품이 없습니다.");
            return;
        }
        try {
            JSONArray items = new JSONArray();
            for (PendingItem it : confirmItems) {
                items.put(new JSONObject()
                        .put("item_name", it.name)
                        .put("quantity", it.quantity)
                        .put("drawer_number", it.drawer));
            }
            localDb.createPlacementBatch(items);
            showPlacementStep();
        } catch (Exception e) {
            showSimpleError("수납 시작 실패", e.getMessage());
        }
    }

    private void showPlacementStep() {
        currentPage = PAGE_PLACEMENT;
        try {
            JSONObject batch = localDb.getActivePlacementBatch();
            if (batch == null) {
                showPlacementComplete();
                return;
            }
            renderPlacement(batch);
            startPlacementPolling();
        } catch (Exception e) {
            showSimpleError("수납 진행 오류", e.getMessage());
        }
    }

    private void renderPlacement(JSONObject batch) {
        currentPage = PAGE_PLACEMENT;
        placementShownIndex = batch.optInt("current_index", -1);
        LinearLayout page = basePage("물품 넣기", true);

        JSONArray items = batch.optJSONArray("items");
        int total = batch.optInt("total", items == null ? 0 : items.length());
        int idx = batch.optInt("current_index", 0);
        JSONObject current = batch.optJSONObject("current");

        LinearLayout cur = card(page);
        cur.addView(text("지금 넣을 물품", 24, "#657184", true));
        if (current != null) {
            cur.addView(text(current.optInt("drawer_number") + "번 서랍에 " + current.optString("item_name") + " 넣기", 36, "#17202A", true));
            addInfoRow(cur, "수량", current.optInt("quantity", 1) + "개");
        } else {
            cur.addView(text("대기 중", 30, "#17202A", true));
        }
        addInfoRow(cur, "진행률", Math.min(idx, total) + " / " + total);
        if ("fail".equals(batch.optString("last_result"))) {
            cur.addView(text("검증 실패 - 다시 넣어주세요.", 24, "#B42318", true));
        } else {
            cur.addView(text("손 동작(잡음 → 펼침) + 무게 센서 확인을 기다리는 중...", 22, "#657184", false));
        }

        LinearLayout list = card(page);
        list.addView(text("수납 순서", 24, "#657184", true));
        if (items != null) {
            for (int i = 0; i < items.length(); i++) {
                JSONObject it = items.optJSONObject(i);
                if (it == null) {
                    continue;
                }
                String st = it.optString("status");
                String badge = "placed".equals(st) ? "완료" : "skipped".equals(st) ? "건너뜀" : (i == idx ? "진행 중" : "대기");
                addInfoRow(list, it.optInt("seq") + ". " + it.optString("item_name") + " (" + it.optInt("drawer_number") + "번)", badge);
            }
        }

        LinearLayout row = horizontal(page);
        addButton(row, "건너뛰기", false, v -> manualAdvance("skip"));
        addButton(row, "완료 처리", true, v -> manualAdvance("success"));
        addButton(page, "수납 중단", false, v -> {
            try {
                localDb.cancelActivePlacementBatch();
            } catch (Exception ignored) {
            }
            stopPlacementPolling();
            showHome();
        });
        setPage(page);
    }

    private void manualAdvance(String result) {
        try {
            JSONObject r = localDb.advancePlacement(result);
            if (r.optBoolean("completed", false)) {
                showPlacementComplete();
            } else {
                renderPlacement(r);
            }
        } catch (Exception e) {
            toast(e.getMessage());
        }
    }

    private void startPlacementPolling() {
        stopPlacementPolling();
        placementPoller = new Runnable() {
            @Override
            public void run() {
                if (!PAGE_PLACEMENT.equals(currentPage)) {
                    return;
                }
                try {
                    JSONObject batch = localDb.getActivePlacementBatch();
                    if (batch == null) {
                        showPlacementComplete();
                        return;
                    }
                    if (batch.optInt("current_index", -1) != placementShownIndex) {
                        renderPlacement(batch);
                    }
                } catch (Exception ignored) {
                }
                placementHandler.postDelayed(this, 1500);
            }
        };
        placementHandler.postDelayed(placementPoller, 1500);
    }

    private void stopPlacementPolling() {
        if (placementPoller != null) {
            placementHandler.removeCallbacks(placementPoller);
            placementPoller = null;
        }
    }

    // 홈에서 대기 중, 휴대폰이 보낸 수납 리스트(활성 배치)가 생기면 자동으로 넣기 화면으로 진입
    private void startHomeBatchWatch() {
        stopHomeBatchWatch();
        homeBatchWatcher = new Runnable() {
            @Override
            public void run() {
                if (!PAGE_HOME.equals(currentPage)) {
                    return;
                }
                try {
                    JSONObject batch = localDb.getActivePlacementBatch();
                    if (batch != null) {
                        stopHomeBatchWatch();
                        showPlacementStep();
                        return;
                    }
                } catch (Exception ignored) {
                }
                placementHandler.postDelayed(this, 2000);
            }
        };
        placementHandler.postDelayed(homeBatchWatcher, 2000);
    }

    private void stopHomeBatchWatch() {
        if (homeBatchWatcher != null) {
            placementHandler.removeCallbacks(homeBatchWatcher);
            homeBatchWatcher = null;
        }
    }

    private void showPlacementComplete() {
        stopPlacementPolling();
        currentPage = PAGE_COMPLETE;
        LinearLayout page = basePage("수납 완료", false);
        LinearLayout resultCard = card(page);
        resultCard.addView(text("모든 물품을 DB에 저장했습니다.", 32, "#17202A", true));
        addButton(page, "홈으로", true, v -> showHome());
        setPage(page);
    }

    private int parseIntOrDefault(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (Exception e) {
            return fallback;
        }
    }

    // --- 설정: Temi에서 직접 Gemini API 키 입력 ---

    private void showSettings() {
        currentPage = PAGE_SETTINGS;
        LinearLayout page = basePage("설정", true);

        LinearLayout settingsCard = card(page);
        settingsCard.addView(text("Gemini API 키", 26, "#657184", true));

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String currentKey = prefs.getString(PREF_API_KEY, "").trim();
        settingsCard.addView(text(
                currentKey.length() == 0 ? "현재 상태: 설정 안 됨 (사진 자동 분석 비활성)" : "현재 상태: 설정됨 (" + maskKey(currentKey) + ")",
                24, currentKey.length() == 0 ? "#B42318" : "#0F7A3B", true));

        EditText keyInput = input("AIza... 형식의 키 입력");
        keyInput.setText(currentKey);
        settingsCard.addView(keyInput, matchHeight(76, 18, 6));
        settingsCard.addView(text("키를 저장하면 사진 업로드 시 물품/서랍/순서가 자동 인식됩니다.", 22, "#657184", false));

        LinearLayout row = horizontal(page);
        addButton(row, "키 지우기", false, v -> {
            prefs.edit().remove(PREF_API_KEY).apply();
            toast("Gemini 키를 삭제했습니다.");
            showSettings();
        });
        addButton(row, "저장", true, v -> {
            String key = keyInput.getText().toString().trim();
            prefs.edit().putString(PREF_API_KEY, key).apply();
            toast(key.length() == 0 ? "키를 비웠습니다." : "Gemini 키를 저장했습니다.");
            showSettings();
        });
        addButton(page, K.HOME_BUTTON, false, v -> showHome());
        setPage(page);
    }

    private String maskKey(String key) {
        if (key.length() <= 6) {
            return "******";
        }
        return key.substring(0, 4) + "****" + key.substring(key.length() - 2);
    }

    private void showLatestUpload() {
        try {
            JSONObject latest = localDb.latestPhotoUpload();
            LinearLayout page = basePage(K.LATEST_UPLOAD_TITLE, true);
            LinearLayout resultCard = card(page);
            if (!latest.optBoolean("found", false)) {
                resultCard.addView(text(K.NO_UPLOAD, 32, "#17202A", true));
            } else {
                resultCard.addView(text(K.UPLOAD_FOUND, 32, "#17202A", true));
                addInfoRow(resultCard, K.STATUS_LABEL, latest.optString("status"));
                addInfoRow(resultCard, K.ADDRESS_LABEL, latest.optString("file_path"));
                JSONObject result = latest.optJSONObject("result");
                if (result != null) {
                    addInfoRow(resultCard, K.GUIDE_LABEL, result.optString("warning", result.toString()));
                }
            }
            LinearLayout row = horizontal(page);
            addButton(row, K.UPLOAD_TITLE, false, v -> showUploadPage());
            addButton(row, K.HOME_BUTTON, true, v -> showHome());
            setPage(page);
        } catch (Exception e) {
            showSimpleError(K.LATEST_UPLOAD_TITLE, e.getMessage());
        }
    }

    private Bitmap createQrBitmap(String text, int size) throws Exception {
        BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
        Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
            }
        }
        return bitmap;
    }

    private void showSimpleLoading(String title, String message) {
        LinearLayout page = basePage(title, true);
        LinearLayout resultCard = card(page);
        resultCard.setGravity(Gravity.CENTER_HORIZONTAL);

        ProgressBar progressBar = new ProgressBar(this);
        resultCard.addView(progressBar, new LinearLayout.LayoutParams(dp(72), dp(72)));

        TextView loading = text(message, 26, "#17202A", true);
        loading.setGravity(Gravity.CENTER);
        loading.setPadding(0, dp(18), 0, 0);
        resultCard.addView(loading);
        setPage(page);
    }

    private void showSimpleError(String title, String message) {
        currentPage = PAGE_RESULT;
        LinearLayout page = basePage(title, true);
        LinearLayout resultCard = card(page);
        TextView headline = text(K.API_UNAVAILABLE, 32, "#17202A", true);
        resultCard.addView(headline);
        addInfoRow(resultCard, K.STATUS_LABEL, message == null ? K.UNKNOWN_ERROR : message);

        LinearLayout row = horizontal(page);
        addButton(row, K.HOME_BUTTON, true, v -> showHome());
        setPage(page);
    }

    private void showDbStatus(boolean connected, String baseUrl, String errorMessage) {
        currentPage = PAGE_DB_STATUS;

        LinearLayout page = basePage(K.DB_STATUS_TITLE, true);
        LinearLayout resultCard = card(page);
        if (connected) {
            TextView headline = text(K.DB_CONNECTED, 34, "#17202A", true);
            resultCard.addView(headline);
            addInfoRow(resultCard, K.STATUS_LABEL, K.DB_CONNECTED_STATUS);
            addInfoRow(resultCard, K.ADDRESS_LABEL, baseUrl);
            addLedStatus(resultCard, K.DB_READY_BADGE);
        } else {
            TextView headline = text(K.DB_DISCONNECTED, 34, "#17202A", true);
            resultCard.addView(headline);
            addInfoRow(resultCard, K.STATUS_LABEL, errorMessage == null ? K.UNKNOWN_ERROR : errorMessage);
            addInfoRow(resultCard, K.GUIDE_LABEL, K.API_CHECK_GUIDE);
        }

        LinearLayout row = horizontal(page);
        addButton(row, K.RECHECK_BUTTON, false, v -> checkDbStatus());
        addButton(row, K.HOME_BUTTON, true, v -> showHome());
        setPage(page);
    }

    private LinearLayout basePage(String title, boolean showBack) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(56), dp(38), dp(56), dp(34));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(header, matchWrap(0, 24));

        if (showBack) {
            Button back = new Button(this);
            back.setText("<");
            back.setTextSize(30);
            back.setTextColor(Color.parseColor("#17202A"));
            back.setBackground(bg("#FFFFFF", "#D7DEE8", 8));
            back.setOnClickListener(v -> onBackPressed());
            header.addView(back, new LinearLayout.LayoutParams(dp(58), dp(58)));
        }

        LinearLayout titleBox = new LinearLayout(this);
        titleBox.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        if (showBack) {
            titleParams.setMargins(dp(16), 0, 0, 0);
        }
        header.addView(titleBox, titleParams);

        TextView titleView = text(title, 42, "#17202A", true);
        titleBox.addView(titleView);

        return content;
    }

    private void setPage(LinearLayout content) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(content);
        pageRoot.removeAllViews();
        pageRoot.addView(scrollView);
    }

    private LinearLayout horizontal(LinearLayout parent) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(layout, matchWrap(0, 22));
        return layout;
    }

    private LinearLayout compactRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(row, matchHeight(82, 0, 12));
        return row;
    }

    private void addCompactInput(LinearLayout row, EditText input) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(dp(8), 0, dp(8), 0);
        row.addView(input, params);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(44), dp(36), dp(44), dp(36));
        card.setBackground(bg("#FFFFFF", "#D7DEE8", 10));
        parent.addView(card, matchWrap(0, 24));
        return card;
    }

    private void addMenuCard(LinearLayout parent, String title, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(30), dp(26), dp(30), dp(26));
        card.setBackground(bg("#FFFFFF", "#D7DEE8", 10));
        card.setOnClickListener(listener);

        TextView titleView = text(title, 34, "#17202A", true);
        titleView.setGravity(Gravity.CENTER);
        card.addView(titleView);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(390), 1);
        params.setMargins(dp(10), 0, dp(10), 0);
        parent.addView(card, params);
    }

    private void addHomeCard(LinearLayout parent, String title, String description, View.OnClickListener listener) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(40), dp(28), dp(40), dp(28));
        card.setBackground(bg("#FFFFFF", "#D7DEE8", 10));
        card.setOnClickListener(listener);

        TextView titleView = text(title, 42, "#17202A", true);
        titleView.setGravity(Gravity.CENTER);
        card.addView(titleView);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(360)
        );
        params.setMargins(dp(8), dp(8), dp(8), dp(18));
        parent.addView(card, params);
    }

    private void addButton(LinearLayout parent, String label, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(26);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : Color.parseColor("#17202A"));
        button.setBackground(primary ? bg("#1E40AF", "#1E40AF", 8) : bg("#FFFFFF", "#D7DEE8", 8));
        button.setOnClickListener(listener);

        LinearLayout.LayoutParams params;
        if (parent.getOrientation() == LinearLayout.HORIZONTAL) {
            params = new LinearLayout.LayoutParams(0, dp(92), 1);
            params.setMargins(dp(10), 0, dp(10), 0);
        } else {
            params = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(92));
            params.setMargins(0, 0, 0, dp(16));
        }
        parent.addView(button, params);
    }

    private EditText input(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextSize(30);
        editText.setTextColor(Color.parseColor("#17202A"));
        editText.setHintTextColor(Color.parseColor("#98A2B3"));
        editText.setPadding(dp(20), 0, dp(20), 0);
        editText.setBackground(bg("#FFFFFF", "#D7DEE8", 8));
        return editText;
    }

    private void addInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(18), 0, 0);

        TextView labelView = text(label, 26, "#657184", true);
        row.addView(labelView, new LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueView = text(value, 30, "#17202A", false);
        row.addView(valueView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row);
    }

    private void addLedStatus(LinearLayout parent, String value) {
        TextView badge = text(value, 30, "#0F7A3B", true);
        badge.setGravity(Gravity.CENTER);
        badge.setBackground(bg("#E8F7EF", "#A7D7BC", 8));
        parent.addView(badge, matchHeight(64, 24, 0));
    }

    private void addVoiceGuide(LinearLayout parent, String value) {
        TextView guide = text(value, 26, "#1E40AF", true);
        guide.setGravity(Gravity.CENTER);
        guide.setBackground(bg("#EAF1FF", "#9DB7E8", 8));
        parent.addView(guide, matchHeight(72, 18, 0));
    }

    private TextView text(String value, int size, String color, boolean bold) {
        TextView textView = new TextView(this);
        textView.setText(value);
        textView.setTextSize(size);
        textView.setTextColor(Color.parseColor(color));
        textView.setTypeface(Typeface.DEFAULT, bold ? Typeface.BOLD : Typeface.NORMAL);
        textView.setIncludeFontPadding(true);
        return textView;
    }

    private LinearLayout.LayoutParams matchWrap(int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, dp(top), 0, dp(bottom));
        return params;
    }

    private LinearLayout.LayoutParams matchHeight(int height, int top, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(height)
        );
        params.setMargins(0, dp(top), 0, dp(bottom));
        return params;
    }

    private GradientDrawable bg(String fill, String stroke, int radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(Color.parseColor(fill));
        drawable.setStroke(dp(1), Color.parseColor(stroke));
        drawable.setCornerRadius(dp(radius));
        return drawable;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
    }

    private void applyTemiImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private static class ItemInfo {
        final String name;
        final String location;
        final int drawerNumber;
        final int quantity;

        ItemInfo(String name, String location, int drawerNumber, int quantity) {
            this.name = name;
            this.location = location;
            this.drawerNumber = drawerNumber;
            this.quantity = quantity;
        }
    }

    private static class PendingItem {
        String name;
        int quantity;
        int drawer;
        int order;

        PendingItem(String name, int quantity, int drawer, int order) {
            this.name = name;
            this.quantity = quantity;
            this.drawer = drawer;
            this.order = order;
        }
    }

    private static class K {
        static final String HOME_TITLE = "\uD648 \uD654\uBA74";
        static final String FIND_CARD_TITLE = "\uC11C\uB78D \uC18D \uBB3C\uAC74 \uCC3E\uAE30";
        static final String REGISTER_CARD_TITLE = "\uBB3C\uAC74 \uB4F1\uB85D";
        static final String UPLOAD_CARD_TITLE = "\uC0AC\uC9C4 \uC5C5\uB85C\uB4DC";
        static final String DB_STATUS_CARD_TITLE = "DB \uC0C1\uD0DC";
        static final String FIND_TITLE = "\uBB3C\uAC74 \uCC3E\uAE30";
        static final String ITEM_NAME_INPUT = "\uCC3E\uC744 \uBB3C\uAC74\uC744 \uC785\uB825\uD558\uC138\uC694";
        static final String VOICE_BUTTON = "\uC74C\uC131 \uC778\uC2DD";
        static final String SEARCH_BUTTON = "\uAC80\uC0C9";
        static final String VOICE_TODO = "\uC74C\uC131 \uC778\uC2DD \uC5F0\uB3D9\uC740 \uB2E4\uC74C \uB2E8\uACC4\uC785\uB2C8\uB2E4.";
        static final String EMPTY_KEYWORD = "\uCC3E\uC744 \uBB3C\uAC74\uC744 \uC785\uB825\uD558\uC138\uC694.";
        static final String API_ERROR = "API \uC624\uB958: ";
        static final String REGISTER_TITLE = "\uBB3C\uAC74 \uB4F1\uB85D";
        static final String REGISTER_NAME_HINT = "\uBB3C\uAC74\uBA85";
        static final String REGISTER_DRAWER_HINT = "\uC11C\uB78D \uBC88\uD638";
        static final String REGISTER_QUANTITY_HINT = "\uC218\uB7C9";
        static final String REGISTER_BUTTON = "\uB4F1\uB85D";
        static final String REGISTER_EMPTY = "\uBAA8\uB4E0 \uD56D\uBAA9\uC744 \uC785\uB825\uD558\uC138\uC694.";
        static final String REGISTER_NUMBER_ERROR = "\uC11C\uB78D, \uC218\uB7C9\uC740 \uC22B\uC790\uB85C \uC785\uB825\uD558\uC138\uC694.";
        static final String REGISTERING_TITLE = "\uB4F1\uB85D \uC911";
        static final String REGISTERING_MESSAGE = "\uC11C\uB78D \uC13C\uC11C \uB9E4\uCE6D\uC744 \uC900\uBE44\uD558\uACE0 \uC788\uC2B5\uB2C8\uB2E4.";
        static final String REGISTER_DONE_TITLE = "\uB4F1\uB85D \uC644\uB8CC";
        static final String REGISTER_DONE_MESSAGE = "\uBB3C\uAC74\uC744 DB\uC5D0 \uB4F1\uB85D\uD588\uC2B5\uB2C8\uB2E4.";
        static final String REGISTER_FAIL_TITLE = "\uB4F1\uB85D \uC2E4\uD328";
        static final String SEARCH_NOW_BUTTON = "\uBC14\uB85C \uCC3E\uAE30";
        static final String HOME_BUTTON = "\uD648\uC73C\uB85C";
        static final String DB_STATUS_TITLE = "DB \uC0C1\uD0DC";
        static final String DB_CHECKING_MESSAGE = "API \uC11C\uBC84\uC5D0 \uC5F0\uACB0\uD558\uACE0 \uC788\uC2B5\uB2C8\uB2E4.";
        static final String DB_CONNECTED = "DB \uC5F0\uACB0 \uC131\uACF5";
        static final String DB_CONNECTED_STATUS = "\uD14C\uBBF8 \uB0B4\uBD80 DB\uC640 \uC6F9\uC11C\uBC84\uAC00 \uC900\uBE44\uB410\uC2B5\uB2C8\uB2E4.";
        static final String DB_READY_BADGE = "\uBB3C\uAC74 \uB4F1\uB85D\uACFC \uAC80\uC0C9 \uC0AC\uC6A9 \uAC00\uB2A5";
        static final String DB_DISCONNECTED = "DB \uC5F0\uACB0 \uC2E4\uD328";
        static final String RECHECK_BUTTON = "\uB2E4\uC2DC \uD655\uC778";
        static final String UNKNOWN_ERROR = "\uC54C \uC218 \uC5C6\uB294 \uC624\uB958";
        static final String LOADING_TITLE = "\uAC80\uC0C9 \uC911";
        static final String LOADING_MESSAGE = "DB\uC5D0\uC11C \uBB3C\uAC74 \uC704\uCE58\uB97C \uD655\uC778\uD558\uACE0 \uC788\uC2B5\uB2C8\uB2E4.";
        static final String RESULT_TITLE = "\uAC80\uC0C9 \uACB0\uACFC";
        static final String API_UNAVAILABLE = "API \uC11C\uBC84\uC5D0 \uC5F0\uACB0\uD560 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.";
        static final String API_CHECK_GUIDE = "\uC11C\uBC84 \uC2E4\uD589 \uB610\uB294 Wi-Fi \uC5F0\uACB0\uC744 \uD655\uC778\uD558\uC138\uC694.";
        static final String FOUND_SUFFIX = " \uC704\uCE58\uB97C \uCC3E\uC558\uC2B5\uB2C8\uB2E4.";
        static final String NOT_FOUND_TITLE = "\uB4F1\uB85D\uB418\uC9C0 \uC54A\uC740 \uBB3C\uAC74\uC785\uB2C8\uB2E4.";
        static final String NOT_FOUND_STATUS = "DB\uC5D0\uC11C \uBB3C\uAC74 \uC815\uBCF4\uB97C \uCC3E\uC744 \uC218 \uC5C6\uC2B5\uB2C8\uB2E4.";
        static final String NOT_FOUND_GUIDE = "\uBB3C\uAC74\uBA85\uC744 \uB2E4\uC2DC \uD655\uC778\uD558\uAC70\uB098 \uC0C8 \uBB3C\uAC74\uC744 \uB4F1\uB85D\uD558\uC138\uC694.";
        static final String STATUS_LABEL = "\uC0C1\uD0DC";
        static final String ITEM_LABEL = "\uBB3C\uAC74";
        static final String ARDUINO_LABEL = "UNO Q";
        static final String ADDRESS_LABEL = "\uC8FC\uC18C";
        static final String LOCATION_LABEL = "\uC704\uCE58";
        static final String DRAWER_LABEL = "\uC11C\uB78D";
        static final String QUANTITY_LABEL = "\uC218\uB7C9";
        static final String GUIDE_LABEL = "\uC548\uB0B4";
        static final String DRAWER_SUFFIX = "\uBC88 \uC11C\uB78D";
        static final String QUANTITY_SUFFIX = "\uAC1C";
        static final String LED_SUFFIX = "\uBC88 \uC810\uB4F1 \uC911";
        static final String TEMI_GUIDE_PREFIX = "Temi \uC74C\uC131 \uC548\uB0B4: ";
        static final String MOVE_SUFFIX = "\uC73C\uB85C \uC774\uB3D9\uD558\uC138\uC694.";
        static final String EXIT_BUTTON = "\uC885\uB8CC";
        static final String SEARCH_AGAIN_BUTTON = "\uB2E4\uC2DC \uAC80\uC0C9";
        static final String DELETE_BUTTON = "\uC0AD\uC81C";
        static final String DELETE_DONE = "\uBB3C\uAC74\uC744 \uC0AD\uC81C\uD588\uC2B5\uB2C8\uB2E4.";
        static final String DELETE_FAIL = "\uC0AD\uC81C\uD560 \uBB3C\uAC74\uC744 \uCC3E\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4.";
        static final String STORAGE_WAIT_TITLE = "\uC11C\uB78D \uB9E4\uCE6D \uB300\uAE30";
        static final String STORAGE_WAIT_MESSAGE = "UNO Q \uC11C\uB78D \uC13C\uC11C\uB97C \uAE30\uB2E4\uB9BD\uB2C8\uB2E4.";
        static final String SENSOR_WAITING = "\uC11C\uB78D \uC5F4\uB9BC/\uB2EB\uD798 \uC774\uBCA4\uD2B8\uB97C \uAE30\uB2E4\uB9AC\uB294 \uC911";
        static final String STORAGE_DONE_OR_NONE = "\uC9C4\uD589 \uC911\uC778 \uC218\uB0A9 \uC791\uC5C5\uC774 \uC5C6\uC2B5\uB2C8\uB2E4.";
        static final String REFRESH_BUTTON = "\uC0C8\uB85C\uACE0\uCE68";
        static final String MANUAL_SAVE_BUTTON = "\uC9C1\uC811 \uC800\uC7A5";
        static final String UPLOAD_TITLE = "\uC0AC\uC9C4 \uC5C5\uB85C\uB4DC";
        static final String UPLOAD_GUIDE = "\uD734\uB300\uD3F0\uC73C\uB85C QR\uC744 \uC2A4\uCE94\uD558\uC5EC \uC0AC\uC9C4\uC744 \uC62C\uB9AC\uC138\uC694.";
        static final String LATEST_UPLOAD_BUTTON = "\uCD5C\uC2E0 \uACB0\uACFC";
        static final String LATEST_UPLOAD_TITLE = "\uCD5C\uC2E0 \uC5C5\uB85C\uB4DC";
        static final String NO_UPLOAD = "\uC544\uC9C1 \uC5C5\uB85C\uB4DC\uB41C \uC0AC\uC9C4\uC774 \uC5C6\uC2B5\uB2C8\uB2E4.";
        static final String UPLOAD_FOUND = "\uC0AC\uC9C4 \uC5C5\uB85C\uB4DC \uACB0\uACFC";
    }
}
