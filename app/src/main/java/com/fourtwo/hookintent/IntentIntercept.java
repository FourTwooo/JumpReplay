package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.Menu;
import android.view.LayoutInflater;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.GridView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.fourtwo.hookintent.utils.AppDialogs;
import com.fourtwo.hookintent.adapter.IntentInterceptAdapter;
import com.fourtwo.hookintent.base.DetailPayloadStore;
import com.fourtwo.hookintent.base.IntentPayloadCodec;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.data.IntentMatchItem;
import com.fourtwo.hookintent.databinding.ActivityIntentInterceptBinding;
import com.fourtwo.hookintent.databinding.ContentInterceptMainBinding;
import com.fourtwo.hookintent.manager.PermissionManager;
import com.fourtwo.hookintent.utils.LoadingOverlay;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import com.fourtwo.hookintent.utils.ShortcutHelper;
import com.google.android.material.textfield.TextInputEditText;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import android.net.Uri;
import com.fourtwo.hookintent.utils.IntentReplay;
import com.fourtwo.hookintent.utils.ShortcutParameterReplacer;
import java.util.concurrent.Executors;

public class IntentIntercept extends AppCompatActivity implements IntentInterceptAdapter.OnIntentMatchClickListener {
    private static final String TAG = "IntentIntercept";

    private ActivityIntentInterceptBinding binding; // 主布局绑定
    private ContentInterceptMainBinding contentBinding; // 子布局绑定
    private Intent originalTarget;
    private final ExecutorService iconExecutor = Executors.newSingleThreadExecutor();
    private final Handler uiHandler = new Handler(Looper.getMainLooper());
    private Future<?> iconTask;
    private LoadingOverlay iconLoadingOverlay;
    private String originalUrlText; // 用于存储初始 URL 内容

    private IntentInterceptAdapter adapter;

    private ActivityResultLauncher<String> filePickerLauncher; // 文件选择器
    private AlertDialog iconSelectionDialog;                  // 图标选择对话框
    private ImageView currentIconPreview;                     // 当前图标预览

    private static boolean isSystemXposed() {
        return false;
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

        handleIncomingIntent(getIntent());
        binding.resetButton.setVisibility(View.GONE);
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

        binding.btnReloadIntent.setOnClickListener(view -> handleReloadIntentClick());

        // 注册文件选择器
        filePickerLauncher = registerForActivityResult(
                new ActivityResultContracts.GetContent(),
                uri -> {
                    if (uri != null && currentIconPreview != null) {
                        ImageView preview = currentIconPreview;
                        iconExecutor.execute(() -> {
                            try {
                                Bitmap bitmap = decodeIcon(uri);
                                uiHandler.post(() -> {
                                    if (!isUiAlive() || currentIconPreview != preview) return;
                                    preview.setImageBitmap(bitmap);
                                    if (iconSelectionDialog != null) iconSelectionDialog.dismiss();
                                });
                            } catch (Exception | OutOfMemoryError error) {
                                uiHandler.post(() -> {
                                    if (isUiAlive()) Toast.makeText(this, "无法读取图片：" + error.getMessage(), Toast.LENGTH_LONG).show();
                                });
                            }
                        });
                    }
                }
        );
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIncomingIntent(intent);
    }

    private boolean isUiAlive() { return !isFinishing() && !isDestroyed(); }

    private Bitmap decodeIcon(Uri uri) throws Exception {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(stream, null, options);
        }
        if (options.outWidth <= 0 || options.outHeight <= 0) throw new IllegalArgumentException("不是有效图片");
        options.inSampleSize = 1;
        while (options.outWidth / options.inSampleSize > 512 || options.outHeight / options.inSampleSize > 512) {
            options.inSampleSize *= 2;
        }
        options.inJustDecodeBounds = false;
        try (InputStream stream = getContentResolver().openInputStream(uri)) {
            Bitmap bitmap = BitmapFactory.decodeStream(stream, null, options);
            if (bitmap == null) throw new IllegalArgumentException("图片解码失败");
            return bitmap;
        }
    }

    private void handleIncomingIntent(Intent incoming) {
        try {
            Intent detail = incoming.getParcelableExtra("DetailIntent");
            String detailText = incoming.getStringExtra("DetailData");
            String token = incoming.getStringExtra(DetailPayloadStore.EXTRA_TOKEN);
            if (token != null) {
                ItemData saved = DetailPayloadStore.load(this, token);
                detail = IntentPayloadCodec.decode(saved.getAppBundle());
                detailText = "Scheme".equals(saved.getCategory())
                        ? saved.getAppBundle().getString("scheme_raw_url") : saved.getUri();
                if (detailText == null || detailText.isEmpty()) detailText = extractUrlFromIntent(detail);
            }
            if (detail != null) originalTarget = new Intent(detail);
            else if (detailText != null) originalTarget = Intent.parseUri(detailText, 0);
            else {
                originalTarget = new Intent(incoming);
                if (originalTarget.getComponent() != null
                        && getPackageName().equals(originalTarget.getComponent().getPackageName())
                        && getClass().getName().equals(originalTarget.getComponent().getClassName())) {
                    originalTarget.setComponent(null);
                }
            }
            originalUrlText = detailText != null ? detailText : extractUrlFromIntent(originalTarget);
            contentBinding.urlTextView.setText(originalUrlText);
            loadMatchingApps(originalTarget);
        } catch (Exception error) {
            Log.e(TAG, "Cannot read incoming Intent", error);
            Toast.makeText(this, "Intent 内容无效：" + error.getMessage(), Toast.LENGTH_LONG).show();
            originalTarget = new Intent(Intent.ACTION_VIEW);
            originalUrlText = "";
            contentBinding.urlTextView.setText("");
            loadMatchingApps(originalTarget);
        }
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

    private List<Drawable> loadAppIcons() {
        PackageManager packageManager = getPackageManager();

        List<ApplicationInfo> apps = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);

        // 使用 Set 去重
        Set<Drawable> appIcons = new HashSet<>();
        for (ApplicationInfo app : apps) {
            if ((app.flags & ApplicationInfo.FLAG_SYSTEM) != 0){continue;}
            appIcons.add(app.loadIcon(packageManager));
        }

        List<Drawable> uniqueAppIcons = new ArrayList<>(appIcons); // 转为 List 以适配 Adapter

        return uniqueAppIcons;
    }

    private void bindAppIcons(GridView iconsGrid, ImageView iconPreview, List<Drawable> uniqueAppIcons) {
        BaseAdapter adapter = new BaseAdapter() {
            @Override
            public int getCount() {
                return uniqueAppIcons.size();
            }

            @Override
            public Object getItem(int position) {
                return uniqueAppIcons.get(position);
            }

            @Override
            public long getItemId(int position) {
                return position;
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                ImageView imageView;
                if (convertView == null) {
                    imageView = new ImageView(parent.getContext());
                    imageView.setLayoutParams(new GridView.LayoutParams(100, 100));
                    imageView.setScaleType(ImageView.ScaleType.CENTER_CROP);
                } else {
                    imageView = (ImageView) convertView;
                }
                imageView.setImageDrawable(uniqueAppIcons.get(position));
                return imageView;
            }
        };

        iconsGrid.setAdapter(adapter);

        // 点击图标后更新预览并关闭对话框
        iconsGrid.setOnItemClickListener((parent, view, position, id) -> {
            iconPreview.setImageDrawable(uniqueAppIcons.get(position));
            if (iconSelectionDialog != null) {
                iconSelectionDialog.dismiss(); // 关闭对话框
            }
        });
    }

    private void showIconSelectionDialog(ImageView iconPreview) {
        // 创建加载进度
        LoadingOverlay loadingOverlay = new LoadingOverlay(this);
        iconLoadingOverlay = loadingOverlay;
        loadingOverlay.setLoadingText("正在加载应用列表...");
        loadingOverlay.show();

        // 保存当前预览图标视图引用
        currentIconPreview = iconPreview;

        // 创建图标选择对话框
        AlertDialog.Builder builder = AppDialogs.builder(this);
        View view = LayoutInflater.from(builder.getContext()).inflate(R.layout.icon_selection_dialog, null);
        builder.setView(view);

        iconSelectionDialog = builder.create(); // 保存对话框实例

        GridView iconsGrid = view.findViewById(R.id.icons_grid);
        ImageView customIconButton = view.findViewById(R.id.custom_icon_button);

        if (iconTask != null) iconTask.cancel(true);
        AlertDialog targetDialog = iconSelectionDialog;
        iconTask = iconExecutor.submit(() -> {
            try {
                List<Drawable> icons = loadAppIcons();
                uiHandler.post(() -> {
                    if (!isUiAlive() || iconSelectionDialog != targetDialog) return;
                    bindAppIcons(iconsGrid, iconPreview, icons);
                    loadingOverlay.hide();
                    targetDialog.show();
                    AppDialogs.sizeForList(targetDialog);
                });
            } catch (Exception | OutOfMemoryError error) {
                uiHandler.post(() -> {
                    if (!isUiAlive()) return;
                    loadingOverlay.hide();
                    Toast.makeText(this, "无法加载图标：" + error.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });

        // 自定义图标按钮点击事件
        customIconButton.setOnClickListener(v -> filePickerLauncher.launch("image/*"));
    }


    private Bitmap getBitmapFromImageView(ImageView imageView) {
        Drawable drawable = imageView.getDrawable();
        if (drawable == null) drawable = getDrawable(R.drawable.ic_launcher_foreground);
        if (drawable instanceof BitmapDrawable && !((BitmapDrawable) drawable).getBitmap().isRecycled()) {
            return ((BitmapDrawable) drawable).getBitmap();
        }
        if (drawable == null) return Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888);

        // 如果是其他类型的 Drawable（例如 VectorDrawable），将其转换为 Bitmap
        Bitmap bitmap = Bitmap.createBitmap(Math.max(1, Math.min(512, drawable.getIntrinsicWidth())), Math.max(1, Math.min(512, drawable.getIntrinsicHeight())), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    private void showShortcutPermissionGuideDialog(String message) {
        if (isFinishing()) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR1 && isDestroyed()) {
            return;
        }

        AppDialogs.builder(this)
                .setTitle("无法创建桌面快捷方式")
                .setMessage(message + "\n\n你可以进入应用权限或桌面权限设置，打开“创建桌面快捷方式”权限后再试。")
                .setPositiveButton("去设置", (dialog, which) ->
                        ShortcutHelper.openShortcutPermissionSettings(this)
                )
                .setNegativeButton("取消", null)
                .show();
    }
    public void SetShortcut() {
        // 在 Activity 或 Fragment 中使用 Dialog
        AlertDialog.Builder builder = AppDialogs.builder(this);
        View view = LayoutInflater.from(builder.getContext()).inflate(R.layout.shortcut_config_dialog, null);
        builder.setView(view);

        AlertDialog dialog = builder.create();

        // 获取控件
        TextInputEditText shortcutName = view.findViewById(R.id.shortcut_name);
        ImageView iconPreview = view.findViewById(R.id.icon_preview);
        TextInputEditText intentString = view.findViewById(R.id.intent_string);
        LinearLayout dynamicParamsContainer = view.findViewById(R.id.dynamic_params_container);
        ImageView addParamButton = view.findViewById(R.id.add_param_button);
        Button submitButton = view.findViewById(R.id.submit_button);
        Button cancelButton = view.findViewById(R.id.cancel_button);
        CheckBox enableFeatureCheckbox = view.findViewById(R.id.enable_feature_checkbox);
        Spinner dropdownSpinner = view.findViewById(R.id.dropdown_spinner);

        intentString.setText(Objects.requireNonNull(contentBinding.urlTextView.getText()).toString());

        // 选择图标
        iconPreview.setOnClickListener(v -> showIconSelectionDialog(iconPreview));

        // 动态添加参数
        addParamButton.setOnClickListener(v -> {
            // 创建一个水平布局，用来包含 EditText 和删除按钮
            LinearLayout paramContainer = new LinearLayout(view.getContext());
            paramContainer.setOrientation(LinearLayout.HORIZONTAL);

            // 创建 EditText 参数输入框
            EditText paramInput = AppDialogs.input(this);
            paramInput.setHint("请输入参数");
            paramInput.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

            // 创建删除按钮
            ImageButton deleteButton = new ImageButton(view.getContext());
            deleteButton.setBackground(null); // 移除默认背景
            deleteButton.setImageResource(R.drawable.baseline_delete_outline_24);
            deleteButton.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
            deleteButton.setOnClickListener(deleteView -> dynamicParamsContainer.removeView(paramContainer));

            // 将 EditText 和删除按钮添加到该容器
            paramContainer.addView(paramInput);
            paramContainer.addView(deleteButton);

            // 将容器添加到动态参数布局中
            dynamicParamsContainer.addView(paramContainer);
        });

        // 提交按钮
        submitButton.setOnClickListener(v -> {
            String name = Objects.requireNonNull(shortcutName.getText()).toString().trim();
            String intent = Objects.requireNonNull(intentString.getText()).toString().trim();

            if (name.isEmpty()) {
                shortcutName.setError("请输入快捷方式名称");
                return;
            }

            if (intent.isEmpty()) {
                intentString.setError("Intent 不能为空");
                return;
            }

            ArrayList<String> dynamicParams = new ArrayList<>();
            for (int i = 0; i < dynamicParamsContainer.getChildCount(); i++) {
                LinearLayout paramContainer = (LinearLayout) dynamicParamsContainer.getChildAt(i);
                EditText paramInput = (EditText) paramContainer.getChildAt(0);
                dynamicParams.add(paramInput.getText().toString());
            }

            Intent completeTarget;
            try {
                ShortcutParameterReplacer.validate(intent, dynamicParams.toArray(new String[0]));
                completeTarget = IntentReplay.fromText(intent, originalTarget, originalUrlText);
            } catch (Exception error) {
                intentString.setError(error.getMessage());
                return;
            }
            Bitmap shortcutIcon = getBitmapFromImageView(iconPreview);

            ShortcutHelper.createShortcut(
                    this,
                    name,
                    shortcutIcon,
                    intent,
                    dynamicParams,
                    enableFeatureCheckbox.isChecked(),
                    dropdownSpinner.getSelectedItem() == null
                            ? ""
                            : dropdownSpinner.getSelectedItem().toString(),
                    completeTarget,
                    new ShortcutHelper.ShortcutCallback() {
                        @Override
                        public void onNotSupported() {
                            Toast.makeText(
                                    IntentIntercept.this,
                                    "当前桌面不支持创建快捷方式",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }

                        @Override
                        public void onPermissionDenied() {
                            showShortcutPermissionGuideDialog(
                                    "检测到当前应用没有创建桌面快捷方式权限，请在系统设置中允许后重试。"
                            );
                        }

                        @Override
                        public void onRequestSent() {
                            dialog.dismiss();
                            Toast.makeText(
                                    IntentIntercept.this,
                                    "请在系统弹窗中确认创建快捷方式",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }

                        @Override
                        public void onPinned() {
                            Toast.makeText(
                                    IntentIntercept.this,
                                    "快捷方式已创建",
                                    Toast.LENGTH_SHORT
                            ).show();
                        }

                        @Override
                        public void onNoResultMaybeDenied() {
                            showShortcutPermissionGuideDialog(
                                    "没有收到桌面创建成功回调。可能是你取消了系统弹窗，也可能是 ROM 禁止了创建桌面快捷方式。"
                            );
                        }

                        @Override
                        public void onError(Exception e) {
                            Toast.makeText(
                                    IntentIntercept.this,
                                    "创建快捷方式失败：" + e.getMessage(),
                                    Toast.LENGTH_SHORT
                            ).show();
                        }
                    }
            );
        });

        // 取消按钮
        cancelButton.setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.disabled) {
            Intent intent = new Intent(this, DisabledActivity.class);
            intent.putExtra("com.fourtwo.hookintent", "com.fourtwo.hookintent.DisabledActivity");
            startActivity(intent);
            return true;
        } else if (itemId == R.id.add_link) {
            SetShortcut();
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
            loadMatchingApps(IntentReplay.fromText(updatedText, originalTarget, originalUrlText));
        } catch (URISyntaxException | IllegalArgumentException ignored) {
            loadMatchingApps(new Intent(Intent.ACTION_VIEW));
        }
    }


    /**
     * 处理发送按钮的点击事件
     */
    private void handleReloadIntentClick() {
        try {
            Intent newIntent = IntentReplay.fromText(Objects.requireNonNull(contentBinding.urlTextView.getText()).toString(), originalTarget, originalUrlText);
            PermissionManager.startActivity(this, newIntent, contentBinding.enableFeatureCheckbox.isChecked(), contentBinding.dropdownSpinner.getSelectedItem().toString());
        } catch (Exception error) {
            Toast.makeText(this, "Intent 无法重放：" + error.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 实现 OnIntentMatchClickListener 接口
     */
    @Override
    public void onIntentMatchClick(Intent intent) {
        if (intent != null) {
            PermissionManager.startActivity(this, intent, contentBinding.enableFeatureCheckbox.isChecked(), contentBinding.dropdownSpinner.getSelectedItem().toString());
        }
    }

    /**
     * 从 Intent 中提取 URL 并解码
     */
    private String extractUrlFromIntent(Intent intent) {
        if (intent.getStringExtra("DetailData") != null){
            return intent.getStringExtra("DetailData");
        }
        String urlText = intent.getDataString();
        if (urlText == null) {
            urlText = intent.toUri(Intent.URI_INTENT_SCHEME);
        }
        return urlText;
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
        Intent queryIntent = new Intent(intent);
        queryIntent.setComponent(null);
        queryIntent.setSelector(null);
        List<ResolveInfo> resolveInfoList = packageManager.queryIntentActivities(queryIntent, PackageManager.MATCH_DEFAULT_ONLY);

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
        if (iconTask != null) iconTask.cancel(true);
        iconExecutor.shutdownNow();
        uiHandler.removeCallbacksAndMessages(null);
        if (iconSelectionDialog != null) iconSelectionDialog.dismiss();
        if (iconLoadingOverlay != null) iconLoadingOverlay.hide();
        currentIconPreview = null;
        super.onDestroy();
    }
}