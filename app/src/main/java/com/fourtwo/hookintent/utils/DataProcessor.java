package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;
import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.base.Extract;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.data.NavigationCaptureSource;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Converts each capture independently; one malformed capture never discards its batch. */
public class DataProcessor {
    private static final String TAG = "DataProcessor";
    private final Context context;
    private Map<String, Object> jsonData = new HashMap<>();
    private final IntentDuplicateChecker intentDuplicates = new IntentDuplicateChecker();
    private final IntentDuplicateChecker schemeDuplicates = new IntentDuplicateChecker();
    private String lastError;

    public DataProcessor(Context context) { this.context = context.getApplicationContext(); }
    public interface DataProcessedCallback { void onDataProcessed(ItemData itemData); }
    public void setJsonData(Map<String, Object> data) { jsonData = data == null ? new HashMap<>() : data; }
    public String getLastError() { return lastError; }
    private static final class DeduplicationCheckpoint {
        final String intent, scheme;
        DeduplicationCheckpoint(String intent, String scheme) { this.intent = intent; this.scheme = scheme; }
    }
    public Object checkpointDeduplication() {
        return new DeduplicationCheckpoint(intentDuplicates.checkpoint(), schemeDuplicates.checkpoint());
    }
    public void restoreDeduplication(Object checkpoint) {
        if (!(checkpoint instanceof DeduplicationCheckpoint)) throw new IllegalArgumentException("Invalid deduplication checkpoint");
        DeduplicationCheckpoint state = (DeduplicationCheckpoint) checkpoint;
        intentDuplicates.restore(state.intent);
        schemeDuplicates.restore(state.scheme);
    }

    public void processBundle(Bundle bundle, DataProcessedCallback callback) {
        if (bundle == null) return;
        String category = bundle.getString("category", "Unknown");
        NavigationCaptureSource navigation = NavigationCaptureSource.fromRecord(category, bundle);
        if (navigation != null) category = navigation.category();
        if (filterData(category, bundle)) return;
        String component;
        String targetPackage;
        String dataString = "";
        if ("Intent".equals(category)) {
            if (!jsonData.isEmpty() && intentDuplicates.isDuplicate(bundle)) return;
            component = present(bundle.getString("componentClassName"));
            targetPackage = present(bundle.getString("package"));
            if (component != null) targetPackage = component;
            String dataUri = present(bundle.getString("dataString"));
            if (component == null) {
                component = dataUri != null ? dataUri : bundle.getString("action", "");
                if (targetPackage == null && dataUri != null) targetPackage = SchemeResolver.findAppByUri(context, dataUri);
            }
            Object extras = bundle.get("intentExtras");
            if (extras instanceof ArrayList) dataString = Extract.extractIntentExtrasString((ArrayList<?>) extras);
        } else if ("Scheme".equals(category)) {
            if (!jsonData.isEmpty() && schemeDuplicates.isDuplicate(bundle)) return;
            String raw = bundle.getString("scheme_raw_url");
            if (raw == null) throw new IllegalArgumentException("Scheme记录缺少原始URL");
            Uri uri = Uri.parse(raw);
            bundle.putAll(DataConverter.convertUriToBundle(uri));
            targetPackage = SchemeResolver.findAppByUri(context, raw);
            // Opaque schemes (tel/mailto/urn), relative paths and intent: all retain their original form.
            component = uri.getAuthority() == null || uri.getScheme() == null ? raw
                    : uri.getScheme() + "://" + uri.getAuthority() + (uri.getPath() == null ? "" : uri.getPath());
            dataString = uri.getQuery();
        } else {
            targetPackage = bundle.getString("packageName");
            component = bundle.getString("title");
            if (navigation != null) {
                Bundle arguments = bundle.getBundle(Constants.NAVIGATION_ARGUMENTS);
                dataString = arguments == null || arguments.isEmpty() ? ""
                        : new JSONObject(JsonHandler.toMap(arguments)).toString();
            } else {
                dataString = bundle.getString("data");
            }
        }
        AppInfoHelper.AppInfo appInfo = AppInfoHelper.getAppInfo(context, targetPackage);
        Drawable icon = appInfo.getAppIcon();
        ItemData item = new ItemData(icon, appInfo.getAppName(), component, dataString,
                Extract.extractTime(bundle.getString("time")), Extract.calculateBundleDataSize(bundle) + " B",
                bundle, category, bundle.getString("stack_trace"), bundle.getString("uri"));
        if (callback != null) callback.onDataProcessed(item);
    }

    private static String present(String value) { return value == null || "null".equals(value) || value.isEmpty() ? null : value; }

    public void processReceivedData(Bundle data, DataProcessedCallback callback) {
        lastError = null;
        final JSONArray records;
        try {
            String payload = data == null ? null : data.getString("batch_data_binder");
            if (payload == null || payload.trim().isEmpty()) throw new IllegalArgumentException("捕获批次缺少内容");
            records = new JSONArray(payload);
        } catch (JSONException | RuntimeException error) {
            lastError = "捕获批次格式错误: " + error.getClass().getSimpleName();
            Log.e(TAG, lastError, error);
            return;
        }
        for (int index = 0; index < records.length(); index++) {
            Object checkpoint = checkpointDeduplication();
            try {
                JSONObject record = records.optJSONObject(index);
                if (record == null) throw new IllegalArgumentException("捕获记录不是JSON对象");
                processBundle(JsonHandler.toBundle(record), callback);
            } catch (RuntimeException error) {
                // Failed conversion must not make an unsaved record look like an accepted duplicate.
                restoreDeduplication(checkpoint);
                lastError = "第" + (index + 1) + "条捕获记录处理失败: " + error.getClass().getSimpleName();
                Log.e(TAG, lastError, error);
            }
        }
    }

    public boolean isFilterMatched(Map<String, Object> group, String key, String value) {
        if (value == null) return false;
        List<Map<String, Object>> rules = JsonHandler.getFilterValueJson(group.get(key));
        if (rules == null) return false;
        for (Map<String, Object> rule : rules) {
            if (Boolean.TRUE.equals(rule.get("type")) && rule.get("text") != null
                    && value.equalsIgnoreCase(String.valueOf(rule.get("text")))) return true;
        }
        return false;
    }

    private boolean filterData(String category, Bundle bundle) {
        if (category == null || jsonData.isEmpty()) return false;
        // Keep the existing Scheme noise filter while accepting opaque and serialized Intent URIs.
        if ("Scheme".equals(category)) {
            String raw = bundle.getString("scheme_raw_url");
            if (raw != null && Uri.parse(raw).getScheme() == null && !raw.startsWith("#Intent;")) return true;
        }
        Object value = jsonData.get(category);
        if (value == null) value = jsonData.get(category.toLowerCase(Locale.ROOT));
        Map<String, Object> group = JsonHandler.getFilterKeyJson(value);
        if (group == null) return false;
        for (String key : group.keySet()) {
            Object field = bundle.get(key);
            if (field == null && "Scheme".equalsIgnoreCase(category)) field = schemeField(bundle.getString("scheme_raw_url"), key);
            if (field != null && isFilterMatched(group, key, String.valueOf(field))) return true;
        }
        return false;
    }

    private Object schemeField(String raw, String key) {
        if (raw == null) return null;
        Uri uri = Uri.parse(raw);
        switch (key) {
            case "scheme_raw_url": return raw;
            case "scheme": return uri.getScheme();
            case "schemeSpecificPart": return uri.getSchemeSpecificPart();
            case "authority": return uri.getAuthority();
            case "userInfo": return uri.getUserInfo();
            case "host": return uri.getHost();
            case "port": return uri.getPort();
            case "path": return uri.getPath();
            case "query": return uri.getQuery();
            case "fragment": return uri.getFragment();
            case "lastPathSegment": return uri.getLastPathSegment();
            case "pathSegments": return uri.getPathSegments();
            default: return null;
        }
    }
}
