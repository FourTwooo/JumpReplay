package com.fourtwo.hookintent.service;


import android.annotation.UserIdInt;
import android.app.ActivityManagerNative;
import android.app.IActivityManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;
import android.os.RemoteException;

import androidx.annotation.NonNull;

import com.fourtwo.hookintent.IRootActivityService;
import com.topjohnwu.superuser.ipc.RootService;


public class RootActivityService extends RootService {
    private final IRootActivityService.Stub binder = new IRootActivityService.Stub() {
        @Override
        public int startActivityAsRoot(Intent intent, @UserIdInt int userHandle) throws SecurityException {
            IActivityManager am = getActivityManager();
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11 及以上调用方式
                    return am.startActivityAsUserWithFeature(null, null, null, intent, intent.getType(),
                            null, null, 0, 0, null, null, userHandle);
                } else {
                    // Android 11 以下调用方式
                    return am.startActivityAsUser(null, null, intent, intent.getType(),
                            null, null, 0, 0, null, null, userHandle);
                }
            } catch (RemoteException e) {
                throw new RuntimeException("System service error", e);
            } catch (SecurityException e) {
                throw new SecurityException("Permission denied: " + e.getMessage());
            }
        }
    };

    private static IActivityManager getActivityManager() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Android 8.0 及以上版本通过 Stub 获取 IActivityManager
            return IActivityManager.Stub.asInterface(android.os.ServiceManager.getService(Context.ACTIVITY_SERVICE));
        } else {
            // Android 8.0 以下版本使用 ActivityManagerNative
            return ActivityManagerNative.asInterface(android.os.ServiceManager.getService(Context.ACTIVITY_SERVICE));
        }
    }

    @Override
    public IBinder onBind(@NonNull Intent intent) {
        return binder;
    }

}