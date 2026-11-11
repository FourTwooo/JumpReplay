package com.fourtwo.hookintent.utils;

import android.app.Application;
import android.content.ClipData;
import android.content.Intent;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class IntentReplayTest {
    @Test public void unchangedRawUriKeepsAllIntentFields() throws Exception {
        Intent original = new Intent("custom.action", Uri.parse("demo://host?q=a+b%26c"));
        original.setType("text/plain");
        original.setDataAndType(Uri.parse("demo://host?q=a+b%26c"), "text/plain");
        original.putExtra("numbers", new int[]{1, 2});
        original.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        original.setClipData(ClipData.newPlainText("label", "payload"));
        Intent replay = IntentReplay.fromText(original.getDataString(), original, original.getDataString());
        assertEquals(original.getDataString(), replay.getDataString());
        assertEquals("custom.action", replay.getAction());
        assertEquals("text/plain", replay.getType());
        assertArrayEquals(new int[]{1, 2}, replay.getIntArrayExtra("numbers"));
        assertNotNull(replay.getClipData());
        assertTrue((replay.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    @Test public void editedDataStillKeepsArrayExtras() throws Exception {
        Intent original = new Intent(Intent.ACTION_VIEW, Uri.parse("demo://host/old"));
        original.putExtra("numbers", new int[]{9});
        Intent replay = IntentReplay.fromText("demo://host/new?q=100%25", original, original.getDataString());
        assertArrayEquals(new int[]{9}, replay.getIntArrayExtra("numbers"));
        assertEquals("demo://host/new?q=100%25", replay.getDataString());
    }

    @Test public void editedIntentUriCanChangeStringWithoutDroppingArray() throws Exception {
        Intent original = new Intent(Intent.ACTION_VIEW, Uri.parse("demo://host/path"));
        original.putExtra("name", "old");
        original.putExtra("numbers", new int[]{9});
        String source = original.toUri(Intent.URI_INTENT_SCHEME);
        Intent replay = IntentReplay.fromText(source.replace("S.name=old", "S.name=new"), original, source);
        assertEquals("new", replay.getStringExtra("name"));
        assertArrayEquals(new int[]{9}, replay.getIntArrayExtra("numbers"));
    }

    @Test public void editedPackageDoesNotConflictWithOldSelector() throws Exception {
        Intent original = new Intent(Intent.ACTION_VIEW);
        original.setSelector(new Intent(Intent.ACTION_MAIN));
        Intent replay = IntentReplay.fromText("intent:#Intent;action=android.intent.action.VIEW;package=com.example;end",
                original, original.toUri(Intent.URI_INTENT_SCHEME));
        assertEquals("com.example", replay.getPackage());
        assertNull(replay.getSelector());
    }
}
