package com.fourtwo.hookintent.base;

import android.content.Context;
import android.os.Bundle;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

public class JsonHandler {
    private static final String TAG = "JsonHandler";
    private static final String FILE_NAME = "filter_data.json";
    private static final Object FILE_LOCK = new Object();

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getFilterKeyJson(Object value) {
        if (value instanceof JSONObject) return toMap((JSONObject) value);
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getFilterValueJson(Object value) {
        if (value instanceof JSONArray) value = toList((JSONArray) value);
        if (!(value instanceof List)) return null;
        List<Map<String, Object>> result = new ArrayList<>();
        boolean originalMaps = true;
        for (Object item : (List<?>) value) {
            Map<String, Object> map = getFilterKeyJson(item);
            if (map != null) result.add(map);
            if (!(item instanceof Map)) originalMaps = false;
        }
        // Keep actual list identity for editors, when all entries already are valid maps.
        if (originalMaps) return (List<Map<String, Object>>) value;
        return result;
    }

    private File file(Context context) {
        File directory = context.getExternalFilesDir(null);
        return new File(directory == null ? context.getFilesDir() : directory, FILE_NAME);
    }

    public Map<String, Object> readJsonFromFile(Context context) {
        synchronized (FILE_LOCK) {
            AtomicFile atomic = new AtomicFile(file(context));
            try {
                if (!atomic.getBaseFile().exists() && !new File(atomic.getBaseFile() + ".bak").exists()) {
                    try (InputStream asset = context.getAssets().open(FILE_NAME)) {
                        writeAtomic(atomic, readBytes(asset));
                    }
                }
                try (InputStream input = atomic.openRead()) {
                    return toMap(new JSONObject(new String(readBytes(input), StandardCharsets.UTF_8)));
                }
            } catch (IOException | JSONException e) {
                Log.e(TAG, "无法读取过滤规则，原文件已保留", e);
                return null;
            }
        }
    }

    public void writeJsonToFile(Context context, Map<String, Object> data) {
        synchronized (FILE_LOCK) {
            try {
                writeAtomic(new AtomicFile(file(context)), new JSONObject(data).toString().getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                throw new IllegalStateException("过滤规则保存失败，原文件已保留", e);
            }
        }
    }

    private void writeAtomic(AtomicFile file, byte[] bytes) throws IOException {
        FileOutputStream output = null;
        try {
            output = file.startWrite();
            output.write(bytes);
            output.getFD().sync();
            file.finishWrite(output);
            // AtomicFile reports some close/rename failures only through logcat.
            // Verify the committed contents before telling an editor that saving succeeded.
            try (InputStream committed = file.openRead()) {
                if (!Arrays.equals(bytes, readBytes(committed))) throw new IOException("过滤规则提交校验失败");
            }
        } catch (IOException | RuntimeException e) {
            if (output != null) file.failWrite(output);
            throw e;
        }
    }

    private byte[] readBytes(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }

    private static Object fromJsonValue(Object value) {
        if (value == JSONObject.NULL) return null;
        if (value instanceof JSONObject) return toMap((JSONObject) value);
        if (value instanceof JSONArray) return toList((JSONArray) value);
        return value;
    }

    public static Map<String, Object> toMap(JSONObject object) {
        Map<String, Object> map = new HashMap<>();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            map.put(key, fromJsonValue(object.opt(key)));
        }
        return map;
    }

    public static Map<String, Object> toMap(Bundle bundle) {
        Map<String, Object> map = new HashMap<>();
        if (bundle == null) return map;
        for (String key : bundle.keySet()) {
            Object value = bundle.get(key);
            map.put(key, value instanceof Bundle ? toMap((Bundle) value) : value);
        }
        return map;
    }

    private static ArrayList<Object> toList(JSONArray array) {
        ArrayList<Object> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) list.add(fromJsonValue(array.opt(i)));
        return list;
    }

    public static String toJson(List<Bundle> bundles) {
        JSONArray array = new JSONArray();
        for (Bundle bundle : bundles) array.put(new JSONObject(toMap(bundle)));
        return array.toString();
    }

    public static List<Bundle> fromJson(String json) {
        List<Bundle> bundles = new ArrayList<>();
        if (json == null || json.trim().isEmpty()) return bundles;
        try {
            JSONArray array = new JSONArray(json);
            for (int i = 0; i < array.length(); i++) {
                JSONObject object = array.optJSONObject(i);
                if (object != null) bundles.add(toBundle(object));
                else Log.w(TAG, "忽略一条非对象记录");
            }
        } catch (JSONException e) { Log.e(TAG, "记录JSON格式错误", e); }
        return bundles;
    }

    public static Bundle toBundle(JSONObject object) {
        Bundle bundle = new Bundle();
        Iterator<String> keys = object.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            Object value = object.opt(key);
            if (value == null || value == JSONObject.NULL) bundle.putString(key, null);
            else if (value instanceof JSONObject) bundle.putBundle(key, toBundle((JSONObject) value));
            else if (value instanceof JSONArray) bundle.putSerializable(key, toList((JSONArray) value));
            else if (value instanceof String) bundle.putString(key, (String) value);
            else if (value instanceof Integer) bundle.putInt(key, (Integer) value);
            else if (value instanceof Long) bundle.putLong(key, (Long) value);
            else if (value instanceof Boolean) bundle.putBoolean(key, (Boolean) value);
            else if (value instanceof Double) bundle.putDouble(key, (Double) value);
            else bundle.putString(key, String.valueOf(value));
        }
        return bundle;
    }

    public static String serializeHookedRecords(List<Map<String, Object>> records) { return new JSONArray(records).toString(); }

    public static List<Map<String, Object>> deserializeHookedRecords(String json) {
        if (json == null || "null".equals(json)) return new ArrayList<>();
        try {
            List<Map<String, Object>> result = getFilterValueJson(new JSONArray(json));
            return result == null ? new ArrayList<>() : result;
        } catch (JSONException e) {
            Log.e(TAG, "Hook配置JSON格式错误", e);
            return new ArrayList<>();
        }
    }

    public static Map<String, String> jsonToMap(String json) {
        Map<String, String> result = new HashMap<>();
        if (json == null) return result;
        try {
            JSONObject object = new JSONObject(json);
            Iterator<String> keys = object.keys();
            while (keys.hasNext()) { String key = keys.next(); result.put(key, object.optString(key)); }
        } catch (JSONException e) { Log.w(TAG, "配置JSON格式错误", e); }
        return result;
    }
    public static String mapToJson(Map<String, String> map) { return new JSONObject(map).toString(); }
    public static Map<String, Boolean> strToBoolean(Map<String, String> map) {
        Map<String, Boolean> result = new HashMap<>();
        for (Map.Entry<String, String> entry : map.entrySet()) result.put(entry.getKey(), Boolean.parseBoolean(entry.getValue()));
        return result;
    }
    public static Map<String, String> booleanToStr(Map<String, Boolean> map) {
        Map<String, String> result = new HashMap<>();
        for (Map.Entry<String, Boolean> entry : map.entrySet()) result.put(entry.getKey(), String.valueOf(entry.getValue()));
        return result;
    }
}
