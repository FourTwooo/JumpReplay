package com.fourtwo.hookintent;

import android.app.Application;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class IntentEntryActivitiesTest {
    @Test public void emptyUriEditorDoesNotCrash() {
        try (ActivityController<IntentIntercept> controller = Robolectric.buildActivity(IntentIntercept.class,
                new Intent(Intent.ACTION_VIEW, Uri.parse("demo://host?q=a+b%26c"))).setup()) {
            EditText editor = controller.get().findViewById(R.id.url_text_view);
            assertNotNull(editor);
            editor.setText("");
            assertEquals("", editor.getText().toString());
        }
    }

    @Test public void newShortcutReplacesExistingParameterDialogAndKeepsLegacyProtocol() {
        Intent first = new Intent().putExtra("target_intent", "demo://host?q=VALUE")
                .putExtra("replace_params", new String[]{"VALUE"});
        try (ActivityController<ShortcutActivity> controller = Robolectric.buildActivity(ShortcutActivity.class, first).setup()) {
            assertFalse(controller.get().isFinishing());
            Intent second = new Intent().putExtra("target_intent", "demo://second?q=a+b%26c");
            controller.newIntent(second);
            Intent launched = Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertNotNull(launched);
            assertEquals("demo://second?q=a+b%26c", launched.getDataString());
            assertTrue(controller.get().isFinishing());
        }
    }
}
