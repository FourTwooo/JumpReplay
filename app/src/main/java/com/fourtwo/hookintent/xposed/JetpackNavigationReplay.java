package com.fourtwo.hookintent.xposed;

import android.app.Activity;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

/** Records only framework navigation operations whose result can be observed. */
final class JetpackNavigationReplay {
    private static final String PREFIX = "androidx.navigation.";
    private static final String ROUTE_URI_PREFIX = "android-app://androidx.navigation/";
    private static final String ARGUMENTS = "arguments";
    private static final String ARGUMENT_TYPES = "argument_types";
    private static final String NULL_ARGUMENTS = "null_arguments";
    private static final String HOST = "host";
    private static final String CONTROLLER = "controller";
    private static final String GRAPH = "graph";
    private static final String SOURCE = "source";
    private static final String DESTINATION = "destination";
    private static final String ACTION_ID = "action_id";
    private static final String RESOLUTION = "resolution";
    private static final String ROUTE = "route";
    private static final String INVOCATION = "jumpReplayNavigationInvocation";
    private static final Pattern ROUTE_PARAMETER = Pattern.compile("\\{([A-Za-z0-9_][A-Za-z0-9_.-]{0,127})\\}");
    private static final int MAX_ARGUMENTS = 24;
    private static final int MAX_IDENTITY = 4096;
    private static final int MAX_ROUTE = 2048;
    private static final long WAIT_MS = 7000;
    private static final long RESULT_MS = 750;

    private final Handler main;
    private final BooleanSupplier capturing;
    private final Predicate<Activity> resumed;
    private final Map<Object, Controller> controllers = new WeakHashMap<>();
    private Request request;

    JetpackNavigationReplay(Handler main, BooleanSupplier capturing, Predicate<Activity> resumed) {
        this.main = main;
        this.capturing = capturing;
        this.resumed = resumed;
    }

    void install(Class<?> navController) throws ReflectiveOperationException {
        ClassLoader loader = navController.getClassLoader();
        Class<?> options = Class.forName(PREFIX + "NavOptions", false, loader);
        Class<?> extras = Class.forName(PREFIX + "Navigator$Extras", false, loader);
        Method idNavigation = optional(navController, "navigate", int.class, Bundle.class, options, extras);
        Method routeNavigation = optional(navController, "navigate", String.class, options, extras);
        if (idNavigation != null) installCall(idNavigation, true);
        if (routeNavigation != null) installCall(routeNavigation, false);
    }

    boolean register(Object navController, Activity owner) {
        return controllers.putIfAbsent(navController, new Controller(owner)) == null;
    }

    private void installCall(Method method, boolean byId) {
        XposedBridge.hookMethod(method, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (Looper.myLooper() != Looper.getMainLooper() || !capturing.getAsBoolean()) return;
                Controller state = controllers.get(param.thisObject);
                if (state == null || state.invocation != null || !resumed.test(state.owner.get())) return;
                // Explicit NavOptions and Navigator extras may change the transaction or carry Views.
                if (param.args[byId ? 2 : 1] != null || param.args[byId ? 3 : 2] != null) return;
                try {
                    Invocation current = begin(param.thisObject, state, param.args, byId);
                    if (current == null) return;
                    state.invocation = current;
                    param.setObjectExtra(INVOCATION, current);
                } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                    // This library shape still participates in observation-only capture.
                }
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                Invocation current = (Invocation) param.getObjectExtra(INVOCATION);
                if (current == null) return;
                Controller state = controllers.get(param.thisObject);
                if (state != null && state.invocation == current) state.invocation = null;
                if (current.delivery != null) current.delivery.accept(param.hasThrowable() ? null : current.result);
            }
        });
    }

    private Invocation begin(Object controller, Controller state, Object[] call, boolean byId)
            throws ReflectiveOperationException {
        Activity owner = state.owner.get();
        if (owner == null) return null;
        String graph = identity(get(controller, "getGraph"));
        Object source = get(controller, "getCurrentDestination");
        String sourceId = identity(source);
        if (graph == null || sourceId == null) return null;
        Bundle descriptor = new Bundle();
        descriptor.putString(HOST, owner.getClass().getName());
        descriptor.putString(CONTROLLER, controller.getClass().getName());
        descriptor.putString(GRAPH, graph);
        descriptor.putString(SOURCE, sourceId);
        if (!byId) {
            String route = (String) call[0];
            if (route == null || route.isEmpty() || route.length() > MAX_ROUTE) return null;
            return new Invocation(descriptor, route, null);
        }
        int id = (Integer) call[0];
        Object target = resolveId(controller, source, id);
        String resolved = identity(target);
        Bundle args = captureArguments((Bundle) call[1], descriptor);
        if (resolved == null || args == null) return null;
        descriptor.putInt(ACTION_ID, id);
        descriptor.putString(RESOLUTION, resolved);
        descriptor.putBoolean(NULL_ARGUMENTS, call[1] == null);
        return new Invocation(descriptor, null, target);
    }

    void observe(Object controller, Object destination, Object rawArguments, Consumer<Bundle> delivery) {
        Bundle actual = rawArguments instanceof Bundle ? (Bundle) rawArguments : Bundle.EMPTY;
        try {
            checkResult(controller, destination, actual);
            if (delivery == null) return;
            Controller state = controllers.get(controller);
            Invocation invocation = state == null ? null : state.invocation;
            if (invocation != null && invocation.delivery == null) {
                Bundle result = descriptorForResult(controller, invocation, destination, actual);
                if (result != null) {
                    invocation.result = result;
                    invocation.delivery = delivery;
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
            // A destination change can be reported even when it has no portable invocation.
        }
        if (delivery != null) delivery.accept(null);
    }

    private Bundle descriptorForResult(Object controller, Invocation invocation, Object destination, Bundle actual)
            throws ReflectiveOperationException {
        String expected = identity(destination);
        if (expected == null) return null;
        Bundle descriptor = invocation.descriptor;
        if (invocation.requestedRoute == null) {
            Object target = destinationLeaf(invocation.resolvedNode);
            if (destination != target) return null;
            if (!containsArguments(actual, descriptor.getBundle(ARGUMENTS), descriptor.getBundle(ARGUMENT_TYPES))) {
                return null;
            }
        } else {
            Object route = get(destination, "getRoute");
            if (!(route instanceof String)) return null;
            String template = (String) route;
            Bundle arguments = routeArguments(template, actual);
            if (arguments == null || !invocation.requestedRoute.equals(render(template, arguments))) return null;
            if (!expected.equals(identity(resolveRoute(controller, invocation.requestedRoute)))) return null;
            if (captureArguments(arguments, descriptor) == null) return null;
            descriptor.putString(ROUTE, template);
        }
        descriptor.putString(DESTINATION, expected);
        return descriptor;
    }

    void replay(Bundle descriptor, Bundle edited, Consumer<Exception> complete) {
        main.post(() -> {
            if (request != null) {
                complete.accept(new IllegalStateException("已有应用内导航重放等待执行"));
                return;
            }
            try {
                Bundle arguments = typedArguments(edited, descriptor.getBundle(ARGUMENT_TYPES));
                request = new Request(new Bundle(descriptor), arguments, complete);
                tick();
            } catch (RuntimeException failure) {
                complete.accept(failure);
            }
        });
    }

    private void tick() {
        Request waiting = request;
        if (waiting == null || waiting.controller != null) return;
        try {
            for (Map.Entry<Object, Controller> entry : controllers.entrySet()) {
                Activity owner = entry.getValue().owner.get();
                Object controller = entry.getKey();
                if (!matchesContext(controller, owner, waiting.descriptor)) continue;
                Object resolution = resolveReplay(controller, waiting);
                String expected = waiting.descriptor.getString(ROUTE) == null
                        ? waiting.descriptor.getString(RESOLUTION) : waiting.descriptor.getString(DESTINATION);
                if (expected == null || !expected.equals(identity(resolution))
                        || !equalIdentity(destinationLeaf(resolution), waiting.descriptor.getString(DESTINATION))) {
                    finish(waiting, new IllegalStateException("原导航入口已经变化"));
                    return;
                }
                if (waiting.controller != null) {
                    finish(waiting, new IllegalStateException("当前页面有多个匹配的导航控制器"));
                    return;
                }
                waiting.controller = controller;
            }
            if (waiting.controller != null) {
                // Finish scanning all active controllers before invoking application code.
                invoke(waiting);
                return;
            }
        } catch (ReflectiveOperationException | RuntimeException | LinkageError failure) {
            finish(waiting, new IllegalStateException("无法执行原导航调用", failure));
            return;
        }
        if (SystemClock.uptimeMillis() >= waiting.deadline) {
            finish(waiting, new IllegalStateException("请打开目标应用并返回记录中的来源页面后再重放"));
            return;
        }
        main.postDelayed(this::tick, 100);
    }

    private boolean matchesContext(Object controller, Activity owner, Bundle descriptor) {
        if (controller == null || owner == null || !resumed.test(owner) || owner.isFinishing()
                || owner.isDestroyed() || !owner.hasWindowFocus()
                || !owner.getClass().getName().equals(descriptor.getString(HOST))
                || !controller.getClass().getName().equals(descriptor.getString(CONTROLLER))) return false;
        try {
            return equalIdentity(get(controller, "getGraph"), descriptor.getString(GRAPH))
                    && equalIdentity(get(controller, "getCurrentDestination"), descriptor.getString(SOURCE));
        } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
            return false;
        }
    }

    private static Object resolveReplay(Object controller, Request request) throws ReflectiveOperationException {
        String route = request.descriptor.getString(ROUTE);
        if (route != null) return resolveRoute(controller, render(route, request.arguments));
        return resolveId(controller, get(controller, "getCurrentDestination"), request.descriptor.getInt(ACTION_ID));
    }

    private void invoke(Request waiting) throws ReflectiveOperationException {
        Object controller = waiting.controller;
        String template = waiting.descriptor.getString(ROUTE);
        if (template == null) {
            Bundle args = waiting.descriptor.getBoolean(NULL_ARGUMENTS) ? null : waiting.arguments;
            required(controller.getClass(), "navigate", int.class, Bundle.class)
                    .invoke(controller, waiting.descriptor.getInt(ACTION_ID), args);
        } else {
            required(controller.getClass(), "navigate", String.class)
                    .invoke(controller, render(template, waiting.arguments));
        }
        if (waiting.confirmed) finish(waiting, null);
        else main.postDelayed(() -> finish(waiting,
                waiting.confirmed ? null : new IllegalStateException("目标应用没有确认进入记录的页面")), RESULT_MS);
    }

    private void checkResult(Object controller, Object destination, Bundle arguments)
            throws ReflectiveOperationException {
        Request active = request;
        if (active == null || active.controller != controller) return;
        if (equalIdentity(destination, active.descriptor.getString(DESTINATION))
                && containsArguments(arguments, active.arguments, active.descriptor.getBundle(ARGUMENT_TYPES))) {
            active.confirmed = true;
        }
    }

    private void finish(Request active, Exception error) {
        if (request != active) return;
        request = null;
        active.complete.accept(error);
    }

    private static Object resolveId(Object controller, Object source, int requested)
            throws ReflectiveOperationException {
        if (source == null || requested == 0) return null;
        Object action = required(source.getClass(), "getAction", int.class).invoke(source, requested);
        int id = action == null ? requested : (Integer) get(action, "getDestinationId");
        if (id == 0) return null;
        // Jetpack's own resolution includes the current nested graph and inherited actions.
        return required(controller.getClass(), "findDestination", int.class).invoke(controller, id);
    }

    private static Object resolveRoute(Object controller, String route) throws ReflectiveOperationException {
        if (route == null || route.length() > MAX_ROUTE) return null;
        ClassLoader loader = controller.getClass().getClassLoader();
        Class<?> request = Class.forName(PREFIX + "NavDeepLinkRequest", false, loader);
        Class<?> builder = Class.forName(PREFIX + "NavDeepLinkRequest$Builder", false, loader);
        Object deepLink = get(required(builder, "fromUri", Uri.class)
                .invoke(null, Uri.parse(ROUTE_URI_PREFIX + route)), "build");
        Object graph = get(controller, "getGraph");
        Object match = required(graph.getClass(), "matchDeepLink", request).invoke(graph, deepLink);
        return match == null ? null : get(match, "getDestination");
    }

    private static Bundle routeArguments(String template, Bundle arguments) {
        if (template.length() > MAX_ROUTE) return null;
        Matcher tokens = ROUTE_PARAMETER.matcher(template);
        Bundle result = new Bundle();
        while (tokens.find()) {
            String key = tokens.group(1);
            Object value = arguments.get(key);
            if (typeOf(value) == null) return null;
            result.putSerializable(key, (java.io.Serializable) value);
            if (result.size() > MAX_ARGUMENTS) return null;
        }
        return result;
    }

    private static String render(String template, Bundle arguments) {
        if (template.length() > MAX_ROUTE) return null;
        Matcher tokens = ROUTE_PARAMETER.matcher(template);
        StringBuffer route = new StringBuffer();
        while (tokens.find()) {
            Object value = arguments.get(tokens.group(1));
            if (typeOf(value) == null) return null;
            tokens.appendReplacement(route, Matcher.quoteReplacement(Uri.encode(String.valueOf(value))));
            if (route.length() > MAX_ROUTE) return null;
        }
        tokens.appendTail(route);
        return route.length() <= MAX_ROUTE ? route.toString() : null;
    }

    private static String identity(Object node) throws ReflectiveOperationException {
        if (node == null) return null;
        StringBuilder path = new StringBuilder();
        for (int depth = 0; node != null; depth++) {
            if (depth == 16) return null;
            Method route = optional(node.getClass(), "getRoute");
            Object routeValue = route == null ? null : route.invoke(node);
            String routeText = routeValue instanceof String ? (String) routeValue : "";
            path.append(node.getClass().getName()).append('#').append(get(node, "getId"))
                    .append('/').append(routeText.length()).append(':').append(routeText).append(';');
            if (path.length() > MAX_IDENTITY) return null;
            node = get(node, "getParent");
        }
        return path.toString();
    }

    private static boolean equalIdentity(Object node, String expected) throws ReflectiveOperationException {
        return expected != null && expected.equals(identity(node));
    }

    private static Object destinationLeaf(Object node) throws ReflectiveOperationException {
        for (int depth = 0; node != null && depth < 16; depth++) {
            Method startId = optional(node.getClass(), "getStartDestinationId");
            if (startId == null) return node;
            int id = (Integer) startId.invoke(node);
            node = required(node.getClass(), "findNode", int.class, boolean.class).invoke(node, id, false);
        }
        return null;
    }

    private static Bundle captureArguments(Bundle original, Bundle descriptor) {
        Bundle args = original == null ? new Bundle() : new Bundle(original);
        if (args.size() > MAX_ARGUMENTS) return null;
        Bundle types = new Bundle();
        for (String key : args.keySet()) {
            if (key == null || key.isEmpty() || key.length() > 128) return null;
            String type = typeOf(args.get(key));
            if (type == null) return null;
            types.putString(key, type);
        }
        descriptor.putBundle(ARGUMENTS, args);
        descriptor.putBundle(ARGUMENT_TYPES, types);
        return args;
    }

    /** Restores numeric wrapper types when a stored capture has crossed the JSON archive. */
    private static Bundle typedArguments(Bundle input, Bundle schema) {
        if (schema == null || input == null || schema.size() > MAX_ARGUMENTS
                || !schema.keySet().equals(input.keySet())) {
            throw new IllegalArgumentException("重放参数与记录中的导航参数不匹配");
        }
        Bundle result = new Bundle();
        for (String key : schema.keySet()) {
            Object value = normalize(input.get(key), schema.getString(key));
            if (value == null) throw new IllegalArgumentException("重放参数类型与记录不一致");
            result.putSerializable(key, (java.io.Serializable) value);
        }
        return result;
    }

    private static boolean containsArguments(Bundle actual, Bundle expected, Bundle schema) {
        if (expected == null || schema == null) return false;
        for (String key : expected.keySet()) {
            Object normalized = normalize(actual.get(key), schema.getString(key));
            if (normalized == null || !normalized.equals(expected.get(key))) return false;
        }
        return true;
    }

    private static String typeOf(Object value) {
        if (value instanceof String && ((String) value).length() <= 512) return "String";
        if (value instanceof Boolean) return "Boolean";
        if (value instanceof Integer) return "Integer";
        if (value instanceof Long) return "Long";
        if (value instanceof Float && Float.isFinite((Float) value)) return "Float";
        if (value instanceof Double && Double.isFinite((Double) value)) return "Double";
        return null;
    }

    private static Object normalize(Object value, String type) {
        if (type == null) return null;
        if (type.equals(typeOf(value))) return value;
        if ("Long".equals(type) && value instanceof Integer) return ((Integer) value).longValue();
        if ("Float".equals(type) && (value instanceof Double || value instanceof Integer || value instanceof Long)) {
            float number = ((Number) value).floatValue();
            return Float.isFinite(number) ? number : null;
        }
        if ("Double".equals(type) && (value instanceof Integer || value instanceof Long)) {
            return ((Number) value).doubleValue();
        }
        return null;
    }

    private static Object get(Object receiver, String method) throws ReflectiveOperationException {
        return required(receiver.getClass(), method).invoke(receiver);
    }

    private static Method required(Class<?> type, String name, Class<?>... parameters)
            throws NoSuchMethodException {
        Method method = type.getMethod(name, parameters);
        if (!java.lang.reflect.Modifier.isPublic(method.getDeclaringClass().getModifiers())) {
            method.setAccessible(true);
        }
        return method;
    }

    private static Method optional(Class<?> type, String name, Class<?>... parameters) {
        try {
            return required(type, name, parameters);
        } catch (NoSuchMethodException missing) {
            return null;
        }
    }

    private static final class Controller {
        final WeakReference<Activity> owner;
        Invocation invocation;

        Controller(Activity activity) {
            owner = new WeakReference<>(activity);
        }
    }

    private static final class Invocation {
        final Bundle descriptor;
        final String requestedRoute;
        final Object resolvedNode;
        Bundle result;
        Consumer<Bundle> delivery;

        Invocation(Bundle descriptor, String requestedRoute, Object resolvedNode) {
            this.descriptor = descriptor;
            this.requestedRoute = requestedRoute;
            this.resolvedNode = resolvedNode;
        }
    }

    private static final class Request {
        final Bundle descriptor;
        final Bundle arguments;
        final Consumer<Exception> complete;
        final long deadline = SystemClock.uptimeMillis() + WAIT_MS;
        Object controller;
        boolean confirmed;

        Request(Bundle descriptor, Bundle arguments, Consumer<Exception> complete) {
            this.descriptor = descriptor;
            this.arguments = arguments;
            this.complete = complete;
        }
    }
}
