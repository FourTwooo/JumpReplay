package com.fourtwo.hookintent;

import android.app.Application;
import android.content.Context;
import android.os.Build;

import androidx.multidex.BuildConfig;

import com.fourtwo.hookintent.manager.PermissionManager;
import com.topjohnwu.superuser.Shell;
import com.fourtwo.hookintent.utils.ShizukuProcessBridge;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class MainApplication extends Application {
    private static boolean isolated() { return android.os.Process.myUid() % 100000 >= 90000; }

    static {
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10));
    }
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (!isolated()) ShizukuProcessBridge.prepare(base);
    }
    @Override
    public void onCreate() {
        super.onCreate();
        // The offline object codec runs with an isolated UID and receives no privileged bridges.
        if (isolated()) return;

        // 开启系统隐藏API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L");
        }

        PermissionManager.ShizukuInit();
        ShizukuProcessBridge.requestBinder(this);
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks() {
            private int visible;
            private final android.os.Handler ui=new android.os.Handler(android.os.Looper.getMainLooper());
            @Override public void onActivityStarted(android.app.Activity activity) {
                visible++; com.fourtwo.hookintent.service.CaptureSessionService.mainVisible(true);
            }
            @Override public void onActivityStopped(android.app.Activity activity) {
                visible=Math.max(0,visible-1);
                ui.postDelayed(() -> { if(visible == 0) com.fourtwo.hookintent.service.CaptureSessionService.mainVisible(false); },200);
            }
            @Override public void onActivityCreated(android.app.Activity activity,android.os.Bundle state) { }
            @Override public void onActivityResumed(android.app.Activity activity) { }
            @Override public void onActivityPaused(android.app.Activity activity) { }
            @Override public void onActivitySaveInstanceState(android.app.Activity activity,android.os.Bundle state) { }
            @Override public void onActivityDestroyed(android.app.Activity activity) { }
        });
        // A provider-only background start must not request Root just to deliver Shizuku's Binder.
        // The visible main Activity warms Root; shortcut Root mode connects on demand.
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        if (!isolated()) PermissionManager.unload(this);
    }

}
