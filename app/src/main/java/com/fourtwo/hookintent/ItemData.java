package com.fourtwo.hookintent;

import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Parcel;
import android.os.Parcelable;

public class ItemData implements Parcelable {
    private Drawable icon; // Drawable 类型无法直接序列化
    private final String appName;
    private final String item_from;
    private final String item_data;
    private final String timestamp;
    private final String Base;
    private final String dataSize;
    private final Bundle bundle;
    private final String stack_trace;
    private final String uri;

    // 构造函数
    public ItemData(Drawable icon, String appName, String item_from, String item_data, String timestamp, String dataSize, Bundle bundle, String Base, String stack_trace, String uri) {
        this.icon = icon;
        this.appName = appName;
        this.item_from = item_from;
        this.item_data = item_data;
        this.timestamp = timestamp;
        this.dataSize = dataSize;
        this.bundle = bundle;
        this.Base = Base;
        this.stack_trace = stack_trace;
        this.uri = uri;
    }

    // Parcelable 构造函数
    protected ItemData(Parcel in) {
        // Note: Drawable can't be directly parcelled, consider storing it another way
        appName = in.readString();
        item_from = in.readString();
        item_data = in.readString();
        timestamp = in.readString();
        Base = in.readString();
        dataSize = in.readString();
        bundle = in.readBundle(getClass().getClassLoader());
        stack_trace = in.readString();
        uri = in.readString();
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
        // Note: Drawable can't be directly parcelled, consider storing it another way
        parcel.writeString(appName);
        parcel.writeString(item_from);
        parcel.writeString(item_data);
        parcel.writeString(timestamp);
        parcel.writeString(Base);
        parcel.writeString(dataSize);
        parcel.writeBundle(bundle);
        parcel.writeString(stack_trace);
        parcel.writeString(uri);
    }

    // Getters and setters
    public Drawable getIcon() {
        return icon;
    }

    public void setIcon(Drawable icon) {
        this.icon = icon;
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

    public String getBase() {
        return Base;
    }

    public String getStackTrace() {
        return stack_trace;
    }

    public String getUri() {
        return uri;
    }
}
