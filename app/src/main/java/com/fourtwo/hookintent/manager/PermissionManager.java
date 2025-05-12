package com.fourtwo.hookintent.manager;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.util.Log;
import android.widget.Toast;

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
        isBinderAvailable = true;
        isShizukuPermissionGranted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
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
            if (isRoot) {
                String[] itemsArray = context.getResources().getStringArray(R.array.items_array);
                if (SelectedItem.equals("root")) {
                    // root
                    RootServiceApi.startActivityAsRoot(context, intent);
                } else if (SelectedItem.equals("Shizuku - 系统助手")) {
                    // Shizuku - 系统助手
                    ShizukuServerApi.launchAssistantWithTemporaryReplacement(context, intent);
                } else if(SelectedItem.equals("Shizuku")){
                    // Shizuku
                    ShizukuServerApi.startActivityAsShizuku(context, intent, 0);
                }
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
        if (isRootPermissionGranted) {
            if (Root) {
                Shell.cmd("su root", command).submit(result -> {
                    if (result.isSuccess()) {
                        Toast.makeText(context, "调用成功", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "调用失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                Shell.cmd("su shell", command).submit(result -> {
                    if (result.isSuccess()) {
                        Toast.makeText(context, "调用成功", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "调用失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

    }
}
