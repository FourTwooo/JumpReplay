package com.fourtwo.hookintent.xposed;

import android.content.Context;
import android.content.Intent;
import android.os.Parcel;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/** Full Intent parcels on disk, with a fixed-size offset index for reading only the visible page. */
public final class IntentJournal {
    private final File dataFile, indexFile;
    private final ClassLoader loader;
    public IntentJournal(Context context) throws IOException { this(context, "floating-history"); }
    public IntentJournal(Context context, String purpose) throws IOException {
        File directory = CaptureFiles.directory(context, purpose);
        dataFile = new File(directory, "intents.bin"); indexFile = new File(directory, "intents.idx");
        loader = context.getClassLoader();
        try (RandomAccessFile ignored = new RandomAccessFile(dataFile, "rw")) { }
        // An interrupted index append is not a committed entry.
        try (RandomAccessFile index = new RandomAccessFile(indexFile, "rw")) {
            index.setLength(index.length() / 8 * 8);
        }
    }
    public synchronized void append(Intent intent) throws IOException {
        append(snapshot(intent));
    }
    /** Copy mutable extras before returning to the target app, without filesystem I/O. */
    public static byte[] snapshot(Intent intent) {
        Parcel parcel = Parcel.obtain();
        try {
            new Intent(intent).writeToParcel(parcel, 0);
            if (parcel.hasFileDescriptors()) throw new IllegalArgumentException("Intent contains file descriptors and cannot be journaled");
            return parcel.marshall();
        } finally { parcel.recycle(); }
    }
    public synchronized void append(byte[] bytes) throws IOException {
        try (RandomAccessFile data = new RandomAccessFile(dataFile, "rw"); RandomAccessFile index = new RandomAccessFile(indexFile, "rw")) {
            long offset = data.length(), previousIndex = index.length();
            try {
                data.seek(offset); data.writeInt(bytes.length); data.write(bytes); data.getFD().sync();
                index.seek(previousIndex); index.writeLong(offset); index.getFD().sync();
            } catch (IOException error) {
                try { index.setLength(previousIndex); index.getFD().sync(); data.setLength(offset); data.getFD().sync(); }
                catch (IOException rollbackError) { error.addSuppressed(rollbackError); }
                throw error;
            }
        }
    }
    public synchronized long count() { return indexFile.length() / 8; }
    public synchronized List<Intent> page(long start, int limit) throws IOException {
        List<Intent> result = new ArrayList<>();
        try (RandomAccessFile data = new RandomAccessFile(dataFile, "r"); RandomAccessFile index = new RandomAccessFile(indexFile, "r")) {
            long count = count();
            for (long number = Math.max(0, start); number < count && result.size() < limit; number++) {
                index.seek(number * 8); long offset = index.readLong();
                data.seek(offset); int length = data.readInt();
                if (length < 0 || length > data.length() - data.getFilePointer()) throw new IOException("Invalid Intent journal entry");
                byte[] bytes = new byte[length]; data.readFully(bytes);
                Parcel parcel = Parcel.obtain();
                try {
                    parcel.unmarshall(bytes, 0, bytes.length); parcel.setDataPosition(0);
                    Intent intent = Intent.CREATOR.createFromParcel(parcel); intent.setExtrasClassLoader(loader); result.add(intent);
                } finally { parcel.recycle(); }
            }
        }
        return result;
    }
    public synchronized void clear() throws IOException {
        try (RandomAccessFile index = new RandomAccessFile(indexFile, "rw"); RandomAccessFile data = new RandomAccessFile(dataFile, "rw")) {
            index.setLength(0); index.getFD().sync(); data.setLength(0); data.getFD().sync();
        }
    }
}
