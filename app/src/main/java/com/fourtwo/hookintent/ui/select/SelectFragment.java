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

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.analysis.JsonHandler;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SelectFragment extends Fragment {

    private final JsonHandler jsonHandler = new JsonHandler();
    private final String TAG = "SelectFragment";
    private SelectAdapter adapter;

    @SuppressLint("NotifyDataSetChanged")
    private void showAddTextDialog() {
        // 创建一个 AlertDialog.Builder 用于构建对话框
        AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
        builder.setTitle("Add Text");

        // 设置输入框到对话框
        final EditText input = new EditText(requireContext());
        builder.setView(input);

        // 设置对话框的按钮
        builder.setPositiveButton("OK", (dialog, which) -> {
            String newText = input.getText().toString().trim();
            if (!newText.isEmpty()) {
                // 创建新的条目
                Map<String, Object> newItem = new HashMap<>();
                newItem.put("text", newText);
                newItem.put("type", false); // 默认未选中

                // 添加到适配器数据集
                adapter.addItem(newItem);
                adapter.notifyDataSetChanged();
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        // 显示对话框
        builder.show();
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
            return null; // 或适当地处理此情况
        }
        Log.d(TAG, "jsonData: " + jsonData);

        Map<String, Object> protocolData = JsonHandler.getFilterKeyJson(jsonData.get(protocol));
//        Map<String, Object> protocolData = (Map<String, Object>) jsonData.get(protocol);
        Log.d(TAG, "onCreateView: " + jsonData.get(protocol));
        if (protocolData == null) {
            Log.e(TAG, "Protocol data is null " + jsonData + " for protocol: " + protocol);
            return null; // 或适当地处理此情况
        }

        List<Map<String, Object>> nameData = JsonHandler.getFilterValueJson(protocolData.get(name));
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
        if (protocolData != null) {
            protocolData.put(name, dataWithCheckStates);
            Log.d(TAG, "onPause: " + jsonData);
            jsonHandler.writeJsonToFile(requireContext(), jsonData);
        } else {
            Log.e(TAG, "Protocol data is null " + jsonData + " for protocol: " + protocol);
        }
    }
}

