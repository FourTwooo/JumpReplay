package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;

import com.fourtwo.hookintent.base.DetailPayloadStore;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ItemData;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class NavigationReplayTest {
    private static final String FIRST = "example.first";
    private static final String SECOND = "example.second";
    private static final String HOST = "HostActivity";

    @Test public void savedRouteAppliesTypedMappingsToLaterRecords() {
        Context context = install(FIRST, true, null);
        ItemData first = capture(Constants.FRAGMENT_CATEGORY, FIRST, "Content", "42");
        Map<String, String> mappings = new LinkedHashMap<>();
        mappings.put("id", "screen_id");
        mappings.put("enabled", "display");
        String template = explicit(FIRST).putExtra("screen_id", 0).putExtra("sort", "latest")
                .toUri(Intent.URI_INTENT_SCHEME);

        assertNull(NavigationReplay.prepare(context, first));
        NavigationReplay.saveBinding(context, first, template, mappings);
        assertEquals(mappings, NavigationReplay.getBinding(context, first).getArgumentToExtra());

        ItemData later = capture(Constants.FRAGMENT_CATEGORY, FIRST, "Content", "89");
        ItemData draft = NavigationReplay.prepare(context, later);
        assertNotNull(draft);
        assertFalse(later.getAppBundle().containsKey(IntentPayloadCodec.KEY));
        assertEquals(Constants.FRAGMENT_CATEGORY, later.getCategory());
        assertEquals("Intent", draft.getCategory());
        ItemData restored = DetailPayloadStore.load(context, DetailPayloadStore.save(context, draft));
        Intent target = IntentPayloadCodec.decode(restored.getAppBundle());
        assertEquals(explicit(FIRST).getComponent(), target.getComponent());
        assertEquals(89, target.getIntExtra("screen_id", -1));
        assertEquals("latest", target.getStringExtra("sort"));
        assertEquals(true, target.getBooleanExtra("display", false));
        assertEquals(3, target.getExtras().size());
        assertEquals(1_700_000_000_000L, restored.getAppBundle().getLong("source_last_update"));
    }

    @Test public void rulesAreGenericAndMatchTheExactSourceDestination() {
        Context context = install(FIRST, true, null);
        install(SECOND, true, null);
        ItemData fragment = capture(Constants.FRAGMENT_CATEGORY, FIRST, "SameDestination", "13");
        ItemData controller = capture(Constants.NAV_CONTROLLER_CATEGORY, SECOND, "SameDestination", "29");
        NavigationReplay.saveBinding(context, fragment, explicit(FIRST).toUri(Intent.URI_INTENT_SCHEME),
                Collections.singletonMap("id", "value"));
        assertNull(NavigationReplay.prepare(context, controller));
        assertNull(NavigationReplay.prepare(context, capture(Constants.FRAGMENT_CATEGORY, FIRST, "Other", "13")));
        assertNull(NavigationReplay.prepare(context, capture(Constants.NAV_CONTROLLER_CATEGORY, FIRST, "SameDestination", "13")));

        NavigationReplay.saveBinding(context, controller, explicit(SECOND).toUri(Intent.URI_INTENT_SCHEME),
                Collections.singletonMap("id", "different_key"));
        Bundle second = NavigationReplay.prepare(context, controller).getAppBundle();
        assertEquals("29", IntentPayloadCodec.decode(second).getStringExtra("different_key"));
        NavigationReplay.removeBinding(context, fragment);
        assertNull(NavigationReplay.prepare(context, fragment));
        assertNotNull(NavigationReplay.prepare(context, controller));
    }

    @Test public void inaccessibleOrInvalidRulesCannotReplaceAnExistingRoute() {
        Context context = install(FIRST, true, null);
        install(SECOND, false, null);
        ItemData first = capture(Constants.FRAGMENT_CATEGORY, FIRST, "Content", "42");
        ItemData second = capture(Constants.FRAGMENT_CATEGORY, SECOND, "Content", "42");
        String template = explicit(FIRST).putExtra("value", 1).toUri(Intent.URI_INTENT_SCHEME);
        Map<String, String> valid = Collections.singletonMap("id", "value");
        NavigationReplay.saveBinding(context, first, template, valid);

        assertThrows(IllegalArgumentException.class, () -> NavigationReplay.saveBinding(context, first,
                explicit(SECOND).toUri(Intent.URI_INTENT_SCHEME), valid));
        assertThrows(IllegalArgumentException.class, () -> NavigationReplay.saveBinding(context, second,
                explicit(SECOND).toUri(Intent.URI_INTENT_SCHEME), valid));
        assertThrows(IllegalArgumentException.class, () -> NavigationReplay.saveBinding(context, first,
                template, Collections.singletonMap("missing", "value")));
        assertThrows(IllegalArgumentException.class, () -> NavigationReplay.saveBinding(context, first,
                template, Collections.singletonMap("enabled", "value")));
        assertEquals(valid, NavigationReplay.getBinding(context, first).getArgumentToExtra());

        install(FIRST, true, "example.permission.RESTRICTED");
        assertNull(NavigationReplay.prepare(context, first));
    }

    private static Intent explicit(String packageName) {
        return new Intent("example.OPEN").setComponent(new ComponentName(packageName, packageName + ".Entry"));
    }

    private static Context install(String packageName, boolean exported, String permission) {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo info = new PackageInfo();
        info.packageName = packageName;
        info.lastUpdateTime = 1_700_000_000_000L;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = packageName;
        info.applicationInfo.uid = FIRST.equals(packageName) ? 10263 : 10264;
        ActivityInfo entry = new ActivityInfo();
        entry.name = packageName + ".Entry";
        entry.packageName = packageName;
        entry.applicationInfo = info.applicationInfo;
        entry.exported = exported;
        entry.permission = permission;
        info.activities = new ActivityInfo[]{entry};
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        return context;
    }

    private static ItemData capture(String category, String packageName, String destination, String id) {
        Bundle arguments = new Bundle();
        arguments.putString("id", id);
        arguments.putBoolean("enabled", true);
        arguments.putString("internal", "Only the navigation record sees this");
        Bundle record = new Bundle();
        record.putString("category", category);
        record.putString("packageName", packageName);
        record.putString("from", HOST);
        record.putString("title", destination);
        record.putLong("source_last_update", 1L);
        record.putBundle(Constants.NAVIGATION_ARGUMENTS, arguments);
        return new ItemData(null, packageName, destination, "", "", "", record, category, "", "");
    }
}
