package com.fourtwo.hookintent.analysis;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class IntentData {

    @SuppressLint("SimpleDateFormat")
    public static Map<String, Object> convertIntentToMap(Intent intent, int requestCode, Bundle bundle) {
        Map<String, Object> intentMap = new HashMap<>();

        // Add basic intent information
        intentMap.put("time", new SimpleDateFormat("yyyy-MM-dd hh:mm:ss a").format(Calendar.getInstance().getTime()));
        intentMap.put("to", intent.getComponent() != null ? intent.getComponent().getClassName() : null);
        intentMap.put("action", intent.getAction());
        intentMap.put("clipData", intent.getClipData());
        intentMap.put("flags", intent.getFlags());
        intentMap.put("dataString", intent.getDataString());
        intentMap.put("type", intent.getType());
        intentMap.put("componentName", extract.extractComponentName(String.valueOf(intent.getComponent())));
        intentMap.put("scheme", intent.getScheme());
        intentMap.put("package", intent.getPackage());

        // Add categories
        Set<String> categories = intent.getCategories();
        if (categories != null) {
            intentMap.put("categories", categories);
        }

        // Add intent extras
        Bundle extras = intent.getExtras();
        if (extras != null) {
            List<Map<String, Object>> extrasList = new ArrayList<>();
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                Map<String, Object> extrasDetailMap = new HashMap<>();
                extrasDetailMap.put("key", key);
                extrasDetailMap.put("value", value != null ? value.toString(): "null");
                extrasDetailMap.put("class", value != null ? value.getClass().getName() : "null");
                // if (value == null || Boolean.parseBoolean(value.getClass().getName())){}
                extrasList.add(extrasDetailMap);
            }
            intentMap.put("intentExtras", extrasList);
        }

        // Add bundle
        if (bundle != null) {
            Map<String, Object> bundleMap = new HashMap<>();
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                bundleMap.put(key, value);
            }
            intentMap.put("bundle", bundleMap);
        }

        // Add request code
        intentMap.put("requestCode", requestCode);

        return intentMap;
    }

    // Overload for Intent only and source
    public static Map<String, Object> convertIntentToMap(Intent intent) {
        return convertIntentToMap(intent, -1, null);
    }

    // Overload for Intent, Bundle, and source
    public static Map<String, Object> convertIntentToMap(Intent intent, Bundle bundle) {
        return convertIntentToMap(intent, -1, bundle);
    }

    // Overload for Intent, requestCode, and source
    public static Map<String, Object> convertIntentToMap(Intent intent, int requestCode) {
        return convertIntentToMap(intent, requestCode, null);
    }

    public static String convertMapToString(Map<String, Object> map) {
        StringBuilder map_string = new StringBuilder();
        map_string.append("{");

        Set<Map.Entry<String, Object>> entrySet = map.entrySet();
        boolean isFirst = true;

        for (Map.Entry<String, Object> entry : entrySet) {
            if (!isFirst) {
                map_string.append(", ");
            } else {
                isFirst = false;
            }

            String key = entry.getKey();
            Object value = entry.getValue();

            map_string.append("\"").append(key).append("\": ");

            if (value instanceof Map) {
                // Recursive call for nested maps
                map_string.append(convertMapToString((Map<String, Object>) value));
            } else if (value instanceof String) {
                map_string.append("\"").append(value).append("\"");
            } else {
                map_string.append(value);
            }
        }

        map_string.append("}");
        return map_string.toString();
    }
}
