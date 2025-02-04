package com.fourtwo.hookintent;

import android.app.ActivityThread;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.fourtwo.hookintent.analysis.IntentData;
import com.fourtwo.hookintent.analysis.UriData;
import com.fourtwo.hookintent.analysis.extract;

import java.util.Map;
import java.util.Objects;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


/**
 * 为什么不使用XSharedPrefsUtil和网络请求做进程通信？XSharedPrefsUtil在安卓高版本无法使用.除非使用LSPosed的New XSharedPrefsUtil
 * 但很显然 我不可能让用户指定去使用LSPosed才能使用这个工具.更别提LSPosed已经停止维护了
 * ...
 * 网络请求我认为他和intent广播一样具有延迟性. 启动部分APP会直接触发大量intent和scheme. 等通信回来早就已经处理完了.所以过滤的拦截是不够迅速的
 * 如果阻塞等待会出问题的.如果你勾选了系统.并且还改动了我的代码为阻塞等待.还把hook状态改成true.出了任何bug.重启手机会卡logo变砖
 * 他和intent广播都具有这个劣势. 或许后面我会考虑使用socket的方式.这样能方便使用中间人代理的方式去做自定义个性化
 * ...
 * 所以综上考虑 我不会让用户只能去使用LSPosed使用这款工具. 为了兼容我采用intent广播为进程通信
 * 如果你有更好的方案愿意提供,我可以自行替换掉这intent广播通信的代码
 * 如果你不是通过github下载使用如何联系我？ <a href="https://github.com/FourTwooo/JumpReplay/"</a>
 */


public class HookIntentStart implements IXposedHookLoadPackage {
    private Boolean isHook = null;

    private void sendTaskIntent(String data) {
        Context appContext = getAppContext();
        if (appContext != null) {
            Handler mainHandler = new Handler(Looper.getMainLooper());
            mainHandler.post(() -> {
                try {
                    Intent intentMsg = new Intent("GET_JUMP_REPLAY_HOOK");
                    intentMsg.putExtra(Constants.DATA, data);
                    intentMsg.putExtra(Constants.TYPE, "msg");
                    appContext.sendBroadcast(intentMsg);
                } catch (Exception e) {
                    XposedBridge.log("HandlerException Error sending intent" + e);
                }
            });
        }
    }

    private Boolean getIsHook() {
        if (isHook == null) {
            sendTaskIntent(Constants.GET_IS_HOOK);
            return false;
        }
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
                intentMsg.putExtra(Constants.TYPE, "data");
                intentMsg.putExtra("stack_trace", StackTraceString);
                intentMsg.putExtra("uri", finalUri);
                appContext.sendBroadcast(intentMsg);
            } catch (Exception e) {
                XposedBridge.log("HandlerException Error sending intent" + e);
            }
        });
    }

    private void filterScheme(String scheme_raw_url, String FunctionCall) {
        if (scheme_raw_url != null) {
            Map<String, Object> MapData = UriData.GetMap(scheme_raw_url);
            MapData.put("FunctionCall", FunctionCall);
            sendBroadcastSafely(MapData, "Scheme", getStackTraceString());
        }
    }

    private void filterIntent(Intent intent, String FunctionCall, String from) {
        intent.putExtra("skipToUriHook", true);
        String uri = intent.toUri(Intent.URI_INTENT_SCHEME);
        uri = uri.replace("B.skipToUriHook=true;", "");
        intent.removeExtra("skipToUriHook");
        Map<String, Object> MapData = IntentData.convertIntentToMap(intent);
        MapData.put("FunctionCall", FunctionCall);
        MapData.put("uri", uri);
        if (from != null) {
            MapData.put("from", from);
        }

        Bundle extras = intent.getExtras();
        XposedBridge.log("intent.getDataString() " + extras);
        sendBroadcastSafely(MapData, "Intent", getStackTraceString());
    }

    private Context getAppContext() {
        try {
            Object activityThread = XposedHelpers.callStaticMethod(ActivityThread.class, "currentActivityThread");
            return (Context) XposedHelpers.callMethod(activityThread, "getApplication");
        } catch (Exception e) {
            XposedBridge.log("getAppContext Failed to get context" + e);
            return null;
        }
    }

    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam.packageName.equals("com.fourtwo.hookintent")) {
            XposedHelpers.findAndHookMethod("com.fourtwo.hookintent.ui.home.HomeFragment",
                    loadPackageParam.classLoader, "isXposed",
                    XC_MethodReplacement.returnConstant(true)
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
                        String DataType = intent.getStringExtra(Constants.TYPE);
                        if (Objects.equals(DataType, Constants.SET_IS_HOOK)) {
                            isHook = intent.getBooleanExtra(Constants.DATA, false);
                            //XposedBridge.log("Constants.SET_IS_HOOK " + isHook);
                        }
                    }
                };
                // 注册接收器
                IntentFilter filter = new IntentFilter("SET_JUMP_REPLAY_HOOK");
                application.registerReceiver(receiver, filter);
                XposedBridge.log(application.getClass().getName() + "注册广播接收");
            }
        });

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                Context context = (Context) param.args[0];
                ClassLoader classLoader = context.getClassLoader();

                // Hook methods
                hookMethods(classLoader);
                // 系统应用
//                if (loadPackageParam.appInfo == null || (loadPackageParam.appInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 1) {
//            try {
//                Class<?> packageManagerServiceClass = XposedHelpers.findClass("com.android.server.pm.PackageManagerService", loadPackageParam.classLoader);
//                /* PackageManagerService.queryIntentActivities */
//                XposedBridge.hookAllMethods(packageManagerServiceClass, "queryIntentActivities",
//                        new XC_MethodHook() {
//                            @Override
//                            protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
//                                super.beforeHookedMethod(methodHookParam);
//                                if (!getIsHook()) return;
//                                if (methodHookParam.args.length > 0 && methodHookParam.args[0] instanceof Intent) {
//                                    Intent intent = (Intent) methodHookParam.args[0];
////                                    XposedBridge.log("queryIntentActivities: " + intent.getScheme() + " " + intent.getDataString());
//                                    filterIntent(intent, "PackageManagerService.queryIntentActivities", methodHookParam.thisObject.getClass().getName());
//                                }
//
//                            }
//
//                        }
//                );

//                /* PackageManagerService.queryIntentServices */
//                XposedBridge.hookAllMethods(packageManagerServiceClass, "queryIntentServices", new XC_MethodHook() {
//                    @Override
//                    protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
//                        super.beforeHookedMethod(methodHookParam);
//                        if (!getIsHook()) return;
//                        if (methodHookParam.args.length > 0 && methodHookParam.args[0] instanceof Intent) {
//                            Intent intent = (Intent) methodHookParam.args[0];
//                            filterIntent(intent, "PackageManagerService.queryIntentServices", methodHookParam.thisObject.getClass().getName());
//                        }
//                    }
//                });
//            } catch (XposedHelpers.ClassNotFoundError e) {
//                XposedBridge.log("Class not found: " + e.getMessage());
//            }
//                }
            }
        });
    }

    private void hookMethods(ClassLoader classLoader) {
        Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", classLoader);

        XposedBridge.hookAllMethods(activityClass, "startActivity", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.beforeHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                if (!(methodHookParam.thisObject instanceof Context)) return;
                if (methodHookParam.args.length > 0 && methodHookParam.args[0] instanceof Intent) {
                    Intent intent = (Intent) methodHookParam.args[0];
                    filterIntent(intent, "Activity.startActivity", methodHookParam.thisObject.getClass().getName());
                }
            }
        });

        XposedBridge.hookAllMethods(activityClass, "startActivityForResult", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.beforeHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                if (!(methodHookParam.thisObject instanceof Context)) return;
                if (methodHookParam.args.length > 0 && methodHookParam.args[0] instanceof Intent) {
                    Intent intent = (Intent) methodHookParam.args[0];
                    filterIntent(intent, "Activity.startActivityForResult", methodHookParam.thisObject.getClass().getName());
                }
            }
        });


        XposedHelpers.findAndHookMethod(activityClass, "onResume", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.beforeHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                Intent intent = (Intent) XposedHelpers.callMethod(methodHookParam.thisObject, "getIntent");
                filterIntent(intent, "Activity.onResume", methodHookParam.thisObject.getClass().getName());
            }
        });


        XposedBridge.hookAllMethods(XposedHelpers.findClass("android.content.ContextWrapper", classLoader), "startActivity", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.beforeHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                if (methodHookParam.args.length > 0 && methodHookParam.args[0] instanceof Intent) {
                    Intent intent = (Intent) methodHookParam.args[0];
                    filterIntent(intent, "ContextWrapper.startActivity", null);
                }
            }
        });

        XposedHelpers.findAndHookMethod("android.net.Uri", classLoader, "parse", String.class, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.beforeHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                String scheme = (String) methodHookParam.args[0];
                filterScheme(scheme, "Uri.parse");
            }
        });

        XposedHelpers.findAndHookMethod("android.content.Intent", classLoader, "parseUri", String.class, int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.afterHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                String scheme = (String) methodHookParam.args[0];
                filterScheme(scheme, "Intent.parseUri");
            }
        });

        XposedHelpers.findAndHookMethod("android.content.Intent", classLoader, "toUri", int.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.afterHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                Intent intent = (Intent) methodHookParam.thisObject;
                if (intent.hasExtra("skipToUriHook")) return;
                String scheme = (String) methodHookParam.getResult();
                filterScheme(scheme, "Intent.toUri");
            }
        });

//        /* PendingIntent.getActivity */
//        XposedBridge.hookAllMethods(XposedHelpers.findClass("android.app.PendingIntent", classLoader), "getActivity", new XC_MethodHook() {
//            @Override
//            protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
//                super.beforeHookedMethod(methodHookParam);
//                if (!getIsHook()) return;
//                Context context = (Context) methodHookParam.args[0];
//                Intent intent = (Intent) methodHookParam.args[2];
//                XposedBridge.log("PendingIntent.getActivity" + intent);
//                filterIntent(intent, "PendingIntent.getActivity", context.getClass().getName());
//            }
//        });
//
//
////         Intent()
//        XposedBridge.hookAllConstructors(XposedHelpers.findClass("android.content.Intent", classLoader),
//                new XC_MethodHook() {
//                    @Override
//                    protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
//                        super.afterHookedMethod(methodHookParam);
//                        if (!getIsHook()) return;
//                        // 使用 thisObject 获取当前构造的 Intent 实例
//                        Intent intent = (Intent) methodHookParam.thisObject;
//                        if ("GET_JUMP_REPLAY_HOOK".equals(intent.getAction())) {
//                            return;
//                        }
//                        XposedBridge.log("new intent: " + intent);
//                        filterIntent(intent, "Intent()", null);
//                    }
//                }
//        );

    }
}
