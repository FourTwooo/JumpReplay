package com.fourtwo.hookintent.data;

import android.net.Uri;

public final class Constants {

    // 防止实例化这个类
    private Constants() {
        throw new UnsupportedOperationException("Cannot instantiate utility class.");
    }

    // Provider Config
    public static final String AUTHORITY = "com.fourtwo.hookintent.configprovider";

    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");

    public static final Uri SCHEME_URI = Uri.parse("content://" + AUTHORITY + "/scheme");

    public static final String INTERNAL_HOOKS_CONFIG = "internalHooksConfig";

    public static final String EXTERNAL_HOOKS_CONFIG = "externalHooksConfig";

    public static final String DISABLED_SCHEME = "disabledScheme";

    public static final String COLORS_CONFIG = "Colors";

    public static final String FLOAT_WINDOW_CONFIG = "FloatWindow";

    //  数据库字段名
    public static final String PACKAGE = "包名";

    public static final String FUNCTION = "方法";

    public static final String TIME = "时间";


    // SQL DB Config
    public static final String STAR_DB_NAME = "Star";
    public static final String STAR_TABLE_NAME = "star_data";
    public static final String SQL_HASH = "_hash";
    public static final String SQL_DATA = "data";


    // Github Config
    public static final String GitHub_README_URL = "https://raw.githubusercontent.com/FourTwooo/JumpReplay/refs/heads/master/README.md";

    public static final String GitHub_VERSION_URL = "https://api.github.com/repos/FourTwooo/JumpReplay/tags";

}

