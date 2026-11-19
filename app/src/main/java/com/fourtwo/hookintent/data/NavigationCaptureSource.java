package com.fourtwo.hookintent.data;

import android.content.Context;
import android.os.Bundle;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import java.util.HashMap;
import java.util.Map;

/** Configuration of in-app Fragment capture and identification of older navigation records. */
public enum NavigationCaptureSource {
    FRAGMENT(Constants.FRAGMENT_CATEGORY, Constants.FRAGMENT_CAPTURE_ENABLED,
            "android.view.ViewTreeObserver$OnGlobalLayoutListener", "onGlobalLayout", "#7255AB");

    private final String category;
    private final String preference;
    private final String className;
    private final String method;
    private final String defaultColor;
    NavigationCaptureSource(String category, String preference, String className, String method,
            String defaultColor) {
        this.category = category;
        this.preference = preference;
        this.className = className;
        this.method = method;
        this.defaultColor = defaultColor;
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
        boolean defaultValue = SharedPreferencesUtils.getBoolean(context, Constants.IN_APP_NAVIGATION_CAPTURE);
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
        // The first release used one category and stored the source in FunctionCall.
        if (Constants.FRAGMENT_CATEGORY.equals(category)
                || (Constants.NAVIGATION_CATEGORY.equals(category)
                && Constants.FRAGMENT_CATEGORY.equals(record.getString("FunctionCall")))) return FRAGMENT;
        return null;
    }

    public static boolean isHistoricalRecord(String category, Bundle record) {
        return record != null && record.containsKey(Constants.NAVIGATION_ARGUMENTS)
                && (isRetiredCategory(category) || (Constants.NAVIGATION_CATEGORY.equals(category)
                && Constants.NAV_CONTROLLER_CATEGORY.equals(record.getString("FunctionCall"))));
    }

    public static boolean isRetiredCategory(String category) {
        return Constants.NAV_CONTROLLER_CATEGORY.equals(category) || Constants.VIEW_PAGER_CATEGORY.equals(category);
    }

    /** Recognize a generated row from older settings without treating custom hooks as built-ins. */
    public static boolean isRetiredConfigurationRow(Map<String, Object> row) {
        if (Boolean.FALSE.equals(row.get("internal")) || !"ALL".equals(row.get("packageName"))) return false;
        Object category = row.get("category");
        Object owner = row.get("className");
        Object method = row.get("methodName");
        return (Constants.NAV_CONTROLLER_CATEGORY.equals(category)
                && "androidx.navigation.NavController$OnDestinationChangedListener".equals(owner)
                && "onDestinationChanged".equals(method))
                || (Constants.VIEW_PAGER_CATEGORY.equals(category)
                && "androidx.viewpager.widget.ViewPager".equals(owner) && "setCurrentItem".equals(method));
    }
}
