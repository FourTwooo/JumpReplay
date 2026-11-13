package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.util.AtomicFile;

import com.fourtwo.hookintent.base.IntentPayloadCodec;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.json.JSONObject;
import org.json.JSONArray;

/** Stores complete payloads privately so launcher Binder messages stay small. */
public final class ShortcutPayloadStore {
    private ShortcutPayloadStore() { }

    public static final class Payload {
        public final String template;
        public final String[] parameters;
        public final Intent target;
        public final Bundle origin;
        Payload(String template, String[] parameters, Intent target) {
            this(template, parameters, target, new Bundle());
        }
        Payload(String template, String[] parameters, Intent target, Bundle origin) {
            this.template = template; this.parameters = parameters; this.target = target;
            this.origin = new Bundle(origin);
        }
    }

    public static String save(Context context, String template, String[] parameters, Intent target) throws Exception {
        return save(context, template, parameters, target, null);
    }

    public static String save(Context context, String template, String[] parameters, Intent target, Bundle origin) throws Exception {
        return saveRaw(context, content(template, parameters, target, origin));
    }

    public static JSONObject content(String template, String[] parameters, Intent target, Bundle origin) throws Exception {
        JSONObject content = new JSONObject();
        content.put("template", template);
        content.put("parameters", new JSONArray(parameters == null ? new String[0] : parameters));
        if (target != null) content.put("parcel", IntentPayloadCodec.encode(target));
        if (origin != null) {
            JSONObject source = new JSONObject();
            for (String key : new String[]{"packageName", "componentPackageName", "package", "processName", "capture_sdk", "source_last_update", "source_uid", "source_app_uid"}) {
                if (origin.get(key) != null) source.put(key, origin.get(key));
            }
            content.put("origin", source);
        }
        return content;
    }

    public static String saveRaw(Context context, JSONObject content) throws Exception {
        byte[] encoded = content.toString().getBytes(StandardCharsets.UTF_8);
        File folder = new File(context.getFilesDir(), "shortcut_payloads");
        if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("无法保存快捷方式内容");
        String id = UUID.randomUUID().toString();
        AtomicFile file = new AtomicFile(new File(folder, id + ".txt"));
        FileOutputStream stream = null;
        try {
            stream = file.startWrite();
            stream.write(encoded);
            stream.getFD().sync();
            file.finishWrite(stream);
            if (!file.getBaseFile().isFile() || file.getBaseFile().length() != encoded.length) {
                throw new java.io.IOException("快捷方式内容保存失败");
            }
            return id;
        } catch (Exception e) {
            file.failWrite(stream);
            throw e;
        }
    }

    public static Payload load(Context context, String id) throws Exception {
        JSONObject content = readRaw(context, id);
        return content == null ? null : decode(context, content);
    }

    public static JSONObject readRaw(Context context, String id) throws Exception {
        if (id == null) return null;
        if (!id.matches("[0-9a-fA-F-]{36}")) throw new IllegalArgumentException("快捷方式内容标识无效");
        File folder = new File(context.getFilesDir(), "shortcut_payloads");
        byte[] bytes = new AtomicFile(new File(folder, id + ".txt")).readFully();
        return new JSONObject(new String(bytes, StandardCharsets.UTF_8));
    }

    public static Payload decode(Context context, JSONObject content) throws Exception {
        JSONArray names = content.optJSONArray("parameters");
        String[] parameters = new String[names == null ? 0 : names.length()];
        for (int index = 0; index < parameters.length; index++) parameters[index] = names.getString(index);
        String parcel = content.optString("parcel", "");
        Intent target = null;
        Bundle bundle = new Bundle();
        if (!parcel.isEmpty()) {
            JSONObject origin = content.optJSONObject("origin");
            if (origin != null) {
                bundle.putAll(com.fourtwo.hookintent.base.JsonHandler.toBundle(origin));
            }
            bundle.putString(IntentPayloadCodec.KEY, parcel);
            target = IntentPayloadCodec.decode(context, bundle);
        }
        return new Payload(content.getString("template"), parameters, target, bundle);
    }
}
