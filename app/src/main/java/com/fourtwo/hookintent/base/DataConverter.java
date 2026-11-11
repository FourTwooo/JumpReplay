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
import java.lang.reflect.Array;

public class DataConverter {

    @SuppressLint("SimpleDateFormat")
    public static Bundle convertIntentToBundle(Intent intent) {
        Bundle bundle = new Bundle();

        try {
            bundle.putString(IntentPayloadCodec.KEY, IntentPayloadCodec.encode(intent));
        } catch (RuntimeException e) {
            bundle.putString("intent_parcel_error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        bundle.putString("action", intent.getAction());
        bundle.putParcelable("clipData", intent.getClipData());
        bundle.putInt("flags", intent.getFlags());
        bundle.putString("dataString", intent.getDataString());
        bundle.putString("type", intent.getType());
        bundle.putString("componentClassName", Extract.extractComponent(String.valueOf(intent.getComponent())));
        bundle.putString("component", intent.getComponent() != null ? intent.getComponent().getClassName() : null);
        bundle.putString("componentPackageName", intent.getComponent() != null ? intent.getComponent().getPackageName() : null);
        bundle.putString("scheme", intent.getScheme());
        bundle.putString("package", intent.getPackage());

        // 添加 categories
        Set<String> categories = intent.getCategories();
        if (categories != null) {
            bundle.putStringArrayList("categories", new ArrayList<>(categories));
        }

        // 添加 intent extras
        try {
        Bundle extras = intent.getExtras();
        if (extras != null) {
            ArrayList<Map<String, Object>> extrasList = new ArrayList<>();
            for (String key : extras.keySet()) {
                Map<String, Object> extrasDetailMap = new HashMap<>();
                extrasDetailMap.put("key", key);
                try {
                    Object value = extras.get(key);
                    extrasDetailMap.put("value", jsonValue(value));
                    extrasDetailMap.put("class", value != null ? value.getClass().getName() : "null");
                } catch (RuntimeException e) {
                    extrasDetailMap.put("value", "<无法读取: " + e.getClass().getSimpleName() + ">");
                    extrasDetailMap.put("class", "unavailable");
                }
                extrasList.add(extrasDetailMap);
            }
            bundle.putSerializable("intentExtras", extrasList); // 使用 Serializable 存储 ArrayList<Map>
        }
        } catch (RuntimeException e) {
            bundle.putString("intentExtras_error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        return bundle;
    }

    private static Object jsonValue(Object value) {
        return jsonValue(value, 0);
    }

    private static Object jsonValue(Object value, int depth) {
        if (value == null) return null;
        if (depth > 8) return "<嵌套内容见完整Intent>";
        if (value instanceof Double && !Double.isFinite((Double) value)) return value.toString();
        if (value instanceof Float && !Float.isFinite((Float) value)) return value.toString();
        if (value instanceof String || value instanceof Number || value instanceof Boolean) return value;
        if (value instanceof Character) return value.toString();
        if (value.getClass().isArray()) {
            ArrayList<Object> values = new ArrayList<>();
            for (int i = 0; i < Array.getLength(value); i++) values.add(jsonValue(Array.get(value, i), depth + 1));
            return values;
        }
        if (value instanceof List) {
            ArrayList<Object> values = new ArrayList<>();
            for (Object element : (List<?>) value) values.add(jsonValue(element, depth + 1));
            return values;
        }
        return String.valueOf(value);
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
        List<ActivityManager.RunningAppProcessInfo> processes = am == null ? null : am.getRunningAppProcesses();
        if (processes == null) return null;
        for (ActivityManager.RunningAppProcessInfo processInfo : processes) {
            if (processInfo.pid == pid) {
                return processInfo.processName;
            }
        }
        return null;
    }
}
