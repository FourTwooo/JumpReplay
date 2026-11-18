package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.Context;
import android.os.Bundle;
import android.content.Intent;
import com.fourtwo.hookintent.base.JsonHandler;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class NavigationCaptureProofTest {
    @Test public void sealedCallSurvivesCaptureStorageAndCannotBeModifiedOrRetargeted() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bundle source = new Bundle();
        source.putString("processName", "example.app");
        source.putLong("source_last_update", 1_765_000_000_000L);
        Bundle descriptor = new Bundle();
        descriptor.putString("host", "example.app.MainActivity");
        descriptor.putInt("action_id", 123);
        Bundle arguments = new Bundle();
        arguments.putLong("smallLong", 5L);
        arguments.putFloat("fraction", 0.1f);
        arguments.putInt("item", 12);
        descriptor.putBundle("arguments", arguments);
        Bundle types = new Bundle();
        types.putString("smallLong", "long");
        types.putString("fraction", "float");
        types.putString("item", "int");
        descriptor.putBundle("argument_types", types);

        Bundle sealed = NavigationCaptureProof.seal(context, source, descriptor);
        JSONObject written = new JSONObject(JsonHandler.toMap(sealed));
        Bundle persisted = JsonHandler.toBundle(new JSONObject(written.toString()));
        Bundle verified = NavigationCaptureProof.verify(context, source, persisted);
        assertEquals(123, verified.getInt("action_id"));
        assertFalse(NavigationCaptureProof.hasProof(verified));

        Bundle editedDescriptor = new Bundle(persisted);
        editedDescriptor.putInt("action_id", 321);
        assertThrows(SecurityException.class, () ->
                NavigationCaptureProof.verify(context, source, editedDescriptor));

        Bundle otherProcess = new Bundle(source);
        otherProcess.putString("processName", "example.app:other");
        assertThrows(SecurityException.class, () ->
                NavigationCaptureProof.verify(context, otherProcess, persisted));
    }

    @Test public void acceptsOnlyBoundedScalarEdits() {
        Bundle edits = new Bundle();
        edits.putParcelable("activity", new Intent());
        assertThrows(IllegalArgumentException.class, () -> NavigationCaptureProof.copy(edits, false));
        edits.clear();
        edits.putBundle("arguments", new Bundle());
        assertThrows(IllegalArgumentException.class, () -> NavigationCaptureProof.copy(edits, false));
    }
}
