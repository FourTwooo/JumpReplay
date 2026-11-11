package com.fourtwo.hookintent.base;

import android.app.Application;
import android.content.Context;
import com.fourtwo.hookintent.data.Constants;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class PersistenceTest {
    @Test public void nestedPrimitiveArraysAndNullSurviveJsonConversion() throws Exception {
        JSONObject original = new JSONObject("{\"nested\":{\"array\":[1,\"x\",null,[2,3]]}}");
        Map<String, Object> map = JsonHandler.toMap(original);
        JSONObject restored = new JSONObject(map);
        assertEquals(original.toString(), restored.toString());
    }
    @Test public void atomicFilterReadRecoversLegacyBackup() throws Exception {
        Context context = RuntimeEnvironment.getApplication();
        File base = new File(context.getExternalFilesDir(null), "filter_data.json");
        base.getParentFile().mkdirs();
        try (FileOutputStream output = new FileOutputStream(base)) { output.write("broken".getBytes(StandardCharsets.UTF_8)); }
        try (FileOutputStream output = new FileOutputStream(base + ".bak")) { output.write("{\"intent\":{\"from\":[]}}".getBytes(StandardCharsets.UTF_8)); }
        assertNotNull(new JsonHandler().readJsonFromFile(context).get("intent"));
        assertFalse(new File(base + ".bak").exists());
    }
    @Test public void failedFavoriteWriteReturnsFalseAndReopenKeepsSavedRecord() throws Exception {
        LocalDatabaseManager database = new LocalDatabaseManager(RuntimeEnvironment.getApplication());
        database.openDatabase("favorite_test"); database.createTable(Constants.STAR_TABLE_NAME);
        JSONObject record = new JSONObject(); record.put(Constants.SQL_HASH, "same"); record.put(Constants.SQL_DATA, "payload");
        assertTrue(database.insertOrUpdateData(Constants.STAR_TABLE_NAME, record));
        assertFalse(database.insertOrUpdateData(Constants.STAR_TABLE_NAME, new JSONObject()));
        database.closeDatabase();
        database.openDatabase("favorite_test");
        assertTrue(database.is_exists(Constants.STAR_TABLE_NAME, "same"));
        database.closeDatabase();
    }

    @Test public void jsonObjectListEntriesAreConvertedToMapsAndEditorListsKeepIdentity() throws Exception {
        List<Object> objects = new ArrayList<>();
        objects.add(new JSONObject("{\"text\":\"demo\",\"type\":true}"));
        List<Map<String, Object>> converted = JsonHandler.getFilterValueJson(objects);
        assertEquals("demo", converted.get(0).get("text"));
        assertEquals(Boolean.TRUE, converted.get(0).get("type"));
        assertSame(converted, JsonHandler.getFilterValueJson(converted));
    }

    @Test public void largeFavoriteWithEmojiReadsAndDeletesWithoutCursorWindowOverflow() throws Exception {
        LocalDatabaseManager database = new LocalDatabaseManager(RuntimeEnvironment.getApplication());
        database.openDatabase("large_favorite_test");
        database.createTable(Constants.STAR_TABLE_NAME);
        StringBuilder payloadBuilder = new StringBuilder();
        // Cross many 65536-character boundaries with surrogate pairs throughout.
        for (int i = 0; i < 400_000; i++) payloadBuilder.append("a\uD83D\uDE00\u4E2D");
        String payload = payloadBuilder.toString();
        JSONObject record = new JSONObject();
        record.put(Constants.SQL_HASH, "large");
        record.put(Constants.SQL_DATA, payload);
        try {
            assertTrue(database.insertOrUpdateData(Constants.STAR_TABLE_NAME, record));
            assertEquals(payload, database.getAllData(Constants.STAR_TABLE_NAME).get(0).getString(Constants.SQL_DATA));
            record.put(Constants.SQL_DATA, payload + "updated");
            assertTrue(database.insertOrUpdateData(Constants.STAR_TABLE_NAME, record));
            assertEquals(1, database.getAllData(Constants.STAR_TABLE_NAME).size());
            assertEquals(payload + "updated", database.getAllData(Constants.STAR_TABLE_NAME).get(0).getString(Constants.SQL_DATA));
            assertTrue(database.deleteData(Constants.STAR_TABLE_NAME, "large"));
            assertFalse(database.is_exists(Constants.STAR_TABLE_NAME, "large"));
            assertTrue(database.getAllData(Constants.STAR_TABLE_NAME).isEmpty());
        } finally { database.closeDatabase(); }
    }
}
