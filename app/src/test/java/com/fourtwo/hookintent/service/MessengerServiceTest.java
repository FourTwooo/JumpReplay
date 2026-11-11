package com.fourtwo.hookintent.service;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class MessengerServiceTest {
    @Test public void nullRegistrationCannotCrashGlobalToggleAndDuplicateRegistrationIsReplaced() throws Exception {
        MessengerService service = Robolectric.buildService(MessengerService.class).create().get();
        Messenger receiver = new Messenger(service.onBind(new Intent()));
        receiver.send(Message.obtain(null, MessengerService.MSG_REGISTER_CLIENT));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        service.notifyClients(new Bundle());
        assertTrue(service.getConnectedClients().isEmpty());
        Messenger client = new Messenger(new Handler(Looper.getMainLooper()));
        for (int index = 0; index < 2; index++) {
            Message registration = Message.obtain(null, MessengerService.MSG_REGISTER_CLIENT);
            registration.replyTo = client;
            Bundle info = new Bundle(); info.putString("packageName", "any.scope.or.lspatch.app");
            registration.setData(info); receiver.send(registration);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, service.getConnectedClients().size());
        assertEquals("any.scope.or.lspatch.app", service.getConnectedClients().get(0).getString("packageName"));
        Message unregister = Message.obtain(null, MessengerService.MSG_UNREGISTER_CLIENT); unregister.replyTo = client;
        receiver.send(unregister); Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue(service.getConnectedClients().isEmpty());
        service.onDestroy();
    }
}
