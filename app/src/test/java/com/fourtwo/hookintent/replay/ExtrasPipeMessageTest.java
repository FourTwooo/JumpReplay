package com.fourtwo.hookintent.replay;

import android.app.Application;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application=Application.class,sdk=33)
public class ExtrasPipeMessageTest {
    @Test public void completeLegacyJsonReplyReturnsWhileAnotherPipeWriterIsStillOpen() throws Exception {
        PipedInputStream input=new PipedInputStream(8192); PipedOutputStream output=new PipedOutputStream(input);
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try {
            Future<JSONObject> response=worker.submit(() -> ExtrasRuntime.readJson(input));
            output.write("{\"ok\":true,\"record\":{\"capture_sdk\":33,\"source_app_uid\":10161,\"source_last_update\":1794552610773},\"nodes\":[null,7,\"中文\"]}".getBytes(StandardCharsets.UTF_8));
            output.flush(); // Deliberately keep the writer open, like a lazy Bundle's duplicated FD.
            JSONObject value=response.get(3,TimeUnit.SECONDS);
            assertTrue(value.getBoolean("ok")); assertEquals(1794552610773L,value.getJSONObject("record").getLong("source_last_update"));
            assertTrue(value.getJSONObject("record").get("capture_sdk") instanceof Integer);
            assertTrue(value.getJSONObject("record").get("source_app_uid") instanceof Integer);
            assertTrue(value.getJSONArray("nodes").isNull(0)); assertEquals("中文",value.getJSONArray("nodes").getString(2));
        } finally { output.close(); input.close(); worker.shutdownNow(); }
    }
}
