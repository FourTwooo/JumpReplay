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
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Binder;
import android.os.Build;
import android.os.Bundle;
import android.content.pm.PackageInfo;
import android.content.pm.ProviderInfo;
import android.content.pm.ServiceInfo;

import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.NavigationCaptureSource;
import com.fourtwo.hookintent.library.IntentLibrary;
import com.fourtwo.hookintent.library.ObservedFragmentIntent;
import com.fourtwo.hookintent.replay.NavigationReplayTransport;
import com.fourtwo.hookintent.service.MessengerClient;
import com.fourtwo.hookintent.service.MessengerService;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import com.fourtwo.hookintent.xposed.RecordXposedBridge;
import com.fourtwo.hookintent.xposed.DurableCaptureOutbox;
import com.fourtwo.hookintent.xposed.IntentJournal;
import com.fourtwo.hookintent.xposed.HookConfigCache;
import com.fourtwo.hookintent.xposed.ActivityCaptureHooks;
import com.fourtwo.hookintent.xposed.InAppNavigationCapture;
import android.os.Handler;
import android.os.Looper;
import com.fourtwo.hookintent.xposed.ui.FloatWindow;
import com.fourtwo.hookintent.xposed.ui.FloatWindowView;

import java.lang.reflect.Method;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.EnumSet;
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
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XC_MethodReplacement;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
//import okhttp3.Request;


public class IntentCapture implements IXposedHookLoadPackage {

    private MessengerClient client = null;
    private Context applicationContext = null;
    private String packageName;
    private volatile boolean isHook = false;
    private volatile boolean isService = false;
    private volatile boolean hookStateKnown;
    private Boolean localHookOverride;
    private final Object stateLock = new Object();
    private Handler mainHandler;
    private final Map<Activity, FloatWindow> floatWindows = new java.util.WeakHashMap<>();
    private DurableCaptureOutbox outbox;
    private IntentJournal intentJournal, pendingJournal;
    private final java.util.concurrent.ExecutorService journalWorker = Executors.newSingleThreadExecutor();
    private volatile HookConfigCache systemConfig;
    private volatile EnumSet<NavigationCaptureSource> navigationSources = EnumSet.noneOf(NavigationCaptureSource.class);
    private volatile InAppNavigationCapture inAppNavigation;
    private volatile boolean learnNativeFragmentEntries;
    private boolean systemProcess;
    private boolean systemHooksInstalled;
    private ClassLoader systemClassLoader;
    private volatile boolean storageErrorShown;
    private long sourceLastUpdateTime;
    private long sourceVersionCode;
    private String sourceVersionName;
    private int sourceAppUid = -1;
    private final ThreadLocal<Boolean> captureInProgress = new ThreadLocal<Boolean>() {
        @Override protected Boolean initialValue() { return false; }
    };
    private final java.util.concurrent.atomic.AtomicBoolean floatingRefreshPending = new java.util.concurrent.atomic.AtomicBoolean();

    private void refreshFloatingViews() {
        if (!floatingRefreshPending.compareAndSet(false, true)) return;
        mainHandler.post(() -> {
            floatingRefreshPending.set(false);
            for (FloatWindow window : new ArrayList<>(floatWindows.values()))
                if (window.floatWindowView != null) window.floatWindowView.refreshJournal();
        });
    }
    private Boolean isHostApp = false;


    public static final String myAppPackage = "com.fourtwo.hookintent";
    private final String myAppClass = "com.fourtwo.hookintent.IntentIntercept";
    private static final String ANDROID_PACKAGE = "android";
    private static final String MY_PROVIDER_CLASS = "com.fourtwo.hookintent.service.ConfigProvider";
    private static final String MY_MESSENGER_SERVICE_CLASS = "com.fourtwo.hookintent.service.MessengerService";


    private Boolean getIsHook() {
        // Buffer startup events until the first handshake instead of dropping the first onResume.
        return !captureInProgress.get() && (!hookStateKnown || isHook);
    }

    private void applyHookState(boolean enabled, boolean snapshot) {
        synchronized (stateLock) {
            if (!snapshot) localHookOverride = null; // an explicit main-app toggle affects every process
            isHook = localHookOverride == null ? enabled : localHookOverride;
            hookStateKnown = true;
            try {
                if (outbox != null) outbox.resolveInitialState(isHook);
                final boolean capturePending = isHook;
                journalWorker.execute(() -> {
                    try {
                        if (pendingJournal != null && intentJournal != null) {
                            if (capturePending) for (long offset = 0; offset < pendingJournal.count(); offset += 100)
                                for (Intent intent : pendingJournal.page(offset, 100)) intentJournal.append(intent);
                            pendingJournal.clear();
                        }
                    } catch (Exception error) { reportCaptureError(error); }
                    refreshFloatingViews();
                });
            } catch (Exception error) { reportCaptureError(error); }
        }
        navigationCaptureStateChanged();
        mainHandler.post(() -> {
            for (FloatWindow window : new ArrayList<>(floatWindows.values())) if (window.floatWindowView != null) {
                window.floatWindowView.setHookState(isHook); window.floatWindowView.refreshJournal();
            }
        });
    }

    private void installNavigationCapture(Object attached) {
        if (isHostApp || inAppNavigation != null || !(attached instanceof Application)) return;
        InAppNavigationCapture capture;
        try {
            capture = new InAppNavigationCapture(this::shouldCaptureNavigation,
                    () -> learnNativeFragmentEntries, this::captureNavigation);
            capture.install((Application) attached);
        } catch (RuntimeException | LinkageError error) {
            XposedBridge.log("JumpReplay navigation initialization: " + error.getClass().getSimpleName());
            return;
        }
        inAppNavigation = capture;
        ContentObserver observer = new ContentObserver(mainHandler) {
            @Override public void onChange(boolean selfChange) {
                journalWorker.execute(IntentCapture.this::reloadNavigationConfig);
            }
        };
        try {
            applicationContext.getContentResolver()
                    .registerContentObserver(Constants.IN_APP_NAVIGATION_URI, false, observer);
        } catch (RuntimeException error) {
            XposedBridge.log("JumpReplay navigation config observer: " + error.getClass().getSimpleName());
        }
        journalWorker.execute(this::reloadNavigationConfig);
    }

    private void reloadNavigationConfig() {
        EnumSet<NavigationCaptureSource> sources = EnumSet.noneOf(NavigationCaptureSource.class);
        boolean learnEntries = false;
        try {
            Map<String, String> config = getConfig(Constants.IN_APP_NAVIGATION_URI);
            learnEntries = Boolean.parseBoolean(config.get(IntentLibrary.PREFERENCE));
            for (NavigationCaptureSource source : NavigationCaptureSource.values()) {
                if (Boolean.parseBoolean(config.get(source.preference()))) sources.add(source);
            }
        } catch (RuntimeException error) {
            sources.clear();
        }
        if (!navigationSources.equals(sources) || learnNativeFragmentEntries != learnEntries) {
            navigationSources = sources;
            learnNativeFragmentEntries = learnEntries;
            navigationCaptureStateChanged();
        }
    }

    private boolean shouldCaptureNavigation(NavigationCaptureSource source) {
        return navigationSources.contains(source) && getIsHook();
    }

    private void navigationCaptureStateChanged() {
        InAppNavigationCapture capture = inAppNavigation;
        if (capture != null) capture.onCaptureStateChanged();
    }

    private void captureNavigation(Activity activity, String destination,
            NavigationCaptureSource source, Bundle arguments, Bundle descriptor, Bundle diagnostics, ObservedFragmentIntent.Evidence nativeEntry) {
        if (!shouldCaptureNavigation(source)) return;
        Bundle record = new Bundle();
        record.putString("category", source.category());
        record.putString("title", destination);
        record.putString("FunctionCall", source.method());
        record.putString("from", activity.getClass().getName());
        record.putBundle(Constants.NAVIGATION_ARGUMENTS, arguments);
        if (nativeEntry != null && learnNativeFragmentEntries) {
            try {
                record.putString(ObservedFragmentIntent.RECORD_KEY, nativeEntry.encode());
            } catch (org.json.JSONException ignored) {
                // The regular Fragment capture is still available when the observed route cannot be indexed.
            }
        }
        if (descriptor == null) {
            if (diagnostics != null) record.putBundle(Constants.NAVIGATION_REPLAY_DIAGNOSTICS, diagnostics);
            filterCustomize(record);
            return;
        }
        journalWorker.execute(() -> {
            if (!shouldCaptureNavigation(source)) return;
            try {
                Bundle sealed = NavigationReplayTransport.seal(applicationContext, descriptor);
                if (sealed != null) {
                    record.putBundle(Constants.NAVIGATION_REPLAY, sealed);
                    record.putString("FunctionCall", descriptor.getString("method", source.method()));
                }
            } catch (Exception unavailable) {
                XposedBridge.log("JumpReplay navigation descriptor: " + unavailable.getClass().getSimpleName());
            }
            if (!record.containsKey(Constants.NAVIGATION_REPLAY)) {
                Bundle failure = new Bundle();
                failure.putString("storage", "proof_unavailable");
                record.putBundle(Constants.NAVIGATION_REPLAY_DIAGNOSTICS, failure);
            }
            filterCustomize(record);
        });
    }

    private void replayNavigation(Bundle descriptor, Bundle arguments, boolean waitForStartup,
            java.util.function.Consumer<Exception> callback) {
        InAppNavigationCapture capture = inAppNavigation;
        if (capture == null) {
            callback.accept(new IllegalStateException("应用内导航尚未初始化"));
            return;
        }
        capture.replay(descriptor, arguments, waitForStartup, callback);
    }

    private void reportCaptureError(Throwable error) {
        XposedBridge.log("JumpReplay capture storage/transport: " + error);
        if (!storageErrorShown && applicationContext != null) {
            storageErrorShown = true;
            mainHandler.post(() -> android.widget.Toast.makeText(applicationContext,
                    "JumpReplay 无法保存捕获数据，请检查存储空间。" + error.getMessage(), android.widget.Toast.LENGTH_LONG).show());
        }
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
        bundle.putInt("source_uid", android.os.Process.myUid());
        // Keep runtime identity distinct from the installed application's owner.
        if (sourceAppUid != -1) bundle.putInt("source_app_uid", sourceAppUid);
        bundle.putInt("capture_sdk", android.os.Build.VERSION.SDK_INT);
        if (sourceLastUpdateTime != 0) bundle.putLong("source_last_update", sourceLastUpdateTime);
        bundle.putString("stack_trace", getStackTraceString());

        addMessage(bundle);
    }

    private void filterScheme(String scheme_raw_url, String FunctionCall) {
        if (captureInProgress.get()) return;
        captureInProgress.set(true);
        try {
            if (scheme_raw_url == null) {
                return;
            }
            Bundle bundle = new Bundle();
            bundle.putString("FunctionCall", FunctionCall);
            bundle.putString("category", "Scheme");
            bundle.putString("scheme_raw_url", scheme_raw_url);
            sendBroadcastSafely(bundle);
        } finally { captureInProgress.set(false); }
    }

    private void filterIntent(Intent intent, String FunctionCall, String from) {
        if (captureInProgress.get()) return;
        captureInProgress.set(true);
        try {
            if (intent == null) return;
            Intent snapshot = new Intent(intent);
            if (client != null) client.rememberExtrasLoaders(snapshot);
            synchronized (stateLock) {
                if (hookStateKnown && !isHook) return;
                try {
                    IntentJournal journal = hookStateKnown ? intentJournal : pendingJournal;
                    if (journal != null) {
                        byte[] frozen = IntentJournal.snapshot(snapshot);
                        journalWorker.execute(() -> {
                            try { journal.append(frozen); }
                            catch (Exception error) { reportCaptureError(error); }
                            refreshFloatingViews();
                        });
                    }
                } catch (Exception error) { reportCaptureError(error); }
            }
            Bundle bundle = DataConverter.convertIntentToBundle(snapshot);
            bundle.putString("FunctionCall", FunctionCall);
            bundle.putString("category", "Intent");
            bundle.putString("uri", snapshot.toUri(Intent.URI_INTENT_SCHEME));
            bundle.putString("from", from);
            if ("Activity.performCreate".equals(FunctionCall) || "ActivityThread.deliverNewIntents".equals(FunctionCall)
                    || "Activity.onNewIntent".equals(FunctionCall)) {
                bundle.putLong("library_destination_version_code", sourceVersionCode);
                bundle.putString("library_destination_version_name", sourceVersionName);
                bundle.putLong("library_destination_last_update", sourceLastUpdateTime);
            }
            sendBroadcastSafely(bundle);
        } finally { captureInProgress.set(false); }
    }

    private void filterCustomize(Bundle bundle) {
        if (captureInProgress.get()) return;
        captureInProgress.set(true);
        try {
            sendBroadcastSafely(bundle);
        } finally { captureInProgress.set(false); }
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
    private void hookAllIfExists(ClassLoader classLoader, String className, String methodName, XC_MethodHook hook) {
        try {
            Class<?> clazz = XposedHelpers.findClass(className, classLoader);
            XposedBridge.hookAllMethods(clazz, methodName, hook);
            XposedBridge.log("Hooked " + className + "#" + methodName);
        } catch (Throwable ignored) {
        }
    }

    private Intent findIntentArg(Object[] args) {
        if (args == null) {
            return null;
        }
        for (Object arg : args) {
            if (arg instanceof Intent) {
                return (Intent) arg;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<ResolveInfo> extractResolveInfoList(Object resultObj) {
        if (resultObj == null) {
            return null;
        }

        if (resultObj instanceof List) {
            return new ArrayList<>((List<ResolveInfo>) resultObj);
        }

        try {
            if ("android.content.pm.ParceledListSlice".equals(resultObj.getClass().getName())) {
                Method getListMethod = resultObj.getClass().getMethod("getList");
                Object listObj = getListMethod.invoke(resultObj);
                if (listObj instanceof List) {
                    return new ArrayList<>((List<ResolveInfo>) listObj);
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("extractResolveInfoList failed: " + t);
        }

        return null;
    }

    private Object rebuildResolveInfoResult(Object originalResultObj, List<ResolveInfo> newList) {
        if (originalResultObj == null) {
            return newList;
        }

        if (originalResultObj instanceof List) {
            return newList;
        }

        try {
            if ("android.content.pm.ParceledListSlice".equals(originalResultObj.getClass().getName())) {
                Constructor<?> constructor = originalResultObj.getClass().getDeclaredConstructor(List.class);
                constructor.setAccessible(true);
                return constructor.newInstance(newList);
            }
        } catch (Throwable t) {
            XposedBridge.log("rebuildResolveInfoResult failed: " + t);
        }

        return originalResultObj;
    }

    private boolean containsMyResolveInfo(List<ResolveInfo> resolveInfos) {
        if (resolveInfos == null) {
            return false;
        }

        for (ResolveInfo resolveInfo : resolveInfos) {
            if (resolveInfo != null
                    && resolveInfo.activityInfo != null
                    && myAppPackage.equals(resolveInfo.activityInfo.packageName)) {
                return true;
            }
        }
        return false;
    }
    private boolean isMyComponent(ComponentName componentName, String className) {
        return componentName != null
                && myAppPackage.equals(componentName.getPackageName())
                && className.equals(componentName.getClassName());
    }

    private boolean isMyProviderRequest(Object[] args) {
        if (args == null) {
            return false;
        }

        for (Object arg : args) {
            if (arg instanceof String) {
                String s = (String) arg;
                if (Constants.AUTHORITY.equals(s) || MY_PROVIDER_CLASS.equals(s)) {
                    return true;
                }
            } else if (arg instanceof Uri) {
                Uri uri = (Uri) arg;
                if (Constants.AUTHORITY.equals(uri.getAuthority())) {
                    return true;
                }
            } else if (arg instanceof ProviderInfo) {
                ProviderInfo pi = (ProviderInfo) arg;
                if (myAppPackage.equals(pi.packageName) || Constants.AUTHORITY.equals(pi.authority)) {
                    return true;
                }
            }
        }

        return false;
    }

    private boolean isMyServiceRequest(Object[] args) {
        if (args == null) {
            return true;
        }

        for (Object arg : args) {
            if (arg instanceof ComponentName) {
                if (isMyComponent((ComponentName) arg, MY_MESSENGER_SERVICE_CLASS)) {
                    return false;
                }
            } else if (arg instanceof Intent) {
                Intent intent = (Intent) arg;

                if (isMyComponent(intent.getComponent(), MY_MESSENGER_SERVICE_CLASS)) {
                    return false;
                }

                Intent selector = intent.getSelector();
                if (selector != null && isMyComponent(selector.getComponent(), MY_MESSENGER_SERVICE_CLASS)) {
                    return false;
                }
            } else if (arg instanceof String) {
                String s = (String) arg;
                if (MY_MESSENGER_SERVICE_CLASS.equals(s)) {
                    return false;
                }
            } else if (arg instanceof ServiceInfo) {
                ServiceInfo si = (ServiceInfo) arg;
                if (myAppPackage.equals(si.packageName) || MY_MESSENGER_SERVICE_CLASS.equals(si.name)) {
                    return false;
                }
            }
        }

        return true;
    }


    private ProviderInfo findMyProviderInfo(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    myAppPackage,
                    PackageManager.GET_PROVIDERS | PackageManager.GET_META_DATA
            );

            if (packageInfo.providers != null) {
                for (ProviderInfo pi : packageInfo.providers) {
                    if (MY_PROVIDER_CLASS.equals(pi.name) || Constants.AUTHORITY.equals(pi.authority)) {
                        return pi;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("findMyProviderInfo failed: " + t);
        }
        return null;
    }

    private ServiceInfo findMyMessengerServiceInfo(Context context) {
        try {
            PackageInfo packageInfo = context.getPackageManager().getPackageInfo(
                    myAppPackage,
                    PackageManager.GET_SERVICES | PackageManager.GET_META_DATA
            );

            if (packageInfo.services != null) {
                for (ServiceInfo si : packageInfo.services) {
                    if (MY_MESSENGER_SERVICE_CLASS.equals(si.name)) {
                        return si;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("findMyMessengerServiceInfo failed: " + t);
        }
        return null;
    }

    private ResolveInfo buildMyServiceResolveInfo(Context context) {
        try {
            ServiceInfo si = findMyMessengerServiceInfo(context);
            if (si == null) {
                return null;
            }

            ResolveInfo ri = new ResolveInfo();
            ri.serviceInfo = si;
            ri.resolvePackageName = si.packageName;
            ri.isDefault = true;
            ri.match = 0x600000;
            ri.priority = 1000;
            ri.preferredOrder = 0;
            return ri;
        } catch (Throwable t) {
            XposedBridge.log("buildMyServiceResolveInfo failed: " + t);
            return null;
        }
    }

    private void hookProviderVisibilityBypass(Context context) {
        ClassLoader classLoader = systemClassLoader == null ? context.getClassLoader() : systemClassLoader;

        XC_MethodHook providerHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.getResult() != null) {
                    return;
                }

                if (!isMyProviderRequest(param.args)) {
                    return;
                }

                ProviderInfo providerInfo = findMyProviderInfo(context);
                if (providerInfo != null) {
                    XposedBridge.log("Bypass resolveContentProvider for " + Constants.AUTHORITY);
                    param.setResult(providerInfo);
                }
            }
        };

        hookAllIfExists(classLoader, "com.android.server.pm.PackageManagerService", "resolveContentProvider", providerHook);
        hookAllIfExists(classLoader, "com.android.server.pm.ComputerEngine", "resolveContentProvider", providerHook);
        hookAllIfExists(classLoader, "com.android.server.pm.ComputerEngine", "resolveContentProviderInternal", providerHook);
    }

    private void hookServiceVisibilityBypass(Context context) {
        ClassLoader classLoader = systemClassLoader == null ? context.getClassLoader() : systemClassLoader;

        XC_MethodHook serviceInfoHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.getResult() != null) {
                    return;
                }

                if (isMyServiceRequest(param.args)) {
                    return;
                }

                ServiceInfo serviceInfo = findMyMessengerServiceInfo(context);
                if (serviceInfo != null) {
                    XposedBridge.log("Bypass getServiceInfo for " + MY_MESSENGER_SERVICE_CLASS);
                    param.setResult(serviceInfo);
                }
            }
        };

        XC_MethodHook resolveServiceHook = new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                if (param.getResult() != null) {
                    return;
                }

                if (isMyServiceRequest(param.args)) {
                    return;
                }

                ResolveInfo resolveInfo = buildMyServiceResolveInfo(context);
                if (resolveInfo != null) {
                    XposedBridge.log("Bypass resolveService for " + MY_MESSENGER_SERVICE_CLASS);
                    param.setResult(resolveInfo);
                }
            }
        };

        hookAllIfExists(classLoader, "com.android.server.pm.PackageManagerService", "getServiceInfo", serviceInfoHook);
        hookAllIfExists(classLoader, "com.android.server.pm.ComputerEngine", "getServiceInfo", serviceInfoHook);
        hookAllIfExists(classLoader, "com.android.server.pm.ComputerEngine", "getServiceInfoInternal", serviceInfoHook);

        hookAllIfExists(classLoader, "com.android.server.pm.PackageManagerService", "resolveService", resolveServiceHook);
        hookAllIfExists(classLoader, "com.android.server.pm.ComputerEngine", "resolveService", resolveServiceHook);
        hookAllIfExists(classLoader, "com.android.server.pm.ComputerEngine", "resolveServiceInternal", resolveServiceHook);
    }

    private ActivityInfo findMyInterceptActivityInfo(Context context) {
        try {
            PackageManager pm = context.getPackageManager();
            PackageInfo packageInfo = pm.getPackageInfo(
                    myAppPackage,
                    PackageManager.GET_ACTIVITIES | PackageManager.GET_META_DATA
            );

            if (packageInfo.activities != null) {
                for (ActivityInfo activityInfo : packageInfo.activities) {
                    if (myAppClass.equals(activityInfo.name)) {
                        return activityInfo;
                    }
                }
            }
        } catch (Throwable t) {
            XposedBridge.log("findMyInterceptActivityInfo failed: " + t);
        }
        return null;
    }

    private ResolveInfo buildMyChooserResolveInfo(Context context, ResolveInfo templateResolveInfo, Intent sourceIntent) {
        try {
            ActivityInfo myActivityInfo = systemConfig == null ? null : systemConfig.getActivity();
            if (myActivityInfo == null) {
                XposedBridge.log("buildMyChooserResolveInfo: myActivityInfo == null");
                return null;
            }

            ResolveInfo myResolveInfo = new ResolveInfo();
            myResolveInfo.activityInfo = myActivityInfo;
            myResolveInfo.resolvePackageName = myActivityInfo.packageName;

            if (templateResolveInfo != null) {
                myResolveInfo.match = templateResolveInfo.match;
                myResolveInfo.priority = templateResolveInfo.priority;
                myResolveInfo.preferredOrder = templateResolveInfo.preferredOrder;
            } else {
                myResolveInfo.match = 0;
                myResolveInfo.priority = 0;
                myResolveInfo.preferredOrder = 0;
            }

            myResolveInfo.isDefault = true;

            IntentFilter intentFilter = new IntentFilter();
            intentFilter.addAction(Intent.ACTION_VIEW);
            intentFilter.addCategory(Intent.CATEGORY_DEFAULT);
            intentFilter.addCategory(Intent.CATEGORY_BROWSABLE);

            if (sourceIntent.getScheme() != null) {
                intentFilter.addDataScheme(sourceIntent.getScheme());
            }

            myResolveInfo.filter = intentFilter;
            return myResolveInfo;
        } catch (Throwable t) {
            XposedBridge.log("buildMyChooserResolveInfo failed: " + t);
            return null;
        }
    }

    public void hookSystemMethods(Context applicationContext) {
        ClassLoader classLoader = systemClassLoader == null ? applicationContext.getClassLoader() : systemClassLoader;
        // System bootstrap must not contact this app's Provider or register receivers.
        // The callback reads only a ready cache; preserve the original result until then.
        new Handler(Looper.getMainLooper()).post(() -> {
            try { systemConfig = new HookConfigCache(applicationContext); }
            catch (Throwable error) { XposedBridge.log("JumpReplay system cache initialization failed: " + error); }
        });

        // 只修复 Android 11+ 上的 Provider / MessengerService 可见性
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            hookProviderVisibilityBypass(applicationContext);
            hookServiceVisibilityBypass(applicationContext);
        }

        String ClassName = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ? "com.android.server.pm.ComputerEngine" : "com.android.server.pm.PackageManagerService";
        if (Build.VERSION.SDK_INT == 31 || Build.VERSION.SDK_INT == 32) {
            if (XposedHelpers.findClassIfExists("com.android.server.pm.PackageManagerService$ComputerEngine", classLoader) != null)
                ClassName = "com.android.server.pm.PackageManagerService$ComputerEngine";
        }
        Class<?> hookClass;
        try {
            hookClass = XposedHelpers.findClass(ClassName, classLoader);
            XposedBridge.log("android.server加载成功! => " + ClassName);
//            HookClass(classLoader, ClassName);
            XposedBridge.hookAllMethods(hookClass, "queryIntentActivitiesInternal", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Intent intent = findIntentArg(param.args);
                    if (intent == null || param.hasThrowable() || systemConfig == null) return;
                    // 如果是自己的 Intent，不再处理，直接返回
                    if (myAppPackage.equals(intent.getPackage())) {
                        return;
                    }
                    if (intent.getComponent() != null || intent.getScheme() == null || intent.getPackage() != null || intent.getDataString() == null) {
                        return;
                    }

                    XposedBridge.log("queryIntentActivitiesInternal Scheme: " + intent.getDataString());

                    // 获取原始返回值
                    List<ResolveInfo> originalResult = extractResolveInfoList(param.getResult());
                    if (originalResult == null) return;
                    // Rules also apply when HTTP/HTTPS already resolves to this module.
                    if (systemConfig.isDisabled(intent.getScheme(), intent.getDataString())) {
                        param.setResult(rebuildResolveInfoResult(param.getResult(), new ArrayList<>())); return;
                    }
                    if (containsMyResolveInfo(originalResult)) return;

                    // 直接手工构造自己的 ResolveInfo，不再递归 queryIntentActivitiesInternal
                    ResolveInfo clAppResolveInfo = originalResult.isEmpty() ? null : originalResult.get(0);
                    ResolveInfo myAppResolveInfo = buildMyChooserResolveInfo(
                            applicationContext,
                            clAppResolveInfo,
                            intent
                    );

                    if (myAppResolveInfo != null) {
                        originalResult.add(myAppResolveInfo);
                        XposedBridge.log("originalResult.add: " + originalResult);
                    } else {
                        XposedBridge.log("myAppResolveInfo == null, skip inject");
                    }

                    // 将修改后的结果设置回返回值
                    param.setResult(rebuildResolveInfoResult(param.getResult(), originalResult));
                }
            });
        } catch (XposedHelpers.ClassNotFoundError ignored) {
        }

    }

    private void initializeSystemHooks() {
        if (systemHooksInstalled) return;
        try {
            Object thread = XposedHelpers.callStaticMethod(ActivityThread.class, "currentActivityThread");
            Context context = (Context) XposedHelpers.callMethod(thread, "getSystemContext");
            if (context == null) throw new IllegalStateException("System context is not ready");
            applicationContext = context;
            systemHooksInstalled = true;
            hookSystemMethods(context);
        } catch (Throwable error) {
            XposedBridge.log("JumpReplay could not initialize system scheme hook: " + error);
        }
    }

    public void SetFloatWindowUi(Context applicationContext) {
        try {
            Class<?> activityClass = Activity.class;
            XposedBridge.hookMethod(activityClass.getDeclaredMethod("onResume"), new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    Activity activity = (Activity) param.thisObject;
                    FloatWindow previous = floatWindows.remove(activity);
                    if (previous != null) previous.removeView();
                    FloatWindow window = new FloatWindow(activity, activity);
                    FloatWindowView view = new FloatWindowView(activity);
                    view.setJournal(intentJournal);
                    view.setOnClearViewClickListener(unused -> {
                        synchronized (stateLock) {
                            journalWorker.execute(() -> {
                                try { if (intentJournal != null) intentJournal.clear(); if (pendingJournal != null) pendingJournal.clear(); }
                                catch (Exception error) { reportCaptureError(error); }
                                refreshFloatingViews();
                            });
                        }
                    });
                    view.setOnOpenViewClickListener(enabled -> {
                        synchronized (stateLock) { localHookOverride = enabled; }
                        applyHookState(enabled, true); // process-local override: never send a SET_HOOK message
                    });
                    window.setFloatWindowView(view.createView(isHook));
                    floatWindows.put(activity, window); window.initialize();
                }
            });
            XC_MethodHook remove = new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    FloatWindow window = floatWindows.remove((Activity) param.thisObject);
                    if (window != null) window.removeView();
                }
            };
            XposedBridge.hookMethod(activityClass.getDeclaredMethod("onPause"), remove);
            XposedBridge.hookMethod(activityClass.getDeclaredMethod("onDestroy"), remove);
        } catch (NoSuchMethodException error) { XposedBridge.log("JumpReplay floating window: " + error); }
    }


    // 确保整个进程只会Hook一次
    public void HookStart(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        mainHandler = new Handler(Looper.getMainLooper());
        if (systemProcess) { initializeSystemHooks(); return; }
        if (ANDROID_PACKAGE.equals(loadPackageParam.packageName) && ("android".equals(loadPackageParam.processName) || "system_server".equals(loadPackageParam.processName))) {
            hookSystemMethods(applicationContext);
            return;
        }

        if (!isHostApp && client == null) {
            try {
                android.content.pm.PackageInfo source = applicationContext.getPackageManager().getPackageInfo(packageName, 0);
                sourceLastUpdateTime = source.lastUpdateTime;
                if (source.applicationInfo != null) sourceAppUid = source.applicationInfo.uid;
                sourceVersionCode = Build.VERSION.SDK_INT >= 28 ? source.getLongVersionCode() : source.versionCode;
                sourceVersionName = source.versionName;
            }
            catch (Exception ignored) { }
            client = new MessengerClient(applicationContext);
            client.enableExtrasEditing(loadPackageParam.classLoader);
            client.enableNavigationReplay(this::replayNavigation);
            try { outbox = new DurableCaptureOutbox(applicationContext, client, error -> XposedBridge.log("JumpReplay transport retry: " + error)); }
            catch (Exception error) { reportCaptureError(error); }
            client.setConnectionCallback(new MessengerClient.ConnectionCallback() {
                @Override public void onConnected() {
                    isService = true;
                    if (outbox != null) outbox.setConnected(true);
                    client.sendMessageAsync(MessengerService.MSG_IS_HOOK, null, true, new MessengerClient.ResultCallback() {
                        @Override public void onResult(Bundle result) { applyHookState(result.getInt("resultCode") == 1, true); }
                        @Override public void onError(Exception error) { XposedBridge.log("JumpReplay state query: " + error); }
                    });
                }
                @Override public void onDisconnected() { isService = false; if (outbox != null) outbox.setConnected(false); }
            });
            client.registerPassiveCallback(data -> applyHookState(data.getBoolean("isHook"), data.getBoolean("stateSnapshot", false)));
            startBatchSender();
        }

        Map<String, Boolean> FloatWindowConfig = new HashMap<>();
        FloatWindowConfig.put("my_float_window", false);
        FloatWindowConfig.put("float_window", true);

        try {
            Map<String, String> HooksConfig = getConfig(Constants.CONFIG_URI);
            hookMethods(applicationContext, HooksConfig);
            FloatWindowConfig.putAll(JsonHandler.strToBoolean(JsonHandler.jsonToMap(HooksConfig.get(Constants.FLOAT_WINDOW_CONFIG))));
        } catch (RuntimeException e) {
            XposedBridge.log("写入失败, 服务端未连接 APP未持有权限 => " + e);
        }

        XposedBridge.log("FloatWindowConfig: " + FloatWindowConfig);
        if (Boolean.TRUE.equals(FloatWindowConfig.get("float_window"))) {
            try {
                intentJournal = new IntentJournal(applicationContext);
                pendingJournal = new IntentJournal(applicationContext, "pending-floating-history");
            } catch (Exception error) { reportCaptureError(error); }
            if (isHostApp) {
                if (Boolean.TRUE.equals(FloatWindowConfig.get("my_float_window"))) {
                    SetFloatWindowUi(applicationContext);
                }
            } else {
                SetFloatWindowUi(applicationContext);
            }
        }
    }

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam loadPackageParam) {
        if ("android".equals(loadPackageParam.packageName)
                && ("android".equals(loadPackageParam.processName) || "system_server".equals(loadPackageParam.processName))) {
            // The system Application may already be attached when LSPosed calls us.
            // Remember the PROCESS role before SettingsProvider loads another package here.
            systemProcess = true;
            systemClassLoader = loadPackageParam.classLoader;
            initializeSystemHooks();
            return;
        }
        // Packages loaded inside system_server must never install normal app capture/IPC.
        if (systemProcess) return;
        // Loading WebView/another package in this process must not overwrite the
        // identity captured when the application's hooks and transport were installed.
        if (applicationContext == null) packageName = loadPackageParam.packageName;
        if (loadPackageParam.packageName.equals(myAppPackage)) {
            XposedHelpers.findAndHookMethod("com.fourtwo.hookintent.MainActivity", loadPackageParam.classLoader, "isXposed", XC_MethodReplacement.returnConstant(true));
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
                    HookStart(loadPackageParam);
                }
                installNavigationCapture(param.thisObject);
            }
        });

        XposedHelpers.findAndHookMethod(Application.class, "attach", Context.class, new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                super.afterHookedMethod(param);
                if (applicationContext == null) {
                    applicationContext = (Context) param.args[0];
                    XposedBridge.log(packageName + ": xposed挂载成功 attach");
                    HookStart(loadPackageParam);
                }
                installNavigationCapture(param.thisObject);
            }
        });
    }

    private Map<String, String> getConfig(Uri uri) {
        Map<String, String> result = new HashMap<>();
        try (Cursor cursor = applicationContext.getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null) while (cursor.moveToNext())
                result.put(cursor.getString(cursor.getColumnIndexOrThrow("key")), cursor.getString(cursor.getColumnIndexOrThrow("value")));
        }
        return result;
    }

    private Class<?> requireHookClass(String name, ClassLoader loader) {
        Class<?> result = XposedHelpers.findClassIfExists(name, loader);
        if (result == null) throw new IllegalArgumentException("Hook class not found: " + name);
        return result;
    }

    private void hookMethods(Context applicationContext, Map<String, String> HooksConfig) {
        ClassLoader classLoader = applicationContext.getClassLoader();
        String internalHooksConfig = HooksConfig.get(Constants.INTERNAL_HOOKS_CONFIG);
        if (internalHooksConfig != null) {
            RecordXposedBridge.setHookedRecords(JsonHandler.deserializeHookedRecords(internalHooksConfig));
        }

        ActivityCaptureHooks.withDelivery(this::getIsHook, this::filterIntent, (activity, intent) -> {
            InAppNavigationCapture capture = inAppNavigation;
            if (capture != null) capture.observeDelivery(activity, intent);
        }).install(classLoader);

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

        if (internalHooksConfig == null || isHostApp) {
            String HookedRecords = JsonHandler.serializeHookedRecords(RecordXposedBridge.getHookedRecords());
            XposedBridge.log("hookAllMethods: " + HookedRecords);
            if (isHostApp) {
                SharedPreferencesUtils.putStr(applicationContext, Constants.INTERNAL_HOOKS_CONFIG, HookedRecords);
            } else {
                // LSPatch适配
                ContentValues values = new ContentValues();
                values.put("value", HookedRecords);
                applicationContext.getContentResolver().insert(
                        Constants.CONFIG_URI,
                        values
                );

            }
        }

        //  用户自定义HOOK
        String externalHooksConfig = HooksConfig.get(Constants.EXTERNAL_HOOKS_CONFIG);
        if (externalHooksConfig != null && !externalHooksConfig.trim().isEmpty() && !externalHooksConfig.equals("\"null\"") && !externalHooksConfig.equals("null")) {
            List<Map<String, Object>> externalHooks;
            externalHooks = JsonHandler.deserializeHookedRecords(externalHooksConfig);

            for (Map<String, Object> externalHook : externalHooks) {
                XposedBridge.log("externalHook => " + externalHook);
                Object enabledValue = externalHook.get("open");
                Boolean open = enabledValue instanceof Boolean ? (Boolean) enabledValue : false;
                Object packageValue = externalHook.get("packageName");
                String hookPackageName = packageValue instanceof String ? (String) packageValue : null;

                if (!("ALL".equals(hookPackageName) || packageName.equals(hookPackageName)) || !Boolean.TRUE.equals(open)) {
                    continue;
                }

                Object classValue = externalHook.get("className"), methodValue = externalHook.get("methodName");
                if (!(classValue instanceof String) || !(methodValue instanceof String)) {
                    XposedBridge.log("Invalid JumpReplay custom Hook: " + externalHook); continue;
                }
                String className = (String) classValue, methodName = (String) methodValue;
                try {
                    XposedBridge.hookAllMethods(
                            requireHookClass(className, classLoader),
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
                } catch (RuntimeException | LinkageError e) {
                    XposedBridge.log("自定义HOOK报错: " + className + "#" + methodName + " " + e);
                }
            }
        }

//        try {
//            Class<?> RealCall = Class.forName("okhttp3.RealCall", false, classLoader);
//            XposedHelpers.findAndHookMethod(RealCall, "execute", new XC_MethodHook() {
//                @Override
//                protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
//                    // 在方法调用前的逻辑（如需要可以添加）
//                }
//
//                @Override
//                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
//                    try {
//                        Object response = param.getResult(); // 获取返回的 Response 对象
//                        Object request = XposedHelpers.callMethod(response, "request"); // 获取 Request 对象
//
//                        // 获取请求方法和 URL
//                        String method = (String) XposedHelpers.callMethod(request, "method");
//                        String url = XposedHelpers.callMethod(request, "url").toString();
//                        XposedBridge.log("[FINAL] [" + method + "] " + url);
//
//                        // 获取请求体
//                        Object body = XposedHelpers.callMethod(request, "body"); // 获取 RequestBody 对象
//                        if (body != null) {
//                            // 创建 Buffer 来读取请求体内容
//                            Class<?> bufferClass = XposedHelpers.findClass("okio.Buffer", classLoader);
//                            Object buffer = XposedHelpers.newInstance(bufferClass);
//
//                            // 调用 RequestBody.writeTo(buffer) 将内容写入 Buffer
//                            XposedHelpers.callMethod(body, "writeTo", buffer);
//
//                            // 获取 Buffer 中的字符串内容
//                            String requestBody = (String) XposedHelpers.callMethod(buffer, "readUtf8");
//                            XposedBridge.log("[REQUEST_BODY] " + requestBody); // 打印请求体日志
//                        }
//                        // 获取响应状态码
//                        int code = (int) XposedHelpers.callMethod(response, "code");
//
//                        // 获取响应体
//                        Object peekBody = XposedHelpers.callMethod(response, "peekBody", 1024 * 1024); // 获取最多 1MB 的数据
//                        String res_body = (String) XposedHelpers.callMethod(peekBody, "string");
//                        // 输出日志
//                        XposedBridge.log("[FINAL_BODY] [" + code + "] " + res_body);
//                    } catch (Exception e) {
//                        XposedBridge.log("[ERROR] " + e);
//                    }
//                }
//            });
//
//        } catch (ClassNotFoundException e) {
//            XposedBridge.log(packageName + ": 未找到okhttp");
//        } catch (Exception e) {
//            XposedBridge.log(packageName + ": Hook OkHttp 发生错误: " + e.getMessage());
//        }


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
        synchronized (stateLock) {
            if (hookStateKnown && !isHook) return;
            try {
                if (outbox == null) throw new java.io.IOException("Capture outbox unavailable");
                outbox.add(bundle, !hookStateKnown);
            } catch (Exception error) {
                // Keep the full record until disk storage recovers; never drain-and-forget it.
                Bundle pending = new Bundle(bundle); pending.putBoolean("_jumpReplayPendingState", !hookStateKnown);
                messageQueue.add(pending); reportCaptureError(error);
            }
        }
    }

    // 定时任务，批量发送消息
    private void startBatchSender() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleWithFixedDelay(() -> {
            synchronized (stateLock) {
                try {
                    if (outbox == null) {
                        outbox = new DurableCaptureOutbox(applicationContext, client, error -> XposedBridge.log("JumpReplay transport retry: " + error));
                        outbox.setConnected(isService);
                    }
                    Bundle record;
                    while ((record = messageQueue.peek()) != null) {
                        boolean pending = record.getBoolean("_jumpReplayPendingState", false);
                        Bundle copy = new Bundle(record); copy.remove("_jumpReplayPendingState");
                        if (!pending || !hookStateKnown || isHook) outbox.add(copy, pending && !hookStateKnown);
                        messageQueue.remove(record);
                    }
                    if (hookStateKnown) outbox.resolveInitialState(isHook);
                } catch (Exception error) { reportCaptureError(error); }
            }
        }, 0, 1, TimeUnit.SECONDS);
    }
}
