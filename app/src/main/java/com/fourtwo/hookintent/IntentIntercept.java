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
import com.fourtwo.hookintent.utils.RootServiceHelper;

import java.io.UnsupportedEncodingException;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class IntentIntercept extends AppCompatActivity implements IntentMatchAdapter.OnIntentMatchClickListener {
    private static final String TAG = "IntentIntercept";

    private ActivityIntentInterceptBinding binding; // 主布局绑定
    private ContentInterceptMainBinding contentBinding; // 子布局绑定
    private String originalUrlText; // 用于存储初始 URL 内容

    private static boolean isSystemXposed() {
        return false;
    }

    @SuppressLint({"SetTextI18n", "UseCompatLoadingForDrawables"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 绑定 RootService
        RootServiceHelper.bindRootService(this);
        // 初始化布局绑定
        initBindings();

        // 处理传入的 Intent 数据
        Intent originalIntent = stripUnwantedFields(getIntent());
        originalUrlText = extractUrlFromIntent(originalIntent);
        contentBinding.urlTextView.setText(originalUrlText);

        // 设置 UI 和事件监听
        setupUI();
        setupListeners(originalIntent);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        Log.d(TAG, "onNewIntent: " + intent);

        // 更新当前 Intent
        setIntent(intent);
        originalUrlText = extractUrlFromIntent(intent);
        contentBinding.urlTextView.setText(originalUrlText);

        // 加载符合的应用列表
        loadMatchingApps(intent);
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        Log.d(TAG, "onCreateOptionsMenu: ");
        getMenuInflater().inflate(R.menu.main, menu); // 替换为你的菜单文件
        return true;
    }

    /**
     * 初始化布局绑定
     */
    private void initBindings() {
        binding = ActivityIntentInterceptBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        contentBinding = ContentInterceptMainBinding.bind(binding.getRoot().findViewById(R.id.content_main));
    }

    /**
     * 设置 UI 组件和事件监听
     */
    private void setupUI() {
        // 设置重置按钮初始状态为隐藏
        binding.resetButton.setVisibility(View.GONE);

        // 动态加载初始的应用列表
        try {
            loadMatchingApps(Intent.parseUri(originalUrlText, 0));
        } catch (URISyntaxException e) {
            loadMatchingApps(getIntent());
        }
    }

    /**
     * 设置事件监听
     */
    private void setupListeners(Intent originalIntent) {
        // 输入框监听事件：根据 URL 内容动态显示重置按钮和加载应用列表
        contentBinding.urlTextView.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                handleUrlTextChange(s.toString());
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // 设置重置按钮点击事件
        binding.resetButton.setOnClickListener(view -> contentBinding.urlTextView.setText(originalUrlText));

        // 设置发送按钮点击事件
        binding.btnReloadIntent.setOnClickListener(view -> handleReloadIntentClick());
    }

    /**
     * 处理 URL 输入框内容变化
     */
    private void handleUrlTextChange(String updatedText) {
        // 显示或隐藏重置按钮
        binding.resetButton.setVisibility(updatedText.equals(originalUrlText) ? View.GONE : View.VISIBLE);

        // 根据新的 URL 动态加载应用列表
        try {
            loadMatchingApps(Intent.parseUri(updatedText, 0));
        } catch (URISyntaxException | NumberFormatException ignored) {
        }
    }

    private void isRootStartActivity(Intent intent) {
        try {
            if (contentBinding.enableFeatureCheckbox.isChecked()) {
                RootServiceHelper.startActivityAsRoot(this, intent);
            } else {
                startActivity(intent);
            }
            Toast.makeText(this, "调用成功", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Log.e(TAG, "无法启动新的 Intent: " + e.getMessage(), e);
            Toast.makeText(this, "调用失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 处理发送按钮的点击事件
     */
    private void handleReloadIntentClick() {
        try {
            Intent newIntent = Intent.parseUri(Objects.requireNonNull(contentBinding.urlTextView.getText()).toString(), 0);
            isRootStartActivity(newIntent);
        } catch (Exception ignored) {
        }


    }

    /**
     * 实现 OnIntentMatchClickListener 接口
     */
    @Override
    public void onIntentMatchClick(Intent intent) {
        if (intent != null) {
            isRootStartActivity(intent);
        }
    }

    /**
     * 从 Intent 中提取 URL 并解码
     */
    private String extractUrlFromIntent(Intent intent) {
        String urlText = intent.getDataString();
        if (urlText == null) {
            urlText = intent.toUri(Intent.URI_INTENT_SCHEME);
        }
        try {
            return URLDecoder.decode(urlText, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException e) {
            return urlText;
        }
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
        PackageManager packageManager = getPackageManager();
        String currentPackageName = getPackageName();

        // 查询符合的应用列表
        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);

        // 转换为 IntentMatchItem 列表
        List<IntentMatchItem> items = new ArrayList<>();
        for (ResolveInfo resolveInfo : resolveInfoList) {
            if (!resolveInfo.activityInfo.packageName.equals(currentPackageName)) {
                items.add(createIntentMatchItem(intent, packageManager, resolveInfo));
            }
        }

        // 创建适配器并设置监听器
        IntentMatchAdapter adapter = new IntentMatchAdapter(this, items, this);
        contentBinding.recyclerView.setLayoutManager(new LinearLayoutManager(this));
        contentBinding.recyclerView.setAdapter(adapter);
    }

    /**
     * 创建 IntentMatchItem
     */
    private IntentMatchItem createIntentMatchItem(Intent intent, PackageManager packageManager, ResolveInfo resolveInfo) {
        String packageName = resolveInfo.activityInfo.packageName;
        String appName = resolveInfo.loadLabel(packageManager).toString();
        Drawable appIcon = resolveInfo.loadIcon(packageManager);
        String appDetails = packageName + "\n" + resolveInfo.activityInfo.name;

        Intent launchIntent = new Intent(intent);
        launchIntent.setClassName(packageName, resolveInfo.activityInfo.name);

        return new IntentMatchItem(appIcon, appName, appDetails, launchIntent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        RootServiceHelper.unbindRootService(this);
    }
}