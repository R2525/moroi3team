package org.techtown.hello;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public class TemiDbHelper extends SQLiteOpenHelper {
    private static final String DB_NAME = "temi_local.db";
    private static final int DB_VERSION = 4;

    public TemiDbHelper(Context context) {
        super(context, DB_NAME, null, DB_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT NOT NULL UNIQUE," +
                "drawer_number INTEGER NOT NULL DEFAULT 0," +
                "quantity INTEGER NOT NULL DEFAULT 0," +
                "source TEXT NOT NULL DEFAULT 'manual'," +
                "updated_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE storage_sessions (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "item_name TEXT NOT NULL," +
                "target_drawer_number INTEGER NOT NULL," +
                "quantity INTEGER NOT NULL DEFAULT 1," +
                "status TEXT NOT NULL," +
                "last_event TEXT," +
                "message TEXT," +
                "created_at INTEGER NOT NULL," +
                "completed_at INTEGER)");
        db.execSQL("CREATE TABLE drawer_events (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "drawer_number INTEGER NOT NULL," +
                "event_type TEXT NOT NULL," +
                "value REAL," +
                "matched_session_id INTEGER," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE photo_uploads (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "file_path TEXT NOT NULL," +
                "status TEXT NOT NULL," +
                "result_json TEXT," +
                "created_at INTEGER NOT NULL)");
        db.execSQL("CREATE TABLE placement_batches (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "status TEXT NOT NULL DEFAULT 'active'," +
                "current_index INTEGER NOT NULL DEFAULT 0," +
                "created_at INTEGER NOT NULL," +
                "completed_at INTEGER)");
        db.execSQL("CREATE TABLE placement_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "batch_id INTEGER NOT NULL," +
                "seq INTEGER NOT NULL," +
                "item_name TEXT NOT NULL," +
                "quantity INTEGER NOT NULL DEFAULT 1," +
                "drawer_number INTEGER NOT NULL DEFAULT 0," +
                "actual_drawer_number INTEGER NOT NULL DEFAULT 0," +
                "sensor_event_at INTEGER NOT NULL DEFAULT 0," +
                "status TEXT NOT NULL DEFAULT 'pending'," +
                "updated_at INTEGER NOT NULL)");
        createShoppingTables(db);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < 4) {
            if (oldVersion >= 2) {
                addPlacementColumnIfMissing(db, "actual_drawer_number", "INTEGER NOT NULL DEFAULT 0");
                addPlacementColumnIfMissing(db, "sensor_event_at", "INTEGER NOT NULL DEFAULT 0");
                createShoppingTables(db);
                return;
            }
        }
        if (oldVersion >= 2 && oldVersion < 3) {
            createShoppingTables(db);
            return;
        }
        db.execSQL("DROP TABLE IF EXISTS placement_items");
        db.execSQL("DROP TABLE IF EXISTS placement_batches");
        db.execSQL("DROP TABLE IF EXISTS shopping_items");
        db.execSQL("DROP TABLE IF EXISTS photo_uploads");
        db.execSQL("DROP TABLE IF EXISTS drawer_events");
        db.execSQL("DROP TABLE IF EXISTS storage_sessions");
        db.execSQL("DROP TABLE IF EXISTS items");
        onCreate(db);
    }

    private void createShoppingTables(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS shopping_items (" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "item_name TEXT NOT NULL," +
                "quantity INTEGER NOT NULL DEFAULT 1," +
                "created_at INTEGER NOT NULL)");
    }

    private void addPlacementColumnIfMissing(SQLiteDatabase db, String column, String definition) {
        Cursor cursor = db.rawQuery("PRAGMA table_info(placement_items)", new String[]{});
        try {
            while (cursor.moveToNext()) {
                if (column.equals(cursor.getString(cursor.getColumnIndexOrThrow("name")))) {
                    return;
                }
            }
        } finally {
            cursor.close();
        }
        db.execSQL("ALTER TABLE placement_items ADD COLUMN " + column + " " + definition);
    }

    public synchronized JSONObject health() throws JSONException {
        JSONObject result = new JSONObject();
        result.put("status", "ok");
        result.put("mode", "temi-local");
        result.put("items_count", count("items"));
        result.put("active_session", getActiveStorageSession());
        return result;
    }

    public synchronized JSONObject savePlacement(String name, int drawerNumber, int quantity, String source) throws JSONException {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "INSERT OR REPLACE INTO items(id, name, drawer_number, quantity, source, updated_at) " +
                        "VALUES((SELECT id FROM items WHERE name = ?), ?, ?, ?, ?, ?)",
                new Object[]{name, name, drawerNumber, quantity, source, now});
        JSONObject result = findItem(name);
        result.put("saved", true);
        return result;
    }

    public synchronized JSONObject findItem(String keyword) throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id, name, drawer_number, quantity, source, updated_at FROM items " +
                        "WHERE name = ? OR name LIKE ? ORDER BY CASE WHEN name = ? THEN 0 ELSE 1 END, updated_at DESC LIMIT 1",
                new String[]{keyword, "%" + keyword + "%", keyword});
        try {
            if (!cursor.moveToFirst()) {
                JSONObject notFound = new JSONObject();
                notFound.put("found", false);
                notFound.put("name", keyword);
                return notFound;
            }
            return itemFromCursor(cursor);
        } finally {
            cursor.close();
        }
    }

    public synchronized JSONArray listItems() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id, name, drawer_number, quantity, source, updated_at FROM items ORDER BY updated_at DESC",
                new String[]{});
        JSONArray items = new JSONArray();
        try {
            while (cursor.moveToNext()) {
                items.put(itemFromCursor(cursor));
            }
        } finally {
            cursor.close();
        }
        return items;
    }

    public synchronized boolean deleteItemByName(String name) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete("items", "name = ?", new String[]{name}) > 0;
    }

    public synchronized JSONObject saveShoppingItem(String itemName, int quantity) throws JSONException {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "INSERT INTO shopping_items(item_name, quantity, created_at) VALUES(?, ?, ?)",
                new Object[]{itemName, Math.max(1, quantity), now});
        JSONObject result = new JSONObject();
        result.put("saved", true);
        result.put("id", getLastInsertId(db));
        result.put("items", listShoppingItems());
        return result;
    }

    public synchronized JSONArray listShoppingItems() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id, item_name, quantity, created_at FROM shopping_items ORDER BY id ASC",
                new String[]{});
        JSONArray items = new JSONArray();
        try {
            while (cursor.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")));
                item.put("item_name", cursor.getString(cursor.getColumnIndexOrThrow("item_name")));
                item.put("quantity", cursor.getInt(cursor.getColumnIndexOrThrow("quantity")));
                item.put("created_at", cursor.getLong(cursor.getColumnIndexOrThrow("created_at")));
                items.put(item);
            }
        } finally {
            cursor.close();
        }
        return items;
    }

    public synchronized boolean deleteShoppingItem(int id) {
        SQLiteDatabase db = getWritableDatabase();
        return db.delete("shopping_items", "id = ?", new String[]{String.valueOf(id)}) > 0;
    }

    public synchronized void clearShoppingItems() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("shopping_items", null, null);
    }

    public synchronized void clearAllData() {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("placement_items", null, null);
        db.delete("placement_batches", null, null);
        db.delete("shopping_items", null, null);
        db.delete("photo_uploads", null, null);
        db.delete("drawer_events", null, null);
        db.delete("storage_sessions", null, null);
        db.delete("items", null, null);
        lastVerifyOk = true;
        lastVerifyMessage = "";
        mismatchPending = false;
        lastVerifyMessage = "";
        mismatchExpectedDrawer = 0;
        mismatchActualDrawer = 0;
    }

    public synchronized JSONObject startStorageSession(String itemName, int drawerNumber, int quantity) throws JSONException {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE storage_sessions SET status = 'cancelled' WHERE status IN ('waiting', 'drawer_opened', 'mismatch')");
        db.execSQL(
                "INSERT INTO storage_sessions(item_name, target_drawer_number, quantity, status, message, created_at) " +
                        "VALUES(?, ?, ?, 'waiting', ?, ?)",
                new Object[]{itemName, drawerNumber, quantity, drawerNumber + "번 서랍을 기다리는 중", now});
        return getActiveStorageSession();
    }

    public synchronized JSONObject getActiveStorageSession() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT * FROM storage_sessions WHERE status IN ('waiting', 'drawer_opened', 'mismatch') " +
                        "ORDER BY id DESC LIMIT 1",
                new String[]{});
        try {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return sessionFromCursor(cursor);
        } finally {
            cursor.close();
        }
    }

    public synchronized JSONObject recordSensorEvent(int drawerNumber, String eventType, Double value) throws JSONException {
        long now = System.currentTimeMillis();
        JSONObject session = getActiveStorageSession();
        Integer sessionId = session == null ? null : session.optInt("id");
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "INSERT INTO drawer_events(drawer_number, event_type, value, matched_session_id, created_at) VALUES(?, ?, ?, ?, ?)",
                new Object[]{drawerNumber, eventType, value, sessionId, now});

        JSONObject result = new JSONObject();
        result.put("stored", true);
        result.put("drawer_number", drawerNumber);
        result.put("event_type", eventType);

        if (session == null) {
            result.put("matched", false);
            result.put("message", "진행 중인 수납 작업이 없습니다.");
            return result;
        }

        int targetDrawer = session.optInt("target_drawer_number");
        int id = session.optInt("id");
        if (drawerNumber != targetDrawer) {
            updateSession(id, "mismatch", eventType, targetDrawer + "번 서랍에 넣어야 합니다.");
            result.put("matched", false);
            result.put("session", getActiveStorageSession());
            return result;
        }

        if ("drawer_close".equals(eventType)) {
            savePlacement(session.optString("item_name"), targetDrawer, session.optInt("quantity", 1), "sensor");
            db.execSQL(
                    "UPDATE storage_sessions SET status = 'completed', last_event = ?, message = ?, completed_at = ? WHERE id = ?",
                    new Object[]{eventType, "수납 완료", now, id});
            result.put("matched", true);
            result.put("completed", true);
            result.put("item", findItem(session.optString("item_name")));
            return result;
        }

        updateSession(id, "drawer_open".equals(eventType) ? "drawer_opened" : "waiting", eventType, "서랍 이벤트 확인됨");
        result.put("matched", true);
        result.put("session", getActiveStorageSession());
        return result;
    }

    public synchronized JSONObject savePhotoUpload(String path, String status, JSONObject resultJson) throws JSONException {
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL(
                "INSERT INTO photo_uploads(file_path, status, result_json, created_at) VALUES(?, ?, ?, ?)",
                new Object[]{path, status, resultJson == null ? null : resultJson.toString(), now});
        JSONObject result = latestPhotoUpload();
        result.put("id", getLastInsertId(db));
        return result;
    }

    public synchronized JSONObject latestPhotoUpload() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery("SELECT * FROM photo_uploads ORDER BY id DESC LIMIT 1", new String[]{});
        try {
            if (!cursor.moveToFirst()) {
                JSONObject empty = new JSONObject();
                empty.put("found", false);
                return empty;
            }
            JSONObject result = new JSONObject();
            result.put("found", true);
            result.put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")));
            result.put("file_path", cursor.getString(cursor.getColumnIndexOrThrow("file_path")));
            result.put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")));
            String resultJson = cursor.getString(cursor.getColumnIndexOrThrow("result_json"));
            result.put("result", resultJson == null ? JSONObject.NULL : new JSONObject(resultJson));
            result.put("created_at", cursor.getLong(cursor.getColumnIndexOrThrow("created_at")));
            return result;
        } finally {
            cursor.close();
        }
    }

    // --- 다중 물품 수납 배치 (사진 → Gemini 순서 → 순차 수납) ---

    public synchronized JSONObject createPlacementBatch(JSONArray items) throws JSONException {
        long now = System.currentTimeMillis();
        lastVerifyOk = true;
        lastVerifyMessage = "";
        SQLiteDatabase db = getWritableDatabase();
        db.execSQL("UPDATE placement_batches SET status = 'cancelled' WHERE status = 'active'");
        db.execSQL("INSERT INTO placement_batches(status, current_index, created_at) VALUES('active', 0, ?)",
                new Object[]{now});
        long batchId = getLastInsertId(db);
        int seq = 1;
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item == null) {
                continue;
            }
            String name = item.optString("item_name", item.optString("name", "")).trim();
            if (name.length() == 0) {
                continue;
            }
            db.execSQL(
                    "INSERT INTO placement_items(batch_id, seq, item_name, quantity, drawer_number, status, updated_at) " +
                            "VALUES(?, ?, ?, ?, ?, 'pending', ?)",
                    new Object[]{batchId, seq, name, Math.max(1, item.optInt("quantity", 1)), item.optInt("drawer_number", 0), now});
            seq++;
        }
        return getActivePlacementBatch();
    }

    public synchronized JSONObject getActivePlacementBatch() throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        Cursor cursor = db.rawQuery(
                "SELECT id FROM placement_batches WHERE status = 'active' ORDER BY id DESC LIMIT 1", new String[]{});
        try {
            if (!cursor.moveToFirst()) {
                return null;
            }
            return buildBatch(cursor.getInt(0));
        } finally {
            cursor.close();
        }
    }

    private JSONObject buildBatch(int batchId) throws JSONException {
        SQLiteDatabase db = getReadableDatabase();
        JSONObject batch = new JSONObject();
        Cursor bc = db.rawQuery("SELECT * FROM placement_batches WHERE id = ?", new String[]{String.valueOf(batchId)});
        try {
            if (!bc.moveToFirst()) {
                return null;
            }
            batch.put("id", batchId);
            batch.put("status", bc.getString(bc.getColumnIndexOrThrow("status")));
            batch.put("current_index", bc.getInt(bc.getColumnIndexOrThrow("current_index")));
        } finally {
            bc.close();
        }
        JSONArray items = new JSONArray();
        Cursor ic = db.rawQuery("SELECT * FROM placement_items WHERE batch_id = ? ORDER BY seq", new String[]{String.valueOf(batchId)});
        try {
            while (ic.moveToNext()) {
                JSONObject item = new JSONObject();
                item.put("id", ic.getInt(ic.getColumnIndexOrThrow("id")));
                item.put("seq", ic.getInt(ic.getColumnIndexOrThrow("seq")));
                item.put("item_name", ic.getString(ic.getColumnIndexOrThrow("item_name")));
                item.put("quantity", ic.getInt(ic.getColumnIndexOrThrow("quantity")));
                item.put("drawer_number", ic.getInt(ic.getColumnIndexOrThrow("drawer_number")));
                item.put("actual_drawer_number", ic.getInt(ic.getColumnIndexOrThrow("actual_drawer_number")));
                item.put("sensor_event_at", ic.getLong(ic.getColumnIndexOrThrow("sensor_event_at")));
                item.put("status", ic.getString(ic.getColumnIndexOrThrow("status")));
                items.put(item);
            }
        } finally {
            ic.close();
        }
        batch.put("items", items);
        batch.put("total", items.length());
        int idx = batch.getInt("current_index");
        batch.put("current", idx >= 0 && idx < items.length() ? items.getJSONObject(idx) : JSONObject.NULL);
        batch.put("verify_ok", lastVerifyOk);
        batch.put("verify_message", lastVerifyMessage);
        batch.put("mismatch_pending", mismatchPending);
        batch.put("mismatch_expected_drawer", mismatchExpectedDrawer);
        batch.put("mismatch_actual_drawer", mismatchActualDrawer);
        return batch;
    }

    // 마지막 검증 결과 (UI에 사유 표시용). 새 배치 시작 시 초기화.
    private boolean lastVerifyOk = true;
    private String lastVerifyMessage = "";
    private boolean mismatchPending = false;
    private int mismatchExpectedDrawer = 0;
    private int mismatchActualDrawer = 0;
    private static final double WEIGHT_MIN_DELTA = 1.0; // 로드셀 무게 변화 최소값(이상이어야 통과)

    /**
     * 넣은 뒤 검증 단계: 보고된 서랍번호/무게를 현재 물품의 기대값과 대조한 뒤 진행한다.
     * - 서랍 불일치 또는 무게 부족 → fail 처리("다시 넣어주세요" + 사유)
     * - 통과 → success 진행
     */
    public synchronized JSONObject verifyAndAdvancePlacement(int reportedDrawer, Double weight, long sensorEventAt) throws JSONException {
        JSONObject batch = getActivePlacementBatch();
        if (batch == null) {
            return new JSONObject().put("active", false).put("message", "진행 중인 수납이 없습니다.");
        }
        JSONObject current = batch.optJSONObject("current");
        int expectedDrawer = current == null ? 0 : current.optInt("drawer_number", 0);
        String expectedName = current == null ? "" : current.optString("item_name", "");
        if (current != null) {
            getWritableDatabase().execSQL(
                    "UPDATE placement_items SET actual_drawer_number = ?, sensor_event_at = ? WHERE id = ?",
                    new Object[]{reportedDrawer, sensorEventAt, current.optInt("id")});
        }

        // 1) 서랍 번호 대조
        if (reportedDrawer > 0 && expectedDrawer > 0 && reportedDrawer != expectedDrawer) {
            lastVerifyOk = false;
            lastVerifyMessage = expectedName + "은 " + expectedDrawer + "번 서랍에 넣어야 합니다 ("
                    + reportedDrawer + "번에서 감지됨)";
            mismatchPending = true;
            mismatchExpectedDrawer = expectedDrawer;
            mismatchActualDrawer = reportedDrawer;
            JSONObject fail = advancePlacement("fail");
            fail.put("verify", new JSONObject().put("ok", false).put("reason", lastVerifyMessage));
            return fail;
        }
        // 2) 무게 임계 검증 (무게가 함께 보고된 경우에만)
        if (weight != null && weight < WEIGHT_MIN_DELTA) {
            lastVerifyOk = false;
            lastVerifyMessage = "무게 변화가 감지되지 않았습니다 (" + weight + ")";
            JSONObject fail = advancePlacement("fail");
            fail.put("verify", new JSONObject().put("ok", false).put("reason", lastVerifyMessage));
            return fail;
        }
        // 통과
        lastVerifyOk = true;
        lastVerifyMessage = "";
        mismatchPending = false;
        mismatchExpectedDrawer = 0;
        mismatchActualDrawer = 0;
        JSONObject ok = advancePlacement("success");
        ok.put("verify", new JSONObject()
                .put("ok", true)
                .put("drawer", expectedDrawer)
                .put("actual_drawer", reportedDrawer)
                .put("sensor_event_at", sensorEventAt)
                .put("weight", weight == null ? JSONObject.NULL : weight));
        return ok;
    }

    /**
     * 현재 물품의 수납 결과를 반영하고 다음 물품으로 진행한다.
     * result: "success"(=놓음, Uno Q VERIFY_SUCCESS / 수동 완료), "skip"(건너뛰기), "fail"(검증 실패, 현재 유지)
     */
    public synchronized JSONObject advancePlacement(String result) throws JSONException {
        JSONObject batch = getActivePlacementBatch();
        if (batch == null) {
            return new JSONObject().put("active", false).put("message", "진행 중인 수납이 없습니다.");
        }
        int batchId = batch.getInt("id");
        int idx = batch.getInt("current_index");
        JSONArray items = batch.getJSONArray("items");
        long now = System.currentTimeMillis();
        SQLiteDatabase db = getWritableDatabase();

        if ("fail".equals(result)) {
            JSONObject retry = getActivePlacementBatch();
            retry.put("last_result", "fail");
            retry.put("message", "검증 실패 - 다시 넣어주세요.");
            return retry;
        }

        if (idx >= 0 && idx < items.length()) {
            JSONObject current = items.getJSONObject(idx);
            String newStatus = "skip".equals(result) ? "skipped" : "placed";
            // 순서대로 현재 물품을 placed로 표시 + 넣은 시각(now) 기록.
            // 실제 Temi DB 저장은 여기서 하지 않고 마지막 단계에서 일괄 커밋한다.
            db.execSQL("UPDATE placement_items SET status = ?, updated_at = ? WHERE id = ?",
                    new Object[]{newStatus, now, current.getInt("id")});
        }

        int nextIdx = idx + 1;
        db.execSQL("UPDATE placement_batches SET current_index = ? WHERE id = ?", new Object[]{nextIdx, batchId});
        if (nextIdx >= items.length()) {
            // === 마지막 단계: 순서(seq)·시간(updated_at) 검증 후 실제 Temi DB에 일괄 커밋 ===
            JSONObject commit = commitPlacementBatch(batchId);
            db.execSQL("UPDATE placement_batches SET status = 'completed', completed_at = ? WHERE id = ?",
                    new Object[]{now, batchId});
            JSONObject done = new JSONObject();
            done.put("active", false);
            done.put("completed", true);
            done.put("message", "모든 물품 수납 완료 · Temi DB " + commit.optInt("committed") + "건 저장"
                    + (commit.optBoolean("time_order_ok", true) ? "" : " (경고: 시간 순서 역전)"));
            done.put("commit", commit);
            done.put("batch", buildBatch(batchId));
            return done;
        }
        JSONObject next = getActivePlacementBatch();
        next.put("last_result", result);
        return next;
    }

    public synchronized void cancelActivePlacementBatch() {
        getWritableDatabase().execSQL("UPDATE placement_batches SET status = 'cancelled' WHERE status = 'active'");
    }

    public synchronized JSONObject resolveDrawerMismatch(boolean useActualDrawer) throws JSONException {
        JSONObject batch = getActivePlacementBatch();
        if (batch == null) {
            return new JSONObject().put("active", false).put("message", "진행 중인 수납이 없습니다.");
        }
        if (!mismatchPending) {
            return batch;
        }
        if (useActualDrawer) {
            JSONObject current = batch.optJSONObject("current");
            if (current != null) {
            getWritableDatabase().execSQL("UPDATE placement_items SET drawer_number = ?, actual_drawer_number = ? WHERE id = ?",
                    new Object[]{mismatchActualDrawer, mismatchActualDrawer, current.optInt("id")});
            }
            lastVerifyOk = true;
            lastVerifyMessage = "";
            mismatchPending = false;
            mismatchExpectedDrawer = 0;
            mismatchActualDrawer = 0;
            return advancePlacement("success");
        }

        JSONObject current = batch.optJSONObject("current");
        if (current != null) {
            getWritableDatabase().execSQL("UPDATE placement_items SET actual_drawer_number = 0, sensor_event_at = 0 WHERE id = ?",
                    new Object[]{current.optInt("id")});
        }
        lastVerifyOk = true;
        lastVerifyMessage = "";
        lastVerifyMessage = mismatchExpectedDrawer + "번 서랍에 다시 넣어주세요.";
        mismatchPending = false;
        mismatchExpectedDrawer = 0;
        mismatchActualDrawer = 0;
        lastVerifyMessage = "";
        JSONObject retry = getActivePlacementBatch();
        retry.put("last_result", "retry");
        return retry;
    }

    // 마지막 단계: placement_items를 순서(seq)대로 읽어 시간(updated_at) 순서를 검증하고,
    // placed 상태인 물품만 실제 Temi DB(items 테이블)에 일괄 저장한다.
    private JSONObject commitPlacementBatch(int batchId) throws JSONException {
        SQLiteDatabase db = getWritableDatabase();
        Cursor c = db.rawQuery(
                "SELECT item_name, drawer_number, actual_drawer_number, sensor_event_at, quantity, status, updated_at "
                        + "FROM placement_items WHERE batch_id = ? ORDER BY seq",
                new String[]{String.valueOf(batchId)});
        int committed = 0;
        long prevTs = 0;
        boolean timeOrderOk = true;
        JSONArray saved = new JSONArray();
        try {
            while (c.moveToNext()) {
                String status = c.getString(c.getColumnIndexOrThrow("status"));
                if (!"placed".equals(status)) {
                    continue; // 건너뛴(skipped) 물품은 저장하지 않음
                }
                long eventTs = c.getLong(c.getColumnIndexOrThrow("sensor_event_at"));
                long ts = eventTs > 0 ? eventTs : c.getLong(c.getColumnIndexOrThrow("updated_at"));
                if (ts < prevTs) {
                    timeOrderOk = false; // 넣은 시각이 순서를 거스르면 경고
                }
                prevTs = ts;
                String name = c.getString(c.getColumnIndexOrThrow("item_name"));
                int drawer = c.getInt(c.getColumnIndexOrThrow("drawer_number"));
                int actualDrawer = c.getInt(c.getColumnIndexOrThrow("actual_drawer_number"));
                int qty = c.getInt(c.getColumnIndexOrThrow("quantity"));
                savePlacement(name, drawer, qty, "placement");
                saved.put(new JSONObject()
                        .put("item_name", name)
                        .put("drawer_number", drawer)
                        .put("actual_drawer_number", actualDrawer)
                        .put("quantity", qty)
                        .put("placed_at", ts));
                committed++;
            }
        } finally {
            c.close();
        }
        return new JSONObject()
                .put("committed", committed)
                .put("time_order_ok", timeOrderOk)
                .put("items", saved);
    }

    private JSONObject itemFromCursor(Cursor cursor) throws JSONException {
        JSONObject item = new JSONObject();
        item.put("found", true);
        item.put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")));
        item.put("name", cursor.getString(cursor.getColumnIndexOrThrow("name")));
        int drawerNumber = cursor.getInt(cursor.getColumnIndexOrThrow("drawer_number"));
        item.put("drawer_number", drawerNumber);
        item.put("location", drawerNumber > 0 ? drawerNumber + "번 서랍" : "미배정");
        item.put("quantity", cursor.getInt(cursor.getColumnIndexOrThrow("quantity")));
        item.put("source", cursor.getString(cursor.getColumnIndexOrThrow("source")));
        item.put("updated_at", cursor.getLong(cursor.getColumnIndexOrThrow("updated_at")));
        return item;
    }

    private JSONObject sessionFromCursor(Cursor cursor) throws JSONException {
        JSONObject session = new JSONObject();
        session.put("id", cursor.getInt(cursor.getColumnIndexOrThrow("id")));
        session.put("item_name", cursor.getString(cursor.getColumnIndexOrThrow("item_name")));
        session.put("target_drawer_number", cursor.getInt(cursor.getColumnIndexOrThrow("target_drawer_number")));
        session.put("quantity", cursor.getInt(cursor.getColumnIndexOrThrow("quantity")));
        session.put("status", cursor.getString(cursor.getColumnIndexOrThrow("status")));
        session.put("last_event", cursor.getString(cursor.getColumnIndexOrThrow("last_event")));
        session.put("message", cursor.getString(cursor.getColumnIndexOrThrow("message")));
        return session;
    }

    private void updateSession(int id, String status, String eventType, String message) {
        getWritableDatabase().execSQL(
                "UPDATE storage_sessions SET status = ?, last_event = ?, message = ? WHERE id = ?",
                new Object[]{status, eventType, message, id});
    }

    private int count(String table) {
        Cursor cursor = getReadableDatabase().rawQuery("SELECT COUNT(*) FROM " + table, new String[]{});
        try {
            return cursor.moveToFirst() ? cursor.getInt(0) : 0;
        } finally {
            cursor.close();
        }
    }

    private long getLastInsertId(SQLiteDatabase db) {
        Cursor cursor = db.rawQuery("SELECT last_insert_rowid()", new String[]{});
        try {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        } finally {
            cursor.close();
        }
    }
}
