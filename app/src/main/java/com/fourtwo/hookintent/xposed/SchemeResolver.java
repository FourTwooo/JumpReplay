package com.fourtwo.hookintent.xposed;

import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;

/** Builds a new result without mutating PackageManager's snapshot or its cached ActivityInfo. */
public final class SchemeResolver {
    private SchemeResolver() { }

    public static Object resolve(Object original, Intent request, int userId, ActivityInfo activity,
                                 BiPredicate<String, String> disabled) {
        Intent intent = SystemQuerySpec.implicitUri(request);
        if (intent == null || !(original instanceof List) || activity == null
                || activity.applicationInfo == null || activity.applicationInfo.uid / 100000 != userId) return original;
        if (disabled.test(intent.getScheme(), intent.getDataString())) return new ArrayList<ResolveInfo>();
        List<ResolveInfo> result = inject((List<?>) original, intent, activity);
        return result == null ? original : result;
    }

    public static List<ResolveInfo> inject(List<?> original, Intent intent, ActivityInfo activity) {
        if (activity == null || activity.applicationInfo == null || !activity.enabled
                || !activity.applicationInfo.enabled || !activity.exported) return null;
        ArrayList<ResolveInfo> result = new ArrayList<>(original.size() + 1);
        for (Object item : original) {
            if (!(item instanceof ResolveInfo)) return null;
            ResolveInfo info = (ResolveInfo) item;
            if (info.activityInfo == null || info.activityInfo.applicationInfo == null) return null;
            if (activity.packageName.equals(info.activityInfo.packageName)
                    && activity.name.equals(info.activityInfo.name)) return null;
            result.add(info);
        }
        ResolveInfo added = new ResolveInfo();
        added.activityInfo = new ActivityInfo(activity);
        added.activityInfo.applicationInfo = new ApplicationInfo(activity.applicationInfo);
        added.resolvePackageName = activity.packageName;
        ResolveInfo first = result.isEmpty() ? null : result.get(0);
        added.priority = first == null ? 0 : first.priority;
        added.preferredOrder = first == null ? 0 : first.preferredOrder;
        added.match = first == null ? IntentFilter.MATCH_CATEGORY_SCHEME | IntentFilter.MATCH_ADJUSTMENT_NORMAL : first.match;
        added.isDefault = first == null || first.isDefault;
        IntentFilter filter = new IntentFilter();
        filter.addAction(intent.getAction() == null ? Intent.ACTION_VIEW : intent.getAction());
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        if (intent.getCategories() != null) for (String category : intent.getCategories()) filter.addCategory(category);
        filter.addDataScheme(intent.getScheme());
        if (intent.getType() != null) {
            try { filter.addDataType(intent.getType()); }
            catch (IntentFilter.MalformedMimeTypeException error) { return null; }
        }
        added.filter = filter;
        // chooseBestActivity compares the first TWO entries before considering a chooser.
        // Appending after a lower-priority second entry can leave this candidate unreachable.
        result.add(result.isEmpty() ? 0 : 1, added);
        return result;
    }
}
