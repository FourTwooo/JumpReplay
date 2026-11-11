package com.fourtwo.hookintent.utils;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import com.fourtwo.hookintent.IRootActivityService;
import com.fourtwo.hookintent.manager.PermissionManager;
import com.fourtwo.hookintent.service.RootActivityService;
import com.topjohnwu.superuser.Shell;
import com.topjohnwu.superuser.ipc.RootService;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/** Owns the libsu connection in the main process; shortcuts use ShortcutRootService. */
public final class RootServiceApi {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static IRootActivityService rootService;
    public static boolean isServiceBound;
    private static boolean binding;
    private static boolean initializing;
    private static int generation;
    private static final List<Consumer<Exception>> pending = new ArrayList<>();
    private static final Runnable TIMEOUT = () -> {
        generation++;
        if (binding) {
            try { RootService.unbind(RootServiceApi.connection); }
            catch (RuntimeException error) { Log.w("RootServiceApi", "Root unbind failed", error); }
        }
        binding = false;
        initializing = false;
        complete(new IllegalStateException("Root 服务连接超时"));
    };
    private static final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder service) {
            rootService = IRootActivityService.Stub.asInterface(service);
            isServiceBound = true;
            binding = false;
            initializing = false;
            MAIN.removeCallbacks(TIMEOUT);
            complete(null);
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            MAIN.removeCallbacks(TIMEOUT);
            generation++;
            rootService = null;
            isServiceBound = false;
            binding = false;
            initializing = false;
            complete(new IllegalStateException("Root 服务已断开"));
        }
    };
    private RootServiceApi() { }

    public static void warmUp(Context context) {
        connectAsync(context, error -> {
            if (error != null) Log.d("RootServiceApi", "Root is not ready: " + error.getMessage());
        });
    }

    public static void connectAsync(Context context, Consumer<Exception> callback) {
        Context app = context.getApplicationContext();
        MAIN.post(() -> {
            if (rootService != null && rootService.asBinder().isBinderAlive()) {
                callback.accept(null);
                return;
            }
            if (isServiceBound) {
                try { RootService.unbind(connection); }
                catch (RuntimeException error) { Log.w("RootServiceApi", "Stale Root unbind failed", error); }
                rootService = null;
                isServiceBound = false;
            }
            pending.add(callback);
            if (initializing || binding) return;
            initializing = true;
            int attempt = ++generation;
            MAIN.removeCallbacks(TIMEOUT);
            MAIN.postDelayed(TIMEOUT, 18000);
            try {
                Shell.getShell(shell -> MAIN.post(() -> {
                    if (attempt != generation) return;
                    PermissionManager.isRootPermissionGranted = shell.isRoot();
                    if (!shell.isRoot()) {
                        initializing = false;
                        MAIN.removeCallbacks(TIMEOUT);
                        complete(new SecurityException("尚未获得 Root 授权"));
                        return;
                    }
                    try { bindRootService(app); }
                    catch (RuntimeException error) {
                        initializing = false;
                        MAIN.removeCallbacks(TIMEOUT);
                        complete(error);
                    }
                }));
            } catch (RuntimeException error) {
                initializing = false;
                MAIN.removeCallbacks(TIMEOUT);
                complete(error);
            }
        });
    }

    private static void complete(Exception error) {
        List<Consumer<Exception>> callbacks = new ArrayList<>(pending);
        pending.clear();
        for (Consumer<Exception> callback : callbacks) {
            try { callback.accept(error); }
            catch (RuntimeException failure) { Log.w("RootServiceApi", "Root callback failed", failure); }
        }
    }

    public static void bindRootService(Context context) {
        if (isServiceBound || binding) return;
        binding = true;
        try { RootService.bind(new Intent(context, RootActivityService.class), connection); }
        catch (RuntimeException error) { binding = false; throw error; }
    }

    public static void startActivityAsRoot(Context context, Intent intent) {
        if (rootService == null) throw new IllegalStateException("Root 服务尚未连接");
        try {
            int result = rootService.startActivityAsRoot(intent, android.os.Process.myUid() / 100000);
            if (result < 0 || result >= 100) throw new IllegalStateException("Root 启动失败，代码：" + result);
        } catch (RemoteException error) { throw new IllegalStateException("Root 服务通信失败", error); }
    }

    public static void unbindRootService(Context context) {
        MAIN.removeCallbacks(TIMEOUT);
        generation++;
        if (isServiceBound || binding) {
            try { RootService.unbind(connection); }
            catch (RuntimeException error) { Log.w("RootServiceApi", "Root unbind failed", error); }
        }
        rootService = null;
        isServiceBound = binding = initializing = false;
        complete(new IllegalStateException("Root 连接已取消"));
    }
}
