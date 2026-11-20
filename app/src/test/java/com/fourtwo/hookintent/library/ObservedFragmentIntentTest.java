package com.fourtwo.hookintent.library;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Bundle;

import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.io.Serializable;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class ObservedFragmentIntentTest {
    private static final String PACKAGE = "fixture.example";
    private static final String HOST = PACKAGE + ".Host";
    private static final String PANEL = PACKAGE + ".Panel";
    private static final int PACKAGE_VERSION = 30;
    private static final int PACKAGE_UID = 12001;
    private static final long INSTALLED = 1_765_000_000_000L;

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

    @Test public void integralArgumentsKeepTheirExactUriTypesAndValues() throws Exception {
        Serializable[] first = {Byte.MIN_VALUE, Short.MIN_VALUE, Integer.MIN_VALUE, Long.MIN_VALUE};
        Serializable[] second = {Byte.MAX_VALUE, Short.MAX_VALUE, Integer.MAX_VALUE, Long.MAX_VALUE};
        Serializable[] target = {(byte) 0, (short) -1, 100, 9_007_199_254_740_993L};
        for (int i = 0; i < first.length; i++) {
            List<ObservedFragmentIntent.Evidence> examples = Arrays.asList(
                    sample(first[i], "document", "open"), sample(second[i], "document", "open"));
            Intent resolved = ObservedFragmentIntent.resolve(examples, fragmentArguments(target[i]));
            assertNotNull(resolved);
            Intent restored = Intent.parseUri(resolved.toUri(Intent.URI_INTENT_SCHEME), Intent.URI_INTENT_SCHEME);
            assertEquals(target[i], restored.getExtras().get("id"));
            assertEquals("document", restored.getStringExtra("mode"));
        }
    }

    @Test public void numericWidthsAndInstallationMustAgreeAndConflictingRoutesStayUnresolved() throws Exception {
        ObservedFragmentIntent.Evidence first = sample(1, "document", "open");
        assertNull(ObservedFragmentIntent.resolve(Arrays.asList(first, sample(2L, "document", "open")),
                fragmentArguments(3)));
        List<ObservedFragmentIntent.Evidence> samples = Arrays.asList(first, sample(2, "document", "open"));
        assertNull(ObservedFragmentIntent.resolve(samples, fragmentArguments(3L)));
        assertNull(ObservedFragmentIntent.resolve(samples, fragmentArguments("3")));
        ObservedFragmentIntent.Evidence stale = ObservedFragmentIntent.Evidence.decode(
                new JSONObject(samples.get(1).encode()).put("updated", INSTALLED - 1000).toString());
        assertNull(ObservedFragmentIntent.resolve(Arrays.asList(first, stale), fragmentArguments(3)));
        assertNull(ObservedFragmentIntent.resolve(Arrays.asList(first, samples.get(1),
                sample(1, "document", "alternate"), sample(2, "document", "alternate")), fragmentArguments(3)));
    }

    @Test public void mappedConstantMustStillMatchBeforeVaryingAnotherArgument() throws Exception {
        List<ObservedFragmentIntent.Evidence> samples = Arrays.asList(
                withBoundConstant(sample(1, "document", "open")), withBoundConstant(sample(2, "document", "open")));
        Bundle target = fragmentArguments(3);
        assertNull(ObservedFragmentIntent.resolve(samples, target));
        target.putLong("fragmentContainer", 5L);
        assertNull(ObservedFragmentIntent.resolve(samples, target));
        target.putInt("fragmentContainer", 6);
        assertNull(ObservedFragmentIntent.resolve(samples, target));
        target.putInt("fragmentContainer", 5);
        Intent resolved = ObservedFragmentIntent.resolve(samples, target);
        assertNotNull(resolved);
        assertEquals(3, resolved.getIntExtra("id", -1));
        assertEquals(5, resolved.getIntExtra("container", -1));
    }

    @Test public void associationRejectsAmbiguousOrDifferentWidthNumericValues() {
        installFixture();
        Activity host = new FixtureHost();
        Intent delivered = ObservedFragmentIntent.delivery(host, new Intent("open").putExtra("id", 9L));
        assertNotNull(delivered);
        assertNotNull(ObservedFragmentIntent.associate(host, delivered, PANEL, Map.of("fragmentId", 9L)));
        assertNull(ObservedFragmentIntent.associate(host, delivered, PANEL, Map.of("fragmentId", 9)));
        assertNull(ObservedFragmentIntent.associate(host, delivered, PANEL, Map.of("first", 9L, "second", 9L)));
        delivered.putExtra("other", 9L);
        assertNull(ObservedFragmentIntent.associate(host, delivered, PANEL, Map.of("fragmentId", 9L)));
    }

    @Test public void libraryLookupRestoresOriginalWidthsAfterCaptureArchiveSerialization() throws Exception {
        Context context = installFixture();
        IntentLibrary library = IntentLibrary.get(context);
        library.setEnabled(true);
        Bundle first = archivedCapture(7L);
        Bundle second = archivedCapture(8L);
        assertEquals(Integer.valueOf(7), first.getBundle(Constants.NAVIGATION_ARGUMENTS).get("fragmentValue"));
        library.ingest(Collections.emptyList(), Arrays.asList(first, second), PACKAGE_UID);
        Intent resolved = ObservedFragmentIntent.lookup(context, archivedCapture(22L));
        assertNotNull(resolved);
        assertEquals(Long.valueOf(22L), resolved.getExtras().get("id"));

        Bundle legacy = archivedCapture(22L);
        legacy.remove(ObservedFragmentIntent.NUMERIC_ARGUMENT_TYPES_KEY);
        assertNull(ObservedFragmentIntent.lookup(context, legacy));
        assertNull(ObservedFragmentIntent.fromRecord(context, legacy, PACKAGE_UID));
        legacy.putBundle(ObservedFragmentIntent.NUMERIC_ARGUMENT_TYPES_KEY, new Bundle());
        legacy.getBundle(ObservedFragmentIntent.NUMERIC_ARGUMENT_TYPES_KEY).putString("fragmentValue", "Byte");
        legacy.getBundle(Constants.NAVIGATION_ARGUMENTS).putInt("fragmentValue", 128);
        assertNull(ObservedFragmentIntent.lookup(context, legacy));
    }

    private static Bundle fragmentArguments(Serializable value) {
        Bundle arguments = new Bundle();
        arguments.putSerializable("fragmentValue", value);
        return arguments;
    }

    private static ObservedFragmentIntent.Evidence withBoundConstant(ObservedFragmentIntent.Evidence sample) throws Exception {
        JSONObject evidence = new JSONObject(sample.encode());
        Intent intent = Intent.parseUri(evidence.getString("uri"), Intent.URI_INTENT_SCHEME);
        evidence.put("uri", intent.putExtra("container", 5).toUri(Intent.URI_INTENT_SCHEME));
        evidence.getJSONObject("bindings").put("container", "fragmentContainer");
        return ObservedFragmentIntent.Evidence.decode(evidence.toString());
    }

    private static Bundle archivedCapture(long value) throws Exception {
        Bundle arguments = fragmentArguments(value);
        Bundle capture = new Bundle();
        capture.putString("category", Constants.FRAGMENT_CATEGORY);
        capture.putString("packageName", PACKAGE);
        capture.putString("from", HOST);
        capture.putString("title", PANEL);
        capture.putLong("source_last_update", INSTALLED);
        capture.putBundle(Constants.NAVIGATION_ARGUMENTS, arguments);
        capture.putString(ObservedFragmentIntent.RECORD_KEY, sample(value, "document", "open").encode());
        ObservedFragmentIntent.retainNumericArgumentTypes(capture, arguments);
        return JsonHandler.toBundle(new JSONObject(new JSONObject(JsonHandler.toMap(capture)).toString()));
    }

    private static Context installFixture() {
        Context context = RuntimeEnvironment.getApplication();
        PackageInfo info = new PackageInfo();
        info.packageName = PACKAGE;
        info.versionCode = PACKAGE_VERSION;
        info.lastUpdateTime = INSTALLED;
        info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = PACKAGE;
        info.applicationInfo.enabled = true;
        info.applicationInfo.uid = PACKAGE_UID;
        ActivityInfo activity = new ActivityInfo();
        activity.name = HOST;
        activity.packageName = PACKAGE;
        activity.applicationInfo = info.applicationInfo;
        activity.enabled = true;
        activity.exported = true;
        info.activities = new ActivityInfo[]{activity};
        shadowOf(context.getPackageManager()).installPackage(info);
        return context;
    }

    private static final class FixtureHost extends Activity {
        @Override public String getPackageName() { return PACKAGE; }
        @Override public PackageManager getPackageManager() {
            return RuntimeEnvironment.getApplication().getPackageManager();
        }
    }

    private static ObservedFragmentIntent.Evidence sample(Serializable value, String mode, String action) throws Exception {
        Intent intent = new Intent(action).setComponent(new ComponentName(PACKAGE, HOST))
                .putExtra("id", value).putExtra("mode", mode);
        JSONObject evidence = new JSONObject();
        evidence.put("package", PACKAGE);
        evidence.put("activity", HOST);
        evidence.put("fragment", PANEL);
        evidence.put("version", PACKAGE_VERSION);
        evidence.put("updated", INSTALLED);
        evidence.put("uri", intent.toUri(Intent.URI_INTENT_SCHEME));
        evidence.put("bindings", new JSONObject().put("id", "fragmentValue"));
        return ObservedFragmentIntent.Evidence.decode(evidence.toString());
    }
}
