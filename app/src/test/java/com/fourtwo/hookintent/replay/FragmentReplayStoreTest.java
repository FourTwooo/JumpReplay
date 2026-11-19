package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.AtomicFile;

import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.xposed.CaptureFiles;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class FragmentReplayStoreTest {
    private static final String TARGET = "example.test";

    @Test public void sameReplayIntentAcceptsDifferentTypedEditsAndKeepsItsOriginalRoute() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bundle capture = capturedCall(context);
        Bundle descriptor = capture.getBundle(Constants.NAVIGATION_REPLAY);
        Bundle edits = new Bundle();
        edits.putString("item", "edited value");
        edits.putLong("selectedIndex", 3L);

        Intent generated = FragmentReplayStore.createIntent(context, capture, descriptor, edits);
        String uri = generated.toUri(Intent.URI_INTENT_SCHEME);
        assertTrue(uri.contains("S.item="));
        assertTrue(uri.contains("l.selectedIndex=3"));
        assertFalse(uri.contains(TARGET));

        Intent incoming = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        assertEquals("edited value", incoming.getStringExtra("item"));
        incoming.putExtra("packageName", "another.app");
        incoming.putExtra("descriptor", "another route");
        incoming.putExtra("item", "first runtime value");
        edits.putString("item", "modified after saving");
        FragmentReplayStore.Prepared loaded = FragmentReplayStore.load(context, incoming);

        assertEquals(TARGET, loaded.source.getString("packageName"));
        assertEquals("first runtime value", loaded.arguments.getString("item"));
        assertEquals(3L, loaded.arguments.get("selectedIndex"));
        assertFalse(loaded.arguments.containsKey("packageName"));
        assertFalse(loaded.arguments.containsKey("descriptor"));
        assertEquals(descriptor.getString("capture_proof"), loaded.descriptor.getString("capture_proof"));

        Intent second = new Intent(incoming).replaceExtras((Bundle) null).putExtra("item", "第二个值");
        FragmentReplayStore.Prepared loadedAgain = FragmentReplayStore.load(context, second);
        assertEquals(incoming.getData(), second.getData());
        assertEquals("第二个值", loadedAgain.arguments.getString("item"));
        assertEquals(3L, loadedAgain.arguments.get("selectedIndex"));
        assertEquals("first runtime value", loaded.arguments.getString("item"));
        assertEquals("edited value", FragmentReplayStore.load(context,
                new Intent(incoming).replaceExtras((Bundle) null)).arguments.getString("item"));
    }

    @Test public void rejectsOverridesWithTheWrongTypeOrSize() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bundle capture = capturedCall(context);
        Bundle descriptor = capture.getBundle(Constants.NAVIGATION_REPLAY);
        Intent generated = FragmentReplayStore.createIntent(context, capture, descriptor,
                descriptor.getBundle("arguments"));

        assertThrows(IllegalArgumentException.class, () -> FragmentReplayStore.load(context,
                new Intent(generated).putExtra("selectedIndex", 3)));
        assertThrows(IllegalArgumentException.class, () -> FragmentReplayStore.load(context,
                new Intent(generated).putExtra("item", new String(new char[8193]))));
    }

    @Test public void olderFixedArgumentIntentStillIgnoresOverrides() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bundle capture = capturedCall(context);
        Bundle descriptor = capture.getBundle(Constants.NAVIGATION_REPLAY);
        Intent generated = FragmentReplayStore.createIntent(context, capture, descriptor,
                descriptor.getBundle("arguments"));
        File directory = new File(context.getNoBackupFilesDir(), "fragment_replays");
        File saved = new File(directory, generated.getData().getLastPathSegment());
        Bundle encoded = new Bundle();
        encoded.putString(IntentPayloadCodec.KEY,
                new String(new AtomicFile(saved).readFully(), StandardCharsets.US_ASCII));
        Intent savedReplay = IntentPayloadCodec.decode(encoded).putExtra("version", 1);
        CaptureFiles.write(saved, IntentPayloadCodec.encode(savedReplay).getBytes(StandardCharsets.US_ASCII));

        Intent legacy = new Intent(generated).replaceExtras((Bundle) null).putExtra("item", "ignored value");
        assertEquals("old captured value", FragmentReplayStore.load(context, legacy).arguments.getString("item"));
    }

    @Test public void rejectsUnknownCapabilitiesAndUnexpectedEntryShape() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bundle capture = capturedCall(context);
        Bundle descriptor = capture.getBundle(Constants.NAVIGATION_REPLAY);
        Intent generated = FragmentReplayStore.createIntent(context, capture, descriptor,
                descriptor.getBundle("arguments"));
        assertThrows(IllegalArgumentException.class, () -> FragmentReplayStore.load(context,
                new Intent(generated).setAction(Intent.ACTION_VIEW)));
        assertThrows(IllegalArgumentException.class, () -> FragmentReplayStore.load(context,
                new Intent(generated).setData(Uri.parse(generated.getDataString() + "?args=edited"))));

        String data = generated.getDataString();
        String unknown = data.substring(0, data.length() - 1) + (data.endsWith("0") ? '1' : '0');
        assertThrows(IOException.class, () -> FragmentReplayStore.load(context,
                new Intent(generated).setData(Uri.parse(unknown))));
    }

    private static Bundle capturedCall(Context context) throws Exception {
        PackageInfo target = new PackageInfo();
        target.packageName = TARGET;
        target.lastUpdateTime = 1_765_000_000_000L;
        target.applicationInfo = new ApplicationInfo();
        target.applicationInfo.packageName = TARGET;
        target.applicationInfo.uid = 11001;
        target.applicationInfo.enabled = true;
        shadowOf(context.getPackageManager()).installPackage(target);

        Bundle source = new Bundle();
        source.putString("packageName", TARGET);
        source.putString("processName", TARGET);
        source.putInt("source_uid", target.applicationInfo.uid);
        source.putInt("source_app_uid", target.applicationInfo.uid);
        source.putLong("source_last_update", target.lastUpdateTime);
        source.putInt("capture_sdk", Build.VERSION.SDK_INT);
        Bundle arguments = new Bundle();
        arguments.putString("item", "old captured value");
        arguments.putLong("selectedIndex", 1L);
        Bundle descriptor = new Bundle();
        descriptor.putString("mechanism", "fragment_host");
        descriptor.putString("host", TARGET + ".MainActivity");
        descriptor.putBundle("arguments", arguments);
        Bundle capture = new Bundle(source);
        capture.putString("category", Constants.FRAGMENT_CATEGORY);
        capture.putBundle(Constants.NAVIGATION_ARGUMENTS, arguments);
        capture.putBundle(Constants.NAVIGATION_REPLAY, NavigationCaptureProof.seal(context, source, descriptor));
        return capture;
    }
}
