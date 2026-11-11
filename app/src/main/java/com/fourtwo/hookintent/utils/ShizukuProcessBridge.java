package com.fourtwo.hookintent.utils;

import android.app.ActivityManager;
import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.Executors;
import rikka.shizuku.ShizukuProvider;
import rikka.sui.Sui;

/** Each process must attach its own Shizuku client, even though app authorization shares a UID. */
public final class ShizukuProcessBridge {
    private static boolean prepared;
    private static boolean requested;
    private static boolean providerProcess;
    private static boolean sui;

    private ShizukuProcessBridge() { }

    /** Called from Application.attachBaseContext, before ContentProviders are installed. */
    public static synchronized void prepare(Context context) {
        if (prepared) return;
        providerProcess = context.getPackageName().equals(processName(context));
        sui = Sui.init(context.getPackageName());
        if (!sui) ShizukuProvider.enableMultiProcessSupport(providerProcess);
        prepared = true;
    }

    public static synchronized void requestBinder(Context context) {
        if (!prepared) prepare(context);
        if (requested || providerProcess || sui) return;
        requested = true;
        Context app = context.getApplicationContext();
        // The provider may cold-start the main process in the background. Never block the shortcut UI.
        java.util.concurrent.ExecutorService request = Executors.newSingleThreadExecutor();
        request.execute(() -> {
            try { ShizukuProvider.requestBinderForNonProviderProcess(app); }
            catch (RuntimeException error) { Log.e("ShizukuProcessBridge", "Cannot request process binder", error); }
            finally { request.shutdown(); }
        });
    }

    static String processName(Context context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            String name = Application.getProcessName();
            if (name != null && !name.isEmpty()) return name;
        }
        ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
        List<ActivityManager.RunningAppProcessInfo> processes = manager == null ? null : manager.getRunningAppProcesses();
        if (processes != null) for (ActivityManager.RunningAppProcessInfo process : processes) {
            if (process.pid == Process.myPid()) return process.processName;
        }
        try (FileInputStream input = new FileInputStream("/proc/self/cmdline")) {
            byte[] buffer = new byte[256];
            int count = input.read(buffer), end = 0;
            while (end < count && buffer[end] != 0) end++;
            if (end > 0) return new String(buffer, 0, end, StandardCharsets.UTF_8);
        } catch (java.io.IOException error) { Log.w("ShizukuProcessBridge", "Cannot read process name", error); }
        return "";
    }
}
