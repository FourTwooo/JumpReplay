package com.fourtwo.hookintent.data;

import android.content.Intent;
import android.graphics.drawable.Drawable;

public class IntentMatchItem {
    private final Drawable appIcon;
    private final String appName;
    private final String appDetails;
    private final Intent intent;

    public IntentMatchItem(Drawable appIcon, String appName, String appDetails, Intent intent) {
        this.appIcon = appIcon;
        this.appName = appName;
        this.appDetails = appDetails;
        this.intent = intent;
    }

    public Drawable getAppIcon() {
        return appIcon;
    }

    public String getAppName() {
        return appName;
    }

    public String getAppDetails() {
        return appDetails;
    }

    public Intent getIntent() {
        return intent;
    }
}
