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

    @Test public void selectedAppsClearAllTheirVersionsAndUnassignedRoutesOnly() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String other = "example.other";
        String otherActivity = other + ".MainActivity";
        install(context, TARGET, 7, INSTALLED);
        install(context, SENDER, 7, INSTALLED);
        install(context, other, 7, INSTALLED);
        IntentLibrary library = IntentLibrary.get(context);
        library.setEnabled(true);
        Bundle outgoing = captured(new Intent("example.OPEN").setClassName(other, otherActivity));
        outgoing.putString("packageName", TARGET);
        outgoing.putString("FunctionCall", "Instrumentation.execStartActivity");
        ingest(library, delivered("older", 6, INSTALLED - 1_000), delivered("current", 7, INSTALLED),
                unresolved(TARGET, "first", 0), unresolved(other, "second", 0), outgoing,
                delivery(SENDER, SENDER + ".MainActivity", new Intent("example.OPEN"), 7, INSTALLED));
        library.ingest(Collections.emptyList(), Arrays.asList(evidence(TARGET, TARGET_ACTIVITY),
                evidence(SENDER, SENDER + ".MainActivity"), evidence(other, otherActivity)), 12001);
        assertEquals(3, library.observationsForApp(TARGET).size());
        assertEquals(1, library.observationsForApp(SENDER).size());
        assertEquals(2, library.observationsForApp(other).size());
        assertEquals(1, library.fragmentEvidence(TARGET, TARGET_ACTIVITY, FRAGMENT, 7, INSTALLED).size());
        assertEquals(1, library.fragmentEvidence(SENDER, SENDER + ".MainActivity", SENDER + ".ViewFragment", 7,
                INSTALLED).size());

        library.deleteApps(Arrays.asList(TARGET, SENDER));
        assertTrue(library.observationsForApp(TARGET).isEmpty());
        assertTrue(library.observationsForApp(SENDER).isEmpty());
        assertTrue(library.fragmentEvidence(TARGET, TARGET_ACTIVITY, FRAGMENT, 7, INSTALLED).isEmpty());
        assertTrue(library.fragmentEvidence(SENDER, SENDER + ".MainActivity", SENDER + ".ViewFragment", 7,
                INSTALLED).isEmpty());
        assertEquals(2, library.observationsForApp(other).size());
        assertEquals(1, library.fragmentEvidence(other, otherActivity, other + ".ViewFragment", 7, INSTALLED).size());
    }

    @Test public void activitySelectionRefreshesGroupsAndPreservesSiblingActivityEvidence() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        String alias = TARGET + ".PublicEntry";
        String sibling = TARGET + ".OtherActivity";
        install(context, TARGET, 7, INSTALLED, declaration(TARGET_ACTIVITY, null), declaration(alias, TARGET_ACTIVITY),
                declaration(sibling, null));
        install(context, SENDER, 7, INSTALLED);
        List<PackageActivityCatalog.Entry> declarations = PackageActivityCatalog.activities(context, TARGET);
        IntentLibrary library = IntentLibrary.get(context);
        library.setEnabled(true);
        ingest(library, delivered("direct", 7, INSTALLED), addressedToAlias("through-alias", alias),
                unresolved(TARGET, "original", 0), unresolved(SENDER, "other-app", 0),
                delivery(TARGET, sibling, new Intent("example.OTHER"), 7, INSTALLED));
        library.ingest(Collections.emptyList(), Arrays.asList(evidence(TARGET, TARGET_ACTIVITY),
                evidence(TARGET, sibling)), 12001);
        List<PackageActivityCatalog.Activity> before = PackageActivityCatalog.merge(declarations,
                library.observationsForApp(TARGET));
        PackageActivityCatalog.Activity selectedAlias = group(before, alias);
        PackageActivityCatalog.Activity selectedRoute = group(before, "");
        assertEquals(1, selectedAlias.observations.size());
        assertEquals(1, selectedRoute.observations.size());
        Bundle lateAlias = addressedToAlias("arrived-later", alias);
        lateAlias.putInt("flags", Intent.FLAG_ACTIVITY_NEW_TASK);
        ingest(library, lateAlias, unresolved(TARGET, "different-value", Intent.FLAG_ACTIVITY_NEW_TASK));
        List<PackageActivityCatalog.Activity> refreshed = PackageActivityCatalog.merge(declarations,
                library.observationsForApp(TARGET));
        assertEquals(2, group(refreshed, alias).observations.size());
        assertEquals(2, group(refreshed, "").observations.size());
        assertEquals(selectedRoute.selectionKey, group(refreshed, "").selectionKey);

        library.deleteActivities(TARGET, Arrays.asList(selectedAlias, selectedRoute));
        List<PackageActivityCatalog.Activity> remaining = PackageActivityCatalog.merge(declarations,
                library.observationsForApp(TARGET));
        assertEquals(2, library.observationsForApp(TARGET).size());
        assertEquals(1, library.observationsForApp(SENDER).size());
        assertTrue(group(remaining, alias).observations.isEmpty());
        assertEquals(1, library.fragmentEvidence(TARGET, TARGET_ACTIVITY, FRAGMENT, 7, INSTALLED).size());
        assertEquals(1, library.fragmentEvidence(TARGET, sibling, FRAGMENT, 7, INSTALLED).size());

        library.deleteActivities(TARGET, Collections.singletonList(group(remaining, TARGET_ACTIVITY)));
        assertEquals(sibling, library.observationsForApp(TARGET).get(0).activity);
        assertTrue(library.fragmentEvidence(TARGET, TARGET_ACTIVITY, FRAGMENT, 7, INSTALLED).isEmpty());
        assertEquals(1, library.fragmentEvidence(TARGET, sibling, FRAGMENT, 7, INSTALLED).size());
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
        Bundle record = evidence(TARGET, TARGET_ACTIVITY);

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
        return delivery(TARGET, TARGET_ACTIVITY, new Intent("example.OPEN")
                .setClassName(TARGET, TARGET_ACTIVITY).putExtra("topic", topic), version, update);
    }

    private static Bundle delivery(String packageName, String activity, Intent original, int version, long update) {
        Bundle record = captured(original);
        record.putString("packageName", packageName);
        record.putString("FunctionCall", "Activity.performCreate");
        record.putString("from", activity);
        record.putLong("source_last_update", update);
        record.putLong("library_destination_version_code", version);
        record.putString("library_destination_version_name", "version-" + version);
        record.putLong("library_destination_last_update", update);
        return record;
    }

    private static Bundle raw(String topic) {
        return captured(new Intent("example.OPEN").setClassName(TARGET, TARGET_ACTIVITY).putExtra("topic", topic));
    }

    private static Bundle captured(Intent original) {
        Bundle record = DataConverter.convertIntentToBundle(original);
        record.putString("uri", original.toUri(Intent.URI_INTENT_SCHEME));
        return record;
    }

    private static Bundle unresolved(String source, String value, int flags) {
        Bundle record = captured(new Intent("example.UNRESOLVED").setFlags(flags)
                .setData(Uri.parse("jumpreplay-unresolved://detail?field=" + value)));
        record.putString("packageName", source);
        record.putString("FunctionCall", "Instrumentation.execStartActivity");
        return record;
    }

    private static Bundle evidence(String packageName, String activity) throws Exception {
        Bundle record = new Bundle();
        String fragment = packageName + ".ViewFragment";
        record.putString("category", Constants.FRAGMENT_CATEGORY);
        record.putString("packageName", packageName);
        record.putString("from", activity);
        record.putString("title", fragment);
        Bundle args = new Bundle();
        args.putString("fragmentTopic", "topic-value");
        record.putBundle(Constants.NAVIGATION_ARGUMENTS, args);
        String uri = new Intent("example.OPEN").setClassName(packageName, activity)
                .putExtra("topic", "topic-value").toUri(Intent.URI_INTENT_SCHEME);
        record.putString(ObservedFragmentIntent.RECORD_KEY, new JSONObject().put("package", packageName)
                .put("activity", activity).put("fragment", fragment).put("version", 7)
                .put("updated", INSTALLED).put("uri", uri)
                .put("bindings", new JSONObject().put("topic", "fragmentTopic")).toString());
        return record;
    }

    private static void ingest(IntentLibrary library, Bundle... records) {
        java.util.List<ItemData> data = new java.util.ArrayList<>();
        for (Bundle record : records) {
            data.add(new ItemData(null, "", "", "", "", "", record, "Intent", "", record.getString("uri")));
        }
        library.ingest(data, Collections.emptyList(), -1);
    }

    private static void install(Context context, String name, int version, long update,
            PackageActivityCatalog.Entry... entries) {
        PackageInfo pkg = new PackageInfo();
        pkg.packageName = name;
        pkg.versionCode = version;
        pkg.versionName = "version-" + version;
        pkg.lastUpdateTime = update;
        pkg.applicationInfo = new ApplicationInfo();
        pkg.applicationInfo.packageName = name;
        pkg.applicationInfo.enabled = true;
        pkg.applicationInfo.uid = 12001;
        pkg.activities = new ActivityInfo[Math.max(1, entries.length)];
        for (int i = 0; i < pkg.activities.length; i++) {
            ActivityInfo activity = new ActivityInfo();
            activity.name = entries.length == 0 ? name + ".MainActivity" : entries[i].name;
            activity.targetActivity = entries.length == 0 ? null : entries[i].aliasTarget;
            activity.packageName = name;
            activity.applicationInfo = pkg.applicationInfo;
            activity.exported = true;
            activity.enabled = true;
            pkg.activities[i] = activity;
        }
        shadowOf(context.getPackageManager()).installPackage(pkg);
    }
}
