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
    public static final String AUTHORITY = "com.fourtwo.hookintent.configprovider";
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");
    public static final Uri SCHEME_URI = Uri.parse("content://" + AUTHORITY + "/scheme");

    @Override
    public boolean onCreate() {
        return true;
    }

    @Nullable
    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        Map<String, String> configData = new HashMap<>();

        if (CONFIG_URI.equals(uri)) {
            // 如果传递的是 CONFIG_URI，返回外部和内部配置
            String EXTERNAL_HOOKS_CONFIG = SharedPreferencesUtils.getStr(getContext(), Constants.EXTERNAL_HOOKS_CONFIG);
            String INTERNAL_HOOKS_CONFIG = SharedPreferencesUtils.getStr(getContext(), Constants.INTERNAL_HOOKS_CONFIG);
            configData.put(Constants.EXTERNAL_HOOKS_CONFIG, EXTERNAL_HOOKS_CONFIG);
            configData.put(Constants.INTERNAL_HOOKS_CONFIG, INTERNAL_HOOKS_CONFIG);
        } else if (SCHEME_URI.equals(uri)) {
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
