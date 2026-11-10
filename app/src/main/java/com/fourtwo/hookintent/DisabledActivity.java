package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;
import android.graphics.drawable.GradientDrawable;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.adapter.DisabledActivityAdapter;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.databinding.ActivityDisabledBinding;
import com.fourtwo.hookintent.utils.BatteryOptimizationHelper;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public class DisabledActivity extends AppCompatActivity {

    private List<Map<String, Object>> dataList; // 数据源
    private DisabledActivityAdapter adapter;   // 适配器
    ActivityDisabledBinding binding;

    final static String TAG = "DisabledActivity";

    public void updateDisabledSchemes() {
        SharedPreferencesUtils.putStr(this, Constants.DISABLED_SCHEME, JsonHandler.serializeHookedRecords(dataList));
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化工具类
        BatteryOptimizationHelper batteryHelper = new BatteryOptimizationHelper(this);
        if (!batteryHelper.isIgnoringBatteryOptimizations()) {
            Log.i(TAG, "不在白名单中");
            // 跳转到电池优化设置页面
            batteryHelper.showSimpleDialog(getString(R.string.disabled_string));
        } else {
            Log.i(TAG, "在白名单中");
        }


        binding = ActivityDisabledBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setSupportActionBar(binding.toolbar);

        // 启用左上角的返回图标
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true); // 显示返回图标
        }

        // 初始化数据源
        dataList = new ArrayList<>();
        adapter = new DisabledActivityAdapter(dataList); // 使用空数据初始化

        // 设置 RecyclerView 的适配器
        RecyclerView recyclerView = binding.disabledSchemes;
        recyclerView.setLayoutManager(new LinearLayoutManager(this)); // 添加布局管理器
        recyclerView.setAdapter(adapter);

        String DISABLED_SCHEME = SharedPreferencesUtils.getStr(this, Constants.DISABLED_SCHEME);
        Log.d(TAG, "onCreate: " + DISABLED_SCHEME);
        if (DISABLED_SCHEME != null) {
            for (Map<String, Object> _item : JsonHandler.deserializeHookedRecords(DISABLED_SCHEME)) {
                addDataToRecyclerView(_item);
            }
        }

    }

    @Override
    public boolean onSupportNavigateUp() {
        // 点击返回图标时，自动退出当前 Activity
        finish();
        return true; // 返回 true 表示事件已处理
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        MenuInflater menuInflater = getMenuInflater();
        menuInflater.inflate(R.menu.select_drawer, menu); // 加载菜单

        return true; // 返回 true 以显示菜单
    }

    private void updateRegexToggle(ImageView regexToggle, TextView modeText, boolean enabled) {
        Drawable drawable = ContextCompat.getDrawable(this, R.drawable.regex);
        if (drawable != null) {
            drawable = DrawableCompat.wrap(drawable.mutate());
            DrawableCompat.setTint(
                    drawable,
                    android.graphics.Color.parseColor(enabled ? "#2E6EF7" : "#6B7280")
            );
            regexToggle.setImageDrawable(drawable);
        }

        modeText.setText(enabled ? "正则匹配已启用" : "普通匹配");
        modeText.setTextColor(android.graphics.Color.parseColor(enabled ? "#2E6EF7" : "#6B7280"));
    }

    private Button createDialogActionButton(String text, String fillColor, String strokeColor, String textColor) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setMinWidth(dp(78));
        button.setPadding(dp(20), 0, dp(20), 0);
        button.setTextSize(14);
        button.setTypeface(null, android.graphics.Typeface.BOLD);
        button.setTextColor(android.graphics.Color.parseColor(textColor));
        button.setBackground(createRoundRect(fillColor, strokeColor, 999));
        return button;
    }

    private GradientDrawable createRoundRect(String fillColor, String strokeColor, int radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setColor(android.graphics.Color.parseColor(fillColor));
        drawable.setCornerRadius(dp(radiusDp));
        if (strokeColor != null) {
            drawable.setStroke(dp(1), android.graphics.Color.parseColor(strokeColor));
        }
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    @SuppressLint("NotifyDataSetChanged")
    private void showAddTextDialog() {
        final boolean[] isRegex = {false};

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(18), dp(18), dp(18));
        root.setBackground(createRoundRect("#FCFCFD", "#ECEEF2", 24));

        TextView title = new TextView(this);
        title.setText("添加 Scheme");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(android.graphics.Color.parseColor("#1F2937"));
        root.addView(title);

        LinearLayout inputBox = new LinearLayout(this);
        inputBox.setOrientation(LinearLayout.HORIZONTAL);
        inputBox.setGravity(android.view.Gravity.CENTER_VERTICAL);
        inputBox.setBackground(createRoundRect("#F3F4F6", "#F3F4F6", 18));
        inputBox.setPadding(dp(14), dp(8), dp(12), dp(8));

        LinearLayout.LayoutParams inputBoxLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        inputBoxLp.topMargin = dp(14);
        root.addView(inputBox, inputBoxLp);

        final EditText input = new EditText(this);
        input.setHint("输入 Scheme 或正则");
        input.setBackground(null);
        input.setTextSize(14);
        input.setHintTextColor(android.graphics.Color.parseColor("#9CA3AF"));
        input.setTextColor(android.graphics.Color.parseColor("#1F2937"));
        input.setSingleLine(true);
        input.setPadding(0, dp(6), 0, dp(6));

        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(
                0,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                1f
        );
        inputBox.addView(input, inputLp);

        ImageView regexToggle = new ImageView(this);
        regexToggle.setPadding(dp(8), dp(8), dp(8), dp(8));
        inputBox.addView(regexToggle, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView modeText = new TextView(this);
        modeText.setTextSize(12);
        modeText.setTextColor(android.graphics.Color.parseColor("#6B7280"));
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        modeLp.topMargin = dp(8);
        root.addView(modeText, modeLp);

        updateRegexToggle(regexToggle, modeText, isRegex[0]);

        regexToggle.setOnClickListener(v -> {
            isRegex[0] = !isRegex[0];
            updateRegexToggle(regexToggle, modeText, isRegex[0]);
        });

        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(android.view.Gravity.CENTER);

        LinearLayout.LayoutParams rowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
        rowLp.topMargin = dp(18);
        root.addView(buttonRow, rowLp);

        Button cancelButton = createDialogActionButton(
                "取消",
                "#F3F4F6",
                "#E6E8EC",
                "#6B7280"
        );

        Button confirmButton = createDialogActionButton(
                "确定",
                "#EAF1FF",
                "#D6E4FF",
                "#2E6EF7"
        );

        LinearLayout.LayoutParams cancelLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        );
        cancelLp.rightMargin = dp(8);

        LinearLayout.LayoutParams confirmLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                dp(38)
        );
        confirmLp.leftMargin = dp(8);

        buttonRow.addView(cancelButton, cancelLp);
        buttonRow.addView(confirmButton, confirmLp);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(root)
                .create();

        dialog.setCanceledOnTouchOutside(true);
        dialog.setCancelable(true);

        cancelButton.setOnClickListener(v -> dialog.dismiss());

        confirmButton.setOnClickListener(v -> {
            String newText = input.getText().toString().trim();
            if (newText.isEmpty()) {
                Toast.makeText(this, "Scheme 不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            Map<String, Object> newItem = new HashMap<>();
            newItem.put("text", newText);
            newItem.put("re", isRegex[0]);
            newItem.put("open", true);

            addDataToRecyclerView(newItem);
            dialog.dismiss();
        });

        dialog.show();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
            dialog.getWindow().setLayout(
                    (int) (getResources().getDisplayMetrics().widthPixels * 0.90f),
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
        }
    }

    /**
     * 将新数据添加到 RecyclerView 并刷新显示
     */
    @SuppressLint("NotifyDataSetChanged")
    private void addDataToRecyclerView(Map<String, Object> newItem) {
        adapter.addItem(newItem);
        adapter.notifyDataSetChanged();
        updateDisabledSchemes();
    }


    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem menuItem) {
        int itemId = menuItem.getItemId();
        if (itemId == R.id.add_text) {
            showAddTextDialog();
            return true;
        }
        return super.onOptionsItemSelected(menuItem);
    }


    @Override
    protected void onPause() {
        super.onPause();
        Log.d(TAG, "onPause: " + dataList);
        updateDisabledSchemes();
    }


}