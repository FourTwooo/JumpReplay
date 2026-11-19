package com.fourtwo.hookintent.library;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Uses Android's installed package index. Only launcher filters are queried here. */
final class PackageActivityCatalog {
    private static final int DISABLED = PackageManager.MATCH_DISABLED_COMPONENTS;

    static List<App> applications(Context context) {
        PackageManager manager = context.getPackageManager();
        List<App> apps = new ArrayList<>();
        for (ApplicationInfo info : manager.getInstalledApplications(0)) {
            apps.add(new App(info.packageName, manager.getApplicationLabel(info).toString()));
        }
        apps.sort((first, second) -> Collator.getInstance().compare(first.label, second.label));
        return apps;
    }

    static List<Entry> activities(Context context, String packageName) throws PackageManager.NameNotFoundException {
        PackageManager manager = context.getPackageManager();
        PackageInfo pkg = manager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES | DISABLED);
        long version = Build.VERSION.SDK_INT >= 28 ? pkg.getLongVersionCode() : pkg.versionCode;
        Intent launcher = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setPackage(packageName);
        Set<String> launcherActivities = new HashSet<>();
        for (ResolveInfo match : manager.queryIntentActivities(launcher, DISABLED)) {
            if (match.activityInfo != null) launcherActivities.add(match.activityInfo.name);
        }
        List<Entry> entries = new ArrayList<>();
        if (pkg.activities == null) return entries;
        for (ActivityInfo info : pkg.activities) {
            ComponentName component = new ComponentName(packageName, info.name);
            int state = manager.getComponentEnabledSetting(component);
            boolean enabled = (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
                    ? info.enabled : state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)
                    && info.applicationInfo != null && info.applicationInfo.enabled;
            entries.add(new Entry(packageName, info.name, info.targetActivity, info.exported, enabled,
                    info.permission, launcherActivities.contains(info.name), pkg.versionName, version, pkg.lastUpdateTime));
        }
        entries.sort(Comparator.comparing(entry -> entry.name));
        return entries;
    }

    static final class App {
        final String packageName, label;

        App(String packageName, String label) {
            this.packageName = packageName;
            this.label = label;
        }
    }

    static final class Entry {
        final String packageName, name, aliasTarget, permission, versionName;
        final boolean exported, enabled, launcher;
        final long versionCode, updated;

        Entry(String packageName, String name, String aliasTarget, boolean exported, boolean enabled, String permission,
                boolean launcher, String versionName, long versionCode, long updated) {
            this.packageName = packageName;
            this.name = name;
            this.aliasTarget = aliasTarget;
            this.exported = exported;
            this.enabled = enabled;
            this.permission = permission;
            this.launcher = launcher;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.updated = updated;
        }
    }

    private PackageActivityCatalog() { }
}
