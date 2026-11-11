package com.fourtwo.hookintent.data;

import android.app.Application;
import android.os.Bundle;
import android.os.Parcel;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class ItemDataCompatibilityTest {
    @Test public void readsLegacyFieldsWithoutConsumingFollowingParcelObject() {
        Parcel parcel = Parcel.obtain();
        try {
            parcel.writeString("app");
            parcel.writeString("from");
            parcel.writeString("data");
            parcel.writeString("time");
            parcel.writeString("Intent");
            parcel.writeString("12 B");
            parcel.writeBundle(new Bundle());
            parcel.writeString("stack");
            parcel.writeString("uri");
            parcel.writeString("following-object");
            parcel.setDataPosition(0);
            ItemData restored = ItemData.CREATOR.createFromParcel(parcel);
            assertEquals("app", restored.getAppName());
            assertEquals(0, restored.getCaptureId());
            assertEquals("following-object", parcel.readString());
        } finally { parcel.recycle(); }
    }

    @Test public void writesOriginalFormatEvenWhenRepositoryIdIsPresent() {
        ItemData item = new ItemData(null, "app", "from", "data", "time", "12 B",
                new Bundle(), "Intent", "stack", "uri");
        item.setCaptureId(123);
        Parcel parcel = Parcel.obtain();
        try {
            item.writeToParcel(parcel, 0);
            parcel.writeString("following-object");
            parcel.setDataPosition(0);
            ItemData restored = ItemData.CREATOR.createFromParcel(parcel);
            assertEquals("uri", restored.getUri());
            assertEquals("following-object", parcel.readString());
            assertEquals(123, item.getCaptureId());
        } finally { parcel.recycle(); }
    }
}
