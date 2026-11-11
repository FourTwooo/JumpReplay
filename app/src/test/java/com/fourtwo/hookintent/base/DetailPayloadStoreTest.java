package com.fourtwo.hookintent.base;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;

import com.fourtwo.hookintent.data.ItemData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.Arrays;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class DetailPayloadStoreTest {
    @Test public void largeTypedIntentRoundTripsWhileNavigationOnlyContainsToken() {
        Context context = RuntimeEnvironment.getApplication();
        char[] characters = new char[2_200_000];
        Arrays.fill(characters, 'x');
        String text = new String(characters);
        Intent intent = new Intent("test.action", Uri.parse("demo://host/path?q=a+b%26c"));
        intent.putExtra("long-text", text);
        intent.putExtra("numbers", new int[]{1, 2, 3});
        Bundle record = DataConverter.convertIntentToBundle(intent);
        record.putString("uri", intent.toUri(Intent.URI_INTENT_SCHEME));
        ItemData original = new ItemData(null, "app", "from", "data", "time", "size", record,
                "Intent", "stack", record.getString("uri"));
        original.setCaptureId(42);

        String token = DetailPayloadStore.save(context, original);
        assertTrue(new File(context.getFilesDir(), "detail_payloads/" + token + ".json").length() > 2_000_000);
        ItemData restored = DetailPayloadStore.load(context, token);
        Intent replay = IntentPayloadCodec.decode(restored.getAppBundle());
        assertEquals(text, replay.getStringExtra("long-text"));
        assertArrayEquals(new int[]{1, 2, 3}, replay.getIntArrayExtra("numbers"));
        assertEquals("demo://host/path?q=a+b%26c", replay.getDataString());
        assertEquals("Intent", restored.getCategory());
        assertEquals(42, restored.getCaptureId());
        assertEquals(original.getStackTrace(), restored.getStackTrace());

        Bundle navigation = new Bundle();
        navigation.putString(DetailPayloadStore.ARG_TOKEN, token);
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeBundle(navigation);
            assertTrue(parcel.dataSize() < 1024);
        } finally { parcel.recycle(); }
    }

    @Test public void opaqueSchemeRetainsItsRawValue() {
        Context context = RuntimeEnvironment.getApplication();
        Bundle record = new Bundle();
        record.putString("scheme_raw_url", "mailto:user+tag@example.org?subject=a%26b");
        ItemData original = new ItemData(null, "mail", record.getString("scheme_raw_url"), "", "", "", record,
                "Scheme", "", "");
        ItemData restored = DetailPayloadStore.load(context, DetailPayloadStore.save(context, original));
        assertEquals(original.getAppBundle().getString("scheme_raw_url"), restored.getAppBundle().getString("scheme_raw_url"));
        assertEquals("Scheme", restored.getCategory());
    }

    @Test(expected = IllegalArgumentException.class) public void pathTraversalTokensAreRejected() {
        DetailPayloadStore.load(RuntimeEnvironment.getApplication(), "../other/file");
    }
}
