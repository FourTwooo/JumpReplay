package com.fourtwo.hookintent;

import android.app.Application;
import android.os.Bundle;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AlertDialog;
import com.fourtwo.hookintent.ui.home.HomeFragment;
import com.google.android.material.textfield.TextInputLayout;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33)
public class InputControlsTest {
    @org.junit.After public void closeRepositoryFixture() throws Exception {
        Class<?> type = com.fourtwo.hookintent.data.CaptureRepository.class;
        java.lang.reflect.Field singleton = type.getDeclaredField("instance"); singleton.setAccessible(true);
        Object repository = singleton.get(null);
        if (repository == null) return;
        java.lang.reflect.Field workerField = type.getDeclaredField("worker"); workerField.setAccessible(true);
        java.util.concurrent.ExecutorService worker = (java.util.concurrent.ExecutorService) workerField.get(repository);
        worker.shutdown(); assertTrue(worker.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
        java.lang.reflect.Field handler = type.getDeclaredField("main"); handler.setAccessible(true);
        ((android.os.Handler) handler.get(repository)).removeCallbacksAndMessages(null);
        java.lang.reflect.Field store = type.getDeclaredField("store"); store.setAccessible(true);
        ((android.database.sqlite.SQLiteOpenHelper) store.get(repository)).close();
        singleton.set(null, null);
    }
    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.Theme_HookIntent_NoActionBar);
            super.onCreate(state);
            FrameLayout frame = new FrameLayout(this); frame.setId(12345); setContentView(frame);
        }
    }
    @Test public void homeDoesNotAutofocusAndRegexCanBeTurnedOffAfterAnError() {
        try (ActivityController<Host> controller = Robolectric.buildActivity(Host.class).setup()) {
            controller.get().getSupportFragmentManager().beginTransaction().add(12345, new HomeFragment()).commitNow();
            EditText input = controller.get().findViewById(R.id.searchEditText);
            assertFalse(input.hasFocus());
            TextInputLayout box = controller.get().findViewById(R.id.home_search_box);
            box.findViewById(com.google.android.material.R.id.text_input_end_icon).performClick();
            input.setText("["); assertNotNull(input.getError());
            box.findViewById(com.google.android.material.R.id.text_input_end_icon).performClick();
            assertNull(input.getError());
        }
    }
    @Test public void dynamicRowsHaveSpacingAndDeletingTheMiddleKeepsOtherValues() {
        Intent incoming = new Intent(Intent.ACTION_VIEW, Uri.parse("demo://preview"));
        try (ActivityController<IntentIntercept> controller = Robolectric.buildActivity(IntentIntercept.class, incoming).setup()) {
            controller.get().SetShortcut();
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            for (int i = 0; i < 3; i++) dialog.findViewById(R.id.add_param_button).performClick();
            LinearLayout rows = dialog.findViewById(R.id.dynamic_params_container);
            for (int i = 0; i < 3; i++) {
                LinearLayout row = (LinearLayout) rows.getChildAt(i);
                assertTrue(((LinearLayout.LayoutParams) row.getLayoutParams()).topMargin > 0);
                ((EditText) row.getChildAt(0)).setText("value" + i);
            }
            ((LinearLayout) rows.getChildAt(1)).getChildAt(1).performClick();
            assertEquals(2, rows.getChildCount());
            assertEquals("value0", ((EditText) ((LinearLayout) rows.getChildAt(0)).getChildAt(0)).getText().toString());
            assertEquals("value2", ((EditText) ((LinearLayout) rows.getChildAt(1)).getChildAt(0)).getText().toString());
            dialog.dismiss();
        }
    }

    @Test public void disabledSchemeOpensAndRegexSwitchWorksInsideTheField() {
        try (ActivityController<DisabledActivity> controller = Robolectric.buildActivity(DisabledActivity.class).setup()) {
            android.view.Menu menu = new android.widget.PopupMenu(controller.get(), new android.view.View(controller.get())).getMenu();
            assertTrue(controller.get().onOptionsItemSelected(menu.add(0, R.id.add_text, 0, "添加")));
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
            android.view.View icon = dialog.findViewById(com.google.android.material.R.id.text_input_end_icon);
            assertNotNull(icon);
            assertEquals(controller.get().getString(R.string.input_regex_off), icon.getContentDescription().toString());
            icon.performClick();
            assertEquals(controller.get().getString(R.string.input_regex_on), icon.getContentDescription().toString());
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertTrue(dialog.isShowing());
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        }
    }
}
