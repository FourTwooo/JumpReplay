package com.fourtwo.hookintent.ui.home;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
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

import com.fourtwo.hookintent.Constants;
import com.fourtwo.hookintent.ItemData;
import com.fourtwo.hookintent.MainViewModel;
import com.fourtwo.hookintent.R;
import com.fourtwo.hookintent.analysis.Extract;
import com.fourtwo.hookintent.analysis.JsonHandler;
import com.fourtwo.hookintent.analysis.UriData;
import com.fourtwo.hookintent.tools.IntentDuplicateChecker;
import com.fourtwo.hookintent.tools.SchemeResolver;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public class HomeFragment extends Fragment {
    private final String TAG = "HomeFragment";
    private RecyclerView recyclerView;
    private TextView emptyView;
    private HomeAdapter adapter;
    private final IntentDuplicateChecker IntentDuplicateChecker = new IntentDuplicateChecker();
    private final IntentDuplicateChecker SchemeDuplicateChecker = new IntentDuplicateChecker();
    private MainViewModel viewModel;
    private BroadcastReceiver addDataReceiver;
    private FloatingActionButton fab;
    private static boolean isHook = false; // 保留 isHook 状态

    private Map<String, Object> JsonData;

    private boolean getIsHook() {
        // 通知广播
        Context context = requireContext();
        Intent SendIntent = new Intent("SET_JUMP_REPLAY_HOOK");
        SendIntent.putExtra(Constants.TYPE, Constants.SET_IS_HOOK);
        SendIntent.putExtra(Constants.DATA, isHook);
        context.sendBroadcast(SendIntent);
        Log.d(TAG, "HoneGetIsHook" + ": " + isHook);
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
            public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                                    float dX, float dY, int actionState, boolean isCurrentlyActive) {
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

        // Register the broadcast receiver
        addDataReceiver = new BroadcastReceiver() {

            public boolean isFilterMatched(Map<String, Object> schemeData, String key, String valueToMatch) {
                List<Map<String, Object>> itemList = JsonHandler.getFilterValueJson(schemeData.get(key));
                if (itemList == null) {
                    return false;
                }

                for (Map<String, Object> item : itemList) {
                    Boolean type = (Boolean) item.get("type");
                    String text = (String) item.get("text");

                    if (Boolean.TRUE.equals(type) && valueToMatch.equalsIgnoreCase(text)) {
                        return true;
                    }
                }
                return false;
            }

            private boolean filter(Intent intent) {
                boolean is_filter = false;
                String base = intent.getStringExtra("Base");
                Bundle info = intent.getBundleExtra("info");

                String functionCall = info.getString("FunctionCall");
                String from = info.getString("from");

                switch (base) {
                    case "Intent":
                        Map<String, Object> intentData = JsonHandler.getFilterKeyJson(JsonData.get("intent"));
                        assert intentData != null;
                        if (isFilterMatched(intentData, "FunctionCall", functionCall)) {
                            is_filter = true;
                        }
                        if (!is_filter && isFilterMatched(intentData, "from", from)) {
                            is_filter = true;
                        }
                        break;
                    case "Scheme":
                        String scheme_url = info.getString("scheme_raw_url");
                        // Log.d(TAG, "scheme_url  => " + scheme_url);
                        if (scheme_url == null) {
                            is_filter = true;
                            break;
                        }
                        String scheme = Uri.parse(scheme_url).getScheme();
                        if (scheme == null) {
                            is_filter = true;
                            break;
                        }

                        Map<String, Object> schemeData = JsonHandler.getFilterKeyJson(JsonData.get("scheme"));
                        assert schemeData != null;

                        if (isFilterMatched(schemeData, "FunctionCall", functionCall)) {
                            is_filter = true;
                        }

                        if (!is_filter && isFilterMatched(schemeData, "from", from)) {
                            is_filter = true;
                        }

                        if (!is_filter && isFilterMatched(schemeData, "scheme", scheme)) {
                            is_filter = true;
                        }
                        break;
                    default:
                        break;
                }
//                Log.d(TAG, "filter end: " + is_filter);
                return is_filter;
            }


            private void handleMsgType(Context context, Intent intent) {
                Intent sendIntent = new Intent("SET_JUMP_REPLAY_HOOK");
                String data = intent.getStringExtra(Constants.DATA);
                if (Constants.GET_IS_HOOK.equals(data)) {
                    sendIntent.putExtra(Constants.TYPE, Constants.SET_IS_HOOK);
                    sendIntent.putExtra(Constants.DATA, isHook);
                }
                context.sendBroadcast(sendIntent);
            }

            private boolean handleIntentBase(Bundle bundle) {
                Log.d(TAG, "removeBundle: " + bundle);
                return IntentDuplicateChecker.isDuplicate(bundle);
            }

            private boolean handleSchemeBase(Bundle bundle) {
                Log.d(TAG, "removeBundle: " + bundle);
                return SchemeDuplicateChecker.isDuplicate(bundle);
            }

            private HomeAppInfoHelper.AppInfo getAppInfo(Context context, String packageName) {
                HomeAppInfoHelper homeAppInfoHelper = new HomeAppInfoHelper(context);
                HomeAppInfoHelper.AppInfo appInfo = homeAppInfoHelper.getAppInfo(packageName);
                if (appInfo != null) {
                    return appInfo;
                }
                return new HomeAppInfoHelper.AppInfo(
                        "未知应用" + ("/".equals(packageName) || "null".equals(packageName) ? "" : String.format("(%s)", packageName)),
                        ContextCompat.getDrawable(context, R.drawable.ic_launcher_foreground)
                );
            }

            @SuppressLint("DefaultLocale")
            @Override
            public void onReceive(Context context, Intent intent) {
                try {
                    Bundle bundle = intent.getBundleExtra("info");
                    String msgType = intent.getStringExtra(Constants.TYPE);
                    if ("msg".equals(msgType)) {
                        handleMsgType(context, intent);
                        return;
                    }
                    Log.d(TAG, "onReceive: " + bundle);
                    if (bundle == null) {
                        return;
                    }

                    // 过滤
                    if (filter(intent)) {
                        return;
                    }

                    String dataSize = Extract.calculateBundleDataSize(bundle);
                    String time = Extract.extractTime(bundle.getString("time"));
                    String base = intent.getStringExtra("Base");

                    String packageName;
                    String to = "";
                    String dataString = "";

                    if ("Intent".equals(base)) {
                        if (handleIntentBase(bundle)) return;
                        ArrayList<?> intentExtras = bundle.getStringArrayList("intentExtras");
                        if (intentExtras != null) {
                            dataString = Extract.extractIntentExtrasString(intentExtras);
                        }
                        to = bundle.getString("to");
                        packageName = bundle.getString("componentName");
                        if (Objects.equals(packageName, "null") && !Objects.equals(bundle.getString("dataString"), "null")) {
                            packageName = SchemeResolver.findAppByUri(context, bundle.getString("dataString")) + "/";
                            to = bundle.getString("dataString");
                        }
                        if (Objects.equals(packageName, "null") && !Objects.equals(bundle.getString("action"), "null")) {
                            packageName = SchemeResolver.findAppByUri(context, bundle.getString("action")) + "/";
                            to = bundle.getString("action");
                        }
                        Log.d(TAG, "packageName" + ": " + packageName);
                    } else if ("Scheme".equals(base)) {
                        if (handleSchemeBase(bundle)) return;
                        String schemeRawUrl = bundle.getString("scheme_raw_url");
                        packageName = SchemeResolver.findAppByUri(context, schemeRawUrl) + "/";
                        Bundle bundle1 = Extract.convertMapToBundle(UriData.convertUriToMap(Uri.parse(schemeRawUrl)));
                        bundle.putAll(bundle1);

                        if (schemeRawUrl.startsWith("#Intent;") || bundle.getString("authority").equals("null")) {
                            to = schemeRawUrl;
                            packageName = Extract.getIntentSchemeValue(schemeRawUrl, "component");
                            if (packageName == null) {
                                packageName = Extract.getIntentSchemeValue(schemeRawUrl, "action") + "/";
                            }
                        } else {
                            to = bundle.getString("scheme") + "://" + bundle.getString("authority") + bundle.getString("path");
                        }
                        dataString = bundle.getString("query");
                        if ("null".equals(dataString)) {
                            dataString = "";
                        }
                    } else {
                        return;
                    }

                    HomeAppInfoHelper.AppInfo appInfo = getAppInfo(context, packageName);
                    String appName = appInfo.getAppName();
                    Drawable appIcon = appInfo.getAppIcon();

                    String stackTrace = intent.getStringExtra("stack_trace");
                    String uri = intent.getStringExtra("uri");

                    ItemData itemData = new ItemData(appIcon, appName, to, dataString, time, String.format("%s B", dataSize), bundle, base, stackTrace, uri);
                    viewModel.addIntentData(itemData);
                } catch (Exception e) {
                    Log.e(TAG, "Error processing intent", e);
                }
            }
        };

        IntentFilter intentFilter = new IntentFilter("GET_JUMP_REPLAY_HOOK");
        requireActivity().registerReceiver(addDataReceiver, intentFilter);
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
        Log.d(TAG, "isHook" + ": " + getIsHook());
    }

    @SuppressLint("SetTextI18n")
    private void setEmptyView(String text) {
        if (isXposed()) {
            emptyView.setText(text);
        } else {
            emptyView.setText("Xposed unopened!");
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
//        // 更新过滤表
//        SharedPreferences sharedPreferences = requireContext().getSharedPreferences(SelectItemData.SHARED_PREFERENCES_NAME, Context.MODE_PRIVATE);
//
//        String STAND_ARD_SCHEMES = sharedPreferences.getString(SelectItemData.SHARED_SCHEMES_NAME, null);
//        if (STAND_ARD_SCHEMES == null) {
//            SharedPreferences.Editor editor = sharedPreferences.edit();
//            editor.putString(SelectItemData.SHARED_SCHEMES_NAME, SelectItemData.SHARED_SCHEMES_VALUE);
//            editor.apply();
//        }
//        Map<String, ?> allEntries = sharedPreferences.getAll();
//        Log.d(TAG, "onResume: " + allEntries);
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
        if (addDataReceiver != null) {
            requireActivity().unregisterReceiver(addDataReceiver);
        }
    }

}
