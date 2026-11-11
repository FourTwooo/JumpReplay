package com.fourtwo.hookintent.xposed;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import java.util.List;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class IntentJournalTest {
    @Test public void completeHistorySurvivesReopenAndPagingKeepsTypedExtras() throws Exception {
        String purpose = "test-intents-" + java.util.UUID.randomUUID();
        IntentJournal journal = new IntentJournal(RuntimeEnvironment.getApplication(), purpose);
        for (int index = 0; index < 151; index++) journal.append(new Intent(Intent.ACTION_VIEW,
                Uri.parse("sample://item/" + index)).putExtra("index", index)
                .putExtra("values", new String[]{"完整😀", "value"}).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        journal = new IntentJournal(RuntimeEnvironment.getApplication(), purpose);
        assertEquals(151, journal.count());
        List<Intent> last = journal.page(100, 100);
        assertEquals(51, last.size());
        assertEquals(150, last.get(50).getIntExtra("index", -1));
        assertArrayEquals(new String[]{"完整😀", "value"}, last.get(50).getStringArrayExtra("values"));
        assertEquals(Intent.FLAG_ACTIVITY_CLEAR_TOP, last.get(50).getFlags());
        journal.clear(); assertEquals(0, journal.count());
    }
}
