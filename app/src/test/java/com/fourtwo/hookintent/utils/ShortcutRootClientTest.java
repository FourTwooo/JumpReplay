package com.fourtwo.hookintent.utils;

import android.app.Application;
import android.content.ClipData;
import android.content.ComponentName;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.ServiceConnection;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.RemoteException;

import com.fourtwo.hookintent.service.ShortcutRootService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public class ShortcutRootClientTest {
    private static final ComponentName SERVICE = new ComponentName(
            "com.fourtwo.hookintent", ShortcutRootService.class.getName());

    private BindingContext context;
    private FakeService service;
    private ShortcutRootClient client;

    @Before public void create() {
        context = new BindingContext(RuntimeEnvironment.getApplication());
        service = new FakeService();
        client = new ShortcutRootClient(context);
    }

    @Test public void preparationMustSucceedBeforeAnyLaunchIsSent() throws Exception {
        Result connection = new Result();
        Result prematureLaunch = new Result();
        client.connect(connection);
        assertSame(client, context.connection);
        assertEquals(SERVICE, context.intent.getComponent());
        assertEquals(Context.BIND_AUTO_CREATE, context.flags);
        assertTrue(service.requests.isEmpty());

        context.connection.onServiceConnected(SERVICE, service.messenger.getBinder());
        idle();
        assertEquals(1, service.requests.size());
        assertEquals(ShortcutRootService.PREPARE, service.requests.get(0).what);
        assertTrue(connection.results.isEmpty());

        client.start(target(), prematureLaunch);
        idle();
        assertEquals(1, service.requests.size());
        assertEquals(1, prematureLaunch.results.size());
        assertNotNull(prematureLaunch.results.get(0));

        service.reply(0, null);
        assertEquals(1, connection.results.size());
        assertNull(connection.results.get(0));
        Result launch = new Result();
        client.start(target(), launch);
        idle();
        assertEquals(2, service.requests.size());
        assertEquals(ShortcutRootService.LAUNCH, service.requests.get(1).what);
        assertTrue(launch.results.isEmpty());
        service.reply(1, null);
        service.reply(1, null);
        assertEquals(1, launch.results.size());
        assertNull(launch.results.get(0));
        client.close();
        assertEquals(1, context.unbinds);
    }

    @Test public void launchPreservesCompleteIntentAcrossTheMessengerParcel() throws Exception {
        connectReady();
        Intent original = target();
        Result launch = new Result();
        client.start(original, launch);
        original.setAction("changed.after.send");
        idle();

        Message request = service.requests.get(1);
        Intent received = request.getData().getParcelable(ShortcutRootService.TARGET);
        assertNotNull(received);
        assertNotSame(original, received);
        assertEquals("custom.action", received.getAction());
        assertEquals("demo://host/path?q=a+b%26c", received.getDataString());
        assertEquals("application/vnd.test", received.getType());
        assertEquals(new ComponentName("com.target", "com.target.Entry"), received.getComponent());
        assertEquals("com.target", received.getPackage());
        assertTrue(received.hasCategory("custom.category"));
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION,
                received.getFlags());
        assertEquals(new Rect(1, 2, 30, 40), received.getSourceBounds());
        assertEquals("中文 + %26", received.getStringExtra("message"));
        assertArrayEquals(new int[]{1, 7, 9}, received.getIntArrayExtra("numbers"));
        assertEquals("nested value", received.getBundleExtra("nested").getString("key"));
        assertEquals("clip label", received.getClipData().getDescription().getLabel().toString());
        assertEquals("clip payload", received.getClipData().getItemAt(0).getText().toString());
        assertTrue(launch.results.isEmpty());
        service.reply(1, null);
        assertNull(launch.results.get(0));
        client.close();
    }

    @Test public void closingWhilePreparingIgnoresLateRepliesAndConnectionCallbacks() throws Exception {
        Result connection = new Result();
        client.connect(connection);
        context.connection.onServiceConnected(SERVICE, service.messenger.getBinder());
        idle();
        client.close();
        client.close();
        service.reply(0, null);
        context.connection.onServiceConnected(SERVICE, service.messenger.getBinder());
        idle();
        assertTrue(connection.results.isEmpty());
        assertEquals(1, context.unbinds);
        assertEquals(1, service.requests.size());

        Result launch = new Result();
        client.start(target(), launch);
        idle();
        assertNotNull(launch.results.get(0));
        assertEquals(1, service.requests.size());
    }

    @Test public void closingDuringLaunchIgnoresItsLateReply() throws Exception {
        connectReady();
        Result launch = new Result();
        client.start(target(), launch);
        idle();
        assertEquals(2, service.requests.size());
        client.close();
        service.reply(1, null);
        service.reply(1, "late failure");
        assertTrue(launch.results.isEmpty());
        assertEquals(1, context.unbinds);
    }

    @Test public void failedBindReportsErrorWithoutUnbindingAnUnregisteredConnection() {
        context.bindSucceeds = false;
        Result connection = new Result();
        client.connect(connection);
        assertEquals(1, connection.results.size());
        assertNotNull(connection.results.get(0));
        assertEquals(0, context.unbinds);
        client.close();
        assertEquals(0, context.unbinds);
    }

    @Test public void exceptionFromBindIsReturnedToTheCaller() {
        context.bindError = new SecurityException("bind denied");
        Result connection = new Result();
        client.connect(connection);
        assertEquals(1, connection.results.size());
        assertSame(context.bindError, connection.results.get(0));
        assertEquals(0, context.unbinds);
    }

    @Test public void disconnectWhilePreparingFailsOnceAndIgnoresLateSuccess() throws Exception {
        Result connection = new Result();
        client.connect(connection);
        context.connection.onServiceConnected(SERVICE, service.messenger.getBinder());
        idle();
        context.connection.onServiceDisconnected(SERVICE);
        context.connection.onServiceDisconnected(SERVICE);
        service.reply(0, null);
        assertEquals(1, connection.results.size());
        assertNotNull(connection.results.get(0));
        assertEquals(1, context.unbinds);
    }

    @Test public void disconnectDuringLaunchFailsPendingLaunchAndIgnoresLateSuccess() throws Exception {
        Result connection = connectReady();
        Result launch = new Result();
        client.start(target(), launch);
        idle();
        context.connection.onServiceDisconnected(SERVICE);
        service.reply(1, null);
        assertEquals(1, connection.results.size());
        assertNull(connection.results.get(0));
        assertEquals(1, launch.results.size());
        assertNotNull(launch.results.get(0));
        assertEquals(1, context.unbinds);
    }

    @Test public void nullBindingFailsThePendingConnectionAndReleasesIt() {
        Result connection = new Result();
        client.connect(connection);
        context.connection.onNullBinding(SERVICE);
        assertEquals(1, connection.results.size());
        assertNotNull(connection.results.get(0));
        assertEquals(1, context.unbinds);
    }

    @Test public void deadBindingFailsThePendingConnectionAndReleasesIt() {
        Result connection = new Result();
        client.connect(connection);
        context.connection.onBindingDied(SERVICE);
        assertEquals(1, connection.results.size());
        assertNotNull(connection.results.get(0));
        assertEquals(1, context.unbinds);
    }

    @Test public void preparationFailureIsReportedAndCannotLaunchATarget() throws Exception {
        Result connection = new Result();
        client.connect(connection);
        context.connection.onServiceConnected(SERVICE, service.messenger.getBinder());
        idle();
        service.reply(0, "root denied");
        assertEquals(1, connection.results.size());
        assertEquals("root denied", connection.results.get(0).getMessage());
        Result launch = new Result();
        client.start(target(), launch);
        idle();
        assertNotNull(launch.results.get(0));
        assertEquals(1, service.requests.size());
        client.close();
    }

    @Test public void remoteLaunchFailureIsReturnedExactlyOnce() throws Exception {
        connectReady();
        Result launch = new Result();
        client.start(target(), launch);
        idle();
        service.reply(1, "target not found");
        service.reply(1, null);
        assertEquals(1, launch.results.size());
        assertEquals("target not found", launch.results.get(0).getMessage());
        client.close();
    }

    private Result connectReady() throws RemoteException {
        Result connection = new Result();
        client.connect(connection);
        context.connection.onServiceConnected(SERVICE, service.messenger.getBinder());
        idle();
        service.reply(0, null);
        assertEquals(1, connection.results.size());
        assertNull(connection.results.get(0));
        return connection;
    }

    private static Intent target() {
        Intent intent = new Intent("custom.action");
        intent.setDataAndType(Uri.parse("demo://host/path?q=a+b%26c"), "application/vnd.test");
        intent.setComponent(new ComponentName("com.target", "com.target.Entry"));
        intent.setPackage("com.target");
        intent.addCategory("custom.category");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        intent.setSourceBounds(new Rect(1, 2, 30, 40));
        intent.putExtra("message", "中文 + %26");
        intent.putExtra("numbers", new int[]{1, 7, 9});
        Bundle nested = new Bundle();
        nested.putString("key", "nested value");
        intent.putExtra("nested", nested);
        intent.setClipData(ClipData.newPlainText("clip label", "clip payload"));
        return intent;
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }

    private static final class Result implements Consumer<Exception> {
        final List<Exception> results = new ArrayList<>();
        @Override public void accept(Exception error) { results.add(error); }
    }

    private static final class BindingContext extends ContextWrapper {
        Intent intent;
        ServiceConnection connection;
        int flags;
        int unbinds;
        boolean bindSucceeds = true;
        RuntimeException bindError;

        BindingContext(Context base) { super(base); }
        @Override public Context getApplicationContext() { return this; }
        @Override public boolean bindService(Intent intent, ServiceConnection connection, int flags) {
            this.intent = intent;
            this.connection = connection;
            this.flags = flags;
            if (bindError != null) throw bindError;
            return bindSucceeds;
        }
        @Override public void unbindService(ServiceConnection connection) {
            assertSame(this.connection, connection);
            unbinds++;
        }
    }

    private static final class FakeService {
        final List<Message> requests = new ArrayList<>();
        final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
            // Local Messenger skips Binder marshalling; round-trip the actual request so the
            // assertions cover the Intent representation sent to the separate main process.
            Parcel parcel = Parcel.obtain();
            try {
                message.writeToParcel(parcel, 0);
                parcel.setDataPosition(0);
                requests.add(Message.CREATOR.createFromParcel(parcel));
            } finally { parcel.recycle(); }
            return true;
        }));

        void reply(int requestIndex, String error) throws RemoteException {
            Message request = requests.get(requestIndex);
            Message response = Message.obtain(null, request.what);
            if (error != null) {
                Bundle data = new Bundle();
                data.putString(ShortcutRootService.ERROR, error);
                response.setData(data);
            }
            request.replyTo.send(response);
            idle();
        }
    }
}
