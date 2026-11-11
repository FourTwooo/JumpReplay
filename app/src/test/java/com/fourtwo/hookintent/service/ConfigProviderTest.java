package com.fourtwo.hookintent.service;

import android.app.Application;
import android.content.ContentValues;
import android.net.Uri;
import com.fourtwo.hookintent.data.Constants;
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
}
