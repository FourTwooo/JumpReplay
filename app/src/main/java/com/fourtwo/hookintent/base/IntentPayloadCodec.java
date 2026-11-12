package com.fourtwo.hookintent.base;

import android.content.Intent;
import android.os.Bundle;
import android.os.Parcel;
import android.util.Base64;

/** The URI is a readable projection; the parcel retains typed extras for replay. */
public final class IntentPayloadCodec {
    public static final String KEY = "intent_parcel";

    private IntentPayloadCodec() {}

    /** Host-side restoration stays opaque. Foreign object code only executes in a codec process. */
    public static Intent decode(android.content.Context context, Bundle record) {
        return decode(record);
    }

    public static String encode(Intent intent) {
        Parcel parcel = Parcel.obtain();
        try {
            intent.writeToParcel(parcel, 0);
            if (parcel.hasFileDescriptors()) {
                throw new IllegalArgumentException("Intent contains file descriptors and cannot be persisted");
            }
            return Base64.encodeToString(parcel.marshall(), Base64.NO_WRAP);
        } finally {
            parcel.recycle();
        }
    }

    public static Intent decode(Bundle record) {
        String encoded = record.getString(KEY);
        if (encoded != null && !encoded.isEmpty()) {
            Parcel parcel = Parcel.obtain();
            try {
                byte[] bytes = Base64.decode(encoded, Base64.DEFAULT);
                parcel.unmarshall(bytes, 0, bytes.length);
                parcel.setDataPosition(0);
                // Do not unpack the extras: target-only Parcelable classes stay in their parcel.
                return Intent.CREATOR.createFromParcel(parcel);
            } finally {
                parcel.recycle();
            }
        }
        String uri = record.getString("uri");
        if (uri == null || uri.isEmpty()) uri = record.getString("scheme_raw_url");
        if (uri == null || uri.isEmpty()) throw new IllegalArgumentException("记录中没有可回放的 Intent");
        try {
            return Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Intent URI 格式错误", e);
        }
    }
}
