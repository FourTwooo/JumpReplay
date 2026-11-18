package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class NavigationReplayTest {
    private static final String WECHAT = "com.tencent.mm";
    private static final String LAUNCHER = "com.tencent.mm.ui.LauncherUI";
    private static final String CHATTING = "com.tencent.mm.ui.chatting.ChattingUIFragment";

    @Test public void verifiedLegacyCaptureCreatesASeparateReplayableDraft() {
        Context context = installWeChat(3200L, true);
        Bundle record = fragmentRecord();
        ItemData capture = capture(Constants.NAVIGATION_CATEGORY, record);

        ItemData draft = NavigationReplay.prepare(context, capture);
        assertNotNull(draft);
        assertFalse(record.containsKey(IntentPayloadCodec.KEY));
        assertEquals("Intent", draft.getCategory());
        ItemData restored = DetailPayloadStore.load(context, DetailPayloadStore.save(context, draft));
        Intent target = IntentPayloadCodec.decode(restored.getAppBundle());
        assertEquals(WECHAT, target.getComponent().getPackageName());
        assertEquals(LAUNCHER, target.getComponent().getClassName());
        assertNull(target.getAction());
        assertEquals(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP, target.getFlags());
        assertEquals("sample-conversation", target.getStringExtra("Main_User"));
        assertFalse(target.getBooleanExtra("Intro_Is_Muti_Talker", true));
        assertEquals(2, target.getExtras().size());
        assertEquals(record.getInt("source_app_uid"), restored.getAppBundle().getInt("source_app_uid"));
    }

    @Test public void limitsReplayToTheVerifiedDestinationAndInstallation() {
        Context context = installWeChat(3201L, true);
        Bundle record = fragmentRecord();
        assertNull(NavigationReplay.prepare(context, capture(Constants.FRAGMENT_CATEGORY, record)));

        installWeChat(3200L, false);
        assertNull(NavigationReplay.prepare(context, capture(Constants.FRAGMENT_CATEGORY, record)));

        installWeChat(3200L, true);
        assertNull(NavigationReplay.prepare(context, capture(Constants.NAV_CONTROLLER_CATEGORY, record)));
        record.putInt("source_app_uid", 10264);
        assertNull(NavigationReplay.prepare(context, capture(Constants.FRAGMENT_CATEGORY, record)));
    }

    private Context installWeChat(long version, boolean exported) {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo info = new PackageInfo();
        info.packageName = WECHAT;
        info.setLongVersionCode(version);
        info.lastUpdateTime = 1734000000L;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = WECHAT;
        info.applicationInfo.uid = 10263;
        ActivityInfo launcher = new ActivityInfo();
        launcher.name = LAUNCHER;
        launcher.packageName = WECHAT;
        launcher.applicationInfo = info.applicationInfo;
        launcher.exported = exported;
        info.activities = new ActivityInfo[]{launcher};
        Shadows.shadowOf(context.getPackageManager()).installPackage(info);
        return context;
    }

    private Bundle fragmentRecord() {
        Bundle arguments = new Bundle();
        arguments.putString("Chat_User", "sample-conversation");
        arguments.putString("internal", "only used by the Fragment");
        Bundle record = new Bundle();
        record.putString("category", Constants.NAVIGATION_CATEGORY);
        record.putString("FunctionCall", Constants.FRAGMENT_CATEGORY);
        record.putString("packageName", WECHAT);
        record.putString("from", LAUNCHER);
        record.putString("title", CHATTING);
        record.putInt("capture_sdk", Build.VERSION.SDK_INT);
        record.putInt("source_app_uid", 10263);
        record.putLong("source_last_update", 1734000000L);
        record.putBundle(Constants.NAVIGATION_ARGUMENTS, arguments);
        return record;
    }

    private ItemData capture(String category, Bundle record) {
        return new ItemData(null, "WeChat", CHATTING, "", "", "", record, category, "", "");
    }
}
