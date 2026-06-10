package org.techtown.storeitem;

import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
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

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

public class MainActivity extends AppCompatActivity {
    private static final int STORE_ID = 1;
    private static final int TRANSFER_ID = 1;
    private static final String[] API_BASE_URLS = {
            "http://127.0.0.1:8000",
            "http://10.0.2.2:8000",
            "http://172.17.76.159:8000",
            "http://172.17.79.72:8000"
    };

    private FrameLayout pageRoot;
    private List<ShoppingItem> shoppingItems = new ArrayList<>();
    private String connectedBaseUrl = "";
    private boolean usingSampleData = false;

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
        loadShoppingList();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersiveMode();
        }
    }

    @Override
    public void onBackPressed() {
        showShoppingList();
    }

    private void loadShoppingList() {
        showLoading();
        new Thread(() -> {
            try {
                FetchResult result = fetchTransfer();
                shoppingItems = result.items;
                connectedBaseUrl = result.baseUrl;
                usingSampleData = false;
                runOnUiThread(this::showShoppingList);
            } catch (Exception e) {
                shoppingItems = sampleItems();
                connectedBaseUrl = "";
                usingSampleData = true;
                runOnUiThread(this::showShoppingList);
            }
        }).start();
    }

    private FetchResult fetchTransfer() throws Exception {
        Exception lastError = null;
        for (String baseUrl : API_BASE_URLS) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(baseUrl + "/api/stores/" + STORE_ID + "/transfers/" + TRANSFER_ID);
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
        return new ShoppingItem(
                json.optString("item_name", ""),
                json.optInt("quantity", 1),
                json.optBoolean("in_stock", false),
                json.optString("section", ""),
                json.optString("aisle", ""),
                json.optString("shelf", ""),
                json.optDouble("map_x", 0),
                json.optDouble("map_y", 0)
        );
    }

    private List<ShoppingItem> sampleItems() {
        List<ShoppingItem> items = new ArrayList<>();
        items.add(new ShoppingItem("book", 1, true, "Books", "A1", "2", 12, 20));
        items.add(new ShoppingItem("remote", 1, true, "Electronics", "B3", "1", 42, 35));
        items.add(new ShoppingItem("charger", 2, true, "Mobile Accessories", "B4", "3", 48, 39));
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
        LinearLayout page = basePage(K.TITLE, K.LIST_SUBTITLE);

        LinearLayout status = card(page);
        TextView statusTitle = text(usingSampleData ? K.SAMPLE_BADGE : K.CONNECTED_BADGE, 30, usingSampleData ? "#B45309" : "#0F7A3B", true);
        status.addView(statusTitle);
        addInfoRow(status, K.TRANSFER_LABEL, "store " + STORE_ID + " / transfer " + TRANSFER_ID);
        addInfoRow(status, K.API_LABEL, usingSampleData ? K.SAMPLE_REASON : connectedBaseUrl);

        LinearLayout row = horizontal(page);
        addButton(row, K.REFRESH_BUTTON, false, v -> loadShoppingList());
        addButton(row, K.START_BUTTON, true, v -> {
            if (!shoppingItems.isEmpty()) {
                showGuide(shoppingItems.get(0));
            }
        });

        for (ShoppingItem item : shoppingItems) {
            addItemCard(page, item);
        }

        setPage(page);
    }

    private void addItemCard(LinearLayout parent, ShoppingItem item) {
        LinearLayout itemCard = card(parent);
        itemCard.setOnClickListener(v -> showGuide(item));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        itemCard.addView(header);

        TextView name = text(item.name, 36, "#17202A", true);
        header.addView(name, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView qty = text(item.quantity + K.QTY_SUFFIX, 28, "#1E40AF", true);
        qty.setGravity(Gravity.CENTER);
        qty.setBackground(bg("#EAF1FF", "#9DB7E8", 8));
        header.addView(qty, new LinearLayout.LayoutParams(dp(112), dp(56)));

        addInfoRow(itemCard, K.LOCATION_LABEL, item.locationText());
        addInfoRow(itemCard, K.STATUS_LABEL, item.inStock ? K.IN_STOCK : K.OUT_OF_STOCK);
    }

    private void showGuide(ShoppingItem item) {
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
        addInfoRow(guide, K.MAP_LABEL, "x " + item.mapX + " / y " + item.mapY);
        addInfoRow(guide, K.VOICE_LABEL, item.inStock ? item.name + K.VOICE_GUIDE_SUFFIX : K.OUT_OF_STOCK_GUIDE);

        LinearLayout row = horizontal(page);
        addButton(row, K.BACK_BUTTON, false, v -> showShoppingList());
        addButton(row, K.PICKED_BUTTON, true, v -> {
            toast(item.name + K.PICKED_TOAST);
            showShoppingList();
        });

        setPage(page);
    }

    private LinearLayout basePage(String title, String subtitle) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(56), dp(38), dp(56), dp(34));

        TextView titleView = text(title, 44, "#17202A", true);
        content.addView(titleView);

        TextView subtitleView = text(subtitle, 24, "#657184", false);
        subtitleView.setPadding(0, dp(8), 0, dp(10));
        content.addView(subtitleView);
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
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
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
        final String aisle;
        final String shelf;
        final double mapX;
        final double mapY;

        ShoppingItem(String name, int quantity, boolean inStock, String section, String aisle, String shelf, double mapX, double mapY) {
            this.name = name;
            this.quantity = quantity;
            this.inStock = inStock;
            this.section = section;
            this.aisle = aisle;
            this.shelf = shelf;
            this.mapX = mapX;
            this.mapY = mapY;
        }

        String locationText() {
            if (!inStock) {
                return K.OUT_OF_STOCK;
            }
            String safeSection = section == null || section.isEmpty() ? K.UNKNOWN_SECTION : section;
            String safeAisle = aisle == null || aisle.isEmpty() ? "-" : aisle;
            String safeShelf = shelf == null || shelf.isEmpty() ? "-" : shelf;
            return safeSection + " / " + K.AISLE_PREFIX + safeAisle + " / " + K.SHELF_PREFIX + safeShelf;
        }
    }

    private static class K {
        static final String TITLE = "\uB9E4\uC7A5 Temi";
        static final String LOADING_SUBTITLE = "\uC804\uC1A1\uB41C \uC1FC\uD551\uB9AC\uC2A4\uD2B8\uB97C \uBD88\uB7EC\uC635\uB2C8\uB2E4.";
        static final String LOADING_MESSAGE = "\uC1FC\uD551\uB9AC\uC2A4\uD2B8 \uD655\uC778 \uC911";
        static final String LIST_SUBTITLE = "\uBB3C\uD488\uC744 \uC120\uD0DD\uD558\uBA74 \uB9E4\uC7A5 \uC704\uCE58\uB85C \uC548\uB0B4\uD569\uB2C8\uB2E4.";
        static final String GUIDE_TITLE = "\uC704\uCE58 \uC548\uB0B4";
        static final String CONNECTED_BADGE = "API \uC5F0\uACB0 \uC644\uB8CC";
        static final String SAMPLE_BADGE = "\uC0D8\uD50C \uBAA8\uB4DC";
        static final String SAMPLE_REASON = "\uC804\uC1A1 \uB370\uC774\uD130\uAC00 \uC5C6\uC5B4 \uC0D8\uD50C\uC744 \uD45C\uC2DC\uD569\uB2C8\uB2E4.";
        static final String TRANSFER_LABEL = "\uC804\uC1A1";
        static final String API_LABEL = "API";
        static final String REFRESH_BUTTON = "\uC0C8\uB85C\uACE0\uCE68";
        static final String START_BUTTON = "\uCC98\uC74C \uBB3C\uD488 \uC548\uB0B4";
        static final String QTY_SUFFIX = "\uAC1C";
        static final String LOCATION_LABEL = "\uC704\uCE58";
        static final String STATUS_LABEL = "\uC0C1\uD0DC";
        static final String IN_STOCK = "\uC7AC\uACE0 \uC788\uC74C";
        static final String OUT_OF_STOCK = "\uC7AC\uACE0 \uC5C6\uC74C";
        static final String GO_TO_LOCATION = "\uC774 \uC704\uCE58\uB85C \uC548\uB0B4\uD569\uB2C8\uB2E4";
        static final String ITEM_LABEL = "\uBB3C\uD488";
        static final String MAP_LABEL = "\uC88C\uD45C";
        static final String VOICE_LABEL = "\uC548\uB0B4\uBB38";
        static final String VOICE_GUIDE_SUFFIX = "\uC744 \uCC3E\uC73C\uB824\uBA74 \uD654\uBA74\uC758 \uC704\uCE58\uB85C \uC774\uB3D9\uD558\uC138\uC694.";
        static final String OUT_OF_STOCK_GUIDE = "\uD574\uB2F9 \uBB3C\uD488\uC740 \uD604\uC7AC \uB9E4\uC7A5 \uC7AC\uACE0\uAC00 \uC5C6\uC2B5\uB2C8\uB2E4.";
        static final String BACK_BUTTON = "\uBAA9\uB85D";
        static final String PICKED_BUTTON = "\uD53D\uC5C5 \uC644\uB8CC";
        static final String PICKED_TOAST = " \uD53D\uC5C5 \uC644\uB8CC";
        static final String UNKNOWN_SECTION = "\uC704\uCE58 \uBBF8\uC9C0\uC815";
        static final String AISLE_PREFIX = "\uD1B5\uB85C ";
        static final String SHELF_PREFIX = "\uC120\uBC18 ";
    }
}
