package com.fourtwo.hookintent.analysis;

import android.util.Log;

import java.util.List;
import java.util.Map;

import java.util.List;
import java.util.Map;

public class AmCommandBuilder {

    public static class CommandResult {
        public String command;
        public boolean hasError;

        public CommandResult(String command, boolean hasError) {
            this.command = command;
            this.hasError = hasError;
        }
    }

    public static CommandResult buildAmCommand(List<Map<String, Object>> extras) {
        StringBuilder command = new StringBuilder();
        boolean hasError = false;

        for (Map<String, Object> map : extras) {
            String key = (String) map.get("key");
            String className = (String) map.get("class");
            Object value = map.get("value");

            if (key != null && className != null) {
                // 如果className和value都是null，将其视为空字符串
                if (className.equals("null")) {
                    className = "java.lang.String";
                    value = "";
                }

                switch (className) {
                    case "java.lang.String":
                        if (value != null) {
                            command.append(" --es ").append(key).append(" ").append("\"").append(value).append("\"");
                        } else {
                            command.append(" --esn ").append(key);
                        }
                        break;
                    case "java.lang.Integer":
                        command.append(" --ei ").append(key).append(" ").append(value);
                        break;
                    case "java.lang.Boolean":
                        command.append(" --ez ").append(key).append(" ").append(value);
                        break;
                    case "java.lang.Long":
                        command.append(" --el ").append(key).append(" ").append(value);
                        break;
                    case "java.lang.Float":
                        command.append(" --ef ").append(key).append(" ").append(value);
                        break;
                    case "android.net.Uri":
                        command.append(" --eu ").append(key).append(" ").append(value);
                        break;
                    case "android.content.ComponentName":
                        command.append(" --ecn ").append(key).append(" ").append(value);
                        break;
                    case "[I": // Integer array
                        command.append(" --eia ").append(key).append(" ").append(arrayToString((int[]) value));
                        break;
                    case "[J": // Long array
                        command.append(" --ela ").append(key).append(" ").append(arrayToString((long[]) value));
                        break;
                    case "[F": // Float array
                        command.append(" --efa ").append(key).append(" ").append(arrayToString((float[]) value));
                        break;
                    case "[Ljava.lang.String;": // String array
                        command.append(" --esa ").append(key).append(" ").append(arrayToString((String[]) value));
                        break;
                    default:
                        Log.d("buildAmCommand", String.format("class: %s key: %s value: %s", className, key, value));
                        hasError = true;
                        break;
                }
            }
        }

        return new CommandResult(command.toString(), hasError);
    }

    private static String arrayToString(int[] array) {
        StringBuilder result = new StringBuilder();
        if (array != null) {
            for (int i = 0; i < array.length; i++) {
                result.append(array[i]);
                if (i < array.length - 1) {
                    result.append(",");
                }
            }
        }
        return result.toString();
    }

    private static String arrayToString(long[] array) {
        StringBuilder result = new StringBuilder();
        if (array != null) {
            for (int i = 0; i < array.length; i++) {
                result.append(array[i]);
                if (i < array.length - 1) {
                    result.append(",");
                }
            }
        }
        return result.toString();
    }

    private static String arrayToString(float[] array) {
        StringBuilder result = new StringBuilder();
        if (array != null) {
            for (int i = 0; i < array.length; i++) {
                result.append(array[i]);
                if (i < array.length - 1) {
                    result.append(",");
                }
            }
        }
        return result.toString();
    }

    private static String arrayToString(String[] array) {
        StringBuilder result = new StringBuilder();
        if (array != null) {
            for (int i = 0; i < array.length; i++) {
                result.append("\"").append(array[i]).append("\"");
                if (i < array.length - 1) {
                    result.append(",");
                }
            }
        }
        return result.toString();
    }
}
