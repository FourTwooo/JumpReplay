package com.fourtwo.hookintent.xposed.ui;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toolbar;

import com.fourtwo.hookintent.base.DataConverter;
import com.fourtwo.hookintent.data.ImagesBase64;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XposedBridge;

public class FloatWindowView extends LinearLayout {

    private final Context context;

    public Drawable openDrawable;
    public Drawable closeDrawable;

    public FloatWindowView floatWindowView;

    public interface OnOpenViewClickListener {
        void onOpenViewClick(boolean isHook);
    }

    private OnOpenViewClickListener openListener;

    private OnOpenViewClickListener clearListener;

    public void setOnOpenViewClickListener(OnOpenViewClickListener listener) {
        this.openListener = listener;
    }

    public void setOnClearViewClickListener(OnOpenViewClickListener listener) {
        this.clearListener = listener;
    }

    private final ArrayAdapter<String> adapter;
    private final List<Intent> intentList; // 存储Intent列表

    @SuppressLint("UseCompatLoadingForDrawables")
    public FloatWindowView(Context context) {
        super(context);
        this.context = context;

        openDrawable = ImagesBase64.base64ToDrawable(context, ImagesBase64.r_drawable_open);
        closeDrawable = ImagesBase64.base64ToDrawable(context, ImagesBase64.r_drawable_close);

        // 初始化数据列表和适配器
        intentList = new ArrayList<>();
        adapter = new ArrayAdapter<>(context, android.R.layout.simple_list_item_1, new ArrayList<>());
    }

    public void updateListView(List<Intent> newIntentList) {
        intentList.clear(); // 清除旧数据
        intentList.addAll(newIntentList); // 添加新数据

        List<String> displayList = new ArrayList<>();
//        displayList.add("item1");
        for (Intent intent : newIntentList) {
            // 将Intent转换为String显示
            XposedBridge.log(newIntentList.toString());
            displayList.add(intent.toUri(Intent.URI_INTENT_SCHEME));
        }

        adapter.clear();
        adapter.addAll(displayList); // 更新String数据到适配器
        adapter.notifyDataSetChanged(); // 通知适配器更新
    }

    @SuppressLint({"UseCompatLoadingForDrawables", "RtlHardcoded"})
    public FloatWindowView createView(boolean isHook) {
        floatWindowView = this;
        floatWindowView.setOrientation(LinearLayout.VERTICAL);
        floatWindowView.setPadding(5, 5, 5, 5); // 设置内边距
        floatWindowView.setAlpha(0f); // 初始隐藏
        floatWindowView.setVisibility(View.GONE); // 设置不可见

        // 获取屏幕宽高
        DisplayMetrics displayMetrics = new DisplayMetrics();
        WindowManager windowManager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        windowManager.getDefaultDisplay().getMetrics(displayMetrics);
        int screenWidth = displayMetrics.widthPixels; // 屏幕宽度
        int screenHeight = displayMetrics.heightPixels; // 屏幕高度

        // 按比例计算宽高
        int targetWidth = screenWidth * 2 / 3; // 宽为屏幕宽度的三分之二
        int targetHeight = screenHeight * 3 / 5; // 高为屏幕高度的五分之三
        floatWindowView.setLayoutParams(new LinearLayout.LayoutParams(targetWidth, targetHeight));

        // 创建 Toolbar
        Toolbar toolbar = new Toolbar(context);
        toolbar.setPadding(4, 4, 4, 4);
        toolbar.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dpToPx(30)
        ));
        toolbar.setBackgroundColor(Color.parseColor("#888888"));

        // 添加昵称 TextView
        TextView nicknameView = new TextView(context);
        nicknameView.setText(DataConverter.getCurrentProcessName(context)); // 设置动态内容
        nicknameView.setTextColor(Color.WHITE);
        nicknameView.setTextSize(12);
        nicknameView.setSingleLine(true);
        nicknameView.setEllipsize(TextUtils.TruncateAt.END); // 显示省略号
        nicknameView.setMaxWidth(targetWidth * 2 / 3);
        nicknameView.setGravity(Gravity.LEFT);
        nicknameView.setPadding(16, 0, 0, 0);
        Toolbar.LayoutParams textViewLayoutParams = new Toolbar.LayoutParams(
                Toolbar.LayoutParams.WRAP_CONTENT,
                Toolbar.LayoutParams.WRAP_CONTENT
        );
        textViewLayoutParams.gravity = Gravity.START;
        toolbar.addView(nicknameView, textViewLayoutParams);

        // 添加删除按钮
        ImageView delListView = new ImageView(context);
        delListView.setImageDrawable(ImagesBase64.base64ToDrawable(context, ImagesBase64.r_drawable_delete));
        delListView.setPadding(16, 0, 16, 0);
        delListView.setOnClickListener(v -> {
            XposedBridge.log("删除 被点击");
            if (clearListener != null) {
                clearListener.onOpenViewClick(true);
            }
        });
        Toolbar.LayoutParams imageViewLayoutParams = new Toolbar.LayoutParams(
                Toolbar.LayoutParams.WRAP_CONTENT,
                Toolbar.LayoutParams.WRAP_CONTENT
        );
        imageViewLayoutParams.gravity = Gravity.END;
        toolbar.addView(delListView, imageViewLayoutParams);

        // 添加开启/关闭按钮
        ImageView openView = new ImageView(context);
        openView.setPadding(16, 0, 16, 0);

//        openView.setImageDrawable(openDrawable);
        openView.setImageDrawable(isHook ? closeDrawable : openDrawable);

        openView.setOnClickListener(v -> {
            boolean newIsHook = openView.getDrawable() == openDrawable;
            openView.setImageDrawable(newIsHook ? closeDrawable : openDrawable);

            if (openListener != null) {
                openListener.onOpenViewClick(newIsHook);
            }

            XposedBridge.log(newIsHook ? "开启 被点击" : "关闭 被点击");
        });
        toolbar.addView(openView, imageViewLayoutParams);

        // 添加 ListView
        ListView listView = new ListView(context);
        listView.setBackgroundColor(Color.parseColor("#F5F7FF"));
        listView.setDividerHeight(1);
        LinearLayout.LayoutParams listViewLayoutParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                screenHeight // 列表高度
        );
        listView.setLayoutParams(listViewLayoutParams);

        // 设置适配器
        listView.setAdapter(adapter);

        // 设置点击监听器
        listView.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) -> {
            if (position >= 0 && position < intentList.size()) {
                Intent intent = intentList.get(position);
                try {
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent); // 启动对应的Activity
                } catch (Exception e) {
                    e.printStackTrace();
                    XposedBridge.log("启动Activity失败: " + e.getMessage());
                }
            }
        });

        // 添加子布局到父布局
        floatWindowView.addView(toolbar);
        floatWindowView.addView(listView);

        return floatWindowView;
    }

    private int dpToPx(int dp) {
        return (int) (dp * context.getResources().getDisplayMetrics().density + 0.5f);
    }
}
