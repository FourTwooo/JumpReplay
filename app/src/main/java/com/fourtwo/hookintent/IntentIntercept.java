package com.fourtwo.hookintent;

import static android.content.pm.PackageManager.PERMISSION_GRANTED;

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
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.fourtwo.hookintent.data.IntentMatchItem;
import com.fourtwo.hookintent.databinding.ActivityIntentInterceptBinding;
import com.fourtwo.hookintent.databinding.ContentInterceptMainBinding;
import com.fourtwo.hookintent.utils.RootServiceHelper;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import com.fourtwo.hookintent.utils.ShizukuSystemServerApi;

import java.io.UnsupportedEncodingException;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import rikka.shizuku.Shizuku;

public class IntentIntercept extends AppCompatActivity implements IntentInterceptAdapter.OnIntentMatchClickListener {
    private static final String TAG = "IntentIntercept";

    private ActivityIntentInterceptBinding binding; // 主布局绑定
    private ContentInterceptMainBinding contentBinding; // 子布局绑定
    private String originalUrlText; // 用于存储初始 URL 内容

    private IntentInterceptAdapter adapter;

    private static boolean isSystemXposed() {
        return false;
    }

    private static final int REQUEST_CODE_BUTTON1 = 1;

    private final Shizuku.OnBinderReceivedListener BINDER_RECEIVED_LISTENER = () -> {
        if (Shizuku.isPreV11()) {
            Log.d(TAG, "Shizuku pre-v11 is not supported");
        } else {
            Log.d(TAG, "Binder received");
        }
    };

    private final Shizuku.OnBinderDeadListener BINDER_DEAD_LISTENER = () -> Log.d(TAG, "Binder dead");

    private final Shizuku.OnRequestPermissionResultListener REQUEST_PERMISSION_RESULT_LISTENER = this::onRequestPermissionsResult;

    private void onRequestPermissionsResult(int requestCode, int grantResult) {
        if (grantResult == PERMISSION_GRANTED) {
            switch (requestCode) {
                case REQUEST_CODE_BUTTON1: {
                    break;
                }
            }
        } else {
            Log.d(TAG, "User denied permission");
        }
    }

    @Override
    public boolean onSupportNavigateUp() {
        // 点击返回图标时，自动退出当前 Activity
        finish();
        return true; // 返回 true 表示事件已处理
    }

    @SuppressLint({"SetTextI18n", "UseCompatLoadingForDrawables"})
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 绑定 RootService
        RootServiceHelper.bindRootService(this);
        // 初始化布局绑定
        binding = ActivityIntentInterceptBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        // 启用左上角的返回图标
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true); // 显示返回图标
        }

        contentBinding = ContentInterceptMainBinding.bind(binding.getRoot().findViewById(R.id.content_main));

        contentBinding.enableFeatureCheckbox.setChecked(SharedPreferencesUtils.getBoolean(this, "interceptIsRoot"));

        contentBinding.enableFeatureCheckbox.setOnCheckedChangeListener((buttonView, isChecked1) -> {
            // 将复选框状态存入 SharedPreferences
            SharedPreferencesUtils.putBoolean(this, "interceptIsRoot", isChecked1);
        });

        // 处理传入的 Intent 数据
        Intent originalIntent = stripUnwantedFields(getIntent());
        originalUrlText = extractUrlFromIntent(originalIntent);
        contentBinding.urlTextView.setText(originalUrlText);

        // 设置 UI 和事件监听
        // 设置重置按钮初始状态为隐藏
        binding.resetButton.setVisibility(View.GONE);

        // 动态加载初始的应用列表
        try {
            loadMatchingApps(Intent.parseUri(originalUrlText, 0));
        } catch (URISyntaxException e) {
            loadMatchingApps(getIntent());
        }
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
//        binding.btnReloadIntent.setOnClickListener((v) -> {
//            String res;
//            try {
//                res = ShizukuSystemServerApi.UserManager_getUsers(true, true, true).toString();
//            } catch (Throwable tr) {
//                tr.printStackTrace();
//                res = tr.getMessage();
//            }
//            Log.d(TAG, "getUsers: " + res);
//        });

        binding.btnReloadIntent.setOnClickListener(view -> handleReloadIntentClick());

        Shizuku.addBinderReceivedListenerSticky(BINDER_RECEIVED_LISTENER);
        Shizuku.addBinderDeadListener(BINDER_DEAD_LISTENER);
        Shizuku.addRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER);

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
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.intercept_main_drawer, menu); // 加载菜单

        // 使用反射设置溢出菜单中的图标可见
        if (menu.getClass().getSimpleName().equalsIgnoreCase("MenuBuilder")) {
            try {
                Method method = menu.getClass().getDeclaredMethod("setOptionalIconsVisible", Boolean.TYPE);
                method.setAccessible(true);
                method.invoke(menu, true);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return true; // 返回 true 以显示菜单
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.disabled) {
            Intent intent = new Intent(this, DisabledActivity.class);
            intent.putExtra("com.fourtwo.hookintent", "com.fourtwo.hookintent.DisabledActivity");
            startActivity(intent);

            return true;
        }
        return super.onOptionsItemSelected(menuItem);
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
                String SelectedItem = contentBinding.dropdownSpinner.getSelectedItem().toString();
                String[] itemsArray = getResources().getStringArray(R.array.items_array);
                if (SelectedItem.equals(itemsArray[0])) {
                    // 使用root
                    RootServiceHelper.startActivityAsRoot(this, intent);
                } else if (SelectedItem.equals(itemsArray[1])) {
                    // 使用Shizuku - 系统助手
                    ShizukuSystemServerApi.launchAssistantWithTemporaryReplacement(this, intent);
                }
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
        adapter = new IntentInterceptAdapter(this, items, this);
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

        Shizuku.removeBinderReceivedListener(BINDER_RECEIVED_LISTENER);
        Shizuku.removeBinderDeadListener(BINDER_DEAD_LISTENER);
        Shizuku.removeRequestPermissionResultListener(REQUEST_PERMISSION_RESULT_LISTENER);

    }
}