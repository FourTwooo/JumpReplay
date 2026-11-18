package com.fourtwo.hookintent.service;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.NavigationCaptureSource;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class ConfigProviderTest {
    @Test public void validInjectionWriteWorksAndMalformedWritePreservesPreviousConfiguration() {
        ConfigProvider provider = Robolectric.buildContentProvider(ConfigProvider.class).create().get();
        String valid = "[{\"packageName\":\"ALL\",\"className\":\"android.app.Activity\",\"methodName\":\"onResume\",\"category\":\"Intent\",\"open\":true,\"extension\":123}]";
        ContentValues values = new ContentValues(); values.put("value", valid);
        assertNotNull(provider.insert(Constants.CONFIG_URI, values));
        values.put("value", "[{\"open\":\"false\"}]");
        try { provider.insert(Constants.CONFIG_URI, values); fail("Invalid structure accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(valid, SharedPreferencesUtils.getStr(provider.getContext(), Constants.INTERNAL_HOOKS_CONFIG));
        values.put("value", valid);
        try { provider.insert(Uri.withAppendedPath(Constants.CONFIG_URI, "wrong"), values); fail("Invalid URI accepted"); }
        catch (IllegalArgumentException expected) { }
        assertEquals(valid, SharedPreferencesUtils.getStr(provider.getContext(), Constants.INTERNAL_HOOKS_CONFIG));
    }

    @Test public void navigationSwitchesMigrateIndependentlyOfInjectedHookConfiguration() {
        ConfigProvider provider = Robolectric.buildContentProvider(ConfigProvider.class).create().get();
        assertEquals("false", navigationFlag(provider, Constants.FRAGMENT_CAPTURE_ENABLED));
        assertEquals("false", navigationFlag(provider, Constants.NAV_CONTROLLER_CAPTURE_ENABLED));
        assertNull(SharedPreferencesUtils.getStr(provider.getContext(), Constants.INTERNAL_HOOKS_CONFIG));

        SharedPreferencesUtils.putBoolean(provider.getContext(), Constants.IN_APP_NAVIGATION_CAPTURE, true);
        assertEquals("true", navigationFlag(provider, Constants.FRAGMENT_CAPTURE_ENABLED));
        assertEquals("true", navigationFlag(provider, Constants.NAV_CONTROLLER_CAPTURE_ENABLED));

        NavigationCaptureSource.FRAGMENT.setEnabled(provider.getContext(), false);
        ContentValues values = new ContentValues();
        values.put("value", "[{\"packageName\":\"ALL\",\"className\":\"android.view.ViewTreeObserver$OnGlobalLayoutListener\","
                + "\"methodName\":\"onGlobalLayout\",\"category\":\"Fragment\",\"open\":true}]");
        provider.insert(Constants.CONFIG_URI, values);
        assertEquals("false", navigationFlag(provider, Constants.FRAGMENT_CAPTURE_ENABLED));
        assertEquals("true", navigationFlag(provider, Constants.NAV_CONTROLLER_CAPTURE_ENABLED));
        assertEquals("true", navigationFlag(provider, Constants.IN_APP_NAVIGATION_CAPTURE));

        NavigationCaptureSource.NAV_CONTROLLER.setEnabled(provider.getContext(), false);
        assertEquals("false", navigationFlag(provider, Constants.IN_APP_NAVIGATION_CAPTURE));
    }

    private String navigationFlag(ConfigProvider provider, String name) {
        try (Cursor cursor = provider.query(Constants.IN_APP_NAVIGATION_URI, null, null, null, null)) {
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(0))) return cursor.getString(1);
            }
        }
        fail("Missing navigation flag " + name);
        return null;
    }
}
