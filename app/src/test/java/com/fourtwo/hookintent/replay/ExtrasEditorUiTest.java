package com.fourtwo.hookintent.replay;

import android.app.Application;
import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.service.MessengerService;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;

/** Real UI loading/failure/cancellation; cross-process editing is exercised on-device. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33, qualifiers = "w320dp-h640dp")
@LooperMode(LooperMode.Mode.PAUSED)
public class ExtrasEditorUiTest {
    public static class Host extends AppCompatActivity {
        @Override protected void onCreate(Bundle state) {
            setTheme(R.style.Theme_HookIntent_NoActionBar);
            super.onCreate(state);
            setContentView(new FrameLayout(this));
        }
    }

    @Before public void useOfflineProcessPath() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName("com.fourtwo.hookintent", MessengerService.class.getName()));
    }

    @Before public void makeIsolatedEndpointUnavailable() {
        Shadows.shadowOf(RuntimeEnvironment.getApplication()).declareComponentUnbindable(
                new ComponentName("com.fourtwo.hookintent", ExtrasSandboxService.class.getName()));
    }

    private static Bundle record() {
        Intent original = new Intent("example.action.OPEN");
        original.putExtra("name", "original");
        original.putExtra("count", 7);
        Bundle nested = new Bundle(); nested.putString("inside", "child");
        original.putExtra("nested", nested);
        Bundle result = new Bundle();
        result.putString(IntentPayloadCodec.KEY, IntentPayloadCodec.encode(original));
        result.putString("packageName", "com.fourtwo.hookintent");
        result.putString("category", "Intent");
        result.putString("capture_note", "must remain unchanged");
        return result;
    }

    @Test public void unavailableEditorShowsRetryAndNeverEnablesSave() {
        AtomicReference<Bundle> saved = new AtomicReference<>();
        try (ActivityController<Host> controller = Robolectric.buildActivity(Host.class).setup()) {
            ExtrasEditorDialog.show(controller.get(), record(), saved::set);
            AlertDialog parent = (AlertDialog) ShadowDialog.getLatestDialog();
            assertFalse(parent.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(parent.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            android.widget.Button retry = findButton(parent.getWindow().getDecorView(), controller.get().getString(R.string.extras_retry));
            assertNotNull(retry);
            assertEquals(View.VISIBLE, retry.getVisibility());
            assertTrue(retry.isEnabled());
            parent.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            assertNull(saved.get());
            assertTrue(parent.isShowing());
            parent.dismiss();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void reconnectAndCancelDoNotPublishOrMutateOriginal() {
        Bundle original = record();
        String encoded = original.getString(IntentPayloadCodec.KEY);
        AtomicReference<Bundle> saved = new AtomicReference<>();
        try (ActivityController<Host> controller = Robolectric.buildActivity(Host.class).setup()) {
            ExtrasEditorDialog.show(controller.get(), original, saved::set);
            AlertDialog parent = (AlertDialog) ShadowDialog.getLatestDialog();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            findButton(parent.getWindow().getDecorView(), controller.get().getString(R.string.extras_retry)).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(parent.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled());
            parent.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertFalse(parent.isShowing());
            assertNull(saved.get());
            assertEquals(encoded, original.getString(IntentPayloadCodec.KEY));
            assertEquals("original", IntentPayloadCodec.decode(original).getStringExtra("name"));
        }
    }

    @Test @Config(qualifiers = "w320dp-h640dp-night")
    public void narrowNightDialogLeavesVisibleSpaceForRowsAndButtons() throws Exception {
        try (ActivityController<Host> controller = Robolectric.buildActivity(Host.class).setup()) {
            ExtrasEditorDialog.show(controller.get(), record(), ignored -> { });
            AlertDialog parent = (AlertDialog) ShadowDialog.getLatestDialog();
            View decor = parent.getWindow().getDecorView();
            RecyclerView list = find(decor, RecyclerView.class);
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            int width = controller.get().getResources().getDisplayMetrics().widthPixels;
            int height = Math.round(controller.get().getResources().getDisplayMetrics().heightPixels * 0.8f);
            decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, width, height);
            assertTrue("Rows need usable vertical space", list.getMeasuredHeight() >= 48);
            assertTrue(list.getMeasuredWidth() > 0);
            assertTrue(parent.getButton(AlertDialog.BUTTON_POSITIVE).getMeasuredWidth() > 0);
            assertTrue(parent.getButton(AlertDialog.BUTTON_NEGATIVE).getMeasuredWidth() > 0);
            assertTrue("Dialog text must remain visible at night", androidx.core.graphics.ColorUtils.calculateContrast(
                    findButton(decor, controller.get().getString(R.string.extras_retry)).getCurrentTextColor(),
                    androidx.core.content.ContextCompat.getColor(controller.get(), R.color.dialog_surface)) > 4.5);
            parent.dismiss();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
    }

    @Test public void destroyingActivityClosesDialogAndDiscardsPendingOpen() {
        AtomicReference<Bundle> saved = new AtomicReference<>();
        ActivityController<Host> controller = Robolectric.buildActivity(Host.class).setup();
        ExtrasEditorDialog.show(controller.get(), record(), saved::set);
        AlertDialog parent = (AlertDialog) ShadowDialog.getLatestDialog();
        controller.pause().stop().destroy();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertFalse(parent.isShowing());
        assertNull(saved.get());
    }

    private static android.widget.Button findButton(View view, String label) {
        if (view instanceof android.widget.Button && ((TextView)view).getText().toString().equals(label)) return (android.widget.Button)view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            android.widget.Button found = findButton(((ViewGroup)view).getChildAt(i), label);
            if (found != null) return found;
        }
        return null;
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) return type.cast(view);
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup)view).getChildCount(); i++) {
            T child = find(((ViewGroup)view).getChildAt(i), type);
            if (child != null) return child;
        }
        return null;
    }
}
