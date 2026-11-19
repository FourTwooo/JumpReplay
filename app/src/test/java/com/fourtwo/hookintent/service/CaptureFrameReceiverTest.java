package com.fourtwo.hookintent.service;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import com.fourtwo.hookintent.data.CaptureRepository;
import com.fourtwo.hookintent.xposed.ByteChunks;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class CaptureFrameReceiverTest {
    @Before public void resetRepositoryForApplication() throws Exception {
        Field instance = CaptureRepository.class.getDeclaredField("instance");
        instance.setAccessible(true);
        instance.set(null, null);
    }

    @Test public void largeTransferSurvivesCloseWhileFinalRepositoryCommitIsInFlightAndRetryIsIdempotent() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        JSONObject record = new JSONObject();
        record.put("category", "Custom"); record.put("title", "large capture");
        record.put("data", new String(new char[1100000]).replace('\0', 'x'));
        byte[] bytes = new JSONArray().put(record).toString().getBytes(StandardCharsets.UTF_8);
        String id = String.format(java.util.Locale.ROOT, "%019d", System.currentTimeMillis()) + "-" + UUID.randomUUID();
        String hash = ByteChunks.sha256(new ByteArrayInputStream(bytes));
        CaptureFrameReceiver receiver = new CaptureFrameReceiver(context);
        int frames = ByteChunks.count(bytes.length);
        for (int frame = 0; frame < frames; frame++) {
            CountDownLatch received = new CountDownLatch(1);
            AtomicBoolean successful = new AtomicBoolean();
            receiver.receive(frame(bytes, id, hash, frame), 12001, (ok, detail) -> {
                successful.set(ok); received.countDown();
            });
            if (frame == frames - 1) receiver.close();
            assertTrue("Frame ACK did not arrive: " + frame, received.await(20, TimeUnit.SECONDS));
            assertTrue("Frame was rejected: " + frame, successful.get());
        }
        assertTrue(worker(receiver).awaitTermination(10, TimeUnit.SECONDS));

        CaptureFrameReceiver retryReceiver = new CaptureFrameReceiver(context);
        CountDownLatch retry = new CountDownLatch(1);
        AtomicBoolean retried = new AtomicBoolean();
        retryReceiver.receive(frame(bytes, id, hash, 0), 12001, (ok, detail) -> { retried.set(ok); retry.countDown(); });
        assertTrue(retry.await(10, TimeUnit.SECONDS));
        assertTrue(retried.get());
        retryReceiver.close();
        assertTrue(worker(retryReceiver).awaitTermination(10, TimeUnit.SECONDS));

        CaptureRepository repository = CaptureRepository.get(context);
        worker(repository).submit(() -> { }).get(10, TimeUnit.SECONDS);
        try (SQLiteDatabase database = context.openOrCreateDatabase("capture_archive.db", 0, null);
                Cursor cursor = database.rawQuery("SELECT COUNT(*) FROM incoming WHERE transport_id=?", new String[]{id})) {
            assertTrue(cursor.moveToFirst()); assertEquals(1, cursor.getInt(0));
        }
    }

    @Test public void invalidChecksumIsNotAcknowledged() throws Exception {
        byte[] bytes = "[]".getBytes(StandardCharsets.UTF_8);
        String id = String.format(java.util.Locale.ROOT, "%019d", System.currentTimeMillis()) + "-" + UUID.randomUUID();
        CaptureFrameReceiver receiver = new CaptureFrameReceiver(RuntimeEnvironment.getApplication());
        CountDownLatch received = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(true);
        receiver.receive(frame(bytes, id, new String(new char[64]).replace('\0', '0'), 0), 12001,
                (ok, detail) -> { accepted.set(ok); received.countDown(); });
        receiver.close();
        assertTrue(received.await(10, TimeUnit.SECONDS));
        assertFalse(accepted.get());
        assertTrue(worker(receiver).awaitTermination(10, TimeUnit.SECONDS));
    }

    @Test public void mixedSenderFramesKeepCaptureButDiscardEvidenceOwnership() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        JSONObject record = new JSONObject().put("category", "Custom").put("title", "mixed sender")
                .put("data", new String(new char[ByteChunks.PAYLOAD_BYTES]).replace('\0', 'x'));
        byte[] bytes = new JSONArray().put(record).toString().getBytes(StandardCharsets.UTF_8);
        int count = ByteChunks.count(bytes.length);
        assertEquals(2, count);
        String id = String.format(java.util.Locale.ROOT, "%019d", System.currentTimeMillis()) + "-" + UUID.randomUUID();
        String hash = ByteChunks.sha256(new ByteArrayInputStream(bytes));
        CaptureFrameReceiver receiver = new CaptureFrameReceiver(context);
        File sender = new File(new File(context.getFilesDir(), "capture-transport/" + id), "sender_uid");

        send(receiver, frame(bytes, id, hash, 0), 12001);
        assertEquals("12001", new String(Files.readAllBytes(sender.toPath()), StandardCharsets.US_ASCII));
        send(receiver, frame(bytes, id, hash, 0), 12002);
        assertEquals("-1", new String(Files.readAllBytes(sender.toPath()), StandardCharsets.US_ASCII));
        send(receiver, frame(bytes, id, hash, 1), 12001);
        receiver.close();
        assertTrue(worker(receiver).awaitTermination(10, TimeUnit.SECONDS));
        assertFalse(sender.exists());
        File transferIndex = new File(context.getFilesDir(), "capture-transport/completed.sqlite");
        try (SQLiteDatabase database = SQLiteDatabase.openDatabase(transferIndex.getPath(), null, SQLiteDatabase.OPEN_READONLY);
                Cursor row = database.rawQuery("SELECT id FROM completed WHERE id=?", new String[]{id})) {
            assertTrue(row.moveToFirst());
            assertEquals(id, row.getString(0));
        }
    }

    private static void send(CaptureFrameReceiver receiver, Bundle frame, int uid) throws Exception {
        CountDownLatch received = new CountDownLatch(1);
        AtomicBoolean accepted = new AtomicBoolean(false);
        receiver.receive(frame, uid, (ok, detail) -> {
            accepted.set(ok);
            received.countDown();
        });
        assertTrue(received.await(10, TimeUnit.SECONDS));
        assertTrue(accepted.get());
    }

    private static Bundle frame(byte[] bytes, String id, String hash, int frame) {
        Bundle message = new Bundle();
        message.putString("transferId", id); message.putString("sha256", hash);
        message.putInt("frame", frame); message.putInt("frames", ByteChunks.count(bytes.length));
        message.putLong("totalBytes", bytes.length); message.putByteArray("payload", ByteChunks.part(bytes, frame));
        return message;
    }

    private static ExecutorService worker(Object instance) throws Exception {
        Field field = instance.getClass().getDeclaredField("worker"); field.setAccessible(true);
        return (ExecutorService) field.get(instance);
    }
}
