package com.fourtwo.hookintent;

import android.app.Application;
import android.content.Intent;
import android.os.Bundle;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.AutoCompleteTextView;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import com.fourtwo.hookintent.utils.AppDialogs;
import com.fourtwo.hookintent.utils.DialogScrollView;
import com.fourtwo.hookintent.utils.StyledInputDialogHelper;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.After;
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
import yuku.ambilwarna.AmbilWarnaDialog;
import static org.junit.Assert.*;

/** Exercises real dialog widgets, including layout at the user's enlarged font size. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 33, qualifiers = "w360dp-h800dp")
@LooperMode(LooperMode.Mode.PAUSED)
public class DialogsConsistencyTest {
    public static class HostActivity extends AppCompatActivity {
        @Override protected void onCreate(Bundle savedState) {
            setTheme(R.style.Theme_HookIntent_NoActionBar);
            super.onCreate(savedState);
        }
    }

    @After public void resetFontScale() { RuntimeEnvironment.setFontScale(1f); }

    @Test public void dialogButtonsFitDefaultFont() { exerciseButtonLayout(1f); }
    @Test public void dialogButtonsFitOneAndHalfFont() { exerciseButtonLayout(1.5f); }
    @Test public void dialogButtonsFitDoubleFont() { exerciseButtonLayout(2f); }

    private void exerciseButtonLayout(float scale) {
        RuntimeEnvironment.setFontScale(scale);
        try (ActivityController<HostActivity> controller = Robolectric.buildActivity(HostActivity.class).setup()) {
            StyledInputDialogHelper.showSingleInputDialog(controller.get(), "替换参数", "参数值", "initial", (value, dialog) -> { });
            AlertDialog dialog = latestDialog();
            measureDialog(dialog);
            assertTextFits(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            assertTextFits(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            dialog.dismiss();
        }
    }

    @Test public void longWaitingMessageCanGrowAtDoubleFont() {
        RuntimeEnvironment.setFontScale(2f);
        try (ActivityController<HostActivity> controller = Robolectric.buildActivity(HostActivity.class).setup()) {
            String message = "请在 Shizuku 弹窗中允许授权\n完成后将继续启动目标应用";
            AppDialogs.ProgressContent content = AppDialogs.progress(controller.get(), message);
            AlertDialog dialog = AppDialogs.builder(controller.get()).setView(content).show();
            measureDialog(dialog);
            assertEquals(message, content.message.getText().toString());
            assertTrue(content.message.getLayout().getLineCount() > 1);
            assertTextFits(content.message);
            dialog.dismiss();
        }
    }

    @Test public void validationCanKeepInputOpenAndAcceptedValueIsTrimmed() {
        try (ActivityController<HostActivity> controller = Robolectric.buildActivity(HostActivity.class).setup()) {
            List<String> received = new ArrayList<>();
            StyledInputDialogHelper.showSingleInputDialog(controller.get(), "添加规则", "Scheme", "", (value, dialog) -> {
                received.add(value);
                if (!value.isEmpty()) dialog.dismiss();
            });
            AlertDialog dialog = latestDialog();
            EditText input = findInputs(dialog.getWindow().getDecorView()).get(0);
            input.setText("   ");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertTrue(dialog.isShowing());
            assertEquals("", received.get(0));
            input.setText("  demo://中文?q=a+b%26c  ");
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            assertFalse(dialog.isShowing());
            assertEquals("demo://中文?q=a+b%26c", received.get(1));
        }
    }

    @Test public void shortcutWaitsWithoutClosingAndCancelDoesNotLaunchLater() {
        Intent incoming = new Intent().putExtra("target_intent", "demo://host?q=VALUE")
                .putExtra("replace_params", new String[]{"VALUE"})
                .putExtra("isChecked", true).putExtra("SelectedItem", "Shizuku");
        try (ActivityController<ShortcutActivity> controller = Robolectric.buildActivity(ShortcutActivity.class, incoming).setup()) {
            AlertDialog dialog = latestDialog();
            findInputs(dialog.getWindow().getDecorView()).get(0).setText("replacement");
            Button positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            String label = positive.getText().toString();
            positive.performClick(); idle();
            assertTrue(dialog.isShowing());
            assertFalse(controller.get().isFinishing());
            assertFalse(positive.isEnabled());
            assertEquals(label, positive.getText().toString());
            assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivity());

            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            assertFalse(dialog.isShowing());
            assertTrue(controller.get().isFinishing());
            Shadows.shadowOf(Looper.getMainLooper()).idleFor(3, TimeUnit.MINUTES);
            assertNull(Shadows.shadowOf(controller.get()).getNextStartedActivity());
        }
    }

    @Test public void newShortcutClosesPreviousFormWithoutFinishingTheReplacement() {
        Intent first = new Intent().putExtra("target_intent", "demo://first?q=FIRST")
                .putExtra("replace_params", new String[]{"FIRST"});
        try (ActivityController<ShortcutActivity> controller = Robolectric.buildActivity(ShortcutActivity.class, first).setup()) {
            AlertDialog previous = latestDialog();
            Intent second = new Intent().putExtra("target_intent", "demo://second?q=SECOND")
                    .putExtra("replace_params", new String[]{"SECOND"});
            controller.newIntent(second); idle();
            AlertDialog replacement = latestDialog();
            assertNotSame(previous, replacement);
            assertFalse(previous.isShowing());
            assertFalse(controller.get().isFinishing());
            EditText input = findInputs(replacement.getWindow().getDecorView()).get(0);
            assertTrue(input.getHint().toString().contains("SECOND"));
            input.setText("latest");
            replacement.getButton(AlertDialog.BUTTON_POSITIVE).performClick(); idle();
            Intent launched = Shadows.shadowOf(controller.get()).getNextStartedActivity();
            assertNotNull(launched);
            assertEquals("demo://second?q=latest", launched.getDataString());
            assertTrue(controller.get().isFinishing());
        }
    }

    @Test public void manyShortcutInputsScrollWhileActionButtonsRemainOutsideTheScroll() {
        RuntimeEnvironment.setFontScale(2f);
        String[] names = new String[10];
        StringBuilder target = new StringBuilder("demo://host?");
        for (int index = 0; index < names.length; index++) {
            names[index] = "PARAM" + index;
            target.append(index == 0 ? "" : "&").append("q").append(index).append('=').append(names[index]);
        }
        Intent incoming = new Intent().putExtra("target_intent", target.toString()).putExtra("replace_params", names);
        try (ActivityController<ShortcutActivity> controller = Robolectric.buildActivity(ShortcutActivity.class, incoming).setup()) {
            AlertDialog dialog = latestDialog();
            measureDialog(dialog);
            List<EditText> inputs = findInputs(dialog.getWindow().getDecorView());
            assertEquals(names.length, inputs.size());
            DialogScrollView scroll = findScroll(dialog.getWindow().getDecorView());
            assertNotNull(scroll);
            assertTrue(scroll.getHeight() > 0);
            assertTrue(scroll.getChildAt(0).getHeight() > scroll.getHeight());
            scroll.scrollTo(0, Integer.MAX_VALUE);
            assertTrue(scroll.getScrollY() > 0);
            assertTrue(inputs.get(inputs.size() - 1).getBottom() - scroll.getScrollY() <= scroll.getHeight());
            View parent = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            while (parent.getParent() instanceof View) {
                parent = (View) parent.getParent();
                assertNotSame(scroll, parent);
            }
            assertTextFits(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            assertTextFits(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            dialog.cancel(); idle();
        }
    }

    @Test public void customDialogLayoutsInflateWithTheirRuntimeWidgetContracts() {
        try (ActivityController<HostActivity> controller = Robolectric.buildActivity(HostActivity.class).setup()) {
            int[] layouts = {R.layout.dialog_custom_hook, R.layout.shortcut_config_dialog,
                    R.layout.dialog_cloud_config, R.layout.dialog_custom_packages,
                    R.layout.dialog_custom_view, R.layout.icon_selection_dialog};
            for (int layout : layouts) {
                AlertDialog.Builder builder = AppDialogs.builder(controller.get());
                View content = LayoutInflater.from(builder.getContext()).inflate(layout, null);
                if (layout == R.layout.dialog_custom_hook) {
                    for (int id : new int[]{R.id.input_package_name, R.id.input_method_name, R.id.input_class_name, R.id.input_category}) {
                        widget(content, id, TextInputEditText.class);
                    }
                    widget(content, R.id.idTILQuery, TextInputLayout.class);
                    widget(content, R.id.input_title_args_index, EditText.class);
                    widget(content, R.id.input_data_args_index, EditText.class);
                    widget(content, R.id.checkbox_title_args, CheckBox.class);
                    widget(content, R.id.checkbox_data_args, CheckBox.class);
                    widget(content, R.id.button_confirm, Button.class);
                    widget(content, R.id.button_pick_color, Button.class);
                } else if (layout == R.layout.shortcut_config_dialog) {
                    widget(content, R.id.shortcut_name, TextInputEditText.class);
                    widget(content, R.id.intent_string, TextInputEditText.class);
                    widget(content, R.id.icon_preview, ImageView.class);
                    widget(content, R.id.add_param_button, ImageView.class);
                    widget(content, R.id.dynamic_params_container, LinearLayout.class);
                    widget(content, R.id.submit_button, Button.class);
                    widget(content, R.id.cancel_button, Button.class);
                    widget(content, R.id.enable_feature_checkbox, CheckBox.class);
                    widget(content, R.id.dropdown_spinner, Spinner.class);
                } else if (layout == R.layout.dialog_cloud_config) {
                    widget(content, R.id.input_cloud_url, AutoCompleteTextView.class);
                    widget(content, R.id.button_request_cloud, Button.class);
                    widget(content, R.id.cloud_progress_bar, ProgressBar.class);
                    widget(content, R.id.cloud_empty_view, TextView.class);
                    widget(content, R.id.cloud_recycler_view, RecyclerView.class);
                } else if (layout == R.layout.dialog_custom_packages) {
                    widget(content, R.id.packages_list, RecyclerView.class);
                    widget(content, R.id.dropdown_spinner, Spinner.class);
                    widget(content, R.id.all_package, TextView.class);
                } else if (layout == R.layout.dialog_custom_view) {
                    widget(content, R.id.command_edit_text, EditText.class);
                    widget(content, R.id.hint_text_view, TextView.class);
                    widget(content, R.id.copy_button, Button.class);
                    widget(content, R.id.su_code_button, Button.class);
                    widget(content, R.id.enable_feature_checkbox, CheckBox.class);
                    widget(content, R.id.dropdown_spinner, Spinner.class);
                } else {
                    widget(content, R.id.icons_grid, GridView.class);
                    widget(content, R.id.custom_icon_button, ImageView.class);
                }
                AlertDialog dialog = builder.setView(content).create();
                dialog.show();
                if (layout == R.layout.dialog_cloud_config || layout == R.layout.dialog_custom_packages
                        || layout == R.layout.icon_selection_dialog) AppDialogs.sizeForList(dialog);
                measureDialog(dialog);
                assertTrue(dialog.isShowing());
                dialog.dismiss();
            }
        }
    }

    @Test public void styledExternalColorPickerKeepsLargeButtonsAndCancelCallback() {
        RuntimeEnvironment.setFontScale(2f);
        try (ActivityController<HostActivity> controller = Robolectric.buildActivity(HostActivity.class).setup()) {
            int initial = 0xff2e6ef7;
            int[] callbacks = {0, 0};
            int[] savedColor = {initial};
            AmbilWarnaDialog picker = new AmbilWarnaDialog(AppDialogs.context(controller.get()), initial,
                    new AmbilWarnaDialog.OnAmbilWarnaListener() {
                        @Override public void onOk(AmbilWarnaDialog dialog, int color) {
                            callbacks[0]++;
                            savedColor[0] = color;
                        }
                        @Override public void onCancel(AmbilWarnaDialog dialog) { callbacks[1]++; }
                    });
            picker.show();
            android.app.AlertDialog dialog = picker.getDialog();
            AppDialogs.styleExternal(dialog);
            measureDialog(dialog);
            assertTextFits(dialog.getButton(AlertDialog.BUTTON_POSITIVE));
            assertTextFits(dialog.getButton(AlertDialog.BUTTON_NEGATIVE));
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick(); idle();
            assertFalse(dialog.isShowing());
            assertEquals(0, callbacks[0]);
            assertEquals(1, callbacks[1]);
            assertEquals(initial, savedColor[0]);
        }
    }

    private static <T extends View> T widget(View root, int id, Class<T> type) {
        View view = root.findViewById(id);
        assertNotNull("Missing runtime widget " + id, view);
        return type.cast(view);
    }

    private static DialogScrollView findScroll(View root) {
        if (root instanceof DialogScrollView) return (DialogScrollView) root;
        if (root instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) root;
            for (int index = 0; index < parent.getChildCount(); index++) {
                DialogScrollView found = findScroll(parent.getChildAt(index));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static AlertDialog latestDialog() {
        idle(); // Android delivers OnShow before a user can interact with the visible dialog.
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull(dialog);
        assertTrue(dialog.isShowing());
        return dialog;
    }

    private static List<EditText> findInputs(View root) {
        List<EditText> inputs = new ArrayList<>();
        if (root instanceof EditText) inputs.add((EditText) root);
        if (root instanceof ViewGroup) {
            ViewGroup parent = (ViewGroup) root;
            for (int i = 0; i < parent.getChildCount(); i++) inputs.addAll(findInputs(parent.getChildAt(i)));
        }
        return inputs;
    }

    private static void measureDialog(android.app.Dialog dialog) {
        idle();
        View decor = dialog.getWindow().getDecorView();
        int width = Math.round(360 * dialog.getContext().getResources().getDisplayMetrics().density);
        int height = Math.round(800 * dialog.getContext().getResources().getDisplayMetrics().density);
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
    }

    private static void assertTextFits(TextView button) {
        assertNotNull(button);
        assertNotNull(button.getLayout());
        int required = button.getLayout().getHeight() + button.getCompoundPaddingTop() + button.getCompoundPaddingBottom();
        assertTrue("Button text needs " + required + "px but only has " + button.getHeight() + "px", button.getHeight() >= required);
        for (int line = 0; line < button.getLayout().getLineCount(); line++) {
            assertEquals("Button text was ellipsized", 0, button.getLayout().getEllipsisCount(line));
        }
    }

    private static void idle() { Shadows.shadowOf(Looper.getMainLooper()).idle(); }
}
