package com.fourtwo.hookintent.xposed;

import android.app.Activity;
import android.app.Application;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import com.fourtwo.hookintent.data.NavigationCaptureSource;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import de.robv.android.xposed.XposedBridge;

/** Observe Fragment destinations and attach verified routes from their original hosts. */
public final class InAppNavigationCapture implements Application.ActivityLifecycleCallbacks {
    public interface Sink {
        void capture(Activity activity, String destination, NavigationCaptureSource source, Bundle arguments,
                Bundle replayDescriptor, Bundle replayDiagnostics);
    }

    static final long SOURCE_READY_MS = 6_500;
    static final long COLD_SOURCE_READY_MS = 15_000;
    private static final long LAYOUT_SAMPLE_MS = 40;
    private static final long SETTLE_MS = 120;
    private static final long TRANSITION_RECHECK_MS = 280;
    private static final long PAGE_WINDOW_MS = 3000;
    private static final int MAX_FRAGMENTS = 64;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Activity, ActivityWatch> resumed = new IdentityHashMap<>();
    private final Predicate<NavigationCaptureSource> enabled;
    private final Sink sink;
    private final FragmentHostReplay fragmentReplay;
    private final FragmentTransactionReplay transactionReplay;
    private final ViewPagerReplay pagerReplay;
    private final Map<ClassLoader, FragmentAccess> supportByLoader = new WeakHashMap<>();
    private FragmentAccess platform;
    private boolean errorLogged;

    public InAppNavigationCapture(Predicate<NavigationCaptureSource> enabled, Sink sink) {
        this.enabled = enabled;
        this.sink = sink;
        Predicate<Activity> capturingFragment = activity -> resumed.containsKey(activity)
                && enabled.test(NavigationCaptureSource.FRAGMENT);
        fragmentReplay = new FragmentHostReplay(main, capturingFragment, () -> resumed.keySet(),
                this::resampleForFragmentReplay);
        transactionReplay = new FragmentTransactionReplay(main, capturingFragment, () -> resumed.keySet(),
                this::resampleForFragmentReplay);
        pagerReplay = new ViewPagerReplay(main, capturingFragment, () -> resumed.keySet(), this::onPageSelected);
    }

    public void replay(Bundle descriptor, Bundle arguments, boolean waitForStartup, Consumer<Exception> result) {
        long sourceWait = waitForStartup ? COLD_SOURCE_READY_MS : SOURCE_READY_MS;
        if (ViewPagerReplay.handles(descriptor)) {
            pagerReplay.replay(descriptor, arguments, sourceWait, result);
        } else if (FragmentTransactionReplay.handles(descriptor)) {
            transactionReplay.replay(descriptor, arguments, sourceWait, result);
        } else if (FragmentHostReplay.handles(descriptor)) {
            fragmentReplay.replay(descriptor, arguments, sourceWait, result);
        } else {
            result.accept(new IllegalArgumentException("这条 Fragment 记录没有可重放的导航调用"));
        }
    }

    private void onPageSelected(Activity activity, View pager, Bundle descriptor) {
        ActivityWatch watch = resumed.get(activity);
        if (watch == null || !watch.observing || !enabled.test(NavigationCaptureSource.FRAGMENT)) return;
        long now = SystemClock.uptimeMillis();
        watch.pendingPages.put(pager, new PageSelection(descriptor, now + PAGE_WINDOW_MS));
        watch.scheduleSample(now + LAYOUT_SAMPLE_MS);
    }

    private boolean waitingForFragmentReplay(Activity activity) {
        return fragmentReplay.waitingFor(activity) || transactionReplay.waitingFor(activity);
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
    @Override public void onActivityCreated(Activity activity, Bundle state) {}
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
        final Map<View, PageSelection> pendingPages = new IdentityHashMap<>();
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
        boolean supportChecked;
        long scheduledAt = Long.MAX_VALUE;

        ActivityWatch(Activity activity) {
            this.activity = activity;
            decor = activity.getWindow().getDecorView();
        }

        void refresh() {
            boolean capturing = enabled.test(NavigationCaptureSource.FRAGMENT);
            if (!capturing && !waitingForFragmentReplay(activity)) {
                stop();
                return;
            }
            if (capturing) {
                fragmentReplay.install(activity);
                transactionReplay.install(activity.getClass().getClassLoader());
            } else {
                pendingPages.clear();
                pagerReplay.forget(activity);
            }
            if (!supportChecked) {
                supportChecked = true;
                ClassLoader loader = activity.getClass().getClassLoader();
                support = supportByLoader.get(loader);
                if (support == null) {
                    support = FragmentAccess.load(loader, "androidx.fragment.app", true);
                    if (support != null) supportByLoader.put(loader, support);
                }
            }
            if (observing) {
                if (capturing) scheduleSample(SystemClock.uptimeMillis());
                return;
            }
            observer = decor.getViewTreeObserver();
            if (!observer.isAlive()) return;
            observing = true;
            observer.addOnGlobalLayoutListener(layout);
            sample();
        }

        void stop() {
            if (observer != null && observer.isAlive()) observer.removeOnGlobalLayoutListener(layout);
            ViewTreeObserver current = decor.getViewTreeObserver();
            if (observing && current != observer && current.isAlive()) {
                current.removeOnGlobalLayoutListener(layout);
            }
            observing = false;
            initialized = false;
            supportChecked = false;
            observer = null;
            scheduledAt = Long.MAX_VALUE;
            main.removeCallbacks(sample);
            states.clear();
            forgetFragmentRoutes();
            pagerReplay.forget(activity);
        }

        private void forgetFragmentRoutes() {
            pendingPages.clear();
            fragmentReplay.forget(activity);
            transactionReplay.forget(activity);
        }

        private void onLayout() {
            if (enabled.test(NavigationCaptureSource.FRAGMENT) || waitingForFragmentReplay(activity)) {
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
            if (!capturing && !waitingForFragmentReplay(activity)) {
                stop();
                return;
            }
            if (!decor.getGlobalVisibleRect(screen)) {
                forgetFragmentRoutes();
                return;
            }
            try {
                if (capturing) pagerReplay.scan(activity);
                long now = SystemClock.uptimeMillis();
                Frame frame = new Frame(now, !initialized || !capturing);
                inspectManager(support, frame);
                inspectManager(platform, frame);
                states.keySet().retainAll(frame.seen.keySet());
                initialized = capturing;
                if (frame.incompleteReason == null) {
                    fragmentReplay.observe(activity, frame.visible);
                    transactionReplay.observe(activity, frame.visibleInstances);
                } else {
                    forgetFragmentRoutes();
                }
                Map<FragmentChange, PageRoute> pageRoutes = associatePages(frame);
                for (FragmentChange change : frame.changes) {
                    Bundle descriptor = null;
                    Bundle diagnostics = new Bundle();
                    if (frame.incompleteReason != null) {
                        diagnostics.putString("observation", frame.incompleteReason);
                    } else {
                        Bundle transaction = transactionReplay.capture(activity, change.fragment);
                        PageRoute pageRoute = pageRoutes.get(change);
                        descriptor = pageRoute == null ? transaction : pageRoute.descriptor;
                        if (descriptor == null) {
                            diagnostics.putString("transaction", transactionReplay.captureFailure(activity, change.fragment));
                            descriptor = fragmentReplay.capture(activity, change.destination, change.arguments);
                            if (descriptor == null) {
                                diagnostics.putString("host", fragmentReplay.captureFailure(activity, change.destination));
                            }
                        }
                    }
                    emit(activity, change.destination, NavigationCaptureSource.FRAGMENT, change.arguments,
                            descriptor, descriptor == null ? diagnostics : null);
                }
                scheduleSample(frame.nextSample);
                if (!capturing && !waitingForFragmentReplay(activity)) stop();
            } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
                forgetFragmentRoutes();
                report(error);
            }
        }

        private Map<FragmentChange, PageRoute> associatePages(Frame frame) {
            if (pendingPages.isEmpty()) return Collections.emptyMap();
            Map<FragmentChange, PageRoute> routes = new IdentityHashMap<>();
            Iterator<Map.Entry<View, PageSelection>> pending = pendingPages.entrySet().iterator();
            while (pending.hasNext()) {
                Map.Entry<View, PageSelection> entry = pending.next();
                View pager = entry.getKey();
                PageSelection selection = entry.getValue();
                if (frame.now >= selection.expiresAt || !pagerReplay.matchesSelection(activity, pager, selection.descriptor)) {
                    pending.remove();
                    continue;
                }
                FragmentChange target = null;
                int nearest = Integer.MAX_VALUE;
                for (FragmentChange change : frame.changes) {
                    if (!change.appeared) continue;
                    int distance = distanceFrom(pager, change.view);
                    if (distance == nearest) target = null;
                    else if (distance < nearest) {
                        target = change;
                        nearest = distance;
                    }
                }
                if (target == null) {
                    frame.nextSample = Math.min(frame.nextSample, frame.now + SETTLE_MS);
                    continue;
                }
                PageRoute prior = routes.get(target);
                if (prior == null || nearest < prior.distance) {
                    routes.put(target, new PageRoute(selection.descriptor, nearest));
                }
                pending.remove();
            }
            return routes;
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
                if (!fragments.isEmpty() && frame.incompleteReason == null) {
                    frame.incompleteReason = "observation_depth_limit";
                }
                return;
            }
            for (Object fragment : fragments) {
                if (fragment == null) continue;
                if (frame.seen.size() >= MAX_FRAGMENTS) {
                    if (frame.incompleteReason == null) frame.incompleteReason = "observation_count_limit";
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
                    frame.visibleInstances.add(fragment);
                }
                state.update(frame, visible, arguments, fragment, view, destination);
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
            boolean appeared;
            long readyAt;

            void update(Frame frame, boolean nowVisible, Map<String, Object> args,
                    Object fragment, View view, String destination) {
                boolean wasVisible = visible;
                boolean changed = nowVisible && (!wasVisible || !args.equals(lastArguments));
                visible = nowVisible;
                lastArguments = args;
                if (!nowVisible || frame.baseline) {
                    readyAt = 0;
                    appeared = false;
                } else if (changed) {
                    appeared |= !wasVisible;
                    readyAt = frame.now + SETTLE_MS;
                }
                if (readyAt == 0) return;
                if (frame.now < readyAt) {
                    frame.nextSample = Math.min(frame.nextSample, readyAt);
                    return;
                }
                readyAt = 0;
                frame.changes.add(new FragmentChange(fragment, view, destination, args, appeared));
                appeared = false;
            }
        }
    }

    private static int distanceFrom(View pager, View view) {
        int distance = 1;
        for (ViewParent ancestor = view.getParent(); ancestor != null; ancestor = ancestor.getParent(), distance++) {
            if (ancestor == pager) return distance;
        }
        return Integer.MAX_VALUE;
    }

    private static final class PageSelection {
        final Bundle descriptor;
        final long expiresAt;

        PageSelection(Bundle descriptor, long expiresAt) {
            this.descriptor = descriptor;
            this.expiresAt = expiresAt;
        }
    }

    private static final class PageRoute {
        final Bundle descriptor;
        final int distance;

        PageRoute(Bundle descriptor, int distance) {
            this.descriptor = descriptor;
            this.distance = distance;
        }
    }

    private static final class FragmentChange {
        final Object fragment;
        final View view;
        final String destination;
        final Map<String, Object> arguments;
        final boolean appeared;

        FragmentChange(Object fragment, View view, String destination, Map<String, Object> arguments, boolean appeared) {
            this.fragment = fragment;
            this.view = view;
            this.destination = destination;
            this.arguments = arguments;
            this.appeared = appeared;
        }
    }

    private static final class Frame {
        final Map<Object, Boolean> seen = new IdentityHashMap<>();
        final Map<String, List<Map<String, Object>>> visible = new LinkedHashMap<>();
        final Set<Object> visibleInstances = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<FragmentChange> changes = new ArrayList<>();
        final long now;
        final boolean baseline;
        String incompleteReason;
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

    private static Class<?> optionalClass(ClassLoader loader, String name) {
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException | LinkageError ignored) {
            return null;
        }
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
                if (value == null || (value instanceof String && ((String) value).length() <= 512)
                        || value instanceof Integer || value instanceof Long || value instanceof Boolean
                        || value instanceof Short || value instanceof Byte
                        || (value instanceof Float && Float.isFinite((Float) value))
                        || (value instanceof Double && Double.isFinite((Double) value))) {
                    result.put(key, value);
                }
            }
        } catch (RuntimeException | LinkageError ignored) {
            // Custom parcelables in arguments are optional for navigation diagnostics.
        }
        return result;
    }

    private void emit(Activity activity, String destination, NavigationCaptureSource source,
            Map<String, Object> args, Bundle replayDescriptor, Bundle replayDiagnostics) {
        if (!enabled.test(source)) return;
        Bundle bundle = new Bundle();
        for (Map.Entry<String, Object> entry : args.entrySet()) {
            bundle.putSerializable(entry.getKey(), (java.io.Serializable) entry.getValue());
        }
        try {
            sink.capture(activity, destination, source, bundle, replayDescriptor, replayDiagnostics);
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
