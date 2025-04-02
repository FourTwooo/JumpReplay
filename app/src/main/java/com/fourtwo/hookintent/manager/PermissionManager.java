package com.fourtwo.hookintent.manager;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.utils.RootServiceHelper;
import com.fourtwo.hookintent.utils.ShizukuSystemServerApi;

import rikka.shizuku.Shizuku;

public class PermissionManager {

    private static String TAG = "PermissionManager";

    private static final Shizuku.OnBinderReceivedListener BINDER_RECEIVED_LISTENER = () -> {
        if (Shizuku.isPreV11()) {
            Log.d(TAG, "Shizuku pre-v11 is not supported");
        } else {
            Log.d(TAG, "Binder received");
        }
    };

    private static final Shizuku.OnBinderDeadListener BINDER_DEAD_LISTENER = () -> Log.d("PermissionManager", "Binder dead");

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
        RootServiceHelper.bindRootService(context);
    }

    public static void unbindRootService(Context context) {
        RootServiceHelper.unbindRootService(context);
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
                if (SelectedItem.equals(itemsArray[0])) {
                    // 使用root
                    RootServiceHelper.startActivityAsRoot(context, intent);
                } else if (SelectedItem.equals(itemsArray[1])) {
                    // 使用Shizuku - 系统助手
                    ShizukuSystemServerApi.launchAssistantWithTemporaryReplacement(context, intent);
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

}
