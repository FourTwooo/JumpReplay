package com.fourtwo.hookintent.base;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import java.util.Arrays;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class IntentRoundTripTest {
    @Test public void typedExtrasAndUriSurviveJsonPersistence() {
        Bundle nested = new Bundle();
        nested.putLong("large", Long.MAX_VALUE);
        Intent original = new Intent(Intent.ACTION_SEND);
        original.setComponent(new ComponentName("example.target", "library.alias.Entry"));
        original.setDataAndType(Uri.parse("demo://host/p?q=a+b%26c%3Bd"), "text/plain");
        original.addCategory("example.CATEGORY");
        original.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        original.putExtra("ints", new int[]{1, -2, 3});
        original.putExtra("bytes", new byte[]{0, 127, -1});
        original.putExtra("nested", nested);
        original.putExtra("null", (String) null);
        original.putExtra("text", "quote' \" $(untrusted) + %26");
        Bundle capture = DataConverter.convertIntentToBundle(original);
        capture.putString("uri", original.toUri(Intent.URI_INTENT_SCHEME));
        Bundle restoredRecord = JsonHandler.fromJson(JsonHandler.toJson(Arrays.asList(capture))).get(0);
        Intent restored = IntentPayloadCodec.decode(restoredRecord);
        assertEquals(original.getDataString(), restored.getDataString());
        assertEquals(original.getComponent(), restored.getComponent());
        assertEquals("example.target", restoredRecord.getString("componentPackageName"));
        assertEquals(original.getType(), restored.getType());
        assertEquals(original.getCategories(), restored.getCategories());
        assertArrayEquals(new int[]{1, -2, 3}, restored.getIntArrayExtra("ints"));
        assertArrayEquals(new byte[]{0, 127, -1}, restored.getByteArrayExtra("bytes"));
        assertEquals(Long.MAX_VALUE, restored.getBundleExtra("nested").getLong("large"));
        assertTrue(restored.hasExtra("null"));
        assertNull(restored.getStringExtra("null"));
        assertEquals(original.getStringExtra("text"), restored.getStringExtra("text"));
    }

    @Test public void oldUriRecordsRemainReplayableWithoutDecodingPercentOrPlus() {
        Bundle record = new Bundle();
        record.putString("uri", "demo://host/?q=a+b%26c");
        assertEquals("demo://host/?q=a+b%26c", IntentPayloadCodec.decode(record).getDataString());
    }

    @Test public void amCommandRetainsImplicitFieldsAndQuotesEveryArgument() {
        Intent intent = new Intent("example.OPEN", Uri.parse("demo://host/?q=a+b%26c"));
        intent.setType("text/plain");
        intent.setData(Uri.parse("demo://host/?q=a+b%26c"));
        intent.addCategory("example.CATEGORY");
        intent.putExtra("key with space", "a' \" $(untrusted)");
        intent.putExtra("numbers", new int[]{4, 5});
        Bundle record = DataConverter.convertIntentToBundle(intent);
        record = JsonHandler.fromJson(JsonHandler.toJson(Arrays.asList(record))).get(0);
        AmCommandBuilder.CommandResult command = AmCommandBuilder.buildIntentCommand(record);
        assertFalse(command.hasError);
        assertFalse(command.command.contains("-n 'null'"));
        assertTrue(command.command.contains("-a 'example.OPEN'"));
        assertTrue(command.command.contains("-d 'demo://host/?q=a+b%26c'"));
        assertTrue(command.command.contains("--eia 'numbers' '4,5'"));
        assertTrue(command.command.contains("--es 'key with space' 'a'\\'' \" $(untrusted)'"));
    }

    @Test public void oldArrayIdentityStringProducesWarningInsteadOfCrash() {
        java.util.Map<String, Object> extra = new java.util.HashMap<>();
        extra.put("key", "array"); extra.put("class", "[I"); extra.put("value", "[I@1234");
        AmCommandBuilder.CommandResult result = AmCommandBuilder.buildAmCommand(Arrays.asList(extra));
        assertTrue(result.hasError);
        assertEquals("", result.command);
    }
}
