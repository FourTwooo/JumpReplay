package com.fourtwo.hookintent.utils;

import android.app.Application;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public class ShortcutLaunchCoordinatorTest {
    private FakeBackend backend;
    private ShortcutLaunchCoordinator coordinator;
    private Result callback;

    @Before public void create() {
        backend = new FakeBackend();
        coordinator = new ShortcutLaunchCoordinator(backend, new Handler(Looper.getMainLooper()));
        callback = new Result();
    }

    private static Intent target() {
        Intent intent = new Intent("custom.action", Uri.parse("demo://host?q=a+b%26c"));
        intent.setComponent(new ComponentName("com.target", "com.target.Entry"));
        intent.addCategory("custom.category");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.putExtra("message", "中文 + %26");
        intent.putExtra("numbers", new int[]{1, 7, 9});
        intent.setClipData(ClipData.newPlainText("label", "clip payload"));
        return intent;
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    @Test public void translatedLegacyAssistantSelectionsStillUseAssistantBackend() {
        for(String label:new String[]{"Shizuku - System Helper","Shizuku - システムアシスタント"}) {
            create();backend.binder=true;backend.granted=true;
            coordinator.launch(target(),true,label,callback);idle();
            assertEquals(ShortcutLaunchCoordinator.Mode.ASSISTANT,backend.modes.get(0));
            coordinator.cancel();idle();
        }
    }

    @Test public void coldBinderWaitsForPermissionThenStartsExactlyOnceWithCompleteIntent() {
        Intent original = target();
        coordinator.launch(original, true, "Shizuku", callback);
        ShortcutLaunchCoordinator.Events events = backend.events;
        assertNotNull(events);
        assertEquals(0, backend.started.size());
        assertTrue(backend.permissions.isEmpty());
        assertEquals(0, callback.completions);

        backend.binder = true;
        events.binderChanged(); idle();
        assertEquals(1, backend.permissions.size());
        int request = backend.permissions.get(0);
        events.binderChanged(); idle();
        assertEquals(1, backend.permissions.size());
        assertEquals(0, backend.started.size());
        events.permissionResult(request + 1, true); idle();
        assertEquals(0, backend.started.size());

        backend.granted = true;
        events.permissionResult(request, true); idle();
        assertEquals(1, backend.started.size());
        assertEquals(ShortcutLaunchCoordinator.Mode.SHIZUKU, backend.modes.get(0));
        Intent launched = backend.started.get(0);
        assertEquals(original.getDataString(), launched.getDataString());
        assertEquals(original.getAction(), launched.getAction());
        assertEquals(original.getComponent(), launched.getComponent());
        assertTrue(launched.hasCategory("custom.category"));
        assertEquals(original.getFlags(), launched.getFlags());
        assertEquals("中文 + %26", launched.getStringExtra("message"));
        assertArrayEquals(new int[]{1, 7, 9}, launched.getIntArrayExtra("numbers"));
        assertEquals("clip payload", launched.getClipData().getItemAt(0).getText());
        assertEquals(0, callback.completions);

        // Binder and permission callbacks may race while the privileged call is still running.
        events.binderChanged(); events.permissionResult(request, true); idle();
        assertEquals(1, backend.started.size());
        backend.starts.get(0).accept(null); idle();
        assertEquals(1, callback.completions);
        assertNull(callback.error);
        assertNull(backend.events);
        assertEquals(1, backend.removed.size());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.MINUTES);
        assertEquals(1, callback.completions);
    }

    @Test public void alreadyGrantedStartsWithoutAnotherPermissionRequest() {
        backend.binder = true; backend.granted = true;
        coordinator.launch(target(), true, "Shizuku", callback);
        assertEquals(1, backend.started.size());
        assertTrue(backend.permissions.isEmpty());
        assertEquals(1, backend.listened.size());
        backend.starts.get(0).accept(null); idle();
        assertEquals(1, callback.completions);
        assertSame(backend.listened.get(0), backend.removed.get(0));
    }

    @Test public void binderRestartDuringAuthorizationRequestsPermissionAgain() {
        backend.binder = true;
        coordinator.launch(target(), true, "Shizuku", callback);
        ShortcutLaunchCoordinator.Events events = backend.events;
        assertEquals(1, backend.permissions.size());
        backend.binder = false;
        events.binderChanged(); idle();
        backend.binder = true;
        events.binderChanged(); idle();
        assertEquals(2, backend.permissions.size());
        backend.granted = true;
        events.permissionResult(backend.permissions.get(1), true); idle();
        assertEquals(1, backend.started.size());
    }

    @Test public void deniedPermissionNeverStartsTargetAndRemovesListener() {
        backend.binder = true;
        coordinator.launch(target(), true, "Shizuku", callback);
        ShortcutLaunchCoordinator.Events events = backend.events;
        int request = backend.permissions.get(0);
        events.permissionResult(request, false); idle();
        assertEquals(0, backend.started.size());
        assertEquals(1, callback.completions);
        assertTrue(callback.error instanceof SecurityException);
        assertNull(backend.events);
        backend.granted = true;
        events.permissionResult(request, true); events.binderChanged(); idle();
        assertEquals(0, backend.started.size());
        assertEquals(1, callback.completions);
    }

    @Test public void cancelBeforeConnectionIgnoresLateBinderAndPermissionCallbacks() {
        coordinator.launch(target(), true, "Shizuku", callback);
        ShortcutLaunchCoordinator.Events old = backend.events;
        coordinator.cancel();
        assertNull(backend.events);
        assertEquals(1, backend.removed.size());
        backend.binder = true; backend.granted = true;
        old.binderChanged(); idle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.MINUTES);
        assertTrue(backend.started.isEmpty());
        assertEquals(0, callback.completions);
    }

    @Test public void newLaunchInvalidatesOldAuthorizationCallback() {
        backend.binder = true;
        coordinator.launch(target(), true, "Shizuku", callback);
        ShortcutLaunchCoordinator.Events old = backend.events;
        int oldRequest = backend.permissions.get(0);
        Result replacement = new Result();
        Intent second = new Intent(Intent.ACTION_VIEW, Uri.parse("demo://second"));
        coordinator.launch(second, true, "Shizuku", replacement);
        ShortcutLaunchCoordinator.Events current = backend.events;
        int currentRequest = backend.permissions.get(1);
        assertNotEquals(oldRequest, currentRequest);
        backend.granted = true;
        old.permissionResult(oldRequest, true); old.binderChanged(); idle();
        assertTrue(backend.started.isEmpty());
        current.permissionResult(currentRequest, true); idle();
        assertEquals(1, backend.started.size());
        assertEquals("demo://second", backend.started.get(0).getDataString());
        backend.starts.get(0).accept(null); idle();
        assertEquals(0, callback.completions);
        assertEquals(1, replacement.completions);
        assertEquals(2, backend.removed.size());
    }

    @Test public void rootWaitsForConnectionAndForLaunchCompletion() {
        coordinator.launch(target(), true, "root", callback);
        assertEquals(1, backend.roots.size());
        assertTrue(backend.started.isEmpty());
        assertTrue(backend.listened.isEmpty());
        backend.roots.get(0).accept(null); idle();
        assertEquals(1, backend.started.size());
        assertEquals(ShortcutLaunchCoordinator.Mode.ROOT, backend.modes.get(0));
        assertEquals(0, callback.completions);
        backend.starts.get(0).accept(null); idle();
        assertEquals(1, callback.completions);
        assertNull(callback.error);
    }

    @Test public void rootCallbackFromReplacedLaunchCannotStartOldTarget() {
        coordinator.launch(target(), true, "root", callback);
        Consumer<Exception> oldRoot = backend.roots.get(0);
        Result replacement = new Result();
        coordinator.launch(new Intent(Intent.ACTION_VIEW, Uri.parse("demo://normal")), false, "", replacement);
        assertEquals(1, backend.started.size());
        assertEquals(ShortcutLaunchCoordinator.Mode.NORMAL, backend.modes.get(0));
        oldRoot.accept(null); idle();
        assertEquals(1, backend.started.size());
        backend.starts.get(0).accept(null); idle();
        assertEquals(0, callback.completions);
        assertEquals(1, replacement.completions);
    }

    @Test public void missingBinderTimesOutAndLateConnectionCannotLaunch() {
        coordinator.launch(target(), true, "Shizuku", callback);
        ShortcutLaunchCoordinator.Events events = backend.events;
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS);
        assertEquals(1, callback.completions);
        assertNotNull(callback.error);
        assertTrue(callback.error.getMessage().contains("连接超时"));
        assertNull(backend.events);
        backend.binder = true; backend.granted = true;
        events.binderChanged(); idle();
        assertTrue(backend.started.isEmpty());
    }

    @Test public void authorizationGetsItsOwnTimeoutInsteadOfConnectionTimeout() {
        backend.binder = true;
        coordinator.launch(target(), true, "Shizuku", callback);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(21, TimeUnit.SECONDS);
        assertEquals(0, callback.completions);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(100, TimeUnit.SECONDS);
        assertEquals(1, callback.completions);
        assertTrue(callback.error.getMessage().contains("授权超时"));
        assertTrue(backend.started.isEmpty());
        assertNull(backend.events);
    }

    @Test public void rootConnectionErrorReportsFailureWithoutStarting() {
        coordinator.launch(target(), true, "root", callback);
        Exception failure = new IllegalStateException("root unavailable");
        backend.roots.get(0).accept(failure); idle();
        assertEquals(1, callback.completions);
        assertSame(failure, callback.error);
        assertTrue(backend.started.isEmpty());
    }

    @Test public void assistantKeepsDistinctModeAfterAuthorization() {
        backend.binder = true;
        coordinator.launch(target(), true, "Shizuku - 系统助手", callback);
        assertTrue(backend.started.isEmpty());
        backend.granted = true;
        backend.events.permissionResult(backend.permissions.get(0), true); idle();
        assertEquals(ShortcutLaunchCoordinator.Mode.ASSISTANT, backend.modes.get(0));
        backend.starts.get(0).accept(null); idle();
        assertEquals(1, callback.completions);
        assertNull(backend.events);
    }

    private static final class Result implements ShortcutLaunchCoordinator.Callback {
        final List<String> waiting = new ArrayList<>();
        int completions;
        Exception error;
        @Override public void waiting(String message) { waiting.add(message); }
        @Override public void complete(Exception error) { completions++; this.error = error; }
    }

    private static final class FakeBackend implements ShortcutLaunchCoordinator.Backend {
        boolean binder, granted;
        ShortcutLaunchCoordinator.Events events;
        final List<ShortcutLaunchCoordinator.Events> listened = new ArrayList<>();
        final List<ShortcutLaunchCoordinator.Events> removed = new ArrayList<>();
        final List<Integer> permissions = new ArrayList<>();
        final List<Consumer<Exception>> roots = new ArrayList<>();
        final List<Consumer<Exception>> starts = new ArrayList<>();
        final List<Intent> started = new ArrayList<>();
        final List<ShortcutLaunchCoordinator.Mode> modes = new ArrayList<>();
        @Override public void listen(ShortcutLaunchCoordinator.Events events) { this.events = events; listened.add(events); }
        @Override public void unlisten(ShortcutLaunchCoordinator.Events events) {
            removed.add(events);
            if (this.events == events) this.events = null;
        }
        @Override public boolean binderReady() { return binder; }
        @Override public boolean permissionGranted() { return granted; }
        @Override public void requestPermission(int requestCode) { permissions.add(requestCode); }
        @Override public void connectRoot(Consumer<Exception> complete) { roots.add(complete); }
        @Override public void start(Intent intent, ShortcutLaunchCoordinator.Mode mode, Consumer<Exception> complete) {
            started.add(new Intent(intent)); modes.add(mode); starts.add(complete);
        }
    }
}
