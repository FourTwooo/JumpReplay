package com.fourtwo.hookintent.replay;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.Base64;
import android.util.JsonReader;
import android.util.JsonToken;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.Serializable;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import java.util.regex.Pattern;

/** A shared template describes a route. Only authenticated local captures supply executable calls. */
final class FragmentReplayTemplate {
    private static final String FORMAT = "jumpreplay-fragment-template-v1";
    private static final int MAX_TEXT = 384 * 1024;
    private static final Pattern DIGEST = Pattern.compile("[A-Za-z0-9_-]{43}");
    final String id;
    final String name;
    final String packageName;
    final String destination;
    final long versionCode;
    final String versionName;
    final String signer;
    final String route;
    final Bundle arguments;

    FragmentReplayTemplate(String id, String name, String packageName, String destination,
            long versionCode, String versionName, String signer, String route, Bundle arguments) {
        this.id = id;
        this.name = name;
        this.packageName = packageName;
        this.destination = destination;
        this.versionCode = versionCode;
        this.versionName = versionName;
        this.signer = signer;
        this.route = route;
        this.arguments = arguments;
    }

    static FragmentReplayTemplate parse(String text) throws Exception {
        if (text == null || text.length() > MAX_TEXT) throw new IOException("重放模板内容过大");
        Bundle fields = new Bundle();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.beginObject();
            while (reader.hasNext()) {
                String key = reader.nextName();
                if (fields.containsKey(key)) throw new IOException("重放模板含重复字段");
                switch (key) {
                    case "format": case "identifier": case "name": case "package": case "destination":
                    case "versionName": case "signer": case "route":
                        fields.putString(key, readString(reader));
                        break;
                    case "versionCode": fields.putLong(key, reader.nextLong()); break;
                    case "arguments": fields.putBundle(key, readArguments(reader)); break;
                    default: throw new IOException("重放模板包含未知字段");
                }
            }
            reader.endObject();
            if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("重放模板格式无效");
        } catch (IllegalStateException | NumberFormatException error) {
            throw new IOException("重放模板格式无效", error);
        }
        if (!FORMAT.equals(fields.getString("format"))) throw new IOException("不支持此重放模板格式");
        String id = fields.getString("identifier");
        String name = fields.getString("name");
        String packageName = fields.getString("package");
        String destination = fields.getString("destination");
        long versionCode = fields.getLong("versionCode", -1);
        String versionName = fields.getString("versionName");
        String signer = fields.getString("signer");
        String route = fields.getString("route");
        Bundle args = fields.getBundle("arguments");
        if (!FragmentReplayStore.validIdentifier(id) || !fits(name, 100) || !fits(packageName, 255)
                || !fits(destination, 255) || versionCode < 0 || versionName == null || versionName.length() > 255
                || !matchesDigest(signer) || !matchesDigest(route) || args == null) {
            throw new IOException("重放模板缺少必要的目标、版本或参数信息");
        }
        return new FragmentReplayTemplate(id, name, packageName, destination, versionCode, versionName,
                signer, route, args);
    }

    private static boolean fits(String text, int length) {
        return text != null && !text.trim().isEmpty() && text.length() <= length;
    }

    private static boolean matchesDigest(String value) {
        return value != null && DIGEST.matcher(value).matches();
    }

    private static String readString(JsonReader reader) throws IOException {
        if (reader.peek() != JsonToken.STRING) throw new IOException("重放模板字段类型无效");
        return reader.nextString();
    }

    private static Bundle readArguments(JsonReader reader) throws IOException {
        Bundle arguments = new Bundle();
        reader.beginArray();
        while (reader.hasNext()) {
            if (arguments.size() >= 128) throw new IOException("重放模板参数过多");
            reader.beginArray();
            String key = readString(reader);
            String type = readString(reader);
            if (arguments.containsKey(key)) throw new IOException("重放模板含重复参数");
            if ("null".equals(type)) arguments.putString(key, null);
            else arguments.putSerializable(key, scalar(type, readString(reader)));
            reader.endArray();
        }
        reader.endArray();
        return NavigationCaptureProof.copy(arguments, false);
    }

    private static Serializable scalar(String type, String value) throws IOException {
        switch (type) {
            case "String": return value;
            case "Boolean":
                if ("true".equals(value) || "false".equals(value)) return Boolean.valueOf(value);
                break;
            case "Byte": return Byte.valueOf(value);
            case "Short": return Short.valueOf(value);
            case "Integer": return Integer.valueOf(value);
            case "Long": return Long.valueOf(value);
            case "Float": return Float.valueOf(value);
            case "Double": return Double.valueOf(value);
            default: break;
        }
        throw new IOException("重放模板含不支持的参数类型");
    }

    String encode() throws Exception {
        JSONArray encodedArguments = new JSONArray();
        for (String key : sortedKeys(arguments)) {
            Object value = arguments.get(key);
            JSONArray encoded = new JSONArray().put(key).put(value == null ? "null" : value.getClass().getSimpleName());
            if (value != null) encoded.put(value.toString());
            encodedArguments.put(encoded);
        }
        String text = new JSONObject().put("format", FORMAT).put("identifier", id).put("name", name)
                .put("package", packageName).put("destination", destination).put("versionCode", versionCode)
                .put("versionName", versionName).put("signer", signer).put("route", route)
                .put("arguments", encodedArguments).toString();
        if (text.length() > MAX_TEXT) throw new IOException("重放模板内容过大");
        return text;
    }

    static String fingerprint(Bundle descriptor) throws Exception {
        JSONArray shape = new JSONArray();
        for (String key : sortedKeys(descriptor)) {
            if ("capture_proof".equals(key) || "arguments".equals(key)) continue;
            Object value = descriptor.get(key);
            if ("invocation".equals(key)) value = invocationShape(new JSONObject((String) value));
            if (value instanceof Bundle) {
                Bundle fields = (Bundle) value;
                JSONArray entries = new JSONArray();
                for (String field : sortedKeys(fields)) {
                    entries.put(new JSONArray().put(field).put(fields.get(field)));
                }
                value = entries;
            }
            shape.put(new JSONArray().put(key).put(value));
        }
        return digest(shape.toString().getBytes(StandardCharsets.UTF_8));
    }

    private static JSONArray invocationShape(JSONObject invocation) throws Exception {
        JSONArray bindings = invocation.getJSONArray("bindings");
        ArrayList<String> sortedBindings = new ArrayList<>();
        for (int i = 0; i < bindings.length(); i++) sortedBindings.add(bindings.getJSONArray(i).toString());
        Collections.sort(sortedBindings);
        JSONArray values = invocation.getJSONArray("values");
        JSONArray types = new JSONArray();
        for (int i = 0; i < values.length(); i++) {
            JSONArray value = values.getJSONArray(i);
            String type = value.getString(0);
            if (!"Bundle".equals(type)) {
                types.put(type);
                continue;
            }
            JSONObject bundle = value.getJSONObject(1);
            ArrayList<String> keys = new ArrayList<>();
            for (Iterator<String> iterator = bundle.keys(); iterator.hasNext(); ) keys.add(iterator.next());
            Collections.sort(keys);
            JSONArray fields = new JSONArray();
            for (String key : keys) fields.put(new JSONArray().put(key).put(bundle.getJSONArray(key).getString(0)));
            types.put(fields);
        }
        return new JSONArray().put(invocation.getString("owner")).put(invocation.getJSONArray("types"))
                .put(new JSONArray(sortedBindings)).put(types);
    }

    private static ArrayList<String> sortedKeys(Bundle bundle) {
        ArrayList<String> keys = new ArrayList<>(bundle.keySet());
        Collections.sort(keys);
        return keys;
    }

    boolean installedOn(Context context) throws Exception {
        try {
            AppVersion version = AppVersion.installed(context, packageName);
            return versionCode == version.code && signer.equals(version.signer);
        } catch (PackageManager.NameNotFoundException missing) {
            return false;
        }
    }

    private static String digest(byte[] bytes) {
        try {
            return Base64.encodeToString(MessageDigest.getInstance("SHA-256").digest(bytes),
                    Base64.URL_SAFE | Base64.NO_PADDING | Base64.NO_WRAP);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    static final class AppVersion {
        final long code;
        final String name;
        final String signer;

        AppVersion(long code, String name, String signer) {
            this.code = code;
            this.name = name;
            this.signer = signer;
        }

        static AppVersion installed(Context context, String packageName) throws PackageManager.NameNotFoundException {
            PackageInfo info = context.getPackageManager().getPackageInfo(packageName, Build.VERSION.SDK_INT >= 28
                    ? PackageManager.GET_SIGNING_CERTIFICATES : PackageManager.GET_SIGNATURES);
            Signature[] signers = Build.VERSION.SDK_INT >= 28
                    ? info.signingInfo == null ? null : info.signingInfo.getApkContentsSigners()
                    : info.signatures;
            String signer = "";
            if (signers != null && signers.length > 0) {
                ArrayList<String> certs = new ArrayList<>();
                for (Signature signature : signers) certs.add(digest(signature.toByteArray()));
                Collections.sort(certs);
                signer = digest(TextUtils.join(",", certs).getBytes(StandardCharsets.US_ASCII));
            }
            long code = Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
            return new AppVersion(code, info.versionName == null ? "" : info.versionName, signer);
        }
    }
}
