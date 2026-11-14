package com.fourtwo.hookintent.utils;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.HashSet;
import java.util.Set;

/** Replaces placeholders once, without interpreting the replacement as Intent syntax. */
public final class ShortcutParameterReplacer {
    private ShortcutParameterReplacer() { }
    public enum Failure { EMPTY_INTENT, EMPTY_PARAMETER, DUPLICATE_PARAMETER, MISSING_PARAMETER, VALUE_COUNT, AUTHORITY_SEPARATOR }
    /** Preserve legacy messages for existing callers; new UIs can localize the typed reason. */
    public static final class ValidationException extends IllegalArgumentException {
        public final Failure reason;
        public final String parameter;
        private ValidationException(Failure reason,String parameter,String message) {
            super(message);this.reason=reason;this.parameter=parameter;
        }
    }

    public static void validate(String template, String[] names) {
        if (template == null || template.trim().isEmpty()) throw new ValidationException(Failure.EMPTY_INTENT,null,"Intent 不能为空");
        Set<String> unique = new HashSet<>();
        if (names == null) return;
        for (String name : names) {
            if (name == null || name.isEmpty()) throw new ValidationException(Failure.EMPTY_PARAMETER,null,"替换参数不能为空");
            if (!unique.add(name)) throw new ValidationException(Failure.DUPLICATE_PARAMETER,name,"替换参数不能重复：" + name);
            if (!template.contains(name)) throw new ValidationException(Failure.MISSING_PARAMETER,name,"Intent 中找不到替换参数：" + name);
        }
    }

    public static String replace(String template, String[] names, String[] values) {
        validate(template, names);
        if (names == null || names.length == 0) return template;
        if (values == null || names.length != values.length) throw new ValidationException(Failure.VALUE_COUNT,null,"替换参数数量不一致");
        StringBuilder result = new StringBuilder();
        int intentStart = template.indexOf("#Intent;");
        int queryStart = template.indexOf('?');
        int fragmentStart = template.indexOf('#');
        int schemeSeparator = template.indexOf("://");
        int authorityEnd = -1;
        int pathStart = -1;
        if (schemeSeparator >= 0) {
            authorityEnd = template.length();
            for (int index = schemeSeparator + 3; index < template.length(); index++) {
                char character = template.charAt(index);
                if (character == '/' || character == '?' || character == '#') { authorityEnd = index; break; }
            }
            if (authorityEnd < template.length() && template.charAt(authorityEnd) == '/') pathStart = authorityEnd;
        } else {
            int colon = template.indexOf(':');
            if (colon >= 0 && colon + 1 < template.length()) pathStart = colon + 1;
        }
        for (int offset = 0; offset < template.length();) {
            int match = -1;
            for (int i = 0; i < names.length; i++) {
                if (template.startsWith(names[i], offset)
                        && (match < 0 || names[i].length() > names[match].length())) match = i;
            }
            if (match < 0) {
                result.append(template.charAt(offset++));
                continue;
            }
            String value = values[match] == null ? "" : values[match];
            boolean inIntentValue = intentStart >= 0 && offset > intentStart
                    && template.lastIndexOf('=', offset) > template.lastIndexOf(';', offset);
            boolean inQueryValue = queryStart >= 0 && offset > queryStart
                    && (fragmentStart < 0 || offset < fragmentStart)
                    && template.lastIndexOf('=', offset) > Math.max(queryStart, template.lastIndexOf('&', offset));
            boolean inPath = pathStart >= 0 && offset >= pathStart
                    && (queryStart < 0 || offset < queryStart) && (fragmentStart < 0 || offset < fragmentStart);
            boolean inFragment = fragmentStart >= 0 && fragmentStart != intentStart && offset > fragmentStart;
            boolean inAuthority = schemeSeparator >= 0 && offset >= schemeSeparator + 3 && offset < authorityEnd;
            if (inAuthority && (value.indexOf('/') >= 0 || value.indexOf('?') >= 0
                    || value.indexOf('#') >= 0 || value.indexOf(';') >= 0)) {
                throw new ValidationException(Failure.AUTHORITY_SEPARATOR,null,"主机参数包含URL结构分隔符");
            }
            result.append(inIntentValue || inQueryValue || inPath || inFragment ? encodeValue(value) : value);
            offset += names[match].length();
        }
        return result.toString();
    }

    private static String encodeValue(String value) {
        try { return URLEncoder.encode(value, "UTF-8").replace("+", "%20"); }
        catch (UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
}
