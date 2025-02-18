package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.LinearLayout;
import android.widget.TextView;

public class IntentIntercept extends Activity {

    String TAG = "InterceptActivity";

    private static boolean isSystemXposed() {
        return false;
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Log.d(TAG, "onCreate");
        // 设置一个简单的布局
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);

        // 创建一个 TextView 用于显示 URL
        TextView textView = new TextView(this);
        textView.setTextSize(18); // 设置字体大小
        textView.setPadding(20, 50, 20, 50); // 设置内边距

        // 获取 Intent 中的 URL
        Intent intent = getIntent();
        Log.d(TAG, "onCreate: " + intent);
        textView.setText(intent.toUri(Intent.URI_INTENT_SCHEME));

        // 将 TextView 添加到布局
        layout.addView(textView);

        // 设置布局为 Activity 的内容视图
        setContentView(layout);
    }
}
