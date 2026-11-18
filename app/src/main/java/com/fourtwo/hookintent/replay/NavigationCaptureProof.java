package com.fourtwo.hookintent.replay;

import android.content.Context;
import android.os.Bundle;
import android.util.AtomicFile;
import android.util.Base64;

import com.fourtwo.hookintent.xposed.CaptureFiles;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.math.BigDecimal;
import java.nio.channels.FileLock;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** Authenticate original replay descriptors across process restarts without trusting capture IPC. */
final class NavigationCaptureProof {
    private static final String PROOF = "capture_proof";
    private static final int KEY_SIZE = 32;
    private static final Map<File, byte[]> keys = new HashMap<>();

    private NavigationCaptureProof() {}

    static Bundle seal(Context context, Bundle identity, Bundle descriptor) throws Exception {
        Bundle result = copy(descriptor, true);
        result.remove(PROOF);
        result.putString(PROOF, Base64.encodeToString(proof(context, identity, result, true), Base64.NO_WRAP));
        return result;
    }

    static boolean hasProof(Bundle descriptor) {
        String value = descriptor.getString(PROOF);
        return value != null && value.length() == 44;
    }

    static Bundle verify(Context context, Bundle identity, Bundle descriptor) throws Exception {
        Bundle result = copy(descriptor, true);
        if (!hasProof(result)) throw new SecurityException("缺少原导航调用的采集凭据");
        String encoded = result.getString(PROOF);
        result.remove(PROOF);
        byte[] supplied = Base64.decode(encoded, Base64.NO_WRAP);
        if (!MessageDigest.isEqual(supplied, proof(context, identity, result, false))) {
            throw new SecurityException("原导航调用凭据无效，请重新捕获");
        }
        return result;
    }

    static Bundle copy(Bundle source, boolean descriptor) {
        return checkedCopy(source, descriptor, new int[]{64 * 1024});
    }

    private static Bundle checkedCopy(Bundle source, boolean descriptor, int[] remaining) {
        if (source == null || source.size() > 128) throw new IllegalArgumentException("导航参数无效或过大");
        Bundle copy = new Bundle();
        for (String name : source.keySet()) {
            if (name == null || name.length() > 256) throw new IllegalArgumentException("导航参数名无效");
            remaining[0] -= name.length();
            Object value = source.get(name);
            if (value == null) copy.putString(name, null);
            else if (value instanceof String) {
                String text = (String)value;
                if (text.length() > 8_192) throw new IllegalArgumentException("导航参数过长");
                remaining[0] -= text.length();
                copy.putString(name, text);
            } else if (value instanceof Integer) copy.putInt(name, (Integer)value);
            else if (value instanceof Long) copy.putLong(name, (Long)value);
            else if (value instanceof Boolean) copy.putBoolean(name, (Boolean)value);
            else if (value instanceof Double && Double.isFinite((Double)value)) copy.putDouble(name, (Double)value);
            else if (value instanceof Float && Float.isFinite((Float)value)) copy.putFloat(name, (Float)value);
            else if (value instanceof Short) copy.putShort(name, (Short)value);
            else if (value instanceof Byte) copy.putByte(name, (Byte)value);
            else if (value instanceof Bundle && descriptor
                    && ("arguments".equals(name) || "argument_types".equals(name))) {
                copy.putBundle(name, checkedCopy((Bundle)value, false, remaining));
            } else throw new IllegalArgumentException("导航记录仅支持基本类型参数");
            if (remaining[0] < 0) throw new IllegalArgumentException("导航参数总量过大");
        }
        return copy;
    }

    private static byte[] proof(Context context, Bundle identity, Bundle descriptor, boolean create) throws Exception {
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(new SecretKeySpec(key(context, create), "HmacSHA256"));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream output = new DataOutputStream(bytes)) {
            output.writeUTF("HookIntent navigation replay v1");
            canonical(output, identity);
            canonical(output, descriptor);
        }
        return hmac.doFinal(bytes.toByteArray());
    }

    private static void canonical(DataOutputStream output, Bundle bundle) throws IOException {
        ArrayList<String> names = new ArrayList<>(bundle.keySet());
        Collections.sort(names);
        output.writeInt(names.size());
        for (String name : names) {
            output.writeUTF(name);
            Object value = bundle.get(name);
            if (value == null) output.writeByte(0);
            else if (value instanceof Bundle) { output.writeByte(1); canonical(output, (Bundle)value); }
            else if (value instanceof String) { output.writeByte(2); output.writeUTF((String)value); }
            else if (value instanceof Boolean) { output.writeByte(3); output.writeBoolean((Boolean)value); }
            else if (value instanceof Number) {
                output.writeByte(4);
                // JSON storage preserves the number but can widen/narrow its boxed representation.
                output.writeUTF(new BigDecimal(value.toString()).stripTrailingZeros().toString());
            } else throw new IllegalArgumentException("导航参数类型无效");
        }
    }

    private static synchronized byte[] key(Context context, boolean create) throws IOException {
        File file = new File(context.getNoBackupFilesDir(), "jumpreplay_navigation_proof");
        byte[] cached = keys.get(file);
        if (cached != null) return cached;
        File lockFile = new File(file.getParentFile(), file.getName() + ".lock");
        try (RandomAccessFile lock = new RandomAccessFile(lockFile, "rw"); FileLock ignored = lock.getChannel().lock()) {
            byte[] secret = new byte[KEY_SIZE];
            try (DataInputStream input = new DataInputStream(new AtomicFile(file).openRead())) {
                input.readFully(secret);
                if (input.read() != -1) throw new IOException("导航凭据无效");
            } catch (FileNotFoundException missing) {
                if (!create) throw new IOException("原导航调用凭据不可用，请重新捕获", missing);
                new SecureRandom().nextBytes(secret);
                CaptureFiles.write(file, secret);
            }
            keys.put(file, secret);
            return secret;
        }
    }
}
