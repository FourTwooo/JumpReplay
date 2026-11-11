package com.fourtwo.hookintent.utils;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;

import androidx.core.content.FileProvider;

import com.android.apksig.ApkSigner;
import com.fourtwo.hookintent.data.Constants;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.HashMap;
import java.util.Collections;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;
import java.io.FilterOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.widget.Toast;

import javax.security.auth.x500.X500Principal;

import java.math.BigInteger;
import java.util.Calendar;

public final class BlockerApkBuilder {

    private static final int MAX_SCHEME_SLOTS = 64;
    private static final String TEMPLATE_PACKAGE = "com.fourtwo.blockertemplate";
    private static final String TEMPLATE_LABEL = "意图拦截器";
    private static final String SIGNER_ALIAS = "hookintent_blocker_signer";

    private BlockerApkBuilder() {
    }

    public static synchronized File build(Context context, JSONObject spec) throws Exception {
        checkInterrupted();
        File blockerDir = new File(context.getFilesDir(), "blocker");
        if (!blockerDir.exists()) {
            blockerDir.mkdirs();
        }

        File unsignedApk = new File(blockerDir, "blocker_unsigned.apk");
        File signedApk = new File(blockerDir, "blocker_signed.apk");

        patchTemplateApk(context, spec, unsignedApk);
        checkInterrupted();
        signApk(unsignedApk, signedApk);

        return signedApk;
    }

    private static void checkInterrupted() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("拦截器生成已取消");
        }
    }

    public static void install(Context context, File apkFile) {
        if (apkFile == null || !apkFile.exists()) {
            throw new IllegalStateException("APK 文件不存在");
        }

        Uri apkUri = FileProvider.getUriForFile(
                context,
                context.getPackageName() + ".fileprovider",
                apkFile
        );

        // 方案1：优先使用标准安装 Intent，不做 canRequestPackageInstalls 的硬判断
        Intent installIntent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
        installIntent.setData(apkUri);
        installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        installIntent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
        installIntent.putExtra(Intent.EXTRA_RETURN_RESULT, false);
        installIntent.putExtra(Intent.EXTRA_INSTALLER_PACKAGE_NAME, context.getPackageName());

        try {
            context.startActivity(installIntent);
            return;
        } catch (Throwable ignored) {
        }

        // 方案2：回退到老式 VIEW 安装方式
        Intent viewIntent = new Intent(Intent.ACTION_VIEW);
        viewIntent.setDataAndType(apkUri, "application/vnd.android.package-archive");
        viewIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        viewIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            context.startActivity(viewIntent);
            return;
        } catch (Throwable ignored) {
        }

        // 方案3：如果前两种都拉不起来，再跳未知来源安装权限页
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                Intent settingsIntent = new Intent(
                        Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + context.getPackageName())
                );
                settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(settingsIntent);
                return;
            } catch (Throwable ignored) {
            }
        }

        throw new IllegalStateException("无法拉起安装器");
    }

    private static void writeDeflatedEntry(ZipOutputStream zos, String name, byte[] data, long time) throws Exception {
        ZipEntry entry = new ZipEntry(name);
        if (time > 0) {
            entry.setTime(time);
        }
        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static void writeStoredAlignedEntry(ZipOutputStream zos,
                                                CountingOutputStream cos,
                                                String name,
                                                byte[] data,
                                                long time,
                                                int alignment) throws Exception {

        CRC32 crc32 = new CRC32();
        crc32.update(data);

        ZipEntry entry = new ZipEntry(name);
        if (time > 0) {
            entry.setTime(time);
        }

        entry.setMethod(ZipEntry.STORED);
        entry.setSize(data.length);
        entry.setCompressedSize(data.length);
        entry.setCrc(crc32.getValue());

        int nameLen = name.getBytes(StandardCharsets.UTF_8).length;

        // ZIP local header:
        // 30 bytes fixed + fileName + extra
        long dataOffsetWithoutExtra = cos.getCount() + 30L + nameLen;
        int padding = (int) ((alignment - (dataOffsetWithoutExtra % alignment)) % alignment);

        if (padding > 0) {
            entry.setExtra(new byte[padding]);
        }

        zos.putNextEntry(entry);
        zos.write(data);
        zos.closeEntry();
    }

    private static final class CountingOutputStream extends FilterOutputStream {

        private long count = 0L;

        CountingOutputStream(OutputStream out) {
            super(out);
        }

        long getCount() {
            return count;
        }

        @Override
        public void write(int b) throws java.io.IOException {
            out.write(b);
            count++;
        }

        @Override
        public void write(byte[] b) throws java.io.IOException {
            out.write(b);
            count += b.length;
        }

        @Override
        public void write(byte[] b, int off, int len) throws java.io.IOException {
            out.write(b, off, len);
            count += len;
        }
    }

    private static void patchTemplateApk(Context context, JSONObject spec, File outApk) throws Exception {
        Map<String, String> replacements = buildManifestReplacements(spec);

        try (InputStream inputStream = context.getAssets().open(Constants.BLOCKER_TEMPLATE_ASSET);
             ZipInputStream zis = new ZipInputStream(new BufferedInputStream(inputStream));
             CountingOutputStream cos = new CountingOutputStream(new BufferedOutputStream(new FileOutputStream(outApk)));
             ZipOutputStream zos = new ZipOutputStream(cos)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                checkInterrupted();
                String name = entry.getName();

                if (isSignatureEntry(name)) {
                    continue;
                }

                byte[] data = readAllBytes(zis);

                if ("AndroidManifest.xml".equals(name)) {
                    data = BinaryXmlStringPoolPatcher.patchManifest(data, replacements);
                }

                if ("resources.arsc".equals(name)) {
                    writeStoredAlignedEntry(zos, cos, name, data, entry.getTime(), 4);
                } else {
                    writeDeflatedEntry(zos, name, data, entry.getTime());
                }
            }
        }
    }

    private static Map<String, String> buildManifestReplacements(JSONObject spec) throws Exception {
        String blockerPackage = trimOrDefault(spec.optString("blockerPackage"), "com.fourtwo.hookscheme");
        String blockerLabel = trimOrDefault(spec.optString("blockerLabel"), "意图拦截器");

        validatePackageName(blockerPackage);

        List<String> schemes = collectUniqueSchemes(spec.optJSONArray("rules"));
        if (schemes.size() > MAX_SCHEME_SLOTS) {
            throw new IllegalStateException("选中的 scheme 数量超过模板上限：" + MAX_SCHEME_SLOTS);
        }

        Map<String, String> replacements = new HashMap<>();
        replacements.put(TEMPLATE_PACKAGE, blockerPackage);
        replacements.put(TEMPLATE_LABEL, blockerLabel);

        for (int i = 0; i < MAX_SCHEME_SLOTS; i++) {
            String placeholder = String.format(Locale.US, "blockslot%02d", i);
            String replacement = i < schemes.size()
                    ? schemes.get(i)
                    : String.format(Locale.US, "zzblockunused%02d", i);
            replacements.put(placeholder, replacement);
        }

        return replacements;
    }

    private static List<String> collectUniqueSchemes(JSONArray rules) {
        Set<String> ordered = new LinkedHashSet<>();
        if (rules == null) {
            return new ArrayList<>();
        }

        for (int i = 0; i < rules.length(); i++) {
            JSONObject object = rules.optJSONObject(i);
            if (object == null) {
                continue;
            }

            String scheme = trimOrDefault(object.optString("scheme"), "");
            if (scheme.isEmpty()) {
                continue;
            }

            // Android matches schemes case-sensitively. Keep the spelling from the
            // application's manifest (and from custom input) intact.
            validateScheme(scheme);
            ordered.add(scheme);
        }

        return new ArrayList<>(ordered);
    }

    private static void validateScheme(String scheme) {
        if (!scheme.matches("^[a-zA-Z][a-zA-Z0-9+.-]*$")) {
            throw new IllegalStateException("非法 scheme: " + scheme);
        }
    }

    private static void validatePackageName(String packageName) {
        if (!packageName.matches("^[a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+$")) {
            throw new IllegalStateException("非法包名: " + packageName);
        }
    }

    private static String trimOrDefault(String value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? fallback : trimmed;
    }

    private static boolean isSignatureEntry(String name) {
        if (name == null) {
            return false;
        }
        String upper = name.toUpperCase(Locale.US);
        return upper.startsWith("META-INF/")
                && (upper.endsWith(".SF")
                || upper.endsWith(".RSA")
                || upper.endsWith(".DSA")
                || upper.endsWith(".EC")
                || upper.endsWith("MANIFEST.MF"));
    }

    private static byte[] readAllBytes(InputStream inputStream) throws Exception {
        byte[] buffer = new byte[8192];
        int len;
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        while ((len = inputStream.read(buffer)) != -1) {
            out.write(buffer, 0, len);
        }
        return out.toByteArray();
    }

    private static void signApk(File unsignedApk, File signedApk) throws Exception {
        if (signedApk.exists()) {
            //noinspection ResultOfMethodCallIgnored
            signedApk.delete();
        }

        KeyStore.PrivateKeyEntry entry = getOrCreateSigningKey();

        ApkSigner.SignerConfig signerConfig = new ApkSigner.SignerConfig.Builder(
                "blocker",
                entry.getPrivateKey(),
                Collections.singletonList((X509Certificate) entry.getCertificate())
        ).build();

        ApkSigner apkSigner = new ApkSigner.Builder(Collections.singletonList(signerConfig))
                .setInputApk(unsignedApk)
                .setOutputApk(signedApk)
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .build();

        apkSigner.sign();
    }

    private static KeyStore.PrivateKeyEntry getOrCreateSigningKey() throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        if (!keyStore.containsAlias(SIGNER_ALIAS)) {
            KeyPairGenerator generator = KeyPairGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_RSA,
                    "AndroidKeyStore"
            );

            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 25);

            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    SIGNER_ALIAS,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY
            )
                    .setDigests(KeyProperties.DIGEST_SHA256, KeyProperties.DIGEST_SHA512)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .setCertificateSubject(new X500Principal("CN=HookIntent Blocker"))
                    .setCertificateSerialNumber(BigInteger.ONE)
                    .setCertificateNotBefore(start.getTime())
                    .setCertificateNotAfter(end.getTime())
                    .build();

            generator.initialize(spec);
            generator.generateKeyPair();
        }

        return (KeyStore.PrivateKeyEntry) keyStore.getEntry(SIGNER_ALIAS, null);
    }
}
