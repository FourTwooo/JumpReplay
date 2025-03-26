package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.content.SharedPreferences;

public class SharedPreferencesUtils {

    private static final String PREFERENCES_NAME = "my_preferences"; // SharedPreferences 文件名

    private SharedPreferencesUtils() {
        // 私有化构造方法，防止实例化
    }

    /**
     * 储存一个 boolean 值
     *
     * @param context 上下文
     * @param key     参数名
     * @param value   参数值
     */
    public static void putBoolean(Context context, String key, boolean value) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putBoolean(key, value);
        editor.apply(); // 异步提交
    }

    /**
     * 获取一个 boolean 值
     *
     * @param context 上下文
     * @param key     参数名
     * @return 参数值 (默认值为 false)
     */
    public static boolean getBoolean(Context context, String key) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        return sharedPreferences.getBoolean(key, false); // 默认返回 false
    }


    public static void putStr(Context context, String key, String value) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(key, value);
        editor.apply(); // 异步提交
    }

    public static String getStr(Context context, String key) {
        SharedPreferences sharedPreferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE);
        return sharedPreferences.getString(key, null); // 默认返回 false
    }
}
