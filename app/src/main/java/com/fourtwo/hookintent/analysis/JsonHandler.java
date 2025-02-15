package com.fourtwo.hookintent.analysis;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class JsonHandler {

    private static final String TAG = "JsonHandler";
    private static final String FILE_NAME = "filter_data.json";
    private final Object lock = new Object();

    public JsonHandler() {
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> getFilterKeyJson(Object jsonData) {
        if (jsonData instanceof JSONObject) {
            return toMap((JSONObject) jsonData);
        }
        return (Map<String, Object>) jsonData;
    }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> getFilterValueJson(Object jsonData) {
        if (jsonData instanceof JSONArray) {
            return toList((JSONArray) jsonData);
        }

        return (List<Map<String, Object>>) jsonData;
    }

    // 读取 JSON 文件并解析为 Map<String, Object>
    public Map<String, Object> readJsonFromFile(Context context) {
        synchronized (lock) {
            File file = new File(context.getExternalFilesDir(null), FILE_NAME);
            if (!file.exists()) {
                Log.e(TAG, "File not found in external files dir. Attempting to copy from assets.");
                copyFromAssetsToExternalFilesDir(context);
            }
            String jsonString = loadJSONFromFilesDir(context);
            if (jsonString == null) {
                return null;
            }
            try {
                JSONObject jsonObject = new JSONObject(jsonString);
                return toMap(jsonObject);
            } catch (JSONException e) {
                Log.e(TAG, "Error parsing JSON", e);
                return null;
            }
        }
    }

    // 将 Map<String, Object> 存储为 JSON
    public void writeJsonToFile(Context context, Map<String, Object> data) {
        synchronized (lock) {
            JSONObject jsonObject = new JSONObject(data);
            String jsonString = jsonObject.toString();
            File file = new File(context.getExternalFilesDir(null), FILE_NAME);
            try (FileOutputStream fos = new FileOutputStream(file)) {
                fos.write(jsonString.getBytes(StandardCharsets.UTF_8));
                fos.flush();
            } catch (IOException e) {
                e.printStackTrace();
                Log.e(TAG, "Error writing to file: " + file.getAbsolutePath());
            }
        }
    }

    // 从应用的外部文件目录加载 JSON 文件
    private String loadJSONFromFilesDir(Context context) {
        File file = new File(context.getExternalFilesDir(null), FILE_NAME);
        if (!file.exists()) {
            Log.e(TAG, "File not found: " + file.getAbsolutePath());
            return null;
        }

        String json = null;
        try (FileInputStream fis = new FileInputStream(file)) {
            int size = fis.available();
            byte[] buffer = new byte[size];
            fis.read(buffer);
            json = new String(buffer, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            ex.printStackTrace();
            Log.e(TAG, "Error reading file: " + file.getAbsolutePath());
        }
        return json;
    }

    // 复制 assets 中的 JSON 文件到外部文件目录
    private void copyFromAssetsToExternalFilesDir(Context context) {
        File file = new File(context.getExternalFilesDir(null), FILE_NAME);
        try (InputStream is = context.getAssets().open(FILE_NAME);
             FileOutputStream fos = new FileOutputStream(file)) {
            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
        } catch (IOException e) {
            e.printStackTrace();
            Log.e(TAG, "Error copying file from assets to external files dir");
        }
    }

    // 辅助方法：将 JSONObject 转换为 Map<String, Object>
    private static Map<String, Object> toMap(JSONObject jsonObject) {
        Map<String, Object> map = new HashMap<>();
        Iterator<String> keys = jsonObject.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            try {
                Object value = jsonObject.get(key);
                if (value instanceof JSONObject) {
                    map.put(key, toMap((JSONObject) value));
                } else if (value instanceof JSONArray) {
                    map.put(key, toList((JSONArray) value));
                } else {
                    map.put(key, value);
                }
            } catch (JSONException e) {
                Log.e(TAG, "Error converting JSONObject to Map", e);
            }
        }
        return map;
    }

    // 辅助方法：将 JSONArray 转换为 List<Map<String, Object>>
    private static List<Map<String, Object>> toList(JSONArray array) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            try {
                JSONObject jsonObject = array.getJSONObject(i);
                list.add(toMap(jsonObject));
            } catch (JSONException e) {
                Log.e(TAG, "Error converting JSONArray to List", e);
            }
        }
        return list;
    }
}

