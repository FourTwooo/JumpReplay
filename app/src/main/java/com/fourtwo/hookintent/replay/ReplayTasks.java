package com.fourtwo.hookintent.replay;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;

import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.utils.IntentReplay;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;

/** URI edits that touch extras execute in the editor environment, never in the host UI process. */
public final class ReplayTasks {
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private ReplayTasks() { }

    /**
     * Returns a cancellable connection for extras merges, or null for a completed lightweight path.
     * Every completion is delivered on the main thread. Closing the returned client cancels its work.
     */
    public static ExtrasClient fromText(Context context, Bundle origin, String text, Intent original,
                                        String originalText, BiConsumer<Intent, Exception> callback) {
        Objects.requireNonNull(callback, "callback");
        // These operations read URI/header fields only. They do not enumerate or mutate extras.
        if (original == null || Objects.equals(text, originalText) || text == null || !text.contains("#Intent;")) {
            Intent result = null;
            Exception failure = null;
            try { result = IntentReplay.fromText(text, original, originalText); }
            catch (Exception error) { failure = error; }
            catch (LinkageError error) { failure = new IllegalStateException("Intent 内容无法读取", error); }
            Intent answer = result;
            Exception error = failure;
            MAIN.post(() -> callback.accept(answer, error));
            return null;
        }

        final Bundle record;
        try {
            record = origin == null ? new Bundle() : new Bundle(origin);
            record.putString(IntentPayloadCodec.KEY, IntentPayloadCodec.encode(original));
            if (!record.containsKey("componentPackageName") && original.getComponent() != null)
                record.putString("componentPackageName", original.getComponent().getPackageName());
            if (!record.containsKey("package") && original.getPackage() != null)
                record.putString("package", original.getPackage());
        } catch (Exception error) {
            MAIN.post(() -> callback.accept(null, error));
            return null;
        } catch (LinkageError error) {
            MAIN.post(() -> callback.accept(null, new IllegalStateException("Intent 快照无法读取", error)));
            return null;
        }

        ExtrasClient client = new ExtrasClient(context, record);
        AtomicBoolean completed = new AtomicBoolean();
        BiConsumer<Intent, Exception> finish = (intent, error) -> {
            if (!completed.compareAndSet(false, true)) return;
            MAIN.post(() -> {
                try { callback.accept(intent, error); }
                finally { client.close(); }
            });
        };
        client.open((opened, openError) -> {
            if (openError != null) { finish.accept(null, openError); return; }
            client.merge(text, originalText, (merged, mergeError) -> {
                if (mergeError != null) { finish.accept(null, mergeError); return; }
                try {
                    String parcel = merged == null ? null : merged.getString("parcel");
                    if (parcel == null || parcel.isEmpty()) throw new IllegalStateException("未收到完整的合并结果");
                    Bundle opaque = new Bundle();
                    opaque.putString(IntentPayloadCodec.KEY, parcel);
                    // Attaching an APK loader here would allow later UI operations to run foreign code.
                    finish.accept(IntentPayloadCodec.decode(opaque), null);
                } catch (Exception error) { finish.accept(null, error); }
                catch (LinkageError error) { finish.accept(null, new IllegalStateException("合并后的 Intent 无法读取", error)); }
            });
        });
        return client;
    }
}
