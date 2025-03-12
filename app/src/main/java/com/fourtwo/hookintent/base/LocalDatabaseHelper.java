package com.fourtwo.hookintent.base;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

public class LocalDatabaseHelper extends SQLiteOpenHelper {

    private static final int DATABASE_VERSION = 1;

    public LocalDatabaseHelper(Context context, String dbName) {
        super(context, dbName, null, DATABASE_VERSION);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        // 不需要在这里创建表，表会动态创建
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // 可实现升级逻辑
    }

    /**
     * 动态创建表（如果不存在）
     */
    public void createTable(String tableName) {
        SQLiteDatabase db = this.getWritableDatabase();
        String createTableQuery = "CREATE TABLE IF NOT EXISTS " + tableName +
                " (_hash TEXT PRIMARY KEY, data TEXT)";
        db.execSQL(createTableQuery);
    }

}
