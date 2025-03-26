package com.fourtwo.hookintent;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.Log;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.Toast;

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
            batteryHelper.showSimpleDialog("禁用意图需要电池优化,否则会出现JumpReplay未启动导致意图禁用失效.");
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

    @SuppressLint({"NotifyDataSetChanged", "ClickableViewAccessibility"})
    private void showAddTextDialog() {
        // 创建一个 AlertDialog.Builder 用于构建对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle("Add Scheme");

        // 创建 EditText
        final EditText input = new EditText(this);

        // 设置右侧图标
        input.setCompoundDrawablesWithIntrinsicBounds(0, 0, R.drawable.pattern, 0);

        // 设置图标是否为正则表达式的标志
        final boolean[] isRegex = {false}; // 默认不是正则

        // 优化点击事件，通过 Drawable 的位置直接判断点击
        input.setOnTouchListener((v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_UP) {
                // 获取 Drawable 的位置
                if (input.getCompoundDrawables()[2] != null) { // 确保右侧 Drawable 存在
                    int drawableStart = input.getWidth() - input.getPaddingRight() - input.getCompoundDrawables()[2].getBounds().width();
                    if (event.getX() >= drawableStart) {
                        // 点击了右侧图标，切换颜色
                        isRegex[0] = !isRegex[0]; // 切换正则状态
                        Drawable drawable = input.getCompoundDrawables()[2]; // 获取右侧 Drawable
                        if (drawable != null) {
                            drawable = DrawableCompat.wrap(drawable); // 包装 Drawable 以支持修改颜色
                            if (isRegex[0]) {
                                DrawableCompat.setTint(drawable, ContextCompat.getColor(this, R.color.black)); // 设置激活状态颜色
                                Toast.makeText(getApplicationContext(), "正则模式已启用", Toast.LENGTH_SHORT).show();
                            } else {
                                DrawableCompat.setTint(drawable, ContextCompat.getColor(this, R.color.teal_700)); // 设置默认颜色
                                Toast.makeText(getApplicationContext(), "正则模式已关闭", Toast.LENGTH_SHORT).show();
                            }
                            input.setCompoundDrawablesWithIntrinsicBounds(null, null, drawable, null); // 更新 Drawable
                        }

                        return true; // 事件已处理
                    }
                }
            }
            return false; // 未处理的事件继续传递
        });

        // 添加到对话框中
        builder.setView(input);

        // 设置对话框的按钮
        builder.setPositiveButton("OK", (dialog, which) -> {
            String newText = input.getText().toString().trim();
            if (!newText.isEmpty()) {
                // 创建新的条目
                Map<String, Object> newItem = new HashMap<>();
                newItem.put("text", newText);
                newItem.put("re", isRegex[0]);
                newItem.put("open", true);

                // 添加到适配器数据集
                addDataToRecyclerView(newItem);

            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        // 显示对话框
        builder.show();
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