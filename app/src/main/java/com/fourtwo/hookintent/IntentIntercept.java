package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
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
        contentBinding.urlTextView.setText(intent.toUri(Intent.URI_INTENT_SCHEME));

        // 设置按钮点击事件
        contentBinding.btnReloadIntent.setOnClickListener(view -> {
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
