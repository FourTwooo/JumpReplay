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

import com.fourtwo.hookintent.utils.AppDialogs;
import com.fourtwo.hookintent.MainActivity;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.base.JsonHandler;
import com.fourtwo.hookintent.data.ItemData;
import com.fourtwo.hookintent.data.CaptureRepository;
import android.widget.Toast;
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
import java.util.HashMap;

public class HomeFragment extends Fragment {
    private final String TAG = "HomeFragment";
    private RecyclerView recyclerView;
    private TextView emptyView;
    private TextView emptyTitle;
    private View emptyState;
    private View moduleHint;
    private EditText searchInput;
    private HomeAdapter adapter;
    private MainViewModel viewModel;

    private CaptureRepository repository;
    private boolean hasMore;
    private boolean loadingMore;
    private boolean regexSearch;
    private boolean monitorPermissionPending;

    private void search(EditText input) {
        String query = input.getText().toString();
        if (regexSearch && !query.trim().isEmpty()) {
            try { java.util.regex.Pattern.compile(query.trim()); }
            catch (java.util.regex.PatternSyntaxException error) {
                input.setError(getString(R.string.input_regex_invalid));
                return;
            }
        }
        input.setError(null);
        repository.setQuery(query, regexSearch);
        updateEmptyState();
    }

    @Override public void onSaveInstanceState(@NonNull Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("capture_search_regex", regexSearch);
        state.putBoolean("monitor_permission_pending", monitorPermissionPending);
    }

    private FloatingActionButton fab;
    private static boolean isHook = false; // 保留 isHook 状态

    private Map<String, Object> JsonData = new HashMap<>();

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
                        @SuppressLint("PrivateApi") Method method = menu.getClass().getDeclaredMethod("setOptionalIconsVisible", Boolean.TYPE);
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
                if (itemId == R.id.capture_monitor_toggle) {
                    configureMonitor(); return true;
                } else if (itemId == R.id.remove_list) {
                    // 清空RecyclerView的数据
                    repository.clear();
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
                    AlertDialog.Builder builder = AppDialogs.builder(requireContext());
//                    builder.setTitle("应用通信连接");

                    // 加载弹窗布局
                    View dialogView = LayoutInflater.from(builder.getContext()).inflate(R.layout.dialog_custom_services, null);
                    RecyclerView recyclerView = dialogView.findViewById(R.id.services_list);
                    recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

                    // 设置 Adapter
                    recyclerView.setAdapter(new ConnectedClientsAdapter(connectedClients)); // 传入数据

                    builder.setView(dialogView);
//                    builder.setPositiveButton("OK", (dialog, which) -> dialog.dismiss());

                    // 创建弹窗
                    AlertDialog dialog = builder.create();

                    dialog.show();
                    AppDialogs.sizeForList(dialog);

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
        repository = CaptureRepository.get(requireContext());
        repository.getItems().observe(getViewLifecycleOwner(), itemDataList -> {
            loadingMore = false;
            adapter.setData(itemDataList);
            toggleEmptyView(itemDataList);
        });

        // Initialize search EditText and set its TextWatcher
        EditText searchEditText = view.findViewById(R.id.searchEditText);
        if (savedInstanceState != null) regexSearch = savedInstanceState.getBoolean("capture_search_regex");
        if (savedInstanceState != null) monitorPermissionPending = savedInstanceState.getBoolean("monitor_permission_pending");
        com.google.android.material.textfield.TextInputLayout searchBox = view.findViewById(R.id.home_search_box);
        Runnable updateSearchIcon = () -> {
            searchBox.setEndIconContentDescription(regexSearch ? R.string.input_regex_on : R.string.input_regex_off);
            searchBox.setEndIconTintList(ColorStateList.valueOf(ContextCompat.getColor(requireContext(),
                    regexSearch ? R.color.dialog_accent : R.color.dialog_secondary)));
        };
        updateSearchIcon.run();
        searchBox.setEndIconOnClickListener(button -> {
            regexSearch = !regexSearch;
            updateSearchIcon.run();
            search(searchEditText);
        });
        searchEditText.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                search(searchEditText);
            }

            @Override
            public void afterTextChanged(Editable s) {
            }
        });

        HookStatusManager.initialize(requireContext());
        if (HookStatusManager.isHook()) com.fourtwo.hookintent.service.CaptureSessionService.refresh(requireContext());
        // 所有全局入口观察同一状态，视图重建不会关闭捕获。
        viewModel.getIsHook().observe(getViewLifecycleOwner(), hook -> {
            isHook = hook;
            updateFabAppearance();
        });

        fab = view.findViewById(R.id.fab);
        fab.setOnClickListener(v -> {
            // Toggle the isHook state
            HookStatusManager.setHook(requireContext(), !HookStatusManager.isHook());
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

                    repository.remove(item);
                    adapter.notifyItemChanged(position);
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

        repository.getErrors().observe(getViewLifecycleOwner(), error -> {
            if (error != null && !error.isEmpty()) Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show();
        });
        repository.getHasMore().observe(getViewLifecycleOwner(), more -> hasMore = Boolean.TRUE.equals(more));
        recyclerView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(@NonNull RecyclerView view, int dx, int dy) {
                LinearLayoutManager layout = (LinearLayoutManager) view.getLayoutManager();
                if (dy < 0 && layout != null && layout.findFirstVisibleItemPosition() <= 3) requestOlderCaptures();
            }
            @Override public void onScrollStateChanged(@NonNull RecyclerView view, int state) {
                if (state == RecyclerView.SCROLL_STATE_DRAGGING && !view.canScrollVertically(-1)) requestOlderCaptures();
            }
        });
    }

    private void requestOlderCaptures() {
        if (hasMore && !loadingMore) {
            loadingMore = true;
            repository.loadMore();
        }
    }

    private void configureMonitor() {
        boolean current = com.fourtwo.hookintent.service.CaptureSessionService.monitorEnabled(requireContext());
        AppDialogs.builder(requireContext()).setTitle(R.string.capture_monitor)
                .setMessage(R.string.capture_monitor_explain)
                .setNegativeButton(R.string.cancel, null)
                .setPositiveButton(current ? R.string.capture_monitor_hide : R.string.capture_monitor_show, (dialog, which) -> {
                    if (current) com.fourtwo.hookintent.service.CaptureSessionService.monitorEnabled(requireContext(), false);
                    else if (android.provider.Settings.canDrawOverlays(requireContext())) {
                        com.fourtwo.hookintent.service.CaptureSessionService.monitorEnabled(requireContext(), true);
                        Toast.makeText(requireContext(), "捕获时离开主应用即可显示监视窗", Toast.LENGTH_LONG).show();
                    } else {
                        monitorPermissionPending = true;
                        try {
                            android.content.Intent permission=new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    android.net.Uri.parse("package:" + requireContext().getPackageName()));
                            // The user's global Scheme interceptor may also match package: settings links.
                            // Select an actual system settings handler explicitly for this permission request.
                            boolean resolved=false;
                            for(android.content.pm.ResolveInfo candidate:requireContext().getPackageManager().queryIntentActivities(permission,0)) {
                                android.content.pm.ActivityInfo activity=candidate.activityInfo;
                                if(activity != null && activity.applicationInfo != null
                                        && (activity.applicationInfo.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0) {
                                    permission.setComponent(new android.content.ComponentName(activity.packageName,activity.name)); resolved=true; break;
                                }
                            }
                            if(!resolved) permission=new android.content.Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION);
                            startActivity(permission);
                        }
                        catch (RuntimeException error) { monitorPermissionPending=false; Toast.makeText(requireContext(),error.getMessage(),Toast.LENGTH_LONG).show(); }
                    }
                }).show();
    }

    private void updateFabAppearance() {
        if (isHook) {
            fab.setImageResource(android.R.drawable.ic_media_pause);
            fab.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#47AA4B")));
        } else {
            fab.setImageResource(android.R.drawable.ic_media_play);
            fab.setBackgroundTintList(ColorStateList.valueOf(Color.parseColor("#CE1A7EAC")));
        }
        updateEmptyState();
        Log.d(TAG, "isHook: " + isHook);
    }

    private void updateEmptyState() {
        if (emptyTitle == null) return;
        boolean searching = searchInput != null && !searchInput.getText().toString().trim().isEmpty();
        emptyTitle.setText(searching ? R.string.home_empty_search_title
                : isHook ? R.string.home_empty_wait_title : R.string.home_empty_title);
        emptyView.setText(searching ? R.string.home_empty_search_body
                : isHook ? R.string.home_empty_wait_body : R.string.home_empty_body);
        moduleHint.setVisibility(MainActivity.isXposed() ? View.GONE : View.VISIBLE);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.fragment_home, container, false);

        recyclerView = root.findViewById(R.id.recycler_view);
        emptyView = root.findViewById(R.id.empty_view);
        emptyTitle = root.findViewById(R.id.empty_title);
        emptyState = root.findViewById(R.id.empty_state);
        moduleHint = root.findViewById(R.id.module_hint);
        searchInput = root.findViewById(R.id.searchEditText);
        updateEmptyState();

        // Set up RecyclerView
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new HomeAdapter(new ArrayList<>());
        recyclerView.setAdapter(adapter);

        return root;
    }

    private void toggleEmptyView(List<ItemData> data) {
        updateEmptyState();
        if (data == null || data.isEmpty()) {
            recyclerView.setVisibility(View.GONE);
            emptyState.setVisibility(View.VISIBLE);
        } else {
            recyclerView.setVisibility(View.VISIBLE);
            emptyState.setVisibility(View.GONE);
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
        if (monitorPermissionPending) {
            monitorPermissionPending = false;
            boolean granted = android.provider.Settings.canDrawOverlays(requireContext());
            com.fourtwo.hookintent.service.CaptureSessionService.monitorEnabled(requireContext(), granted);
            Toast.makeText(requireContext(), granted ? "监视窗已开启，捕获时离开主应用即可显示" : "未授予悬浮窗权限，基础捕获仍可使用", Toast.LENGTH_LONG).show();
        }

        if (fab != null) {
            fab.show();
        }

        // 获取当前搜索框内容
        EditText searchEditText = requireView().findViewById(R.id.searchEditText);
        String currentSearchText = searchEditText != null ? searchEditText.getText().toString().trim() : "";

        if (searchEditText != null) search(searchEditText);
        repository.refresh();
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
