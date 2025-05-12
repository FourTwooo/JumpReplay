package com.fourtwo.hookintent.utils;

import android.annotation.SuppressLint;
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
import android.os.IUserManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import com.fourtwo.hookintent.manager.PermissionManager;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class ShizukuServerApi {

    static final String TAG = "ShizukuServerApi";

    private static final Singleton<IPackageManager> PACKAGE_MANAGER = new Singleton<IPackageManager>() {
        @Override
        protected IPackageManager create() {
            return IPackageManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package")));
        }
    };

    private static final Singleton<IActivityManager> ACTIVITY_MANAGER = new Singleton<IActivityManager>() {
        @Override
        protected IActivityManager create() {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0 及以上版本通过 Stub 获取 IActivityManager
                return IActivityManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)));
            } else {
                // Android 8.0 以下版本使用 ActivityManagerNative
                return ActivityManagerNative.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.ACTIVITY_SERVICE)));
            }
        }
    };

    public static int startActivityAsShizuku(Context context, Intent intent, @UserIdInt int userHandle) throws SecurityException {
        if (!requestShizukuPermission()){Toast.makeText(context, "Shizuku未授权", Toast.LENGTH_SHORT).show(); throw new IllegalArgumentException();}

        IActivityManager am = ACTIVITY_MANAGER.get();
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

    private static boolean requestShizukuPermission() {
        if (!PermissionManager.isShizukuPermissionGranted){
            Shizuku.requestPermission(1);
            Log.d(TAG, "requestShizukuPermission: Shizuku未授权");
            return false;
        }

        if (Shizuku.isPreV11()) {
            Log.d(TAG, "requestShizukuPermission: 未拥有权限");
            return false;
        }

        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "requestShizukuPermission: 已拥有权限");
            return true;
        }

        // 动态申请权限
        Shizuku.requestPermission(1);
        return false;
    }

    public static void launchAssistantWithTemporaryReplacement(Context context, Intent intent) throws IllegalAccessException, NoSuchFieldException, NoSuchMethodException, InvocationTargetException {
        if (!requestShizukuPermission()){Toast.makeText(context, "Shizuku未授权", Toast.LENGTH_SHORT).show(); throw new IllegalArgumentException();}

        if (!(context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED)) {
            IPackageManager packageManager = PACKAGE_MANAGER.get();
            Method grantPermissionMethod = packageManager.getClass().getDeclaredMethod(
                    "grantRuntimePermission",
                    String.class, String.class, int.class
            );
            grantPermissionMethod.invoke(packageManager,
                    context.getPackageName(), android.Manifest.permission.WRITE_SECURE_SETTINGS, 0);
        }

        SearchManager searchManager = (SearchManager) context.getSystemService(Context.SEARCH_SERVICE);

        @SuppressLint("DiscouragedPrivateApi") Field assistantField = Settings.Secure.class.getDeclaredField("ASSISTANT");
        assistantField.setAccessible(true);
        String ASSISTANT = (String) assistantField.get(null);
        // Get the current Assistant component name
        String currentAssistant = Settings.Secure.getString(context.getContentResolver(), ASSISTANT);

        // Get the new Assistant component name from the intent
        ComponentName component = intent.getComponent();
        if (component == null) {
            Toast.makeText(context, "缺失component", Toast.LENGTH_SHORT).show();
            throw new IllegalArgumentException("Intent does not contain a valid component.");
        }
        String replacedAssistant = component.flattenToString();

        try {
            // Replace the Assistant component in Settings.Secure
            Settings.Secure.putString(context.getContentResolver(), ASSISTANT, replacedAssistant);

            // Launch the Assistant with the provided extras
            Bundle extras = intent.getExtras();
            if (extras == null) {
                extras = new Bundle();
            }
            Method launchAssistMethod = SearchManager.class.getDeclaredMethod("launchAssist", Bundle.class);
            launchAssistMethod.setAccessible(true);
            launchAssistMethod.invoke(searchManager, extras);
//            searchManager.launchAssist(extras);
            // Delay for 500ms to give the Assistant time to launch
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            e.printStackTrace();
        } catch (InvocationTargetException | NoSuchMethodException e) {
            throw new RuntimeException(e);
        } finally {
            try {
                // Restore the original Assistant component in Settings.Secure
                Settings.Secure.putString(context.getContentResolver(), ASSISTANT, currentAssistant);
            } catch (Throwable e) {
                Log.d(TAG, "launchAssistantWithTemporaryReplacement: " + e);
            }
        }
    }

    private static final Singleton<IUserManager> USER_MANAGER = new Singleton<IUserManager>() {
        @Override
        protected IUserManager create() {
            return IUserManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.USER_SERVICE)));
        }
    };

}
