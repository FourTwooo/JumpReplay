package com.fourtwo.hookintent;

import android.app.ActivityThread;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.os.Bundle;

import com.fourtwo.hookintent.base.Extract;
import com.fourtwo.hookintent.base.IntentData;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.base.UriData;
import com.fourtwo.hookintent.service.MessengerClient;
import com.fourtwo.hookintent.service.MessengerService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;


public class IntentCapture implements IXposedHookLoadPackage {

    private MessengerClient client = null;
    private Context applicationContext = null;

    private String packageName;
    private Boolean isHook = false;

    private Boolean isService = false;
    private final String myAppPackage = "com.fourtwo.hookintent";
    private final String myAppClass = "com.fourtwo.hookintent.IntentIntercept";

    private Boolean getIsHook() {
//        XposedBridge.log("isHook: " + isHook);
        if (client != null && !isService) {
            client.sendMessageAsync(MessengerService.MSG_IS_HOOK, null, true, new MessengerClient.ResultCallback() {
                @Override
                public void onResult(Bundle result) {
                    int resultCode = result.getInt("resultCode");
                    isHook = (resultCode == 1);
                    isService = true;
//                    XposedBridge.log(isHook + " Client Async Result: resultCode=" + resultCode + ", resultData=" + resultData);
                }

                @Override
                public void onError(Exception e) {
                    XposedBridge.log("Client Error during async call" + e);
                }
            });
            return isHook;
        }
        return isHook;
    }

    private String getStackTraceString() {
//        return "";
        Throwable throwable = new Throwable();
        StackTraceElement[] stackTrace = throwable.getStackTrace();
        StringBuilder stackTraceString = new StringBuilder();
        for (StackTraceElement element : stackTrace) {
            stackTraceString.append(element.toString()).append("\n");
        }
        return stackTraceString.toString();
    }

    private void sendBroadcastSafely(Map<String, Object> mapData, String base, String stackTraceString) {
        Context appContext = getAppContext();
        if (appContext == null) return;
        if (!mapData.containsKey("from")) {
            mapData.put("from", appContext.getClass().getName());
        }

        // 接收到 MSG_SEND_DATA 请求时
        Bundle bundle = Extract.convertMapToBundle(mapData);
        bundle.putString("Base", base);
        bundle.putString("packageName", packageName);
        bundle.putString("stack_trace", stackTraceString);

        addMessage(bundle);
//        Handler mainHandler = new Handler(Looper.getMainLooper());
//        mainHandler.post(() -> {
//            try {
//
//                List<Bundle> batch = new ArrayList<>();
//
//                // 从队列中取出所有数据
//                batch.add(bundle);
//
//                String batchDataJson = JsonHandler.toJson(batch);
//                Bundle batchBundle = new Bundle();
//                batchBundle.putString("batch_data_binder", batchDataJson);
//                // 使用 MessengerClient 发送数据
//                client.sendMessageAsync(MessengerService.MSG_SEND_DATA, batchBundle, false, null);
//
//            } catch (Exception e) {
//                XposedBridge.log("HandlerException Error sending data: " + e);
//            }
//        });
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
        XposedBridge.log("filterIntent:" + MapData);
        sendBroadcastSafely(MapData, "Intent", getStackTraceString());
    }

    private Context getAppContext() {
        try {
            if (applicationContext == null) {
                Object activityThread = XposedHelpers.callStaticMethod(ActivityThread.class, "currentActivityThread");
                return (Context) XposedHelpers.callMethod(activityThread, "getApplication");
            } else {
                return applicationContext;
            }
        } catch (Exception e) {
            XposedBridge.log("getAppContext Failed to get context" + e);
            return null;
        }
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
                        if (intent.getComponent() != null || intent.getScheme() == null) {
                            return;
                        }

                        if (myAppPackage.equals(intent.getPackage())) {
                            // 如果是特定的 Intent，不再处理，直接返回
                            return;
                        }
                        XposedBridge.log("queryIntentActivitiesInternal Scheme: " + intent.getScheme());

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

                        if (hasHookIntent || originalResult.isEmpty()) {
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
                        @SuppressWarnings("unchecked")
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
            return;
        }

        packageName = loadPackageParam.packageName;

        XposedHelpers.findAndHookMethod(
                Application.class,
                "attach",
                Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        applicationContext = (Context) param.args[0];
                        ClassLoader classLoader = applicationContext.getClassLoader();
                        if (client == null) {
                            client = new MessengerClient(applicationContext);
                            client.registerPassiveCallback(data -> {
                                isHook = data.getBoolean("isHook");
                                XposedBridge.log("Client Received hook state: " + isHook);
                            });
                        }
                        // Hook methods
                        hookMethods(classLoader);

                        // 启动定时分流检测
                        startBatchSender();
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

    }

    /**
     *  调试测试代码
     */
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

    /**
     * 定义线程安全的队列，存储待发送的消息
     */
    private final BlockingQueue<Bundle> messageQueue = new LinkedBlockingQueue<>();

    // 将数据添加到队列中
    public void addMessage(Bundle bundle) {
        try {
            // 将数据放入队列，线程安全
            messageQueue.put(bundle);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            XposedBridge.log("Error adding message to queue: " + e);
        }
    }

    // 定时任务，批量发送消息
    private void startBatchSender() {
        ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

        // 每隔固定时间执行一次
        scheduler.scheduleAtFixedRate(() -> {
            try {
                // 批量取出队列中的数据
                List<Bundle> batch = new ArrayList<>();
                messageQueue.drainTo(batch); // 将队列中的所有数据取出

                // 如果有数据，则发送
                if (!batch.isEmpty()) {
                    String batchDataJson = JsonHandler.toJson(batch);
                    Bundle batchBundle = new Bundle();
                    batchBundle.putString("batch_data_binder", batchDataJson);

                    // 使用 MessengerClient 发送数据
                    client.sendMessageAsync(MessengerService.MSG_SEND_DATA, batchBundle, false, null);
                }
            } catch (Exception e) {
                XposedBridge.log("Error sending batch data: " + e);
            }
        }, 0, 1000, TimeUnit.MILLISECONDS); // 初始延迟为0，每隔500ms执行一次
    }



}
