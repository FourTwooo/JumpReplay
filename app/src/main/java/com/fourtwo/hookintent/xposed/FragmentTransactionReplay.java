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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Reconstructs a directly observed FragmentManager transaction and its destination. */
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
    private static final int REMOVE = 3;
    private static final int HIDE = 4;
    private static final int SHOW = 5;
    private static final int ATTACH = 7;
    private static final int SET_PRIMARY = 8;
    private static final int MAX_OPERATIONS = 16;
    private static final String OPERATIONS = "operations";
    private static final String PRIMARY_SOURCE = "primary_source";
    private static final String EXISTING_DESTINATION = "transaction_existing_destination";
    private static final String INSPECTION_FAILED = "transaction_inspection_failed";
    private static final int MAX_DEPTH = 8;
    private static final int MAX_ARGUMENTS = 64;
    private static final int MAX_FIELD = 8192;
    private static final long CAPTURE_MS = 9000;
    private static final long RESULT_MS = 8500;

    private final Handler main;
    private final Predicate<Activity> capturing;
    private final Supplier<? extends Collection<Activity>> resumed;
    private final Consumer<Activity> resample;
    private final Map<Class<?>, Api> installed = new HashMap<>();
    private final Set<ClassLoader> scanned = new HashSet<>();
    private final Set<Class<?>> hooked = new HashSet<>();
    private final Map<Object, Recorded> records = new WeakHashMap<>();
    private final Runnable poll = this::tick;
    private Request request;
    private WeakReference<Activity> lastActivity = new WeakReference<>(null);
    private WeakReference<Object> lastFragment = new WeakReference<>(null);
    private String lastFailure;

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
                        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                            // The original operations themselves could not be read.
                        }
                    }

                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        Recording recording = (Recording) param.getObjectExtra(TRANSACTION);
                        if (recording == null) return;
                        recordCompletion(api, recording, param.hasThrowable());
                    }
                });
            }
            hooked.add(api.record);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // The target may omit this Fragment library or ship a different internal layout.
        }
    }

    private void recordCompletion(Api api, Recording recording, boolean failed) {
        for (Object fragment : recording.observedDestinations) {
            Recorded prior = records.get(fragment);
            boolean duplicate = prior != null && SystemClock.uptimeMillis() - prior.start <= CAPTURE_MS;
            String cause = recording.failure;
            if (failed) cause = "transaction_commit_failed";
            else if (duplicate) cause = "transaction_duplicate";
            else if (cause == null && fragment != recording.fragment) cause = EXISTING_DESTINATION;
            records.put(fragment, new Recorded(api, recording.activity,
                    cause == null ? recording.descriptor : null, cause));
        }
    }

    private Recording snapshot(Api api, Object transaction, String commit) throws ReflectiveOperationException {
        List<?> ops = (List<?>) api.operations.get(transaction);
        if (ops == null || ops.isEmpty()) return null;
        Recording recording = new Recording();
        Object destinationOp = null;
        int creations = 0;
        for (Object op : ops) {
            int command = api.command.getInt(op);
            Object fragment = api.destination.get(op);
            if (!api.fragment.isInstance(fragment)) continue;
            if (command == ADD || command == REPLACE) {
                creations++;
                destinationOp = op;
            }
            if (command == ADD || command == REPLACE || command == SHOW || command == ATTACH) {
                recording.observedDestinations.add(fragment);
            }
        }
        if (recording.observedDestinations.isEmpty()) return null;
        if (creations == 0) return recording.failed(EXISTING_DESTINATION);
        if (creations != 1) return recording.failed("transaction_multiple_destinations");
        if (ops.size() > MAX_OPERATIONS) return recording.failed("transaction_unsupported_operation");
        try {
            describe(api, transaction, commit, ops, destinationOp, recording);
            return recording;
        } catch (ReflectiveOperationException | JSONException | RuntimeException | LinkageError error) {
            return recording.failed(INSPECTION_FAILED);
        }
    }

    private void describe(Api api, Object transaction, String commit, List<?> ops, Object destinationOp,
            Recording recording) throws ReflectiveOperationException, JSONException {
        Object manager = api.recordManager.get(transaction);
        if (manager == null) {
            recording.failed("transaction_source_unresolved");
            return;
        }
        if (api.hasCustomState(transaction)) {
            recording.failed("transaction_custom_state");
            return;
        }
        Object fragment = api.destination.get(destinationOp);
        if (Boolean.TRUE.equals(api.isAdded.invoke(fragment))) {
            recording.failed(EXISTING_DESTINATION);
            return;
        }
        int container = api.container.getInt(fragment);
        if (container <= 0) {
            recording.failed("transaction_container_unavailable");
            return;
        }

        ArrayList<String> parents = new ArrayList<>();
        Object root = manager;
        for (Object parent = api.parent.get(root); parent != null; parent = api.parent.get(root)) {
            if (parents.size() == MAX_DEPTH || !isAttached(api.view.invoke(parent))) {
                recording.failed("transaction_source_unresolved");
                return;
            }
            parents.add(api.identity(parent));
            root = api.fragmentManager.invoke(parent);
            if (root == null) {
                recording.failed("transaction_source_unresolved");
                return;
            }
        }
        Collections.reverse(parents);
        for (Activity activity : resumed.get()) {
            if (api.rootFor(activity) != root || !active(activity) || !capturing.test(activity)) continue;
            if (recording.activity != null) {
                recording.failed("transaction_source_unresolved");
                return;
            }
            recording.activity = activity;
        }
        Activity owner = recording.activity;
        if (owner == null) {
            recording.failed("transaction_source_unresolved");
            return;
        }
        if (!isAttached(api.containerView(owner, manager, container))) {
            recording.failed("transaction_container_unavailable");
            return;
        }
        String path = new JSONArray(parents).toString();
        String source = api.slot(manager, container);
        if (!field(source) || !field(path)) {
            recording.failed("transaction_source_unresolved");
            return;
        }

        Bundle original = (Bundle) api.getArguments.invoke(fragment);
        Bundle arguments = original == null ? new Bundle() : new Bundle(original);
        Bundle types = new Bundle();
        String reason = describeArgumentTypes(arguments, types);
        if (reason != null) {
            recording.failed(reason);
            return;
        }

        Bundle descriptor = new Bundle();
        JSONArray operations = new JSONArray();
        boolean destinationAdded = false;
        for (Object op : ops) {
            int command = api.command.getInt(op);
            Object target = api.destination.get(op);
            if (op == destinationOp) {
                destinationAdded = true;
                operations.put(describeOperation(api, op, command, null, false));
                continue;
            }
            if (command == SET_PRIMARY && api.setPrimary != null && api.primary != null) {
                Object currentPrimary = api.primary.invoke(manager);
                descriptor.putString(PRIMARY_SOURCE, currentPrimary == null ? "" : api.identity(currentPrimary));
                if (target == null || (target == fragment && destinationAdded)) {
                    operations.put(describeOperation(api, op, command, target == null ? "" : null, false));
                    continue;
                }
            } else if (command != HIDE && command != REMOVE && command != SHOW) {
                recording.failed("transaction_unsupported_operation");
                return;
            }
            if (!api.fragment.isInstance(target) || target == fragment) {
                recording.failed("transaction_unsupported_operation");
                return;
            }
            String identity = api.identity(target);
            boolean hidden = Boolean.TRUE.equals(api.hidden.invoke(target));
            if (api.existingFragment(manager, identity, hidden) != target) {
                recording.failed("transaction_source_unresolved");
                return;
            }
            operations.put(describeOperation(api, op, command, identity, hidden));
        }
        String operationList = operations.toString();
        if (!field(operationList)) {
            recording.failed("transaction_unsupported_operation");
            return;
        }

        descriptor.putString(MECHANISM, TRANSACTION);
        descriptor.putString("family", api.family);
        descriptor.putString("host", owner.getClass().getName());
        descriptor.putString("destination", fragment.getClass().getName());
        descriptor.putString("parents", path);
        descriptor.putString("source", source);
        descriptor.putString("method", commit);
        descriptor.putInt("container", container);
        descriptor.putString("tag", (String) api.tag.invoke(fragment));
        descriptor.putInt("operation", api.command.getInt(destinationOp));
        descriptor.putString(OPERATIONS, operationList);
        descriptor.putBoolean("back_stack", api.addToBackStack.getBoolean(transaction));
        descriptor.putString("back_stack_name", (String) api.name.get(transaction));
        descriptor.putInt("transition", api.transition.getInt(transaction));
        descriptor.putInt("style", api.transitionStyle == null ? 0 : api.transitionStyle.getInt(transaction));
        descriptor.putBoolean("reorder", api.reordering != null && api.reordering.getBoolean(transaction));
        descriptor.putBoolean("null_args", original == null);
        descriptor.putBundle(ARGUMENTS, arguments);
        descriptor.putBundle(ARGUMENT_TYPES, types);
        recording.fragment = fragment;
        recording.descriptor = descriptor;
    }

    private static JSONArray describeOperation(Api api, Object op, int command, String identity, boolean hidden)
            throws IllegalAccessException {
        JSONArray fields = new JSONArray().put(command).put(identity).put(hidden);
        for (Field animation : api.animation) fields.put(animation.getInt(op));
        return fields;
    }

    void forget(Activity activity) {
        records.values().removeIf(record -> record.activity.get() == activity);
    }

    Bundle capture(Activity activity, Object fragment) {
        lastActivity = new WeakReference<>(activity);
        lastFragment = new WeakReference<>(fragment);
        Recorded record = records.remove(fragment);
        if (record == null) {
            boolean available = installed.values().stream()
                    .anyMatch(api -> api.fragment.isInstance(fragment) && hooked.contains(api.record));
            lastFailure = available ? "transaction_not_seen" : "transaction_hook_unavailable";
            return null;
        }
        Activity recordedOwner = record.activity.get();
        if ((recordedOwner != null || record.descriptor != null) && recordedOwner != activity) {
            lastFailure = "transaction_source_unresolved";
            return null;
        }
        if (SystemClock.uptimeMillis() - record.start > CAPTURE_MS) {
            lastFailure = "transaction_expired";
            return null;
        }
        lastFailure = record.failure;
        if (lastFailure != null) return null;
        try {
            Bundle actual = (Bundle) record.api.getArguments.invoke(fragment);
            if (contains(actual, record.descriptor.getBundle(ARGUMENTS))) return record.descriptor;
            lastFailure = "transaction_arguments_changed";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            lastFailure = INSPECTION_FAILED;
        }
        return null;
    }

    String captureFailure(Activity activity, Object fragment) {
        return lastActivity.get() == activity && lastFragment.get() == fragment ? lastFailure : null;
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

    void replay(Bundle descriptor, Bundle edited, long sourceWait, Consumer<Exception> complete) {
        main.post(() -> {
            if (request != null) {
                complete.accept(new IllegalStateException("已有 Fragment 事务正在重放"));
                return;
            }
            try {
                request = new Request(descriptor, edited, sourceWait, complete);
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
        List<Object> targets = null;
        for (Activity activity : new ArrayList<>(resumed.get())) {
            if (!waitingFor(activity) || !active(activity)) continue;
            install(activity.getClass().getClassLoader());
            for (Api candidate : installed.values()) {
                if (!candidate.family.equals(pending.family)) continue;
                Object found = candidate.managerAt(activity, pending.parents);
                if (found == null || Boolean.TRUE.equals(candidate.stateSaved.invoke(found))
                        || !isAttached(candidate.containerView(activity, found, pending.container))
                        || !pending.source.equals(candidate.slot(found, pending.container))) continue;
                List<Object> foundTargets = candidate.replayTargets(found, pending.plan, pending.primarySource);
                if (foundTargets == null) continue;
                if (target != null) throw new IllegalStateException("有多个匹配的 Fragment 宿主");
                target = activity;
                api = candidate;
                manager = found;
                targets = foundTargets;
            }
        }
        if (target == null) return;

        Object destination = api.create(manager, target, pending.descriptor.getString("destination"));
        if (!pending.descriptor.getBoolean("null_args")) api.setArguments.invoke(destination, pending.arguments);
        Object transaction = api.begin.invoke(manager);
        Bundle descriptor = pending.descriptor;
        api.setTransition.invoke(transaction, descriptor.getInt("transition"));
        if (api.setTransitionStyle != null) api.setTransitionStyle.invoke(transaction, descriptor.getInt("style"));
        if (api.setReordering != null) api.setReordering.invoke(transaction, descriptor.getBoolean("reorder"));
        for (int i = 0; i < pending.plan.size(); i++) {
            Operation op = pending.plan.get(i);
            Object fragment = op.target == null ? destination : targets.get(i);
            int[] animations = op.animations;
            api.animations.invoke(transaction, animations[0], animations[1], animations[2], animations[3]);
            switch (op.command) {
                case ADD:
                case REPLACE:
                    Method create = op.command == ADD ? api.add : api.replace;
                    create.invoke(transaction, pending.container, fragment, descriptor.getString("tag"));
                    break;
                case REMOVE:
                    api.remove.invoke(transaction, fragment);
                    break;
                case HIDE:
                    api.hide.invoke(transaction, fragment);
                    break;
                case SHOW:
                    api.show.invoke(transaction, fragment);
                    break;
                case SET_PRIMARY:
                    api.setPrimary.invoke(transaction, fragment);
                    break;
                default:
                    throw new IllegalArgumentException("Unknown Fragment operation");
            }
        }
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

    private static String describeArgumentTypes(Bundle arguments, Bundle types) {
        if (arguments.size() > MAX_ARGUMENTS) return "transaction_argument_limit";
        for (String key : arguments.keySet()) {
            if (key == null || key.isEmpty()) return "transaction_unsupported_arguments";
            if (key.length() > 256) return "transaction_argument_limit";
            Object value = arguments.get(key);
            if (value == null) types.putString(key, "null");
            else if (value instanceof String) {
                if (((String) value).length() > MAX_FIELD) return "transaction_argument_limit";
                types.putString(key, "String");
            } else if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                    || value instanceof Byte || value instanceof Short
                    || (value instanceof Float && Float.isFinite((Float) value))
                    || (value instanceof Double && Double.isFinite((Double) value))) {
                types.putString(key, value.getClass().getSimpleName());
            } else return "transaction_unsupported_arguments";
        }
        return null;
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
        if (describeArgumentTypes(arguments, new Bundle()) != null) {
            throw new IllegalArgumentException("Invalid edited argument values");
        }
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

    private static Method optional(Class<?> type, String name, Class<?>... parameters) {
        try { return type.getMethod(name, parameters); }
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
        final Method hidden;
        final Method add;
        final Method replace;
        final Method remove;
        final Method hide;
        final Method show;
        final Method primary;
        final Method setPrimary;
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
            hidden = fragment.getMethod("isHidden");
            getArguments = fragment.getMethod("getArguments");
            setArguments = fragment.getMethod("setArguments", Bundle.class);
            add = transaction.getMethod("add", int.class, fragment, String.class);
            replace = transaction.getMethod("replace", int.class, fragment, String.class);
            remove = transaction.getMethod("remove", fragment);
            hide = transaction.getMethod("hide", fragment);
            show = transaction.getMethod("show", fragment);
            primary = optional(manager, "getPrimaryNavigationFragment");
            setPrimary = optional(transaction, "setPrimaryNavigationFragment", fragment);
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

        Object existingFragment(Object manager, String label, boolean wasHidden) throws ReflectiveOperationException {
            Object matched = null;
            for (Object fragment : (List<?>) fragments.invoke(manager)) {
                if (fragment == null || !label.equals(identity(fragment))) continue;
                if (matched != null) return null;
                matched = fragment;
            }
            return matched != null && Boolean.TRUE.equals(isAdded.invoke(matched))
                    && Boolean.TRUE.equals(hidden.invoke(matched)) == wasHidden ? matched : null;
        }

        List<Object> replayTargets(Object manager, List<Operation> plan, String primarySource)
                throws ReflectiveOperationException {
            if (primarySource != null) {
                if (primary == null || setPrimary == null) return null;
                Object current = primary.invoke(manager);
                if (!primarySource.equals(current == null ? "" : identity(current))) return null;
            }
            List<Object> targets = new ArrayList<>(plan.size());
            for (Operation op : plan) {
                Object target = null;
                if (op.target != null && !op.target.isEmpty()) {
                    target = existingFragment(manager, op.target, op.hidden);
                    if (target == null) return null;
                }
                targets.add(target);
            }
            return targets;
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
        final Set<Object> observedDestinations = Collections.newSetFromMap(new IdentityHashMap<>());
        Activity activity;
        Object fragment;
        Bundle descriptor;
        String failure;

        Recording failed(String reason) {
            failure = reason;
            return this;
        }
    }

    private static final class Recorded {
        final long start = SystemClock.uptimeMillis();
        final Api api;
        final WeakReference<Activity> activity;
        final Bundle descriptor;
        final String failure;

        Recorded(Api api, Activity activity, Bundle descriptor, String failure) {
            this.api = api;
            this.activity = new WeakReference<>(activity);
            this.descriptor = descriptor;
            this.failure = failure;
        }
    }

    private static final class Operation {
        final int command;
        // null means the newly instantiated destination, and an empty value clears primary navigation.
        final String target;
        final boolean hidden;
        final int[] animations;

        Operation(int command, String target, boolean hidden, int[] animations) {
            this.command = command;
            this.target = target;
            this.hidden = hidden;
            this.animations = animations;
        }

        static List<Operation> plan(Bundle descriptor) throws JSONException {
            int creation = descriptor.getInt("operation");
            String encoded = descriptor.getString(OPERATIONS);
            if (encoded == null) {
                int[] animations = new int[ANIMATIONS.length];
                for (int i = 0; i < ANIMATIONS.length; i++) animations[i] = descriptor.getInt(ANIMATIONS[i]);
                return Collections.singletonList(new Operation(creation, null, false, animations));
            }
            if (!field(encoded)) throw new IllegalArgumentException("Invalid Fragment operation list");
            JSONArray operations = new JSONArray(encoded);
            if (operations.length() < 1 || operations.length() > MAX_OPERATIONS) {
                throw new IllegalArgumentException("Invalid Fragment operation count");
            }
            List<Operation> plan = new ArrayList<>(operations.length());
            boolean created = false;
            for (int i = 0; i < operations.length(); i++) {
                JSONArray fields = operations.getJSONArray(i);
                int command = fields.getInt(0);
                String target = fields.isNull(1) ? null : fields.getString(1);
                if ((command == ADD || command == REPLACE) && command == creation && !created && target == null) {
                    created = true;
                } else if (command == SET_PRIMARY && descriptor.containsKey(PRIMARY_SOURCE)
                        && (target != null || created)) {
                    if (target != null && target.length() > MAX_FIELD) {
                        throw new IllegalArgumentException("Invalid Fragment operation target");
                    }
                } else if ((command != REMOVE && command != HIDE && command != SHOW) || !field(target)) {
                    throw new IllegalArgumentException("Invalid auxiliary Fragment operation");
                }
                int[] animations = new int[ANIMATIONS.length];
                for (int j = 0; j < ANIMATIONS.length; j++) animations[j] = fields.getInt(j + 3);
                plan.add(new Operation(command, target, fields.getBoolean(2), animations));
            }
            if (!created) throw new IllegalArgumentException("Missing Fragment destination");
            return plan;
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
        final List<Operation> plan;
        final String primarySource;
        final int container;
        final Consumer<Exception> complete;

        long deadline;
        Activity activity;
        Object fragment;
        Api api;

        Request(Bundle descriptor, Bundle edited, long sourceWait, Consumer<Exception> complete) throws JSONException {
            deadline = SystemClock.uptimeMillis() + sourceWait;
            this.descriptor = descriptor;
            this.complete = complete;
            host = descriptor.getString("host");
            family = descriptor.getString("family");
            source = descriptor.getString("source");
            commit = descriptor.getString("method");
            int operation = descriptor.getInt("operation");
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
            plan = Operation.plan(descriptor);
            primarySource = descriptor.getString(PRIMARY_SOURCE);
            arguments = typed(edited, descriptor.getBundle(ARGUMENT_TYPES));
        }
    }
}
