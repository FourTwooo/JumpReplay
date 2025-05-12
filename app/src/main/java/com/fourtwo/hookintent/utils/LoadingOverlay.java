package com.fourtwo.hookintent.utils;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

public class LoadingOverlay {

    private Dialog dialog;
    private TextView loadingText;

    public LoadingOverlay(Context context) {
        // 初始化 Dialog
        dialog = new Dialog(context);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE); // 移除标题
        dialog.setCancelable(false); // 禁止取消
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT)); // 设置透明背景
        }

        // 创建根布局
        LinearLayout rootLayout = new LinearLayout(context);
        rootLayout.setOrientation(LinearLayout.VERTICAL); // 垂直布局
        rootLayout.setGravity(Gravity.CENTER); // 居中显示
        rootLayout.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        // 设置圆角背景
        GradientDrawable backgroundDrawable = new GradientDrawable();
        backgroundDrawable.setColor(Color.parseColor("#CC333333")); // 半透明深灰色背景
        backgroundDrawable.setCornerRadius(20); // 设置圆角半径
        rootLayout.setBackground(backgroundDrawable);
        rootLayout.setPadding(50, 50, 50, 50); // 设置内边距

        // 创建 ProgressBar
        ProgressBar progressBar = new ProgressBar(context);
        LinearLayout.LayoutParams progressBarParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        progressBarParams.gravity = Gravity.CENTER; // 居中显示
        progressBar.setLayoutParams(progressBarParams);

        // 创建 TextView
        loadingText = new TextView(context);
        LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
        );
        textParams.gravity = Gravity.CENTER; // 居中显示
        textParams.topMargin = 30; // 设置文字与进度条的间距
        loadingText.setLayoutParams(textParams);
        loadingText.setText("正在加载应用列表..."); // 设置文字内容
        loadingText.setTextColor(Color.LTGRAY); // 设置文字颜色为亮灰色
        loadingText.setTextSize(16); // 设置文字大小
        loadingText.setGravity(Gravity.CENTER); // 文字居中对齐

        // 将控件添加到根布局
        rootLayout.addView(progressBar);
        rootLayout.addView(loadingText);

        // 设置 Dialog 的内容视图
        dialog.setContentView(rootLayout);
    }

    /**
     * 显示加载层
     */
    public void show() {
        dialog.show();
    }

    /**
     * 隐藏加载层
     */
    public void hide() {
        dialog.dismiss();
    }

    /**
     * 设置加载文字
     *
     * @param text 要显示的文字
     */
    public void setLoadingText(String text) {
        loadingText.setText(text);
    }

    /**
     * 获取当前的加载文字
     *
     * @return 返回加载文字
     */
    public String getLoadingText() {
        return loadingText.getText().toString();
    }
}
