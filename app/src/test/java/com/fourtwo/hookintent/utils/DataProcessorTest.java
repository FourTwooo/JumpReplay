package com.fourtwo.hookintent.utils;

import android.app.Application;
import android.os.Bundle;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ItemData;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 28)
public class DataProcessorTest {
    private Bundle scheme(String value) {
        Bundle record = new Bundle();
        record.putString("category", "Scheme");
        record.putString("scheme_raw_url", value);
        record.putString("time", "2026-11-10 12:00:00");
        return record;
    }
    @Test public void opaqueSchemesAndFollowingRecordsSurviveOneMalformedRecord() {
        Bundle broken = new Bundle(); broken.putString("category", "Scheme");
        Bundle batch = new Bundle();
        batch.putString("batch_data_binder", JsonHandler.toJson(Arrays.asList(scheme("tel:123"), broken, scheme("mailto:a@example.org"), scheme("https://example.org/ok"))));
        ArrayList<ItemData> records = new ArrayList<>();
        DataProcessor processor = new DataProcessor(RuntimeEnvironment.getApplication());
        processor.processReceivedData(batch, records::add);
        assertEquals(3, records.size());
        assertEquals("tel:123", records.get(0).getItem_from());
        assertEquals("mailto:a@example.org", records.get(1).getItem_from());
        assertNotNull(processor.getLastError());
    }
    @Test public void consecutiveDedupPreservesInputAndExistingExcludedFields() {
        Bundle first = scheme("https://example.org/ok");
        first.putString("FunctionCall", "first");
        first.putString("from", "source1");
        Bundle second = new Bundle(first);
        second.putString("time", "2026-11-10 12:01:00");
        second.putString("FunctionCall", "second");
        second.putString("from", "source2");
        IntentDuplicateChecker checker = new IntentDuplicateChecker();
        assertFalse(checker.isDuplicate(first));
        assertTrue(checker.isDuplicate(second));
        assertEquals("first", first.getString("FunctionCall"));
        assertEquals("source2", second.getString("from"));
        assertFalse(first.containsKey("stack_trace"));
    }
    @Test public void componentPackageParsingHandlesAliasesAndFlattenedNames() {
        assertEquals("example.app", AppInfoHelper.packageFromComponent("example.app/library.Entry"));
        assertEquals("example.app", AppInfoHelper.packageFromComponent("ComponentInfo{example.app/.Alias}"));
        assertNull(AppInfoHelper.packageFromComponent(null));
    }

    @Test public void nonObjectRecordsAreReportedWithoutDiscardingFollowingRecords() {
        Bundle batch = new Bundle();
        batch.putString("batch_data_binder", "[123,null,{\"category\":\"Scheme\",\"scheme_raw_url\":\"tel:456\"}]");
        ArrayList<ItemData> records = new ArrayList<>();
        DataProcessor processor = new DataProcessor(RuntimeEnvironment.getApplication());
        processor.processReceivedData(batch, records::add);
        assertEquals(1, records.size());
        assertEquals("tel:456", records.get(0).getItem_from());
        assertNotNull(processor.getLastError());
    }

    @Test public void malformedBatchIsReportedAndNextValidBatchClearsError() {
        DataProcessor processor = new DataProcessor(RuntimeEnvironment.getApplication());
        Bundle batch = new Bundle();
        batch.putString("batch_data_binder", "not-json");
        ArrayList<ItemData> records = new ArrayList<>();
        processor.processReceivedData(batch, records::add);
        assertNotNull(processor.getLastError());
        assertTrue(records.isEmpty());
        batch.putString("batch_data_binder", JsonHandler.toJson(Arrays.asList(scheme("tel:1"))));
        processor.processReceivedData(batch, records::add);
        assertNull(processor.getLastError());
        assertEquals(1, records.size());
    }

    @Test public void failedConversionDoesNotAdvanceDeduplication() {
        DataProcessor processor = new DataProcessor(RuntimeEnvironment.getApplication());
        HashMap<String, Object> filters = new HashMap<>();
        filters.put("Scheme", new HashMap<String, Object>());
        processor.setJsonData(filters);
        Bundle batch = new Bundle();
        batch.putString("batch_data_binder", JsonHandler.toJson(Arrays.asList(scheme("tel:1"))));
        processor.processReceivedData(batch, item -> { throw new IllegalStateException("storage failed"); });
        assertNotNull(processor.getLastError());
        ArrayList<ItemData> records = new ArrayList<>();
        processor.processReceivedData(batch, records::add);
        assertEquals(1, records.size());
        assertNull(processor.getLastError());
    }

    @Test public void configuredNoiseFilterStillIgnoresRelativePathsAndPreservesOpaqueSchemes() {
        DataProcessor processor = new DataProcessor(RuntimeEnvironment.getApplication());
        HashMap<String, Object> filters = new HashMap<>();
        filters.put("Scheme", new HashMap<String, Object>());
        processor.setJsonData(filters);
        Bundle batch = new Bundle();
        batch.putString("batch_data_binder", JsonHandler.toJson(Arrays.asList(scheme("relative/path"), scheme("tel:1"), scheme("mailto:a@example.org"))));
        ArrayList<ItemData> records = new ArrayList<>();
        processor.processReceivedData(batch, records::add);
        assertEquals(2, records.size());
        assertNull(processor.getLastError());
    }

    @Test public void preservesArgumentsAndLabelsForHistoricalNavigationRecords() throws Exception {
        DataProcessor processor = new DataProcessor(RuntimeEnvironment.getApplication());
        ArrayList<ItemData> records = new ArrayList<>();
        for (String category : Arrays.asList("NavController", "ViewPager")) {
            Bundle arguments = new Bundle();
            arguments.putInt("position", 1);
            processor.processBundle(navigationRecord(category, arguments), records::add);
        }
        assertEquals(2, records.size());
        assertEquals("NavController", records.get(0).getCategory());
        assertEquals("ViewPager", records.get(1).getCategory());
        for (ItemData record : records) {
            assertEquals("example.app.Page", record.getItem_from());
            assertEquals(1, new JSONObject(record.getItem_data()).getInt("position"));
        }
    }

    @Test public void keepsPagerReplayInputsSeparateFromFragmentArguments() {
        DataProcessor processor = new DataProcessor(RuntimeEnvironment.getApplication());
        Bundle record = navigationRecord("Fragment", Bundle.EMPTY);
        Bundle editedArguments = new Bundle();
        editedArguments.putInt("position", 1);
        Bundle replay = new Bundle();
        replay.putBundle("arguments", editedArguments);
        record.putBundle(Constants.NAVIGATION_REPLAY, replay);
        ArrayList<ItemData> records = new ArrayList<>();
        processor.processBundle(record, records::add);
        ItemData processed = records.get(0);
        assertEquals("Fragment", processed.getCategory());
        assertEquals("example.app.Page", processed.getItem_from());
        assertEquals("", processed.getItem_data());
        assertEquals(1, processed.getAppBundle().getBundle(Constants.NAVIGATION_REPLAY)
                .getBundle("arguments").getInt("position"));
    }

    private Bundle navigationRecord(String category, Bundle arguments) {
        Bundle record = new Bundle();
        record.putString("category", category);
        record.putString("title", "example.app.Page");
        record.putString("packageName", "example.app");
        record.putString("time", "2026-11-10 12:00:00");
        record.putBundle(Constants.NAVIGATION_ARGUMENTS, arguments);
        return record;
    }

}
