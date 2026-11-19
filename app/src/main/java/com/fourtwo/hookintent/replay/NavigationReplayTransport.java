package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.SystemClock;

import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.service.MessengerService;

import java.util.Objects;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Per-request replay channel, independent of the open capture transport. */
public final class NavigationReplayTransport {
    public static final int DISCOVER = 202;
    public static final String ENDPOINT = "navigation_replay_endpoint";
    private static final int EXECUTE = 203;
    private static final int RESULT = 204;
    private static final int REQUEST_ID = 1;
    private static final long DELIVERY_TIMEOUT_MS = 3_000;
    private static final long STARTUP_DELIVERY_TIMEOUT_MS = 8_000;
    private static final long STARTUP_TIMEOUT_MS = 15_000;
    private static final long DISCOVERY_RETRY_MS = 250;
    private static final long RESULT_TIMEOUT_MS = 18_000;
    private static final long STARTUP_RESULT_TIMEOUT_MS = 35_000;
    private static final String DESCRIPTOR = "descriptor";
    private static final String ARGUMENTS = "arguments";
    private static final String WAIT_FOR_STARTUP = "wait_for_startup";
    private static final String ERROR = "error";

    private NavigationReplayTransport() {}

    public interface Executor {
        void replay(Bundle descriptor, Bundle editedArguments, boolean waitForStartup,
                    Consumer<Exception> completion);
    }

    public static Bundle seal(Context source, Bundle descriptor) throws Exception {
        return NavigationCaptureProof.seal(source, Source.current(source).pack(), descriptor);
    }

    public static IBinder endpoint(Context context, Executor executor) {
        Context app = appContext(context);
        return new Messenger(new Handler(Looper.getMainLooper(),
                message -> receive(app, executor, message))).getBinder();
    }

    private static boolean receive(Context app, Executor executor, Message message) {
        // Binder supplies the sender UID. Authenticate it before touching the parcel.
        if (message.what != EXECUTE || message.replyTo == null || !isHost(app, message.sendingUid)) return true;
        Messenger reply = message.replyTo;
        int request = message.arg1;
        AtomicBoolean completed = new AtomicBoolean();
        Consumer<Exception> completion = error -> {
            if (!completed.compareAndSet(false, true)) return;
            Bundle response = new Bundle();
            if (error != null) {
                String detail = error.getMessage();
                response.putString(ERROR, detail == null ? error.getClass().getSimpleName()
                        : detail.substring(0, Math.min(512, detail.length())));
            }
            Message result = Message.obtain(null, RESULT);
            result.arg1 = request;
            result.setData(response);
            try { reply.send(result); } catch (RemoteException ignored) { }
        };
        try {
            Bundle data = message.getData();
            boolean waitForStartup = data.getBoolean(WAIT_FOR_STARTUP, false);
            long deliveryTimeout = waitForStartup ? STARTUP_DELIVERY_TIMEOUT_MS : DELIVERY_TIMEOUT_MS;
            long remaining = data.getLong("expires_at") - SystemClock.elapsedRealtime();
            if (remaining <= 0 || remaining > deliveryTimeout) {
                throw new TimeoutException("进程内重放请求已过期");
            }
            Source actual = Source.current(app);
            if (!actual.matches(data)) {
                throw new IllegalStateException("目标应用进程或版本与捕获时不一致");
            }
            Bundle descriptor = NavigationCaptureProof.verify(app, actual.pack(), data.getBundle(DESCRIPTOR));
            Bundle arguments = NavigationCaptureProof.copy(data.getBundle(ARGUMENTS), false);
            executor.replay(descriptor, arguments, waitForStartup, completion);
        } catch (Exception error) { completion.accept(error); }
        return true;
    }

    /** Call from the host UI. Run onDispatched after delivery, then report the target's actual result. */
    public static void replay(Context context, Bundle captureRecord, Bundle replayCall, Bundle editedArgs,
                              Runnable onDispatched, Consumer<Exception> completion) {
        begin(context, captureRecord, replayCall, editedArgs, null, onDispatched, completion);
    }

    /** Launch from a foreground Activity, then wait for the injected target process to register. */
    public static void replayAfterLaunch(Context context, Bundle captureRecord, Bundle replayCall,
                                         Bundle editedArgs, Runnable launch, Consumer<Exception> completion) {
        begin(context, captureRecord, replayCall, editedArgs, Objects.requireNonNull(launch), null, completion);
    }

    private static void begin(Context context, Bundle captureRecord, Bundle replayCall, Bundle editedArgs,
                              Runnable launch, Runnable onDispatched, Consumer<Exception> completion) {
        Context app = appContext(context);
        new Handler(Looper.getMainLooper()).post(() -> {
            ClientRequest request;
            try {
                if (!ExtrasRuntime.HOST.equals(app.getPackageName()) || !isHost(app, android.os.Process.myUid())) {
                    throw new SecurityException("只有 HookIntent 可以请求进程内重放");
                }
                Source source = Source.installed(app, captureRecord);
                Bundle descriptor = NavigationCaptureProof.copy(replayCall, true);
                if (!NavigationCaptureProof.hasProof(descriptor)) {
                    throw new IllegalArgumentException("这条记录没有可验证的原导航调用，请重新捕获");
                }
                request = new ClientRequest(app, source, descriptor,
                        NavigationCaptureProof.copy(editedArgs, false), launch != null, onDispatched, completion);
                if (launch != null) launch.run();
            } catch (Exception error) {
                completion.accept(error);
                return;
            }
            request.start();
        });
    }

    private static Context appContext(Context context) {
        // Injected clients register from Application.attach before a ContextImpl may know its Application.
        Context application = context.getApplicationContext();
        return application == null ? context : application;
    }

    public static boolean isHost(Context context, int uid) {
        if (uid < 0) return false;
        try { return context.getPackageManager().getApplicationInfo(ExtrasRuntime.HOST, 0).uid == uid; }
        catch (PackageManager.NameNotFoundException | SecurityException ignored) { return false; }
    }

    /** Identity supplied by the saved record and verified against the current installed app. */
    public static final class Source {
        public final String packageName;
        public final String processName;
        public final int uid;
        private final long updateTime;

        private Source(String packageName, String processName, int uid, long updateTime) {
            this.packageName = packageName;
            this.processName = processName;
            this.uid = uid;
            this.updateTime = updateTime;
        }

        public static Source installed(Context context, Bundle record) throws PackageManager.NameNotFoundException {
            if (record == null) throw new IllegalArgumentException("缺少导航记录来源");
            String pkg = record.getString("packageName");
            String process = record.getString("processName");
            if (pkg == null || pkg.isEmpty() || pkg.length() > 255 || ExtrasRuntime.HOST.equals(pkg)
                    || process == null || process.isEmpty() || process.length() > 255) {
                throw new IllegalArgumentException("缺少目标应用或进程");
            }
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            if (info.applicationInfo == null || !info.applicationInfo.enabled) {
                throw new IllegalStateException("来源应用已不可用");
            }
            Source source = new Source(pkg, process, info.applicationInfo.uid, info.lastUpdateTime);
            if (!source.matches(record)) throw new IllegalStateException("来源应用已变更，请重新捕获");
            return source;
        }

        private static Source current(Context context) throws PackageManager.NameNotFoundException {
            String pkg = context.getPackageName();
            String process = Build.VERSION.SDK_INT >= 28 ? Application.getProcessName()
                    : DataConverter.getCurrentProcessName(context);
            PackageInfo info = context.getPackageManager().getPackageInfo(pkg, 0);
            int uid = android.os.Process.myUid();
            if (process == null || process.isEmpty() || info.applicationInfo == null
                    || !info.applicationInfo.enabled || info.applicationInfo.uid != uid) {
                throw new IllegalStateException("无法验证当前导航进程");
            }
            return new Source(pkg, process, uid, info.lastUpdateTime);
        }

        private boolean matches(Bundle record) {
            return Objects.equals(packageName, record.getString("packageName"))
                    && Objects.equals(processName, record.getString("processName"))
                    && uid == record.getInt("source_uid", -1)
                    && uid == record.getInt("source_app_uid", -1)
                    && updateTime > 0 && updateTime == record.getLong("source_last_update", -1)
                    && Build.VERSION.SDK_INT == record.getInt("capture_sdk", -1);
        }

        private Bundle pack() {
            Bundle identity = new Bundle();
            identity.putString("packageName", packageName);
            identity.putString("processName", processName);
            identity.putInt("source_uid", uid);
            identity.putInt("source_app_uid", uid);
            identity.putLong("source_last_update", updateTime);
            identity.putInt("capture_sdk", Build.VERSION.SDK_INT);
            return identity;
        }
    }

    private static final class ClientRequest implements ServiceConnection {
        private final Context app;
        private final Source source;
        private final Bundle descriptor;
        private final Bundle arguments;
        private final boolean waitForStartup;
        private final Runnable onDispatched;
        private final Consumer<Exception> completion;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Messenger replies = new Messenger(new Handler(Looper.getMainLooper(), this::receive));
        private final Runnable timeout = () -> finish(new TimeoutException("等待目标应用响应超时"));
        private final Runnable discoveryTimeout = this::missingEndpoint;
        private final Runnable retryDiscovery = this::discover;
        private Messenger captureService;
        private int discoverySequence;
        private int pendingDiscovery;
        private boolean bound;
        private boolean discovered;
        private boolean finished;

        ClientRequest(Context app, Source source, Bundle descriptor, Bundle arguments, boolean waitForStartup,
                      Runnable onDispatched, Consumer<Exception> completion) {
            this.app = app;
            this.source = source;
            this.descriptor = descriptor;
            this.arguments = arguments;
            this.waitForStartup = waitForStartup;
            this.onDispatched = onDispatched;
            this.completion = completion;
        }

        void start() {
            try {
                Intent intent = new Intent(app, MessengerService.class);
                bound = app.bindService(intent, this, Context.BIND_AUTO_CREATE);
                if (bound) main.postDelayed(discoveryTimeout, waitForStartup ? STARTUP_TIMEOUT_MS : DELIVERY_TIMEOUT_MS);
                else finish(new IllegalStateException("无法连接捕获服务"));
            } catch (RuntimeException error) { finish(error); }
        }

        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            if (finished || discovered) return;
            captureService = new Messenger(binder);
            discover();
        }

        private void discover() {
            if (finished || discovered || captureService == null || pendingDiscovery != 0) return;
            pendingDiscovery = ++discoverySequence;
            Message discover = Message.obtain(null, DISCOVER);
            discover.arg1 = pendingDiscovery;
            discover.replyTo = replies;
            discover.setData(source.pack());
            try { captureService.send(discover); }
            catch (RemoteException error) { finish(error); }
        }

        private void missingEndpoint() {
            String detail = waitForStartup ? "目标应用启动后未连接到唯一的导航进程"
                    : "目标应用的导航进程未连接或存在多个匹配进程，请先打开目标应用";
            finish(new IllegalStateException(detail));
        }

        @Override public void onServiceDisconnected(ComponentName name) {
            if (!discovered) finish(new IllegalStateException("捕获服务连接已断开"));
        }

        @Override public void onBindingDied(ComponentName name) { onServiceDisconnected(name); }

        @Override public void onNullBinding(ComponentName name) {
            finish(new IllegalStateException("捕获服务不可用"));
        }

        private boolean receive(Message message) {
            if (finished) return true;
            if (message.what == MessengerService.MSG_REPLY && !discovered && isHost(app, message.sendingUid)) {
                Bundle data = message.getData();
                if (pendingDiscovery == 0 || data.getInt("requestType") != DISCOVER
                        || data.getInt("requestId") != pendingDiscovery) return true;
                pendingDiscovery = 0;
                IBinder endpoint = data.getBinder(ENDPOINT);
                if (data.getInt("resultCode") != 1 || endpoint == null || !endpoint.isBinderAlive()) {
                    if (waitForStartup) main.postDelayed(retryDiscovery, DISCOVERY_RETRY_MS);
                    else missingEndpoint();
                } else dispatch(endpoint);
            } else if (message.what == RESULT && discovered && message.arg1 == REQUEST_ID
                    && message.sendingUid == source.uid) {
                String error = message.getData().getString(ERROR);
                finish(error == null ? null : new IllegalStateException(error));
            }
            return true;
        }

        private void dispatch(IBinder endpoint) {
            discovered = true;
            main.removeCallbacks(discoveryTimeout);
            main.removeCallbacks(retryDiscovery);
            Bundle data = source.pack();
            long deliveryTimeout = waitForStartup ? STARTUP_DELIVERY_TIMEOUT_MS : DELIVERY_TIMEOUT_MS;
            data.putLong("expires_at", SystemClock.elapsedRealtime() + deliveryTimeout);
            data.putBoolean(WAIT_FOR_STARTUP, waitForStartup);
            data.putBundle(DESCRIPTOR, descriptor);
            data.putBundle(ARGUMENTS, arguments);
            Message execute = Message.obtain(null, EXECUTE);
            execute.arg1 = REQUEST_ID;
            execute.replyTo = replies;
            execute.setData(data);
            try {
                new Messenger(endpoint).send(execute);
                main.postDelayed(timeout, waitForStartup ? STARTUP_RESULT_TIMEOUT_MS : RESULT_TIMEOUT_MS);
                if (onDispatched != null) onDispatched.run();
            } catch (RemoteException | RuntimeException error) { finish(error); }
        }

        private void finish(Exception error) {
            if (finished) return;
            finished = true;
            main.removeCallbacks(discoveryTimeout);
            main.removeCallbacks(retryDiscovery);
            main.removeCallbacks(timeout);
            if (bound) {
                bound = false;
                try { app.unbindService(this); } catch (IllegalArgumentException ignored) { }
            }
            completion.accept(error);
        }
    }
}
