package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.res.AssetManager;
import android.content.res.Resources;
import android.content.res.XmlResourceParser;
import android.util.Log;

import com.fourtwo.hookintent.ui.blocker.BlockerGroup;
import com.fourtwo.hookintent.ui.blocker.DeepLinkRule;

import org.xmlpull.v1.XmlPullParser;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class ManifestRuleScanner {
    private static final String TAG = "ManifestRuleScanner";
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    public static List<BlockerGroup> scan(Context context) {
        List<BlockerGroup> result = new ArrayList<>();
        PackageManager pm = context.getPackageManager();
        List<ApplicationInfo> applications = pm.getInstalledApplications(PackageManager.GET_META_DATA);
        for (ApplicationInfo appInfo : applications) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            try {
                String appName = String.valueOf(pm.getApplicationLabel(appInfo));
                List<DeepLinkRule> rules = parseRulesFromApk(context, appInfo, appName);
                if (!rules.isEmpty()) {
                    result.add(new BlockerGroup(appInfo.packageName, appName, false, rules));
                }
            } catch (RuntimeException e) {
                // An application can be removed while the scan is in progress.
                Log.w(TAG, "Cannot scan package " + appInfo.packageName, e);
            }
        }
        result.sort(Comparator.comparing(BlockerGroup::getAppName, String.CASE_INSENSITIVE_ORDER));
        return result;
    }

    private static List<DeepLinkRule> parseRulesFromApk(Context context, ApplicationInfo appInfo,
                                                       String appName) {
        List<DeepLinkRule> rules = new ArrayList<>();
        Set<String> dedupe = new HashSet<>();
        Set<String> apkPaths = new LinkedHashSet<>();
        if (appInfo.sourceDir != null) {
            apkPaths.add(appInfo.sourceDir);
        }
        if (appInfo.splitSourceDirs != null) {
            apkPaths.addAll(Arrays.asList(appInfo.splitSourceDirs));
        }
        try {
            Resources resources = context.getPackageManager().getResourcesForApplication(appInfo);
            for (String apkPath : apkPaths) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                parseManifest(apkPath, appInfo.packageName, appName, resources, rules, dedupe);
            }
        } catch (PackageManager.NameNotFoundException e) {
            Log.w(TAG, "Package disappeared during scan: " + appInfo.packageName);
        }
        return rules;
    }

    private static void parseManifest(String apkPath, String packageName, String appName,
                                      Resources resources, List<DeepLinkRule> rules,
                                      Set<String> dedupe) {
        AssetManager assetManager = null;
        XmlResourceParser parser = null;
        try {
            assetManager = AssetManager.class.getDeclaredConstructor().newInstance();
            Method addAssetPath = AssetManager.class.getMethod("addAssetPath", String.class);
            int cookie = (int) addAssetPath.invoke(assetManager, apkPath);
            if (cookie == 0) {
                return;
            }
            Method openXml = AssetManager.class.getMethod("openXmlResourceParser", int.class, String.class);
            parser = (XmlResourceParser) openXml.invoke(assetManager, cookie, "AndroidManifest.xml");
            String currentActivity = null;
            int filterDepth = -1;
            boolean hasView = false;
            boolean hasBrowsable = false;
            ManifestFilterData filterData = null;

            for (int event = parser.getEventType(); event != XmlPullParser.END_DOCUMENT;
                 event = parser.next()) {
                if (Thread.currentThread().isInterrupted()) {
                    return;
                }
                String tag = parser.getName();
                if (event == XmlPullParser.START_TAG) {
                    if ("activity".equals(tag) || "activity-alias".equals(tag)) {
                        currentActivity = normalizeClassName(packageName, value(parser, resources, "name"));
                    } else if ("intent-filter".equals(tag)) {
                        filterDepth = parser.getDepth();
                        hasView = false;
                        hasBrowsable = false;
                        filterData = new ManifestFilterData();
                    } else if (filterDepth >= 0 && parser.getDepth() == filterDepth + 1) {
                        if ("action".equals(tag)) {
                            hasView |= "android.intent.action.VIEW".equals(value(parser, resources, "name"));
                        } else if ("category".equals(tag)) {
                            hasBrowsable |= "android.intent.category.BROWSABLE".equals(value(parser, resources, "name"));
                        } else if ("data".equals(tag)) {
                            filterData.add(value(parser, resources, "scheme"),
                                    value(parser, resources, "host"), value(parser, resources, "port"),
                                    value(parser, resources, "path"), value(parser, resources, "pathPrefix"),
                                    value(parser, resources, "pathPattern"));
                        }
                    }
                } else if (event == XmlPullParser.END_TAG) {
                    if ("intent-filter".equals(tag) && parser.getDepth() == filterDepth) {
                        if (currentActivity != null && !currentActivity.isEmpty() && hasView && hasBrowsable) {
                            for (DeepLinkRule rule : filterData.toRules(packageName, appName, currentActivity)) {
                                String key = rule.toJson().toString();
                                if (dedupe.add(key)) {
                                    rules.add(rule);
                                }
                            }
                        }
                        filterDepth = -1;
                        filterData = null;
                    } else if ("activity".equals(tag) || "activity-alias".equals(tag)) {
                        currentActivity = null;
                    }
                }
            }
        } catch (Exception e) {
            // Keep the other splits usable if one archive cannot be read.
            Log.w(TAG, "Cannot read manifest for " + packageName, e);
        } finally {
            if (parser != null) {
                parser.close();
            }
            if (assetManager != null) {
                assetManager.close();
            }
        }
    }

    private static String normalizeClassName(String packageName, String rawName) {
        if (rawName.isEmpty()) {
            return "";
        }
        if (rawName.startsWith(".")) {
            return packageName + rawName;
        }
        return rawName.contains(".") ? rawName : packageName + "." + rawName;
    }

    private static String value(XmlResourceParser parser, Resources resources, String attr) {
        int resourceId = parser.getAttributeResourceValue(ANDROID_NS, attr, 0);
        if (resourceId != 0) {
            try {
                return resources.getString(resourceId);
            } catch (Resources.NotFoundException ignored) {
                return "";
            }
        }
        String value = parser.getAttributeValue(ANDROID_NS, attr);
        return value == null ? "" : value;
    }
}
