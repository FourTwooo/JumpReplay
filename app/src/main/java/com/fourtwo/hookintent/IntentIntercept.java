package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.fourtwo.hookintent.data.IntentMatchItem;
import com.fourtwo.hookintent.databinding.ActivityIntentInterceptBinding;
import com.fourtwo.hookintent.databinding.ContentInterceptMainBinding;

import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class IntentIntercept extends AppCompatActivity {
    private ActivityIntentInterceptBinding binding; // 主布局绑定
    private ContentInterceptMainBinding contentBinding; // 子布局绑定
    String TAG = "IntentIntercept";

    private static boolean isSystemXposed() {
        return false;
    }

    @SuppressLint({"SetTextI18n", "UseCompatLoadingForDrawables"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 主布局绑定
        binding = ActivityIntentInterceptBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        // 子布局绑定（content_intercept_main.xml）
        contentBinding = ContentInterceptMainBinding.bind(binding.getRoot().findViewById(R.id.content_main));

        // 获取 Intent 中的数据
        Intent originalIntent = stripUnwantedFields(getIntent());
        Log.d(TAG, "onCreate: " + originalIntent.getDataString());
        String urlText;

        String DataString = originalIntent.getDataString();
        if (DataString != null) {
            urlText = DataString;
        } else {
            urlText = originalIntent.toUri(Intent.URI_INTENT_SCHEME);
        }

        try {
            urlText = URLDecoder.decode(urlText, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ignored) {
        }

        contentBinding.urlTextView.setText(urlText);

        // 设置重置按钮初始状态为隐藏
        binding.resetButton.setVisibility(View.GONE);

        // 监听编辑框内容的变化
        String finalUrlText = urlText;
        contentBinding.urlTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                // 如果编辑框内容变化，显示重置按钮
                if (!s.toString().equals(finalUrlText)) {
                    binding.resetButton.setVisibility(View.VISIBLE);
                } else {
                    binding.resetButton.setVisibility(View.GONE);
                }

                // 动态加载符合的应用列表
                try {
                    loadMatchingApps(Intent.parseUri(s.toString(), 0));
                } catch (URISyntaxException ignored) {
                }

            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // 设置重置按钮点击事件
        binding.resetButton.setOnClickListener(view -> {
            contentBinding.urlTextView.setText(finalUrlText);
        });

        // 设置发送按钮点击事件
        binding.btnReloadIntent.setOnClickListener(view -> {
            // 启动新的 Activity
            try {
                Intent newIntent = Intent.parseUri(Objects.requireNonNull(contentBinding.urlTextView.getText()).toString(), 0);
                startActivity(newIntent);
                Toast.makeText(this, "调用意图成功", Toast.LENGTH_SHORT).show();
            } catch (Exception e) {
                Log.e(TAG, "无法启动新的 Intent: " + e.getMessage(), e);
                Toast.makeText(this, "调用意图失败", Toast.LENGTH_SHORT).show();
            }
        });

        // 动态加载符合的应用列表
        try {
            loadMatchingApps(Intent.parseUri(Objects.requireNonNull(contentBinding.urlTextView.getText()).toString(), 0));
        } catch (URISyntaxException e) {
            loadMatchingApps(originalIntent);
        }

    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent: " + intent);

        // 更新绑定到新的 Intent
        setIntent(intent);

        // 更新 UI 数据
        contentBinding.urlTextView.setText(intent.toUri(Intent.URI_INTENT_SCHEME)); // 更新 TextView

        // 动态加载新的应用列表
        loadMatchingApps(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.d(TAG, "onCreateOptionsMenu: ");
        getMenuInflater().inflate(R.menu.main, menu); // 替换为你的菜单文件
        return true;
    }

    /**
     * 移除多余字段的 Intent
     */
    private Intent stripUnwantedFields(Intent intent) {
        // 移除可能被系统附加的字段
        intent.setComponent(null); // 移除 Component
        intent.setSelector(null);  // 移除 Selector
        intent.setFlags(0);        // 移除多余的 Flags
        return intent;
    }

    /**
     * 动态加载符合的应用列表
     */
    private void loadMatchingApps(Intent intent) {
        // 获取 PackageManager
        PackageManager packageManager = getPackageManager();
        String currentPackageName = getPackageName(); // 获取当前应用的包名

        // 使用 queryIntentActivities() 获取符合的应用列表
        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

        // 转换为 IntentMatchItem 列表
        List<IntentMatchItem> items = new ArrayList<>();
        for (ResolveInfo resolveInfo : resolveInfoList) {
            String packageName = resolveInfo.activityInfo.packageName;

            // 跳过当前应用
            if (packageName.equals(currentPackageName)) {
                continue; // 如果是自己的应用，直接跳过
            }

            String appName = resolveInfo.loadLabel(packageManager).toString();
            Drawable appIcon = resolveInfo.loadIcon(packageManager);
            String appDetails = packageName + "\n" + resolveInfo.activityInfo.name;

            // 构建 IntentMatchItem
            Intent launchIntent = new Intent(intent);
            launchIntent.setClassName(packageName, resolveInfo.activityInfo.name);

            items.add(new IntentMatchItem(appIcon, appName, appDetails, launchIntent));
        }

        // 更新 RecyclerView
        contentBinding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        contentBinding.recyclerView.setAdapter(new IntentMatchAdapter(this, items));
    }

}
