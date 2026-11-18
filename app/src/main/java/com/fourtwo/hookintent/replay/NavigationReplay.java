package com.fourtwo.hookintent.replay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import androidx.core.content.pm.PackageInfoCompat;

import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.data.NavigationCaptureSource;

/** Creates an editor draft only for a destination with a verified launch entry point. */
public final class NavigationReplay {
    private static final String WECHAT = "com.tencent.mm";
    private static final String LAUNCHER = "com.tencent.mm.ui.LauncherUI";
    private static final String CHATTING = "com.tencent.mm.ui.chatting.ChattingUIFragment";
    private static final long VERIFIED_VERSION_CODE = 3200L;

    private NavigationReplay() { }

    public static ItemData prepare(Context context, ItemData capture) {
        Bundle record = capture.getAppBundle();
        if (NavigationCaptureSource.fromRecord(capture.getCategory(), record) != NavigationCaptureSource.FRAGMENT
                || !WECHAT.equals(record.getString("packageName"))
                || !LAUNCHER.equals(record.getString("from"))
                || !CHATTING.equals(record.getString("title"))) return null;
        Bundle arguments = record.getBundle(Constants.NAVIGATION_ARGUMENTS);
        String user = arguments == null ? null : arguments.getString("Chat_User");
        if (user == null || user.trim().isEmpty()) return null;

        ComponentName target = new ComponentName(WECHAT, LAUNCHER);
        PackageManager packages = context.getPackageManager();
        try {
            PackageInfo installed = packages.getPackageInfo(WECHAT, 0);
            if (PackageInfoCompat.getLongVersionCode(installed) != VERIFIED_VERSION_CODE
                    || installed.applicationInfo == null || !installed.applicationInfo.enabled
                    || installed.applicationInfo.uid != record.getInt("source_app_uid", -1)
                    || installed.lastUpdateTime != record.getLong("source_last_update", -1)
                    || Build.VERSION.SDK_INT != record.getInt("capture_sdk", -1)) return null;
            ActivityInfo launcher = packages.getActivityInfo(target, 0);
            if (!launcher.exported || !launcher.enabled || launcher.permission != null) return null;
        } catch (PackageManager.NameNotFoundException | SecurityException unavailable) {
            return null;
        }

        Intent intent = new Intent().setComponent(target)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("Main_User", user)
                .putExtra("Intro_Is_Muti_Talker", false);
        String uri = intent.toUri(Intent.URI_INTENT_SCHEME);
        Bundle draft = new Bundle(record);
        draft.putAll(DataConverter.convertIntentToBundle(intent));
        draft.putString("uri", uri);
        draft.putString("category", "Intent");
        return new ItemData(capture.getIcon(), capture.getAppName(), capture.getItem_from(), capture.getItem_data(),
                capture.getTimestamp(), capture.getDataSize(), draft, "Intent", capture.getStackTrace(), uri);
    }
}
