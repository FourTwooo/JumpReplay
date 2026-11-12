package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.Parcel;
import android.os.Parcelable;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

/** Unknown extras remain opaque while lightweight header/URI edits run in the UI process. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
@LooperMode(LooperMode.Mode.PAUSED)
public class ReplayTasksTest {
    public static class HostMustNotRead implements Parcelable {
        static final AtomicInteger reads = new AtomicInteger();
        @Override public int describeContents() { return 0; }
        @Override public void writeToParcel(Parcel parcel, int flags) { parcel.writeInt(73); }
        public static final Creator<HostMustNotRead> CREATOR = new Creator<HostMustNotRead>() {
            @Override public HostMustNotRead createFromParcel(Parcel parcel) {
                reads.incrementAndGet();
                throw new AssertionError("The host process must never deserialize this extra");
            }
            @Override public HostMustNotRead[] newArray(int count) { return new HostMustNotRead[count]; }
        };
    }

    private static Bundle record() {
        HostMustNotRead.reads.set(0);
        Intent source = new Intent("example.OPEN", Uri.parse("demo://host/original"));
        source.addCategory("example.CATEGORY");
        source.putExtra("custom", new HostMustNotRead());
        Bundle record = new Bundle();
        record.putString(IntentPayloadCodec.KEY, IntentPayloadCodec.encode(source));
        return record;
    }

    @Test public void unchangedUriCopiesOpaqueExtrasWithoutCallingTheirCreator() {
        Bundle record = record();
        Intent source = IntentPayloadCodec.decode(record);
        AtomicReference<Intent> result = new AtomicReference<>();
        AtomicInteger calls = new AtomicInteger();
        ExtrasClient connection = ReplayTasks.fromText(RuntimeEnvironment.getApplication(), record,
                "demo://host/original", source, "demo://host/original", (intent, error) -> {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper());
                    assertNull(error); result.set(intent); calls.incrementAndGet();
                });
        assertNull(connection);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, calls.get());
        assertEquals("demo://host/original", result.get().getDataString());
        assertEquals("example.OPEN", result.get().getAction());
        assertTrue(result.get().hasCategory("example.CATEGORY"));
        IntentPayloadCodec.encode(result.get());
        assertEquals(0, HostMustNotRead.reads.get());
    }

    @Test public void changingRawDataUriNeverUnpacksUnknownExtrasOrMutatesSource() {
        Bundle record = record();
        Intent source = IntentPayloadCodec.decode(record);
        AtomicReference<Intent> result = new AtomicReference<>();
        assertNull(ReplayTasks.fromText(RuntimeEnvironment.getApplication(), record,
                "demo://host/edited?q=a+b%26c", source, "demo://host/original", (intent, error) -> {
                    assertNull(error); result.set(intent);
                }));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("demo://host/edited?q=a+b%26c", result.get().getDataString());
        assertEquals("demo://host/original", source.getDataString());
        assertEquals("example.OPEN", result.get().getAction());
        IntentPayloadCodec.encode(result.get());
        assertEquals(0, HostMustNotRead.reads.get());
    }

    @Test public void emptyTextCompletesOnceWithAnErrorOnMainThread() {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Exception> failure = new AtomicReference<>();
        assertNull(ReplayTasks.fromText(RuntimeEnvironment.getApplication(), null, null, null, null, (intent, error) -> {
            assertEquals(Looper.getMainLooper(), Looper.myLooper());
            assertNull(intent); failure.set(error); calls.incrementAndGet();
        }));
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, calls.get());
        assertNotNull(failure.get());
    }

    @Test public void newPrimitiveIntentFromWorkerCompletesOnMainThread() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Intent> result = new AtomicReference<>();
        Thread caller = new Thread(() -> assertNull(ReplayTasks.fromText(RuntimeEnvironment.getApplication(), null,
                "intent:#Intent;action=example.OPEN;S.name=edited;end", null, null, (intent, error) -> {
                    assertEquals(Looper.getMainLooper(), Looper.myLooper());
                    assertNull(error); result.set(intent); calls.incrementAndGet();
                })));
        caller.start(); caller.join(2000);
        assertFalse(caller.isAlive());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(1, calls.get());
        assertEquals("edited", result.get().getStringExtra("name"));
    }
}
