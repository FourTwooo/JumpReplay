package com.fourtwo.hookintent.service;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.os.Bundle;
import android.util.AtomicFile;
import android.util.Log;
import com.fourtwo.hookintent.data.CaptureRepository;
import com.fourtwo.hookintent.xposed.ByteChunks;
import com.fourtwo.hookintent.xposed.CaptureFiles;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** ACK means durable receipt; the final ACK waits for the repository's idempotent commit. */
final class CaptureFrameReceiver {
    private final Context context;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object lifecycle = new Object();
    private boolean closing;
    private int inFlight;
    private SQLiteDatabase completed;

    CaptureFrameReceiver(Context context) { this.context = context; }

    void receive(Bundle data, BiConsumer<Boolean, String> reply) {
        synchronized (lifecycle) {
            if (closing) { respond(reply, false, "Capture receiver is closing; retry on the next connection"); return; }
            inFlight++;
            worker.execute(() -> process(data, reply));
        }
    }

    private void process(Bundle data, BiConsumer<Boolean, String> reply) {
        boolean waitingForRepository = false;
        try {
            String id = data.getString("transferId");
            int frame = data.getInt("frame", -1), frames = data.getInt("frames", 0);
            long length = data.getLong("totalBytes", -1);
            byte[] payload = data.getByteArray("payload");
            String hash = data.getString("sha256");
            if (id == null || !id.matches("[0-9]{19}-[0-9a-fA-F-]{36}")
                    || hash == null || !hash.matches("[0-9a-f]{64}")
                    || frames != ByteChunks.count(length) || frame < 0 || frame >= frames
                    || payload == null || payload.length != Math.min(ByteChunks.PAYLOAD_BYTES, length - (long) frame * ByteChunks.PAYLOAD_BYTES))
                throw new IllegalArgumentException("Invalid capture frame");
            File root = new File(context.getFilesDir(), "capture-transport");
            if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create capture transport directory");
            if (completed == null) {
                completed = SQLiteDatabase.openOrCreateDatabase(new File(root, "completed.sqlite"), null);
                completed.execSQL("PRAGMA synchronous=FULL");
                completed.execSQL("CREATE TABLE IF NOT EXISTS completed (id TEXT PRIMARY KEY)");
            }
            try (Cursor cursor = completed.rawQuery("SELECT 1 FROM completed WHERE id=?", new String[]{id})) {
                if (cursor.moveToFirst()) { respond(reply, true, "Already committed"); return; }
            }
            File directory = new File(root, id);
            if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create transfer directory");
            File metadata = new File(directory, "metadata");
            byte[] expected = (length + ":" + frames + ":" + hash).getBytes(StandardCharsets.UTF_8);
            if (metadata.exists() && !java.util.Arrays.equals(readSmall(metadata), expected))
                throw new IllegalArgumentException("Transfer metadata changed");
            if (!metadata.exists()) CaptureFiles.write(metadata, expected);
            File part = new File(directory, frame + ".part");
            if (!part.exists() || !java.util.Arrays.equals(readSmall(part), payload)) CaptureFiles.write(part, payload);
            if (frame != frames - 1) { respond(reply, true, "Frame persisted"); return; }
            File assembled = assemble(directory, frames, length, hash);
            AtomicBoolean settled = new AtomicBoolean();
            waitingForRepository = true;
            try {
                CaptureRepository.get(context).appendFile(assembled, id,
                        () -> completeRepository(settled, () -> {
                            completed.execSQL("INSERT OR IGNORE INTO completed(id) VALUES(?)", new Object[]{id});
                            respond(reply, true, "Capture committed");
                            removeTransfer(directory);
                        }),
                        error -> completeRepository(settled, () -> respond(reply, false, error.toString())));
            } catch (RuntimeException error) {
                completeRepository(settled, () -> respond(reply, false, error.toString()));
            }
        } catch (Exception error) {
            respond(reply, false, error.toString());
        } finally {
            if (!waitingForRepository) finished();
        }
    }

    private interface Completion { void run() throws Exception; }

    private void completeRepository(AtomicBoolean settled, Completion completion) {
        if (!settled.compareAndSet(false, true)) return;
        // close() retains this executor and database until every accepted append callback settles.
        worker.execute(() -> {
            try { completion.run(); }
            catch (Exception error) { Log.w("CaptureTransport", "Cannot finish capture receipt", error); }
            finally { finished(); }
        });
    }

    private static File assemble(File directory, int frames, long length, String hash) throws IOException {
        File target = new File(directory, "payload.complete");
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream output = null;
        try {
            output = atomic.startWrite();
            byte[] buffer = new byte[8192];
            long written = 0;
            for (int index = 0; index < frames; index++) {
                File fragment = new File(directory, index + ".part");
                if (!fragment.isFile()) throw new IOException("Missing capture frame " + index);
                try (FileInputStream input = new FileInputStream(fragment)) {
                    int count;
                    while ((count = input.read(buffer)) != -1) { output.write(buffer, 0, count); written += count; }
                }
            }
            if (written != length) throw new IOException("Capture length mismatch");
            output.getFD().sync();
            atomic.finishWrite(output);
            output = null;
            if (!target.isFile() || target.length() != length) throw new IOException("Cannot commit assembled capture");
            try (FileInputStream input = new FileInputStream(target)) {
                if (!hash.equals(ByteChunks.sha256(input))) throw new IOException("Capture checksum mismatch");
            }
            return target;
        } catch (IOException | RuntimeException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
    }

    private static byte[] readSmall(File file) throws IOException {
        if (file.length() > ByteChunks.PAYLOAD_BYTES) throw new IOException("Unexpected frame file length");
        try (FileInputStream input = new FileInputStream(file); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
            return output.toByteArray();
        }
    }

    private static void removeTransfer(File directory) {
        File[] files = directory.listFiles();
        if (files != null) for (File file : files) if (!file.delete()) Log.w("CaptureTransport", "Cannot remove " + file);
        if (!directory.delete()) Log.w("CaptureTransport", "Cannot remove " + directory);
    }

    private static void respond(BiConsumer<Boolean, String> reply, boolean ok, String detail) {
        try { reply.accept(ok, detail); }
        catch (RuntimeException error) { Log.w("CaptureTransport", "Capture reply failed; sender will retry", error); }
    }

    private void finished() {
        synchronized (lifecycle) {
            inFlight--;
            if (closing && inFlight == 0) shutdown();
        }
    }

    private void shutdown() {
        worker.execute(() -> { if (completed != null) { completed.close(); completed = null; } });
        worker.shutdown();
    }

    void close() {
        synchronized (lifecycle) {
            if (closing) return;
            closing = true;
            if (inFlight == 0) shutdown();
        }
    }
}
