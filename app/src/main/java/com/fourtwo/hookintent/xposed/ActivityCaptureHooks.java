package com.fourtwo.hookintent.xposed;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Independent sending paths and real delivery paths; no startActivity wrapper hooks. */
public final class ActivityCaptureHooks {
    public interface Sink { void capture(Intent intent, String function, String from); }
    interface Installer { void install(Method method, XC_MethodHook callback); }
    private final BooleanSupplier enabled;
    private final Sink sink;
    private final Installer installer;
    private final ThreadLocal<Integer> createDepth = new ThreadLocal<>();
    private final AtomicBoolean errorLogged = new AtomicBoolean();

    public ActivityCaptureHooks(BooleanSupplier enabled, Sink sink) {
        this(enabled, sink, (method, callback) -> XposedBridge.hookMethod(method, callback));
    }

    ActivityCaptureHooks(BooleanSupplier enabled, Sink sink, Installer installer) {
        this.enabled = enabled;
        this.sink = sink;
        this.installer = installer;
    }

    public void install(ClassLoader loader) {
        boolean activityStart = RecordXposedBridge.recordHook("Intent", Activity.class.getName(), "startActivity");
        boolean activityResult = RecordXposedBridge.recordHook("Intent", Activity.class.getName(), "startActivityForResult");
        boolean contextStart = RecordXposedBridge.recordHook("Intent", "android.content.ContextWrapper", "startActivity");
        boolean senderDefault = activityStart || activityResult || contextStart;
        boolean createEnabled = RecordXposedBridge.recordHook("Intent", Activity.class.getName(), "onResume");
        boolean newIntentEnabled = RecordXposedBridge.recordHook("Intent", Activity.class.getName(), "onNewIntent");
        for (String method : new String[]{"execStartActivity", "execStartActivitiesAsUser",
                "execStartActivityAsCaller", "execStartActivityFromAppTask"})
            RecordXposedBridge.recordHook("Intent", Instrumentation.class.getName(), method, senderDefault);
        RecordXposedBridge.recordHook("Intent", "android.app.ContextImpl", "startActivityAsUser", senderDefault);
        RecordXposedBridge.recordHook("Intent", Activity.class.getName(), "startActivityIfNeeded", senderDefault);
        RecordXposedBridge.recordHook("Intent", Activity.class.getName(), "startNextMatchingActivity", senderDefault);
        if (RecordXposedBridge.isHostApp) return;
        for (String method : new String[]{"execStartActivity", "execStartActivitiesAsUser",
                "execStartActivityAsCaller", "execStartActivityFromAppTask"}) {
            try {
                if (RecordXposedBridge.recordHook("Intent", Instrumentation.class.getName(), method))
                    for (Method declared : Instrumentation.class.getDeclaredMethods())
                        if (declared.getName().equals(method)) hook(declared, sender("Instrumentation." + method));
            } catch (Throwable error) { report(error); }
        }
        // ContextImpl.startActivityAsUser makes a Binder call directly, bypassing Instrumentation.
        Class<?> contextImpl = XposedHelpers.findClassIfExists("android.app.ContextImpl", loader);
        if (contextImpl != null && RecordXposedBridge.recordHook("Intent", contextImpl.getName(), "startActivityAsUser")) {
            for (Method method : contextImpl.getDeclaredMethods()) {
                Class<?>[] p = method.getParameterTypes();
                if (method.getName().equals("startActivityAsUser") && p.length == 3
                        && p[0] == Intent.class && p[1] == Bundle.class) hook(method, sender("ContextImpl.startActivityAsUser"));
            }
        }
        // These Activity methods also call the system Binder directly. Shorter overloads
        // delegate here; hooking them would only capture the same request twice.
        for (Method method : Activity.class.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            boolean direct = (method.getName().equals("startActivityIfNeeded") && p.length == 3)
                    || (method.getName().equals("startNextMatchingActivity") && p.length == 2);
            if (direct && p[0] == Intent.class && p[p.length - 1] == Bundle.class
                    && RecordXposedBridge.getOpenStatus(Activity.class.getName(), "ALL", method.getName(), "Intent"))
                hook(method, sender("Activity." + method.getName()));
        }
        if (createEnabled) {
            for (Method method : Activity.class.getDeclaredMethods()) {
                // Android 10+ the one-argument method delegates to the two-argument method.
                // Older releases have independent implementations of both overloads.
                if (method.getName().equals("performCreate")
                        && (Build.VERSION.SDK_INT < 29 || method.getParameterTypes().length == 2)) {
                    hook(method, new XC_MethodHook() {
                        @Override protected void beforeHookedMethod(MethodHookParam param) {
                            int previous = createDepth.get() == null ? 0 : createDepth.get();
                            createDepth.set(previous + 1);
                            if (previous != 0 || !enabled.getAsBoolean()) return;
                            try {
                                Activity activity = (Activity) param.thisObject;
                                capture(activity.getIntent(), "Activity.performCreate", activity.getClass().getName());
                            } catch (Throwable error) { report(error); }
                        }
                        @Override protected void afterHookedMethod(MethodHookParam param) {
                            Integer current = createDepth.get();
                            if (current == null || current <= 1) createDepth.remove(); else createDepth.set(current - 1);
                        }
                    });
                }
            }
        }
        if (newIntentEnabled) installNewIntentDelivery(loader);
    }

    private XC_MethodHook sender(String function) {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (!enabled.getAsBoolean()) return;
                try {
                    if (!ActivityCapturePolicy.senderAllowed(Thread.currentThread().getStackTrace(),
                            (owner, method) -> RecordXposedBridge.getOpenStatus(owner, "ALL", method, "Intent"))) return;
                    String from = param.thisObject instanceof Context ? param.thisObject.getClass().getName() : null;
                    for (Object arg : param.args) if (arg instanceof Context) { from = arg.getClass().getName(); break; }
                    for (Intent intent : ActivityCapturePolicy.sendingIntents(param.args)) capture(intent, function, from);
                } catch (Throwable error) { report(error); }
            }
        };
    }

    private void installNewIntentDelivery(ClassLoader loader) {
        Class<?> thread = XposedHelpers.findClassIfExists("android.app.ActivityThread", loader);
        if (thread == null) { report(new ClassNotFoundException("ActivityThread")); return; }
        boolean installed = false;
        for (Method method : thread.getDeclaredMethods()) {
            Class<?>[] p = method.getParameterTypes();
            if (!method.getName().equals("deliverNewIntents") || p.length != 2
                    || !List.class.isAssignableFrom(p[1])) continue;
            try {
                Field field = p[0].getDeclaredField("activity");
                field.setAccessible(true);
                installed |= hook(method, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!enabled.getAsBoolean()) return;
                        try {
                            Activity activity = (Activity) field.get(param.args[0]);
                            if (activity == null || !(param.args[1] instanceof List)) return;
                            // Incoming ReferrerIntent extends Intent. Read the delivered argument,
                            // never Activity.getIntent(), which may still contain the old launch.
                            for (Object item : (List<?>) param.args[1]) if (item instanceof Intent) {
                                Intent snapshot = new Intent((Intent) item);
                                snapshot.setExtrasClassLoader(activity.getClassLoader());
                                capture(snapshot, "ActivityThread.deliverNewIntents", activity.getClass().getName());
                            }
                        } catch (Throwable error) { report(error); }
                    }
                });
            } catch (Throwable error) { report(error); }
        }
        if (!installed) {
            // Unrecognized OEM framework: keep the old hook instead of breaking application code.
            try {
                XC_MethodHook fallback = new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!enabled.getAsBoolean()) return;
                        for (Intent intent : ActivityCapturePolicy.sendingIntents(param.args))
                            capture(intent, "Activity.onNewIntent", param.thisObject.getClass().getName());
                    }
                };
                for (Method method : Activity.class.getDeclaredMethods())
                    if (method.getName().equals("onNewIntent")) hook(method, fallback);
            } catch (Throwable error) { report(error); }
        }
    }

    private boolean hook(Method method, XC_MethodHook callback) {
        try { installer.install(method, callback); return true; }
        catch (Throwable error) { report(error); return false; }
    }

    private void capture(Intent intent, String function, String from) {
        if (intent == null) return;
        try { sink.capture(intent, function, from); }
        catch (Throwable error) { report(error); }
    }

    private void report(Throwable error) {
        if (errorLogged.compareAndSet(false, true)) XposedBridge.log("JumpReplay activity capture: " + error);
    }
}
