package com.fourtwo.hookintent.ui.home;

import android.annotation.SuppressLint;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.core.view.MenuProvider;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.ViewModelProvider;
import androidx.navigation.NavController;
import androidx.navigation.Navigation;
import androidx.recyclerview.widget.DividerItemDecoration;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.manager.HookStatusManager;
import com.fourtwo.hookintent.service.MessengerService;
import com.fourtwo.hookintent.utils.DataProcessor;
import com.fourtwo.hookintent.viewmodel.MainViewModel;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentLinkedQueue;

public class HomeFragment extends Fragment {
    private final String TAG = "HomeFragment";
    private RecyclerView recyclerView;
    private TextView emptyView;
    private HomeAdapter adapter;
    private MainViewModel viewModel;

    private DataProcessor dataProcessor;

    private FloatingActionButton fab;
    private static boolean isHook = false; // 保留 isHook 状态

    private Map<String, Object> JsonData;

    private boolean getIsHook() {
        // 调用 MessengerService 的 notifyClients 方法
        MessengerService service = MessengerService.getInstance();
        if (service != null) {
            Bundle bundle = new Bundle();
            bundle.putBoolean("isHook", isHook);
            service.notifyClients(bundle);
        }

        return isHook;
    }

    private static boolean isXposed() {
        return false;
    }


    @SuppressLint("UseCompatLoadingForDrawables")
    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);
        requireActivity().addMenuProvider(new MenuProvider() {
            @Override
            public void onCreateMenu(@NonNull Menu menu, @NonNull MenuInflater menuInflater) {
                menu.clear(); // 清除现有菜单项以避免重复
                menuInflater.inflate(R.menu.home_drawer, menu); // 加载特定于此Fragment的菜单

                // 使用反射设置溢出菜单中的图标可见
                if (menu.getClass().getSimpleName().equalsIgnoreCase("MenuBuilder")) {
                    try {
                        Method method = menu.getClass().getDeclaredMethod("setOptionalIconsVisible", Boolean.TYPE);
                        method.setAccessible(true);
                        method.invoke(menu, true);
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }

            @Override
            public boolean onMenuItemSelected(@NonNull MenuItem menuItem) {
                int itemId = menuItem.getItemId();
                if (itemId == R.id.remove_list) {
                    // 清空RecyclerView的数据
                    adapter.clearData();
                    // 如果需要，也可以在ViewModel中清空数据
                    viewModel.clearIntentDataList();
                    return true;
                } else if (itemId == R.id.action_filter) {
                    // 使用 NavController 进行导航
                    NavController navController = Navigation.findNavController(requireActivity(), R.id.nav_host_fragment_content_main);
                    navController.navigate(R.id.nav_filter);
                    return true;
                } else if (itemId == R.id.action_services) {
                    List<Bundle> connectedClients = new ArrayList<>();
                    try {
                        connectedClients = MessengerService.getInstance().getConnectedClients();
                    } catch (NullPointerException ignored) {
                    }

                    Log.d(TAG, "onMenuItemSelected: connectedClients size = " + connectedClients.size());

                    // 创建弹窗
                    AlertDialog.Builder builder = new AlertDialog.Builder(requireContext());
//                    builder.setTitle("应用通信连接");

                    // 加载弹窗布局
                    View dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_custom_services, null);
                    RecyclerView recyclerView = dialogView.findViewById(R.id.services_list);
                    recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

                    // 设置 Adapter
                    recyclerView.setAdapter(new ConnectedClientsAdapter(connectedClients)); // 传入数据

                    builder.setView(dialogView);
//                    builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());

                    // 创建弹窗
                    AlertDialog dialog = builder.create();

                    // 设置弹窗背景为圆角背景
                    if (dialog.getWindow() != null) {
                        dialog.getWindow().setBackgroundDrawableResource(R.drawable.dialog_rounded_background);
                    }

                    dialog.show();

                    return true;
                }

                return false;
            }

        }, getViewLifecycleOwner(), Lifecycle.State.RESUMED);


        // Initialize FloatingActionButton using the view parameter
        fab = view.findViewById(R.id.fab);

        // Other initializations
        recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));

        // Initialize Adapter
        adapter = new HomeAdapter(new ArrayList<>());
        recyclerView.setAdapter(adapter);

        // Add divider
        DividerItemDecoration dividerItemDecoration = new DividerItemDecoration(recyclerView.getContext(), DividerItemDecoration.VERTICAL);
        dividerItemDecoration.setDrawable(Objects.requireNonNull(ContextCompat.getDrawable(requireContext(), R.drawable.divider)));
        recyclerView.addItemDecoration(dividerItemDecoration);

        // Initialize ViewModel and observe LiveData
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        viewModel.getIntentDataList().observe(getViewLifecycleOwner(), itemDataList -> {
            adapter.setData(itemDataList);
            toggleEmptyView(itemDataList);
        });

        // Initialize search EditText and set its TextWatcher
        EditText searchEditText = view.findViewById(R.id.searchEditText);
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                adapter.getFilter().filter(s);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        // 按钮保活
        viewModel.getIsHook().observe(getViewLifecycleOwner(), hook -> {
            isHook = hook;
            updateFabAppearance();
        });

        fab = view.findViewById(R.id.fab);
        fab.setOnClickListener(v -> {
            // Toggle the isHook state
            boolean newIsHook = !isHook;
            viewModel.setIsHook(newIsHook);
        });

        updateFabAppearance();
        // Set up ItemTouchHelper for swipe actions
        ItemTouchHelper.SimpleCallback simpleItemTouchCallback = new ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT) {
            @Override
            public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, @NonNull RecyclerView.ViewHolder target) {
                return false;
            }

            @SuppressLint("NotifyDataSetChanged")
            @Override
            public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
                int position = viewHolder.getAdapterPosition();

                if (position >= 0 && position < adapter.getItemCount()) {
                    // 从适配器获取要删除的 ItemData
                    ItemData item = adapter.getFilteredData().get(position);

                    // 从 ViewModel 中删除数据
                    int originalPosition = adapter.getData().indexOf(item);
                    Log.d(TAG, "originalPosition: " + originalPosition);
                    if (originalPosition != -1) {
                        viewModel.removeIntentData(originalPosition);
                    }

                    // 从适配器中删除数据
                    adapter.removeItem(position);
                } else {
                    // 如果位置无效，刷新适配器以避免崩溃
                    adapter.notifyDataSetChanged();
                }
            }


            @Override
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder, float dX, float dY, int actionState, boolean isCurrentlyActive) {
                float alpha = 1 - Math.abs(dX) / (float) viewHolder.itemView.getWidth();
                viewHolder.itemView.setAlpha(alpha);
                super.onChildDraw(c, recyclerView, viewHolder, dX, dY, actionState, isCurrentlyActive);
            }

            @Override
            public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
                super.clearView(recyclerView, viewHolder);
                viewHolder.itemView.setAlpha(1.0f);
            }
        };

        ItemTouchHelper itemTouchHelper = new ItemTouchHelper(simpleItemTouchCallback);
        itemTouchHelper.attachToRecyclerView(recyclerView);

        // 初始化 DataProcessor
        dataProcessor = new DataProcessor(requireContext());
        dataProcessor.setJsonData(JsonData);

        Log.d(TAG, "onViewCreated: MessengerService");
        // 观察 MessengerService 的 liveDataTrigger
        MessengerService.liveDataTrigger.observe(getViewLifecycleOwner(), trigger -> {
            if (trigger != null && trigger) {
                // 逐条处理队列中的数据
                MessengerService Instance = MessengerService.getInstance();
                Log.d(TAG, "onViewCreated: " + Instance);
                if (Instance == null) {
                    return;
                }
                ConcurrentLinkedQueue<Bundle> queue = Instance.getDataQueue();
                while (!queue.isEmpty()) {
                    Bundle data = queue.poll(); // 从队列中取出数据
                    if (data != null) {
                        dataProcessor.processReceivedData(data, itemData -> viewModel.addIntentData(itemData)); // 使用 DataProcessor 处理数据
                    }
                }
            }
        });
    }

    private void updateFabAppearance() {
        if (isHook) {
            fab.setImageResource(android.R.drawable.ic_media_pause);
            fab.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#47AA4B")));
            setEmptyView("暂无数据");
        } else {
            setEmptyView("点击按钮开启HOOK");
            fab.setImageResource(android.R.drawable.ic_media_play);
            fab.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#CE1A7EAC")));
        }
        HookStatusManager.setHook(isHook);
        Log.d(TAG, "isHook" + ": " + getIsHook());
    }

    @SuppressLint("SetTextI18n")
    private void setEmptyView(String text) {
        if (isXposed()) {
            emptyView.setText(text);
        } else {

            emptyView.setText("模块未激活! 如果使用了LSPatch 请忽略");
            emptyView.setTextColor(ContextCompat.getColor(requireContext(), R.color.red));
        }
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_home, container, false);

        recyclerView = root.findViewById(R.id.recycler_view);
        emptyView = root.findViewById(R.id.empty_view);
        setEmptyView("点击按钮开启HOOK");

        // Set up RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new HomeAdapter(new ArrayList<>());
        recyclerView.setAdapter(adapter);

        return root;
    }

    private void toggleEmptyView(List<ItemData> data) {
        if (data == null || data.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyView.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyView.setVisibility(View.GONE);
        }
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        Log.d(TAG, "onDestroyView");
    }

    @Override
    public void onResume() {
        super.onResume();

        if (fab != null) {
            fab.show();
        }

        // 获取当前搜索框内容
        EditText searchEditText = requireView().findViewById(R.id.searchEditText);
        String currentSearchText = searchEditText != null ? searchEditText.getText().toString().trim() : "";

        // 根据搜索框内容重新过滤数据
        adapter.getFilter().filter(currentSearchText);

        // 确保显示正确的数据集
        List<ItemData> allData = viewModel.getIntentDataList().getValue();
        if (allData != null && currentSearchText.isEmpty()) {
            adapter.setData(allData);
        }

        JsonData = new JsonHandler().readJsonFromFile(requireContext());
        Log.d(TAG, "onResume: " + JsonData);

        dataProcessor.setJsonData(JsonData);

    }

    @Override
    public void onPause() {
        super.onPause();
        if (fab != null) {
            fab.hide();
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        Log.d(TAG, "onDestroy");
    }

}
