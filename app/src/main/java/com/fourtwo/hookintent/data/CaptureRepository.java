package com.fourtwo.hookintent.data;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.AtomicFile;
import android.util.JsonReader;
import android.util.JsonToken;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.utils.AppInfoHelper;
import com.fourtwo.hookintent.utils.DataProcessor;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns capture ingestion independently of any Activity or Fragment. */
public final class CaptureRepository {
    private static final String TAG = "CaptureRepository";
    private static final int PAGE_SIZE = 200;
    private static volatile CaptureRepository instance;

    public static CaptureRepository get(Context context) {
        if (instance == null) {
            synchronized (CaptureRepository.class) {
                if (instance == null) instance = new CaptureRepository(context.getApplicationContext());
            }
        }
        return instance;
    }

    private final Context context;
    private final Store store;
    private final DataProcessor processor;
    private final File archiveDirectory;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final MutableLiveData<List<ItemData>> items = new MutableLiveData<List<ItemData>>(Collections.emptyList()) {
        @Override protected void onActive() { worker.execute(CaptureRepository.this::publishSnapshot); }
    };
    private final MutableLiveData<Boolean> hasMore = new MutableLiveData<>(false);
    private final MutableLiveData<String> errors = new MutableLiveData<>();
    private final AtomicBoolean publishScheduled = new AtomicBoolean();
    private final Runnable publish = () -> {
        if (items.hasActiveObservers()) worker.execute(() -> {
            publishScheduled.set(false);
            publishSnapshot();
        });
        else publishScheduled.set(false);
    };
    private int visibleLimit = PAGE_SIZE;
    private String query = "";
    private java.util.regex.Pattern searchPattern;
    private boolean searchAborted;
    private final java.util.concurrent.atomic.AtomicInteger searchGeneration = new java.util.concurrent.atomic.AtomicInteger();

    private CaptureRepository(Context context) {
        this.context = context;
        archiveDirectory = new File(context.getFilesDir(), "capture_archive");
        store = new Store(context);
        processor = new DataProcessor(context);
        worker.execute(() -> {
            reloadFilters();
            drainPending();
            publishSnapshot();
        });
    }

    public LiveData<List<ItemData>> getItems() { return items; }
    public LiveData<Boolean> getHasMore() { return hasMore; }
    public LiveData<String> getErrors() { return errors; }

    public void latestDetail(java.util.function.BiConsumer<String, Exception> callback) {
        worker.execute(() -> {
            String token = null; Exception error = null;
            try (Cursor cursor = store.getReadableDatabase().query("captures", new String[]{"data"},
                    null, null, null, null, "id DESC", "1")) {
                if (cursor.moveToFirst()) token = com.fourtwo.hookintent.base.DetailPayloadStore.save(context, decode(readArchive(cursor.getString(0))));
            } catch (Exception failure) { error = failure; }
            String value = token; Exception failure = error;
            main.post(() -> callback.accept(value, failure));
        });
    }

    public void append(Bundle message) { append(message, null, null); }

    /** The success callback runs only after the raw payload has been committed to disk. */
    public void append(Bundle message, Runnable onPersisted, Consumer<Exception> onError) {
        try {
            if (message == null) throw new IllegalArgumentException("Empty capture message");
            String payload = message.getString("batch_data_binder");
            if (payload == null) throw new IllegalArgumentException("Missing capture payload");
            String transportId = message.getString("capture_transport_id");
            worker.execute(() -> persistIncoming(payload, null, transportId, onPersisted, onError));
        } catch (Exception error) { fail(error, onError); }
    }

    /** Receives a reassembled file without copying a large payload through Binder or byte arrays. */
    public void appendFile(File source, String transportId, Runnable onPersisted, Consumer<Exception> onError) {
        worker.execute(() -> persistIncoming(null, source, transportId, onPersisted, onError));
    }

    private void persistIncoming(String payload, File source, String suppliedId,
            Runnable onPersisted, Consumer<Exception> onError) {
        try {
            String transportId = suppliedId == null || suppliedId.isEmpty() ? UUID.randomUUID().toString() : suppliedId;
            SQLiteDatabase db = store.getWritableDatabase();
            boolean exists;
            try (Cursor cursor = db.query("incoming", new String[]{"id"}, "transport_id=?",
                    new String[]{transportId}, null, null, null)) {
                exists = cursor.moveToFirst();
            }
            if (!exists) {
                File payloadFile;
                if (source != null) {
                    validateContainer(source);
                    try (InputStream stream = new FileInputStream(source)) { payloadFile = writeArchive("pending", stream); }
                } else {
                    new JSONArray(payload);
                    payloadFile = writeArchive("pending", payload);
                }
                db.beginTransaction();
                try {
                    ContentValues values = new ContentValues();
                    values.put("transport_id", transportId);
                    values.put("payload", payloadFile.getAbsolutePath());
                    values.put("processed", 0);
                    db.insertOrThrow("incoming", null, values);
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
            }
            if (onPersisted != null) {
                try { onPersisted.run(); }
                catch (RuntimeException callbackError) { Log.w(TAG, "Capture ACK failed", callbackError); }
            }
            drainPending();
            schedulePublish();
        } catch (Exception error) { fail(error, onError); }
    }

    private void validateContainer(File file) throws IOException {
        try (JsonReader reader = new JsonReader(new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8))) {
            reader.beginArray();
            while (reader.hasNext()) reader.skipValue();
            reader.endArray();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Unexpected capture data after batch");
        }
    }

    public void setQuery(String value) {
        setQuery(value, false);
    }

    public void setQuery(String value, boolean regex) {
        final String next = value == null ? "" : value.trim();
        final int generation = searchGeneration.incrementAndGet();
        worker.execute(() -> {
            if (generation != searchGeneration.get()) return;
            java.util.regex.Pattern compiled;
            try {
                compiled = regex && !next.isEmpty() ? java.util.regex.Pattern.compile(next,
                        java.util.regex.Pattern.CASE_INSENSITIVE | java.util.regex.Pattern.UNICODE_CASE) : null;
            } catch (java.util.regex.PatternSyntaxException error) { fail(error, null); return; }
            if (!query.equals(next) || (searchPattern != null) != (compiled != null)) visibleLimit = PAGE_SIZE;
            query = next;
            searchPattern = compiled;
            searchAborted = false;
            publishSnapshot();
        });
    }

    public void loadMore() {
        worker.execute(() -> {
            visibleLimit = (int) Math.min(Integer.MAX_VALUE - 1L, (long) visibleLimit + PAGE_SIZE);
            publishSnapshot();
        });
    }

    public void refresh() {
        worker.execute(() -> {
            reloadFilters();
            drainPending();
            publishSnapshot();
        });
    }

    public void remove(ItemData item) {
        if (item == null || item.getCaptureId() <= 0) return;
        long id = item.getCaptureId();
        worker.execute(() -> {
            try {
                SQLiteDatabase db = store.getWritableDatabase();
                String path = null;
                try (Cursor cursor = db.query("captures", new String[]{"data"}, "id=?",
                        new String[]{Long.toString(id)}, null, null, null)) {
                    if (cursor.moveToFirst()) path = cursor.getString(0);
                }
                db.delete("captures", "id=?", new String[]{Long.toString(id)});
                deleteArchive(path);
                publishSnapshot();
            } catch (Exception e) { fail(e, null); }
        });
    }

    public void clear() {
        worker.execute(() -> {
            try {
                SQLiteDatabase db = store.getWritableDatabase();
                List<String> files = new ArrayList<>();
                try (Cursor cursor = db.query("captures", new String[]{"data"}, null, null, null, null, null)) {
                    while (cursor.moveToNext()) files.add(cursor.getString(0));
                }
                try (Cursor cursor = db.query("incoming", new String[]{"payload"}, "payload IS NOT NULL",
                        null, null, null, null)) {
                    while (cursor.moveToNext()) files.add(cursor.getString(0));
                }
                db.beginTransaction();
                try {
                    db.delete("captures", null, null);
                    // Keep transport IDs so an ACK retry cannot resurrect deliberately cleared records.
                    db.execSQL("UPDATE incoming SET processed=1,payload=NULL");
                    db.setTransactionSuccessful();
                } finally { db.endTransaction(); }
                for (String path : files) deleteArchive(path);
                for (String folder : new String[]{"pending", "records"}) {
                    File[] leftovers = new File(archiveDirectory, folder).listFiles();
                    if (leftovers != null) for (File file : leftovers) deleteArchive(file.getAbsolutePath());
                }
                visibleLimit = PAGE_SIZE;
                publishSnapshot();
            } catch (Exception e) { fail(e, null); }
        });
    }

    private void reloadFilters() {
        try { processor.setJsonData(new JsonHandler().readJsonFromFile(context)); }
        catch (Exception e) { fail(e, null); }
    }

    private void drainPending() {
        try {
            SQLiteDatabase db = store.getWritableDatabase();
            while (true) {
                long id;
                String payloadPath;
                try (Cursor cursor = db.query("incoming", new String[]{"id", "payload"}, "processed=0",
                        null, null, null, "id ASC", "1")) {
                    if (!cursor.moveToFirst()) return;
                    id = cursor.getLong(0);
                    payloadPath = cursor.getString(1);
                }
                Object checkpoint = processor.checkpointDeduplication();
                List<String> uncommittedFiles = new ArrayList<>();
                boolean committed = false;
                try {
                    Bundle batch = new Bundle();
                    batch.putString("batch_data_binder", readArchive(payloadPath));
                    List<ItemData> converted = new ArrayList<>();
                    processor.processReceivedData(batch, converted::add);
                    String processingError = processor.getLastError();
                    db.beginTransaction();
                    try {
                        for (ItemData item : converted) {
                            File recordFile = writeArchive("records", encode(item));
                            uncommittedFiles.add(recordFile.getAbsolutePath());
                            ContentValues values = new ContentValues();
                            values.put("source_id", id);
                            values.put("data", recordFile.getAbsolutePath());
                            values.put("search", (item.getAppName() + "\n" + item.getItem_from() + "\n"
                                    + item.getItem_data()).toLowerCase(Locale.ROOT));
                            db.insertOrThrow("captures", null, values);
                        }
                        ContentValues completed = new ContentValues();
                        completed.put("processed", 1);
                        if (processingError == null) completed.putNull("payload");
                        db.update("incoming", completed, "id=?", new String[]{Long.toString(id)});
                        db.setTransactionSuccessful();
                    } finally { db.endTransaction(); }
                    committed = true;
                    com.fourtwo.hookintent.manager.CaptureMonitor.committed(converted);
                    if (processingError == null) deleteArchive(payloadPath);
                    else errors.postValue(processingError + "（原始批次已保留）");
                } finally {
                    if (!committed) {
                        processor.restoreDeduplication(checkpoint);
                        for (String path : uncommittedFiles) deleteArchive(path);
                    }
                }
            }
        } catch (Exception e) {
            // Uncommitted raw batches remain in incoming and are retried on refresh/restart.
            fail(e, null);
        }
    }

    private void schedulePublish() {
        if (publishScheduled.compareAndSet(false, true)) main.postDelayed(publish, 100L);
    }

    private void publishSnapshot() {
        if (searchAborted) return;
        try {
            SQLiteDatabase db = store.getReadableDatabase();
            boolean regex = searchPattern != null;
            String selection = query.isEmpty() || regex ? null : "search LIKE ? ESCAPE '\\'";
            String escaped = query.toLowerCase(Locale.ROOT).replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            String[] args = selection == null ? null : new String[]{"%" + escaped + "%"};
            List<ItemData> page = new ArrayList<>();
            boolean more = false;
            int generation = searchGeneration.get();
            try (Cursor cursor = db.query("captures", new String[]{"id", "data"}, selection, args,
                    null, null, "id DESC", regex ? null : Integer.toString(visibleLimit + 1))) {
                while (cursor.moveToNext()) {
                    if (generation != searchGeneration.get()) return;
                    if (!regex && page.size() == visibleLimit) { more = true; break; }
                    try {
                        ItemData item = decode(readArchive(cursor.getString(1)));
                        if (regex && !SearchMatch.find(searchPattern, item.getAppName() + "\n"
                                + item.getItem_from() + "\n" + item.getItem_data(),
                                System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(200))) continue;
                        if (page.size() == visibleLimit) { more = true; break; }
                        item.setCaptureId(cursor.getLong(0));
                        page.add(item);
                    } catch (SearchMatch.TooSlow error) { throw error; }
                    catch (Exception badRecord) { fail(badRecord, null); }
                }
            }
            Collections.reverse(page);
            items.postValue(Collections.unmodifiableList(page));
            hasMore.postValue(more);
        } catch (SearchMatch.TooSlow error) {
            searchAborted = true;
            fail(error, null);
        } catch (Exception e) { fail(e, null); }
    }

    private static String encode(ItemData item) throws Exception {
        JSONObject value = new JSONObject();
        value.put("appName", item.getAppName());
        value.put("from", item.getItem_from());
        value.put("data", item.getItem_data());
        value.put("time", item.getTimestamp());
        value.put("size", item.getDataSize());
        value.put("category", item.getCategory());
        value.put("stack", item.getStackTrace());
        value.put("uri", item.getUri());
        Bundle bundle = item.getAppBundle();
        value.put("bundle", new JSONObject(JsonHandler.toMap(bundle == null ? new Bundle() : bundle)));
        return value.toString();
    }

    private File writeArchive(String directory, String value) throws IOException {
        try (InputStream stream = new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8))) {
            return writeArchive(directory, stream);
        }
    }

    private File writeArchive(String directory, InputStream source) throws IOException {
        File folder = new File(archiveDirectory, directory);
        if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Cannot create capture archive");
        File target = new File(folder, UUID.randomUUID() + ".json");
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream output = null;
        long bytes = 0;
        try {
            output = atomic.startWrite();
            byte[] buffer = new byte[8192];
            int count;
            while ((count = source.read(buffer)) != -1) { output.write(buffer, 0, count); bytes += count; }
            output.getFD().sync();
            atomic.finishWrite(output);
            if (!target.isFile() || target.length() != bytes) throw new IOException("Capture archive commit failed");
            return target;
        } catch (IOException | RuntimeException e) {
            if (output != null) atomic.failWrite(output);
            throw e;
        }
    }

    private String readArchive(String path) throws IOException {
        if (path == null) throw new IOException("Missing archived capture payload");
        File target = new File(path);
        if (!target.getCanonicalPath().startsWith(archiveDirectory.getCanonicalPath() + File.separator)) {
            throw new IOException("Capture path is outside archive");
        }
        return new String(new AtomicFile(target).readFully(), StandardCharsets.UTF_8);
    }

    private void deleteArchive(String path) {
        if (path == null) return;
        try {
            File target = new File(path);
            if (target.getCanonicalPath().startsWith(archiveDirectory.getCanonicalPath() + File.separator)) {
                new AtomicFile(target).delete();
            }
        } catch (IOException e) { Log.w(TAG, "Cannot remove archived capture", e); }
    }

    private ItemData decode(String json) throws Exception {
        JSONObject value = new JSONObject(json);
        Bundle bundle = JsonHandler.toBundle(value.getJSONObject("bundle"));
        String pkg = bundle.getString("componentPackageName");
        if (pkg == null || pkg.isEmpty()) pkg = bundle.getString("componentClassName");
        if (pkg == null || pkg.isEmpty() || "null".equals(pkg)) pkg = bundle.getString("packageName");
        return new ItemData(AppInfoHelper.getAppInfo(context, pkg).getAppIcon(), value.optString("appName"),
                value.optString("from"), value.optString("data"), value.optString("time"),
                value.optString("size"), bundle, value.optString("category"), value.optString("stack"),
                value.optString("uri"));
    }

    private void fail(Exception error, Consumer<Exception> callback) {
        Log.e(TAG, "Capture storage failed", error);
        errors.postValue("捕获数据处理失败：" + error.getMessage());
        if (callback != null) {
            try { callback.accept(error); }
            catch (RuntimeException callbackError) { Log.w(TAG, "Error callback failed", callbackError); }
        }
    }

    private static final class Store extends SQLiteOpenHelper {
        Store(Context context) { super(context, "capture_archive.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE incoming(id INTEGER PRIMARY KEY AUTOINCREMENT,"
                    + "transport_id TEXT NOT NULL UNIQUE,payload TEXT,processed INTEGER NOT NULL DEFAULT 0)");
            db.execSQL("CREATE INDEX incoming_pending ON incoming(processed,id)");
            db.execSQL("CREATE TABLE captures(id INTEGER PRIMARY KEY AUTOINCREMENT,source_id INTEGER NOT NULL,"
                    + "data TEXT NOT NULL,search TEXT NOT NULL)");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("Unsupported capture archive version " + newVersion);
        }
    }
}
