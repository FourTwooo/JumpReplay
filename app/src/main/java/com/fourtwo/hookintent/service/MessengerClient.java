package com.fourtwo.hookintent.service;

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
import android.os.RemoteException;
import android.util.Log;
import androidx.annotation.Nullable;
import com.fourtwo.hookintent.base.DataConverter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

/** One connection per injected process; requests complete or report a timeout. */
public class MessengerClient {
    private static final long REQUEST_TIMEOUT_MS = 15000;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger ids = new AtomicInteger();
    private final Map<Integer, Request> requests = new HashMap<>();
    private Messenger service;
    private boolean binding;
    private boolean closed;
    private PassiveCallback passiveCallback;
    private ConnectionCallback connectionCallback;
    private com.fourtwo.hookintent.replay.ExtrasRuntime extrasRuntime;
    private final Messenger incoming = new Messenger(new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) {
            if (msg.what == MessengerService.MSG_REPLY) {
                Request request = requests.remove(msg.getData().getInt("requestId"));
                if (request != null && request.callback != null) request.callback.onResult(msg.getData());
            } else if (msg.what == MessengerService.MSG_NOTIFY_HOOK_STATE && passiveCallback != null) {
                passiveCallback.onMessageReceived(msg.getData());
            }
        }
    });
    public interface ResultCallback { void onResult(Bundle result); void onError(Exception error); }
    public interface PassiveCallback { void onMessageReceived(Bundle data); }
    public interface ConnectionCallback { void onConnected(); void onDisconnected(); }
    private static final class Request {
        final int id, type;
        final Bundle data;
        final boolean needReply;
        final ResultCallback callback;
        boolean sent;
        Request(int id, int type, Bundle data, boolean needReply, ResultCallback callback) {
            this.id = id; this.type = type; this.data = data;
            this.needReply = needReply; this.callback = callback;
        }
    }
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (closed) return;
            service = new Messenger(binder);
            binding = true;
            try {
                Message register = Message.obtain(null, MessengerService.MSG_REGISTER_CLIENT);
                register.replyTo = incoming;
                Bundle info = new Bundle();
                info.putString("packageName", context.getPackageName());
                info.putInt("uid", context.getApplicationInfo().uid);
                info.putString("className", context.getApplicationInfo().className);
                info.putString("processName", DataConverter.getCurrentProcessName(context));
                if (extrasRuntime != null) {
                    info.putInt("editor_version", 2);
                    info.putBinder("editor_endpoint", extrasRuntime.binder());
                }
                register.setData(info);
                service.send(register);
                if (connectionCallback != null) connectionCallback.onConnected();
                flush();
            } catch (Exception error) { disconnected(error); }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            disconnected(new RemoteException("Capture service disconnected"));
        }
        @Override public void onBindingDied(ComponentName name) {
            releaseConnection();
            disconnected(new RemoteException("Capture binding died"));
            main.postDelayed(MessengerClient.this::connect, 1000);
        }
        @Override public void onNullBinding(ComponentName name) {
            releaseConnection();
            disconnected(new RemoteException("Capture service returned a null binder"));
            main.postDelayed(MessengerClient.this::connect, 1000);
        }
    };
    public MessengerClient(Context context) { this.context = context; }
    /** Enable before registering; editor availability is independent of the capture switch. */
    public void enableExtrasEditing(ClassLoader loader) {
        extrasRuntime = new com.fourtwo.hookintent.replay.ExtrasRuntime(context, loader);
    }
    public void rememberExtrasLoaders(Intent intent) {
        if (extrasRuntime != null) extrasRuntime.remember(intent);
    }
    public void setConnectionCallback(ConnectionCallback callback) { main.post(() -> connectionCallback = callback); }
    public void registerPassiveCallback(PassiveCallback callback) {
        main.post(() -> { passiveCallback = callback; closed = false; connect(); });
    }
    private void connect() {
        if (closed || binding) return;
        Intent intent = new Intent().setClassName("com.fourtwo.hookintent", "com.fourtwo.hookintent.service.MessengerService");
        try {
            binding = context.bindService(intent, connection, Context.BIND_AUTO_CREATE);
            if (!binding) {
                releaseConnection();
                disconnected(new IllegalStateException("Capture service is not available"));
                main.postDelayed(this::connect, 1000);
            }
        } catch (Exception error) {
            releaseConnection();
            disconnected(error);
            main.postDelayed(this::connect, 1000);
        }
    }
    private void disconnected(Exception error) {
        service = null;
        for (Request request : new ArrayList<>(requests.values())) {
            if (request.sent) {
                requests.remove(request.id);
                if (request.callback != null) request.callback.onError(error);
            }
        }
        if (connectionCallback != null) connectionCallback.onDisconnected();
        Log.w("MessengerClient", error.getMessage());
    }
    private void releaseConnection() {
        try { context.unbindService(connection); } catch (IllegalArgumentException ignored) { }
        binding = false;
    }
    public void unbindService() {
        main.post(() -> {
            closed = true;
            if (service != null) {
                try {
                    Message unregister = Message.obtain(null, MessengerService.MSG_UNREGISTER_CLIENT);
                    unregister.replyTo = incoming;
                    service.send(unregister);
                } catch (RemoteException ignored) { }
            }
            releaseConnection();
            disconnected(new IllegalStateException("Capture client closed"));
            for (Request request : new ArrayList<>(requests.values())) {
                requests.remove(request.id);
                if (request.callback != null) request.callback.onError(new IllegalStateException("Capture client closed"));
            }
        });
    }
    public void sendMessageAsync(int type, Bundle data, boolean needReply, @Nullable ResultCallback callback) {
        main.post(() -> {
            if (closed) { if (callback != null) callback.onError(new IllegalStateException("Capture client closed")); return; }
            int id = ids.incrementAndGet();
            Request request = new Request(id, type, data == null ? null : new Bundle(data), needReply, callback);
            requests.put(id, request);
            main.postDelayed(() -> {
                Request pending = requests.remove(id);
                if (pending != null && pending.callback != null)
                    pending.callback.onError(new java.util.concurrent.TimeoutException("Capture request timed out"));
            }, REQUEST_TIMEOUT_MS);
            if (service == null) connect(); else flush();
        });
    }
    private void flush() {
        if (service == null) return;
        for (Request request : new ArrayList<>(requests.values())) {
            if (request.sent || service == null) continue;
            Message message = Message.obtain(null, request.type);
            message.arg1 = request.id;
            message.replyTo = request.needReply ? incoming : null;
            if (request.data != null) message.setData(request.data);
            try {
                request.sent = true;
                service.send(message);
                if (!request.needReply) {
                    requests.remove(request.id);
                    if (request.callback != null) request.callback.onResult(new Bundle());
                }
            } catch (Exception error) {
                requests.remove(request.id);
                if (request.callback != null) request.callback.onError(error);
                Log.w("MessengerClient", "Capture send failed", error);
            }
        }
    }
}
