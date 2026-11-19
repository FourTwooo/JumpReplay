package com.fourtwo.hookintent.library;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class ObservedFragmentIntentTest {
    private static final String PACKAGE = "fixture.example";
    private static final String HOST = PACKAGE + ".Host";

    @Test public void independentDeliveriesCorrelateDifferentNamedFieldsAndFillNewValues() throws Exception {
        List<ObservedFragmentIntent.Evidence> examples = Arrays.asList(
                sample("sample-one", "document", "open"), sample("sample-two", "document", "open"));

        Intent resolved = ObservedFragmentIntent.resolve(examples, fragmentArguments("unseen document"));

        assertNotNull(resolved);
        assertEquals(new ComponentName(PACKAGE, HOST), resolved.getComponent());
        assertEquals("unseen document", resolved.getStringExtra("id"));
        assertEquals("document", resolved.getStringExtra("mode"));
        assertNull(resolved.getStringExtra("fragmentValue"));
    }

    @Test public void singleDeliveryRepeatedValueOrUnknownChangedFieldsCannotInferAnIntent() throws Exception {
        ObservedFragmentIntent.Evidence first = sample("same value", "document", "open");
        assertNull(ObservedFragmentIntent.resolve(Collections.singletonList(first), fragmentArguments("other")));
        assertNull(ObservedFragmentIntent.resolve(Arrays.asList(first, first), fragmentArguments("other")));
        assertNull(ObservedFragmentIntent.resolve(
                Arrays.asList(first, sample("sample-two", "unrelated change", "open")), fragmentArguments("other")));
        assertNull(ObservedFragmentIntent.resolve(
                Arrays.asList(first, sample("sample-two", "document", "differentAction")), fragmentArguments("other")));
    }

    @Test public void twoIndependentlyMatchedEntrypointsAreAmbiguous() throws Exception {
        List<ObservedFragmentIntent.Evidence> examples = Arrays.asList(
                sample("first", "document", "open"), sample("second", "document", "open"),
                sample("first", "document", "alternative"), sample("second", "document", "alternative"));
        assertNull(ObservedFragmentIntent.resolve(examples, fragmentArguments("other")));
    }

    private static Bundle fragmentArguments(String value) {
        Bundle arguments = new Bundle();
        arguments.putString("fragmentValue", value);
        return arguments;
    }

    private static ObservedFragmentIntent.Evidence sample(String value, String mode, String action) throws Exception {
        Intent intent = new Intent(action).setComponent(new ComponentName(PACKAGE, HOST))
                .putExtra("id", value).putExtra("mode", mode);
        JSONObject evidence = new JSONObject();
        evidence.put("package", PACKAGE);
        evidence.put("activity", HOST);
        evidence.put("fragment", PACKAGE + ".Panel");
        evidence.put("version", 30);
        evidence.put("updated", 1234);
        evidence.put("uri", intent.toUri(Intent.URI_INTENT_SCHEME));
        evidence.put("bindings", new JSONObject().put("id", "fragmentValue"));
        return ObservedFragmentIntent.Evidence.decode(evidence.toString());
    }
}
