package com.fourtwo.hookintent.xposed;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;

import org.json.JSONArray;
import org.json.JSONException;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
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

/** Reconstructs a directly observed, single-operation FragmentManager transaction. */
final class FragmentTransactionReplay {
    private static final String MECHANISM = "mechanism";
    private static final String TRANSACTION = "fragment_transaction";
    private static final String ARGUMENTS = "arguments";
    private static final String ARGUMENT_TYPES = "argument_types";
    private static final String[] COMMITS = {
            "commit", "commitNow", "commitAllowingStateLoss", "commitNowAllowingStateLoss"
    };
    private static final String[] ANIMATIONS = {"enter", "exit", "popEnter", "popExit"};
    private static final int ADD = 1;
    private static final int REPLACE = 2;
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ARGUMENTS = 64;
    private static final int MAX_FIELD = 8192;
    private static final long CAPTURE_MS = 9000;
    private static final long SOURCE_MS = 6500;
    private static final long RESULT_MS = 8500;

    private final Handler main;
    private final Predicate<Activity> capturing;
    private final Supplier<? extends Collection<Activity>> resumed;
    private final Consumer<Activity> resample;
    private final Map<Class<?>, Api> installed = new HashMap<>();
    private final Set<ClassLoader> scanned = new HashSet<>();
    private final Map<Object, Recorded> records = new WeakHashMap<>();
    private final Runnable poll = this::tick;
    private Request request;

    FragmentTransactionReplay(Handler main, Predicate<Activity> capturing,
            Supplier<? extends Collection<Activity>> resumed, Consumer<Activity> resample) {
        this.main = main;
        this.capturing = capturing;
        this.resumed = resumed;
        this.resample = resample;
    }

    void install(ClassLoader loader) {
        if (!scanned.add(loader)) return;
        install(loader, "androidx.fragment.app", true);
        install(loader, "android.app", false);
    }

    private void install(ClassLoader loader, String framework, boolean support) {
        try {
            Api api = new Api(loader, framework, support);
            if (installed.putIfAbsent(api.record, api) != null) return;
            for (String commit : COMMITS) {
                XposedBridge.hookMethod(declared(api.record, commit), new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (Looper.myLooper() != Looper.getMainLooper() || request != null
                                || resumed.get().stream().noneMatch(capturing)) return;
                        try {
                            param.setObjectExtra(TRANSACTION, snapshot(api, param.thisObject, commit));
                        } catch (ReflectiveOperationException | JSONException | RuntimeException | LinkageError ignored) {
                            // Visibility capture remains available for transactions we cannot reconstruct.
                        }
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Recording recording = (Recording) param.getObjectExtra(TRANSACTION);
                        if (recording == null || param.hasThrowable()) return;
                        Recorded prior = records.get(recording.fragment);
                        records.put(recording.fragment,
                                new Recorded(api, recording.activity, prior == null ? recording.descriptor : null));
                    }
                });
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The target may omit this Fragment library or ship a different internal layout.
        }
    }

    private Recording snapshot(Api api, Object transaction, String commit)
            throws ReflectiveOperationException, JSONException {
        Object manager = api.recordManager.get(transaction);
        List<?> ops = (List<?>) api.operations.get(transaction);
        if (manager == null || ops == null || ops.size() != 1 || api.hasCustomState(transaction)) return null;
        Object op = ops.get(0);
        int command = api.command.getInt(op);
        if (command != ADD && command != REPLACE) return null;
        Object fragment = api.destination.get(op);
        if (!api.fragment.isInstance(fragment) || Boolean.TRUE.equals(api.isAdded.invoke(fragment))) return null;
        int container = api.container.getInt(fragment);
        if (container <= 0) return null;

        ArrayList<String> parents = new ArrayList<>();
        Object root = manager;
        Object parent = api.parent.get(root);
        while (parent != null) {
            if (parents.size() == MAX_DEPTH || !isAttached(api.view.invoke(parent))) return null;
            parents.add(api.identity(parent));
            root = api.fragmentManager.invoke(parent);
            if (root == null) return null;
            parent = api.parent.get(root);
        }
        Collections.reverse(parents);
        Activity owner = null;
        for (Activity activity : new ArrayList<>(resumed.get())) {
            if (api.rootFor(activity) == root && active(activity) && capturing.test(activity)) {
                if (owner != null) return null;
                owner = activity;
            }
        }
        if (owner == null || !isAttached(api.containerView(owner, manager, container))) return null;
        JSONArray path = new JSONArray(parents);
        String source = api.slot(manager, container);
        if (!field(source) || !field(path.toString())) return null;

        Bundle original = (Bundle) api.getArguments.invoke(fragment);
        Bundle arguments = original == null ? new Bundle() : new Bundle(original);
        Bundle types = schema(arguments);
        if (types == null) return null;

        Bundle descriptor = new Bundle();
        descriptor.putString(MECHANISM, TRANSACTION);
        descriptor.putString("family", api.family);
        descriptor.putString("host", owner.getClass().getName());
        descriptor.putString("destination", fragment.getClass().getName());
        descriptor.putString("parents", path.toString());
        descriptor.putString("source", source);
        descriptor.putString("method", commit);
        descriptor.putInt("container", container);
        descriptor.putString("tag", (String) api.tag.invoke(fragment));
        descriptor.putInt("operation", command);
        descriptor.putBoolean("back_stack", api.addToBackStack.getBoolean(transaction));
        descriptor.putString("back_stack_name", (String) api.name.get(transaction));
        descriptor.putInt("transition", api.transition.getInt(transaction));
        descriptor.putInt("style", api.transitionStyle == null ? 0 : api.transitionStyle.getInt(transaction));
        descriptor.putBoolean("reorder", api.reordering != null && api.reordering.getBoolean(transaction));
        descriptor.putBoolean("null_args", original == null);
        for (int i = 0; i < ANIMATIONS.length; i++) {
            descriptor.putInt(ANIMATIONS[i], api.animation[i].getInt(op));
        }
        descriptor.putBundle(ARGUMENTS, arguments);
        descriptor.putBundle(ARGUMENT_TYPES, types);
        return new Recording(owner, fragment, descriptor);
    }

    void forget(Activity activity) {
        records.values().removeIf(record -> record.activity.get() == activity);
    }

    Bundle capture(Activity activity, Object fragment) {
        Recorded record = records.remove(fragment);
        if (record == null || record.activity.get() != activity || record.descriptor == null
                || SystemClock.uptimeMillis() - record.start > CAPTURE_MS) return null;
        try {
            Bundle actual = (Bundle) record.api.getArguments.invoke(fragment);
            // The collector's display snapshot deliberately omits some supported primitive values.
            return contains(actual, record.descriptor.getBundle(ARGUMENTS)) ? record.descriptor : null;
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            return null;
        }
    }

    static boolean handles(Bundle descriptor) {
        return descriptor != null && TRANSACTION.equals(descriptor.getString(MECHANISM));
    }

    boolean waitingFor(Activity activity) {
        Request pending = request;
        return pending != null && pending.host.equals(activity.getClass().getName())
                && (pending.activity == null || pending.activity == activity);
    }

    void observe(Activity activity, Set<Object> visibleFragments) {
        Request pending = request;
        if (pending == null || pending.activity != activity || !active(activity)
                || !visibleFragments.contains(pending.fragment)) return;
        try {
            Bundle actual = (Bundle) pending.api.getArguments.invoke(pending.fragment);
            if (contains(actual, pending.arguments)) finish(pending, null);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // Continue waiting for a readable visible destination.
        }
    }

    void replay(Bundle descriptor, Bundle edited, Consumer<Exception> complete) {
        main.post(() -> {
            if (request != null) {
                complete.accept(new IllegalStateException("已有 Fragment 事务正在重放"));
                return;
            }
            try {
                request = new Request(descriptor, edited, complete);
            } catch (JSONException | RuntimeException invalid) {
                complete.accept(new IllegalArgumentException("Fragment 事务参数无效", invalid));
                return;
            }
            tick();
        });
    }

    private void tick() {
        Request pending = request;
        if (pending == null) return;
        try {
            if (pending.activity == null) tryReplay(pending);
            else resample.accept(pending.activity);
        } catch (ReflectiveOperationException | JSONException | RuntimeException | LinkageError failure) {
            finish(pending, new IllegalStateException("无法重放记录中的 Fragment 事务", failure));
        }
        if (request != pending) return;
        if (SystemClock.uptimeMillis() >= pending.deadline) {
            finish(pending, new IllegalStateException(pending.activity == null
                    ? "请返回捕获时的来源页面后再重放" : "未观察到带有修改参数的目标 Fragment"));
            return;
        }
        main.postDelayed(poll, 100);
    }

    private void tryReplay(Request pending) throws ReflectiveOperationException, JSONException {
        Activity target = null;
        Api api = null;
        Object manager = null;
        for (Activity activity : new ArrayList<>(resumed.get())) {
            if (!waitingFor(activity) || !active(activity)) continue;
            install(activity.getClass().getClassLoader());
            for (Api candidate : installed.values()) {
                if (!candidate.family.equals(pending.family)) continue;
                Object found = candidate.managerAt(activity, pending.parents);
                if (found == null || Boolean.TRUE.equals(candidate.stateSaved.invoke(found))
                        || !isAttached(candidate.containerView(activity, found, pending.container))
                        || !pending.source.equals(candidate.slot(found, pending.container))) continue;
                if (target != null) throw new IllegalStateException("有多个匹配的 Fragment 宿主");
                target = activity;
                api = candidate;
                manager = found;
            }
        }
        if (target == null) return;

        Object destination = api.create(manager, target, pending.descriptor.getString("destination"));
        if (!pending.descriptor.getBoolean("null_args")) api.setArguments.invoke(destination, pending.arguments);
        Object transaction = api.begin.invoke(manager);
        Bundle descriptor = pending.descriptor;
        api.animations.invoke(transaction, descriptor.getInt("enter"), descriptor.getInt("exit"),
                descriptor.getInt("popEnter"), descriptor.getInt("popExit"));
        api.setTransition.invoke(transaction, descriptor.getInt("transition"));
        if (api.setTransitionStyle != null) api.setTransitionStyle.invoke(transaction, descriptor.getInt("style"));
        if (api.setReordering != null) api.setReordering.invoke(transaction, descriptor.getBoolean("reorder"));
        Method operation = pending.operation == ADD ? api.add : api.replace;
        operation.invoke(transaction, pending.container, destination, descriptor.getString("tag"));
        if (descriptor.getBoolean("back_stack")) {
            api.addBackStack.invoke(transaction, descriptor.getString("back_stack_name"));
        }

        pending.api = api;
        pending.activity = target;
        pending.fragment = destination;
        pending.deadline = SystemClock.uptimeMillis() + RESULT_MS;
        api.transaction.getMethod(pending.commit).invoke(transaction);
        resample.accept(target);
    }

    private void finish(Request pending, Exception error) {
        if (request != pending) return;
        request = null;
        main.removeCallbacks(poll);
        if (pending.activity != null) main.post(() -> resample.accept(pending.activity));
        pending.complete.accept(error);
    }

    private static boolean isAttached(Object view) {
        return view instanceof View && ((View) view).isAttachedToWindow();
    }

    private static boolean active(Activity activity) {
        return !activity.isFinishing() && !activity.isDestroyed() && activity.hasWindowFocus();
    }

    private static boolean field(String value) {
        return value != null && !value.isEmpty() && value.length() <= MAX_FIELD;
    }

    private static boolean contains(Bundle actual, Bundle expected) {
        if (expected.isEmpty()) return true;
        if (actual == null) return false;
        for (String key : expected.keySet()) {
            if (!actual.containsKey(key) || !java.util.Objects.equals(actual.get(key), expected.get(key))) return false;
        }
        return true;
    }

    private static Bundle schema(Bundle arguments) {
        if (arguments.size() > MAX_ARGUMENTS) return null;
        Bundle result = new Bundle();
        for (String key : arguments.keySet()) {
            if (!field(key) || key.length() > 256) return null;
            Object value = arguments.get(key);
            if (value == null) result.putString(key, "null");
            else if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                    || value instanceof Byte || value instanceof Short
                    || (value instanceof String && ((String) value).length() <= MAX_FIELD)
                    || (value instanceof Float && Float.isFinite((Float) value))
                    || (value instanceof Double && Double.isFinite((Double) value))) {
                result.putString(key, value.getClass().getSimpleName());
            } else return null;
        }
        return result;
    }

    private static Bundle typed(Bundle edited, Bundle schema) {
        if (edited == null || schema == null || !edited.keySet().equals(schema.keySet())) {
            throw new IllegalArgumentException("Invalid edited argument set");
        }
        Bundle arguments = new Bundle();
        for (String key : schema.keySet()) {
            Object value = edited.get(key);
            String type = schema.getString(key, "");
            if ("null".equals(type) && value == null) arguments.putString(key, null);
            else if ("String".equals(type) && value instanceof String && ((String)value).length() <= MAX_FIELD) {
                arguments.putString(key, (String) value);
            } else if ("Boolean".equals(type) && value instanceof Boolean) {
                arguments.putBoolean(key, (Boolean) value);
            } else if (value instanceof Number) {
                String number = value.toString();
                switch (type) {
                    case "Byte": arguments.putByte(key, Byte.parseByte(number)); break;
                    case "Short": arguments.putShort(key, Short.parseShort(number)); break;
                    case "Integer": arguments.putInt(key, Integer.parseInt(number)); break;
                    case "Long": arguments.putLong(key, Long.parseLong(number)); break;
                    case "Float": arguments.putFloat(key, Float.parseFloat(number)); break;
                    case "Double": arguments.putDouble(key, Double.parseDouble(number)); break;
                    default: throw new IllegalArgumentException("Invalid edited argument type");
                }
            } else throw new IllegalArgumentException("Invalid edited argument type");
        }
        if (schema(arguments) == null) throw new IllegalArgumentException("Invalid edited argument values");
        return arguments;
    }

    private static Field getField(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> candidate = type; candidate != null; candidate = candidate.getSuperclass()) {
            try {
                Field found = candidate.getDeclaredField(name);
                found.setAccessible(true);
                return found;
            } catch (NoSuchFieldException ignored) {
                // Walk to the declaring type.
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static Field optionalField(Class<?> type, String name) {
        try { return getField(type, name); }
        catch (NoSuchFieldException ignored) { return null; }
    }

    private static Method declared(Class<?> type, String name) throws NoSuchMethodException {
        Method method = type.getDeclaredMethod(name);
        method.setAccessible(true);
        return method;
    }

    private static Method optional(Class<?> type, String name, Class<?> parameter) {
        try { return type.getMethod(name, parameter); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    private static final class Api {
        final String family;
        final Class<?> activity;
        final Class<?> fragment;
        final Class<?> transaction;
        final Class<?> record;
        final Method root;
        final Method fragmentManager;
        final Method children;
        final Method begin;
        final Method fragments;
        final Method stateSaved;
        final Method factory;
        final Method instantiate;
        final Method getArguments;
        final Method setArguments;
        final Method id;
        final Method tag;
        final Method view;
        final Method isAdded;
        final Method add;
        final Method replace;
        final Method animations;
        final Method setTransition;
        final Method setTransitionStyle;
        final Method setReordering;
        final Method addBackStack;
        final Field recordManager;
        final Field parent;
        final Field operations;
        final Field command;
        final Field destination;
        final Field container;
        final Field transition;
        final Field transitionStyle;
        final Field reordering;
        final Field addToBackStack;
        final Field name;
        final Field[] animation = new Field[ANIMATIONS.length];
        final List<Field> custom = new ArrayList<>();

        Api(ClassLoader loader, String prefix, boolean support) throws ReflectiveOperationException {
            family = support ? "androidx" : "platform";
            activity = support ? Class.forName(prefix + ".FragmentActivity", false, loader) : Activity.class;
            fragment = Class.forName(prefix + ".Fragment", false, loader);
            transaction = Class.forName(prefix + ".FragmentTransaction", false, loader);
            record = Class.forName(prefix + ".BackStackRecord", false, loader);
            root = activity.getMethod(support ? "getSupportFragmentManager" : "getFragmentManager");
            Class<?> manager = root.getReturnType();
            Class<?> managerImplementation = support ? manager
                    : Class.forName(prefix + ".FragmentManagerImpl", false, loader);
            Class<?> op = Class.forName((support ? transaction : record).getName() + "$Op", false, loader);
            parent = getField(managerImplementation, "mParent");
            recordManager = getField(record, "mManager");
            operations = getField(record, "mOps");
            command = getField(op, support ? "mCmd" : "cmd");
            destination = getField(op, support ? "mFragment" : "fragment");
            for (int index = 0; index < ANIMATIONS.length; index++) {
                String animationName = ANIMATIONS[index];
                if (support) animationName = "m" + Character.toUpperCase(animationName.charAt(0))
                        + animationName.substring(1);
                animation[index] = getField(op, animationName + "Anim");
            }
            container = getField(fragment, "mContainerId");
            transition = getField(record, "mTransition");
            transitionStyle = optionalField(record, "mTransitionStyle");
            reordering = optionalField(record, "mReorderingAllowed");
            addToBackStack = getField(record, "mAddToBackStack");
            name = getField(record, "mName");
            for (String field : new String[]{"mCommitRunnables", "mSharedElementSourceNames",
                    "mSharedElementTargetNames", "mBreadCrumbTitleText", "mBreadCrumbShortTitleText",
                    "mBreadCrumbTitleRes", "mBreadCrumbShortTitleRes"}) {
                Field value = optionalField(record, field);
                if (value != null) custom.add(value);
            }
            fragments = manager.getMethod("getFragments");
            begin = manager.getMethod("beginTransaction");
            stateSaved = manager.getMethod("isStateSaved");
            factory = support ? manager.getMethod("getFragmentFactory") : null;
            instantiate = support ? factory.getReturnType().getMethod("instantiate", ClassLoader.class, String.class)
                    : fragment.getMethod("instantiate", android.content.Context.class, String.class);
            fragmentManager = fragment.getMethod("getFragmentManager");
            children = fragment.getMethod("getChildFragmentManager");
            view = fragment.getMethod("getView");
            id = fragment.getMethod("getId");
            tag = fragment.getMethod("getTag");
            isAdded = fragment.getMethod("isAdded");
            getArguments = fragment.getMethod("getArguments");
            setArguments = fragment.getMethod("setArguments", Bundle.class);
            add = transaction.getMethod("add", int.class, fragment, String.class);
            replace = transaction.getMethod("replace", int.class, fragment, String.class);
            animations = transaction.getMethod("setCustomAnimations", int.class, int.class, int.class, int.class);
            setTransition = transaction.getMethod("setTransition", int.class);
            setTransitionStyle = optional(transaction, "setTransitionStyle", int.class);
            setReordering = optional(transaction, "setReorderingAllowed", boolean.class);
            addBackStack = transaction.getMethod("addToBackStack", String.class);
        }

        boolean hasCustomState(Object tx) throws IllegalAccessException {
            for (Field field : custom) {
                Object value = field.get(tx);
                if (value instanceof List && ((List<?>)value).isEmpty()) continue;
                if (value instanceof Number && ((Number)value).intValue() == 0) continue;
                if (value != null) return true;
            }
            return false;
        }

        Object rootFor(Activity host) throws ReflectiveOperationException {
            return activity.isInstance(host) ? root.invoke(host) : null;
        }

        String identity(Object candidate) throws ReflectiveOperationException {
            return new JSONArray().put(candidate.getClass().getName()).put(id.invoke(candidate))
                    .put(tag.invoke(candidate)).toString();
        }

        String slot(Object manager, int targetContainer) throws ReflectiveOperationException {
            List<String> occupants = new ArrayList<>();
            List<?> current = (List<?>) fragments.invoke(manager);
            for (Object candidate : current) {
                if (candidate == null || container.getInt(candidate) != targetContainer) continue;
                if (occupants.size() == 16) return null;
                occupants.add(identity(candidate));
            }
            Collections.sort(occupants);
            return new JSONArray(occupants).toString();
        }

        Object managerAt(Activity host, JSONArray path) throws ReflectiveOperationException, JSONException {
            Object current = rootFor(host);
            for (int depth = 0; current != null && depth < path.length(); depth++) {
                Object matched = null;
                for (Object next : (List<?>) fragments.invoke(current)) {
                    if (next == null || !path.getString(depth).equals(identity(next))
                            || !isAttached(view.invoke(next))) continue;
                    if (matched != null) return null;
                    matched = next;
                }
                current = matched == null ? null : children.invoke(matched);
            }
            return current;
        }

        Object containerView(Activity host, Object manager, int containerId) throws ReflectiveOperationException {
            Object owner = parent.get(manager);
            View base = owner == null ? host.getWindow().getDecorView() : (View) view.invoke(owner);
            View target = base == null ? null : base.findViewById(containerId);
            return target instanceof ViewGroup ? target : null;
        }

        Object create(Object manager, Activity host, String target) throws ReflectiveOperationException {
            return factory == null ? instantiate.invoke(null, host, target)
                    : instantiate.invoke(factory.invoke(manager), host.getClassLoader(), target);
        }
    }

    private static final class Recording {
        final Activity activity;
        final Object fragment;
        final Bundle descriptor;

        Recording(Activity activity, Object fragment, Bundle descriptor) {
            this.activity = activity;
            this.fragment = fragment;
            this.descriptor = descriptor;
        }
    }

    private static final class Recorded {
        final long start = SystemClock.uptimeMillis();
        final Api api;
        final WeakReference<Activity> activity;
        final Bundle descriptor;

        Recorded(Api api, Activity activity, Bundle descriptor) {
            this.api = api;
            this.activity = new WeakReference<>(activity);
            this.descriptor = descriptor;
        }
    }

    private static final class Request {
        final Bundle descriptor;
        final Bundle arguments;
        final String host;
        final String family;
        final JSONArray parents;
        final String source;
        final String commit;
        final int operation;
        final int container;
        final Consumer<Exception> complete;

        long deadline = SystemClock.uptimeMillis() + SOURCE_MS;
        Activity activity;
        Object fragment;
        Api api;

        Request(Bundle descriptor, Bundle edited, Consumer<Exception> complete) throws JSONException {
            this.descriptor = descriptor;
            this.complete = complete;
            host = descriptor.getString("host");
            family = descriptor.getString("family");
            source = descriptor.getString("source");
            commit = descriptor.getString("method");
            operation = descriptor.getInt("operation");
            container = descriptor.getInt("container");
            String path = descriptor.getString("parents");
            if (!field(host) || !field(source) || !field(path) || !field(descriptor.getString("destination"))
                    || (!"androidx".equals(family) && !"platform".equals(family))
                    || !java.util.Arrays.asList(COMMITS).contains(commit)
                    || (operation != ADD && operation != REPLACE) || container <= 0) {
                throw new IllegalArgumentException("Invalid transaction descriptor");
            }
            parents = new JSONArray(path);
            if (parents.length() > MAX_DEPTH) throw new IllegalArgumentException("Fragment parent path too deep");
            arguments = typed(edited, descriptor.getBundle(ARGUMENT_TYPES));
        }
    }
}
