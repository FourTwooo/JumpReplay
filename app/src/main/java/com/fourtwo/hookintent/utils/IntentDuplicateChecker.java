package com.fourtwo.hookintent.utils;

import android.os.Bundle;
import com.fourtwo.hookintent.base.JsonHandler;
import org.json.JSONObject;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.TreeMap;

/** Preserves the original consecutive-event comparison without mutating captured data. */
public class IntentDuplicateChecker {
    private String lastBundleString = "";

    synchronized String checkpoint() { return lastBundleString; }
    synchronized void restore(String value) { lastBundleString = value; }

    public synchronized boolean isDuplicate(Bundle bundle) {
        if (bundle == null) return false;
        Bundle comparable = new Bundle(bundle);
        for (String key : new String[]{"stack_trace", "uri", "category", "time", "FunctionCall", "from"}) comparable.remove(key);
        String fingerprint = new JSONObject((Map<?, ?>) stable(JsonHandler.toMap(comparable))).toString();
        boolean duplicate = fingerprint.equals(lastBundleString);
        lastBundleString = fingerprint;
        return duplicate;
    }

    private Object stable(Object value) {
        if (value instanceof Map) {
            TreeMap<String, Object> map = new TreeMap<>();
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) map.put(String.valueOf(entry.getKey()), stable(entry.getValue()));
            return map;
        }
        if (value instanceof List) {
            ArrayList<Object> list = new ArrayList<>();
            for (Object item : (List<?>) value) list.add(stable(item));
            return list;
        }
        return value;
    }
}
