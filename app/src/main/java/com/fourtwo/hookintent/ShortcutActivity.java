package com.fourtwo.hookintent;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.EditText;
import android.widget.LinearLayout;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.fourtwo.hookintent.manager.PermissionManager;

import java.net.URISyntaxException;

public class ShortcutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 设置透明背景
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);


        Log.d("showReplaceParamsDialog", "showReplaceParamsDialog: " + getIntent().toUri(Intent.URI_INTENT_SCHEME));

        // 接收传递的参数
        Bundle bundle = getIntent().getExtras();
        if (bundle != null) {
            String targetIntent = bundle.getString("target_intent");
            String[] replaceParams = bundle.getStringArray("replace_params");
            boolean isChecked = bundle.getBoolean("isChecked", false);
            String SelectedItem = bundle.getString("SelectedItem");
            if (targetIntent != null && replaceParams != null && replaceParams.length > 0) {
                // 弹出输入框
                showReplaceParamsDialog(targetIntent, replaceParams, isChecked, SelectedItem);
            } else {
                try {
                    Intent newIntent = Intent.parseUri(targetIntent, 0);
                    PermissionManager.startActivity(this, newIntent, isChecked, SelectedItem);
                } catch (URISyntaxException e) {
                    throw new RuntimeException(e);
                }
                // 没有参数，直接关闭
                finish();
            }
        } else {
            // 没有传递数据，直接关闭
            finish();
        }
    }

    /**
     * 弹窗让用户输入替换参数
     *
     * @param targetIntent  原始 Intent 字符串
     * @param replaceParams 替换参数列表
     */
    private void showReplaceParamsDialog(String targetIntent, String[] replaceParams, Boolean isChecked, String SelectedItem) {
        // 使用一个数组来包装 targetIntent，确保它可以被修改
        final String[] finalTargetIntent = {targetIntent};

        // 创建一个 AlertDialog
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("替换参数");

        // 使用一个 LinearLayout 来动态添加输入框
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(16, 16, 16, 16);

        // 为每个替换参数创建一个 EditText
        EditText[] editTexts = new EditText[replaceParams.length];
        for (int i = 0; i < replaceParams.length; i++) {
            EditText editText = new EditText(this);
            editText.setHint("替换 " + replaceParams[i] + " 的值");
            layout.addView(editText);
            editTexts[i] = editText;
        }

        builder.setView(layout);

        // 设置确认按钮
        builder.setPositiveButton("确认", (dialog, which) -> {
            for (int i = 0; i < replaceParams.length; i++) {
                String userInput = editTexts[i].getText().toString();
                // 替换参数
                finalTargetIntent[0] = finalTargetIntent[0].replace(replaceParams[i], userInput);
            }

            // 启动新的 Intent
            try {
                Intent newIntent = Intent.parseUri(finalTargetIntent[0], 0);
                PermissionManager.startActivity(this, newIntent, isChecked, SelectedItem);
                Log.d("TransparentDialogActivity", "替换后启动的 Intent: " + finalTargetIntent[0]);
            } catch (URISyntaxException e) {
                Log.e("TransparentDialogActivity", "Intent URI 解析失败");
                throw new RuntimeException(e);
            } finally {
                finish(); // 关闭透明 Activity
            }
        });

        // 设置取消按钮
        builder.setNegativeButton("取消", (dialog, which) -> {
            dialog.dismiss();
            finish(); // 用户取消后直接关闭透明 Activity
        });

        // 添加弹窗消失监听器
        builder.setOnDismissListener(dialogInterface -> finish());

        // 显示弹窗
        builder.create().show();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}
