package com.fourtwo.hookintent.replay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.ParcelFileDescriptor;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.service.MessengerService;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Iterator;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeoutException;

/** Serial editor connection. UI receives primitive metadata only, never foreign objects. */
public final class ExtrasClient {
    public interface Callback { void complete(Bundle result, Exception error); }
    private final Context context;
    private final Bundle record;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private volatile boolean closed;
    private boolean bound, discovered, sandboxBound, sandbox;
    private Messenger remote;
    private String session;
    private Callback opening;
    private volatile ParcelFileDescriptor activeInput, activeOutput;
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        if (message.what != MessengerService.MSG_REPLY || discovered || closed) return true;
        if (message.sendingUid != android.os.Process.myUid()) return true;
        Bundle result = message.getData();
        if (result.getInt("requestType") != ExtrasRuntime.DISCOVER) return true;
        discovered = true;
        IBinder endpoint = result.getBinder("editor_endpoint");
        if (endpoint != null) remote = new Messenger(endpoint);
        startOpen();
        return true;
    }));
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed || discovered) return;
            Message request = Message.obtain(null, ExtrasRuntime.DISCOVER);
            request.arg1 = 1; request.replyTo = replies;
            Bundle data = new Bundle();
            data.putString("packageName", record.getString("packageName"));
            data.putString("processName", record.getString("processName"));
            request.setData(data);
            try { new Messenger(binder).send(request); }
            catch (Exception error) { failOpening(error); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (!discovered) failOpening(new IllegalStateException("主应用编辑服务连接已断开"));
        }
        @Override public void onNullBinding(ComponentName name) { offlineOpen(); }
    };
    private final ServiceConnection sandboxConnection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed || opening == null) return;
            remote = new Messenger(binder); sandbox = true;
            performOpen();
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            if (opening != null) failOpening(new IllegalStateException("隔离编辑进程已退出，请重新打开参数编辑器"));
        }
        @Override public void onNullBinding(ComponentName name) { failOpening(new IllegalStateException("无法连接隔离编辑进程")); }
    };

    public ExtrasClient(Context context, Bundle captureRecord) {
        this.context = context.getApplicationContext(); record = new Bundle(captureRecord);
    }
    public void open(Callback callback) {
        main.post(() -> {
            if (closed) { callback.complete(null, new IllegalStateException("参数编辑器已关闭")); return; }
            if (opening != null || session != null) { callback.complete(null, new IllegalStateException("参数编辑器已经连接")); return; }
            opening = callback;
            try {
                Intent intent = new Intent().setClassName(ExtrasRuntime.HOST, MessengerService.class.getName());
                bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
                if (!bound) offlineOpen();
                else main.postDelayed(() -> { if (!discovered && !closed) offlineOpen(); }, 3000);
            } catch (Exception error) { failOpening(error); }
        });
    }
    private void offlineOpen() { if (!discovered && !closed) { discovered = true; startOpen(); } }
    private void failOpening(Exception error) {
        discovered = true;
        Callback callback = opening; opening = null;
        if (callback != null && !closed) callback.complete(null, error);
    }
    private void startOpen() {
        if (remote == null) {
            try {
                sandboxBound = context.bindService(new Intent(context, ExtrasSandboxService.class), sandboxConnection, Context.BIND_AUTO_CREATE);
                if (!sandboxBound) failOpening(new IllegalStateException("无法启动隔离参数编辑进程"));
                else main.postDelayed(() -> { if (!closed && opening != null) failOpening(new TimeoutException("隔离编辑进程启动超时")); }, 10000);
            } catch (Exception error) { failOpening(error); }
        } else performOpen();
    }
    private void performOpen() {
        Callback callback = opening; opening = null;
        request("open", null, null, false, null, callback);
    }
    public void children(String node, Callback callback) { request("children", node, null, false, null, callback); }
    public void set(String node, String text, boolean makeNull, Callback callback) {
        request("set", node, text, makeNull, null, callback);
    }
    public void setTyped(String node, String type, String text, boolean makeNull, Callback callback) {
        request("set", node, text, makeNull, type, callback);
    }
    public void merge(String text, String originalText, Callback callback) {
        request("merge", null, text, false, null, originalText, callback);
    }
    private void request(String operation, String node, String text, boolean makeNull, String type, Callback callback) {
        request(operation, node, text, makeNull, type, null, callback);
    }
    private void request(String operation, String node, String text, boolean makeNull, String type, String originalText, Callback callback) {
        main.post(() -> {
            if (closed) { if (callback != null) callback.complete(null, new IllegalStateException("参数编辑器已关闭")); return; }
            final boolean[] finished = {false};
            Runnable timeout = () -> {
                if (finished[0] || closed) return;
                finished[0] = true;
                closeActive();
                if (callback != null) callback.complete(null, new TimeoutException("参数处理超时，请重新打开编辑器"));
            };
            main.postDelayed(timeout, 30000);
            worker.execute(() -> {
                if (closed) { main.removeCallbacks(timeout); return; }
                Bundle result = null; Exception failure = null;
                try {
                    JSONObject data = new JSONObject().put("op", operation);
                    if ("open".equals(operation)) {
                        String parcel = record.getString(IntentPayloadCodec.KEY);
                        if (parcel == null) parcel = IntentPayloadCodec.encode(IntentPayloadCodec.decode(record));
                        data.put("parcel", parcel);
                        Bundle prepared = sandbox ? ReplayClassLoaders.prepare(context, record) : record;
                        JSONObject metadata = new JSONObject();
                        for (String key : new String[]{"packageName", "componentPackageName", "package", "processName", "source_last_update", "source_uid", "source_app_uid", "capture_sdk", "loader_dex_paths", "loader_native_paths"}) {
                            Object value = prepared.get(key);
                            if (value instanceof String[]) metadata.put(key, new org.json.JSONArray(java.util.Arrays.asList((String[])value)));
                            else if (value != null) metadata.put(key, value);
                        }
                        data.put("record", metadata);
                    } else data.put("session", session);
                    if (node != null) data.put("node", node);
                    if (text != null) data.put("text", text);
                    if (originalText != null) data.put("originalText", originalText);
                    if (type != null) data.put("type", type);
                    data.put("null", makeNull);
                    if (remote == null) throw new IllegalStateException("参数编辑进程尚未连接");
                    JSONObject response = exchange(data);
                    if (!response.optBoolean("ok")) throw new IllegalStateException(response.optString("error", "参数处理失败"));
                    if (response.has("session")) session = response.getString("session");
                    result = new Bundle();
                    for (Iterator<String> keys = response.keys(); keys.hasNext();) {
                        String key = keys.next(); Object value = response.get(key);
                        if (value instanceof String) result.putString(key, (String)value);
                    }
                    result.putString("environment", sandbox ? "installed_apk" : "app_process");
                } catch (Throwable error) {
                    if (error instanceof VirtualMachineError) throw (VirtualMachineError)error;
                    if (error instanceof ThreadDeath) throw (ThreadDeath)error;
                    String detail = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                    failure = new IllegalStateException("参数处理失败（" + (sandbox ? "隔离进程" : "原应用进程") + "）：" + detail, error);
                    android.util.Log.w("JumpReplayExtras", "op=" + operation + ", source=" + record.getString("packageName")
                            + ", process=" + record.getString("processName") + ", sandbox=" + sandbox, failure);
                }
                Bundle answer = result; Exception error = failure;
                main.post(() -> {
                    main.removeCallbacks(timeout);
                    if (closed || finished[0]) return;
                    finished[0] = true;
                    if (callback != null) callback.complete(answer, error);
                });
            });
        });
    }
    private JSONObject exchange(JSONObject request) throws Exception {
        boolean cleanup = "close".equals(request.optString("op"));
        if (closed && !cleanup) throw new IllegalStateException("参数编辑器已关闭");
        ParcelFileDescriptor[] input = ParcelFileDescriptor.createPipe();
        ParcelFileDescriptor[] output = ParcelFileDescriptor.createPipe();
        activeInput = output[0]; activeOutput = input[1];
        try {
            if (closed && !cleanup) throw new IllegalStateException("参数编辑器已关闭");
            Message message = Message.obtain(null, ExtrasRuntime.REQUEST);
            Bundle data = new Bundle(); data.putParcelable("input", input[0]); data.putParcelable("output", output[1]);
            message.setData(data); remote.send(message);
            close(input[0]); close(output[1]);
            try (OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(input[1])) { ExtrasRuntime.writeJson(stream, request); }
            try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(output[0])) { return ExtrasRuntime.readJson(stream); }
        } finally { for (ParcelFileDescriptor fd : input) close(fd); for (ParcelFileDescriptor fd : output) close(fd); activeInput = null; activeOutput = null; }
    }
    public void close() {
        main.post(() -> {
            if (closed) return;
            closed = true; opening = null; main.removeCallbacksAndMessages(null); closeActive();
            if (bound) { try { context.unbindService(connection); } catch (Exception ignored) { } bound = false; }
            worker.execute(() -> {
                Runnable cancelCleanup = this::closeActive;
                main.postDelayed(cancelCleanup, 2000);
                try {
                    if (session != null) {
                        JSONObject request = new JSONObject().put("op", "close").put("session", session);
                        if (remote != null) exchange(request);
                    }
                } catch (Exception ignored) { }
                finally {
                    main.removeCallbacks(cancelCleanup); closeActive();
                    main.post(() -> {
                        if (sandboxBound) { try { context.unbindService(sandboxConnection); } catch (Exception ignored) { } sandboxBound = false; }
                    });
                    session = null;
                }
            });
            worker.shutdown();
        });
    }
    private void closeActive() { close(activeInput); close(activeOutput); }
    private static void close(ParcelFileDescriptor descriptor) { if (descriptor != null) try { descriptor.close(); } catch (Exception ignored) { } }
}
