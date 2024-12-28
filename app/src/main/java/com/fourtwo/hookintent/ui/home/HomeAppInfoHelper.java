package com.fourtwo.hookintent.ui.home;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;

public class HomeAppInfoHelper {

    private final PackageManager packageManager;
    private final Map<String, AppInfo> cache = new HashMap<>();

    public HomeAppInfoHelper(Context context) {
        this.packageManager = context.getPackageManager();
    }

    public AppInfo getAppInfo(String componentName) {
        if (Objects.equals(componentName, "/") || componentName == null) return null;

        // Check if the info is already cached
        if (cache.containsKey(componentName)) {
            return cache.get(componentName);
        }

        // Parse the package name from the component
        String packageName = componentName.split("/")[0];

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

