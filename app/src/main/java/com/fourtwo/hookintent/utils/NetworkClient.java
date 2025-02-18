package com.fourtwo.hookintent.utils;

import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class NetworkClient {

    private final OkHttpClient client;
    private final Handler mainHandler;

    public NetworkClient() {
        client = new OkHttpClient();
        mainHandler = new Handler(Looper.getMainLooper());
    }

    public interface ReadMeCallback {
        void onSuccess(String data);
        void onFailure(String errorMessage);
    }

    public interface VersionCallback {
        void onVersionReceived(String version);
        void onFailure(String errorMessage);
    }

    public void getReadMe(String url, ReadMeCallback callback) {
        Request request = new Request.Builder()
                .url(url)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> callback.onFailure("请求失败：" + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String responseData = response.body().string();
                    mainHandler.post(() -> callback.onSuccess(responseData));
                } else {
                    mainHandler.post(() -> callback.onFailure("请求失败，响应不成功"));
                }
            }
        });
    }

    public void getVersion(String url, VersionCallback callback) {
        Request request = new Request.Builder()
                .url(url)
                .build();

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> callback.onFailure("请求失败：" + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                if (response.isSuccessful() && response.body() != null) {
                    String responseData = response.body().string();
                    try {
                        JSONArray jsonArray = new JSONArray(responseData);
                        if (jsonArray.length() > 0) {
                            JSONObject latestTag = jsonArray.getJSONObject(0);
                            String versionName = latestTag.getString("name");
                            String processedVersion = processVersion(versionName);
                            mainHandler.post(() -> callback.onVersionReceived(processedVersion));
                        } else {
                            mainHandler.post(() -> callback.onFailure("没有找到版本信息"));
                        }
                    } catch (JSONException e) {
                        mainHandler.post(() -> callback.onFailure("解析JSON失败：" + e.getMessage()));
                    }
                } else {
                    mainHandler.post(() -> callback.onFailure("请求失败，响应不成功"));
                }
            }
        });
    }

    private String processVersion(String version) {
        // 去掉前面的 "v" 和后面的 "-beta" 或其他后缀
        if (version.startsWith("v")) {
            version = version.substring(1);
        }
        int index = version.indexOf('-');
        if (index != -1) {
            version = version.substring(0, index);
        }
        return version;
    }
}
