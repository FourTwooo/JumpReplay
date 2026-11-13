package com.fourtwo.hookintent.replay;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.List;

/** Loads the installed package's APK/splits without launching its Application. */
public final class ReplayClassLoaders {
    private ReplayClassLoaders() { }

    /** Repair legacy records whose package was overwritten by a later WebView load.
     * Require installed UID, process naming and exact update time to agree; never guess. */
    public static Bundle normalize(Context context, Bundle capture) throws Exception {
        Bundle result = new Bundle(capture);
        if (capture.containsKey("source_app_uid")) return result;
        int uid = capture.getInt("source_uid", -1);
        long updated = capture.getLong("source_last_update", 0);
        String process = capture.getString("processName", "");
        if (uid < 0 || updated == 0 || process.isEmpty()) return result;
        String[] packages = context.getPackageManager().getPackagesForUid(uid);
        String match = null;
        if (packages != null) for (String name : packages) {
            if (!process.equals(name) && !process.startsWith(name + ":")) continue;
            PackageInfo info = context.getPackageManager().getPackageInfo(name, 0);
            if (info.lastUpdateTime != updated) continue;
            if (match != null) return result;
            match = name;
        }
        if (match != null) result.putString("packageName", match);
        return result;
    }

    public static ClassLoader forRecord(Context context, Bundle record) throws Exception {
        return resolve(context, record, null);
    }

    /** The host resolves trusted installed-package paths; foreign bytecode runs in the sandbox only. */
    public static Bundle prepare(Context context, Bundle capture) throws Exception {
        capture = normalize(context, capture);
        validate(context, capture);
        Bundle result = new Bundle(capture);
        java.util.LinkedHashSet<String> dex = new java.util.LinkedHashSet<>();
        java.util.LinkedHashSet<String> nativePaths = new java.util.LinkedHashSet<>();
        for (String name : new String[]{capture.getString("packageName"), capture.getString("componentPackageName"), capture.getString("package")}) {
            if (name == null || name.isEmpty() || "null".equals(name)) continue;
            try {
                android.content.pm.ApplicationInfo info = context.getPackageManager().getApplicationInfo(name, 0);
                if (info.sourceDir != null) dex.add(info.sourceDir);
                if (info.splitSourceDirs != null) java.util.Collections.addAll(dex, info.splitSourceDirs);
                if (info.nativeLibraryDir != null) nativePaths.add(info.nativeLibraryDir);
            } catch (android.content.pm.PackageManager.NameNotFoundException ignored) { }
        }
        result.putStringArray("loader_dex_paths", dex.toArray(new String[0]));
        result.putStringArray("loader_native_paths", nativePaths.toArray(new String[0]));
        return result;
    }

    public static ClassLoader resolve(Context context, Bundle record, ClassLoader runtime) throws Exception {
        if (runtime == null && record.containsKey("loader_dex_paths")) {
            String[] paths = record.getStringArray("loader_dex_paths");
            String[] libraries = record.getStringArray("loader_native_paths");
            return new dalvik.system.PathClassLoader(android.text.TextUtils.join(java.io.File.pathSeparator, paths == null ? new String[0] : paths),
                    android.text.TextUtils.join(java.io.File.pathSeparator, libraries == null ? new String[0] : libraries),
                    android.content.Intent.class.getClassLoader());
        }
        if (runtime == null && context.getPackageName().equals(ExtrasRuntime.HOST))
            throw new IllegalStateException("自定义对象必须通过原应用进程或隔离编辑进程处理");
        validate(context, record);
        List<ClassLoader> delegates = new ArrayList<>();
        if (runtime != null) delegates.add(runtime);
        add(context, record.getString("packageName"), delegates);
        add(context, record.getString("componentPackageName"), delegates);
        add(context, record.getString("package"), delegates);
        delegates.add(context.getClassLoader());
        return new ClassLoader(android.content.Intent.class.getClassLoader()) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                for (ClassLoader delegate : delegates) {
                    try { return delegate.loadClass(name); }
                    catch (ClassNotFoundException ignored) { }
                }
                throw new ClassNotFoundException(name + "（未在已安装的来源/目标应用中找到；动态加载类需要原应用运行环境）");
            }
        };
    }

    private static void validate(Context context, Bundle record) throws Exception {
        String source = record.getString("packageName");
        if (source != null && !source.isEmpty() && !"null".equals(source)) {
            PackageInfo info = context.getPackageManager().getPackageInfo(source, 0);
            // source_uid in the first editor build was Process.myUid(): an isolated
            // process UID is not the owning package's installation UID.
            int capturedUid = record.getInt("source_app_uid", -1);
            if (capturedUid != -1 && info.applicationInfo != null && capturedUid != info.applicationInfo.uid)
                throw new IllegalStateException("来源应用安装身份不匹配：" + source + "，记录 UID=" + capturedUid
                        + "，当前 UID=" + info.applicationInfo.uid + "。请核对记录来源或重新捕获。");
            long captured = record.getLong("source_last_update", 0);
            if (captured != 0 && captured != info.lastUpdateTime) {
                throw new IllegalStateException("来源应用已更新，请重新捕获后编辑");
            }
        }
        int sdk = record.getInt("capture_sdk", 0);
        if (sdk != 0 && sdk != android.os.Build.VERSION.SDK_INT)
            throw new IllegalStateException("系统版本已变化，请重新捕获后编辑");
    }

    private static void add(Context context, String name, List<ClassLoader> loaders) throws Exception {
        if (name == null || name.isEmpty() || "null".equals(name)) return;
        try {
            Context packageContext = context.createPackageContext(name, Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
            ClassLoader loader = packageContext.getClassLoader();
            if (!loaders.contains(loader)) loaders.add(loader);
        } catch (android.content.pm.PackageManager.NameNotFoundException error) {
            // Implicit Intent targets can be absent; a captured source is checked separately above.
        }
    }
}
