package com.fourtwo.hookintent.ui.star;

import android.annotation.SuppressLint;
import android.graphics.Canvas;
import android.os.Bundle;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.base.LocalDatabaseManager;
import com.fourtwo.hookintent.data.Constants;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.databinding.FragmentStarBinding;
import com.fourtwo.hookintent.ui.home.HomeAdapter;
import com.fourtwo.hookintent.utils.DataProcessor;
import com.fourtwo.hookintent.viewmodel.MainViewModel;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class StarFragment extends Fragment {

    private FragmentStarBinding binding;

    private LocalDatabaseManager dbManager;

    private StarViewModel viewModel;
    final String TAG = "StarFragment";

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // 初始化数据库管理类
        dbManager = new LocalDatabaseManager(requireContext());
        // 打开数据库
        dbManager.openDatabase(Constants.STAR_DB_NAME);
        // 确保表存在
        dbManager.createTable(Constants.STAR_TABLE_NAME);
    }

    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {

        binding = FragmentStarBinding.inflate(inflater, container, false);
        View root = binding.getRoot();

        final RecyclerView recyclerView = binding.recyclerView;
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        // Initialize Adapter
        HomeAdapter adapter = new HomeAdapter(new ArrayList<>());
        recyclerView.setAdapter(adapter);

        viewModel = new ViewModelProvider(requireActivity()).get(StarViewModel.class);
        viewModel.clearIntentDataList();
        viewModel.getIntentDataList().observe(getViewLifecycleOwner(), adapter::setData);
        // Add divider
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL);
        dividerItemDecoration.setDrawable(Objects.requireNonNull(ContextCompat.getDrawable(requireContext(), R.drawable.divider)));
        recyclerView.addItemDecoration(dividerItemDecoration);

        // 初始化 DataProcessor
        DataProcessor dataProcessor = new DataProcessor(requireContext());
        dataProcessor.setJsonData(new HashMap<>());

//        starViewModel.getText().observe(getViewLifecycleOwner(), textView::setText);
        List<JSONObject> allStarData = dbManager.getAllData(Constants.STAR_TABLE_NAME);
        Log.d(TAG, "onCreateView: " + allStarData.size());
        for (JSONObject jsonObject : allStarData) {
            try {
                String jsonString = jsonObject.getString(Constants.SQL_DATA);
                JSONObject jsonObjectFromSQL = new JSONObject(jsonString);
                Bundle data = JsonHandler.toBundle(jsonObjectFromSQL);
                dataProcessor.processBundle(data, itemData -> viewModel.addIntentData(itemData));
            } catch (JSONException e) {
                throw new RuntimeException(e);
            }
        }
        return root;
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        binding = null;
    }
}