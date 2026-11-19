package com.fourtwo.hookintent.service;

import android.app.Application;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.NavigationCaptureSource;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import java.util.Map;
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

    @Test public void fragmentSwitchMigratesIndependentlyOfInjectedHookConfiguration() {
        ConfigProvider provider = Robolectric.buildContentProvider(ConfigProvider.class).create().get();
        assertEquals("false", navigationFlag(provider, Constants.FRAGMENT_CAPTURE_ENABLED));
        assertNull(navigationFlag(provider, "navigationControllerCapture"));
        assertNull(navigationFlag(provider, "viewPagerCapture"));
        assertNull(SharedPreferencesUtils.getStr(provider.getContext(), Constants.INTERNAL_HOOKS_CONFIG));

        SharedPreferencesUtils.putBoolean(provider.getContext(), Constants.IN_APP_NAVIGATION_CAPTURE, true);
        assertEquals("true", navigationFlag(provider, Constants.FRAGMENT_CAPTURE_ENABLED));

        NavigationCaptureSource.FRAGMENT.setEnabled(provider.getContext(), false);
        SharedPreferencesUtils.putBoolean(provider.getContext(), "navigationControllerCapture", true);
        SharedPreferencesUtils.putBoolean(provider.getContext(), "viewPagerCapture", true);
        ContentValues values = new ContentValues();
        values.put("value", "[{\"packageName\":\"ALL\",\"className\":\"android.view.ViewTreeObserver$OnGlobalLayoutListener\","
                + "\"methodName\":\"onGlobalLayout\",\"category\":\"Fragment\",\"open\":true}]");
        provider.insert(Constants.CONFIG_URI, values);
        assertEquals("false", navigationFlag(provider, Constants.FRAGMENT_CAPTURE_ENABLED));
        assertEquals("false", navigationFlag(provider, Constants.IN_APP_NAVIGATION_CAPTURE));

        NavigationCaptureSource fragment = NavigationCaptureSource.FRAGMENT;
        fragment.setEnabled(provider.getContext(), true);
        assertSame(fragment, NavigationCaptureSource.forConfigurationRow(fragment.configurationRow(provider.getContext())));
        assertEquals("true", navigationFlag(provider, Constants.FRAGMENT_CAPTURE_ENABLED));
        assertEquals("true", navigationFlag(provider, Constants.IN_APP_NAVIGATION_CAPTURE));
    }

    @Test public void retiredBuiltInsRemainRecognizableInHistory() {
        Bundle record = new Bundle();
        record.putBundle(Constants.NAVIGATION_ARGUMENTS, Bundle.EMPTY);
        assertTrue(NavigationCaptureSource.isHistoricalRecord(Constants.VIEW_PAGER_CATEGORY, record));
        assertTrue(NavigationCaptureSource.isHistoricalRecord(Constants.NAV_CONTROLLER_CATEGORY, record));
        assertNull(NavigationCaptureSource.fromRecord(Constants.VIEW_PAGER_CATEGORY, record));
        assertNull(NavigationCaptureSource.fromRecord(Constants.NAV_CONTROLLER_CATEGORY, record));
        record.putString("FunctionCall", "Fragment");
        assertSame(NavigationCaptureSource.FRAGMENT,
                NavigationCaptureSource.fromRecord(Constants.NAVIGATION_CATEGORY, record));
        record.putString("FunctionCall", "NavController");
        assertTrue(NavigationCaptureSource.isHistoricalRecord(Constants.NAVIGATION_CATEGORY, record));

        assertTrue(NavigationCaptureSource.isRetiredConfigurationRow(Map.of(
                "packageName", "ALL", "category", Constants.VIEW_PAGER_CATEGORY,
                "className", "androidx.viewpager.widget.ViewPager", "methodName", "setCurrentItem")));
        assertFalse(NavigationCaptureSource.isRetiredConfigurationRow(Map.of(
                "internal", false, "packageName", "ALL", "category", Constants.VIEW_PAGER_CATEGORY,
                "className", "androidx.viewpager.widget.ViewPager", "methodName", "setCurrentItem")));
    }

    private String navigationFlag(ConfigProvider provider, String name) {
        try (Cursor cursor = provider.query(Constants.IN_APP_NAVIGATION_URI, null, null, null, null)) {
            while (cursor.moveToNext()) {
                if (name.equals(cursor.getString(0))) return cursor.getString(1);
            }
        }
        return null;
    }
}
