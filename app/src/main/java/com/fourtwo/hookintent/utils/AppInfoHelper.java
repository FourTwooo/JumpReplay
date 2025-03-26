package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class AppInfoHelper {

    private final PackageManager packageManager;
    private final Map<String, AppInfo> cache = new HashMap<>();

    public AppInfoHelper(Context context) {
        this.packageManager = context.getPackageManager();
    }

    public AppInfo getAppInfo(String componentName) {
        if (componentName == null) return null;

        String packageName;
        if (Objects.equals(componentName, "/")) {
            packageName = componentName.split("/")[0];
        } else {
            packageName = componentName;
        }

        Log.d("AppInfoHelper", "getAppInfo: " + packageName + " " + componentName);

        // Check if the info is already cached
        if (cache.containsKey(componentName)) {
            return cache.get(componentName);
        }

        // Try to find the application info
        AppInfo appInfo = findAppInfo(packageName);
        if (appInfo != null) {
            // Cache the result
            cache.put(componentName, appInfo);
        }
        return appInfo;
    }

    private AppInfo findAppInfo(String packageName) {
        while (!packageName.isEmpty()) {
            try {
                ApplicationInfo appInfo = packageManager.getApplicationInfo(packageName, 0);
                String appName = packageManager.getApplicationLabel(appInfo).toString();
                Drawable appIcon = packageManager.getApplicationIcon(appInfo);
                return new AppInfo(appName, appIcon);
            } catch (PackageManager.NameNotFoundException e) {
                // Remove the last segment of the package name and try again
                int lastDotIndex = packageName.lastIndexOf('.');
                if (lastDotIndex == -1) {
                    return null; // No more segments to remove
                }
                packageName = packageName.substring(0, lastDotIndex);
            }
        }
        return null;
    }

    public static class AppInfo {
        private final String appName;
        private final Drawable appIcon;

        public AppInfo(String appName, Drawable appIcon) {
            this.appName = appName;
            this.appIcon = appIcon;
        }

        public String getAppName() {
            return appName;
        }

        public Drawable getAppIcon() {
            return appIcon;
        }
    }
}

