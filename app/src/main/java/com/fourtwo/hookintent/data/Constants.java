package com.fourtwo.hookintent.data;

import android.net.Uri;

public final class Constants {

    private Constants() {
        throw new UnsupportedOperationException("Cannot instantiate utility class.");
    }

    public static final String BLOCKER_OUTPUT_PACKAGE = "blockerOutputPackage";
    public static final String BLOCKER_OUTPUT_LABEL = "blockerOutputLabel";
    public static final String BLOCKER_CUSTOM_RULES = "blockerCustomRules";
    public static final String BLOCKER_TEMPLATE_ASSET = "blocker/blocker_template.apk";
    public static final String BLOCKER_SPEC_FILE_NAME = "blocker_spec.json";

    public static final String CLOUD_CONFIG_URL = "cloudConfigUrl";
    public static final String CLOUD_CONFIG_URL_HISTORY = "cloudConfigUrlHistory";
    public static final String DEFAULT_CLOUD_CONFIG_URL = "https://raw.githubusercontent.com/FourTwooo/JumpReplay/refs/heads/master/config.json";

    public static final String AUTHORITY = "com.fourtwo.hookintent.configprovider";
    public static final Uri CONFIG_URI = Uri.parse("content://" + AUTHORITY + "/config");
    public static final Uri SCHEME_URI = Uri.parse("content://" + AUTHORITY + "/scheme");

    public static final String INTERNAL_HOOKS_CONFIG = "internalHooksConfig";
    public static final String EXTERNAL_HOOKS_CONFIG = "externalHooksConfig";
    public static final String DISABLED_SCHEME = "disabledScheme";
    public static final String COLORS_CONFIG = "Colors";
    public static final String FLOAT_WINDOW_CONFIG = "FloatWindow";

    public static final String PACKAGE = "包名";
    public static final String FUNCTION = "方法";
    public static final String TIME = "时间";

    public static final String STAR_DB_NAME = "Star";
    public static final String STAR_TABLE_NAME = "star_data";
    public static final String SQL_HASH = "_hash";
    public static final String SQL_DATA = "data";

    public static final String GitHub_README_URL = "https://raw.githubusercontent.com/FourTwooo/JumpReplay/refs/heads/master/README.md";
    public static final String GitHub_VERSION_URL = "https://api.github.com/repos/FourTwooo/JumpReplay/tags";
    public static final String GitHub_RELEASES_URL = "https://api.github.com/repos/FourTwooo/JumpReplay/releases";
    public static final String GitHub_ISSUES_URL = "https://github.com/FourTwooo/JumpReplay/issues";

    public static final String LEGAL_NOTICE_ACCEPTED_VERSION_KEY = "legalNoticeAcceptedVersion";
    public static final String LEGAL_NOTICE_VERSION = "20260324_1";

    public static final String UPDATE_APK_FILE_NAME = "JumpReplay_update.apk";

    public static final String RELEASES_CACHE_JSON = "releasesCacheJson";
    public static final String RELEASES_CACHE_TIME = "releasesCacheTime";
    public static final long RELEASES_CACHE_EXPIRE_MS = 6L * 60L * 60L * 1000L; // 6小时

}