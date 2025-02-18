package com.fourtwo.hookintent.base;

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
    public static Map<String, Object> convertIntentToMap(Intent intent) {
        Map<String, Object> intentMap = new HashMap<>();

        // Add basic intent information
        intentMap.put("time", new SimpleDateFormat("yyyy-MM-dd hh:mm:ss a").format(Calendar.getInstance().getTime()));
        intentMap.put("to", intent.getComponent() != null ? intent.getComponent().getClassName() : null);

        intentMap.put("action", intent.getAction());
        intentMap.put("clipData", intent.getClipData());
        intentMap.put("flags", intent.getFlags());
        intentMap.put("dataString", intent.getDataString());
        intentMap.put("type", intent.getType());
        intentMap.put("componentName", Extract.extractComponentName(String.valueOf(intent.getComponent())));
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
                extrasList.add(extrasDetailMap);
            }
            intentMap.put("intentExtras", extrasList);
        }

        return intentMap;
    }

}
