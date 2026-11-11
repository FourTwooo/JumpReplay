package com.fourtwo.hookintent.utils;

import android.annotation.UserIdInt;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.RemoteException;
import android.provider.Settings;
import android.widget.Toast;
import com.fourtwo.hookintent.manager.PermissionManager;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.channels.FileLock;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class ShizukuServerApi {
    private static final ExecutorService ASSISTANT_EXECUTOR = Executors.newSingleThreadExecutor();
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    // A binder wrapper belongs to the currently running Shizuku server. Never cache across restarts.
    private static IActivityManager activityManager() {
        ShizukuBinderWrapper binder = new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE));
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? IActivityManager.Stub.asInterface(binder) : ActivityManagerNative.asInterface(binder);
    }

    public static int startActivityAsShizuku(Context context, Intent intent, @UserIdInt int userHandle) throws SecurityException {
        if (!requestShizukuPermission()) throw new SecurityException("Shizuku未连接或未授权");
        IActivityManager manager = activityManager();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                return manager.startActivityAsUserWithFeature(null, null, null, intent, intent.getType(), null, null, 0, 0, null, null, userHandle);
            }
            return manager.startActivityAsUser(null, null, intent, intent.getType(), null, null, 0, 0, null, null, userHandle);
        } catch (RemoteException e) {
            PermissionManager.isBinderAvailable = Shizuku.pingBinder();
            throw new IllegalStateException("Shizuku系统服务连接失败", e);
        }
    }

    private static boolean requestShizukuPermission() {
        try {
            boolean connected = Shizuku.pingBinder();
            PermissionManager.isBinderAvailable = connected;
            boolean granted = connected && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
            PermissionManager.isShizukuPermissionGranted = granted;
            if (connected && !Shizuku.isPreV11() && !granted) Shizuku.requestPermission(1);
            return granted;
        } catch (RuntimeException e) {
            PermissionManager.isShizukuPermissionGranted = false;
            return false;
        }
    }

    public static void launchAssistantAsync(Context context, Intent intent) {
        Context app = context.getApplicationContext();
        launchAssistantAsync(app, intent, error -> Toast.makeText(app,
                error == null ? "调用成功" : "调用失败: " + error.getMessage(), Toast.LENGTH_LONG).show());
    }

    public static void launchAssistantAsync(Context context, Intent intent, java.util.function.Consumer<Exception> complete) {
        launchAssistantAsync(context, intent, complete, () -> true);
    }

    static void launchAssistantAsync(Context context, Intent intent, java.util.function.Consumer<Exception> complete,
            java.util.function.BooleanSupplier active) {
        Context app = context.getApplicationContext();
        Intent target = new Intent(intent);
        ASSISTANT_EXECUTOR.execute(() -> {
            Exception failure = null;
            try { launchAssistantWithTemporaryReplacement(app, target, active); }
            catch (Exception e) { failure = e; }
            Exception result = failure;
            MAIN.post(() -> complete.accept(result));
        });
    }

    public static synchronized void launchAssistantWithTemporaryReplacement(Context context, Intent intent)
            throws IllegalAccessException, NoSuchFieldException, NoSuchMethodException, InvocationTargetException {
        launchAssistantWithTemporaryReplacement(context, intent, () -> true);
    }

    private static synchronized void launchAssistantWithTemporaryReplacement(Context context, Intent intent,
            java.util.function.BooleanSupplier active)
            throws IllegalAccessException, NoSuchFieldException, NoSuchMethodException, InvocationTargetException {
        // The Java monitor only serializes this process. Main UI and :shortcut share the setting.
        File directory = new File(context.getFilesDir(), "privileged_operations");
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IllegalStateException("无法创建助手操作锁目录");
        }
        try (RandomAccessFile file = new RandomAccessFile(new File(directory, "assistant.lock"), "rw");
             FileLock ignored = file.getChannel().lock()) {
            requireActive(active);
            launchAssistantLocked(context, intent, active);
        } catch (IOException error) {
            throw new IllegalStateException("无法锁定系统助手操作", error);
        }
    }

    private static void requireActive(java.util.function.BooleanSupplier active) {
        if (!active.getAsBoolean()) throw new java.util.concurrent.CancellationException("助手启动已取消");
    }

    private static void launchAssistantLocked(Context context, Intent intent, java.util.function.BooleanSupplier active)
            throws IllegalAccessException, NoSuchFieldException, NoSuchMethodException, InvocationTargetException {
        if (!requestShizukuPermission()) throw new SecurityException("Shizuku未连接或未授权");
        ComponentName component = intent.getComponent();
        if (component == null) throw new IllegalArgumentException("Intent缺少component");
        int user = android.os.Process.myUid() / 100000;
        if (context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            IPackageManager manager = IPackageManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package")));
            Method grant = manager.getClass().getDeclaredMethod("grantRuntimePermission", String.class, String.class, int.class);
            grant.invoke(manager, context.getPackageName(), android.Manifest.permission.WRITE_SECURE_SETTINGS, user);
        }
        final String key = "assistant";
        String previous = Settings.Secure.getString(context.getContentResolver(), key);
        String replacement = component.flattenToString();
        requireActive(active);
        if (!Settings.Secure.putString(context.getContentResolver(), key, replacement)) throw new IllegalStateException("无法临时修改系统助手");
        try {
            SearchManager manager = (SearchManager) context.getSystemService(Context.SEARCH_SERVICE);
            Method launch = SearchManager.class.getDeclaredMethod("launchAssist", Bundle.class);
            launch.setAccessible(true);
            requireActive(active);
            Object result = launch.invoke(manager, intent.getExtras() == null ? new Bundle() : intent.getExtras());
            if (Boolean.FALSE.equals(result)) throw new IllegalStateException("系统未启动助手");
            // This method runs on the serial worker; restoration cannot freeze the Activity.
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("助手启动被中断", e);
        } finally {
            // Do not overwrite a new assistant choice made by the user during this operation.
            if (replacement.equals(Settings.Secure.getString(context.getContentResolver(), key))
                    && !Settings.Secure.putString(context.getContentResolver(), key, previous)) {
                throw new IllegalStateException("系统助手恢复失败");
            }
        }
    }
}
