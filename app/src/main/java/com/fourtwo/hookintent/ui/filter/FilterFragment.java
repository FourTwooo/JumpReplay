package com.fourtwo.hookintent.ui.filter;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;

import java.util.ArrayList;
import java.util.List;

public class FilterFragment extends Fragment {

    private RecyclerView recyclerView;
    private FilterAdapter adapter;
    private String currentProtocol; // 用于存储当前被点击的按钮

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_filter, container, false);


        recyclerView = view.findViewById(R.id.list);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        List<String> initialData = new ArrayList<>();
        adapter = new FilterAdapter(initialData, this::goSelect);
        recyclerView.setAdapter(adapter);

        Button buttonIntent = view.findViewById(R.id.button_intent);
        Button buttonScheme = view.findViewById(R.id.button_scheme);

        View.OnClickListener filterButtonClickListener = v -> {
            Button button = (Button) v;
            String protocol = button.getText().toString();
            currentProtocol = protocol.toLowerCase(); // 更新当前协议
            filterContent(protocol);

            // 更新按钮的选中状态和颜色
            if (button.getId() == R.id.button_intent) {
                buttonIntent.setSelected(true);
                buttonScheme.setSelected(false);

                buttonIntent.setTextColor(getResources().getColor(R.color.white));
                buttonScheme.setTextColor(getResources().getColor(R.color.black));
            } else if (button.getId() == R.id.button_scheme) {
                buttonIntent.setSelected(false);
                buttonScheme.setSelected(true);

                buttonIntent.setTextColor(getResources().getColor(R.color.black));
                buttonScheme.setTextColor(getResources().getColor(R.color.white));
            }
        };


        buttonIntent.setOnClickListener(filterButtonClickListener);
        buttonScheme.setOnClickListener(filterButtonClickListener);

        return view;
    }

    private void filterContent(String protocol) {
        List<String> filteredData = new ArrayList<>();
        filteredData.add("FunctionCall");
        filteredData.add("from");
        switch (protocol) {
            case "Intent":
                break;
            case "Scheme":
                filteredData.add("scheme");
                break;
        }
        adapter.updateData(filteredData);
    }

    private void goSelect(String item) {
        NavController navController = Navigation.findNavController(requireActivity(), R.id.list);
        Bundle bundle = new Bundle();
        bundle.putString("NAME", item);
        bundle.putString("PROTOCOL", currentProtocol); // 添加协议信息到 Bundle
        navController.navigate(R.id.nav_select, bundle);
    }
}
