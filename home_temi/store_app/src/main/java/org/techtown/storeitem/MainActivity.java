package org.techtown.storeitem;

import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONArray;
import org.json.JSONObject;

import com.robotemi.sdk.Robot;
import com.robotemi.sdk.TtsRequest;
import com.robotemi.sdk.listeners.OnGoToLocationStatusChangedListener;
import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity implements OnGoToLocationStatusChangedListener {
    private static final int DEFAULT_STORE_ID = 1;
    private static final int DEFAULT_TRANSFER_ID = 5;
    private static final String[] API_BASE_URLS = {
            "http://127.0.0.1:8000",
            "http://10.0.2.2:8000",
            "http://172.17.72.159:8000",
            "http://172.17.74.149:8000",
            "http://172.17.76.159:8000",
            "http://172.17.79.72:8000"
    };

    private FrameLayout pageRoot;
    private List<ShoppingItem> shoppingItems = new ArrayList<>();
    private boolean usingSampleData = false;
    private Robot robot;
    private int currentPage = PAGE_HOME;
    private int storeId = DEFAULT_STORE_ID;
    private int transferId = DEFAULT_TRANSFER_ID;
    private String qrApiBaseUrl;
    private String pendingArrivalSection;

    private static final int PAGE_HOME = 0;
    private static final int PAGE_LIST = 1;
    private static final int PAGE_GUIDE = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode();
        if (getSupportActionBar() != null) {
            getSupportActionBar().hide();
        }

        pageRoot = new FrameLayout(this);
        pageRoot.setBackgroundColor(Color.parseColor("#F5F7FA"));
        setContentView(pageRoot);
        robot = Robot.getInstance();
        Log.i("StoreTemiLocations", "Saved locations: " + robot.getLocations());
        pageRoot.postDelayed(() -> Log.i(
                "StoreTemiLocations",
                "Saved locations after SDK init: " + robot.getLocations()
        ), 5000);
        showHome();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        robot.addOnGoToLocationStatusChangedListener(this);
    }

    @Override
    protected void onStop() {
        robot.removeOnGoToLocationStatusChangedListener(this);
        super.onStop();
    }

    @Override
    public void onGoToLocationStatusChanged(
            String location,
            String status,
            int descriptionId,
            String description
    ) {
        if (!"complete".equalsIgnoreCase(status) || pendingArrivalSection == null) {
            return;
        }

        robot.speak(TtsRequest.create(pendingArrivalSection + " 구역에 도착했습니다.", false));
        pendingArrivalSection = null;
    }

    @Override
    public void onBackPressed() {
        if (currentPage == PAGE_GUIDE) {
            showShoppingList();
        } else if (currentPage == PAGE_LIST) {
            showHome();
        } else {
            super.onBackPressed();
        }
    }

    private void showHome() {
        currentPage = PAGE_HOME;
        LinearLayout page = basePage(K.HOME_TITLE, K.HOME_SUBTITLE);

        LinearLayout welcomeCard = card(page);
        welcomeCard.setGravity(Gravity.CENTER);
        welcomeCard.setPadding(dp(56), dp(70), dp(56), dp(70));

        TextView headline = text(K.HOME_HEADLINE, 42, "#17202A", true);
        headline.setGravity(Gravity.CENTER);
        welcomeCard.addView(headline);

        TextView description = text(K.HOME_DESCRIPTION, 27, "#657184", false);
        description.setGravity(Gravity.CENTER);
        description.setPadding(0, dp(22), 0, dp(38));
        welcomeCard.addView(description);

        LinearLayout buttonRow = horizontal(welcomeCard);
        addButton(buttonRow, K.QR_SHOPPING_START_BUTTON, true, v -> startQrScan());

        LinearLayout directStartRow = horizontal(welcomeCard);
        addButton(directStartRow, K.SHOPPING_START_BUTTON, false, v -> {
            storeId = DEFAULT_STORE_ID;
            transferId = DEFAULT_TRANSFER_ID;
            qrApiBaseUrl = null;
            loadShoppingList();
        });

        LinearLayout secondaryButtonRow = horizontal(welcomeCard);
        addButton(secondaryButtonRow, K.GO_HOME_BASE_BUTTON, false, v -> goToHomeBase());
        addButton(secondaryButtonRow, K.EXIT_APP_BUTTON, false, v -> finishAndRemoveTask());
        setPage(page);
    }

    private void startQrScan() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.QR_CODE);
        integrator.setPrompt(K.QR_SCAN_PROMPT);
        integrator.setBeepEnabled(true);
        integrator.setOrientationLocked(false);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result == null) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (result.getContents() == null) {
            toast(K.QR_SCAN_CANCELLED);
            showHome();
            return;
        }
        try {
            boolean containsShoppingItems = applyQrPayload(result.getContents());
            toast(K.QR_SCAN_SUCCESS);
            if (containsShoppingItems) {
                usingSampleData = false;
                showShoppingList();
            } else {
                loadShoppingList();
            }
        } catch (Exception e) {
            Log.w("StoreTemiQr", "Invalid QR payload", e);
            toast(K.QR_SCAN_INVALID);
            showHome();
        }
    }

    private boolean applyQrPayload(String payload) throws Exception {
        String value = payload == null ? "" : payload.trim();
        if (value.startsWith("{")) {
            JSONObject json = new JSONObject(value);
            String type = json.optString("type");
            if ("temi_shopping_list".equals(type)) {
                JSONArray items = json.optJSONArray("items");
                List<ShoppingItem> parsedItems = new ArrayList<>();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        ShoppingItem item = parseItem(items.getJSONObject(i));
                        if (!item.name.trim().isEmpty()) {
                            parsedItems.add(item);
                        }
                    }
                }
                if (parsedItems.isEmpty()) {
                    throw new IllegalArgumentException("Empty embedded shopping list");
                }
                shoppingItems = parsedItems;
                transferId = json.optInt("transfer_id", DEFAULT_TRANSFER_ID);
                return true;
            }
            if (!"temi_shopping_transfer".equals(type)) {
                throw new IllegalArgumentException("Unsupported QR type");
            }
            String serverUrl = json.optString("server_url", "").trim();
            int scannedStoreId = json.optInt("store_id", 0);
            int scannedTransferId = json.optInt("transfer_id", 0);
            applyTransferTarget(serverUrl, scannedStoreId, scannedTransferId);
            return false;
        }

        Pattern apiUrlPattern = Pattern.compile(
                "^(https?://[^/]+)/api/stores/(\\d+)/transfers/(\\d+)/?$",
                Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = apiUrlPattern.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Unsupported QR payload");
        }
        applyTransferTarget(
                matcher.group(1),
                Integer.parseInt(matcher.group(2)),
                Integer.parseInt(matcher.group(3))
        );
        return false;
    }

    private void applyTransferTarget(String serverUrl, int scannedStoreId, int scannedTransferId) {
        if (!(serverUrl.startsWith("http://") || serverUrl.startsWith("https://"))
                || scannedStoreId <= 0 || scannedTransferId <= 0) {
            throw new IllegalArgumentException("Invalid transfer target");
        }
        qrApiBaseUrl = serverUrl.replaceAll("/+$", "");
        storeId = scannedStoreId;
        transferId = scannedTransferId;
    }

    private void loadShoppingList() {
        showLoading();
        new Thread(() -> {
            try {
                FetchResult result = fetchTransfer();
                shoppingItems = result.items;
                usingSampleData = false;
                runOnUiThread(this::showShoppingList);
            } catch (Exception e) {
                shoppingItems = sampleItems();
                usingSampleData = true;
                runOnUiThread(this::showShoppingList);
            }
        }).start();
    }

    private FetchResult fetchTransfer() throws Exception {
        Exception lastError = null;
        List<String> baseUrls = new ArrayList<>();
        if (qrApiBaseUrl != null && !qrApiBaseUrl.isEmpty()) {
            baseUrls.add(qrApiBaseUrl);
        }
        for (String defaultUrl : API_BASE_URLS) {
            if (!baseUrls.contains(defaultUrl)) {
                baseUrls.add(defaultUrl);
            }
        }
        for (String baseUrl : baseUrls) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(baseUrl + "/api/stores/" + storeId + "/transfers/" + transferId);
                connection = (HttpURLConnection) url.openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(2500);
                connection.setReadTimeout(2500);

                int code = connection.getResponseCode();
                InputStream stream = code >= 200 && code < 300
                        ? connection.getInputStream()
                        : connection.getErrorStream();
                String body = readBody(stream);
                if (code < 200 || code >= 300) {
                    throw new IllegalStateException("API " + code);
                }

                JSONObject json = new JSONObject(body);
                JSONArray items = json.optJSONArray("items");
                List<ShoppingItem> parsed = new ArrayList<>();
                if (items != null) {
                    for (int i = 0; i < items.length(); i++) {
                        parsed.add(parseItem(items.getJSONObject(i)));
                    }
                }
                if (parsed.isEmpty()) {
                    throw new IllegalStateException("Empty shopping list");
                }
                return new FetchResult(baseUrl, parsed);
            } catch (Exception e) {
                lastError = e;
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }
        throw lastError == null ? new IllegalStateException("API unavailable") : lastError;
    }

    private ShoppingItem parseItem(JSONObject json) {
        String itemName = json.optString("item_name", "").trim();
        String section = cleanJsonString(json, "section");
        if (isMissingSection(section)) {
            section = sectionForItemName(itemName);
        }
        boolean inStock = json.has("in_stock")
                ? json.optBoolean("in_stock", false)
                : !section.isEmpty();
        return new ShoppingItem(
                itemName,
                json.optInt("quantity", 1),
                section,
                inStock
        );
    }

    private boolean isMissingSection(String section) {
        if (section == null || section.trim().isEmpty()) {
            return true;
        }
        String normalized = section.trim().toLowerCase(Locale.US);
        return "unknown".equals(normalized)
                || "unknown section".equals(normalized)
                || K.UNKNOWN_SECTION.equals(section.trim());
    }

    private String sectionForItemName(String itemName) {
        String normalized = itemName == null
                ? ""
                : itemName.trim().replaceAll("\\s+", "").toLowerCase(Locale.US);
        if ("라면".equals(normalized) || "과자".equals(normalized)) {
            return "식품";
        }
        if ("모자".equals(normalized)) {
            return "의류";
        }
        if ("물티슈".equals(normalized) || "샴푸".equals(normalized)) {
            return "생활용품";
        }
        return "";
    }

    private String cleanJsonString(JSONObject json, String key) {
        if (json.isNull(key)) {
            return "";
        }
        String value = json.optString(key, "");
        return "null".equalsIgnoreCase(value) ? "" : value;
    }

    private List<ShoppingItem> sampleItems() {
        List<ShoppingItem> items = new ArrayList<>();
        items.add(new ShoppingItem("라면", 1, "식품", true));
        items.add(new ShoppingItem("과자", 1, "식품", true));
        items.add(new ShoppingItem("모자", 1, "의류", true));
        items.add(new ShoppingItem("물티슈", 1, "생활용품", true));
        items.add(new ShoppingItem("우유", 1, "", false));
        return items;
    }

    private void showLoading() {
        LinearLayout page = basePage(K.TITLE, K.LOADING_SUBTITLE);
        LinearLayout card = card(page);
        card.setGravity(Gravity.CENTER);
        ProgressBar progressBar = new ProgressBar(this);
        card.addView(progressBar, new LinearLayout.LayoutParams(dp(72), dp(72)));
        TextView text = text(K.LOADING_MESSAGE, 28, "#17202A", true);
        text.setGravity(Gravity.CENTER);
        text.setPadding(0, dp(20), 0, 0);
        card.addView(text);
        setPage(page);
    }

    private void showShoppingList() {
        currentPage = PAGE_LIST;
        int availableCount = 0;
        int completedCount = 0;
        for (ShoppingItem item : shoppingItems) {
            if (item.inStock) {
                availableCount++;
                if (item.completed) {
                    completedCount++;
                }
            }
        }
        LinearLayout page = basePage(
                K.TITLE,
                completedCount + " / " + availableCount + K.COMPLETED_SUMMARY_SUFFIX,
                true
        );

        if (availableCount > 0 && completedCount == availableCount) {
            LinearLayout row = horizontal(page);
            addButton(row, K.FINISH_AND_HOME_BUTTON, true, v -> returnToTemiHome());
        }

        for (ShoppingItem item : shoppingItems) {
            addItemCard(page, item);
        }

        setPage(page);
    }

    private void addItemCard(LinearLayout parent, ShoppingItem item) {
        LinearLayout itemCard = card(parent);
        if (item.completed) {
            itemCard.setBackground(bg("#ECFDF3", "#47A66A", 8));
        }

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        itemCard.addView(header);

        TextView name = text(item.name + "  " + item.quantity + K.QTY_SUFFIX, 36, "#17202A", true);
        header.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!item.inStock) {
            addItemActionButton(header, item);
        }

        addInfoRow(itemCard, K.LOCATION_LABEL, item.locationText());

        if (item.inStock) {
            LinearLayout actions = compactActionRow(itemCard);
            if (item.completed) {
                addCompactCardButton(actions, K.COMPLETED_BADGE, false, null);
            } else {
                addCompactCardButton(actions, K.LOCATION_GUIDE_BUTTON, false, v -> showGuide(item));
                addCompactCardButton(actions, K.PICKED_BUTTON, true, v -> completePickup(item));
            }
        }
    }

    private void addItemActionButton(LinearLayout parent, ShoppingItem item) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setTextSize(21);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);

        if (item.completed) {
            button.setText(K.COMPLETED_BADGE);
            button.setTextColor(Color.parseColor("#166534"));
            button.setBackground(bg("#DCFCE7", "#47A66A", 8));
            button.setEnabled(false);
        } else if (!item.inStock) {
            button.setText(K.OUT_OF_STOCK);
            button.setTextColor(Color.parseColor("#657184"));
            button.setBackground(bg("#F1F3F5", "#CBD2DC", 8));
            button.setEnabled(false);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(220),
                dp(62)
        );
        params.setMargins(dp(18), 0, 0, 0);
        parent.addView(button, params);
    }

    private LinearLayout compactActionRow(LinearLayout parent) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.RIGHT | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(72)
        );
        params.setMargins(0, dp(14), 0, 0);
        parent.addView(row, params);
        return row;
    }

    private void addCompactCardButton(
            LinearLayout parent,
            String label,
            boolean primary,
            View.OnClickListener listener
    ) {
        Button button = new Button(this);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextSize(21);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setTextColor(primary ? Color.WHITE : Color.parseColor("#1E40AF"));
        button.setBackground(primary
                ? bg("#1E40AF", "#1E40AF", 8)
                : bg("#FFFFFF", "#1E40AF", 8));
        if (listener == null) {
            button.setEnabled(false);
            button.setAlpha(0.65f);
        } else {
            button.setOnClickListener(listener);
        }

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                dp(240),
                LinearLayout.LayoutParams.MATCH_PARENT
        );
        params.setMargins(dp(12), 0, 0, 0);
        parent.addView(button, params);
    }

    private void completePickup(ShoppingItem item) {
        if (item.completed || !item.inStock) {
            return;
        }
        item.completed = true;
        toast(item.name + K.PICKED_TOAST);
        showShoppingList();
    }

    private void showGuide(ShoppingItem item) {
        currentPage = PAGE_GUIDE;
        LinearLayout page = basePage(K.GUIDE_TITLE, item.name);

        LinearLayout guide = card(page);
        TextView headline = text(item.inStock ? K.GO_TO_LOCATION : K.OUT_OF_STOCK, 40, item.inStock ? "#17202A" : "#B42318", true);
        headline.setGravity(Gravity.CENTER);
        guide.addView(headline);

        TextView location = text(item.locationText(), 46, "#1E40AF", true);
        location.setGravity(Gravity.CENTER);
        location.setPadding(0, dp(30), 0, dp(16));
        guide.addView(location);

        addInfoRow(guide, K.ITEM_LABEL, item.name + " / " + item.quantity + K.QTY_SUFFIX);

        LinearLayout row = horizontal(page);
        addButton(row, K.BACK_BUTTON, false, v -> showShoppingList());
        if (item.inStock && !item.completed) {
            addButton(row, K.PICKED_BUTTON, true, v -> completePickup(item));
        }

        setPage(page);
        pageRoot.postDelayed(() -> goToItemLocation(item), 500);
    }

    private void goToItemLocation(ShoppingItem item) {
        if (item.completed || !item.inStock || item.section == null || item.section.trim().isEmpty()) {
            return;
        }
        String temiLocation = toTemiLocationName(item.section);
        try {
            pendingArrivalSection = toKoreanSectionName(item.section);
            robot.goTo(temiLocation);
            toast(K.MOVING_TO_PREFIX + temiLocation);
        } catch (Exception e) {
            pendingArrivalSection = null;
            toast(K.MOVE_FAILED_PREFIX + temiLocation);
        }
    }

    private String toTemiLocationName(String section) {
        String normalized = section.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US);
        if (normalized.contains("food") || normalized.contains("식품")) {
            return "식품";
        }
        if (normalized.contains("clothing") || normalized.contains("의류")) {
            return "의류";
        }
        if (normalized.contains("home goods") || normalized.contains("생활용품")) {
            return "생활용품";
        }
        normalized = normalized.replaceAll("\\s+(\\d+)$", "$1");
        return normalized;
    }

    private String toKoreanSectionName(String section) {
        String normalized = section.trim().replaceAll("\\s+", " ").toLowerCase(Locale.US);
        if (normalized.contains("food")) {
            return "식품";
        }
        if (normalized.contains("clothing")) {
            return "의류";
        }
        if (normalized.contains("home goods")) {
            return "생활용품";
        }
        return section.trim();
    }

    private void returnToTemiHome() {
        showHome();
    }

    private void goToHomeBase() {
        try {
            robot.goTo("home base");
            toast(K.MOVING_HOME_BASE);
        } catch (Exception e) {
            toast(K.MOVE_HOME_BASE_FAILED);
        }
    }

    private LinearLayout basePage(String title, String subtitle) {
        return basePage(title, subtitle, false);
    }

    private LinearLayout basePage(String title, String subtitle, boolean showBackButton) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(56), dp(38), dp(56), dp(34));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        content.addView(titleRow, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView titleView = text(title, 44, "#17202A", true);
        titleRow.addView(titleView, new LinearLayout.LayoutParams(
                0,
                LinearLayout.LayoutParams.WRAP_CONTENT,
                1
        ));

        if (showBackButton) {
            Button backButton = new Button(this);
            backButton.setText(K.TOP_BACK_BUTTON);
            backButton.setAllCaps(false);
            backButton.setTextSize(20);
            backButton.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            backButton.setTextColor(Color.parseColor("#17202A"));
            backButton.setBackground(bg("#FFFFFF", "#CBD2DC", 8));
            backButton.setOnClickListener(v -> showHome());
            titleRow.addView(backButton, new LinearLayout.LayoutParams(dp(90), dp(62)));
        }

        if (subtitle != null && !subtitle.isEmpty()) {
            TextView subtitleView = text(subtitle, 24, "#657184", false);
            subtitleView.setPadding(0, dp(8), 0, dp(10));
            content.addView(subtitleView);
        }
        return content;
    }

    private void setPage(LinearLayout content) {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.addView(content);
        pageRoot.removeAllViews();
        pageRoot.addView(scrollView);
    }

    private LinearLayout card(LinearLayout parent) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(36), dp(28), dp(36), dp(28));
        card.setBackground(bg("#FFFFFF", "#D7DEE8", 8));
        parent.addView(card, matchWrap(14, 10));
        return card;
    }

    private LinearLayout horizontal(LinearLayout parent) {
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.HORIZONTAL);
        parent.addView(layout, matchHeight(86, 12, 12));
        return layout;
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMargins(dp(10), 0, dp(10), 0);
        parent.addView(button, params);
    }

    private void addInfoRow(LinearLayout parent, String label, String value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, dp(14), 0, 0);

        TextView labelView = text(label, 24, "#657184", true);
        row.addView(labelView, new LinearLayout.LayoutParams(dp(150), LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView valueView = text(value, 28, "#17202A", false);
        row.addView(valueView, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row);
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

    private String readBody(InputStream stream) throws Exception {
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, "UTF-8"));
        StringBuilder builder = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            builder.append(line);
        }
        reader.close();
        return builder.toString();
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private void toast(String message) {
        TextView messageView = new TextView(getApplicationContext());
        messageView.setText(message);
        messageView.setTextColor(Color.WHITE);
        messageView.setTextSize(22);
        messageView.setGravity(Gravity.CENTER);
        messageView.setPadding(dp(28), dp(16), dp(28), dp(16));
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.parseColor("#CC17202A"));
        background.setCornerRadius(dp(14));
        messageView.setBackground(background);

        Toast toast = new Toast(getApplicationContext());
        toast.setDuration(Toast.LENGTH_SHORT);
        toast.setGravity(Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL, 0, dp(80));
        toast.setView(messageView);
        toast.show();
    }

    private void applyImmersiveMode() {
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private static class FetchResult {
        final String baseUrl;
        final List<ShoppingItem> items;

        FetchResult(String baseUrl, List<ShoppingItem> items) {
            this.baseUrl = baseUrl;
            this.items = items;
        }
    }

    private static class ShoppingItem {
        final String name;
        final int quantity;
        final boolean inStock;
        final String section;
        boolean completed;

        ShoppingItem(String name, int quantity, String section, boolean inStock) {
            this.name = name;
            this.quantity = quantity;
            this.section = section;
            this.inStock = inStock;
            this.completed = false;
        }

        String locationText() {
            if (!inStock) {
                return K.OUT_OF_STOCK;
            }
            return section == null || section.isEmpty() ? K.UNKNOWN_SECTION : section;
        }
    }

    private static class K {
        static final String HOME_TITLE = "\uB9E4\uC7A5 Temi";
        static final String HOME_SUBTITLE = "\uC2A4\uB9C8\uD2B8 \uC1FC\uD551 \uC548\uB0B4";
        static final String HOME_HEADLINE = "\uC1FC\uD551\uC744 \uC2DC\uC791\uD560\uAE4C\uC694?";
        static final String HOME_DESCRIPTION = "\uC1FC\uD551\uB9AC\uC2A4\uD2B8\uB97C \uBD88\uB7EC\uC640 \uBB3C\uD488 \uC704\uCE58\uB85C \uC548\uB0B4\uD569\uB2C8\uB2E4.";
        static final String SHOPPING_START_BUTTON = "\uC1FC\uD551 \uC2DC\uC791\uD558\uAE30";
        static final String QR_SHOPPING_START_BUTTON = "QR\uB85C \uC1FC\uD551 \uC2DC\uC791";
        static final String QR_SCAN_PROMPT = "\uC1FC\uD551\uB9AC\uC2A4\uD2B8 QR\uC744 \uBCF4\uC5EC\uC8FC\uC138\uC694";
        static final String QR_SCAN_CANCELLED = "QR \uC2A4\uCE94\uC744 \uCDE8\uC18C\uD588\uC2B5\uB2C8\uB2E4";
        static final String QR_SCAN_SUCCESS = "\uC1FC\uD551\uB9AC\uC2A4\uD2B8 QR\uC744 \uC778\uC2DD\uD588\uC2B5\uB2C8\uB2E4";
        static final String QR_SCAN_INVALID = "\uC62C\uBC14\uB978 \uC1FC\uD551\uB9AC\uC2A4\uD2B8 QR\uC774 \uC544\uB2D9\uB2C8\uB2E4";
        static final String EXIT_APP_BUTTON = "\uC571 \uC885\uB8CC";
        static final String GO_HOME_BASE_BUTTON = "\uD648\uBCA0\uC774\uC2A4";
        static final String MOVING_HOME_BASE = "\uD648\uBCA0\uC774\uC2A4\uB85C \uC774\uB3D9\uD569\uB2C8\uB2E4";
        static final String MOVE_HOME_BASE_FAILED = "\uD648\uBCA0\uC774\uC2A4 \uC774\uB3D9\uC744 \uC2DC\uC791\uD558\uC9C0 \uBABB\uD588\uC2B5\uB2C8\uB2E4";
        static final String TITLE = "\uB9E4\uC7A5 Temi";
        static final String LOADING_SUBTITLE = "\uC804\uC1A1\uB41C \uC1FC\uD551\uB9AC\uC2A4\uD2B8\uB97C \uBD88\uB7EC\uC635\uB2C8\uB2E4.";
        static final String LOADING_MESSAGE = "\uC1FC\uD551\uB9AC\uC2A4\uD2B8 \uD655\uC778 \uC911";
        static final String GUIDE_TITLE = "\uC704\uCE58 \uC548\uB0B4";
        static final String LOCATION_GUIDE_BUTTON = "\uC704\uCE58 \uC548\uB0B4";
        static final String QTY_SUFFIX = "\uAC1C";
        static final String LOCATION_LABEL = "\uC704\uCE58";
        static final String OUT_OF_STOCK = "\uC7AC\uACE0 \uC5C6\uC74C";
        static final String GO_TO_LOCATION = "\uC774 \uC704\uCE58\uB85C \uC548\uB0B4\uD569\uB2C8\uB2E4";
        static final String ITEM_LABEL = "\uBB3C\uD488";
        static final String MOVING_TO_PREFIX = "\uC774\uB3D9 \uC2DC\uC791: ";
        static final String MOVE_FAILED_PREFIX = "\uC774\uB3D9 \uC2E4\uD328: ";
        static final String BACK_BUTTON = "\uBAA9\uB85D";
        static final String PICKED_BUTTON = "\uD53D\uC5C5 \uC644\uB8CC";
        static final String PICKED_TOAST = " \uD53D\uC5C5 \uC644\uB8CC";
        static final String COMPLETED_BADGE = "\uD53D\uC5C5 \uC644\uB8CC";
        static final String COMPLETED_SUMMARY_SUFFIX = "\uAC1C \uD53D\uC5C5 \uC644\uB8CC";
        static final String ALREADY_COMPLETED_TOAST = " \uD53D\uC5C5 \uC644\uB8CC\uB41C \uBB3C\uD488\uC785\uB2C8\uB2E4";
        static final String ALL_COMPLETED = "\uBAA8\uB4E0 \uBB3C\uD488 \uD53D\uC5C5\uC774 \uC644\uB8CC\uB410\uC2B5\uB2C8\uB2E4";
        static final String FINISH_AND_HOME_BUTTON = "\uC1FC\uD551 \uC644\uB8CC";
        static final String TOP_BACK_BUTTON = "\u2190";
        static final String UNKNOWN_SECTION = "\uC704\uCE58 \uBBF8\uC9C0\uC815";
    }
}
