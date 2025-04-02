package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.ShortcutActivity;

import java.util.ArrayList;
import java.util.UUID;

public class ShortcutHelper {

    public static void createShortcut(Context context, String shortcutName, Object shortcutIcon, String targetIntent, ArrayList<String> replaceParams, Boolean isChecked, String SelectedItem) {
        String shortcutId = UUID.randomUUID().toString();
        ShortcutManager shortcutManager = null;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N_MR1) {
            shortcutManager = context.getSystemService(ShortcutManager.class);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (shortcutManager != null && shortcutManager.isRequestPinShortcutSupported()) {
                // 创建用于快捷方式的 Intent
                Intent shortcutIntent = new Intent(context, ShortcutActivity.class);
                shortcutIntent.setAction(Intent.ACTION_VIEW);
                shortcutIntent.putExtra("target_intent", targetIntent);
                shortcutIntent.putExtra("replace_params", replaceParams.toArray(new String[0]));
                shortcutIntent.putExtra("isChecked", isChecked);
                shortcutIntent.putExtra("SelectedItem", SelectedItem);

                // 构建 Icon，根据传入的 shortcutIcon 类型
                Icon icon;
                if (shortcutIcon instanceof Integer) {
                    // 如果传入的是资源 ID
                    icon = Icon.createWithResource(context, (int) shortcutIcon);
                } else if (shortcutIcon instanceof Bitmap) {
                    // 如果传入的是 Bitmap
                    icon = Icon.createWithBitmap((Bitmap) shortcutIcon);
                } else if (shortcutIcon instanceof Uri) {
                    // 如果传入的是 Uri
                    icon = Icon.createWithContentUri((Uri) shortcutIcon);
                } else {
                    icon = Icon.createWithResource(context, R.drawable.ic_launcher_foreground);
                }

                // 创建快捷方式信息
                ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(context, shortcutId)
                        .setShortLabel(shortcutName)
                        .setIcon(icon)
                        .setIntent(shortcutIntent)
                        .build();

                // 请求创建快捷方式
                shortcutManager.requestPinShortcut(shortcutInfo, null);
            }
        }
    }
}
