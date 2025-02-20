package com.fourtwo.hookintent.utils;

public class HookStatusManager {
    private static boolean isHook = false; // 默认状态

    // 获取 isHook 状态
    public static boolean isHook() {
        return isHook;
    }

    // 设置 isHook 状态
    public static void setHook(boolean hook) {
        isHook = hook;
    }
}
