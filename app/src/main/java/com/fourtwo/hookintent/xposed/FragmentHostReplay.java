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
import java.math.BigDecimal;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Observes app-owned Activity methods and correlates their inputs with a newly visible Fragment. */
final class FragmentHostReplay {
    private static final String MECHANISM = "mechanism";
    private static final String FRAGMENT_HOST = "fragment_host";
    private static final String HOST = "host";
    private static final String SOURCE = "source";
    private static final String METHOD = "method";
    private static final String DESTINATION = "destination";
    private static final String ARGUMENTS = "arguments";
    private static final String INVOCATION = "invocation";
    private static final String CALL = "hookIntentFragmentHostCall";
    private static final Class<?>[] SCALAR_CLASSES = { String.class, Boolean.class, Byte.class,
            Short.class, Integer.class, Long.class, Float.class, Double.class };
    private static final int MAX_METHODS_PER_CLASS = 192;
    private static final int NO_EVIDENCE = 0;
    private static final int NUMERIC_EVIDENCE = 1;
    private static final int NAMED_EVIDENCE = 2;
    private static final int TEXT_EVIDENCE = 3;
    private static final int[] CAPACITY_BY_EVIDENCE = {0, 32, 64, 96};
    private static final int MAX_CALLS_PER_METHOD = 8;
    private static final int MAX_PARAMETERS = 12;
    private static final int MAX_FIELDS = 64;
    private static final int MAX_KEY = 256;
    private static final int MAX_VALUE = 8192;
    private static final long CORRELATION_MS = 9000;
    private static final long DESTINATION_WAIT_MS = 8500;
    private static final long POLL_MS = 100;

    private final Handler main;
    private final Predicate<Activity> capturing;
    private final Supplier<? extends Collection<Activity>> resumed;
    private final Consumer<Activity> resample;
    private final Map<Activity, HostState> states = new WeakHashMap<>();
    private final Set<Class<?>> inspected = new HashSet<>();
    private final Set<Class<?>> limited = new HashSet<>();
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
        for (Class<?> type = activity.getClass(); appActivity(type); type = type.getSuperclass()) {
            if (!inspected.add(type)) continue;
            try {
                List<Method> methods = new ArrayList<>();
                for (Method method : type.getDeclaredMethods()) {
                    if (eligible(method)) methods.add(method);
                }
                methods.sort(Comparator.comparingInt(FragmentHostReplay::priority).reversed());
                if (methods.size() > MAX_METHODS_PER_CLASS) limited.add(type);
                for (Method method : methods.subList(0, Math.min(methods.size(), MAX_METHODS_PER_CLASS))) {
                    try { hook(method); }
                    catch (RuntimeException | LinkageError ignored) { limited.add(type); }
                }
            } catch (RuntimeException | LinkageError ignored) {
                limited.add(type);
            }
        }
    }

    private static boolean appActivity(Class<?> type) {
        if (type == null || !Activity.class.isAssignableFrom(type)) return false;
        String name = type.getName();
        return !name.startsWith("android.") && !name.startsWith("androidx.")
                && !name.startsWith("android.support.");
    }

    private static boolean eligible(Method method) {
        int modifiers = method.getModifiers();
        return !Modifier.isStatic(modifiers) && !Modifier.isNative(modifiers)
                && !Modifier.isAbstract(modifiers) && !method.isSynthetic() && !method.isBridge()
                && method.getParameterCount() <= MAX_PARAMETERS && priority(method) > 0;
    }

    private static int priority(Method method) {
        int score = 0;
        for (Class<?> type : method.getParameterTypes()) {
            if (type == Bundle.class) score += 12;
            else if (type == String.class) score += 8;
            else if (type.isPrimitive() && type != char.class) score += 2;
            else if (type.isAssignableFrom(Bundle.class)) score += 1;
            else for (Class<?> scalar : SCALAR_CLASSES) {
                if (type.isAssignableFrom(scalar)) { score += 1; break; }
            }
        }
        return score;
    }

    private void hook(Method method) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Looper.myLooper() != Looper.getMainLooper() || request != null) return;
                Activity activity = (Activity) param.thisObject;
                param.setObjectExtra(CALL, begin(activity, method, param.args));
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Call call = (Call) param.getObjectExtra(CALL);
                if (call != null) end((Activity) param.thisObject, call, !param.hasThrowable());
            }
        });
    }

    private Call begin(Activity activity, Method method, Object[] inputs) {
        if (!active(activity) || !capturing.test(activity)) return null;
        HostState state = states.get(activity);
        if (state == null || state.source == null) return null;
        try {
            if (!canInvoke(activity.getClass(), method)) return null;
            Snapshot snapshot = new Snapshot(inputs);
            if (snapshot.strength == NO_EVIDENCE) return null;
            Call call = new Call(method, state, snapshot);
            state.current = call;
            return call;
        } catch (RuntimeException | LinkageError unreadable) {
            state.observationLimited = true;
            return null;
        }
    }

    private void end(Activity activity, Call call, boolean success) {
        HostState state = states.get(activity);
        if (state == null || state.current != call) return;
        state.current = call.parent;
        if (!success) return;
        prune(state);
        int strength = call.snapshot.strength;
        ArrayDeque<Call> calls = state.calls.get(strength - 1);
        Call oldestForMethod = null;
        int retainedForMethod = 0;
        for (Iterator<Call> iterator = calls.iterator(); iterator.hasNext(); ) {
            Call previous = iterator.next();
            if (!call.method.equals(previous.method)) continue;
            if (call.sameInvocation(previous)) {
                iterator.remove();
                continue;
            }
            if (oldestForMethod == null) oldestForMethod = previous;
            retainedForMethod++;
        }
        if (retainedForMethod == MAX_CALLS_PER_METHOD) {
            calls.removeFirstOccurrence(oldestForMethod);
            state.discarded.remember(oldestForMethod);
        } else if (calls.size() == CAPACITY_BY_EVIDENCE[strength]) {
            state.discarded.remember(calls.removeFirst());
        }
        calls.addLast(call);
    }

    private static boolean canInvoke(Class<?> host, Method candidate) {
        int modifiers = candidate.getModifiers();
        if (Modifier.isPrivate(modifiers) || Modifier.isFinal(modifiers)) return true;
        for (Class<?> type = host; type != candidate.getDeclaringClass(); type = type.getSuperclass()) {
            try {
                Method override = type.getDeclaredMethod(candidate.getName(), candidate.getParameterTypes());
                if (!Modifier.isPrivate(override.getModifiers())) return false;
            } catch (NoSuchMethodException ignored) {
                // This class inherits the observed method.
            }
        }
        return true;
    }

    void forget(Activity activity) { states.remove(activity); }

    void observe(Activity activity, Map<String, List<Map<String, Object>>> visible) {
        HostState state = states.computeIfAbsent(activity, ignored -> new HostState());
        state.visible = visible;
        state.source = source(visible);
        prune(state);
        Request waiting = request;
        if (waiting != null && waiting.invoked == activity && active(activity)) {
            Map<String, Object> actual = uniqueDestination(state, waiting.destination);
            if (actual != null && waiting.expected.entrySet().stream()
                    .allMatch(entry -> actual.containsKey(entry.getKey())
                            && Objects.equals(entry.getValue(), actual.get(entry.getKey())))) {
                waiting.observed = true;
                if (!waiting.invoking) finish(waiting, null);
            }
        }
    }

    String captureFailure(Activity activity, String destination) {
        HostState state = states.get(activity);
        if (state == null || state.source == null) return "host_no_source";
        return destination.equals(state.lastDestination) ? state.failure : "host_call_not_observed";
    }

    Bundle capture(Activity activity, String destination, Map<String, Object> arguments) {
        HostState state = states.get(activity);
        if (state == null || state.source == null) return null;
        state.lastDestination = destination;
        state.failure = "host_capture_busy";
        if (request != null) return null;
        state.failure = "host_destination_ambiguous";
        if (!arguments.equals(uniqueDestination(state, destination))) return null;

        prune(state);
        List<Match> matches = new ArrayList<>();
        boolean reusedDestination = false;
        int strongest = NO_EVIDENCE;
        List<Call> calls = new ArrayList<>();
        for (ArrayDeque<Call> queue : state.calls) calls.addAll(queue);
        for (Call call : calls) {
            Match match = new Match(call, arguments);
            if (match.strength == NO_EVIDENCE) continue;
            if (call.priorFragments.contains(destination)) reusedDestination = true;
            else {
                matches.add(match);
                strongest = Math.max(strongest, match.strength);
            }
        }
        if (state.discarded.mightMatch(arguments, strongest)) {
            state.failure = "host_candidate_overflow";
            return null;
        }
        if (matches.isEmpty()) {
            state.failure = reusedDestination ? "host_destination_already_visible"
                    : failureWithoutMatch(activity, state);
            return null;
        }

        // A nested method can receive another copy of the same arguments. Keep the outer invocation
        // when it can account for those same fields, so its intermediate app setup still executes.
        List<Match> roots = new ArrayList<>();
        for (Match candidate : matches) {
            if (candidate.strength == strongest && matches.stream().noneMatch(outer -> outer != candidate
                    && outer.strength >= candidate.strength && outer.call.snapshot.preservable
                    && outer.call.isAncestorOf(candidate.call)
                    && outer.linked.keySet().containsAll(candidate.linked.keySet()))) roots.add(candidate);
        }
        Set<Call> consumed = new HashSet<>();
        for (Match match : matches) consumed.add(match.call);
        for (ArrayDeque<Call> queue : state.calls) queue.removeIf(consumed::contains);
        state.failure = "host_call_ambiguous";
        if (roots.size() != 1) return null;
        Match match = roots.get(0);
        state.failure = "host_arguments_unsupported";
        if (!match.call.snapshot.preservable) return null;
        try {
            Bundle descriptor = describe(activity, destination, match);
            state.failure = null;
            return descriptor;
        } catch (JSONException | IllegalArgumentException unrepresentable) {
            return null;
        }
    }

    private String failureWithoutMatch(Activity activity, HostState state) {
        for (ArrayDeque<Call> queue : state.calls) {
            if (!queue.isEmpty()) return "host_no_argument_mapping";
        }
        if (state.observationLimited) return "host_observation_limited";
        for (Class<?> type = activity.getClass(); appActivity(type); type = type.getSuperclass()) {
            if (limited.contains(type)) return "host_observation_limited";
        }
        return state.lastExpired > SystemClock.uptimeMillis() - CORRELATION_MS
                ? "host_call_expired" : "host_call_not_observed";
    }

    private static Bundle describe(Activity activity, String destination, Match match) throws JSONException {
        Bundle editable = new Bundle();
        List<Field> fields = match.call.snapshot.fields;
        Set<String> used = new HashSet<>(match.linked.keySet());
        JSONArray bindings = new JSONArray();
        for (Field field : fields) {
            if (field.value == null) continue;
            String output = match.outputs.get(field);
            String name = output;
            if (name == null) {
                String position = "arg" + (field.index + 1);
                name = field.key == null ? position : field.key;
                if (!used.add(name)) {
                    name = field.key == null ? position : position + "." + field.key;
                    if (name.length() > MAX_KEY) name = name.substring(0, MAX_KEY - 3);
                    for (int suffix = 2; !used.add(name); suffix++) {
                        String ending = "#" + suffix;
                        name = name.substring(0, Math.min(name.length(), MAX_KEY - ending.length())) + ending;
                    }
                }
            }
            put(editable, name, field.value);
            bindings.put(new JSONArray().put(field.index).put(field.key == null ? JSONObject.NULL : field.key)
                    .put(name).put(output == null ? JSONObject.NULL : output));
        }
        Method method = match.call.method;
        JSONArray types = new JSONArray();
        for (Class<?> type : method.getParameterTypes()) types.put(type.getName());
        JSONArray values = new JSONArray();
        for (Object value : match.call.snapshot.values) values.put(encodeValue(value));
        JSONObject invocation = new JSONObject().put("owner", method.getDeclaringClass().getName())
                .put("types", types).put("values", values).put("bindings", bindings);
        if (invocation.toString().length() > MAX_VALUE) throw new IllegalArgumentException("Invocation too large");

        Bundle descriptor = new Bundle();
        descriptor.putString(MECHANISM, FRAGMENT_HOST);
        descriptor.putString(HOST, activity.getClass().getName());
        descriptor.putString(SOURCE, match.call.source);
        descriptor.putString(METHOD, method.getName());
        descriptor.putString(DESTINATION, destination);
        descriptor.putBundle(ARGUMENTS, editable);
        descriptor.putString(INVOCATION, invocation.toString());
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

    void replay(Bundle descriptor, Bundle arguments, long sourceWait, Consumer<Exception> complete) {
        main.post(() -> {
            if (request != null) {
                complete.accept(new IllegalStateException("已有 Fragment 重放等待执行"));
                return;
            }
            try {
                request = new Request(descriptor, arguments, sourceWait, complete);
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
        Method method = waiting.resolve(host.getClass());
        waiting.invoked = host;
        waiting.deadline = SystemClock.uptimeMillis() + DESTINATION_WAIT_MS;
        waiting.invoking = true;
        try {
            method.setAccessible(true);
            method.invoke(host, waiting.inputs);
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
        long now = SystemClock.uptimeMillis();
        Predicate<Call> expired = call -> call.started <= now - CORRELATION_MS;
        for (ArrayDeque<Call> queue : state.calls) {
            if (queue.removeIf(expired)) state.lastExpired = now;
        }
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

    private static boolean field(String name, int limit) {
        return name != null && !name.isEmpty() && name.length() <= limit;
    }

    private static boolean scalar(Object value) {
        if (value == null) return true;
        if (value instanceof String) return ((String) value).length() <= MAX_VALUE;
        if (value instanceof Float) return Float.isFinite((Float) value);
        if (value instanceof Double) return Double.isFinite((Double) value);
        return value instanceof Boolean || value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long;
    }

    private static boolean distinctive(Object value) {
        if (value instanceof String) return !((String) value).isEmpty();
        // Common default/flag values alone are too weak to identify a host method.
        return value instanceof Number && Math.abs(((Number) value).doubleValue()) > 1;
    }

    private static int evidence(String key, Object value) {
        if (value == null) return NO_EVIDENCE;
        if (value instanceof String && distinctive(value)) return TEXT_EVIDENCE;
        if (key != null) return NAMED_EVIDENCE;
        return distinctive(value) ? NUMERIC_EVIDENCE : NO_EVIDENCE;
    }

    private static void put(Bundle bundle, String key, Object value) {
        if (!scalar(value)) throw new IllegalArgumentException("Unsupported navigation value");
        bundle.putSerializable(key, (java.io.Serializable) value);
    }

    private static JSONArray encodeValue(Object value) throws JSONException {
        if (value instanceof Bundle) {
            JSONObject entries = new JSONObject();
            Bundle bundle = (Bundle) value;
            for (String key : bundle.keySet()) entries.put(key, encodeValue(bundle.get(key)));
            return new JSONArray().put("Bundle").put(entries);
        }
        return value == null ? new JSONArray().put("Null")
                : new JSONArray().put(value.getClass().getSimpleName()).put(value.toString());
    }

    private static Object decodeValue(JSONArray encoded) throws JSONException {
        String type = encoded.getString(0);
        if ("Bundle".equals(type)) return decodeBundle(encoded.getJSONObject(1));
        if ("Null".equals(type)) return null;
        String value = encoded.getString(1);
        switch (type) {
            case "String": return value;
            case "Boolean": return Boolean.valueOf(value);
            case "Byte": return Byte.valueOf(value);
            case "Short": return Short.valueOf(value);
            case "Integer": return Integer.valueOf(value);
            case "Long": return Long.valueOf(value);
            case "Float": return Float.valueOf(value);
            case "Double": return Double.valueOf(value);
            default: throw new IllegalArgumentException("Unsupported navigation value");
        }
    }

    private static Bundle decodeBundle(JSONObject entries) throws JSONException {
        if (entries.length() > MAX_FIELDS) throw new IllegalArgumentException("Too many navigation values");
        Bundle bundle = new Bundle();
        for (Iterator<String> keys = entries.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!field(key, MAX_KEY)) throw new IllegalArgumentException("Invalid navigation value key");
            put(bundle, key, decodeValue(entries.getJSONArray(key)));
        }
        return bundle;
    }

    private static Object coerce(Object edited, Object original) {
        if (edited == null || original == null) throw new IllegalArgumentException("Invalid edited argument");
        if (edited.getClass() == original.getClass() && scalar(edited)) return edited;
        if (!(edited instanceof Number) || !(original instanceof Number)) {
            throw new IllegalArgumentException("Navigation argument type changed");
        }
        BigDecimal number = new BigDecimal(edited.toString());
        if (original instanceof Byte) return number.byteValueExact();
        if (original instanceof Short) return number.shortValueExact();
        if (original instanceof Integer) return number.intValueExact();
        if (original instanceof Long) return number.longValueExact();
        if (original instanceof Float && Float.isFinite(number.floatValue())) return number.floatValue();
        if (original instanceof Double && Double.isFinite(number.doubleValue())) return number.doubleValue();
        throw new IllegalArgumentException("Invalid edited number");
    }

    private static final class HostState {
        final List<ArrayDeque<Call>> calls = Arrays.asList(new ArrayDeque<>(), new ArrayDeque<>(), new ArrayDeque<>());
        final DiscardedWitnesses discarded = new DiscardedWitnesses();
        Map<String, List<Map<String, Object>>> visible = Collections.emptyMap();
        String source;
        Call current;
        String lastDestination;
        String failure;
        boolean observationLimited;
        long lastExpired = -CORRELATION_MS;
    }

    private static final class DiscardedWitnesses {
        private static final long BUCKET_MS = CORRELATION_MS / 6;
        private static final int BITS = 16 * 1024;
        private final Map<Long, BitSet> windows = new HashMap<>();

        void remember(Call evicted) {
            long current = SystemClock.uptimeMillis();
            expire(current);
            long bucket = evicted.started / BUCKET_MS;
            BitSet witnesses = windows.computeIfAbsent(bucket, unused -> new BitSet(BITS));
            for (Field field : evicted.snapshot.fields) {
                if (evidence(field.key, field.value) == NO_EVIDENCE) continue;
                int hash = witnessHash(field.key, field.value);
                witnesses.set(bit(hash));
                witnesses.set(bit(mix(hash)));
                witnesses.set(bit(mix(hash ^ 0x9e3779b9)));
            }
        }

        boolean mightMatch(Map<String, Object> arguments, int strength) {
            expire(SystemClock.uptimeMillis());
            for (Map.Entry<String, Object> argument : arguments.entrySet()) {
                Object value = argument.getValue();
                if (value == null || !scalar(value)) continue;
                String key = argument.getKey();
                if (evidence(key, value) >= strength && contains(witnessHash(key, value))) return true;
                int positionalStrength = evidence(null, value);
                if (positionalStrength != NO_EVIDENCE && positionalStrength >= strength
                        && contains(witnessHash(null, value))) return true;
            }
            return false;
        }

        private void expire(long now) {
            // Keep a complete final bucket to avoid overlooking an evicted live witness.
            windows.keySet().removeIf(bucket -> (bucket + 1) * BUCKET_MS <= now - CORRELATION_MS);
        }

        private boolean contains(int hash) {
            int first = bit(hash);
            int second = bit(mix(hash));
            int third = bit(mix(hash ^ 0x9e3779b9));
            for (BitSet window : windows.values()) {
                if (window.get(first) && window.get(second) && window.get(third)) return true;
            }
            return false;
        }

        private static int witnessHash(String key, Object value) {
            return Objects.hash(key, value.getClass(), value);
        }

        private static int mix(int value) {
            value ^= value >>> 16;
            value *= 0x7feb352d;
            value ^= value >>> 15;
            value *= 0x846ca68b;
            return value ^ (value >>> 16);
        }

        private static int bit(int value) { return value & (BITS - 1); }
    }

    private static final class Field {
        final int index;
        final String key;
        final Object value;

        Field(int index, String key, Object value) {
            this.index = index;
            this.key = key;
            this.value = value;
        }
    }

    private static final class Snapshot {
        final Object[] values;
        final List<Field> fields = new ArrayList<>();
        boolean preservable = true;
        int strength = NO_EVIDENCE;

        Snapshot(Object[] inputs) {
            values = new Object[inputs.length];
            for (int index = 0; index < inputs.length; index++) {
                Object value = inputs[index];
                if (value instanceof Bundle) {
                    Bundle input = (Bundle) value;
                    if (input.size() > MAX_FIELDS) { preservable = false; continue; }
                    Bundle copy = new Bundle();
                    List<String> keys = new ArrayList<>(input.keySet());
                    if (keys.remove(null)) preservable = false;
                    Collections.sort(keys);
                    for (String key : keys) {
                        Object arrival = input.get(key);
                        if (!field(key, MAX_KEY) || !add(index, key, arrival)) {
                            preservable = false;
                            continue;
                        }
                        put(copy, key, arrival);
                    }
                    values[index] = copy;
                } else if (add(index, null, value)) values[index] = value;
                else preservable = false;
            }
        }

        boolean sameInputs(Snapshot other) {
            if (!preservable || !other.preservable || fields.size() != other.fields.size()) return false;
            for (int index = 0; index < fields.size(); index++) {
                Field left = fields.get(index);
                Field right = other.fields.get(index);
                if (left.index != right.index || !Objects.equals(left.key, right.key)
                        || !Objects.equals(left.value, right.value)) return false;
            }
            return true;
        }

        private boolean add(int index, String key, Object value) {
            if (!scalar(value) || fields.size() == MAX_FIELDS) return false;
            fields.add(new Field(index, key, value));
            strength = Math.max(strength, evidence(key, value));
            return true;
        }
    }

    private static final class Call {
        final long started = SystemClock.uptimeMillis();
        final Method method;
        final String source;
        final Set<String> priorFragments;
        final Snapshot snapshot;
        final Call parent;

        Call(Method method, HostState state, Snapshot snapshot) {
            this.method = method;
            this.source = state.source;
            this.priorFragments = new HashSet<>(state.visible.keySet());
            this.snapshot = snapshot;
            this.parent = state.current;
        }

        // Identical completed calls from the same source and parent represent one replay recipe.
        boolean sameInvocation(Call other) {
            return parent == other.parent && source.equals(other.source) && snapshot.sameInputs(other.snapshot);
        }

        boolean isAncestorOf(Call other) {
            for (Call current = other.parent; current != null; current = current.parent) {
                if (current == this) return true;
            }
            return false;
        }
    }

    private static final class Match {
        final Call call;
        final Map<Field, String> outputs = new HashMap<>();
        final Map<String, Field> linked = new LinkedHashMap<>();
        int strength = NO_EVIDENCE;

        Match(Call call, Map<String, Object> destination) {
            this.call = call;
            for (Field field : call.snapshot.fields) {
                if (field.key != null && field.value != null && field(field.key, MAX_KEY)
                        && field.value.equals(destination.get(field.key))) {
                    if (link(field, field.key)) strength = Math.max(strength, evidence(field.key, field.value));
                }
            }
            for (Field field : call.snapshot.fields) {
                if (field.key != null || field.value == null) continue;
                String key = uniqueKey(field, destination);
                if (key == null) continue;
                if (link(field, key)) strength = Math.max(strength, evidence(null, field.value));
            }
        }

        private String uniqueKey(Field field, Map<String, Object> destination) {
            String key = null;
            for (Map.Entry<String, Object> output : destination.entrySet()) {
                if (!field(output.getKey(), MAX_KEY) || !field.value.equals(output.getValue())) continue;
                if (key != null) return null;
                key = output.getKey();
            }
            if (key == null) return null;
            for (Field candidate : call.snapshot.fields) {
                if (candidate != field && candidate.key == null && field.value.equals(candidate.value)) return null;
            }
            return key;
        }

        private boolean link(Field field, String key) {
            if (linked.putIfAbsent(key, field) != null) return false;
            outputs.put(field, key);
            return true;
        }
    }

    private static final class Request {
        final String host;
        final String owner;
        final String source;
        final String method;
        final String destination;
        final List<String> parameterTypes = new ArrayList<>();
        final Object[] inputs;
        final Map<String, Object> expected = new LinkedHashMap<>();
        final Consumer<Exception> complete;
        Activity invoked;
        boolean invoking;
        boolean observed;
        long deadline;

        Request(Bundle descriptor, Bundle edited, long sourceWait, Consumer<Exception> complete) throws JSONException {
            deadline = SystemClock.uptimeMillis() + sourceWait;
            host = descriptor.getString(HOST);
            source = descriptor.getString(SOURCE);
            method = descriptor.getString(METHOD);
            destination = descriptor.getString(DESTINATION);
            Bundle original = descriptor.getBundle(ARGUMENTS);
            if (!field(host, MAX_VALUE) || source == null || source.length() > MAX_VALUE
                    || !field(method, MAX_VALUE) || !field(destination, MAX_VALUE)
                    || original == null || edited == null || !original.keySet().equals(edited.keySet())) {
                throw new IllegalArgumentException("Invalid Fragment descriptor");
            }
            String invocation = descriptor.getString(INVOCATION);
            if (invocation == null) {
                owner = host;
                inputs = legacyInputs(descriptor, original, edited);
            } else {
                if (!field(invocation, MAX_VALUE)) throw new IllegalArgumentException("Invalid invocation");
                JSONObject saved = new JSONObject(invocation);
                owner = saved.getString("owner");
                JSONArray types = saved.getJSONArray("types");
                JSONArray values = saved.getJSONArray("values");
                JSONArray bindings = saved.getJSONArray("bindings");
                if (types.length() > MAX_PARAMETERS || types.length() != values.length()
                        || bindings.length() != original.size() || bindings.length() > MAX_FIELDS) {
                    throw new IllegalArgumentException("Invalid invocation inputs");
                }
                inputs = new Object[values.length()];
                for (int index = 0; index < inputs.length; index++) {
                    parameterTypes.add(types.getString(index));
                    inputs[index] = decodeValue(values.getJSONArray(index));
                }
                for (int index = 0; index < bindings.length(); index++) {
                    JSONArray link = bindings.getJSONArray(index);
                    int parameter = link.getInt(0);
                    String editorKey = link.getString(2);
                    if (parameter < 0 || parameter >= inputs.length || !edited.containsKey(editorKey)) {
                        throw new IllegalArgumentException("Invalid invocation mapping");
                    }
                    Object value;
                    if (link.isNull(1)) {
                        value = coerce(edited.get(editorKey), inputs[parameter]);
                        inputs[parameter] = value;
                    } else {
                        Bundle options = (Bundle) inputs[parameter];
                        String key = link.getString(1);
                        value = coerce(edited.get(editorKey), options.get(key));
                        put(options, key, value);
                    }
                    if (!link.isNull(3)) expected.put(link.getString(3), value);
                }
            }
            if (expected.isEmpty()) throw new IllegalArgumentException("Missing destination evidence");
            this.complete = complete;
        }

        private Object[] legacyInputs(Bundle descriptor, Bundle original, Bundle edited) throws JSONException {
            if (original.size() != 1) throw new IllegalArgumentException("Invalid legacy invocation");
            String key = original.keySet().iterator().next();
            String value = (String) coerce(edited.get(key), original.getString(key));
            expected.put(key, value);
            Bundle fixed = descriptor.containsKey("fixed_bundle")
                    ? decodeBundle(new JSONObject(descriptor.getString("fixed_bundle"))) : null;
            if (fixed != null && fixed.containsKey(key)) fixed.putString(key, value);
            parameterTypes.add(String.class.getName());
            parameterTypes.add(Bundle.class.getName());
            if (!descriptor.containsKey("boolean_argument")) return new Object[] { value, fixed };
            parameterTypes.add(boolean.class.getName());
            return new Object[] { value, fixed, descriptor.getBoolean("boolean_argument") };
        }

        Method resolve(Class<?> type) throws NoSuchMethodException {
            for (Class<?> candidate = type; appActivity(candidate); candidate = candidate.getSuperclass()) {
                if (!owner.equals(candidate.getName())) continue;
                for (Method member : candidate.getDeclaredMethods()) {
                    if (!method.equals(member.getName()) || !eligible(member)
                            || !canInvoke(type, member)) continue;
                    if (hasRecordedTypes(member)) return member;
                }
                break;
            }
            throw new NoSuchMethodException("Recorded Fragment navigation method changed");
        }

        private boolean hasRecordedTypes(Method member) {
            Class<?>[] types = member.getParameterTypes();
            if (types.length != parameterTypes.size()) return false;
            for (int index = 0; index < types.length; index++) {
                if (!types[index].getName().equals(parameterTypes.get(index))) return false;
            }
            return true;
        }
    }
}
