package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.AtomicFile;

import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.CaptureRepository;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.xposed.CaptureFiles;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class FragmentReplayStoreTest {
    private static final String TARGET = "example.test";
    @Rule public final TemporaryFolder folders = new TemporaryFolder();

    @Test public void sameReplayIntentAcceptsDifferentTypedEditsAndKeepsItsOriginalRoute() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bundle capture = capturedCall(context);
        Bundle descriptor = capture.getBundle(Constants.NAVIGATION_REPLAY);
        Bundle edits = new Bundle();
        edits.putString("item", "edited value");
        edits.putLong("selectedIndex", 3L);

        Intent generated = FragmentReplayStore.createIntent(context, capture, descriptor, edits);
        String uri = generated.toUri(Intent.URI_INTENT_SCHEME);
        assertTrue(uri.contains("S.item="));
        assertTrue(uri.contains("l.selectedIndex=3"));
        assertFalse(uri.contains(TARGET));

        Intent incoming = Intent.parseUri(uri, Intent.URI_INTENT_SCHEME);
        assertEquals("edited value", incoming.getStringExtra("item"));
        incoming.putExtra("packageName", "another.app");
        incoming.putExtra("descriptor", "another route");
        incoming.putExtra("item", "first runtime value");
        edits.putString("item", "modified after saving");
        FragmentReplayStore.Prepared loaded = FragmentReplayStore.load(context, incoming);

        assertEquals(TARGET, loaded.source.getString("packageName"));
        assertEquals("first runtime value", loaded.arguments.getString("item"));
        assertEquals(3L, loaded.arguments.get("selectedIndex"));
        assertFalse(loaded.arguments.containsKey("packageName"));
        assertFalse(loaded.arguments.containsKey("descriptor"));
        assertEquals(descriptor.getString("capture_proof"), loaded.descriptor.getString("capture_proof"));

        Intent second = new Intent(incoming).replaceExtras((Bundle) null).putExtra("item", "第二个值");
        FragmentReplayStore.Prepared loadedAgain = FragmentReplayStore.load(context, second);
        assertEquals(incoming.getData(), second.getData());
        assertEquals("第二个值", loadedAgain.arguments.getString("item"));
        assertEquals(3L, loadedAgain.arguments.get("selectedIndex"));
        assertEquals("first runtime value", loaded.arguments.getString("item"));
        assertEquals("edited value", FragmentReplayStore.load(context,
                new Intent(incoming).replaceExtras((Bundle) null)).arguments.getString("item"));
    }

    @Test public void rejectsOverridesWithTheWrongTypeOrSize() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bundle capture = capturedCall(context);
        Bundle descriptor = capture.getBundle(Constants.NAVIGATION_REPLAY);
        Intent generated = FragmentReplayStore.createIntent(context, capture, descriptor,
                descriptor.getBundle("arguments"));

        assertThrows(IllegalArgumentException.class, () -> FragmentReplayStore.load(context,
                new Intent(generated).putExtra("selectedIndex", 3)));
        assertThrows(IllegalArgumentException.class, () -> FragmentReplayStore.load(context,
                new Intent(generated).putExtra("item", new String(new char[8193]))));
    }

    @Test public void templateDraftEditsSurviveUriRoundTripWithoutChangingSavedDefaults() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bundle capture = capturedCall(context);
        Bundle descriptor = capture.getBundle(Constants.NAVIGATION_REPLAY);
        Intent original = FragmentReplayStore.createIntent(context, capture, descriptor,
                descriptor.getBundle("arguments"), "draft test", "draft-test");
        FragmentReplayStore.TemplateDraft draft = FragmentReplayStore.templateDraft(context, "draft-test");
        assertTrue(draft.editable);
        Bundle edited = draft.arguments();
        edited.putString("item", "只用于本次重放");
        edited.putLong("selectedIndex", 42L);
        Intent replay = draft.intent(edited);
        assertEquals(original.getData(), replay.getData());
        Intent copied = Intent.parseUri(replay.toUri(Intent.URI_INTENT_SCHEME), Intent.URI_INTENT_SCHEME);
        FragmentReplayStore.Prepared loaded = FragmentReplayStore.load(context, copied);
        assertEquals("只用于本次重放", loaded.arguments.getString("item"));
        assertEquals(42L, loaded.arguments.get("selectedIndex"));
        assertEquals(original.toUri(Intent.URI_INTENT_SCHEME),
                FragmentReplayStore.templateIntent(context, "draft-test").toUri(Intent.URI_INTENT_SCHEME));
        edited.putInt("selectedIndex", 42);
        assertThrows(IllegalArgumentException.class, () -> draft.intent(edited));
    }

    @Test public void olderFixedArgumentIntentStillIgnoresOverrides() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bundle capture = capturedCall(context);
        Bundle descriptor = capture.getBundle(Constants.NAVIGATION_REPLAY);
        Intent generated = FragmentReplayStore.createIntent(context, capture, descriptor,
                descriptor.getBundle("arguments"));
        File directory = new File(context.getNoBackupFilesDir(), "fragment_replays");
        File saved = new File(directory, generated.getData().getLastPathSegment());
        Bundle encoded = new Bundle();
        encoded.putString(IntentPayloadCodec.KEY,
                new String(new AtomicFile(saved).readFully(), StandardCharsets.US_ASCII));
        Intent savedReplay = IntentPayloadCodec.decode(encoded).putExtra("version", 1);
        CaptureFiles.write(saved, IntentPayloadCodec.encode(savedReplay).getBytes(StandardCharsets.US_ASCII));

        Intent legacy = new Intent(generated).replaceExtras((Bundle) null).putExtra("item", "ignored value");
        assertEquals("old captured value", FragmentReplayStore.load(context, legacy).arguments.getString("item"));

        FragmentReplayStore.TemplateDraft draft = FragmentReplayStore.templateDraft(context,
                generated.getData().getLastPathSegment());
        assertFalse(draft.editable);
        assertEquals("old captured value", draft.arguments().getString("item"));
        Intent fromDraft = draft.intent(legacy.getExtras());
        assertEquals(generated.getData(), fromDraft.getData());
        assertFalse(fromDraft.hasExtra("item"));
    }

    @Test public void rejectsUnknownCapabilitiesAndUnexpectedEntryShape() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        Bundle capture = capturedCall(context);
        Bundle descriptor = capture.getBundle(Constants.NAVIGATION_REPLAY);
        Intent generated = FragmentReplayStore.createIntent(context, capture, descriptor,
                descriptor.getBundle("arguments"));
        assertThrows(IllegalArgumentException.class, () -> FragmentReplayStore.load(context,
                new Intent(generated).setAction(Intent.ACTION_VIEW)));
        assertThrows(IllegalArgumentException.class, () -> FragmentReplayStore.load(context,
                new Intent(generated).setData(Uri.parse(generated.getDataString() + "?args=edited"))));

        String data = generated.getDataString();
        String unknown = data.substring(0, data.length() - 1) + (data.endsWith("0") ? '1' : '0');
        assertThrows(IOException.class, () -> FragmentReplayStore.load(context,
                new Intent(generated).setData(Uri.parse(unknown))));
    }

    @Test public void importedAliasUsesOnlyMatchingLocalReplayEvidence() throws Exception {
        Context sender = templateContext();
        Bundle senderCapture = capturedCall(sender);
        Bundle senderDescriptor = senderCapture.getBundle(Constants.NAVIGATION_REPLAY);
        Bundle sharedValues = new Bundle(senderDescriptor.getBundle("arguments"));
        sharedValues.putString("item", "shared editable value");
        Intent published = FragmentReplayStore.createIntent(sender, senderCapture, senderDescriptor,
                sharedValues, "文章示例", "open-chat");
        assertEquals("open-chat", published.getData().getLastPathSegment());
        String exported = FragmentReplayStore.exportTemplate(sender, "open-chat");
        assertFalse(exported.contains("capture_proof"));
        assertFalse(exported.contains("source_uid"));
        assertFalse(exported.contains("invocation"));
        assertFalse(exported.contains("old captured value"));
        assertTrue(exported.contains("shared editable value"));

        Context receiver = templateContext();
        FragmentReplayStore.Template pending = FragmentReplayStore.importTemplate(receiver, exported, null);
        assertEquals("文章示例", pending.name);
        assertEquals("open-chat", pending.id);
        assertEquals(1234, pending.versionCode);
        assertTrue(pending.pending);
        assertFalse(pending.ready);
        assertThrows(IllegalStateException.class, () -> FragmentReplayStore.load(receiver, published));

        String altered = new JSONObject(exported).put("route", "AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA").toString();
        FragmentReplayStore.importTemplate(receiver, altered, "changed-route");
        String otherVersion = new JSONObject(exported).put("versionCode", 1235).toString();
        FragmentReplayStore.importTemplate(receiver, otherVersion, "other-version");
        Bundle receiverCapture = capturedCall(receiver, 12023, "another locally observed value");
        Bundle receiverDescriptor = receiverCapture.getBundle(Constants.NAVIGATION_REPLAY);
        FragmentReplayStore.createIntent(receiver, receiverCapture, receiverDescriptor,
                receiverDescriptor.getBundle("arguments"), null, "local-evidence");

        FragmentReplayStore.Prepared opened = FragmentReplayStore.load(receiver,
                new Intent(published).putExtra("item", "runtime value"));
        assertEquals(12023, opened.source.getInt("source_uid"));
        assertEquals("runtime value", opened.arguments.getString("item"));
        assertEquals(receiverDescriptor.getString("capture_proof"), opened.descriptor.getString("capture_proof"));
        assertEquals("shared editable value", FragmentReplayStore.templateIntent(receiver, "open-chat").getStringExtra("item"));
        assertThrows(IllegalStateException.class, () -> FragmentReplayStore.templateIntent(receiver, "changed-route"));
        assertThrows(IllegalStateException.class, () -> FragmentReplayStore.templateIntent(receiver, "other-version"));
        assertThrows(IOException.class, () -> FragmentReplayStore.importTemplate(receiver, exported, "open-chat"));
        FragmentReplayStore.renameTemplate(receiver, "open-chat", "后来修改的名称");
        assertTrue(FragmentReplayStore.exportTemplate(receiver, "open-chat").contains("后来修改的名称"));
        FragmentReplayStore.duplicateTemplate(receiver, "open-chat", "archived-link");
        FragmentReplayStore.deleteTemplate(receiver, "open-chat");
        assertThrows(IOException.class, () -> FragmentReplayStore.load(receiver, published));
        assertEquals("shared editable value", FragmentReplayStore.templateIntent(receiver, "archived-link").getStringExtra("item"));
        assertThrows(IllegalArgumentException.class, () -> FragmentReplayStore.importTemplate(receiver, exported, "../other"));
    }

    @Test public void newCaptureArchiveRecordResolvesAnEarlierImportWithoutGeneratingAnotherLocalLink() throws Exception {
        Context sender = templateContext();
        Bundle senderCapture = capturedCall(sender);
        Bundle descriptor = senderCapture.getBundle(Constants.NAVIGATION_REPLAY);
        FragmentReplayStore.createIntent(sender, senderCapture, descriptor, descriptor.getBundle("arguments"), null, "cached-route");
        String exported = FragmentReplayStore.exportTemplate(sender, "cached-route");

        Context receiver = templateContext();
        FragmentReplayStore.Template pending = FragmentReplayStore.importTemplate(receiver, exported, null);
        assertTrue(pending.pending);
        assertThrows(IllegalStateException.class, () -> FragmentReplayStore.templateIntent(receiver, "cached-route"));

        CaptureRepository archive = CaptureRepository.get(receiver);
        long revision = archive.captureHistoryRevision();
        Bundle localCapture = capturedCall(receiver, 12023, "captured on this device");
        Bundle batch = new Bundle();
        batch.putString("batch_data_binder", JsonHandler.toJson(Collections.singletonList(localCapture)));
        archive.append(batch);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (archive.captureHistoryRevision() == revision && System.nanoTime() < deadline) Thread.sleep(20);
        assertTrue(archive.captureHistoryRevision() > revision);

        Intent replay = FragmentReplayStore.templateIntent(receiver, "cached-route");
        FragmentReplayStore.Prepared opened = FragmentReplayStore.load(receiver, replay);
        assertEquals(12023, opened.source.getInt("source_uid"));
        assertEquals("old captured value", opened.arguments.getString("item"));
        assertEquals(1, FragmentReplayStore.listTemplates(receiver).size());
        assertTrue(FragmentReplayStore.listTemplates(receiver).get(0).ready);
    }

    private Context templateContext() throws IOException {
        File directory = folders.newFolder();
        return new ContextWrapper(RuntimeEnvironment.getApplication()) {
            @Override public File getNoBackupFilesDir() { return directory; }
        };
    }

    private static Bundle capturedCall(Context context) throws Exception {
        return capturedCall(context, 11001, "old captured value");
    }

    private static Bundle capturedCall(Context context, int uid, String capturedValue) throws Exception {
        PackageInfo target = new PackageInfo();
        target.packageName = TARGET;
        target.lastUpdateTime = 1_765_000_000_000L;
        target.setLongVersionCode(1234);
        target.versionName = "1.2.3";
        target.signingInfo = new SigningInfo();
        shadowOf(target.signingInfo).setSignatures(new Signature[]{new Signature(new byte[]{1, 2, 3, 4})});
        target.applicationInfo = new ApplicationInfo();
        target.applicationInfo.packageName = TARGET;
        target.applicationInfo.uid = uid;
        target.applicationInfo.enabled = true;
        shadowOf(context.getPackageManager()).installPackage(target);

        Bundle source = new Bundle();
        source.putString("packageName", TARGET);
        source.putString("processName", TARGET);
        source.putInt("source_uid", target.applicationInfo.uid);
        source.putInt("source_app_uid", target.applicationInfo.uid);
        source.putLong("source_last_update", target.lastUpdateTime);
        source.putInt("capture_sdk", Build.VERSION.SDK_INT);
        Bundle arguments = new Bundle();
        arguments.putString("item", capturedValue);
        arguments.putLong("selectedIndex", 1L);
        Bundle descriptor = new Bundle();
        descriptor.putString("mechanism", "fragment_host");
        descriptor.putString("host", TARGET + ".MainActivity");
        descriptor.putString("destination", TARGET + ".ConversationFragment");
        descriptor.putString("method", "openConversation");
        descriptor.putString("invocation", new JSONObject()
                .put("owner", TARGET + ".MainActivity")
                .put("types", new org.json.JSONArray().put("java.lang.String"))
                .put("bindings", new org.json.JSONArray().put(new org.json.JSONArray()
                        .put(0).put(JSONObject.NULL).put("item").put("item")))
                .put("values", new org.json.JSONArray().put(new org.json.JSONArray().put("String").put(capturedValue)))
                .toString());
        descriptor.putBundle("arguments", arguments);
        Bundle capture = new Bundle(source);
        capture.putString("category", Constants.FRAGMENT_CATEGORY);
        capture.putString("title", descriptor.getString("destination"));
        capture.putBundle(Constants.NAVIGATION_ARGUMENTS, arguments);
        capture.putBundle(Constants.NAVIGATION_REPLAY, NavigationCaptureProof.seal(context, source, descriptor));
        return capture;
    }
}
