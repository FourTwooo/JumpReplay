package com.fourtwo.hookintent.replay;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import android.os.SystemClock;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Reverse RPC endpoint injected into an application. Large data travels through private pipes. */
public final class ExtrasRuntime {
    public static final int DISCOVER = 200;
    public static final int REQUEST = 201;
    public static final String HOST = "com.fourtwo.hookintent";
    private final Context context;
    private final ClassLoader runtimeLoader;
    private final boolean sandbox;
    private final java.util.concurrent.CopyOnWriteArrayList<ClassLoader> capturedLoaders = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Map<String, Entry> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private final Handler lifecycle = new Handler(Looper.getMainLooper());
    private volatile boolean disposed;
    private final Runnable expire = new Runnable() {
        @Override public void run() {
            if (disposed) return;
            long now = SystemClock.elapsedRealtime();
            for (Map.Entry<String, Entry> entry : sessions.entrySet())
                if (now - entry.getValue().touched > IDLE_MS) sessions.remove(entry.getKey(), entry.getValue());
            lifecycle.postDelayed(this, 60000);
        }
    };
    private final Messenger endpoint;
    private static final long IDLE_MS = 10 * 60 * 1000L;
    private static final class Entry {
        final ExtrasSession session;
        final ClassLoader loader;
        volatile long touched = SystemClock.elapsedRealtime();
        Entry(ExtrasSession session, ClassLoader loader) { this.session = session; this.loader = loader; }
    }

    public ExtrasRuntime(Context context, ClassLoader loader) {
        this(context, loader, false);
    }
    public ExtrasRuntime(Context context, ClassLoader loader, boolean sandbox) {
        this.context = context;
        this.sandbox = sandbox;
        runtimeLoader = loader;
        endpoint = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
        lifecycle.postDelayed(expire, 60000);
    }
    public IBinder binder() { return endpoint.getBinder(); }
    public static Messenger endpoint(Context context) {
        return new ExtrasRuntime(context, context.getClassLoader()).endpoint;
    }
    public void dispose() {
        disposed = true; lifecycle.removeCallbacks(expire); sessions.clear(); worker.shutdownNow();
    }
    /** Retains the actual loaders used by dynamically loaded top-level extras. */
    public void remember(Intent intent) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return;
            rememberLoader(extras.getClassLoader());
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                if (value != null) rememberLoader(value.getClass().getClassLoader());
            }
        } catch (Throwable ignored) { /* Display extraction may also fail; the original snapshot is still captured. */ }
    }
    private void rememberLoader(ClassLoader loader) {
        if (loader != null && loader != runtimeLoader && !capturedLoaders.contains(loader)) capturedLoaders.add(0, loader);
    }
    private ClassLoader liveLoader() {
        if (runtimeLoader == null) return null;
        return new ClassLoader(android.content.Intent.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                for (ClassLoader loader : capturedLoaders) {
                    try { return loader.loadClass(name); } catch (ClassNotFoundException ignored) { }
                }
                return runtimeLoader.loadClass(name);
            }
        };
    }

    private boolean receive(Message message) {
        if (message.what != REQUEST) return true;
        boolean authorized = false;
        try { authorized = message.sendingUid == (sandbox ? context.getApplicationInfo().uid
                : context.getPackageManager().getApplicationInfo(HOST, 0).uid); }
        catch (Exception ignored) { }
        // Authenticate before touching a foreign Bundle: even unpacking it can execute Parcelable code.
        if (!authorized || disposed) return true;
        ParcelFileDescriptor receivedInput = null, receivedOutput = null;
        try {
            Bundle data = message.getData();
            data.setClassLoader(ParcelFileDescriptor.class.getClassLoader());
            receivedInput = data.getParcelable("input");
            receivedOutput = data.getParcelable("output");
        } catch (RuntimeException error) { close(receivedInput); close(receivedOutput); return true; }
        final ParcelFileDescriptor input = receivedInput, output = receivedOutput;
        if (input == null || output == null) { close(input); close(output); return true; }
        worker.execute(() -> {
            JSONObject result;
            try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(input)) {
                result = process(readJson(stream));
                result.put("ok", true);
            } catch (Throwable error) {
                if (error instanceof VirtualMachineError) throw (VirtualMachineError)error;
                if (error instanceof ThreadDeath) throw (ThreadDeath)error;
                result = failure(error);
            }
            try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(output)) {
                writeJson(stream, result);
            } catch (Exception ignored) { /* UI timeout/close cancelled the pipe. */ }
        });
        return true;
    }

    /** Also used by the offline installed-APK client; called only on a serial worker. */
    public JSONObject process(JSONObject request) throws Exception {
        if (disposed) throw new IllegalStateException("参数编辑器已关闭");
        long now = SystemClock.elapsedRealtime();
        Iterator<Entry> iterator = sessions.values().iterator();
        while (iterator.hasNext()) if (now - iterator.next().touched > IDLE_MS) iterator.remove();
        String op = request.getString("op");
        JSONObject result = new JSONObject();
        if ("open".equals(op)) {
            Bundle record = new Bundle();
            JSONObject metadata = request.getJSONObject("record");
            for (Iterator<String> keys = metadata.keys(); keys.hasNext();) {
                String key = keys.next(); Object value = metadata.get(key);
                if (value instanceof Integer) record.putInt(key, (Integer)value);
                else if (value instanceof Number) record.putLong(key, ((Number)value).longValue());
                else if (value instanceof String) record.putString(key, (String)value);
                else if (value instanceof JSONArray) {
                    JSONArray array = (JSONArray)value; String[] strings = new String[array.length()];
                    for (int i = 0; i < strings.length; i++) strings[i] = array.getString(i);
                    record.putStringArray(key, strings);
                }
            }
            if (sandbox && !record.containsKey("loader_dex_paths")) throw new IllegalArgumentException("缺少来源应用加载信息");
            ClassLoader loader = ReplayClassLoaders.resolve(context, record, liveLoader());
            Entry entry = new Entry(new ExtrasSession(request.getString("parcel"), loader), loader);
            String id = UUID.randomUUID().toString();
            sessions.put(id, entry);
            result.put("session", id);
            fill(result, entry);
        } else {
            String id = request.getString("session");
            Entry entry = sessions.get(id);
            if (entry == null) throw new IllegalStateException("编辑连接已失效，请重新打开参数编辑器");
            entry.touched = now;
            if ("children".equals(op)) result.put("nodes", entry.session.children(request.optString("node", "root")).toString());
            else if ("set".equals(op)) {
                if (request.has("type")) entry.session.setTyped(request.getString("node"), request.getString("type"),
                        request.optString("text"), request.optBoolean("null"));
                else entry.session.set(request.getString("node"), request.optString("text"), request.optBoolean("null"));
                fill(result, entry);
            } else if ("merge".equals(op)) {
                entry.session.merge(request.getString("text"), request.optString("originalText", ""));
                fill(result, entry);
            } else if ("close".equals(op)) sessions.remove(id);
            else throw new IllegalArgumentException("未知参数编辑操作");
        }
        return result;
    }

    private static void fill(JSONObject result, Entry entry) throws Exception {
        result.put("parcel", entry.session.parcel());
        result.put("uri", entry.session.uri());
        Bundle record = new Bundle(); record.putString(IntentPayloadCodec.KEY, entry.session.parcel());
        Intent intent = IntentPayloadCodec.decode(record); intent.setExtrasClassLoader(entry.loader);
        JSONArray projection = new JSONArray();
        Bundle extras = intent.getExtras();
        if (extras != null) {
            extras.setClassLoader(entry.loader);
            for (String key : extras.keySet()) {
                Object value = extras.get(key);
                projection.put(new JSONObject().put("key", key)
                        .put("class", value == null ? "null" : value.getClass().getName())
                        .put("value", display(value, 0)));
            }
        }
        result.put("intentExtras", projection.toString());
    }
    /** Metadata only. Never execute application-defined toString/getters during export. */
    private static Object display(Object value, int depth) throws Exception {
        if (value == null) return JSONObject.NULL;
        Class<?> type = value.getClass();
        if (type == String.class || type == Boolean.class || type == Integer.class || type == Long.class
                || type == Short.class || type == Byte.class) return value;
        if (type == Double.class) return Double.isFinite((Double)value) ? value : value.toString();
        if (type == Float.class) return Float.isFinite((Float)value) ? value : value.toString();
        if (type == Character.class) return value.toString();
        if (value instanceof android.net.Uri) return value.toString();
        if (value instanceof android.content.ComponentName) return ((android.content.ComponentName)value).flattenToString();
        if (depth < 8 && type.isArray()) {
            JSONArray values = new JSONArray();
            for (int i = 0; i < java.lang.reflect.Array.getLength(value); i++) values.put(display(java.lang.reflect.Array.get(value, i), depth + 1));
            return values;
        }
        if (depth < 8 && value instanceof java.util.List) {
            JSONArray values = new JSONArray();
            for (Object item : (java.util.List<?>)value) values.put(display(item, depth + 1));
            return values;
        }
        return "<" + type.getName() + ">";
    }
    public static JSONObject readJson(InputStream stream) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
        while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }
    public static void writeJson(OutputStream stream, JSONObject json) throws Exception {
        stream.write(json.toString().getBytes(StandardCharsets.UTF_8)); stream.flush();
    }
    public static JSONObject failure(Throwable error) {
        JSONObject result = new JSONObject();
        try { result.put("ok", false); result.put("error", error.getClass().getSimpleName() + ": " + error.getMessage()); }
        catch (Exception ignored) { }
        return result;
    }
    private static void close(ParcelFileDescriptor descriptor) { if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) { } }
}
