package com.fourtwo.hookintent.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.graphics.drawable.Drawable;
import android.util.LruCache;
import androidx.core.content.ContextCompat;
import com.fourtwo.hookintent.R;

public class AppInfoHelper {
    private static final LruCache<String, AppInfo> CACHE = new LruCache<>(128);
    private final Context context;
    private final PackageManager packageManager;
    public AppInfoHelper(Context context) {
        this.context = context.getApplicationContext();
        packageManager = this.context.getPackageManager();
    }
    public AppInfo getAppInfo(String componentName) {
        String packageName = packageFromComponent(componentName);
        if (packageName == null) return null;
        AppInfo cached = CACHE.get(packageName);
        if (cached != null) return cached;
        String candidate = packageName;
        while (!candidate.isEmpty()) {
            try {
                ApplicationInfo info = packageManager.getApplicationInfo(candidate, 0);
                AppInfo result = new AppInfo(packageManager.getApplicationLabel(info).toString(), packageManager.getApplicationIcon(info));
                CACHE.put(packageName, result);
                return result;
            } catch (PackageManager.NameNotFoundException e) {
                // Legacy captures sometimes stored a fully-qualified class instead of package/component.
                if (componentName.indexOf('/') >= 0) return null;
                int lastDot = candidate.lastIndexOf('.');
                if (lastDot < 0) return null;
                candidate = candidate.substring(0, lastDot);
            }
        }
        return null;
    }
    public static String packageFromComponent(String value) {
        if (value == null || value.isEmpty() || "null".equals(value) || "/".equals(value)) return null;
        String name = value;
        int start = name.indexOf('{');
        int end = name.lastIndexOf('}');
        if (start >= 0 && end > start) name = name.substring(start + 1, end);
        ComponentName component = ComponentName.unflattenFromString(name);
        if (component != null) return component.getPackageName();
        int slash = name.indexOf('/');
        return slash > 0 ? name.substring(0, slash) : name;
    }
    public static class AppInfo {
        private final String appName;
        private final Drawable appIcon;
        public AppInfo(String name, Drawable icon) { appName = name; appIcon = icon; }
        public String getAppName() { return appName; }
        public Drawable getAppIcon() { return appIcon; }
    }
    public static AppInfo getAppInfo(Context context, String componentName) {
        AppInfo info = new AppInfoHelper(context).getAppInfo(componentName);
        String name = packageFromComponent(componentName);
        return info != null ? info : new AppInfo("未知应用" + (name == null ? "" : "(" + name + ")"), ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground));
    }
}
