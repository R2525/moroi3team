package org.techtown.myapplication;

import android.graphics.Color;
import android.graphics.Bitmap;
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
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import com.robotemi.sdk.Robot;

import android.speech.tts.TextToSpeech;
import java.util.Locale;

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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends Activity implements Robot.AsrListener, Robot.WakeupWordListener {

    private static final int REQUEST_PICK_IMAGE = 1001;
    // --- Temi 내부 서버 주소 및 포트 자동바인딩 설정 ---
    // 에뮬레이터(Temi_UI_API_29)가 자기 자신(localhost) 안에서 구동 중인 API/DB 서비스를 호출할 때 쓰는 루프백 주소.
    // 안드로이드 에뮬레이터에서 10.0.2.2는 호스트 PC의 127.0.0.1로 매핑된다.
    private static final String FINAL_TEMI_SERVER_URL = "http://10.0.2.2:8000";
    private static final String DEFAULT_API_BASE_URL = FINAL_TEMI_SERVER_URL;
    // 실제 Temi(물리 기기)에서 쓸 기본 서버 주소. 10.0.2.2는 에뮬레이터 전용이라 실기기에서는 동작하지 않으므로,
    // API 서버(FastAPI/uvicorn)를 띄운 PC의 실제 공유기 LAN IP를 사용한다.
    private static final String DEFAULT_REAL_DEVICE_API_BASE_URL = "http://172.17.65.144:8000";
    // 외부 스마트폰 브라우저가 같은 공유기(Wi-Fi) 환경에서 QR을 찍고 들어와 사진을 입력할 "Temi 내부 DB 입력창" 주소.
    // 에뮬레이터 루프백 주소(10.0.2.2)와는 분리된 값으로, 설정 화면에서 실제 Temi가 할당받은 로컬 IP로 바꿀 수 있다.
    // 주의: 이 주소는 PC(API 서버)의 LAN IP여야 한다. Temi 본체의 IP를 넣으면 그 기기에는 서버가 없어 "사이트에 연결할 수 없음" 오류가 난다.
    private static final String DEFAULT_TEMI_DB_INPUT_WEB_URL = "http://172.17.65.144:8000/api/drawers/link?device=temi";
    private static final String PREFS_NAME = "temi_settings";
    private static final String PREF_API_BASE_URL = "api_base_url";
    private static final String PREF_TEMI_DB_INPUT_WEB_URL = "temi_db_input_web_url";

    // QR 연동 검증을 위한 폴링 제어 변수
    private final android.os.Handler linkPollingHandler = new android.os.Handler();
    private Runnable linkPollerRunnable;
    private boolean isPollingForLink = false;

    // 아두이노 -> Temi 내부 FastAPI DB -> 앱으로 이어지는 수납 센서 데이터를 주기적으로 가져오기 위한 폴링 제어 변수
    private static final long SENSOR_POLL_INTERVAL_MS = 3000;
    private final android.os.Handler sensorPollingHandler = new android.os.Handler();
    private Runnable sensorPollerRunnable;
    private boolean isSensorPolling = false;
    private int lastNotifiedDrawerNumber = -1;

    // 앱 실행 즉시(onCreate) 미리 구워두는 "Temi DB 입력창" QR 비트맵 캐시.
    // createQrCodeContainer()가 이 캐시를 먼저 확인하므로, 홈 화면 진입 시 Loading 없이 바로 렌더링된다.
    private volatile Bitmap cachedTemiDbInputQrBitmap;
    private volatile String cachedTemiDbInputQrUrl;

    private FrameLayout screenRoot;
    private Uri selectedImageUri;
    private final List<DetectedPhotoItem> detectedPhotoItems = new ArrayList<>();
    private String autoDrawerStatusText = "서랍 센서: 대기 중";
    private String apiBaseUrl = DEFAULT_API_BASE_URL;
    private String temiDbInputWebUrl = DEFAULT_TEMI_DB_INPUT_WEB_URL;
    private boolean isQrLinked = false;
    private boolean isPhotoUploading = false;
    private boolean isPhotoAnalyzing = false;
    private boolean shouldStartPlacementAfterSave = false;
    private int placementItemIndex = 0;
    private boolean isStoreTemiGuidanceActive = false;
    private int storeTemiGuidanceStep = 0;
    private boolean isShoppingListReceived = false;
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
    private final List<StoreTransferItem> storeTransferItems = new ArrayList<>();

    // 쇼핑리스트 음성 인터랙션(중복 구매 확인) 제어 변수
    private TextToSpeech temiTts;
    private boolean isTtsReady = false;
    private boolean isAwaitingDuplicateConfirmation = false;
    private String pendingDuplicateItemName;
    private int pendingDuplicateItemCount;
    private static final List<String> FORCE_ADD_VOICE_PHRASES = Arrays.asList(
            "그래도 추가할래", "그래도추가할래", "그래도 추가해줘", "그래도 추가해 줘",
            "추가해줘", "추가해 줘", "그래도 살래", "그래도 구매할래", "그래도 넣어줘", "그래도 넣어 줘");

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
        
        // 에뮬레이터에서는 10.0.2.2 루프백을 강제하고, 실제 Temi 기기에서는 저장된 LAN 주소(없으면 기본 LAN 주소)를 사용한다.
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String defaultApiBaseUrl = isRunningOnEmulator() ? FINAL_TEMI_SERVER_URL : DEFAULT_REAL_DEVICE_API_BASE_URL;
        apiBaseUrl = isRunningOnEmulator() ? FINAL_TEMI_SERVER_URL : prefs.getString(PREF_API_BASE_URL, defaultApiBaseUrl);
        temiDbInputWebUrl = prefs.getString(PREF_TEMI_DB_INPUT_WEB_URL, DEFAULT_TEMI_DB_INPUT_WEB_URL);
        prefs.edit()
                .putString(PREF_API_BASE_URL, apiBaseUrl)
                .putString(PREF_TEMI_DB_INPUT_WEB_URL, temiDbInputWebUrl)
                .apply();

        // 서버 연결 상태 자동 체크 (Health Check)
        checkServerHealth();

        // 앱이 켜지자마자 Temi DB 입력창 QR을 미리 구워서 캐싱 (홈 화면 진입 시 Loading 없이 즉시 표시)
        prewarmTemiDbInputQrCode();

        // 아두이노가 Temi 내부 DB 서버에 적재한 수납 센서 데이터를 앱이 주기적으로 가져오도록 폴링 시작
        startSensorPolling();

        // "Temi야~ 칫솔 4개 사야해" 같은 음성 명령으로 쇼핑 리스트에 자동 등록하기 위해 Temi 음성 리스너 등록
        if (!isRunningOnEmulator()) {
            Robot.getInstance().addAsrListener(this);
            Robot.getInstance().addWakeupWordListener(this);
        }

        // 중복 구매 안내("이미 구매된 물건입니다")를 음성으로 출력하기 위한 안드로이드 TTS 초기화
        temiTts = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if (status == TextToSpeech.SUCCESS && temiTts != null) {
                    temiTts.setLanguage(Locale.KOREAN);
                    isTtsReady = true;
                }
            }
        });

        showHome();
    }

    private boolean isRunningOnEmulator() {
        String fingerprint = android.os.Build.FINGERPRINT;
        String hardware = android.os.Build.HARDWARE;
        String model = android.os.Build.MODEL;
        return fingerprint.startsWith("generic")
                || fingerprint.startsWith("unknown")
                || hardware.contains("goldfish")
                || hardware.contains("ranchu")
                || model.contains("Emulator")
                || model.contains("Android SDK built for");
    }

    private void prewarmTemiDbInputQrCode() {
        final String linkUrl = temiDbInputWebUrl;
        final int size = dp(260);
        new Thread(new Runnable() {
            @Override public void run() {
                Bitmap qrBitmap = createQrBitmap(linkUrl, size);
                if (qrBitmap != null) {
                    cachedTemiDbInputQrBitmap = qrBitmap;
                    cachedTemiDbInputQrUrl = linkUrl;
                }
            }
        }).start();
    }

    private void checkServerHealth() {
        requestJson("GET", "/api/health", null, new ApiCallback() {
            @Override
            public void onSuccess(JSONObject response) {
                if (response != null && "ok".equals(response.optString("status"))) {
                    Log.d("TemiApi", "Temi 서버 연결 성공");
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(MainActivity.this, "Temi 서버 연결 성공 (Health Check OK)", Toast.LENGTH_SHORT).show();
                        }
                    });
                }
            }

            @Override
            public void onFailure(String message) {
                Log.e("TemiApi", "Temi 서버 연결 실패: " + message);
            }
        });
    }

    @Override
    protected void onDestroy() {
        stopLinkPolling();
        stopSensorPolling();
        if (!isRunningOnEmulator()) {
            Robot.getInstance().removeAsrListener(this);
            Robot.getInstance().removeWakeupWordListener(this);
        }
        if (temiTts != null) {
            temiTts.stop();
            temiTts.shutdown();
            temiTts = null;
        }
        super.onDestroy();
    }

    // Temi가 한국어 문장을 음성으로 출력한다 (TTS 미준비 상태면 조용히 무시).
    private void speak(String text) {
        if (temiTts != null && isTtsReady && text != null && text.length() > 0) {
            temiTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "temi_tts_" + text.hashCode());
        }
    }

    // 사용자가 "Temi야~"라고 부르면 호출됨 (호출 자체는 Toast로만 알리고, 실제 명령 처리는 onAsrResult에서 처리)
    @Override
    public void onWakeupWord(String wakeupWord, int direction) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(MainActivity.this, "듣고 있어요...", Toast.LENGTH_SHORT).show();
            }
        });
    }

    // 웨이크워드 이후 실제로 말한 문장이 인식되면 호출됨.
    // 중복 구매 확인 응답을 기다리는 중이면 그 답변으로 처리하고, 그렇지 않으면 "물품명 N개" 패턴의 쇼핑 등록 명령으로 처리한다.
    @Override
    public void onAsrResult(final String asrResult) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                if (isAwaitingDuplicateConfirmation) {
                    handleDuplicateConfirmationAnswer(asrResult);
                } else {
                    handleVoiceShoppingRequest(asrResult);
                }
            }
        });
    }

    // "이미 구매된 물건입니다" 음성 안내 직후의 사용자 답변을 처리한다.
    // "그래도 추가할래" / "추가해 줘" 류의 발화면 강제로 쇼핑리스트에 INSERT하고, 그 외(취소 등)면 저장을 취소한다.
    private void handleDuplicateConfirmationAnswer(String asrResult) {
        if (!isAwaitingDuplicateConfirmation) {
            return;
        }
        isAwaitingDuplicateConfirmation = false;

        String normalizedAnswer = asrResult == null ? "" : asrResult.trim().replace(" ", "");
        boolean forceAdd = false;
        for (String phrase : FORCE_ADD_VOICE_PHRASES) {
            if (normalizedAnswer.contains(phrase.replace(" ", ""))) {
                forceAdd = true;
                break;
            }
        }

        String itemName = pendingDuplicateItemName;
        int itemCount = pendingDuplicateItemCount;
        pendingDuplicateItemName = null;
        pendingDuplicateItemCount = 0;

        if (forceAdd && itemName != null) {
            Toast.makeText(this, "음성 확인: \"" + asrResult + "\" → 그대로 추가합니다.", Toast.LENGTH_SHORT).show();
            addShoppingItemToServer(itemName, itemCount);
        } else {
            speak("추가를 취소했습니다");
            Toast.makeText(this, "추가를 취소했습니다: \"" + asrResult + "\"", Toast.LENGTH_SHORT).show();
        }
    }

    private static final Pattern VOICE_SHOPPING_ITEM_PATTERN =
            Pattern.compile("([가-힣A-Za-z0-9]+)\\s*(\\d+)\\s*개");

    // "칫솔 4개 사야해", "물 2개 사줘" 같은 자유 발화에서 (물품명, 개수)를 뽑아 쇼핑 리스트 서버에 추가한다.
    private void handleVoiceShoppingRequest(String asrResult) {
        if (asrResult == null) {
            return;
        }
        String text = asrResult.trim();
        if (text.isEmpty()) {
            return;
        }

        Matcher matcher = VOICE_SHOPPING_ITEM_PATTERN.matcher(text);
        if (!matcher.find()) {
            Toast.makeText(this, "음성에서 물품/개수를 인식하지 못했습니다: \"" + text + "\"", Toast.LENGTH_LONG).show();
            return;
        }

        String itemName = matcher.group(1).trim();
        int quantity;
        try {
            quantity = Integer.parseInt(matcher.group(2));
        } catch (NumberFormatException error) {
            quantity = 1;
        }
        if (quantity <= 0) {
            quantity = 1;
        }

        Toast.makeText(this, "음성 인식: \"" + text + "\" → " + itemName + " " + quantity + "개 추가 중...", Toast.LENGTH_SHORT).show();
        addShoppingItemToServer(itemName, quantity);
    }

    private void startSensorPolling() {
        if (isSensorPolling) return;
        isSensorPolling = true;
        sensorPollerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isSensorPolling) return;
                fetchLatestDrawerNumber(new DrawerNumberCallback() {
                    @Override
                    public void onSuccess(int drawerNumber) {
                        String updatedStatusText = "서랍 센서: " + drawerNumber + "번 서랍 감지";
                        if (drawerNumber != lastNotifiedDrawerNumber) {
                            lastNotifiedDrawerNumber = drawerNumber;
                            Log.d("TemiApi", "아두이노 -> Temi DB 갱신 감지: " + updatedStatusText);
                        }
                        autoDrawerStatusText = updatedStatusText;
                        if (isSensorPolling) {
                            sensorPollingHandler.postDelayed(sensorPollerRunnable, SENSOR_POLL_INTERVAL_MS);
                        }
                    }

                    @Override
                    public void onFailure(String message) {
                        Log.e("TemiApi", "아두이노 센서 데이터 폴링 실패: " + message);
                        if (isSensorPolling) {
                            sensorPollingHandler.postDelayed(sensorPollerRunnable, SENSOR_POLL_INTERVAL_MS);
                        }
                    }
                });
            }
        };
        sensorPollingHandler.post(sensorPollerRunnable);
    }

    private void stopSensorPolling() {
        isSensorPolling = false;
        if (sensorPollerRunnable != null) {
            sensorPollingHandler.removeCallbacks(sensorPollerRunnable);
            sensorPollerRunnable = null;
        }
    }

    private void showHome() {
        LinearLayout page = page("가정 Temi", "수납, 쇼핑리스트, DB 조회, Temi 연결을 한 곳에서 관리합니다.");

        LinearLayout status = card();
        status.addView(label("연결 상태"));
        status.addView(body("가정 Temi: 미연결"));
        status.addView(body("매장 Temi: 미연결"));
        status.addView(body("서랍: 1개 연결 대기"));
        page.addView(status);

        page.addView(navButton("서랍관리", "QR 연동, 카메라 촬영, 수동 추가로 서랍 수납을 관리합니다.", new View.OnClickListener() {
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
        // QR 연동, 사진 분석, 수동 입력을 한 화면에 모두 보여준다 (단계 구분 없음).
        LinearLayout page = page("서랍관리", "QR 연동, 사진 분석, 수동 입력으로 서랍 수납을 관리합니다.");
        page.addView(backTo("홈으로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) {
                stopLinkPolling();
                showHome();
            }
        }));

        LinearLayout qrCard = card();
        qrCard.addView(label("Temi QR 스캔"));
        qrCard.addView(body(isQrLinked
                ? "서랍 연동이 완료되었습니다. 스마트폰으로 계속 사진을 보내 등록할 수 있습니다."
                : "스마트폰으로 아래 QR 코드를 찍어서 Temi 내부 서버에 접속하면 연동이 자동으로 완료됩니다."));
        page.addView(qrCard);
        // 외부 스마트폰이 스캔할 주소이므로 에뮬레이터 루프백(apiBaseUrl)이 아닌 temiDbInputWebUrl을 사용한다.
        page.addView(createQrCodeContainer("서랍 연동", temiDbInputWebUrl));
        startLinkPolling();

        LinearLayout statusCard = card();
        statusCard.addView(label("사진으로 등록"));
        statusCard.addView(body(selectedImageUri == null ? "이미지 선택 대기 중" : "선택된 이미지: " + selectedImageUri));

        String uploadText = "사진 업로드 상태: ";
        if (isPhotoUploading) uploadText += "업로드 중...";
        else if (selectedImageUri != null) uploadText += "업로드 완료";
        else uploadText += "대기 중";
        statusCard.addView(body(uploadText));

        String analysisText = "분석 상태: ";
        if (isPhotoAnalyzing) analysisText += "분석 중...";
        else if (selectedImageUri != null) analysisText += "분석 대기 중";
        else analysisText += "분석 준비";
        statusCard.addView(body(analysisText));

        if (isPhotoUploading || isPhotoAnalyzing) {
            ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyleLarge);
            statusCard.addView(progressBar, params(-2, -2, 0, 12, 0, 12));
        }
        page.addView(statusCard);

        LinearLayout actions = row();
        actions.addView(button("갤러리 선택", true, (isPhotoUploading || isPhotoAnalyzing) ? null : new View.OnClickListener() {
            @Override public void onClick(View v) { openGallery(); }
        }), weightParams());

        actions.addView(button("분석 요청", false, (isPhotoUploading || isPhotoAnalyzing) ? null : new View.OnClickListener() {
            @Override public void onClick(View v) { analyzeSelectedPhoto(); }
        }), weightParams());
        page.addView(actions);

        if (!detectedPhotoItems.isEmpty()) {
            LinearLayout analysisResult = card();
            analysisResult.addView(label("Gemini 분석 결과 - " + detectedPhotoItems.size() + "개"));
            analysisResult.addView(body("검출된 물품과 수량을 확인하고 [물건 넣기 시작]을 누르세요."));
            for (int i = 0; i < detectedPhotoItems.size(); i++) {
                analysisResult.addView(detectedPhotoItemEditor(detectedPhotoItems.get(i), i));
            }
            analysisResult.addView(button("물건 넣기 시작", true, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    placementItemIndex = 0;
                    shouldStartPlacementAfterSave = true;
                    saveDetectedItemsWithAutoDrawer();
                }
            }));
            page.addView(analysisResult);
        }

        LinearLayout manualAddCard = card();
        manualAddCard.addView(label("수동 추가"));
        manualAddCard.addView(body("사진 없이 물품명과 서랍 번호를 직접 입력해 바로 저장합니다."));
        final EditText manualItemNameInput = input("물품명입력");
        final EditText manualQuantityInput = input("개수입력");
        manualQuantityInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        final EditText manualDrawerNumberInput = input("서랍번호입력");
        manualDrawerNumberInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        manualAddCard.addView(manualItemNameInput);
        manualAddCard.addView(manualQuantityInput);
        manualAddCard.addView(manualDrawerNumberInput);
        manualAddCard.addView(button("직접 추가", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveDbItem(manualItemNameInput, manualQuantityInput, manualDrawerNumberInput, new Runnable() {
                    @Override public void run() { showCamera(); }
                });
            }
        }));
        page.addView(manualAddCard);

        setScreen(page);
    }

    private Bitmap createQrBitmap(String text, int size) {
        try {
            // 쇼핑리스트 QR에는 한글 물품명이 들어가므로, UTF-8로 인코딩해야 매장 Temi가 스캔했을 때 깨지지 않는다.
            Map<com.google.zxing.EncodeHintType, Object> hints = new HashMap<>();
            hints.put(com.google.zxing.EncodeHintType.CHARACTER_SET, "UTF-8");
            BitMatrix matrix = new MultiFormatWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints);
            Bitmap bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.RGB_565);
            for (int x = 0; x < size; x++) {
                for (int y = 0; y < size; y++) {
                    bitmap.setPixel(x, y, matrix.get(x, y) ? Color.BLACK : Color.WHITE);
                }
            }
            return bitmap;
        } catch (Exception e) {
            Log.e("QR_ERROR", "QR Code generation failed in createQrBitmap", e);
            return null;
        }
    }

    private void startLinkPolling() {
        if (isPollingForLink) return;
        isPollingForLink = true;
        linkPollerRunnable = new Runnable() {
            @Override
            public void run() {
                if (!isPollingForLink) return;
                checkDrawerLinkStatus(new ApiCallback() {
                    @Override
                    public void onSuccess(JSONObject response) {
                        boolean linked = response.optBoolean("linked", false);
                        if (linked) {
                            isQrLinked = true;
                            stopLinkPolling();
                            Toast.makeText(MainActivity.this, "서랍 연동이 완료되었습니다.", Toast.LENGTH_SHORT).show();
                            showCamera();
                        } else {
                            if (isPollingForLink) {
                                linkPollingHandler.postDelayed(linkPollerRunnable, 2000);
                            }
                        }
                    }

                    @Override
                    public void onFailure(String message) {
                        if (isPollingForLink) {
                            linkPollingHandler.postDelayed(linkPollerRunnable, 2000);
                        }
                    }
                });
            }
        };
        linkPollingHandler.post(linkPollerRunnable);
    }

    private void stopLinkPolling() {
        isPollingForLink = false;
        if (linkPollerRunnable != null) {
            linkPollingHandler.removeCallbacks(linkPollerRunnable);
            linkPollerRunnable = null;
        }
    }

    private void checkDrawerLinkStatus(ApiCallback callback) {
        requestJson("GET", "/api/drawers/link-status", null, callback);
    }

    private View createQrCodeContainer(final String title, final String linkUrl) {
        final LinearLayout qrLayout = new LinearLayout(this);
        qrLayout.setOrientation(LinearLayout.VERTICAL);
        qrLayout.setGravity(Gravity.CENTER);
        qrLayout.setPadding(dp(16), dp(16), dp(16), dp(16));
        qrLayout.setBackgroundResource(R.drawable.temi_card);
        qrLayout.setLayoutParams(params(-1, -2, 0, 0, 0, 16));

        TextView titleLabel = new TextView(this);
        titleLabel.setText(title);
        titleLabel.setGravity(Gravity.CENTER);
        titleLabel.setTextColor(getColorCompat(R.color.temi_text));
        titleLabel.setTextSize(22);
        titleLabel.setTypeface(null, 1);
        qrLayout.addView(titleLabel, params(-1, -2, 0, 0, 0, 12));

        // Loading container showing progress spinner and loading text
        final LinearLayout loadingContainer = new LinearLayout(this);
        loadingContainer.setOrientation(LinearLayout.VERTICAL);
        loadingContainer.setGravity(Gravity.CENTER);
        loadingContainer.setLayoutParams(new LinearLayout.LayoutParams(dp(260), dp(260)));

        ProgressBar progressBar = new ProgressBar(this, null, android.R.attr.progressBarStyle);
        loadingContainer.addView(progressBar);

        TextView loadingLabel = new TextView(this);
        loadingLabel.setText("Loading...");
        loadingLabel.setGravity(Gravity.CENTER);
        loadingLabel.setTextColor(getColorCompat(R.color.temi_muted));
        loadingLabel.setTextSize(16);
        loadingLabel.setPadding(0, dp(8), 0, 0);
        loadingContainer.addView(loadingLabel);

        qrLayout.addView(loadingContainer);

        // QR Image (initially GONE)
        final ImageView qrImage = new ImageView(this);
        qrImage.setBackgroundColor(Color.WHITE);
        int padding = dp(12);
        qrImage.setPadding(padding, padding, padding, padding);
        qrImage.setVisibility(View.GONE);
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(dp(260), dp(260));
        imageParams.gravity = Gravity.CENTER;
        qrLayout.addView(qrImage, imageParams);

        // Helper label (initially GONE)
        final TextView helperLabel = new TextView(this);
        helperLabel.setText("스마트폰으로 QR 코드를 스캔하세요.\n연동 URL: " + linkUrl);
        helperLabel.setGravity(Gravity.CENTER);
        helperLabel.setTextColor(getColorCompat(R.color.temi_muted));
        helperLabel.setTextSize(16);
        helperLabel.setPadding(0, dp(12), 0, 0);
        helperLabel.setVisibility(View.GONE);
        qrLayout.addView(helperLabel);

        // Error message label (initially GONE)
        final TextView errorLabel = new TextView(this);
        errorLabel.setGravity(Gravity.CENTER);
        errorLabel.setTextColor(Color.RED);
        errorLabel.setTextSize(18);
        errorLabel.setTypeface(null, 1);
        errorLabel.setPadding(dp(16), dp(16), dp(16), dp(16));
        errorLabel.setVisibility(View.GONE);
        qrLayout.addView(errorLabel);

        // onCreate에서 미리 구워둔 캐시가 이 linkUrl과 일치하면 즉시 사용 (Loading 스킵)
        if (linkUrl.equals(cachedTemiDbInputQrUrl) && cachedTemiDbInputQrBitmap != null) {
            loadingContainer.setVisibility(View.GONE);
            qrImage.setImageBitmap(cachedTemiDbInputQrBitmap);
            qrImage.setVisibility(View.VISIBLE);
            helperLabel.setVisibility(View.VISIBLE);
            return qrLayout;
        }

        // Asynchronously generate QR bitmap to keep UI smooth and avoid ANRs
        final int size = dp(260);
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    // linkUrl 주소를 기반으로 QR 비트맵 생성
                    final Bitmap qrBitmap = createQrBitmap(linkUrl, size);
                    if (qrBitmap != null) {
                        if (linkUrl.equals(temiDbInputWebUrl)) {
                            cachedTemiDbInputQrBitmap = qrBitmap;
                            cachedTemiDbInputQrUrl = linkUrl;
                        }
                        runOnUiThread(new Runnable() {
                            @Override
                            public void run() {
                                loadingContainer.setVisibility(View.GONE);
                                qrImage.setImageBitmap(qrBitmap);
                                qrImage.setVisibility(View.VISIBLE);
                                helperLabel.setVisibility(View.VISIBLE);
                            }
                        });
                    } else {
                        throw new Exception("QR Code Bitmap is null");
                    }
                } catch (final Exception e) {
                    Log.e("QR_ERROR", "Error generating QR Code", e);
                    runOnUiThread(new Runnable() {
                        @Override
                        public void run() {
                            loadingContainer.setVisibility(View.GONE);
                            errorLabel.setText("⚠️ QR 생성 실패!\n서버 주소가 올바르지 않거나 생성 중 오류가 발생했습니다.\n설정에서 서버 주소를 확인해주세요.\n연동 시도 주소: " + linkUrl);
                            errorLabel.setVisibility(View.VISIBLE);
                        }
                    });
                }
            }
        }).start();

        return qrLayout;
    }

    private LinearLayout detectedPhotoItemEditor(final DetectedPhotoItem item, final int index) {
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
                final String newName = itemNameInput.getText().toString().trim();
                final int newQty = parsePositiveNumber(quantityInput.getText().toString().trim(), 1, "개수");
                if (newQty <= 0) return;

                fetchLatestDrawerNumber(new DrawerNumberCallback() {
                    @Override public void onSuccess(final int drawerNumber) {
                        saveDbItemValues(newName, newQty, drawerNumber, new ApiCallback() {
                            @Override public void onSuccess(JSONObject response) {
                                DetectedPhotoItem updated = new DetectedPhotoItem(item.id, newName, newQty, item.confidence);
                                updated.assignedDrawerNumber = drawerNumber;
                                detectedPhotoItems.set(index, updated);

                                dbStatusText = "DB 서버: 마지막 저장 성공";
                                Toast.makeText(MainActivity.this, "DB 서버에 저장했습니다.", Toast.LENGTH_SHORT).show();
                                showCamera();
                            }
                            @Override public void onFailure(String message) {
                                Toast.makeText(MainActivity.this, "DB 저장 실패: " + message, Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                    @Override public void onFailure(String message) {
                        Toast.makeText(MainActivity.this, "서랍 센서 정보를 받아올 수 없습니다: " + message, Toast.LENGTH_LONG).show();
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
        Intent intent = new Intent(Intent.ACTION_PICK);
        intent.setType("image/*");
        try {
            startActivityForResult(intent, REQUEST_PICK_IMAGE);
        } catch (ActivityNotFoundException openMissing) {
            Intent fallback = new Intent(Intent.ACTION_GET_CONTENT);
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
            Toast.makeText(this, "갤러리 이미지를 선택했습니다. 업로드 및 분석을 시작합니다.", Toast.LENGTH_SHORT).show();
            analyzeSelectedPhoto();
        }
    }

    private void analyzeSelectedPhoto() {
        if (selectedImageUri == null) {
            Toast.makeText(this, "이미지를 선택한 뒤 분석을 요청하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        isPhotoUploading = true;
        isPhotoAnalyzing = false;
        showCamera();
        uploadPhotoForAnalysis(selectedImageUri, new ApiCallback() {
            @Override public void onSuccess(JSONObject response) {
                isPhotoUploading = false;
                isPhotoAnalyzing = false;
                detectedPhotoItems.clear();
                detectedPhotoItems.addAll(parseDetectedPhotoItems(response));
                if (detectedPhotoItems.isEmpty()) {
                    Toast.makeText(MainActivity.this, "인식된 물품이 없습니다.", Toast.LENGTH_LONG).show();
                } else {
                    Toast.makeText(MainActivity.this, "Gemini가 " + detectedPhotoItems.size() + "개 물품을 인식했습니다.", Toast.LENGTH_SHORT).show();
                }
                showCamera();
            }

            @Override public void onFailure(String message) {
                isPhotoUploading = false;
                isPhotoAnalyzing = false;
                Toast.makeText(MainActivity.this, "사진 분석 실패: " + message, Toast.LENGTH_LONG).show();
                showCamera();
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

                    runOnUiThread(new Runnable() {
                        @Override public void run() {
                            isPhotoUploading = false;
                            isPhotoAnalyzing = true;
                            showCamera();
                        }
                    });

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

        page.addView(button("입력 완료", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showShoppingListName(); }
        }));

        page.addView(button("작성 완료 (QR로 내보내기)", false, new View.OnClickListener() {
            @Override public void onClick(View v) { showShoppingListQrExport(); }
        }));

        setScreen(page);
    }

    // [쇼핑리스트 QR 내보내기] 현재 쇼핑리스트 DB(모든 사용자 카트)에 담긴 전체 목록을 JSON 문자열로 압축하고,
    // 그 문자열을 QR 코드로 그려서 화면에 띄운다. 사용자는 이 QR을 폰 카메라로 찍어 갤러리에 저장하고,
    // 추후 매장 Temi가 그 사진을 스캔해서 쇼핑리스트를 읽는 데 사용한다.
    private void showShoppingListQrExport() {
        LinearLayout page = page("쇼핑리스트 QR 내보내기", "현재 쇼핑리스트 전체를 QR 코드 하나로 압축해서 보여줍니다.");
        page.addView(backTo("쇼핑리스트로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { showShoppingInput(); }
        }));

        String shoppingListJson = buildShoppingListJson();

        LinearLayout card = card();
        card.addView(label("쇼핑리스트 QR"));
        card.addView(body("이 QR을 스마트폰으로 찍어 갤러리에 저장해두세요. 매장 Temi가 이 사진을 스캔해서 쇼핑리스트를 확인합니다."));

        if (totalCartItemCount() == 0) {
            card.addView(body("내보낼 쇼핑리스트 항목이 없습니다."));
        } else {
            ImageView qrImageView = new ImageView(this);
            int size = dp(260);
            Bitmap qrBitmap = createQrBitmap(shoppingListJson, size);
            if (qrBitmap != null) {
                qrImageView.setImageBitmap(qrBitmap);
            } else {
                card.addView(body("QR 코드 생성에 실패했습니다."));
            }
            LinearLayout.LayoutParams qrParams = new LinearLayout.LayoutParams(size, size);
            qrParams.gravity = Gravity.CENTER;
            qrParams.topMargin = dp(12);
            card.addView(qrImageView, qrParams);
        }
        page.addView(card);

        setScreen(page);
    }

    // 모든 사용자의 카트(cartItemsByUser)를 합쳐 {"shopping_list":[{"user":..,"name":..,"quantity":..}, ...]} 형태의 JSON으로 만든다.
    private String buildShoppingListJson() {
        JSONArray itemsArray = new JSONArray();
        for (int userIndex = 0; userIndex < cartItemsByUser.size(); userIndex++) {
            String userName = userIndex < shoppingUsers.size() ? shoppingUsers.get(userIndex) : ("사용자 " + (userIndex + 1));
            for (String cartItemText : cartItemsByUser.get(userIndex)) {
                ItemQuantity parsed = parseItemQuantity(cartItemText);
                try {
                    JSONObject itemObject = new JSONObject();
                    itemObject.put("user", userName);
                    itemObject.put("name", parsed.name);
                    itemObject.put("quantity", parsed.quantity);
                    itemsArray.put(itemObject);
                } catch (JSONException ignored) {
                    // 개별 항목 변환 실패는 건너뛰고 나머지 항목으로 계속 진행한다.
                }
            }
        }

        try {
            JSONObject root = new JSONObject();
            root.put("shopping_list", itemsArray);
            return root.toString();
        } catch (JSONException error) {
            return "{\"shopping_list\":[]}";
        }
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
        LinearLayout page = page("최종 쇼핑 리스트", "DB에 없는 구매 필요 항목만 정리한 최종 리스트입니다.");
        page.addView(backTo("입력으로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { showShoppingInput(); }
        }));

        if (integratedShoppingLists.isEmpty()) {
            page.addView(body("생성된 통합 리스트가 없습니다."));
            setScreen(page);
            return;
        }

        IntegratedShoppingList activeList = selectedIntegratedListIndex >= 0 && selectedIntegratedListIndex < integratedShoppingLists.size()
                ? integratedShoppingLists.get(selectedIntegratedListIndex)
                : integratedShoppingLists.get(integratedShoppingLists.size() - 1);

        LinearLayout summary = card();
        summary.addView(label("최종 구매 필요 항목 - " + activeList.items.size() + "개"));
        if (activeList.items.isEmpty()) {
            summary.addView(body("구매할 품목이 없습니다. (모든 품목 보유 중)"));
        } else {
            for (String item : activeList.items) {
                summary.addView(listItem(item, "구매 필요"));
            }
        }
        page.addView(summary);

        LinearLayout actions = row();
        actions.addView(button("저장", true, new View.OnClickListener() {
            @Override public void onClick(View v) { showStoreTemi(); }
        }));
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
            handleDuplicateItemDetected(finalItemNameForPurchase, finalItemCountForPurchase);
            return;
        }

        addShoppingItemToServer(itemName, itemCount);
    }

    // [서랍 관리 DB 대조] 서랍에 남은 같은 물건의 개수가 요청 개수 이상이면 저장을 보류하고
    // 음성(TTS)으로 "이미 구매된 물건입니다"를 안내한 뒤, 음성 답변("그래도 추가할래" 등)을 기다린다.
    // (화면 터치로도 같은 결과를 낼 수 있도록 기존 "+ 추가 구매" 버튼도 함께 보여준다.)
    private void handleDuplicateItemDetected(final String itemName, final int requestedQuantity) {
        pendingDuplicateItemName = itemName;
        pendingDuplicateItemCount = requestedQuantity;
        isAwaitingDuplicateConfirmation = true;

        speak("이미 구매된 물건입니다");

        showAlreadyOwnedNotice(new View.OnClickListener() {
            @Override public void onClick(View v) {
                isAwaitingDuplicateConfirmation = false;
                pendingDuplicateItemName = null;
                pendingDuplicateItemCount = 0;
                addShoppingItemToServer(itemName, requestedQuantity);
            }
        });
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
                showShoppingSave();
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
        int assignedDrawerNumber = 1;

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

    private static class StoreTransferItem {
        final String itemName;
        final int quantity;
        final boolean inStock;
        final String section;
        final String aisle;
        final String shelf;

        StoreTransferItem(String itemName, int quantity, boolean inStock, String section, String aisle, String shelf) {
            this.itemName = itemName;
            this.quantity = quantity;
            this.inStock = inStock;
            this.section = section;
            this.aisle = aisle;
            this.shelf = shelf;
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

        for (final DetectedPhotoItem item : detectedPhotoItems) {
            item.assignedDrawerNumber = drawerNumber;
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
        if (shouldStartPlacementAfterSave) {
            shouldStartPlacementAfterSave = false;
            placementItemIndex = 0;
            showPlacementGuide();
        } else {
            showCamera();
        }
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

    private void showPlacementGuide() {
        if (detectedPhotoItems.isEmpty()) {
            Toast.makeText(this, "수납할 물품이 없습니다.", Toast.LENGTH_SHORT).show();
            showHome();
            return;
        }

        LinearLayout page = page("물건 넣기", "물건을 순서대로 서랍에 넣어주세요.");
        page.addView(backButton());

        DetectedPhotoItem currentItem = detectedPhotoItems.get(Math.min(placementItemIndex, detectedPhotoItems.size() - 1));
        LinearLayout currentCard = card();
        currentCard.addView(label("현재 물건"));
        currentCard.addView(big(currentItem.name + " " + currentItem.quantity + "개"));
        currentCard.addView(label("서랍 위치"));
        currentCard.addView(big(currentItem.assignedDrawerNumber + "번 서랍"));
        page.addView(currentCard);

        LinearLayout progressCard = card();
        progressCard.addView(label("진행 상태"));
        progressCard.addView(body("총 " + detectedPhotoItems.size() + "개 중 " + (placementItemIndex + 1) + "번째 진행 중"));
        page.addView(progressCard);

        LinearLayout actions = row();
        if (placementItemIndex < detectedPhotoItems.size() - 1) {
            actions.addView(button("다음 물건", true, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    placementItemIndex++;
                    showPlacementGuide();
                }
            }));
        } else {
            actions.addView(button("완료", true, new View.OnClickListener() {
                @Override public void onClick(View v) {
                    showPlacementComplete();
                }
            }));
        }
        page.addView(actions);
        setScreen(page);
    }

    private void showPlacementComplete() {
        LinearLayout page = page("수납 완료", "모든 물건 넣기가 완료되었습니다.");
        page.addView(body("홈으로 돌아갑니다."));
        page.addView(button("홈으로", true, new View.OnClickListener() {
            @Override public void onClick(View v) {
                placementItemIndex = 0;
                detectedPhotoItems.clear();
                showHome();
            }
        }));
        setScreen(page);
    }

    private void startStoreTemiGuidance() {
        if (integratedShoppingLists.isEmpty()) {
            Toast.makeText(this, "전송할 리스트가 없습니다.", Toast.LENGTH_SHORT).show();
            showStoreTemi();
            return;
        }
        isStoreTemiGuidanceActive = true;
        storeTemiGuidanceStep = 0;
        showStoreTemi();
    }

    private void advanceStoreTemiGuidance() {
        storeTemiGuidanceStep++;
        showStoreTemi();
    }

    private List<String> availableStoreTemiItems() {
        List<String> availableItems = new ArrayList<>();
        for (StoreTransferItem item : storeTransferItems) {
            if (item.inStock) {
                availableItems.add(item.itemName + " " + item.quantity + "개 (" + item.section + " " + item.aisle + "구역 " + item.shelf + "열)");
            }
        }
        return availableItems;
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

        LinearLayout qrLinkCard = card();
        qrLinkCard.addView(label("Temi DB 입력창 QR 주소"));
        qrLinkCard.addView(body("스마트폰이 같은 공유기(Wi-Fi)에서 접속할 주소입니다. 현재 주소: " + temiDbInputWebUrl));
        final EditText qrLinkUrlInput = input("예: http://192.168.0.10:8000/api/drawers/link?device=temi");
        qrLinkUrlInput.setText(temiDbInputWebUrl);
        qrLinkCard.addView(qrLinkUrlInput);
        qrLinkCard.addView(button("QR 주소 저장", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                saveTemiDbInputWebUrl(qrLinkUrlInput);
            }
        }));
        page.addView(qrLinkCard);

        page.addView(navButton("가정 Temi", "집에서 사용하는 Temi를 QR로 연결합니다.", new View.OnClickListener() {
            @Override public void onClick(View v) { showHomeTemi(); }
        }));
        page.addView(navButton("매장 Temi", "저장된 쇼핑리스트를 매장 Temi로 전송합니다.", new View.OnClickListener() {
            @Override public void onClick(View v) { showStoreTemi(); }
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

    private void saveTemiDbInputWebUrl(EditText qrLinkUrlInput) {
        String value = normalizeApiBaseUrl(qrLinkUrlInput.getText().toString().trim());
        if (value.length() == 0) {
            Toast.makeText(this, "QR 주소를 입력해주세요.", Toast.LENGTH_SHORT).show();
            return;
        }

        temiDbInputWebUrl = value;
        SharedPreferences.Editor editor = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit();
        editor.putString(PREF_TEMI_DB_INPUT_WEB_URL, temiDbInputWebUrl);
        editor.apply();
        // 주소가 바뀌었으므로 캐시를 비우고 새 QR을 미리 다시 구워둔다.
        cachedTemiDbInputQrBitmap = null;
        cachedTemiDbInputQrUrl = null;
        prewarmTemiDbInputQrCode();
        Toast.makeText(this, "Temi DB 입력창 QR 주소를 저장했습니다.", Toast.LENGTH_SHORT).show();
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
        LinearLayout page = page("매장 Temi 안내", "매장 Temi에 쇼핑리스트를 전달하고 재고 있는 물품만 안내합니다.");
        page.addView(backTo("설정으로 돌아가기", new View.OnClickListener() {
            @Override public void onClick(View v) { showSettings(); }
        }));
        page.addView(qrPanel("검색된 Temi: STORE-TEMI-03"));

        if (selectedIntegratedListIndex < 0 && !integratedShoppingLists.isEmpty()) {
            selectedIntegratedListIndex = 0;
        }

        LinearLayout savedLists = card();
        savedLists.addView(label("통합 쇼핑 리스트"));
        if (integratedShoppingLists.isEmpty()) {
            savedLists.addView(body("전송할 통합 리스트가 없습니다."));
        } else {
            IntegratedShoppingList activeList = integratedShoppingLists.get(selectedIntegratedListIndex);
            savedLists.addView(label("선택된 리스트: " + activeList.title + " (ID " + activeList.id + ")"));
            savedLists.addView(body("요청 항목 " + activeList.requestedItems.size() + "개, 실제 구매 항목 " + activeList.items.size() + "개"));
            for (int i = 0; i < integratedShoppingLists.size(); i++) {
                savedLists.addView(integratedListRow(integratedShoppingLists.get(i), i));
            }
        }
        page.addView(savedLists);

        if (!isStoreTemiGuidanceActive) {
            if (!isShoppingListReceived) {
                page.addView(button("쇼핑리스트 받아오기", true, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        transferSelectedIntegratedList();
                    }
                }));
            } else {
                page.addView(button("물건 안내 시작", true, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        isStoreTemiGuidanceActive = true;
                        storeTemiGuidanceStep = 0;
                        showStoreTemi();
                    }
                }));
            }
        }

        if (isStoreTemiGuidanceActive) {
            List<String> availableItems = availableStoreTemiItems();
            LinearLayout progressCard = card();
            progressCard.addView(label("진행 상태"));
            if (availableItems.isEmpty()) {
                progressCard.addView(body("재고 있는 안내 대상 물품이 없습니다."));
                page.addView(button("홈으로", true, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        isStoreTemiGuidanceActive = false;
                        isShoppingListReceived = false;
                        showHome();
                    }
                }));
            } else if (storeTemiGuidanceStep >= availableItems.size()) {
                progressCard.addView(body("안내가 완료되었습니다."));
                page.addView(button("홈으로", true, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        isStoreTemiGuidanceActive = false;
                        isShoppingListReceived = false;
                        showHome();
                    }
                }));
            } else {
                progressCard.addView(body("현재 안내 중 항목"));
                progressCard.addView(big(availableItems.get(storeTemiGuidanceStep)));
                progressCard.addView(body("진행 " + (storeTemiGuidanceStep + 1) + " / " + availableItems.size()));
                page.addView(button(storeTemiGuidanceStep == availableItems.size() - 1 ? "완료" : "다음 물건", true, new View.OnClickListener() {
                    @Override public void onClick(View v) {
                        advanceStoreTemiGuidance();
                    }
                }));
            }
            page.addView(progressCard);
        }

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
                
                storeTransferItems.clear();
                JSONArray itemsArray = response.optJSONArray("items");
                if (itemsArray != null) {
                    for (int i = 0; i < itemsArray.length(); i++) {
                        JSONObject itemObj = itemsArray.optJSONObject(i);
                        if (itemObj != null) {
                            String itemName = itemObj.optString("item_name");
                            int quantity = itemObj.optInt("quantity", 1);
                            boolean inStock = itemObj.optBoolean("in_stock", false);
                            String section = itemObj.optString("section", "\uBBF8\uC9C0\uC815");
                            String aisle = itemObj.optString("aisle", "-");
                            String shelf = itemObj.optString("shelf", "-");
                            storeTransferItems.add(new StoreTransferItem(itemName, quantity, inStock, section, aisle, shelf));
                        }
                    }
                }
                
                isShoppingListReceived = true;
                showStoreTemi();
            }

            @Override public void onFailure(String message) {
                Toast.makeText(MainActivity.this, "\uB9E4\uC7A5 Temi \uC804\uC1A1 \uC2E4\uD328: " + message, Toast.LENGTH_LONG).show();
            }
        });
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
        page.setPadding(dp(24), dp(32), dp(24), dp(36));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColorCompat(R.color.temi_text));
        titleView.setTextSize(36);
        titleView.setGravity(Gravity.START);
        titleView.setTypeface(null, 1);
        page.addView(titleView);

        TextView subtitleView = body(subtitle);
        subtitleView.setTextColor(getColorCompat(R.color.temi_muted));
        page.addView(subtitleView, params(-1, -2, 0, 8, 0, 20));
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
        card.setPadding(dp(22), dp(20), dp(22), dp(20));
        card.setBackgroundResource(R.drawable.temi_card);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setClipToOutline(true);
        card.setLayoutParams(params(-1, -2, 0, 0, 0, 16));
        return card;
    }

    private TextView qrPanel(String searchedText) {
        TextView qr = new TextView(this);
        qr.setText("QR 카메라\n\n" + searchedText);
        qr.setGravity(Gravity.CENTER);
        qr.setTextColor(Color.WHITE);
        qr.setTextSize(24);
        qr.setBackgroundResource(R.drawable.temi_camera_panel);
        qr.setLayoutParams(params(-1, dp(300), 0, 0, 0, 16));
        return qr;
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColorCompat(R.color.temi_text));
        view.setTextSize(20);
        view.setTypeface(null, 1);
        return view;
    }

    private TextView big(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColorCompat(R.color.temi_text));
        view.setTextSize(28);
        view.setTypeface(null, 1);
        return view;
    }

    private TextView body(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(getColorCompat(R.color.temi_muted));
        view.setTextSize(18);
        view.setLineSpacing(3, 1.1f);
        return view;
    }

    private EditText input(String hint) {
        EditText editText = new EditText(this);
        editText.setHint(hint);
        editText.setSingleLine(true);
        editText.setTextSize(20);
        editText.setBackgroundResource(R.drawable.temi_input);
        editText.setPadding(dp(16), dp(12), dp(16), dp(12));
        editText.setLayoutParams(params(-1, dp(64), 0, 10, 0, 10));
        return editText;
    }

    private TextView listItem(String title, String meta) {
        TextView item = new TextView(this);
        item.setText(title + "    " + meta);
        item.setTextColor(getColorCompat(R.color.temi_text));
        item.setTextSize(20);
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setPadding(dp(16), dp(14), dp(16), dp(14));
        item.setBackgroundResource(R.drawable.temi_input);
        item.setLayoutParams(params(-1, -2, 0, 10, 0, 0));
        return item;
    }

    private LinearLayout shoppingCartItem(String title, final int itemIndex) {
        LinearLayout item = row();
        item.setGravity(Gravity.CENTER_VERTICAL);
        item.setBackgroundResource(R.drawable.temi_input);
        item.setPadding(dp(16), dp(10), dp(12), dp(10));
        item.setLayoutParams(params(-1, -2, 0, 10, 0, 0));

        TextView titleView = new TextView(this);
        titleView.setText(title);
        titleView.setTextColor(getColorCompat(R.color.temi_text));
        titleView.setTextSize(20);
        titleView.setGravity(Gravity.CENTER_VERTICAL);
        item.addView(titleView, new LinearLayout.LayoutParams(0, dp(64), 1));

        Button deleteButton = button("삭제", false, new View.OnClickListener() {
            @Override public void onClick(View v) {
                deleteShoppingItem(itemIndex);
            }
        });
        LinearLayout.LayoutParams deleteParams = new LinearLayout.LayoutParams(dp(110), dp(58));
        deleteParams.setMargins(dp(12), 0, 0, 0);
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
        chip.setTextSize(18);
        chip.setBackgroundResource(selected ? R.drawable.temi_primary_button : R.drawable.temi_input);
        chip.setPadding(dp(12), dp(14), dp(12), dp(14));
        chip.setOnClickListener(listener);
        return chip;
    }

    private Button button(String text, boolean primary, View.OnClickListener listener) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setTextSize(20);
        button.setGravity(Gravity.CENTER);
        button.setTextColor(primary ? Color.WHITE : getColorCompat(R.color.temi_text));
        button.setBackgroundResource(primary ? R.drawable.temi_primary_button : R.drawable.temi_outline_button);
        button.setOnClickListener(listener);
        button.setMinHeight(dp(64));
        button.setLayoutParams(params(-1, dp(66), 0, 10, 0, 10));
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
        row.setLayoutParams(params(-1, -2, 0, 0, 0, 10));
        return row;
    }

    private LinearLayout.LayoutParams weightParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(66), 1);
        params.setMargins(dp(6), dp(6), dp(6), dp(6));
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
