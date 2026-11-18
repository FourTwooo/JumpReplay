package com.fourtwo.hookintent.data;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

public class ItemData implements Parcelable {
    private Drawable icon;
    private final String appName;
    private final String item_from;
    private final String item_data;
    private final String timestamp;
    private final String category;
    private final String dataSize;
    private final Bundle bundle;
    private final String stack_trace;
    private final String uri;
    // Repository-only identity. Keep it out of the legacy Parcelable wire format so
    // saved navigation state remains compatible when upgrading or rolling back.
    private long captureId;

    private static String safe(String value) {
        return value == null ? "" : value;
    }

    public ItemData(Drawable icon, String appName, String item_from, String item_data,
                    String timestamp, String dataSize, Bundle bundle, String category,
                    String stack_trace, String uri) {
        this.icon = icon;
        this.appName = safe(appName);
        this.item_from = safe(item_from);
        this.item_data = safe(item_data);
        this.timestamp = safe(timestamp);
        this.dataSize = safe(dataSize);
        this.bundle = bundle;
        this.category = safe(category);
        this.stack_trace = safe(stack_trace);
        this.uri = safe(uri);
    }

    protected ItemData(Parcel in) {
        appName = safe(in.readString());
        item_from = safe(in.readString());
        item_data = safe(in.readString());
        timestamp = safe(in.readString());
        category = safe(in.readString());
        dataSize = safe(in.readString());
        bundle = in.readBundle(getClass().getClassLoader());
        stack_trace = safe(in.readString());
        uri = safe(in.readString());
    }

    public static final Creator<ItemData> CREATOR = new Creator<ItemData>() {
        @Override
        public ItemData createFromParcel(Parcel in) {
            return new ItemData(in);
        }

        @Override
        public ItemData[] newArray(int size) {
            return new ItemData[size];
        }
    };

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public void writeToParcel(Parcel parcel, int i) {
        parcel.writeString(appName);
        parcel.writeString(item_from);
        parcel.writeString(item_data);
        parcel.writeString(timestamp);
        parcel.writeString(category);
        parcel.writeString(dataSize);
        parcel.writeBundle(bundle);
        parcel.writeString(stack_trace);
        parcel.writeString(uri);
    }

    public Drawable getIcon() {
        return icon;
    }

    public String getAppName() {
        return appName;
    }

    public String getItem_from() {
        return item_from;
    }

    public String getItem_data() {
        return item_data;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getDataSize() {
        return dataSize;
    }

    public Bundle getAppBundle() {
        return bundle;
    }

    public String getCategory() {
        NavigationCaptureSource source = NavigationCaptureSource.fromRecord(category, bundle);
        return source == null ? category : source.category();
    }

    public String getStackTrace() {
        return stack_trace;
    }

    public String getUri() {
        return uri;
    }

    public long getCaptureId() { return captureId; }
    public void setCaptureId(long captureId) { this.captureId = captureId; }
}
