package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ReleaseInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

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

    public void cancelAll() {
        client.dispatcher().cancelAll();
    }

    public interface VersionCallback {
        void onVersionReceived(String version);
        void onFailure(String errorMessage);
    }

    public interface JsonArrayCallback {
        void onSuccess(JSONArray data);
        void onFailure(String errorMessage);
    }

    public interface ReleaseListCallback {
        void onSuccess(List<ReleaseInfo> releases);
        void onFailure(String errorMessage);
    }

    public interface DownloadFileCallback {
        void onSuccess(File file);
        void onFailure(String errorMessage);
    }

    private Request buildJsonRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "JumpReplay")
                .build();
    }

    private Request buildFileRequest(String url) {
        return new Request.Builder()
                .url(url)
                .header("User-Agent", "JumpReplay")
                .build();
    }

    public void getVersion(String url, VersionCallback callback) {
        Request request = buildJsonRequest(url);

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> callback.onFailure("请求失败：" + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response responseResource = response) {
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

                } catch (Exception error) {
                    mainHandler.post(() -> callback.onFailure("请求处理失败：" + error.getMessage()));
                }
            }
        });
    }

    public void getJsonArray(String url, JsonArrayCallback callback) {
        Request request = buildJsonRequest(url);

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> callback.onFailure("请求失败：" + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response responseResource = response) {
                if (!response.isSuccessful() || response.body() == null) {
                    mainHandler.post(() -> callback.onFailure("请求失败，响应不成功"));
                    return;
                }

                String responseData = response.body().string().trim();

                try {
                    JSONArray result;

                    if (responseData.startsWith("[")) {
                        result = new JSONArray(responseData);
                    } else {
                        JSONObject obj = new JSONObject(responseData);
                        JSONArray data = obj.optJSONArray("data");
                        if (data != null) {
                            result = data;
                        } else {
                            JSONArray list = obj.optJSONArray("list");
                            if (list != null) {
                                result = list;
                            } else {
                                throw new JSONException("响应不是 JSONArray，且未找到 data/list 数组字段");
                            }
                        }
                    }

                    JSONArray finalResult = result;
                    mainHandler.post(() -> callback.onSuccess(finalResult));
                } catch (JSONException e) {
                    mainHandler.post(() -> callback.onFailure("解析JSON失败：" + e.getMessage()));
                }

                } catch (Exception error) {
                    mainHandler.post(() -> callback.onFailure("请求处理失败：" + error.getMessage()));
                }
            }
        });
    }

    public void getReleases(String url, ReleaseListCallback callback) {
        Request request = buildJsonRequest(url);

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                mainHandler.post(() -> callback.onFailure("请求失败：" + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response responseResource = response) {
                if (!response.isSuccessful() || response.body() == null) {
                    mainHandler.post(() -> callback.onFailure("请求失败，响应不成功"));
                    return;
                }

                try {
                    String responseData = response.body().string();
                    List<ReleaseInfo> releases = parseReleases(responseData);
                    mainHandler.post(() -> callback.onSuccess(releases));
                } catch (JSONException e) {
                    mainHandler.post(() -> callback.onFailure("解析JSON失败：" + e.getMessage()));
                }

                } catch (Exception error) {
                    mainHandler.post(() -> callback.onFailure("请求处理失败：" + error.getMessage()));
                }
            }
        });
    }

    public void getReleasesCached(Context context, String url, boolean forceRefresh, ReleaseListCallback callback) {
        String cachedJson = SharedPreferencesUtils.getStr(context, Constants.RELEASES_CACHE_JSON);
        long cachedTime = parseLongSafely(SharedPreferencesUtils.getStr(context, Constants.RELEASES_CACHE_TIME));
        long now = System.currentTimeMillis();

        boolean hasCache = cachedJson != null && !cachedJson.trim().isEmpty();
        boolean cacheFresh = hasCache && (now - cachedTime) < Constants.RELEASES_CACHE_EXPIRE_MS;

        if (!forceRefresh && cacheFresh) {
            try {
                List<ReleaseInfo> releases = parseReleases(cachedJson);
                mainHandler.post(() -> callback.onSuccess(releases));
                return;
            } catch (JSONException ignored) {
            }
        }

        Request request = buildJsonRequest(url);
        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (hasCache) {
                    try {
                        List<ReleaseInfo> releases = parseReleases(cachedJson);
                        mainHandler.post(() -> callback.onSuccess(releases));
                        return;
                    } catch (JSONException ignored) {
                    }
                }
                mainHandler.post(() -> callback.onFailure("请求失败：" + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response responseResource = response) {
                if (!response.isSuccessful() || response.body() == null) {
                    if (hasCache) {
                        try {
                            List<ReleaseInfo> releases = parseReleases(cachedJson);
                            mainHandler.post(() -> callback.onSuccess(releases));
                            return;
                        } catch (JSONException ignored) {
                        }
                    }
                    mainHandler.post(() -> callback.onFailure("请求失败，响应不成功"));
                    return;
                }

                try {
                    String responseData = response.body().string();
                    List<ReleaseInfo> releases = parseReleases(responseData);

                    SharedPreferencesUtils.putStr(context, Constants.RELEASES_CACHE_JSON, responseData);
                    SharedPreferencesUtils.putStr(context, Constants.RELEASES_CACHE_TIME, String.valueOf(System.currentTimeMillis()));

                    mainHandler.post(() -> callback.onSuccess(releases));
                } catch (JSONException e) {
                    if (hasCache) {
                        try {
                            List<ReleaseInfo> releases = parseReleases(cachedJson);
                            mainHandler.post(() -> callback.onSuccess(releases));
                            return;
                        } catch (JSONException ignored) {
                        }
                    }
                    mainHandler.post(() -> callback.onFailure("解析JSON失败：" + e.getMessage()));
                }

                } catch (Exception error) {
                    mainHandler.post(() -> callback.onFailure("请求处理失败：" + error.getMessage()));
                }
            }
        });
    }

    public void downloadFile(String url, File outputFile, DownloadFileCallback callback) {
        Request request = buildFileRequest(url);

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                if (outputFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outputFile.delete();
                }
                mainHandler.post(() -> callback.onFailure("下载失败：" + e.getMessage()));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) throws IOException {
                try (Response responseResource = response) {
                if (!response.isSuccessful() || response.body() == null) {
                    if (outputFile.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        outputFile.delete();
                    }
                    mainHandler.post(() -> callback.onFailure("下载失败，响应不成功"));
                    return;
                }

                if (outputFile.getParentFile() != null && !outputFile.getParentFile().exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    outputFile.getParentFile().mkdirs();
                }

                try (InputStream inputStream = response.body().byteStream();
                     FileOutputStream outputStream = new FileOutputStream(outputFile)) {

                    byte[] buffer = new byte[8192];
                    int len;
                    while ((len = inputStream.read(buffer)) != -1) {
                        outputStream.write(buffer, 0, len);
                    }
                    outputStream.flush();

                    mainHandler.post(() -> callback.onSuccess(outputFile));
                } catch (Exception e) {
                    if (outputFile.exists()) {
                        //noinspection ResultOfMethodCallIgnored
                        outputFile.delete();
                    }
                    mainHandler.post(() -> callback.onFailure("保存文件失败：" + e.getMessage()));
                }

                } catch (Exception error) {
                    mainHandler.post(() -> callback.onFailure("请求处理失败：" + error.getMessage()));
                }
            }
        });
    }

    private List<ReleaseInfo> parseReleases(String responseData) throws JSONException {
        JSONArray array = new JSONArray(responseData);
        List<ReleaseInfo> releases = new ArrayList<>();

        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.getJSONObject(i);

            String tagName = obj.optString("tag_name", "");
            String name = obj.optString("name", "");
            String body = obj.optString("body", "");
            String publishedAt = obj.optString("published_at", "");
            boolean prerelease = obj.optBoolean("prerelease", false);
            boolean draft = obj.optBoolean("draft", false);

            String downloadUrl = "";
            JSONArray assets = obj.optJSONArray("assets");
            if (assets != null) {
                for (int j = 0; j < assets.length(); j++) {
                    JSONObject asset = assets.getJSONObject(j);
                    String assetName = asset.optString("name", "");
                    String contentType = asset.optString("content_type", "");
                    String browserDownloadUrl = asset.optString("browser_download_url", "");

                    if ("application/vnd.android.package-archive".equals(contentType)
                            || assetName.endsWith(".apk")) {
                        downloadUrl = browserDownloadUrl;
                        break;
                    }
                }
            }

            releases.add(new ReleaseInfo(
                    tagName,
                    name,
                    body,
                    downloadUrl,
                    publishedAt,
                    prerelease,
                    draft
            ));
        }

        return releases;
    }

    private long parseLongSafely(String value) {
        try {
            if (value == null || value.trim().isEmpty()) {
                return 0L;
            }
            return Long.parseLong(value.trim());
        } catch (Exception ignored) {
            return 0L;
        }
    }

    private String processVersion(String version) {
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
