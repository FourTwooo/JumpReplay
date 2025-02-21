package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.Extract;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.base.UriData;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.ui.home.HomeAppInfoHelper;
import com.fourtwo.hookintent.viewmodel.MainViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DataProcessor {
    private static final String TAG = "DataProcessor";

    private final Context context;
    private final MainViewModel viewModel;
    private final IntentDuplicateChecker intentDuplicateChecker;
    private final IntentDuplicateChecker schemeDuplicateChecker;
    private Map<String, Object> JsonData; // 新增 JsonData 变量

    public DataProcessor(Context context, MainViewModel viewModel) {
        this.context = context;
        this.viewModel = viewModel;
        this.intentDuplicateChecker = new IntentDuplicateChecker();
        this.schemeDuplicateChecker = new IntentDuplicateChecker();
    }

    // 新增方法：设置 JsonData
    public void setJsonData(Map<String, Object> jsonData) {
        this.JsonData = jsonData;
    }

    public void processReceivedData(Bundle data) {
        try {
            if (data == null) {
                return;
            }

            // 从 Binder 中获取批量数据
//            AIDL_MSG_SEND_DATA batchBinder = AIDL_MSG_SEND_DATA.Stub.asInterface(data.getBinder("batch_data_binder"));
//            if (batchBinder == null) {return;}

            // 从 Binder 中获取 JSON 字符串
//            String batchDataJson = batchBinder.GET_MSG_SEND_DATA();
            String batchDataJson = data.getString("batch_data_binder");

            // 将 JSON 字符串解析为 List<Bundle>
            List<Bundle> bundles = JsonHandler.fromJson(batchDataJson);
            Log.d(TAG, "processReceivedData: " + bundles.size());
            // 遍历解析每个 Bundle 的数据
            for (Bundle bundle : bundles) {
                String base = bundle.getString("Base");
                String stackTrace = bundle.getString("stack_trace");
                String uri = bundle.getString("uri");

                // 数据处理逻辑
                bundle.remove("stack_trace");
                bundle.remove("uri");
                bundle.remove("Base");

                // 你可以在这里对单个 Bundle 做进一步处理
                Log.d(TAG, "Processed bundle: bundle=" + bundle);

                // 数据过滤逻辑
                if (filterData(base, bundle)) {
                    continue;
                }

                // 其他数据处理逻辑
                String dataSize = Extract.calculateBundleDataSize(bundle);
                String time = Extract.extractTime(bundle.getString("time"));
                String packageName = "";
                String to = "";
                String dataString = "";

                if ("Intent".equals(base)) {
                    if (handleIntentBase(bundle)) continue;
                    ArrayList<?> intentExtras = bundle.getStringArrayList("intentExtras");
                    if (intentExtras != null) {
                        dataString = Extract.extractIntentExtrasString(intentExtras);
                    }
                    to = bundle.getString("to");
                    packageName = bundle.getString("componentName");
                    if (Objects.equals(packageName, "null") && !Objects.equals(bundle.getString("dataString"), "null")) {
                        packageName = SchemeResolver.findAppByUri(context, bundle.getString("dataString")) + "/";
                        to = bundle.getString("dataString");
                    }
                    if (Objects.equals(packageName, "null") && !Objects.equals(bundle.getString("action"), "null")) {
                        packageName = SchemeResolver.findAppByUri(context, bundle.getString("action")) + "/";
                        to = bundle.getString("action");
                    }
                } else if ("Scheme".equals(base)) {
                    if (handleSchemeBase(bundle)) continue;
                    String schemeRawUrl = bundle.getString("scheme_raw_url");
                    packageName = SchemeResolver.findAppByUri(context, schemeRawUrl) + "/";
                    Bundle bundle1 = Extract.convertMapToBundle(UriData.convertUriToMap(Uri.parse(schemeRawUrl)));
                    bundle.putAll(bundle1);

                    if (schemeRawUrl.startsWith("#Intent;") || bundle.getString("authority").equals("null")) {
                        to = schemeRawUrl;
                        packageName = Extract.getIntentSchemeValue(schemeRawUrl, "component");
                        if (packageName == null) {
                            packageName = Extract.getIntentSchemeValue(schemeRawUrl, "action") + "/";
                        }
                    } else {
                        to = bundle.getString("scheme") + "://" + bundle.getString("authority") + bundle.getString("path");
                    }
                    dataString = bundle.getString("query");
                    if ("null".equals(dataString)) {
                        dataString = "";
                    }
                } else {
                    continue;
                }

                // 获取应用信息
                HomeAppInfoHelper.AppInfo appInfo = getAppInfo(context, packageName);
                String appName = appInfo.getAppName();
                Drawable appIcon = appInfo.getAppIcon();

                // 创建 ItemData
                ItemData itemData = new ItemData(appIcon, appName, to, dataString, time, String.format("%s B", dataSize), bundle, base, stackTrace, uri);
                viewModel.addIntentData(itemData);

            }


        } catch (Exception e) {
            Log.e(TAG, "Error processing data", e);
        }
    }

    private boolean handleIntentBase(Bundle bundle) {
        Log.d(TAG, "Removing duplicate Intent bundle: " + bundle);
        return intentDuplicateChecker.isDuplicate(bundle);
    }

    private boolean handleSchemeBase(Bundle bundle) {
        Log.d(TAG, "Removing duplicate Scheme bundle: " + bundle);
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
                assert intentData != null;
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
                assert schemeData != null;

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
