package com.fourtwo.hookintent.replay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;

import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.data.NavigationCaptureSource;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Builds an external Intent from an explicitly saved navigation entry and argument mapping. */
public final class NavigationReplay {
    private static final String PREFERENCES = "navigation_replay_bindings";

    private NavigationReplay() { }

    public static final class Binding {
        private final String intentUri;
        private final Map<String, String> argumentToExtra;

        private Binding(String intentUri, Map<String, String> argumentToExtra) {
            this.intentUri = intentUri;
            this.argumentToExtra = Collections.unmodifiableMap(new LinkedHashMap<>(argumentToExtra));
        }

        public String getIntentUri() {
            return intentUri;
        }

        public Map<String, String> getArgumentToExtra() {
            return argumentToExtra;
        }
    }

    public static Binding getBinding(Context context, ItemData capture) {
        String key = sourceKey(capture);
        if (key == null) return null;
        try {
            String raw = preferences(context).getString(key, null);
            if (raw == null) return null;
            JSONObject json = new JSONObject(raw);
            JSONObject mappings = json.getJSONObject("extras");
            Map<String, String> extras = new LinkedHashMap<>();
            for (Iterator<String> keys = mappings.keys(); keys.hasNext();) {
                String source = keys.next();
                extras.put(source, mappings.getString(source));
            }
            return new Binding(json.getString("intent"), extras);
        } catch (JSONException | ClassCastException invalid) {
            return null;
        }
    }

    public static void saveBinding(Context context, ItemData capture, String intentUri,
            Map<String, String> argumentToExtra) {
        String key = sourceKey(capture);
        if (key == null) throw new IllegalArgumentException("记录缺少应用内导航来源");
        Binding binding = new Binding(intentUri == null ? "" : intentUri.trim(), argumentToExtra);
        // Validate this route and its mappings against the selected record before replacing a saved rule.
        buildIntent(context, capture.getAppBundle(), binding);
        JSONObject json = new JSONObject();
        try {
            json.put("intent", binding.intentUri);
            json.put("extras", new JSONObject(binding.argumentToExtra));
        } catch (JSONException invalid) {
            throw new IllegalArgumentException("无法保存重放映射", invalid);
        }
        preferences(context).edit().putString(key, json.toString()).apply();
    }

    public static void removeBinding(Context context, ItemData capture) {
        String key = sourceKey(capture);
        if (key != null) preferences(context).edit().remove(key).apply();
    }

    public static ItemData prepare(Context context, ItemData capture) {
        Binding binding = getBinding(context, capture);
        if (binding == null) return null;
        Bundle record = capture.getAppBundle();
        try {
            Intent intent = buildIntent(context, record, binding);
            Bundle payload = DataConverter.convertIntentToBundle(intent);
            if (!payload.containsKey(IntentPayloadCodec.KEY)) return null;
            Bundle draft = new Bundle(record);
            draft.putAll(payload);
            // The new Intent is built from standard URI data and scalar mappings in this process.
            // The original capture's APK and SDK identity does not describe this synthesized payload.
            PackageInfo target = context.getPackageManager().getPackageInfo(intent.getComponent().getPackageName(), 0);
            draft.remove("source_uid");
            draft.remove("navigation_replay");
            draft.putInt("source_app_uid", target.applicationInfo.uid);
            draft.putLong("source_last_update", target.lastUpdateTime);
            draft.putInt("capture_sdk", Build.VERSION.SDK_INT);
            String uri = intent.toUri(Intent.URI_INTENT_SCHEME);
            draft.putString("uri", uri);
            draft.putString("category", "Intent");
            return new ItemData(capture.getIcon(), capture.getAppName(), capture.getItem_from(),
                    capture.getItem_data(), capture.getTimestamp(), capture.getDataSize(), draft,
                    "Intent", capture.getStackTrace(), uri);
        } catch (PackageManager.NameNotFoundException | RuntimeException unavailable) {
            return null;
        }
    }

    private static SharedPreferences preferences(Context context) {
        return context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE);
    }

    private static String sourceKey(ItemData capture) {
        if (capture == null) return null;
        Bundle record = capture.getAppBundle();
        NavigationCaptureSource mechanism = NavigationCaptureSource.fromRecord(capture.getCategory(), record);
        if (mechanism == null) return null;
        String packageName = record.getString("packageName");
        String host = record.getString("from");
        String destination = record.getString("title");
        if (TextUtils.isEmpty(packageName) || TextUtils.isEmpty(host) || TextUtils.isEmpty(destination)) return null;
        return new JSONArray().put(mechanism.category()).put(packageName).put(host).put(destination).toString();
    }

    private static Intent buildIntent(Context context, Bundle record, Binding binding) {
        if (binding.intentUri.isEmpty()) throw new IllegalArgumentException("请输入 Intent URI 或深层链接");
        Intent intent;
        try {
            intent = Intent.parseUri(binding.intentUri, 0);
        } catch (URISyntaxException error) {
            throw new IllegalArgumentException("Intent URI 格式错误", error);
        }

        String source = record.getString("packageName");
        ComponentName component = intent.getComponent();
        if ((component != null && !source.equals(component.getPackageName()))
                || (intent.getPackage() != null && !source.equals(intent.getPackage()))) {
            throw new IllegalArgumentException("Intent 入口必须属于这条导航记录的应用");
        }
        if (intent.getSelector() != null) throw new IllegalArgumentException("请绑定没有选择器的直接 Intent 入口");
        intent.setPackage(source);
        ActivityInfo activity = accessibleActivity(context, intent);
        intent.setComponent(new ComponentName(activity.packageName, activity.name));

        Bundle arguments = record.getBundle(Constants.NAVIGATION_ARGUMENTS);
        Bundle template = intent.getExtras();
        Set<String> destinationKeys = new HashSet<>();
        for (Map.Entry<String, String> mapping : binding.argumentToExtra.entrySet()) {
            String argument = mapping.getKey();
            String extra = mapping.getValue();
            if (TextUtils.isEmpty(argument) || TextUtils.isEmpty(extra) || !destinationKeys.add(extra)) {
                throw new IllegalArgumentException("导航参数和 Intent 参数名不能为空，每个 Intent 参数只能映射一次");
            }
            Object value = arguments == null ? null : arguments.get(argument);
            if (!isScalar(value)) throw new IllegalArgumentException("找不到可映射的标量导航参数：" + argument);
            Object existing = template == null ? null : template.get(extra);
            intent.putExtra(extra, typedValue(value, existing, extra));
        }
        return intent;
    }

    private static ActivityInfo accessibleActivity(Context context, Intent intent) {
        ResolveInfo resolved = context.getPackageManager().resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
        ActivityInfo activity = resolved == null ? null : resolved.activityInfo;
        if (activity == null || !intent.getPackage().equals(activity.packageName)
                || activity.applicationInfo == null || !activity.applicationInfo.enabled || !activity.enabled) {
            throw new IllegalArgumentException("找不到此应用中可用的 Activity 入口");
        }
        boolean sameUid = activity.applicationInfo.uid == context.getApplicationInfo().uid;
        if (!sameUid && (!activity.exported || (!TextUtils.isEmpty(activity.permission)
                && context.checkSelfPermission(activity.permission) != PackageManager.PERMISSION_GRANTED))) {
            throw new IllegalArgumentException("当前应用无权直接启动这个 Activity");
        }
        return activity;
    }

    private static boolean isScalar(Object value) {
        if (value instanceof Double) return Double.isFinite((Double) value);
        if (value instanceof Float) return Float.isFinite((Float) value);
        return value instanceof String || value instanceof Boolean || value instanceof Character
                || value instanceof Integer || value instanceof Long || value instanceof Short || value instanceof Byte;
    }

    private static Serializable typedValue(Object value, Object existing, String extra) {
        // A typed placeholder in the template declares what the receiver expects.
        if (existing == null || (existing.getClass() == value.getClass() && isScalar(existing))) {
            return (Serializable) value;
        }
        String text = String.valueOf(value);
        try {
            if (existing instanceof String) return text;
            String number = text.trim();
            if (existing instanceof Integer) return Integer.valueOf(number);
            if (existing instanceof Long) return Long.valueOf(number);
            if (existing instanceof Short) return Short.valueOf(number);
            if (existing instanceof Byte) return Byte.valueOf(number);
            if (existing instanceof Float && Float.isFinite(Float.parseFloat(number))) return Float.valueOf(number);
            if (existing instanceof Double && Double.isFinite(Double.parseDouble(number))) return Double.valueOf(number);
            if (existing instanceof Character && text.length() == 1) return text.charAt(0);
            if (existing instanceof Boolean && ("true".equalsIgnoreCase(text) || "false".equalsIgnoreCase(text))) {
                return Boolean.valueOf(text);
            }
        } catch (NumberFormatException invalid) {
            throw new IllegalArgumentException("导航参数与 Intent 参数类型不匹配：" + extra, invalid);
        }
        throw new IllegalArgumentException("导航参数与 Intent 参数类型不匹配：" + extra);
    }
}
