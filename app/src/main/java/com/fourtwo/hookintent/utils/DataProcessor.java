package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.base.Extract;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.ui.home.HomeAppInfoHelper;
import com.fourtwo.hookintent.ui.star.StarViewModel;
import com.fourtwo.hookintent.viewmodel.MainViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DataProcessor {
    private static final String TAG = "DataProcessor";

    private final Context context;
    private final IntentDuplicateChecker intentDuplicateChecker;
    private final IntentDuplicateChecker schemeDuplicateChecker;
    private Map<String, Object> JsonData;

    public DataProcessor(Context context) {
        this.context = context;
        this.intentDuplicateChecker = new IntentDuplicateChecker();
        this.schemeDuplicateChecker = new IntentDuplicateChecker();
    }

    public interface DataProcessedCallback {
        void onDataProcessed(ItemData itemData);
    }

    // 新增方法：设置 JsonData
    public void setJsonData(Map<String, Object> jsonData) {
        this.JsonData = jsonData;
    }

    public void processBundle(Bundle bundle, DataProcessedCallback callback) {
        String base = bundle.getString("Base");
        String stackTrace = bundle.getString("stack_trace");
        String uri = bundle.getString("uri");
        String time = Extract.extractTime(bundle.getString("time"));
        if (filterData(base, bundle) && !JsonData.isEmpty()) {
            return;
        }
        Log.d(TAG, "processBundle1: " + bundle.getString("time"));
        String dataSize = Extract.calculateBundleDataSize(bundle);
        String packageName;
        String component;
        String dataString = "";

        if ("Intent".equals(base)) {
            if (handleIntentBase(bundle) && !JsonData.isEmpty()) return;
            component = bundle.getString("component");
            packageName = component;
            ArrayList<?> intentExtras = (ArrayList<?>) bundle.getSerializable("intentExtras");
            if (intentExtras != null) {
                dataString = Extract.extractIntentExtrasString(intentExtras);
            }
            if (Objects.equals(component, "null") && !Objects.equals(bundle.getString("dataString"), "null")) {
                packageName = SchemeResolver.findAppByUri(context, bundle.getString("dataString"));
                component = bundle.getString("dataString");
            }
            if (Objects.equals(component, "null") && !Objects.equals(bundle.getString("action"), "null")) {
                packageName = SchemeResolver.findAppByUri(context, bundle.getString("action"));
                component = bundle.getString("action");
            }
        } else if ("Scheme".equals(base)) {
            if (handleSchemeBase(bundle) && !JsonData.isEmpty()) return;
            String schemeRawUrl = bundle.getString("scheme_raw_url");
            packageName = SchemeResolver.findAppByUri(context, schemeRawUrl);
            Bundle bundle1 = DataConverter.convertUriToBundle(Uri.parse(schemeRawUrl));
            bundle.putAll(bundle1);

            if (schemeRawUrl.startsWith("#Intent;") || bundle.getString("authority").equals("null")) {
                component = schemeRawUrl;
                packageName = Extract.getIntentSchemeValue(schemeRawUrl, "component");
                if (packageName == null) {
                    packageName = Extract.getIntentSchemeValue(schemeRawUrl, "action");
                }
            } else {
                component = bundle.getString("scheme") + "://" + bundle.getString("authority") + bundle.getString("path");
            }
            dataString = bundle.getString("query");
            if ("null".equals(dataString)) {
                dataString = "";
            }
        } else {
            return;
        }

        HomeAppInfoHelper.AppInfo appInfo = getAppInfo(context, packageName);
        String appName = appInfo.getAppName();
        Drawable appIcon = appInfo.getAppIcon();

        Log.d(TAG, "processBundle end: " + bundle.getString("time"));
        ItemData itemData = new ItemData(
                appIcon,
                appName,
                component,
                dataString,
                time,
                String.format("%s B", dataSize),
                bundle,
                base,
                stackTrace,
                uri
        );

        if (callback != null) {
            callback.onDataProcessed(itemData);
        }
    }

    public void processReceivedData(Bundle data, DataProcessedCallback callback) {
        try {
            if (data == null) {
                return;
            }

            String batchDataJson = data.getString("batch_data_binder");

            List<Bundle> bundles = JsonHandler.fromJson(batchDataJson);
            Log.d(TAG, "processReceivedData: " + bundles.size());

            for (Bundle bundle : bundles) {
                processBundle(bundle, callback);
            }

        } catch (Exception e) {
            Log.e(TAG, "Error processing data", e);
        }
    }

    private boolean handleIntentBase(Bundle bundle) {
//        Log.d(TAG, "Removing duplicate Intent bundle: " + bundle);
        return intentDuplicateChecker.isDuplicate(bundle);
    }

    private boolean handleSchemeBase(Bundle bundle) {
//        Log.d(TAG, "Removing duplicate Scheme bundle: " + bundle);
        return schemeDuplicateChecker.isDuplicate(bundle);
    }

    private HomeAppInfoHelper.AppInfo getAppInfo(Context context, String packageName) {
        HomeAppInfoHelper homeAppInfoHelper = new HomeAppInfoHelper(context);
        HomeAppInfoHelper.AppInfo appInfo = homeAppInfoHelper.getAppInfo(packageName);
        if (appInfo != null) {
            return appInfo;
        }
        return new HomeAppInfoHelper.AppInfo(
                "未知应用" + ("/".equals(packageName) || "null".equals(packageName) ? "" : String.format("(%s)", packageName)),
                ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
        );
    }


    public boolean isFilterMatched(Map<String, Object> schemeData, String key, String valueToMatch) {
        List<Map<String, Object>> itemList = JsonHandler.getFilterValueJson(schemeData.get(key));
        if (itemList == null) {
            return false;
        }

        for (Map<String, Object> item : itemList) {
            Boolean type = (Boolean) item.get("type");
            String text = (String) item.get("text");

            if (Boolean.TRUE.equals(type) && valueToMatch.equalsIgnoreCase(text)) {
                return true;
            }
        }
        return false;
    }


    private boolean filterData(String base, Bundle bundle) {
        boolean is_filter = false;

        String functionCall = bundle.getString("FunctionCall");
        String from = bundle.getString("from");

        switch (base) {
            case "Intent":
                Map<String, Object> intentData = JsonHandler.getFilterKeyJson(JsonData.get("intent"));
                if (intentData == null) return false;
                if (isFilterMatched(intentData, "FunctionCall", functionCall)) {
                    is_filter = true;
                }
                if (!is_filter && isFilterMatched(intentData, "from", from)) {
                    is_filter = true;
                }
                break;
            case "Scheme":
                String scheme_url = bundle.getString("scheme_raw_url");
                if (scheme_url == null) {
                    is_filter = true;
                    break;
                }
                String scheme = Uri.parse(scheme_url).getScheme();
                if (scheme == null) {
                    is_filter = true;
                    break;
                }

                Map<String, Object> schemeData = JsonHandler.getFilterKeyJson(JsonData.get("scheme"));
                if (schemeData == null) return false;

                if (isFilterMatched(schemeData, "FunctionCall", functionCall)) {
                    is_filter = true;
                }

                if (!is_filter && isFilterMatched(schemeData, "from", from)) {
                    is_filter = true;
                }

                if (!is_filter && isFilterMatched(schemeData, "scheme", scheme)) {
                    is_filter = true;
                }
                break;
            default:
                break;
        }
        return is_filter;
    }
}
