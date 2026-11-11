package com.fourtwo.hookintent.service;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.Process;
import android.os.RemoteException;
import com.fourtwo.hookintent.utils.RootServiceApi;

/** Keeps libsu's UID-based client registration in the main process without opening its UI. */
public final class ShortcutRootService extends Service {
    public static final int PREPARE = 1;
    public static final int LAUNCH = 2;
    public static final String TARGET = "target";
    public static final String ERROR = "error";
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), this::handle));

    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    private boolean handle(Message message) {
        if (message.sendingUid != Process.myUid() || message.replyTo == null) return true;
        Messenger reply = message.replyTo;
        if (message.what == PREPARE) {
            RootServiceApi.connectAsync(this, error -> respond(reply, PREPARE, error));
        } else if (message.what == LAUNCH) {
            Exception failure = null;
            try {
                Bundle data = message.getData();
                data.setClassLoader(Intent.class.getClassLoader());
                Intent target = data.getParcelable(TARGET);
                if (target == null) throw new IllegalArgumentException("缺少目标 Intent");
                RootServiceApi.startActivityAsRoot(this, target);
            } catch (Exception error) { failure = error; }
            respond(reply, LAUNCH, failure);
        }
        return true;
    }

    private static void respond(Messenger reply, int what, Exception error) {
        Message response = Message.obtain(null, what);
        if (error != null) {
            Bundle data = new Bundle();
            data.putString(ERROR, error.getMessage() == null ? error.toString() : error.getMessage());
            response.setData(data);
        }
        try { reply.send(response); }
        catch (RemoteException ignored) { /* The shortcut was closed; no target is launched by PREPARE. */ }
    }
}
