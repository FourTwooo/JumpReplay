package com.fourtwo.hookintent;

import android.app.Application;
import android.content.Context;
import android.os.Build;
import android.util.Log;
import android.widget.Toast;

import androidx.multidex.BuildConfig;

import com.topjohnwu.superuser.Shell;

import org.lsposed.hiddenapibypass.HiddenApiBypass;

public class MainApplication extends Application {

    final String TAG = "MainApplication";

    static Boolean isRoot = false;

    static {
        Shell.enableVerboseLogging = BuildConfig.DEBUG;
        Shell.setDefaultBuilder(Shell.Builder.create()
                .setFlags(Shell.FLAG_MOUNT_MASTER)
                .setTimeout(10));
    }

    public static void executeCommand(String command, Boolean Root, Context context) {
        if (isRoot) {
            if (Root) {
                Shell.cmd("su root", command).submit(result -> {
                    if (result.isSuccess()) {
                        Toast.makeText(context, "调用成功", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "调用失败", Toast.LENGTH_SHORT).show();
                    }
                });
            } else {
                Shell.cmd("su shell", command).submit(result -> {
                    if (result.isSuccess()) {
                        Toast.makeText(context, "调用成功", Toast.LENGTH_SHORT).show();
                    } else {
                        Toast.makeText(context, "调用失败", Toast.LENGTH_SHORT).show();
                    }
                });
            }
        }

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
            if (isRoot = shell.isRoot()) {
                Log.d(TAG, "onCreate: 手机已root");
            } else {
                Log.d(TAG, "onCreate: 手机未root");
            }
        });


    }

}
