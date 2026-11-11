package com.fourtwo.hookintent.utils;

import android.app.AppOpsManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Icon;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.ShortcutActivity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ShortcutHelper {

    private static final long NO_CALLBACK_WARNING_DELAY_MS = 15000L;
    private static final long RECEIVER_RELEASE_DELAY_MS = 120000L;

    // MIUI 中常见的安装桌面快捷方式 AppOps 值。不是 Android 官方公开 API，只能作为最佳努力检测。
    private static final int MIUI_OP_INSTALL_SHORTCUT = 10017;

    private ShortcutHelper() {
    }

    public enum PermissionState {
        GRANTED,
        DENIED,
        UNKNOWN
    }

    public interface ShortcutCallback {
        default void onNotSupported() {
        }

        default void onPermissionDenied() {
        }

        default void onRequestSent() {
        }

        default void onPinned() {
        }

        default void onNoResultMaybeDenied() {
        }

        default void onError(Exception e) {
        }
    }

    /**
     * 兼容你原来的调用方式。
     */
    public static boolean createShortcut(
            Context context,
            String shortcutName,
            Object shortcutIcon,
            String targetIntent,
            ArrayList<String> replaceParams,
            Boolean isChecked,
            String selectedItem
    ) {
        return createShortcut(
                context,
                shortcutName,
                shortcutIcon,
                targetIntent,
                replaceParams,
                isChecked,
                selectedItem,
                null
        );
    }

    /**
     * 推荐使用这个重载：可以拿到请求结果、成功回调、疑似权限被拦截提示。
     */
    public static boolean createShortcut(
            Context context,
            String shortcutName,
            Object shortcutIcon,
            String targetIntent,
            ArrayList<String> replaceParams,
            Boolean isChecked,
            String selectedItem,
            ShortcutCallback callback
    ) {
        return createShortcut(context, shortcutName, shortcutIcon, targetIntent, replaceParams,
                isChecked, selectedItem, null, callback);
    }

    public static boolean createShortcut(Context context, String shortcutName, Object shortcutIcon,
            String targetIntent, ArrayList<String> replaceParams, Boolean isChecked, String selectedItem,
            Intent completeTarget, ShortcutCallback callback) {
        final ShortcutCallback cb = callback == null ? new ShortcutCallback() {
        } : callback;

        final Context appContext = context.getApplicationContext();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            cb.onNotSupported();
            return false;
        }

        ShortcutManager shortcutManager = appContext.getSystemService(ShortcutManager.class);
        if (shortcutManager == null || !shortcutManager.isRequestPinShortcutSupported()) {
            cb.onNotSupported();
            return false;
        }

        PermissionState permissionState = checkShortcutPermissionBestEffort(appContext);
        if (permissionState == PermissionState.DENIED) {
            cb.onPermissionDenied();
            return false;
        }

        String shortcutId = UUID.randomUUID().toString();
        String safeName = TextUtils.isEmpty(shortcutName)
                ? appContext.getString(R.string.app_name)
                : shortcutName.trim();

        Intent shortcutIntent = new Intent(appContext, ShortcutActivity.class);
        shortcutIntent.setAction(Intent.ACTION_VIEW);
        shortcutIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        shortcutIntent.putExtra("isChecked", Boolean.TRUE.equals(isChecked));
        shortcutIntent.putExtra("SelectedItem", selectedItem == null ? "" : selectedItem);

        try {
            ShortcutParameterReplacer.validate(targetIntent, replaceParams == null ? null : replaceParams.toArray(new String[0]));
            shortcutIntent.putExtra("target_payload_id", ShortcutPayloadStore.save(appContext, targetIntent,
                    replaceParams == null ? null : replaceParams.toArray(new String[0]), completeTarget));
        } catch (Exception error) {
            cb.onError(error);
            return false;
        }

        Icon icon = buildIcon(appContext, shortcutIcon);

        ShortcutInfo shortcutInfo = new ShortcutInfo.Builder(appContext, shortcutId)
                .setShortLabel(safeName)
                .setLongLabel(safeName)
                .setIcon(icon)
                .setIntent(shortcutIntent)
                .build();

        Handler mainHandler = new Handler(Looper.getMainLooper());
        AtomicBoolean pinned = new AtomicBoolean(false);
        AtomicBoolean receiverUnregistered = new AtomicBoolean(false);

        String callbackAction = appContext.getPackageName()
                + ".action.SHORTCUT_PINNED."
                + shortcutId;

        BroadcastReceiver successReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                pinned.set(true);
                safeUnregisterReceiver(appContext, this, receiverUnregistered);
                cb.onPinned();
            }
        };

        try {
            registerReceiverCompat(appContext, successReceiver, new IntentFilter(callbackAction));

            Intent callbackIntent = new Intent(callbackAction);
            callbackIntent.setPackage(appContext.getPackageName());
            callbackIntent.putExtra("shortcut_id", shortcutId);

            int pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                pendingIntentFlags |= PendingIntent.FLAG_IMMUTABLE;
            }

            PendingIntent successCallback = PendingIntent.getBroadcast(
                    appContext,
                    shortcutId.hashCode(),
                    callbackIntent,
                    pendingIntentFlags
            );

            boolean requestSent = shortcutManager.requestPinShortcut(
                    shortcutInfo,
                    successCallback.getIntentSender()
            );

            if (!requestSent) {
                safeUnregisterReceiver(appContext, successReceiver, receiverUnregistered);
                cb.onNotSupported();
                return false;
            }

            cb.onRequestSent();

            mainHandler.postDelayed(() -> {
                if (!pinned.get()) {
                    cb.onNoResultMaybeDenied();
                }
            }, NO_CALLBACK_WARNING_DELAY_MS);

            mainHandler.postDelayed(() -> {
                if (!pinned.get()) {
                    safeUnregisterReceiver(appContext, successReceiver, receiverUnregistered);
                }
            }, RECEIVER_RELEASE_DELAY_MS);

            return true;

        } catch (Exception e) {
            safeUnregisterReceiver(appContext, successReceiver, receiverUnregistered);
            cb.onError(e);
            return false;
        }
    }

    /**
     * 最佳努力检测快捷方式权限。
     *
     * 注意：
     * 1. Android 官方没有公开的“创建桌面快捷方式权限”检测接口。
     * 2. 这里主要针对 MIUI 等 ROM 的 AppOps 做尝试。
     * 3. UNKNOWN 不代表有权限，只代表无法提前判断。
     */
    public static PermissionState checkShortcutPermissionBestEffort(Context context) {
        if (!isMiuiLikeRom() || Build.VERSION.SDK_INT < Build.VERSION_CODES.KITKAT) {
            return PermissionState.UNKNOWN;
        }

        try {
            AppOpsManager appOpsManager =
                    (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);

            if (appOpsManager == null) {
                return PermissionState.UNKNOWN;
            }

            Method checkOpNoThrow = AppOpsManager.class.getDeclaredMethod(
                    "checkOpNoThrow",
                    int.class,
                    int.class,
                    String.class
            );
            checkOpNoThrow.setAccessible(true);

            int mode = (int) checkOpNoThrow.invoke(
                    appOpsManager,
                    MIUI_OP_INSTALL_SHORTCUT,
                    Process.myUid(),
                    context.getPackageName()
            );

            if (mode == AppOpsManager.MODE_ALLOWED) {
                return PermissionState.GRANTED;
            }

            if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED) {
                return PermissionState.DENIED;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
                    && mode == AppOpsManager.MODE_FOREGROUND) {
                return PermissionState.GRANTED;
            }

        } catch (Throwable ignored) {
        }

        return PermissionState.UNKNOWN;
    }

    public static boolean canRequestPinShortcut(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return false;
        }

        ShortcutManager shortcutManager = context.getSystemService(ShortcutManager.class);
        return shortcutManager != null && shortcutManager.isRequestPinShortcutSupported();
    }

    public static void openShortcutPermissionSettings(Context context) {
        if (openMiuiPermissionEditor(context)) {
            return;
        }

        Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
        intent.setData(Uri.parse("package:" + context.getPackageName()));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static boolean openMiuiPermissionEditor(Context context) {
        if (!isMiuiLikeRom()) {
            return false;
        }

        Intent[] intents = new Intent[]{
                new Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName(
                                "com.miui.securitycenter",
                                "com.miui.permcenter.permissions.PermissionsEditorActivity"
                        )
                        .putExtra("extra_pkgname", context.getPackageName()),

                new Intent("miui.intent.action.APP_PERM_EDITOR")
                        .setClassName(
                                "com.miui.securitycenter",
                                "com.miui.permcenter.permissions.AppPermissionsEditorActivity"
                        )
                        .putExtra("extra_pkgname", context.getPackageName())
        };

        for (Intent intent : intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
                return true;
            } catch (Exception ignored) {
            }
        }

        return false;
    }

    private static Icon buildIcon(Context context, Object shortcutIcon) {
        if (shortcutIcon instanceof Integer) {
            return Icon.createWithResource(context, (int) shortcutIcon);
        }

        if (shortcutIcon instanceof Bitmap) {
            Bitmap bitmap = resizeBitmapIfNeeded(context, (Bitmap) shortcutIcon);
            return Icon.createWithBitmap(bitmap);
        }

        if (shortcutIcon instanceof Uri) {
            return Icon.createWithContentUri(((Uri) shortcutIcon).toString());
        }

        return Icon.createWithResource(context, R.drawable.ic_launcher_foreground);
    }

    private static Bitmap resizeBitmapIfNeeded(Context context, Bitmap bitmap) {
        if (bitmap == null || bitmap.isRecycled()) {
            return bitmap;
        }

        int width = bitmap.getWidth();
        int height = bitmap.getHeight();

        if (width <= 0 || height <= 0) {
            return bitmap;
        }

        int maxPx = Math.max(
                96,
                (int) (96 * context.getResources().getDisplayMetrics().density)
        );

        if (width <= maxPx && height <= maxPx) {
            return bitmap;
        }

        float scale = Math.min(maxPx / (float) width, maxPx / (float) height);
        int targetWidth = Math.max(1, Math.round(width * scale));
        int targetHeight = Math.max(1, Math.round(height * scale));

        return Bitmap.createScaledBitmap(bitmap, targetWidth, targetHeight, true);
    }

    private static void registerReceiverCompat(
            Context context,
            BroadcastReceiver receiver,
            IntentFilter filter
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            context.registerReceiver(receiver, filter);
        }
    }

    private static void safeUnregisterReceiver(
            Context context,
            BroadcastReceiver receiver,
            AtomicBoolean unregistered
    ) {
        if (unregistered.compareAndSet(false, true)) {
            try {
                context.unregisterReceiver(receiver);
            } catch (Exception ignored) {
            }
        }
    }

    private static boolean isMiuiLikeRom() {
        return "xiaomi".equalsIgnoreCase(Build.MANUFACTURER)
                || "redmi".equalsIgnoreCase(Build.BRAND)
                || "poco".equalsIgnoreCase(Build.BRAND)
                || !TextUtils.isEmpty(getSystemProperty("ro.miui.ui.version.name"));
    }

    private static String getSystemProperty(String key) {
        try {
            Class<?> systemProperties = Class.forName("android.os.SystemProperties");
            Method get = systemProperties.getDeclaredMethod("get", String.class);
            get.setAccessible(true);
            Object value = get.invoke(null, key);
            return value == null ? "" : String.valueOf(value);
        } catch (Throwable ignored) {
            return "";
        }
    }
}
