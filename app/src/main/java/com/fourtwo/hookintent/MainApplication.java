package com.fourtwo.hookintent;

import android.app.Application;
import android.os.Build;

import androidx.multidex.BuildConfig;

import com.fourtwo.hookintent.manager.PermissionManager;
import com.topjohnwu.superuser.Shell;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class MainApplication extends Application {

    static {
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10));
    }
    @Override
    public void onCreate() {
        super.onCreate();

        // 开启系统隐藏API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            HiddenApiBypass.addHiddenApiExemptions("L");
        }

        // ROOT提权
        Shell.getShell(shell -> {
            PermissionManager.isRootPermissionGranted = shell.isRoot();
            PermissionManager.init(this);
        });
    }

    @Override
    public void onTerminate() {
        super.onTerminate();
        PermissionManager.unload(this); // 清理资源
    }

}
