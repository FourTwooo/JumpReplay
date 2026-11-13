package com.fourtwo.hookintent.xposed;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import java.lang.reflect.Method;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = {28, 30, 31, 33})
public class SystemQuerySpecTest {
    // Signatures taken from android11/12, android13/14, and android15/16-release.
    static class R {
        List<?> queryIntentActivitiesInternal(Intent i, String t, int f, int u) { return null; }
        List<?> queryIntentActivitiesInternal(Intent i, String t, int f, int p, int uid, int u, boolean s, boolean d) { return null; }
    }
    static class T {
        List<?> queryIntentActivitiesInternal(Intent i, String t, long f, long p, int uid, int u, boolean s, boolean d) { return null; }
    }
    static class V {
        List<?> queryIntentActivitiesInternal(Intent i, String t, long f, long p, int uid, int pid, int u, boolean s, boolean d) { return null; }
    }
    static class Unknown {
        List<?> queryIntentActivitiesInternal(Intent i, String t, long f, int p, int uid, int u, boolean s, boolean d) { return null; }
        String queryIntentActivitiesInternal(Intent i, String t, int f, int u) { return null; }
    }
    @Test public void fullLeafOverloadIsSelectedOnce() {
        Method r = SystemQuerySpec.select(R.class);
        assertEquals(8, r.getParameterTypes().length);
        assertEquals(5, SystemQuerySpec.userIdIndex(r));
        assertEquals(5, SystemQuerySpec.userIdIndex(SystemQuerySpec.select(T.class)));
        assertEquals(6, SystemQuerySpec.userIdIndex(SystemQuerySpec.select(V.class)));
        assertNull(SystemQuerySpec.select(Unknown.class));
    }
    @Test public void android12NestedOwnerIsIncludedBeforeWrapperService() {
        assertArrayEquals(new String[]{"com.android.server.pm.ComputerEngine",
                "com.android.server.pm.PackageManagerService$ComputerEngine",
                "com.android.server.pm.PackageManagerService"}, SystemQuerySpec.owners());
    }
    @Test public void actualAndroidFrameworkQuerySignatureIsRecognized() throws Exception {
        Method selected = null;
        for (String owner : SystemQuerySpec.owners()) {
            try { selected = SystemQuerySpec.select(Class.forName(owner, false, getClass().getClassLoader())); }
            catch (ClassNotFoundException ignored) { }
            if (selected != null) break;
        }
        assertNotNull(selected);
        assertTrue(SystemQuerySpec.userIdIndex(selected) >= 0);
        assertEquals(android.os.Build.VERSION.SDK_INT < 30 ? 7 : 8, selected.getParameterTypes().length);
        if (android.os.Build.VERSION.SDK_INT >= 31) assertTrue(selected.getDeclaringClass().getName().contains("ComputerEngine"));
    }
    @Test public void explicitAndPackageRestrictedRequestsAreUntouchedIncludingSelectors() {
        Intent implicit = new Intent(Intent.ACTION_VIEW, Uri.parse("sample://item"));
        assertSame(implicit, SystemQuerySpec.implicitUri(implicit));
        assertNull(SystemQuerySpec.implicitUri(new Intent(implicit).setPackage("example")));
        assertNull(SystemQuerySpec.implicitUri(new Intent(implicit).setClassName("example", "example.Screen")));
        Intent outer = new Intent(Intent.ACTION_MAIN); outer.setSelector(implicit);
        assertSame(implicit, SystemQuerySpec.implicitUri(outer));
        implicit.setPackage("example");
        assertNull(SystemQuerySpec.implicitUri(outer));
        assertNull(SystemQuerySpec.implicitUri(new Intent(Intent.ACTION_MAIN)));
    }
}
