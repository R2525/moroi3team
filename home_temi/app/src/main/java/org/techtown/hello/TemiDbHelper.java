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
    private static final int DB_VERSION = 1;

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
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS photo_uploads");
        db.execSQL("DROP TABLE IF EXISTS drawer_events");
        db.execSQL("DROP TABLE IF EXISTS storage_sessions");
        db.execSQL("DROP TABLE IF EXISTS items");
        onCreate(db);
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
