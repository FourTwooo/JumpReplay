package com.fourtwo.hookintent;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fourtwo.hookintent.utils.AndroidShortcutBackend;
import com.fourtwo.hookintent.utils.AppDialogs;
import com.fourtwo.hookintent.utils.DialogScrollView;
import com.fourtwo.hookintent.utils.ShortcutLaunchCoordinator;
import android.os.Handler;
import android.os.Looper;

import com.fourtwo.hookintent.utils.ShortcutParameterReplacer;
import com.fourtwo.hookintent.utils.ShortcutPayloadStore;
import com.fourtwo.hookintent.utils.IntentReplay;

public class ShortcutActivity extends AppCompatActivity {

    private AlertDialog parameterDialog;
    private AlertDialog waitingDialog;
    private TextView waitingText;
    private TextView launchStatus;
    private String waitingMessage;
    private final Handler launchUi = new Handler(Looper.getMainLooper());
    private ShortcutLaunchCoordinator launches;
    private final Runnable showWaiting = () -> {
        if (isFinishing() || isDestroyed() || waitingMessage == null || waitingDialog != null) return;
        AppDialogs.ProgressContent content = AppDialogs.progress(this, waitingMessage);
        waitingText = content.message;
        waitingDialog = AppDialogs.builder(this).setView(content).create();
        waitingDialog.setCanceledOnTouchOutside(false);
        waitingDialog.setOnCancelListener(dialog -> { launches.cancel(); finish(); });
        waitingDialog.show();
    };
    private Intent originalTarget;
    private String originalTargetText;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        launches = new ShortcutLaunchCoordinator(new AndroidShortcutBackend(this), launchUi);
        handleShortcut(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        launches.cancel();
        launchStatus = null;
        hideWaiting();
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
                launchTarget(IntentReplay.fromText(targetIntent, originalTarget, originalTargetText),
                        isChecked, selectedItem, null);
            }
        } catch (Exception error) {
            Log.e("ShortcutActivity", "Cannot open shortcut", error);
            Toast.makeText(this, "快捷方式内容无效：" + error.getMessage(), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    private void hideWaiting() {
        waitingMessage = null;
        launchUi.removeCallbacks(showWaiting);
        if (waitingDialog != null) {
            waitingDialog.setOnCancelListener(null);
            waitingDialog.dismiss();
            waitingDialog = null;
        }
        waitingText = null;
    }

    private void launchTarget(Intent target, boolean privileged, String mode, Button confirm) {
        if (confirm != null) confirm.setEnabled(false);
        launches.launch(target, privileged, mode, new ShortcutLaunchCoordinator.Callback() {
            @Override public void waiting(String message) {
                if (isFinishing() || isDestroyed()) return;
                if (confirm != null) {
                    if (launchStatus != null) { launchStatus.setText(message); launchStatus.setVisibility(android.view.View.VISIBLE); }
                }
                else {
                    waitingMessage = message;
                    if (waitingText != null) waitingText.setText(message);
                    launchUi.removeCallbacks(showWaiting);
                    launchUi.postDelayed(showWaiting, 150);
                }
            }
            @Override public void complete(Exception error) {
                hideWaiting();
                if (isFinishing() || isDestroyed()) return;
                if (error != null) {
                    Toast.makeText(ShortcutActivity.this, "启动失败：" + error.getMessage(), Toast.LENGTH_LONG).show();
                    if (confirm != null) {
                        confirm.setEnabled(true);
                        if (launchStatus != null) launchStatus.setVisibility(android.view.View.GONE);
                    }
                    else finish();
                    return;
                }
                if (parameterDialog != null) parameterDialog.dismiss();
                else finish();
            }
        });
    }

    /**
     * 弹窗让用户输入替换参数
     *
     * @param targetIntent  原始 Intent 字符串
     * @param replaceParams 替换参数列表
     */
    private void showReplaceParamsDialog(String targetIntent, String[] replaceParams, Boolean isChecked, String selectedItem) {
        EditText[] inputs = new EditText[replaceParams.length];
        LinearLayout content = AppDialogs.content(this);
        for (int index = 0; index < replaceParams.length; index++) {
            EditText input = AppDialogs.input(this);
            input.setHint(getString(R.string.dialog_replace_parameter, replaceParams[index]));
            input.setSingleLine(true);
            inputs[index] = input;
            LinearLayout.LayoutParams parameters = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (index > 0) parameters.topMargin = dp(12);
            content.addView(input, parameters);
        }
        launchStatus = new TextView(AppDialogs.context(this));
        launchStatus.setTextAppearance(this, R.style.TextAppearance_HookIntent_DialogBody);
        launchStatus.setVisibility(android.view.View.GONE);
        LinearLayout.LayoutParams statusParameters = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statusParameters.topMargin = dp(12);
        content.addView(launchStatus, statusParameters);
        DialogScrollView scroll = new DialogScrollView(AppDialogs.context(this));
        scroll.addView(content);
        AlertDialog dialog = AppDialogs.builder(this)
                .setTitle(R.string.dialog_shortcut_parameters)
                .setView(scroll)
                .setNegativeButton(R.string.cancel, (dismissed, which) -> dismissed.dismiss())
                .setPositiveButton(R.string.ok, null)
                .create();
        dialog.setCanceledOnTouchOutside(true);
        dialog.setOnShowListener(shown -> {
            Button confirm = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            confirm.setOnClickListener(view -> {
                try {
                    String[] replacements = new String[replaceParams.length];
                    for (int index = 0; index < replaceParams.length; index++) {
                        replacements[index] = inputs[index].getText() == null ? "" : inputs[index].getText().toString();
                    }
                    String finalText = ShortcutParameterReplacer.replace(targetIntent, replaceParams, replacements);
                    Intent target = IntentReplay.fromText(finalText, originalTarget, originalTargetText);
                    launchTarget(target, isChecked, selectedItem, confirm);
                } catch (Exception error) {
                    Log.e("ShortcutActivity", "Cannot read shortcut parameters", error);
                    Toast.makeText(this, "Intent URI 解析失败", Toast.LENGTH_SHORT).show();
                }
            });
        });
        parameterDialog = dialog;
        dialog.setOnDismissListener(dismissed -> {
            parameterDialog = null;
            launchStatus = null;
            launches.cancel();
            finish();
        });
        dialog.show();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    @Override
    protected void onDestroy() {
        if (launches != null) launches.cancel();
        hideWaiting();
        launchUi.removeCallbacksAndMessages(null);
        if (parameterDialog != null) {
            parameterDialog.setOnDismissListener(null);
            parameterDialog.dismiss();
            parameterDialog = null;
        }
        super.onDestroy();
    }
}
