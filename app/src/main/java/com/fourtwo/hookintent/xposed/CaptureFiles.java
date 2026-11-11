package com.fourtwo.hookintent.xposed;

import android.content.Context;
import android.os.Build;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/** Files belong to the injected application's own sandbox, separated by process. */
public final class CaptureFiles {
    private CaptureFiles() { }
    public static File directory(Context context, String purpose) throws IOException {
        String process = Build.VERSION.SDK_INT >= 28 ? android.app.Application.getProcessName()
                : readProcessName();
        File dir = new File(context.getFilesDir(), "jumpreplay/" + purpose + "/" + process.replaceAll("[^A-Za-z0-9_.-]", "_"));
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir);
        return dir;
    }
    private static String readProcessName() {
        try (java.io.FileInputStream in = new java.io.FileInputStream("/proc/self/cmdline")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            int value;
            while ((value = in.read()) > 0) out.write(value);
            return out.toString("UTF-8");
        } catch (IOException error) { return "process-" + android.os.Process.myPid(); }
    }
    public static void write(File target, byte[] bytes) throws IOException {
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream out = null;
        try {
            out = atomic.startWrite();
            out.write(bytes);
            out.getFD().sync();
            atomic.finishWrite(out);
            out = null;
            if (!target.isFile() || target.length() != bytes.length) throw new IOException("Cannot commit " + target);
            try (java.io.FileInputStream input = new java.io.FileInputStream(target)) {
                byte[] buffer = new byte[8192];
                int offset = 0, read;
                while ((read = input.read(buffer)) != -1) {
                    for (int index = 0; index < read; index++)
                        if (buffer[index] != bytes[offset + index]) throw new IOException("Capture commit verification failed");
                    offset += read;
                }
                if (offset != bytes.length) throw new IOException("Capture commit length mismatch");
            }
        } catch (IOException | RuntimeException error) {
            if (out != null) atomic.failWrite(out);
            throw error;
        }
    }

    /** Writes record bytes without first constructing a second, potentially huge batch buffer. */
    public static void writeJsonArray(File target, java.util.List<byte[]> records) throws IOException {
        AtomicFile atomic = new AtomicFile(target);
        FileOutputStream output = null;
        try {
            java.security.MessageDigest digest = sha256Digest();
            output = atomic.startWrite();
            output.write('['); digest.update((byte) '[');
            long length = 2;
            for (int index = 0; index < records.size(); index++) {
                if (index != 0) { output.write(','); digest.update((byte) ','); length++; }
                byte[] record = records.get(index);
                output.write(record); digest.update(record); length += record.length;
            }
            output.write(']'); digest.update((byte) ']');
            output.getFD().sync();
            atomic.finishWrite(output);
            output = null;
            if (!target.isFile() || target.length() != length) throw new IOException("Cannot commit capture batch " + target);
            java.security.MessageDigest stored = sha256Digest();
            try (java.io.FileInputStream input = new java.io.FileInputStream(target)) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = input.read(buffer)) != -1) stored.update(buffer, 0, count);
            }
            if (!java.security.MessageDigest.isEqual(digest.digest(), stored.digest()))
                throw new IOException("Capture batch commit verification failed");
        } catch (IOException | RuntimeException error) {
            if (output != null) atomic.failWrite(output);
            throw error;
        }
    }

    private static java.security.MessageDigest sha256Digest() {
        try { return java.security.MessageDigest.getInstance("SHA-256"); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
