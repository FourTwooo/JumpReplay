package com.fourtwo.hookintent.ui.detail;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.PopupMenu;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.util.Pair;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.MainApplication;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.AmCommandBuilder;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.base.LocalDatabaseManager;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.manager.PermissionManager;
import com.fourtwo.hookintent.utils.HashUtil;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import com.google.android.material.tabs.TabLayout;

import org.json.JSONObject;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class DetailFragment extends Fragment {

    private String TAG = "DetailFragment";
    private static final String ARG_ITEM_DATA = "itemData";
    private RecyclerView recyclerView;
    private TextView urlTextView;
    private TabLayout tabLayout;
    private LocalDatabaseManager dbManager;
    private JSONObject jsonObject;
    private ItemData itemData = null;
    @Override
    public void onResume() {
        super.onResume();

        // 初始化数据库管理类
        dbManager = new LocalDatabaseManager(requireContext());
        // 打开数据库
        dbManager.openDatabase(Constants.STAR_DB_NAME);
        // 触发菜单重新创建
        requireActivity().invalidateOptionsMenu();
    }
    @SuppressLint("MissingInflatedId")
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_detail, container, false);

        // 初始化 View
        urlTextView = view.findViewById(R.id.urlTextView);
        recyclerView = view.findViewById(R.id.dataRecyclerView);
        tabLayout = view.findViewById(R.id.tabLayout);
        TextView tabTextView = view.findViewById(R.id.tabTextView);
        tabTextView.setMovementMethod(new ScrollingMovementMethod());

        // 设置 TabLayout 的选项卡
        tabLayout.addTab(tabLayout.newTab().setText("Text"));
        tabLayout.addTab(tabLayout.newTab().setText("StackTrace"));

        // 从 Arguments 获取 ItemData
        if (getArguments() != null) {
            itemData = getArguments().getParcelable(ARG_ITEM_DATA);
        }

        // 设置选项卡选择监听器
        ItemData finalItemData = itemData; // 确保 finalItemData 被正确初始化
        tabLayout.addOnTabSelectedListener(new TabLayout.OnTabSelectedListener() {
            @Override
            public void onTabSelected(TabLayout.Tab tab) {
                if (tab.getPosition() == 0) {
                    recyclerView.setVisibility(View.VISIBLE);
                    tabTextView.setVisibility(View.GONE);
                } else if (tab.getPosition() == 1) {
                    recyclerView.setVisibility(View.GONE);
                    tabTextView.setVisibility(View.VISIBLE);
                    if (finalItemData != null) {
                        tabTextView.setText(finalItemData.getStackTrace()); // 显示 stack trace
                    }
                }
            }

            @Override
            public void onTabUnselected(TabLayout.Tab tab) {
            }

            @Override
            public void onTabReselected(TabLayout.Tab tab) {
            }
        });

        // 初始化 RecyclerView 数据
        if (itemData != null) {
            urlTextView = view.findViewById(R.id.urlTextView);
            urlTextView.setText(itemData.getItem_from());

            List<String> keys = Arrays.asList("time", "FunctionCall", "packageName", "processName", "from", "component", "scheme_raw_url");

            Map<String, String> dataMap = new HashMap<>();
            Bundle bundle = itemData.getAppBundle();

            Map<String, Object> map = JsonHandler.toMap(bundle);
            jsonObject = new JSONObject();
            try {
                jsonObject.put(Constants.SQL_DATA, new JSONObject(map).toString());
                jsonObject.put(Constants.SQL_HASH, HashUtil.hash(map.toString(), "SHA-256"));
            } catch (Exception e) {
                Log.e(TAG, "onCreateView: ", e);
            }
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                dataMap.put(key, value != null ? value.toString() : "null");
            }

            List<Pair<String, String>> dataList = new ArrayList<>();

            for (String key : keys) {
                if (dataMap.containsKey(key)) {
                    dataList.add(new Pair<>(key, dataMap.get(key)));
                    dataMap.remove(key);
                }
            }

            dataList.add(new Pair<>("separator", ""));

            List<String> no_print_keys = Arrays.asList("stack_trace", "category", "uri", "title", "data", "componentClassName");

            for (Map.Entry<String, String> entry : dataMap.entrySet()) {
                if (no_print_keys.contains(entry.getKey())) {
                    continue;
                }
                if (Objects.equals(entry.getValue(), "null")){
                    continue;
                }
                dataList.add(new Pair<>(entry.getKey(), entry.getValue()));
            }

            DetailAdapter adapter = new DetailAdapter(dataList);
            recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
            recyclerView.setAdapter(adapter);

            DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL);
            dividerItemDecoration.setDrawable(Objects.requireNonNull(ContextCompat.getDrawable(requireContext(), R.drawable.divider)));
            recyclerView.addItemDecoration(dividerItemDecoration);
        }


        return view;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        // 保存当前Tab的状态
        if (tabLayout != null) {
            outState.putInt("selected_tab", tabLayout.getSelectedTabPosition());
        }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化数据库管理类
        dbManager = new LocalDatabaseManager(requireContext());

        // 打开数据库
        dbManager.openDatabase(Constants.STAR_DB_NAME);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        DetailViewModel viewModel = new ViewModelProvider(this).get(DetailViewModel.class);

        if (viewModel.getItemData().getValue() == null && getArguments() != null) {
            ItemData itemData = getArguments().getParcelable(ARG_ITEM_DATA); // 使用 getParcelable
            viewModel.setItemData(itemData);
        }

        viewModel.getItemData().observe(getViewLifecycleOwner(), itemData -> {
            if (itemData != null) {
                // 更新UI组件
                urlTextView.setText(itemData.getItem_from());
                // 更新RecyclerView等
            }
        });

        // 恢复Tab的状态
        if (savedInstanceState != null) {
            int selectedTab = savedInstanceState.getInt("selected_tab", 0);
            TabLayout.Tab tab = tabLayout.getTabAt(selectedTab);
            if (tab != null) {
                tab.select();
            }
        }

        requireActivity().addMenuProvider(new MenuProvider() {
            boolean isStarred = false; // 是否已收藏的状态

            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menu.clear(); // 清除现有菜单项，避免重复
                menuInflater.inflate(R.menu.detail_drawer, menu); // 加载菜单

                // 动态设置收藏按钮图标
                try {
                    String _hash = jsonObject.getString(Constants.SQL_HASH); // 获取 _hash
                    dbManager.createTable(Constants.STAR_TABLE_NAME); // 确保表存在

                    // 检查数据库中是否存在该 _hash
                    isStarred = dbManager.is_exists(Constants.STAR_TABLE_NAME, _hash);

                    // 根据状态设置收藏图标
                    MenuItem starMenuItem = menu.findItem(R.id.star);
                    if (isStarred) {
                        starMenuItem.setIcon(R.drawable.star_filled); // 已收藏图标
                    } else {
                        starMenuItem.setIcon(R.drawable.star_outline); // 未收藏图标
                    }
                } catch (Exception e) {
                    Log.e(TAG, "onCreateMenu: ", e);
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.copy_code) {
                    // 获取菜单项的 View 并传递给 showOptionsDialog
                    View anchorView = requireActivity().findViewById(R.id.copy_code);
                    showOptionsDialog(anchorView); // 调用修改后的 showOptionsDialog
                    return true;
                } else if (itemId == R.id.star) {
                    try {
                        String _hash = jsonObject.getString(Constants.SQL_HASH); // 获取 _hash
                        dbManager.createTable(Constants.STAR_TABLE_NAME); // 确保表存在

                        // 切换收藏状态
                        if (isStarred) {
                            // 如果已收藏，执行取消收藏操作
                            boolean isDeleted = dbManager.deleteData(Constants.STAR_TABLE_NAME, _hash);
                            if (isDeleted) {
                                isStarred = false;
                                menuItem.setIcon(R.drawable.star_outline); // 更新图标为未收藏
                                Toast.makeText(requireContext(), "取消收藏成功", Toast.LENGTH_SHORT).show();
                            } else {
                                Toast.makeText(requireContext(), "取消收藏失败", Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            // 如果未收藏，执行收藏操作
                            dbManager.insertOrUpdateData(Constants.STAR_TABLE_NAME, jsonObject);
                            isStarred = true;
                            menuItem.setIcon(R.drawable.star_filled); // 更新图标为已收藏
                            Toast.makeText(requireContext(), "收藏成功", Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        Toast.makeText(requireContext(), "操作失败", Toast.LENGTH_SHORT).show();
                    }
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

    }

    private void showOptionsDialog(View anchorView) {
        // 获取 Arguments 中的 itemData
        ItemData itemData = null;
        if (getArguments() != null) {
            itemData = getArguments().getParcelable(ARG_ITEM_DATA); // 使用 getParcelable
        }

        // 如果 itemData 为空，直接返回
        if (itemData == null) {
            Toast.makeText(requireContext(), "错误：数据未加载", Toast.LENGTH_SHORT).show();
            return;
        }

        Bundle bundle = itemData.getAppBundle();
        String Category = itemData.getCategory();
        List<String> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (Category.equals("Intent")) {
            ArrayList<?> intentExtras = bundle.getStringArrayList("intentExtras");
            boolean hasError = false;
            String activityTemplate = "am start -n %s %s";
            String packageName = bundle.getString("componentClassName");
            String buildAmCommand = "";
            if (intentExtras != null) {
                AmCommandBuilder.CommandResult result = AmCommandBuilder.buildAmCommand((List<Map<String, Object>>) intentExtras);
                buildAmCommand = result.command;
                hasError = result.hasError;
                Log.d(TAG, "buildAmCommand:" + buildAmCommand);
            }
            String activityCommand = String.format(activityTemplate, packageName, buildAmCommand);

            options.add("am命令");
            boolean finalHasError = hasError;
            actions.add(() -> showAmCommandDialog(activityCommand, finalHasError, "am命令"));

            String uriCommand = itemData.getUri();
            Log.d(TAG, "intentCommand: " + uriCommand);

            options.add("intentUri");
            actions.add(() -> showAmCommandDialog(uriCommand, false, "intentUri"));
        } else if (Category.equals("Scheme")) {
            String uriCommand = itemData.getAppBundle().getString("scheme_raw_url");

            Log.d(TAG, "schemeCommand: " + uriCommand);
            options.add("schemeUri");
            actions.add(() -> showAmCommandDialog(uriCommand, false, "schemeUri"));
        }

        // 改用 PopupMenu
        PopupMenu popupMenu = new PopupMenu(requireContext(), anchorView);
        for (int i = 0; i < options.size(); i++) {
            popupMenu.getMenu().add(Menu.NONE, i, i, options.get(i));
        }

        // 设置菜单项点击事件
        popupMenu.setOnMenuItemClickListener(item -> {
            int which = item.getItemId();
            actions.get(which).run(); // 执行对应的操作
            return true;
        });

        // 显示 PopupMenu
        popupMenu.show();
    }

    /**
     * @param amCommand  命令
     * @param hasError   命令是否异常
     * @param optionName 类型
     */
    @SuppressLint("SetTextI18n")
    private void showAmCommandDialog(String amCommand, boolean hasError, String optionName) {
        // 使用 LayoutInflater 加载自定义视图
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View dialogView = inflater.inflate(R.layout.dialog_custom_view, null);

        // 获取视图中的元素
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) EditText commandTextView = dialogView.findViewById(R.id.command_edit_text);
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) TextView hintTextView = dialogView.findViewById(R.id.hint_text_view);
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) Button copyButton = dialogView.findViewById(R.id.copy_button);
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) Button suCodeButton = dialogView.findViewById(R.id.su_code_button);
        CheckBox enableFeatureCheckbox = dialogView.findViewById(R.id.enable_feature_checkbox);
        Spinner dropdownSpinner = dialogView.findViewById(R.id.dropdown_spinner);
        if (Objects.equals(optionName, "am命令")) {
            dropdownSpinner.setEnabled(false); // 禁用 Spinner
        }
        enableFeatureCheckbox.setChecked(SharedPreferencesUtils.getBoolean(requireContext(), "detailIsRoot"));

        enableFeatureCheckbox.setOnCheckedChangeListener((buttonView, isChecked1) -> {
            // 将复选框状态存入 SharedPreferences
            SharedPreferencesUtils.putBoolean(requireContext(), "detailIsRoot", isChecked1);
        });

        try {
            amCommand = URLDecoder.decode(amCommand, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ignored) {
        }

        commandTextView.setText(amCommand); // 确保文本足够长以测试滚动
        commandTextView.setMovementMethod(new ScrollingMovementMethod());

        if (hasError) {
            hintTextView.setVisibility(View.VISIBLE);
            hintTextView.setText("Extras包含自定义类型,已忽略但命令可能无效");
        }

        suCodeButton.setOnClickListener(v -> {
            if (!Objects.equals(optionName, "am命令")) {
                try {
                    Intent newIntent = Intent.parseUri(String.valueOf(commandTextView.getText()), 0);

                    String SelectedItem = dropdownSpinner.getSelectedItem().toString();
                    PermissionManager.startActivity(requireContext(), newIntent, enableFeatureCheckbox.isChecked(), SelectedItem);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            } else {
                MainApplication.executeCommand(String.valueOf(commandTextView.getText()), enableFeatureCheckbox.isChecked(), requireContext());
            }
        });

        copyButton.setOnClickListener(v -> {
            ClipboardManager clipboard = (ClipboardManager) requireContext().getSystemService(Context.CLIPBOARD_SERVICE);
            ClipData clip = ClipData.newPlainText("amCommand", commandTextView.getText());
            clipboard.setPrimaryClip(clip);
            Toast.makeText(requireContext(), "已复制到剪贴板", Toast.LENGTH_SHORT).show();
        });

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                // .setPositiveButton("关闭", null)
                .create();

        // 设置弹窗背景为圆角背景
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_rounded_background);
        }
        dialog.show();
    }

    @Override
    public void onPause() {
        super.onPause();
        if (dbManager != null) {
            dbManager.closeDatabase();
        }
    }
}