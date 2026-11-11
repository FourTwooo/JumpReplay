package com.fourtwo.hookintent.manager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;
import android.os.Handler;
import android.os.Looper;
import com.fourtwo.hookintent.base.AmCommandBuilder;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.utils.RootServiceApi;
import com.fourtwo.hookintent.utils.ShizukuServerApi;
import com.topjohnwu.superuser.Shell;

import rikka.shizuku.Shizuku;

public class PermissionManager {

    private static final String TAG = "PermissionManager";

    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED_LISTENER = () -> {
        if (Shizuku.isPreV11()) {
            Log.d(TAG, "Shizuku pre-v11 is not supported");
        } else {
            Log.d(TAG, "Binder received ");
        }
        isBinderAvailable = Shizuku.pingBinder();
        isShizukuPermissionGranted = !Shizuku.isPreV11() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    };

    private static final Shizuku.OnBinderDeadListener BINDER_DEAD_LISTENER = () -> {
        isBinderAvailable = false;
        isShizukuPermissionGranted = false;
    };

    public static boolean isShizukuPermissionGranted;
    public static boolean isRootPermissionGranted;
    public static boolean isBinderAvailable;


    public static void ShizukuInit() {
        // 注册监听器
        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER);
        Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER);
    }

    public static void ShizukuCleanUp() {
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

    public static void startActivity(Context context, Intent intent, Boolean isRoot, String SelectedItem) {
        try {
            if (Boolean.TRUE.equals(isRoot)) {
                if ("root".equals(SelectedItem)) {
                    // root
                    RootServiceApi.startActivityAsRoot(context, intent);
                } else if ("Shizuku - 系统助手".equals(SelectedItem)) {
                    // Shizuku - 系统助手
                    ShizukuServerApi.launchAssistantAsync(context, intent);
                    return;
                } else if("Shizuku".equals(SelectedItem)){
                    // Shizuku
                    int result = ShizukuServerApi.startActivityAsShizuku(context, intent, android.os.Process.myUid() / 100000);
                    if (result < 0 || result >= 100) throw new IllegalStateException("Activity启动失败，代码: " + result);
                } else throw new IllegalArgumentException("未知启动模式: " + SelectedItem);
            } else {
                context.startActivity(intent);
            }
            Toast.makeText(context, "调用成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "无法启动新的 Intent: " + e.getMessage(), e);
            Toast.makeText(context, "调用失败", Toast.LENGTH_SHORT).show();
        }
    }

    public static void executeCommand(String command, Boolean Root, Context context) {
        if (!isRootPermissionGranted) {
            Toast.makeText(context, "Root服务不可用，无法执行am命令", Toast.LENGTH_SHORT).show();
            return;
        }
        Context app = context.getApplicationContext();
        String job = Boolean.TRUE.equals(Root) ? command : "su shell -c " + AmCommandBuilder.shellQuote(command);
        Shell.cmd(job).submit(result -> new Handler(Looper.getMainLooper()).post(() ->
                Toast.makeText(app, result.isSuccess() ? "调用成功" : "调用失败", Toast.LENGTH_SHORT).show()));
    }
}
