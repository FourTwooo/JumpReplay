package com.fourtwo.hookintent;

import android.app.ActivityThread;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.fourtwo.hookintent.analysis.Extract;
import com.fourtwo.hookintent.analysis.IntentData;
import com.fourtwo.hookintent.analysis.UriData;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


public class HookIntentStart implements IXposedHookLoadPackage {
    private Boolean isHook = null;
    private final String myAppPackage = "com.fourtwo.hookintent";
    private final String myAppClass = "com.fourtwo.hookintent.SchemeHandlerActivity";

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
                Bundle bundle = Extract.convertMapToBundle(mapData);
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

    private void HookClass(XC_LoadPackage.LoadPackageParam loadPackageParam, String classNameToHook) {
        try {
            // 获取目标类的 Class 对象
            Class<?> clazz = XposedHelpers.findClass(classNameToHook, loadPackageParam.classLoader);

            // 获取类中的所有方法
            Method[] methods = clazz.getDeclaredMethods();

            // 对每个方法进行 hook
            for (Method method : methods) {
                XposedBridge.hookMethod(method, new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                        // 构造参数输出
                        StringBuilder paramDetails = new StringBuilder();

                        if (methodHookParam.args != null && methodHookParam.args.length > 0) {
                            for (int i = 0; i < methodHookParam.args.length; i++) {
                                Object arg = methodHookParam.args[i];
                                String className = (arg != null) ? arg.getClass().getName() : "null";
                                String toStringValue = (arg != null) ? arg.toString() : "null";

                                // 拼接参数信息
                                paramDetails
                                        .append(className)
                                        .append(": ")
                                        .append(toStringValue);

                                // 如果不是最后一个参数，加上分隔符
                                if (i < methodHookParam.args.length - 1) {
                                    paramDetails.append("; ");
                                }
                            }
                        } else {
                            paramDetails.append("无参数");
                        }

                        // 打印日志
                        XposedBridge.log("Method called: " + method.getName() + " | 参数详情: " + paramDetails);

                    }
                });
            }
        } catch (Throwable t) {
            XposedBridge.log("Failed to hook class: " + classNameToHook);
            XposedBridge.log(t);
        }
    }

    private void logParams(XC_MethodHook.MethodHookParam methodHookParam) {
        // 构造参数输出
        StringBuilder paramDetails = new StringBuilder();

        if (methodHookParam.args != null && methodHookParam.args.length > 0) {
            for (int i = 0; i < methodHookParam.args.length; i++) {
                Object arg = methodHookParam.args[i];
                String className = (arg != null) ? arg.getClass().getName() : "null";
                String toStringValue = (arg != null) ? arg.toString() : "null";

                // 拼接参数信息
                paramDetails
                        .append(className)
                        .append(": ")
                        .append(toStringValue);

                // 如果不是最后一个参数，加上分隔符
                if (i < methodHookParam.args.length - 1) {
                    paramDetails.append("; ");
                }
            }
        } else {
            paramDetails.append("无参数");
        }

        // 打印日志
        XposedBridge.log("参数详情: " + paramDetails);

    }


    public void handleLoadSystem(XC_LoadPackage.LoadPackageParam loadPackageParam) {

        String ClassName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? "com.android.server.pm.ComputerEngine"
                : "com.android.server.pm.PackageManagerService";
        Class<?> hookClass;
        try {
            hookClass = XposedHelpers.findClass(ClassName, loadPackageParam.classLoader);
        } catch (XposedHelpers.ClassNotFoundError error) {
            return;
        }


        XposedBridge.hookAllMethods(
                hookClass,
                "queryIntentActivitiesInternal",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {

                        Intent intent = (Intent) param.args[0];
                        XposedBridge.log("queryIntentActivitiesInternal Scheme: " + intent.getScheme() + " " + intent.toUri(Intent.URI_INTENT_SCHEME));
                        if (intent.getComponent() != null || intent.getScheme() == null) {
                            return;
                        }
                        if (myAppPackage.equals(intent.getPackage())) {
                            // 如果是特定的 Intent，不再处理，直接返回
                            return;
                        }

                        // 获取原始返回值
                        @SuppressWarnings("unchecked")
                        List<ResolveInfo> originalResult = (List<ResolveInfo>) param.getResult();
                        XposedBridge.log("originalResult: " + originalResult);
                        boolean hasHookIntent = false; // 用于标记是否存在目标 ResolveInfo

                        // 遍历 originalResult
                        for (ResolveInfo resolveInfo : originalResult) {
                            if (resolveInfo.activityInfo != null &&
                                    myAppPackage.equals(resolveInfo.activityInfo.packageName)) {
                                hasHookIntent = true; // 找到目标 ResolveInfo
                                break; // 提前结束循环
                            }
                        }

                        if (hasHookIntent) {
                            return;
                        }
                        // 构造特殊 Intent，用于主动调用
                        Intent specialIntent = new Intent(Intent.ACTION_VIEW); // 自定义的特殊 Intent
                        specialIntent.setPackage(myAppPackage); // 只匹配自己 APP 的包名
                        specialIntent.setComponent(new ComponentName(myAppPackage, myAppClass)); // 设置组件
                        specialIntent.addCategory(Intent.CATEGORY_DEFAULT); // 添加默认分类
                        specialIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK); // 设置 Intent 标志

                        // 动态获取参数
                        Object[] dynamicArgs = param.args.clone(); // 克隆原始参数
                        dynamicArgs[0] = specialIntent; // 替换 Intent 为特殊 Intent
                        dynamicArgs[1] = null;                      // 清空 resolvedType
                        dynamicArgs[2] = PackageManager.MATCH_DEFAULT_ONLY; // 设置 flags，仅匹配默认组件

                        // 调用 queryIntentActivitiesInternal
                        List<ResolveInfo> myAppResolveInfos = (List<ResolveInfo>) XposedHelpers.callMethod(
                                param.thisObject, // 当前 Hook 的类实例
                                "queryIntentActivitiesInternal",
                                dynamicArgs // 动态参数数组
                        );

                        // 如果成功获取到自己的 ResolveInfo，则合并到原始结果中
                        if (myAppResolveInfos != null && !myAppResolveInfos.isEmpty()) {
                            ResolveInfo myAppResolveInfo = myAppResolveInfos.get(0);
                            ResolveInfo clAppResolveInfo = originalResult.get(0);

                            // 修改 match 值
                            Field matchField = ResolveInfo.class.getDeclaredField("match");
                            matchField.setAccessible(true);
                            matchField.set(myAppResolveInfo, clAppResolveInfo.match); // 与目标应用一致

                            // myAppResolveInfo.activityInfo.exported = true;
                            // myAppResolveInfo.activityInfo.permission = null;
                            // myAppResolveInfo.activityInfo.launchMode = ActivityInfo.LAUNCH_SINGLE_TASK;
                            // myAppResolveInfo.priority = 1000; // 优先级
                            myAppResolveInfo.isDefault = true; // 标记为默认候选项

                            IntentFilter intentFilter = new IntentFilter();
                            intentFilter.addAction(Intent.ACTION_VIEW); // 添加 ACTION_VIEW
                            intentFilter.addCategory(Intent.CATEGORY_DEFAULT); // 添加 CATEGORY_DEFAULT
                            intentFilter.addCategory(Intent.CATEGORY_BROWSABLE); // 添加 CATEGORY_BROWSABLE
                            intentFilter.addDataScheme(intent.getScheme()); // 添加 scheme，例如 "orpheus"

                            // 设置到 ResolveInfo
                            myAppResolveInfo.filter = intentFilter;

                            originalResult.add(myAppResolveInfo);
                            // originalResult.set(0, myAppResolveInfo);
                            XposedBridge.log("originalResult.addAll: " + originalResult);
                        }

                        // 将修改后的结果设置回返回值
                        param.setResult(originalResult);
                    }
                }
        );

    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam.packageName.equals(myAppPackage)) {
            XposedHelpers.findAndHookMethod("com.fourtwo.hookintent.ui.home.HomeFragment",
                    loadPackageParam.classLoader, "isXposed",
                    XC_MethodReplacement.returnConstant(true)
            );
            return;
        }

        if (loadPackageParam.appInfo == null || (loadPackageParam.appInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 1) {
            handleLoadSystem(loadPackageParam);
        }

        XposedHelpers.findAndHookMethod(
                Application.class,
                "onCreate",
                new XC_MethodHook() {
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
                }
        );

        XposedHelpers.findAndHookMethod(
                Application.class,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        Context context = (Context) param.args[0];
                        ClassLoader classLoader = context.getClassLoader();

                        // Hook methods
                        hookMethods(classLoader);
                    }
                }
        );
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
