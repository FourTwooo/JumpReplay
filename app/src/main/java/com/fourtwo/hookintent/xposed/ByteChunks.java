package com.fourtwo.hookintent.xposed;

import java.util.Arrays;

/** Byte-based framing: a multibyte UTF-8 character may span frames and is decoded after joining. */
public final class ByteChunks {
    public static final int PAYLOAD_BYTES = 48 * 1024;
    private ByteChunks() { }
    public static int count(long length) {
        if (length < 1 || length > (long) Integer.MAX_VALUE * PAYLOAD_BYTES)
            throw new IllegalArgumentException("Invalid payload length");
        return (int) ((length + PAYLOAD_BYTES - 1) / PAYLOAD_BYTES);
    }
    public static byte[] part(byte[] bytes, int index) {
        if (index < 0 || index >= count(bytes.length)) throw new IllegalArgumentException("Invalid frame");
        int start = Math.toIntExact((long) index * PAYLOAD_BYTES);
        return Arrays.copyOfRange(bytes, start, (int) Math.min(bytes.length, (long) start + PAYLOAD_BYTES));
    }
    public static String sha256(java.io.InputStream input) throws java.io.IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192]; int length;
            while ((length = input.read(buffer)) != -1) digest.update(buffer, 0, length);
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
