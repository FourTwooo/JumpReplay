package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=33)
public class ReplayIdentityTest {
    private Context install() {
        Context context=RuntimeEnvironment.getApplication();
        PackageInfo info=new PackageInfo(); info.packageName="example.source"; info.lastUpdateTime=123456L;
        info.applicationInfo=new ApplicationInfo(); info.applicationInfo.packageName=info.packageName;
        info.applicationInfo.uid=10356;
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        return context;
    }
    private Bundle record() {
        Bundle record=new Bundle(); record.putString("packageName","example.source");
        record.putLong("source_last_update",123456L); record.putInt("source_uid",99085);
        return record;
    }
    @Test public void oldProcessUidIsNotAnInstallationIdentity() throws Exception {
        Bundle prepared=ReplayClassLoaders.prepare(install(),record());
        assertEquals(99085,prepared.getInt("source_uid"));
    }
    @Test public void matchingOwnerUidAndDifferentProcessUidRemainValid() throws Exception {
        Bundle record=record(); record.putInt("source_app_uid",10356);
        assertEquals(10356,ReplayClassLoaders.prepare(install(),record).getInt("source_app_uid"));
    }
    @Test public void actualChangedInstallationOwnerIsRejected() throws Exception {
        Bundle record=record(); record.putInt("source_app_uid",10357);
        try { ReplayClassLoaders.prepare(install(),record); fail("changed owner accepted"); }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("10357")); }
    }
    @Test public void legacyProcessUidDoesNotBypassAppVersionCheck() throws Exception {
        Bundle record=record(); record.putLong("source_last_update",222222L);
        try { ReplayClassLoaders.prepare(install(),record); fail("changed version accepted"); }
        catch(IllegalStateException expected) { assertTrue(expected.getMessage().contains("更新")); }
    }
    @Test public void laterWebViewPackageDoesNotInvalidateLegacyActualSource() throws Exception {
        Context context=install();
        Bundle record=record(); record.putInt("source_uid",10356);
        record.putString("packageName","com.google.android.webview");
        record.putString("processName","example.source:renderer");
        Bundle normalized=ReplayClassLoaders.normalize(context,record);
        assertEquals("example.source",normalized.getString("packageName"));
        assertEquals("com.google.android.webview",record.getString("packageName"));
        assertEquals("example.source",ReplayClassLoaders.prepare(context,record).getString("packageName"));
    }
    @Test public void legacyRepairRequiresMatchingVersionAndProcess() throws Exception {
        Context context=install();
        Bundle record=record(); record.putInt("source_uid",10356);
        record.putString("packageName","com.google.android.webview");
        record.putString("processName","another.application");
        assertEquals("com.google.android.webview",ReplayClassLoaders.normalize(context,record).getString("packageName"));
        record.putString("processName","example.source"); record.putLong("source_last_update",1L);
        assertEquals("com.google.android.webview",ReplayClassLoaders.normalize(context,record).getString("packageName"));
    }
}
