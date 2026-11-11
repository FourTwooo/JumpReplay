package com.fourtwo.blockertemplate;

import android.content.ClipData;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.net.Uri;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(AndroidJUnit4.class)
public class ForwardingIntentsInstrumentedTest {
    @Test
    public void queryAndForwardPreserveFileGrantsAndPayload() {
        Intent original = fileIntent();
        original.setComponent(new ComponentName("blocker.a", BlockerEntryActivity.class.getName()));
        Intent query = ForwardingIntents.forQuery(original);
        assertNull(query.getComponent());
        assertNull(query.getPackage());
        assertEquals(original.getFlags(), query.getFlags());

        ResolveInfo realTarget = target("real.app", "real.app.Viewer");
        Intent launch = ForwardingIntents.forLaunch(query, original, realTarget, "blocker.a");
        assertEquals(original.getFlags(), launch.getFlags());
        assertEquals(original.getClipData().getItemAt(0).getUri(), launch.getClipData().getItemAt(0).getUri());
        assertEquals("payload", launch.getStringExtra("test.payload"));
        assertEquals("real.app", launch.getComponent().getPackageName());
        assertFalse(launch.hasExtra(ForwardingIntents.EXTRA_VISITED_BLOCKERS));
    }

    @Test
    public void internalDecisionKeepsUriPermissionOwnerAlive() {
        Intent original = fileIntent();
        Intent decision = new Intent();
        ForwardingIntents.carryUriGrants(original, decision);
        assertEquals(original.getData(), decision.getData());
        assertEquals(original.getType(), decision.getType());
        assertTrue((decision.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
        assertEquals(original.getClipData().getItemCount(), decision.getClipData().getItemCount());
    }

    @Test
    public void forwardingToAnotherBlockerCarriesHistoryEvenAfterFallback() {
        Intent original = fileIntent();
        Intent fallback = new Intent(Intent.ACTION_VIEW, original.getData());
        ResolveInfo blocker = target("blocker.b", BlockerEntryActivity.class.getName());
        Intent launch = ForwardingIntents.forLaunch(fallback, original, blocker, "blocker.a");
        assertEquals(Arrays.asList("blocker.a"), ForwardingIntents.readHistory(launch));
        assertEquals("payload", launch.getStringExtra("test.payload"));
        assertTrue((launch.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0);
    }

    private Intent fileIntent() {
        Uri uri = Uri.parse("content://example.files/private/document");
        Intent intent = new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "text/plain");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.setClipData(ClipData.newRawUri("file", uri));
        intent.putExtra("test.payload", "payload");
        return intent;
    }

    private ResolveInfo target(String packageName, String className) {
        ResolveInfo info = new ResolveInfo();
        info.activityInfo = new ActivityInfo();
        info.activityInfo.packageName = packageName;
        info.activityInfo.name = className;
        return info;
    }
}
