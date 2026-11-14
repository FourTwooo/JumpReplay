package com.fourtwo.hookintent.actions;

import android.app.Application;
import android.content.Context;
import java.io.File;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
// The API 33 AtomicFile implementation uses POSIX rename-over-existing, which Java's
// Windows filesystem cannot emulate. Modern overwrite/recreation is tested on-device.
@Config(application=Application.class,sdk=28)
public class ActionDraftStoreTest {
    @Test public void repeatedSavedStateReusesOneFileAndPruningNeverTouchesShortcuts() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        String permanent=com.fourtwo.hookintent.utils.ShortcutPayloadStore.save(context,"demo://permanent",new String[0],null);
        File shortcut=new File(new File(context.getFilesDir(),"shortcut_payloads"),permanent+".txt");
        long stale=System.currentTimeMillis()-ActionDraftStore.RETENTION_MS-10000;
        assertTrue(shortcut.setLastModified(stale));
        File folder=new File(context.getFilesDir(),"action_drafts");
        int existing=folder.list()==null ? 0 : folder.list().length;
        String draft=ActionDraftStore.newId();ActionDraftStore.retain(draft);
        for(int i=0;i<8;i++) ActionDraftStore.write(context,draft,new JSONObject().put("name","edit-"+i));
        File file=new File(new File(context.getFilesDir(),"action_drafts"),draft+".json");
        assertEquals("edit-7",ActionDraftStore.read(context,draft).getString("name"));
        assertEquals(java.util.Arrays.toString(file.getParentFile().list()),existing+1,file.getParentFile().list().length);
        assertTrue(file.setLastModified(stale));
        ActionDraftStore.prune(context,System.currentTimeMillis());assertTrue(file.exists());
        ActionDraftStore.release(draft);ActionDraftStore.prune(context,System.currentTimeMillis());
        assertFalse(file.exists());assertTrue(shortcut.exists());
        assertEquals("demo://permanent",com.fourtwo.hookintent.utils.ShortcutPayloadStore.load(context,permanent).template);
    }
    @Test public void deletingOneDraftKeepsOtherRecentDraftsAndRejectsPaths() throws Exception {
        Context context=RuntimeEnvironment.getApplication();
        String first=ActionDraftStore.create(context,new JSONObject().put("name","first"));
        String second=ActionDraftStore.create(context,new JSONObject().put("name","second"));
        ActionDraftStore.delete(context,first);ActionDraftStore.prune(context,System.currentTimeMillis());
        assertEquals("second",ActionDraftStore.read(context,second).getString("name"));
        try { ActionDraftStore.delete(context,"../shortcut_payloads/entry");fail(); }
        catch(IllegalArgumentException expected) { }
    }
}
