package com.fourtwo.hookintent.tools;

import java.io.DataOutputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ShellExecutor {

    private static final ExecutorService executorService = Executors.newSingleThreadExecutor();

    public static void executeCommandInBackground(String command) {
        executorService.execute(() -> {
            ShellExecutor.executeSuCommand(command);
        });
    }

    public static boolean executeSuCommand(String command) {
        Process process = null;
        DataOutputStream os = null;
        try {
            // 获取root权限的进程
            process = Runtime.getRuntime().exec("su");
            os = new DataOutputStream(process.getOutputStream());

            // 执行命令
            os.writeBytes(command + "\n");
            os.flush();

            // 结束命令
            os.writeBytes("exit\n");
            os.flush();

            // 等待进程完成
            int exitValue = process.waitFor();
            return exitValue == 0;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        } finally {
            try {
                if (os != null) {
                    os.close();
                }
                if (process != null) {
                    process.destroy();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

