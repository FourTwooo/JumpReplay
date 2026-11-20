package com.fourtwo.hookintent.manager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.os.Handler;
import android.os.Looper;
import com.fourtwo.hookintent.base.AmCommandBuilder;

import com.fourtwo.hookintent.replay.ReplayFeedback;
import com.fourtwo.hookintent.utils.RootServiceApi;
import com.fourtwo.hookintent.utils.ShizukuServerApi;
import com.fourtwo.hookintent.utils.ShortcutLaunchCoordinator;
import com.topjohnwu.superuser.Shell;

import java.util.function.Consumer;

import rikka.shizuku.Shizuku;

public class PermissionManager {

    private static final String TAG = "PermissionManager";

    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED_LISTENER = () -> {
        try {
            isBinderAvailable = Shizuku.pingBinder();
            isShizukuPermissionGranted = PermissionManager.isBinderAvailable && !Shizuku.isPreV11()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (RuntimeException error) {
            isShizukuPermissionGranted = false;
            Log.w(TAG, "Cannot query Shizuku state", error);
        }
    };

    private static final Shizuku.OnBinderDeadListener BINDER_DEAD_LISTENER = () -> {
        isBinderAvailable = false;
        isShizukuPermissionGranted = false;
    };

    private static boolean shizukuInitialized;
    private static final Shizuku.OnRequestPermissionResultListener PERMISSION_RESULT_LISTENER =
            (code, result) -> isShizukuPermissionGranted = result == PackageManager.PERMISSION_GRANTED;

    public static boolean isShizukuPermissionGranted;
    public static boolean isRootPermissionGranted;
    public static boolean isBinderAvailable;


    public static synchronized void ShizukuInit() {
        if (shizukuInitialized) return;
        shizukuInitialized = true;
        Shizuku.addRequestPermissionResultListener(PERMISSION_RESULT_LISTENER);
        // 注册监听器
        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER);
        Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER);
    }

    public static synchronized void ShizukuCleanUp() {
        if (!shizukuInitialized) return;
        shizukuInitialized = false;
        Shizuku.removeRequestPermissionResultListener(PERMISSION_RESULT_LISTENER);
        // 移除监听器
        Shizuku.removeBinderReceivedListener(BINDER_RECEIVED_LISTENER);
        Shizuku.removeBinderDeadListener(BINDER_DEAD_LISTENER);
    }

    public static void bindRootService(Context context) {
        RootServiceApi.bindRootService(context);
    }

    public static void unbindRootService(Context context) {
        RootServiceApi.unbindRootService(context);
    }

    public static void init(Context context){
        bindRootService(context);
        ShizukuInit();
    }

    public static void unload(Context context){
        unbindRootService(context);
        ShizukuCleanUp();
    }

    public static void startActivity(Context context, Intent intent, Boolean privileged, String mode) {
        ReplayFeedback.Attempt attempt = ReplayFeedback.begin(context, ReplayFeedback.INTERCEPTOR_KEY);
        startActivity(context, intent, privileged, mode, error -> {
            if (error == null) attempt.sent();
            else attempt.failed(error);
            attempt.announce();
        });
    }

    /** Completion acknowledges Android's launch request. The caller can only report that it was sent. */
    public static void startActivity(Context context, Intent intent, Boolean privileged, String mode,
                                     Consumer<Exception> complete) {
        String selection = ShortcutLaunchCoordinator.canonicalSelection(mode);
        try {
            if (!Boolean.TRUE.equals(privileged)) {
                context.startActivity(intent);
            } else if ("root".equals(selection)) {
                Context app = context.getApplicationContext();
                Intent target = new Intent(intent);
                RootServiceApi.connectAsync(app, error -> {
                    Exception failure = error;
                    if (failure == null) {
                        try {
                            RootServiceApi.startActivityAsRoot(app, target);
                        } catch (Exception launchError) {
                            failure = launchError;
                        }
                    }
                    complete.accept(failure);
                });
                return;
            } else if ("Shizuku - 系统助手".equals(selection)) {
                ShizukuServerApi.launchAssistantAsync(context, intent, complete);
                return;
            } else if ("Shizuku".equals(selection)) {
                int result = ShizukuServerApi.startActivityAsShizuku(context, intent,
                        android.os.Process.myUid() / 100000);
                if (result < 0 || result >= 100) {
                    throw new IllegalStateException("Activity启动失败，代码: " + result);
                }
            } else {
                throw new IllegalArgumentException("未知启动模式: " + mode);
            }
        } catch (Exception error) {
            Log.e(TAG, "无法启动新的 Intent", error);
            complete.accept(error);
            return;
        }
        complete.accept(null);
    }

    public static void executeCommand(String command, boolean root, Consumer<Exception> complete) {
        if (!isRootPermissionGranted) {
            complete.accept(new IllegalStateException("Root 服务不可用，无法执行 am 命令"));
            return;
        }
        String job = root ? command : "su shell -c " + AmCommandBuilder.shellQuote(command);
        Shell.cmd(job).submit(result -> new Handler(Looper.getMainLooper()).post(() ->
                complete.accept(result.isSuccess() ? null
                        : new IllegalStateException("am 命令执行失败，状态码：" + result.getCode()))));
    }
}
