package com.fourtwo.hookintent.library;

import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.utils.AppInfoHelper;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** An opt-in index of Intent observations independent of the capture archive. */
public final class IntentLibrary {
    public static final String PREFERENCE = "intent_library_enabled";
    static final ExecutorService UI_IO = Executors.newSingleThreadExecutor();
    static final int PAGE_SIZE = 60;
    private static final String TAG = "IntentLibrary";
    private static final int MAX_EXTRAS = 64;
    private static final int MAX_SAMPLE_TEXT = 1024;
    private static final int MAX_PARAMETER_EXAMPLES = 4;
    private static final int MAX_URI = 16_384;
    private static final int MAX_EVIDENCE = 65_536;
    private static IntentLibrary instance;

    public static synchronized IntentLibrary get(Context context) {
        if (instance == null) instance = new IntentLibrary(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Store store;
    private final MutableLiveData<Boolean> changes = new MutableLiveData<>();

    private IntentLibrary(Context context) {
        this.context = context;
        store = new Store(context);
    }

    public boolean enabled() { return SharedPreferencesUtils.getBoolean(context, PREFERENCE); }

    public void setEnabled(boolean enabled) {
        SharedPreferencesUtils.putBoolean(context, PREFERENCE, enabled);
        context.getContentResolver().notifyChange(Constants.IN_APP_NAVIGATION_URI, null);
    }

    LiveData<Boolean> changes() { return changes; }

    /** Called after publishing a batch of newly committed captures. */
    public void ingest(List<ItemData> records, List<Bundle> fragmentEvidence, int sendingUid) {
        if (!enabled() || (records.isEmpty() && fragmentEvidence.isEmpty())) return;
        try {
            SQLiteDatabase db = store.getWritableDatabase();
            db.beginTransaction();
            boolean changed = false;
            try {
                Map<String, Version> versions = new HashMap<>();
                for (ItemData item : records) {
                    if (!"Intent".equals(item.getCategory())) continue;
                    try { changed |= observe(db, item.getAppBundle(), versions); }
                    catch (Exception error) { Log.w(TAG, "Cannot index an Intent capture", error); }
                }
                for (Bundle record : fragmentEvidence) {
                    try { changed |= observeFragment(db, record, sendingUid); }
                    catch (Exception error) { Log.w(TAG, "Cannot index a Fragment delivery", error); }
                }
                db.setTransactionSuccessful();
            } finally { db.endTransaction(); }
            if (changed) changes.postValue(true);
        } catch (RuntimeException error) {
            // A cache failure must never change the outcome of persisting a capture.
            Log.w(TAG, "Cannot update the Intent index", error);
        }
    }

    private boolean observeFragment(SQLiteDatabase db, Bundle record, int sendingUid) throws Exception {
        ObservedFragmentIntent.Evidence evidence = ObservedFragmentIntent.fromRecord(context, record, sendingUid);
        if (evidence == null) return false;
        String payload = evidence.encode();
        if (payload.length() > MAX_EVIDENCE) return false;
        ContentValues values = new ContentValues();
        values.put("package_name", evidence.destinationPackage);
        values.put("activity", evidence.activity);
        values.put("fragment", evidence.fragment);
        values.put("version", evidence.versionCode);
        values.put("updated", evidence.lastUpdateTime);
        values.put("payload", payload);
        values.put("seen_at", System.currentTimeMillis());
        db.insertWithOnConflict("fragment_evidence", null, values, SQLiteDatabase.CONFLICT_REPLACE);
        return true;
    }

    public List<ObservedFragmentIntent.Evidence> fragmentEvidence(String destinationPackage,
            String activity, String fragment, long versionCode, long lastUpdateTime) throws Exception {
        if (!new File(store.getDatabaseName()).isFile()) return Collections.emptyList();
        List<ObservedFragmentIntent.Evidence> values = new ArrayList<>();
        try (Cursor rows = store.getReadableDatabase().query("fragment_evidence", new String[]{"payload"},
                "package_name=? AND activity=? AND fragment=? AND version=? AND updated=?",
                new String[]{destinationPackage, activity, fragment, String.valueOf(versionCode),
                        String.valueOf(lastUpdateTime)}, null, null, "seen_at DESC, id DESC", "16")) {
            while (rows.moveToNext()) values.add(ObservedFragmentIntent.Evidence.decode(rows.getString(0)));
        }
        return values;
    }

    List<Observation> page(String search, int limit, int offset) {
        String pattern = "%" + search.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
        return query(search.isEmpty() ? null : "summary LIKE ? ESCAPE '\\'",
                search.isEmpty() ? null : new String[]{pattern}, limit + " OFFSET " + offset);
    }

    public List<Observation> observations(String destinationPackage, long destinationVersionCode) {
        return query("package_name=? AND target_version=?",
                new String[]{destinationPackage, String.valueOf(destinationVersionCode)}, "100");
    }

    List<Observation> observationsForApp(String packageName) {
        if (packageName.isEmpty() || !new File(store.getDatabaseName()).isFile()) return Collections.emptyList();
        List<Observation> observations = query("package_name=? OR package_name=''", new String[]{packageName}, null);
        observations.removeIf(observation -> observation.destinationPackage.isEmpty()
                && !packageName.equals(observation.sourcePackage));
        return observations;
    }

    private List<Observation> query(String selection, String[] args, String limit) {
        List<Observation> values = new ArrayList<>();
        try (Cursor rows = store.getReadableDatabase().query("observations",
                new String[]{"id", "payload", "hits", "seen_at"}, selection, args, null, null,
                "seen_at DESC, id DESC", limit)) {
            while (rows.moveToNext()) {
                try {
                    values.add(new Observation(rows.getLong(0), new JSONObject(rows.getString(1)),
                            rows.getInt(2), rows.getLong(3)));
                } catch (Exception error) { Log.w(TAG, "Cannot read cached Intent metadata", error); }
            }
        }
        return values;
    }

    void delete(Observation entry) {
        store.getWritableDatabase().delete("observations", "id=?", new String[]{String.valueOf(entry.id)});
        changes.postValue(true);
    }

    void clear() {
        SQLiteDatabase db = store.getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("observations", null, null);
            db.delete("fragment_evidence", null, null);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
        changes.postValue(true);
    }

    private boolean observe(SQLiteDatabase db, Bundle record, Map<String, Version> versions) throws Exception {
        if (record == null) return false;
        String source = value(record.getString("packageName"));
        String componentPackage = value(record.getString("componentPackageName"));
        String componentClass = value(record.getString("component"));
        String requestedPackage = value(record.getString("package"));
        String function = value(record.getString("FunctionCall"));
        boolean delivered = Arrays.asList("Activity.performCreate", "ActivityThread.deliverNewIntents",
                "Activity.onNewIntent").contains(function) && !source.isEmpty() && !value(record.getString("from")).isEmpty();
        String destination = delivered ? source : componentPackage;
        String activity = delivered ? record.getString("from") : componentClass;
        String action = value(record.getString("action"));
        String data = clipped(value(record.getString("dataString")), MAX_URI);
        String type = value(record.getString("type"));
        String raw = value(record.getString("uri"));
        if (!delivered && destination.isEmpty() && raw.length() <= MAX_URI) {
            // PackageManager can resolve a route at indexing time. This is still a request sample.
            ResolveInfo resolved = resolve(raw);
            if (resolved != null && resolved.activityInfo != null) {
                destination = value(resolved.activityInfo.packageName);
                activity = value(resolved.activityInfo.name);
            }
        }
        if (destination.isEmpty()) destination = requestedPackage;
        if (destination.isEmpty() && action.isEmpty() && data.isEmpty()) return false;
        if (context.getPackageName().equals(destination)) return false;

        long sourceUpdate = longValue(record, "source_last_update", 0);
        Version sourceVersion = version(versions, source, sourceUpdate);
        Version destinationVersion = source.equals(destination) ? sourceVersion : version(versions, destination, 0);
        if (delivered && record.containsKey("library_destination_version_code")
                && longValue(record, "library_destination_last_update", 0) == sourceUpdate) {
            destinationVersion = new Version(longValue(record, "library_destination_version_code", -1),
                    value(record.getString("library_destination_version_name")), sourceUpdate);
            sourceVersion = destinationVersion;
        }

        List<Parameter> extras = parameters(record);
        List<String> categories = record.getStringArrayList("categories");
        TreeSet<String> categorySet = new TreeSet<>();
        if (categories != null) categorySet.addAll(categories);
        int flags = record.getInt("flags");
        String safeUri = safeUri(record, raw, extras, action, data, type, flags, categorySet);
        JSONArray fields = new JSONArray(), shapes = new JSONArray();
        for (Parameter extra : extras) {
            fields.put(new JSONArray().put(extra.name).put(extra.type).put(extra.example));
            shapes.put(new JSONArray().put(extra.name).put(extra.type));
        }
        JSONArray qualifiers = new JSONArray(categorySet);
        JSONObject payload = new JSONObject().put("package", destination).put("activity", activity)
                .put("componentPackage", componentPackage).put("componentClass", componentClass)
                .put("targetCode", destinationVersion.code).put("targetName", destinationVersion.name)
                .put("targetUpdated", destinationVersion.updated)
                .put("source", source).put("sourceCode", sourceVersion.code).put("sourceName", sourceVersion.name)
                .put("sourceUpdated", sourceVersion.updated)
                .put("action", action).put("data", data).put("type", type).put("flags", flags)
                .put("delivered", delivered).put("categories", qualifiers).put("extras", fields)
                .put("extrasComplete", complete(record, extras)).put("uri", safeUri);
        JSONArray shape = new JSONArray().put(destination).put(activity).put(destinationVersion.code)
                .put(destinationVersion.updated).put(source).put(sourceVersion.code).put(sourceVersion.updated)
                .put(delivered).put(requestedPackage).put(action).put(uriShape(data)).put(type).put(flags)
                .put(qualifiers).put(shapes);
        String addressedClass = componentClass.startsWith(".") ? componentPackage + componentClass : componentClass;
        if (delivered && destination.equals(componentPackage) && !addressedClass.isEmpty()
                && !activity.equals(addressedClass)) shape.put(addressedClass);

        long timestamp = System.currentTimeMillis();
        String shapeKey = shape.toString();
        int previousHits = 0;
        try (Cursor row = db.query("observations", new String[]{"hits", "payload"}, "shape=?", new String[]{shapeKey},
                null, null, null)) {
            if (row.moveToFirst()) {
                previousHits = row.getInt(0);
                retainExamples(fields, row.getString(1));
            }
        }
        ContentValues values = new ContentValues();
        values.put("package_name", destination);
        values.put("target_version", destinationVersion.code);
        String appLabel = destination.isEmpty() ? "" : AppInfoHelper.getAppInfo(context, destination).getAppName();
        values.put("summary", appLabel + " " + destination + " " + activity + " " + source + " " + action + " " + data + " " + fields);
        values.put("payload", payload.toString());
        values.put("seen_at", timestamp);
        values.put("hits", previousHits == Integer.MAX_VALUE ? previousHits : previousHits + 1);
        if (previousHits == 0) {
            values.put("shape", shapeKey);
            db.insertOrThrow("observations", null, values);
        } else db.update("observations", values, "shape=?", new String[]{shapeKey});
        return true;
    }

    private static void retainExamples(JSONArray fields, String previous) throws JSONException {
        JSONArray old;
        try { old = new JSONObject(previous).optJSONArray("extras"); }
        catch (JSONException unreadable) { return; }
        if (old == null || old.length() != fields.length()) return;
        for (int i = 0; i < fields.length(); i++) {
            JSONArray field = fields.getJSONArray(i);
            JSONArray previousField = old.optJSONArray(i);
            if (previousField == null || !field.getString(0).equals(previousField.optString(0))
                    || !field.getString(1).equals(previousField.optString(1))) continue;
            LinkedHashSet<String> samples = new LinkedHashSet<>();
            samples.add(field.getString(2));
            samples.add(previousField.optString(2));
            JSONArray older = previousField.optJSONArray(3);
            for (int j = 0; older != null && j < older.length() && samples.size() < MAX_PARAMETER_EXAMPLES; j++) {
                samples.add(older.optString(j));
            }
            if (samples.size() > 1) field.put(3, new JSONArray(samples));
        }
    }

    private ResolveInfo resolve(String raw) {
        if (raw.isEmpty()) return null;
        try {
            Intent intent = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
            List<ResolveInfo> matches = context.getPackageManager().queryIntentActivities(intent,
                    PackageManager.MATCH_DEFAULT_ONLY);
            return matches.size() == 1 ? matches.get(0) : null;
        } catch (Exception error) { return null; }
    }

    private Version version(Map<String, Version> versions, String pkg, long expectedUpdate) {
        if (pkg.isEmpty()) return Version.UNKNOWN;
        Version current = versions.computeIfAbsent(pkg, key -> {
            try {
                PackageInfo info = context.getPackageManager().getPackageInfo(key, 0);
                long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
                return new Version(code, value(info.versionName), info.lastUpdateTime);
            } catch (PackageManager.NameNotFoundException error) { return Version.UNKNOWN; }
        });
        return expectedUpdate == 0 || expectedUpdate == current.updated ? current : new Version(-1, "", expectedUpdate);
    }

    static JSONArray uriShape(String data) {
        Uri uri = Uri.parse(data);
        TreeSet<String> queryKeys = new TreeSet<>();
        if (uri.isHierarchical()) queryKeys.addAll(uri.getQueryParameterNames());
        return new JSONArray().put(uri.getScheme()).put(uri.getEncodedAuthority()).put(uri.isHierarchical()
                ? uri.getEncodedPath() : uri.getEncodedSchemeSpecificPart()).put(new JSONArray(queryKeys));
    }

    private static List<Parameter> parameters(Bundle record) {
        Object raw = record.get("intentExtras");
        if (!(raw instanceof List)) return Collections.emptyList();
        List<Parameter> values = new ArrayList<>();
        for (Object entry : (List<?>) raw) {
            if (values.size() >= MAX_EXTRAS) break;
            Map<String, Object> detail = JsonHandler.getFilterKeyJson(entry);
            if (detail == null || !(detail.get("key") instanceof String) || !(detail.get("class") instanceof String)) continue;
            String name = (String) detail.get("key");
            String type = (String) detail.get("class");
            String example = example(detail.get("value"));
            values.add(new Parameter(clipped(name, 256), clipped(type, 256), clipped(example, MAX_SAMPLE_TEXT),
                    !"unavailable".equals(type) && name.length() <= 256 && type.length() <= 256
                            && example.length() <= MAX_SAMPLE_TEXT));
        }
        values.sort(Comparator.comparing(extra -> extra.name));
        return values;
    }

    private static String example(Object value) {
        if (value == null) return "null";
        if (value instanceof List) return new JSONArray((List<?>) value).toString();
        if (value instanceof Map) return new JSONObject((Map<?, ?>) value).toString();
        return value.toString();
    }

    private static boolean complete(Bundle record, List<Parameter> parameters) {
        Object raw = record.get("intentExtras");
        return record.getString("intentExtras_error") == null && parameters.stream().allMatch(value -> value.complete)
                && (raw == null || (raw instanceof List && ((List<?>) raw).size() == parameters.size()));
    }

    private static String safeUri(Bundle record, String raw, List<Parameter> parameters, String action,
            String data, String type, int flags, TreeSet<String> categories) {
        if (raw.isEmpty() || raw.length() > MAX_URI || !complete(record, parameters)) return null;
        try {
            Intent parsed = Intent.parseUri(raw, Intent.URI_INTENT_SCHEME);
            if (!action.equals(value(parsed.getAction())) || !data.equals(value(parsed.getDataString()))
                    || !type.equals(value(parsed.getType())) || flags != parsed.getFlags()
                    || !categories.equals(parsed.getCategories() == null ? Collections.emptySet() : parsed.getCategories())
                    || !value(record.getString("package")).equals(value(parsed.getPackage()))
                    || !value(record.getString("componentPackageName")).equals(parsed.getComponent() == null
                            ? "" : parsed.getComponent().getPackageName())
                    || !value(record.getString("component")).equals(parsed.getComponent() == null
                            ? "" : parsed.getComponent().getClassName())) return null;
            Bundle extras = parsed.getExtras();
            if (parameters.size() != (extras == null ? 0 : extras.size())) return null;
            for (Parameter parameter : parameters) {
                Object value = extras.get(parameter.name);
                if (value == null || !parameter.type.equals(value.getClass().getName())
                        || !parameter.example.equals(example(value))) return null;
            }
            if (record.get("clipData") != null || parsed.getSelector() != null) return null;
            return raw;
        } catch (Exception error) { return null; }
    }

    private static long longValue(Bundle record, String key, long fallback) {
        Object value = record.get(key);
        return value instanceof Number ? ((Number) value).longValue() : fallback;
    }

    private static String value(String value) { return value == null || "null".equals(value) ? "" : value; }

    private static String clipped(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit - 1) + "…";
    }

    private static final class Version {
        static final Version UNKNOWN = new Version(-1, "", 0);
        final long code, updated;
        final String name;
        Version(long code, String name, long updated) {
            this.code = code;
            this.name = name;
            this.updated = updated;
        }
    }

    public static final class Parameter {
        public final String name, type, example;
        public final List<String> examples;
        private final boolean complete;
        Parameter(String name, String type, String example, boolean complete) {
            this(name, type, example, complete, Collections.singletonList(example));
        }
        Parameter(String name, String type, String example, boolean complete, List<String> examples) {
            this.name = name;
            this.type = type;
            this.example = example;
            this.complete = complete;
            this.examples = Collections.unmodifiableList(examples);
        }
    }

    public static final class Observation {
        public final long id, destinationVersionCode, destinationLastUpdate, sourceVersionCode, sourceLastUpdate, seenAt;
        public final String destinationPackage, activity, destinationVersionName, sourcePackage, sourceVersionName;
        public final String action, data, mimeType, intentUri;
        final String componentPackage, componentClass;
        public final int flags, count;
        public final boolean delivered, parametersComplete;
        public final List<Parameter> parameters;
        public final List<String> categories;

        Observation(long id, JSONObject data, int count, long seenAt) {
            this.id = id;
            this.count = count;
            this.seenAt = seenAt;
            destinationPackage = data.optString("package");
            activity = data.optString("activity");
            componentPackage = data.optString("componentPackage");
            componentClass = data.optString("componentClass");
            destinationVersionCode = data.optLong("targetCode", -1);
            destinationVersionName = data.optString("targetName");
            destinationLastUpdate = data.optLong("targetUpdated");
            sourcePackage = data.optString("source");
            sourceVersionCode = data.optLong("sourceCode", -1);
            sourceVersionName = data.optString("sourceName");
            sourceLastUpdate = data.optLong("sourceUpdated");
            action = data.optString("action");
            this.data = data.optString("data");
            mimeType = data.optString("type");
            intentUri = data.isNull("uri") ? null : data.optString("uri");
            flags = data.optInt("flags");
            delivered = data.optBoolean("delivered");
            parametersComplete = data.optBoolean("extrasComplete");
            List<Parameter> fields = new ArrayList<>();
            JSONArray extras = Objects.requireNonNull(data.optJSONArray("extras"));
            for (int i = 0; i < extras.length(); i++) {
                JSONArray extra = extras.optJSONArray(i);
                if (extra == null) continue;
                String example = extra.optString(2);
                List<String> samples = new ArrayList<>();
                samples.add(example);
                JSONArray retained = extra.optJSONArray(3);
                for (int j = 0; retained != null && j < retained.length(); j++) {
                    String sample = retained.optString(j);
                    if (!samples.contains(sample)) samples.add(sample);
                }
                fields.add(new Parameter(extra.optString(0), extra.optString(1), example, true, samples));
            }
            parameters = Collections.unmodifiableList(fields);
            List<String> categories = new ArrayList<>();
            JSONArray qualifiers = data.optJSONArray("categories");
            if (qualifiers != null) for (int i = 0; i < qualifiers.length(); i++) categories.add(qualifiers.optString(i));
            this.categories = Collections.unmodifiableList(categories);
        }
    }

    private static final class Store extends SQLiteOpenHelper {
        Store(Context context) {
            super(context, new File(context.getNoBackupFilesDir(), "intent_library.db").getAbsolutePath(), null, 1);
        }

        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE observations(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "shape TEXT NOT NULL UNIQUE,package_name TEXT NOT NULL,target_version INTEGER NOT NULL,"
                    + "summary TEXT NOT NULL,payload TEXT NOT NULL,hits INTEGER NOT NULL,seen_at INTEGER NOT NULL)");
            db.execSQL("CREATE INDEX intent_target ON observations(package_name,target_version)");
            db.execSQL("CREATE INDEX intent_latest ON observations(seen_at DESC,id DESC)");
            db.execSQL("CREATE TABLE fragment_evidence(id INTEGER PRIMARY KEY AUTOINCREMENT,package_name TEXT NOT NULL,"
                    + "activity TEXT NOT NULL,fragment TEXT NOT NULL,version INTEGER NOT NULL,updated INTEGER NOT NULL,"
                    + "payload TEXT NOT NULL,seen_at INTEGER NOT NULL,UNIQUE(package_name,activity,fragment,version,updated,payload))");
            db.execSQL("CREATE INDEX fragment_evidence_route ON fragment_evidence(package_name,activity,fragment,version,updated,seen_at DESC)");
        }

        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported Intent library version " + newVersion);
        }
    }
}
