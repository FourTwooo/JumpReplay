package com.fourtwo.hookintent.replay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;

import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.NavigationCaptureSource;
import com.fourtwo.hookintent.xposed.CaptureFiles;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** Generated intents reference sealed, typed replay data kept in the app's private storage. */
public final class FragmentReplayStore {
    static final String ACTION = "com.fourtwo.hookintent.action.REPLAY_FRAGMENT";
    private static final String URI_PREFIX = "jumpreplay-fragment://replay/";
    private static final Pattern TOKEN = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
    private static final int MAX_BYTES = 512 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Executor IO = Executors.newSingleThreadExecutor();

    private FragmentReplayStore() {}

    public static void createIntentAsync(Context context, Bundle captureRecord, Bundle descriptor,
            Bundle editedArguments, BiConsumer<Intent, Exception> callback) {
        Context app = context.getApplicationContext();
        runIo(() -> createIntent(app, captureRecord, descriptor, editedArguments), callback);
    }

    public static Intent createIntent(Context context, Bundle captureRecord, Bundle descriptor,
            Bundle editedArguments) throws Exception {
        if (captureRecord == null || NavigationCaptureSource.fromRecord(
                captureRecord.getString("category"), captureRecord) != NavigationCaptureSource.FRAGMENT) {
            throw new IllegalArgumentException("此记录不是可生成重放 Intent 的 Fragment 记录");
        }
        NavigationReplayTransport.Source.installed(context, captureRecord);
        Bundle archived = captureRecord.getBundle(Constants.NAVIGATION_REPLAY);
        if (archived == null || descriptor == null || !NavigationCaptureProof.hasProof(archived)
                || !Objects.equals(archived.getString("capture_proof"), descriptor.getString("capture_proof"))) {
            throw new IllegalArgumentException("这条记录没有可验证的原导航调用");
        }
        Bundle capturedCall = NavigationCaptureProof.copy(archived, true);
        Bundle arguments = checkedEdits(capturedCall, editedArguments);
        Bundle payload = new Bundle();
        payload.putInt("version", 1);
        payload.putBundle("source", sourceOf(captureRecord));
        payload.putBundle("descriptor", capturedCall);
        payload.putBundle("arguments", arguments);
        byte[] bytes = IntentPayloadCodec.encode(new Intent().putExtras(payload)).getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > MAX_BYTES) throw new IOException("重放记录过大");

        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建重放记录目录");
        }
        String token = UUID.randomUUID().toString();
        CaptureFiles.write(new File(directory, token), bytes);
        return new Intent(ACTION, Uri.parse(URI_PREFIX + token)).setComponent(component(context));
    }

    static void openAsync(Context context, Intent incoming, BiConsumer<Prepared, Exception> callback) {
        Context app = context.getApplicationContext();
        runIo(() -> load(app, incoming), callback);
    }

    private static String tokenFrom(Context context, Intent incoming) {
        if (incoming != null && ACTION.equals(incoming.getAction())
                && component(context).equals(incoming.getComponent())) {
            String data = incoming.getDataString();
            if (data != null && data.length() == URI_PREFIX.length() + 36 && data.startsWith(URI_PREFIX)) {
                String token = data.substring(URI_PREFIX.length());
                if (TOKEN.matcher(token).matches()) return token;
            }
        }
        throw new IllegalArgumentException("重放 Intent 无效");
    }

    static Prepared load(Context context, Intent incoming) throws Exception {
        String token = tokenFrom(context, incoming);
        AtomicFile file = new AtomicFile(new File(directory(context), token));
        byte[] bytes;
        try (FileInputStream input = file.openRead(); DataInputStream data = new DataInputStream(input)) {
            long length = input.getChannel().size();
            if (length < 1 || length > MAX_BYTES) throw new IOException("重放记录大小无效");
            bytes = new byte[(int) length];
            data.readFully(bytes);
            if (data.read() != -1) throw new IOException("重放记录大小无效");
        } catch (FileNotFoundException error) {
            throw new IOException("找不到此 Intent 保存的重放记录", error);
        }

        Bundle encoded = new Bundle();
        encoded.putString(IntentPayloadCodec.KEY, new String(bytes, StandardCharsets.US_ASCII));
        Bundle payload = IntentPayloadCodec.decode(encoded).getExtras();
        if (payload == null || payload.getInt("version") != 1) {
            throw new IOException("重放记录版本无效");
        }
        Bundle source = payload.getBundle("source");
        NavigationReplayTransport.Source.installed(context, source);
        Bundle descriptor = NavigationCaptureProof.copy(payload.getBundle("descriptor"), true);
        if (!NavigationCaptureProof.hasProof(descriptor)) {
            throw new IllegalArgumentException("这条记录没有可验证的原导航调用");
        }
        return new Prepared(source, descriptor, checkedEdits(descriptor, payload.getBundle("arguments")));
    }

    private static Bundle checkedEdits(Bundle descriptor, Bundle edited) {
        Bundle arguments = NavigationCaptureProof.copy(edited, false);
        Bundle original = descriptor.getBundle("arguments");
        if (original == null || !original.keySet().equals(arguments.keySet())) {
            throw new IllegalArgumentException("重放参数与原调用不匹配");
        }
        return arguments;
    }

    private static Bundle sourceOf(Bundle record) {
        Bundle source = new Bundle();
        source.putString("packageName", record.getString("packageName"));
        source.putString("processName", record.getString("processName"));
        source.putInt("source_uid", record.getInt("source_uid"));
        source.putInt("source_app_uid", record.getInt("source_app_uid"));
        source.putLong("source_last_update", record.getLong("source_last_update"));
        source.putInt("capture_sdk", record.getInt("capture_sdk"));
        return source;
    }

    private static File directory(Context context) {
        return new File(context.getNoBackupFilesDir(), "fragment_replays");
    }

    private static ComponentName component(Context context) {
        return new ComponentName(context, FragmentReplayActivity.class);
    }

    private static <T> void runIo(Callable<T> action, BiConsumer<T, Exception> callback) {
        IO.execute(() -> {
            T result;
            try {
                result = action.call();
            } catch (Exception error) {
                MAIN.post(() -> callback.accept(null, error));
                return;
            }
            MAIN.post(() -> callback.accept(result, null));
        });
    }

    static final class Prepared {
        final Bundle source;
        final Bundle descriptor;
        final Bundle arguments;

        Prepared(Bundle source, Bundle descriptor, Bundle arguments) {
            this.source = source;
            this.descriptor = descriptor;
            this.arguments = arguments;
        }
    }
}
