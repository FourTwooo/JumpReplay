package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.fourtwo.hookintent.databinding.ActivityIntentInterceptBinding;
import com.fourtwo.hookintent.databinding.ContentInterceptMainBinding;

public class IntentIntercept extends AppCompatActivity {
    private ActivityIntentInterceptBinding binding; // 主布局绑定
    private ContentInterceptMainBinding contentBinding; // 子布局绑定
    String TAG = "IntentIntercept";

    private static boolean isSystemXposed() {
        return false;
    }

    @SuppressLint("SetTextI18n")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 主布局绑定
        binding = ActivityIntentInterceptBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        // 子布局绑定（content_intercept_main.xml）
        contentBinding = ContentInterceptMainBinding.bind(binding.getRoot().findViewById(R.id.content_main));

        Log.d(TAG, "onCreate");

        // 获取 Intent 中的数据
        Intent intent = getIntent();
        Log.d(TAG, "onCreate: " + intent);

        // 设置 TextView 的内容为 Intent 的 URI
        String initialUri = intent.toUri(Intent.URI_INTENT_SCHEME);
        contentBinding.urlTextView.setText(initialUri);

        // 设置重置按钮初始状态为隐藏
        binding.resetButton.setVisibility(View.GONE);

        // 监听编辑框内容的变化
        contentBinding.urlTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 如果编辑框内容变化，显示重置按钮
                if (!s.toString().equals(initialUri)) {
                    binding.resetButton.setVisibility(View.VISIBLE);
                } else {
                    binding.resetButton.setVisibility(View.GONE);
                }
            }

            @Override
            public void afterTextChanged(Editable s) {}
        });

        // 设置重置按钮点击事件
        binding.resetButton.setOnClickListener(view -> {
            contentBinding.urlTextView.setText(initialUri);
        });

        // 设置发送按钮点击事件
        binding.btnReloadIntent.setOnClickListener(view -> {
            Log.d(TAG, "重新调用 Intent 按钮被点击");

            // 获取当前的 Intent
            Intent originalIntent = getIntent();

            // 创建新的 Intent
            Intent newIntent = new Intent(originalIntent);

            // 移除 component 字段
            newIntent.setComponent(null);

            // 如果需要，还可以重新设置其他字段（如 category 或 flags）
            newIntent.addCategory(Intent.CATEGORY_DEFAULT);

            // 启动新的 Activity
            try {
                startActivity(newIntent);
                Toast.makeText(this, "重新调用 Intent 成功", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "无法启动新的 Intent: " + e.getMessage(), e);
                Toast.makeText(this, "重新调用 Intent 失败", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent: " + intent);

        // 更新绑定到新的 Intent
        setIntent(intent);

        // 更新 UI 数据
        contentBinding.urlTextView.setText(intent.toUri(Intent.URI_INTENT_SCHEME)); // 更新 TextView
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.d(TAG, "onCreateOptionsMenu: ");
        getMenuInflater().inflate(R.menu.main, menu); // 替换为你的菜单文件
        return true;
    }
}
