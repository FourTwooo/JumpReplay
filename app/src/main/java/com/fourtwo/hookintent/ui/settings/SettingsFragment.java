package com.fourtwo.hookintent.ui.settings;

import android.annotation.SuppressLint;
import androidx.appcompat.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ProgressBar;
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
import com.fourtwo.hookintent.utils.AppDialogs;
import com.fourtwo.hookintent.utils.LoadingOverlay;
import com.fourtwo.hookintent.utils.NetworkClient;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import yuku.ambilwarna.AmbilWarnaDialog;

public class SettingsFragment extends Fragment {

    private static final String TAG = "SettingsFragment";
    private static final int MAX_CLOUD_URL_HISTORY = 20;

    private RecyclerView recyclerView;
    private List<Map<String, Object>> dataList;

    private SettingsAdapter adapter;

    private RecyclerView category_list;
    private List<Pair<String, String>> categoryData = new ArrayList<>();
    private CategoryAdapter categoryAdapter;

    private List<Map<String, Object>> filteredData;

    private String selectedCategory;
    private String ALL_STRING;

    private final NetworkClient networkClient = new NetworkClient();
    private final ExecutorService applicationsExecutor = Executors.newSingleThreadExecutor();
    private final List<Dialog> activeDialogs = new ArrayList<>();
    private LoadingOverlay applicationsLoading;
    private int viewGeneration;
    private int cloudGeneration;

    private boolean currentView(int generation) {
        return generation == viewGeneration && isAdded() && getView() != null;
    }
    private <T extends Dialog> T track(T dialog) {
        activeDialogs.add(dialog);
        dialog.setOnDismissListener(ignored -> activeDialogs.remove(dialog));
        return dialog;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ALL_STRING = getString(R.string.all);
    }

    private void onEdit(Map<String, Object> currentItem, int position) {
        showEditDialog(currentItem, null, requireContext(), () -> {});
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_settings, container, false);

        recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        dataList = prepareData();
        filteredData = new ArrayList<>(dataList);

        adapter = new SettingsAdapter(filteredData, this::onEdit, this::onDeleteItem);
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

        selectedCategory = ALL_STRING;
        filterData(selectedCategory);

        return view;
    }

    private void onDeleteItem(int position) {
        Map<String, Object> toDelete = filteredData.get(position);
        dataList.remove(toDelete);
        saveData();
        filterData(selectedCategory);
        Toast.makeText(requireContext(), "记录已删除", Toast.LENGTH_SHORT).show();
    }

    @SuppressLint("NotifyDataSetChanged")
    private void onCategoryLongPressed(String category, int position) {
        AlertDialog.Builder builder = AppDialogs.builder(requireContext());
        builder.setTitle("操作标签")
                .setItems(new String[]{"删除", "编辑"}, (dialog, which) -> {
                    if (which == 0) {
                        if (isBuiltInCategory(category)) {
                            Toast.makeText(requireContext(), "内建标签不能删除", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        if (isCategoryReferenced(category)) {
                            Toast.makeText(requireContext(), "该标签仍被规则使用，请先修改或删除对应规则", Toast.LENGTH_SHORT).show();
                            return;
                        }

                        categoryData.remove(position);

                        Map<String, String> COLORS = JsonHandler.jsonToMap(
                                SharedPreferencesUtils.getStr(requireContext(), Constants.COLORS_CONFIG)
                        );
                        COLORS.remove(category);
                        SharedPreferencesUtils.putStr(
                                requireContext(),
                                Constants.COLORS_CONFIG,
                                JsonHandler.mapToJson(COLORS)
                        );

                        removeFilterGroup(category);

                        selectedCategory = ALL_STRING;
                        categoryAdapter.notifyDataSetChanged();
                        filterData(ALL_STRING);
                    } else if (which == 1) {
                        showColorPickerDialog(requireContext(), category);
                    }
                })
                .show();
    }

    private boolean isCategoryReferenced(String category) {
        for (Map<String, Object> item : dataList) {
            String itemCategory = (String) item.get("category");
            if (category.equals(itemCategory)) {
                return true;
            }
        }
        return false;
    }

    private boolean isBuiltInCategory(String category) {
        return "Intent".equalsIgnoreCase(category)
                || "Scheme".equalsIgnoreCase(category)
                || "intent".equalsIgnoreCase(category)
                || "scheme".equalsIgnoreCase(category);
    }

    private String normalizeFilterGroup(String category) {
        if (category == null) {
            return null;
        }

        String trimmed = category.trim();
        if (trimmed.isEmpty()) {
            return null;
        }

        if ("Intent".equalsIgnoreCase(trimmed)) {
            return "intent";
        }

        if ("Scheme".equalsIgnoreCase(trimmed)) {
            return "scheme";
        }

        return trimmed;
    }

    private void removeFilterGroup(String category) {
        String normalized = normalizeFilterGroup(category);
        if (normalized == null || isBuiltInCategory(normalized)) {
            return;
        }

        JsonHandler jsonHandler = new JsonHandler();
        Map<String, Object> filterJson = jsonHandler.readJsonFromFile(requireContext());
        if (filterJson == null) {
            return;
        }

        if (filterJson.remove(normalized) != null) {
            try { jsonHandler.writeJsonToFile(requireContext(), filterJson); }
            catch (IllegalStateException e) { Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show(); }
        }
    }

    private void showColorPickerDialog(Context context, String category) {
        Map<String, String> COLORS = JsonHandler.jsonToMap(SharedPreferencesUtils.getStr(context, Constants.COLORS_CONFIG));
        int color = COLORS.containsKey(category) ? Color.parseColor(COLORS.get(category)) : Color.GRAY;
        AmbilWarnaDialog dialog = new AmbilWarnaDialog(AppDialogs.context(context), color, new AmbilWarnaDialog.OnAmbilWarnaListener() {
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
        AppDialogs.styleExternal(dialog.getDialog());
    }

    private void prepareCategoryData() {
        categoryData.clear();
        categoryData.add(new Pair<>(ALL_STRING, "#808080"));

        String colorsJson = SharedPreferencesUtils.getStr(requireContext(), Constants.COLORS_CONFIG);

        try {
            Map<String, String> colorMap = JsonHandler.jsonToMap(colorsJson);
            for (Map.Entry<String, String> entry : colorMap.entrySet()) {
                categoryData.add(new Pair<>(entry.getKey(), entry.getValue()));
            }
        } catch (Exception e) {
            Log.e(TAG, "解析颜色配置失败: " + e.getMessage());
        }
    }

    private void onCategorySelected(String category) {
        selectedCategory = category;
        filterData(category);
    }

    @SuppressLint("NotifyDataSetChanged")
    private void filterData(String category) {
        filteredData.clear();
        if (ALL_STRING.equals(category)) {
            filteredData.addAll(dataList);
        } else {
            for (Map<String, Object> item : dataList) {
                String itemCategory = (String) item.get("category");
                if (category.equals(itemCategory)) {
                    filteredData.add(item);
                }
            }
        }
        Log.d(TAG, "Filter applied. Category: " + category + ", filteredData size: " + filteredData.size());
        adapter.notifyDataSetChanged();
    }

    private void saveData() {
        List<Map<String, Object>> internalList = new ArrayList<>();
        List<Map<String, Object>> externalList = new ArrayList<>();

        classifyData(dataList, internalList, externalList);

        saveToPreferences(Constants.INTERNAL_HOOKS_CONFIG, internalList);
        saveToPreferences(Constants.EXTERNAL_HOOKS_CONFIG, externalList);

        Log.d(TAG, "数据已保存到 SharedPreferences");
    }

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

    private void saveToPreferences(String key, List<Map<String, Object>> dataList) {
        String jsonData = dataList.isEmpty() ? null : JsonHandler.serializeHookedRecords(dataList);
        SharedPreferencesUtils.putStr(requireContext(), key, jsonData);
        Log.d(TAG, key + " saved: " + jsonData);
    }

    public int findIndexByUuid(List<Map<String, Object>> dataList, String targetUuid) {
        for (int i = 0; i < dataList.size(); i++) {
            Map<String, Object> item = dataList.get(i);
            if (item.containsKey("_uuid") && targetUuid != null && targetUuid.equals(item.get("_uuid"))) {
                return i;
            }
        }
        return -1;
    }

    @SuppressLint("NotifyDataSetChanged")
    private void addOrUpdateItem(Map<String, Object> newItem) {
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
            dataList.set(index, newItem);
            Log.d(TAG, "Item updated at index: " + index + ", new state: " + newItem);
            Toast.makeText(requireContext(), "记录已更新", Toast.LENGTH_SHORT).show();
        } else {
            dataList.add(newItem);
            Log.d(TAG, "Item added: " + newItem);
            Toast.makeText(requireContext(), "新记录已添加", Toast.LENGTH_SHORT).show();
        }

        saveData();
        filterData(selectedCategory);
    }

    private List<Map<String, Object>> prepareData() {
        List<Map<String, Object>> list = new ArrayList<>();

        loadFromPreferences(Constants.INTERNAL_HOOKS_CONFIG, true, list);
        loadFromPreferences(Constants.EXTERNAL_HOOKS_CONFIG, false, list);

        return list;
    }

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
    private void showEditDialog(@Nullable Map<String, Object> existingData,
                                @Nullable Map<String, Object> presetData,
                                @NonNull Context context,
                                @NonNull Runnable onSaveCallback) {

        AlertDialog.Builder builder = AppDialogs.builder(context);
        View dialogView = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_custom_hook, null);
        builder.setView(dialogView);

        TextInputEditText inputPackageName = dialogView.findViewById(R.id.input_package_name);
        TextInputEditText inputMethodName = dialogView.findViewById(R.id.input_method_name);
        TextInputEditText inputClassName = dialogView.findViewById(R.id.input_class_name);
        TextInputEditText inputCategory = dialogView.findViewById(R.id.input_category);
        EditText inputTitleArgsIndex = dialogView.findViewById(R.id.input_title_args_index);
        EditText inputDataArgsIndex = dialogView.findViewById(R.id.input_data_args_index);
        CheckBox checkboxTitleArgs = dialogView.findViewById(R.id.checkbox_title_args);
        CheckBox checkboxDataArgs = dialogView.findViewById(R.id.checkbox_data_args);
        checkboxTitleArgs.setOnCheckedChangeListener((button, checked) -> inputTitleArgsIndex.setEnabled(checked));
        checkboxDataArgs.setOnCheckedChangeListener((button, checked) -> inputDataArgsIndex.setEnabled(checked));
        inputTitleArgsIndex.setEnabled(checkboxTitleArgs.isChecked());
        inputDataArgsIndex.setEnabled(checkboxDataArgs.isChecked());
        Button buttonConfirm = dialogView.findViewById(R.id.button_confirm);
        Button buttonPickColor = dialogView.findViewById(R.id.button_pick_color);

        TextInputLayout idTILQuery = dialogView.findViewById(R.id.idTILQuery);
        idTILQuery.setEndIconOnClickListener(view -> {
            final int generation = viewGeneration;
            Context appContext = requireContext().getApplicationContext();
            LoadingOverlay loadingOverlay = new LoadingOverlay(requireContext());
            applicationsLoading = loadingOverlay;
            loadingOverlay.setLoadingText("正在加载应用列表...");
            loadingOverlay.show();

            applicationsExecutor.execute(() -> {
                PackageManager packageManager = appContext.getPackageManager();
                List<ApplicationInfo> installedApplications = packageManager.getInstalledApplications(PackageManager.GET_META_DATA);

                List<Map<String, Object>> appInfoHash = new ArrayList<>();

                for (ApplicationInfo app : installedApplications) {
                    if (Thread.currentThread().isInterrupted()) return;
                    Map<String, Object> appInfo = new HashMap<>();
                    try {
                        appInfo.put("isSystem", (app.flags & ApplicationInfo.FLAG_SYSTEM) != 0);
                        appInfo.put("appName", packageManager.getApplicationLabel(app).toString());
                        appInfo.put("packageName", app.packageName);

                        PackageInfo packageInfo = packageManager.getPackageInfo(app.packageName, 0);
                        appInfo.put("version", String.format("%s(%s)", packageInfo.versionName, packageInfo.versionCode));

                        appInfoHash.add(appInfo);
                    } catch (PackageManager.NameNotFoundException e) {
                        Log.e(TAG, "应用信息加载失败", e);
                    }
                }

                new Handler(Looper.getMainLooper()).post(() -> {
                    if (!currentView(generation)) { loadingOverlay.hide(); return; }
                    AlertDialog.Builder builderPackages = AppDialogs.builder(requireContext());

                    View dialogPackagesView = LayoutInflater.from(builderPackages.getContext()).inflate(R.layout.dialog_custom_packages, null);
                    RecyclerView recyclerView = dialogPackagesView.findViewById(R.id.packages_list);
                    recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

                    Spinner dropdownSpinner = dialogPackagesView.findViewById(R.id.dropdown_spinner);
                    TextView all_package = dialogPackagesView.findViewById(R.id.all_package);

                    List<Map<String, Object>> filteredList = new ArrayList<>(appInfoHash);
                    AlertDialog dialog;

                    all_package.setText(String.format("共%d个应用", filteredList.size()));

                    builderPackages.setView(dialogPackagesView);
                    dialog = track(builderPackages.create());

                    PackageAdapter adapter = new PackageAdapter(filteredList, packageName -> {
                        inputPackageName.setText(packageName);
                        dialog.dismiss();
                    });
                    recyclerView.setAdapter(adapter);

                    Map<String, Object> allPackage = new HashMap<>();
                    allPackage.put("appName", "所有应用");
                    allPackage.put("packageName", "ALL");
                    allPackage.put("version", "");

                    dropdownSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
                        @Override
                        public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                            filteredList.clear();

                            switch (position) {
                                case 0:
                                    filteredList.add(allPackage);
                                    filteredList.addAll(appInfoHash);
                                    break;
                                case 1:
                                    filteredList.add(allPackage);
                                    for (Map<String, Object> app : appInfoHash) {
                                        if (!(Boolean) app.get("isSystem")) {
                                            filteredList.add(app);
                                        }
                                    }
                                    break;
                                case 2:
                                    filteredList.add(allPackage);
                                    for (Map<String, Object> app : appInfoHash) {
                                        if ((Boolean) app.get("isSystem")) {
                                            filteredList.add(app);
                                        }
                                    }
                                    break;
                            }

                            adapter.notifyDataSetChanged();
                            all_package.setText(String.format("共%d个应用", filteredList.size() - 1));
                        }

                        @Override
                        public void onNothingSelected(AdapterView<?> parent) {
                        }
                    });

                    loadingOverlay.hide();

                    dialog.show();
                    AppDialogs.sizeForList(dialog);
                });
            });
        });

        final int[][] newColor = {{Color.GRAY}};
        final boolean[] isOnOk = {false};
        Map<String, String> COLORS = JsonHandler.jsonToMap(SharedPreferencesUtils.getStr(context, Constants.COLORS_CONFIG));

        Map<String, Object> initialData = existingData != null ? existingData : presetData;
        if (initialData != null) {
            inputPackageName.setText((String) initialData.get("packageName"));
            inputMethodName.setText((String) initialData.get("methodName"));
            inputClassName.setText((String) initialData.get("className"));
            inputCategory.setText((String) initialData.get("category"));

            if (initialData.containsKey("title")) {
                checkboxTitleArgs.setChecked(true);
                inputTitleArgsIndex.setText(String.valueOf(initialData.get("title")));
            } else {
                checkboxTitleArgs.setChecked(false);
                inputTitleArgsIndex.setText("");
            }

            if (initialData.containsKey("data")) {
                checkboxDataArgs.setChecked(true);
                inputDataArgsIndex.setText(String.valueOf(initialData.get("data")));
            } else {
                checkboxDataArgs.setChecked(false);
                inputDataArgsIndex.setText("");
            }
        }

        AlertDialog dialog = track(builder.create());

        buttonPickColor.setOnClickListener(v -> {
            String category = Objects.requireNonNull(inputCategory.getText()).toString();
            if (category.isEmpty()) {
                Toast.makeText(requireContext(), "category 未填写", Toast.LENGTH_SHORT).show();
                return;
            }

            int colorConfig = newColor[0][0];
            if (COLORS.containsKey(category)) {
                colorConfig = Color.parseColor(COLORS.get(category));
            }

            AmbilWarnaDialog colorDialog = new AmbilWarnaDialog(AppDialogs.context(v.getContext()), colorConfig, new AmbilWarnaDialog.OnAmbilWarnaListener() {
                @Override
                public void onOk(AmbilWarnaDialog dialog, int color) {
                    newColor[0][0] = color;
                    isOnOk[0] = true;
                }

                @Override
                public void onCancel(AmbilWarnaDialog dialog) {
                }
            });
            colorDialog.show();
            AppDialogs.styleExternal(colorDialog.getDialog());
        });

        buttonConfirm.setOnClickListener(v -> {
            String packageValue = Objects.requireNonNull(inputPackageName.getText()).toString().trim();
            String classValue = Objects.requireNonNull(inputClassName.getText()).toString().trim();
            String methodValue = Objects.requireNonNull(inputMethodName.getText()).toString().trim();
            String categoryValue = Objects.requireNonNull(inputCategory.getText()).toString().trim();
            if (packageValue.isEmpty() || classValue.isEmpty() || methodValue.isEmpty() || categoryValue.isEmpty()) {
                Toast.makeText(requireContext(), "className、methodName、packageName、category不能为空", Toast.LENGTH_LONG).show();
                return;
            }
            for (EditText field : new EditText[]{inputTitleArgsIndex, inputDataArgsIndex}) {
                if ((field == inputTitleArgsIndex ? checkboxTitleArgs : checkboxDataArgs).isChecked()) {
                    String index = field.getText().toString().trim();
                    try { if (!index.isEmpty() && Integer.parseInt(index) < 0) throw new NumberFormatException(); }
                    catch (NumberFormatException e) { field.setError("请输入非负整数参数下标"); return; }
                }
            }
            Map<String, Object> newData = new HashMap<>();
            newData.put("packageName", packageValue);
            newData.put("methodName", methodValue);
            newData.put("className", classValue);
            newData.put("category", categoryValue);

            Map<String, Object> metadataSource = existingData != null ? existingData : presetData;
            if (metadataSource != null) for (String key : new String[]{"name", "remark"}) {
                if (metadataSource.containsKey(key)) newData.put(key, metadataSource.get(key));
            }

            String category = categoryValue;
            String colorHex;

            if (COLORS.containsKey(category)) {
                colorHex = isOnOk[0]
                        ? String.format("#%06X", (0xFFFFFF & newColor[0][0]))
                        : COLORS.get(category);
            } else {
                colorHex = String.format("#%06X", (0xFFFFFF & newColor[0][0]));
                COLORS.put(category, colorHex);
                SharedPreferencesUtils.putStr(v.getContext(), Constants.COLORS_CONFIG, JsonHandler.mapToJson(COLORS));
            }

            if (isOnOk[0] || !COLORS.containsKey(category)) {
                COLORS.put(category, colorHex);
                SharedPreferencesUtils.putStr(v.getContext(), Constants.COLORS_CONFIG, JsonHandler.mapToJson(COLORS));
            }

            boolean categoryUpdated = false;
            for (int i = 0; i < categoryData.size(); i++) {
                Pair<String, String> pair = categoryData.get(i);
                if (pair.first.equals(category)) {
                    categoryData.set(i, new Pair<>(category, colorHex));
                    categoryAdapter.notifyItemChanged(i);
                    categoryUpdated = true;
                    break;
                }
            }
            if (!categoryUpdated) {
                categoryData.add(new Pair<>(category, colorHex));
                categoryAdapter.notifyItemInserted(categoryData.size() - 1);
            }

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
                newData.put("internal", false);
                newData.put("open", true);
                newData.put("_uuid", UUID.randomUUID().toString());
            }

            if (checkboxTitleArgs.isChecked()) {
                String titleArgsValue = inputTitleArgsIndex.getText().toString().trim();
                newData.put("title", titleArgsValue.isEmpty() ? "0" : titleArgsValue);
            } else {
                newData.remove("title");
            }

            if (checkboxDataArgs.isChecked()) {
                String dataArgsValue = inputDataArgsIndex.getText().toString().trim();
                newData.put("data", dataArgsValue.isEmpty() ? "0" : dataArgsValue);
            } else {
                newData.remove("data");
            }

            addOrUpdateItem(newData);
            dialog.dismiss();
            onSaveCallback.run();
        });

        dialog.show();
    }

    private void showAddEntryModeDialog() {
        AppDialogs.builder(requireContext())
                .setTitle("添加方式")
                .setItems(new String[]{"手动添加", "云端导入"}, (dialog, which) -> {
                    if (which == 0) {
                        showEditDialog(null, null, requireContext(), () -> adapter.notifyDataSetChanged());
                    } else if (which == 1) {
                        showCloudConfigDialog();
                    }
                })
                .show();
    }

    private void showCloudConfigDialog() {
        AlertDialog.Builder builder = AppDialogs.builder(requireContext());
        View dialogView = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_cloud_config, null);

        AutoCompleteTextView inputUrl = dialogView.findViewById(R.id.input_cloud_url);
        Button requestButton = dialogView.findViewById(R.id.button_request_cloud);
        ProgressBar progressBar = dialogView.findViewById(R.id.cloud_progress_bar);
        TextView emptyView = dialogView.findViewById(R.id.cloud_empty_view);
        RecyclerView recyclerView = dialogView.findViewById(R.id.cloud_recycler_view);

        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        builder.setView(dialogView);
        AlertDialog dialog = track(builder.create());

        ArrayAdapter<String> urlHistoryAdapter = new ArrayAdapter<>(
                requireContext(),
                android.R.layout.simple_dropdown_item_1line,
                new ArrayList<>()
        );
        inputUrl.setAdapter(urlHistoryAdapter);
        inputUrl.setThreshold(0);
        refreshCloudUrlHistoryAdapter(urlHistoryAdapter);

        inputUrl.setOnClickListener(v -> inputUrl.showDropDown());
        inputUrl.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) {
                inputUrl.showDropDown();
            }
        });

        inputUrl.setOnLongClickListener(v -> {
            String currentUrl = trimToEmpty(inputUrl.getText().toString());
            if (currentUrl.isEmpty()) {
                Toast.makeText(requireContext(), "当前没有可删除的地址", Toast.LENGTH_SHORT).show();
                return true;
            }

            showDeleteCloudUrlDialog(currentUrl, inputUrl, urlHistoryAdapter);
            return true;
        });

        CloudConfigAdapter cloudAdapter = new CloudConfigAdapter(new ArrayList<>(), templateData -> {
            dialog.dismiss();
            int generation = viewGeneration;
            new Handler(Looper.getMainLooper()).post(() -> {
                if (currentView(generation)) showEditDialog(null, templateData, requireContext(), () -> adapter.notifyDataSetChanged());
            });
        });
        recyclerView.setAdapter(cloudAdapter);

        String initUrl = getInitialCloudConfigUrl();
        if (!initUrl.isEmpty()) {
            inputUrl.setText(initUrl, false);
        }

        requestButton.setOnClickListener(v -> {
            String url = trimToEmpty(inputUrl.getText().toString());
            if (url.isEmpty()) {
                Toast.makeText(requireContext(), "接口地址不能为空", Toast.LENGTH_SHORT).show();
                return;
            }

            if (url.startsWith("REPLACE_WITH_")) {
                Toast.makeText(requireContext(), "请先把 Constants.DEFAULT_CLOUD_CONFIG_URL 改成你的真实 GitHub Raw JSON 地址", Toast.LENGTH_LONG).show();
                return;
            }

            requestCloudTemplates(url, cloudAdapter, progressBar, emptyView, recyclerView, () -> {
                rememberCloudConfigUrl(url);
                refreshCloudUrlHistoryAdapter(urlHistoryAdapter);
                inputUrl.setText(url, false);
            });
        });

        dialog.show();
        AppDialogs.sizeForList(dialog);

        if (!initUrl.isEmpty() && !initUrl.startsWith("REPLACE_WITH_")) {
            requestCloudTemplates(initUrl, cloudAdapter, progressBar, emptyView, recyclerView, () -> {
                rememberCloudConfigUrl(initUrl);
                refreshCloudUrlHistoryAdapter(urlHistoryAdapter);
                inputUrl.setText(initUrl, false);
            });
        }
    }

    private void showDeleteCloudUrlDialog(String url,
                                          AutoCompleteTextView inputUrl,
                                          ArrayAdapter<String> urlHistoryAdapter) {

        List<String> history = getCloudConfigUrlHistory();
        if (!history.contains(url)) {
            Toast.makeText(requireContext(), "当前地址不在历史列表中", Toast.LENGTH_SHORT).show();
            return;
        }

        AppDialogs.builder(requireContext())
                .setTitle("删除地址")
                .setMessage("从历史地址中删除当前 URL？\n\n" + url)
                .setPositiveButton("删除", (dialog, which) -> {
                    deleteCloudConfigUrl(url);
                    refreshCloudUrlHistoryAdapter(urlHistoryAdapter);

                    String nextUrl = getInitialCloudConfigUrl();
                    inputUrl.setText(nextUrl, false);
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void requestCloudTemplates(String url,
                                       CloudConfigAdapter cloudAdapter,
                                       ProgressBar progressBar,
                                       TextView emptyView,
                                       RecyclerView recyclerView,
                                       @Nullable Runnable onSuccessAction) {

        progressBar.setVisibility(View.VISIBLE);
        recyclerView.setVisibility(View.GONE);
        emptyView.setVisibility(View.VISIBLE);
        emptyView.setText("正在请求云配置...");

        final int generation = viewGeneration;
        final int request = ++cloudGeneration;
        networkClient.getJsonArray(url, new NetworkClient.JsonArrayCallback() {
            @Override
            public void onSuccess(JSONArray data) {
                if (!currentView(generation) || request != cloudGeneration || !recyclerView.isAttachedToWindow()) return;
                List<Map<String, Object>> templateList = parseCloudConfigList(data);

                progressBar.setVisibility(View.GONE);
                cloudAdapter.updateData(templateList);

                if (templateList.isEmpty()) {
                    recyclerView.setVisibility(View.GONE);
                    emptyView.setVisibility(View.VISIBLE);
                    emptyView.setText("没有可用的云配置");
                } else {
                    recyclerView.setVisibility(View.VISIBLE);
                    emptyView.setVisibility(View.GONE);
                }

                if (onSuccessAction != null) {
                    onSuccessAction.run();
                }
            }

            @Override
            public void onFailure(String errorMessage) {
                if (!currentView(generation) || request != cloudGeneration || !recyclerView.isAttachedToWindow()) return;
                progressBar.setVisibility(View.GONE);
                recyclerView.setVisibility(View.GONE);
                emptyView.setVisibility(View.VISIBLE);
                emptyView.setText(errorMessage);
                Toast.makeText(requireContext(), errorMessage, Toast.LENGTH_SHORT).show();
            }
        });
    }

    private List<Map<String, Object>> parseCloudConfigList(JSONArray jsonArray) {
        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = 0; i < jsonArray.length(); i++) {
            try {
                JSONObject obj = jsonArray.getJSONObject(i);

                Map<String, Object> item = new HashMap<>();

                String name = firstNonEmpty(obj, "name", "nickname", "nickName");
                String remark = firstNonEmpty(obj, "remark", "note", "desc", "description");
                String packageName = firstNonEmpty(obj, "packageName", "package_name", "package");
                String className = firstNonEmpty(obj, "className", "class_name", "class");
                String methodName = firstNonEmpty(obj, "methodName", "method_name", "method");
                String category = firstNonEmpty(obj, "category", "tag");
                String title = firstNonEmpty(obj, "title", "titleIndex", "title_index");
                String data = firstNonEmpty(obj, "data", "dataIndex", "data_index");

                if (!name.isEmpty()) {
                    item.put("name", name);
                }
                if (!remark.isEmpty()) {
                    item.put("remark", remark);
                }
                if (!packageName.isEmpty()) {
                    item.put("packageName", packageName);
                }
                if (!className.isEmpty()) {
                    item.put("className", className);
                }
                if (!methodName.isEmpty()) {
                    item.put("methodName", methodName);
                }
                if (!category.isEmpty()) {
                    item.put("category", category);
                }
                if (!title.isEmpty()) {
                    item.put("title", title);
                }
                if (!data.isEmpty()) {
                    item.put("data", data);
                }

                if (!item.isEmpty()) {
                    result.add(item);
                }
            } catch (JSONException e) {
                Log.e(TAG, "parseCloudConfigList: 解析单条模板失败", e);
            }
        }

        return result;
    }

    private String firstNonEmpty(JSONObject obj, String... keys) {
        for (String key : keys) {
            if (obj.has(key) && !obj.isNull(key)) {
                String value = obj.optString(key, "").trim();
                if (!value.isEmpty()) {
                    return value;
                }
            }
        }
        return "";
    }

    private String getInitialCloudConfigUrl() {
        String current = trimToEmpty(SharedPreferencesUtils.getStr(requireContext(), Constants.CLOUD_CONFIG_URL));
        if (!current.isEmpty()) {
            return current;
        }

        List<String> history = getCloudConfigUrlHistory();
        if (!history.isEmpty()) {
            return history.get(0);
        }

        return trimToEmpty(Constants.DEFAULT_CLOUD_CONFIG_URL);
    }

    private List<String> getCloudConfigUrlHistory() {
        List<String> result = new ArrayList<>();

        String historyJson = SharedPreferencesUtils.getStr(requireContext(), Constants.CLOUD_CONFIG_URL_HISTORY);
        if (historyJson == null) {
            String defaultUrl = trimToEmpty(Constants.DEFAULT_CLOUD_CONFIG_URL);
            if (isValidCloudUrlForHistory(defaultUrl)) {
                result.add(defaultUrl);
            }
        } else {
            try {
                JSONArray jsonArray = new JSONArray(historyJson);
                for (int i = 0; i < jsonArray.length(); i++) {
                    String url = trimToEmpty(jsonArray.optString(i));
                    if (isValidCloudUrlForHistory(url) && !result.contains(url)) {
                        result.add(url);
                    }
                }
            } catch (JSONException e) {
                Log.e(TAG, "读取云配置 URL 历史失败", e);
            }
        }

        String current = trimToEmpty(SharedPreferencesUtils.getStr(requireContext(), Constants.CLOUD_CONFIG_URL));
        if (isValidCloudUrlForHistory(current)) {
            result.remove(current);
            result.add(0, current);
        }

        return result;
    }

    private void refreshCloudUrlHistoryAdapter(ArrayAdapter<String> adapter) {
        adapter.clear();
        adapter.addAll(getCloudConfigUrlHistory());
        adapter.notifyDataSetChanged();
    }

    private void rememberCloudConfigUrl(String url) {
        String normalized = trimToEmpty(url);
        if (!isValidCloudUrlForHistory(normalized)) {
            return;
        }

        List<String> history = getCloudConfigUrlHistory();
        history.remove(normalized);
        history.add(0, normalized);

        if (history.size() > MAX_CLOUD_URL_HISTORY) {
            history = new ArrayList<>(history.subList(0, MAX_CLOUD_URL_HISTORY));
        }

        persistCloudConfigUrlHistory(history);
        SharedPreferencesUtils.putStr(requireContext(), Constants.CLOUD_CONFIG_URL, normalized);
    }

    private void deleteCloudConfigUrl(String url) {
        String normalized = trimToEmpty(url);
        if (normalized.isEmpty()) {
            return;
        }

        List<String> history = getCloudConfigUrlHistory();
        history.remove(normalized);

        persistCloudConfigUrlHistory(history);

        String current = trimToEmpty(SharedPreferencesUtils.getStr(requireContext(), Constants.CLOUD_CONFIG_URL));
        if (normalized.equals(current)) {
            String next = history.isEmpty() ? null : history.get(0);
            SharedPreferencesUtils.putStr(requireContext(), Constants.CLOUD_CONFIG_URL, next);
        }
    }

    private void persistCloudConfigUrlHistory(List<String> history) {
        JSONArray jsonArray = new JSONArray();
        for (String url : history) {
            if (isValidCloudUrlForHistory(url)) {
                jsonArray.put(url);
            }
        }
        SharedPreferencesUtils.putStr(requireContext(), Constants.CLOUD_CONFIG_URL_HISTORY, jsonArray.toString());
    }

    private boolean isValidCloudUrlForHistory(String url) {
        return url != null
                && !url.trim().isEmpty()
                && !url.startsWith("REPLACE_WITH_");
    }

    private String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().addMenuProvider(new MenuProvider() {

            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menu.clear();
                menuInflater.inflate(R.menu.settings_drawer, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.add_text) {
                    showAddEntryModeDialog();
                    return true;
                }
                return false;
            }

        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);
    }

    @Override public void onDestroyView() {
        viewGeneration++;
        cloudGeneration++;
        for (Dialog dialog : new ArrayList<>(activeDialogs)) dialog.dismiss();
        activeDialogs.clear();
        if (applicationsLoading != null) applicationsLoading.hide();
        applicationsLoading = null;
        super.onDestroyView();
    }

    @Override public void onDestroy() {
        applicationsExecutor.shutdownNow();
        super.onDestroy();
    }

    @Override
    public void onPause() {
        super.onPause();
        saveData();
    }
}
