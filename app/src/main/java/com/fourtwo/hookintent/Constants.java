package com.fourtwo.hookintent;

import java.util.Arrays;
import java.util.List;

public final class Constants {

    // 防止实例化这个类
    private Constants() {
        throw new UnsupportedOperationException("Cannot instantiate utility class.");
    }

    // 数据库表名
    public static final String SHARED_PREFERENCES_NAME = "CONFIG";
    // 数据库列名
    public static final String SET_IS_HOOK = "set_isHook";
    public static final String SET_STAND_ARD_SCHEMES_STRING = "set_standardSchemesSting";

    public static final List<String> SET_STAND_ARD_SCHEMES = Arrays.asList("http", "https", "file", "content", "data", "about", "javascript", "mailto", "ftp", "ftps", "ws", "wss", "tel", "sms", "smsto", "geo", "market", "res");

    public static final String GET_IS_HOOK = "get_isHook";
    public static final String GET_STAND_ARD_SCHEME_STRING = "get_standardSchemesSting";

}

