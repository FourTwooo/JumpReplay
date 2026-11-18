package com.fourtwo.hookintent.ui.filter;

import android.annotation.SuppressLint;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.navigation.fragment.NavHostFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.NavigationCaptureSource;
import com.fourtwo.hookintent.utils.SharedPreferencesUtils;
import com.fourtwo.hookintent.utils.StyledInputDialogHelper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class FilterFragment extends Fragment {

    private static final String LEGACY_INTENT_KEY = "intent";
    private static final String LEGACY_SCHEME_KEY = "scheme";

    private static final String FIELD_FUNCTION_CALL = "FunctionCall";
    private static final String FIELD_FROM = "from";
    private static final String FIELD_SCHEME = "scheme";

    private RecyclerView recyclerView;
    private FilterAdapter adapter;
    private LinearLayout buttonContainer;

    private final JsonHandler jsonHandler = new JsonHandler();
    private Map<String, Object> jsonData = new HashMap<>();

    private String currentProtocol;
    private boolean dataReady;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_filter, container, false);

        buttonContainer = view.findViewById(R.id.button_container);

        recyclerView = view.findViewById(R.id.list);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        adapter = new FilterAdapter(new ArrayList<>(), this::goSelect);
        recyclerView.setAdapter(adapter);

        return view;
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menu.clear();
                menuInflater.inflate(R.menu.select_drawer, menu);
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                if (menuItem.getItemId() == R.id.add_text) {
                    showAddKeyDialog();
                    return true;
                }
                return false;
            }
        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

        reloadAndRender();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadAndRender();
    }

    private void reloadAndRender() {
        if (!loadFilterData()) return;

        boolean changed = syncFilterGroups();

        List<String> protocolKeys = buildProtocolKeys();
        if (currentProtocol == null || !protocolKeys.contains(currentProtocol)) {
            currentProtocol = protocolKeys.isEmpty() ? null : protocolKeys.get(0);
        }

        renderProtocolButtons(protocolKeys);
        refreshFieldList();

        if (changed) {
            saveFilterData();
        }
    }

    private boolean loadFilterData() {
        Map<String, Object> data = jsonHandler.readJsonFromFile(requireContext());
        if (data == null) {
            dataReady = false;
            Toast.makeText(requireContext(), "过滤规则读取失败，原文件已保留", Toast.LENGTH_LONG).show();
            return false;
        }
        jsonData = data;
        dataReady = true;
        return true;
    }

    private void saveFilterData() {
        try {
            jsonHandler.writeJsonToFile(requireContext(), jsonData);
        } catch (IllegalStateException e) {
            Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    /**
     * 过滤分组包含内建和自定义捕获标签。
     * 这里只自动补，不自动删，避免误删已有过滤规则。
     */
    private boolean syncFilterGroups() {
        boolean changed = false;

        Set<String> allowedGroups = new LinkedHashSet<>();
        allowedGroups.add(LEGACY_INTENT_KEY);
        allowedGroups.add(LEGACY_SCHEME_KEY);
        allowedGroups.addAll(getSettingsGroupKeys());

        changed |= ensureBuiltInGroup(LEGACY_INTENT_KEY, FIELD_FUNCTION_CALL, FIELD_FROM);
        changed |= ensureBuiltInGroup(LEGACY_SCHEME_KEY, FIELD_FUNCTION_CALL, FIELD_FROM, FIELD_SCHEME);
        for (NavigationCaptureSource source : NavigationCaptureSource.values()) {
            changed |= ensureBuiltInGroup(source.category(), FIELD_FUNCTION_CALL, FIELD_FROM);
        }

        for (String groupKey : allowedGroups) {
            if (isBuiltInGroup(groupKey)) {
                continue;
            }
            changed |= ensureCustomGroup(groupKey);
        }

        return changed;
    }

    private boolean ensureBuiltInGroup(String groupKey, String... fieldKeys) {
        boolean changed = false;

        Map<String, Object> groupData = JsonHandler.getFilterKeyJson(jsonData.get(groupKey));
        if (groupData == null) {
            groupData = new HashMap<>();
            jsonData.put(groupKey, groupData);
            changed = true;
        }

        for (String fieldKey : fieldKeys) {
            Object value = groupData.get(fieldKey);
            if (!(value instanceof List)) {
                groupData.put(fieldKey, new ArrayList<Map<String, Object>>());
                changed = true;
            }
        }

        return changed;
    }

    private boolean ensureCustomGroup(String groupKey) {
        Map<String, Object> groupData = JsonHandler.getFilterKeyJson(jsonData.get(groupKey));
        if (groupData != null) {
            return false;
        }

        jsonData.put(groupKey, new HashMap<String, Object>());
        return true;
    }

    private List<String> getSettingsGroupKeys() {
        Set<String> result = new LinkedHashSet<>();
        collectGroupKeysFromPreference(Constants.INTERNAL_HOOKS_CONFIG, result);
        collectGroupKeysFromPreference(Constants.EXTERNAL_HOOKS_CONFIG, result);
        return new ArrayList<>(result);
    }

    private void collectGroupKeysFromPreference(String prefKey, Set<String> out) {
        String config = SharedPreferencesUtils.getStr(requireContext(), prefKey);
        if (config == null || config.trim().isEmpty() || "null".equals(config) || "\"null\"".equals(config)) {
            return;
        }

        List<Map<String, Object>> records = JsonHandler.deserializeHookedRecords(config);
        for (Map<String, Object> item : records) {
            String rawCategory = (String) item.get("category");
            String normalized = normalizeGroupKey(rawCategory);
            if (normalized != null && !normalized.isEmpty()) {
                out.add(normalized);
            }
        }
    }

    private String normalizeGroupKey(String rawCategory) {
        if (rawCategory == null) {
            return null;
        }

        String category = rawCategory.trim();
        if (category.isEmpty()) {
            return null;
        }

        if ("Intent".equalsIgnoreCase(category)) {
            return LEGACY_INTENT_KEY;
        }

        if ("Scheme".equalsIgnoreCase(category)) {
            return LEGACY_SCHEME_KEY;
        }

        return category;
    }

    private boolean isBuiltInGroup(String groupKey) {
        return LEGACY_INTENT_KEY.equalsIgnoreCase(groupKey)
                || LEGACY_SCHEME_KEY.equalsIgnoreCase(groupKey)
                || Constants.FRAGMENT_CATEGORY.equals(groupKey)
                || Constants.NAV_CONTROLLER_CATEGORY.equals(groupKey);
    }

    private List<String> buildProtocolKeys() {
        List<String> result = new ArrayList<>();

        if (jsonData.containsKey(LEGACY_INTENT_KEY)) {
            result.add(LEGACY_INTENT_KEY);
        }

        if (jsonData.containsKey(LEGACY_SCHEME_KEY)) {
            result.add(LEGACY_SCHEME_KEY);
        }

        for (NavigationCaptureSource source : NavigationCaptureSource.values()) {
            if (jsonData.containsKey(source.category())) result.add(source.category());
        }

        List<String> customKeys = new ArrayList<>();
        for (String key : jsonData.keySet()) {
            if (!isBuiltInGroup(key)) {
                customKeys.add(key);
            }
        }

        Collections.sort(customKeys, String.CASE_INSENSITIVE_ORDER);
        result.addAll(customKeys);

        return result;
    }

    private void renderProtocolButtons(List<String> protocolKeys) {
        buttonContainer.removeAllViews();

        for (String protocolKey : protocolKeys) {
            Button button = new Button(requireContext());
            button.setTag(protocolKey);
            button.setText(getProtocolDisplayName(protocolKey));
            button.setAllCaps(false);
            button.setBackgroundResource(R.drawable.button_background_selector);
            button.setTextColor(ContextCompat.getColor(requireContext(), R.color.screen_text));

            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
            );
            params.setMargins(0, 0, dpToPx(8), 0);
            button.setLayoutParams(params);

            button.setOnClickListener(v -> {
                currentProtocol = protocolKey;
                refreshFieldList();
            });

            buttonContainer.addView(button);
        }

        updateProtocolButtons();
    }

    private void refreshFieldList() {
        List<String> fieldKeys = new ArrayList<>();

        if (currentProtocol != null) {
            Map<String, Object> groupData = JsonHandler.getFilterKeyJson(jsonData.get(currentProtocol));
            if (groupData != null) {
                fieldKeys.addAll(groupData.keySet());
                Collections.sort(fieldKeys, String.CASE_INSENSITIVE_ORDER);
            }
        }

        adapter.updateData(fieldKeys);
        updateProtocolButtons();
    }

    private void updateProtocolButtons() {
        for (int i = 0; i < buttonContainer.getChildCount(); i++) {
            View child = buttonContainer.getChildAt(i);
            if (!(child instanceof Button)) {
                continue;
            }

            Button button = (Button) child;
            String protocolKey = (String) button.getTag();
            boolean selected = protocolKey != null && protocolKey.equals(currentProtocol);

            button.setSelected(selected);
            button.setTextColor(ContextCompat.getColor(
                    requireContext(),
                    selected ? R.color.white : R.color.screen_text
            ));
        }
    }

    private String getProtocolDisplayName(String protocolKey) {
        if (LEGACY_INTENT_KEY.equalsIgnoreCase(protocolKey)) {
            return "Intent";
        }

        if (LEGACY_SCHEME_KEY.equalsIgnoreCase(protocolKey)) {
            return "Scheme";
        }

        return protocolKey;
    }

    @SuppressLint("SetTextI18n")
    private void showAddKeyDialog() {
        if (!dataReady) return;
        if (currentProtocol == null || currentProtocol.trim().isEmpty()) {
            Toast.makeText(requireContext(), getString(R.string.is_not_list), Toast.LENGTH_SHORT).show();
            return;
        }

        StyledInputDialogHelper.showSingleInputDialog(
                requireContext(),
                getString(R.string.add_key),
                getString(R.string.key_input_text),
                null,
                (newKey, dialog) -> {
                    if (newKey.isEmpty()) {
                        Toast.makeText(requireContext(), getString(R.string.key_false), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    Map<String, Object> groupData = JsonHandler.getFilterKeyJson(jsonData.get(currentProtocol));
                    if (groupData == null) {
                        groupData = new HashMap<>();
                        jsonData.put(currentProtocol, groupData);
                    }

                    if (groupData.containsKey(newKey)) {
                        Toast.makeText(requireContext(), getString(R.string.key_true), Toast.LENGTH_SHORT).show();
                        return;
                    }

                    groupData.put(newKey, new ArrayList<Map<String, Object>>());
                    saveFilterData();
                    refreshFieldList();
                    dialog.dismiss();
                }
        );
    }

    private void goSelect(String item) {
        if (!dataReady) return;
        if (currentProtocol == null || currentProtocol.isEmpty()) {
            return;
        }

        Bundle bundle = new Bundle();
        bundle.putString("NAME", item);
        bundle.putString("PROTOCOL", currentProtocol);

        NavHostFragment.findNavController(this).navigate(R.id.nav_select, bundle);
    }

    private int dpToPx(int dp) {
        return Math.round(dp * getResources().getDisplayMetrics().density);
    }
}
