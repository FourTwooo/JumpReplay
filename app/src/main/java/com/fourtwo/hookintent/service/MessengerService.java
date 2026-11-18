package com.fourtwo.hookintent.service;

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.util.Log;
import androidx.lifecycle.MutableLiveData;
import com.fourtwo.hookintent.manager.HookStatusManager;
import com.fourtwo.hookintent.data.CaptureRepository;
import com.fourtwo.hookintent.replay.NavigationReplayTransport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;

/** Open capture IPC for injected apps and LSPatch, with host-only editor discovery and navigation replay. */
public class MessengerService extends Service {
    public static final int MSG_IS_HOOK = 1;
    public static final int MSG_SEND_DATA = 3;
    public static final int MSG_SEND_FRAME = 4;
    public static final int MSG_REPLY = 99;
    public static final int MSG_REGISTER_CLIENT = 100;
    public static final int MSG_UNREGISTER_CLIENT = 101;
    public static final int MSG_NOTIFY_HOOK_STATE = 102;
    private static MessengerService instance;
    // Retained for source compatibility; repository owns consumption, so no second in-memory backlog.
    private final ConcurrentLinkedQueue<Bundle> dataQueue = new ConcurrentLinkedQueue<>();
    public static final MutableLiveData<Boolean> liveDataTrigger = new MutableLiveData<>();
    public static final MutableLiveData<Integer> connectionCount = new MutableLiveData<>(0);
    private final Map<IBinder, Client> clients = new LinkedHashMap<>();
    private CaptureFrameReceiver frames;
    private static final class Client {
        final Messenger messenger;
        final Bundle info;
        final IBinder.DeathRecipient death;
        final int uid;
        Client(Messenger messenger, Bundle info, IBinder.DeathRecipient death, int uid) {
            this.messenger = messenger; this.info = info; this.death = death; this.uid = uid;
        }
    }
    private final Handler handler = new Handler(Looper.getMainLooper()) {
        @Override public void handleMessage(Message msg) {
            try {
                final Messenger reply = msg.replyTo;
                final int type = msg.what, request = msg.arg1;
                switch (type) {
                    case MSG_SEND_DATA:
                        // Legacy messages intentionally do not require replyTo, nor caller registration.
                        CaptureRepository.get(getApplicationContext()).append(new Bundle(msg.getData()),
                                () -> sendReply(reply, type, request, 1, "Capture committed"),
                                error -> { Log.e("MessengerService", "Cannot persist capture", error);
                                    sendReply(reply, type, request, 0, error.toString()); });
                        break;
                    case MSG_SEND_FRAME:
                        if (reply != null) frames.receive(new Bundle(msg.getData()),
                                (ok, detail) -> sendReply(reply, type, request, ok ? 1 : 0, detail));
                        break;
                    case MSG_IS_HOOK:
                        sendReply(reply, type, request, HookStatusManager.isHook() ? 1 : 0, "Hook status checked");
                        break;
                    case MSG_REGISTER_CLIENT:
                        register(reply, msg.getData(), msg.sendingUid);
                        break;
                    case com.fourtwo.hookintent.replay.ExtrasRuntime.DISCOVER:
                        if (msg.sendingUid == android.os.Process.myUid()) discoverEditor(reply, request, msg.getData());
                        break;
                    case NavigationReplayTransport.DISCOVER:
                        if (msg.sendingUid == android.os.Process.myUid()) discoverNavigation(reply, request, msg.getData());
                        break;
                    case MSG_UNREGISTER_CLIENT:
                        if (reply != null) remove(reply.getBinder());
                        break;
                    default: super.handleMessage(msg);
                }
            } catch (RuntimeException error) { Log.w("MessengerService", "Invalid IPC message", error); }
        }
    };
    private final Messenger messenger = new Messenger(handler);
    @Override public void onCreate() { super.onCreate(); HookStatusManager.initialize(this); instance = this; frames = new CaptureFrameReceiver(getApplicationContext()); }
    @Override public void onDestroy() {
        for (IBinder binder : new ArrayList<>(clients.keySet())) remove(binder);
        frames.close(); instance = null; super.onDestroy();
    }
    public static MessengerService getInstance() { return instance; }
    public ConcurrentLinkedQueue<Bundle> getDataQueue() { return dataQueue; }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }
    private void register(Messenger messenger, Bundle info, int uid) {
        if (messenger == null) return;
        IBinder binder = messenger.getBinder();
        remove(binder);
        IBinder.DeathRecipient death = () -> handler.post(() -> remove(binder));
        try {
            binder.linkToDeath(death, 0);
            clients.put(binder, new Client(messenger, new Bundle(info), death, uid));
            connectionCount.setValue(clients.size());
            // A connection snapshot must not overwrite a process-local floating-window override.
            Bundle snapshot = new Bundle();
            snapshot.putBoolean("isHook", HookStatusManager.isHook());
            snapshot.putBoolean("stateSnapshot", true);
            Message message = Message.obtain(null, MSG_NOTIFY_HOOK_STATE);
            message.setData(snapshot); messenger.send(message);
        } catch (RemoteException error) { remove(binder); }
    }
    private void remove(IBinder binder) {
        Client client = clients.remove(binder);
        connectionCount.setValue(clients.size());
        if (client != null) try { binder.unlinkToDeath(client.death, 0); } catch (java.util.NoSuchElementException ignored) { }
    }
    /** Only the host's editor can discover endpoints. Legacy capture registration stays open. */
    private void discoverEditor(Messenger reply, int request, Bundle query) {
        if (reply == null) return;
        String source = query.getString("packageName"), process = query.getString("processName");
        IBinder endpoint = null;
        int endpointUid = -1;
        for (Client client : clients.values()) {
            if (client.info.getInt("editor_version", 0) < 2
                    || !java.util.Objects.equals(source, client.info.getString("packageName"))) continue;
            if (process != null && !process.isEmpty() && !java.util.Objects.equals(process, client.info.getString("processName"))) continue;
            try {
                if (getPackageManager().getApplicationInfo(source, 0).uid != client.uid) continue;
                IBinder candidate = client.info.getBinder("editor_endpoint");
                if (candidate != null && candidate.isBinderAlive()) { endpoint = candidate; endpointUid = client.uid; break; }
            } catch (Exception ignored) { }
        }
        Bundle data = new Bundle();
        data.putInt("requestType", com.fourtwo.hookintent.replay.ExtrasRuntime.DISCOVER);
        data.putInt("requestId", request); data.putInt("resultCode", endpoint == null ? 0 : 1);
        if (endpoint != null) { data.putBinder("editor_endpoint", endpoint); data.putInt("endpoint_uid", endpointUid); }
        Message response = Message.obtain(null, MSG_REPLY); response.setData(data);
        try { reply.send(response); } catch (RemoteException ignored) { }
    }
    private void discoverNavigation(Messenger reply, int request, Bundle query) {
        if (reply == null) return;
        IBinder endpoint = null;
        try {
            NavigationReplayTransport.Source source = NavigationReplayTransport.Source.installed(this, query);
            for (Client client : clients.values()) {
                if (source.uid != client.uid
                        || !source.packageName.equals(client.info.getString("packageName"))
                        || !source.processName.equals(client.info.getString("processName"))) continue;
                IBinder candidate = client.info.getBinder(NavigationReplayTransport.ENDPOINT);
                if (candidate == null || !candidate.isBinderAlive()) continue;
                if (endpoint != null && !endpoint.equals(candidate)) {
                    endpoint = null;
                    break;
                }
                endpoint = candidate;
            }
        } catch (Exception ignored) { }
        Bundle data = new Bundle();
        data.putInt("requestType", NavigationReplayTransport.DISCOVER);
        data.putInt("requestId", request);
        data.putInt("resultCode", endpoint == null ? 0 : 1);
        if (endpoint != null) data.putBinder(NavigationReplayTransport.ENDPOINT, endpoint);
        Message response = Message.obtain(null, MSG_REPLY);
        response.setData(data);
        try { reply.send(response); } catch (RemoteException ignored) { }
    }

    public List<Bundle> getConnectedClients() {
        List<Bundle> result = new ArrayList<>();
        for (Client client : clients.values()) result.add(new Bundle(client.info));
        return result;
    }
    private void sendReply(Messenger client, int type, int request, int code, String detail) {
        if (client == null) return;
        Message message = Message.obtain(null, MSG_REPLY);
        Bundle data = new Bundle(); data.putInt("requestType", type); data.putInt("requestId", request);
        data.putInt("resultCode", code); data.putString("resultData", detail); message.setData(data);
        try { client.send(message); } catch (RemoteException error) { Log.w("MessengerService", "Reply failed", error); }
    }
    public void notifyClients(Bundle state) {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post(() -> notifyClients(state)); return; }
        for (IBinder binder : new ArrayList<>(clients.keySet())) {
            Client client = clients.get(binder);
            try {
                Message message = Message.obtain(null, MSG_NOTIFY_HOOK_STATE);
                message.setData(new Bundle(state)); client.messenger.send(message);
            } catch (RemoteException error) { remove(binder); }
        }
    }
}
