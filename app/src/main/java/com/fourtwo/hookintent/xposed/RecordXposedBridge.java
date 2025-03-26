package com.fourtwo.hookintent.xposed;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;

public class RecordXposedBridge {

    // 使用 List<Map<String, Object>> 存储 Hook 的信息
    private static final List<Map<String, Object>> hookedRecords = new ArrayList<>();

    public static Boolean isHostApp = false;

    public static Set<XC_MethodHook.Unhook> hookAllMethods(String category, Class<?> hookClass, String methodName, XC_MethodHook callback) {
        // 获取类名和包名
        String className = hookClass.getName();
        String packageName = "ALL";
//        String packageName = Objects.requireNonNull(hookClass.getPackage()).getName();

        // 根据这四个字段查询 open 的值
        boolean open = getOpenStatus(className, packageName, methodName, category);

        // 如果是 HostApp，使用空的 callback
        if (isHostApp || !open) {
            callback = new XC_MethodHook() {};
        }

        // 构造记录 Map
        Map<String, Object> record = new HashMap<>();
        record.put("packageName", packageName); // String 类型
        record.put("open", open); // 根据查询结果设置 open
        record.put("className", className); // String 类型
        record.put("methodName", methodName); // String 类型
        record.put("category", category); // String 类型

        // 检查是否已经存在该记录（去重逻辑）
        if (!containsRecord(className, packageName, methodName, category)) { // 使用改进的去重逻辑
            hookedRecords.add(record); // 添加到记录列表
        }

        // 调用 XposedBridge 的 hookAllMethods 方法
        return XposedBridge.hookAllMethods(hookClass, methodName, callback);
    }

    /**
     * 改进后的去重逻辑
     * 只根据 className, packageName, methodName, category 这四个字段判断唯一性
     */
    private static boolean containsRecord(String className, String packageName, String methodName, String category) {
        for (Map<String, Object> existingRecord : hookedRecords) {
            if (className.equals(existingRecord.get("className")) &&
                    packageName.equals(existingRecord.get("packageName")) &&
                    methodName.equals(existingRecord.get("methodName")) &&
                    category.equals(existingRecord.get("category"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 根据 className, packageName, methodName, category 查询 open 的状态
     * 如果不存在对应记录，则默认返回 true
     */
    private static boolean getOpenStatus(String className, String packageName, String methodName, String category) {
        for (Map<String, Object> record : hookedRecords) {
            if (className.equals(record.get("className")) &&
                    packageName.equals(record.get("packageName")) &&
                    methodName.equals(record.get("methodName")) &&
                    category.equals(record.get("category"))) {
                // 如果记录存在，返回其 open 状态
                Object openValue = record.get("open");
                return openValue instanceof Boolean ? (Boolean) openValue : true; // 防止 open 不是 Boolean 类型
            }
        }
        // 如果没有找到记录，默认返回 true
        return true;
    }

    /**
     * 获取 hookedRecords，直接返回 List<Map<String, Object>>
     */
    public static List<Map<String, Object>> getHookedRecords() {
        return new ArrayList<>(hookedRecords); // 返回副本，避免外部修改原列表
    }

    /**
     * 设置 hookedRecords，接收 List<Map<String, Object>> 并更新 hookedRecords
     */
    public static void setHookedRecords(List<Map<String, Object>> newRecords) {
        hookedRecords.clear(); // 清空原有的 hookedRecords
        hookedRecords.addAll(newRecords); // 直接添加新的记录
    }


    /**
     * 清空 hookedRecords
     */
    public static void clearHookedRecords() {
        hookedRecords.clear();
    }

    /**
     * 记录日志
     */
    public static synchronized void log(String text) {
        XposedBridge.log(text);
    }
}
