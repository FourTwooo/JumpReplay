package com.fourtwo.hookintent.base;

import android.annotation.SuppressLint;
import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class DataConverter {

    @SuppressLint("SimpleDateFormat")
    public static Bundle convertIntentToBundle(Intent intent) {
        Bundle bundle = new Bundle();

        bundle.putString("action", intent.getAction());
        bundle.putParcelable("clipData", intent.getClipData());
        bundle.putInt("flags", intent.getFlags());
        bundle.putString("dataString", intent.getDataString());
        bundle.putString("type", intent.getType());
        bundle.putString("componentClassName", Extract.extractComponent(String.valueOf(intent.getComponent())));
        bundle.putString("component", intent.getComponent() != null ? intent.getComponent().getClassName() : null);
        bundle.putString("scheme", intent.getScheme());
        bundle.putString("package", intent.getPackage());

        // 添加 categories
        Set<String> categories = intent.getCategories();
        if (categories != null) {
            bundle.putStringArrayList("categories", new ArrayList<>(categories));
        }

        // 添加 intent extras
        Bundle extras = intent.getExtras();
        if (extras != null) {
            ArrayList<Map<String, Object>> extrasList = new ArrayList<>();
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                Map<String, Object> extrasDetailMap = new HashMap<>();
                extrasDetailMap.put("key", key);
                extrasDetailMap.put("value", value != null ? value.toString() : "null");
                extrasDetailMap.put("class", value != null ? value.getClass().getName() : "null");
                extrasList.add(extrasDetailMap);
            }
            bundle.putSerializable("intentExtras", extrasList); // 使用 Serializable 存储 ArrayList<Map>
        }

        return bundle;
    }

    public static Bundle convertUriToBundle(Uri uri) {
        Bundle bundle = new Bundle();

        if (uri != null) {
            bundle.putString("scheme", uri.getScheme());
            bundle.putString("schemeSpecificPart", uri.getSchemeSpecificPart());
            bundle.putString("authority", uri.getAuthority());
            bundle.putString("userInfo", uri.getUserInfo());
            bundle.putString("host", uri.getHost());
            bundle.putInt("port", uri.getPort());
            bundle.putString("path", uri.getPath());
            bundle.putString("query", uri.getQuery());
            bundle.putString("fragment", uri.getFragment());

            // 添加 path segments
            List<String> pathSegments = uri.getPathSegments();
            if (!pathSegments.isEmpty()) {
                bundle.putStringArrayList("pathSegments", new ArrayList<>(pathSegments));
            }

            // 添加 last path segment
            String lastPathSegment = uri.getLastPathSegment();
            if (lastPathSegment != null) {
                bundle.putString("lastPathSegment", lastPathSegment);
            }
        }

        return bundle;
    }

    public static String getCurrentProcessName(Context context) {
        int pid = android.os.Process.myPid();
        ActivityManager am = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        for (ActivityManager.RunningAppProcessInfo processInfo : am.getRunningAppProcesses()) {
            if (processInfo.pid == pid) {
                return processInfo.processName;
            }
        }
        return null;
    }
}
