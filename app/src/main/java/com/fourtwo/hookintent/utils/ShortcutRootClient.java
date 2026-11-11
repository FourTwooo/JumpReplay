package com.fourtwo.hookintent.utils;

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
import com.fourtwo.hookintent.service.ShortcutRootService;
import java.util.function.Consumer;

/** A cancellable connection to the main-process Root owner; never starts MainActivity. */
public final class ShortcutRootClient implements ServiceConnection {
    private final Context app;
    private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
    private Messenger service;
    private boolean bound;
    private boolean closed;
    private boolean ready;
    private Consumer<Exception> connectionCallback;
    private Consumer<Exception> launchCallback;

    public ShortcutRootClient(Context context) { app = context.getApplicationContext(); }

    public void connect(Consumer<Exception> complete) {
        if (closed || bound) throw new IllegalStateException("Root 连接已使用");
        connectionCallback = complete;
        try {
            bound = app.bindService(new Intent(app, ShortcutRootService.class), this, Context.BIND_AUTO_CREATE);
            if (!bound) fail(new IllegalStateException("无法连接 Root 中转服务"));
        } catch (RuntimeException error) { fail(error); }
    }

    @Override public void onServiceConnected(ComponentName name, IBinder binder) {
        if (closed) return;
        service = new Messenger(binder);
        try { send(ShortcutRootService.PREPARE, null); }
        catch (RemoteException | RuntimeException error) { fail(error); }
    }
    @Override public void onServiceDisconnected(ComponentName name) { fail(new IllegalStateException("Root 中转服务已断开")); }
    @Override public void onBindingDied(ComponentName name) { fail(new IllegalStateException("Root 中转服务绑定失效")); }
    @Override public void onNullBinding(ComponentName name) { fail(new IllegalStateException("Root 中转服务不可用")); }

    public void start(Intent target, Consumer<Exception> complete) {
        if (closed || !ready || service == null) { complete.accept(new IllegalStateException("Root 服务尚未连接")); return; }
        launchCallback = complete;
        try { send(ShortcutRootService.LAUNCH, target); }
        catch (RemoteException | RuntimeException error) { fail(error); }
    }

    private void send(int what, Intent target) throws RemoteException {
        Message request = Message.obtain(null, what);
        request.replyTo = replies;
        if (target != null) {
            Bundle data = new Bundle();
            data.putParcelable(ShortcutRootService.TARGET, new Intent(target));
            request.setData(data);
        }
        service.send(request);
    }

    private boolean receive(Message message) {
        if (closed) return true;
        String reason = message.getData().getString(ShortcutRootService.ERROR);
        Exception error = reason == null ? null : new IllegalStateException(reason);
        Consumer<Exception> callback;
        if (message.what == ShortcutRootService.PREPARE) {
            ready = error == null;
            callback = connectionCallback;
            connectionCallback = null;
        } else if (message.what == ShortcutRootService.LAUNCH) {
            callback = launchCallback;
            launchCallback = null;
        } else return true;
        if (callback != null) callback.accept(error);
        return true;
    }

    private void fail(Exception error) {
        Consumer<Exception> connection = connectionCallback;
        Consumer<Exception> launch = launchCallback;
        close();
        if (connection != null) connection.accept(error);
        if (launch != null) launch.accept(error);
    }

    public void close() {
        if (closed) return;
        closed = true;
        ready = false;
        service = null;
        connectionCallback = launchCallback = null;
        if (bound) {
            bound = false;
            app.unbindService(this);
        }
    }
}
