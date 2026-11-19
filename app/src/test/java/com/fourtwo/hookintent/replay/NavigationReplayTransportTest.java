package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import android.os.SystemClock;

import com.fourtwo.hookintent.service.MessengerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public class NavigationReplayTransportTest {
    private static final String SOURCE = "example.source";
    private static final long UPDATE_TIME = 123_456L;
    private final AtomicInteger launches = new AtomicInteger();
    private final AtomicInteger completions = new AtomicInteger();
    private final AtomicReference<Exception> failure = new AtomicReference<>();
    private Application app;

    @Before public void installSource() {
        app = RuntimeEnvironment.getApplication();
        PackageInfo target = new PackageInfo();
        target.packageName = SOURCE;
        target.lastUpdateTime = UPDATE_TIME;
        target.applicationInfo = new ApplicationInfo();
        target.applicationInfo.packageName = SOURCE;
        target.applicationInfo.uid = Process.myUid();
        target.applicationInfo.enabled = true;
        Shadows.shadowOf(app.getPackageManager()).installPackage(target);
    }

    @Test public void launchesBeforeDiscoveryAndDeliversOnceAfterTargetRegistration() {
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger executions = new AtomicInteger();
        AtomicInteger staleExecutions = new AtomicInteger();
        AtomicInteger firstDiscovery = new AtomicInteger();
        Messenger endpoint = messenger(message -> {
            executions.incrementAndGet();
            assertTrue(message.getData().getBoolean("wait_for_startup"));
            assertEquals(8_000L, message.getData().getLong("expires_at") - SystemClock.elapsedRealtime());
            assertEquals("changed", message.getData().getBundle("arguments").getString("key"));
            Message response = Message.obtain(null, 204);
            response.arg1 = message.arg1;
            send(message.replyTo, response);
            return true;
        });
        Messenger staleEndpoint = messenger(message -> { staleExecutions.incrementAndGet(); return true; });
        bind(messenger(message -> {
            assertEquals(1, launches.get());
            assertEquals(NavigationReplayTransport.DISCOVER, message.what);
            if (attempts.incrementAndGet() == 1) {
                firstDiscovery.set(message.arg1);
                reply(message.replyTo, message.arg1, null);
            } else {
                reply(message.replyTo, firstDiscovery.get(), staleEndpoint);
                reply(message.replyTo, message.arg1, endpoint);
                reply(message.replyTo, message.arg1, endpoint);
            }
            return true;
        }));

        start(capture());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, attempts.get());
        assertEquals(0, executions.get());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(1));
        assertEquals(2, attempts.get());
        assertEquals(1, executions.get());
        assertEquals(0, staleExecutions.get());
        assertEquals(1, completions.get());
        assertNull(failure.get());
    }

    @Test public void reportsStartupFailureWithoutReopeningTargetOrPollingForever() {
        AtomicInteger attempts = new AtomicInteger();
        bind(messenger(message -> {
            attempts.incrementAndGet();
            reply(message.replyTo, message.arg1, null);
            return true;
        }));

        start(capture());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(16));
        int expiredAttempts = attempts.get();
        assertTrue(expiredAttempts > 1);
        assertEquals(1, launches.get());
        assertEquals(1, completions.get());
        assertNotNull(failure.get());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertEquals(expiredAttempts, attempts.get());
        assertEquals(1, completions.get());
    }

    @Test public void doesNotLaunchAfterSourceInstallationChanges() {
        Bundle record = capture();
        record.putLong("source_last_update", UPDATE_TIME + 1);
        start(record);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(0, launches.get());
        assertEquals(1, completions.get());
        assertNotNull(failure.get());
    }

    @Test public void waitsLongEnoughForColdTargetToRestoreAndVerifyItsHostPage() {
        Messenger endpoint = messenger(message -> {
            Message response = Message.obtain(null, 204);
            response.arg1 = message.arg1;
            Messenger target = message.replyTo;
            new Handler(Looper.getMainLooper()).postDelayed(() -> send(target, response), 28_000);
            return true;
        });
        bind(messenger(message -> {
            reply(message.replyTo, message.arg1, endpoint);
            return true;
        }));

        start(capture());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
        assertEquals(0, completions.get());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(9));
        assertEquals(1, completions.get());
        assertNull(failure.get());
    }

    private void start(Bundle record) {
        Bundle descriptor = new Bundle();
        // The fake endpoint verifies delivery only; target-side proof is covered separately.
        descriptor.putString("capture_proof", "x".repeat(44));
        Bundle args = new Bundle();
        args.putString("key", "changed");
        NavigationReplayTransport.replayAfterLaunch(app, record, descriptor, args,
                launches::incrementAndGet, error -> {
                    failure.set(error);
                    completions.incrementAndGet();
                });
    }

    private Bundle capture() {
        Bundle record = new Bundle();
        record.putString("packageName", SOURCE);
        record.putString("processName", SOURCE);
        record.putInt("source_uid", Process.myUid());
        record.putInt("source_app_uid", Process.myUid());
        record.putInt("capture_sdk", Build.VERSION.SDK_INT);
        record.putLong("source_last_update", UPDATE_TIME);
        return record;
    }

    private void bind(Messenger service) {
        Shadows.shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
                new Intent(app, MessengerService.class),
                new ComponentName(app, MessengerService.class), service.getBinder());
    }

    private static Messenger messenger(Handler.Callback callback) {
        return new Messenger(new Handler(Looper.getMainLooper(), callback));
    }

    private static void reply(Messenger target, int request, Messenger endpoint) {
        Bundle data = new Bundle();
        data.putInt("requestType", NavigationReplayTransport.DISCOVER);
        data.putInt("requestId", request);
        data.putInt("resultCode", endpoint == null ? 0 : 1);
        if (endpoint != null) data.putBinder(NavigationReplayTransport.ENDPOINT, endpoint.getBinder());
        Message message = Message.obtain(null, MessengerService.MSG_REPLY);
        message.setData(data);
        send(target, message);
    }

    private static void send(Messenger messenger, Message message) {
        try {
            messenger.send(message);
        } catch (RemoteException error) {
            throw new AssertionError(error);
        }
    }
}
