package com.fourtwo.hookintent.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.RemoteException;
import android.widget.Toast;

import com.fourtwo.hookintent.IRootActivityService;
import com.fourtwo.hookintent.service.RootActivityService;
import com.topjohnwu.superuser.ipc.RootService;

public class RootServiceHelper {

    private static IRootActivityService rootService;
    private static boolean isServiceBound = false;

    private RootServiceHelper() {
        // 工具类不允许实例化
    }

    private static final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            rootService = IRootActivityService.Stub.asInterface(service);
            isServiceBound = true;
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            rootService = null;
            isServiceBound = false;
        }
    };

    // 绑定 RootService
    public static void bindRootService(Context context) {
        if (isServiceBound) return;

        Intent serviceIntent = new Intent(context, RootActivityService.class);
        RootService.bind(serviceIntent, connection);
    }

    // 调用 RootService 启动 Activity
    public static void startActivityAsRoot(Context context, Intent intent) {
        if (rootService == null) {
            Toast.makeText(context, "Root service not connected", Toast.LENGTH_SHORT).show();
            return;
        }

        try {
            int result = rootService.startActivityAsRoot(intent, 0);
            if (result != 0) {
                Toast.makeText(context, "Failed to start activity as root. Code: " + result, Toast.LENGTH_SHORT).show();
            }
        } catch (RemoteException e) {
            Toast.makeText(context, "IPC Error: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    // 解绑 RootService
    public static void unbindRootService(Context context) {
        if (isServiceBound) {
            try {
                context.unbindService(connection);
            } catch (RuntimeException ignored) {
            }

            isServiceBound = false;
        }
    }
}
