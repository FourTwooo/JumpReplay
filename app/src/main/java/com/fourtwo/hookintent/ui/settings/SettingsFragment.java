package com.fourtwo.hookintent.ui.settings;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.util.Pair;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import yuku.ambilwarna.AmbilWarnaDialog;

public class SettingsFragment extends Fragment {

    private static final String TAG = "SettingsFragment";

    private RecyclerView recyclerView;
    private List<Map<String, Object>> dataList;

    private SettingsAdapter adapter;

    private RecyclerView category_list;
    private List<Pair<String, String>> categoryData = new ArrayList<>();
    private CategoryAdapter categoryAdapter; // 横向列表的适配器

    private List<Map<String, Object>> filteredData;

    private String selectedCategory;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    }

    private void onEdit(Map<String, Object> _currentItem, int _position) {
        showEditDialog(_currentItem, requireContext(), () -> {
            adapter.notifyItemChanged(_position);
        });
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        dataList = prepareData();
        filteredData = new ArrayList<>(dataList); // 初始显示全部数据

        adapter = new SettingsAdapter(filteredData, this::onEdit, this::onDeleteItem); // 传递删除逻辑
        recyclerView.setAdapter(adapter);

        category_list = view.findViewById(R.id.category_list);
        category_list.setLayoutManager(new LinearLayoutManager(
                requireContext(),
                LinearLayoutManager.HORIZONTAL,
                false
        ));

        prepareCategoryData();

        categoryAdapter = new CategoryAdapter(categoryData, this::onCategorySelected, this::onCategoryLongPressed);
        category_list.setAdapter(categoryAdapter);

        // 默认选择 "所有" 标签
        selectedCategory = "所有";
        filterData(selectedCategory);

        return view;
    }

    private void onDeleteItem(int position) {
        // 获取要删除的项
        Map<String, Object> toDelete = filteredData.get(position);

        // 从 dataList 中移除
        dataList.remove(toDelete);

        // 调用 saveData 更新 SharedPreferences
        saveData();

        // 重新筛选数据以刷新 UI
        filterData(selectedCategory);

        Toast.makeText(requireContext(), "记录已删除", Toast.LENGTH_SHORT).show();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void onCategoryLongPressed(String category, int position) {
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("操作标签")
                .setItems(new String[]{"删除", "编辑"}, (dialog, which) -> {
                    if (which == 0) {
                        // 删除标签
                        categoryData.remove(position); // 从列表中移除
                        Map<String, String> COLORS = JsonHandler.jsonToMap(SharedPreferencesUtils.getStr(requireContext(), Constants.COLORS_CONFIG));
                        COLORS.remove(category); // 从 SharedPreferences 中移除
                        SharedPreferencesUtils.putStr(requireContext(), Constants.COLORS_CONFIG, JsonHandler.mapToJson(COLORS));
                        categoryAdapter.notifyDataSetChanged();
                        filterData("所有"); // 重置筛选为 "所有"
                    } else if (which == 1) {
                        // 编辑标签
                        showColorPickerDialog(requireContext(), category);
                    }
                })
                .show();
    }

    private void showColorPickerDialog(Context context, String category) {
        Map<String, String> COLORS = JsonHandler.jsonToMap(SharedPreferencesUtils.getStr(context, Constants.COLORS_CONFIG));
        int color = COLORS.containsKey(category) ? Color.parseColor(COLORS.get(category)) : Color.GRAY;
        AmbilWarnaDialog dialog = new AmbilWarnaDialog(context, color, new AmbilWarnaDialog.OnAmbilWarnaListener() {
            @Override
            public void onOk(AmbilWarnaDialog dialog, int color) {
                String colorHex = String.format("#%06X", (0xFFFFFF & color));
                COLORS.put(category, colorHex);
                SharedPreferencesUtils.putStr(context, Constants.COLORS_CONFIG, JsonHandler.mapToJson(COLORS));
                for (int i = 0; i < categoryData.size(); i++) {
                    if (categoryData.get(i).first.equals(category)) {
                        categoryData.set(i, new Pair<>(category, colorHex));
                        categoryAdapter.notifyItemChanged(i);
                        break;
                    }
                }
            }

            @Override
            public void onCancel(AmbilWarnaDialog dialog) {
            }
        });
        dialog.show();
    }

    private void prepareCategoryData() {
        categoryData.add(new Pair<>("所有", "#808080")); // 默认添加 "所有" 标签

        String colorsJson = SharedPreferencesUtils.getStr(requireContext(), Constants.COLORS_CONFIG);

        try {
            Map<String, String> colorMap = JsonHandler.jsonToMap(colorsJson);
            for (Map.Entry<String, String> entry : colorMap.entrySet()) {
                String key = entry.getKey();
                String value = entry.getValue();
                categoryData.add(new Pair<>(key, value));
            }
        } catch (Exception e) {
            Log.e(TAG, "解析颜色配置失败: " + e.getMessage());
            categoryData.add(new Pair<>("默认标签", "#808080"));
        }
    }

    private void onCategorySelected(String category) {
        selectedCategory = category;
        filterData(category);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void filterData(String category) {
        filteredData.clear();
        if ("所有".equals(category)) {
            filteredData.addAll(dataList); // 引用 dataList 的原始数据
        } else {
            for (Map<String, Object> item : dataList) {
                String itemCategory = (String) item.get("category");
                if (category.equals(itemCategory)) {
                    filteredData.add(item); // 引用原始数据
                }
            }
        }
        Log.d("SettingsFragment", "Filter applied. Category: " + category + ", filteredData size: " + filteredData.size());
        adapter.notifyDataSetChanged();
    }


    private void saveData() {
        List<Map<String, Object>> internalList = new ArrayList<>();
        List<Map<String, Object>> externalList = new ArrayList<>();

        // 根据 internal 属性分类数据
        classifyData(dataList, internalList, externalList);

        // 保存 internal 数据
        saveToPreferences(Constants.INTERNAL_HOOKS_CONFIG, internalList);

        // 保存 external 数据
        saveToPreferences(Constants.EXTERNAL_HOOKS_CONFIG, externalList);

        Log.d(TAG, "数据已保存到 SharedPreferences");
    }


    /**
     * 根据 internal 属性分类数据。
     *
     * @param sourceList   原始数据列表。
     * @param internalList 内部数据列表。
     * @param externalList 外部数据列表。
     */
    private void classifyData(List<Map<String, Object>> sourceList, List<Map<String, Object>> internalList, List<Map<String, Object>> externalList) {
        for (Map<String, Object> item : sourceList) {
            Boolean isInternal = (Boolean) item.get("internal");
            if (isInternal != null && isInternal) {
                internalList.add(item);
            } else {
                externalList.add(item);
            }
        }
    }

    /**
     * 保存数据到 SharedPreferences。
     *
     * @param key      SharedPreferences 的键。
     * @param dataList 要保存的数据列表。
     */
    private void saveToPreferences(String key, List<Map<String, Object>> dataList) {
        String jsonData = dataList.isEmpty() ? null : JsonHandler.serializeHookedRecords(dataList);
        SharedPreferencesUtils.putStr(requireContext(), key, jsonData);
        Log.d(TAG, key + " saved: " + jsonData);
    }

    public int findIndexByUuid(List<Map<String, Object>> dataList, String targetUuid) {
        for (int i = 0; i < dataList.size(); i++) {
            Map<String, Object> item = dataList.get(i);
            if (item.containsKey("_uuid") && targetUuid.equals(item.get("_uuid"))) {
                return i; // 找到时返回索引
            }
        }
        return -1; // 未找到时返回 -1
    }


    /**
     * 向 dataList 中添加或更新一条记录。
     *
     * @param newItem 包含新增或更新的数据项。
     */
    @SuppressLint("NotifyDataSetChanged")
    private void addOrUpdateItem(Map<String, Object> newItem) {
        // 获取新项的关键字段

        String newPackageName = (String) newItem.get("packageName");
        String newClassName = (String) newItem.get("className");
        String newMethodName = (String) newItem.get("methodName");
        String newCategory = (String) newItem.get("category");

        if (newClassName == null || newMethodName == null || newCategory == null || newPackageName == null ||
                newClassName.isEmpty() || newMethodName.isEmpty() || newCategory.isEmpty() || newPackageName.isEmpty()) {
            Toast.makeText(requireContext(), "className、methodName、packageName、category 不能为空", Toast.LENGTH_SHORT).show();
            return;
        }

        int index = findIndexByUuid(dataList, (String) newItem.get("_uuid"));

        if (index != -1) {
            // 更新现有记录
            dataList.set(index, newItem);
            Log.d("SettingsFragment", "Item updated at index: " + index + ", new state: " + newItem);
            Toast.makeText(requireContext(), "记录已更新", Toast.LENGTH_SHORT).show();
        } else {
            // 添加新记录
            dataList.add(newItem);
            Log.d("SettingsFragment", "Item added: " + newItem);
            Toast.makeText(requireContext(), "新记录已添加", Toast.LENGTH_SHORT).show();
        }

        // 保存数据到 SharedPreferences
        saveData();

        // 重新筛选并通知适配器
        filterData(selectedCategory);
    }

    private List<Map<String, Object>> prepareData() {
        List<Map<String, Object>> list = new ArrayList<>();

        // 加载内部数据
        loadFromPreferences(Constants.INTERNAL_HOOKS_CONFIG, true, list);

        // 加载外部数据
        loadFromPreferences(Constants.EXTERNAL_HOOKS_CONFIG, false, list);

        return list;
    }

    /**
     * 从 SharedPreferences 加载数据。
     *
     * @param key        SharedPreferences 的键。
     * @param isInternal 是否为内部数据。
     * @param targetList 要添加到的数据列表。
     */
    private void loadFromPreferences(String key, boolean isInternal, List<Map<String, Object>> targetList) {
        String config = SharedPreferencesUtils.getStr(requireContext(), key);
        if (config != null) {
            List<Map<String, Object>> records = JsonHandler.deserializeHookedRecords(config);
            for (Map<String, Object> item : records) {
                if (!item.containsKey("_uuid")) {
                    item.put("_uuid", UUID.randomUUID().toString());
                }
                item.put("internal", isInternal);
                targetList.add(item);
            }
        }
    }

    @SuppressLint({"ClickableViewAccessibility", "DefaultLocale"})
    private void showEditDialog(@Nullable Map<String, Object> existingData, @NonNull Context context, @NonNull Runnable onSaveCallback) {
        // 创建弹窗并设置布局
        View dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_custom_hook, null);
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        builder.setView(dialogView);

        // 初始化弹窗内的控件
        TextInputEditText inputPackageName = dialogView.findViewById(R.id.input_package_name);
        TextInputEditText inputMethodName = dialogView.findViewById(R.id.input_method_name);
        TextInputEditText inputClassName = dialogView.findViewById(R.id.input_class_name);
        TextInputEditText inputCategory = dialogView.findViewById(R.id.input_category);
        EditText inputTitleArgsIndex = dialogView.findViewById(R.id.input_title_args_index);
        EditText inputDataArgsIndex = dialogView.findViewById(R.id.input_data_args_index);
        CheckBox checkboxTitleArgs = dialogView.findViewById(R.id.checkbox_title_args);
        CheckBox checkboxDataArgs = dialogView.findViewById(R.id.checkbox_data_args);
        Button buttonConfirm = dialogView.findViewById(R.id.button_confirm);
        Button buttonPickColor = dialogView.findViewById(R.id.button_pick_color);

        @SuppressLint({"MissingInflatedId", "LocalSuppress"}) TextInputLayout idTILQuery = dialogView.findViewById(R.id.idTILQuery);
        idTILQuery.setEndIconOnClickListener(view -> {
            Log.d(TAG, "showEditDialog: 图标被点击");

            // 获取包管理器
            PackageManager packageManager = requireContext().getPackageManager();

            // 获取所有已安装的应用程序
            List<ApplicationInfo> installedApplications = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);

            List<Map<String, Object>> appInfoHash = new ArrayList<>();

            for (ApplicationInfo app : installedApplications) {
                Map<String, Object> appInfo = new HashMap<>();
                try {
                    appInfo.put("isSystem", (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                    appInfo.put("appName", packageManager.getApplicationLabel(app));
                    appInfo.put("packageName", app.packageName);

                    PackageInfo packageInfo = packageManager.getPackageInfo(app.packageName, 0);
                    appInfo.put("version", String.format("%s(%s)", packageInfo.versionName, packageInfo.versionCode));

                    appInfoHash.add(appInfo);
                    Log.d(TAG, appInfo.toString());
                } catch (PackageManager.NameNotFoundException e) {
                    throw new RuntimeException(e);
                }
            }

            // 创建弹窗
            androidx.appcompat.app.AlertDialog.Builder builderPackages = new androidx.appcompat.app.AlertDialog.Builder(requireContext());

            // 加载弹窗布局
            @SuppressLint("InflateParams") View dialogPackagesView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_packages, null);
            @SuppressLint({"MissingInflatedId", "LocalSuppress"}) RecyclerView recyclerView = dialogPackagesView.findViewById(R.id.packages_list);
            recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

            // 获取 Spinner 和 TextView
            Spinner dropdownSpinner = dialogPackagesView.findViewById(R.id.dropdown_spinner);
            TextView all_package = dialogPackagesView.findViewById(R.id.all_package);

            // 创建 RecyclerView 的 Adapter
            List<Map<String, Object>> filteredList = new ArrayList<>(appInfoHash); // 初始为所有应用
            androidx.appcompat.app.AlertDialog dialog;

            // 更新标题
            all_package.setText(String.format("共%d个应用", filteredList.size()));

            // 设置弹窗布局
            builderPackages.setView(dialogPackagesView);
            dialog = builderPackages.create();

            PackageAdapter adapter = new PackageAdapter(filteredList, packageName -> {
                inputPackageName.setText(packageName);
                Log.d(TAG, "Clicked packageName: " + packageName);
                dialog.dismiss();
            });
            recyclerView.setAdapter(adapter);


            Map<String, Object> allPackage =  new HashMap<>();
            allPackage.put("appName", "所有应用");
            allPackage.put("packageName", "ALL");
            allPackage.put("version", "");

            // 设置 Spinner 的监听器
            dropdownSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                @SuppressLint("NotifyDataSetChanged")
                @Override
                public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                    filteredList.clear();

                    switch (position) {
                        case 0: // 所有应用
                            filteredList.add(allPackage);
                            filteredList.addAll(appInfoHash);
                            break;
                        case 1: // 用户应用
                            filteredList.add(allPackage);
                            for (Map<String, Object> app : appInfoHash) {
                                if (!(Boolean) app.get("isSystem")) {
                                    filteredList.add(app);
                                }
                            }
                            break;
                        case 2: // 系统应用
                            filteredList.add(allPackage);
                            for (Map<String, Object> app : appInfoHash) {
                                if ((Boolean) app.get("isSystem")) {
                                    filteredList.add(app);
                                }
                            }
                            break;
                    }
                    // 更新 RecyclerView 和标题
                    adapter.notifyDataSetChanged();
                    all_package.setText(String.format("共%d个应用", filteredList.size() - 1));
                }

                @Override
                public void onNothingSelected(AdapterView<?> parent) {
                    // 默认不做任何操作
                }
            });

            // 设置弹窗背景
            if (dialog.getWindow() != null) {
                dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_rounded_background);
            }

            // 显示弹窗
            dialog.show();
            dialog.getWindow().setLayout((int) (getResources().getDisplayMetrics().widthPixels * 0.9), (int) (getResources().getDisplayMetrics().heightPixels * 0.9));

        });
        // 默认标签颜色
        final int[][] newColor = {{Color.GRAY}};
        final boolean[] isOnOk = {false};
        Map<String, String> COLORS = JsonHandler.jsonToMap(SharedPreferencesUtils.getStr(context, Constants.COLORS_CONFIG));

        // 如果是编辑模式，填充现有数据
        if (existingData != null) {
            inputPackageName.setText((String) existingData.get("packageName"));
            inputMethodName.setText((String) existingData.get("methodName"));
            inputClassName.setText((String) existingData.get("className"));
            inputCategory.setText((String) existingData.get("category"));

            // 设置 Title Args 状态
            if (existingData.containsKey("title")) {
                checkboxTitleArgs.setChecked(true);
                inputTitleArgsIndex.setText(String.valueOf(existingData.get("title")));
            } else {
                checkboxTitleArgs.setChecked(false);
                inputTitleArgsIndex.setText(""); // 清空输入框
            }

            // 设置 Data Args 状态
            if (existingData.containsKey("data")) {
                checkboxDataArgs.setChecked(true);
                inputDataArgsIndex.setText(String.valueOf(existingData.get("data")));
            } else {
                checkboxDataArgs.setChecked(false);
                inputDataArgsIndex.setText(""); // 清空输入框
            }
        }

        // 创建弹窗
        AlertDialog dialog = builder.create();

        // 设置弹窗背景为圆角背景
        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_rounded_background);
        }

        buttonPickColor.setOnClickListener(v -> {

            String category = Objects.requireNonNull(inputCategory.getText()).toString();
            Log.d(TAG, "showEditDialog: " + category);
            if (category.isEmpty()) {
                Toast.makeText(requireContext(), "category 未填写", Toast.LENGTH_SHORT).show();
                return;
            }
            int colorConfig = newColor[0][0];
            if (COLORS.containsKey(category)) {
                colorConfig = Color.parseColor(COLORS.get(category));
            }
            AmbilWarnaDialog _dialog = new AmbilWarnaDialog(v.getContext(), colorConfig, new AmbilWarnaDialog.OnAmbilWarnaListener() {
                @Override
                public void onOk(AmbilWarnaDialog dialog, int color) {
                    newColor[0][0] = color;
                    isOnOk[0] = true;
                }

                @Override
                public void onCancel(AmbilWarnaDialog dialog) {
                    // cancel was selected by the user
                }
            });
            _dialog.show();
        });

        buttonConfirm.setOnClickListener(v -> {
            Map<String, Object> newData = new HashMap<>();
            newData.put("packageName", Objects.requireNonNull(inputPackageName.getText()).toString());
            newData.put("methodName", Objects.requireNonNull(inputMethodName.getText()).toString());
            newData.put("className", Objects.requireNonNull(inputClassName.getText()).toString());
            newData.put("category", Objects.requireNonNull(inputCategory.getText()).toString());

            // 颜色储存
            String category = Objects.requireNonNull(inputCategory.getText()).toString();
            String colorHex;

            if (COLORS.containsKey(category)) {
                // 如果标签已存在
                colorHex = isOnOk[0]
                        ? String.format("#%06X", (0xFFFFFF & newColor[0][0])) // 用户确认更新
                        : COLORS.get(category); // 使用已有颜色
            } else {
                // 如果标签不存在，直接使用新颜色
                colorHex = String.format("#%06X", (0xFFFFFF & newColor[0][0]));
                COLORS.put(category, colorHex);
                SharedPreferencesUtils.putStr(v.getContext(), Constants.COLORS_CONFIG, JsonHandler.mapToJson(COLORS));
            }

            // 如果用户确认更新或新增颜色，保存到 SharedPreferences
            if (isOnOk[0] || !COLORS.containsKey(category)) {
                COLORS.put(category, colorHex);
                SharedPreferencesUtils.putStr(v.getContext(), Constants.COLORS_CONFIG, JsonHandler.mapToJson(COLORS));
            }


            // 更新 CategoryAdapter 的标签（避免重复）
            boolean categoryUpdated = false;
            for (int i = 0; i < categoryData.size(); i++) {
                Pair<String, String> pair = categoryData.get(i);
                if (pair.first.equals(category)) {
                    // 如果标签已存在，更新颜色
                    categoryData.set(i, new Pair<>(category, colorHex));
                    categoryAdapter.notifyItemChanged(i);
                    categoryUpdated = true;
                    break;
                }
            }
            if (!categoryUpdated) {
                // 如果标签不存在，添加新的标签
                categoryData.add(new Pair<>(category, colorHex));
                categoryAdapter.notifyItemInserted(categoryData.size() - 1);
            }

            // 保留原始的 internal 值（如果存在）
            if (existingData != null) {
                if (existingData.containsKey("internal")) {
                    newData.put("internal", existingData.get("internal"));
                }
                if (existingData.containsKey("open")) {
                    newData.put("open", existingData.get("open"));
                }
                if (existingData.containsKey("_uuid")) {
                    newData.put("_uuid", existingData.get("_uuid"));
                }
            } else {
                // 默认值
                newData.put("internal", false);
                newData.put("open", true);
                newData.put("_uuid", UUID.randomUUID().toString());
            }

            // 根据用户选择处理 Title Args
            if (checkboxTitleArgs.isChecked()) {
                // 如果勾选了 Args，使用输入框的值作为 title
                String titleArgsValue = inputTitleArgsIndex.getText().toString();
                newData.put("title", titleArgsValue.isEmpty() ? "0" : titleArgsValue);
            } else {
                // 如果未勾选 Args，则移除 title
                newData.remove("title");
            }

            // 根据用户选择处理 Data Args
            if (checkboxDataArgs.isChecked()) {
                // 如果勾选了 Args，使用输入框的值作为 data
                String dataArgsValue = inputDataArgsIndex.getText().toString();
                newData.put("data", dataArgsValue.isEmpty() ? "0" : dataArgsValue);
            } else {
                // 如果未勾选 Args，则移除 data
                newData.remove("data");
            }

            // 调用保存逻辑
            addOrUpdateItem(newData);
            dialog.dismiss();

            // 执行回调（如刷新 UI）
            onSaveCallback.run();
        });


        dialog.show();
    }


    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().addMenuProvider(new MenuProvider() {

            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menu.clear(); // 清除现有菜单项，避免重复
                menuInflater.inflate(R.menu.settings_drawer, menu); // 加载菜单
            }

            @SuppressLint("NotifyDataSetChanged")
            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.add_text) {
                    Log.d(TAG, "onMenuItemSelected: ADD TEXT");

                    // 调用弹窗，新增数据
                    showEditDialog(null, requireContext(), () -> {
                        adapter.notifyDataSetChanged();
                    });

                    return true;
                }
                return false;
            }

        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }


    @Override
    public void onPause() {
        super.onPause();
        saveData(); // 离开页面时保存数据
    }

}
