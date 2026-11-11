package com.fourtwo.hookintent;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fourtwo.hookintent.manager.PermissionManager;

import java.net.URISyntaxException;
import com.fourtwo.hookintent.utils.ShortcutParameterReplacer;
import com.fourtwo.hookintent.utils.ShortcutPayloadStore;
import com.fourtwo.hookintent.utils.IntentReplay;

public class ShortcutActivity extends AppCompatActivity {

    private AlertDialog parameterDialog;
    private Intent originalTarget;
    private String originalTargetText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        handleShortcut(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        if (parameterDialog != null) {
            parameterDialog.setOnDismissListener(null);
            parameterDialog.dismiss();
            parameterDialog = null;
        }
        handleShortcut(intent);
    }

    private void handleShortcut(Intent incoming) {
        try {
            Bundle bundle = incoming == null ? null : incoming.getExtras();
            if (bundle == null) { finish(); return; }
            String targetIntent = bundle.getString("target_intent");
            String[] replaceParams = bundle.getStringArray("replace_params");
            boolean isChecked = bundle.getBoolean("isChecked", false);
            String selectedItem = bundle.getString("SelectedItem", "");
            ShortcutPayloadStore.Payload payload = ShortcutPayloadStore.load(this, bundle.getString("target_payload_id"));
            if (payload != null) {
                targetIntent = payload.template;
                replaceParams = payload.parameters;
            }
            ShortcutParameterReplacer.validate(targetIntent, replaceParams);
            originalTargetText = targetIntent;
            originalTarget = payload == null ? null : payload.target;
            if (replaceParams != null && replaceParams.length > 0) {
                showReplaceParamsDialog(targetIntent, replaceParams, isChecked, selectedItem);
            } else {
                PermissionManager.startActivity(this,
                        IntentReplay.fromText(targetIntent, originalTarget, originalTargetText), isChecked, selectedItem);
                finish();
            }
        } catch (Exception error) {
            Log.e("ShortcutActivity", "Cannot open shortcut", error);
            Toast.makeText(this, "快捷方式内容无效：" + error.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    /**
     * 弹窗让用户输入替换参数
     *
     * @param targetIntent  原始 Intent 字符串
     * @param replaceParams 替换参数列表
     */
    private void showReplaceParamsDialog(String targetIntent, String[] replaceParams, Boolean isChecked, String selectedItem) {
        final EditText[] editTexts = new EditText[replaceParams.length];

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackground(createRoundRect("#FCFCFD", "#ECEEF2", 24));

//        TextView title = createDialogTitle("替换参数");
//        root.addView(title);

        ScrollView scrollView = new ScrollView(this);
        LinearLayout.LayoutParams scrollLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                replaceParams.length > 3 ? dp(280) : ViewGroup.LayoutParams.WRAP_CONTENT
        );
        scrollLp.topMargin = dp(14);
        root.addView(scrollView, scrollLp);

        LinearLayout formContainer = new LinearLayout(this);
        formContainer.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(formContainer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        for (int i = 0; i < replaceParams.length; i++) {
//            TextView label = createDialogFieldLabel(replaceParams[i]);
//            if (i > 0) {
//                LinearLayout.LayoutParams labelLp = new LinearLayout.LayoutParams(
//                        ViewGroup.LayoutParams.WRAP_CONTENT,
//                        ViewGroup.LayoutParams.WRAP_CONTENT
//                );
//                labelLp.topMargin = dp(14);
//                formContainer.addView(label, labelLp);
//            } else {
//                formContainer.addView(label);
//            }

            EditText input = createDialogInput("替换 " + replaceParams[i] + " 的值");
            editTexts[i] = input;

            LinearLayout inputBox = wrapDialogInput(input);
            LinearLayout.LayoutParams inputBoxLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            inputBoxLp.topMargin = dp(8);
            formContainer.addView(inputBox, inputBoxLp);
        }

        LinearLayout buttonRow = createDialogButtonRow();
        LinearLayout.LayoutParams buttonRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        buttonRowLp.topMargin = dp(18);
        root.addView(buttonRow, buttonRowLp);

        Button cancelButton = createDialogActionButton(
                "取消",
                "#F3F4F6",
                "#E6E8EC",
                "#6B7280"
        );

        Button confirmButton = createDialogActionButton(
                "确认",
                "#EAF1FF",
                "#D6E4FF",
                "#2E6EF7"
        );

        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        );
        cancelLp.rightMargin = dp(8);

        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        );
        confirmLp.leftMargin = dp(8);

        buttonRow.addView(cancelButton, cancelLp);
        buttonRow.addView(confirmButton, confirmLp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .create();

        dialog.setCanceledOnTouchOutside(true);
        dialog.setCancelable(true);

        cancelButton.setOnClickListener(v -> dialog.dismiss());

        confirmButton.setOnClickListener(v -> {
            try {
                String[] replacements = new String[replaceParams.length];
                for (int i = 0; i < replaceParams.length; i++) {
                    replacements[i] = editTexts[i].getText() == null ? "" : editTexts[i].getText().toString();
                }
                String finalTargetIntent = ShortcutParameterReplacer.replace(targetIntent, replaceParams, replacements);
                Intent newIntent = IntentReplay.fromText(finalTargetIntent, originalTarget, originalTargetText);
                PermissionManager.startActivity(this, newIntent, isChecked, selectedItem);
                Log.d("TransparentDialogActivity", "替换后启动的 Intent: " + finalTargetIntent);
                dialog.dismiss();
            } catch (Exception e) {
                Log.e("TransparentDialogActivity", "Intent URI 解析失败", e);
                Toast.makeText(this, "Intent URI 解析失败", Toast.LENGTH_SHORT).show();
            }
        });

        parameterDialog = dialog;
        dialog.setOnDismissListener(dialogInterface -> {
            parameterDialog = null;
            finish();
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.90f),
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
    }

    private TextView createDialogTitle(String text) {
        TextView title = new TextView(this);
        title.setText(text);
        title.setTextSize(17);
        title.setTypeface(null, Typeface.BOLD);
        title.setTextColor(Color.parseColor("#1F2937"));
        return title;
    }

    private TextView createDialogFieldLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(12);
        label.setTextColor(Color.parseColor("#6B7280"));
        return label;
    }

    private EditText createDialogInput(String hint) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setBackground(null);
        input.setTextSize(14);
        input.setHintTextColor(Color.parseColor("#9CA3AF"));
        input.setTextColor(Color.parseColor("#1F2937"));
        input.setSingleLine(true);
        input.setPadding(0, dp(6), 0, dp(6));
        return input;
    }

    private LinearLayout wrapDialogInput(EditText input) {
        LinearLayout inputBox = new LinearLayout(this);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(Gravity.CENTER_VERTICAL);
        inputBox.setBackground(createRoundRect("#F3F4F6", "#F3F4F6", 18));
        inputBox.setPadding(dp(14), dp(8), dp(12), dp(8));

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputBox.addView(input, inputLp);
        return inputBox;
    }

    private LinearLayout createDialogButtonRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER);
        return row;
    }

    private Button createDialogActionButton(String text, String fillColor, String strokeColor, String textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinWidth(dp(78));
        button.setPadding(dp(20), 0, dp(20), 0);
        button.setTextSize(14);
        button.setTypeface(null, Typeface.BOLD);
        button.setTextColor(Color.parseColor(textColor));
        button.setBackground(createRoundRect(fillColor, strokeColor, 999));
        return button;
    }

    private GradientDrawable createRoundRect(String fillColor, String strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(Color.parseColor(fillColor));
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != null) {
            drawable.setStroke(dp(1), Color.parseColor(strokeColor));
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    @Override
    protected void onDestroy() {
        if (parameterDialog != null) {
            parameterDialog.setOnDismissListener(null);
            parameterDialog.dismiss();
            parameterDialog = null;
        }
        super.onDestroy();
    }
}
