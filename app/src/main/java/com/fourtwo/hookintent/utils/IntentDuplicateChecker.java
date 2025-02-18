package com.fourtwo.hookintent.utils;

import android.os.Bundle;
import android.util.Log;

public class IntentDuplicateChecker {
    private String lastBundleString = "";

    /**
     * 检查新的 Bundle 是否与上一个相同
     *
     * @param newBundle 新收到的 Bundle
     * @return 如果相同返回 true，不同返回 false
     */
    public boolean isDuplicate(Bundle newBundle) {
        if (newBundle == null) {
            return false;
        }

        String FunctionCall = newBundle.getString("FunctionCall");
        String From = newBundle.getString("from");
        newBundle.remove("FunctionCall");
        newBundle.remove("from");

        // 将当前 Bundle 转换为字符串
        String newBundleString = newBundle.toString();
        Log.d("newBundleString", newBundleString);
        // 比较新 Bundle 字符串和上一个 Bundle 字符串
        boolean isDuplicate = newBundleString.equals(lastBundleString);
        // 更新 lastBundleString 为当前的新 Bundle 字符串
        lastBundleString = newBundleString;

        newBundle.putString("FunctionCall", FunctionCall);
        newBundle.putString("from", From);
        return isDuplicate;
    }
}