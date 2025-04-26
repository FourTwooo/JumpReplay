package com.fourtwo.hookintent.utils;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.PowerManager;
import android.provider.Settings;

import androidx.appcompat.app.AlertDialog;

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
        // 创建 AlertDialog
        AlertDialog.Builder builder = new AlertDialog.Builder(context);

        // 设置弹窗的文本内容
        builder.setMessage(message);

        // 设置弹窗的「确定」按钮
        builder.setPositiveButton("确定", (dialog, which) -> {
            // 用户点击「确定」按钮后的逻辑
            dialog.dismiss(); // 关闭弹窗
            requestIgnoreBatteryOptimizations();
        });

        // 设置弹窗不可取消（点击外部不会关闭弹窗）
        builder.setCancelable(false);

        // 显示弹窗
        builder.show();
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
