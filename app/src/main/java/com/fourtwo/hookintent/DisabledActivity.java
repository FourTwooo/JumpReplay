package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.widget.EditText;
import android.widget.Toast;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.DrawableCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.utils.AppDialogs;
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
                    ContextCompat.getColor(this, enabled ? R.color.dialog_accent : R.color.dialog_secondary)
            );
            regexToggle.setImageDrawable(drawable);
        }

        modeText.setText(enabled ? "正则匹配已启用" : "普通匹配");
        modeText.setTextColor(ContextCompat.getColor(this, enabled ? R.color.dialog_accent : R.color.dialog_secondary));
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
    @SuppressLint("NotifyDataSetChanged")
    private void showAddTextDialog() {
        final boolean[] isRegex = {false};

        LinearLayout root = AppDialogs.content(this);
        LinearLayout inputRow = new LinearLayout(root.getContext());
        inputRow.setOrientation(LinearLayout.HORIZONTAL);
        inputRow.setGravity(android.view.Gravity.CENTER_VERTICAL);

        final EditText input = AppDialogs.input(this);
        input.setHint("输入 Scheme 或正则");
        input.setSingleLine(true);
        inputRow.addView(input, new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        ImageView regexToggle = new ImageView(root.getContext());
        regexToggle.setPadding(dp(12), dp(12), dp(12), dp(12));
        regexToggle.setContentDescription("切换正则匹配");
        inputRow.addView(regexToggle, new LinearLayout.LayoutParams(dp(48), dp(48)));
        root.addView(inputRow, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView modeText = new TextView(root.getContext());
        modeText.setTextSize(12);
        LinearLayout.LayoutParams modeLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        modeLp.topMargin = dp(8);
        root.addView(modeText, modeLp);

        updateRegexToggle(regexToggle, modeText, isRegex[0]);

        regexToggle.setOnClickListener(v -> {
            isRegex[0] = !isRegex[0];
            updateRegexToggle(regexToggle, modeText, isRegex[0]);
        });

        AlertDialog dialog = AppDialogs.builder(this)
                .setTitle("添加 Scheme")
                .setView(root)
                .setPositiveButton("确定", null)
                .setNegativeButton("取消", null)
                .create();

        dialog.setCanceledOnTouchOutside(true);
        dialog.setCancelable(true);
        dialog.show();
        // A validation failure must keep the editor open, just like the original custom button.
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
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