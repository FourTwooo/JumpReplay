package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import com.fourtwo.hookintent.data.Constants;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.IOException;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class FragmentReplayStoreTest {
    private static final String TARGET = "example.test";

    @Test public void uriRoundTripLoadsTheSavedEditsAndIgnoresIncomingExtras() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bundle capture = capturedCall(context);
        Bundle descriptor = capture.getBundle(Constants.NAVIGATION_REPLAY);
        Bundle edits = new Bundle();
        edits.putString("item", "edited value");
        edits.putLong("selectedIndex", 3L);

        Intent generated = FragmentReplayStore.createIntent(context, capture, descriptor, edits);
        String uri = generated.toUri(Intent.URI_INTENT_SCHEME);
        assertFalse(uri.contains("edited value"));
        assertFalse(uri.contains(TARGET));

        Intent incoming = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        incoming.putExtra("packageName", "another.app");
        incoming.putExtra("item", "injected value");
        edits.putString("item", "modified after saving");
        FragmentReplayStore.Prepared loaded = FragmentReplayStore.load(context, incoming);

        assertEquals(TARGET, loaded.source.getString("packageName"));
        assertEquals("edited value", loaded.arguments.getString("item"));
        assertEquals(3L, loaded.arguments.get("selectedIndex"));
        assertEquals(descriptor.getString("capture_proof"), loaded.descriptor.getString("capture_proof"));
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
