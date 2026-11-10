package com.fourtwo.hookintent.utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.method.ScrollingMovementMethod;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;

import com.fourtwo.hookintent.R;

public class BatteryOptimizationHelper {

    private final Context context;

    public BatteryOptimizationHelper(Context context) {
        this.context = context;
    }

    /**
     * 检查是否在白名单内
     */
    public boolean isIgnoringBatteryOptimizations() {
        boolean isIgnoring = false;
        PowerManager powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
        if (powerManager != null) {
            isIgnoring = powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return isIgnoring;
    }

    public void showSimpleDialog(String message) {
        if (!(context instanceof Activity)) {
            requestIgnoreBatteryOptimizations();
            return;
        }

        Activity activity = (Activity) context;
        if (activity.isFinishing()) {
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && activity.isDestroyed()) {
            return;
        }

        View dialogView = LayoutInflater.from(activity).inflate(R.layout.dialog_battery_optimization, null);
        TextView textMessage = dialogView.findViewById(R.id.text_message);
        Button buttonConfirm = dialogView.findViewById(R.id.button_confirm);

        textMessage.setText(message);
        textMessage.setTextIsSelectable(false);
        textMessage.setLongClickable(false);
        textMessage.setClickable(false);
        textMessage.setFocusable(false);
        textMessage.setMovementMethod(new ScrollingMovementMethod());
        textMessage.setVerticalScrollBarEnabled(true);
        textMessage.setScrollbarFadingEnabled(false);

        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setView(dialogView)
                .setCancelable(false)
                .create();

        dialog.setCanceledOnTouchOutside(false);

        buttonConfirm.setOnClickListener(v -> {
            dialog.dismiss();
            requestIgnoreBatteryOptimizations();
        });

        dialog.show();

        Window window = dialog.getWindow();
        if (window != null) {
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            window.setLayout(
                    (int) (activity.getResources().getDisplayMetrics().widthPixels * 0.90f),
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
    }

    public void requestIgnoreBatteryOptimizations() {
        try {
            Intent intent = new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    /**
     * 跳转到指定应用的首页
     */
    private void showActivity(String packageName) {
        Intent intent = context.getPackageManager().getLaunchIntentForPackage(packageName);
        if (intent != null) {
            context.startActivity(intent);
        }
    }

    /**
     * 跳转到指定应用的指定页面
     */
    private void showActivity(String packageName, String activityDir) {
        Intent intent = new Intent();
        intent.setComponent(new ComponentName(packageName, activityDir));
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * context.getResources().getDisplayMetrics().density);
    }

    // 华为
    @SuppressLint("DefaultLocale")
    public boolean isHuawei() {
        return Build.BRAND != null && (Build.BRAND.toLowerCase().equals("huawei") || Build.BRAND.toLowerCase().equals("honor"));
    }

    public void goHuaweiSetting() {
        try {
            showActivity(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            );
        } catch (Exception e) {
            showActivity(
                    "com.huawei.systemmanager",
                    "com.huawei.systemmanager.optimize.bootstart.BootStartActivity"
            );
        }
    }

    // 小米
    public boolean isXiaomi() {
        return Build.BRAND != null && Build.BRAND.toLowerCase().equals("xiaomi");
    }

    public void goXiaomiSetting() {
        showActivity(
                "com.miui.securitycenter",
                "com.miui.permcenter.autostart.AutoStartManagementActivity"
        );
    }

    // OPPO
    public boolean isOPPO() {
        return Build.BRAND != null && Build.BRAND.toLowerCase().equals("oppo");
    }

    public void goOPPOSetting() {
        try {
            showActivity("com.coloros.phonemanager");
        } catch (Exception e1) {
            try {
                showActivity("com.oppo.safe");
            } catch (Exception e2) {
                try {
                    showActivity("com.coloros.oppoguardelf");
                } catch (Exception e3) {
                    showActivity("com.coloros.safecenter");
                }
            }
        }
    }

    // VIVO
    public boolean isVIVO() {
        return Build.BRAND != null && Build.BRAND.toLowerCase().equals("vivo");
    }

    public void goVIVOSetting() {
        showActivity("com.iqoo.secure");
    }

    // 魅族
    public boolean isMeizu() {
        return Build.BRAND != null && Build.BRAND.toLowerCase().equals("meizu");
    }

    public void goMeizuSetting() {
        showActivity("com.meizu.safe");
    }

    // 三星
    public boolean isSamsung() {
        return Build.BRAND != null && Build.BRAND.toLowerCase().equals("samsung");
    }

    public void goSamsungSetting() {
        try {
            showActivity("com.samsung.android.sm_cn");
        } catch (Exception e) {
            showActivity("com.samsung.android.sm");
        }
    }

    // 乐视
    public boolean isLeTV() {
        return Build.BRAND != null && Build.BRAND.toLowerCase().equals("letv");
    }

    public void goLetvSetting() {
        showActivity(
                "com.letv.android.letvsafe",
                "com.letv.android.letvsafe.AutobootManageActivity"
        );
    }

    // 锤子
    public boolean isSmartisan() {
        return Build.BRAND != null && Build.BRAND.toLowerCase().equals("smartisan");
    }

    public void goSmartisanSetting() {
        showActivity("com.smartisanos.security");
    }
}