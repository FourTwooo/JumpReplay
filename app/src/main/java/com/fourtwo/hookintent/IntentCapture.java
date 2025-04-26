package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.ActivityThread;
import android.app.Application;
import android.content.ComponentName;
import android.content.ContentValues;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.service.MessengerClient;
import com.fourtwo.hookintent.service.MessengerService;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import com.fourtwo.hookintent.xposed.RecordXposedBridge;
import com.fourtwo.hookintent.xposed.ui.FloatWindow;
import com.fourtwo.hookintent.xposed.ui.FloatWindowView;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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

    private Boolean isHostApp = false;
    public static final String myAppPackage = "com.fourtwo.hookintent";
    private final String myAppClass = "com.fourtwo.hookintent.IntentIntercept";

    public static final String AUTHORITY = "com.fourtwo.hookintent.configprovider";

    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");

    public static final Uri SCHEME_URI = Uri.parse("content://" + AUTHORITY + "/scheme");

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

    @SuppressLint("SimpleDateFormat")
    private void sendBroadcastSafely(Bundle bundle) {
        Context appContext = getAppContext();
        if (appContext == null) return;
        if (!bundle.containsKey("from")) {
            bundle.putString("from", appContext.getClass().getName());
        }

        // 接收到 MSG_SEND_DATA 请求时
        bundle.putString("time", new SimpleDateFormat("yyyy-MM-dd hh:mm:ss a").format(Calendar.getInstance().getTime()));
        bundle.putString("packageName", packageName);
        bundle.putString("processName", DataConverter.getCurrentProcessName(appContext));
        bundle.putString("stack_trace", getStackTraceString());

        addMessage(bundle);
    }

    private void filterScheme(String scheme_raw_url, String FunctionCall) {
        if (scheme_raw_url == null) {
            return;
        }
        Bundle bundle = new Bundle();
        bundle.putString("FunctionCall", FunctionCall);
        bundle.putString("category", "Scheme");
        bundle.putString("scheme_raw_url", scheme_raw_url);
        sendBroadcastSafely(bundle);
    }

    private void filterIntent(Intent intent, String FunctionCall, String from) {
        String uri = intent.toUri(Intent.URI_INTENT_SCHEME);
        Bundle bundle = DataConverter.convertIntentToBundle(intent);
        bundle.putString("FunctionCall", FunctionCall);
        bundle.putString("category", "Intent");
        bundle.putString("uri", uri);
        bundle.putString("from", from);
        sendBroadcastSafely(bundle);
    }

    private void filterCustomize(Bundle bundle) {
        sendBroadcastSafely(bundle);
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

    public void hookSystemMethods(Context applicationContext) {
        ClassLoader classLoader = applicationContext.getClassLoader();

        String ClassName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? "com.android.server.pm.ComputerEngine" : "com.android.server.pm.PackageManagerService";
        Class<?> hookClass;
        try {
            hookClass = XposedHelpers.findClass(ClassName, classLoader);
            XposedBridge.log("android.server加载成功! => " + ClassName);
            HookClass(classLoader, ClassName);
            XposedBridge.hookAllMethods(hookClass, "queryIntentActivitiesInternal", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Intent intent = (Intent) param.args[0];

                    XposedBridge.log("queryIntentActivitiesInternal Scheme: " + intent.getDataString());
                    // 如果是自己的 Intent，不再处理，直接返回
                    if (myAppPackage.equals(intent.getPackage())) {
                        return;
                    }

                    if (intent.getComponent() != null || intent.getScheme() == null) {
                        return;
                    }

                    // 获取原始返回值
                    @SuppressWarnings("unchecked") List<ResolveInfo> originalResult = (List<ResolveInfo>) param.getResult();
                    boolean hasHookIntent = false; // 用于标记是否存在目标 ResolveInfo

                    // 遍历 originalResult
                    for (ResolveInfo resolveInfo : originalResult) {
                        if (resolveInfo.activityInfo != null && myAppPackage.equals(resolveInfo.activityInfo.packageName)) {
                            hasHookIntent = true; // 找到目标 ResolveInfo
                            break; // 提前结束循环
                        }
                    }

                    if (hasHookIntent || originalResult.isEmpty()) {
                        return;
                    }

                    String disabledScheme = null;
                    long token = Binder.clearCallingIdentity();
                    try {
                        Map<String, String> HooksConfig = getConfig(SCHEME_URI);
                        disabledScheme = HooksConfig.get("disabledScheme");
                    } catch (Exception ignored) {
                    } finally {
                        Binder.restoreCallingIdentity(token);
                    }


                    if (disabledScheme != null) {
                        List<Map<String, Object>> disabledSchemeList = JsonHandler.deserializeHookedRecords(disabledScheme);

                        for (Map<String, Object> disabledSchemeObj : disabledSchemeList) {
                            Boolean re = (Boolean) disabledSchemeObj.get("re");
                            Boolean open = (Boolean) disabledSchemeObj.get("open");
                            String text = (String) disabledSchemeObj.get("text");

                            // 如果不开启，则跳过
                            if (!Boolean.TRUE.equals(open)) {
                                continue;
                            }

                            // 正则匹配逻辑
                            if (Boolean.TRUE.equals(re)) {
                                if (text == null) {
                                    continue;
                                }

                                Pattern pattern;
                                try {
                                    pattern = Pattern.compile(text);
                                } catch (PatternSyntaxException e) {
                                    System.out.println("正则表达式语法错误：" + e.getMessage());
                                    continue;
                                }

                                Matcher matcher = pattern.matcher(intent.getDataString());
                                if (matcher.find()) {
                                    param.setResult(new ArrayList<>());
                                    return;
                                }
                            } else {
                                // 非正则匹配逻辑
                                if (Objects.equals(text, intent.getScheme())) {
                                    param.setResult(new ArrayList<>());
                                    return;
                                }
                            }
                        }
                    }

//                 构造特殊 Intent，用于主动调用
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
                    @SuppressWarnings("unchecked") List<ResolveInfo> myAppResolveInfos = (List<ResolveInfo>) XposedHelpers.callMethod(param.thisObject, // 当前 Hook 的类实例
                            "queryIntentActivitiesInternal", dynamicArgs // 动态参数数组
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
                        intentFilter.addDataScheme(intent.getScheme()); // 添加 scheme

                        // 设置到 ResolveInfo
                        myAppResolveInfo.filter = intentFilter;

                        originalResult.add(myAppResolveInfo);
                        // originalResult.set(0, myAppResolveInfo);
                        XposedBridge.log("originalResult.addAll: " + originalResult);
                    }

                    // 将修改后的结果设置回返回值
                    param.setResult(originalResult);
                }
            });
        } catch (XposedHelpers.ClassNotFoundError ignored) {
        }


        Class<?> PackageParserClass = XposedHelpers.findClass("android.content.pm.PackageParser", classLoader);
        XposedBridge.hookAllMethods(PackageParserClass, "parseUsesPermission",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                        super.beforeHookedMethod(param);
                    }

                    @Override
                    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        super.afterHookedMethod(param);
                        Object pp_Package = param.args[0];
                        XposedBridge.log("pp_Package " + pp_Package);
//                        ArrayList<String> requestedPermissions = (ArrayList<String>) getObjectField(pp_Package, "requestedPermissions");
//                        if (-1 == requestedPermissions.indexOf(permission)) {
//                            requestedPermissions.add(permission);
//                        }
                        //setObjectField(pp_Package,"requestedPermissions",requestedPermissions);
                    }
                }
        );

    }

    public void SetFloatWindowUi(Context applicationContext) {
        try {
            Class<?> declared = XposedHelpers.findClass(Activity.class.getName(), applicationContext.getClassLoader());
            final FloatWindow[] floatWindow = new FloatWindow[1];
            XposedBridge.hookMethod(declared.getDeclaredMethod("onResume"), new XC_MethodHook() {

                @Override
                protected void afterHookedMethod(MethodHookParam methodHookParam) {
                    XposedBridge.log("SetFloatWindowUi onResume");
                    Activity activity = (Activity) methodHookParam.thisObject;
                    floatWindow[0] = new FloatWindow(applicationContext, activity);
                    FloatWindowView floatWindowView = (FloatWindowView) new FloatWindowView(applicationContext);
                    // 设置回调监听器
                    floatWindowView.setOnOpenViewClickListener(onIsHook -> {
                        isHook = onIsHook;
                        // 打印日志
                        XposedBridge.log("isHook 已更新: " + isHook);
                    });

                    floatWindow[0].setFloatWindowView((LinearLayout) floatWindowView.createView());
                    floatWindow[0].initialize();
                }
            });

            XposedBridge.hookMethod(declared.getDeclaredMethod("onPause"), new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam methodHookParam) {
                    XposedBridge.log("SetFloatWindowUi onPause");
                    floatWindow[0].setIsOnPause(true);
                    floatWindow[0].removeView();
                }
            });

        } catch (NoSuchMethodException ignored) {
        } catch (java.lang.AssertionError e) {
            XposedBridge.log("悬浮窗加载失败 =>" + e);
        }
    }


    // 确保整个进程只会Hook一次
    public void Hook(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if (loadPackageParam.appInfo == null || (loadPackageParam.appInfo.flags & (ApplicationInfo.FLAG_SYSTEM | ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) == 1) {
            hookSystemMethods(applicationContext);
            return;
        }


        if (!isHostApp) {
            if (client == null) {
                client = new MessengerClient(applicationContext);
                client.registerPassiveCallback(data -> {
                    isHook = data.getBoolean("isHook");
                    XposedBridge.log("Client Received hook state: " + isHook);
                });
            }
            // 启动定时分流检测
            startBatchSender();
        }

        try {
            hookMethods(applicationContext);
        } catch (java.lang.IllegalArgumentException e) {
            XposedBridge.log("写入失败, 服务端未连接 APP未持有权限 => " + e);
        }

        SetFloatWindowUi(applicationContext);
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        packageName = loadPackageParam.packageName;
        if (loadPackageParam.packageName.equals(myAppPackage)) {
            XposedHelpers.findAndHookMethod("com.fourtwo.hookintent.ui.home.HomeFragment", loadPackageParam.classLoader, "isXposed", XC_MethodReplacement.returnConstant(true));
            isHostApp = true;
        }

        RecordXposedBridge.isHostApp = isHostApp;

        // 偶然发现有些机型(LGE Nexus 5X[Android 8.1.0])居然Application attach不会触发
        XposedHelpers.findAndHookMethod(ContextWrapper.class, "attachBaseContext", Context.class, new XC_MethodHook() {
            @SuppressLint("WrongConstant")
            @Override
            protected void afterHookedMethod(XC_MethodHook.MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (applicationContext == null) {
                    XposedBridge.log(packageName + ": xposed挂载成功 attachBaseContext");
                    applicationContext = (Context) param.args[0];
                    Hook(loadPackageParam);
                }
            }
        });

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (applicationContext == null) {
                    applicationContext = (Context) param.args[0];
                    XposedBridge.log(packageName + ": xposed挂载成功 attach");
                    Hook(loadPackageParam);
                }
            }
        });
    }

    private Map<String, String> getConfig(Uri uri) {
        Cursor cursor = applicationContext.getContentResolver().query(uri, null, null, null, null);

        Map<String, String> mapConfig = new HashMap<>();
        if (cursor != null) {
            while (cursor.moveToNext()) {
                @SuppressLint("Range") String key = cursor.getString(cursor.getColumnIndex("key"));
                @SuppressLint("Range") String value = cursor.getString(cursor.getColumnIndex("value"));
                mapConfig.put(key, value);
                XposedBridge.log("Config => " + key + ": " + value);
            }
            cursor.close();
        }
        return mapConfig;
    }

    private void hookMethods(Context applicationContext) {
        ClassLoader classLoader = applicationContext.getClassLoader();

        Map<String, String> HooksConfig = getConfig(CONFIG_URI);
        String internalHooksConfig = HooksConfig.get("internalHooksConfig");
        if (internalHooksConfig != null) {
            RecordXposedBridge.setHookedRecords(JsonHandler.deserializeHookedRecords(internalHooksConfig));
        }

        Class<?> activityClass = XposedHelpers.findClass("android.app.Activity", classLoader);
        RecordXposedBridge.hookAllMethods("Intent", activityClass, "startActivity", new XC_MethodHook() {
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

        RecordXposedBridge.hookAllMethods("Intent", activityClass, "startActivityForResult", new XC_MethodHook() {
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


        RecordXposedBridge.hookAllMethods("Intent", activityClass, "onResume", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.beforeHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                Intent intent = (Intent) XposedHelpers.callMethod(methodHookParam.thisObject, "getIntent");
                filterIntent(intent, "Activity.onResume", methodHookParam.thisObject.getClass().getName());
            }
        });


        RecordXposedBridge.hookAllMethods("Intent", XposedHelpers.findClass("android.content.ContextWrapper", classLoader), "startActivity", new XC_MethodHook() {
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

        RecordXposedBridge.hookAllMethods("Scheme", XposedHelpers.findClass("android.net.Uri", classLoader), "parse", new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.beforeHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                String scheme = (String) methodHookParam.args[0];
                filterScheme(scheme, "Uri.parse");
            }
        });


        RecordXposedBridge.hookAllMethods("Scheme", XposedHelpers.findClass("android.content.Intent", classLoader), "parseUri", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                super.afterHookedMethod(methodHookParam);
                if (!getIsHook()) return;
                String scheme = (String) methodHookParam.args[0];
                filterScheme(scheme, "Intent.parseUri");
            }
        });

        if (internalHooksConfig == null) {
            String HookedRecords = JsonHandler.serializeHookedRecords(RecordXposedBridge.getHookedRecords());
            XposedBridge.log("hookAllMethods: " + HookedRecords);
            if (isHostApp) {
                SharedPreferencesUtils.putStr(applicationContext, Constants.INTERNAL_HOOKS_CONFIG, HookedRecords);
            } else {
                // LSPatch适配
                ContentValues values = new ContentValues();
                values.put("value", HookedRecords);
                applicationContext.getContentResolver().insert(
                        CONFIG_URI,
                        values
                );

            }
        }


        //  用户自定义HOOK
        String externalHooksConfig = HooksConfig.get("externalHooksConfig");
        if (internalHooksConfig != null && !internalHooksConfig.trim().isEmpty() && !internalHooksConfig.equals("\"null\"") && !internalHooksConfig.equals("null")) {
            List<Map<String, Object>> externalHooks;
            externalHooks = JsonHandler.deserializeHookedRecords(externalHooksConfig);

            for (Map<String, Object> externalHook : externalHooks) {
                XposedBridge.log("externalHook => " + externalHook);
                Boolean open = (Boolean) externalHook.get("open");
                String hookPackageName = (String) externalHook.get("packageName");

                if (!("ALL".equals(hookPackageName) || packageName.equals(hookPackageName)) || !Boolean.TRUE.equals(open)) {
                    return;
                }

                String className = (String) externalHook.get("className");
                String methodName = (String) externalHook.get("methodName");
                try {
                    XposedBridge.hookAllMethods(
                            XposedHelpers.findClass(className, classLoader),
                            methodName,
                            new XC_MethodHook() {
                                @SuppressLint("DefaultLocale")
                                @Override
                                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                                    super.beforeHookedMethod(methodHookParam);
                                    if (!getIsHook()) return;
                                    Map<String, Bundle> Params = getParams(methodHookParam);
                                    Bundle resBundle = Params.get("res");
                                    Bundle logBundle = Params.get("log");

                                    Bundle bundle = new Bundle();
                                    bundle.putString("category", (String) externalHook.get("category"));
                                    assert logBundle != null;
                                    if (externalHook.get("title") != null) {
                                        bundle.putString("title", logBundle.getString(String.format("arg%s", externalHook.get("title"))));
                                    } else {
                                        bundle.putString("title", methodName);
                                    }
                                    if (externalHook.get("data") != null) {
                                        bundle.putString("data", logBundle.getString(String.format("arg%s", externalHook.get("data"))));
                                    } else {
                                        bundle.putString("data", logBundle.toString());
                                    }

                                    bundle.putString("FunctionCall", methodName);

                                    bundle.putAll(resBundle);
                                    filterCustomize(bundle);
                                }
                            }
                    );
                } catch (Exception e) {
                    XposedBridge.log("自定义HOOK报错: " + e);
                }
            }
        }
    }

    /**
     * 调试测试代码
     */
    private void HookClass(ClassLoader classLoader, String classNameToHook) {
        try {
            // 获取目标类的 Class 对象
            Class<?> clazz = XposedHelpers.findClass(classNameToHook, classLoader);

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
                                paramDetails.append(className).append(": ").append(toStringValue);

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

    @SuppressLint("DefaultLocale")
    private Map<String, Bundle> getParams(XC_MethodHook.MethodHookParam methodHookParam) {
        // 构造参数输出
        Map<String, Bundle> result = new HashMap<>();
        Bundle logBundle = new Bundle();
        Bundle resBundle = new Bundle();

        if (methodHookParam.args != null && methodHookParam.args.length > 0) {
            for (int i = 0; i < methodHookParam.args.length; i++) {
                Object arg = methodHookParam.args[i];
                String className = (arg != null) ? arg.getClass().getName() : "null";
                String toStringValue = (arg != null) ? arg.toString() : "null";

                logBundle.putString(String.format("arg%d", i), toStringValue);
                resBundle.putString(String.format("arg%d[%s]", i, className), toStringValue);
            }
        }
        result.put("log", logBundle);
        result.put("res", resBundle);
        XposedBridge.log("参数详情: " + logBundle);
        return result;
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
