package com.fourtwo.hookintent.data;

import android.app.Application;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class CaptureRepositoryTest {
    private Context context;
    private CaptureRepository repository;
    private ExecutorService worker;

    @Before public void create() throws Exception {
        context = RuntimeEnvironment.getApplication();
        open();
    }

    private void open() throws Exception {
        Constructor<CaptureRepository> constructor = CaptureRepository.class.getDeclaredConstructor(Context.class);
        constructor.setAccessible(true);
        repository = constructor.newInstance(context);
        worker = (ExecutorService) field("worker");
        idle();
    }

    private Object field(String name) throws Exception {
        Field field = CaptureRepository.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(repository);
    }

    private void idle() throws Exception {
        worker.submit(() -> {}).get(30, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        worker.submit(() -> {}).get(30, TimeUnit.SECONDS);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @After public void close() throws Exception {
        ((Handler) field("main")).removeCallbacksAndMessages(null);
        worker.shutdown();
        assertTrue(worker.awaitTermination(30, TimeUnit.SECONDS));
        ((SQLiteOpenHelper) field("store")).close();
    }

    private Bundle batch(String id, String payload) {
        Bundle data = new Bundle();
        data.putString("capture_transport_id", id);
        data.putString("batch_data_binder", payload);
        return data;
    }

    private String record(String text) throws Exception {
        return new JSONArray().put(new JSONObject().put("category", "Custom")
                .put("title", "record").put("data", text)
                .put("time", "2026-11-11 10:00:00 AM").put("packageName", "com.example.capture")).toString();
    }

    private void append(Bundle data) throws Exception {
        CountDownLatch committed = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        repository.append(data, committed::countDown, error -> { failure.set(error); committed.countDown(); });
        assertTrue(committed.await(30, TimeUnit.SECONDS));
        if (failure.get() != null) throw failure.get();
        idle();
    }

    private int count(String table) {
        try (SQLiteDatabase db = context.openOrCreateDatabase("capture_archive.db", 0, null);
             Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM " + table, null)) {
            assertTrue(cursor.moveToFirst());
            return cursor.getInt(0);
        }
    }

    @Test public void persistedReceiptIsIdempotentAcrossRetryAndClear() throws Exception {
        Bundle data = batch("same-transfer", record("keep+%26"));
        append(data);
        append(data);
        assertEquals(1, count("captures"));
        repository.clear(); idle();
        assertEquals(0, count("captures"));
        append(data);
        assertEquals(0, count("captures"));
    }

    @Test public void historyLoadsWithoutHomeObserverAndSearchesBeyondPage() throws Exception {
        JSONArray records = new JSONArray();
        for (int index = 0; index < 205; index++) {
            records.put(new JSONArray(record(index == 0 ? "old-needle" : "entry" + index)).getJSONObject(0));
        }
        append(batch("history", records.toString()));
        assertEquals(205, count("captures"));
        repository.refresh(); idle();
        assertEquals(200, repository.getItems().getValue().size());
        assertEquals(Boolean.TRUE, repository.getHasMore().getValue());
        repository.setQuery("old-needle"); idle();
        assertEquals(1, repository.getItems().getValue().size());
        repository.setQuery("OLD-(needle|other)", true); idle();
        assertEquals(1, repository.getItems().getValue().size());
        repository.setQuery("entry\\d+", true); idle();
        assertEquals(200, repository.getItems().getValue().size());
        assertEquals(Boolean.TRUE, repository.getHasMore().getValue());
        repository.loadMore(); idle();
        assertEquals(204, repository.getItems().getValue().size());
        assertEquals(Boolean.FALSE, repository.getHasMore().getValue());
        repository.setQuery("[", true); idle();
        assertEquals(204, repository.getItems().getValue().size());
        repository.setQuery("entry%"); idle();
        assertTrue(repository.getItems().getValue().isEmpty());
        repository.setQuery(""); repository.loadMore(); idle();
        assertEquals(205, repository.getItems().getValue().size());
        repository.remove(repository.getItems().getValue().get(0)); idle();
        assertEquals(204, count("captures"));
        close(); open(); repository.refresh(); idle();
        assertEquals(204, count("captures"));
        assertEquals(200, repository.getItems().getValue().size());
    }

    @Test public void largeRecordAvoidsCursorWindowAndFileReceiptIsDurable() throws Exception {
        char[] characters = new char[2200000]; java.util.Arrays.fill(characters, 'x');
        String large = new String(characters);
        File source = new File(context.getCacheDir(), "large-batch.json");
        Files.write(source.toPath(), record(large).getBytes(StandardCharsets.UTF_8));
        CountDownLatch committed = new CountDownLatch(1);
        AtomicReference<Exception> failure = new AtomicReference<>();
        repository.appendFile(source, "large-transfer", committed::countDown,
                error -> { failure.set(error); committed.countDown(); });
        assertTrue(committed.await(30, TimeUnit.SECONDS));
        assertNull(failure.get()); idle();
        assertEquals(1, count("captures"));
        repository.refresh(); idle();
        assertEquals(large, repository.getItems().getValue().get(0).getItem_data());
        assertTrue(source.delete());
    }

    @Test public void malformedMemberIsRetainedAndClearRemovesQuarantine() throws Exception {
        JSONArray data = new JSONArray().put(123).put(new JSONArray(record("valid")).getJSONObject(0));
        append(batch("bad-member", data.toString()));
        assertEquals(1, count("captures"));
        try (SQLiteDatabase db = context.openOrCreateDatabase("capture_archive.db", 0, null);
             Cursor cursor = db.rawQuery("SELECT payload FROM incoming", null)) {
            assertTrue(cursor.moveToFirst());
            assertTrue(new File(cursor.getString(0)).isFile());
        }
        repository.clear(); idle();
        File[] pending = new File(context.getFilesDir(), "capture_archive/pending").listFiles();
        assertNotNull(pending); assertEquals(0, pending.length);
    }

    @Test public void failedTransactionDoesNotAdvanceDeduplication() throws Exception {
        try (SQLiteDatabase db = context.openOrCreateDatabase("capture_archive.db", 0, null)) {
            db.execSQL("CREATE TRIGGER fail_captures BEFORE INSERT ON captures BEGIN SELECT RAISE(ABORT,'simulated'); END");
        }
        JSONObject intent = new JSONObject().put("category", "Intent").put("componentClassName", "com.example/.Main")
                .put("component", "com.example.Main").put("uri", "intent:#Intent;component=com.example/.Main;end")
                .put("packageName", "com.source").put("time", "2026-11-11 10:00:00 AM");
        append(batch("retry-on-disk", new JSONArray().put(intent).toString()));
        assertEquals(0, count("captures"));
        try (SQLiteDatabase db = context.openOrCreateDatabase("capture_archive.db", 0, null)) {
            db.execSQL("DROP TRIGGER fail_captures");
        }
        repository.refresh(); idle();
        assertEquals(1, count("captures"));
    }
}
