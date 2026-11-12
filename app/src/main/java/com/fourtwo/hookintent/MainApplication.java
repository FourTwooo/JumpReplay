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

    static {
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10));
    }
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        if (android.os.Process.myUid() == base.getApplicationInfo().uid) ShizukuProcessBridge.prepare(base);
    }
    @Override
    public void onCreate() {
        super.onCreate();
        // The offline object codec runs with an isolated UID and receives no privileged bridges.
        if (android.os.Process.myUid() != getApplicationInfo().uid) return;

        // 开启系统隐藏API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L");
        }

        PermissionManager.ShizukuInit();
        ShizukuProcessBridge.requestBinder(this);
        // A provider-only background start must not request Root just to deliver Shizuku's Binder.
        // The visible main Activity warms Root; shortcut Root mode connects on demand.
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        if (android.os.Process.myUid() == getApplicationInfo().uid) PermissionManager.unload(this);
    }

}
