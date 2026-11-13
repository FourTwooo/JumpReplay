package com.fourtwo.hookintent.actions;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import com.fourtwo.hookintent.utils.ShortcutPayloadStore;
import org.json.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;
import java.util.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=33)
public class ActionStoreTest {
    private Context context;
    private ActionStore store;
    @Before public void open() throws Exception {
        context=RuntimeEnvironment.getApplication();
        java.lang.reflect.Field field=ActionStore.class.getDeclaredField("instance"); field.setAccessible(true); field.set(null,null);
        store=ActionStore.get(context);
    }
    @After public void close() { store.close(); }
    private QuickAction action(String name,String payload) {
        return new QuickAction(UUID.randomUUID().toString(),name,"demo://host?q=VALUE",new String[]{"VALUE"},false,"root",payload,null);
    }
    @Test public void multiMegabyteSnapshotAndTypedExtrasSurviveWithoutLoadingSnapshotsInTheList() throws Exception {
        Intent target=new Intent(Intent.ACTION_VIEW,Uri.parse("demo://host?q=VALUE"));
        byte[] large=new byte[3_000_000]; Arrays.fill(large,(byte)17);
        target.putExtra("opaque",large); target.putExtra("numbers",new int[]{3,7,11});
        String payload=ShortcutPayloadStore.content("demo://host?q=VALUE",new String[]{"VALUE"},target,null).toString();
        QuickAction value=action("large",payload); store.save(value);
        assertNull(store.all().get(0).payload);
        QuickAction restored=store.find(value.id);
        assertEquals(payload,restored.payload);
        Intent decoded=ShortcutPayloadStore.decode(context,new JSONObject(restored.payload)).target;
        assertArrayEquals(large,decoded.getByteArrayExtra("opaque")); assertArrayEquals(new int[]{3,7,11},decoded.getIntArrayExtra("numbers"));
        store.save(new QuickAction(value.id,"renamed","demo://host?q=EDITED",new String[0],true,"Shizuku",payload,null));
        assertEquals(1,store.all().size()); assertEquals("renamed",store.find(value.id).name);
        assertEquals(value.id,ActionStore.launchIntent(context,value.id).getStringExtra("quick_action_id"));
        assertEquals(payload,store.find(value.id).payload);
    }
    @Test public void invalidBackupCannotPartiallyImportOrOverwriteExistingActions() throws Exception {
        QuickAction original=action("original",null); store.save(original);
        JSONArray entries=new JSONArray().put(action("valid",null).json()).put(action("invalid",null).json().put("template","not a URI"));
        JSONObject data=new JSONObject().put("format","jumpreplay-actions").put("version",1).put("actions",entries);
        try { store.importData(data); fail("Invalid entry must reject the entire backup"); } catch(IllegalArgumentException expected) { }
        assertEquals(1,store.all().size()); assertEquals(original.id,store.all().get(0).id);
    }
    @Test public void multiMegabyteUriDoesNotOverflowTheListCursorWindow() throws Exception {
        char[] text=new char[2_200_000]; Arrays.fill(text,'x');
        String uri="demo://host?q="+new String(text);
        QuickAction value=new QuickAction(UUID.randomUUID().toString(),"large URI",uri,new String[0],false,"root",null,null);
        store.save(value);
        assertTrue(store.all().get(0).template.length() <= 512);
        assertEquals(uri,store.find(value.id).template);
    }
    @Test public void chunkBoundariesPreserveEmojiSurrogatePairs() throws Exception {
        StringBuilder emoji=new StringBuilder(); for(int i=0;i<100000;i++) emoji.append("🐈");
        for(String prefix:new String[]{"demo://host/","demo://host/a"}) {
            String uri=prefix+emoji;
            QuickAction value=new QuickAction(UUID.randomUUID().toString(),"emoji",uri,new String[0],false,"root",null,null);
            store.save(value); assertEquals(uri,store.find(value.id).template);
        }
    }
    @Test public void backupRoundTripPreservesOrderParametersAndCompleteSnapshotWithFreshIds() throws Exception {
        QuickAction one=action("one",ShortcutPayloadStore.content("demo://host?q=VALUE",new String[]{"VALUE"},
                new Intent(Intent.ACTION_VIEW,Uri.parse("demo://host?q=VALUE")).putExtra("typed",new long[]{1,9}),null).toString());
        QuickAction two=action("two",null); store.save(one); store.save(two); store.reorder(Arrays.asList(two.id,one.id));
        JSONObject backup=store.exportData(); store.delete(one.id); store.delete(two.id);
        assertEquals(2,store.importData(backup)); List<QuickAction> imported=store.all();
        assertEquals("two",imported.get(0).name); assertEquals("one",imported.get(1).name);
        assertNotEquals(one.id,imported.get(1).id);
        QuickAction restored=store.find(imported.get(1).id);
        assertArrayEquals(new String[]{"VALUE"},restored.parameters);
        assertArrayEquals(new long[]{1,9},ShortcutPayloadStore.decode(context,new JSONObject(restored.payload)).target.getLongArrayExtra("typed"));
    }
    @Test public void streamingBackupRestoresRecordsAndMalformedTailRollsBackEverything() throws Exception {
        QuickAction value=action("streamed",ShortcutPayloadStore.content("demo://host?q=VALUE",new String[]{"VALUE"},
                new Intent(Intent.ACTION_VIEW,Uri.parse("demo://host?q=VALUE")).putExtra("array",new int[]{1,7}),null).toString());
        store.save(value); java.io.ByteArrayOutputStream output=new java.io.ByteArrayOutputStream(); store.exportTo(output);
        byte[] valid=output.toByteArray(); store.delete(value.id);
        assertEquals(1,store.importFrom(new java.io.ByteArrayInputStream(valid)));
        assertArrayEquals(new int[]{1,7},ShortcutPayloadStore.decode(context,new JSONObject(store.find(store.all().get(0).id).payload)).target.getIntArrayExtra("array"));
        byte[] broken=(output.toString("UTF-8")+" invalid-tail").getBytes(java.nio.charset.StandardCharsets.UTF_8);
        try { store.importFrom(new java.io.ByteArrayInputStream(broken)); fail("Trailing corruption must not commit partial data"); }
        catch(Exception expected) { }
        assertEquals(1,store.all().size());
    }
}
