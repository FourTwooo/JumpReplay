package com.fourtwo.hookintent.service;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;

import java.util.HashMap;
import java.util.Map;

public class ConfigProvider extends ContentProvider {
    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Map<String, String> configData = new HashMap<>();

        if (Constants.CONFIG_URI.equals(uri)) {
            // 如果传递的是 CONFIG_URI，返回外部和内部配置
            String EXTERNAL_HOOKS_CONFIG = SharedPreferencesUtils.getStr(getContext(), Constants.EXTERNAL_HOOKS_CONFIG);
            String INTERNAL_HOOKS_CONFIG = SharedPreferencesUtils.getStr(getContext(), Constants.INTERNAL_HOOKS_CONFIG);
            String FLOAT_WINDOW_CONFIG = SharedPreferencesUtils.getStr(getContext(), Constants.FLOAT_WINDOW_CONFIG);
            configData.put(Constants.EXTERNAL_HOOKS_CONFIG, EXTERNAL_HOOKS_CONFIG);
            configData.put(Constants.INTERNAL_HOOKS_CONFIG, INTERNAL_HOOKS_CONFIG);
            configData.put(Constants.FLOAT_WINDOW_CONFIG, FLOAT_WINDOW_CONFIG);
        } else if (Constants.SCHEME_URI.equals(uri)) {
            // 如果传递的是 SCHEME_URI，返回禁用的 Scheme 配置
            String DISABLED_SCHEME = SharedPreferencesUtils.getStr(getContext(), Constants.DISABLED_SCHEME);
            configData.put(Constants.DISABLED_SCHEME, DISABLED_SCHEME);
        }

        MatrixCursor cursor = new MatrixCursor(new String[]{"key", "value"});
        for (Map.Entry<String, String> entry : configData.entrySet()) {
            cursor.addRow(new Object[]{entry.getKey(), entry.getValue()});
        }
        return cursor;
    }

    @Override
    public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.com.fourtwo.config";
    }

    @Nullable
    @Override
    public Uri insert(@NonNull Uri uri, @Nullable ContentValues values) {
        if (values != null) {
            String value = values.getAsString("value");

            // 将数据存储到 SharedPreferences
            SharedPreferencesUtils.putStr(getContext(), Constants.INTERNAL_HOOKS_CONFIG, value);

            // 返回新的数据 URI
            return Uri.withAppendedPath(Constants.CONFIG_URI, Constants.INTERNAL_HOOKS_CONFIG);
        }
        return null;
    }

    @Override
    public int delete(@NonNull Uri uri, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }

    @Override
    public int update(@NonNull Uri uri, @Nullable ContentValues values, @Nullable String selection, @Nullable String[] selectionArgs) {
        return 0;
    }
}
