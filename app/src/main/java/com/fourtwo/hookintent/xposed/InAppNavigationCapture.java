package com.fourtwo.hookintent.xposed;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import com.fourtwo.hookintent.data.NavigationCaptureSource;
import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Observe destinations within resumed activities without intercepting every View or Intent. */
public final class InAppNavigationCapture implements Application.ActivityLifecycleCallbacks {
    public interface Sink {
        void capture(Activity activity, String destination, NavigationCaptureSource source, Bundle arguments,
                Bundle replayDescriptor);
    }

    private static final String NAV_CONTROLLER = "androidx.navigation.NavController";
    private static final long LAYOUT_SAMPLE_MS = 40;
    private static final long SETTLE_MS = 120;
    private static final long TRANSITION_RECHECK_MS = 280;
    private static final int MAX_FRAGMENTS = 64;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Activity, ActivityWatch> resumed = new IdentityHashMap<>();
    private final Predicate<NavigationCaptureSource> enabled;
    private final Sink sink;
    private final JetpackNavigationReplay jetpackReplay;
    private final FragmentHostReplay fragmentReplay;
    private final Map<ClassLoader, FragmentAccess> supportByLoader = new WeakHashMap<>();
    private final Set<Class<?>> navControllers = Collections.newSetFromMap(new WeakHashMap<>());
    private FragmentAccess platform;
    private boolean errorLogged;

    public InAppNavigationCapture(Predicate<NavigationCaptureSource> enabled, Sink sink) {
        this.enabled = enabled;
        this.sink = sink;
        jetpackReplay = new JetpackNavigationReplay(main,
                () -> enabled.test(NavigationCaptureSource.NAV_CONTROLLER),
                activity -> activity != null && resumed.containsKey(activity));
        fragmentReplay = new FragmentHostReplay(main,
                activity -> resumed.containsKey(activity) && enabled.test(NavigationCaptureSource.FRAGMENT),
                () -> resumed.keySet(), this::resampleForFragmentReplay);
    }

    public void replay(Bundle descriptor, Bundle arguments, Consumer<Exception> result) {
        if (FragmentHostReplay.handles(descriptor)) {
            fragmentReplay.replay(descriptor, arguments, result);
        } else {
            jetpackReplay.replay(descriptor, arguments, result);
        }
    }

    private void resampleForFragmentReplay(Activity activity) {
        ActivityWatch watch = resumed.get(activity);
        if (watch == null) return;
        boolean alreadyObserving = watch.observing;
        watch.refresh();
        if (alreadyObserving && watch.observing) watch.sample();
    }

    public void install(Application application) {
        ClassLoader loader = application.getClassLoader();
        platform = FragmentAccess.load(loader, "android.app", false);
        application.registerActivityLifecycleCallbacks(this);
        installNavController(loader);
    }

    public void onCaptureStateChanged() {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            refreshWatches();
        } else {
            main.post(this::refreshWatches);
        }
    }

    private void refreshWatches() {
        for (ActivityWatch watch : resumed.values()) watch.refresh();
    }

    @Override public void onActivityResumed(Activity activity) {
        if (resumed.containsKey(activity)) return;
        ActivityWatch watch = new ActivityWatch(activity);
        resumed.put(activity, watch);
        watch.refresh();
    }

    @Override public void onActivityPaused(Activity activity) { stopWatching(activity); }
    @Override public void onActivityDestroyed(Activity activity) { stopWatching(activity); }
    @Override public void onActivityPreCreated(Activity activity, Bundle state) {
        installNavController(activity.getClass().getClassLoader());
    }

    @Override public void onActivityCreated(Activity activity, Bundle state) {
        installNavController(activity.getClass().getClassLoader());
    }
    @Override public void onActivityStarted(Activity activity) {}
    @Override public void onActivityStopped(Activity activity) {}
    @Override public void onActivitySaveInstanceState(Activity activity, Bundle state) {}

    private void stopWatching(Activity activity) {
        ActivityWatch watch = resumed.remove(activity);
        if (watch != null) watch.stop();
    }

    private final class ActivityWatch {
        final Activity activity;
        final View decor;
        FragmentAccess support;
        final Map<Object, FragmentState> states = new IdentityHashMap<>();
        final Rect screen = new Rect();
        final Rect bounds = new Rect();
        final Runnable sample = () -> {
            scheduledAt = Long.MAX_VALUE;
            sample();
        };
        final ViewTreeObserver.OnGlobalLayoutListener layout = this::onLayout;
        ViewTreeObserver observer;
        boolean observing;
        boolean initialized;
        long scheduledAt = Long.MAX_VALUE;
        String navFragment;
        long navFragmentUntil;

        ActivityWatch(Activity activity) {
            this.activity = activity;
            decor = activity.getWindow().getDecorView();
        }

        void refresh() {
            boolean capturing = enabled.test(NavigationCaptureSource.FRAGMENT);
            if (!capturing && !fragmentReplay.waitingFor(activity)) {
                stop();
                return;
            }
            if (capturing) fragmentReplay.install(activity);
            if (!observing) {
                observer = decor.getViewTreeObserver();
                if (!observer.isAlive()) return;
                ClassLoader loader = activity.getClass().getClassLoader();
                support = supportByLoader.get(loader);
                if (support == null) {
                    support = FragmentAccess.load(loader, "androidx.fragment.app", true);
                    if (support != null) supportByLoader.put(loader, support);
                }
                observing = true;
                observer.addOnGlobalLayoutListener(layout);
                sample();
            }
        }

        void stop() {
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(layout);
            ViewTreeObserver current = decor.getViewTreeObserver();
            if (observing && current != observer && current.isAlive()) {
                current.removeOnGlobalLayoutListener(layout);
            }
            observing = false;
            initialized = false;
            observer = null;
            states.clear();
            navFragment = null;
            navFragmentUntil = 0;
            scheduledAt = Long.MAX_VALUE;
            main.removeCallbacks(sample);
            fragmentReplay.forget(activity);
        }

        private void onLayout() {
            if (enabled.test(NavigationCaptureSource.FRAGMENT) || fragmentReplay.waitingFor(activity)) {
                scheduleSample(SystemClock.uptimeMillis() + LAYOUT_SAMPLE_MS);
            }
        }

        private void scheduleSample(long when) {
            if (when >= scheduledAt) return;
            main.removeCallbacks(sample);
            scheduledAt = when;
            main.postAtTime(sample, when);
        }

        void sample() {
            if (!observing) return;
            boolean capturing = enabled.test(NavigationCaptureSource.FRAGMENT);
            if (!capturing && !fragmentReplay.waitingFor(activity)) {
                stop();
                return;
            }
            if (!decor.getGlobalVisibleRect(screen)) {
                fragmentReplay.forget(activity);
                return;
            }
            try {
                long now = SystemClock.uptimeMillis();
                Frame frame = new Frame(now, !initialized || !capturing);
                inspectManager(support, frame);
                inspectManager(platform, frame);
                states.keySet().retainAll(frame.seen.keySet());
                initialized = capturing;
                if (frame.complete) {
                    fragmentReplay.observe(activity, frame.visible);
                } else {
                    fragmentReplay.forget(activity);
                }
                for (FragmentChange change : frame.changes) {
                    Bundle descriptor = frame.complete
                            ? fragmentReplay.capture(activity, change.destination, change.arguments) : null;
                    emit(activity, change.destination, NavigationCaptureSource.FRAGMENT, change.arguments,
                            descriptor);
                }
                scheduleSample(frame.nextSample);
                if (!capturing && !fragmentReplay.waitingFor(activity)) stop();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                fragmentReplay.forget(activity);
                report(error);
            }
        }

        private void inspectManager(FragmentAccess access, Frame frame) throws ReflectiveOperationException {
            if (access == null || !access.activityClass.isInstance(activity)) return;
            inspectFragments(access, access.manager.invoke(activity), frame, false, 0);
        }

        private void inspectFragments(FragmentAccess access, Object manager, Frame frame,
                boolean parentHidden, int depth) throws ReflectiveOperationException {
            if (manager == null) return;
            List<?> fragments = (List<?>) access.fragments.invoke(manager);
            if (fragments == null) return;
            if (depth == 8) {
                if (!fragments.isEmpty()) frame.complete = false;
                return;
            }
            for (Object fragment : fragments) {
                if (fragment == null) continue;
                if (frame.seen.size() >= MAX_FRAGMENTS) {
                    frame.complete = false;
                    return;
                }
                frame.seen.put(fragment, Boolean.TRUE);
                View view = (View) access.view.invoke(fragment);
                boolean hidden = parentHidden || Boolean.TRUE.equals(access.hidden.invoke(fragment));
                boolean shown = !hidden && isShown(view);
                boolean visible = shown && isOnScreen(view);
                FragmentState state = states.computeIfAbsent(fragment, unused -> new FragmentState());
                if (shown && !visible && !state.rechecked) {
                    frame.nextSample = Math.min(frame.nextSample, frame.now + TRANSITION_RECHECK_MS);
                    state.rechecked = true;
                } else if (!shown || visible) {
                    state.rechecked = false;
                }
                if (view != null && !hidden && access.childManager != null && access.host.invoke(fragment) != null) {
                    inspectFragments(access, access.childManager.invoke(fragment), frame, !shown, depth + 1);
                }
                if (access.navHost != null && access.navHost.isInstance(fragment)) continue;
                String destination = fragment.getClass().getName();
                Map<String, Object> arguments = null;
                if (visible) {
                    arguments = scalarArguments((Bundle) access.arguments.invoke(fragment));
                    frame.visible.computeIfAbsent(destination, unused -> new ArrayList<>()).add(arguments);
                }
                boolean fromNav = enabled.test(NavigationCaptureSource.NAV_CONTROLLER)
                        && destination.equals(navFragment) && frame.now < navFragmentUntil;
                state.update(frame, visible, arguments, fromNav, destination);
            }
        }

        private boolean isShown(View view) {
            if (view == null || !view.isAttachedToWindow() || !view.isShown()) return false;
            for (View current = view; current != null; ) {
                if (current.getAlpha() <= 0.05f) return false;
                ViewParent parent = current.getParent();
                current = parent instanceof View ? (View) parent : null;
            }
            return true;
        }

        private boolean isOnScreen(View view) {
            if (!view.getGlobalVisibleRect(bounds) || !bounds.intersect(screen)) return false;
            long total = (long) view.getWidth() * view.getHeight();
            long visible = (long) bounds.width() * bounds.height();
            return total > 0 && visible * 5 >= total * 3;
        }

        private final class FragmentState {
            Map<String, Object> lastArguments;
            boolean visible;
            boolean rechecked;
            long readyAt;

            void update(Frame frame, boolean nowVisible, Map<String, Object> args,
                    boolean fromNav, String destination) {
                boolean changed = nowVisible && (!visible || !args.equals(lastArguments));
                visible = nowVisible;
                lastArguments = args;
                if (!nowVisible || frame.baseline || fromNav) {
                    readyAt = 0;
                } else if (changed) {
                    readyAt = frame.now + SETTLE_MS;
                }
                if (readyAt == 0) return;
                if (frame.now < readyAt) {
                    frame.nextSample = Math.min(frame.nextSample, readyAt);
                    return;
                }
                readyAt = 0;
                frame.changes.add(new FragmentChange(destination, args));
            }
        }
    }

    private static final class FragmentChange {
        final String destination;
        final Map<String, Object> arguments;

        FragmentChange(String destination, Map<String, Object> arguments) {
            this.destination = destination;
            this.arguments = arguments;
        }
    }

    private static final class Frame {
        final Map<Object, Boolean> seen = new IdentityHashMap<>();
        final Map<String, List<Map<String, Object>>> visible = new LinkedHashMap<>();
        final List<FragmentChange> changes = new ArrayList<>();
        final long now;
        final boolean baseline;
        boolean complete = true;
        long nextSample = Long.MAX_VALUE;

        Frame(long now, boolean baseline) {
            this.now = now;
            this.baseline = baseline;
        }
    }

    private static final class FragmentAccess {
        final Class<?> activityClass;
        final Class<?> navHost;
        final Method manager;
        final Method fragments;
        final Method childManager;
        final Method host;
        final Method view;
        final Method arguments;
        final Method hidden;

        FragmentAccess(ClassLoader loader, String prefix, boolean support)
                throws ReflectiveOperationException {
            Class<?> fragment = Class.forName(prefix + ".Fragment", false, loader);
            activityClass = support ? Class.forName(prefix + ".FragmentActivity", false, loader) : Activity.class;
            manager = activityClass.getMethod(support ? "getSupportFragmentManager" : "getFragmentManager");
            fragments = manager.getReturnType().getMethod("getFragments");
            childManager = support ? fragment.getMethod("getChildFragmentManager") : null;
            host = support ? fragment.getMethod("getHost") : null;
            view = fragment.getMethod("getView");
            arguments = fragment.getMethod("getArguments");
            hidden = fragment.getMethod("isHidden");
            navHost = support ? optionalClass(loader, "androidx.navigation.NavHost") : null;
        }

        static FragmentAccess load(ClassLoader loader, String prefix, boolean support) {
            try {
                return new FragmentAccess(loader, prefix, support);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                return null;
            }
        }
    }

    private void installNavController(ClassLoader loader) {
        Class<?> controllerClass = optionalClass(loader, NAV_CONTROLLER);
        Class<?> listenerClass = optionalClass(loader, NAV_CONTROLLER + "$OnDestinationChangedListener");
        if (controllerClass == null || listenerClass == null) return;
        if (!navControllers.add(controllerClass)) return;
        try {
            Method addListener = controllerClass.getMethod("addOnDestinationChangedListener", listenerClass);
            Method backStackEntry = optionalMethod(controllerClass, "getCurrentBackStackEntry");
            try {
                jetpackReplay.install(controllerClass);
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                // Keep destination observation available on versions without these overloads.
            }
            XposedBridge.hookAllConstructors(controllerClass, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.hasThrowable() || param.args.length == 0 || !(param.args[0] instanceof Context)) return;
                    Activity activity = findActivity((Context) param.args[0]);
                    if (activity == null) return;
                    Object controller = param.thisObject;
                    main.post(() -> {
                        if (jetpackReplay.register(controller, activity)) {
                            observeNavController(controller, activity, listenerClass, addListener, backStackEntry);
                        }
                    });
                }
            });
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report(error);
        }
    }

    private void observeNavController(Object controller, Activity activity, Class<?> listenerClass,
            Method addListener, Method backStackEntry) {
        WeakReference<Activity> owner = new WeakReference<>(activity);
        try {
            NavListener listener = new NavListener(owner, backStackEntry);
            Object proxy = Proxy.newProxyInstance(listenerClass.getClassLoader(), new Class<?>[]{listenerClass},
                    (self, method, args) -> {
                        switch (method.getName()) {
                            case "onDestinationChanged":
                                if (listener.registered && Looper.myLooper() == Looper.getMainLooper()) {
                                    listener.changed(args[0], args[1], args[2]);
                                }
                                return null;
                            case "hashCode": return System.identityHashCode(self);
                            case "equals": return self == args[0];
                            case "toString": return InAppNavigationCapture.class.getSimpleName();
                            default: return null;
                        }
                    });
            addListener.invoke(controller, proxy);
            listener.registered = true; // Registration may immediately deliver the starting destination.
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            report(error);
        }
    }

    private final class NavListener {
        final WeakReference<Activity> owner;
        final Method backStackEntry;
        Object previousEntry;
        String previousDestination;
        Map<String, Object> previousArgs;
        boolean registered;

        NavListener(WeakReference<Activity> owner, Method backStackEntry) {
            this.owner = owner;
            this.backStackEntry = backStackEntry;
        }

        void changed(Object controller, Object destination, Object value) {
            Activity activity = owner.get();
            ActivityWatch watch = resumed.get(activity);
            if (watch == null || !enabled.test(NavigationCaptureSource.NAV_CONTROLLER) || destination == null) {
                previousEntry = null;
                previousDestination = null;
                previousArgs = null;
                jetpackReplay.observe(controller, destination, value, null);
                return;
            }
            try {
                Object entry = backStackEntry == null ? null : backStackEntry.invoke(controller);
                String route = stringProperty(destination, "getRoute");
                String label = stringProperty(destination, "getDisplayName");
                if (route == null) route = label;
                if (route == null) route = destination.getClass().getName();
                Map<String, Object> args = scalarArguments(value instanceof Bundle ? (Bundle) value : null);
                if (entry == previousEntry && route.equals(previousDestination) && args.equals(previousArgs)) {
                    jetpackReplay.observe(controller, destination, value, null);
                    return;
                }
                previousEntry = entry;
                previousDestination = route;
                previousArgs = args;
                String fragment = stringProperty(destination, "getClassName");
                if (fragment != null && fragment.startsWith(".")) fragment = activity.getPackageName() + fragment;
                watch.navFragment = fragment;
                watch.navFragmentUntil = SystemClock.uptimeMillis() + 800;
                String destinationLabel = route;
                jetpackReplay.observe(controller, destination, value,
                        descriptor -> emit(activity, destinationLabel, NavigationCaptureSource.NAV_CONTROLLER,
                                args, descriptor));
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                report(error);
            }
        }
    }

    private static Activity findActivity(Context context) {
        for (int i = 0; context != null && i < 12; i++) {
            if (context instanceof Activity) return (Activity) context;
            if (!(context instanceof ContextWrapper)) break;
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    private static Class<?> optionalClass(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
    }

    private static Method optionalMethod(Class<?> type, String name) {
        try {
            return type.getMethod(name);
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static String stringProperty(Object object, String name) throws ReflectiveOperationException {
        Method method = optionalMethod(object.getClass(), name);
        if (method == null) return null;
        Object value = method.invoke(object);
        return value instanceof CharSequence ? value.toString() : null;
    }

    private static Map<String, Object> scalarArguments(Bundle source) {
        if (source == null) return Collections.emptyMap();
        Map<String, Object> result = new LinkedHashMap<>();
        try {
            int visited = 0;
            for (String key : source.keySet()) {
                if (++visited > 64 || result.size() == 24) break;
                if (key == null || key.length() > 128) continue;
                Object value = source.get(key);
                if ((value instanceof String && ((String) value).length() <= 512)
                        || value instanceof Integer || value instanceof Long || value instanceof Boolean
                        || value instanceof Float || value instanceof Double) {
                    result.put(key, value);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Custom parcelables in arguments are optional for navigation diagnostics.
        }
        return result;
    }

    private void emit(Activity activity, String destination, NavigationCaptureSource source,
            Map<String, Object> args, Bundle replayDescriptor) {
        if (!enabled.test(source)) return;
        Bundle bundle = new Bundle();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            bundle.putSerializable(entry.getKey(), (java.io.Serializable) entry.getValue());
        }
        try {
            sink.capture(activity, destination, source, bundle, replayDescriptor);
        } catch (RuntimeException | LinkageError error) {
            report(error);
        }
    }

    private void report(Throwable error) {
        if (errorLogged) return;
        errorLogged = true;
        XposedBridge.log("JumpReplay navigation capture: " + error.getClass().getSimpleName());
    }
}
