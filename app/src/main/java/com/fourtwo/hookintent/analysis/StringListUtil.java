package com.fourtwo.hookintent.analysis;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class StringListUtil {

    public static final String STAND_ARD_SCHEMES = "standardSchemes";
    private static final String DELIMITER = ","; // 定义分隔符

    // 将 List<String> 转换为 单个字符串
    public static String listToString(List<String> list) {
        if (list == null || list.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String item : list) {
            if (sb.length() > 0) {
                sb.append(DELIMITER);
            }
            sb.append(item);
        }
        return sb.toString();
    }

    // 将字符串转换为 List<String>
    public static List<String> stringToList(String str) {
        if (str == null || str.isEmpty()) {
            return new ArrayList<>();
        }
        return new ArrayList<>(Arrays.asList(str.split(DELIMITER)));
    }
}
