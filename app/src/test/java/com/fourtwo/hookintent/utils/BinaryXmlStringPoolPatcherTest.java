package com.fourtwo.hookintent.utils;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class BinaryXmlStringPoolPatcherTest {
    @Test
    public void replacesAll64SlotsIncludingCaseSensitiveLastScheme() throws Exception {
        Map<String, String> replacements = new LinkedHashMap<>();
        for (int i = 0; i < 64; i++) {
            replacements.put(String.format(Locale.ROOT, "blockslot%02d", i),
                    i == 63 ? "CaseSensitive" : "scheme" + i);
        }
        byte[] result = BinaryXmlStringPoolPatcher.patchManifest(
                manifest(new ArrayList<>(replacements.keySet())), replacements);
        assertEquals(result.length, ByteBuffer.wrap(result).order(ByteOrder.LITTLE_ENDIAN).getInt(4));
        String data = new String(result, StandardCharsets.UTF_8);
        assertTrue(data.contains("CaseSensitive"));
        assertTrue(data.contains("scheme62"));
    }

    @Test(expected = IllegalStateException.class)
    public void refusesOldTemplateWithMissingLastSlot() throws Exception {
        Map<String, String> replacements = new LinkedHashMap<>();
        List<String> strings = new ArrayList<>();
        for (int i = 0; i < 64; i++) {
            String placeholder = String.format(Locale.ROOT, "blockslot%02d", i);
            replacements.put(placeholder, "scheme" + i);
            strings.add(i == 63 ? "dewulink" : placeholder);
        }
        BinaryXmlStringPoolPatcher.patchManifest(manifest(strings), replacements);
    }

    private byte[] manifest(List<String> strings) throws Exception {
        List<Integer> offsets = new ArrayList<>();
        ByteArrayOutputStream data = new ByteArrayOutputStream();
        for (String value : strings) {
            offsets.add(data.size());
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            data.write(value.length());
            data.write(bytes.length);
            data.write(bytes);
            data.write(0);
        }
        while (data.size() % 4 != 0) {
            data.write(0);
        }
        int stringsStart = 28 + 4 * strings.size();
        int poolSize = stringsStart + data.size();
        ByteBuffer xml = ByteBuffer.allocate(8 + poolSize).order(ByteOrder.LITTLE_ENDIAN);
        xml.putShort((short) 3).putShort((short) 8).putInt(xml.capacity());
        xml.putShort((short) 1).putShort((short) 28).putInt(poolSize);
        xml.putInt(strings.size()).putInt(0).putInt(0x100).putInt(stringsStart).putInt(0);
        for (int offset : offsets) {
            xml.putInt(offset);
        }
        xml.put(data.toByteArray());
        return xml.array();
    }
}
