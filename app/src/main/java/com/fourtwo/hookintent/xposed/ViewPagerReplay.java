package com.fourtwo.hookintent.xposed;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Observes explicit pager selections that may supply a Fragment replay action. */
final class ViewPagerReplay {
    interface Sink {
        void capture(Activity activity, View pager, Bundle descriptor);
    }

    static final String POSITION = "position";
    private static final String MECHANISM = "view_pager";
    private static final String METHOD = "setCurrentItem";
    private static final String CALL = "hookIntentViewPagerCall";
    private static final int MAX_VIEWS = 4096;
    private static final int MAX_PATH = 8192;
    private static final long SOURCE_WAIT_MS = 6500;
    private static final long RESULT_WAIT_MS = 2500;
    private static final long POLL_MS = 100;

    private final Handler main;
    private final Predicate<Activity> capturing;
    private final Supplier<? extends Collection<Activity>> resumed;
    private final Sink sink;
    private final Map<Class<?>, Access> accessors = new HashMap<>();
    private final Map<Class<?>, Method> counters = new HashMap<>();
    private final Set<Method> hooked = new HashSet<>();
    private final Map<View, Activity> watched = new WeakHashMap<>();
    private final Set<View> activeCalls = java.util.Collections.newSetFromMap(new WeakHashMap<>());
    private final Runnable poll = this::tick;
    private Request request;

    ViewPagerReplay(Handler main, Predicate<Activity> capturing,
                    Supplier<? extends Collection<Activity>> resumed, Sink sink) {
        this.main = main;
        this.capturing = capturing;
        this.resumed = resumed;
        this.sink = sink;
    }

    void scan(Activity activity) {
        if (!capturing.test(activity)) return;
        for (View view : pagerViews(activity, null)) {
            if (watched.put(view, activity) != activity) {
                install(view.getClass());
            }
        }
    }

    void forget(Activity activity) {
        watched.values().removeIf(owner -> owner == activity);
    }

    static boolean handles(Bundle descriptor) {
        return descriptor != null && MECHANISM.equals(descriptor.getString("mechanism"));
    }

    boolean matchesSelection(Activity activity, View pager, Bundle descriptor) {
        if (!active(activity) || !capturing.test(activity) || pager == null
                || watched.get(pager) != activity || !visibleIn(activity, pager) || !handles(descriptor)
                || !activity.getClass().getName().equals(descriptor.getString("host"))
                || !pager.getClass().getName().equals(descriptor.getString("pager"))) return false;
        try {
            int id = descriptor.getInt("view_id", View.NO_ID);
            if (id > 0) {
                if (pager.getId() != id) return false;
            } else {
                String path = descriptor.getString("view_path");
                if (path == null || !path.equals(pathTo(decor(activity), pager))) return false;
            }
            Bundle arguments = descriptor.getBundle("arguments");
            State actual = state(pager, access(pager.getClass()));
            return arguments != null && actual != null
                    && actual.position == arguments.getInt(POSITION, -1)
                    && actual.adapter.equals(descriptor.getString("adapter"))
                    && actual.count == descriptor.getInt("page_count");
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private void install(Class<?> type) {
        for (Class<?> owner = type; owner != null && owner != ViewGroup.class; owner = owner.getSuperclass()) {
            try {
                hook(owner.getDeclaredMethod(METHOD, int.class));
            } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
                // The setter may be inherited or exposed only as the two-argument overload.
            }
            try {
                hook(owner.getDeclaredMethod(METHOD, int.class, boolean.class));
            } catch (NoSuchMethodException | RuntimeException | LinkageError ignored) {
                // The setter may be inherited or exposed only as the one-argument overload.
            }
        }
    }

    private void hook(Method method) {
        int flags = method.getModifiers();
        if (method.getReturnType() != void.class || Modifier.isAbstract(flags)
                || Modifier.isNative(flags) || Modifier.isStatic(flags) || method.isSynthetic()
                || !Modifier.isPublic(flags) || hooked.contains(method)) return;
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Looper.myLooper() != Looper.getMainLooper() || request != null) return;
                View view = (View) param.thisObject;
                Activity activity = watched.get(view);
                if (!active(activity) || !capturing.test(activity) || !visibleIn(activity, view)
                        || !activeCalls.add(view)) return;
                try {
                    Access access = access(view.getClass());
                    State current = state(view, access);
                    int target = (Integer) param.args[0];
                    Boolean smooth = param.args.length == 2 ? (Boolean) param.args[1] : null;
                    if (current != null && current.position != target && current.contains(target)
                            && method.equals(smooth == null ? access.simple : access.animated)) {
                        param.setObjectExtra(CALL, new Selection(activity, view, current, target, smooth, method));
                        return;
                    }
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // A broken or inactive adapter cannot supply a replayable selection.
                }
                activeCalls.remove(view);
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Selection selection = (Selection) param.getObjectExtra(CALL);
                if (selection == null) return;
                activeCalls.remove(selection.view);
                if (!param.hasThrowable()) offerSelection(selection);
            }
        });
        hooked.add(method);
    }

    private void offerSelection(Selection selection) {
        Activity activity = selection.activity;
        View view = selection.view;
        if (!active(activity) || !capturing.test(activity) || !visibleIn(activity, view)) return;
        try {
            State actual = state(view, access(view.getClass()));
            if (actual == null || actual.position != selection.position
                    || !selection.before.adapter.equals(actual.adapter) || selection.before.count != actual.count) return;
            Bundle descriptor = describe(selection);
            if (descriptor != null) sink.capture(activity, view, descriptor);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // A selection whose outcome or host cannot be verified cannot supply a replay action.
        }
    }

    private Bundle describe(Selection selection) {
        Activity activity = selection.activity;
        View view = selection.view;
        String type = view.getClass().getName();
        int id = view.getId();
        String path = null;
        if (id <= 0 || !uniqueId(activity, type, id)) {
            id = View.NO_ID;
            path = pathTo(decor(activity), view);
            if (path == null) return null;
        }
        Bundle arguments = new Bundle();
        arguments.putInt(POSITION, selection.position);
        Bundle descriptor = new Bundle();
        descriptor.putString("mechanism", MECHANISM);
        descriptor.putString("method", METHOD);
        descriptor.putString("setter_owner", selection.setter.getDeclaringClass().getName());
        descriptor.putString("host", activity.getClass().getName());
        descriptor.putString("pager", type);
        descriptor.putString("adapter", selection.before.adapter);
        descriptor.putInt("view_id", id);
        if (path != null) descriptor.putString("view_path", path);
        descriptor.putInt("source_position", selection.before.position);
        descriptor.putInt("page_count", selection.before.count);
        if (selection.smooth != null) descriptor.putBoolean("smooth_scroll", selection.smooth);
        descriptor.putBundle("arguments", arguments);
        return descriptor;
    }

    private boolean uniqueId(Activity activity, String type, int id) {
        int count = 0;
        for (View candidate : pagerViews(activity, type)) {
            if (candidate.getId() == id && ++count > 1) return false;
        }
        return count == 1;
    }

    void replay(Bundle descriptor, Bundle arguments, Consumer<Exception> complete) {
        main.post(() -> {
            if (request != null) {
                complete.accept(new IllegalStateException("已有 ViewPager 重放正在进行"));
                return;
            }
            try {
                request = new Request(descriptor, arguments, complete);
            } catch (RuntimeException error) {
                complete.accept(new IllegalArgumentException("ViewPager 重放参数无效", error));
                return;
            }
            tick();
        });
    }

    private void tick() {
        Request pending = request;
        if (pending == null) return;
        try {
            if (pending.view == null) {
                if (SystemClock.uptimeMillis() >= pending.deadline) {
                    String detail = pending.adapterMismatch ? "原 ViewPager 的适配器或页数已变化，请重新捕获"
                            : "找不到捕获时的 ViewPager 或其适配器";
                    finish(pending, new IllegalStateException(detail));
                    return;
                }
                beginReplay(pending);
            }
            if (pending.view != null) {
                State actual = state(pending.view, access(pending.view.getClass()));
                if (actual != null && active(pending.activity) && visibleIn(pending.activity, pending.view)
                        && pending.adapter.equals(actual.adapter) && actual.count == pending.count
                        && actual.position == pending.position) {
                    finish(pending, null);
                } else if (SystemClock.uptimeMillis() >= pending.deadline) {
                    finish(pending, new IllegalStateException("ViewPager 未显示修改后的索引"));
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError error) {
            finish(pending, new IllegalStateException("无法调用捕获的 ViewPager 切换方法", error));
        }
        if (request == pending) main.postDelayed(poll, POLL_MS);
    }

    private void beginReplay(Request pending) throws ReflectiveOperationException {
        Activity owner = null;
        View selected = null;
        State state = null;
        for (Activity candidate : new ArrayList<>(resumed.get())) {
            if (!active(candidate) || !pending.host.equals(candidate.getClass().getName())) continue;
            View view = locate(candidate, pending);
            if (view == null || !visibleIn(candidate, view)) continue;
            State candidateState = state(view, access(view.getClass()));
            if (candidateState == null) continue;
            if (!pending.adapter.equals(candidateState.adapter) || candidateState.count != pending.count) {
                pending.adapterMismatch = true;
                continue;
            }
            if (selected != null) throw new IllegalStateException("找到多个匹配的 ViewPager 宿主");
            owner = candidate;
            selected = view;
            state = candidateState;
        }
        if (selected == null) return;
        if (!state.contains(pending.position)) {
            finish(pending, new IllegalArgumentException("页签索引超出当前范围 0–" + (state.count - 1)));
            return;
        }
        if (state.position == pending.position) {
            finish(pending, new IllegalStateException("ViewPager 已位于索引 " + pending.position));
            return;
        }
        Access access = access(selected.getClass());
        Method setter = pending.smooth == null ? access.simple : access.animated;
        if (setter == null || !pending.setterOwner.equals(setter.getDeclaringClass().getName())) {
            throw new NoSuchMethodException(METHOD);
        }
        pending.activity = owner;
        pending.view = selected;
        pending.deadline = SystemClock.uptimeMillis() + RESULT_WAIT_MS;
        if (pending.smooth == null) setter.invoke(selected, pending.position);
        else setter.invoke(selected, pending.position, pending.smooth);
    }

    private void finish(Request pending, Exception error) {
        if (request != pending) return;
        request = null;
        main.removeCallbacks(poll);
        pending.complete.accept(error);
    }

    private View locate(Activity owner, Request pending) {
        View found = null;
        for (View view : pagerViews(owner, pending.pagerType)) {
            boolean matches = pending.id > 0 ? view.getId() == pending.id
                    : pending.path.equals(pathTo(decor(owner), view));
            if (!matches) continue;
            if (found != null) throw new IllegalStateException("页面中存在多个同身份的 ViewPager");
            found = view;
        }
        return found;
    }

    private List<View> pagerViews(Activity activity, String type) {
        List<View> found = new ArrayList<>();
        View root = decor(activity);
        if (!(root instanceof ViewGroup)) return found;
        ArrayDeque<ViewGroup> queue = new ArrayDeque<>();
        queue.add((ViewGroup) root);
        int visited = 0;
        while (!queue.isEmpty()) {
            if (++visited > MAX_VIEWS) return java.util.Collections.emptyList();
            ViewGroup group = queue.removeFirst();
            Class<?> groupType = group.getClass();
            if ((type == null || groupType.getName().equals(type)) && access(groupType) != null) found.add(group);
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);
                if (child instanceof ViewGroup) queue.addLast((ViewGroup) child);
            }
        }
        return found;
    }

    private Access access(Class<?> type) {
        if (!accessors.containsKey(type)) {
            Access item = null;
            try {
                Method current = publicMethod(type, "getCurrentItem");
                Method adapter = publicMethod(type, "getAdapter");
                Method simple = publicMethod(type, METHOD, int.class);
                Method animated = publicMethod(type, METHOD, int.class, boolean.class);
                if (current != null && current.getReturnType() == int.class && adapter != null
                        && !adapter.getReturnType().isPrimitive() && (simple != null || animated != null)) {
                    item = new Access(current, adapter, simple, animated);
                }
            } catch (RuntimeException | LinkageError ignored) {
                // Optional discovery must not affect layout for unrelated views.
            }
            accessors.put(type, item);
        }
        return accessors.get(type);
    }

    private State state(View pager, Access access) throws ReflectiveOperationException {
        if (access == null) return null;
        Object adapter = access.adapter.invoke(pager);
        if (adapter == null) return null;
        Class<?> type = adapter.getClass();
        if (!counters.containsKey(type)) {
            Method counter = publicMethod(type, "getCount");
            if (counter == null || counter.getReturnType() != int.class) counter = publicMethod(type, "getItemCount");
            counters.put(type, counter != null && counter.getReturnType() == int.class ? counter : null);
        }
        Method counter = counters.get(type);
        if (counter == null) return null;
        int count = (Integer) counter.invoke(adapter);
        int position = (Integer) access.current.invoke(pager);
        return count > 0 && position >= 0 && position < count ? new State(type.getName(), count, position) : null;
    }

    private static Method publicMethod(Class<?> type, String name, Class<?>... arguments) {
        try {
            Method method = type.getMethod(name, arguments);
            if (Modifier.isStatic(method.getModifiers()) || (METHOD.equals(name) && method.getReturnType() != void.class)) {
                return null;
            }
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ignored) {
            return null;
        }
    }

    private static boolean active(Activity activity) {
        return activity != null && !activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus();
    }

    private static View decor(Activity activity) {
        return activity.getWindow() == null ? null : activity.getWindow().peekDecorView();
    }

    private static boolean visibleIn(Activity activity, View pager) {
        View root = decor(activity);
        return root != null && pager.isAttachedToWindow() && pager.getRootView() == root && pager.isShown()
                && pager.getWidth() > 0 && pager.getHeight() > 0;
    }

    private static String pathTo(View root, View view) {
        ArrayDeque<String> path = new ArrayDeque<>();
        while (view != root) {
            ViewParent parent = view.getParent();
            if (!(parent instanceof ViewGroup)) return null;
            ViewGroup group = (ViewGroup) parent;
            int index = group.indexOfChild(view);
            if (index < 0) return null;
            path.addFirst(index + ":" + view.getClass().getName() + ":" + view.getId());
            view = group;
        }
        String identity = android.text.TextUtils.join("/", path);
        return identity.length() <= MAX_PATH ? identity : null;
    }

    private static final class Access {
        final Method current;
        final Method adapter;
        final Method simple;
        final Method animated;

        Access(Method current, Method adapter, Method simple, Method animated) {
            this.current = current;
            this.adapter = adapter;
            this.simple = simple;
            this.animated = animated;
        }
    }

    private static final class State {
        final String adapter;
        final int count;
        final int position;

        State(String adapter, int count, int position) {
            this.adapter = adapter;
            this.count = count;
            this.position = position;
        }

        boolean contains(int target) { return target >= 0 && target < count; }
    }

    private static final class Selection {
        final Activity activity;
        final View view;
        final State before;
        final int position;
        final Boolean smooth;
        final Method setter;

        Selection(Activity activity, View view, State before, int position, Boolean smooth, Method setter) {
            this.activity = activity;
            this.view = view;
            this.before = before;
            this.position = position;
            this.smooth = smooth;
            this.setter = setter;
        }
    }

    private static final class Request {
        final String host;
        final String pagerType;
        final String adapter;
        final String setterOwner;
        final int id;
        final String path;
        final int count;
        final int position;
        final Boolean smooth;
        final Consumer<Exception> complete;
        long deadline = SystemClock.uptimeMillis() + SOURCE_WAIT_MS;
        boolean adapterMismatch;
        Activity activity;
        View view;

        Request(Bundle descriptor, Bundle arguments, Consumer<Exception> complete) {
            host = descriptor.getString("host");
            pagerType = descriptor.getString("pager");
            adapter = descriptor.getString("adapter");
            setterOwner = descriptor.getString("setter_owner");
            id = descriptor.getInt("view_id", View.NO_ID);
            path = descriptor.getString("view_path");
            count = descriptor.getInt("page_count", 0);
            int source = descriptor.getInt("source_position", -1);
            if (!handles(descriptor) || !METHOD.equals(descriptor.getString("method")) || empty(host)
                    || empty(pagerType) || empty(adapter) || empty(setterOwner)
                    || (id <= 0 && (path == null || path.length() > MAX_PATH))
                    || source < 0 || source >= count || arguments == null || arguments.size() != 1
                    || !(arguments.get(POSITION) instanceof Integer)) {
                throw new IllegalArgumentException("Invalid pager record");
            }
            position = arguments.getInt(POSITION);
            smooth = descriptor.containsKey("smooth_scroll") ? descriptor.getBoolean("smooth_scroll") : null;
            this.complete = complete;
        }

        private static boolean empty(String value) { return value == null || value.isEmpty(); }
    }
}
