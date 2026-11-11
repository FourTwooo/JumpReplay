package com.fourtwo.hookintent.utils;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Process;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.function.Consumer;
import rikka.shizuku.Shizuku;

/** The shortcut process uses its own live Binder and permission callbacks, never main-process flags. */
public final class AndroidShortcutBackend implements ShortcutLaunchCoordinator.Backend {
    private final Activity activity;
    private ShortcutRootClient root;
    private java.util.concurrent.atomic.AtomicBoolean assistantCancelled;
    private final Map<ShortcutLaunchCoordinator.Events, Registration> registrations = new IdentityHashMap<>();
    private static final class Registration {
        final Shizuku.OnBinderReceivedListener received;
        final Shizuku.OnBinderDeadListener dead;
        final Shizuku.OnRequestPermissionResultListener permission;
        Registration(ShortcutLaunchCoordinator.Events events) {
            received = events::binderChanged;
            dead = events::binderChanged;
            permission = (code, result) -> events.permissionResult(code, result == PackageManager.PERMISSION_GRANTED);
        }
    }

    public AndroidShortcutBackend(Activity activity) { this.activity = activity; }

    @Override public void listen(ShortcutLaunchCoordinator.Events events) {
        if (registrations.containsKey(events)) return;
        Registration registration = new Registration(events);
        registrations.put(events, registration);
        Shizuku.addRequestPermissionResultListener(registration.permission);
        Shizuku.addBinderDeadListener(registration.dead);
        Shizuku.addBinderReceivedListenerSticky(registration.received);
    }

    @Override public void unlisten(ShortcutLaunchCoordinator.Events events) {
        if (root != null) { root.close(); root = null; }
        if (assistantCancelled != null) { assistantCancelled.set(true); assistantCancelled = null; }
        Registration registration = registrations.remove(events);
        if (registration == null) return;
        Shizuku.removeBinderReceivedListener(registration.received);
        Shizuku.removeBinderDeadListener(registration.dead);
        Shizuku.removeRequestPermissionResultListener(registration.permission);
    }

    @Override public boolean binderReady() { return Shizuku.pingBinder(); }
    @Override public boolean permissionGranted() {
        if (!binderReady()) return false;
        if (Shizuku.isPreV11()) throw new IllegalStateException("请升级 Shizuku 后重试");
        return Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
    }
    @Override public void requestPermission(int requestCode) { Shizuku.requestPermission(requestCode); }
    @Override public void connectRoot(Consumer<Exception> complete) {
        root = new ShortcutRootClient(activity);
        root.connect(complete);
    }

    @Override public void start(Intent intent, ShortcutLaunchCoordinator.Mode mode, Consumer<Exception> complete) {
        Intent target = new Intent(intent);
        // The destination belongs to its normal task; finishing this transparent root must not expose MainActivity.
        target.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            switch (mode) {
                case NORMAL: activity.startActivity(target); break;
                case ROOT:
                    if (root == null) throw new IllegalStateException("Root 服务尚未连接");
                    root.start(target, complete);
                    return;
                case SHIZUKU:
                    int result = ShizukuServerApi.startActivityAsShizuku(activity, target, Process.myUid() / 100000);
                    if (result < 0 || result >= 100) throw new IllegalStateException("启动失败，代码：" + result);
                    break;
                case ASSISTANT:
                    java.util.concurrent.atomic.AtomicBoolean cancelled = new java.util.concurrent.atomic.AtomicBoolean();
                    assistantCancelled = cancelled;
                    ShizukuServerApi.launchAssistantAsync(activity, target, complete, () -> !cancelled.get());
                    return;
                default: throw new IllegalArgumentException("未知启动模式");
            }
            complete.accept(null);
        } catch (Exception error) { complete.accept(error); }
    }
}
