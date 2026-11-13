package com.fourtwo.hookintent.manager;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.fourtwo.hookintent.data.ItemData;

/** Committed, filtered records, independent of the home page's search and pagination. */
public final class CaptureMonitor {
    public static final class Snapshot {
        public final long count, time;
        public final String source;
        Snapshot(long count, long time, String source) { this.count = count; this.time = time; this.source = source; }
    }
    private static final MutableLiveData<Snapshot> changes = new MutableLiveData<>(new Snapshot(0, 0, ""));
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile long epoch;
    private static Context app;
    private CaptureMonitor() { }
    public static LiveData<Snapshot> changes() { return changes; }
    public static void initialize(Context context) {
        if(app != null) return;
        app=context.getApplicationContext();
        android.content.SharedPreferences saved=app.getSharedPreferences("capture_session",0);
        changes.setValue(new Snapshot(saved.getLong("count",0),saved.getLong("time",0),saved.getString("source","")));
    }
    public static void reset() { epoch++; changes.setValue(new Snapshot(0, 0, "")); persist(); }
    private static void persist() {
        if(app == null) return;
        Snapshot value=changes.getValue();
        app.getSharedPreferences("capture_session",0).edit().putLong("count",value.count).putLong("time",value.time).putString("source",value.source).apply();
    }
    public static void committed(java.util.List<ItemData> items) {
        if (items.isEmpty()) return;
        long generation = epoch;
        int count = items.size();
        String source = items.get(count - 1).getAppName();
        main.post(() -> {
            if (epoch != generation) return;
            Snapshot before = changes.getValue();
            changes.setValue(new Snapshot((before == null ? 0 : before.count) + count, System.currentTimeMillis(), source));
            persist();
        });
    }
}
