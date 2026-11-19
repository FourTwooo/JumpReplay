package com.fourtwo.hookintent.replay;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.AtomicFile;
import android.util.Log;
import android.util.LruCache;

import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.data.CaptureRepository;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.NavigationCaptureSource;
import com.fourtwo.hookintent.xposed.CaptureFiles;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.regex.Pattern;

/** Generated intents reference a sealed replay and can carry edits to its saved arguments. */
public final class FragmentReplayStore {
    static final String ACTION = "com.fourtwo.hookintent.action.REPLAY_FRAGMENT";
    private static final String URI_PREFIX = "jumpreplay-fragment://replay/";
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_-]{0,63}");
    private static final int VERSION_FIXED_ARGUMENTS = 1;
    private static final int VERSION_EDITABLE_ARGUMENTS = 2;
    private static final int VERSION_PENDING_TEMPLATE = 3;
    private static final int MAX_BYTES = 512 * 1024;
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final Executor IO = Executors.newSingleThreadExecutor();
    private static final LruCache<File, Long> ARCHIVE_MISSES = new LruCache<>(128);

    private FragmentReplayStore() {}

    public static void createIntentAsync(Context context, Bundle captureRecord, Bundle descriptor,
            Bundle editedArguments, BiConsumer<Intent, Exception> callback) {
        Context app = context.getApplicationContext();
        runIo(() -> createIntent(app, captureRecord, descriptor, editedArguments), callback);
    }

    public static Intent createIntent(Context context, Bundle captureRecord, Bundle descriptor,
            Bundle editedArguments) throws Exception {
        return createIntent(context, captureRecord, descriptor, editedArguments, null, null);
    }

    public static synchronized Intent createIntent(Context context, Bundle captureRecord, Bundle descriptor,
            Bundle editedArguments, String name, String identifier) throws Exception {
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
        Bundle source = sourceOf(captureRecord);
        String id = availableIdentifier(context, identifier == null || identifier.trim().isEmpty()
                ? UUID.randomUUID().toString() : identifier.trim());
        FragmentReplayTemplate.AppVersion stamp = FragmentReplayTemplate.AppVersion.installed(context,
                source.getString("packageName"));
        Bundle payload = replayPayload(source, capturedCall, arguments,
                displayName(name, capturedCall.getString("destination", id)), stamp);
        writePayload(context, id, payload);
        return intent(context, id, arguments);
    }

    private static Bundle replayPayload(Bundle source, Bundle descriptor, Bundle arguments, String name,
            FragmentReplayTemplate.AppVersion stamp) {
        Bundle payload = new Bundle();
        payload.putInt("version", VERSION_EDITABLE_ARGUMENTS);
        payload.putBundle("source", source);
        payload.putBundle("descriptor", descriptor);
        payload.putBundle("arguments", arguments);
        payload.putString("name", name);
        payload.putLong("app_version_code", stamp.code);
        payload.putString("app_version_name", stamp.name);
        payload.putString("app_signer", stamp.signer);
        return payload;
    }

    private static void writePayload(Context context, String id, Bundle payload) throws IOException {
        byte[] bytes = IntentPayloadCodec.encode(new Intent().putExtras(payload)).getBytes(StandardCharsets.US_ASCII);
        if (bytes.length > MAX_BYTES) throw new IOException("重放记录过大");

        File directory = directory(context);
        if (!directory.isDirectory() && !directory.mkdirs() && !directory.isDirectory()) {
            throw new IOException("无法创建重放记录目录");
        }
        CaptureFiles.write(new File(directory, id), bytes);
    }

    private static Intent intent(Context context, String id, Bundle arguments) {
        Intent generated = new Intent(ACTION, Uri.parse(URI_PREFIX + id)).setComponent(component(context));
        if (arguments != null) generated.putExtras(arguments);
        return generated;
    }

    static void openAsync(Context context, Intent incoming, BiConsumer<Prepared, Exception> callback) {
        Context app = context.getApplicationContext();
        runIo(() -> load(app, incoming), callback);
    }

    private static String tokenFrom(Context context, Intent incoming) {
        if (incoming != null && ACTION.equals(incoming.getAction())
                && component(context).equals(incoming.getComponent())) {
            String data = incoming.getDataString();
            if (data != null && data.length() <= URI_PREFIX.length() + 64 && data.startsWith(URI_PREFIX)) {
                String token = data.substring(URI_PREFIX.length());
                if (validIdentifier(token)) return token;
            }
        }
        throw new IllegalArgumentException("重放 Intent 无效");
    }

    static boolean validIdentifier(String identifier) {
        return identifier != null && TOKEN.matcher(identifier).matches();
    }

    private static File templateFile(Context context, String id) {
        if (!validIdentifier(id)) throw new IllegalArgumentException("链接 ID 须为 1 至 64 位字母、数字、连字符或下划线，并以字母或数字开头");
        return new File(directory(context), id);
    }

    private static String availableIdentifier(Context context, String id) throws IOException {
        File file = templateFile(context, id);
        if (file.exists() || new File(file + ".bak").exists()) throw new IOException("此链接 ID 已存在，请选择其他 ID");
        return id;
    }

    private static String displayName(String name, String fallback) {
        String value = name == null || name.trim().isEmpty() ? fallback : name.trim();
        if (value.length() > 100) {
            if (name != null && !name.trim().isEmpty()) throw new IllegalArgumentException("模板名称不能超过 100 个字符");
            value = value.substring(0, 100);
        }
        return value;
    }

    private static Bundle readPayload(Context context, String id) throws Exception {
        AtomicFile file = new AtomicFile(templateFile(context, id));
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
        int version = payload == null ? 0 : payload.getInt("version");
        if (version != VERSION_FIXED_ARGUMENTS && version != VERSION_EDITABLE_ARGUMENTS
                && version != VERSION_PENDING_TEMPLATE) {
            throw new IOException("重放记录版本无效");
        }
        return payload;
    }

    static synchronized Prepared load(Context context, Intent incoming) throws Exception {
        String id = tokenFrom(context, incoming);
        Bundle payload = requireReady(context, id, readPayload(context, id));
        Bundle source = payload.getBundle("source");
        NavigationReplayTransport.Source.installed(context, source);
        Bundle descriptor = NavigationCaptureProof.copy(payload.getBundle("descriptor"), true);
        if (!NavigationCaptureProof.hasProof(descriptor)) {
            throw new IllegalArgumentException("这条记录没有可验证的原导航调用");
        }
        Bundle arguments = checkedEdits(descriptor, payload.getBundle("arguments"));
        if (payload.getInt("version") == VERSION_EDITABLE_ARGUMENTS) {
            arguments = applyOverrides(arguments, incoming.getExtras());
        }
        return new Prepared(source, descriptor, arguments);
    }

    public static synchronized List<Template> listTemplates(Context context) {
        List<Template> templates = new ArrayList<>();
        for (String id : storedIdentifiers(context)) {
            try {
                Bundle payload = readPayload(context, id);
                payload = prepareImported(context, id, payload);
                templates.add(overview(context, id, payload));
            } catch (Exception error) {
                Log.w("FragmentReplayStore", "Cannot read replay template", error);
            }
        }
        templates.sort(Comparator.comparing((Template template) -> template.name)
                .thenComparing(template -> template.id));
        return templates;
    }

    public static synchronized Intent templateIntent(Context context, String id) throws Exception {
        Bundle payload = requireReady(context, id, readPayload(context, id));
        NavigationReplayTransport.Source.installed(context, payload.getBundle("source"));
        Bundle arguments = payload.getInt("version") == VERSION_EDITABLE_ARGUMENTS
                ? payload.getBundle("arguments") : null;
        return intent(context, id, arguments);
    }

    public static synchronized void deleteTemplate(Context context, String id) throws IOException {
        File file = templateFile(context, id);
        new AtomicFile(file).delete();
        if (file.exists() || new File(file + ".bak").exists()) throw new IOException("无法删除重放模板");
        ARCHIVE_MISSES.remove(file);
    }

    public static synchronized void renameTemplate(Context context, String id, String name) throws Exception {
        Bundle payload = readPayload(context, id);
        payload.putString("name", displayName(name, overview(context, id, payload).destination));
        writePayload(context, id, payload);
    }

    public static synchronized Template duplicateTemplate(Context context, String existingId, String customIdentifier)
            throws Exception {
        String id = availableIdentifier(context, customIdentifier == null ? null : customIdentifier.trim());
        Bundle payload = readPayload(context, existingId);
        writePayload(context, id, payload);
        return overview(context, id, payload);
    }

    public static synchronized String exportTemplate(Context context, String id) throws Exception {
        Bundle payload = readPayload(context, id);
        FragmentReplayTemplate spec;
        if (payload.containsKey("portable")) {
            spec = FragmentReplayTemplate.parse(payload.getString("portable"));
        } else {
            Bundle source = payload.getBundle("source");
            Bundle descriptor = NavigationCaptureProof.copy(payload.getBundle("descriptor"), true);
            if (!NavigationCaptureProof.hasProof(descriptor)) throw new IllegalStateException("缺少原导航调用的采集凭据");
            String packageName = source.getString("packageName");
            FragmentReplayTemplate.AppVersion stamp = storedStamp(context, payload, true);
            if (stamp.signer.isEmpty()) throw new IllegalStateException("无法验证目标 App 的安装签名");
            String destination = descriptor.getString("destination");
            if (destination == null || destination.isEmpty()) throw new IllegalStateException("此记录缺少 Fragment 目标");
            spec = new FragmentReplayTemplate(id, destination, packageName, destination, stamp.code,
                    stamp.name, stamp.signer, FragmentReplayTemplate.fingerprint(descriptor),
                    checkedEdits(descriptor, payload.getBundle("arguments")));
        }
        return new FragmentReplayTemplate(id, displayName(payload.getString("name"), spec.name), spec.packageName,
                spec.destination, spec.versionCode, spec.versionName, spec.signer, spec.route, spec.arguments).encode();
    }

    public static synchronized Template importTemplate(Context context, String text, String customIdentifier)
            throws Exception {
        FragmentReplayTemplate spec = FragmentReplayTemplate.parse(text);
        String id = availableIdentifier(context, customIdentifier == null || customIdentifier.trim().isEmpty()
                ? spec.id : customIdentifier.trim());
        ARCHIVE_MISSES.remove(templateFile(context, id));
        Bundle payload = new Bundle();
        payload.putInt("version", VERSION_PENDING_TEMPLATE);
        payload.putString("portable", spec.encode());
        payload.putString("name", spec.name);
        Bundle resolved = resolveImported(context, id, payload);
        if (resolved == payload) writePayload(context, id, payload);
        return overview(context, id, resolved);
    }

    private static Bundle requireReady(Context context, String id, Bundle payload) throws Exception {
        payload = prepareImported(context, id, payload);
        if (pending(payload)) throw new IllegalStateException("尚未找到兼容的本机导航记录。请安装模板对应版本的目标 App，并在本机捕获一次同一导航。");
        return payload;
    }

    private static Bundle prepareImported(Context context, String id, Bundle payload) throws Exception {
        if (!payload.containsKey("portable")) return payload;
        if (pending(payload)) return resolveImported(context, id, payload);
        if (installed(context, payload.getBundle("source"))) return payload;
        Bundle waiting = new Bundle();
        waiting.putInt("version", VERSION_PENDING_TEMPLATE);
        waiting.putString("name", payload.getString("name"));
        waiting.putString("portable", payload.getString("portable"));
        Bundle resolved = resolveImported(context, id, waiting);
        if (resolved == waiting) writePayload(context, id, waiting);
        return resolved;
    }

    private static Bundle resolveImported(Context context, String id, Bundle pendingPayload) throws Exception {
        FragmentReplayTemplate spec = FragmentReplayTemplate.parse(pendingPayload.getString("portable"));
        if (!spec.installedOn(context)) return pendingPayload;

        Prepared evidence = null;
        for (String other : storedIdentifiers(context)) {
            if (id.equals(other)) continue;
            try {
                Bundle payload = readPayload(context, other);
                if (pending(payload)) continue;
                evidence = matchingEvidence(context, spec, payload.getBundle("source"), payload.getBundle("descriptor"));
                if (evidence != null) break;
            } catch (Exception unreadable) {
                Log.w("FragmentReplayStore", "Cannot read local replay evidence", unreadable);
            }
        }
        if (evidence == null) {
            CaptureRepository archive = CaptureRepository.get(context);
            long revision = archive.captureHistoryRevision();
            File file = templateFile(context, id);
            Long previous = ARCHIVE_MISSES.get(file);
            if (previous != null && previous == revision) return pendingPayload;
            evidence = archive.findNavigationCapture(spec.destination, capture -> matchingEvidence(context, spec,
                    sourceOf(capture), capture.getBundle(Constants.NAVIGATION_REPLAY)));
            if (evidence == null) ARCHIVE_MISSES.put(file, revision);
        }
        if (evidence == null) return pendingPayload;
        FragmentReplayTemplate.AppVersion stamp = new FragmentReplayTemplate.AppVersion(spec.versionCode,
                spec.versionName, spec.signer);
        Bundle resolved = replayPayload(evidence.source, evidence.descriptor, evidence.arguments,
                displayName(pendingPayload.getString("name"), spec.name), stamp);
        resolved.putString("portable", pendingPayload.getString("portable"));
        writePayload(context, id, resolved);
        return resolved;
    }

    private static Prepared matchingEvidence(Context context, FragmentReplayTemplate spec, Bundle source, Bundle sealed) {
        if (source == null || sealed == null || !spec.packageName.equals(source.getString("packageName"))
                || !spec.destination.equals(sealed.getString("destination"))) return null;
        try {
            NavigationReplayTransport.Source.installed(context, source);
            // The transport verifies the original seal inside the target process before calling this descriptor.
            if (!NavigationCaptureProof.hasProof(sealed) || !spec.route.equals(FragmentReplayTemplate.fingerprint(sealed))) return null;
            Bundle arguments = checkedEdits(sealed, spec.arguments);
            Bundle originals = sealed.getBundle("arguments");
            for (String key : originals.keySet()) {
                if (!compatibleTypes(originals.get(key), arguments.get(key))) return null;
            }
            return new Prepared(source, sealed, arguments);
        } catch (Exception incompatible) {
            return null;
        }
    }

    private static boolean compatibleTypes(Object original, Object edited) {
        if (original == null || edited == null) return original == edited;
        if (original.getClass() == edited.getClass()) return true;
        if (!(original instanceof Number) || !(edited instanceof Number)) return false;
        return (original instanceof Double || original instanceof Float)
                == (edited instanceof Double || edited instanceof Float);
    }

    private static boolean pending(Bundle payload) {
        return payload.getInt("version") == VERSION_PENDING_TEMPLATE;
    }

    private static String[] storedIdentifiers(Context context) {
        String[] ids = directory(context).list((directory, id) -> validIdentifier(id));
        return ids == null ? new String[0] : ids;
    }

    private static FragmentReplayTemplate.AppVersion storedStamp(Context context, Bundle payload, boolean required)
            throws Exception {
        if (payload.containsKey("app_version_code")) {
            return new FragmentReplayTemplate.AppVersion(payload.getLong("app_version_code"),
                    payload.getString("app_version_name", ""), payload.getString("app_signer", ""));
        }
        Bundle source = payload.getBundle("source");
        try {
            NavigationReplayTransport.Source.installed(context, source);
            return FragmentReplayTemplate.AppVersion.installed(context, source.getString("packageName"));
        } catch (PackageManager.NameNotFoundException | IllegalStateException failure) {
            if (required) throw failure;
            return new FragmentReplayTemplate.AppVersion(-1, "", "");
        }
    }

    private static Template overview(Context context, String id, Bundle payload) throws Exception {
        if (pending(payload)) {
            FragmentReplayTemplate spec = FragmentReplayTemplate.parse(payload.getString("portable"));
            return new Template(id, displayName(payload.getString("name"), spec.name), spec.packageName,
                    spec.destination, spec.versionName, spec.versionCode, false, true);
        }
        Bundle source = payload.getBundle("source");
        Bundle descriptor = payload.getBundle("descriptor");
        String destination = descriptor.getString("destination", id);
        FragmentReplayTemplate.AppVersion stamp = storedStamp(context, payload, false);
        return new Template(id, displayName(payload.getString("name"), destination), source.getString("packageName"),
                destination, stamp.name, stamp.code, installed(context, source), false);
    }

    private static boolean installed(Context context, Bundle source) {
        try {
            NavigationReplayTransport.Source.installed(context, source);
            return true;
        } catch (PackageManager.NameNotFoundException | IllegalStateException failure) {
            return false;
        }
    }

    public static final class Template {
        public final String id;
        public final String name;
        public final String packageName;
        public final String destination;
        public final String versionName;
        public final long versionCode;
        public final boolean ready;
        public final boolean pending;

        Template(String id, String name, String packageName, String destination,
                String versionName, long versionCode, boolean ready, boolean pending) {
            this.id = id;
            this.name = name;
            this.packageName = packageName;
            this.destination = destination;
            this.versionName = versionName;
            this.versionCode = versionCode;
            this.ready = ready;
            this.pending = pending;
        }
    }

    private static Bundle applyOverrides(Bundle arguments, Bundle overrides) {
        if (overrides == null) return arguments;
        overrides.setClassLoader(ClassLoader.getSystemClassLoader());
        for (String key : arguments.keySet()) {
            if (!overrides.containsKey(key)) continue;
            Object original = arguments.get(key);
            Object supplied = overrides.get(key);
            if (original == null ? supplied != null : supplied == null || original.getClass() != supplied.getClass()) {
                throw new IllegalArgumentException("重放参数 " + key + " 的类型不符");
            }
            arguments.putSerializable(key, (Serializable) supplied);
        }
        return NavigationCaptureProof.copy(arguments, false);
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
