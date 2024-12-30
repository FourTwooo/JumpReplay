package com.fourtwo.hookintent.ui.detail;

import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
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
import android.widget.EditText;
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

import com.fourtwo.hookintent.analysis.AmCommandBuilder;
import com.fourtwo.hookintent.ItemData;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.tools.ShellExecutor;
import com.google.android.material.tabs.TabLayout;

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
        ItemData itemData = null;
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

            // Define the keys in the desired order
            List<String> keys = Arrays.asList("FunctionCall", "time", "from", "to", "scheme_raw_url");

            // Create a map to store the Bundle data
            Map<String, String> dataMap = new HashMap<>();
            Bundle bundle = itemData.getAppBundle();
            for (String key : bundle.keySet()) {
                Object value = bundle.get(key);
                dataMap.put(key, value != null ? value.toString() : "null");
            }

            // Create the dataList following the specified order
            List<Pair<String, String>> dataList = new ArrayList<>();

            // Add prioritized keys first
            for (String key : keys) {
                if (dataMap.containsKey(key)) {
                    dataList.add(new Pair<>(key, dataMap.get(key)));
                    dataMap.remove(key);  // Remove the added key from the map
                }
            }

            // Add a special empty pair to indicate the separator
            dataList.add(new Pair<>("separator", ""));  // Use a special key to identify the separator

            // Add the remaining keys that were not specified in the order
            for (Map.Entry<String, String> entry : dataMap.entrySet()) {
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
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menu.clear(); // Clear existing menu items to avoid duplication
                menuInflater.inflate(R.menu.detail_drawer, menu); // Inflate the menu specific to this Fragment
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.copy_code) {
                    showOptionsDialog();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    private void showOptionsDialog() {
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

        // Continue with processing itemData
        Log.d(TAG, "AlertDialogItemData: " + itemData.getAppBundle());

        Bundle bundle = itemData.getAppBundle();
        String Base = itemData.getBase();
        List<String> options = new ArrayList<>();
        List<Runnable> actions = new ArrayList<>();

        if (Base.equals("Intent")) {

            ArrayList<?> intentExtras = bundle.getStringArrayList("intentExtras");
            boolean hasError = false;
            String activityTemplate = "am start -n %s %s";
            String intentTemplate = "am start \"%s\"";
            String packageName = bundle.getString("componentName");
            String buildAmCommand = "";
            if (intentExtras != null) {
                AmCommandBuilder.CommandResult result = AmCommandBuilder.buildAmCommand((List<Map<String, Object>>) intentExtras);
                buildAmCommand = result.command;
                hasError = result.hasError;
                Log.d(TAG, "buildAmCommand:" + buildAmCommand);
            }
            String activityCommand = String.format(activityTemplate, packageName, buildAmCommand);

            options.add("activity命令");
            boolean finalHasError = hasError;
            actions.add(() -> showAmCommandDialog(activityCommand, finalHasError));

            String uriCommand = String.format(intentTemplate, itemData.getUri());

            Log.d(TAG, "intentCommand: " + uriCommand);
            options.add("intent协议");
            actions.add(() -> showAmCommandDialog(uriCommand, false));
        } else if (Base.equals("Scheme")) {
            String uriTemplate = "am start -d \"%s\"";
            String uriCommand = String.format(uriTemplate, itemData.getAppBundle().getString("scheme_raw_url"));

            Log.d(TAG, "schemeCommand: " + uriCommand);
            options.add("scheme协议");
            actions.add(() -> showAmCommandDialog(uriCommand, false));
        }

        // 创建并显示对话框
        new AlertDialog.Builder(requireContext())
                .setTitle("选择转换模式")
                .setItems(options.toArray(new String[0]), (dialog, which) -> {
                    // 执行用户选择的操作
                    actions.get(which).run();
                })
                .create()
                .show();
    }

    @SuppressLint("SetTextI18n")
    private void showAmCommandDialog(String amCommand, boolean hasError) {
        // 使用 LayoutInflater 加载自定义视图
        LayoutInflater inflater = LayoutInflater.from(requireContext());
        View dialogView = inflater.inflate(R.layout.dialog_custom_view, null);

        // 获取视图中的元素
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) EditText commandTextView = dialogView.findViewById(R.id.command_edit_text);
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) TextView hintTextView = dialogView.findViewById(R.id.hint_text_view);
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) Button copyButton = dialogView.findViewById(R.id.copy_button);
        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) Button suCodeButton = dialogView.findViewById(R.id.su_code_button);

        commandTextView.setText(amCommand); // 确保文本足够长以测试滚动
        commandTextView.setMovementMethod(new ScrollingMovementMethod());

        if (hasError) {
            hintTextView.setVisibility(View.VISIBLE);
            hintTextView.setText("Extras包含自定义类型,已忽略但命令可能无效");
        }

        suCodeButton.setOnClickListener(v -> {
            ShellExecutor.executeSuCommand("su -c '" + commandTextView.getText() + "'");
            Toast.makeText(requireContext(), "已执行", Toast.LENGTH_SHORT).show();
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

        dialog.show();
    }
}