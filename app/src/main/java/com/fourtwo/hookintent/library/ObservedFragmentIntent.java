package com.fourtwo.hookintent.library;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import com.fourtwo.hookintent.data.Constants;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.Serializable;
import java.net.URISyntaxException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Native entry routes inferred from repeated matching deliveries and visible Fragment arguments. */
public final class ObservedFragmentIntent {
    public static final String RECORD_KEY = "observed_fragment_intent";
    public static final String NUMERIC_ARGUMENT_TYPES_KEY = "observed_fragment_numeric_types";
    private static final int MAX_URI_LENGTH = 8192;
    private static final int MAX_MAPPINGS = 24;
    private static final int URI_FLAGS = Intent.URI_INTENT_SCHEME;
    private static final int GRANT_FLAGS = Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION;

    private ObservedFragmentIntent() {}

    public static Intent delivery(Activity activity, Intent incoming) {
        if (incoming == null || incoming.getClipData() != null || incoming.getSelector() != null
                || incoming.getSourceBounds() != null
                || (incoming.getFlags() & GRANT_FLAGS) != 0 || "content".equals(incoming.getScheme())
                || "file".equals(incoming.getScheme())) return null;
        ComponentName actual = new ComponentName(activity, activity.getClass());
        if (incoming.getComponent() != null && !actual.equals(incoming.getComponent())) return null;
        Intent snapshot = new Intent(incoming).setComponent(actual);
        try {
            Bundle extras = snapshot.getExtras();
            if (extras == null || extras.size() > MAX_MAPPINGS || extras.isEmpty()) return null;
            return roundTrip(snapshot);
        } catch (RuntimeException | URISyntaxException | LinkageError error) {
            return null;
        }
    }

    private static Intent roundTrip(Intent snapshot) throws URISyntaxException {
        String uri = snapshot.toUri(URI_FLAGS);
        if (uri.length() > MAX_URI_LENGTH) return null;
        Intent encoded = Intent.parseUri(uri, URI_FLAGS);
        return snapshot.filterEquals(encoded) && snapshot.getFlags() == encoded.getFlags()
                && sameValues(snapshot.getExtras(), encoded.getExtras()) ? encoded : null;
    }

    public static Evidence associate(Activity activity, Intent delivered, String fragment, Map<String, Object> arguments) {
        if (delivered == null) return null;
        Bundle extras = delivered.getExtras();
        if (extras == null) return null;
        Map<String, String> bindings = new LinkedHashMap<>();
        for (String key : extras.keySet()) {
            Object sample = extras.get(key);
            if (!isBindable(sample)) continue;
            String argument = uniqueKey(arguments, sample);
            if (argument == null) continue;
            int repeated = 0;
            for (String other : extras.keySet()) if (sample.equals(extras.get(other))) repeated++;
            if (repeated == 1) bindings.put(key, argument);
        }
        if (bindings.isEmpty()) return null;
        try {
            PackageInfo info = activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0);
            return new Evidence(activity.getPackageName(), activity.getClass().getName(), fragment,
                    versionCode(info), info.lastUpdateTime, delivered.toUri(URI_FLAGS), bindings);
        } catch (PackageManager.NameNotFoundException error) {
            return null;
        }
    }

    private static boolean isIntegral(Object value) {
        return value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long;
    }

    private static boolean isBindable(Object value) {
        return (value instanceof String && !((String) value).isEmpty()) || isIntegral(value);
    }

    public static void retainNumericArgumentTypes(Bundle record, Bundle arguments) {
        Bundle types = new Bundle();
        for (String key : arguments.keySet()) {
            Object value = arguments.get(key);
            if (isIntegral(value)) types.putString(key, value.getClass().getSimpleName());
        }
        if (!types.isEmpty()) record.putBundle(NUMERIC_ARGUMENT_TYPES_KEY, types);
    }

    private static Bundle capturedArguments(Bundle record) {
        Bundle arguments = record.getBundle(Constants.NAVIGATION_ARGUMENTS);
        if (arguments == null) return null;
        Bundle types = record.getBundle(NUMERIC_ARGUMENT_TYPES_KEY);
        Bundle restored = new Bundle(arguments);
        for (String key : arguments.keySet()) {
            Object value = arguments.get(key);
            if (!isIntegral(value)) continue;
            // The capture archive stores JSON numbers without their original Java widths.
            // A legacy capture cannot reliably distinguish a small Long from an Integer.
            Serializable exact = integralValue(value.toString(), types == null ? "" : types.getString(key, ""));
            if (exact == null) restored.remove(key);
            else restored.putSerializable(key, exact);
        }
        return restored;
    }

    private static Serializable integralValue(String value, String type) {
        try {
            switch (type) {
                case "Byte": return Byte.valueOf(value);
                case "Short": return Short.valueOf(value);
                case "Integer": return Integer.valueOf(value);
                case "Long": return Long.valueOf(value);
                default: return null;
            }
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String uniqueKey(Map<String, Object> values, Object sample) {
        String match = null;
        for (Map.Entry<String, Object> field : values.entrySet()) {
            if (!sample.equals(field.getValue())) continue;
            if (match != null) return null;
            match = field.getKey();
        }
        return match;
    }

    static Evidence fromRecord(Context context, Bundle record, int sendingUid) throws Exception {
        String value = record.getString(RECORD_KEY);
        if (value == null || !Constants.FRAGMENT_CATEGORY.equals(record.getString("category"))) return null;
        Evidence evidence = Evidence.decode(value);
        if (!evidence.destinationPackage.equals(record.getString("packageName"))
                || !evidence.activity.equals(record.getString("from"))
                || !evidence.fragment.equals(record.getString("title"))) return null;
        Bundle captured = capturedArguments(record);
        if (captured == null || !evidence.matchesCapture(captured)) return null;
        PackageManager manager = context.getPackageManager();
        PackageInfo info = manager.getPackageInfo(evidence.destinationPackage, 0);
        if (sendingUid < 0 || info.applicationInfo == null || info.applicationInfo.uid != sendingUid
                || versionCode(info) != evidence.versionCode || info.lastUpdateTime != evidence.lastUpdateTime
                || !evidence.canLaunch(context)) return null;
        return evidence;
    }

    public static Intent lookup(Context context, Bundle record) throws Exception {
        String pkg = record.getString("packageName");
        String activity = record.getString("from");
        String fragment = record.getString("title");
        Bundle args = capturedArguments(record);
        if (pkg == null || activity == null || fragment == null || args == null) return null;
        PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
        long recordedUpdate = record.getLong("source_last_update", 0);
        if (recordedUpdate == 0 || recordedUpdate != info.lastUpdateTime) return null;
        List<Evidence> evidence = IntentLibrary.get(context).fragmentEvidence(
                pkg, activity, fragment, versionCode(info), info.lastUpdateTime);
        Intent intent = resolve(evidence, args);
        return intent != null && canLaunch(context, intent.getComponent()) ? intent : null;
    }

    static Intent resolve(List<Evidence> evidence, Bundle arguments) {
        Intent unique = null;
        for (int i = 0; i < evidence.size(); i++) {
            Evidence first = evidence.get(i);
            for (int j = i + 1; j < evidence.size(); j++) {
                Intent candidate = first.resolve(evidence.get(j), arguments);
                if (candidate == null) continue;
                if (unique != null && (!unique.filterEquals(candidate) || unique.getFlags() != candidate.getFlags()
                        || !sameValues(unique.getExtras(), candidate.getExtras()))) return null;
                unique = candidate;
            }
        }
        return unique;
    }

    private static boolean sameValues(Bundle left, Bundle right) {
        return left != null && right != null && left.keySet().equals(right.keySet())
                && left.keySet().stream().allMatch(key -> Objects.equals(left.get(key), right.get(key)));
    }

    private static boolean canLaunch(Context context, ComponentName target) {
        if (target == null || context.getPackageName().equals(target.getPackageName())) return false;
        try {
            ActivityInfo activity = context.getPackageManager().getActivityInfo(target, 0);
            return activity.exported && activity.enabled && activity.applicationInfo.enabled
                    && (activity.permission == null || context.checkSelfPermission(activity.permission)
                    == PackageManager.PERMISSION_GRANTED);
        } catch (PackageManager.NameNotFoundException error) {
            return false;
        }
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    public static final class Evidence {
        public final String destinationPackage;
        public final String activity;
        public final String fragment;
        public final long versionCode;
        public final long lastUpdateTime;
        private final String uri;
        private final Map<String, String> bindings;

        private Evidence(String destinationPackage, String activity, String fragment, long versionCode,
                long lastUpdateTime, String uri, Map<String, String> bindings) {
            this.destinationPackage = destinationPackage;
            this.activity = activity;
            this.fragment = fragment;
            this.versionCode = versionCode;
            this.lastUpdateTime = lastUpdateTime;
            this.uri = uri;
            this.bindings = Collections.unmodifiableMap(new LinkedHashMap<>(bindings));
        }

        public String encode() throws JSONException {
            JSONObject json = new JSONObject();
            json.put("package", destinationPackage);
            json.put("activity", activity);
            json.put("fragment", fragment);
            json.put("version", versionCode);
            json.put("updated", lastUpdateTime);
            json.put("uri", uri);
            json.put("bindings", new JSONObject(new java.util.TreeMap<>(bindings)));
            return json.toString();
        }

        public static Evidence decode(String data) throws JSONException, URISyntaxException {
            JSONObject json = new JSONObject(data);
            String pkg = json.getString("package");
            String activity = json.getString("activity");
            String fragment = json.getString("fragment");
            String uri = json.getString("uri");
            JSONObject fields = json.getJSONObject("bindings");
            if (pkg.isEmpty() || activity.isEmpty() || fragment.isEmpty() || uri.length() > MAX_URI_LENGTH
                    || fields.length() < 1 || fields.length() > MAX_MAPPINGS) throw new JSONException("Invalid delivery evidence");
            Intent intent = Intent.parseUri(uri, URI_FLAGS);
            if (!new ComponentName(pkg, activity).equals(intent.getComponent())) throw new JSONException("Invalid delivery target");
            Bundle extras = intent.getExtras();
            Map<String, String> bindings = new LinkedHashMap<>();
            for (java.util.Iterator<String> keys = fields.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (extras == null || !isBindable(extras.get(key))) throw new JSONException("Invalid delivery arguments");
                bindings.put(key, fields.getString(key));
            }
            return new Evidence(pkg, activity, fragment, json.getLong("version"), json.getLong("updated"), uri, bindings);
        }

        private boolean canLaunch(Context context) {
            return ObservedFragmentIntent.canLaunch(context, new ComponentName(destinationPackage, activity));
        }

        private boolean matchesCapture(Bundle arguments) throws URISyntaxException {
            Bundle delivered = Intent.parseUri(uri, URI_FLAGS).getExtras();
            return bindings.entrySet().stream().allMatch(field ->
                    Objects.equals(delivered.get(field.getKey()), arguments.get(field.getValue())));
        }

        private Intent resolve(Evidence other, Bundle arguments) {
            if (versionCode != other.versionCode || lastUpdateTime != other.lastUpdateTime
                    || !destinationPackage.equals(other.destinationPackage) || !activity.equals(other.activity)
                    || !fragment.equals(other.fragment)) return null;
            try {
                Intent first = Intent.parseUri(uri, URI_FLAGS);
                Intent second = Intent.parseUri(other.uri, URI_FLAGS);
                if (!first.filterEquals(second) || first.getFlags() != second.getFlags()) return null;
                Bundle left = first.getExtras();
                Bundle right = second.getExtras();
                if (!left.keySet().equals(right.keySet())) return null;
                Bundle resolved = new Bundle(left);
                boolean varied = false;
                for (String key : left.keySet()) {
                    Object sample = left.get(key);
                    Object otherSample = right.get(key);
                    String fragmentKey = bindings.get(key);
                    boolean sharedBinding = fragmentKey != null && fragmentKey.equals(other.bindings.get(key));
                    Object replacement = sharedBinding ? arguments.get(fragmentKey) : null;
                    if (Objects.equals(sample, otherSample)) {
                        // The samples do not prove how to change a mapped but constant extra.
                        if (sharedBinding && !Objects.equals(sample, replacement)) return null;
                        continue;
                    }
                    if (replacement == null || sample.getClass() != replacement.getClass()
                            || sample.getClass() != otherSample.getClass()) return null;
                    resolved.putSerializable(key, (Serializable) replacement);
                    varied = true;
                }
                return varied ? roundTrip(first.replaceExtras(resolved)) : null;
            } catch (URISyntaxException | RuntimeException error) {
                return null;
            }
        }
    }
}
