package org.techtown.myapplication;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.text.InputType;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Locale;

public class ShoppingListActivity extends Activity {

    private static final String TAG = "ShoppingListActivity";
    private ShoppingListDbHelper dbHelper;
    private TextToSpeech textToSpeech;
    private SpeechRecognizer speechRecognizer;
    
    // UI elements
    private LinearLayout listContainer;
    private EditText itemNameInput;
    private EditText itemQuantityInput;
    private ImageView qrImageView;
    private TextView ttsStatusLabel;

    // Temporary variables for duplicate check bypass
    private String pendingItemName;
    private int pendingItemQuantity;
    private boolean isAwaitingSpeechInput = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        
        // Initialize DB Helper
        dbHelper = new ShoppingListDbHelper(this);
        
        // Setup initial dummy items in drawer DB for demonstration/comparison
        setupDummyDrawerItems();

        // Initialize TTS
        initTextToSpeech();

        // Initialize STT
        initSpeechRecognizer();

        // Build Dynamic UI (Vanilla CSS layout equivalent in Java)
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(Color.rgb(18, 18, 18)); // Sleek dark mode background

        LinearLayout mainLayout = new LinearLayout(this);
        mainLayout.setOrientation(LinearLayout.VERTICAL);
        mainLayout.setPadding(dp(24), dp(24), dp(24), dp(24));
        mainLayout.setGravity(Gravity.CENTER_HORIZONTAL);

        // Header Title
        TextView headerTitle = new TextView(this);
        headerTitle.setText("스마트 쇼핑리스트");
        headerTitle.setTextColor(Color.WHITE);
        headerTitle.setTextSize(26);
        headerTitle.setTypeface(null, 1);
        headerTitle.setGravity(Gravity.CENTER);
        mainLayout.addView(headerTitle, params(-1, -2, 0, 0, 0, 16));

        // Subtitle
        TextView headerSub = new TextView(this);
        headerSub.setText("Temi 서랍 재고 실시간 대조 및 음성 제어 시스템");
        headerSub.setTextColor(Color.rgb(170, 170, 170));
        headerSub.setTextSize(14);
        headerSub.setGravity(Gravity.CENTER);
        mainLayout.addView(headerSub, params(-1, -2, 0, 0, 0, 24));

        // Input Box container
        LinearLayout inputCard = createCard();
        
        itemNameInput = createEditText("물품명 입력 (예: 세제, 수건)");
        itemQuantityInput = createEditText("수량 입력 (예: 2)");
        itemQuantityInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        
        inputCard.addView(itemNameInput);
        inputCard.addView(itemQuantityInput);

        Button addButton = createButton("쇼핑리스트에 추가", Color.rgb(46, 125, 50));
        addButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                processShoppingItemInput();
            }
        });
        inputCard.addView(addButton, params(-1, dp(54), 0, 12, 0, 0));
        
        mainLayout.addView(inputCard);

        // TTS / STT Status Label
        ttsStatusLabel = new TextView(this);
        ttsStatusLabel.setText("연동 상태: 대기 중");
        ttsStatusLabel.setTextColor(Color.rgb(76, 175, 80));
        ttsStatusLabel.setTextSize(15);
        ttsStatusLabel.setGravity(Gravity.CENTER);
        ttsStatusLabel.setPadding(0, dp(12), 0, dp(12));
        mainLayout.addView(ttsStatusLabel);

        // Shopping List display container
        LinearLayout listCard = createCard();
        TextView listLabel = new TextView(this);
        listLabel.setText("현재 쇼핑리스트 항목");
        listLabel.setTextColor(Color.WHITE);
        listLabel.setTextSize(18);
        listLabel.setTypeface(null, 1);
        listCard.addView(listLabel, params(-1, -2, 0, 0, 0, 12));

        listContainer = new LinearLayout(this);
        listContainer.setOrientation(LinearLayout.VERTICAL);
        listCard.addView(listContainer);

        mainLayout.addView(listCard);

        // Actions container (Done & Export QR)
        Button doneButton = createButton("작성 완료 (QR 내보내기)", Color.rgb(21, 101, 192));
        doneButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                exportShoppingListAsQr();
            }
        });
        mainLayout.addView(doneButton, params(-1, dp(56), 0, 16, 0, 16));

        // QR Output Card
        qrImageView = new ImageView(this);
        qrImageView.setBackgroundColor(Color.WHITE);
        int padding = dp(12);
        qrImageView.setPadding(padding, padding, padding, padding);
        qrImageView.setVisibility(View.GONE);
        LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(dp(260), dp(260));
        qrParams.gravity = Gravity.CENTER;
        mainLayout.addView(qrImageView, qrParams);

        scrollView.addView(mainLayout);
        setContentView(scrollView);

        // Refresh UI list display
        refreshShoppingListUI();
    }

    private void initTextToSpeech() {
        textToSpeech = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS) {
                    textToSpeech.setLanguage(Locale.KOREAN);
                    textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                        @Override
                        public void onStart(String utteranceId) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    ttsStatusLabel.setText("음성 안내 중...");
                                }
                            });
                        }

                        @Override
                        public void onDone(String utteranceId) {
                            if ("duplicate_notice".equals(utteranceId)) {
                                runOnUiThread(new Runnable() {
                                    @Override
                                    public void run() {
                                        ttsStatusLabel.setText("음성 답변 대기 중 (마이크 ON)...");
                                        startListeningVoiceResponse();
                                    }
                                });
                            }
                        }

                        @Override
                        public void onError(String utteranceId) {
                            Log.e(TAG, "TTS Error for utterance ID: " + utteranceId);
                        }
                    });
                } else {
                    Log.e(TAG, "TextToSpeech initialization failed.");
                }
            }
        });
    }

    private void initSpeechRecognizer() {
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this);
        speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                Log.d(TAG, "STT is ready");
            }

            @Override
            public void onBeginningOfSpeech() {}

            @Override
            public void onRmsChanged(float rmsdB) {}

            @Override
            public void onBufferReceived(byte[] buffer) {}

            @Override
            public void onEndOfSpeech() {}

            @Override
            public void onError(int error) {
                Log.e(TAG, "STT Error code: " + error);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ttsStatusLabel.setText("음성 인식 실패 또는 취소됨");
                        isAwaitingSpeechInput = false;
                    }
                });
            }

            @Override
            public void onResults(Bundle results) {
                ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (matches != null && isAwaitingSpeechInput) {
                    for (String text : matches) {
                        Log.d(TAG, "STT Result: " + text);
                        if (text.contains("그래도 추가할래") || text.contains("추가해줘") || text.contains("추가해 줘") || text.contains("그래도")) {
                            runOnUiThread(new Runnable() {
                                @Override
                                public void run() {
                                    insertShoppingItem(pendingItemName, pendingItemQuantity);
                                    Toast.makeText(ShoppingListActivity.this, "음성 인식: 강제 추가 완료", Toast.LENGTH_SHORT).show();
                                    isAwaitingSpeechInput = false;
                                }
                            });
                            return;
                        }
                    }
                }
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        ttsStatusLabel.setText("쇼핑리스트 추가가 취소되었습니다.");
                        isAwaitingSpeechInput = false;
                    }
                });
            }

            @Override
            public void onPartialResults(Bundle partialResults) {}

            @Override
            public void onEvent(int eventType, Bundle params) {}
        });
    }

    private void startListeningVoiceResponse() {
        isAwaitingSpeechInput = true;
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.KOREAN.toString());
        speechRecognizer.startListening(intent);
    }

    private void processShoppingItemInput() {
        String name = itemNameInput.getText().toString().trim();
        String qtyText = itemQuantityInput.getText().toString().trim();

        if (name.length() == 0) {
            Toast.makeText(this, "물품명을 입력해 주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        int quantity = 1;
        if (qtyText.length() > 0) {
            try {
                quantity = Integer.parseInt(qtyText);
            } catch (NumberFormatException e) {
                Toast.makeText(this, "수량은 숫자로 입력해 주세요.", Toast.LENGTH_SHORT).show();
                return;
            }
        }

        if (quantity <= 0) {
            Toast.makeText(this, "수량은 1개 이상이어야 합니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        // [시나리오 2단계]: Temi 내부 DB의 서랍 물품 목록과 실시간 대조
        if (isAlreadyOwnedInDrawer(name, quantity)) {
            pendingItemName = name;
            pendingItemQuantity = quantity;
            
            // TTS 안내
            Bundle params = new Bundle();
            params.putString(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, "duplicate_notice");
            textToSpeech.speak("이미 구매된 물건입니다", TextToSpeech.QUEUE_FLUSH, params, "duplicate_notice");
            
            Toast.makeText(this, "이미 서랍 내 충분한 재고가 있어 보류되었습니다. 음성 답변을 기다립니다.", Toast.LENGTH_LONG).show();
        } else {
            // 조건 불만족 시 일반 추가
            insertShoppingItem(name, quantity);
        }
    }

    // [서랍 DB 대조 쿼리]
    private boolean isAlreadyOwnedInDrawer(String itemName, int requestedQty) {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT quantity FROM drawer_items WHERE name = ?", 
                new String[]{itemName}
        );
        boolean result = false;
        if (cursor.moveToFirst()) {
            int ownedQty = cursor.getInt(0);
            if (ownedQty >= requestedQty) {
                result = true;
            }
        }
        cursor.close();
        return result;
    }

    private void insertShoppingItem(String name, int qty) {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("quantity", qty);
        
        db.insertWithOnConflict("shopping_items", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        
        // Reset Inputs
        itemNameInput.setText("");
        itemQuantityInput.setText("");
        
        refreshShoppingListUI();
        ttsStatusLabel.setText("쇼핑리스트 추가 완료");
    }

    private void refreshShoppingListUI() {
        listContainer.removeAllViews();
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT name, quantity FROM shopping_items", null);
        
        if (cursor.getCount() == 0) {
            TextView emptyText = new TextView(this);
            emptyText.setText("쇼핑리스트가 비어 있습니다.");
            emptyText.setTextColor(Color.rgb(150, 150, 150));
            emptyText.setTextSize(16);
            listContainer.addView(emptyText);
        } else {
            while (cursor.moveToNext()) {
                String name = cursor.getString(0);
                int qty = cursor.getInt(1);
                
                LinearLayout rowLayout = new LinearLayout(this);
                rowLayout.setOrientation(LinearLayout.HORIZONTAL);
                rowLayout.setPadding(0, dp(6), 0, dp(6));
                rowLayout.setGravity(Gravity.CENTER_VERTICAL);

                TextView itemText = new TextView(this);
                itemText.setText("• " + name + " (수량: " + qty + "개)");
                itemText.setTextColor(Color.WHITE);
                itemText.setTextSize(16);
                
                rowLayout.addView(itemText);
                listContainer.addView(rowLayout);
            }
        }
        cursor.close();
    }

    private void exportShoppingListAsQr() {
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT name, quantity FROM shopping_items", null);
        
        JSONArray itemsArray = new JSONArray();
        while (cursor.moveToNext()) {
            try {
                JSONObject obj = new JSONObject();
                obj.put("name", cursor.getString(0));
                obj.put("quantity", cursor.getInt(1));
                itemsArray.put(obj);
            } catch (Exception e) {
                Log.e(TAG, "JSON build error", e);
            }
        }
        cursor.close();

        if (itemsArray.length() == 0) {
            Toast.makeText(this, "내보낼 쇼핑리스트 목록이 비어 있습니다.", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            JSONObject root = new JSONObject();
            root.put("shopping_list", itemsArray);
            String jsonString = root.toString();
            
            // Generate QR Code bitmap from the JSON string
            Bitmap qrBitmap = createQrBitmap(jsonString, dp(260));
            if (qrBitmap != null) {
                qrImageView.setImageBitmap(qrBitmap);
                qrImageView.setVisibility(View.VISIBLE);
                Toast.makeText(this, "쇼핑리스트 QR 코드가 성공적으로 생성되었습니다.", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "QR 코드 생성 도중 오류가 발생했습니다.", Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Log.e(TAG, "QR export error", e);
        }
    }

    private Bitmap createQrBitmap(String text, int size) {
        try {
            BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            Log.e("QR_ERROR", "QR Code generation failed", e);
            return null;
        }
    }

    private void setupDummyDrawerItems() {
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        // Clear drawer items
        db.execSQL("DELETE FROM drawer_items");
        
        // Add sample inventory items to comparison DB
        insertDummyDrawerItem(db, "세제", 5);
        insertDummyDrawerItem(db, "수건", 10);
        insertDummyDrawerItem(db, "샴푸", 2);
    }

    private void insertDummyDrawerItem(SQLiteDatabase db, String name, int quantity) {
        ContentValues values = new ContentValues();
        values.put("name", name);
        values.put("quantity", quantity);
        db.insert("drawer_items", null, values);
    }

    // Helper to build modern card-style containers
    private LinearLayout createCard() {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(18), dp(18), dp(18));
        card.setBackgroundColor(Color.rgb(30, 30, 30));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(16);
        card.setLayoutParams(params);
        return card;
    }

    private EditText createEditText(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setHintTextColor(Color.rgb(120, 120, 120));
        editText.setTextColor(Color.WHITE);
        editText.setTextSize(16);
        editText.setPadding(dp(8), dp(12), dp(8), dp(12));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
        params.bottomMargin = dp(8);
        editText.setLayoutParams(params);
        return editText;
    }

    private Button createButton(String text, int color) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextColor(Color.WHITE);
        btn.setBackgroundColor(color);
        btn.setAllCaps(false);
        return btn;
    }

    private int dp(int value) {
        return (int) (value * getResources().getDisplayMetrics().density + 0.5f);
    }

    private LinearLayout.LayoutParams params(int width, int height, int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(width, height);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    @Override
    protected void onDestroy() {
        if (textToSpeech != null) {
            textToSpeech.stop();
            textToSpeech.shutdown();
        }
        if (speechRecognizer != null) {
            speechRecognizer.destroy();
        }
        super.onDestroy();
    }

    // Database helper class for storing local Shopping List & Drawer Inventory
    private static class ShoppingListDbHelper extends SQLiteOpenHelper {
        private static final String DATABASE_NAME = "shopping_list.db";
        private static final int DATABASE_VERSION = 1;

        public ShoppingListDbHelper(Context context) {
            super(context, DATABASE_NAME, null, DATABASE_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // Table for drawer items (Drawer management simulation)
            db.execSQL("CREATE TABLE drawer_items (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT UNIQUE, " +
                    "quantity INTEGER)");

            // Table for shopping list items
            db.execSQL("CREATE TABLE shopping_items (" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT, " +
                    "name TEXT UNIQUE, " +
                    "quantity INTEGER)");
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            db.execSQL("DROP TABLE IF EXISTS drawer_items");
            db.execSQL("DROP TABLE IF EXISTS shopping_items");
            onCreate(db);
        }
    }
}
