package com.fourtwo.hookintent.library;

import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;

import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ItemData;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class IntentLibraryTest {
    private static final String TARGET = "example.target";
    private static final String TARGET_ACTIVITY = TARGET + ".MainActivity";
    private static final String SENDER = "example.sender";
    private static final String FRAGMENT = TARGET + ".ViewFragment";
    private static final long INSTALLED = 1_765_000_000_000L;

    @Before public void resetLibraryForApplication() throws Exception {
        Field instance = IntentLibrary.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test public void disabledByDefaultAndSeparatesUpdatedTargetVersions() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        install(context, TARGET, 7, INSTALLED);
        IntentLibrary library = IntentLibrary.get(context);
        assertFalse(library.enabled());
        ingest(library, delivered("first", 7, INSTALLED));
        assertTrue(library.observations(TARGET, 7).isEmpty());

        library.setEnabled(true);
        ingest(library, delivered("first", 7, INSTALLED), delivered("second", 7, INSTALLED));
        List<IntentLibrary.Observation> firstVersion = library.observations(TARGET, 7);
        assertEquals(1, firstVersion.size());
        IntentLibrary.Observation entry = firstVersion.get(0);
        assertEquals(2, entry.count);
        assertTrue(entry.delivered);
        assertEquals(TARGET_ACTIVITY, entry.activity);
        assertEquals("second", entry.parameters.get(0).example);
        assertEquals(Arrays.asList("second", "first"), entry.parameters.get(0).examples);
        assertEquals("java.lang.String", entry.parameters.get(0).type);
        assertEquals("second", Intent.parseUri(entry.intentUri, Intent.URI_INTENT_SCHEME).getStringExtra("topic"));
        ingest(library, delivered("third", 7, INSTALLED), delivered("fourth", 7, INSTALLED),
                delivered("fifth", 7, INSTALLED), delivered("second", 7, INSTALLED));
        assertEquals(Arrays.asList("second", "fifth", "fourth", "third"),
                library.observations(TARGET, 7).get(0).parameters.get(0).examples);

        long update = INSTALLED + 1_000;
        install(context, TARGET, 8, update);
        ingest(library, delivered("third", 8, update));
        assertEquals(1, library.observations(TARGET, 7).size());
        assertEquals(update, library.observations(TARGET, 8).get(0).destinationLastUpdate);
        assertEquals(2, library.observationsForApp(TARGET).size());
        library.setEnabled(false);
        ingest(library, delivered("ignored", 8, update));
        assertEquals("third", library.observations(TARGET, 8).get(0).parameters.get(0).example);
        library.clear();
        assertTrue(library.observations(TARGET, 7).isEmpty());
        assertTrue(library.observations(TARGET, 8).isEmpty());
    }

    @Test public void installedApplicationsExposeVersionsAndUnresolvedObservationsStayWithTheirSource() {
        Context context = RuntimeEnvironment.getApplication();
        install(context, TARGET, 7, INSTALLED);
        install(context, SENDER, 22, INSTALLED);
        IntentLibrary library = IntentLibrary.get(context);
        library.setEnabled(true);
        Intent unspecific = new Intent("example.UNRESOLVED")
                .setData(Uri.parse("jumpreplay-unresolved://detail?field=one"));
        Bundle sent = DataConverter.convertIntentToBundle(unspecific);
        sent.putString("uri", unspecific.toUri(Intent.URI_INTENT_SCHEME));
        sent.putString("packageName", SENDER);
        sent.putString("FunctionCall", "Instrumentation.execStartActivity");
        ingest(library, sent, delivered("known-target", 7, INSTALLED));

        List<IntentLibrary.Observation> sender = library.observationsForApp(SENDER);
        assertEquals(1, sender.size());
        assertEquals("", sender.get(0).destinationPackage);
        assertEquals(SENDER, sender.get(0).sourcePackage);
        assertEquals(1, library.observationsForApp(TARGET).size());
        assertTrue(library.observationsForApp("example.unrelated").isEmpty());
        PackageActivityCatalog.App installed = PackageActivityCatalog.applications(context).stream()
                .filter(app -> TARGET.equals(app.packageName)).findFirst().orElseThrow(AssertionError::new);
        assertEquals(7, installed.versionCode);
        assertEquals("version-7", installed.versionName);
        assertEquals(TARGET, installed.applicationInfo.packageName);
    }

    @Test public void groupsDeclaredCapturedAndAliasedActivitiesWithoutDroppingHistoricalActivities() throws Exception {
        String aliasName = TARGET + ".PublicEntry";
        String historicalActivity = TARGET + ".RemovedActivity";
        PackageActivityCatalog.Entry direct = declaration(TARGET_ACTIVITY, null);
        PackageActivityCatalog.Entry alias = declaration(aliasName, TARGET_ACTIVITY);
        IntentLibrary.Observation ordinary = observed(1, TARGET_ACTIVITY, null, "");
        IntentLibrary.Observation legacyAlias = observed(2, TARGET_ACTIVITY,
                new Intent("example.OPEN").setClassName(TARGET, aliasName).toUri(Intent.URI_INTENT_SCHEME), "");
        IntentLibrary.Observation historical = observed(3, historicalActivity, null, "");
        IntentLibrary.Observation firstRoute = observed(4, "", null, "jumpreplay-unresolved://detail?field=one");
        IntentLibrary.Observation secondRoute = observed(5, "", null, "jumpreplay-unresolved://detail?field=two");
        List<PackageActivityCatalog.Activity> groups = PackageActivityCatalog.merge(
                Arrays.asList(alias, direct, declaration(TARGET + ".UnopenedActivity", null)),
                Arrays.asList(secondRoute, firstRoute, historical, legacyAlias, ordinary));

        assertEquals(5, groups.size());
        assertEquals(Collections.singletonList(ordinary), group(groups, TARGET_ACTIVITY).observations);
        assertEquals(Collections.singletonList(legacyAlias), group(groups, aliasName).observations);
        assertEquals(alias, group(groups, aliasName).declaration);
        assertTrue(group(groups, TARGET + ".UnopenedActivity").observations.isEmpty());
        assertNull(group(groups, historicalActivity).declaration);
        assertEquals(Collections.singletonList(historical), group(groups, historicalActivity).observations);
        assertEquals(Arrays.asList(secondRoute, firstRoute), group(groups, "").observations);
        assertEquals("example.OPEN", group(groups, "").action);
    }

    @Test public void distinctAliasesKeepTheirCapturedSamplesSeparate() {
        Context context = RuntimeEnvironment.getApplication();
        install(context, TARGET, 7, INSTALLED);
        IntentLibrary library = IntentLibrary.get(context);
        library.setEnabled(true);
        String firstAlias = TARGET + ".PublicEntry";
        String secondAlias = TARGET + ".OtherPublicEntry";
        ingest(library, delivered("direct", 7, INSTALLED), addressedToAlias("through-first", firstAlias),
                addressedToAlias("through-second", secondAlias));

        List<IntentLibrary.Observation> observed = library.observationsForApp(TARGET);
        assertEquals(3, observed.size());
        List<PackageActivityCatalog.Activity> groups = PackageActivityCatalog.merge(
                Arrays.asList(declaration(TARGET_ACTIVITY, null), declaration(firstAlias, TARGET_ACTIVITY),
                        declaration(secondAlias, TARGET_ACTIVITY)), observed);
        assertEquals("direct", group(groups, TARGET_ACTIVITY).observations.get(0).parameters.get(0).example);
        assertEquals("through-first", group(groups, firstAlias).observations.get(0).parameters.get(0).example);
        assertEquals("through-second", group(groups, secondAlias).observations.get(0).parameters.get(0).example);
    }

    private static Bundle addressedToAlias(String topic, String alias) {
        Bundle record = delivered(topic, 7, INSTALLED);
        Intent original = new Intent("example.OPEN").setClassName(TARGET, alias).putExtra("topic", topic);
        record.putString("component", alias);
        record.putString("uri", original.toUri(Intent.URI_INTENT_SCHEME));
        return record;
    }

    private static PackageActivityCatalog.Entry declaration(String name, String aliasTarget) {
        return new PackageActivityCatalog.Entry(TARGET, name, aliasTarget, true, true, null, false,
                "version-7", 7, INSTALLED);
    }

    private static IntentLibrary.Observation observed(long id, String name, String intentUri, String data)
            throws Exception {
        return new IntentLibrary.Observation(id, new JSONObject().put("package", TARGET).put("activity", name)
                .put("delivered", true).put("action", "example.OPEN").put("data", data)
                .put("uri", intentUri == null ? JSONObject.NULL : intentUri).put("extras", new JSONArray()), 1,
                INSTALLED);
    }

    private static PackageActivityCatalog.Activity group(List<PackageActivityCatalog.Activity> groups, String name) {
        return groups.stream().filter(group -> group.name.equals(name)).findFirst().orElseThrow(AssertionError::new);
    }

    @Test public void explicitOutgoingRequestUsesDestinationVersionAndKeepsSenderSeparate() {
        Context context = RuntimeEnvironment.getApplication();
        install(context, TARGET, 7, INSTALLED);
        install(context, SENDER, 22, INSTALLED - 1_000);
        IntentLibrary library = IntentLibrary.get(context);
        library.setEnabled(true);
        Bundle request = raw("requested");
        request.putString("packageName", SENDER);
        request.putString("from", SENDER + ".StartActivity");
        request.putString("FunctionCall", "Instrumentation.execStartActivity");
        request.putLong("source_last_update", INSTALLED - 1_000);
        ingest(library, request);
        IntentLibrary.Observation result = library.observations(TARGET, 7).get(0);
        assertFalse(result.delivered);
        assertEquals(22, result.sourceVersionCode);
        assertEquals(7, result.destinationVersionCode);
        assertEquals(TARGET_ACTIVITY, result.activity);
        assertEquals(SENDER, result.sourcePackage);
        assertTrue(library.page("100%_not_the_app", 60, 0).isEmpty());

        library.delete(result);
        assertTrue(library.observations(TARGET, 7).isEmpty());
    }

    @Test public void clearingRemovesFragmentEvidenceAndTurningOffRecordingRetainsIt() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        install(context, TARGET, 7, INSTALLED);
        IntentLibrary library = IntentLibrary.get(context);
        Bundle record = new Bundle();
        record.putString("category", Constants.FRAGMENT_CATEGORY);
        record.putString("packageName", TARGET);
        record.putString("from", TARGET_ACTIVITY);
        record.putString("title", FRAGMENT);
        Bundle args = new Bundle();
        args.putString("fragmentTopic", "topic-value");
        record.putBundle(Constants.NAVIGATION_ARGUMENTS, args);
        String uri = new Intent("example.OPEN").setClassName(TARGET, TARGET_ACTIVITY)
                .putExtra("topic", "topic-value").toUri(Intent.URI_INTENT_SCHEME);
        record.putString(ObservedFragmentIntent.RECORD_KEY, new JSONObject().put("package", TARGET)
                .put("activity", TARGET_ACTIVITY).put("fragment", FRAGMENT).put("version", 7)
                .put("updated", INSTALLED).put("uri", uri)
                .put("bindings", new JSONObject().put("topic", "fragmentTopic")).toString());

        library.setEnabled(true);
        library.ingest(Collections.emptyList(), Collections.singletonList(record), 12002);
        assertTrue(library.fragmentEvidence(TARGET, TARGET_ACTIVITY, FRAGMENT, 7, INSTALLED).isEmpty());
        library.ingest(Collections.emptyList(), Collections.singletonList(record), 12001);
        library.ingest(Collections.emptyList(), Collections.singletonList(record), 12001);
        assertEquals(1, library.fragmentEvidence(TARGET, TARGET_ACTIVITY, FRAGMENT, 7, INSTALLED).size());
        assertTrue(library.fragmentEvidence(TARGET, TARGET_ACTIVITY, FRAGMENT, 7, INSTALLED + 1).isEmpty());
        library.setEnabled(false);
        assertEquals(1, library.fragmentEvidence(TARGET, TARGET_ACTIVITY, FRAGMENT, 7, INSTALLED).size());
        library.clear();
        library.setEnabled(true);
        assertTrue(library.fragmentEvidence(TARGET, TARGET_ACTIVITY, FRAGMENT, 7, INSTALLED).isEmpty());
    }

    private static Bundle delivered(String topic, int version, long update) {
        Bundle record = raw(topic);
        record.putString("packageName", TARGET);
        record.putString("FunctionCall", "Activity.performCreate");
        record.putString("from", TARGET_ACTIVITY);
        record.putLong("source_last_update", update);
        record.putLong("library_destination_version_code", version);
        record.putString("library_destination_version_name", "version-" + version);
        record.putLong("library_destination_last_update", update);
        return record;
    }

    private static Bundle raw(String topic) {
        Intent original = new Intent("example.OPEN").setClassName(TARGET, TARGET_ACTIVITY).putExtra("topic", topic);
        Bundle record = DataConverter.convertIntentToBundle(original);
        record.putString("uri", original.toUri(Intent.URI_INTENT_SCHEME));
        return record;
    }

    private static void ingest(IntentLibrary library, Bundle... records) {
        java.util.List<ItemData> data = new java.util.ArrayList<>();
        for (Bundle record : records) {
            data.add(new ItemData(null, "", "", "", "", "", record, "Intent", "", record.getString("uri")));
        }
        library.ingest(data, Collections.emptyList(), -1);
    }

    private static void install(Context context, String name, int version, long update) {
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = name;
        pkg.versionCode = version;
        pkg.versionName = "version-" + version;
        pkg.lastUpdateTime = update;
        pkg.applicationInfo = new ApplicationInfo();
        pkg.applicationInfo.packageName = name;
        pkg.applicationInfo.enabled = true;
        pkg.applicationInfo.uid = 12001;
        ActivityInfo activity = new ActivityInfo();
        activity.name = name + ".MainActivity";
        activity.packageName = name;
        activity.applicationInfo = pkg.applicationInfo;
        activity.exported = true;
        activity.enabled = true;
        pkg.activities = new ActivityInfo[]{activity};
        shadowOf(context.getPackageManager()).installPackage(pkg);
    }
}
