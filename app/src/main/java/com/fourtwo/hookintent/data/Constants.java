package com.fourtwo.hookintent.data;

public final class Constants {

    // 防止实例化这个类
    private Constants() {
        throw new UnsupportedOperationException("Cannot instantiate utility class.");
    }

    public static final String SET_IS_HOOK = "set_isHook";
    public static final String GET_IS_HOOK = "get_isHook";

    public static final String TYPE = "type";

    public static final String DATA = "data";


    // Github Config

    public static final String GitHub_README_URL = "https://raw.githubusercontent.com/FourTwooo/JumpReplay/refs/heads/master/README.md";

    public static final String GitHub_VERSION_URL = "https://api.github.com/repos/FourTwooo/JumpReplay/tags";
}

