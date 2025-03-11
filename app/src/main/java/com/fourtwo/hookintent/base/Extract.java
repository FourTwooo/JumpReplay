package com.fourtwo.hookintent.base;

import android.os.Bundle;
import android.os.Parcel;

import java.util.ArrayList;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Extract {


    public static String extractTime(String fullTime) {
        if (fullTime != null && fullTime.contains(" ")) {
            // 分割字符串并获取时间部分
            String[] parts = fullTime.split(" ");
            if (parts.length >= 2) {
                return parts[1]; // 返回时间部分
            }
        }
        return ""; // 返回空字符串如果格式不符
    }


    public static String calculateBundleDataSize(Bundle bundle) {
        // 使用 Parcel 将 Bundle 序列化
        Parcel parcel = Parcel.obtain();
        bundle.writeToParcel(parcel, 0);

        // 获取序列化后的字节数组大小
        int dataSize = parcel.dataSize();

        // 回收 Parcel 对象
        parcel.recycle();

        return String.valueOf(dataSize);
    }
    public static String extractIntentExtrasString(ArrayList<?> dataList) {
        StringBuilder result = new StringBuilder();

        for (Object obj : dataList) {
            if (obj instanceof Map) {
                Map<String, Object> map = (Map<String, Object>) obj;
                String key = (String) map.get("key");
                String value = Objects.requireNonNull(map.get("value")).toString();

                result.append(key).append("=").append(value).append(";");
            }
        }

        String formattedString = result.toString();
        // 去除末尾多余的分号
        if (formattedString.length() > 0) {
            formattedString = formattedString.substring(0, formattedString.length() - 1);
        }

        return formattedString;
    }

    public static String getIntentSchemeValue(String intentUri, String key) {
        // Check if the string starts with the expected prefix
        if (!intentUri.startsWith("#Intent;")) {
            return null;
        }

        // Look for 'component=' and find the value
        String componentPrefix = String.format("%s=", key);
        int componentStartIndex = intentUri.indexOf(componentPrefix);
        if (componentStartIndex == -1) {
            return null; // Component not found
        }

        // Start index of component value
        componentStartIndex += componentPrefix.length();

        // Find the end of the component value
        int componentEndIndex = intentUri.indexOf(';', componentStartIndex);
        if (componentEndIndex == -1) {
            return null; // End of component not found
        }

        // Extract the component value
        return intentUri.substring(componentStartIndex, componentEndIndex);
    }
}
