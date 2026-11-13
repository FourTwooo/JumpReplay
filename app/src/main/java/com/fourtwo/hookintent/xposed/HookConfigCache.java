package com.fourtwo.hookintent.xposed;

import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.database.Cursor;
import android.os.Handler;
import android.os.HandlerThread;
import com.fourtwo.hookintent.data.Constants;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Pattern;
import de.robv.android.xposed.XposedBridge;

/** No provider queries or regexp compilation on PackageManager's query hot path. */
public final class HookConfigCache {
    private final Context context;
    private final Handler worker;
    private volatile List<Rule> rules = Collections.emptyList();
    private volatile ActivityInfo activity;
    private static final class Rule {
        final String scheme;
        final Pattern pattern;
        Rule(String scheme, Pattern pattern) { this.scheme = scheme; this.pattern = pattern; }
    }
    public HookConfigCache(Context context) {
        this.context = context;
        HandlerThread thread = new HandlerThread("JumpReplay-config"); thread.start();
        worker = new Handler(thread.getLooper());
        // Observer registration and initial fetch are outside PackageManager's Binder call stack.
        worker.post(this::startWatching);
        android.content.IntentFilter packages = new android.content.IntentFilter(android.content.Intent.ACTION_PACKAGE_REPLACED);
        packages.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
        packages.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED); packages.addDataScheme("package");
        context.registerReceiver(new android.content.BroadcastReceiver() {
            @Override public void onReceive(Context context, android.content.Intent intent) {
                if (intent.getData() != null && "com.fourtwo.hookintent".equals(intent.getData().getSchemeSpecificPart())) {
                    activity = null; worker.post(() -> { reload(); loadActivity(); });
                }
            }
        }, packages);
    }
    private void startWatching() {
        try {
            context.getContentResolver().registerContentObserver(Constants.SCHEME_URI, false,
                    new ContentObserver(worker) { @Override public void onChange(boolean selfChange) { reload(); } });
            reload(); loadActivity();
        } catch (Exception error) {
            XposedBridge.log("JumpReplay observer registration: " + error); worker.postDelayed(this::startWatching, 1000);
        }
    }
    private void loadActivity() {
        try {
            PackageInfo info = context.getPackageManager().getPackageInfo("com.fourtwo.hookintent", PackageManager.GET_ACTIVITIES);
            if (info.activities != null) for (ActivityInfo item : info.activities)
                if ("com.fourtwo.hookintent.IntentIntercept".equals(item.name)) { activity = item; break; }
        } catch (Exception error) { XposedBridge.log("JumpReplay activity cache: " + error); }
        if (activity == null) worker.postDelayed(this::loadActivity, 1000);
    }
    private void reload() {
        try (Cursor cursor = context.getContentResolver().query(Constants.SCHEME_URI, null, null, null, null)) {
            if (cursor == null) throw new IllegalStateException("Configuration provider unavailable");
            List<Rule> next = new ArrayList<>();
            while (cursor.moveToNext()) {
                if (!Constants.DISABLED_SCHEME.equals(cursor.getString(cursor.getColumnIndexOrThrow("key")))) continue;
                String value = cursor.getString(cursor.getColumnIndexOrThrow("value"));
                if (value == null) continue;
                JSONArray array = new JSONArray(value);
                for (int index = 0; index < array.length(); index++) {
                    JSONObject record = array.getJSONObject(index);
                    if (!record.optBoolean("open", false)) continue;
                    String text = record.optString("text", "");
                    if (text.isEmpty()) continue;
                    try { next.add(new Rule(text, record.optBoolean("re", false) ? Pattern.compile(text) : null)); }
                    catch (java.util.regex.PatternSyntaxException error) { XposedBridge.log("Invalid JumpReplay rule: " + error); }
                }
            }
            rules = Collections.unmodifiableList(next);
        } catch (Exception error) {
            XposedBridge.log("JumpReplay config refresh failed; keeping last valid rules: " + error);
            worker.removeCallbacks(retry); worker.postDelayed(retry, 1000); // retry failures, never poll valid rules with a TTL
        }
    }
    private final Runnable retry = this::reload;
    public boolean isDisabled(String scheme, String uri) {
        for (Rule rule : rules) if (rule.pattern == null ? rule.scheme.equals(scheme) : rule.pattern.matcher(uri).find()) return true;
        return false;
    }
    public ActivityInfo getActivity() { ActivityInfo value = activity; return value == null ? null : new ActivityInfo(value); }
}
