package com.fourtwo.hookintent.xposed;

import android.content.Context;
import android.os.Bundle;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.service.MessengerClient;
import com.fourtwo.hookintent.service.MessengerService;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Queue quickly on a Hook thread; serialize/persist short batches before sending any frame. */
public final class DurableCaptureOutbox {
    private static final int BATCH_RECORDS = 64;
    private static final long BATCH_BYTES = 256L * 1024;
    private final File directory;
    private final MessengerClient client;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private final java.util.function.Consumer<Exception> errors;
    private final ConcurrentLinkedQueue<Entry> queued = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean flushScheduled = new AtomicBoolean();
    private File active;
    private String activeHash;
    private int frame;
    private boolean waiting, enabled;
    private Boolean initialCapture;
    private long lastTimestamp;

    private static final class Entry {
        final Bundle record;
        final boolean pending;
        byte[] serialized;
        Entry(Bundle record, boolean pending) { this.record = record; this.pending = pending; }
    }

    public DurableCaptureOutbox(Context context, MessengerClient client, java.util.function.Consumer<Exception> errors) throws IOException {
        directory = CaptureFiles.directory(context, "outbox");
        this.client = client;
        this.errors = errors;
        worker.scheduleWithFixedDelay(this::pump, 0, 1, TimeUnit.SECONDS);
    }

    public void add(Bundle record, boolean pendingState) throws IOException {
        if (record == null) throw new IOException("Missing capture record");
        queued.add(new Entry(new Bundle(record), pendingState));
        scheduleFlush();
    }

    public void resolveInitialState(boolean capture) throws IOException {
        worker.execute(() -> {
            if (initialCapture == null) initialCapture = capture;
            try { resolvePendingFiles(); flushQueued(); pump(); }
            catch (Exception error) { report(error); }
        });
    }

    public void setConnected(boolean value) { worker.execute(() -> { enabled = value; if (value) pump(); }); }

    private void scheduleFlush() {
        if (flushScheduled.compareAndSet(false, true)) worker.schedule(() -> {
            flushScheduled.set(false);
            try { flushQueued(); pump(); }
            catch (Exception error) { report(error); }
        }, 50, TimeUnit.MILLISECONDS);
    }

    /** Only this worker consumes the queue; a failed write leaves every entry in place for retry. */
    private void flushQueued() throws IOException {
        for (int batchNumber = 0; batchNumber < 8; batchNumber++) {
            Entry first = queued.peek();
            if (first == null) return;
            List<byte[]> records = new ArrayList<>();
            long batchBytes = 2;
            for (Entry entry : queued) {
                if (entry.pending != first.pending || records.size() >= BATCH_RECORDS) break;
                if (entry.serialized == null) {
                    entry.serialized = new org.json.JSONObject(JsonHandler.toMap(entry.record))
                            .toString().getBytes(StandardCharsets.UTF_8);
                }
                long nextBytes = batchBytes + (records.isEmpty() ? 0 : 1) + entry.serialized.length;
                if (!records.isEmpty() && nextBytes > BATCH_BYTES) break;
                records.add(entry.serialized);
                batchBytes = nextBytes;
                // A large single event is complete and uncropped, but never multiplied into a 64-event buffer.
                if (batchBytes >= BATCH_BYTES) break;
            }
            if (first.pending && Boolean.FALSE.equals(initialCapture)) {
                // The first handshake confirmed that capture was off for these startup events.
                for (int index = 0; index < records.size(); index++) queued.poll();
                continue;
            }
            boolean pending = first.pending && initialCapture == null;
            lastTimestamp = Math.max(System.currentTimeMillis(), lastTimestamp + 1);
            String id = String.format(java.util.Locale.ROOT, "%019d", lastTimestamp) + "-" + UUID.randomUUID();
            CaptureFiles.writeJsonArray(new File(directory, id + (pending ? ".pending" : ".json")), records);
            for (int index = 0; index < records.size(); index++) queued.poll();
        }
        if (!queued.isEmpty()) scheduleFlush();
    }

    private void resolvePendingFiles() throws IOException {
        if (initialCapture == null) return;
        File[] files = directory.listFiles((dir, name) -> name.endsWith(".pending"));
        if (files == null) throw new IOException("Cannot list capture outbox");
        for (File file : files) {
            if (initialCapture) {
                File target = new File(directory, file.getName().replace(".pending", ".json"));
                if (!file.renameTo(target)) throw new IOException("Cannot confirm pending capture " + file);
            } else if (!file.delete()) throw new IOException("Cannot discard capture recorded while capture was off");
        }
    }

    private void pump() {
        try {
            resolvePendingFiles();
            flushQueued();
            if (!enabled || waiting) return;
            if (active == null) {
                File[] files = directory.listFiles((dir, name) -> name.endsWith(".json"));
                if (files == null) throw new IOException("Cannot list capture outbox");
                if (files.length == 0) return;
                Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
                active = files[0]; frame = 0;
                try (java.io.FileInputStream input = new java.io.FileInputStream(active)) { activeHash = ByteChunks.sha256(input); }
            }
            long length = active.length();
            int total = ByteChunks.count(length);
            byte[] bytes = new byte[(int) Math.min(ByteChunks.PAYLOAD_BYTES, length - (long) frame * ByteChunks.PAYLOAD_BYTES)];
            try (RandomAccessFile input = new RandomAccessFile(active, "r")) {
                input.seek((long) frame * ByteChunks.PAYLOAD_BYTES); input.readFully(bytes);
            }
            Bundle data = new Bundle();
            data.putString("transferId", active.getName().replace(".json", ""));
            data.putInt("frame", frame); data.putInt("frames", total);
            data.putLong("totalBytes", length); data.putByteArray("payload", bytes);
            data.putString("sha256", activeHash);
            waiting = true;
            client.sendMessageAsync(MessengerService.MSG_SEND_FRAME, data, true, new MessengerClient.ResultCallback() {
                @Override public void onResult(Bundle result) { worker.execute(() -> {
                    waiting = false;
                    if (result.getInt("resultCode") != 1) {
                        frame = 0;
                        report(new IOException("Capture frame rejected: " + result.getString("resultData")));
                        return;
                    }
                    frame++;
                    if (frame == total) {
                        if (!active.delete()) report(new IOException("Cannot remove confirmed capture " + active));
                        active = null;
                    }
                    pump();
                }); }
                @Override public void onError(Exception error) { worker.execute(() -> {
                    waiting = false;
                    report(error); // The exact same frame remains on disk for retry.
                }); }
            });
        } catch (Exception error) { waiting = false; report(error); }
    }

    private void report(Exception error) {
        try { errors.accept(error); }
        catch (RuntimeException callbackError) { android.util.Log.w("CaptureOutbox", "Error callback failed", callbackError); }
    }
}
