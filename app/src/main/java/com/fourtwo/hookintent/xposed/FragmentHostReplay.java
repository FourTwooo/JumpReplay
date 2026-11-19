package com.fourtwo.hookintent.xposed;

import android.app.Activity;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Correlates a bounded set of Activity calls with observed Fragment navigation. */
final class FragmentHostReplay {
    private static final String MECHANISM = "mechanism";
    private static final String FRAGMENT_HOST = "fragment_host";
    private static final String HOST = "host";
    private static final String SOURCE = "source";
    private static final String METHOD = "method";
    private static final String DESTINATION = "destination";
    private static final String ARGUMENTS = "arguments";
    private static final String OPTION = "boolean_argument";
    private static final String FIXED_BUNDLE = "fixed_bundle";
    private static final String CALL = "hookIntentFragmentHostCall";
    private static final int MAX_METHODS = 32;
    private static final int MAX_CALLS = 12;
    private static final int MAX_ARGUMENTS = 24;
    private static final int MAX_KEY = 256;
    private static final int MAX_VALUE = 8192;
    // The host can defer rendering while it initializes the destination off screen.
    private static final long CORRELATION_MS = 9000;
    private static final long SOURCE_WAIT_MS = 6500;
    private static final long DESTINATION_WAIT_MS = 8500;
    private static final long POLL_MS = 100;

    private final Handler main;
    private final Predicate<Activity> capturing;
    private final Supplier<? extends Collection<Activity>> resumed;
    private final Consumer<Activity> resample;
    private final Map<Activity, HostState> states = new WeakHashMap<>();
    private final Set<Class<?>> inspected = new HashSet<>();
    private final Runnable poll = this::tick;
    private Request request;

    FragmentHostReplay(Handler main, Predicate<Activity> capturing,
                       Supplier<? extends Collection<Activity>> resumed, Consumer<Activity> resample) {
        this.main = main;
        this.capturing = capturing;
        this.resumed = resumed;
        this.resample = resample;
    }

    void install(Activity activity) {
        Class<?> host = activity.getClass();
        if (!inspected.add(host) || !Modifier.isPublic(host.getModifiers())) return;
        List<Method> methods = new ArrayList<>();
        try {
            for (Method method : host.getDeclaredMethods()) {
                if (supported(method)) methods.add(method);
                if (methods.size() > MAX_METHODS) return;
            }
        } catch (RuntimeException | LinkageError ignored) {
            return;
        }
        for (Method method : methods) {
            try {
                hook(method);
            } catch (RuntimeException | LinkageError ignored) {
                // The host can still participate in Fragment observation.
            }
        }
    }

    private static boolean supported(Method method) {
        int modifiers = method.getModifiers();
        if (!Modifier.isPublic(modifiers) || Modifier.isStatic(modifiers) || Modifier.isNative(modifiers)
                || Modifier.isAbstract(modifiers) || method.isSynthetic() || method.getReturnType() != void.class) {
            return false;
        }
        Class<?>[] parameters = method.getParameterTypes();
        return (parameters.length == 2 || (parameters.length == 3 && parameters[2] == boolean.class))
                && parameters[0] == String.class && parameters[1] == Bundle.class;
    }

    private void hook(Method method) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Looper.myLooper() != Looper.getMainLooper() || request != null) return;
                Activity activity = (Activity) param.thisObject;
                if (activity.getClass() != method.getDeclaringClass() || !active(activity)
                        || !capturing.test(activity)) return;
                HostState state = states.get(activity);
                String value = (String) param.args[0];
                if (state == null || state.source == null || !validValue(value)) return;
                Bundle extras = (Bundle) param.args[1];
                String fixed = null;
                boolean preservable = extras == null;
                if (extras != null) {
                    try {
                        fixed = encode(extras);
                        preservable = true;
                    } catch (JSONException | RuntimeException | LinkageError ignored) {
                        // Keep the observation as a potential ambiguity even when its call cannot be restored.
                    }
                }
                Boolean option = param.args.length == 3 ? (Boolean) param.args[2] : null;
                param.setObjectExtra(CALL, new Call(method, state.source, state.visible.keySet(), value,
                        option, fixed, preservable));
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Call call = (Call) param.getObjectExtra(CALL);
                if (call == null || param.hasThrowable()) return;
                HostState state = states.get((Activity) param.thisObject);
                if (state == null) return;
                prune(state);
                if (state.calls.size() == MAX_CALLS) {
                    Call evicted = state.calls.removeFirst();
                    state.incompleteUntil = Math.max(state.incompleteUntil, evicted.started + CORRELATION_MS);
                }
                state.calls.addLast(call);
            }
        });
    }

    void forget(Activity activity) {
        states.remove(activity);
    }

    void observe(Activity activity, Map<String, List<Map<String, Object>>> visible) {
        HostState state = states.computeIfAbsent(activity, ignored -> new HostState());
        state.visible = visible;
        state.source = source(visible);
        prune(state);
        Request waiting = request;
        if (waiting != null && waiting.invoked == activity && active(activity)) {
            Map<String, Object> actual = uniqueDestination(state, waiting.destination);
            if (actual != null && waiting.value.equals(actual.get(waiting.key))) {
                waiting.observed = true;
                if (!waiting.invoking) finish(waiting, null);
            }
        }
    }

    Bundle capture(Activity activity, String destination, Map<String, Object> arguments) {
        HostState state = states.get(activity);
        if (state == null || request != null || SystemClock.uptimeMillis() < state.incompleteUntil
                || !arguments.equals(uniqueDestination(state, destination))) {
            return null;
        }
        prune(state);
        Call match = null;
        String key = null;
        int associations = 0;
        Iterator<Call> calls = state.calls.iterator();
        while (calls.hasNext()) {
            Call call = calls.next();
            if (call.priorFragments.contains(destination)) continue;
            boolean linked = false;
            for (Map.Entry<String, Object> entry : arguments.entrySet()) {
                if (!call.value.equals(entry.getValue())) continue;
                linked = true;
                match = call;
                key = entry.getKey();
                associations++;
            }
            if (linked) calls.remove();
        }
        if (associations != 1 || !match.preservable || !validField(key, MAX_KEY)) return null;
        if (match.fixed != null) {
            try {
                Bundle fixed = decode(match.fixed);
                if (fixed.containsKey(key) && !match.value.equals(fixed.get(key))) return null;
            } catch (JSONException | RuntimeException ignored) {
                return null;
            }
        }

        Bundle editable = new Bundle();
        editable.putString(key, match.value);
        Bundle descriptor = new Bundle();
        descriptor.putString(MECHANISM, FRAGMENT_HOST);
        descriptor.putString(HOST, activity.getClass().getName());
        descriptor.putString(SOURCE, match.source);
        descriptor.putString(METHOD, match.method.getName());
        descriptor.putString(DESTINATION, destination);
        descriptor.putBundle(ARGUMENTS, editable);
        if (match.option != null) descriptor.putBoolean(OPTION, match.option);
        if (match.fixed != null) descriptor.putString(FIXED_BUNDLE, match.fixed);
        return descriptor;
    }

    static boolean handles(Bundle descriptor) {
        return descriptor != null && FRAGMENT_HOST.equals(descriptor.getString(MECHANISM));
    }

    boolean waitingFor(Activity activity) {
        Request waiting = request;
        return waiting != null && waiting.host.equals(activity.getClass().getName())
                && (waiting.invoked == null || waiting.invoked == activity);
    }

    void replay(Bundle descriptor, Bundle arguments, Consumer<Exception> complete) {
        main.post(() -> {
            if (request != null) {
                complete.accept(new IllegalStateException("已有 Fragment 重放等待执行"));
                return;
            }
            try {
                request = new Request(descriptor, arguments, complete);
            } catch (JSONException | RuntimeException failure) {
                complete.accept(new IllegalArgumentException("Fragment 重放参数无效", failure));
                return;
            }
            tick();
        });
    }

    private void tick() {
        Request waiting = request;
        if (waiting == null) return;
        if (SystemClock.uptimeMillis() >= waiting.deadline) {
            String error = waiting.invoked == null ? "请返回捕获时的来源页面后再重放"
                    : "原导航调用结束后，未观察到带有重放参数的 Fragment";
            finish(waiting, new IllegalStateException(error));
            return;
        }
        try {
            if (waiting.invoked == null) findHost(waiting);
            else resample.accept(waiting.invoked);
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            finish(waiting, new IllegalStateException("无法调用记录中的 Fragment 导航入口", failure));
        }
        if (request == waiting) main.postDelayed(poll, POLL_MS);
    }

    private void findHost(Request waiting) throws ReflectiveOperationException {
        Activity host = null;
        for (Activity candidate : new ArrayList<>(resumed.get())) {
            if (!waitingFor(candidate) || !active(candidate)) continue;
            resample.accept(candidate);
            HostState state = states.get(candidate);
            if (!active(candidate) || state == null || !waiting.source.equals(state.source)
                    || state.visible.containsKey(waiting.destination)) continue;
            if (host != null) throw new IllegalStateException("当前有多个匹配的 Fragment 宿主");
            host = candidate;
        }
        if (host == null || SystemClock.uptimeMillis() >= waiting.deadline) return;

        Class<?> owner = host.getClass();
        Method method = waiting.option == null
                ? owner.getDeclaredMethod(waiting.method, String.class, Bundle.class)
                : owner.getDeclaredMethod(waiting.method, String.class, Bundle.class, boolean.class);
        if (!Modifier.isPublic(owner.getModifiers()) || !supported(method)) {
            throw new IllegalStateException("记录中的 Fragment 导航入口已变化");
        }
        waiting.invoked = host;
        waiting.deadline = SystemClock.uptimeMillis() + DESTINATION_WAIT_MS;
        waiting.invoking = true;
        try {
            if (waiting.option == null) method.invoke(host, waiting.value, waiting.fixed);
            else method.invoke(host, waiting.value, waiting.fixed, waiting.option);
        } finally {
            waiting.invoking = false;
        }
        if (waiting.observed) finish(waiting, null);
        else resample.accept(host);
    }

    private void finish(Request waiting, Exception error) {
        if (request != waiting) return;
        request = null;
        main.removeCallbacks(poll);
        main.post(() -> {
            for (Activity activity : new ArrayList<>(resumed.get())) {
                if (waiting.host.equals(activity.getClass().getName())) resample.accept(activity);
            }
        });
        waiting.complete.accept(error);
    }

    private static boolean active(Activity host) {
        return !host.isFinishing() && !host.isDestroyed() && host.hasWindowFocus();
    }

    private static void prune(HostState state) {
        long cutoff = SystemClock.uptimeMillis() - CORRELATION_MS;
        state.calls.removeIf(call -> call.started <= cutoff);
    }

    private static String source(Map<String, List<Map<String, Object>>> visible) {
        List<String> names = new ArrayList<>(visible.keySet());
        Collections.sort(names);
        StringBuilder identity = new StringBuilder();
        for (String name : names) {
            identity.append(name.length()).append(':').append(name).append(':').append(visible.get(name).size());
            identity.append('\n');
        }
        return identity.length() <= MAX_VALUE ? identity.toString() : null;
    }

    private static Map<String, Object> uniqueDestination(HostState state, String destination) {
        List<Map<String, Object>> matches = state.visible.get(destination);
        return matches == null || matches.size() != 1 ? null : matches.get(0);
    }

    private static boolean validValue(String value) {
        return validField(value, MAX_VALUE);
    }

    private static boolean validField(String field, int limit) {
        return field != null && !field.isEmpty() && field.length() <= limit;
    }

    private static String encode(Bundle bundle) throws JSONException {
        if (bundle.size() > MAX_ARGUMENTS) throw new IllegalArgumentException("Too many arguments");
        JSONObject fields = new JSONObject();
        for (String key : bundle.keySet()) {
            if (key == null || key.length() > MAX_KEY) throw new IllegalArgumentException("Invalid argument key");
            Object value = bundle.get(key);
            JSONArray field = new JSONArray();
            if (value == null) field.put("Null");
            else if (value instanceof Boolean || value instanceof Byte || value instanceof Short
                    || value instanceof Integer || value instanceof Long || value instanceof String
                    || (value instanceof Double && Double.isFinite((Double) value))
                    || (value instanceof Float && Float.isFinite((Float) value))) {
                String text = value.toString();
                if (text.length() > MAX_VALUE) throw new IllegalArgumentException("Argument is too long");
                field.put(value.getClass().getSimpleName()).put(text);
            } else throw new IllegalArgumentException("Unsupported argument type");
            fields.put(key, field);
        }
        String encoded = fields.toString();
        if (encoded.length() > MAX_VALUE) throw new IllegalArgumentException("Arguments are too large");
        return encoded;
    }

    private static Bundle decode(String encoded) throws JSONException {
        if (!validField(encoded, MAX_VALUE)) throw new IllegalArgumentException("Invalid fixed arguments");
        JSONObject fields = new JSONObject(encoded);
        if (fields.length() > MAX_ARGUMENTS) throw new IllegalArgumentException("Too many arguments");
        Bundle fixed = new Bundle();
        Iterator<String> keys = fields.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            if (key.length() > MAX_KEY) throw new IllegalArgumentException("Invalid argument key");
            JSONArray field = fields.getJSONArray(key);
            String type = field.getString(0);
            String text = "Null".equals(type) ? null : field.getString(1);
            switch (type) {
                case "Null": fixed.putString(key, null); break;
                case "String": fixed.putString(key, text); break;
                case "Boolean": fixed.putBoolean(key, Boolean.parseBoolean(text)); break;
                case "Byte": fixed.putByte(key, Byte.parseByte(text)); break;
                case "Short": fixed.putShort(key, Short.parseShort(text)); break;
                case "Integer": fixed.putInt(key, Integer.parseInt(text)); break;
                case "Long": fixed.putLong(key, Long.parseLong(text)); break;
                case "Float": fixed.putFloat(key, Float.parseFloat(text)); break;
                case "Double": fixed.putDouble(key, Double.parseDouble(text)); break;
                default: throw new IllegalArgumentException("Unsupported argument type");
            }
        }
        return fixed;
    }

    private static final class HostState {
        final ArrayDeque<Call> calls = new ArrayDeque<>();
        Map<String, List<Map<String, Object>>> visible = Collections.emptyMap();
        String source;
        long incompleteUntil;
    }

    private static final class Call {
        final long started = SystemClock.uptimeMillis();
        final Method method;
        final String source;
        final Set<String> priorFragments;
        final String value;
        final Boolean option;
        final String fixed;
        final boolean preservable;

        Call(Method method, String source, Set<String> priorFragments, String value, Boolean option,
             String fixed, boolean preservable) {
            this.method = method;
            this.source = source;
            this.priorFragments = new HashSet<>(priorFragments);
            this.value = value;
            this.option = option;
            this.fixed = fixed;
            this.preservable = preservable;
        }
    }

    private static final class Request {
        final String host;
        final String source;
        final String method;
        final String destination;
        final String key;
        final String value;
        final Boolean option;
        final Bundle fixed;
        final Consumer<Exception> complete;
        Activity invoked;
        boolean invoking;
        boolean observed;
        long deadline = SystemClock.uptimeMillis() + SOURCE_WAIT_MS;

        Request(Bundle descriptor, Bundle arguments, Consumer<Exception> complete) throws JSONException {
            host = descriptor.getString(HOST);
            source = descriptor.getString(SOURCE);
            method = descriptor.getString(METHOD);
            destination = descriptor.getString(DESTINATION);
            Bundle original = descriptor.getBundle(ARGUMENTS);
            if (!validField(host, MAX_VALUE) || source == null || source.length() > MAX_VALUE
                    || !validField(method, MAX_VALUE) || !validField(destination, MAX_VALUE)
                    || original == null || original.size() != 1 || arguments.size() != 1) {
                throw new IllegalArgumentException("Invalid Fragment descriptor");
            }
            key = original.keySet().iterator().next();
            value = arguments.getString(key);
            if (!validField(key, MAX_KEY) || !validValue(original.getString(key)) || !validValue(value)) {
                throw new IllegalArgumentException("Invalid edited Fragment argument");
            }
            option = descriptor.containsKey(OPTION) ? descriptor.getBoolean(OPTION) : null;
            fixed = descriptor.containsKey(FIXED_BUNDLE) ? decode(descriptor.getString(FIXED_BUNDLE)) : null;
            if (fixed != null && fixed.containsKey(key)) {
                if (!original.getString(key).equals(fixed.get(key))) {
                    throw new IllegalArgumentException("Conflicting Fragment argument");
                }
                fixed.putString(key, value);
            }
            this.complete = complete;
        }
    }
}
