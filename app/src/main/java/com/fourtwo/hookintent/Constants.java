package com.fourtwo.hookintent;

public final class Constants {

    // 防止实例化这个类
    private Constants() {
        throw new UnsupportedOperationException("Cannot instantiate utility class.");
    }

    // 数据库列名
    public static final String SET_IS_HOOK = "set_isHook";
    public static final String GET_IS_HOOK = "get_isHook";

    public static final String TYPE = "type";

    public static final String DATA = "data";
}

