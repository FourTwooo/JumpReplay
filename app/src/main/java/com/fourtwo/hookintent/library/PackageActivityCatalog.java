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

import org.json.JSONArray;

import java.net.URISyntaxException;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Uses Android's installed package index. Only launcher filters are queried here. */
final class PackageActivityCatalog {
    private static final int DISABLED = PackageManager.MATCH_DISABLED_COMPONENTS;

    static List<App> applications(Context context) {
        PackageManager manager = context.getPackageManager();
        List<App> apps = new ArrayList<>();
        for (PackageInfo pkg : manager.getInstalledPackages(0)) {
            ApplicationInfo info = pkg.applicationInfo;
            String label = info == null ? pkg.packageName : manager.getApplicationLabel(info).toString();
            apps.add(new App(pkg.packageName, label, pkg.versionName, versionCode(pkg), info));
        }
        Collator collator = Collator.getInstance();
        apps.sort(Comparator.comparing((App app) -> app.label, collator).thenComparing(app -> app.packageName));
        return apps;
    }

    static List<Entry> activities(Context context, String packageName) throws PackageManager.NameNotFoundException {
        PackageManager manager = context.getPackageManager();
        PackageInfo pkg = manager.getPackageInfo(packageName, PackageManager.GET_ACTIVITIES | DISABLED);
        long version = versionCode(pkg);
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

    static List<Activity> merge(List<Entry> declared, List<IntentLibrary.Observation> observed) {
        Map<String, Entry> declarations = new LinkedHashMap<>();
        Map<String, List<IntentLibrary.Observation>> byActivity = new TreeMap<>();
        Map<String, List<IntentLibrary.Observation>> unresolved = new LinkedHashMap<>();
        boolean hasAliases = false;
        for (Entry entry : declared) {
            String name = className(entry.packageName, entry.name);
            declarations.put(name, entry);
            byActivity.put(name, new ArrayList<>());
            hasAliases |= entry.aliasTarget != null;
        }
        for (IntentLibrary.Observation observation : observed) {
            if (observation.destinationPackage.isEmpty() || observation.activity.isEmpty()) {
                String route = new JSONArray().put(observation.action).put(IntentLibrary.uriShape(observation.data))
                        .put(observation.mimeType).toString();
                unresolved.computeIfAbsent(route, ignored -> new ArrayList<>()).add(observation);
                continue;
            }
            String name = className(observation.destinationPackage, observation.activity);
            if (hasAliases && observation.delivered) name = observedAlias(declarations, observation, name);
            byActivity.computeIfAbsent(name, ignored -> new ArrayList<>()).add(observation);
        }
        List<Activity> activities = new ArrayList<>();
        for (Map.Entry<String, List<IntentLibrary.Observation>> group : byActivity.entrySet()) {
            String name = group.getKey();
            activities.add(new Activity(name, declarations.get(name), group.getValue(), "", ""));
        }
        for (List<IntentLibrary.Observation> samples : unresolved.values()) {
            IntentLibrary.Observation latest = samples.get(0);
            activities.add(new Activity("", null, samples, latest.action, latest.data));
        }
        return activities;
    }

    private static String observedAlias(Map<String, Entry> declarations, IntentLibrary.Observation observation,
            String activity) {
        String requestedPackage = observation.componentPackage;
        String requestedClass = observation.componentClass;
        if (requestedClass.isEmpty() && observation.intentUri != null) {
            try {
                ComponentName component = Intent.parseUri(observation.intentUri, Intent.URI_INTENT_SCHEME).getComponent();
                if (component != null) {
                    requestedPackage = component.getPackageName();
                    requestedClass = component.getClassName();
                }
            } catch (URISyntaxException | RuntimeException ignored) {
                return activity;
            }
        }
        if (!observation.destinationPackage.equals(requestedPackage)) return activity;
        String requested = className(requestedPackage, requestedClass);
        Entry declaration = declarations.get(requested);
        return declaration != null && declaration.aliasTarget != null
                && activity.equals(className(requestedPackage, declaration.aliasTarget)) ? requested : activity;
    }

    private static String className(String packageName, String name) {
        return name.startsWith(".") ? packageName + name : name;
    }

    private static long versionCode(PackageInfo info) {
        return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
    }

    static final class App {
        final String packageName, label, versionName;
        final long versionCode;
        final ApplicationInfo applicationInfo;

        App(String packageName, String label, String versionName, long versionCode, ApplicationInfo applicationInfo) {
            this.packageName = packageName;
            this.label = label;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.applicationInfo = applicationInfo;
        }
    }

    static final class Activity {
        final String name, action, data;
        final Entry declaration;
        final List<IntentLibrary.Observation> observations;

        private Activity(String name, Entry declaration, List<IntentLibrary.Observation> observations,
                String action, String data) {
            this.name = name;
            this.declaration = declaration;
            this.observations = Collections.unmodifiableList(observations);
            this.action = action;
            this.data = data;
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
