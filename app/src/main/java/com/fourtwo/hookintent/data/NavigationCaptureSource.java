package com.fourtwo.hookintent.data;

import android.content.Context;
import android.os.Bundle;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import java.util.HashMap;
import java.util.Map;

/** Source and configuration of a built-in in-app navigation observation. */
public enum NavigationCaptureSource {
    FRAGMENT(Constants.FRAGMENT_CATEGORY, Constants.FRAGMENT_CAPTURE_ENABLED,
            "android.view.ViewTreeObserver$OnGlobalLayoutListener", "onGlobalLayout", "#7255AB", true),
    NAV_CONTROLLER(Constants.NAV_CONTROLLER_CATEGORY, Constants.NAV_CONTROLLER_CAPTURE_ENABLED,
            "androidx.navigation.NavController$OnDestinationChangedListener", "onDestinationChanged", "#2C7585", true),
    VIEW_PAGER(Constants.VIEW_PAGER_CATEGORY, Constants.VIEW_PAGER_CAPTURE_ENABLED,
            "androidx.viewpager.widget.ViewPager", "setCurrentItem", "#8B5735", false);

    private final String category;
    private final String preference;
    private final String className;
    private final String method;
    private final String defaultColor;
    private final boolean migrateLegacyPreference;

    NavigationCaptureSource(String category, String preference, String className, String method,
            String defaultColor, boolean migrateLegacyPreference) {
        this.category = category;
        this.preference = preference;
        this.className = className;
        this.method = method;
        this.defaultColor = defaultColor;
        this.migrateLegacyPreference = migrateLegacyPreference;
    }

    public String category() {
        return category;
    }

    public String method() {
        return method;
    }

    public String preference() {
        return preference;
    }

    public String defaultColor() {
        return defaultColor;
    }

    public boolean isEnabled(Context context) {
        boolean defaultValue = migrateLegacyPreference
                && SharedPreferencesUtils.getBoolean(context, Constants.IN_APP_NAVIGATION_CAPTURE);
        return SharedPreferencesUtils.getBoolean(context, preference, defaultValue);
    }

    public void setEnabled(Context context, boolean enabled) {
        SharedPreferencesUtils.putBoolean(context, preference, enabled);
    }

    public Map<String, Object> configurationRow(Context context) {
        Map<String, Object> row = new HashMap<>();
        row.put("internal", true);
        row.put("packageName", "ALL");
        row.put("category", category);
        row.put("className", className);
        row.put("methodName", method);
        row.put("open", isEnabled(context));
        return row;
    }

    public static NavigationCaptureSource forConfigurationRow(Map<String, Object> row) {
        if (!Boolean.TRUE.equals(row.get("internal")) || !"ALL".equals(row.get("packageName"))) return null;
        NavigationCaptureSource source = forCategory(String.valueOf(row.get("category")));
        return source != null && source.className.equals(row.get("className"))
                && source.method.equals(row.get("methodName")) ? source : null;
    }

    public static NavigationCaptureSource forCategory(String category) {
        for (NavigationCaptureSource source : values()) {
            if (source.category.equalsIgnoreCase(category)) return source;
        }
        return null;
    }

    public static boolean isPreference(String preference) {
        for (NavigationCaptureSource source : values()) {
            if (source.preference.equals(preference)) return true;
        }
        return false;
    }

    public static NavigationCaptureSource fromRecord(String category, Bundle record) {
        if (record == null || !record.containsKey(Constants.NAVIGATION_ARGUMENTS)) return null;
        for (NavigationCaptureSource source : values()) {
            if (source.category.equals(category)) return source;
        }

        // The first release used one category and stored the source in FunctionCall.
        if (Constants.NAVIGATION_CATEGORY.equals(category)) {
            String kind = record.getString("FunctionCall");
            if (Constants.FRAGMENT_CATEGORY.equals(kind)) return FRAGMENT;
            if (Constants.NAV_CONTROLLER_CATEGORY.equals(kind)) return NAV_CONTROLLER;
        }
        return null;
    }
}
