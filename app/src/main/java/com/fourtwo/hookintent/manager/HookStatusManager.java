package com.fourtwo.hookintent.manager;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import com.fourtwo.hookintent.service.CaptureSessionService;
import com.fourtwo.hookintent.service.CaptureTileService;
import com.fourtwo.hookintent.service.MessengerService;

/** Main-process global capture state. Injected process-local overrides stay in MessengerClient. */
public final class HookStatusManager {
    private static volatile boolean enabled;
    private static Context app;
    private static final MutableLiveData<Boolean> state = new MutableLiveData<>(false);
    private static final Handler main = new Handler(Looper.getMainLooper());
    private HookStatusManager() { }

    public static void initialize(Context context) {
        if (app != null) return;
        app = context.getApplicationContext();
        enabled = app.getSharedPreferences("capture_control", 0).getBoolean("enabled", false);
        CaptureMonitor.initialize(app);
        publish();
    }
    public static boolean isHook() { return enabled; }
    public static LiveData<Boolean> state() { return state; }

    public static void setHook(Context context, boolean value) {
        initialize(context);
        if(value && !enabled && !CaptureSessionService.isRunning()) CaptureMonitor.reset();
        setHook(value);
        CaptureSessionService.refresh(context);
    }

    public static void setHook(boolean value) {
        if (Looper.myLooper() != Looper.getMainLooper()) { main.post(() -> setHook(value)); return; }
        boolean changed = enabled != value;
        enabled = value;
        if (app != null) app.getSharedPreferences("capture_control", 0).edit().putBoolean("enabled", value).apply();
        if (changed) {
            MessengerService service = MessengerService.getInstance();
            if (service != null) {
                Bundle message = new Bundle(); message.putBoolean("isHook", value);
                service.notifyClients(message);
            }
        }
        publish();
        if (app != null) CaptureTileService.refresh(app);
    }
    private static void publish() {
        if (Looper.myLooper() == Looper.getMainLooper()) state.setValue(enabled);
        else state.postValue(enabled);
    }
}
