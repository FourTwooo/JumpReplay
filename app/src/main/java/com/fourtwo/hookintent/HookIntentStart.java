package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.app.ActivityThread;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.fourtwo.hookintent.analysis.CustomUri;
import com.fourtwo.hookintent.analysis.IntentData;
import com.fourtwo.hookintent.analysis.UriData;
import com.fourtwo.hookintent.analysis.extract;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class HookIntentStart implements IXposedHookLoadPackage {

    private Boolean isHook = false;

    private final String TAG = "XposedJumpReplay";

    private Boolean getIsHook() {
        Context appContext = getAppContext();
        if (appContext != null) {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                try {
                    Intent intentMsg = new Intent("GET_JUMP_REPLAY_HOOK");
                    intentMsg.putExtra("data", "get_isHook");
                    intentMsg.putExtra("type", "msg");
                    appContext.sendBroadcast(intentMsg);
                } catch (Exception e) {
                    Log.e(TAG, "HandlerException Error sending intent", e);
                }
            });
        }
        Log.d(TAG, "onReceiveIntentIsHook" + isHook);
        return isHook;
    }

    private String getStackTraceString() {
        Throwable throwable = new Throwable();
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        StringBuilder stackTraceString = new StringBuilder();
        for (StackTraceElement element : stackTrace) {
            stackTraceString.append(element.toString()).append("\n");
        }
        return stackTraceString.toString();
    }


    private void sendBroadcastSafely(Map<String, Object> mapData, String base, String StackTraceString) {
        Context appContext = getAppContext();
        String uri = "";
        if (appContext == null) return;
        if (mapData.containsKey("uri")) {
            uri = (String) mapData.get("uri");
            mapData.remove("uri");
        }
        if (!mapData.containsKey("from")) {
            mapData.put("from", appContext.getClass().getName());
        }
        Handler mainHandler = new Handler(Looper.getMainLooper());
        String finalUri = uri;
        mainHandler.post(() -> {
            try {
                Bundle bundle = extract.convertMapToBundle(mapData);
                Intent intentMsg = new Intent("GET_JUMP_REPLAY_HOOK");
                intentMsg.putExtra("info", bundle);
                intentMsg.putExtra("Base", base);
                intentMsg.putExtra("data-type", "data");
                intentMsg.putExtra("stack_trace", StackTraceString);
                intentMsg.putExtra("uri", finalUri);

                appContext.sendBroadcast(intentMsg);
                Log.d(TAG, "putExtraBundle " + bundle);
            } catch (Exception e) {
                Log.e(TAG, "HandlerException Error sending intent", e);
            }
        });
    }

    private boolean isCustomScheme(String scheme_url) {
        String scheme = CustomUri.getScheme(scheme_url);
        Log.d(TAG, "scheme_url  => " + scheme_url);
        List<String> standardSchemes = Arrays.asList("http", "https", "file", "content", "data", "about", "javascript", "mailto", "ftp", "ftps", "ws", "wss", "tel", "sms", "smsto", "geo", "market", "res");
        if (scheme != null) {
            for (String standardScheme : standardSchemes) {
                if (scheme.equalsIgnoreCase(standardScheme)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    private void filterScheme(String scheme_raw_url, String FunctionCall) {

        if (scheme_raw_url != null && isCustomScheme(scheme_raw_url)) {
            Map<String, Object> MapData = UriData.GetMap(scheme_raw_url);
            Log.d(TAG, "filterSchemeMapData MapData: " + MapData);
            MapData.put("FunctionCall", FunctionCall);
            sendBroadcastSafely(MapData, "Scheme", getStackTraceString());
        }
    }

    private void filterIntent(Intent intent, String FunctionCall, String from) {
//        String uri = (String) XposedHelpers.callMethod(intent, "toUri", Intent.URI_INTENT_SCHEME);
        intent.putExtra("skipToUriHook", true);
        String uri = intent.toUri(Intent.URI_INTENT_SCHEME);
        uri = uri.replace("B.skipToUriHook=true;", "");
        intent.removeExtra("skipToUriHook");
        Log.d(TAG, String.format("%s Intent.toUriCeSi %s", FunctionCall, uri));
        Map<String, Object> MapData = IntentData.convertIntentToMap(intent);
        MapData.put("FunctionCall", FunctionCall);
        MapData.put("uri", uri);
        if (from != null) {
            MapData.put("from", from);
        }
        sendBroadcastSafely(MapData, "Intent", getStackTraceString());
    }

    private Context getAppContext() {
        // return (Context) XposedHelpers.callStaticMethod(ActivityThread.class, "currentApplication");
        try {
            Object activityThread = XposedHelpers.callStaticMethod(ActivityThread.class, "currentActivityThread");
            return (Context) XposedHelpers.callMethod(activityThread, "getApplication");
        } catch (Exception e) {
            Log.e(TAG, "getAppContext Failed to get context", e);
            return null;
        }
    }

    private final XC_MethodHook HookStartActivityForResult = new XC_MethodHook() {
        @SuppressLint("LongLogTag")
        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
            super.beforeHookedMethod(methodHookParam);
            if (!getIsHook()) return;
            if (!(methodHookParam.thisObject instanceof Context)) return;

            Intent intent = (Intent) methodHookParam.args[0];
            int requestCode = (Integer) methodHookParam.args[1];
            Bundle options = null;

            if (methodHookParam.args.length > 2 && methodHookParam.args[2] instanceof Bundle) {
                options = (Bundle) methodHookParam.args[2];
            }

            // Map<String, Object> MapData = IntentData.convertIntentToMap(intent, requestCode, options);
            filterIntent(intent, "Activity.startActivityForResult", methodHookParam.thisObject.getClass().getName());

        }
    };

    private final XC_MethodHook HookStartActivity = new XC_MethodHook() {
        @SuppressLint("LongLogTag")
        protected void beforeHookedMethod(XC_MethodHook.MethodHookParam methodHookParam) throws Throwable {
            super.beforeHookedMethod(methodHookParam);
            if (!getIsHook()) return;
            Log.d(TAG, "HookStartActivityRun");
            if (!(methodHookParam.thisObject instanceof Context)) return;

            Intent intent = (Intent) methodHookParam.args[0];
            Bundle options = null;
            if (methodHookParam.args.length > 1) {
                options = (Bundle) methodHookParam.args[1];
            }

            Map<String, Object> MapData;
            if (options != null) {
                MapData = IntentData.convertIntentToMap(intent, options);
            } else {
                MapData = IntentData.convertIntentToMap(intent);
            }

            filterIntent(intent, "Activity.startActivity", methodHookParam.thisObject.getClass().getName());
        }
    };

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {

        if (loadPackageParam.packageName.equals("com.fourtwo.hookintent")) {
            XposedHelpers.findAndHookMethod("com.fourtwo.hookintent.ui.home.HomeFragment", // 你的 Activity 的完整类名
                    loadPackageParam.classLoader, "isXposed", // 需要 hook 的方法名
                    XC_MethodReplacement.returnConstant(true) // 将返回值替换为 true
            );
            return;
        }

        XposedHelpers.findAndHookMethod(Application.class, "onCreate", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                Application application = (Application) param.thisObject;

                BroadcastReceiver receiver = new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        String DataType = intent.getStringExtra("type");
                        if (Objects.equals(DataType, "set_isHook")) {
                            isHook = intent.getBooleanExtra("data", false);
                        }
                    }
                };
                // 注册接收器
                IntentFilter filter = new IntentFilter("SET_JUMP_REPLAY_HOOK");
                application.registerReceiver(receiver, filter);
                Log.d(TAG, "注册广播接收");
            }
        });

//        XposedHelpers.findAndHookMethod(Application.class, "onDestroy", new XC_MethodHook() {
//            @Override
//            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
//                super.afterHookedMethod(param);
//                Log.d(TAB, "准备销毁广播接收");
//                Application application = (Application) param.thisObject;
//                application.unregisterReceiver(receiver);
//                Log.d(TAB, "完成销毁广播接收");
//            }
//        });

        Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", loadPackageParam.classLoader);

        /* Activity.startActivityForResult */
        XposedHelpers.findAndHookMethod(activityClass, "startActivityForResult", Intent.class, Integer.TYPE, Bundle.class, HookStartActivityForResult);
        XposedHelpers.findAndHookMethod(activityClass, "startActivityForResult", Intent.class, Integer.TYPE, HookStartActivityForResult);

        /* Activity.startActivity */
        XposedHelpers.findAndHookMethod(activityClass, "startActivity", Intent.class, HookStartActivity);
        XposedHelpers.findAndHookMethod(activityClass, "startActivity", Intent.class, Bundle.class, HookStartActivity);

        /* Activity.onResume */
        XposedHelpers.findAndHookMethod(activityClass, "onResume", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.beforeHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                Intent intent = (Intent) XposedHelpers.callMethod(methodHookParam.thisObject, "getIntent");
                filterIntent(intent, "Activity.onResume", methodHookParam.thisObject.getClass().getName());
            }
        });

        /* Uri.parse */
        XposedHelpers.findAndHookMethod("android.net.Uri", loadPackageParam.classLoader, "parse", String.class, new XC_MethodHook() {

            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.beforeHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                String scheme = (String) methodHookParam.args[0];
                filterScheme(scheme, "Uri.parse");
                Log.d(TAG, "parseUri scheme" + scheme);
            }


        });

        /* Intent.parseUri */
        XposedHelpers.findAndHookMethod("android.content.Intent", loadPackageParam.classLoader, "parseUri", String.class, int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.afterHookedMethod(methodHookParam);
                if (!getIsHook()) return;

                String scheme = (String) methodHookParam.args[0];
                Log.d(TAG, "parseUri scheme" + scheme);
                filterScheme(scheme, "Intent.parseUri");

                Intent intent = (Intent) methodHookParam.getResult();
                filterIntent(intent, "Intent.parseUri", null);
            }

        });

        /* Intent.parseUri */
        XposedHelpers.findAndHookMethod("android.content.Intent", loadPackageParam.classLoader, "toUri", int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.afterHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                Intent intent = (Intent) methodHookParam.thisObject;
                if (intent.hasExtra("skipToUriHook")) return; // 检查自定义标志
                String scheme = (String) methodHookParam.getResult();
                Log.d(TAG, "Intent.toUriCeSi " + scheme);
                filterScheme(scheme, "Intent.toUri");
            }

        });

        /* PendingIntent.getActivity */
        XposedHelpers.findAndHookMethod("android.app.PendingIntent", loadPackageParam.classLoader, "getActivity", Context.class, int.class, Intent.class, int.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.beforeHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                Intent intent = (Intent) methodHookParam.args[2];
                Context context = (Context) methodHookParam.args[1];
                filterIntent(intent, "PendingIntent.getActivity", context.getClass().getName());
            }
        });


    }

}


