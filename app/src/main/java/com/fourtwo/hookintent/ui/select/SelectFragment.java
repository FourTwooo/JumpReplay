package com.fourtwo.hookintent.ui.select;

import android.annotation.SuppressLint;
import android.app.AlertDialog;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.utils.StyledInputDialogHelper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

public class SelectFragment extends Fragment {

    private final JsonHandler jsonHandler = new JsonHandler();
    private final String TAG = "SelectFragment";
    private SelectAdapter adapter;

    @SuppressLint("NotifyDataSetChanged")
    private void showAddTextDialog() {
        StyledInputDialogHelper.showSingleInputDialog(
                requireContext(),
                "Add Text",
                "请输入内容",
                null,
                (newText, dialog) -> {
                    if (newText.isEmpty()) {
                        return;
                    }

                    Map<String, Object> newItem = new HashMap<>();
                    newItem.put("text", newText);
                    newItem.put("type", false);

                    adapter.addItem(newItem);
                    adapter.notifyDataSetChanged();
                    dialog.dismiss();
                }
        );
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menu.clear(); // 清除现有菜单项以避免重复
                menuInflater.inflate(R.menu.select_drawer, menu); // 加载特定于此Fragment的菜单
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.add_text) {
                    if (adapter == null) {
                        Toast.makeText(requireContext(), "过滤规则尚未成功读取，无法添加", Toast.LENGTH_LONG).show();
                        return true;
                    }
                    showAddTextDialog();
                    return true;
                }
                return false;
            }

        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);

    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        adapter = null;
        assert getArguments() != null;

        String name = getArguments().getString("NAME");
        String protocol = getArguments().getString("PROTOCOL");

        // 设置 Toolbar 的标题
        if (getActivity() != null) {
            androidx.appcompat.widget.Toolbar toolbar = getActivity().findViewById(R.id.toolbar);
            if (toolbar != null) {
                toolbar.setTitle(name);
            }
        }

        Map<String, Object> jsonData = jsonHandler.readJsonFromFile(requireContext());

        if (jsonData == null) {
            Log.e(TAG, "JSON data is null");
            Toast.makeText(requireContext(), "过滤规则读取失败，原文件已保留", Toast.LENGTH_LONG).show();
            return inflater.inflate(R.layout.fragment_select, container, false);
        }
        Log.d(TAG, "jsonData: " + jsonData);

        Map<String, Object> protocolData = JsonHandler.getFilterKeyJson(jsonData.get(protocol));
        Log.d(TAG, "onCreateView: " + jsonData.get(protocol));

        if (protocolData == null) {
            protocolData = new HashMap<>();
            jsonData.put(protocol, protocolData);
        }

        List<Map<String, Object>> nameData = JsonHandler.getFilterValueJson(protocolData.get(name));
        if (nameData == null) {
            nameData = new ArrayList<>();
            protocolData.put(name, nameData);
            try { jsonHandler.writeJsonToFile(requireContext(), jsonData); }
            catch (IllegalStateException e) { Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show(); }
        }

        Log.d(TAG, "onCreateView: " + name + " " + protocol + " " + nameData);

        View view = inflater.inflate(R.layout.fragment_select, container, false);
        RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new SelectAdapter(nameData);
        recyclerView.setAdapter(adapter);
        return view;
    }

    @Override
    public void onPause() {
        super.onPause();
        if (adapter == null) return;
        List<Map<String, Object>> dataWithCheckStates = adapter.getDataWithCheckStates();

        // Reading the JSON data again
        Map<String, Object> jsonData = jsonHandler.readJsonFromFile(requireContext());
        if (jsonData == null) {
            Log.e(TAG, "JSON data is null in onPause");
            return; // or handle this case appropriately
        }

        assert getArguments() != null;
        String name = getArguments().getString("NAME");
        String protocol = getArguments().getString("PROTOCOL");
        Map<String, Object> protocolData = JsonHandler.getFilterKeyJson(jsonData.get(protocol));
        if (protocolData == null) {
            protocolData = new HashMap<>();
            jsonData.put(protocol, protocolData);
        }

        protocolData.put(name, dataWithCheckStates);
        Log.d(TAG, "onPause: " + jsonData);
        try {
            jsonHandler.writeJsonToFile(requireContext(), jsonData);
        } catch (IllegalStateException e) {
            Toast.makeText(requireContext(), e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}

