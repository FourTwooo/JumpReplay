package com.fourtwo.hookintent.utils;

import android.annotation.SuppressLint;
import android.app.SearchManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.IPackageManager;
import android.content.pm.PackageManager;
import android.content.pm.UserInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.IInterface;
import android.os.IUserManager;
import android.os.RemoteException;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

import rikka.shizuku.Shizuku;
import rikka.shizuku.ShizukuBinderWrapper;
import rikka.shizuku.SystemServiceHelper;

public class ShizukuSystemServerApi {

    static final String TAG = "ShizukuSystemServerApi";

    private static final Singleton<IPackageManager> PACKAGE_MANAGER = new Singleton<IPackageManager>() {
        @Override
        protected IPackageManager create() {
            return IPackageManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService("package")));
        }
    };

    public static void checkPackageManagerLevel(Object iPackageManager) {
        try {
            // 确保传入的对象是 IPackageManager 接口的实现
            if (!(iPackageManager instanceof IInterface)) {
                Log.d(TAG, "传入的对象不是 IPackageManager 实现");
                return;
            }

            // 通过反射检查是否能够调用系统级方法
            Method grantRuntimePermissionMethod = iPackageManager.getClass().getDeclaredMethod(
                    "grantRuntimePermission",
                    String.class, String.class, int.class
            );
            grantRuntimePermissionMethod.setAccessible(true);

            Log.d(TAG, "IPackageManager权限：具有调用系统方法的能力（可能是系统权限或更高级别权限）");
        } catch (NoSuchMethodException e) {
            Log.d(TAG, "IPackageManager权限：没有系统权限（普通用户权限）");
        } catch (Exception e) {
            Log.e(TAG, "检查 IPackageManager 权限时发生异常", e);
        }
    }

    private static boolean requestShizukuPermission() {
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            Log.d(TAG, "requestShizukuPermission: 已拥有权限");
            return true;
        }

        if (Shizuku.isPreV11()) {
            Log.d(TAG, "requestShizukuPermission: 未拥有权限");
            return false;
        }
        // 动态申请权限
        Shizuku.requestPermission(1);
        return false;
    }

    public static void launchAssistantWithTemporaryReplacement(Context context, Intent intent) throws IllegalAccessException, NoSuchFieldException, NoSuchMethodException, InvocationTargetException {
        if (!requestShizukuPermission()){return;}

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
                e.printStackTrace();
            }
        }
    }

    private static final Singleton<IUserManager> USER_MANAGER = new Singleton<IUserManager>() {
        @Override
        protected IUserManager create() {
            return IUserManager.Stub.asInterface(new ShizukuBinderWrapper(SystemServiceHelper.getSystemService(Context.USER_SERVICE)));
        }
    };

    public static List<UserInfo> UserManager_getUsers(boolean excludePartial, boolean excludeDying, boolean excludePreCreated) throws RemoteException {
        if (Build.VERSION.SDK_INT >= 30) {
            return USER_MANAGER.get().getUsers(excludePartial, excludeDying, excludePreCreated);
        } else {
            try {
                return USER_MANAGER.get().getUsers(excludeDying);
            } catch (NoSuchFieldError e) {
                return USER_MANAGER.get().getUsers(excludePartial, excludeDying, excludePreCreated);
            }
        }
    }

}
