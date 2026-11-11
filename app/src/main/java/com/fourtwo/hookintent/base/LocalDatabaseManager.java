package com.fourtwo.hookintent.base;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;
import com.fourtwo.hookintent.data.Constants;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class LocalDatabaseManager {
    private final Context context;
    private LocalDatabaseHelper helper;
    private SQLiteDatabase database;
    public LocalDatabaseManager(Context context) { this.context = context.getApplicationContext(); }
    public synchronized void openDatabase(String name) {
        closeDatabase();
        helper = new LocalDatabaseHelper(context, name + ".db");
        database = helper.getWritableDatabase();
    }
    public synchronized void closeDatabase() {
        if (helper != null) helper.close();
        helper = null;
        database = null;
    }
    private String table(String table) {
        if (table == null || !table.matches("[A-Za-z_][A-Za-z0-9_]*")) throw new IllegalArgumentException("Invalid table name");
        return table;
    }
    public synchronized void createTable(String name) { helper.createTable(table(name)); }
    private ContentValues values(JSONObject data) throws JSONException {
        ContentValues values = new ContentValues();
        values.put(Constants.SQL_HASH, data.getString(Constants.SQL_HASH));
        values.put(Constants.SQL_DATA, data.toString());
        return values;
    }
    public synchronized void insertData(String name, JSONObject data) throws JSONException {
        database.insertOrThrow(table(name), null, values(data));
    }
    public synchronized boolean deleteData(String name, String hash) {
        try { return database.delete(table(name), "_hash = ?", new String[]{hash}) > 0; }
        catch (RuntimeException e) { Log.e("LocalDatabaseManager", "删除收藏失败", e); return false; }
    }
    public synchronized boolean is_exists(String name, String hash) {
        try (Cursor cursor = database.query(table(name), new String[]{Constants.SQL_HASH}, "_hash = ?", new String[]{hash}, null, null, null)) {
            return cursor.moveToFirst();
        }
    }
    public synchronized boolean insertOrUpdateData(String name, JSONObject data) {
        try {
            return database.insertWithOnConflict(table(name), null, values(data), SQLiteDatabase.CONFLICT_REPLACE) != -1;
        } catch (RuntimeException | JSONException e) {
            Log.e("LocalDatabaseManager", "保存收藏失败", e);
            return false;
        }
    }
    public synchronized List<JSONObject> getAllData(String name) {
        List<JSONObject> result = new ArrayList<>();
        String tableName = table(name);
        // Query only small metadata through CursorWindow. Complete Intent snapshots can
        // exceed its usual 2 MB row limit, so read the unchanged TEXT column in pieces.
        try (Cursor cursor = database.query(tableName, new String[]{"rowid", "length(" + Constants.SQL_DATA + ")"},
                null, null, null, null, "rowid ASC")) {
            while (cursor.moveToNext()) {
                try {
                    long row = cursor.getLong(0);
                    long characters = cursor.getLong(1);
                    StringBuilder contents = new StringBuilder((int) Math.min(characters, 4096));
                    final int chunkCharacters = 65536;
                    // SQLite substr indexes Unicode code points, not Java UTF-16 units.
                    for (long start = 1; start <= characters; start += chunkCharacters) {
                        try (Cursor chunk = database.rawQuery("SELECT substr(" + Constants.SQL_DATA + ", ?, ?) FROM "
                                + tableName + " WHERE rowid=?", new String[]{Long.toString(start),
                                Integer.toString(chunkCharacters), Long.toString(row)})) {
                            if (!chunk.moveToFirst() || chunk.isNull(0)) throw new JSONException("收藏记录内容不完整");
                            contents.append(chunk.getString(0));
                        }
                    }
                    result.add(new JSONObject(contents.toString()));
                } catch (JSONException e) { Log.e("LocalDatabaseManager", "跳过损坏的收藏记录", e); }
            }
        }
        return result;
    }
}
