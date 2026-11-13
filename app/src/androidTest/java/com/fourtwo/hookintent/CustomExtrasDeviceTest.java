package com.fourtwo.hookintent;

import android.app.Instrumentation;
import android.content.*;
import android.os.*;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.replay.ExtrasClient;
import com.fourtwo.hookintent.utils.IntentReplay;
import com.fourtwo.hookintent.utils.ShortcutPayloadStore;
import org.json.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Real APK/class-loader isolation. No fixture classes may be linked by the module or test APK. */
@RunWith(AndroidJUnit4.class)
public class CustomExtrasDeviceTest {
    static final String FIXTURE = "com.fourtwo.hookintent.fixture";
    final Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    final Context context = instrumentation.getTargetContext();
    interface Operation { void run(ExtrasClient.Callback callback) throws Exception; }

    @Test public void coldProcessAndLiveProcessEditThenReplayCompleteForeignObjects() throws Exception {
        try { Class.forName(FIXTURE + ".FixtureService$PostData"); fail("Fixture type leaked into the module classpath"); }
        catch (ClassNotFoundException expected) { }
        Bundle original;
        try (Fixture fixture = new Fixture()) { original = bundle(fixture.call(1, null)); }
        shell("am force-stop " + FIXTURE);
        assertEquals("", shell("pidof " + FIXTURE).trim());
        Bundle edited = edit(original, "installed_apk");
        assertEquals("Offline class loading must not start the target Application", "", shell("pidof " + FIXTURE).trim());
        try (Fixture fixture = new Fixture()) {
            assertValues(fixture.call(2, new JSONObject().put("parcel", edited.getString(IntentPayloadCodec.KEY))), true);
            assertValues(fixture.call(2, new JSONObject().put("parcel", original.getString(IntentPayloadCodec.KEY))), false);
            // Register the identical production runtime in the foreign process, without modifying LSPosed scopes.
            assertTrue(fixture.call(5, null).getBoolean("registered"));
            Thread.sleep(700);
            Bundle live = edit(original, "app_process");
            assertValues(fixture.call(2, new JSONObject().put("parcel", live.getString(IntentPayloadCodec.KEY))), true);

            // Save/reload through the actual launcher payload store, then edit its ordinary URI extra.
            Intent target = IntentPayloadCodec.decode(context, live);
            String uri = live.getString("uri");
            String shortcut = ShortcutPayloadStore.save(context, uri, new String[0], target, live);
            try {
                ShortcutPayloadStore.Payload restored = ShortcutPayloadStore.load(context, shortcut);
                CountDownLatch merged = new CountDownLatch(1);
                AtomicReference<Intent> mergedIntent = new AtomicReference<>();
                AtomicReference<Exception> mergeError = new AtomicReference<>();
                com.fourtwo.hookintent.replay.ReplayTasks.fromText(context, restored.origin,
                        uri.replace("S.title=plain-edited", "S.title=plain-shortcut"), restored.target, uri, (value, error) -> {
                            mergedIntent.set(value); mergeError.set(error); merged.countDown();
                        });
                assertTrue(merged.await(35, TimeUnit.SECONDS));
                assertNull(mergeError.get());
                Intent replay = mergedIntent.get();
                JSONObject values = fixture.call(2, new JSONObject().put("parcel", IntentPayloadCodec.encode(replay)));
                assertEquals("plain-shortcut", values.getString("plain"));
                assertEquals(9007199254740993L, values.getLong("id"));
                assertEquals("nested-edited", values.getString("note"));

                fixture.call(4, null);
                replay.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(replay);
                JSONObject receipt = receipt(fixture);
                assertEquals("plain-shortcut", receipt.getString("plain"));
                assertEquals(9007199254740993L, receipt.getLong("id"));
                assertTrue(receipt.getBoolean("cycle"));
                assertTrue(receipt.getBoolean("alias"));
                assertEquals(400000, receipt.getInt("largeLength"));

                // The new action/card path must keep the same foreign objects through a
                // database snapshot, backup import and ordinary-parameter URI edit.
                com.fourtwo.hookintent.actions.ActionStore actions=com.fourtwo.hookintent.actions.ActionStore.get(context);
                String actionId=java.util.UUID.randomUUID().toString(), importedId=null;
                String baseUri=uri.replace("S.title=plain-edited","S.title=plain-shortcut");
                com.fourtwo.hookintent.actions.QuickAction action=new com.fourtwo.hookintent.actions.QuickAction(actionId,
                        "compatibility-"+actionId,baseUri.replace("S.title=plain-shortcut","S.title=plain-action"),new String[0],false,"root",
                        ShortcutPayloadStore.content(baseUri,new String[0],replay,restored.origin).toString(),null);
                try {
                    actions.save(action);
                    JSONObject backup=new JSONObject().put("format","jumpreplay-actions").put("version",1)
                            .put("actions",new JSONArray().put(actions.find(actionId).json()));
                    assertEquals(1,actions.importFrom(new ByteArrayInputStream(backup.toString().getBytes(StandardCharsets.UTF_8))));
                    java.util.List<com.fourtwo.hookintent.actions.QuickAction> saved=actions.all();
                    importedId=saved.get(saved.size()-1).id;
                    actions.delete(actionId); // The imported action owns its own snapshot.
                    fixture.call(4,null);
                    context.startActivity(com.fourtwo.hookintent.actions.ActionStore.launchIntent(context,importedId));
                    JSONObject actionReceipt=receipt(fixture);
                    assertEquals("plain-action",actionReceipt.getString("plain"));
                    assertEquals(9007199254740993L,actionReceipt.getLong("id"));
                    assertEquals("nested-edited",actionReceipt.getString("note"));
                    assertEquals("serial-edited",actionReceipt.getString("serialNote"));
                    assertEquals(200,actionReceipt.getInt("number"));
                    assertTrue(actionReceipt.getBoolean("cycle")); assertTrue(actionReceipt.getBoolean("alias"));
                    assertTrue(actionReceipt.getBoolean("nullableIsNull")); assertEquals(400000,actionReceipt.getInt("largeLength"));
                } finally {
                    actions.delete(actionId); if(importedId != null) actions.delete(importedId);
                }
            } finally { new File(new File(context.getFilesDir(), "shortcut_payloads"), shortcut + ".txt").delete(); }
        }
    }

    @Test public void actionReplayWithinActivityBinderBudgetKeepsForeignObjects() throws Exception {
        Bundle original;
        try(Fixture fixture=new Fixture()) { original=bundle(fixture.call(1,null)); }
        shell("am force-stop "+FIXTURE);
        Bundle edited=edit(original,"installed_apk");
        try(Fixture fixture=new Fixture()) {
            // Keep the original 400k-character codec test. Only the actual Activity launch
            // uses 100k characters: Android 13 rejects the original ~800k Binder transaction.
            assertValues(fixture.call(2,new JSONObject().put("parcel",edited.getString(IntentPayloadCodec.KEY))),true);
            ExtrasClient client=new ExtrasClient(context,edited);
            Bundle compact;
            try {
                await(client::open);
                compact=set(client,find(children(client,"root"),"large"),new String(new char[100000]).replace('\0','x'));
            } finally { client.close(); }
            String uri=compact.getString("uri");
            Bundle record=new Bundle(edited); record.putString(IntentPayloadCodec.KEY,compact.getString("parcel")); record.putString("uri",uri);
            com.fourtwo.hookintent.actions.ActionStore actions=com.fourtwo.hookintent.actions.ActionStore.get(context);
            String id=java.util.UUID.randomUUID().toString(), imported=null;
            try {
                com.fourtwo.hookintent.actions.QuickAction action=new com.fourtwo.hookintent.actions.QuickAction(id,"binder-budget-"+id,
                        uri.replace("S.title=plain-edited","S.title=plain-action"),new String[0],false,"root",
                        ShortcutPayloadStore.content(uri,new String[0],IntentPayloadCodec.decode(context,record),record).toString(),null);
                actions.save(action);
                JSONObject backup=new JSONObject().put("format","jumpreplay-actions").put("version",1).put("actions",new JSONArray().put(actions.find(id).json()));
                assertEquals(1,actions.importFrom(new ByteArrayInputStream(backup.toString().getBytes(StandardCharsets.UTF_8))));
                java.util.List<com.fourtwo.hookintent.actions.QuickAction> saved=actions.all(); imported=saved.get(saved.size()-1).id;
                actions.delete(id); fixture.call(4,null);
                context.startActivity(com.fourtwo.hookintent.actions.ActionStore.launchIntent(context,imported));
                JSONObject received=receipt(fixture);
                assertEquals("plain-action",received.getString("plain")); assertEquals(9007199254740993L,received.getLong("id"));
                assertEquals("post-edited",received.getString("postTitle")); assertEquals("nested-edited",received.getString("note"));
                assertEquals("serial-edited",received.getString("serialNote")); assertEquals(200,received.getInt("number"));
                assertEquals("tag-edited",received.getString("tag")); assertEquals(99,received.getInt("map"));
                assertTrue(received.getBoolean("cycle")); assertTrue(received.getBoolean("alias")); assertTrue(received.getBoolean("nullableIsNull"));
                assertEquals(100000,received.getInt("largeLength"));
            } finally { actions.delete(id); if(imported != null) actions.delete(imported); }
        }
    }

    private Bundle edit(Bundle record, String environment) throws Exception {
        ExtrasClient client = new ExtrasClient(context, record);
        try {
            Bundle state = await(client::open);
            assertEquals(environment, state.getString("environment"));
            JSONArray root = children(client, "root");
            JSONObject post = find(root, "post");
            JSONArray postFields = children(client, post.getString("id"));
            int decoderUid = Integer.parseInt(find(postFields, "creatorUid").getString("value"));
            if ("installed_apk".equals(environment)) {
                assertNotEquals("Foreign CREATOR must not execute with host privileges", android.os.Process.myUid(), decoderUid);
                assertNotEquals(context.getPackageManager().getApplicationInfo(FIXTURE, 0).uid, decoderUid);
            } else assertEquals(context.getPackageManager().getApplicationInfo(FIXTURE, 0).uid, decoderUid);
            state = set(client, find(postFields, "id"), "9007199254740993");
            state = set(client, find(postFields, "title"), "post-edited");
            JSONArray nested = children(client, find(postFields, "nested").getString("id"));
            state = set(client, find(nested, "note"), "nested-edited");
            JSONArray serial = children(client, find(root, "serial").getString("id"));
            state = set(client, find(serial, "note"), "serial-edited");
            JSONArray tags = children(client, find(serial, "tags").getString("id"));
            state = set(client, find(tags, "[1]"), "tag-edited");
            JSONArray attributes = children(client, find(serial, "attributes").getString("id"));
            state = set(client, find(attributes, "score"), "99");
            JSONArray numbers = children(client, find(root, "numbers").getString("id"));
            state = set(client, find(numbers, "[1]"), "200");
            state = set(client, find(root, "title"), "plain-edited");
            // No JavaScript/JSON numeric conversion is permitted to round Long values.
            String id = find(postFields, "id").getString("id");
            try { await(callback -> client.set(id, "not-a-number", false, callback)); fail("invalid long accepted"); }
            catch (IllegalStateException expected) { }
            JSONArray after = children(client, post.getString("id"));
            assertEquals("9007199254740993", find(after, "id").getString("value"));
            JSONObject self = find(serial, "self");
            assertTrue(self.has("reference"));
            Bundle result = new Bundle(record);
            result.putString(IntentPayloadCodec.KEY, state.getString("parcel"));
            result.putString("uri", state.getString("uri"));
            return result;
        } finally { client.close(); }
    }
    private JSONArray children(ExtrasClient client, String node) throws Exception {
        return new JSONArray(await(callback -> client.children(node, callback)).getString("nodes"));
    }
    private Bundle set(ExtrasClient client, JSONObject node, String value) throws Exception {
        return await(callback -> client.set(node.getString("id"), value, false, callback));
    }
    private JSONObject find(JSONArray list, String name) throws Exception {
        for (int i=0;i<list.length();i++) if (name.equals(list.getJSONObject(i).optString("name"))) return list.getJSONObject(i);
        throw new AssertionError("Missing " + name + " in " + list);
    }
    private Bundle await(Operation operation) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bundle> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        operation.run((value, error) -> { result.set(value); failure.set(error); done.countDown(); });
        assertTrue("Editor RPC did not complete", done.await(35, TimeUnit.SECONDS));
        if (failure.get() != null) throw new IllegalStateException(failure.get());
        return result.get();
    }
    private void assertValues(JSONObject values, boolean edited) throws Exception {
        assertFalse(values.toString(), values.has("error"));
        assertEquals(FIXTURE + ".FixtureService$PostData", values.getString("postClass"));
        assertEquals(edited ? 9007199254740993L : 42L, values.getLong("id"));
        assertEquals(edited ? "post-edited" : "post-original", values.getString("postTitle"));
        assertEquals(edited ? "nested-edited" : "original", values.getString("note"));
        assertEquals(edited ? "serial-edited" : "original", values.getString("serialNote"));
        assertEquals(edited ? "plain-edited" : "plain-original", values.getString("plain"));
        assertEquals(edited ? 200 : 2, values.getInt("number"));
        assertEquals(edited ? "tag-edited" : "b", values.getString("tag"));
        assertEquals(edited ? 99 : 10, values.getInt("map"));
        assertEquals(400000, values.getInt("largeLength"));
        assertTrue(values.getBoolean("cycle")); assertTrue(values.getBoolean("alias"));
        assertTrue(values.getBoolean("nullableIsNull"));
    }
    private JSONObject receipt(Fixture fixture) throws Exception {
        long deadline = SystemClock.uptimeMillis()+10000;
        do {
            String value = fixture.call(3, null).optString("receipt");
            if (!value.isEmpty()) { JSONObject json = new JSONObject(value); assertFalse(json.toString(),json.has("error")); return json; }
            Thread.sleep(100);
        } while (SystemClock.uptimeMillis()<deadline);
        throw new AssertionError("No receiver Activity receipt");
    }
    private String shell(String command) throws Exception {
        try (InputStream stream = new ParcelFileDescriptor.AutoCloseInputStream(instrumentation.getUiAutomation().executeShellCommand(command))) {
            return new String(read(stream), StandardCharsets.UTF_8);
        }
    }
    private static byte[] read(InputStream stream) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int n;
        while ((n=stream.read(buffer))!=-1) bytes.write(buffer,0,n);
        return bytes.toByteArray();
    }
    private static Bundle bundle(JSONObject json) throws Exception {
        assertFalse(json.toString(),json.has("error"));
        Bundle result=new Bundle(); java.util.Iterator<String> keys=json.keys();
        while(keys.hasNext()) { String key=keys.next(); result.putString(key,json.getString(key)); }
        return result;
    }
    final class Fixture implements AutoCloseable {
        Messenger service;
        final CountDownLatch connected = new CountDownLatch(1);
        final ServiceConnection connection = new ServiceConnection() {
            @Override public void onServiceConnected(ComponentName name,IBinder binder) { service=new Messenger(binder); connected.countDown(); }
            @Override public void onServiceDisconnected(ComponentName name) { service=null; }
        };
        Fixture() throws Exception {
            assertTrue(context.bindService(new Intent().setComponent(new ComponentName(FIXTURE,FIXTURE+".FixtureService")),connection,Context.BIND_AUTO_CREATE));
            assertTrue(connected.await(10,TimeUnit.SECONDS));
        }
        JSONObject call(int what,JSONObject request) throws Exception {
            CompletableFuture<JSONObject> answer = new CompletableFuture<>();
            Messenger reply = new Messenger(new Handler(Looper.getMainLooper(), message -> {
                ParcelFileDescriptor input=message.getData().getParcelable("response");
                new Thread(() -> {
                    try (InputStream stream=new ParcelFileDescriptor.AutoCloseInputStream(input)) {
                        answer.complete(new JSONObject(new String(read(stream),StandardCharsets.UTF_8)));
                    } catch(Exception error) { answer.completeExceptionally(error); }
                },"fixture-result").start();
                return true;
            }));
            Message message=Message.obtain(null,what); message.replyTo=reply;
            ParcelFileDescriptor[] pipe=ParcelFileDescriptor.createPipe();
            Bundle data=new Bundle(); data.putParcelable("request",pipe[0]); message.setData(data);
            service.send(message); pipe[0].close();
            try (OutputStream stream=new ParcelFileDescriptor.AutoCloseOutputStream(pipe[1])) {
                stream.write((request==null?new JSONObject():request).toString().getBytes(StandardCharsets.UTF_8));
            }
            JSONObject result=answer.get(30,TimeUnit.SECONDS);
            assertFalse(result.toString(),result.has("error")); return result;
        }
        @Override public void close() { context.unbindService(connection); }
    }
}
