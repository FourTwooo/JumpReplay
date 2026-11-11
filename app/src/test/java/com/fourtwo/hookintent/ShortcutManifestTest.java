package com.fourtwo.hookintent;

import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.pm.ServiceInfo;
import android.content.pm.ProviderInfo;
import com.fourtwo.hookintent.service.ShortcutRootService;
import android.content.res.TypedArray;
import android.view.ContextThemeWrapper;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class ShortcutManifestTest {
    @Test public void privilegeOwnersRemainInMainProcess() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ServiceInfo root = context.getPackageManager().getServiceInfo(new ComponentName(context, ShortcutRootService.class), 0);
        ProviderInfo shizuku = context.getPackageManager().resolveContentProvider(context.getPackageName() + ".shizuku", 0);
        assertFalse(root.exported);
        assertEquals(context.getPackageName(), root.processName);
        assertNotNull(shizuku);
        assertEquals(context.getPackageName(), shizuku.processName);
        assertFalse(shizuku.multiprocess);
    }
    @Test public void shortcutHasPrivateProcessAndTaskWithoutChangingMainTask() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        ActivityInfo shortcut = context.getPackageManager().getActivityInfo(
                new ComponentName(context, ShortcutActivity.class), 0);
        ActivityInfo main = context.getPackageManager().getActivityInfo(
                new ComponentName(context, MainActivity.class), 0);

        assertEquals(context.getPackageName() + ":shortcut", shortcut.processName);
        assertEquals(context.getPackageName() + ".shortcut", shortcut.taskAffinity);
        assertEquals(ActivityInfo.LAUNCH_SINGLE_TASK, shortcut.launchMode);
        assertTrue((shortcut.flags & ActivityInfo.FLAG_EXCLUDE_FROM_RECENTS) != 0);
        // Permission UI may temporarily cover this Activity: it must survive for the result.
        assertEquals(0, shortcut.flags & ActivityInfo.FLAG_NO_HISTORY);
        assertNotEquals(shortcut.taskAffinity, main.taskAffinity);
        assertNotEquals(shortcut.processName, main.processName);
        assertEquals(ActivityInfo.LAUNCH_MULTIPLE, main.launchMode);
    }

    @Test public void shortcutWindowIsTransparentWithoutPreviewOrWindowDimming() {
        assertTransparentTheme();
    }

    @Test @Config(qualifiers = "night")
    public void shortcutWindowRemainsTransparentInNightMode() {
        assertTransparentTheme();
    }

    private void assertTransparentTheme() {
        Context themed = new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.TransparentActivity);
        TypedArray attributes = themed.obtainStyledAttributes(new int[]{
                android.R.attr.windowIsTranslucent, android.R.attr.windowDisablePreview,
                android.R.attr.backgroundDimEnabled, android.R.attr.windowBackground});
        try {
            assertTrue(attributes.getBoolean(0, false));
            assertTrue(attributes.getBoolean(1, false));
            assertFalse(attributes.getBoolean(2, true));
            assertEquals(android.graphics.Color.TRANSPARENT, attributes.getColor(3, -1));
        } finally { attributes.recycle(); }
    }
}
