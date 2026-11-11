package com.fourtwo.hookintent.utils;

import android.content.Intent;
import android.os.Handler;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/** One pending launch per shortcut Activity, cancelled when replaced or destroyed. */
public final class ShortcutLaunchCoordinator {
    public enum Mode { NORMAL, ROOT, SHIZUKU, ASSISTANT }
    public interface Events {
        void binderChanged();
        void permissionResult(int requestCode, boolean granted);
    }
    public interface Backend {
        void listen(Events events);
        void unlisten(Events events);
        boolean binderReady();
        boolean permissionGranted();
        void requestPermission(int requestCode);
        void connectRoot(Consumer<Exception> complete);
        void start(Intent intent, Mode mode, Consumer<Exception> complete);
    }
    public interface Callback {
        void waiting(String message);
        void complete(Exception error);
    }
    private static final AtomicInteger REQUESTS = new AtomicInteger(20000);
    private static final long CONNECTION_TIMEOUT_MS = 20000;
    private static final long PERMISSION_TIMEOUT_MS = 120000;
    private final Backend backend;
    private final Handler main;
    private Pending pending;
    private final class Pending implements Events {
        final Intent target;
        final Mode mode;
        final Callback callback;
        final int request = REQUESTS.incrementAndGet();
        boolean requestingPermission;
        boolean starting;
        final Runnable timeout = () -> finish(this, new IllegalStateException(requestingPermission
                ? "等待 Shizuku 授权超时，请重试" : "服务连接超时，请确认服务已启动后重试"));
        Pending(Intent target, Mode mode, Callback callback) {
            this.target = new Intent(target); this.mode = mode; this.callback = callback;
        }
        @Override public void binderChanged() { main.post(() -> advance(this)); }
        @Override public void permissionResult(int code, boolean granted) {
            if (code != request) return;
            main.post(() -> {
                if (pending != this) return;
                if (granted) advance(this);
                else finish(this, new SecurityException("Shizuku 授权被拒绝"));
            });
        }
    }

    public ShortcutLaunchCoordinator(Backend backend, Handler main) { this.backend = backend; this.main = main; }

    public void launch(Intent target, boolean privileged, String selected, Callback callback) {
        cancel();
        Mode mode;
        if (!privileged) mode = Mode.NORMAL;
        else if ("root".equals(selected)) mode = Mode.ROOT;
        else if ("Shizuku".equals(selected)) mode = Mode.SHIZUKU;
        else if ("Shizuku - 系统助手".equals(selected)) mode = Mode.ASSISTANT;
        else { callback.complete(new IllegalArgumentException("未知启动模式：" + selected)); return; }
        Pending operation = new Pending(target, mode, callback);
        pending = operation;
        main.postDelayed(operation.timeout, CONNECTION_TIMEOUT_MS);
        if (mode == Mode.ROOT) {
            operation.callback.waiting("正在连接 Root 服务…");
            try {
                backend.connectRoot(error -> main.post(() -> {
                    if (pending != operation) return;
                    if (error == null) start(operation); else finish(operation, error);
                }));
            } catch (RuntimeException error) { finish(operation, error); }
        } else if (mode == Mode.NORMAL) start(operation);
        else {
            try {
                backend.listen(operation);
                advance(operation);
            } catch (RuntimeException error) { finish(operation, error); }
        }
    }

    private void advance(Pending operation) {
        if (pending != operation || operation.starting) return;
        try {
            if (!backend.binderReady()) {
                if (operation.requestingPermission) {
                    operation.requestingPermission = false;
                    main.removeCallbacks(operation.timeout);
                    main.postDelayed(operation.timeout, CONNECTION_TIMEOUT_MS);
                }
                operation.callback.waiting("正在连接 Shizuku…");
                return;
            }
            if (!backend.permissionGranted()) {
                if (!operation.requestingPermission) {
                    operation.requestingPermission = true;
                    main.removeCallbacks(operation.timeout);
                    main.postDelayed(operation.timeout, PERMISSION_TIMEOUT_MS);
                    operation.callback.waiting("请在 Shizuku 弹窗中允许授权");
                    backend.requestPermission(operation.request);
                }
                return;
            }
            start(operation);
        } catch (RuntimeException error) { finish(operation, error); }
    }

    private void start(Pending operation) {
        if (pending != operation || operation.starting) return;
        operation.starting = true;
        operation.requestingPermission = false;
        main.removeCallbacks(operation.timeout);
        main.postDelayed(operation.timeout, CONNECTION_TIMEOUT_MS);
        operation.callback.waiting("正在启动…");
        try { backend.start(operation.target, operation.mode, error -> main.post(() -> finish(operation, error))); }
        catch (RuntimeException error) { finish(operation, error); }
    }

    private void finish(Pending operation, Exception error) {
        if (pending != operation) return;
        pending = null;
        main.removeCallbacks(operation.timeout);
        backend.unlisten(operation);
        operation.callback.complete(error);
    }

    public void cancel() {
        Pending operation = pending;
        pending = null;
        if (operation != null) {
            main.removeCallbacks(operation.timeout);
            backend.unlisten(operation);
        }
    }
}
