package com.fourtwo.hookintent.analysis;

import android.content.Context;
import android.util.Log;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.Map;

public class JsonHandler {

    private static final String TAG = "JsonHandler";
    private static final String FILE_NAME = "filter_data.json";
    private final Gson gson;
    private final Object lock = new Object();

    public JsonHandler() {
        this.gson = new Gson();
    }

    // 读取 JSON 文件并解析为 Java 对象
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
            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            return gson.fromJson(jsonString, type);
        }
    }

    // 将 Java 对象存储为 JSON
    public void writeJsonToFile(Context context, Map<String, Object> data) {
        synchronized (lock) {
            String jsonString = gson.toJson(data);
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
}
