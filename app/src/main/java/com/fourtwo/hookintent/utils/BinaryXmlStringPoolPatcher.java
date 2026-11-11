package com.fourtwo.hookintent.utils;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.HashSet;
import java.util.Set;

public final class BinaryXmlStringPoolPatcher {

    private static final int RES_STRING_POOL_TYPE = 0x0001;

    private BinaryXmlStringPoolPatcher() {
    }

    public static byte[] patchManifest(byte[] xmlBytes, Map<String, String> replacements) throws Exception {
        if (xmlBytes == null || xmlBytes.length < 8) {
            throw new IllegalArgumentException("Invalid AndroidManifest.xml");
        }

        int stringPoolOffset = -1;
        int stringPoolChunkSize = -1;

        int offset = 8;
        while (offset + 8 <= xmlBytes.length) {
            int type = readU16(xmlBytes, offset);
            int chunkSize = readU32(xmlBytes, offset + 4);

            if (type == RES_STRING_POOL_TYPE) {
                stringPoolOffset = offset;
                stringPoolChunkSize = chunkSize;
                break;
            }

            if (chunkSize <= 0) {
                break;
            }
            offset += chunkSize;
        }

        if (stringPoolOffset < 0 || stringPoolChunkSize <= 0) {
            throw new IllegalStateException("String pool chunk not found in AndroidManifest.xml");
        }

        byte[] newStringPoolChunk = rebuildStringPoolChunk(xmlBytes, stringPoolOffset, replacements);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(xmlBytes, 0, stringPoolOffset);
        output.write(newStringPoolChunk);
        output.write(xmlBytes,
                stringPoolOffset + stringPoolChunkSize,
                xmlBytes.length - stringPoolOffset - stringPoolChunkSize);

        byte[] result = output.toByteArray();
        writeU32(result, 4, result.length);
        return result;
    }

    private static byte[] rebuildStringPoolChunk(byte[] xmlBytes,
                                                 int chunkOffset,
                                                 Map<String, String> replacements) throws Exception {

        int type = readU16(xmlBytes, chunkOffset);
        int headerSize = readU16(xmlBytes, chunkOffset + 2);
        int chunkSize = readU32(xmlBytes, chunkOffset + 4);

        int stringCount = readU32(xmlBytes, chunkOffset + 8);
        int styleCount = readU32(xmlBytes, chunkOffset + 12);
        int flags = readU32(xmlBytes, chunkOffset + 16);
        int stringsStart = readU32(xmlBytes, chunkOffset + 20);
        int stylesStart = readU32(xmlBytes, chunkOffset + 24);

        boolean utf8 = (flags & 0x00000100) != 0;

        int offsetsBase = chunkOffset + headerSize;
        List<String> strings = new ArrayList<>(stringCount);
        Set<String> missingReplacements = new HashSet<>(replacements.keySet());

        for (int i = 0; i < stringCount; i++) {
            int stringOffset = readU32(xmlBytes, offsetsBase + i * 4);
            int absolute = chunkOffset + stringsStart + stringOffset;
            String value = utf8 ? readUtf8String(xmlBytes, absolute) : readUtf16String(xmlBytes, absolute);

            if (replacements.containsKey(value)) {
                missingReplacements.remove(value);
                value = replacements.get(value);
            }
            strings.add(value);
        }

        if (!missingReplacements.isEmpty()) {
            throw new IllegalStateException("模板 Manifest 缺少占位符: " + missingReplacements);
        }

        byte[] styleData = new byte[0];
        if (styleCount > 0 && stylesStart > 0) {
            int styleAbs = chunkOffset + stylesStart;
            int styleLen = chunkOffset + chunkSize - styleAbs;
            if (styleLen > 0) {
                styleData = new byte[styleLen];
                System.arraycopy(xmlBytes, styleAbs, styleData, 0, styleLen);
            }
        }

        List<Integer> newOffsets = new ArrayList<>(stringCount);
        ByteArrayOutputStream stringsOut = new ByteArrayOutputStream();

        for (String value : strings) {
            newOffsets.add(stringsOut.size());
            byte[] encoded = utf8 ? encodeUtf8String(value) : encodeUtf16String(value);
            stringsOut.write(encoded);
        }

        while ((stringsOut.size() % 4) != 0) {
            stringsOut.write(0);
        }

        byte[] stringData = stringsOut.toByteArray();

        int newStringsStart = headerSize + stringCount * 4 + styleCount * 4;
        int newStylesStart = styleCount == 0 ? 0 : (newStringsStart + stringData.length);
        int newChunkSize = (styleCount == 0)
                ? (newStringsStart + stringData.length)
                : (newStylesStart + styleData.length);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        writeU16(out, type);
        writeU16(out, headerSize);
        writeU32(out, newChunkSize);
        writeU32(out, stringCount);
        writeU32(out, styleCount);
        writeU32(out, flags);
        writeU32(out, newStringsStart);
        writeU32(out, newStylesStart);

        for (int offsetValue : newOffsets) {
            writeU32(out, offsetValue);
        }

        for (int i = 0; i < styleCount; i++) {
            // 保留 style offsets 个数，简单写 0，模板 manifest 一般 styleCount=0
            writeU32(out, 0);
        }

        out.write(stringData);

        if (styleCount > 0) {
            out.write(styleData);
        }

        return out.toByteArray();
    }

    private static String readUtf8String(byte[] bytes, int offset) {
        IntWithSize charLen = readUtf8Length(bytes, offset);
        IntWithSize byteLen = readUtf8Length(bytes, offset + charLen.size);
        int strStart = offset + charLen.size + byteLen.size;
        int len = byteLen.value;
        return new String(bytes, strStart, len, java.nio.charset.StandardCharsets.UTF_8);
    }

    private static String readUtf16String(byte[] bytes, int offset) {
        IntWithSize charLen = readUtf16Length(bytes, offset);
        int strStart = offset + charLen.size;
        int byteLen = charLen.value * 2;
        return new String(bytes, strStart, byteLen, java.nio.charset.StandardCharsets.UTF_16LE);
    }

    private static byte[] encodeUtf8String(String value) throws Exception {
        byte[] utf8 = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        int charLen = value.length();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUtf8Length(out, charLen);
        writeUtf8Length(out, utf8.length);
        out.write(utf8);
        out.write(0);
        return out.toByteArray();
    }

    private static byte[] encodeUtf16String(String value) throws Exception {
        byte[] utf16 = value.getBytes(java.nio.charset.StandardCharsets.UTF_16LE);
        int charLen = value.length();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        writeUtf16Length(out, charLen);
        out.write(utf16);
        out.write(0);
        out.write(0);
        return out.toByteArray();
    }

    private static IntWithSize readUtf8Length(byte[] bytes, int offset) {
        int first = bytes[offset] & 0xFF;
        if ((first & 0x80) == 0) {
            return new IntWithSize(first, 1);
        }
        int second = bytes[offset + 1] & 0xFF;
        return new IntWithSize(((first & 0x7F) << 8) | second, 2);
    }

    private static IntWithSize readUtf16Length(byte[] bytes, int offset) {
        int first = readU16(bytes, offset);
        if ((first & 0x8000) == 0) {
            return new IntWithSize(first, 2);
        }
        int second = readU16(bytes, offset + 2);
        return new IntWithSize(((first & 0x7FFF) << 16) | second, 4);
    }

    private static void writeUtf8Length(ByteArrayOutputStream out, int value) {
        if (value > 0x7F) {
            out.write(((value >> 8) & 0x7F) | 0x80);
            out.write(value & 0xFF);
        } else {
            out.write(value);
        }
    }

    private static void writeUtf16Length(ByteArrayOutputStream out, int value) {
        if (value > 0x7FFF) {
            writeU16(out, ((value >> 16) & 0x7FFF) | 0x8000);
            writeU16(out, value & 0xFFFF);
        } else {
            writeU16(out, value);
        }
    }

    private static int readU16(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF) | ((bytes[offset + 1] & 0xFF) << 8);
    }

    private static int readU32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xFF)
                | ((bytes[offset + 1] & 0xFF) << 8)
                | ((bytes[offset + 2] & 0xFF) << 16)
                | ((bytes[offset + 3] & 0xFF) << 24);
    }

    private static void writeU16(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >> 8) & 0xFF);
    }

    private static void writeU32(byte[] bytes, int offset, int value) {
        bytes[offset] = (byte) (value & 0xFF);
        bytes[offset + 1] = (byte) ((value >> 8) & 0xFF);
        bytes[offset + 2] = (byte) ((value >> 16) & 0xFF);
        bytes[offset + 3] = (byte) ((value >> 24) & 0xFF);
    }

    private static void writeU16(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    private static void writeU32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static final class IntWithSize {
        final int value;
        final int size;

        IntWithSize(int value, int size) {
            this.value = value;
            this.size = size;
        }
    }
}
