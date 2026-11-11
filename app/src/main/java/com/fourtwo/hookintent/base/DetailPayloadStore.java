package com.fourtwo.hookintent.base;

import android.content.Context;
import android.os.Bundle;
import android.util.AtomicFile;

import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.utils.AppInfoHelper;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

/** Keeps complete detail data out of navigation saved state and Activity Binder messages. */
public final class DetailPayloadStore {
    public static final String ARG_TOKEN = "item_token";
    public static final String EXTRA_TOKEN = "DetailPayloadId";

    private DetailPayloadStore() { }

    public static String save(Context context, ItemData item) {
        if (item == null) throw new IllegalArgumentException("详情记录为空");
        try {
            File directory = new File(context.getFilesDir(), "detail_payloads");
            if (!directory.isDirectory() && !directory.mkdirs()) throw new java.io.IOException("无法创建详情目录");
            JSONObject data = new JSONObject();
            data.put("version", 1);
            data.put("appName", item.getAppName());
            data.put("from", item.getItem_from());
            data.put("data", item.getItem_data());
            data.put("time", item.getTimestamp());
            data.put("size", item.getDataSize());
            data.put("category", item.getCategory());
            data.put("stack", item.getStackTrace());
            data.put("uri", item.getUri());
            data.put("captureId", item.getCaptureId());
            data.put("bundle", new JSONObject(JsonHandler.toMap(item.getAppBundle())));
            byte[] bytes = data.toString().getBytes(StandardCharsets.UTF_8);
            String token = UUID.randomUUID().toString();
            AtomicFile file = new AtomicFile(new File(directory, token + ".json"));
            FileOutputStream output = null;
            try {
                output = file.startWrite();
                output.write(bytes);
                output.getFD().sync();
                file.finishWrite(output);
                if (!Arrays.equals(bytes, file.readFully())) throw new java.io.IOException("详情保存校验失败");
            } catch (Exception error) {
                if (output != null) file.failWrite(output);
                throw error;
            }
            return token;
        } catch (Exception error) {
            throw new IllegalStateException("详情记录保存失败", error);
        }
    }

    public static ItemData load(Context context, String token) {
        if (token == null || !token.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("详情记录标识无效");
        }
        try {
            File directory = new File(context.getFilesDir(), "detail_payloads");
            AtomicFile file = new AtomicFile(new File(directory, token + ".json"));
            JSONObject data = new JSONObject(new String(file.readFully(), StandardCharsets.UTF_8));
            if (data.getInt("version") != 1) throw new IllegalArgumentException("不支持的详情记录版本");
            Bundle bundle = JsonHandler.toBundle(data.getJSONObject("bundle"));
            String targetPackage = bundle.getString("componentPackageName");
            if (targetPackage == null || targetPackage.isEmpty()) targetPackage = bundle.getString("componentClassName");
            if (targetPackage == null || targetPackage.isEmpty() || "null".equals(targetPackage)) targetPackage = bundle.getString("packageName");
            ItemData item = new ItemData(AppInfoHelper.getAppInfo(context, targetPackage).getAppIcon(), data.optString("appName"),
                    data.optString("from"), data.optString("data"), data.optString("time"), data.optString("size"),
                    bundle, data.optString("category"), data.optString("stack"), data.optString("uri"));
            item.setCaptureId(data.optLong("captureId"));
            return item;
        } catch (Exception error) {
            throw new IllegalStateException("详情记录读取失败", error);
        }
    }
}
