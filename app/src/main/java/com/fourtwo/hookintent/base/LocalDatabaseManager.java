package com.fourtwo.hookintent.base;

import android.annotation.SuppressLint;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import com.fourtwo.hookintent.data.Constants;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class LocalDatabaseManager {

    private final Context context;
    private LocalDatabaseHelper dbHelper;
    private SQLiteDatabase database;

    public LocalDatabaseManager(Context context) {
        this.context = context;
    }

    /**
     * 打开指定数据库
     */
    public void openDatabase(String dbName) {
        dbHelper = new LocalDatabaseHelper(context, dbName + ".db");
        database = dbHelper.getWritableDatabase();
    }

    /**
     * 关闭数据库
     */
    public void closeDatabase() {
        if (database != null) {
            database.close();
        }
    }

    /**
     * 创建表
     */
    public void createTable(String tableName) {
        dbHelper.createTable(tableName);
    }

    /**
     * 插入数据
     */
    public void insertData(String tableName, JSONObject data) throws JSONException {
        // 创建 ContentValues
        ContentValues values = new ContentValues();
        // 从 JSON 数据中提取 `_hash`，以字符串形式处理
        String _hash = data.getString(Constants.SQL_HASH);
        values.put(Constants.SQL_HASH, _hash);
        values.put(Constants.SQL_DATA, data.toString());
        database.insert(tableName, null, values);
    }

    /**
     * 根据 _hash 删除指定数据
     *
     * @param tableName 表名
     * @param _hash     要删除的数据的哈希值
     * @return 删除是否成功
     */
    public boolean deleteData(String tableName, String _hash) {
        try {
            // 执行删除操作
            int rowsAffected = database.delete(tableName, "_hash = ?", new String[]{_hash});
            return rowsAffected > 0; // 如果受影响的行数大于0，则说明删除成功
        } catch (Exception e) {
            e.printStackTrace();
            return false; // 删除失败
        }
    }

    public boolean is_exists(String tableName, String _hash) {
        // 检查是否已存在该 `_hash` 的记录
        Cursor cursor = database.query(
                tableName,
                new String[]{Constants.SQL_HASH},
                "_hash = ?",
                new String[]{_hash},
                null,
                null,
                null
        );

        boolean exists = cursor.getCount() > 0; // 判断记录是否存在
        cursor.close();
        return exists;
    }

    /**
     * 插入或更新数据
     * 根据 `_hash` 判断数据是否存在。
     * 如果存在，则更新；如果不存在，则插入。
     */
    public void insertOrUpdateData(String tableName, JSONObject data) {
        try {
            // 从 JSON 数据中提取 `_hash`，以字符串形式处理
            String _hash = data.getString(Constants.SQL_HASH);

            // 创建 ContentValues
            ContentValues values = new ContentValues();
            values.put(Constants.SQL_HASH, _hash);
            values.put(Constants.SQL_DATA, data.toString());
            boolean exists = is_exists(tableName, _hash);

            if (exists) {
                // 如果记录存在，执行更新操作
                database.update(tableName, values, "_hash = ?", new String[]{_hash});
            } else {
                // 如果记录不存在，执行插入操作
                database.insert(tableName, null, values);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    /**
     * 查询所有数据
     */
    public List<JSONObject> getAllData(String tableName) {
        List<JSONObject> dataList = new ArrayList<>();
        Cursor cursor = database.rawQuery("SELECT * FROM " + tableName, null);

        if (cursor.moveToFirst()) {
            do {
                @SuppressLint("Range") String dataString = cursor.getString(cursor.getColumnIndex(Constants.SQL_DATA));
                try {
                    dataList.add(new JSONObject(dataString));
                } catch (Exception e) {
                    e.printStackTrace();
                }
            } while (cursor.moveToNext());
        }
        cursor.close();
        return dataList;
    }


}
