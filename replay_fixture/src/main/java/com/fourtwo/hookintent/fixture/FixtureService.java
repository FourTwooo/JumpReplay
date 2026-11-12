package com.fourtwo.hookintent.fixture;

import android.app.Service;
import android.content.Intent;
import android.os.*;
import android.util.Base64;
import org.json.JSONObject;
import java.io.Serializable;
import java.util.*;

/** This APK deliberately has no dependency on the module. The module cannot link its data classes. */
public final class FixtureService extends Service {
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newCachedThreadPool();
    private Object runtimeClient;
    private final Messenger messenger = new Messenger(new Handler(Looper.getMainLooper(), message -> {
        Message copy = Message.obtain(message);
        worker.execute(() -> handle(copy));
        return true;
    }));

    private void handle(Message message) {
        Bundle result = new Bundle();
        try {
            ParcelFileDescriptor input = message.getData().getParcelable("request");
            if (input != null) {
                String text;
                try (java.io.InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(input)) {
                    text = new String(read(stream), java.nio.charset.StandardCharsets.UTF_8);
                }
                JSONObject json = new JSONObject(text);
                Bundle data = new Bundle();
                java.util.Iterator<String> keys = json.keys();
                while (keys.hasNext()) { String key = keys.next(); data.putString(key, json.getString(key)); }
                message.setData(data);
            }
            if (message.what == 1) {
                Intent intent = new Intent("fixture.OPEN").setClass(this, ReceiverActivity.class);
                intent.putExtra("title", "plain-original");
                intent.putExtra("post", new PostData());
                intent.putExtra("serial", new Envelope());
                intent.putExtra("numbers", new int[]{1, 2, 3});
                intent.putExtra("nullable", (String) null);
                intent.putExtra("large", new String(new char[400000]).replace('\0', 'x'));
                result.putString("intent_parcel", encode(intent));
                result.putString("uri", intent.toUri(Intent.URI_INTENT_SCHEME));
                result.putString("packageName", getPackageName());
                result.putString("componentPackageName", getPackageName());
                result.putString("processName", getPackageName());
                result.putString("category", "Intent");
                result.putString("title", "Compatibility fixture");
            } else if (message.what == 2) {
                result = inspect(decode(message.getData().getString("parcel")));
            } else if (message.what == 3) {
                result.putString("receipt", getSharedPreferences("receipt", 0).getString("json", ""));
            } else if (message.what == 4) {
                getSharedPreferences("receipt", 0).edit().clear().commit();
            } else if (message.what == 5) {
                // Exercise the identical module runtime inside a different APK, without changing LSPosed scopes.
                android.content.pm.ApplicationInfo host = getPackageManager().getApplicationInfo("com.fourtwo.hookintent", 0);
                ClassLoader module = new dalvik.system.PathClassLoader(host.sourceDir, getClassLoader());
                Class<?> clientType = module.loadClass("com.fourtwo.hookintent.service.MessengerClient");
                runtimeClient = clientType.getConstructor(android.content.Context.class).newInstance(this);
                clientType.getMethod("enableExtrasEditing", ClassLoader.class).invoke(runtimeClient, getClassLoader());
                Class<?> callback = module.loadClass("com.fourtwo.hookintent.service.MessengerClient$PassiveCallback");
                Object noop = java.lang.reflect.Proxy.newProxyInstance(module, new Class[]{callback}, (p,m,args) -> null);
                clientType.getMethod("registerPassiveCallback", callback).invoke(runtimeClient, noop);
                result.putBoolean("registered", true);
            }
        } catch (Throwable error) { result.putString("error", error.toString()); }
        if (message.replyTo != null) {
            try {
                JSONObject json = new JSONObject();
                for (String key : result.keySet()) json.put(key, result.get(key));
                byte[] bytes = json.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
                ParcelFileDescriptor[] pipe = ParcelFileDescriptor.createPipe();
                Message response = Message.obtain(null, message.what); response.arg1 = message.arg1;
                Bundle data = new Bundle(); data.putParcelable("response", pipe[0]); response.setData(data);
                try { message.replyTo.send(response); }
                finally { pipe[0].close(); }
                try (java.io.OutputStream stream = new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) { stream.write(bytes); }
            } catch (Exception ignored) { }
        }
        message.recycle();
    }
    private static byte[] read(java.io.InputStream stream) throws java.io.IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        byte[] buffer = new byte[8192]; int count;
        while ((count = stream.read(buffer)) != -1) bytes.write(buffer, 0, count);
        return bytes.toByteArray();
    }
    @Override public void onDestroy() { worker.shutdownNow(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return messenger.getBinder(); }

    static Bundle inspect(Intent intent) {
        intent.setExtrasClassLoader(FixtureService.class.getClassLoader());
        PostData post = intent.getParcelableExtra("post");
        Envelope serial = (Envelope) intent.getSerializableExtra("serial");
        Bundle result = new Bundle();
        result.putString("postClass", post.getClass().getName());
        result.putLong("id", post.id);
        result.putString("postTitle", post.title);
        result.putString("note", post.nested.note);
        result.putString("serialNote", serial.note);
        result.putString("plain", intent.getStringExtra("title"));
        result.putInt("number", intent.getIntArrayExtra("numbers")[1]);
        result.putString("tag", serial.tags.get(1));
        result.putInt("map", serial.attributes.get("score"));
        result.putBoolean("cycle", serial.self == serial);
        result.putBoolean("alias", serial.shared == serial.alias);
        result.putBoolean("nullableIsNull", intent.hasExtra("nullable") && intent.getStringExtra("nullable") == null);
        result.putInt("largeLength", intent.getStringExtra("large").length());
        return result;
    }
    static Intent decode(String value) {
        byte[] bytes = Base64.decode(value, Base64.DEFAULT); Parcel p = Parcel.obtain();
        try { p.unmarshall(bytes,0,bytes.length); p.setDataPosition(0); return Intent.CREATOR.createFromParcel(p); }
        finally { p.recycle(); }
    }
    static String encode(Intent intent) {
        Parcel p = Parcel.obtain();
        try { intent.writeToParcel(p,0); return Base64.encodeToString(p.marshall(),Base64.NO_WRAP); }
        finally { p.recycle(); }
    }
    public static final class Envelope implements Serializable {
        private static final long serialVersionUID = 1;
        private String note = "original";
        private ArrayList<String> tags = new ArrayList<>(Arrays.asList("a", "b"));
        private LinkedHashMap<String,Integer> attributes = new LinkedHashMap<>();
        private Envelope self = this;
        private ArrayList<String> shared = new ArrayList<>(Collections.singletonList("shared"));
        private ArrayList<String> alias = shared;
        public Envelope() { attributes.put("score", 10); }
    }
    public static final class PostData implements Parcelable {
        private int creatorUid = android.os.Process.myUid();
        private long id = 42;
        private String title = "post-original";
        private Envelope nested = new Envelope();
        public PostData() { }
        private PostData(Parcel source) {
            id = source.readLong(); title = source.readString(); nested = (Envelope) source.readValue(PostData.class.getClassLoader());
        }
        @Override public void writeToParcel(Parcel target,int flags) { target.writeLong(id); target.writeString(title); target.writeValue(nested); }
        @Override public int describeContents() { return 0; }
        public static final Creator<PostData> CREATOR = new Creator<PostData>() {
            @Override public PostData createFromParcel(Parcel source) { return new PostData(source); }
            @Override public PostData[] newArray(int size) { return new PostData[size]; }
        };
    }
}
