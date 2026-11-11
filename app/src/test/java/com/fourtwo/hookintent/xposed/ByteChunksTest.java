package com.fourtwo.hookintent.xposed;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class ByteChunksTest {
    @Test public void boundariesDoNotCreateEmptyFinalFrame() {
        assertEquals(1, ByteChunks.count(1));
        assertEquals(1, ByteChunks.count(ByteChunks.PAYLOAD_BYTES));
        assertEquals(2, ByteChunks.count(ByteChunks.PAYLOAD_BYTES + 1));
    }
    @Test public void largeUnicodeRecordSurvivesByteFramingWithoutTruncation() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < 400000; index++) text.append("跳转😀&参数=").append(index);
        byte[] source = text.toString().getBytes(StandardCharsets.UTF_8);
        assertTrue(source.length > 1024 * 1024);
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (int index = 0; index < ByteChunks.count(source.length); index++) {
            byte[] frame = ByteChunks.part(source, index);
            assertTrue(frame.length <= ByteChunks.PAYLOAD_BYTES);
            joined.write(frame);
        }
        assertArrayEquals(source, joined.toByteArray());
        assertEquals(text.toString(), new String(joined.toByteArray(), StandardCharsets.UTF_8));
    }
    @Test(expected = IllegalArgumentException.class) public void negativeFrameIsRejected() { ByteChunks.part(new byte[1], -1); }
    @Test(expected = IllegalArgumentException.class) public void emptyTransferIsRejected() { ByteChunks.count(0); }
    @Test public void checksumUsesRawBytes() throws Exception {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                ByteChunks.sha256(new java.io.ByteArrayInputStream("abc".getBytes(StandardCharsets.UTF_8))));
    }
}
