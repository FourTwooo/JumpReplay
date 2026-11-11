package com.fourtwo.hookintent.base;

import android.os.Bundle;
import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Builds shell text only for values which am can represent without losing their type. */
public final class AmCommandBuilder {
    private AmCommandBuilder() {}

    public static class CommandResult {
        public final String command;
        public final boolean hasError;
        public CommandResult(String command, boolean hasError) {
            this.command = command;
            this.hasError = hasError;
        }
    }

    public static String shellQuote(String value) {
        return "'" + (value == null ? "" : value).replace("'", "'\\''") + "'";
    }

    public static CommandResult buildIntentCommand(Bundle record) {
        StringBuilder command = new StringBuilder("am start");
        String component = record.getString("componentClassName");
        if (component != null && !component.isEmpty() && !"null".equals(component)) {
            option(command, "-n", component);
        }
        option(command, "-a", record.getString("action"));
        option(command, "-d", record.getString("dataString"));
        option(command, "-t", record.getString("type"));
        option(command, "-p", record.getString("package"));
        Object categories = record.get("categories");
        if (categories instanceof Iterable) {
            for (Object category : (Iterable<?>) categories) option(command, "-c", String.valueOf(category));
        }
        Object flags = record.get("flags");
        if (flags instanceof Number && ((Number) flags).intValue() != 0) {
            option(command, "-f", String.valueOf(((Number) flags).intValue()));
        }
        List<Map<String, Object>> extras = JsonHandler.getFilterValueJson(record.get("intentExtras"));
        CommandResult result = buildAmCommand(extras);
        command.append(result.command);
        return new CommandResult(command.toString(), result.hasError);
    }

    private static void option(StringBuilder command, String flag, String value) {
        if (value != null && !"null".equals(value)) command.append(' ').append(flag).append(' ').append(shellQuote(value));
    }

    public static CommandResult buildAmCommand(List<Map<String, Object>> extras) {
        StringBuilder command = new StringBuilder();
        boolean error = false;
        if (extras == null) return new CommandResult("", false);
        for (Map<String, Object> extra : extras) {
            String key = String.valueOf(extra.get("key"));
            String type = String.valueOf(extra.get("class"));
            Object value = extra.get("value");
            String flag;
            switch (type) {
                case "null": flag = "--esn"; break;
                case "java.lang.String": flag = value == null ? "--esn" : "--es"; break;
                case "java.lang.Integer": flag = "--ei"; break;
                case "java.lang.Boolean": flag = "--ez"; break;
                case "java.lang.Long": flag = "--el"; break;
                case "java.lang.Float": flag = "--ef"; break;
                case "android.content.ComponentName": flag = "--ecn"; break;
                case "[I": flag = "--eia"; break;
                case "[J": flag = "--ela"; break;
                case "[F": flag = "--efa"; break;
                case "[Ljava.lang.String;": flag = "--esa"; break;
                default:
                    if (type.startsWith("android.net.Uri")) flag = "--eu";
                    else { error = true; continue; }
            }
            String text = value == null ? "" : String.valueOf(value);
            if (type.startsWith("[")) {
                text = arrayText(value, "[Ljava.lang.String;".equals(type));
                // Old captures stored array identity strings, so their contents cannot be reconstructed.
                if (text == null) { error = true; continue; }
            }
            command.append(' ').append(flag).append(' ').append(shellQuote(key));
            if (!"--esn".equals(flag)) command.append(' ').append(shellQuote(text));
        }
        return new CommandResult(command.toString(), error);
    }

    private static String arrayText(Object value, boolean strings) {
        List<Object> values = new ArrayList<>();
        if (value instanceof Iterable) {
            for (Object entry : (Iterable<?>) value) values.add(entry);
        } else if (value != null && value.getClass().isArray()) {
            for (int i = 0; i < Array.getLength(value); i++) values.add(Array.get(value, i));
        } else return null;
        StringBuilder text = new StringBuilder();
        for (Object entry : values) {
            if (entry == null) return null; // am cannot distinguish a null element.
            if (text.length() > 0) text.append(',');
            String token = String.valueOf(entry);
            if (strings) token = token.replace("\\", "\\\\").replace(",", "\\,");
            text.append(token);
        }
        // am parses an empty token as an element instead of an empty array.
        return values.isEmpty() ? null : text.toString();
    }
}
