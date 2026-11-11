package com.fourtwo.hookintent.xposed;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Bundle;
import com.fourtwo.hookintent.service.MessengerClient;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class DurableCaptureOutboxTest {
    @Test public void addDoesNoDiskWriteOnCallingThreadAndBatchesKeepEveryRecord() throws Exception {
        Context context = context();
        DurableCaptureOutbox outbox = new DurableCaptureOutbox(context, new MessengerClient(context), error -> { });
        ScheduledExecutorService worker = worker(outbox);
        CountDownLatch blocked = new CountDownLatch(1), release = new CountDownLatch(1);
        try {
            worker.execute(() -> {
                blocked.countDown();
                try { release.await(10, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            });
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            for (int index = 0; index < 100; index++) outbox.add(record(index), false);
            File directory = directory(outbox);
            assertEquals("add() must not serialize/write synchronously", 0, directory.list().length);
            release.countDown();
            worker.schedule(() -> { }, 150, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
            File[] batches = directory.listFiles((parent, name) -> name.endsWith(".json"));
            assertNotNull(batches); assertEquals(2, batches.length);
            int records = 0;
            for (File file : batches) records += new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8)).length();
            assertEquals(100, records);
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test public void firstOffSnapshotDiscardsOnlyUnknownStartupEventsAndNeverKnownCapturedEvents() throws Exception {
        Context context = context();
        DurableCaptureOutbox outbox = new DurableCaptureOutbox(context, new MessengerClient(context), error -> { });
        ScheduledExecutorService worker = worker(outbox);
        try {
            outbox.add(record(1), true);
            worker.schedule(() -> { }, 100, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
            assertEquals(1, directory(outbox).listFiles((parent, name) -> name.endsWith(".pending")).length);
            outbox.resolveInitialState(false);
            outbox.add(record(2), false);
            outbox.resolveInitialState(true);
            outbox.add(record(3), true); // A delayed startup event must retain the first snapshot's decision.
            worker.schedule(() -> { }, 150, TimeUnit.MILLISECONDS).get(5, TimeUnit.SECONDS);
            assertEquals(0, directory(outbox).listFiles((parent, name) -> name.endsWith(".pending")).length);
            File[] batches = directory(outbox).listFiles((parent, name) -> name.endsWith(".json"));
            assertEquals(1, batches.length);
            JSONArray records = new JSONArray(new String(Files.readAllBytes(batches[0].toPath()), StandardCharsets.UTF_8));
            assertEquals(1, records.length()); assertEquals(2, records.getJSONObject(0).getInt("value"));
        } finally { worker.shutdownNow(); }
    }

    @Test public void oversizedEventsArePersistedIndividuallyWithoutCropping() throws Exception {
        Context context = context();
        DurableCaptureOutbox outbox = new DurableCaptureOutbox(context, new MessengerClient(context), error -> { });
        ScheduledExecutorService worker = worker(outbox);
        try {
            String large = new String(new char[300000]).replace('\0', 'x');
            for (int index = 0; index < 3; index++) {
                Bundle record = record(index); record.putString("data", large); outbox.add(record, false);
            }
            worker.schedule(() -> { }, 200, TimeUnit.MILLISECONDS).get(10, TimeUnit.SECONDS);
            File[] batches = directory(outbox).listFiles((parent, name) -> name.endsWith(".json"));
            assertNotNull(batches); assertEquals(3, batches.length);
            for (File file : batches) {
                JSONArray records = new JSONArray(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
                assertEquals(1, records.length()); assertEquals(large, records.getJSONObject(0).getString("data"));
            }
        } finally { worker.shutdownNow(); }
    }

    private static Bundle record(int number) { Bundle bundle = new Bundle(); bundle.putInt("value", number); return bundle; }
    private static Context context() {
        File files = new File(RuntimeEnvironment.getApplication().getFilesDir(), "outbox-test-" + UUID.randomUUID());
        return new ContextWrapper(RuntimeEnvironment.getApplication()) { @Override public File getFilesDir() { return files; } };
    }
    private static ScheduledExecutorService worker(DurableCaptureOutbox outbox) throws Exception {
        Field field = DurableCaptureOutbox.class.getDeclaredField("worker"); field.setAccessible(true);
        return (ScheduledExecutorService) field.get(outbox);
    }
    private static File directory(DurableCaptureOutbox outbox) throws Exception {
        Field field = DurableCaptureOutbox.class.getDeclaredField("directory"); field.setAccessible(true);
        return (File) field.get(outbox);
    }
}
