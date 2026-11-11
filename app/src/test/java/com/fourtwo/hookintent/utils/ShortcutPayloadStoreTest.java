package com.fourtwo.hookintent.utils;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class ShortcutPayloadStoreTest {
    @Test public void launcherOnlyNeedsSmallIdWhileCompleteTemplateAndArraysSurvive() throws Exception {
        char[] characters = new char[2200000]; java.util.Arrays.fill(characters, 'x');
        String template = "demo://host?q=" + new String(characters);
        Intent original = new Intent(Intent.ACTION_VIEW, Uri.parse(template));
        original.putExtra("numbers", new int[]{1, 7});
        String id = ShortcutPayloadStore.save(RuntimeEnvironment.getApplication(), template, new String[]{"host"}, original);
        ShortcutPayloadStore.Payload restored = ShortcutPayloadStore.load(RuntimeEnvironment.getApplication(), id);
        assertEquals(template, restored.template);
        assertArrayEquals(new String[]{"host"}, restored.parameters);
        assertArrayEquals(new int[]{1, 7}, restored.target.getIntArrayExtra("numbers"));
        assertEquals(template, restored.target.getDataString());
        assertEquals(36, id.length());
    }

    @Test public void legacyShortcutWithoutPayloadIdStillWorks() throws Exception {
        assertNull(ShortcutPayloadStore.load(RuntimeEnvironment.getApplication(), null));
    }
}
